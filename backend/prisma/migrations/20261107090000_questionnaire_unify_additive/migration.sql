-- QUESTIONNAIRE UNIFICATION, U-M0 (C:/dev/IIT/docs/questionnaire/UNIFY_2026-10-21.md section 3.1),
-- designer-portal's half: the questionnaire v2 tables, as documentation-portal has them, so the
-- shared package (backend/app/questionnaire) can run here. documentation-portal's half is the
-- migration of the same name there; every table created here has the same columns, keys and
-- checks, and the model blocks in schema.prisma are byte-identical (shared/questionnaire.prisma,
-- tests/test_questionnaire_schema_parity.py).
--
-- ADDITIVE AND DARK. New enums, new tables, new nullable or defaulted columns, new indexes, one
-- view and four triggers. NOTHING READS OR WRITES THE NEW TABLES until QUESTIONNAIRE_V2 is switched
-- on here (it is off, and no route is mounted yet: U1). designer-portal's own forms
-- (QuestionnaireForm*) and the global interview form keep working exactly as before:
--
--   * the question triggers give every new global question a lineage and a frozen display code,
--     which nothing on the global form reads;
--   * the binding triggers mirror today's "attach to a design workshop" into QuestionnaireBinding;
--   * the two new CHECKs on existing tables are NOT VALID: they hold every row written from here on
--     and leave existing rows alone.
--
-- NOT HERE, deliberately (they are not additive, and they are U5's, behind its dry run and the
-- owner's approval): the global uniques on QuestionnaireSection (code, sortOrder) and
-- QuestionnaireInterview.artisanSetKey, the SectionStatus enum cast, copying the forms' sections and
-- questions into the instrument tables, and the primary binding per design workshop.

-- ── enums (the same values as documentation-portal's; shared/questionnaire.prisma) ──────────────
CREATE TYPE "SessionState" AS ENUM ('OPEN', 'CLOSED');
CREATE TYPE "SessionPurpose" AS ENUM ('FIRST_SITTING', 'FOLLOW_UP', 'REDO');
CREATE TYPE "SessionOrigin" AS ENUM ('NATIVE', 'LEGACY_RECONSTRUCTED');
CREATE TYPE "ResearcherRole" AS ENUM ('LEAD', 'CONTRIBUTOR', 'VOLUNTEER');
CREATE TYPE "SubjectRole" AS ENUM ('ANSWERED', 'PRESENT');
CREATE TYPE "RepeatReason" AS ENUM ('REDO', 'FOLLOW_UP', 'RE_INTERVIEW', 'NEW_SETTING');
CREATE TYPE "TakeFlag" AS ENUM ('CONCURRENT', 'LATE_ARRIVAL', 'AFTER_CLOSE', 'LEGACY');
CREATE TYPE "CapturedAtSource" AS ENUM ('DEVICE', 'FILE_METADATA', 'SESSION_DATE', 'UPLOAD_TIME', 'UPLOAD_TIME_OUT_OF_WINDOW');
CREATE TYPE "RecordingFiling" AS ENUM ('FILED', 'NEEDS_DECISION');
CREATE TYPE "RecordingProvenance" AS ENUM ('NATIVE', 'SHIM', 'LEGACY_META', 'LEGACY_CAPTION_TEXT', 'LEGACY_FILENAME', 'MANUAL');
CREATE TYPE "AnswerOrigin" AS ENUM ('TYPED', 'TRANSCRIPT', 'MACHINE_SUGGESTED', 'MACHINE_SUGGESTED_EDITED', 'PREFILL');
CREATE TYPE "AnswerAttribution" AS ENUM ('INDIVIDUAL', 'GROUP_SHARED');
CREATE TYPE "SectionStatus" AS ENUM ('COMPLETED', 'NEEDS_REVIEW', 'NEEDS_REDO');
CREATE TYPE "RespondentKind" AS ENUM ('GUEST', 'SELF');
CREATE TYPE "SessionSource" AS ENUM ('APP', 'UPLOAD', 'HANDOFF');
ALTER TYPE "MediaProcessingJobType" ADD VALUE IF NOT EXISTS 'SHA_VERIFY';

-- ── MediaFile: soft removal, verified hash, consent at send ────────────────────────────────────
ALTER TABLE "MediaFile"
  ADD COLUMN "removedAt" TIMESTAMP(3),
  ADD COLUMN "removedById" TEXT,
  ADD COLUMN "contentSha256" TEXT,
  ADD COLUMN "shaVerifiedAt" TIMESTAMP(3),
  ADD COLUMN "consentAtSend" "ConsentState";
UPDATE "MediaFile" SET "contentSha256" = lower("checksum")
 WHERE "checksum" ~* '^[0-9a-f]{64}$' AND "contentSha256" IS NULL;
CREATE INDEX "MediaFile_contentSha256_idx" ON "MediaFile"("contentSha256");

-- ── forms: the global default and the library order ────────────────────────────────────────────
ALTER TABLE "Questionnaire"
  ADD COLUMN "isDefault" BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN "sortOrder" INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX "Questionnaire_isDefault_key" ON "Questionnaire"("isDefault") WHERE "isDefault";

-- ── sections and questions: the per-form columns (null on the global instrument until U5) ──────
ALTER TABLE "QuestionnaireSection"
  ADD COLUMN "questionnaireId" TEXT REFERENCES "Questionnaire"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE UNIQUE INDEX "QuestionnaireSection_questionnaireId_code_key" ON "QuestionnaireSection"("questionnaireId", "code");
CREATE INDEX "QuestionnaireSection_questionnaireId_idx" ON "QuestionnaireSection"("questionnaireId");

ALTER TABLE "QuestionnaireQuestion"
  ADD COLUMN "questionnaireId" TEXT REFERENCES "Questionnaire"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  ADD COLUMN "helpText" TEXT,
  ADD COLUMN "isRequired" BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN "artisanField" TEXT,
  ADD COLUMN "lineageId" TEXT,
  ADD COLUMN "displayCode" TEXT;

-- lineage = the root of the supersede chain (a row nobody supersedes into)
WITH RECURSIVE chain AS (
  SELECT q."id" AS root, q."id" AS id
    FROM "QuestionnaireQuestion" q
   WHERE NOT EXISTS (SELECT 1 FROM "QuestionnaireQuestion" p WHERE p."supersededById" = q."id")
  UNION ALL
  SELECT c.root, q."supersededById"
    FROM chain c JOIN "QuestionnaireQuestion" q ON q."id" = c.id
   WHERE q."supersededById" IS NOT NULL
)
UPDATE "QuestionnaireQuestion" q SET "lineageId" = chain.root FROM chain WHERE chain.id = q."id";
UPDATE "QuestionnaireQuestion" SET "lineageId" = "id" WHERE "lineageId" IS NULL;

-- code = section code + the lineage root's position in its section, then frozen
WITH roots AS (
  SELECT q."id", upper(q."sectionCode") AS sec,
         ROW_NUMBER() OVER (PARTITION BY q."questionnaireId", upper(q."sectionCode")
                            ORDER BY q."sortOrder", q."createdAt", q."id") AS n
    FROM "QuestionnaireQuestion" q WHERE q."lineageId" = q."id"
)
UPDATE "QuestionnaireQuestion" q SET "displayCode" = roots.sec || roots.n::text
  FROM roots WHERE roots."id" = q."lineageId";

CREATE INDEX "QuestionnaireQuestion_lineageId_idx" ON "QuestionnaireQuestion"("lineageId");
CREATE INDEX "QuestionnaireQuestion_questionnaireId_isActive_idx" ON "QuestionnaireQuestion"("questionnaireId", "isActive");
CREATE UNIQUE INDEX "QuestionnaireQuestion_live_displayCode_key"
  ON "QuestionnaireQuestion"("questionnaireId", "displayCode")
  WHERE "retiredAt" IS NULL AND "supersededById" IS NULL;

-- New rows get their lineage and next code from the database (documentation-portal's trigger,
-- as fixed by its 20261102090000_question_display_code), so every writer keeps working unchanged.
CREATE FUNCTION questionnaire_question_lineage_ins() RETURNS trigger AS $$
DECLARE
  sec text := upper(COALESCE(NEW."sectionCode", ''));
  next_n integer;
BEGIN
  IF NEW."lineageId" IS NULL THEN NEW."lineageId" := NEW."id"; END IF;
  IF NEW."displayCode" IS NULL THEN
    SELECT COALESCE(max(substr(q."displayCode", length(sec) + 1)::integer), 0) + 1 INTO next_n
      FROM "QuestionnaireQuestion" q
     WHERE q."questionnaireId" = NEW."questionnaireId"
       AND upper(q."sectionCode") = sec
       AND starts_with(q."displayCode", sec)
       AND substr(q."displayCode", length(sec) + 1) ~ '^[0-9]{1,9}$';
    WHILE EXISTS (SELECT 1 FROM "QuestionnaireQuestion" q
                   WHERE q."questionnaireId" = NEW."questionnaireId" AND q."displayCode" = sec || next_n::text) LOOP
      next_n := next_n + 1;
    END LOOP;
    NEW."displayCode" := sec || next_n::text;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER questionnaire_question_lineage_ins BEFORE INSERT ON "QuestionnaireQuestion"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_question_lineage_ins();

CREATE FUNCTION questionnaire_question_lineage_upd() RETURNS trigger AS $$
BEGIN
  IF NEW."supersededById" IS NOT NULL AND NEW."supersededById" IS DISTINCT FROM OLD."supersededById" THEN
    UPDATE "QuestionnaireQuestion"
       SET "lineageId" = NEW."lineageId", "displayCode" = NEW."displayCode"
     WHERE "id" = NEW."supersededById";
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER questionnaire_question_lineage_upd AFTER UPDATE OF "supersededById" ON "QuestionnaireQuestion"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_question_lineage_upd();

-- ── the admin section override keeps its three states (owner, amendment 2 point 1) ─────────────
-- NOT VALID: enforced on every new or changed row; existing rows keep their values (they are already
-- these three) and are cast to the SectionStatus enum by U5.
ALTER TABLE "QuestionnaireSectionStatus"
  ADD CONSTRAINT "QuestionnaireSectionStatus_three_states"
  CHECK ("status" IN ('COMPLETED', 'NEEDS_REVIEW', 'NEEDS_REDO')) NOT VALID;

-- ── QuestionnaireInterview: the session ────────────────────────────────────────────────────────
ALTER TABLE "QuestionnaireInterview"
  ADD COLUMN "questionnaireId" TEXT REFERENCES "Questionnaire"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  ADD COLUMN "submittedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ADD COLUMN "clientKey" TEXT,
  ADD COLUMN "joinCode" TEXT,
  ADD COLUMN "sessionDate" DATE,
  ADD COLUMN "sessionDateEstimated" BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN "startedAt" TIMESTAMP(3),
  ADD COLUMN "endedAt" TIMESTAMP(3),
  ADD COLUMN "closedAt" TIMESTAMP(3),
  ADD COLUMN "state" "SessionState" NOT NULL DEFAULT 'OPEN',
  ADD COLUMN "purpose" "SessionPurpose" NOT NULL DEFAULT 'FIRST_SITTING',
  ADD COLUMN "origin" "SessionOrigin" NOT NULL DEFAULT 'NATIVE',
  ADD COLUMN "note" TEXT,
  ADD COLUMN "legacyTitle" TEXT,
  ADD COLUMN "languageCode" TEXT,
  ADD COLUMN "languageOther" TEXT,
  ADD COLUMN "mergedIntoId" TEXT,
  ADD COLUMN "version" INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN "pendingChangesAt" TIMESTAMP(3),
  ADD COLUMN "source" "SessionSource" NOT NULL DEFAULT 'APP',
  ADD CONSTRAINT "QuestionnaireInterview_note_len" CHECK (char_length("note") <= 80);
-- One workshop kind per session, for every session written from here on (existing global-form
-- interviews are left as they are).
ALTER TABLE "QuestionnaireInterview"
  ADD CONSTRAINT "QuestionnaireInterview_one_workshop_kind"
  CHECK (num_nonnulls("workshopId", "designWorkshopId") <= 1) NOT VALID;
CREATE UNIQUE INDEX "QuestionnaireInterview_clientKey_key" ON "QuestionnaireInterview"("clientKey");
CREATE UNIQUE INDEX "QuestionnaireInterview_joinCode_key" ON "QuestionnaireInterview"("joinCode");
CREATE INDEX "QuestionnaireInterview_sessionDate_idx" ON "QuestionnaireInterview"("sessionDate");
CREATE INDEX "QuestionnaireInterview_state_startedAt_idx" ON "QuestionnaireInterview"("state", "startedAt");
CREATE INDEX "QuestionnaireInterview_questionnaireId_createdAt_idx" ON "QuestionnaireInterview"("questionnaireId", "createdAt");
CREATE INDEX "QuestionnaireInterview_pendingChangesAt_idx" ON "QuestionnaireInterview"("pendingChangesAt");

ALTER TABLE "QuestionnaireInterviewArtisan"
  ADD COLUMN "sortOrder" INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN "joinedAt" TIMESTAMP(3),
  ADD COLUMN "leftAt" TIMESTAMP(3);

CREATE TABLE "QuestionnaireInterviewResearcher" (
  "interviewId" TEXT NOT NULL REFERENCES "QuestionnaireInterview"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "userId" TEXT NOT NULL REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "role" "ResearcherRole" NOT NULL,
  "addedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireInterviewResearcher_pkey" PRIMARY KEY ("interviewId", "userId")
);
CREATE INDEX "QuestionnaireInterviewResearcher_userId_idx" ON "QuestionnaireInterviewResearcher"("userId");

-- ── recordings, targets, subjects, events ──────────────────────────────────────────────────────
CREATE SEQUENCE questionnaire_change_seq;

CREATE TABLE "QuestionnaireRecording" (
  "id" TEXT NOT NULL,
  "mediaFileId" TEXT NOT NULL REFERENCES "MediaFile"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "clientClipKey" TEXT NOT NULL,
  "deviceCapturedAt" TIMESTAMP(3),
  "clockOffsetMs" INTEGER,
  "capturedAt" TIMESTAMP(3) NOT NULL,
  "capturedAtSource" "CapturedAtSource" NOT NULL,
  "durationMs" INTEGER,
  "rosterCount" INTEGER NOT NULL,
  "recordedById" TEXT NOT NULL REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "continuationOfId" TEXT REFERENCES "QuestionnaireRecording"("id") ON DELETE SET NULL ON UPDATE CASCADE,
  "replacesRecordingId" TEXT REFERENCES "QuestionnaireRecording"("id") ON DELETE SET NULL ON UPDATE CASCADE,
  "ackedRecordingIds" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  "filing" "RecordingFiling" NOT NULL DEFAULT 'FILED',
  "decisionKind" TEXT,
  "provenance" "RecordingProvenance" NOT NULL,
  "languageCode" TEXT,
  "removedAt" TIMESTAMP(3),
  "removedById" TEXT,
  "changeSeq" BIGINT NOT NULL DEFAULT nextval('questionnaire_change_seq'::regclass),
  "version" INTEGER NOT NULL DEFAULT 1,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "QuestionnaireRecording_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "QuestionnaireRecording_mediaFileId_key" ON "QuestionnaireRecording"("mediaFileId");
CREATE UNIQUE INDEX "QuestionnaireRecording_clientClipKey_key" ON "QuestionnaireRecording"("clientClipKey");
CREATE INDEX "QuestionnaireRecording_changeSeq_idx" ON "QuestionnaireRecording"("changeSeq");
CREATE INDEX "QuestionnaireRecording_continuationOfId_idx" ON "QuestionnaireRecording"("continuationOfId");

CREATE TABLE "QuestionnaireRecordingTarget" (
  "recordingId" TEXT NOT NULL REFERENCES "QuestionnaireRecording"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "targetKey" TEXT NOT NULL,
  "sectionId" TEXT REFERENCES "QuestionnaireSection"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "questionLineageId" TEXT REFERENCES "QuestionnaireQuestion"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "askedQuestionId" TEXT REFERENCES "QuestionnaireQuestion"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT "QuestionnaireRecordingTarget_pkey" PRIMARY KEY ("recordingId", "targetKey"),
  CONSTRAINT "QuestionnaireRecordingTarget_one_kind" CHECK (num_nonnulls("sectionId", "questionLineageId") = 1)
);
CREATE INDEX "QuestionnaireRecordingTarget_questionLineageId_idx" ON "QuestionnaireRecordingTarget"("questionLineageId");
CREATE INDEX "QuestionnaireRecordingTarget_sectionId_idx" ON "QuestionnaireRecordingTarget"("sectionId");

CREATE TABLE "QuestionnaireRecordingSubject" (
  "recordingId" TEXT NOT NULL REFERENCES "QuestionnaireRecording"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "artisanId" TEXT NOT NULL REFERENCES "Artisan"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "role" "SubjectRole" NOT NULL,
  "named" BOOLEAN NOT NULL DEFAULT false,
  "repeatReason" "RepeatReason",
  "flag" "TakeFlag",
  CONSTRAINT "QuestionnaireRecordingSubject_pkey" PRIMARY KEY ("recordingId", "artisanId")
);
CREATE INDEX "QuestionnaireRecordingSubject_artisanId_idx" ON "QuestionnaireRecordingSubject"("artisanId");

CREATE TABLE "QuestionnaireRecordingEvent" (
  "id" TEXT NOT NULL,
  "recordingId" TEXT NOT NULL REFERENCES "QuestionnaireRecording"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "kind" TEXT NOT NULL,
  "before" JSONB,
  "after" JSONB,
  "actorId" TEXT NOT NULL,
  "at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireRecordingEvent_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "QuestionnaireRecordingEvent_recordingId_at_idx" ON "QuestionnaireRecordingEvent"("recordingId", "at");

-- ── answers ────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE "QuestionnaireAnswer" (
  "id" TEXT NOT NULL,
  "artisanId" TEXT NOT NULL REFERENCES "Artisan"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "questionnaireId" TEXT NOT NULL REFERENCES "Questionnaire"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "questionLineageId" TEXT NOT NULL REFERENCES "QuestionnaireQuestion"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "currentRevisionId" TEXT,
  "pinned" BOOLEAN NOT NULL DEFAULT false,
  "version" INTEGER NOT NULL DEFAULT 1,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "QuestionnaireAnswer_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "QuestionnaireAnswer_artisanId_questionLineageId_key" ON "QuestionnaireAnswer"("artisanId", "questionLineageId");
CREATE INDEX "QuestionnaireAnswer_questionnaireId_idx" ON "QuestionnaireAnswer"("questionnaireId");

CREATE TABLE "QuestionnaireAnswerRevision" (
  "id" TEXT NOT NULL,
  "answerId" TEXT NOT NULL REFERENCES "QuestionnaireAnswer"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "text" TEXT NOT NULL,
  "languageCode" TEXT,
  "origin" "AnswerOrigin" NOT NULL,
  "attribution" "AnswerAttribution" NOT NULL,
  "askedQuestionId" TEXT NOT NULL REFERENCES "QuestionnaireQuestion"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "sessionId" TEXT NOT NULL REFERENCES "QuestionnaireInterview"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "sourceRecordingId" TEXT REFERENCES "QuestionnaireRecording"("id") ON DELETE SET NULL ON UPDATE CASCADE,
  "sourceTranscriptHash" TEXT,
  "sourceFieldValue" TEXT,
  "status" "RecordStatus" NOT NULL DEFAULT 'PENDING',
  "actorId" TEXT NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireAnswerRevision_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "QuestionnaireAnswerRevision_answerId_createdAt_idx" ON "QuestionnaireAnswerRevision"("answerId", "createdAt");
CREATE INDEX "QuestionnaireAnswerRevision_sessionId_idx" ON "QuestionnaireAnswerRevision"("sessionId");

CREATE TABLE "QuestionnaireTakeDraft" (
  "id" TEXT NOT NULL,
  "recordingId" TEXT NOT NULL REFERENCES "QuestionnaireRecording"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "questionLineageId" TEXT NOT NULL,
  "artisanId" TEXT NOT NULL DEFAULT '',
  "text" TEXT NOT NULL,
  "origin" "AnswerOrigin" NOT NULL,
  "model" TEXT,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireTakeDraft_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "QuestionnaireTakeDraft_recording_lineage_artisan_key"
  ON "QuestionnaireTakeDraft"("recordingId", "questionLineageId", "artisanId");

-- ── upload intents ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE "MediaUploadIntent" (
  "id" TEXT NOT NULL,
  "objectKey" TEXT NOT NULL,
  "clientClipKey" TEXT NOT NULL,
  "sessionId" TEXT NOT NULL,
  "uploaderId" TEXT NOT NULL,
  "sizeBytes" BIGINT NOT NULL,
  "contentType" TEXT NOT NULL,
  "contentSha256" TEXT,
  "multipartUploadId" TEXT,
  "partSize" INTEGER,
  "expiresAt" TIMESTAMP(3) NOT NULL,
  "closedAt" TIMESTAMP(3),
  "mediaFileId" TEXT,
  "meta" JSONB,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "MediaUploadIntent_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "MediaUploadIntent_objectKey_key" ON "MediaUploadIntent"("objectKey");
CREATE UNIQUE INDEX "MediaUploadIntent_clientClipKey_key" ON "MediaUploadIntent"("clientClipKey");
CREATE INDEX "MediaUploadIntent_closedAt_expiresAt_idx" ON "MediaUploadIntent"("closedAt", "expiresAt");
CREATE INDEX "MediaUploadIntent_uploaderId_idx" ON "MediaUploadIntent"("uploaderId");

-- ── guest respondents ──────────────────────────────────────────────────────────────────────────
CREATE TABLE "QuestionnaireRespondent" (
  "id" TEXT NOT NULL,
  "sessionId" TEXT NOT NULL REFERENCES "QuestionnaireInterview"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "name" TEXT NOT NULL,
  "kind" "RespondentKind" NOT NULL DEFAULT 'GUEST',
  "userId" TEXT,
  "notes" TEXT,
  "artisanId" TEXT REFERENCES "Artisan"("id") ON DELETE SET NULL ON UPDATE CASCADE,
  "registeredAt" TIMESTAMP(3),
  "createdById" TEXT NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "QuestionnaireRespondent_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "QuestionnaireRespondent_name_len" CHECK (char_length("name") BETWEEN 1 AND 120)
);
CREATE INDEX "QuestionnaireRespondent_sessionId_idx" ON "QuestionnaireRespondent"("sessionId");
CREATE INDEX "QuestionnaireRespondent_artisanId_idx" ON "QuestionnaireRespondent"("artisanId");

-- A guest on a take, beside the artisan subjects (whose key is (recordingId, artisanId)).
CREATE TABLE "QuestionnaireRecordingRespondent" (
  "recordingId" TEXT NOT NULL REFERENCES "QuestionnaireRecording"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "respondentId" TEXT NOT NULL REFERENCES "QuestionnaireRespondent"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  "role" "SubjectRole" NOT NULL,
  "named" BOOLEAN NOT NULL DEFAULT false,
  "repeatReason" "RepeatReason",
  "flag" "TakeFlag",
  CONSTRAINT "QuestionnaireRecordingRespondent_pkey" PRIMARY KEY ("recordingId", "respondentId")
);
CREATE INDEX "QuestionnaireRecordingRespondent_respondentId_idx" ON "QuestionnaireRecordingRespondent"("respondentId");

-- An answer is an artisan's OR a guest's, never both, never neither; one per owner per lineage.
ALTER TABLE "QuestionnaireAnswer"
  ALTER COLUMN "artisanId" DROP NOT NULL,
  ADD COLUMN "respondentId" TEXT REFERENCES "QuestionnaireRespondent"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  ADD CONSTRAINT "QuestionnaireAnswer_one_owner" CHECK (num_nonnulls("artisanId", "respondentId") = 1);
CREATE UNIQUE INDEX "QuestionnaireAnswer_respondentId_questionLineageId_key"
  ON "QuestionnaireAnswer"("respondentId", "questionLineageId");

-- The answer note (A20) lives on the revision.
ALTER TABLE "QuestionnaireAnswerRevision" ADD COLUMN "note" TEXT;

-- ── bindings: several forms per design workshop, one primary ──────────────────────────────────
CREATE TABLE "QuestionnaireBinding" (
  "id" TEXT NOT NULL,
  "questionnaireId" TEXT NOT NULL REFERENCES "Questionnaire"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "workshopId" TEXT REFERENCES "Workshop"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "designWorkshopId" TEXT REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "isPrimary" BOOLEAN NOT NULL DEFAULT false,
  "createdById" TEXT,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireBinding_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "QuestionnaireBinding_one_workshop" CHECK (num_nonnulls("workshopId", "designWorkshopId") = 1)
);
CREATE UNIQUE INDEX "QuestionnaireBinding_questionnaireId_workshopId_key" ON "QuestionnaireBinding"("questionnaireId", "workshopId");
CREATE UNIQUE INDEX "QuestionnaireBinding_questionnaireId_designWorkshopId_key" ON "QuestionnaireBinding"("questionnaireId", "designWorkshopId");
CREATE UNIQUE INDEX "QuestionnaireBinding_one_primary_workshop" ON "QuestionnaireBinding"("workshopId") WHERE "isPrimary" AND "workshopId" IS NOT NULL;
CREATE UNIQUE INDEX "QuestionnaireBinding_one_primary_design_workshop" ON "QuestionnaireBinding"("designWorkshopId") WHERE "isPrimary" AND "designWorkshopId" IS NOT NULL;
CREATE INDEX "QuestionnaireBinding_workshopId_idx" ON "QuestionnaireBinding"("workshopId");
CREATE INDEX "QuestionnaireBinding_designWorkshopId_idx" ON "QuestionnaireBinding"("designWorkshopId");

-- Every form attached to a design workshop today becomes a binding; which one is PRIMARY is the
-- owner's choice in the U5 dry-run report (section 3.3), so none is primary yet: a workshop with one
-- form resolves to it ("its only binding"), one with several shows the picker grouped by kind.
INSERT INTO "QuestionnaireBinding" ("id", "questionnaireId", "designWorkshopId", "isPrimary", "createdById")
SELECT gen_random_uuid()::text, q."id", q."designWorkshopId", false, q."ownerId"
  FROM "Questionnaire" q WHERE q."designWorkshopId" IS NOT NULL
ON CONFLICT DO NOTHING;

-- THE MIRROR, until the cleanup migration drops Questionnaire."designWorkshopId": attaching a form
-- to a design workshop in today's screens binds it, detaching unbinds it; a binding made through the
-- shared routes fills an empty attachment. Guarded so the two triggers settle after one round.
CREATE FUNCTION questionnaire_binding_from_form() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW."designWorkshopId" IS NOT DISTINCT FROM OLD."designWorkshopId" THEN RETURN NEW; END IF;
  IF TG_OP = 'UPDATE' AND OLD."designWorkshopId" IS NOT NULL THEN
    DELETE FROM "QuestionnaireBinding" WHERE "questionnaireId" = NEW."id" AND "designWorkshopId" = OLD."designWorkshopId";
  END IF;
  IF NEW."designWorkshopId" IS NOT NULL THEN
    INSERT INTO "QuestionnaireBinding" ("id", "questionnaireId", "designWorkshopId", "isPrimary", "createdById")
    VALUES (gen_random_uuid()::text, NEW."id", NEW."designWorkshopId", false, NEW."ownerId")
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER questionnaire_binding_from_form AFTER INSERT OR UPDATE OF "designWorkshopId" ON "Questionnaire"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_binding_from_form();

CREATE FUNCTION questionnaire_form_from_binding() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD."designWorkshopId" IS NOT NULL THEN
      UPDATE "Questionnaire" SET "designWorkshopId" = (
          SELECT b."designWorkshopId" FROM "QuestionnaireBinding" b
           WHERE b."questionnaireId" = OLD."questionnaireId" AND b."designWorkshopId" IS NOT NULL
           ORDER BY b."isPrimary" DESC, b."createdAt" LIMIT 1)
       WHERE "id" = OLD."questionnaireId" AND "designWorkshopId" = OLD."designWorkshopId";
    END IF;
    RETURN OLD;
  END IF;
  IF NEW."designWorkshopId" IS NOT NULL THEN
    UPDATE "Questionnaire" SET "designWorkshopId" = NEW."designWorkshopId"
     WHERE "id" = NEW."questionnaireId" AND "designWorkshopId" IS NULL;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER questionnaire_form_from_binding AFTER INSERT OR DELETE ON "QuestionnaireBinding"
  FOR EACH ROW EXECUTE FUNCTION questionnaire_form_from_binding();

-- ── a default form per workshop type (owner, amendment 2 of 22 Oct 2026, point 2) ──────────────
CREATE TABLE "QuestionnaireTypeDefault" (
  "id" TEXT NOT NULL,
  "questionnaireId" TEXT NOT NULL REFERENCES "Questionnaire"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "workshopType" "WorkshopType",
  "designWorkshopKind" TEXT,
  "setById" TEXT NOT NULL,
  "setAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "QuestionnaireTypeDefault_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "QuestionnaireTypeDefault_one_type" CHECK (num_nonnulls("workshopType", "designWorkshopKind") = 1)
);
CREATE UNIQUE INDEX "QuestionnaireTypeDefault_workshopType_key" ON "QuestionnaireTypeDefault"("workshopType");
CREATE UNIQUE INDEX "QuestionnaireTypeDefault_designWorkshopKind_key" ON "QuestionnaireTypeDefault"("designWorkshopKind");

-- ── client builds seen (FR audit H22; the version-code cutoff) ──────────────────────────────────
CREATE TABLE "AppClientVersion" (
    "userId" TEXT NOT NULL,
    "platform" TEXT NOT NULL,
    "versionCode" INTEGER NOT NULL,
    "versionName" TEXT,
    "firstSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "AppClientVersion_pkey" PRIMARY KEY ("userId","platform","versionCode")
);

CREATE INDEX "AppClientVersion_platform_versionCode_idx" ON "AppClientVersion"("platform", "versionCode");

CREATE INDEX "AppClientVersion_lastSeenAt_idx" ON "AppClientVersion"("lastSeenAt");

ALTER TABLE "AppClientVersion" ADD CONSTRAINT "AppClientVersion_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- The the hosted database's Data API (docs/ENVIRONMENT.md, "The database") stays locked down (RLS on every table, anon revoked): the event trigger adds
-- RLS to a new table automatically where it is installed; this makes it explicit everywhere.
-- ── coverage view: live sessions only, with the subject key and the design workshop ────────────
-- The same definition as documentation-portal's 20261107090000_questionnaire_unify_additive.
CREATE VIEW questionnaire_take_v AS
WITH RECURSIVE live AS (
  SELECT r.*, m."questionnaireInterviewId" AS "sessionId", m."createdAt" AS "mediaCreatedAt",
         m."contentSha256", i."questionnaireId", i."workshopId", i."designWorkshopId"
    FROM "QuestionnaireRecording" r
    JOIN "MediaFile" m ON m."id" = r."mediaFileId"
    JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId"
   WHERE r."removedAt" IS NULL AND m."removedAt" IS NULL AND i."deletedAt" IS NULL
),
roots AS (
  SELECT l."id", l."id" AS "rootId" FROM live l WHERE l."continuationOfId" IS NULL
  UNION ALL
  SELECT l."id", roots."rootId" FROM live l JOIN roots ON l."continuationOfId" = roots."id"
),
takes AS (
  SELECT l."id" AS "recordingId", COALESCE(ro."rootId", l."id") AS "rootRecordingId",
         l."sessionId", l."questionnaireId", l."workshopId", s."artisanId", t."targetKey",
         t."sectionId", t."questionLineageId", t."askedQuestionId", l."capturedAt", l."capturedAtSource",
         l."filing", l."continuationOfId", l."rosterCount", l."recordedById", l."durationMs",
         l."changeSeq", l."mediaFileId", l."mediaCreatedAt", s."repeatReason", s."flag", s."named",
         l."designWorkshopId", 'A:' || s."artisanId" AS "subjectKey"
    FROM live l
    LEFT JOIN roots ro ON ro."id" = l."id"
    JOIN "QuestionnaireRecordingTarget" t ON t."recordingId" = l."id"
    JOIN "QuestionnaireRecordingSubject" s ON s."recordingId" = l."id" AND s."role" = 'ANSWERED'
),
ranked AS (
  SELECT tk."recordingId", tk."artisanId", tk."targetKey",
         ROW_NUMBER() OVER (PARTITION BY tk."artisanId", tk."targetKey"
                            ORDER BY tk."capturedAt", tk."mediaCreatedAt", tk."recordingId") AS "position"
    FROM takes tk WHERE tk."continuationOfId" IS NULL
)
SELECT tk.*, rk."position"
  FROM takes tk
  LEFT JOIN ranked rk ON rk."recordingId" = tk."rootRecordingId"
                     AND rk."artisanId" = tk."artisanId" AND rk."targetKey" = tk."targetKey";

-- ── the hosted database's Data API (docs/ENVIRONMENT.md, "The database"): every new table is server-only ──────────────────────────────────────────
ALTER TABLE "QuestionnaireInterviewResearcher" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireRecording" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireRecordingTarget" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireRecordingSubject" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireRecordingEvent" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireAnswer" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireAnswerRevision" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireTakeDraft" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "MediaUploadIntent" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireRespondent" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireRecordingRespondent" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireBinding" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "QuestionnaireTypeDefault" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "AppClientVersion" ENABLE ROW LEVEL SECURITY;
