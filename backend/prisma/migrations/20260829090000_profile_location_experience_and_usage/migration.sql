-- Three changes the same wave asked for: a designer's own address moved onto "Location", experience
-- becoming years AND months on both models that record it, and the first table in this repository
-- that records anybody LOOKING at anything.
--
-- Generated with `prisma migrate diff --from-schema-datamodel ... --script` and then corrected by
-- hand -- see the IF NOT EXISTS note and the two CHECK constraints below, neither of which
-- `migrate diff` can emit because neither is expressible in schema.prisma.
--
-- They ride together because they are one edit of one file and every one of them is additive: three
-- nullable columns across two existing tables, and one new table nothing has written to yet. No
-- column dropped, no column retyped, no constraint relaxed and no existing index touched. Every row
-- in the database plans and prints exactly as it did yesterday.
--
-- =============================================================================================
-- 1. THE DESIGNER'S ADDRESS: "DesignerProfile"."locationId"
-- =============================================================================================
--
-- The owner, 2026-08-29: "My designer profile is missing district and the map point" -- the rest of
-- the record pages have both.
--
-- They have both because they hang off "Location", which splits an address into two groups that
-- answer two different questions: PROVENANCE (latitude, longitude, altitude, accuracy, capturedAt,
-- placeName, address -- where the DEVICE was) and STATED ADDRESS (state, district, village, pincode,
-- subjectLatitude, subjectLongitude -- where the SUBJECT is). "DesignerProfile" had neither group.
-- It had a flat addressLine/city/state/pincode and no district and no coordinate anywhere.
--
-- SO IT GETS THE RELATION AND NOT FOUR MORE LOOSE COLUMNS. A district column here would be a second
-- spelling of a district, a second validator pairing it with a state, and a fourth reimplementation
-- of the picker, the map pin and the coordinate-versus-state mismatch flag that "LocationFields"
-- already draws for the six owners that came before. The FK shape is copied from those six exactly:
-- the column on the OWNER and never on "Location", nullable, ON DELETE SET NULL, and NO INDEX --
-- none of the six carries one, this row is reached by "userId" and never by its location, and the
-- only probe of the column is Postgres's own on a "Location" delete.
--
-- NOTHING IS COPIED OUT OF THE FLAT COLUMNS AND NOTHING IS DROPPED. addressLine, city, state and
-- pincode stay exactly where they are, holding exactly what they hold, because they are still the
-- only place the live rows' addresses exist. A backfill is not merely deferred here, it is
-- IMPOSSIBLE TO DO HONESTLY: "Location"."latitude"/"longitude" are NOT NULL, so a row cannot be
-- manufactured for a profile that has an address and no coordinate without inventing the
-- coordinate -- which is the precise failure the "Location" docstring was rewritten to end, arriving
-- this time by a migration rather than by a form. A later migration retires the four columns once a
-- person has moved the values across, and it has one decision to make that this one cannot make for
-- it: "city" has no home on the far side. "Location" carries district and village; a designer in
-- Ahmedabad is stating neither.
--
-- AND WHY THAT LEAVES "latitude"/"longitude" NOT NULL. Relaxing them is the other way to close this
-- gap and it was rejected. It would weaken the invariant on all six field-record owners -- where it
-- is load-bearing, and where `require_location` in schemas/common.py depends on it -- to save one
-- screen a click, and it would retype every reader of every location in the repository from float to
-- an optional float. The cost of the choice is real and is written on the column: a designer who
-- wants their district recorded must also give a coordinate, by pressing 'Use current GPS'
-- DELIBERATELY, dropping a pin, or typing the two numbers. The form already states that rule in
-- those words and only states it once an address has been started.
--
-- THE ONE THING THE FORM MUST NOT DO is auto-capture. A designer profile is the only form in this
-- system whose subject is the person filling it in, always edited from a desk -- so it is at once
-- the likeliest place for a device fix to be stamped on and the place where the result would look
-- most plausible. "Designer based in Kharagpur" is a sentence nobody would query, and it is the same
-- sentence that put fifteen artisans in Rajasthan, Gujarat, Uttarakhand and Andhra Pradesh at
-- 22.31 N, 87.31 E.
--
-- =============================================================================================
-- 2. EXPERIENCE IN YEARS AND MONTHS: "Artisan"."experienceMonths", "DesignerProfile"."experienceMonths"
-- =============================================================================================
--
-- The requirement is two dropdowns on one line, and the read-back has to hand the form back exactly
-- what was chosen. That is why this is a second COLUMN on each model and not a single
-- "experienceTotalMonths" the two boxes are computed out of: a stored 66 has to be re-divided on
-- every read, and that arithmetic cannot tell "5 years and 6 months" from "66 months" from "five and
-- a half years". It would also make "experienceYears" -- read by four export surfaces and printed in
-- the participant table of every submitted report -- a derived column overnight, to save one integer.
--
-- ON "Artisan" IT IS THE SECOND ANSWER, NOT THE FIRST. 20260823093000 added "craftStartDate" and
-- made the date the real answer: `derive_experience_years` reads the date first, the stated column
-- second, and the legacy `extraMetadata` spellings third. Months slot into exactly that precedence.
-- Where a row has a date, BOTH dropdowns are arithmetic on that one date -- the date already
-- contains the month, so nothing new is stored and the two boxes can never come to disagree with
-- what they were computed from. Where it does not, the two columns are what a person stated.
--
-- ON "DesignerProfile" IT IS THE WHOLE ANSWER, because there is nothing on that model to derive
-- from. Its only other DateTime is "empanelmentDate", which is the day a ministry listed a designer
-- and not the day they began practising. That asymmetry is why both columns are added in one file:
-- a reader who finds one of them must find the other, and must find this paragraph explaining why
-- the two behave differently.
--
-- NO BACKFILL, AND THE COLUMNS ARE NULLABLE RATHER THAN DEFAULT 0. This is 20260823093000's refusal
-- applied to a narrower case and it is the reason this section exists. Every row in both tables was
-- written by a form that asked for years alone. A DEFAULT 0 would put "and no months" on record for
-- all of them as a stated fact, and the second dropdown would open with an answer already selected
-- on records whose artisan or designer was never asked the question. An artisan who said "about
-- thirty years" said nothing whatever about months. Absent and zero are different answers, this
-- repository's forms already turn on that distinction, and NULL is the only one of the two that is
-- true here.
--
-- THE CHECK CONSTRAINTS, WHICH `migrate diff` DID NOT WRITE AND WHICH ARE DELIBERATE. 0..11 is
-- frozen in SQL although 0..70 and 0..90 on the years columns beside them are not, and the
-- difference is what owns the bound. A ceiling on YEARS is policy -- it is 70 on one model and 90 on
-- the other, it is stated in Pydantic, and moving it is somebody's decision. A ceiling on MONTHS is
-- the calendar: it is 11 on every model, in every country, for ever, and no policy can move it. That
-- is the test 20260822120000 applied when it took one CHECK and refused another -- freeze what
-- cannot change, leave what Python owns to Python -- and this is the side of it that gets a
-- constraint. What it actually catches is a client sending a TOTAL where a remainder belongs: a 60
-- in this column is not an odd value, it is five years filed as months, and the pair no longer
-- round-trips. The clients must still bound it in Pydantic so the refusal reaches a designer as a
-- 422 rather than as a 500; the constraint is the backstop for the day one of the three or four
-- mirrored copies of that bound is forgotten.
--
-- =============================================================================================
-- 3. USAGE: THE "UsageEvent" TABLE
-- =============================================================================================
--
-- Requirements 22-25 ask how designers navigate the platform and where it is slow. Nothing in this
-- system could answer either half: there is no page-view, screen-view, session or navigation record
-- anywhere, no table, no endpoint and no client code on web or Android. Everything that looks like
-- it might be one records somebody CHANGING something -- "RecordRevision" and "ReviewLog" audit
-- writes, "DwReportExport" counts one feature, the two daily meters count AI spend -- and none of
-- them records anybody LOOKING at anything, which is the whole of what navigation is.
--
-- NOT NAMED "Analytics". That word is taken at every layer by the cross-workshop CONTENT comparison
-- -- /analytics/design-workshops, /admin/analytics, the guard entry, the nav label
-- "Cross-workshop analytics" -- which observes no user and writes nothing. Two unrelated meanings of
-- one word, one of them a table that records people and the other a comparison of craft outcomes,
-- is a collision this repository has already been warned about in writing.
--
-- "routeTemplate" AND NEVER THE INTERPOLATED PATH. "/design-workshops/{workshop_id}/stages/
-- {stage_key}", not "/design-workshops/3f9c.../stages/sketches". Every record id in this API travels
-- in the path, so a table of raw paths would be a per-designer reading list of other people's
-- artisans, sketches and interviews -- assembled with no access check, kept for ever, and readable
-- by anybody who can query the table. `access.REVISION_REDACTED_FIELDS` exists because "a retraction
-- which copies the retracted value into an append-only table is not a retraction"; refusing the id
-- before it is written is the stronger form of the same discipline. THERE IS NO QUERY-STRING COLUMN
-- for the same reason, and the second reason is arithmetic: a million distinct paths group into a
-- million groups of one, and the template is the only form in which the question has an answer.
--
-- SHAPED FOR BATCHED WRITES. `DATABASE_CONNECTION_LIMIT` is 10 and was deliberately cut to it from
-- 40, `gather_reads` is bounded by that width, and the dashboard route carries a written warning not
-- to raise it to fit something new in -- so an INSERT in the request path is not affordable. Nothing
-- in this table forces one: no unique constraint, so no upsert; no counter, so no read-modify-write;
-- no updatedAt, so no row is ever revisited; and a cuid the client library generates without asking
-- the database for anything. One `create_many` per flush is the intended writer.
--
-- TWO INDEXES AND NOT THREE. (userId, createdAt) and (routeTemplate, createdAt): equality first,
-- range last, which is the only order a btree serves both halves of, and the same shape as
-- "DwReportExport"'s and "DwWorkshopConsentDecision"'s. The first is also what Postgres probes for
-- the CASCADE. There is deliberately no index on createdAt alone -- a whole-window scan across every
-- user and every route is a report nobody has asked for, and it would be paid for on every insert
-- into what will be by far the highest-write table here.
--
-- CASCADE ON THE USER, joining the two daily meters rather than the decision ledgers. A usage row is
-- not a record of anybody taking responsibility for anything -- it is an observation OF a person --
-- so it must never become a new reason an admin cannot delete an account, and when an account goes,
-- what the system noticed about that person goes with it. SetNull was the alternative and it loses
-- on honesty: it would make NULL mean "nobody was signed in" AND "the person has since been
-- deleted", so every count of unauthenticated traffic would quietly include ex-colleagues.
--
-- CONSENT IS FLAGGED AND NOT SETTLED. Observing designers is a new category of personal data in this
-- repository -- everything else it holds about a person is something that person typed in. This
-- codebase already models consent explicitly where it collects a recording ("DwDictationConsent" is
-- three states and not a boolean, precisely so "not asked" cannot be read as "no"), and there is no
-- equivalent for watching a colleague navigate. "consentState" is a nullable place to RECORD an
-- answer and NOT a flow that asks the question; NULL means nobody was asked, which is the honest
-- state of every row written before such a flow exists AND the only way those rows can be found and
-- deleted afterwards. It is a String and not the audio enum because a designer's browsing consent
-- and an artisan's recording consent must not be one fact. THE TABLE MUST NOT BE WRITTEN TO UNTIL
-- THAT DECISION IS TAKEN: creating it is not the same as switching it on, and this migration
-- deliberately ships the store ahead of the middleware.
--
-- =============================================================================================
-- WHY THIS IS SAFE TO APPLY, AND ROLLING BACK
-- =============================================================================================
--
-- Additive throughout. Three nullable columns added to two existing tables, plus one empty table of
-- nine; nothing is dropped, retyped, re-defaulted or relaxed; no existing index changes, so every
-- existing query plans exactly as it does today. A client that has never heard of any of this is
-- unaffected, which matters because the field handsets run offline for a fortnight at a time.
--
-- IF NOT EXISTS throughout, for 20260822120000's stated reason, which still holds today: this lands
-- in a wave where several agents apply migrations against one local Postgres, so a half-applied run
-- followed by a re-run is a realistic Tuesday.
--
-- THERE IS NO DOWN-MIGRATION IN THIS PROJECT and pushing main deploys with no test gate, so the
-- rollback is hand-run and is worth stating in full:
--
--   ALTER TABLE "Artisan"         DROP COLUMN "experienceMonths";
--   ALTER TABLE "DesignerProfile" DROP COLUMN "experienceMonths";
--   ALTER TABLE "DesignerProfile" DROP COLUMN "locationId";
--   DROP TABLE "UsageEvent";
--
-- Each drop takes its own constraints and indexes with it and nothing else references any of them.
-- No data is lost by any of the four, because no data is moved or copied by this file: the flat
-- address columns still hold every address they held, and both experience columns are NULL on every
-- row that exists on the day this runs.

-- AlterTable
-- INTEGER and not SMALLINT, matching "experienceYears" beside it. Prisma maps `Int?` to INTEGER, and
-- a SMALLINT here would be the one column in these two tables the client did not expect.
ALTER TABLE "Artisan" ADD COLUMN IF NOT EXISTS "experienceMonths" INTEGER;

-- AlterTable
ALTER TABLE "DesignerProfile" ADD COLUMN IF NOT EXISTS "experienceMonths" INTEGER;
ALTER TABLE "DesignerProfile" ADD COLUMN IF NOT EXISTS "locationId" TEXT;

-- AddCheckConstraint
-- 0..11, on both tables, for the reason set out in section 2: this is the calendar and not a policy.
-- NULL passes a CHECK in Postgres, so no existing row is touched and no backfill is implied.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'Artisan_experienceMonths_range'
  ) THEN
    ALTER TABLE "Artisan"
      ADD CONSTRAINT "Artisan_experienceMonths_range" CHECK ("experienceMonths" BETWEEN 0 AND 11);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'DesignerProfile_experienceMonths_range'
  ) THEN
    ALTER TABLE "DesignerProfile"
      ADD CONSTRAINT "DesignerProfile_experienceMonths_range"
      CHECK ("experienceMonths" BETWEEN 0 AND 11);
  END IF;
END
$$;

-- CreateTable
CREATE TABLE IF NOT EXISTS "UsageEvent" (
    "id" TEXT NOT NULL,

    -- NULL is an answer and not a gap: an unauthenticated request still has a route, a status and a
    -- duration, and "the sign-in page is slow for the people who cannot get in" is exactly the kind
    -- of thing this table exists to be able to show.
    "userId" TEXT,

    -- The matched route's TEMPLATE. Read off the route AFTER the handler has run, because the router
    -- is what populates it; never the interpolated path. See section 3 -- this is the column the
    -- whole table is arranged around.
    "routeTemplate" TEXT NOT NULL,

    -- "GET" / "POST" / "PATCH" / "DELETE", and "web" / "android" / "api". TEXT and not enums, on
    -- "DwAiVerbDailyUsage"."verb"'s reasoning: these are meter labels, not something printed in a
    -- document, and an enum would turn an unenumerated value into a failed INSERT -- which on a
    -- batched writer discards a whole flush rather than one row. "clientApp" is additionally
    -- CLIENT-SUPPLIED, so an enum would let one handset with a typo in a header do that.
    "method" TEXT NOT NULL,
    "statusCode" INTEGER NOT NULL,

    -- Wall-clock milliseconds inside the middleware. An INTEGER because sub-millisecond precision
    -- answers nothing anybody asked, and because it measures THE SERVER: no network time, no render
    -- time, nothing a person actually waited for.
    "durationMs" INTEGER NOT NULL,
    "clientApp" TEXT NOT NULL,

    -- Nullable, and NULL means nobody was asked. See section 3: a place to record an answer, not a
    -- flow that asks the question, and the NULLs are how the rows collected beforehand stay
    -- findable.
    "consentState" TEXT,

    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "UsageEvent_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX IF NOT EXISTS "UsageEvent_userId_createdAt_idx" ON "UsageEvent"("userId", "createdAt");
CREATE INDEX IF NOT EXISTS "UsageEvent_routeTemplate_createdAt_idx" ON "UsageEvent"("routeTemplate", "createdAt");

-- AddForeignKey
DO $$
BEGIN
  -- SET NULL, and no index on the column, exactly as the six record types that already point at
  -- "Location" are. Deleting a place must not delete a person.
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'DesignerProfile_locationId_fkey'
  ) THEN
    ALTER TABLE "DesignerProfile" ADD CONSTRAINT "DesignerProfile_locationId_fkey"
      FOREIGN KEY ("locationId") REFERENCES "Location"("id")
      ON DELETE SET NULL ON UPDATE CASCADE;
  END IF;

  -- CASCADE on the user, matching the two daily meters. An observation of a person must never become
  -- a new reason an admin cannot delete their account.
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'UsageEvent_userId_fkey'
  ) THEN
    ALTER TABLE "UsageEvent" ADD CONSTRAINT "UsageEvent_userId_fkey"
      FOREIGN KEY ("userId") REFERENCES "User"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
END
$$;
