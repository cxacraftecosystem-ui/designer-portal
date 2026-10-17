"""Move every stored two-step secret to the current ``TWO_FACTOR_ENCRYPTION_KEY``. Dry run by default.

WHY THIS EXISTS. Since 2026-10-17 a TOTP secret is sealed under its own key, named by an id in the
stored value (``v2:<kid>.…``), instead of a key derived from ``JWT_SECRET`` (``v1.…``). A value is moved
to the current key the next time its owner types a right code, but somebody who signs in once a
quarter would keep the old key alive for a quarter. This script moves the rest in one pass, so the
legacy key (and any key in ``TWO_FACTOR_PREVIOUS_KEYS``) can be retired on the operator's schedule
rather than on the least active user's. Runbook: ``docs/SECURITY.md``, "Rotating the two-step
encryption key".

WHAT IT TOUCHES. ``User.twoFactorSecret`` and ``User.twoFactorPendingSecret``, nothing else. Each
value is opened with whichever configured key it names and sealed again under the current one.

IDEMPOTENT. A value already under the current key is skipped, and every write is conditional on the
column still holding exactly what was read — a setup, a switch-off or a sign-in's own re-encryption
between the read and the write wins, and the row is counted as skipped. Running it twice changes
nothing the second time.

IT PRINTS COUNTS ONLY: never an account id, an address, a secret or a ciphertext. A value no
configured key can open is counted as unreadable and left alone; that account needs a recovery code
or an administrator's reset, which is what it already needed.

Usage, from ``backend/``::

    python -m scripts.reencrypt_two_factor_secrets            # DRY RUN, writes nothing
    python -m scripts.reencrypt_two_factor_secrets --apply

Exit status: 0 on success, 2 when ``TWO_FACTOR_ENCRYPTION_KEY`` is not set (there is no key to move
anything to), 1 when ``--apply`` left any value unreadable.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from dataclasses import dataclass, field

from app.core.db import connect_db, db, disconnect_db
from app.services import two_factor

COLUMNS = ("twoFactorSecret", "twoFactorPendingSecret")


@dataclass
class Tally:
    current: int = 0
    moved: int = 0
    would_move: int = 0
    unreadable: int = 0
    raced: int = 0
    by_column: dict[str, int] = field(default_factory=lambda: dict.fromkeys(COLUMNS, 0))

    def line(self, apply: bool) -> str:
        moved = f"moved {self.moved}" if apply else f"would move {self.would_move}"
        return (
            f"already current {self.current}, {moved}, unreadable {self.unreadable}, "
            f"changed under us {self.raced}"
        )


async def run(*, apply: bool, only_ids: list[str] | None = None) -> Tally:
    """One pass. Separate from :func:`main` so a test can call it with the client it already holds;
    ``only_ids`` keeps a test to its own rows in a shared database."""
    tally = Tally()
    where: dict = {"OR": [{column: {"not": None}} for column in COLUMNS]}
    if only_ids is not None:
        where["id"] = {"in": only_ids}
    rows = await db.user.find_many(
        where=where,
        order={"id": "asc"},
    )
    for row in rows:
        for column in COLUMNS:
            stored = getattr(row, column, None)
            if not stored:
                continue
            tally.by_column[column] += 1
            if two_factor.is_current(stored):
                tally.current += 1
                continue
            fresh = two_factor.reencrypted(stored)
            if fresh is None:
                tally.unreadable += 1
                continue
            if not apply:
                tally.would_move += 1
                continue
            # NOT invalidate_cached_user: this process is not the API's, so its cache is not the one
            # serving requests. A serving process may hold the old ciphertext for up to
            # AUTH_USER_CACHE_TTL_SECONDS; it still opens, because the key it names stays configured
            # until this script reports nothing left to move (the runbook's step 4).
            written = await db.user.update_many(
                where={"id": row.id, column: stored}, data={column: fresh}
            )
            if written == 1:
                tally.moved += 1
            else:
                tally.raced += 1
    return tally


async def _connect() -> None:
    """``connect_db``, with the Windows codec failure named (see backfill_sessions_valid_from)."""
    try:
        await connect_db()
    except UnicodeDecodeError as exc:
        raise SystemExit(
            "Could not connect: Prisma's config loader could not decode backend/pyproject.toml "
            f"with this interpreter's default codec ({exc.encoding}). Re-run in UTF-8 mode:\n"
            "    py -X utf8 -m scripts.reencrypt_two_factor_secrets"
        ) from exc


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--apply", action="store_true", help="write; without it nothing is written")
    args = parser.parse_args(argv)

    two_factor.verify_key_configuration()
    if two_factor.keyring().current is None:
        print("TWO_FACTOR_ENCRYPTION_KEY is not set: there is no key to move secrets to. Nothing done.")
        return 2

    await _connect()
    try:
        tally = await run(apply=args.apply)
    finally:
        await disconnect_db()
    mode = "APPLIED" if args.apply else "DRY RUN (pass --apply to write)"
    print(f"{mode}: {tally.line(args.apply)}.")
    print(
        "Stored values: "
        + ", ".join(f"{column} {count}" for column, count in tally.by_column.items())
        + "."
    )
    if args.apply and tally.raced:
        print("Some values changed while this ran; run it again to finish them.")
    return 1 if args.apply and tally.unreadable else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
