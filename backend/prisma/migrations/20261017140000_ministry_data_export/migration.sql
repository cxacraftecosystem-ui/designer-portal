-- C15: the Ministry dashboard's background stage-data export. One new, empty table.
--
-- ADDITIVE ONLY: no existing table or column is touched, so a deployment that applies this and
-- keeps serving the old code behaves exactly as before. `IF NOT EXISTS` and the guarded constraint
-- block let an interrupted run be re-run, as 20261010100000_email_outbox does.

CREATE TABLE IF NOT EXISTS "MinistryDataExport" (
    "id" TEXT NOT NULL,
    "requestedById" TEXT,
    "filters" JSONB,
    "status" TEXT NOT NULL DEFAULT 'QUEUED',
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "lockedAt" TIMESTAMP(3),
    "lockedBy" TEXT,
    "workshopCount" INTEGER,
    "rowCount" INTEGER,
    "fileName" TEXT,
    "fileSizeBytes" INTEGER,
    "content" BYTEA,
    "error" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "startedAt" TIMESTAMP(3),
    "finishedAt" TIMESTAMP(3),
    "expiresAt" TIMESTAMP(3),
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "MinistryDataExport_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "MinistryDataExport_status_createdAt_idx"
    ON "MinistryDataExport"("status", "createdAt");
CREATE INDEX IF NOT EXISTS "MinistryDataExport_requestedById_createdAt_idx"
    ON "MinistryDataExport"("requestedById", "createdAt");

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'MinistryDataExport_requestedById_fkey'
    ) THEN
        ALTER TABLE "MinistryDataExport"
            ADD CONSTRAINT "MinistryDataExport_requestedById_fkey"
            FOREIGN KEY ("requestedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
    END IF;
END $$;
