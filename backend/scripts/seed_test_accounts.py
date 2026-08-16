"""One local account per role, so a refusal can actually be tested.

WHY THIS EXISTS. Every permission on this repository is a pair — a predicate in
``app/core/deps.py`` and its mirror in ``frontend/lib/permissions.ts`` — and the failure mode that
matters is not the allow path but the REFUSE path: a UI guard over an open endpoint hides the link
and leaves the URL, the API and the Android client wide open. That exact bug shipped twice this
season (the designer profile, and the design-workshop write routes), and both times the reason it
survived was the same: the only local credential was the master admin, so nobody could open the app
AS a researcher and find the page still there.

These accounts make that a two-minute check instead of a code review.

    cd backend && ./.venv/Scripts/python.exe scripts/seed_test_accounts.py

Idempotent: run it as often as you like. It UPDATES an existing row rather than failing on the
unique email, so it also serves as "I have forgotten the test password again".

NOT FOR PRODUCTION, and it refuses to run against one. The guard is the same one the DB-backed test
modules use — a DATABASE_URL that does not point at localhost is refused outright, because seeding
seven known-password accounts into a live repository would be handing out seven ways in.
"""

import asyncio
import os
import sys

from app.core.db import connect_db, db, disconnect_db
from app.core.deps import invalidate_cached_user
from app.core.security import hash_password
from app.services import access_roster

#: The shared password. Deliberately obvious: these are throwaway local accounts and a memorable
#: password is the whole point. Nothing here is ever created against a remote database.
PASSWORD = "LocalDev123!"

#: One per tier of the ladder in ``deps.ROLE_RANK``, plus DESIGNER — which is not a rung anybody
#: reaches by promotion but a set member (see ``can_run_design_workshops``), and is therefore the
#: one role whose absence hides the most.
#: ``@example.org`` and NOT ``@test.local``. The login body is validated by pydantic's EmailStr,
#: which runs ``email-validator`` and refuses ``.local`` — it is not a public TLD. The first
#: version of this script used it and produced six accounts that were created successfully and
#: could not sign in, with a 422 on the EMAIL FIELD rather than anything about the password. The
#: DB-backed test modules already use example.org for exactly this reason.
ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("volunteer@example.org", "CROWDSOURCE_VOLUNTEER", "Test Volunteer"),
    ("contributor@example.org", "FIELD_CONTRIBUTOR", "Test Field Contributor"),
    ("researcher@example.org", "RESEARCHER", "Test Researcher"),
    ("designer@example.org", "DESIGNER", "Test Designer"),
    ("professor@example.org", "PROFESSOR", "Test Professor"),
    ("admin2@example.org", "ADMIN", "Test Admin"),
)

#: The one account that also needs a roster row — see the note where it is written.
DESIGNER_EMAIL = "designer@example.org"


def _is_local(url: str) -> bool:
    return any(host in url for host in ("localhost", "127.0.0.1", "@postgres", "@design-workshop"))


async def main() -> None:
    url = os.environ.get("DATABASE_URL", "")
    if not _is_local(url):
        raise SystemExit(
            "REFUSED: DATABASE_URL does not point at a local database.\n"
            "This script seeds accounts with a known, shared password. Running it against a "
            "deployed repository would create six ways in."
        )

    await connect_db()
    try:
        for email, role, name in ACCOUNTS:
            data = {"name": name, "role": role, "passwordHash": hash_password(PASSWORD)}
            existing = await db.user.find_unique(where={"email": email})
            if existing:
                row = await db.user.update(where={"email": email}, data=data)
                verb = "updated"
            else:
                row = await db.user.create(data={**data, "email": email, "authProvider": "LOCAL"})
                verb = "created"
            # Every module that writes a User row invalidates the identity cache — the contract
            # ``tests/test_user_identity_cache.py`` audits mechanically, for the reason set out in
            # ``seed_admin.py``. It is a no-op here, because this runs as its own process and the
            # API's cache lives in the API's memory. It matters anyway: this script REWRITES THE
            # ROLE of an existing account, and the whole purpose of these accounts is to sign in as
            # each role and watch a refusal. Re-seeding while the API is up and testing within the
            # TTL would otherwise check the OLD role's permissions and quietly report a pass.
            if row is not None:
                invalidate_cached_user(row.id)
            # AND THE PLATFORM ALLOW-LIST, or none of these six can sign in at all.
            #
            # Same class of trap as the designer roster row below, one level up: since the
            # allow-list arrived, `auth.assert_access_admits` refuses any account with no ACTIVE row
            # — it fails closed on purpose — and an account written straight into the User table has
            # taken neither of the two paths that admit somebody. Without this, running this script
            # produces six accounts that look perfect in Settings > Users and answer every sign-in
            # with "your access request is awaiting administrator approval", which is the single
            # most misleading state a test bench can be in.
            await access_roster.admit(
                email,
                admit_role=role,
                full_name=name,
                note="Seeded by scripts/seed_test_accounts.py.",
                decided=False,
            )
            print(f"  {verb:<8} {role:<22} {email}")

        # THE DESIGNER ALSO NEEDS A ROSTER ROW, or they cannot sign in at all.
        #
        # `User.role = DESIGNER` is NOT what admits a designer: `DesignerRoster` is the sign-in gate,
        # and an account with the role but no active roster row is refused with "your access has
        # been suspended". Seeding the role alone would produce the single most confusing possible
        # test account — one that looks right in the user table and cannot log in.
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

    print(f"\nAll six share the password: {PASSWORD}")
    print("Sign in as each to check that a REFUSED page is actually refused, not merely unlinked.")


if __name__ == "__main__":
    sys.exit(asyncio.run(main()) or 0)
