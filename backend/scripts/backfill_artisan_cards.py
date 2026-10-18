"""Issue a QR card for every artisan that has no live one. Dry run by default.

Cards are issued lazily the first time one is read (``services/artisan_card_store.ensure_card``),
so nothing breaks without this script. It exists so that the register a phone caches already
carries every artisan's card the day cards ship, and a card scanned offline matches.

Run it on the box after the release that added ``ArtisanCard`` is live, with the release's own
``.env`` (ARTISAN_CARD_KEY must be set: the script refuses while cards are off).

IDEMPOTENT. An artisan with a live card is skipped, and the insert is ``ON CONFLICT DO NOTHING``
against the one-live-card index, so a card the API issued in the meantime wins. Running it twice
issues nothing the second time.

IT PRINTS COUNTS ONLY: never an artisan id, a name or a code.

Usage, from ``backend/``::

    python -m scripts.backfill_artisan_cards            # DRY RUN, writes nothing
    python -m scripts.backfill_artisan_cards --apply

Exit status: 0 when every artisan has a card (or, in a dry run, always), 2 when cards are off,
1 when ``--apply`` left artisans without one.
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from app.core.config import get_settings
from app.core.db import connect_db, db, disconnect_db
from app.services import artisan_card, artisan_card_store as store

DEFAULT_BATCH = 500


async def run(*, apply: bool, batch: int = DEFAULT_BATCH) -> tuple[int, int, int]:
    """``(artisans seen, without a card, cards issued)``. Expects a connected ``db``."""
    seen = missing = issued = 0
    cursor: str | None = None
    while True:
        where: dict = {} if cursor is None else {"id": {"gt": cursor}}
        rows = await db.artisan.find_many(where=where, order={"id": "asc"}, take=batch)
        if not rows:
            break
        cursor = rows[-1].id
        ids = [row.id for row in rows]
        seen += len(ids)
        live = await db.artisancard.find_many(where={"artisanId": {"in": ids}, "revokedAt": None})
        have = {card.artisanId for card in live}
        lacking = [artisan_id for artisan_id in ids if artisan_id not in have]
        missing += len(lacking)
        if apply and lacking:
            cards = await store.ensure_cards(lacking, user_id=None, source=store.SOURCE_WEB)
            issued += sum(1 for artisan_id in lacking if artisan_id in cards)
    return seen, missing, issued


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--apply", action="store_true", help="issue the cards (default: dry run)")
    parser.add_argument("--batch", type=int, default=DEFAULT_BATCH)
    args = parser.parse_args(argv)
    if not artisan_card.active(get_settings()):
        print("Artisan cards are off here (ARTISAN_CARDS_ENABLED and ARTISAN_CARD_KEY). Nothing done.")
        return 2
    await connect_db()
    try:
        seen, missing, issued = await run(apply=args.apply, batch=max(1, args.batch))
    finally:
        await disconnect_db()
    print(f"artisans: {seen}")
    if args.apply:
        print(f"without a live card: {missing}, cards issued: {issued}")
        return 0 if issued == missing else 1
    print(f"without a live card: {missing} (dry run; --apply issues them)")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
