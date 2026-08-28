"""Create (or remove) N signed-in-able identities, each owning one workshop, for the load driver.

WHY THIS EXISTS AT ALL. Production is pre-launch — two users and one workshop (2026-08-27) — so
there is no traffic to learn from and no population to replay. A load test therefore has to
manufacture its own population, and the population is the part most benchmarks get wrong: they sign
in once and reuse one token across every worker. That is not a thousand users, it is one user with a
thousand sockets, and against this API it measures the wrong thing twice over —

  * the rate limiter installed on 2026-08-27 keys its bucket on a DIGEST OF THE BEARER TOKEN
    (``app/scale/rate_limit.py::_identity``), so one shared token is one bucket of 120 requests a
    minute and the run degenerates into a 429 benchmark; and
  * ``app/core/deps`` caches the authenticated row per user id with an LRU of 512 entries, so one
    shared identity is a 100 % cache hit rate that a real population of a thousand cannot get.

Both effects only appear with genuinely distinct identities, and both are things the owner asked
about. Hence: real rows, real bcrypt hashes, real tokens.

WHY IT TALKS TO POSTGRES THROUGH ``psql`` AND NOT THROUGH PRISMA, which is what every other seeder
in ``backend/scripts/`` does. Importing the generated Prisma client costs 15+ MINUTES on this
machine's Python 3.14 venv — ``prisma/types.py`` is 444,394 lines of ``TypedDict``, and 3.14's
PEP 649 annotation machinery (via ``typing_extensions.TypedDict.__new__``) evaluates all of it
eagerly. Production runs Python 3.12 (``backend/Dockerfile``: ``ARG PYTHON_VERSION=3.12``) where
this does not happen, so it is a local-toolchain problem and not a product one — but it is not one a
seeder should have to pay to write three INSERT statements. ``psql`` starts in milliseconds and the
statements below are flat enough to read.

VERIFIED 2026-08-27. Re-check with:
    backend/.venv/Scripts/python.exe backend/loadtest/seed_load_identities.py --count 4 --dry-run

THE ONE THING THAT IS NOT HAND-ROLLED IS THE PASSWORD HASH. It comes from
``app.core.security.hash_password`` — the API's own function — so the seeded rows cannot drift from
what ``verify_password`` accepts. That import does NOT touch Prisma (``app.core.security`` pulls in
only ``app.core.config``, ``jose`` and ``passlib``), which is why it is affordable here.

ONE HASH FOR EVERY IDENTITY, computed once and reused. bcrypt at cost 12 takes ~370 ms, so hashing
a thousand rows separately would cost six minutes for no benefit: distinct salts protect a stolen
password database, and this database is a loopback container full of accounts whose password is
written in the source. Say it out loud rather than let a reader assume it was an oversight.

REFUSES ANY DSN THAT IS NOT LOOPBACK, on the same guard ``scripts/seed_test_accounts.py`` uses and
for a stronger reason: that script writes seven known-password accounts, this one writes a thousand.

Usage
-----
    cd backend
    ./.venv/Scripts/python.exe loadtest/seed_load_identities.py --count 1000
    ./.venv/Scripts/python.exe loadtest/seed_load_identities.py --purge      # remove them again

``--purge`` is not optional politeness. The local database is shared with the pytest suite, and a
thousand extra DESIGNER rows will change the answer of any test that counts users or lists
workshops. Seed, measure, purge.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

from loadtest.scenario import EMAIL_PATTERN, LOAD_PASSWORD  # noqa: E402

#: Every row this script writes carries one of these id prefixes, which is what makes ``--purge`` a
#: statement anybody can read and check rather than a date-range guess.
USER_ID = "loadtest-user-{index:05d}"
WORKSHOP_ID = "loadtest-ws-{index:05d}"
ACCESS_ID = "loadtest-acc-{index:05d}"
ROSTER_ID = "loadtest-ros-{index:05d}"
ID_PREFIX = "loadtest-"

#: The compose service that holds the database (docker-compose.yml).
CONTAINER = "design-workshop-postgres"


def _local_dsn(url: str) -> bool:
    """The guard. Substring rather than a parse, matching ``scripts/seed_test_accounts.py``."""
    return any(host in url for host in ("localhost", "127.0.0.1", "@postgres", "@design-workshop"))


def _database_url() -> str:
    """The DSN the API itself would use — settings first, environment second.

    Deliberately the same resolution order ``tests/conftest.py`` documents at length: reading
    ``os.environ`` alone answers "" on a developer machine where the DSN lives only in
    ``backend/.env``, and a guard that cannot see the DSN is a guard that cannot refuse.
    """
    try:
        from app.core.config import get_settings

        resolved = str(get_settings().database_url or "")
        if resolved:
            return resolved
    except Exception:  # noqa: BLE001 - no settings is less evidence, not an error
        pass
    return os.environ.get("DATABASE_URL", "")


def _psql(sql: str, *, database: str) -> str:
    """Run one script through ``psql`` inside the postgres container, on stdin.

    ``-v ON_ERROR_STOP=1`` so a broken statement fails the run instead of leaving a half-seeded
    population that the driver would then sign in to and get 401s from — the failure mode that
    looks like an application defect and is not.
    """
    proc = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "postgres", "-d", database,
         "-v", "ON_ERROR_STOP=1", "-At", "-f", "-"],
        input=sql.encode("utf-8"),
        capture_output=True,
        # The return code is inspected below and turned into a SystemExit with psql's own stderr,
        # which says WHICH statement failed. `check=True` would raise a CalledProcessError whose
        # message is the argv and nothing else.
        check=False,
    )
    if proc.returncode != 0:
        raise SystemExit(
            f"psql failed ({proc.returncode}):\n{proc.stderr.decode('utf-8', 'replace')[:4000]}"
        )
    return proc.stdout.decode("utf-8", "replace")


def _quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def build_seed_sql(count: int, password_hash: str) -> str:
    """One transaction: N users, N allow-list rows, N empanelments, N workshops.

    IDEMPOTENT VIA ``ON CONFLICT``, so re-running after a partial failure is safe and so raising
    ``--count`` from 200 to 1000 adds 800 rows rather than erroring on the first 200.

    ``firstSeenAt`` IS PRE-SET ON BOTH ROSTER ROWS, and that is a measurement decision rather than a
    convenience. ``auth.mark_roster_seen`` / ``access_roster.mark_access_seen`` stamp that column on
    the FIRST successful sign-in only (both guard it in the WHERE clause). Left NULL, the very first
    sign-in of every identity would carry two extra UPDATEs that no subsequent sign-in pays — so a
    warm-up of a thousand identities would measure a write path a returning user never touches, and
    would report sign-in as more expensive than it is. Pre-stamped, every measured sign-in is the
    ordinary one: three reads and a bcrypt.
    """
    parts = [
        "BEGIN;",
        # A DESIGNER, not an admin: it is the role that actually runs workshops, and it is the only
        # role that has to pass BOTH gates in `auth.login` (the platform allow-list AND the
        # empanelment check), so seeding it exercises the more expensive of the two sign-in paths.
        f"""
INSERT INTO "User" (id, email, name, "passwordHash", role, "authProvider", "createdAt", "updatedAt")
SELECT
  'loadtest-user-' || lpad(i::text, 5, '0'), 'loadtest+' || lpad(i::text, 5, '0') || '@example.org',
  format('Load Designer %s', i), {_quote(password_hash)}, 'DESIGNER', 'LOCAL', NOW(), NOW()
FROM generate_series(0, {count - 1}) AS i
ON CONFLICT (email) DO UPDATE SET "passwordHash" = EXCLUDED."passwordHash";
""",
        f"""
INSERT INTO "AccessRoster" (id, email, status, "admitRole", "firstSeenAt", "joinedAt",
                            "createdAt", "updatedAt")
SELECT
  'loadtest-acc-' || lpad(i::text, 5, '0'), 'loadtest+' || lpad(i::text, 5, '0') || '@example.org',
  'ACTIVE', 'DESIGNER', NOW(), NOW(), NOW(), NOW()
FROM generate_series(0, {count - 1}) AS i
ON CONFLICT (email) DO UPDATE SET status = 'ACTIVE', "firstSeenAt" = COALESCE("AccessRoster"."firstSeenAt", NOW());
""",
        f"""
INSERT INTO "DesignerRoster" (id, email, "fullName", "isActive", "firstSeenAt",
                              "createdAt", "updatedAt")
SELECT
  'loadtest-ros-' || lpad(i::text, 5, '0'), 'loadtest+' || lpad(i::text, 5, '0') || '@example.org',
  format('Load Designer %s', i), TRUE, NOW(), NOW(), NOW()
FROM generate_series(0, {count - 1}) AS i
ON CONFLICT (email) DO UPDATE SET "isActive" = TRUE, "firstSeenAt" = COALESCE("DesignerRoster"."firstSeenAt", NOW());
""",
        # ONE WORKSHOP PER IDENTITY, CREATED BY THAT IDENTITY. Two reasons, and neither is tidiness.
        # (1) `services/design_workshops.load_workshop_or_404` admits the creator on a cheap column
        #     comparison and everybody else through an extra `has_viewer_grant` query — so pointing a
        #     thousand identities at shared workshops would silently add a database round trip to
        #     every read and write in the mix and attribute it to the endpoint.
        # (2) A thousand writers hammering one row is row-lock contention, which is a real
        #     phenomenon but NOT the one being measured here, and it would swamp everything else.
        #     Real designers each work on their own record; so does this population.
        f"""
INSERT INTO "DesignWorkshop" (id, title, "templateId", status, "craftName", state,
                              "createdById", "createdAt", "updatedAt")
SELECT
  'loadtest-ws-' || lpad(i::text, 5, '0'), format('Load Workshop %s', i), 'DCH_STANDARD', 'IN_PROGRESS',
  'Loadtest Craft', 'Loadtest State', 'loadtest-user-' || lpad(i::text, 5, '0'), NOW(), NOW()
FROM generate_series(0, {count - 1}) AS i
ON CONFLICT (id) DO NOTHING;
""",
        "COMMIT;",
        # Printed so the caller sees what actually landed rather than trusting the exit code.
        f"""SELECT (SELECT count(*) FROM "User" WHERE id LIKE '{ID_PREFIX}%')
       || ' users, '
       || (SELECT count(*) FROM "DesignWorkshop" WHERE id LIKE '{ID_PREFIX}%')
       || ' workshops';""",
    ]
    return "\n".join(parts)


def build_purge_sql() -> str:
    """Remove everything this script created, plus everything the DRIVER wrote through it.

    ORDER IS FORCED BY THE FOREIGN KEYS, and one of them bites: ``DesignWorkshop.createdById`` is
    THE COLUMN IS ``designWorkshopId``, NOT ``workshopId``, on both ``DwStageEntry`` and
    ``DwCustomSection``. Written down because this purge shipped with ``workshopId`` and every
    ``--purge`` died on ``ERROR: column "workshopId" does not exist`` (found 2026-08-27) — after
    the seed had already worked, so the failure arrived at the one moment nobody re-reads the
    script: cleanup. A population that will not delete is worse than one that will not seed,
    because the next pytest run inherits a thousand extra DESIGNER rows and blames itself.
    Re-check the names with:
        docker exec design-workshop-postgres psql -U postgres -d design_workshop -At -c           "SELECT column_name FROM information_schema.columns WHERE table_name='DwStageEntry';"

    ``ON DELETE RESTRICT``, so the users cannot go until their workshops have. ``DwStageEntry`` rows
    are the participants and notes the load run itself created — they are not seeded here, but they
    are load-test data and leaving them behind would quietly change what ``GET /design-workshops/{id}``
    costs the next time anybody measures it.
    """
    return f"""
BEGIN;
DELETE FROM "DwStageEntry" WHERE "designWorkshopId" LIKE '{ID_PREFIX}%';
DELETE FROM "DwCustomField"  WHERE "sectionId" IN (SELECT id FROM "DwCustomSection" WHERE "designWorkshopId" LIKE '{ID_PREFIX}%');
DELETE FROM "DwCustomSection" WHERE "designWorkshopId" LIKE '{ID_PREFIX}%';
DELETE FROM "DesignWorkshop" WHERE id LIKE '{ID_PREFIX}%';
DELETE FROM "DesignerRoster" WHERE id LIKE '{ID_PREFIX}%';
DELETE FROM "AccessRoster"   WHERE id LIKE '{ID_PREFIX}%';
DELETE FROM "User"           WHERE id LIKE '{ID_PREFIX}%';
COMMIT;
SELECT 'remaining: '
    || (SELECT count(*) FROM "User" WHERE id LIKE '{ID_PREFIX}%') || ' users, '
    || (SELECT count(*) FROM "DesignWorkshop" WHERE id LIKE '{ID_PREFIX}%') || ' workshops';
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--count", type=int, default=1000, help="identities to create (default 1000)")
    parser.add_argument("--purge", action="store_true", help="delete every loadtest-* row and exit")
    parser.add_argument("--dry-run", action="store_true", help="print the SQL and exit")
    parser.add_argument("--database", default="design_workshop", help="database name inside the container")
    args = parser.parse_args()

    dsn = _database_url()
    if not dsn:
        print("No DATABASE_URL resolved. Run from backend/ so app.core.config can read backend/.env.", file=sys.stderr)
        return 2
    if not _local_dsn(dsn):
        # Deliberately does not print the DSN: it carries a password.
        print("REFUSED: DATABASE_URL is not loopback. This script seeds known-password accounts.", file=sys.stderr)
        return 2

    if args.purge:
        sql = build_purge_sql()
        if args.dry_run:
            print(sql)
            return 0
        print(_psql(sql, database=args.database).strip())
        return 0

    # Imported here rather than at module scope: see the module docstring on why this file
    # must be able to run (--purge, --dry-run) without paying for app.core.config at all.
    from app.core.security import hash_password

    password_hash = hash_password(LOAD_PASSWORD)
    sql = build_seed_sql(args.count, password_hash)
    if args.dry_run:
        print(sql)
        return 0
    print(f"Seeding {args.count} identities as {EMAIL_PATTERN.format(index=0)} .. "
          f"{EMAIL_PATTERN.format(index=args.count - 1)}")
    print(_psql(sql, database=args.database).strip())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
