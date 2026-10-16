-- Two-step verification (TOTP): fourteen columns on "User" and one new table for sign-in challenges.
--
-- ADDITIVE ONLY. Every new column is nullable or carries a DEFAULT, so every existing account reads
-- "two-step verification off" without an UPDATE, and the new table is empty. A deployment that
-- applies this and keeps serving the old code behaves exactly as before.
--
-- Written by hand and checked against `model User` and `model TwoFactorChallenge` in schema.prisma.
-- `IF NOT EXISTS` and the guarded constraint block are here for the reason
-- 20260913130200_dw_inspection_feedback gives: the migration runner can be interrupted and re-run.

ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorEnabled" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorSecret" TEXT;
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorPendingSecret" TEXT;
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorRecoveryCodes" TEXT[] DEFAULT ARRAY[]::TEXT[];
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorLastStep" INTEGER;
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorEnabledAt" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorFailedAttempts" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorFailureWindowStartedAt" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorLockedUntil" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorLockouts" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorRecoveryFailedAttempts" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorRecoveryWindowStartedAt" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorRecoveryLockedUntil" TIMESTAMP(3);
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorRecoveryLockouts" INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS "TwoFactorChallenge" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "usedAt" TIMESTAMP(3),
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "TwoFactorChallenge_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "TwoFactorChallenge_userId_idx" ON "TwoFactorChallenge"("userId");
CREATE INDEX IF NOT EXISTS "TwoFactorChallenge_expiresAt_idx" ON "TwoFactorChallenge"("expiresAt");

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'TwoFactorChallenge_userId_fkey'
    ) THEN
        ALTER TABLE "TwoFactorChallenge"
            ADD CONSTRAINT "TwoFactorChallenge_userId_fkey"
            FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
END $$;
