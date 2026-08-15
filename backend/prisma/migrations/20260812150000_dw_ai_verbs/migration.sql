-- Five AI verbs, as layers: proofread, expand, translate, caption, subtitle. And a ceiling on them.
--
-- Steps 7 and 8 of docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §5, and the whole of its §3. The law
-- these objects serve is written out in app/services/ai_layers.py and is not repeated here; what
-- this file records is what the DATABASE enforces, and the three places it deliberately cannot.
--
--   1. Every layer is a ROW, never an edit. No AI output overwrites its input, ever.
--   2. Every layer carries provenance: source, tier, provider/model id, model version, timestamp,
--      language. TRANSLATION needs TWO languages, which is why this migration adds columns and not
--      only enum values.
--   3. A layer is INERT until a person accepts it, and acceptance records who and when.
--   4. The report prints the ACCEPTED layer and NAMES it as machine-assisted.
--   5. Deleting a derived layer NEVER touches its source.
--
-- =============================================================================================
-- WHAT IS ADDITIVE, AND THE ONE THING THAT IS NOT
-- =============================================================================================
--
-- Additive: five enum values, three nullable columns on "DwAiLayer", one nullable column on
-- "AppSetting", one new table. No existing column is retyped or dropped, no stored row is read or
-- rewritten, and a deployment that never calls a verb behaves exactly as it did before.
--
-- **NOT ADDITIVE, AND SAID PLAINLY: "DwAiLayer_source_is_exactly_one" IS DROPPED AND RE-CREATED.**
-- It was `num_nonnulls("sourceMediaId", "sourceLayerId") = 1`. It becomes
-- `num_nonnulls("sourceMediaId", "sourceLayerId", "sourceText") = 1`. That is a WIDENING — every row
-- the old constraint admitted the new one admits, and every row it refused the new one still refuses
-- except the one new shape it is being widened for — so no stored row can fail the re-ADD and the
-- statement cannot leave the table in a state it could not leave it in before. It is still the only
-- statement in this file that touches something that already existed, so it is named at the top
-- rather than left to be discovered in the diff.
--
-- Rolling back is:
--
--   ALTER TABLE "DwAiLayer" DROP CONSTRAINT "DwAiLayer_source_is_exactly_one";
--   ALTER TABLE "DwAiLayer" ADD CONSTRAINT "DwAiLayer_source_is_exactly_one"
--     CHECK (num_nonnulls("sourceMediaId", "sourceLayerId") = 1);   -- after deleting text-rooted rows
--   ALTER TABLE "DwAiLayer"
--     DROP COLUMN "sourceText", DROP COLUMN "sourceLanguage", DROP COLUMN "targetLanguage";
--   ALTER TABLE "AppSetting" DROP COLUMN "dwAiVerbDailyCap";
--   DROP TABLE "DwAiVerbDailyUsage";
--
-- and the five enum values, which Postgres cannot remove. An unused enum value is inert; the rows
-- carrying one are not, which is why the rollback above has to say "after deleting text-rooted rows"
-- out loud instead of pretending the constraint narrows for free.
--
-- NO PERFORMANCE MEASUREMENT IS OFFERED, and the omission is deliberate rather than lazy, exactly as
-- in 20260811090000 and 20260812120000. The new table holds zero rows on the day it is created; the
-- three new columns are read only by the code added alongside them; and no existing query plan
-- changes, because no index is added, dropped or reshaped. Any figure quoted here would be a
-- measurement of an empty table. What these cost once a fleet has filled them is UNMEASURED.

-- AlterEnum
-- FIVE NEW VERBS, EACH ONE A NEW WAY FOR A MACHINE TO PUT WORDS IN A GOVERNMENT DOCUMENT, which is
-- why each is a deliberate value in a Postgres enum rather than a string somebody can typo. The
-- kind is what the annexure prints as a layer's NAME (report_ai_layers._KIND_TITLES), so a value
-- Postgres accepted and this build could not name would print as "Machine-generated text" under a
-- heading nobody chose.
--
-- WHY THEY ARE ADDED IN THEIR OWN STATEMENTS, FIRST, AND WHY NOTHING BELOW MENTIONS THEM. Inside a
-- transaction a value added by ALTER TYPE ... ADD VALUE cannot be USED in the same transaction —
-- `unsafe use of new value "PROOFREAD" of enum type "DwAiLayerKind"` — and Prisma sends a migration
-- file as one multi-statement query, which Postgres wraps in an implicit transaction. Migration
-- 20260807120000 records the same trap for "UserRole"."DESIGNER". Nothing below names any of these
-- five: the columns added are TEXT and the new table stores its verb as TEXT, for the reason given
-- against it.
--
-- IF NOT EXISTS on every one, matching 20260724120000 and 20260807120000, so a database that was
-- repaired by hand or restored mid-deploy does not stop the whole migration on a value it already
-- holds.
--
-- PROOFREAD: spelling, grammar and punctuation corrected, and NOTHING ELSE changed. It is deliberately
-- a different value from CLEANED_TRANSCRIPT, which restructures a conversation into speaker turns and
-- may translate it: those are two different promises to a reader, and one heading for both would let
-- a rewrite be printed under the word "proofread".
ALTER TYPE "DwAiLayerKind" ADD VALUE IF NOT EXISTS 'PROOFREAD';
-- EXPANDED: a terse field note written out into prose. THE HIGHEST-RISK VALUE IN THIS ENUM, because
-- it is the only one that INVENTS sentences rather than transforming ones somebody said. A row of
-- this kind may be printed next to an artisan's name in a document a ministry officer reads, so the
-- annexure carries a warning of its own for it and the service refuses to derive one from anybody
-- else's words — see ai_layers.TEXT_ROOTED_KINDS and its note.
ALTER TYPE "DwAiLayerKind" ADD VALUE IF NOT EXISTS 'EXPANDED';
-- TRANSLATION: the same words in another language, ALWAYS AS A SIBLING AND NEVER AS A REPLACEMENT.
-- The original layer stays exactly where it was; this row points at it. That is rule 1 applied to
-- the one verb most likely to be implemented as an overwrite — and the defect already in this
-- repository is exactly that overwrite: on a default REFINED_TRANSLATED deployment the media queue
-- writes an English rewrite into "MediaFile"."transcriptText", where a raw transcript is expected.
ALTER TYPE "DwAiLayerKind" ADD VALUE IF NOT EXISTS 'TRANSLATION';
-- CAPTION: one sentence describing a photograph or a video, for the media annexure and for a screen
-- reader. Media-rooted, and the evidence a reader checks it against is the photograph itself, which
-- the media annexure prints beside it.
ALTER TYPE "DwAiLayerKind" ADD VALUE IF NOT EXISTS 'CAPTION';
-- SUBTITLES: timed text. The one kind whose principal content is STRUCTURE rather than prose — a cue
-- list of {start, end, text} — because a subtitle without its timings is just a transcript, and the
-- timings are the whole of what this verb adds. Stored in "payload" and rendered as SRT or WebVTT by
-- app/services/subtitles.py; "text" carries a plain reading of the same cues so the annexure has
-- something to print and a search has something to match.
ALTER TYPE "DwAiLayerKind" ADD VALUE IF NOT EXISTS 'SUBTITLES';

-- AlterTable
-- Three nullable columns on "DwAiLayer". Every existing row takes NULL in all three, which is what
-- those rows truthfully are: no transcript registered before today was made from supplied text, and
-- none of them is a translation.
ALTER TABLE "DwAiLayer"
  -- THE THIRD KIND OF SOURCE, AND THE ONLY ONE THAT IS EVIDENCE RATHER THAN A POINTER.
  --
  -- A PROOFREAD or an EXPANDED of a designer's field note has no row to point at. The note is being
  -- typed into a form that has not been saved — that is the whole moment the verb is for — and even
  -- once it is saved there is deliberately nowhere on this table to record WHICH field it came from:
  -- `test_the_layer_table_cannot_name_a_place_in_stage_data` forbids "fieldKey", "entityKey",
  -- "stageKey", "entryId" and "ordinal", because a column answering "which box does this belong in"
  -- is the invitation a later feature accepts by writing model prose into that box.
  --
  -- So the evidence travels as a COPY of the words the verb was given, exactly as a layer holds a
  -- copy of a transcript rather than pointing at "MediaFile"."transcriptText". The reason is the
  -- same one REFERENCE_HYDRATION gives: a field edited next March must not silently change what an
  -- annexure printed last year under somebody's name. A pointer would re-resolve; a copy cannot.
  --
  -- IT IS NOT A BACK DOOR FOR MODEL PROSE, and the difference from a `text` field on the register
  -- body — which app/schemas/design_workshops.py refuses in the strongest terms — is which side of
  -- the model the string sits on. This column holds the verb's INPUT, supplied by the caller. The
  -- layer's "text" holds the verb's OUTPUT, produced by a provider THIS SERVER called, with the
  -- provider and model id this server observed. A client can choose what to have proofread; it
  -- cannot choose what the proofreading says it is.
  --
  -- THE HONEST LIMIT, stated here rather than left to be discovered: a supplied-text source is
  -- readable by anybody who may open the workshop, because it is workshop content and not a
  -- recording. A designer who pastes an artisan's transcript into a note has therefore widened who
  -- can read those words beyond the per-file media gate. The route says so in its refusal copy; the
  -- database cannot tell one paragraph from another.
  ADD COLUMN "sourceText" TEXT,
  -- The two halves of a translation's provenance. Recorded as COLUMNS and not inside "payload"
  -- because they are the fact a reader of the annexure needs in the provenance line — "translated
  -- from Odia into English" — and a fact that has to be dug out of a JSON blob is one the report
  -- will eventually stop printing.
  --
  -- "language" (which already exists) keeps its meaning for every other kind: the language the
  -- layer's own text is IN. For a TRANSLATION it equals "targetLanguage", written to both, so a
  -- reader or a query that knows only about "language" is not silently wrong about a translation.
  --
  -- NULLABLE, AND NULL MEANS NOBODY RECORDED IT — never "English". A provider that auto-detects
  -- (ElevenLabs Scribe deliberately is not told a language, because these interviews code-switch
  -- mid-sentence) may report nothing, and 'multi' is a legitimate value here for the same reason
  -- Deepgram Nova-3 is called with language=multi.
  ADD COLUMN "sourceLanguage" TEXT,
  ADD COLUMN "targetLanguage" TEXT;

-- AlterTable
-- How many AI-verb runs ONE DESIGNER may spend per India-time day, across all five verbs together.
--
-- SEPARATE FROM "dwDictationDailyCap", AND THE SEPARATION IS A DECISION RATHER THAN AN OVERSIGHT.
-- Plan §6.1 settled the dictation cap's scope in the user's own words: it "should only apply to the
-- global /dictate when it is utilizing the ElevenLabs / Deepgram / Whisper API". Counting a caption
-- against that number would break the sentence the designer reads — "you have used all 40 of today's
-- dictations that go to the transcription service" would be false on a day spent captioning
-- photographs at a desk — and would let desk work take away a control somebody needs in a courtyard.
-- These verbs also spend a different bill: a chat completion is priced per token and a dictation per
-- second of audio, so one number cannot bound both.
--
-- NULL MEANS UNCAPPED AND 0 MEANS REFUSE EVERY RUN, exactly as its sibling above, and for the same
-- stated reason: both are real settings and a reader who guesses will guess wrong in the expensive
-- direction. The default is NULL because the right number is UNMEASURED, in that word — nothing in
-- this repository has ever measured verb runs per designer per day, and a placeholder shipped here
-- would become the number on the settings screen.
ALTER TABLE "AppSetting"
  ADD COLUMN "dwAiVerbDailyCap" INTEGER;

-- CreateTable
-- One designer's AI-verb spend on one India-time calendar day, per verb.
--
-- MODELLED ON "DwDictationDailyUsage" DELIBERATELY, down to the string day key, so a reader who
-- knows one knows the other — and the day itself is computed by `dictation_cap.ist_day` and by
-- nothing else, because two definitions of "daily" is how a designer ends up with two allowances or
-- none. See that module for why the boundary is midnight IST and why the handset's clock is not
-- consulted.
--
-- THE VERB IS IN THE KEY THOUGH THE CEILING IS SHARED, which looks like a contradiction and is not.
-- The ceiling counts every verb together, because the bill does; the breakdown is kept because the
-- first useful thing anybody will ask when a cap is reached is WHICH verb spent it, and a single
-- counter can never answer that. It costs one short TEXT column and it is the only measurement of
-- what these verbs actually cost that this system will ever have.
--
-- THE VERB IS TEXT AND NOT AN ENUM, unlike "DwAiLayerKind" one statement above, and the difference
-- is what the value DOES. A kind is printed as a heading in a submitted document, so a typo there is
-- a document defect and Postgres must refuse it. This is a meter label: a typo produces a wrong
-- breakdown line and nothing else, and making it an enum would mean a migration every time somebody
-- wants to meter a sixth verb — which is the friction that gets a meter left out instead.
CREATE TABLE "DwAiVerbDailyUsage" (
    "userId" TEXT NOT NULL,
    -- The SERVER's India-time calendar date as 'YYYY-MM-DD'. TEXT and not a timestamp, because what
    -- is stored is a calendar DAY and not an instant — see "DwDictationDailyUsage"."istDay".
    "istDay" TEXT NOT NULL,
    -- PROOFREAD / EXPAND / TRANSLATE / CAPTION / SUBTITLES, as `ai_verbs.Verb` spells them.
    "verb" TEXT NOT NULL,
    -- Runs that actually reached a provider. NOT incremented for a body refused before the call, and
    -- NOT for the 503 that means no provider key is configured: neither spent anything, and charging
    -- a designer's allowance for the server's own misconfiguration is the one refusal they can do
    -- nothing about. The identical rule, for the identical reason, as `dictation_cap.spend`.
    "count" INTEGER NOT NULL DEFAULT 0,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    -- The triple IS the identity, and it serves the only read this table has: "how much has THIS
    -- designer spent TODAY". Leading with the user for "DwDictationDailyUsage"'s reason — every
    -- question here starts with the account — and the day before the verb because the day is what
    -- the allowance is scoped to and the verb is only ever the breakdown.
    CONSTRAINT "DwAiVerbDailyUsage_pkey" PRIMARY KEY ("userId","istDay","verb")
);

-- AddForeignKey
-- CASCADE on the user, matching "DwDictationDailyUsage"."userId" rather than the RESTRICT the
-- decision logs carry. A usage row records nobody taking responsibility for anything — it is a meter
-- reading — so it must never become a new reason an admin cannot delete an account.
ALTER TABLE "DwAiVerbDailyUsage" ADD CONSTRAINT "DwAiVerbDailyUsage_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddCheckConstraint
-- THE DISCRIMINATED SOURCE, WIDENED FROM TWO POINTERS TO THREE SLOTS.
--
-- Dropped and re-created rather than added beside the old one, because two CHECKs over overlapping
-- columns is a row shape refused by one and admitted by the other, and the message Postgres raises
-- names whichever it happened to evaluate first. One rule, one constraint, one name.
--
-- Both NULL-everywhere and two-set-at-once stay impossible, which is the whole point: a layer
-- derived from nothing is model prose with no evidence behind it, and a layer claiming two origins
-- leaves the annexure guessing which one to print under it. num_nonnulls() is a Postgres built-in
-- and needs no extension.
--
-- **THE THING THIS CHECK CANNOT SAY, WRITTEN DOWN SO NOBODY ASSUMES IT DOES.** Which KINDS may take
-- which source is a rule in `ai_layers._check_chain` and not here: a CHECK expressing "SUBTITLES may
-- not stand on supplied text" would be a second copy of a vocabulary that lives in Python, invisible
-- in schema.prisma, and wrong the first time the vocabulary grows. The database enforces the SHAPE;
-- the service enforces the MEANING and answers with a sentence a client can act on.
ALTER TABLE "DwAiLayer" DROP CONSTRAINT IF EXISTS "DwAiLayer_source_is_exactly_one";
ALTER TABLE "DwAiLayer" ADD CONSTRAINT "DwAiLayer_source_is_exactly_one" CHECK (num_nonnulls("sourceMediaId", "sourceLayerId", "sourceText") = 1);
