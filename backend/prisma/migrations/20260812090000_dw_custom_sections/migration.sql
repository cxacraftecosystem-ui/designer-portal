-- Designer-defined sections and fields: one workshop's own questions, added with no deployment.
--
-- Step 6 of docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §5, and the whole of its §4. The rules these
-- two tables serve are written out in app/services/custom_sections.py; what this file records is
-- what the DATABASE does and does not enforce, and one place where it deliberately cannot.
--
-- WHAT THIS IS FOR. The 22 stages are Python literals in services/stage_definitions.py, and that
-- file is a deployment. A designer standing in a cluster who needs to record one more thing — how
-- many looms are in the shed, the name of the co-operative's secretary — has had exactly two
-- options: type it into a free-text notes box where nothing can count it, or not record it.
--
-- THE ANSWERS ARE NOT IN EITHER OF THESE TABLES, and that is the most important sentence here.
-- They live in a "DwStageEntry" row per (workshop, stage) whose "entityKey" is the reserved literal
-- '_custom' and whose "data" is keyed by "DwCustomField"."key". NO SCHEMA CHANGE WAS NEEDED FOR
-- THAT — the row is an ordinary stage entry — and that is the point rather than a happy accident:
--
--   * `save_stage`'s default is merge=false, which writes "data" WHOLESALE. Nesting the container
--     inside the stage's own singleton row would have meant that a client one release behind — one
--     that has never heard of custom sections and therefore sends no `custom` key — DELETED every
--     custom answer on the stage, silently, with nothing in `droppedKeys` to say so. With a
--     separate row an old client simply does not mention it and nothing is touched.
--   * Eight of the twenty-two stages (6, 11, 12, 13, 14, 15, 16, 17) declare NO SINGLETON entity at
--     all, so "hang it on the singleton row" could not have served the third of the stages a
--     designer is most likely to want to extend.
--   * The shallow {**previous, **clean} merge, the MAX_FIELD_KEYS cap and the rejected-value
--     preservation loop are all already correct for a row whose keys are top level.
--
-- PURELY ADDITIVE. Two new tables. No existing table gains, loses or retypes a column; no existing
-- constraint is relaxed; not one stored row is rewritten or read. Every "DwStageEntry"."data" blob
-- is exactly as it was. Rolling back is:
--
--   DROP TABLE "DwCustomField";
--   DROP TABLE "DwCustomSection";
--
-- and nothing else — nothing outside these two tables references either of them, and a workshop
-- that has no rows here behaves in every particular as it did before the migration ran.
--
-- =============================================================================================
-- THE ONE RULE THIS SCHEMA CANNOT ENFORCE, STATED HERE BECAUSE THIS IS WHERE IT WILL BE MET
-- =============================================================================================
--
-- The edit-after-answers rule (services/questionnaire_forms.py states it in full) says that a field
-- somebody has ANSWERED is never deleted — it is retired, or superseded, and its answers stay
-- readable under the wording they were given. That module's rule is enforced by the database as
-- well as by Python: "QuestionnaireFormAnswer"."questionId" is ON DELETE RESTRICT, so a
-- `delete_many` written by somebody who never read the docstring is refused by Postgres.
--
-- HERE THERE IS NO SUCH FOREIGN KEY, AND THERE CANNOT BE ONE. A custom answer is a KEY inside a
-- JSONB blob, not a row: Postgres has nothing to point at and nothing to refuse. So the rule is
-- Python-only. What stands in for the constraint:
--
--   (a) `custom_sections.plan_definition` is the only thing that can express a delete, and it
--       refuses to express one for a field whose key holds an answer;
--   (b) tests/test_custom_sections.py asserts that refusal, with no database underneath it;
--   (c) this paragraph, so that the next person to write a DELETE against "DwCustomField" meets the
--       rule in the file they are working in.
--
-- Three guards short of a foreign key. Recorded as a gap rather than papered over.
--
-- NO PERFORMANCE MEASUREMENT IS OFFERED, and the omission is deliberate rather than lazy. Migration
-- 20260808140000 carries EXPLAIN output because it changed how an existing query was planned
-- against 6,952 existing rows. These tables hold ZERO rows on the day they are created and no
-- existing query touches them, so any figure quoted for them would be a measurement of an empty
-- table — which is to say, invented. The indexes below are sized by READ SHAPE, stated against each
-- one. What the real numbers are once a fleet has filled them is UNMEASURED.
--
-- NO CHECK CONSTRAINTS, unlike 20260811090000. That migration added two because "DwAiLayer" has
-- shapes a row can hold that no reader can make sense of (a layer derived from nothing, a layer
-- claiming two sources). Nothing here is like that: every column below is either NOT NULL with a
-- default or legitimately absent, and the rules that could be expressed as CHECKs — the key
-- pattern, the twelve allowed types, "only a BASIC field may be required" — are about a VOCABULARY
-- that lives in Python and deploys with the server. A CHECK naming those twelve tokens would have
-- to be migrated every time the registry gains a type, and it would be invisible in schema.prisma
-- while doing it.

-- CreateTable
CREATE TABLE "DwCustomSection" (
    "id" TEXT NOT NULL,
    "designWorkshopId" TEXT NOT NULL,
    -- The StageSpec.key these questions are asked at. NOT NULL and no default: the answers live in
    -- the '_custom' row of THIS stage, so a section with no stage would have nowhere to put them.
    -- The plan's "empty means it belongs nowhere" is about where the section PRINTS, and that half
    -- is honoured in `apply_report_settings` — a template that does not carry this stage prints the
    -- section as its own annexure at the back.
    "stageKey" TEXT NOT NULL,
    "key" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT NOT NULL DEFAULT '',
    "sortOrder" INTEGER NOT NULL DEFAULT 0,
    -- Counts the edits that could NOT be applied in place — every supersede and every retire. What
    -- "Questionnaire"."version" counts, for its reason: it orders edits for an audit and tells two
    -- reports of one workshop apart. It is not the staleness signal; that is the content digest.
    "revision" INTEGER NOT NULL DEFAULT 1,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "retiredAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "createdById" TEXT,

    CONSTRAINT "DwCustomSection_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DwCustomField" (
    "id" TEXT NOT NULL,
    "sectionId" TEXT NOT NULL,
    -- WHAT THE ANSWER IS STORED UNDER. Permanent: renaming one orphans the answers already given
    -- under it, exactly as `stage_schema`'s rule 1 says of a FieldSpec key.
    "key" TEXT NOT NULL,
    "label" TEXT NOT NULL,
    "help" TEXT NOT NULL DEFAULT '',
    -- One of the twelve FieldType tokens v1 allows. TEXT and not a Postgres enum, deliberately, and
    -- the contrast with "DwAiLayerKind" one migration ago is the argument: that one is seven values
    -- fixed by a plan, where a typo would print an unknown annexure heading in a submitted
    -- document, so Postgres refuses it. This vocabulary belongs to the registry, which is Python
    -- that deploys with the server and gains a type from time to time; an enum here would mean a
    -- migration per registry type and a column the schema could not describe in between.
    "type" TEXT NOT NULL,
    "tier" TEXT NOT NULL DEFAULT 'STANDARD',
    "isRequired" BOOLEAN NOT NULL DEFAULT false,
    -- ENUM/MULTI_ENUM options as an ordered [{value,label}] array. Inline rather than an entry in
    -- the shared ENUMS table: a designer's list is theirs, and the shared table's whole value is
    -- that "cotton" means one thing across all 22 stages — a promise a per-workshop list cannot
    -- keep and must not appear to.
    "options" JSONB,
    "unit" TEXT NOT NULL DEFAULT '',
    "maxLength" INTEGER,
    "minValue" DOUBLE PRECISION,
    "maxValue" DOUBLE PRECISION,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "retiredAt" TIMESTAMP(3),
    -- THE FIELD THAT REPLACED THIS ONE when its label was rewritten after it had answers. A plain
    -- column with NO foreign key, exactly as "QuestionnaireFormQuestion"."supersededById" is: it is
    -- only ever read to follow "replaced by", never joined through. `custom_sections` walks it with
    -- a hop bound and a visited set precisely because nothing here refuses a row that points at
    -- itself.
    "supersededById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DwCustomField_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
-- Two designers both naming a section 'extra' is normal and must not collide, so uniqueness is
-- scoped to the workshop — exactly "QuestionnaireFormSection"."@@unique([questionnaireId, code])".
CREATE UNIQUE INDEX "DwCustomSection_designWorkshopId_key_key" ON "DwCustomSection"("designWorkshopId", "key");
-- The only read this table has: one workshop's whole definition, in the order it prints. Leading
-- with the workshop is what keeps the cost proportional to the ANSWER rather than to the archive —
-- the distinction 20260808140000 was written to make about "DwStageEntry", applied in advance here
-- rather than after a sequential scan.
CREATE INDEX "DwCustomSection_designWorkshopId_sortOrder_idx" ON "DwCustomSection"("designWorkshopId", "sortOrder");
-- Indexed for the WRITE it is on the wrong end of rather than for a read: createdById is ON DELETE
-- SET NULL, so deleting a user makes Postgres find every section naming them, and without this that
-- is a sequential scan inside a request an admin is waiting on.
CREATE INDEX "DwCustomSection_createdById_idx" ON "DwCustomSection"("createdById");

-- A field key identifies one question within one section. The WIDER rule — unique across the whole
-- workshop — is enforced in `custom_sections.validate_definition` and cannot be expressed here,
-- because it spans sections. It is the rule that actually matters: one stage's answers all share
-- one flat JSONB container, so two fields on one stage sharing a key would share one answer.
CREATE UNIQUE INDEX "DwCustomField_sectionId_key_key" ON "DwCustomField"("sectionId", "key");
-- The definition load's second query: every field of the sections just read, in printing order.
CREATE INDEX "DwCustomField_sectionId_sortOrder_idx" ON "DwCustomField"("sectionId", "sortOrder");
-- The supersede walk: given a retired field, the field that replaced it. Small today and indexed
-- anyway, because it is walked on every definition PUT for every field a stale client re-sends.
CREATE INDEX "DwCustomField_supersededById_idx" ON "DwCustomField"("supersededById");

-- AddForeignKey
-- CASCADE on the workshop, matching every other design-workshop child table. It fires only on a
-- HARD delete; the API's delete is a soft one, so an ordinary deletion leaves the definition intact
-- and a restore brings it back with the workshop — which matters more here than elsewhere, because
-- the answers in the "_custom" rows would otherwise come back with nothing left to say what any of
-- their keys had been asking.
ALTER TABLE "DwCustomSection" ADD CONSTRAINT "DwCustomSection_designWorkshopId_fkey" FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- SET NULL for the author, matching "DwStageEntry"."createdById" and NOT the Restrict that
-- `DesignWorkshop.createdBy` carries. Restrict is right for AUTHORSHIP — deleting the account would
-- erase who recorded the research — and a definition is a form rather than a finding. Restricting
-- here would add a brand-new reason an account cannot be deleted, and no screen offers "take this
-- person off every definition they ever wrote".
ALTER TABLE "DwCustomSection" ADD CONSTRAINT "DwCustomSection_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- CASCADE from the section to its fields. Note what this does NOT reach: the ANSWERS, which are
-- keys inside a "DwStageEntry"."data" blob and survive the definition being deleted outright. That
-- asymmetry is why `plan_definition` refuses to delete an answered field at all — the database
-- would let it, and what would be left is a container of values nothing can name.
ALTER TABLE "DwCustomField" ADD CONSTRAINT "DwCustomField_sectionId_fkey" FOREIGN KEY ("sectionId") REFERENCES "DwCustomSection"("id") ON DELETE CASCADE ON UPDATE CASCADE;
