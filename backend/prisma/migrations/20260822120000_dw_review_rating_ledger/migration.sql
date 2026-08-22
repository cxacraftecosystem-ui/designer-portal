-- Peer and pool review: one designer's rating of one sketch or one prototype.
--
-- The owner's requirement, in their words: designers "rate peers work qualitatively and
-- quantitatively, leave suggestions, and RANK sketches and prototypes" — with "two review levels:
-- workshop peers first, then the whole pool of designers once prototypes are finalised", and
-- "admins and master admins see who rated what, when and how; designers see the same for their own
-- records only".
--
-- Almost none of that needs a table. RANKING DOES NOT: it is "DwStageEntry"."ordinal", which both
-- clients already derive from array order and already move with up/down arrows. The two ROUNDS do
-- not: they are `sketchReview.reviewRound` and `prototypeValidation.reviewRound`, registry fields
-- added in the same change as this file. The FINALISATION event does not: `prototype
-- .peerRoundClosedAt`, likewise. Exactly one thing here cannot be a registry field, and this
-- migration creates exactly that one thing.
--
-- =============================================================================================
-- WHY THE RATING CANNOT BE A REGISTRY FIELD, WHICH IS THE OBVIOUS-LOOKING HOME
-- =============================================================================================
--
-- This is the paragraph to read if anybody proposes carrying ratings in the stage entry's JSON
-- alongside the round fields that DID go there. Three reasons, and any one of them settles it.
--
--   1. ONE-RATING-PER-REVIEWER IS NOT EXPRESSIBLE. A stage entry's answers are a JSON blob in a
--      shared table; a unique index cannot reach inside "data". Enforcing it in the service is a
--      read-then-write with a network round trip in the middle, which is the same hole
--      "DwStageEntry"."clientKey" was given a reserved value to close — two designers on one
--      workshop each found no row and each inserted one. Here the ordinary way it happens is not
--      even concurrency: it is a phone retrying a sync over a flaky link.
--   2. THE REGISTRY'S REF TYPE CANNOT POINT AT AN ACCOUNT. `reference_options` resolves a REF
--      against the five REFERENCE_MODELS — Artisan, ProductDocumentation, ToolDocumentation,
--      Process, Craft — or against an entity of the same workshop. "User" is in neither set, so a
--      reviewer held in a stage entry could only ever be a typed-in name, and the owner's
--      "admins see who rated what" would be answerable to a reader and not to a query.
--   3. THE POOL ROUND IS READ BY THE PEOPLE THE WORKSHOP DOOR TURNS AWAY. A "DwStageEntry" is
--      reachable only through `load_workshop_or_404`, which admits the creator, an admin and the
--      holder of a viewer grant — and which grants READ PLUS STAGE WRITES, because the stage save
--      routes go through that same helper. The pool round is by definition everybody else, so
--      serving it from inside the stage row would mean widening that helper, which would hand every
--      designer in the country write access to every finished workshop's 22 stages. The ledger is a
--      separate object behind a separate, narrower door
--      (`design_ratings.load_ratable_workshop_or_404`).
--
--      NOT because the pool read is cross-workshop — IT IS NOT, and an earlier draft of this file
--      said it was. GET /design-ratings/rounds/{round} requires a workshopId for BOTH rounds,
--      because the placed order is "DwStageEntry"."ordinal" and an ordinal orders one collection
--      inside one workshop. Level 2 is the same list read by a wider audience, not a wider list.
--
-- =============================================================================================
-- AND WHY THIS IS NOT A FOURTH WRITE-ONLY LEDGER
-- =============================================================================================
--
-- This repository already has three tables that are written and never read back: "ReviewLog" has
-- two create sites and no reader, and "DwAiLayerDecision" and "DwWorkshopConsentDecision" both say
-- "RECORDED, NOT YET SERVED" in their own docstrings. That is a defensible omission for an audit
-- trail somebody can reach with psql. It would not be defensible here, because THE RATINGS ARE THE
-- FEATURE: the review page's default sort is the mean of "score", so a table with no reader leaves
-- that page sorting by nothing at all.
--
-- The pairing is PINNED rather than promised. `backend/tests/test_review_rating_ledger.py` fails
-- the day any code under `app/` creates a row here without any code under `app/` reading one back.
--
-- =============================================================================================
-- SHAPE, AND WHERE IT COPIES AND WHERE IT DEPARTS
-- =============================================================================================
--
-- Copied from "DwWorkshopConsentDecision" (20260812120000): the workshop CASCADE, the actor
-- RESTRICT, the two clocks, and the habit of stating each index against the read it serves.
--
-- DEPARTED FROM IT IN ONE WAY, stated rather than glossed: that table is APPEND-ONLY and this one
-- is not. Consent is an event history because a withdrawal must not erase the answer nine sends
-- were made under. A rating is a CURRENT OPINION — a reviewer who moves a score from 3 to 4 has
-- not made two judgements, they have one — so the unique triple below turns the second filing into
-- an update of the first, and "updatedAt" records when they last changed their mind. Ranking reads
-- the opinion; nothing reads a history of it, and keeping one would mean every aggregate had to
-- remember to take only the newest row per reviewer.
--
-- ADDITIVE, AND NO EXISTING ROW IS READ OR REWRITTEN. One new enum type, one new table, one unique
-- index, two plain indexes and three new foreign keys. No existing column is retyped or dropped, no existing
-- constraint is relaxed, and every existing query plans exactly as it did.
--
-- IDEMPOTENT, unlike 20260812120000's straight DDL, and the guards are worth the noise for one
-- reason: this lands in a wave where several agents are applying migrations against one local
-- Postgres, so a half-applied run followed by a re-run is a realistic Tuesday rather than a
-- hypothetical. Everything below is either `IF NOT EXISTS` or wrapped in a catalogue check; the
-- file can be run twice and the second run is a no-op.
--
-- Rolling back is:
--
--   DROP TABLE "DwReviewRating";
--   DROP TYPE "DwReviewRound";
--
-- and nothing else. Nothing outside these two objects references either of them.
--
-- NO PERFORMANCE MEASUREMENT IS OFFERED, and the omission is deliberate rather than lazy — the
-- same omission 20260812120000 makes, for the same stated reason. This table holds ZERO rows on
-- the day it is created and no existing query touches it, so any figure quoted here would be a
-- measurement of an empty table, which is to say invented. The indexes below are sized by READ
-- SHAPE, argued against each. What the real numbers are once a fleet has filled them is UNMEASURED.

-- CreateEnum
-- WHICH AUDIENCE THE RATING CAME FROM. The same two tokens as the registry's REVIEW_ROUND
-- controlled list; `test_review_rating_ledger` asserts the two lists are identical member for
-- member, because a token on one side and not the other is a rating the form can file and the
-- aggregate cannot count.
--
-- An enum and not TEXT, on "DwDictationConsent"'s reasoning and unlike "DwAiVerbDailyUsage"."verb":
-- a meter label's typo costs a wrong breakdown line, but this value decides WHICH POPULATION a mean
-- is taken over, so a typo'd round is a row that silently belongs to neither round and is dropped
-- from both averages. Postgres refuses the typo; TEXT would store it.
--
-- CREATE TYPE has no IF NOT EXISTS, hence the catalogue check.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'DwReviewRound') THEN
    CREATE TYPE "DwReviewRound" AS ENUM ('PEER', 'POOL');
  END IF;
END
$$;

-- CreateTable
CREATE TABLE IF NOT EXISTS "DwReviewRating" (
    "id" TEXT NOT NULL,

    -- The workshop the rated thing belongs to, COPIED off the stage entry and not joined for it.
    -- "Every rating filed in this workshop's peer round" is the level-1 review page, and without
    -- this column that is a join through "DwStageEntry" on every load. It is also what the workshop
    -- CASCADE probes. A stage entry cannot move between workshops, so the copy can never disagree
    -- with its source.
    "designWorkshopId" TEXT NOT NULL,

    -- WHAT IS BEING RATED: the "DwStageEntry" row holding the sketch or the prototype. The row id
    -- and NOT (workshop, entityKey, ordinal), because the ordinal is exactly the thing review
    -- CHANGES — a rating keyed on a position would follow the position rather than the object the
    -- moment anybody dragged the list, which is the one operation this feature exists to support.
    "stageEntryId" TEXT NOT NULL,

    -- 'sketch' or 'prototype', copied from the entry so the pool read filters without the join.
    -- TEXT and not an enum, unlike "round" below, and deliberately. The list of rateable entities
    -- is `design_ratings.RATEABLE_ENTITIES`, a hard-coded frozenset that `load_subject` checks the
    -- entry against; it is NOT read off the registry today, so a sixth reviewable entity needs a
    -- Python edit whichever type this column is. The difference is what ELSE it needs: with TEXT a
    -- constant changes and the next deploy carries it, while an enum also needs an ALTER TYPE
    -- landed in its own migration BEFORE the code that writes the new token — a two-step ordering
    -- across a late-syncing fleet, for a value the service already constrains on the way in.
    "entityKey" TEXT NOT NULL,

    -- WHO RATED IT — a real foreign key and not a typed-in name, which is the whole reason this is
    -- a table rather than a field. "Designers see who rated what for their own records only" is a
    -- predicate on this column, and a name could not carry it.
    "reviewerId" TEXT NOT NULL,

    "round" "DwReviewRound" NOT NULL,

    -- THE QUANTITATIVE HALF, on the registry's QUALITY_RATING scale of 1 to 5 — the same five
    -- tokens "prototypeValidation" already scores five separate qualities on, so a designer meets
    -- one scale on this feature and not two. The bound is a CHECK below rather than a convention.
    "score" INTEGER NOT NULL,

    -- THE QUALITATIVE HALF, in two columns because the owner asked for two things: rate the work,
    -- and leave suggestions. An assessment and a proposed change are different speech acts with
    -- different readers — the first belongs beside the score, the second belongs in the maker's
    -- list of what to do next — and collapsed into one box the suggestions are unfindable inside
    -- the prose. Both NULLABLE: a score with no words is a legitimate rating, and demanding a
    -- sentence for it is how a reviewer stops rating at all.
    "comment" TEXT,
    "suggestion" TEXT,

    -- WHAT THE DEVICE SAID, as distinct from when this server heard it —
    -- "DwWorkshopConsentDecision"."recordedAt"'s column, for its reason stated there in full. A
    -- rating typed in a courtyard reaches the server on the next sync, which on this fleet can be a
    -- fortnight later. NULL when the rating was filed straight against the server, where the two
    -- are the same moment and repeating it would add nothing.
    --
    -- TIMESTAMP(3), the same as every other clock here, AND WIDENING IT IS NOT THE FIX FOR THE
    -- REPLAY RULE — measured on 2026-08-22, because it looks like one. The Prisma query engine
    -- truncates a datetime to milliseconds before Postgres sees it, so with the column at (6) every
    -- stored row still read back as ...053000 and `_is_stale_delivery`'s `incoming <= stored`
    -- stayed false for a redelivery of the identical capture. The column matches what the client
    -- can deliver; the comparison is what has to tolerate the truncation.
    "ratedAt" TIMESTAMP(3),

    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- When the reviewer last changed their mind. Present here and absent on the two decision tables
    -- this is modelled on, because those are append-only and this one is an upsert. See the header.
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DwReviewRating_pkey" PRIMARY KEY ("id")
);

-- AlterColumn, and it exists only to undo an experiment: this migration was briefly applied with
-- "ratedAt" at TIMESTAMP(6), on the wrong theory that Postgres' rounding was what defeated the
-- replay rule. A database created by the CREATE TABLE above is already (3) and this is a no-op on
-- it; one that took the (6) form is put back here rather than by asking anybody to remember.
-- Narrowing to (3) rewrites nothing, because no value in the column has ever had a microsecond
-- component to lose — the client never sent one.
ALTER TABLE "DwReviewRating" ALTER COLUMN "ratedAt" TYPE TIMESTAMP(3);

-- AddCheckConstraint
-- A CHECK and not merely a convention, BECAUSE THIS COLUMN IS AVERAGED. A stray 40 does not look
-- wrong in a row; it looks wrong in every ranking that row is part of, and by then nobody is
-- looking at rows. That is the same test 20260811090000 applied when it added its two CHECKs — row
-- shapes no reader can make sense of — and it is why 20260812120000 added none: nothing there was
-- like this.
--
-- THE RANGE IS SAFE TO FREEZE IN SQL, which the "istDay looks like a date" CHECK that migration
-- rejected was not. That one would have encoded a FORMAT whose definition lived in Python. This one
-- encodes the registry's QUALITY_RATING list, whose five tokens are '1'..'5' and are pinned to this
-- constraint by `test_review_rating_ledger`: widening the scale means changing that list, which
-- fails the test, which sends you here. A scale change is a deliberate migration either way.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'DwReviewRating_score_range'
  ) THEN
    ALTER TABLE "DwReviewRating"
      ADD CONSTRAINT "DwReviewRating_score_range" CHECK ("score" BETWEEN 1 AND 5);
  END IF;
END
$$;

-- CreateIndex
-- ONE PERSON, ONE THING, ONE ROUND — the constraint that stops a reviewer filing five ratings of
-- the same sketch and quintupling their own weight in its mean. In the database and not in the
-- service, because the service alone is a read-then-write and the offline case retries.
--
-- ROUND IS IN THE KEY BECAUSE THE SAME PERSON LEGITIMATELY RATES TWICE. A workshop's own designer
-- is also a member of the pool, and their peer-round view of a prototype and their pool-round view
-- of the finished thing are two judgements of two different objects. Leaving round out would
-- silently discard the second.
--
-- IT IS ALSO THE READ INDEX FOR ONE OBJECT'S RATINGS. Leading with "stageEntryId" means the
-- aggregate behind the default sort — every rating of this sketch — is a prefix probe of this same
-- btree, and the CASCADE from "DwStageEntry" uses it too. No second index is added for either, on
-- "DwDictationDailyUsage"'s stated reasoning: an index nothing probes is a write cost with no read.
CREATE UNIQUE INDEX IF NOT EXISTS "DwReviewRating_stageEntryId_reviewerId_round_key"
  ON "DwReviewRating"("stageEntryId", "reviewerId", "round");

-- THE ROUND LISTING, COLUMN FOR COLUMN. `design_ratings.workshop_ratings` — the one query behind
-- every render of the review tab — is where={designWorkshopId, entityKey, round}, which is exactly
-- these three equality predicates. The leading column is also what the workshop CASCADE probes, so
-- this index does two jobs, the same double duty
-- "DwWorkshopConsentDecision_designWorkshopId_createdAt_idx" does.
CREATE INDEX IF NOT EXISTS "DwReviewRating_designWorkshopId_entityKey_round_idx"
  ON "DwReviewRating"("designWorkshopId", "entityKey", "round");

-- THE RESTRICT BELOW, AND NOTHING ELSE — indexed for the WRITE it is on the wrong end of rather
-- than for any read, exactly as "DwWorkshopConsentDecision_actorId_idx" is. Deleting a user makes
-- Postgres look for a rating naming them, on every account deletion, and without this that is a
-- sequential scan of this table.
--
-- NO "createdAt" BESIDE IT, and no per-reviewer index at all, because NOTHING QUERIES BY REVIEWER.
-- "Who rated what, when" looks like a per-reviewer read and is not one: `visible_rows` takes the
-- round's rows and filters them in Python, and the only reviewer-keyed probe (`existing_rating`'s
-- `find_first` — NOT `rating_plan`, which is a pure planner and issues no query) also names
-- "stageEntryId" and so rides the unique index above. A composite here
-- would be a write cost with no read behind it.
CREATE INDEX IF NOT EXISTS "DwReviewRating_reviewerId_idx"
  ON "DwReviewRating"("reviewerId");

-- NO CROSS-WORKSHOP INDEX, and that is a property of the feature rather than an omission. The pool
-- round reads no wider than the peer round does: GET /design-ratings/rounds/{round} requires a
-- workshopId for BOTH rounds, because the placed order is "DwStageEntry"."ordinal", and an ordinal
-- orders one collection inside one workshop — two prototypes in two workshops are both ordinal 0,
-- so a mixed list has no arrangement anywhere to be stored. Level 2 is THE SAME LIST READ BY A
-- WIDER AUDIENCE, not a wider list, and every read of this table is scoped by workshop or subject.
-- An index leading on "round" would have nothing to probe it.
--
-- If a cross-workshop BROWSE is ever built — a different feature, as that route's docstring says
-- — it needs ("round", "entityKey", "createdAt"), for the reason "DwStageEntry"'s bare "entityKey"
-- index states: a read whose cost grows with the archive rather than with the answer. It is
-- deliberately not added ahead of the query that would use it.

-- AddForeignKey
-- CASCADE on the workshop and on the stage entry, matching every other design-workshop child table.
-- Both fire on a HARD delete only: the API's workshop delete is a soft one and `save_stage`
-- soft-deletes a removed collection row rather than dropping it (there is no hard delete of a
-- "DwStageEntry" anywhere in `app/`), so an ordinary deletion leaves the ratings intact and a
-- restore brings them back with the thing they are about.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'DwReviewRating_designWorkshopId_fkey'
  ) THEN
    ALTER TABLE "DwReviewRating" ADD CONSTRAINT "DwReviewRating_designWorkshopId_fkey"
      FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'DwReviewRating_stageEntryId_fkey'
  ) THEN
    ALTER TABLE "DwReviewRating" ADD CONSTRAINT "DwReviewRating_stageEntryId_fkey"
      FOREIGN KEY ("stageEntryId") REFERENCES "DwStageEntry"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;

  -- RESTRICT on the reviewer, exactly as "DwWorkshopConsentDecision"."actorId",
  -- "DwAiLayerDecision"."actorId" and "ReviewLog"."reviewerId" are. This row says a named designer
  -- judged a colleague's work; the judgement is shown to admins WITH THE NAME ON IT and is counted
  -- into the ranking the maker sees. An account deleted out from under it would leave a score in a
  -- ranking that nobody filed. Deleting such an account already fails on a dozen other relations,
  -- and the message the admin is shown degrades honestly for relations it cannot name
  -- (`_undeletable_detail` in api/routes/users.py).
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'DwReviewRating_reviewerId_fkey'
  ) THEN
    ALTER TABLE "DwReviewRating" ADD CONSTRAINT "DwReviewRating_reviewerId_fkey"
      FOREIGN KEY ("reviewerId") REFERENCES "User"("id")
      ON DELETE RESTRICT ON UPDATE CASCADE;
  END IF;
END
$$;
