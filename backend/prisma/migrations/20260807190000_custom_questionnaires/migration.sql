-- Custom questionnaires: a form a designer authored from the downloadable .xlsx pro-forma, plus
-- the answers recorded against it in the app.
--
-- PURELY ADDITIVE. Four new tables and not one ALTER on an existing one. No column is added,
-- dropped or retyped anywhere in the live schema, and no constraint is relaxed — in particular
-- QuestionnaireSection keeps `code UNIQUE` and `UNIQUE(sortOrder)` exactly as it has them today.
-- That is the whole reason this feature got its own tables instead of a nullable questionnaireId
-- on the existing ones: in Postgres NULLs are DISTINCT inside a unique index, so making those
-- constraints composite with a nullable column would have left the global questionnaire — every
-- row of which would carry NULL — with no uniqueness at all, while the migration read as though it
-- were merely "scoping" them. See the block comment above `model Questionnaire` in schema.prisma.
--
-- Rolling this back is dropping four tables that nothing else references.
--
-- The one deliberate asymmetry, and it is load-bearing:
--   * Questionnaire -> Section -> Question and Questionnaire -> Entry -> Answer are ON DELETE
--     CASCADE, so a questionnaire that nobody ever answered can be cleaned up in one statement;
--   * Answer -> Question is ON DELETE RESTRICT.
-- Together those mean a questionnaire WITH answers cannot be deleted at all, by anyone, including a
-- future `delete_many` written by somebody who never read the service layer. Deleting a question
-- orphans a recorded answer, and the answer is a designer's fieldwork. The API offers deactivation
-- instead and never a delete; this is the floor under that promise rather than a restatement of it.

-- CreateTable
CREATE TABLE "Questionnaire" (
    "id" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT,
    "ownerId" TEXT NOT NULL,
    "designWorkshopId" TEXT,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    -- Bumped on every supersede/retire applied after answers existed, so a client holding a cached
    -- copy of the form can tell it is stale with an integer compare instead of a question diff.
    "version" INTEGER NOT NULL DEFAULT 1,
    "sourceFilename" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Questionnaire_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "QuestionnaireFormSection" (
    "id" TEXT NOT NULL,
    "questionnaireId" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "QuestionnaireFormSection_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "QuestionnaireFormQuestion" (
    "id" TEXT NOT NULL,
    "sectionId" TEXT NOT NULL,
    "prompt" TEXT NOT NULL,
    "helpText" TEXT,
    "isRequired" BOOLEAN NOT NULL DEFAULT false,
    "sortOrder" INTEGER NOT NULL,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "retiredAt" TIMESTAMP(3),
    -- The question that replaced this one when its prompt was rewritten after it had answers. A
    -- plain column, not a foreign key to itself: it is read to render "replaced by" and never
    -- joined through, and an FK here would make the cascade order above harder to reason about for
    -- no gain.
    "supersededById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "QuestionnaireFormQuestion_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "QuestionnaireFormEntry" (
    "id" TEXT NOT NULL,
    "questionnaireId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "respondentName" TEXT,
    -- APP | UPLOAD. Answers that arrived already filled in on the spreadsheet are stored as an
    -- ordinary entry, so the file's answers and the app's answers are the same rows and the edit
    -- rules need no special case for either.
    "source" TEXT NOT NULL DEFAULT 'APP',
    "notes" TEXT,
    "createdById" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "QuestionnaireFormEntry_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "QuestionnaireFormAnswer" (
    "id" TEXT NOT NULL,
    "entryId" TEXT NOT NULL,
    "questionId" TEXT NOT NULL,
    "answerText" TEXT,
    "notes" TEXT,
    "answeredById" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "QuestionnaireFormAnswer_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "Questionnaire_ownerId_createdAt_idx" ON "Questionnaire" ("ownerId", "createdAt");
CREATE INDEX "Questionnaire_designWorkshopId_idx" ON "Questionnaire" ("designWorkshopId");
CREATE INDEX "Questionnaire_isActive_createdAt_idx" ON "Questionnaire" ("isActive", "createdAt");

-- Section codes are unique WITHIN one questionnaire and nowhere else. Two designers both calling a
-- section "A" is normal; a single form carrying "A" twice is the thing that makes an uploaded
-- spreadsheet ambiguous. Neither column in this key is nullable, which is the property the rejected
-- nullable-questionnaireId design could not offer.
CREATE UNIQUE INDEX "QuestionnaireFormSection_questionnaireId_code_key" ON "QuestionnaireFormSection" ("questionnaireId", "code");
CREATE INDEX "QuestionnaireFormSection_questionnaireId_sortOrder_idx" ON "QuestionnaireFormSection" ("questionnaireId", "sortOrder");

CREATE INDEX "QuestionnaireFormQuestion_sectionId_sortOrder_idx" ON "QuestionnaireFormQuestion" ("sectionId", "sortOrder");
CREATE INDEX "QuestionnaireFormQuestion_isActive_idx" ON "QuestionnaireFormQuestion" ("isActive");
CREATE INDEX "QuestionnaireFormQuestion_supersededById_idx" ON "QuestionnaireFormQuestion" ("supersededById");

CREATE INDEX "QuestionnaireFormEntry_questionnaireId_createdAt_idx" ON "QuestionnaireFormEntry" ("questionnaireId", "createdAt");
CREATE INDEX "QuestionnaireFormEntry_createdById_idx" ON "QuestionnaireFormEntry" ("createdById");

-- One answer per question per sitting. Re-saving a section updates rows instead of appending a
-- second opinion, which is what makes the record-answers endpoint safely idempotent.
CREATE UNIQUE INDEX "QuestionnaireFormAnswer_entryId_questionId_key" ON "QuestionnaireFormAnswer" ("entryId", "questionId");
CREATE INDEX "QuestionnaireFormAnswer_questionId_idx" ON "QuestionnaireFormAnswer" ("questionId");
CREATE INDEX "QuestionnaireFormAnswer_answeredById_idx" ON "QuestionnaireFormAnswer" ("answeredById");

-- AddForeignKey
ALTER TABLE "Questionnaire" ADD CONSTRAINT "Questionnaire_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "User" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;
-- SET NULL, never CASCADE: deleting the workshop a questionnaire happened to be attached to must
-- not take the questionnaire and its recorded answers with it.
ALTER TABLE "Questionnaire" ADD CONSTRAINT "Questionnaire_designWorkshopId_fkey" FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop" ("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "QuestionnaireFormSection" ADD CONSTRAINT "QuestionnaireFormSection_questionnaireId_fkey" FOREIGN KEY ("questionnaireId") REFERENCES "Questionnaire" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "QuestionnaireFormQuestion" ADD CONSTRAINT "QuestionnaireFormQuestion_sectionId_fkey" FOREIGN KEY ("sectionId") REFERENCES "QuestionnaireFormSection" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "QuestionnaireFormEntry" ADD CONSTRAINT "QuestionnaireFormEntry_questionnaireId_fkey" FOREIGN KEY ("questionnaireId") REFERENCES "Questionnaire" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "QuestionnaireFormEntry" ADD CONSTRAINT "QuestionnaireFormEntry_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "QuestionnaireFormAnswer" ADD CONSTRAINT "QuestionnaireFormAnswer_entryId_fkey" FOREIGN KEY ("entryId") REFERENCES "QuestionnaireFormEntry" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
-- The floor under the edit-after-answers rule. See the header.
ALTER TABLE "QuestionnaireFormAnswer" ADD CONSTRAINT "QuestionnaireFormAnswer_questionId_fkey" FOREIGN KEY ("questionId") REFERENCES "QuestionnaireFormQuestion" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "QuestionnaireFormAnswer" ADD CONSTRAINT "QuestionnaireFormAnswer_answeredById_fkey" FOREIGN KEY ("answeredById") REFERENCES "User" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;
