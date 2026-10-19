"""Generate the Prisma client, then keep its TypedDicts out of every process that imports it.

    cd backend
    python scripts/generate_prisma_client.py          # prisma generate, the future import, the split
    python scripts/generate_prisma_client.py --check  # exit 1 unless the installed client has both

EVERY PLACE THAT GENERATES THE CLIENT FOR REAL RUNS THIS, NOT ``python -m prisma generate``: both
backend jobs in .github/workflows/checks.yml, e2e-live.yml, the runner-side generator in
deploy-backend.yml (whose output is what the EC2 box runs) and backend/Dockerfile's ``prisma`` stage.
Run it locally too; a plain ``prisma generate`` gives you a client that takes many minutes to import.

WHY IT EXISTS (measured 2026-10-09). prisma-client-py 0.15.0 writes ``prisma/types.py`` without
``from __future__ import annotations``: 675,391 lines declaring 80,290 class-syntax TypedDicts for this
schema (``recursive_type_depth = 5``). Under Python 3.14's PEP 649 every one of those classes defers
its annotations, and ``TypedDict`` evaluates them at class creation through
``annotationlib.call_annotate_function(..., Format.FORWARDREF)``, which builds a FRESH COPY OF THE
MODULE'S GLOBALS for every class it is asked about. In a module that is still defining tens of
thousands of classes that is quadratic, in time and in memory:

    ``python -m prisma migrate deploy`` before its first line of output, on a GitHub runner:
        Python 3.12.14   8 s            Python 3.14.8   209 s
    ``from prisma import Prisma`` in a Linux container on the development machine, one pinned lock:
        3.14.8 as generated         cold 1,363 s, >= 1.7 GiB   warm  > 17 min, ~1.1 GiB (stopped)
        3.14.8 with this line       cold   23.5 s, 1,394 MiB   warm   8.7 s,   727 MiB
        3.12.15 as generated        cold   13.1 s, 1,283 MiB   warm   6.4 s,   557 MiB
    ("cold" compiles the 30 MB of source to bytecode; "warm" reads it back, which is what every
    restart of the two units on the box does.)

With the future import the same annotations are stored as strings, ``TypedDict`` takes its
``__annotations__`` path and never calls the annotate machinery, and the import is seconds again.
It is not free: on 3.14 each importing process still holds about 170 MiB more than it did on 3.12,
which the box's memory ceilings in deploy-backend.yml now account for in a comment.

Nothing at run time reads these annotations: the TypedDicts exist for type checkers, prisma's own
``actions.py`` and ``client.py`` already use the future import, and the app never introspects them.

AND THEN THE TYPEDDICTS THEMSELVES LEAVE THE RUNTIME (2026-10-19). The schema has grown to 178,641
of them, 62 MB of ``types.py``, and holding them was most of the memory of both units on the box:
the API at 956 MB RSS plus 623 MB in swap and the queue at 802 MB plus 731 MB, idle. Measured in a
``python:3.14.8`` container against the same lock and schema:

    ``import prisma``                   full types.py  1,434 MiB      split  128 MiB
    API (uvicorn app.main) after start  1,527 MiB RSS, 87 s to /health    248 MiB, 14 s
    queue (python -m app.worker)        1,453 MiB RSS                     202 MiB

So after the future import the whole generated file is moved, unchanged, to ``types.pyi`` beside
it, and ``types.py`` becomes a shim: the template's own preamble (the imports and the handful of
real aliases such as ``SortOrder`` and ``HttpConfig`` that ``client.py`` imports by name), then a
module ``__getattr__`` that answers ``dict`` for every other name. Type checkers and editors read
``types.pyi`` in preference to ``types.py``, so every declaration is still checked. At run time the
generated modules use these names only inside string annotations and in ``cast(types.X, value)``,
which returns ``value``; a Prisma query is built from plain dicts. ``recursive_type_depth`` in
``schema.prisma`` was the other lever and is not enough: at -1 (its smallest output) the import is
still 430 MiB, and it weakens the types editors see.

THIS IS A PATCH TO GENERATED CODE AND IT IS KEPT HONEST BY REFUSING TO GUESS. It inserts one line
before the first statement, only when every line above that statement is a comment or blank and the
statement is an import — the shape 0.15.0 writes. The split needs the template's
``# -- template types.py.jinja --`` line and a preamble that defines every name ``client.py`` imports
from it. Any other shape stops with an error naming the file rather than editing it. Both steps are
idempotent: a client that already has them is left alone. The way
out is the one docs/OPEN_FINDINGS.md gives for prisma-client-py as a whole (the project is archived):
the data layer moving to a maintained async stack, after which this file is deleted.
"""

from __future__ import annotations

import argparse
import importlib.util
import subprocess
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SCHEMA = BACKEND_ROOT / "prisma" / "schema.prisma"
FUTURE_IMPORT = "from __future__ import annotations\n"


def installed_types_py() -> Path:
    """Where the generated ``prisma/types.py`` lives for THIS interpreter, without importing prisma.

    ``find_spec`` on a top-level package locates it without executing it — importing it to ask
    would pay the very import this script exists to make cheap.
    """
    spec = importlib.util.find_spec("prisma")
    if spec is None or not spec.submodule_search_locations:
        raise SystemExit("prisma is not installed for this interpreter; install requirements.lock first")
    return Path(next(iter(spec.submodule_search_locations))) / "types.py"


def _first_statement(lines: list[str]) -> int:
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            return index
    raise SystemExit("generated types.py has no statements at all; refusing to edit it")


def has_future_import(path: Path) -> bool:
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            # The future import has to be the first statement to mean anything, so the first
            # statement is the only line that can answer.
            return stripped == FUTURE_IMPORT.strip()
    return False


def add_future_import(path: Path) -> bool:
    """Insert the future import; True when the file changed, False when it already had it."""
    if has_future_import(path):
        return False
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    first = _first_statement(lines)
    statement = lines[first].lstrip()
    if not statement.startswith(("from ", "import ")):
        raise SystemExit(
            f"{path}: the first statement is not an import ({statement[:60]!r}), which is not the "
            "shape prisma-client-py 0.15.0 generates. Refusing to patch a file this script does not "
            "recognise; read the docstring of scripts/generate_prisma_client.py."
        )
    lines.insert(first, FUTURE_IMPORT)
    path.write_text("".join(lines), encoding="utf-8")
    return True


#: The first line of the runtime ``types.py`` this script writes; how it recognises its own output.
SHIM_MARKER = "# prisma.types runtime shim, written by backend/scripts/generate_prisma_client.py\n"
#: Where the generated template's own runtime preamble starts. Everything from here to the first
#: declaration is a handful of real aliases (``SortOrder``, ``HttpConfig``, ...) that ``client.py``
#: imports by name, so the shim keeps them verbatim.
TEMPLATE_MARKER = "# -- template types.py.jinja --"
#: Names the generated ``client.py`` imports from ``.types`` at run time. The shim must define them.
PREAMBLE_NAMES = ("SortMode", "SortOrder", "MetricsFormat", "DatasourceOverride", "HttpConfig")

SHIM_TAIL = """
# ---------------------------------------------------------------------------------------------
# EVERY DECLARATION THAT USED TO FOLLOW IS IN types.pyi, BESIDE THIS FILE, FOR TYPE CHECKERS.
#
# prisma-client-py generates ~180,000 TypedDicts here for this schema, 62 MB of source that costs
# every importing process about 1.3 GB of RAM on Python 3.14. Nothing at run time needs them: a
# Prisma query is built from plain dicts, and the generated modules name them only in annotations
# (strings, under the future import) and in ``cast(types.X, value)``, which returns ``value``. Type
# checkers and editors read ``types.pyi`` in preference to this file, so they still see every
# declaration. At run time any other name resolves to ``dict``, which is what a TypedDict is once
# the type checker has gone home. Regenerate with backend/scripts/generate_prisma_client.py.
# ---------------------------------------------------------------------------------------------


def __getattr__(name: str) -> Any:
    if name.startswith("__") and name.endswith("__"):
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    return dict
"""


def is_runtime_shim(path: Path) -> bool:
    with path.open(encoding="utf-8") as handle:
        return handle.readline() == SHIM_MARKER


def _preamble_end(lines: list[str], start: int) -> int:
    """Index of the first line after the template's runtime preamble (imports, ``X = _types.X``)."""
    index = start
    while index < len(lines):
        stripped = lines[index].strip()
        name, _, value = stripped.partition(" = ")
        if (
            not stripped
            or stripped.startswith(("#", "from ", "import "))
            or (name.isidentifier() and value == f"_types.{name}")
        ):
            index += 1
            continue
        return index
    raise SystemExit("generated types.py ends inside its preamble; refusing to split it")


def split_types(types_py: Path) -> bool:
    """Move the declarations to ``types.pyi`` and leave a small runtime ``types.py``.

    True when the files changed; False when ``types.py`` is already the shim.
    """
    if is_runtime_shim(types_py):
        return False
    if not has_future_import(types_py):
        raise SystemExit(f"{types_py}: add the future import before splitting")
    text = types_py.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    try:
        marker = next(i for i, line in enumerate(lines) if line.strip() == TEMPLATE_MARKER)
    except StopIteration:
        raise SystemExit(
            f"{types_py}: no {TEMPLATE_MARKER!r} line, which is not the shape prisma-client-py "
            "0.15.0 generates. Refusing to split a file this script does not recognise."
        ) from None
    end = _preamble_end(lines, marker + 1)
    preamble = "".join(lines[:end])
    missing = [name for name in PREAMBLE_NAMES if f"\n{name} = _types.{name}\n" not in preamble]
    if missing:
        raise SystemExit(
            f"{types_py}: the preamble does not define {', '.join(missing)}, which client.py "
            "imports at run time. Refusing to split a file this script does not recognise."
        )
    types_py.with_suffix(".pyi").write_text(text, encoding="utf-8")
    types_py.write_text(SHIM_MARKER + preamble.rstrip("\n") + "\n\n" + SHIM_TAIL, encoding="utf-8")
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA)
    parser.add_argument(
        "--check", action="store_true", help="only report whether the installed client is patched"
    )
    args = parser.parse_args(argv)

    if not args.check:
        subprocess.run(
            [sys.executable, "-m", "prisma", "generate", "--schema", str(args.schema)], check=True
        )

    types_py = installed_types_py()
    types_pyi = types_py.with_suffix(".pyi")
    if not types_py.exists():
        print(f"{types_py} does not exist: the client has not been generated", file=sys.stderr)
        return 1
    if args.check:
        shim = is_runtime_shim(types_py)
        stub = types_pyi.exists() and has_future_import(types_pyi)
        print(f"{types_py}: {'is' if shim else 'is NOT'} the runtime shim")
        print(f"{types_pyi}: {'has' if stub else 'LACKS'} the declarations with the future import")
        return 0 if shim and stub else 1

    if is_runtime_shim(types_py):
        print(f"{types_py}: already the runtime shim")
        return 0
    changed = add_future_import(types_py)
    print(f"{types_py}: {'added' if changed else 'already has'} `{FUTURE_IMPORT.strip()}`")
    split_types(types_py)
    print(f"{types_py}: declarations moved to {types_pyi.name}; runtime shim written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
