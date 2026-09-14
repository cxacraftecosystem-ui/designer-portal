-- THE ARTISAN-LIST IMPORT LEDGER: one row per accepted upload, and what it did.
--
-- ADDITIVE ONLY. It exists because the upload's own response — the counts and the per-row problem
-- list — is read once, by the officer who pressed the button, and the question it answers ("where
-- did these fourteen artisans come from, and which rows were refused") is asked months later by
-- somebody holding a ministry document. The artisan rows themselves carry `createdById` and nothing
-- about the spreadsheet, so without this table the answer does not exist anywhere.
--
-- THE ROW IS WRITTEN BEFORE ANY ARTISAN IS, AND THAT ORDERING IS THE POINT. The importer creates
-- this row first and updates its counts at the end, so a deployment where this table is missing
-- fails on the importer's FIRST write: the upload 500s having changed nothing, rather than creating
-- fifteen artisans and then failing to record that it did.
--
-- THE WORKBOOK IS NOT STORED, ONLY ITS FILENAME, and that is a PII decision rather than a size one:
-- an artisan list carries Aadhaar numbers, and keeping the bytes would turn one regulated COLUMN
-- into a regulated FILE with its own retention, access and deletion questions. The parse is
-- transient, exactly as `parse_questionnaire_workbook`'s is, and the bytes are dropped as soon as
-- it returns.
--
-- `problems` IS JSONB HOLDING THE SAME SHAPE THE RESPONSE CARRIES — a list of
-- {sheet, row, severity, reason, value}. `value` is ALREADY MASKED by the importer for any identity
-- number (services/artisan_identity.mask_aadhaar); nothing on the read path re-masks it, because a
-- mask applied in two places is a mask that can be forgotten in one.
--
-- NO INDEX ON `problems` AND NONE ON `sourceFilename`. Nothing queries either; the one read is this
-- workshop's imports newest-first, which is the composite below.

CREATE TABLE "DwArtisanImport" (
    "id" TEXT NOT NULL,
    "designWorkshopId" TEXT NOT NULL,
    "uploadedById" TEXT,
    "sourceFilename" TEXT,
    "sheetName" TEXT,
    "rowsRead" INTEGER NOT NULL DEFAULT 0,
    "artisansCreated" INTEGER NOT NULL DEFAULT 0,
    "artisansLinked" INTEGER NOT NULL DEFAULT 0,
    "rowsRefused" INTEGER NOT NULL DEFAULT 0,
    "participantsCreated" INTEGER NOT NULL DEFAULT 0,
    "problems" JSONB NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DwArtisanImport_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "DwArtisanImport_designWorkshopId_createdAt_idx" ON "DwArtisanImport"("designWorkshopId", "createdAt");
CREATE INDEX "DwArtisanImport_uploadedById_idx" ON "DwArtisanImport"("uploadedById");

ALTER TABLE "DwArtisanImport" ADD CONSTRAINT "DwArtisanImport_designWorkshopId_fkey"
    FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DwArtisanImport" ADD CONSTRAINT "DwArtisanImport_uploadedById_fkey"
    FOREIGN KEY ("uploadedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
