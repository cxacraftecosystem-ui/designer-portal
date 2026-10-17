-- Notices and delivery (2026-10-18): designers' and officials' e-mail choices, a topic on each
-- outbox row so a screen can say whether a message went, the office addresses an approved report is
-- e-mailed to, and the expiring download links it is sent under.
--
-- ADDITIVE ONLY and safe on a live database: new columns carry defaults that keep today's behaviour
-- (every opt-out reads "yes", the office list is empty so hand-on stays manual), and one new table.
-- `IF NOT EXISTS` throughout because the migration runner can be interrupted and re-run.

ALTER TABLE "UserPreference" ADD COLUMN IF NOT EXISTS "emailWorkshopUpdates" BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE "UserPreference" ADD COLUMN IF NOT EXISTS "emailAccessNotices" BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE "UserPreference" ADD COLUMN IF NOT EXISTS "emailHandInNotices" TEXT NOT NULL DEFAULT 'EACH';

ALTER TABLE "EmailMessage" ADD COLUMN IF NOT EXISTS "topic" TEXT;
CREATE INDEX IF NOT EXISTS "EmailMessage_topic_createdAt_idx" ON "EmailMessage"("topic", "createdAt");

ALTER TABLE "AppSetting" ADD COLUMN IF NOT EXISTS "dcOfficeEmails" TEXT[] DEFAULT ARRAY[]::TEXT[];
ALTER TABLE "AppSetting" ADD COLUMN IF NOT EXISTS "dcOfficeName" TEXT;

CREATE TABLE IF NOT EXISTS "DwReportDelivery" (
    "id" TEXT NOT NULL,
    "designWorkshopId" TEXT NOT NULL,
    "exportId" TEXT,
    "storageKey" TEXT NOT NULL,
    "fileName" TEXT NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "toAddresses" TEXT[],
    "officeName" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "revokedAt" TIMESTAMP(3),
    "firstOpenedAt" TIMESTAMP(3),
    "lastOpenedAt" TIMESTAMP(3),
    "openCount" INTEGER NOT NULL DEFAULT 0,
    "sentById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DwReportDelivery_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "DwReportDelivery_tokenHash_key" ON "DwReportDelivery"("tokenHash");
CREATE INDEX IF NOT EXISTS "DwReportDelivery_designWorkshopId_createdAt_idx" ON "DwReportDelivery"("designWorkshopId", "createdAt");
CREATE INDEX IF NOT EXISTS "DwReportDelivery_exportId_idx" ON "DwReportDelivery"("exportId");
CREATE INDEX IF NOT EXISTS "DwReportDelivery_sentById_idx" ON "DwReportDelivery"("sentById");

DO $$ BEGIN
    ALTER TABLE "DwReportDelivery" ADD CONSTRAINT "DwReportDelivery_designWorkshopId_fkey" FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE "DwReportDelivery" ADD CONSTRAINT "DwReportDelivery_exportId_fkey" FOREIGN KEY ("exportId") REFERENCES "DwReportExport"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE "DwReportDelivery" ADD CONSTRAINT "DwReportDelivery_sentById_fkey" FOREIGN KEY ("sentById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
