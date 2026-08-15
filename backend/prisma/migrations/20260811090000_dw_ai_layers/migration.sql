-- Provenance for AI output: what a model produced, what from, and who accepted it.
--
-- Step 2 of docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §5. NOTHING IN THIS MIGRATION MAKES ANY AI
-- CALL and no route added alongside it writes model output; the tables land before the writing does,
-- deliberately, because the plan says so in one line: put provenance in "before there is a backlog
-- of unattributed AI text". A transcript already in the archive with no record of which of four
-- providers produced it is exactly that backlog, one row deep.
--
-- THE LAW THESE TABLES ENFORCE (plan §3, and app/services/ai_layers.py in full):
--
--   1. Every layer is a ROW, never an edit. No AI output overwrites its input, ever.
--   2. Every layer carries provenance: source, tier, provider/model id, model version, timestamp,
--      language.
--   3. A layer is INERT until a person accepts it, and acceptance records who and when.
--   4. The report prints the ACCEPTED layer and names it as such.
--   5. Deleting a derived layer NEVER touches its source.
--
-- It is the law `REFERENCE_HYDRATION` already obeys, one step further out: hydration copies a
-- referenced record's display fields at SAVE time and the report never re-resolves them, because a
-- record edited in 2027 must not silently change a document handed to a ministry officer in 2026.
-- Re-running a summariser six months later is the same failure with worse consequences — it
-- replaces an artisan's account of their own work with a different paraphrase, and nothing on the
-- page would say it had happened.
--
-- PURELY ADDITIVE. Two new tables and three new enum types. No existing table gains, loses or
-- retypes a column; no existing constraint is relaxed; not one stored row is rewritten or read.
-- `MediaFile.transcriptText` and every `DwStageEntry.data` blob are exactly as they were, and the
-- media queue's write path is untouched. Rolling back is:
--
--   DROP TABLE "DwAiLayerDecision";
--   DROP TABLE "DwAiLayer";
--   DROP TYPE "DwAiDecision"; DROP TYPE "DwAiTier"; DROP TYPE "DwAiLayerKind";
--
-- and nothing else — nothing outside these two tables references either of them.
--
-- NO PERFORMANCE MEASUREMENT IS OFFERED HERE, and the omission is deliberate rather than lazy. The
-- sibling migration 20260808140000 carries EXPLAIN output because it changed how an existing query
-- was planned against 6,952 existing rows. These tables hold zero rows on the day they are created
-- and no existing query touches them, so any figure quoted for them would be a measurement of an
-- empty table — which is to say, invented. The indexes below are sized by READ SHAPE (stated
-- against each one), not by a timing, and that distinction is the honest one to record. What the
-- real numbers are, once a fleet has filled these tables, is unmeasured.
--
-- TWO CHECK CONSTRAINTS, WHICH PRISMA CANNOT SEE. Prisma has no syntax for CHECK, so both live only
-- in this file and are invisible in schema.prisma. Migration 20260808140000 refused to add a
-- partial INDEX as raw SQL for that reason, and the reasoning does not carry over: Prisma DOES diff
-- indexes, so an index it cannot see is one a later generated migration would emit a DROP for,
-- whereas check constraints are outside what its diff models at all. That is a behaviour of the
-- tool as it stands, not something this repository has measured — so if a generated migration is
-- ever seen to contain a DROP CONSTRAINT for either name below, that is the tool changing its mind,
-- and `app/services/ai_layers.py` refuses both shapes independently in the meantime. Two guards for
-- one rule, on purpose: the constraint answers for rows this API never sees (a backfill run by
-- hand), the service answers with a sentence a client can act on.

-- CreateEnum
-- The vocabulary is exactly the plan's two chains plus the two extractive verbs §2.1 says to start
-- with, and nothing else:
--     audio ──▶ RAW_TRANSCRIPT ──▶ CLEANED_TRANSCRIPT ──▶ SUMMARY
--     photo ──▶ OCR_TEXT       ──▶ STRUCTURED_TEXT
--     (either) ──▶ TAGS, METADATA
-- An enum rather than a TEXT column — unlike "DwStageEntry"."stageKey", which is free text because
-- the 22 stages are Python literals that deploy with the server. Seven values fixed by a plan are
-- not like that, and a typo'd kind would print as an unknown annexure heading in a submitted
-- document. Postgres refuses the typo; TEXT would store it.
CREATE TYPE "DwAiLayerKind" AS ENUM ('RAW_TRANSCRIPT', 'CLEANED_TRANSCRIPT', 'SUMMARY', 'OCR_TEXT', 'STRUCTURED_TEXT', 'TAGS', 'METADATA');

-- CreateEnum
-- Which machine produced it: 1 on the handset, 2 a small model on the handset, 3 the cloud. This is
-- the column that makes device-dependent tiering safe at all (plan §2.1) — a 4 GB Galaxy M32 and a
-- 12 GB flagship will not run the same tier, so the fleet WILL produce two classes of record, and
-- without this they are indistinguishable on the page.
--
-- Spelled TIER_1 rather than stored as an integer for two reasons: Postgres can then refuse a 0 or
-- a 4 without a CHECK constraint nobody can see, and nothing computes with a tier. An integer would
-- invite a "higher is better" ordering the plan denies — Tier 1 is the only tier that works in a
-- courtyard with no signal.
CREATE TYPE "DwAiTier" AS ENUM ('TIER_1', 'TIER_2', 'TIER_3');

-- CreateEnum
CREATE TYPE "DwAiDecision" AS ENUM ('ACCEPTED', 'WITHDRAWN');

-- CreateTable
CREATE TABLE "DwAiLayer" (
    "id" TEXT NOT NULL,
    "designWorkshopId" TEXT NOT NULL,
    "kind" "DwAiLayerKind" NOT NULL,
    "tier" "DwAiTier" NOT NULL,
    -- Exactly one of the next two is set: media for the raw rung of a chain, layer for every rung
    -- above it. See the CHECK below.
    "sourceMediaId" TEXT,
    "sourceLayerId" TEXT,
    -- NOT NULL, and allowed to hold the exact string 'UNRECORDED'. The unknown is not hypothetical:
    -- `media_queue._transcript_write` stores transcriptText/Summary/Status/Error and NOT which of
    -- the four providers in `services/ai.py` produced them, so every transcript already in this
    -- archive has a provider nobody wrote down. Stating that is honest; guessing it is not, and a
    -- NULL would read as "none" rather than "not recorded".
    "provider" TEXT NOT NULL,
    "modelId" TEXT NOT NULL,
    "modelVersion" TEXT,
    -- 'multi' is a legitimate value here, not a placeholder: Deepgram Nova-3 is called with
    -- language=multi precisely because a workshop is Hindi code-switched with English mid-sentence.
    "language" TEXT,
    -- When the MODEL ran, which is not when this row was written. NULL rather than a now() default,
    -- because a transcript produced last March and registered today would otherwise carry today's
    -- date as a fact about the model run — a fabricated timestamp of exactly the kind rule 2 exists
    -- to prevent. "createdAt" always says when the row appeared.
    "producedAt" TIMESTAMP(3),
    "text" TEXT,
    "payload" JSONB,
    -- Rule 3. Null on every insert; only a person pressing accept sets them. The authoritative
    -- history is "DwAiLayerDecision"; these two are the current state, kept here so the report
    -- builder can pick the accepted layer without walking a log per layer per render.
    "acceptedAt" TIMESTAMP(3),
    "acceptedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "createdById" TEXT,
    -- Soft delete, exactly as "DesignWorkshop"."deletedAt" and "DwStageEntry"."deletedAt": a
    -- designer's two weeks of fieldwork is not something a mis-tap should end, and a declined
    -- suggestion is worth keeping as a declined suggestion.
    "deletedAt" TIMESTAMP(3),
    "deletedById" TEXT,

    CONSTRAINT "DwAiLayer_pkey" PRIMARY KEY ("id")
);

-- CreateTable
-- Every acceptance and withdrawal, kept for ever. Modelled on "ReviewLog", which does the same job
-- for record review: an append-only row per decision carrying the actor and the moment, beside a
-- current-state column on the record itself.
--
-- WHY A LOG AND NOT JUST THE TWO COLUMNS. Withdrawal would erase an acceptance a report has already
-- printed. A designer accepts a cleaned transcript on the 3rd, a .docx goes to the directorate on
-- the 4th naming it as accepted, somebody withdraws it on the 11th — with columns alone
-- "acceptedById" returns to NULL and the document in the officer's hand names an acceptance the
-- database now denies ever happened.
CREATE TABLE "DwAiLayerDecision" (
    "id" TEXT NOT NULL,
    "layerId" TEXT NOT NULL,
    "decision" "DwAiDecision" NOT NULL,
    "note" TEXT,
    "actorId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DwAiLayerDecision_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
-- Every read of this table starts with one workshop, so every one of them starts here: the list
-- screen (a whole workshop, newest first — it narrows by kind in Python, because deciding who may
-- read a layer's text means walking its chain down to a recording and a narrowed query would be
-- missing the parents) and the report's accepted-layer read, which is the one that does ask SQL for
-- a kind. Leading with the workshop is what keeps the cost proportional to the ANSWER rather than to
-- the archive — the distinction 20260808140000 was written to make about "DwStageEntry", learned
-- here in advance instead of after the sequential scan.
CREATE INDEX "DwAiLayer_designWorkshopId_kind_createdAt_idx" ON "DwAiLayer"("designWorkshopId", "kind", "createdAt");
-- "What has already been derived from this recording" — the question the create path asks before
-- registering a transcript, so the same text cannot be put into one annexure twice.
CREATE INDEX "DwAiLayer_sourceMediaId_idx" ON "DwAiLayer"("sourceMediaId");
-- The chain walk: given a layer, the rungs above it. Also what makes the CASCADE below cheap —
-- deleting a recording has to find the rungs standing on the rung it is deleting.
CREATE INDEX "DwAiLayer_sourceLayerId_idx" ON "DwAiLayer"("sourceLayerId");
-- The three account pointers, indexed for the WRITE they are on the wrong end of rather than for a
-- read this step performs: all three are ON DELETE SET NULL, so deleting a user makes Postgres find
-- every layer naming them, and without these that is three sequential scans of this table inside a
-- request an admin is waiting on.
CREATE INDEX "DwAiLayer_acceptedById_idx" ON "DwAiLayer"("acceptedById");
CREATE INDEX "DwAiLayer_createdById_idx" ON "DwAiLayer"("createdById");
CREATE INDEX "DwAiLayer_deletedById_idx" ON "DwAiLayer"("deletedById");
-- The history of one layer, in order.
CREATE INDEX "DwAiLayerDecision_layerId_createdAt_idx" ON "DwAiLayerDecision"("layerId", "createdAt");
-- The actor, indexed for the RESTRICT below rather than for a read: deleting a user makes Postgres
-- look for a decision row naming them, and that lookup happens on every account deletion.
CREATE INDEX "DwAiLayerDecision_actorId_idx" ON "DwAiLayerDecision"("actorId");

-- AddForeignKey
-- CASCADE on the workshop, matching every other design-workshop child table. It fires only on a
-- HARD delete; the API's delete is a soft one, so an ordinary deletion leaves the layers intact and
-- a restore brings them back with the workshop.
ALTER TABLE "DwAiLayer" ADD CONSTRAINT "DwAiLayer_designWorkshopId_fkey" FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- CASCADE on the source media, and chosen for a narrow reason rather than a principled one.
-- RESTRICT would be the principled choice — a transcript is evidence and should not vanish with its
-- audio — but it would also give this table a VETO over deleting a "MediaFile", which the media
-- routes permit today. Adding a new refusal to another lane's endpoint is not this step's to make.
-- Recorded here so the next reader knows it was decided rather than overlooked.
ALTER TABLE "DwAiLayer" ADD CONSTRAINT "DwAiLayer_sourceMediaId_fkey" FOREIGN KEY ("sourceMediaId") REFERENCES "MediaFile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- CASCADE on the source layer, and it has to be the same action as the source media above or an
-- ordinary media delete becomes a 500.
--
-- RESTRICT reads like the principled choice here — a hard delete of a layer something was derived
-- FROM would destroy the derived row, which is rule 5 read backwards — and this file said RESTRICT
-- first, reasoning that nothing in this codebase hard-deletes a layer so the constraint could only
-- ever fire against a hand-written statement. That reasoning missed the cascade three lines up.
-- DELETE /api/media/{id} is a HARD delete (`db.mediafile.delete`, api/routes/media.py:740), and on a
-- default REFINED_TRANSLATED deployment registering one recording writes TWO rows: a raw rung
-- pointing at the media file and a cleaned rung pointing at the raw rung. Deleting that recording
-- cascades into the raw rung, whose deletion the cleaned rung's RESTRICT then refuses — inside
-- Postgres, where `delete_media` has no handler for a foreign-key violation, so it reaches the admin
-- as "Something went wrong on the server. The error has been logged." (app/main.py:295). That is the
-- defect the user-delete route already had to grow an except clause for (api/routes/users.py:283),
-- and this migration is not entitled to plant it in another lane's endpoint.
--
-- The consequence, stated rather than discovered: deleting a recording now destroys the whole chain
-- derived from it AND the acceptance log underneath it, because "DwAiLayerDecision" cascades from
-- the layer. That is a real loss. Making it a refusal instead means giving this table a veto over
-- another lane's endpoint, which is the change this step deliberately does not make.
ALTER TABLE "DwAiLayer" ADD CONSTRAINT "DwAiLayer_sourceLayerId_fkey" FOREIGN KEY ("sourceLayerId") REFERENCES "DwAiLayer"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- SET NULL for the three User pointers on the layer itself: the row must outlive the account, and
-- for `createdById`/`deletedById` a departed colleague is not made into a new reason an admin cannot
-- delete a user. `acceptedById` is SET NULL for consistency and NOT because it lets the account go —
-- every acceptance also writes a "DwAiLayerDecision" whose actor is RESTRICT below, so an account
-- that has accepted anything is undeletable for as long as that log stands. What SET NULL buys there
-- is that a hand-removed account leaves "accepted by somebody no longer on record" rather than a
-- half-applied delete.
ALTER TABLE "DwAiLayer" ADD CONSTRAINT "DwAiLayer_acceptedById_fkey" FOREIGN KEY ("acceptedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "DwAiLayer" ADD CONSTRAINT "DwAiLayer_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "DwAiLayer" ADD CONSTRAINT "DwAiLayer_deletedById_fkey" FOREIGN KEY ("deletedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "DwAiLayerDecision" ADD CONSTRAINT "DwAiLayerDecision_layerId_fkey" FOREIGN KEY ("layerId") REFERENCES "DwAiLayer"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- RESTRICT on the decision's actor, exactly as "ReviewLog"."reviewerId" is, and for the same
-- reason: this row says a named person took responsibility for putting model output into a
-- government document, and an account deleted out from under it would leave the record saying the
-- decision was made by nobody.
ALTER TABLE "DwAiLayerDecision" ADD CONSTRAINT "DwAiLayerDecision_actorId_fkey" FOREIGN KEY ("actorId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddCheckConstraint
-- THE DISCRIMINATED SOURCE PAIR, enforced by the database rather than only by the service.
-- num_nonnulls() is a Postgres built-in and needs no extension. Both NULL would be a layer derived
-- from nothing — model prose with no evidence behind it, which is the one row shape this table
-- exists to make impossible. Both set would be a row claiming two origins, and the annexure would
-- have to guess which one to print under it.
ALTER TABLE "DwAiLayer" ADD CONSTRAINT "DwAiLayer_source_is_exactly_one" CHECK (num_nonnulls("sourceMediaId", "sourceLayerId") = 1);

-- A layer with no content at all is a provenance record for nothing: it would take a row, a
-- heading and an index line in the annexure and print nothing underneath. Prose kinds fill "text",
-- extractive kinds (TAGS, METADATA) fill "payload"; which kind uses which is the service's
-- judgement, but that at least one is present is the database's.
ALTER TABLE "DwAiLayer" ADD CONSTRAINT "DwAiLayer_has_content" CHECK ("text" IS NOT NULL OR "payload" IS NOT NULL);
