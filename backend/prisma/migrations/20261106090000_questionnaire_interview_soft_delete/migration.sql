-- QUESTIONNAIRE INTERVIEWS ARE SOFT-DELETED (audit H5, 21 Oct 2026), as custom forms and sittings have
-- been since 20 Oct. Additive: two nullable columns and one index. DELETE /questionnaire/interviews/{id}
-- now sets "deletedAt" (and frees "artisanSetKey"); a content admin's restore clears it. No row is
-- rewritten.

ALTER TABLE "QuestionnaireInterview" ADD COLUMN "deletedAt" TIMESTAMP(3),
ADD COLUMN "deletedById" TEXT;

CREATE INDEX "QuestionnaireInterview_deletedAt_idx" ON "QuestionnaireInterview"("deletedAt");
