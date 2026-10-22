-- U-M2 (UNIFY_2026-10-21.md section 3.1; design M3): a session that holds clips can no longer be
-- hard-deleted out from under them. Sessions are soft-deleted (content admins) and only the purge job
-- hard-deletes, so once every delete route refuses, the database refuses too. Applied after the DR
-- Android release (section 3.2, step 8), never by the switch-on commit. Idempotent.
ALTER TABLE "MediaFile" DROP CONSTRAINT IF EXISTS "MediaFile_questionnaireInterviewId_fkey";
ALTER TABLE "MediaFile" ADD CONSTRAINT "MediaFile_questionnaireInterviewId_fkey"
  FOREIGN KEY ("questionnaireInterviewId") REFERENCES "QuestionnaireInterview"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
