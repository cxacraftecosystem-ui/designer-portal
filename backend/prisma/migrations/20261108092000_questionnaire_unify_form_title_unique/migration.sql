-- U5 (UNIFY_2026-10-21.md section 2.1): a title is unique among the SHARED, live forms only. Designers
-- may reuse a title on their private forms. The same index, by the same name, in both portals
-- (documentation-portal drops its global "Questionnaire_title_key" in its copy of this file).
-- Idempotent: questionnaire_unify.py --apply runs it first (its dry run lists any clash, which blocks
-- the apply), and the switch-on commit moves this folder into prisma/migrations.
CREATE UNIQUE INDEX IF NOT EXISTS "Questionnaire_shared_title_key"
  ON "Questionnaire"("title") WHERE "isShared" AND "deletedAt" IS NULL;
