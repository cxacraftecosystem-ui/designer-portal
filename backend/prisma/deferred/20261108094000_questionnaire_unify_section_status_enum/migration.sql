-- U5 (owner, amendment 2 of 22 Oct 2026, point 1): the admin section override keeps its three states,
-- and DR's rows migrate as they are. The text column becomes the shared SectionStatus enum. The U0
-- CHECK (NOT VALID) has held every row written since; questionnaire_unify.py's dry run counts any
-- older row outside the three states, and this cast fails loudly rather than guess if one remains.
-- Moved into prisma/migrations by the switch-on commit, together with `status SectionStatus` in
-- schema.prisma. Idempotent.
DO $$
BEGIN
  IF (SELECT data_type FROM information_schema.columns
       WHERE table_schema = current_schema() AND table_name = 'QuestionnaireSectionStatus' AND column_name = 'status') = 'text' THEN
    ALTER TABLE "QuestionnaireSectionStatus" DROP CONSTRAINT IF EXISTS "QuestionnaireSectionStatus_three_states";
    ALTER TABLE "QuestionnaireSectionStatus"
      ALTER COLUMN "status" TYPE "SectionStatus" USING "status"::"SectionStatus";
  END IF;
END $$;
