-- The twelve account-pointer indexes, built WITHOUT locking the tables. Run this by hand against
-- production BEFORE deploying; then deploy normally and let `prisma migrate deploy` run
-- migration.sql, every statement of which is IF NOT EXISTS and therefore a no-op over the work
-- done here.
--
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f apply_concurrently.sql
--
-- WHY A SEPARATE FILE. Postgres refuses CREATE INDEX CONCURRENTLY inside a transaction block, and
-- `prisma migrate deploy` sends a migration file as one multi-statement query wrapped in an
-- implicit transaction. It does not degrade to a plain build — it fails the deploy with
-- ERROR 25001 / Prisma P3018 and leaves a failed row in _prisma_migrations that blocks every later
-- migration until somebody runs `prisma migrate resolve`. psql sends each statement separately, in
-- its own implicit transaction, so CONCURRENTLY is legal here. Do not wrap this file in
-- BEGIN/COMMIT and do not run it through a tool that does. The full reasoning, reproduced against
-- this schema rather than taken on trust, is in 20260726200000_index_coverage/apply_concurrently.sql.
--
-- WHAT IT COSTS. Each build scans its table twice and waits out older transactions, so it is slower
-- in wall clock than a plain build — but it takes only SHARE UPDATE EXCLUSIVE, so stage saves,
-- grants and uploads keep working throughout. That is the trade this deployment wants: one small
-- instance, no second node. `DwStageEntry` is the only large table in the list.
--
-- IF A BUILD IS INTERRUPTED it leaves an invalid index behind, which costs writes and serves no
-- reads. Find and remove any before re-running:
--
--     SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;
--     DROP INDEX CONCURRENTLY "<the one it named>";

CREATE INDEX CONCURRENTLY IF NOT EXISTS "AppRelease_publishedById_idx"           ON "AppRelease"("publishedById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "Craft_createdById_idx"                  ON "Craft"("createdById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "QuestionnaireSectionStatus_setById_idx" ON "QuestionnaireSectionStatus"("setById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "WorkshopAssignment_assignedById_idx"    ON "WorkshopAssignment"("assignedById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "WorkshopAssignment_requestedById_idx"   ON "WorkshopAssignment"("requestedById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "WorkshopAssignment_decidedById_idx"     ON "WorkshopAssignment"("decidedById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "ManagedSecret_updatedById_idx"          ON "ManagedSecret"("updatedById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "DataAccessGrant_requestedById_idx"      ON "DataAccessGrant"("requestedById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "DataAccessGrant_decidedById_idx"        ON "DataAccessGrant"("decidedById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "DesignWorkshop_deletedById_idx"         ON "DesignWorkshop"("deletedById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "DesignWorkshopViewer_grantedById_idx"   ON "DesignWorkshopViewer"("grantedById");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "DwStageEntry_createdById_idx"           ON "DwStageEntry"("createdById");

-- Confirm afterwards: this should return no rows.
--   SELECT indexrelid::regclass AS invalid_index FROM pg_index WHERE NOT indisvalid;
