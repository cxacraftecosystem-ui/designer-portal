-- U5: THE FORM MIRROR. designer-portal's form authoring (create, edit, supersede, retire and delete of
-- sections and questions, workbook upload and re-upload, reuse, bundle import; web, Android and old
-- builds) still writes QuestionnaireFormSection and QuestionnaireFormQuestion, while the shared
-- package (sessions, coverage, answers, the record) reads QuestionnaireSection and
-- QuestionnaireQuestion. questionnaire_unify.py --apply copies every form across (ids kept) and
-- installs these triggers first, so from then on every write to the old tables lands in the new ones
-- in the same transaction, whatever wrote it, until authoring itself moves onto the package and the
-- cleanup migration (U-M3) drops the old tables with these triggers.
--
-- * a section: same id, code, title, isActive, timestamps; its position is the form's dense order
--   (sortOrder, createdAt, id), because the old table allows two sections at one position and the
--   new one holds one per form; a renamed section renames its questions' denormalised code/title;
-- * a question: same id and columns; a NEW question gets its lineage and next display code from the
--   U0 insert trigger, and a reword (supersededById set) hands the old code to the new wording through
--   the U0 update trigger, so the code stays frozen;
-- * a delete: the new row goes too, unless something in v2 points at it (a take, an answer, a draft),
--   in which case it is retired instead (isActive false, retiredAt now); nothing v2 holds is lost.
--
-- Idempotent (CREATE OR REPLACE, DROP TRIGGER IF EXISTS). Applied by --apply, never by deploy until
-- the switch-on commit moves it into prisma/migrations; --undo removes it when the run installed it.

CREATE OR REPLACE FUNCTION questionnaire_form_mirror_rank(form_id text) RETURNS void AS $$
BEGIN
  -- Two steps, so a permutation never trips the one-position-per-form unique mid-statement.
  -- The form's live sections in their order, then any kept only for v2 (retired on delete) after them.
  UPDATE "QuestionnaireSection" q SET "sortOrder" = -r.n
    FROM (SELECT i."id", ROW_NUMBER() OVER (ORDER BY (f."id" IS NULL), COALESCE(f."sortOrder", i."sortOrder"), i."createdAt", i."id") AS n
            FROM "QuestionnaireSection" i LEFT JOIN "QuestionnaireFormSection" f ON f."id" = i."id"
           WHERE i."questionnaireId" = form_id) r
   WHERE q."id" = r."id" AND q."sortOrder" IS DISTINCT FROM r.n;
  UPDATE "QuestionnaireSection" SET "sortOrder" = -"sortOrder"
   WHERE "questionnaireId" = form_id AND "sortOrder" < 0;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION questionnaire_form_section_mirror() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    BEGIN
      DELETE FROM "QuestionnaireSection" WHERE "id" = OLD."id";
    EXCEPTION WHEN foreign_key_violation THEN
      UPDATE "QuestionnaireSection" SET "isActive" = false, "updatedAt" = now() WHERE "id" = OLD."id";
    END;
    PERFORM questionnaire_form_mirror_rank(OLD."questionnaireId");
    RETURN OLD;
  END IF;
  INSERT INTO "QuestionnaireSection" ("id", "code", "title", "sortOrder", "isActive", "createdAt", "updatedAt", "questionnaireId")
  VALUES (NEW."id", NEW."code", NEW."title",
          -1000000 - (SELECT count(*) FROM "QuestionnaireSection" WHERE "questionnaireId" = NEW."questionnaireId"),
          NEW."isActive", NEW."createdAt", NEW."updatedAt", NEW."questionnaireId")
  ON CONFLICT ("id") DO UPDATE SET "code" = EXCLUDED."code", "title" = EXCLUDED."title", "isActive" = EXCLUDED."isActive",
                                   "updatedAt" = EXCLUDED."updatedAt", "questionnaireId" = EXCLUDED."questionnaireId";
  IF TG_OP = 'UPDATE' AND (NEW."code" IS DISTINCT FROM OLD."code" OR NEW."title" IS DISTINCT FROM OLD."title") THEN
    UPDATE "QuestionnaireQuestion" SET "sectionCode" = NEW."code", "sectionTitle" = NEW."title" WHERE "sectionId" = NEW."id";
  END IF;
  PERFORM questionnaire_form_mirror_rank(NEW."questionnaireId");
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION questionnaire_form_question_mirror() RETURNS trigger AS $$
DECLARE
  sec record;
BEGIN
  IF TG_OP = 'DELETE' THEN
    BEGIN
      DELETE FROM "QuestionnaireQuestion" WHERE "id" = OLD."id";
    EXCEPTION WHEN foreign_key_violation THEN
      UPDATE "QuestionnaireQuestion" SET "isActive" = false, "retiredAt" = COALESCE("retiredAt", now()), "updatedAt" = now()
       WHERE "id" = OLD."id";
    END;
    RETURN OLD;
  END IF;
  SELECT s."code", s."title", s."questionnaireId" INTO sec FROM "QuestionnaireFormSection" s WHERE s."id" = NEW."sectionId";
  IF TG_OP = 'INSERT' THEN
    INSERT INTO "QuestionnaireQuestion" ("id", "sectionCode", "sectionTitle", "prompt", "sortOrder", "isActive", "retiredAt",
                                         "supersededById", "createdAt", "updatedAt", "sectionId", "questionCode", "questionnaireId",
                                         "helpText", "isRequired")
    SELECT NEW."id", sec."code", sec."title", NEW."prompt", NEW."sortOrder", NEW."isActive", NEW."retiredAt", NEW."supersededById",
           NEW."createdAt", NEW."updatedAt", NEW."sectionId", NEW."questionCode", sec."questionnaireId", NEW."helpText", NEW."isRequired"
     WHERE EXISTS (SELECT 1 FROM "QuestionnaireSection" i WHERE i."id" = NEW."sectionId")  -- a form not copied yet: the apply copies it
    ON CONFLICT ("id") DO NOTHING;
    RETURN NEW;
  END IF;
  UPDATE "QuestionnaireQuestion" SET
      "sectionCode" = sec."code", "sectionTitle" = sec."title", "prompt" = NEW."prompt", "sortOrder" = NEW."sortOrder",
      "isActive" = NEW."isActive", "retiredAt" = NEW."retiredAt", "supersededById" = NEW."supersededById", "updatedAt" = NEW."updatedAt",
      "sectionId" = NEW."sectionId", "questionCode" = NEW."questionCode", "questionnaireId" = sec."questionnaireId",
      "helpText" = NEW."helpText", "isRequired" = NEW."isRequired"
   WHERE "id" = NEW."id";
  RETURN NEW;
END $$ LANGUAGE plpgsql;

-- A form deleted outright (old cleanup paths, account removal) takes its mirrored copy with it first,
-- so the old cascade is not stopped by the copy; anything v2 recorded against it still refuses.
CREATE OR REPLACE FUNCTION questionnaire_form_mirror_drop() RETURNS trigger AS $$
BEGIN
  DELETE FROM "QuestionnaireQuestion" WHERE "questionnaireId" = OLD."id";
  DELETE FROM "QuestionnaireSection" WHERE "questionnaireId" = OLD."id";
  RETURN OLD;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS questionnaire_form_mirror_drop ON "Questionnaire";
CREATE TRIGGER questionnaire_form_mirror_drop BEFORE DELETE ON "Questionnaire"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_form_mirror_drop();
DROP TRIGGER IF EXISTS questionnaire_form_section_mirror ON "QuestionnaireFormSection";
CREATE TRIGGER questionnaire_form_section_mirror AFTER INSERT OR UPDATE OR DELETE ON "QuestionnaireFormSection"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_form_section_mirror();
DROP TRIGGER IF EXISTS questionnaire_form_question_mirror ON "QuestionnaireFormQuestion";
CREATE TRIGGER questionnaire_form_question_mirror AFTER INSERT OR UPDATE OR DELETE ON "QuestionnaireFormQuestion"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_form_question_mirror();
