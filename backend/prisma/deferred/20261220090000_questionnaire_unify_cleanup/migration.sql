-- U-M3 (UNIFY_2026-10-21.md section 3.1): drop what the unification replaced. ONLY after the telemetry
-- gate (section 4.5: AppClientVersion shows no old build for 30 days), after the shims are removed,
-- after questionnaire_unify.py's run snapshot schema has been dropped by the owner, and once no reader
-- in app/ names these tables any more (on 22 Oct 2026 the old routes, tasks, completeness and the
-- search trigger on QuestionnaireResponse still did; check `grep -rn QuestionnaireResponse backend/app`). Then: the forms' sections, questions, sittings and answers live in the instrument
-- and session tables (ids kept), and the binding table replaced the attachment column.
-- Idempotent. Never moved into prisma/migrations by the switch-on commit.
DROP TRIGGER IF EXISTS questionnaire_binding_from_form ON "Questionnaire";
DROP TRIGGER IF EXISTS questionnaire_form_from_binding ON "QuestionnaireBinding";
DROP FUNCTION IF EXISTS questionnaire_binding_from_form();
DROP FUNCTION IF EXISTS questionnaire_form_from_binding();
DROP TABLE IF EXISTS "QuestionnaireFormAnswer";
DROP TABLE IF EXISTS "QuestionnaireFormEntry";
DROP TABLE IF EXISTS "QuestionnaireFormQuestion";
DROP TABLE IF EXISTS "QuestionnaireFormSection";
DROP TABLE IF EXISTS "QuestionnaireResponse";
DROP TABLE IF EXISTS "QuestionnaireAnswerDraft";
ALTER TABLE "Questionnaire" DROP COLUMN IF EXISTS "designWorkshopId";
