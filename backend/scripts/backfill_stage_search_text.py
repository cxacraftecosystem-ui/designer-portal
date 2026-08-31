"""Fill ``DwStageEntry.searchText`` for rows written before the column existed.

WHY THIS IS A SCRIPT AND NOT PART OF THE MIGRATION. Two reasons, and either alone would decide it:
a migration that rewrites every stage row of every workshop is a migration that times out on a
managed instance, and the value is produced by the Python renderer
(``services/design_workshop_data.entry_search_text``) — no .sql file can resolve an ENUM token to
its label or flatten a rich-text document. The column arrives NULL, which can never match, so a
deployment that has taken the migration and not yet run this searches exactly the rows it searched
before the migration rather than the wrong ones.

IDEMPOTENT. A row whose stored value already equals the computed one is not written, so a second run
over an unchanged tree touches nothing and says so. That is what makes the "rows touched" figure
below worth printing: it is a measurement, not a row count.

RESUMABLE, AND IN TWO INDEPENDENT WAYS, because a long run on a cross-region link gets interrupted.
Workshops are walked in id order and ``--after <workshopId>`` restarts from a point the previous run
printed; and, separately, the default run only computes rows whose column is NULL, so simply running
it again finishes what an interrupted run left. ``--recompute`` turns the second one off — that is
the flag for the day :func:`entry_search_text` itself changes, when every row's value is stale and
"already has a value" stops meaning "already correct".

DRY RUN BY DEFAULT, like every other script in this directory. Nothing is written without
``--execute``.

Usage, from ``backend/``::

    python -m scripts.backfill_stage_search_text                  # DRY RUN, NULL rows only
    python -m scripts.backfill_stage_search_text --execute
    python -m scripts.backfill_stage_search_text --execute --recompute
    python -m scripts.backfill_stage_search_text --execute --after dw_abc123
"""

import argparse
import asyncio
from typing import Any

from app.core.db import connect_db, db, disconnect_db
from app.services import custom_sections, design_workshop_data as dwd

#: Workshops per page. The unit of work is a WORKSHOP and not a row, because the designer's own
#: questions are defined per workshop: rendering a ``_custom`` row needs that workshop's field
#: definitions, and paging by row would reload them for every row of every stage. Fifty workshops is
#: a few thousand rows in one wave on a real corpus.
WORKSHOP_PAGE = 50


async def _definition_for(workshop_id: str, entries: list[Any]) -> Any:
    """The workshop's own field definitions, loaded ONLY when it has custom answers to render.

    Most workshops have no custom section at all, and a definition load is a database round trip on a
    cross-region link. Skipping it where there is nothing to apply it to is the difference between
    one read per workshop and one read per workshop that needs one.
    """
    if not any(row.entityKey == custom_sections.CUSTOM_ENTITY_KEY for row in entries):
        return None
    return await custom_sections.load_definition(workshop_id)


def _computed(row: Any, definition: Any) -> str | None:
    """What this row's ``searchText`` should be, or None when it has nothing searchable in it.

    NONE AND NOT "", matching the writers. An empty string would be a value the column distinguishes
    from NULL, which would break the ``searchText IS NULL`` resume point above for every row that
    holds nothing but numbers — a cost line, a measurement — i.e. for a large minority of the table.
    """
    entity_key = str(getattr(row, "entityKey", "") or "")
    data = getattr(row, "data", None)
    if entity_key == custom_sections.CUSTOM_ENTITY_KEY:
        specs = definition.fields_for(str(getattr(row, "stageKey", "") or ""))
        return dwd.custom_search_text(specs, data) or None
    found = dwd.entity_by_key(entity_key)
    if found is None:
        return None
    _stage, entity = found
    return dwd.entry_search_text(entity, data) or None


async def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill DwStageEntry.searchText.")
    parser.add_argument("--execute", action="store_true", help="apply the writes (default: dry run)")
    parser.add_argument(
        "--recompute",
        action="store_true",
        help="also recompute rows that already hold a value (use when the renderer changes)",
    )
    parser.add_argument("--after", default="", help="resume: skip workshops with an id <= this one")
    args = parser.parse_args()

    print("MODE:", "EXECUTE" if args.execute else "DRY RUN (no changes)")
    print("SCOPE:", "every row" if args.recompute else "rows whose searchText is NULL")
    await connect_db()
    try:
        cursor = args.after
        workshops = 0
        rows_seen = 0
        rows_touched = 0
        rows_unknown_entity = 0
        rows_no_definition = 0
        last_id = cursor
        while True:
            page = await db.designworkshop.find_many(
                where={"id": {"gt": cursor}} if cursor else {},
                order={"id": "asc"},
                take=WORKSHOP_PAGE,
            )
            if not page:
                break
            for workshop in page:
                cursor = workshop.id
                last_id = workshop.id
                workshops += 1
                # SOFT-DELETED WORKSHOPS AND SOFT-DELETED ROWS ARE INCLUDED. Neither is reachable
                # from search — the bucket filters both — so this is not about recall. It is about
                # the column never being stale ANYWHERE: a soft delete in this product is reversible
                # (a resurrected stage row is an ordinary update), and a backfill that skipped them
                # would leave a set of rows whose column disagreed with their data, which is the one
                # failure mode this design exists to make impossible.
                entries = await db.dwstageentry.find_many(
                    where={"designWorkshopId": workshop.id}
                    if args.recompute
                    else {"designWorkshopId": workshop.id, "searchText": None},
                )
                if not entries:
                    continue
                definition = await _definition_for(workshop.id, entries)
                for row in entries:
                    rows_seen += 1
                    if row.entityKey == custom_sections.CUSTOM_ENTITY_KEY and definition is None:
                        # Unreachable while `_definition_for` loads on exactly this condition, and
                        # counted rather than asserted so that a load which answers None (a
                        # definition this server cannot read) is reported instead of crashing a
                        # backfill somebody is watching.
                        rows_no_definition += 1
                        continue
                    if (
                        row.entityKey != custom_sections.CUSTOM_ENTITY_KEY
                        and dwd.entity_by_key(row.entityKey) is None
                    ):
                        # A ROW THIS BUILD'S REGISTRY CANNOT DESCRIBE IS LEFT ALONE, not blanked.
                        # It was written against a newer registry than this server runs, which
                        # `design_workshop_data` treats as a real state everywhere else it appears.
                        # Blanking would destroy a value a newer server had correctly computed.
                        rows_unknown_entity += 1
                        continue
                    value = _computed(row, definition)
                    if value == row.searchText:
                        # THE IDEMPOTENCE, MEASURED AT THE ROW. A re-run over an unchanged tree
                        # reaches here for every row and writes nothing, which is what makes
                        # `--recompute` safe to run on a whim rather than in a maintenance window.
                        continue
                    rows_touched += 1
                    if args.execute:
                        await db.dwstageentry.update(
                            where={"id": row.id}, data={"searchText": value}
                        )
            print(
                f"  ... {workshops} workshops, {rows_seen} rows examined, "
                f"{rows_touched} to write (last id {last_id})"
            )

        print(f"workshops={workshops}")
        print(f"rows_examined={rows_seen}")
        print(f"rows_{'written' if args.execute else 'that_would_be_written'}={rows_touched}")
        # NAMED RATHER THAN FOLDED INTO A TOTAL, both of them, because the two skips mean different
        # things and only one of them is ever worth acting on. A row against an unknown entity key
        # says a client is ahead of this server; a `_custom` row with no loadable definition says a
        # workshop's own questions could not be read, which is a repair job.
        print(f"rows_skipped_unknown_entity={rows_unknown_entity}")
        print(f"rows_skipped_no_custom_definition={rows_no_definition}")
        if last_id:
            print(f"resume_after={last_id}")
        if not args.execute and rows_touched:
            print("Re-run with --execute to apply.")
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
