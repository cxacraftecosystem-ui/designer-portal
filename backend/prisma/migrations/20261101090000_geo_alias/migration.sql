-- COVERAGE MAP: place aliases an administrator saves for free-text state/district spellings.
-- Additive: one new table, nothing existing is changed, and no record table gains an LGD column
-- (codes are resolved when the map is read). Guarded, so a re-run changes nothing.

CREATE TABLE IF NOT EXISTS "GeoAlias" (
    "id" TEXT NOT NULL,
    "rawStateKey" TEXT NOT NULL,
    "rawDistrictKey" TEXT NOT NULL DEFAULT '',
    "stateLgd" INTEGER NOT NULL,
    "districtLgd" INTEGER,
    "createdById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "GeoAlias_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "GeoAlias_rawStateKey_rawDistrictKey_key"
    ON "GeoAlias"("rawStateKey", "rawDistrictKey");
CREATE INDEX IF NOT EXISTS "GeoAlias_createdById_idx" ON "GeoAlias"("createdById");

DO $$ BEGIN
    ALTER TABLE "GeoAlias" ADD CONSTRAINT "GeoAlias_createdById_fkey"
        FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
