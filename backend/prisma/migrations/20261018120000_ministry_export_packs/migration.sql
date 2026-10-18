-- Ministry export packs (2026-10-18): who is e-mailed a month's packs, one row per (period,
-- scheme) pack, and a log of every download link minted for one.
--
-- ADDITIVE ONLY and safe on a live database: three new tables and nothing else changes. No row is
-- written, so nothing runs until MINISTRY_EXPORTS_ENABLED is switched on (after the Terraform apply
-- that gives the API key the `exports/*` prefix). `IF NOT EXISTS` throughout because the migration
-- runner can be interrupted and re-run.
--
-- `MinistryExportPack_period_scheme_key` is the per-month idempotency guarantee: the scheduler
-- inserts and treats a unique violation as "already there".

CREATE TABLE IF NOT EXISTS "MinistryExportRecipient" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "schemes" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "active" BOOLEAN NOT NULL DEFAULT true,
    "createdById" TEXT,
    "updatedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "MinistryExportRecipient_pkey" PRIMARY KEY ("id")
);

CREATE TABLE IF NOT EXISTS "MinistryExportPack" (
    "id" TEXT NOT NULL,
    "period" TEXT NOT NULL,
    "scheme" TEXT NOT NULL,
    "revision" INTEGER NOT NULL DEFAULT 1,
    "trigger" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'QUEUED',
    "monthToDate" BOOLEAN NOT NULL DEFAULT false,
    "requestedById" TEXT,
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "maxAttempts" INTEGER NOT NULL DEFAULT 3,
    "runAfter" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lockedAt" TIMESTAMP(3),
    "lockedBy" TEXT,
    "snapshotAt" TIMESTAMP(3),
    "xlsxKey" TEXT,
    "xlsxBytes" INTEGER,
    "xlsxSha256" TEXT,
    "pdfKey" TEXT,
    "pdfBytes" INTEGER,
    "pdfSha256" TEXT,
    "counts" JSONB,
    "schemaVersion" TEXT,
    "error" TEXT,
    "finishedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "MinistryExportPack_pkey" PRIMARY KEY ("id")
);

CREATE TABLE IF NOT EXISTS "MinistryExportDownload" (
    "id" TEXT NOT NULL,
    "packId" TEXT NOT NULL,
    "userId" TEXT,
    "file" TEXT NOT NULL,
    "revision" INTEGER NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "MinistryExportDownload_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "MinistryExportRecipient_userId_key" ON "MinistryExportRecipient"("userId");

CREATE INDEX IF NOT EXISTS "MinistryExportRecipient_active_idx" ON "MinistryExportRecipient"("active");

CREATE INDEX IF NOT EXISTS "MinistryExportRecipient_createdById_idx" ON "MinistryExportRecipient"("createdById");

CREATE INDEX IF NOT EXISTS "MinistryExportRecipient_updatedById_idx" ON "MinistryExportRecipient"("updatedById");

CREATE INDEX IF NOT EXISTS "MinistryExportPack_status_runAfter_idx" ON "MinistryExportPack"("status", "runAfter");

CREATE INDEX IF NOT EXISTS "MinistryExportPack_requestedById_idx" ON "MinistryExportPack"("requestedById");

CREATE UNIQUE INDEX IF NOT EXISTS "MinistryExportPack_period_scheme_key" ON "MinistryExportPack"("period", "scheme");

CREATE INDEX IF NOT EXISTS "MinistryExportDownload_packId_createdAt_idx" ON "MinistryExportDownload"("packId", "createdAt");

CREATE INDEX IF NOT EXISTS "MinistryExportDownload_userId_idx" ON "MinistryExportDownload"("userId");

DO $$ BEGIN
    ALTER TABLE "MinistryExportRecipient" ADD CONSTRAINT "MinistryExportRecipient_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE "MinistryExportRecipient" ADD CONSTRAINT "MinistryExportRecipient_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE "MinistryExportRecipient" ADD CONSTRAINT "MinistryExportRecipient_updatedById_fkey" FOREIGN KEY ("updatedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE "MinistryExportPack" ADD CONSTRAINT "MinistryExportPack_requestedById_fkey" FOREIGN KEY ("requestedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE "MinistryExportDownload" ADD CONSTRAINT "MinistryExportDownload_packId_fkey" FOREIGN KEY ("packId") REFERENCES "MinistryExportPack"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE "MinistryExportDownload" ADD CONSTRAINT "MinistryExportDownload_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
