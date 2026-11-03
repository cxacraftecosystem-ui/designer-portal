import argparse
import asyncio
from typing import Any

from app.core.db import connect_db, db, disconnect_db
from app.services import custom_sections, design_workshop_data as dwd

WORKSHOP_PAGE = 50


async def _definition_for(workshop_id: str, entries: list[Any]) -> Any:
    if not any(row.entityKey == custom_sections.CUSTOM_ENTITY_KEY for row in entries):
        return None
    return await custom_sections.load_definition(workshop_id)


def _computed(row: Any, definition: Any) -> str | None:
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
                        rows_no_definition += 1
                        continue
                    if (
                        row.entityKey != custom_sections.CUSTOM_ENTITY_KEY
                        and dwd.entity_by_key(row.entityKey) is None
                    ):
                        rows_unknown_entity += 1
                        continue
                    value = _computed(row, definition)
                    if value == row.searchText:
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
