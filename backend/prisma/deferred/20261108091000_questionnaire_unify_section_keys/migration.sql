-- U5 (UNIFY_2026-10-21.md section 2.1): a section's code and position are unique WITHIN ITS FORM.
-- The global keys came from the one global instrument; the designers' forms all have a section "A".
-- Idempotent: questionnaire_unify.py --apply runs it before it copies the forms' sections, and the
-- switch-on commit moves this folder into prisma/migrations (a no-op there).
DROP INDEX IF EXISTS "QuestionnaireSection_code_key";
DROP INDEX IF EXISTS "QuestionnaireSection_sortOrder_key";
CREATE UNIQUE INDEX IF NOT EXISTS "QuestionnaireSection_questionnaireId_sortOrder_key"
  ON "QuestionnaireSection"("questionnaireId", "sortOrder");
