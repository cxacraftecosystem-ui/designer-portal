"""Copy the shared questionnaire package to the other portal and rewrite both manifests.

The questionnaire is ONE package kept byte-identical in designer-portal and documentation-portal
(C:/dev/IIT/docs/questionnaire/UNIFY_2026-10-21.md, U1). Edit it here, in designer-portal, then run
from this repository's root:

    python scripts/sync_questionnaire.py --to ../documentation-portal      # copy + manifests
    python scripts/sync_questionnaire.py --check                            # verify this repo only

Digests are taken over LF-normalised text, so a Windows checkout (CRLF) and CI (LF) agree.
``backend/tests/test_questionnaire_parity.py`` (itself one of the shared files) fails when any
listed file differs from ``shared/questionnaire.sha256``, and when the two portals' manifests differ.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("shared/questionnaire.sha256")
PACKAGE = "backend/app/questionnaire"
#: Each portal's own capture form; it differs between the portals on purpose and is not shared.
NOT_SHARED = {"shared/questionnaire-form-contract.json", "shared/questionnaire.sha256"}
SHARED_FILES_EXTRA = (
    # The generated vocabulary the package and its schemas read, and its sources.
    "backend/app/shared_vocab.py",
    "shared/enums.json",
    "shared/languages.json",
    "shared/strings/questionnaire.json",
    # The tests that hold the two portals together.
    "backend/tests/test_questionnaire_parity.py",
    "backend/tests/test_questionnaire_schema_parity.py",
    "backend/tests/test_questionnaire_feature_parity.py",
    "backend/tests/test_questionnaire_core.py",
    # The safety rails every questionnaire data script shares (dry run, host allow-list, approval).
    "backend/scripts/qv2_guard.py",
)


def shared_files(root: Path) -> list[str]:
    package = sorted(
        path.relative_to(root).as_posix()
        for path in (root / PACKAGE).rglob("*.py")
        if "__pycache__" not in path.parts
    )
    contracts = sorted(
        path.relative_to(root).as_posix() for path in (root / "shared").glob("questionnaire*")
        if path.relative_to(root).as_posix() not in NOT_SHARED
    )
    return package + contracts + list(SHARED_FILES_EXTRA)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def manifest_text(root: Path) -> str:
    return "".join(f"{digest(root / rel)}  {rel}\n" for rel in shared_files(root))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--to", type=Path, help="the other portal's repository root")
    parser.add_argument("--check", action="store_true", help="verify this repository's manifest only")
    args = parser.parse_args()

    if args.check:
        expected = (ROOT / MANIFEST).read_text(encoding="utf-8").replace("\r\n", "\n")
        if expected != manifest_text(ROOT):
            print("shared/questionnaire.sha256 is out of date; run with --to to resync", file=sys.stderr)
            return 1
        print("questionnaire manifest OK")
        return 0

    text = manifest_text(ROOT)
    (ROOT / MANIFEST).write_text(text, encoding="utf-8", newline="\n")
    if args.to:
        target = args.to.resolve()
        package = target / PACKAGE
        if package.exists():
            shutil.rmtree(package)
        for rel in shared_files(ROOT):
            destination = target / rel
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / rel, destination)
        (target / MANIFEST).write_text(text, encoding="utf-8", newline="\n")
        if manifest_text(target) != text:
            print("the copy does not match after syncing (a stray shared/questionnaire* file there?)", file=sys.stderr)
            return 1
        print(f"synced {len(shared_files(ROOT))} files to {target}")
    else:
        print("manifest rewritten")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
