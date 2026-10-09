-- E-mail: one new table (the outbox the queue worker drains, which is also the send log) and one
-- new opt-out column on UserPreference.
--
-- ADDITIVE ONLY. No existing column is dropped or retyped and no existing row is rewritten: the new
-- column carries a DEFAULT, so every existing preference row reads "e-mail me" without an UPDATE,
-- and the new table is empty. A deployment that applies this and keeps serving the old code behaves
-- exactly as before.
--
-- Written by hand and checked against `model EmailMessage` and `model UserPreference` in
-- schema.prisma. `IF NOT EXISTS` and the guarded constraint block are here for the reason
-- 20260913130200_dw_inspection_feedback gives: the migration runner can be interrupted and re-run.

ALTER TABLE "UserPreference" ADD COLUMN IF NOT EXISTS "emailReviewNotes" BOOLEAN NOT NULL DEFAULT true;

CREATE TABLE IF NOT EXISTS "EmailMessage" (
    "id" TEXT NOT NULL,
    "kind" TEXT NOT NULL,
    "toAddress" TEXT NOT NULL,
    "recipientId" TEXT,
    "subject" TEXT NOT NULL,
    "params" JSONB,
    "sealed" TEXT,
    "status" TEXT NOT NULL DEFAULT 'QUEUED',
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "maxAttempts" INTEGER NOT NULL DEFAULT 5,
    "runAfter" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lockedAt" TIMESTAMP(3),
    "lockedBy" TEXT,
    "sentAt" TIMESTAMP(3),
    "providerMessageId" TEXT,
    "error" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "EmailMessage_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "EmailMessage_status_runAfter_createdAt_idx"
    ON "EmailMessage"("status", "runAfter", "createdAt");

CREATE INDEX IF NOT EXISTS "EmailMessage_recipientId_idx" ON "EmailMessage"("recipientId");

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'EmailMessage_recipientId_fkey'
    ) THEN
        ALTER TABLE "EmailMessage"
            ADD CONSTRAINT "EmailMessage_recipientId_fkey"
            FOREIGN KEY ("recipientId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
    END IF;
END $$;
