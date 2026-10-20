-- QUESTIONNAIRE FORMS ARE SOFT-DELETED (owner, 2026-10-20). Additive: two nullable columns on each
-- table and one index. DELETE /questionnaires/{id} and DELETE /questionnaires/{id}/entries/{entryId}
-- now set "deletedAt"; a content admin's restore clears it. No row is rewritten.

ALTER TABLE "Questionnaire" ADD COLUMN "deletedAt" TIMESTAMP(3),
ADD COLUMN "deletedById" TEXT;

ALTER TABLE "QuestionnaireFormEntry" ADD COLUMN "deletedAt" TIMESTAMP(3),
ADD COLUMN "deletedById" TEXT;

CREATE INDEX "Questionnaire_deletedAt_idx" ON "Questionnaire"("deletedAt");
