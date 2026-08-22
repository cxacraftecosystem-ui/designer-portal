"""Only a ``Location`` a record still points at may teach a district anchor.

THE DEFECT THESE PIN. ``records.attach_location`` is INSERT-ONLY — on the UPDATE path as much as
the create path — and no code anywhere in the backend updates or deletes a ``Location``. The rows a
CREATE mints are all referenced (``attach_location`` ends by writing ``locationId`` onto the record
being saved, and the media route does the same for each photograph); the orphans come from RE-SAVES,
where ``attach_location`` runs again, inserts a second row and repoints ``locationId`` at it,
abandoning the first. To a predicate that asks
only "does this row carry a subject pin", an abandoned row is indistinguishable from a live one, so
a pin a researcher CORRECTED went on voting: it kept pulling its district's mean back toward the
place that had just been rejected, on ``/map`` and in the map printed into the .docx a ministry
officer reads. An anchor is a MEAN, which is what makes it quiet — the district keeps a pin, it is
simply in the wrong place by an amount nobody can see.

Both tests are STRUCTURAL and neither needs a database, because the failure they guard against is
not a wrong number in one request: it is a reader stopping being narrowed, or a seventh model
starting to point at ``Location`` and never being added to the disjunction. Either one is silent.
"""

from __future__ import annotations

import ast
import pathlib
import re

BACKEND = pathlib.Path(__file__).resolve().parents[1]


def test_the_disjunction_names_every_model_that_points_at_a_location():
    """The branches are read out of ``schema.prisma``, not restated from memory.

    A MISSING BRANCH IS WORSE THAN THE BUG THIS PREDICATE FIXES. Leave one model out and every
    location that only that record type references stops voting — a whole record type's pins
    silently absent from the anchors, which no request errors on and no page shows. So the source of
    truth is the schema's own back-relation list on ``model Location``, and this fails the moment a
    seventh model gains a ``locationId``.
    """
    from app.services.geography import REFERENCED_BY_A_RECORD

    schema = (BACKEND / "prisma" / "schema.prisma").read_text(encoding="utf-8")
    body = re.search(r"\nmodel Location \{(.*?)\n\}", schema, re.DOTALL)
    assert body, "model Location is gone from schema.prisma; this test is stale, not the code"

    # A back-relation is a list-typed field whose ELEMENT TYPE IS A DECLARED MODEL — `artisans
    # Artisan[]`. The model check is not decoration: `tags String[]` is the same shape, and a
    # scalar list added to this model would otherwise be demanded as a relation branch Prisma
    # cannot express, failing this test over a field that can never reference anything.
    models = set(re.findall(r"^model\s+(\w+)\s*\{", schema, re.MULTILINE))
    declared = {
        match.group(1)
        for match in re.finditer(r"^\s{2}(\w+)\s+(\w+)\[\]\s*$", body.group(1), re.MULTILINE)
        if match.group(2) in models
    }
    assert declared, "no back-relations parsed out of model Location; the parse, not the schema"

    branches = {next(iter(branch)) for branch in REFERENCED_BY_A_RECORD["OR"]}
    assert branches == declared, (
        "geography.REFERENCED_BY_A_RECORD must name every model that can reference a Location.\n"
        f"  in the schema but not in the predicate: {sorted(declared - branches)}\n"
        f"  in the predicate but not in the schema: {sorted(branches - declared)}\n"
        "A name only in the schema means that record type's pins have stopped teaching anchors; a "
        "name only in the predicate is a relation Prisma will reject at query time."
    )


#: EVERY ``db.location.find_many`` IN ``app/`` THAT IS NEITHER NARROWED NOR ADDRESSED BY ID, keyed by
#: (module, enclosing function) and carrying the reason it is allowed to read unreferenced rows.
#:
#: An allowlist rather than a text heuristic, because the heuristic it replaces could not see the
#: ordinary way this query gets written. The first version of this sweep flagged a call only when the
#: literal ``subjectLatitude`` appeared inside the call node — and ``_capture_narrowing`` already
#: writes ``db.location.find_many(where=window, ...)`` with the predicate built into a variable two
#: lines above. A third anchor read written in that perfectly normal style would have been INVISIBLE,
#: and this test would have passed while the drift it exists to prevent had happened.
#:
#: The set is checked for staleness too: an entry naming a call that no longer exists fails, so this
#: cannot quietly accumulate permission for code somebody deleted.
MAY_READ_UNREFERENCED_LOCATIONS = {
    ("app/api/routes/map_points.py", "_capture_narrowing"): (
        "Not an anchor read. It resolves ONE map cell to the record ids inside it, and its result "
        "is only ever used as `locationId in (...)` against the record tables — so an abandoned row "
        "in the window matches no record and contributes nothing. Narrowing it would cost a join on "
        "the hot path of every map click to remove rows that are already inert."
    ),
}


def _by_id_only(where_source: str) -> bool:
    """Is this ``where`` addressed by primary key and nothing else?

    A read that names the rows it wants cannot learn an anchor from a set it did not choose, so it
    needs no predicate. Decided from the string keys the expression actually contains rather than
    from the function it sits in, because ``map_points`` issues BOTH this and the anchor read.

    A read with NO ``where`` at all, or one this cannot parse, is emphatically not by-id: the first
    is the whole table and the second is something nobody should be waved through unexamined. Both
    answer False and land in the offenders list, where a human decides.
    """
    try:
        expression = ast.parse(where_source, mode="eval")
    except SyntaxError:
        return False
    keys = {
        node.value
        for node in ast.walk(expression)
        if isinstance(node, ast.Constant) and isinstance(node.value, str)
    }
    return bool(keys) and keys <= {"id", "in"}


def test_every_anchor_read_asks_for_referenced_rows_only():
    """The sweep, because two call sites will not stay two.

    ``geography.MAX_ANCHOR_ROWS`` already exists because ``/map`` and the design-workshop report
    learn from the same table and a cap that differed between them would place one district in two
    positions in two products of the same data. The referenced-only predicate is the identical
    argument, and a third reader written without it would reintroduce exactly the drift the second
    one did.

    THE RULE IS INVERTED FROM WHAT IT WAS: every ``db.location.find_many`` must AND in
    ``REFERENCED_BY_A_RECORD``, and anything that must not is named in
    :data:`MAY_READ_UNREFERENCED_LOCATIONS` with its reason. A new reader therefore fails this test
    by default and somebody has to decide which it is, which is the only version of this check that
    survives being written in a style its author did not anticipate.

    A ``where`` passed as a local variable IS RESOLVED — that is how the one existing exemption is
    written, and assuming nobody else would write it that way is what made the previous sweep blind.
    """
    offenders = []
    seen: set[tuple[str, int]] = set()
    used: set[tuple[str, str]] = set()
    for path in sorted((BACKEND / "app").rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        rel = path.relative_to(BACKEND).as_posix()
        for scope, roots in _scopes(tree):
            nodes = [node for root in roots for node in ast.walk(root)]
            # Simple `name = <expr>` assignments of the enclosing scope, so `where=window` can be
            # judged on what `window` actually holds.
            locals_: dict[str, str] = {}
            for node in nodes:
                if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(
                    node.targets[0], ast.Name
                ):
                    locals_[node.targets[0].id] = ast.unparse(node.value)
            for node in nodes:
                if not isinstance(node, ast.Call):
                    continue
                if ast.unparse(node.func) not in ("db.location.find_many", "tx.location.find_many"):
                    continue
                # A nested function is reached both on its own and through its parent's walk.
                if (rel, node.lineno) in seen:
                    continue
                seen.add((rel, node.lineno))
                where = next(
                    (kw.value for kw in node.keywords if kw.arg == "where"), None
                )
                where_source = ast.unparse(where) if where is not None else ""
                if isinstance(where, ast.Name):
                    where_source = locals_.get(where.id, where_source)
                if "REFERENCED_BY_A_RECORD" in where_source:
                    continue
                if _by_id_only(where_source):
                    continue
                if (rel, scope) in MAY_READ_UNREFERENCED_LOCATIONS:
                    used.add((rel, scope))
                    continue
                offenders.append(f"{rel}:{node.lineno} in {scope}()")

    assert not offenders, (
        "these read every Location in their window, including the rows `attach_location` abandoned "
        "when a record's pin was re-saved — so a corrected pin keeps voting for the place it was "
        "corrected away from. AND `geography.REFERENCED_BY_A_RECORD` into the where, or add the "
        "call to MAY_READ_UNREFERENCED_LOCATIONS with the reason it does not need it:\n"
        + "\n".join(offenders)
    )
    stale = set(MAY_READ_UNREFERENCED_LOCATIONS) - used
    assert not stale, (
        "MAY_READ_UNREFERENCED_LOCATIONS names reads that no longer exist, so it is granting "
        f"permission to nothing and hiding the next one that moves into the same name: {sorted(stale)}"
    )


def _scopes(tree: ast.Module):
    """``(name, roots)`` for every function in a module, nested ones included, then the module body.

    The module comes LAST and the caller dedupes by line, so a call inside a function is attributed
    to that function and only a read issued at IMPORT time falls through to ``<module>``. There are
    none today; a read at import time would be a strange thing to write, which is exactly why it
    should not be the one shape this sweep cannot see.
    """
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            yield node.name, [node]
    yield "<module>", tree.body
