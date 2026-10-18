"""Place, release or list a legal hold on one record's Aadhaar log (Regulation 18(3)).

While a hold is open the record's Aadhaar log entries are neither archived nor deleted, and no
archive holding them is deleted (``services/aadhaar/log_retention.py``). Place one when a court
order or a pending dispute requires the entries to be kept; release it when that ends.

Usage, from ``backend/`` on the box::

    python -m scripts.aadhaar_legal_hold list
    python -m scripts.aadhaar_legal_hold place artisan <record id> "W.P. 1234/2027, Calcutta HC"
    python -m scripts.aadhaar_legal_hold release artisan <record id>

``reason`` names the order or the dispute; never put personal data in it.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from datetime import UTC, datetime

from app.core.db import connect_db, db, disconnect_db

RECORD_TYPES = ("user", "artisan")


async def _run(args: argparse.Namespace) -> int:
    if args.command == "list":
        holds = await db.aadhaarlegalhold.find_many(where={"releasedAt": None}, order={"createdAt": "asc"})
        for hold in holds:
            print(f"{hold.recordType} {hold.recordId}  since {hold.createdAt:%Y-%m-%d}  {hold.reason}")
        print(f"{len(holds)} open hold(s)")
        return 0
    model = db.user if args.record_type == "user" else db.artisan
    if await model.find_unique(where={"id": args.record_id}) is None:
        print(f"No {args.record_type} with that id.")
        return 1
    open_holds = {"recordType": args.record_type, "recordId": args.record_id, "releasedAt": None}
    if args.command == "place":
        if await db.aadhaarlegalhold.find_first(where=open_holds):
            print("That record is already under a legal hold.")
            return 0
        await db.aadhaarlegalhold.create(
            data={"recordType": args.record_type, "recordId": args.record_id, "reason": args.reason}
        )
        print("Hold placed.")
        return 0
    released = await db.aadhaarlegalhold.update_many(
        where=open_holds, data={"releasedAt": datetime.now(UTC)}
    )
    print(f"Released {released} hold(s).")
    return 0


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("list")
    place = sub.add_parser("place")
    place.add_argument("record_type", choices=RECORD_TYPES)
    place.add_argument("record_id")
    place.add_argument("reason")
    release = sub.add_parser("release")
    release.add_argument("record_type", choices=RECORD_TYPES)
    release.add_argument("record_id")
    args = parser.parse_args(argv)
    await connect_db()
    try:
        return await _run(args)
    finally:
        await disconnect_db()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
