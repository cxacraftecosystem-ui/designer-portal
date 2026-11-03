import asyncio
import os
import sys
from datetime import UTC, datetime

from app.core.db import connect_db, db, disconnect_db
from app.core.deps import invalidate_cached_user
from app.core.security import hash_password
from app.services import access_roster

PASSWORD = "LocalDev123!"

ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("volunteer@example.org", "CROWDSOURCE_VOLUNTEER", "Test Volunteer"),
    ("contributor@example.org", "FIELD_CONTRIBUTOR", "Test Field Contributor"),
    ("researcher@example.org", "RESEARCHER", "Test Researcher"),
    ("designer@example.org", "DESIGNER", "Test Designer"),
    ("inspector@example.org", "INSPECTOR", "Test Inspector"),
    ("professor@example.org", "PROFESSOR", "Test Professor"),
    ("assistantdirector@example.org", "ASSISTANT_DIRECTOR", "Test Assistant Director"),
    ("regionaldirector@example.org", "REGIONAL_DIRECTOR", "Test Regional Director"),
    ("ministryadmin@example.org", "MINISTRY_ADMIN", "Test Ministry Admin"),
    ("admin2@example.org", "ADMIN", "Test Admin"),
)

DESIGNER_EMAIL = "designer@example.org"


def _is_local(url: str) -> bool:
    return any(host in url for host in ("localhost", "127.0.0.1", "@postgres", "@design-workshop"))


async def main() -> None:
    url = os.environ.get("DATABASE_URL", "")
    if not _is_local(url):
        raise SystemExit(
            "REFUSED: DATABASE_URL does not point at a local database.\n"
            "This script seeds accounts with a known, shared password. Running it against a "
            "deployed repository would create ten ways in."
        )

    await connect_db()
    try:
        for email, role, name in ACCOUNTS:
            data = {
                "name": name,
                "role": role,
                "passwordHash": hash_password(PASSWORD),
                "mustChangePassword": False,
                "passwordSetAt": datetime.now(UTC),
            }
            existing = await db.user.find_unique(where={"email": email})
            if existing:
                row = await db.user.update(where={"email": email}, data=data)
                verb = "updated"
            else:
                row = await db.user.create(data={**data, "email": email, "authProvider": "LOCAL"})
                verb = "created"
            if row is not None:
                invalidate_cached_user(row.id)
            await access_roster.admit(
                email,
                admit_role=role,
                full_name=name,
                note="Seeded by scripts/seed_test_accounts.py.",
                decided=False,
            )
            print(f"  {verb:<8} {role:<22} {email}")

        roster = await db.designerroster.find_unique(where={"email": DESIGNER_EMAIL})
        row = {
            "fullName": "Test Designer",
            "institution": "Local test bench",
            "isActive": True,
        }
        if roster:
            await db.designerroster.update(where={"email": DESIGNER_EMAIL}, data=row)
            print(f"  updated  roster                 {DESIGNER_EMAIL}")
        else:
            await db.designerroster.create(data={**row, "email": DESIGNER_EMAIL})
            print(f"  created  roster                 {DESIGNER_EMAIL}")
    finally:
        await disconnect_db()

    print(
        "\nAll ten share one password: PASSWORD at the top of this script, and E2E_PASSWORD on the "
        "last line of 'The sequence' in docs/TESTING-E2E-LOCAL.md."
    )
    print("Sign in as each to check that a REFUSED page is actually refused, not merely unlinked.")


if __name__ == "__main__":
    sys.exit(asyncio.run(main()) or 0)
