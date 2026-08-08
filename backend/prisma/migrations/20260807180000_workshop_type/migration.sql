-- What KIND of workshop a `Workshop` row records.
--
-- ADDITIVE AND DEFAULTED. A design & prototype workshop and an ordinary documentation workshop
-- were both `Workshop` rows with nothing to tell them apart, so a designer starting a 22-stage
-- record had to find the right one in a list holding every craft-documentation visit ever made.
-- Only a design-prototype workshop carries the sanction, the cluster and the dates that stage 1's
-- cover page is built from, so the two are not interchangeable.
--
-- Every existing row becomes OTHER, which is what they were implicitly. Nothing changes for any
-- reader that does not ask about the column, and rolling back is dropping it.

CREATE TYPE "WorkshopType" AS ENUM ('DESIGN_PROTOTYPE', 'OTHER');

ALTER TABLE "Workshop"
  ADD COLUMN "workshopType" "WorkshopType" NOT NULL DEFAULT 'OTHER';

-- The picker filters on the type and orders by date; one composite index serves both.
CREATE INDEX "Workshop_workshopType_startDate_idx" ON "Workshop" ("workshopType", "startDate");
