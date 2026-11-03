from __future__ import annotations

import ast
import pathlib
import re

BACKEND = pathlib.Path(__file__).resolve().parents[1]


def test_the_disjunction_names_every_model_that_points_at_a_location():
    from app.services.geography import REFERENCED_BY_A_RECORD

    schema = (BACKEND / "prisma" / "schema.prisma").read_text(encoding="utf-8")
    body = re.search(r"\nmodel Location \{(.*?)\n\}", schema, re.DOTALL)
    assert body, "model Location is gone from schema.prisma; this test is stale, not the code"

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


MAY_READ_UNREFERENCED_LOCATIONS = {
    ("app/api/routes/map_points.py", "_capture_narrowing"): (
        "Not an anchor read. It resolves ONE map cell to the record ids inside it, and its result "
        "is only ever used as `locationId in (...)` against the record tables — so an abandoned row "
        "in the window matches no record and contributes nothing. Narrowing it would cost a join on "
        "the hot path of every map click to remove rows that are already inert."
    ),
}


def _by_id_only(where_source: str) -> bool:
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
    offenders = []
    seen: set[tuple[str, int]] = set()
    used: set[tuple[str, str]] = set()
    for path in sorted((BACKEND / "app").rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        rel = path.relative_to(BACKEND).as_posix()
        for scope, roots in _scopes(tree):
            nodes = [node for root in roots for node in ast.walk(root)]
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
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            yield node.name, [node]
    yield "<module>", tree.body
