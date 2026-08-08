-- The DwStageEntry cross-workshop index, built WITHOUT locking the table. Run this by hand against
-- production BEFORE deploying; then deploy normally and let `prisma migrate deploy` run
-- migration.sql, whose one statement is IF NOT EXISTS and therefore a no-op over the work done here.
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
-- WHAT IT COSTS. The build scans the table twice and waits out older transactions, so it is slower
-- in wall clock than a plain build — but it takes only SHARE UPDATE EXCLUSIVE, so stage saves keep
-- working throughout. That is the trade this deployment wants: one small instance, no second node.
--
-- IF THE BUILD IS INTERRUPTED it leaves an invalid index behind, which costs writes and serves no
-- reads. Find and remove any before re-running:
--
--     SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;
--     DROP INDEX CONCURRENTLY "DwStageEntry_entityKey_idx";

CREATE INDEX CONCURRENTLY IF NOT EXISTS "DwStageEntry_entityKey_idx" ON "DwStageEntry"("entityKey");

-- Confirm afterwards: this should return no rows.
--   SELECT indexrelid::regclass AS invalid_index FROM pg_index WHERE NOT indisvalid;
