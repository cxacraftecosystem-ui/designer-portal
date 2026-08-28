-- Let every repository record type be filed under a Design & Prototype Workshop.
--
-- =============================================================================================
-- WHAT THE OWNER ASKED FOR, 2026-08-28
-- =============================================================================================
--
-- "Ensure that all of the following record types can be linked to a Design and Prototype Workshop:
--  Artisans, Products, Process, Tools, Questionnaires, Miscellaneous Media, Consolidated
--  Questionnaires."
--
-- Seven names, six columns. "Questionnaires" is already served by "Questionnaire"."designWorkshopId"
-- — the designer-authored form, linked since that table was written — and the CONSOLIDATED
-- questionnaire stores nothing of its own: it reads one artisan's answers back out of every
-- interview they sat in, so scoping it means scoping "QuestionnaireInterview", which is one of the
-- six below. Nothing else is needed for it and nothing else would work.
--
-- =============================================================================================
-- WHY A SECOND COLUMN RATHER THAN REUSING "workshopId"
-- =============================================================================================
--
-- The link was ALREADY EXPRESSIBLE and was not usable. "Workshop"."workshopType" has a
-- DESIGN_PROTOTYPE member and "DesignWorkshop"."workshopId" optionally points at such a row, so a
-- record could reach a design workshop in two hops. Three facts about the data stop that being an
-- answer:
--
--  1. THE HOP IS OPTIONAL AT BOTH ENDS. "DesignWorkshop"."workshopId" is nullable and its own schema
--     comment calls the link an optimisation. A designer who opened a design workshop without first
--     creating a "Workshop" row — the ordinary case, since nothing asks them to — has nothing for a
--     record to point at.
--  2. IT IS NOT ONE-TO-ONE. Nothing stops two design workshops naming one "Workshop", so "which
--     design workshop is this artisan filed under" would have no single answer.
--  3. THE TWO SCOPES ARE ENFORCED BY DIFFERENT MACHINERY — resolve_workshop_access over
--     "WorkshopAssignment" for one, load_workshop_or_404 over creator/"DesignWorkshopViewer" for the
--     other. One column carrying both meanings is how a scope comes to be checked by whichever of
--     the two the caller happened to remember.
--
-- =============================================================================================
-- WHY THIS IS SAFE TO APPLY
-- =============================================================================================
--
-- Every column is ADDITIVE and NULLABLE, so no existing row changes and no existing query breaks. A
-- client that has never heard of the column is unaffected, which matters on this product because a
-- field handset runs offline for a fortnight at a time and may be several releases behind.
--
-- ON DELETE SET NULL, NEVER CASCADE, on all six — the rule "Questionnaire"."designWorkshopId" states
-- in its own words: deleting a workshop must not take a fortnight of recorded fieldwork with it. A
-- design workshop is additionally soft-deleted ("deletedAt"), so the hard-delete path this guards is
-- rare; it is spelled out anyway rather than left to the provider's default.
--
-- ON UPDATE CASCADE is Prisma's default for a relation and is written explicitly here so the
-- generated client and the database agree. Ids are cuids and are never updated, so it never fires.
--
-- =============================================================================================
-- THE INDEXES
-- =============================================================================================
--
-- One composite per table, ("designWorkshopId", "createdAt"), mirroring the ("workshopId",
-- "createdAt") pair each of these tables already carries and for the identical reason recorded in
-- 20260726200000_index_coverage: every record list is ORDER BY "createdAt" DESC LIMIT n, so a
-- workshop-scoped page reads n rows straight off the index instead of sorting the whole workshop.
-- Equality column first, then the sort column.
--
-- The composite also serves the foreign key's own reverse walk: SET NULL on delete makes Postgres
-- find every row naming the workshop, and "designWorkshopId" is this index's leading column, so no
-- separate single-column index is needed.

ALTER TABLE "Artisan" ADD COLUMN "designWorkshopId" TEXT;
ALTER TABLE "ProductDocumentation" ADD COLUMN "designWorkshopId" TEXT;
ALTER TABLE "ToolDocumentation" ADD COLUMN "designWorkshopId" TEXT;
ALTER TABLE "Process" ADD COLUMN "designWorkshopId" TEXT;
ALTER TABLE "QuestionnaireInterview" ADD COLUMN "designWorkshopId" TEXT;
ALTER TABLE "MediaFile" ADD COLUMN "designWorkshopId" TEXT;

ALTER TABLE "Artisan"
  ADD CONSTRAINT "Artisan_designWorkshopId_fkey"
  FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id")
  ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "ProductDocumentation"
  ADD CONSTRAINT "ProductDocumentation_designWorkshopId_fkey"
  FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id")
  ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "ToolDocumentation"
  ADD CONSTRAINT "ToolDocumentation_designWorkshopId_fkey"
  FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id")
  ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "Process"
  ADD CONSTRAINT "Process_designWorkshopId_fkey"
  FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id")
  ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "QuestionnaireInterview"
  ADD CONSTRAINT "QuestionnaireInterview_designWorkshopId_fkey"
  FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id")
  ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "MediaFile"
  ADD CONSTRAINT "MediaFile_designWorkshopId_fkey"
  FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id")
  ON DELETE SET NULL ON UPDATE CASCADE;

CREATE INDEX "Artisan_designWorkshopId_createdAt_idx"
  ON "Artisan"("designWorkshopId", "createdAt");
CREATE INDEX "ProductDocumentation_designWorkshopId_createdAt_idx"
  ON "ProductDocumentation"("designWorkshopId", "createdAt");
CREATE INDEX "ToolDocumentation_designWorkshopId_createdAt_idx"
  ON "ToolDocumentation"("designWorkshopId", "createdAt");
CREATE INDEX "Process_designWorkshopId_createdAt_idx"
  ON "Process"("designWorkshopId", "createdAt");
CREATE INDEX "QuestionnaireInterview_designWorkshopId_createdAt_idx"
  ON "QuestionnaireInterview"("designWorkshopId", "createdAt");
CREATE INDEX "MediaFile_designWorkshopId_createdAt_idx"
  ON "MediaFile"("designWorkshopId", "createdAt");
