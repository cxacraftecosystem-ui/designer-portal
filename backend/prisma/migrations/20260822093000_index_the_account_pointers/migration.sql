-- The twelve User pointers that had no index, indexed. Additive; nothing else changes.
--
-- WHAT THIS CLOSES IS AN INCONSISTENCY, NOT A SLOW QUERY, and it is worth saying that first because
-- an index migration reads as a performance change and this one is not. The schema states the rule
-- in three places — `DwAiLayer` ("the three account pointers, indexed for the WRITE they are on the
-- wrong end of rather than for any read this step performs"), `DwAiLayerDecision.actorId` and
-- `DwWorkshopConsentDecision.actorId` — and follows it in four, `DwCustomSection.createdById` being
-- the fourth. Twelve columns of the same kind did not have it, including `DwStageEntry.createdById`
-- on the largest table in the design-workshop family. A reader comparing the models could only
-- conclude the rule had an unwritten exemption and go looking for what it was.
--
-- THE TWELVE, and how they were found rather than remembered: every field in schema.prisma whose
-- `@relation` targets `User`, whose foreign-key column is not the leading column of any `@@index`,
-- `@@unique` or `@@id` and does not carry a field-level `@unique`, and which declares an `onDelete`
-- action. Counted 2026-08-22, before this migration: twelve. After it: zero.
--
--     AppRelease.publishedById              SetNull
--     Craft.createdById                     SetNull
--     QuestionnaireSectionStatus.setById    Restrict
--     WorkshopAssignment.assignedById       SetNull
--     WorkshopAssignment.requestedById      SetNull
--     WorkshopAssignment.decidedById        SetNull
--     ManagedSecret.updatedById             SetNull
--     DataAccessGrant.requestedById         SetNull
--     DataAccessGrant.decidedById           SetNull
--     DesignWorkshop.deletedById            SetNull
--     DesignWorkshopViewer.grantedById      SetNull
--     DwStageEntry.createdById              SetNull
--
-- The five remaining User pointers (Feedback, UserPreference, DesignerProfile,
-- DwDictationDailyUsage, DwAiVerbDailyUsage) are already covered — a field-level `@unique` or an
-- `@@id` whose leading column is `userId` builds the index this needs — so they are not here.
--
-- WHAT AN UNINDEXED ONE COSTS. `onDelete: SetNull` and `onDelete: Restrict` are both enforced by a
-- trigger that has to FIND the referencing rows when a `User` row is deleted; with no index that is
-- a sequential scan of the referencing table. At today's volumes that is milliseconds per table —
-- `DwStageEntry` held 6,952 rows when `@@index([entityKey])` was measured — and deleting an account
-- is a rare administrative action. **No claim is made here that anybody will feel this.** No claim
-- is made either about what a delete does when it hits a `Restrict` relation first: the order in
-- which Postgres evaluates the constraint set is not something a schema controls.
--
-- WHAT IT COSTS, honestly: one extra btree insert per row inserted into each of these ten tables.
-- Only `DwStageEntry` is on a hot write path (every stage save), and it already carries five
-- indexes; the other nine are small or rarely written.
--
-- PURELY ADDITIVE AND FULLY REVERSIBLE. No column, constraint, default or row is touched, and an
-- index cannot change what a query RETURNS — only how fast it is found. Rolling back is a DROP
-- INDEX per line. Every statement is IF NOT EXISTS, so running this twice is a no-op.
--
-- HOW TO APPLY THIS ONE, exactly as 20260726200000_index_coverage and
-- 20260808140000_dw_stage_entry_entity_key_index do it. There are two files in this directory and
-- they do the same work:
--
--   apply_concurrently.sql  run against production by hand, BEFORE deploying
--   migration.sql           this file, which `prisma migrate deploy` runs during the deploy
--
-- They are a pair because CREATE INDEX CONCURRENTLY cannot run inside a transaction block and
-- `prisma migrate deploy` sends a migration file as one implicitly-transacted multi-statement
-- query — it fails the deploy with ERROR 25001 / Prisma P3018 rather than degrading. See the long
-- note in 20260726200000_index_coverage/migration.sql. Running only this file is still correct; it
-- just takes a brief lock per index while it builds.

-- CreateIndex
CREATE INDEX IF NOT EXISTS "AppRelease_publishedById_idx"           ON "AppRelease"("publishedById");
CREATE INDEX IF NOT EXISTS "Craft_createdById_idx"                  ON "Craft"("createdById");
CREATE INDEX IF NOT EXISTS "QuestionnaireSectionStatus_setById_idx" ON "QuestionnaireSectionStatus"("setById");
CREATE INDEX IF NOT EXISTS "WorkshopAssignment_assignedById_idx"    ON "WorkshopAssignment"("assignedById");
CREATE INDEX IF NOT EXISTS "WorkshopAssignment_requestedById_idx"   ON "WorkshopAssignment"("requestedById");
CREATE INDEX IF NOT EXISTS "WorkshopAssignment_decidedById_idx"     ON "WorkshopAssignment"("decidedById");
CREATE INDEX IF NOT EXISTS "ManagedSecret_updatedById_idx"          ON "ManagedSecret"("updatedById");
CREATE INDEX IF NOT EXISTS "DataAccessGrant_requestedById_idx"      ON "DataAccessGrant"("requestedById");
CREATE INDEX IF NOT EXISTS "DataAccessGrant_decidedById_idx"        ON "DataAccessGrant"("decidedById");
CREATE INDEX IF NOT EXISTS "DesignWorkshop_deletedById_idx"         ON "DesignWorkshop"("deletedById");
CREATE INDEX IF NOT EXISTS "DesignWorkshopViewer_grantedById_idx"   ON "DesignWorkshopViewer"("grantedById");
CREATE INDEX IF NOT EXISTS "DwStageEntry_createdById_idx"           ON "DwStageEntry"("createdById");
