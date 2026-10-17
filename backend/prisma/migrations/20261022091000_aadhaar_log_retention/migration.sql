-- Regulation 18(3) log retention (services/aadhaar/log_retention.py): the ledger of archives
-- written to the encrypted backup bucket, and legal holds that suspend archiving and deletion.

CREATE TABLE "AadhaarLogArchive" (
    "id" TEXT NOT NULL,
    "objectKey" TEXT NOT NULL,
    "sha256" TEXT NOT NULL,
    "entryCount" INTEGER NOT NULL,
    "oldestAt" TIMESTAMP(3) NOT NULL,
    "newestAt" TIMESTAMP(3) NOT NULL,
    "recordKeys" TEXT[],
    "archivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleteAfter" TIMESTAMP(3) NOT NULL,
    "deletedAt" TIMESTAMP(3),

    CONSTRAINT "AadhaarLogArchive_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "AadhaarLogArchive_objectKey_key" ON "AadhaarLogArchive"("objectKey");
CREATE INDEX "AadhaarLogArchive_deleteAfter_idx" ON "AadhaarLogArchive"("deleteAfter");

CREATE TABLE "AadhaarLegalHold" (
    "id" TEXT NOT NULL,
    "recordType" TEXT NOT NULL,
    "recordId" TEXT NOT NULL,
    "reason" TEXT NOT NULL,
    "placedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "releasedAt" TIMESTAMP(3),
    "releasedById" TEXT,

    CONSTRAINT "AadhaarLegalHold_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "AadhaarLegalHold_recordType_recordId_idx" ON "AadhaarLegalHold"("recordType", "recordId");
