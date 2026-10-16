-- "Forgot your password?": one audit row per self-service request (model PasswordResetRequest).
--
-- ADDITIVE ONLY: a new, empty table and its indexes. A deployment that applies this and keeps serving
-- the old code behaves exactly as before. `IF NOT EXISTS` for the reason
-- 20260913130200_dw_inspection_feedback gives: the migration runner can be interrupted and re-run.

CREATE TABLE IF NOT EXISTS "PasswordResetRequest" (
    "id" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "email" TEXT NOT NULL,
    "accountId" TEXT,
    "outcome" TEXT NOT NULL,
    "ipAddress" TEXT,
    "userAgent" TEXT,

    CONSTRAINT "PasswordResetRequest_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "PasswordResetRequest_email_createdAt_idx" ON "PasswordResetRequest"("email", "createdAt");
CREATE INDEX IF NOT EXISTS "PasswordResetRequest_accountId_idx" ON "PasswordResetRequest"("accountId");
CREATE INDEX IF NOT EXISTS "PasswordResetRequest_createdAt_idx" ON "PasswordResetRequest"("createdAt");
