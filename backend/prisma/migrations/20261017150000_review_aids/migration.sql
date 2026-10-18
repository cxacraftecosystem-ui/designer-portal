-- Review aids for the approving authority and the inspectors (2026-10-17).
--
-- ADDITIVE ONLY, safe on a live database: two new tables, two nullable columns, one index. Nothing is
-- backfilled. `IF NOT EXISTS` throughout because the migration runner can be interrupted and re-run.
--
--   DwInspectionClearance  "Checked, no corrections" — one row per inspector per round (C5).
--   DwRoundSnapshot        the stage data as handed in, per round, for "Changed since round N" (C13).
--   DwInspectionFeedback.actorPost        the oversight post an Assistant or Regional Director filed a
--                                         note from (C16); null for every earlier row.
--   DwReportExport.inputFingerprint       what a stored report file was built from, so an unchanged
--                                         report is served again rather than rebuilt (A22).

CREATE TABLE IF NOT EXISTS "DwInspectionClearance" (
    "id" TEXT NOT NULL,
    "designWorkshopId" TEXT NOT NULL,
    "inspectorId" TEXT NOT NULL,
    "round" INTEGER NOT NULL,
    "clearedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DwInspectionClearance_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "DwInspectionClearance_designWorkshopId_inspectorId_round_key"
    ON "DwInspectionClearance"("designWorkshopId", "inspectorId", "round");
CREATE INDEX IF NOT EXISTS "DwInspectionClearance_designWorkshopId_round_idx"
    ON "DwInspectionClearance"("designWorkshopId", "round");
CREATE INDEX IF NOT EXISTS "DwInspectionClearance_inspectorId_idx"
    ON "DwInspectionClearance"("inspectorId");

DO $$ BEGIN
    ALTER TABLE "DwInspectionClearance" ADD CONSTRAINT "DwInspectionClearance_designWorkshopId_fkey"
        FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE "DwInspectionClearance" ADD CONSTRAINT "DwInspectionClearance_inspectorId_fkey"
        FOREIGN KEY ("inspectorId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS "DwRoundSnapshot" (
    "id" TEXT NOT NULL,
    "designWorkshopId" TEXT NOT NULL,
    "round" INTEGER NOT NULL,
    "takenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "stages" JSONB NOT NULL,

    CONSTRAINT "DwRoundSnapshot_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "DwRoundSnapshot_designWorkshopId_round_key"
    ON "DwRoundSnapshot"("designWorkshopId", "round");

DO $$ BEGIN
    ALTER TABLE "DwRoundSnapshot" ADD CONSTRAINT "DwRoundSnapshot_designWorkshopId_fkey"
        FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

ALTER TABLE "DwInspectionFeedback" ADD COLUMN IF NOT EXISTS "actorPost" TEXT;

ALTER TABLE "DwReportExport" ADD COLUMN IF NOT EXISTS "inputFingerprint" TEXT;
CREATE INDEX IF NOT EXISTS "DwReportExport_designWorkshopId_inputFingerprint_idx"
    ON "DwReportExport"("designWorkshopId", "inputFingerprint");
