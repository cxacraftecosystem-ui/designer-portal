"""Copy the shared transcription service to the other portal and rewrite both manifests.

The service is ONE package kept byte-identical in designer-portal and documentation-portal
(docs/transcription/TRANSCRIPTION_SERVICE_2026-10-18.md, section 4, option B). Edit it here, in
designer-portal, then run from this repository's root:

    python scripts/sync_transcription.py --to ../documentation-portal      # copy + manifests
    python scripts/sync_transcription.py --check                            # verify this repo only

Digests are taken over LF-normalised text, so a Windows checkout (CRLF) and CI (LF) agree.
``backend/tests/test_transcription_parity.py`` (itself one of the shared files) fails when any
listed file differs from ``shared/transcription.sha256``.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("shared/transcription.sha256")
SHARED_FILES_EXTRA = (
    "backend/app/services/transcription_store.py",
    "backend/app/services/transcription_jobs.py",
    "backend/app/api/routes/transcription_admin.py",
    "frontend/components/settings/TranscriptionPanel.tsx",
    "backend/tests/test_transcription_service.py",
    "backend/tests/test_transcription_parity.py",
    # The one consent store (19 Oct 2026): its migration and its tests, beside the rule in the package.
    "backend/prisma/migrations/20261103090000_one_consent_store/migration.sql",
    "backend/tests/test_consent_ledger.py",
)


def shared_files(root: Path) -> list[str]:
    package = sorted(
        path.relative_to(root).as_posix()
        for path in (root / "backend/app/transcription").rglob("*.py")
        if "__pycache__" not in path.parts
    )
    return package + list(SHARED_FILES_EXTRA)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def manifest_text(root: Path) -> str:
    lines = [f"{digest(root / rel)}  {rel}" for rel in shared_files(root)]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--to", type=Path, help="the other portal's repository root")
    parser.add_argument(
        "--check", action="store_true", help="verify this repository's manifest only"
    )
    args = parser.parse_args()

    if args.check:
        expected = (ROOT / MANIFEST).read_text(encoding="utf-8")
        actual = manifest_text(ROOT)
        if expected.replace("\r\n", "\n") != actual:
            print(
                "shared/transcription.sha256 is out of date; run with --to to resync",
                file=sys.stderr,
            )
            return 1
        print("transcription manifest OK")
        return 0

    text = manifest_text(ROOT)
    (ROOT / MANIFEST).write_text(text, encoding="utf-8", newline="\n")
    if args.to:
        target = args.to.resolve()
        package = target / "backend/app/transcription"
        if package.exists():
            shutil.rmtree(package)
        for rel in shared_files(ROOT):
            destination = target / rel
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / rel, destination)
        (target / MANIFEST).write_text(text, encoding="utf-8", newline="\n")
        if manifest_text(target) != text:
            print("the copy does not match after syncing", file=sys.stderr)
            return 1
        print(f"synced {len(shared_files(ROOT))} files to {target}")
    else:
        print("manifest rewritten")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
