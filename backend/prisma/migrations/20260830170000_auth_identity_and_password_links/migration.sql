-- ================================================================================================
-- THREE-WAY SIGN-IN IDENTITY, THE FIRST-LOGIN PASSWORD, AND ADMIN-ISSUED PASSWORD LINKS
-- ================================================================================================
--
-- Four things, in the order they have to happen:
--
--   1. Four columns on "User" that separate "has never had a password" from "signs in with Google",
--      carry the must-change flag, stamp the first real sign-in, and give session revocation
--      somewhere to be written.
--   2. Two NORMALISED lookup keys on "DesignerProfile", so a phone number and an empanelment number
--      can be signed in with. The raw columns are untouched — they are printed on reports.
--   3. THE BACKFILL, WHICH IS THE PART THAT NEEDS READING. It deliberately does not fill every row.
--   4. "PasswordResetToken" and its enum.
--
-- ── WHY THE BACKFILL SKIPS DUPLICATES INSTEAD OF PICKING A WINNER ───────────────────────────────
--
-- Measured on this database before the constraint was written, which is the only order that is
-- safe:
--
--     SELECT upper(regexp_replace("empanelmentNo",'[^A-Za-z0-9]','','g')) AS k, count(*)
--       FROM "DesignerProfile"
--      WHERE "empanelmentNo" IS NOT NULL AND btrim("empanelmentNo") <> ''
--      GROUP BY 1 HAVING count(*) > 1;
--
--     k           | count
--     EMP20260042 |    44
--
-- 185 profiles, 44 of them holding one identical empanelment number and none holding a phone
-- number at all. A plain `ALTER TABLE ... ADD CONSTRAINT UNIQUE` over the normalised value would
-- therefore have FAILED TO APPLY, in production, on the deploy that shipped the feature.
--
-- The answer is not to drop the constraint and it is not to keep one of the 44. A unique index
-- ignores NULLs, so the backfill claims a key only where exactly one profile holds that value, and
-- leaves every member of a colliding group NULL. Those 44 profiles keep their number, keep printing
-- it on their reports, and simply cannot be signed in with it — which is the correct answer,
-- because an ambiguous number identifies nobody and choosing among them would sign the wrong person
-- in without saying so. The application applies the same rule on every save (see
-- app/services/identity.py: a key already held by another profile is not claimed).
--
-- ================================================================================================

-- 1. ── User -------------------------------------------------------------------------------------
ALTER TABLE "User" ADD COLUMN "passwordSetAt" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN "mustChangePassword" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "User" ADD COLUMN "firstLoginAt" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN "sessionsValidFrom" TIMESTAMP(3);

-- Every account that already holds a password hash has had one set by somebody, at some point
-- nobody recorded. `createdAt` is the only honest lower bound available and it is used rather than
-- now(): stamping now() would say every existing password was set on the day of this deploy, and
-- the column would then be worse than useless for the one question it exists to answer.
--
-- NOT `mustChangePassword`, deliberately. Grandfathering every existing account into "you must
-- change your password" would meet every designer in the product with a change-password screen on
-- their next sign-in, for a password nobody had any reason to distrust. The flag starts false for
-- everybody who exists today and is set only by the paths that mint a password FOR somebody.
UPDATE "User" SET "passwordSetAt" = "createdAt" WHERE "passwordHash" IS NOT NULL;

-- 2. ── DesignerProfile lookup keys ---------------------------------------------------------------
ALTER TABLE "DesignerProfile" ADD COLUMN "phoneKey" TEXT;
ALTER TABLE "DesignerProfile" ADD COLUMN "empanelmentKey" TEXT;

-- 3. ── The backfill: unambiguous values only -----------------------------------------------------
--
-- The two normalisations below MUST agree character for character with `normalise_phone` and
-- `normalise_empanelment_no` in app/services/identity.py. They are written twice — once in SQL for
-- the rows that already exist and once in Python for every row written from now on — and there is
-- no way to share them across that boundary. If you change one, change the other, and say so.
--
--   phone:       digits only; the last 10 when there are more than 10, so "+91 98765 43210",
--                "098765 43210" and "9876543210" are one key. Fewer than 6 digits is not a phone
--                number and is not claimed.
--   empanelment: upper-cased, every character that is not a letter or a digit removed.

WITH normalised AS (
  SELECT
    "id",
    CASE
      WHEN length(regexp_replace(COALESCE("phone", ''), '[^0-9]', '', 'g')) > 10
        THEN right(regexp_replace("phone", '[^0-9]', '', 'g'), 10)
      ELSE nullif(regexp_replace(COALESCE("phone", ''), '[^0-9]', '', 'g'), '')
    END AS phone_key
  FROM "DesignerProfile"
),
usable AS (
  SELECT phone_key FROM normalised
   WHERE phone_key IS NOT NULL AND length(phone_key) >= 6
   GROUP BY phone_key HAVING count(*) = 1
)
UPDATE "DesignerProfile" p
   SET "phoneKey" = n.phone_key
  FROM normalised n
  JOIN usable u ON u.phone_key = n.phone_key
 WHERE p."id" = n."id";

WITH normalised AS (
  SELECT
    "id",
    nullif(upper(regexp_replace(COALESCE("empanelmentNo", ''), '[^A-Za-z0-9]', '', 'g')), '') AS emp_key
  FROM "DesignerProfile"
),
usable AS (
  SELECT emp_key FROM normalised
   WHERE emp_key IS NOT NULL
   GROUP BY emp_key HAVING count(*) = 1
)
UPDATE "DesignerProfile" p
   SET "empanelmentKey" = n.emp_key
  FROM normalised n
  JOIN usable u ON u.emp_key = n.emp_key
 WHERE p."id" = n."id";

CREATE UNIQUE INDEX "DesignerProfile_phoneKey_key" ON "DesignerProfile"("phoneKey");
CREATE UNIQUE INDEX "DesignerProfile_empanelmentKey_key" ON "DesignerProfile"("empanelmentKey");

-- 4. ── Password reset links -----------------------------------------------------------------------
CREATE TYPE "CredentialLinkPurpose" AS ENUM ('INVITE', 'RESET');

CREATE TABLE "PasswordResetToken" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "purpose" "CredentialLinkPurpose" NOT NULL DEFAULT 'RESET',
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "usedAt" TIMESTAMP(3),
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "issuedById" TEXT,

    CONSTRAINT "PasswordResetToken_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "PasswordResetToken_tokenHash_key" ON "PasswordResetToken"("tokenHash");
CREATE INDEX "PasswordResetToken_userId_createdAt_idx" ON "PasswordResetToken"("userId", "createdAt");
CREATE INDEX "PasswordResetToken_issuedById_idx" ON "PasswordResetToken"("issuedById");

ALTER TABLE "PasswordResetToken" ADD CONSTRAINT "PasswordResetToken_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "PasswordResetToken" ADD CONSTRAINT "PasswordResetToken_issuedById_fkey" FOREIGN KEY ("issuedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
