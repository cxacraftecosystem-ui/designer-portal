from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from questionnaire_android import PKG, _base, digest, portal_of  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("shared/android-explorer.sha256")
TREES = (("main", "explorer"), ("test", "explorer"), ("androidTest", "explorer"))
PORTAL_DIR = "explorer/portal/"


def shared_files(root: Path) -> list[tuple[str, Path]]:
    out: list[tuple[str, Path]] = []
    for source_set, tree in TREES:
        base = _base(root, source_set)
        folder = base / tree
        if not folder.is_dir():
            continue
        for path in sorted(folder.rglob("*.kt")):
            rel = path.relative_to(base).as_posix()
            if rel.startswith(PORTAL_DIR):
                continue
            out.append((f"{source_set}/{rel}", path))
    return out


def manifest_text(root: Path) -> str:
    return "".join(f"{digest(path)}  {rel}\n" for rel, path in shared_files(root))


def check(root: Path) -> list[str]:
    expected = (root / MANIFEST).read_text(encoding="utf-8").replace("\r\n", "\n") if (root / MANIFEST).is_file() else ""
    if expected != manifest_text(root):
        return [f"{MANIFEST} is out of date; run scripts/explorer_android.py"]
    return []


def sync(root: Path, target: Path | None) -> int:
    text = manifest_text(root)
    (root / MANIFEST).write_text(text, encoding="utf-8", newline="\n")
    if target is None:
        return 0
    source_portal, target_portal = portal_of(root), portal_of(target)
    wanted = set()
    for rel, path in shared_files(root):
        source_set, tree_rel = rel.split("/", 1)
        destination = _base(target, source_set) / tree_rel
        wanted.add(destination.resolve())
        raw = path.read_bytes().decode("utf-8")
        crlf = "\r\n" in raw
        body = raw.replace("\r\n", "\n").replace(PKG[source_portal], PKG[target_portal])
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((body.replace("\n", "\r\n") if crlf else body).encode("utf-8"))
    for rel, path in shared_files(target):
        if path.resolve() not in wanted:
            path.unlink()
    (target / MANIFEST).write_text(text, encoding="utf-8", newline="\n")
    return len(wanted)


def main() -> int:
    parser = argparse.ArgumentParser(description="The data explorer's shared Android trees: digest and copy.")
    parser.add_argument("--to", type=Path, help="the other portal's repository root")
    parser.add_argument("--check", action="store_true", help="verify this repository only")
    args = parser.parse_args()
    if args.check:
        problems = check(ROOT)
        print("\n".join(problems) if problems else "explorer Android manifest OK", file=sys.stderr if problems else sys.stdout)
        return 1 if problems else 0
    copied = sync(ROOT, args.to.resolve() if args.to else None)
    if args.to:
        print(f"synced {copied} Android explorer files to {args.to.resolve()}")
        problems = check(args.to.resolve())
        if problems:
            print("\n".join(problems), file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
