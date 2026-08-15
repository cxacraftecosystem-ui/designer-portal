-- Tier 3 consent, per workshop and recorded; and a per-designer daily ceiling on what it permits.
--
-- Plan docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §6 answers 1 and 3, in the user's own words:
--
--   3. "Consent for Tier 3. ANSWERED: per workshop, recorded, and it gates rung 2." A workshop
--      carries an explicit answer to "may recordings and dictation from this workshop leave the
--      device for a third-party provider", WITH WHO SET IT AND WHEN. An account-level setting was
--      rejected because a consent given for one cluster would silently cover the next one, and the
--      artisan whose voice it is changes between them.
--   1. "Cost ceiling for Tier 3 dictation. ANSWERED: a per-designer daily cap, named in words when
--      it is hit." It refuses and says so; it does not fail silently.
--
-- The rules these objects serve are written out in app/services/dictation_consent.py and
-- app/services/dictation_cap.py. What this file records is what the DATABASE does and does not
-- enforce, and the two places it deliberately cannot.
--
-- =============================================================================================
-- WHY CONSENT IS COLUMNS AND A LOG AND NOT A FIELD IN THE REGISTRY
-- =============================================================================================
--
-- This is the paragraph to read if anybody proposes a consent question on stage 1, which is the
-- obvious-looking home. It is wrong five independent ways and any one of them settles it.
--
--   1. IT CANNOT CARRY WHO AND WHEN — decisive on its own, because that is the entire content of the
--      decision. `save_stage`'s UPDATE branch writes exactly {data, ordinal, deletedAt};
--      "DwStageEntry"."createdById" is set on the CREATE branch alone; "DwStageEntry"."updatedAt" is
--      @updatedAt and moves whenever ANY field in that stage's row changes. A consent stored there
--      would be attributed to whoever first saved stage 1 and dated to the last edit of any of stage
--      1's fields.
--   2. IT MOVES `registry_version()`. That digest covers key/type/tier/required/enum/deprecated/
--      derivation/hydration over the module-level STAGES, and it is the refetch signal for a file
--      COMPILED INTO THE APK — the 119 KB assets/design-workshop-schema.json a handset renders forms
--      from before it has ever reached the network. One new FieldSpec marks that asset stale on every
--      handset in the fleet.
--   3. IT FORCES AN ANDROID RELEASE. Two tests pin that asset: one to the digest, one to the full
--      `registry_to_dict()` CONTENT. Anything added there fails the suite until 119 KB is re-dumped.
--   4. THE REPOSITORY ALREADY WROTE THE RULE DOWN, one migration ago, for the immediately preceding
--      feature (app/services/custom_sections.py): "`registry_to_dict()` gains nothing — not a key,
--      not a flag, not one string."
--   5. IT WOULD MOVE EVERY EXISTING WORKSHOP'S COMPLETENESS, because the scorer walks the registry and
--      counts every non-deprecated singleton field into requiredTotal/optionalTotal. A column touches
--      no score at all.
--
-- ADDITIVE, AND EVERY EXISTING ROW TAKES A DEFAULT THAT CHANGES NOTHING. Two new enum types, two new
-- tables, three new columns on "DesignWorkshop" and one on "AppSetting". No existing column is
-- retyped or dropped, no existing constraint is relaxed, and not one stored row is read or rewritten.
--
--   * every existing workshop becomes NOT_RECORDED, which is what it truthfully was: nobody has been
--     asked. It is NOT "granted by default", and that is the one thing this migration must not do —
--     defaulting to GRANTED would silently clear a third-party send for every workshop already in the
--     database, which is precisely the failure the plan rejects an account-level consent for.
--   * "AppSetting"."dwDictationDailyCap" is NULL, which means UNCAPPED, which is today's behaviour.
--     So the cap arrives switched off and the first real number is a deliberate act by a master
--     admin rather than something that happens to a fleet on a deploy.
--
-- Rolling back is:
--
--   DROP TABLE "DwDictationDailyUsage";
--   DROP TABLE "DwWorkshopConsentDecision";
--   ALTER TABLE "AppSetting" DROP COLUMN "dwDictationDailyCap";
--   ALTER TABLE "DesignWorkshop"
--     DROP COLUMN "dictationConsentById", DROP COLUMN "dictationConsentAt",
--     DROP COLUMN "dictationConsent";
--   DROP TYPE "DwDictationConsent";
--
-- and nothing else — nothing outside these objects references any of them, and a workshop whose
-- consent has never been recorded behaves in every particular as it did before the migration ran.
--
-- NO CHECK CONSTRAINTS, unlike 20260811090000. That migration added two because "DwAiLayer" has row
-- shapes no reader can make sense of (a layer derived from nothing, a layer claiming two sources).
-- Nothing here is like that. The two rules that could be written as CHECKs are both worse as CHECKs:
--
--   * "dictationConsentAt IS NOT NULL when the consent is not NOT_RECORDED" would refuse the one
--     legitimate repair somebody will eventually need — an operator correcting a decision on a row
--     whose timestamp was lost — and the service refuses the shape on the way in anyway, with a
--     sentence.
--   * "istDay looks like a date" is a REGEX over a vocabulary that lives in Python (`ist_day`), and a
--     CHECK naming a format is invisible in schema.prisma while it does it.
--
-- NO PERFORMANCE MEASUREMENT IS OFFERED, and the omission is deliberate rather than lazy. Migration
-- 20260808140000 carries EXPLAIN output because it changed how an existing query was planned against
-- 6,952 existing rows. Both tables here hold ZERO rows on the day they are created and no existing
-- query touches them; the three new columns are read by exactly the code added alongside them. Any
-- figure quoted would be a measurement of an empty table — which is to say, invented. The indexes
-- below are sized by READ SHAPE, stated against each. What the real numbers are once a fleet has
-- filled them is UNMEASURED.

-- CreateEnum
-- THREE STATES, NOT A BOOLEAN, and the third is the reason this is an enum at all. "Nobody has asked
-- the artisan yet" and "the artisan said no" both stop a recording being sent, but they are different
-- facts with different next moves — the first is answered by asking, the second only by the artisan
-- changing their mind — and they need different sentences on the phone. A boolean would have had to
-- default to false for every existing workshop, making thirty thousand never-asked workshops
-- indistinguishable from thirty thousand refusals nobody ever made.
--
-- An enum rather than TEXT, on the same reasoning as "DwAiLayerKind" one migration ago and unlike
-- "DwCustomField"."type": three values fixed by a decision recorded in a plan, where a typo'd token
-- is a workshop whose consent state no reader can interpret. Postgres refuses the typo; TEXT would
-- store it. The service ALSO refuses to read an unknown token as anything but NOT_RECORDED, because
-- a consent that cannot be read must gate rather than permit.
CREATE TYPE "DwDictationConsent" AS ENUM ('NOT_RECORDED', 'GRANTED', 'REFUSED');

-- AlterTable
-- The current answer, denormalised onto the workshop so the gate is one column read on a request a
-- designer is standing there waiting on, and so the workshop LIST can show it without a join. The
-- authoritative history is "DwWorkshopConsentDecision" below; this is a cache of its last row, which
-- is the same split "DwAiLayer"."acceptedAt"/"acceptedById" has against "DwAiLayerDecision".
--
-- "dictationConsentAt" IS WHEN THE ARTISAN ANSWERED, NOT WHEN THIS SERVER HEARD IT, and on this fleet
-- the difference is a fortnight: a consent recorded in a courtyard reaches the server on the next
-- sync. A client that answered offline sends the moment it happened and it lands here. When the
-- server heard it is the log row's own "createdAt". Both are kept because they are two questions, and
-- collapsing them would fabricate one of the two answers — a signature dated to the day it was filed.
ALTER TABLE "DesignWorkshop"
  ADD COLUMN "dictationConsent" "DwDictationConsent" NOT NULL DEFAULT 'NOT_RECORDED',
  ADD COLUMN "dictationConsentAt" TIMESTAMP(3),
  ADD COLUMN "dictationConsentById" TEXT;

-- AlterTable
-- How many server dictations ONE DESIGNER may spend per India-time day.
--
-- NULL MEANS UNCAPPED AND 0 MEANS REFUSE EVERY ONE. Both are real settings and neither is a "not set"
-- sentinel; a reader who guesses will guess wrong in the expensive direction, so it is written down
-- in the SQL as well as in schema.prisma and in the service.
--
-- THE DEFAULT IS NULL BECAUSE THE RIGHT NUMBER IS UNMEASURED, IN THAT WORD. Nothing in this
-- repository measures dictations per designer per day, and a placeholder shipped here would become
-- the number on the settings screen. What is known bounds it rather than choosing it: 510 field specs
-- in the registry as counted on the day this migration was written (the "496" repeated in older
-- comments is stale), a 6 MB per-clip ceiling (DICTATION_MAX_BYTES), a four-minute recorder limit at 32 kbit/s
-- which is about 960 KB per clip. The first real number belongs to whoever sees the first bill.
ALTER TABLE "AppSetting"
  ADD COLUMN "dwDictationDailyCap" INTEGER;

-- CreateTable
-- Every answer a workshop has ever given, kept for ever.
--
-- WHY A LOG AND NOT JUST THE THREE COLUMNS. Because consent can be WITHDRAWN, and a withdrawal would
-- erase the answer the sends were made under. An artisan agrees on the 3rd, nine dictations go to a
-- provider over the following week, the artisan changes their mind on the 9th and a designer records
-- REFUSED — with columns alone the database now says this workshop never permitted anything, and
-- nothing is left that explains the nine transcripts sitting in it. That is the same argument
-- "DwAiLayerDecision" carries about a report already in an officer's hands.
CREATE TABLE "DwWorkshopConsentDecision" (
    "id" TEXT NOT NULL,
    "designWorkshopId" TEXT NOT NULL,
    "decision" "DwDictationConsent" NOT NULL,
    "note" TEXT,
    "actorId" TEXT NOT NULL,
    -- WHAT THE DEVICE SAID, as distinct from when this server heard it. NULL when the answer was
    -- recorded straight against the server, where "createdAt" is the same moment and repeating it
    -- would add nothing. A separate column for "DwAiLayer"."producedAt"'s reason, stated there in
    -- full: two different questions, and collapsing them means one of the two answers is fabricated.
    "recordedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DwWorkshopConsentDecision_pkey" PRIMARY KEY ("id")
);

-- CreateTable
-- One designer's server-dictation spend on one India-time calendar day.
--
-- AUTHORITATIVE HERE AND NOWHERE ELSE. A client-side counter is a client-side counter: process memory
-- clears on a swipe-away, and a spend ceiling defeated by a swipe-away is the one direction that costs
-- money rather than a retry. The handset's copy exists so the dictation control can fall back to the
-- phone's own recogniser BEFORE recording, not so it can enforce anything.
--
-- A TABLE, AND NOT A COUNTER IN MEMORY OR IN REDIS. One uvicorn worker per pod is deliberate, but prod
-- runs two replicas: an in-process counter would give every designer TWICE the cap in production and
-- once in staging, and a cap wrong by the replica count is worse than no cap because the number on the
-- settings screen would be a lie. Redis is not there to fall back on either — the cache backend
-- defaults to "memory" and the compose Redis is profile-gated — so a Redis INCR+EXPIRE day key would
-- silently do nothing on a default deployment. Postgres is the only always-present store. Redis may
-- later sit IN FRONT of this table; it may never replace it.
--
-- AND NOT app/scale/rate_limit.py, which looks like the obvious home and is disqualified four times
-- over: flag-gated OFF by default, keyed on a digest of the BEARER TOKEN or the client IP and never on
-- a user id (so one designer's phone and laptop are two buckets), in-process unless Redis is
-- configured, and its own docstring calls it "a courtesy backstop … NOT a security control".
--
-- NO RESET JOB, because the key IS the day: a new day is a new row. The old rows are ordinary history,
-- about one per designer per working day, and they still answer "what did we spend last month" by
-- summing. That is also why this is not a per-dictation event table — one row per sentence across the
-- registry's 510 fields per workshop per designer, to answer a question nobody has asked yet.
CREATE TABLE "DwDictationDailyUsage" (
    "userId" TEXT NOT NULL,
    -- The SERVER's India-time calendar date as 'YYYY-MM-DD'. TEXT and not a timestamp, because what is
    -- stored is a calendar DAY and not an instant: a timestamp would have to pick a time of day to
    -- stand for midnight IST and every reader would have to remember which one.
    --
    -- IST, AND THE BOUNDARY IS THE POINT — "daily" means nothing without a stated boundary. A UTC day
    -- would reset a designer's allowance at 05:30 IST, mid-morning to this fleet, which
    -- api/routes/public.py calls "visibly wrong to the people it is for" about the same choice for the
    -- census. The HANDSET's timezone is deliberately not consulted: two phones with different clocks
    -- would hold two different allowances against one server row. Computed with a fixed +05:30 offset
    -- by `dictation_cap.ist_day`, for that module's stated reason (India has observed one offset since
    -- 1945, and zoneinfo needs a tz database a Windows box does not have).
    "istDay" TEXT NOT NULL,
    -- Uploads that actually reached a transcription provider. NOT incremented for a clip refused for
    -- size, and NOT for the 503 that means no provider is configured — neither spent anything, and
    -- charging a designer's allowance for the server's own misconfiguration is the one refusal they
    -- can do nothing about. See `dictation_cap.spend`.
    "count" INTEGER NOT NULL DEFAULT 0,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    -- THE PAIR IS THE IDENTITY, and it serves the only read this table has: "how much has THIS
    -- designer spent TODAY". No secondary index and no scan — the same argument
    -- "DesignWorkshopViewer"'s composite primary key makes, with the leading column chosen the same
    -- way, because every question here starts with the user.
    CONSTRAINT "DwDictationDailyUsage_pkey" PRIMARY KEY ("userId","istDay")
);

-- CreateIndex
-- Indexed for the WRITE it is on the wrong end of rather than for any read: "dictationConsentById" is
-- ON DELETE SET NULL, so deleting a user makes Postgres find every workshop naming them, and without
-- this that is a sequential scan of the workshop table inside a request an admin is waiting on. The
-- same reasoning as "DwAiLayer"'s three account indexes.
CREATE INDEX "DesignWorkshop_dictationConsentById_idx" ON "DesignWorkshop"("dictationConsentById");

-- The history of one workshop's consent, in order — the only read that table has.
CREATE INDEX "DwWorkshopConsentDecision_designWorkshopId_createdAt_idx" ON "DwWorkshopConsentDecision"("designWorkshopId", "createdAt");
-- The actor, indexed for the RESTRICT below rather than for a read: deleting a user makes Postgres
-- look for a decision row naming them, and that lookup happens on every account deletion.
CREATE INDEX "DwWorkshopConsentDecision_actorId_idx" ON "DwWorkshopConsentDecision"("actorId");

-- AddForeignKey
-- SET NULL on the cache pointer, exactly as "DwAiLayer"."acceptedById" is and for its stated reason:
-- the record of the ACT is the log below, whose actor is RESTRICT, so this is not what lets the
-- account go — anybody who has ever recorded a consent is undeletable for as long as that log stands.
-- What SET NULL buys is narrower and still worth having: a hand-removed account leaves the workshop
-- with its answer and an empty name ("cleared by somebody no longer on record") rather than a
-- half-applied delete, and — the part that matters here — the consent does NOT silently revert to
-- NOT_RECORDED, which would re-open the third-party send question on a workshop that had settled it.
ALTER TABLE "DesignWorkshop" ADD CONSTRAINT "DesignWorkshop_dictationConsentById_fkey" FOREIGN KEY ("dictationConsentById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- CASCADE on the workshop, matching every other design-workshop child table. It fires only on a HARD
-- delete; the API's delete is a soft one, so an ordinary deletion leaves the consent history intact
-- and a restore brings it back with the workshop.
ALTER TABLE "DwWorkshopConsentDecision" ADD CONSTRAINT "DwWorkshopConsentDecision_designWorkshopId_fkey" FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- RESTRICT on the actor, exactly as "DwAiLayerDecision"."actorId" and "ReviewLog"."reviewerId" are,
-- and for a sharper version of their reason: this row says A NAMED PERSON TOOK RESPONSIBILITY FOR AN
-- ARTISAN'S VOICE LEAVING THE DEVICE for a third party. An account deleted out from under it would
-- leave the record saying somebody's recordings were cleared for export by nobody. Deleting such an
-- account already fails on a dozen other relations, and the message the admin is shown degrades
-- honestly for relations it cannot name (`_undeletable_detail` in api/routes/users.py).
ALTER TABLE "DwWorkshopConsentDecision" ADD CONSTRAINT "DwWorkshopConsentDecision_actorId_fkey" FOREIGN KEY ("actorId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- CASCADE on the usage row's user, matching "DesignWorkshopViewer"."userId" and
-- "WorkshopAssignment"."userId" rather than the RESTRICT above. A usage row records nobody taking
-- responsibility for anything — it is a meter reading — so it must never become a new reason an admin
-- cannot delete an account.
ALTER TABLE "DwDictationDailyUsage" ADD CONSTRAINT "DwDictationDailyUsage_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
