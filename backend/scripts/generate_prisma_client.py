"""Generate the Prisma client, then make its 80,000 TypedDicts cheap to import on Python 3.14.

    cd backend
    python scripts/generate_prisma_client.py          # prisma generate, then the one-line fix
    python scripts/generate_prisma_client.py --check  # exit 1 unless the installed client has it

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

THIS IS A PATCH TO GENERATED CODE AND IT IS KEPT HONEST BY REFUSING TO GUESS. It inserts one line
before the first statement, only when every line above that statement is a comment or blank and the
statement is an import — the shape 0.15.0 writes. Any other shape stops with an error naming the file
rather than editing it. It is idempotent: a client that already has the import is left alone. The way
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
    if not types_py.exists():
        print(f"{types_py} does not exist: the client has not been generated", file=sys.stderr)
        return 1
    if args.check:
        patched = has_future_import(types_py)
        print(f"{types_py}: {'has' if patched else 'LACKS'} `{FUTURE_IMPORT.strip()}`")
        return 0 if patched else 1

    changed = add_future_import(types_py)
    print(f"{types_py}: {'added' if changed else 'already has'} `{FUTURE_IMPORT.strip()}`")
    return 0


if __name__ == "__main__":
    sys.exit(main())
