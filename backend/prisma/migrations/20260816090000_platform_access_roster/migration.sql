-- THE PLATFORM ALLOW-LIST, AND THE MIGRATION THAT MUST NOT LOCK ANYBODY OUT.
--
-- From this migration onward `POST /api/auth/login` refuses any account without an ACTIVE row in
-- `AccessRoster` (the master admin excepted, in code, as the break-glass). That makes the two
-- INSERTs at the bottom of this file the load-bearing part: without them, deploying this migration
-- locks EVERY user of the product out of it at once, including the admins who would have to let
-- them back in. `tests/test_platform_access_gate.py` seeds an account of every role, runs against
-- the migrated schema and signs each one in, precisely so that "the grandfathering worked" is a
-- test failure rather than a support queue.
--
-- Additive and reversible. One new enum, one new table, nothing existing is altered or dropped —
-- `DesignerRoster` is deliberately untouched, see the model docstring in schema.prisma for why the
-- allow-list is not a status column on it.

-- CreateEnum
CREATE TYPE "AccessStatus" AS ENUM ('ACTIVE', 'PENDING', 'REJECTED', 'SUSPENDED');

-- CreateTable
CREATE TABLE "AccessRoster" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "status" "AccessStatus" NOT NULL DEFAULT 'PENDING',
    "admitRole" "UserRole",
    "joinedAt" TIMESTAMP(3),
    "requestedAt" TIMESTAMP(3),
    "attemptCount" INTEGER NOT NULL DEFAULT 0,
    "lastAttemptAt" TIMESTAMP(3),
    "decidedAt" TIMESTAMP(3),
    "decidedById" TEXT,
    "firstSeenAt" TIMESTAMP(3),
    "fullName" TEXT,
    "notes" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "addedById" TEXT,

    CONSTRAINT "AccessRoster_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "AccessRoster_email_key" ON "AccessRoster"("email");

-- CreateIndex
CREATE INDEX "AccessRoster_status_idx" ON "AccessRoster"("status");

-- CreateIndex
CREATE INDEX "AccessRoster_status_requestedAt_idx" ON "AccessRoster"("status", "requestedAt");

-- CreateIndex
CREATE INDEX "AccessRoster_addedById_idx" ON "AccessRoster"("addedById");

-- CreateIndex
CREATE INDEX "AccessRoster_decidedById_idx" ON "AccessRoster"("decidedById");

-- AddForeignKey
ALTER TABLE "AccessRoster" ADD CONSTRAINT "AccessRoster_decidedById_fkey" FOREIGN KEY ("decidedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "AccessRoster" ADD CONSTRAINT "AccessRoster_addedById_fkey" FOREIGN KEY ("addedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- ======================================================================================
-- GRANDFATHERING. Every account that exists on the day the gate arrives is admitted.
-- ======================================================================================
--
-- `joinedAt` IS BACKFILLED FROM `User.createdAt` RATHER THAN SET TO NOW(). The admin screen prints
-- this column as "joined", and a wholesale now() would tell an admin that all four hundred people
-- who have been using this product for two years joined on the afternoon of the deploy — a fact
-- that is not merely useless but wrong, and unrecoverable once the real dates are overwritten.
-- `createdAt` stays now(), because this ROW was created now; the two columns answer two questions
-- and this migration is exactly the case where they differ.
--
-- `admitRole` is left NULL: these accounts already hold a role in `User.role` and the allow-list
-- must not restate it. A value here is only ever read when an account is being CREATED or LIFTED,
-- and neither happens to somebody who is already signed up.
--
-- `decidedAt`/`decidedById` are left NULL and the note says why. Attributing four hundred approvals
-- to whichever admin happened to run the deploy would be a fabricated audit trail.
--
-- DISTINCT ON, because `AccessRoster.email` is UNIQUE on the LOWER-CASED address while `User.email`
-- is not lower-cased at all: two accounts differing only in capitalisation (which the User table
-- permits and this one does not) would otherwise abort the whole statement. The oldest account
-- wins, so the surviving row carries the earliest joining date of the addresses that collapsed.
INSERT INTO "AccessRoster" (
    "id", "email", "status", "admitRole", "joinedAt", "notes", "createdAt", "updatedAt"
)
SELECT DISTINCT ON (lower(u."email"))
    'acc_' || replace(gen_random_uuid()::text, '-', ''),
    lower(u."email"),
    'ACTIVE'::"AccessStatus",
    NULL,
    u."createdAt",
    'Admitted automatically when the platform allow-list was introduced: this account already '
        || 'existed and signing in was not something anybody had to approve. No administrator '
        || 'reviewed it, which is why the decision fields are empty.',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM "User" u
WHERE u."email" IS NOT NULL AND length(trim(u."email")) > 0
ORDER BY lower(u."email"), u."createdAt" ASC
ON CONFLICT ("email") DO NOTHING;

-- THE SECOND POPULATION: designers an admin empanelled who have never opened the app.
--
-- `DesignerRoster` exists so an admin can empanel somebody BEFORE their account does — the account
-- provisions itself at DESIGNER on first Google sign-in. That flow has no `User` row for the INSERT
-- above to find, so without this statement the gate would refuse the very people an admin has
-- already approved, and the admin's remedy would be to approve them a second time in a different
-- screen. `admitRole` is DESIGNER here for the same reason `login_with_google` promotes them.
--
-- SUSPENDED empanelments are deliberately NOT admitted. An admin ended that empanelment; the fact
-- that a suspended, account-less address can today still self-provision as a volunteer through
-- Google is the exact behaviour this feature was asked to end.
INSERT INTO "AccessRoster" (
    "id", "email", "status", "admitRole", "joinedAt", "fullName", "notes", "addedById",
    "createdAt", "updatedAt"
)
SELECT DISTINCT ON (lower(r."email"))
    'acc_' || replace(gen_random_uuid()::text, '-', ''),
    lower(r."email"),
    'ACTIVE'::"AccessStatus",
    'DESIGNER'::"UserRole",
    r."createdAt",
    r."fullName",
    'Admitted automatically when the platform allow-list was introduced: an administrator had '
        || 'already empanelled this address on the designer roster, and an empanelment is an '
        || 'approval.',
    r."addedById",
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM "DesignerRoster" r
WHERE r."isActive" = true AND length(trim(r."email")) > 0
ORDER BY lower(r."email"), r."createdAt" ASC
ON CONFLICT ("email") DO NOTHING;
