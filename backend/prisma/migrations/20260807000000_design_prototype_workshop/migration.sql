-- The Design & Prototype Workshop record: a 22-stage capture, its stage entries, and the
-- reports generated from it.
--
-- Additive only. Nothing here alters or drops an existing table; the two ALTERs at the end add
-- back-relation columns to no table at all (Prisma back-relations are virtual), so this
-- migration is safe to apply to a populated production database and safe to roll back by
-- dropping the three tables and the enum.
--
-- WHY THE SHAPE IS WHAT IT IS. `DwStageEntry.data` is a jsonb document keyed by the field keys
-- declared in app/services/stage_schema.py, and the columns on `DesignWorkshop` are an INDEX
-- over that document rather than a second copy of it. Two and a half thousand typed columns
-- would make every new Standard-tier field a migration in the middle of a workshop season, and
-- the source requirements document is explicit that the field list is not finished ("some of
-- these could be optional fields", "later we can refine"). A pure document, on the other hand,
-- cannot answer "every Ikat workshop in Odisha in 2026" without reading every row. See the
-- comment above the model in schema.prisma.

-- CreateEnum
CREATE TYPE "DesignWorkshopStatus" AS ENUM ('DRAFT', 'IN_PROGRESS', 'COMPLETE', 'SUBMITTED', 'ARCHIVED');

-- CreateTable
CREATE TABLE "DesignWorkshop" (
    "id" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "templateId" TEXT NOT NULL DEFAULT 'DCH_STANDARD',
    "status" "DesignWorkshopStatus" NOT NULL DEFAULT 'DRAFT',
    "workshopCode" TEXT,
    "scheme" TEXT,
    "craftName" TEXT,
    "clusterName" TEXT,
    "state" TEXT,
    "district" TEXT,
    "venue" TEXT,
    "startDate" TIMESTAMP(3),
    "endDate" TIMESTAMP(3),
    "designerName" TEXT,
    "implementingAgency" TEXT,
    "sponsor" TEXT,
    "notes" TEXT,
    "schemaVersion" TEXT,
    "deletedAt" TIMESTAMP(3),
    "deletedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "createdById" TEXT NOT NULL,
    "workshopId" TEXT,

    CONSTRAINT "DesignWorkshop_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DwStageEntry" (
    "id" TEXT NOT NULL,
    "designWorkshopId" TEXT NOT NULL,
    "stageKey" TEXT NOT NULL,
    "entityKey" TEXT NOT NULL,
    "ordinal" INTEGER NOT NULL DEFAULT 0,
    "data" JSONB NOT NULL,
    "clientKey" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "createdById" TEXT,
    "deletedAt" TIMESTAMP(3),

    CONSTRAINT "DwStageEntry_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DwReportExport" (
    "id" TEXT NOT NULL,
    "designWorkshopId" TEXT NOT NULL,
    "format" TEXT NOT NULL,
    "templateId" TEXT NOT NULL,
    "fileName" TEXT NOT NULL,
    "fileSizeBytes" INTEGER,
    "pageCount" INTEGER,
    "checksumSha256" TEXT,
    "generatedOnDevice" BOOLEAN NOT NULL DEFAULT false,
    "schemaVersion" TEXT,
    "warnings" TEXT,
    "storageKey" TEXT,
    "generatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "generatedById" TEXT,

    CONSTRAINT "DwReportExport_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
-- The list's default order, and the filters a researcher reaches for first. Every one of these
-- reads a promoted column, which is what those columns are for.
CREATE INDEX "DesignWorkshop_createdAt_idx" ON "DesignWorkshop"("createdAt");
CREATE INDEX "DesignWorkshop_startDate_idx" ON "DesignWorkshop"("startDate");
CREATE INDEX "DesignWorkshop_createdById_idx" ON "DesignWorkshop"("createdById");
CREATE INDEX "DesignWorkshop_status_idx" ON "DesignWorkshop"("status");
CREATE INDEX "DesignWorkshop_craftName_idx" ON "DesignWorkshop"("craftName");
CREATE INDEX "DesignWorkshop_state_district_idx" ON "DesignWorkshop"("state", "district");
CREATE INDEX "DesignWorkshop_workshopId_idx" ON "DesignWorkshop"("workshopId");
CREATE INDEX "DesignWorkshop_deletedAt_idx" ON "DesignWorkshop"("deletedAt");

-- CreateIndex
-- The read pattern is "every row of one stage of one workshop, in order", which this covers
-- exactly; the second serves the entity-scoped reads the report builder makes.
CREATE INDEX "DwStageEntry_designWorkshopId_stageKey_ordinal_idx" ON "DwStageEntry"("designWorkshopId", "stageKey", "ordinal");
CREATE INDEX "DwStageEntry_designWorkshopId_entityKey_idx" ON "DwStageEntry"("designWorkshopId", "entityKey");
CREATE INDEX "DwStageEntry_deletedAt_idx" ON "DwStageEntry"("deletedAt");

-- CreateIndex
-- The offline-sync idempotency key. A row created on a phone in a village with no signal keeps
-- this id across the sync, which is what stops every reconnect duplicating the collection.
-- Postgres treats NULLs as distinct here, so the many rows created on the web (which sets no
-- clientKey) do not collide with each other.
CREATE UNIQUE INDEX "DwStageEntry_designWorkshopId_entityKey_clientKey_key" ON "DwStageEntry"("designWorkshopId", "entityKey", "clientKey");

-- CreateIndex
CREATE INDEX "DwReportExport_designWorkshopId_generatedAt_idx" ON "DwReportExport"("designWorkshopId", "generatedAt");
CREATE INDEX "DwReportExport_generatedById_idx" ON "DwReportExport"("generatedById");

-- AddForeignKey
-- createdBy is RESTRICT: a user who ran a workshop cannot be deleted out from under the record,
-- matching every other authored record in this schema. deletedBy and generatedBy are SET NULL
-- because they are audit fields, and losing who archived something is preferable to blocking
-- the removal of a departed account.
ALTER TABLE "DesignWorkshop" ADD CONSTRAINT "DesignWorkshop_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "DesignWorkshop" ADD CONSTRAINT "DesignWorkshop_deletedById_fkey" FOREIGN KEY ("deletedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "DesignWorkshop" ADD CONSTRAINT "DesignWorkshop_workshopId_fkey" FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
-- CASCADE from the workshop: a stage entry has no meaning without its workshop, and the
-- workshop itself is soft-deleted, so this only ever fires on a deliberate hard purge.
ALTER TABLE "DwStageEntry" ADD CONSTRAINT "DwStageEntry_designWorkshopId_fkey" FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DwStageEntry" ADD CONSTRAINT "DwStageEntry_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DwReportExport" ADD CONSTRAINT "DwReportExport_designWorkshopId_fkey" FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DwReportExport" ADD CONSTRAINT "DwReportExport_generatedById_fkey" FOREIGN KEY ("generatedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
