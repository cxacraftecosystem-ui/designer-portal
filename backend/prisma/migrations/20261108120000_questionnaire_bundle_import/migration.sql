-- UNIFY A10: a form imported from a handoff bundle remembers the bundle, so a second import of one
-- file by one person returns the first copy. The same column and key as documentation-portal.
ALTER TABLE "Questionnaire" ADD COLUMN IF NOT EXISTS "importedBundleId" TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS "Questionnaire_importedBundleId_ownerId_key" ON "Questionnaire"("importedBundleId", "ownerId");
