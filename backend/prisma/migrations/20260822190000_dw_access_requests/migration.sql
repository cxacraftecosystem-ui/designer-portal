-- A designer asking to be let into a design & prototype workshop they are not on.
--
-- =============================================================================================
-- THE GAP THIS CLOSES, AND WHY IT IS A GAP RATHER THAN A MISSING NICETY
-- =============================================================================================
--
-- The owner's rule is that a designer may only PARTICIPATE in a workshop, and only once they are on
-- that workshop's list of designers. Both halves of that are already built and are not weakened
-- here: `can_create_design_workshops` refuses a designer the create (rival copies of one workshop
-- on two devices is how a fortnight of fieldwork gets split in half), and every route in
-- `api/routes/design_workshop_viewers.py` is `Depends(require_admin)`.
--
-- What follows from those two, and had no answer, is that a designer standing next to the person
-- who created the workshop — holding the card that person just printed — could not ask. There was
-- no endpoint. The web client already knows it: `unresolvedWorkshopCodeMessage` in
-- `frontend/lib/workshopCodes.ts` returns a sentence telling a designer who scans a workshop card
-- they cannot open to send the code to an admin and ask, and the comment above that `return` says
-- why — "AND IT DOES NOT SAY 'REQUEST SENT', because nothing has been sent ... any UI over this must
-- not dress that up as a submitted request". A previous wave deliberately shipped no client-side
-- "pending" state rather than tell somebody their request was queued somewhere no admin would ever
-- look, and named this table as the prerequisite.
--
-- =============================================================================================
-- WHY A SECOND TABLE RATHER THAN COLUMNS ON "DesignWorkshopViewer"
-- =============================================================================================
--
-- One row carrying both the ask and the grant is the shape "WorkshopAssignment" took, and that
-- table demonstrates the problem rather than avoiding it. The two objects have OPPOSITE retention
-- rules, and each is argued for in its own place:
--
--   * A VIEWER ROW IS CURRENT FACT and is DELETED when access ends. 20260808120000's header states
--     why: "A grant here carries no decision to audit — it never refused anybody and was never
--     asked for — so a tombstone would record only that an admin changed their mind."
--   * A REQUEST AND ITS REFUSAL ARE HISTORY and are kept for ever. "WorkshopAssignment"."status"
--     carries the same rule in its own comment: DENIED rows are kept "so a user cannot silently
--     re-request their way around a refusal".
--
-- Merged, one of those rules has to give: either removing a viewer leaves a tombstone the viewers
-- PUT has to learn to skip, or a refusal is destroyed the next time an admin edits the roster.
--
-- Keeping them apart is also what keeps the promise that there is exactly ONE way to be a viewer.
-- NOTHING READS THIS TABLE TO DECIDE ACCESS. `load_workshop_or_404` still asks `has_viewer_grant`
-- and nothing else; granting a request calls `design_workshop_viewers.replace_viewers`, so a
-- GRANTED row here is a receipt for a write that happened in the other table.
--
-- =============================================================================================
-- WHAT THE UNIQUE INDEX IS FOR, WHICH IS NOT TIDINESS
-- =============================================================================================
--
-- One row per (workshop, requester), enforced by Postgres and not by the service. A request typed
-- in a courtyard reaches this server whenever the handset next finds signal and a flaky link
-- retries; the same ask arriving twice must be one row, and a designer pressing the button again
-- next week must not deposit a second card in an admin's queue. A read-then-write in the service
-- is not that promise — it is two round trips with a window in the middle, which is the shape this
-- repository has already shipped a double-filed government record from.
--
-- =============================================================================================
-- ADDITIVE, AND ROLLING BACK
-- =============================================================================================
--
-- Two new enum types, one new table, one unique index, two plain indexes and three foreign keys.
-- No existing column is added, dropped or retyped, no existing constraint is relaxed, and every
-- existing query plans exactly as it did. Rolling back is:
--
--   DROP TABLE "DesignWorkshopAccessRequest";
--   DROP TYPE "DwAccessRequestSource";
--   DROP TYPE "DwAccessRequestStatus";
--
-- and nothing else references any of the three.
--
-- IDEMPOTENT, for 20260822120000's stated reason: this lands in a wave where several agents are
-- applying migrations against one local Postgres, so a half-applied run followed by a re-run is a
-- realistic Tuesday. Everything below is either IF NOT EXISTS or wrapped in a catalogue check.
--
-- NO PERFORMANCE MEASUREMENT IS OFFERED, deliberately and for the same reason 20260822120000 gives:
-- this table holds zero rows on the day it is created and no existing query touches it, so any
-- figure here would be a measurement of an empty table. The indexes are sized by READ SHAPE and
-- each is argued against the read it serves.

-- CreateEnum
-- WHAT A REQUEST CAN BE. Three states and no fourth: there is deliberately no REVOKED, unlike
-- "WorkshopAssignment"."status", because revoking access is taking a name off
-- "DesignWorkshopViewer" and leaves no question here to answer. A request that was granted and
-- later revoked stays GRANTED — it records that the ASK was answered yes, not that access stands.
--
-- An enum and not TEXT, on "DwReviewRound"'s reasoning: this column decides WHICH QUEUE a row is
-- in, so a typo'd status is a request that appears neither in the pending list an admin works
-- through nor in the settled history they audit — somebody waiting for access that nobody can see
-- they asked for, which is the exact failure this feature exists to end.
--
-- CREATE TYPE has no IF NOT EXISTS, hence the catalogue checks.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'DwAccessRequestStatus') THEN
    CREATE TYPE "DwAccessRequestStatus" AS ENUM ('PENDING', 'GRANTED', 'DENIED');
  END IF;
END
$$;

-- CreateEnum
-- HOW THE REQUESTER CAME BY THE ID THEY ASKED ABOUT. SCAN means the request carried a well-formed
-- DPW1:G:... code that decoded to this workshop; MANUAL means it did not.
--
-- IT IS EVIDENCE, NOT AUTHORISATION. The code's four check characters are FNV-1a and the algorithm
-- ships to the browser; `frontend/lib/workshopCodes.ts` says so in terms — "it is a typo detector
-- and nothing more ... anyone can compute a valid check for any id". SCAN therefore does not prove
-- possession of a card and must never be read as though it did. What it is worth is what was asked
-- for: an admin can see at a glance whether a request came from a real code or a bare id.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'DwAccessRequestSource') THEN
    CREATE TYPE "DwAccessRequestSource" AS ENUM ('SCAN', 'MANUAL');
  END IF;
END
$$;

-- CreateTable
CREATE TABLE IF NOT EXISTS "DesignWorkshopAccessRequest" (
    "id" TEXT NOT NULL,

    "designWorkshopId" TEXT NOT NULL,

    -- WHO IS ASKING. NOT NULL, unlike "WorkshopAssignment"."requestedById", which is nullable
    -- because that column's job is to tell an admin's grant from a user's ask on a SHARED row.
    -- This table holds only asks, so a request nobody filed is not a state that exists.
    "requestedById" TEXT NOT NULL,

    "status" "DwAccessRequestStatus" NOT NULL DEFAULT 'PENDING',
    "source" "DwAccessRequestSource" NOT NULL,

    -- THE CODE AS IT WAS SCANNED, in its canonical DPW1:G:...:CHCK form, NULL for a MANUAL ask.
    -- Kept as the string rather than reduced to the "source" beside it so an admin can compare it
    -- against the card in front of them. It carries no identity data by construction — the encoder
    -- refuses at runtime to encode anything not shaped like an id this repository issues, naming
    -- Aadhaar and Pehchan numbers specifically.
    "scannedCode" TEXT,

    -- What the requester wants to say to the admin. Optional: a bare ask is legitimate, and
    -- demanding a sentence is how people stop asking.
    "note" TEXT,

    "decidedById" TEXT,
    "decidedAt" TIMESTAMP(3),
    "decisionNote" TEXT,

    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DesignWorkshopAccessRequest_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
-- ONE ASK PER PERSON PER WORKSHOP — the whole idempotency guarantee, in the database. See the
-- header. It is also the probe behind "has this person already asked", which the request route
-- makes on every call, so the constraint costs no extra index.
CREATE UNIQUE INDEX IF NOT EXISTS "DesignWorkshopAccessRequest_designWorkshopId_requestedById_key"
  ON "DesignWorkshopAccessRequest"("designWorkshopId", "requestedById");

-- CreateIndex
-- THE QUEUE, COLUMN FOR COLUMN: where status = 'PENDING' ordered by "createdAt" ascending. A range
-- scan of this btree rather than a sort of every request ever filed. Oldest-first is what stops a
-- queue nobody finishes, and it is why "createdAt" is the second column rather than absent.
--
-- The leading column is also what the workshop CASCADE below would probe if it could; it cannot
-- (status is not the workshop id), which is why the unique index above carries that job instead —
-- "designWorkshopId" leads it.
CREATE INDEX IF NOT EXISTS "DesignWorkshopAccessRequest_status_createdAt_idx"
  ON "DesignWorkshopAccessRequest"("status", "createdAt");

-- CreateIndex
-- The two account pointers, indexed for the onDelete actions they are on the wrong end of rather
-- than for any read — the same reasoning as 20260822093000 applied across this schema. Deleting a
-- user makes Postgres look for requests naming them, on every account deletion, and the unique
-- index above cannot serve "requestedById" because it is its SECOND column.
CREATE INDEX IF NOT EXISTS "DesignWorkshopAccessRequest_requestedById_idx"
  ON "DesignWorkshopAccessRequest"("requestedById");
CREATE INDEX IF NOT EXISTS "DesignWorkshopAccessRequest_decidedById_idx"
  ON "DesignWorkshopAccessRequest"("decidedById");

-- AddForeignKey
DO $$
BEGIN
  -- CASCADE on the workshop, matching every other design-workshop child table. It fires on a HARD
  -- delete only: the API's workshop delete is a soft one, so an ordinary deletion leaves the queue
  -- intact and a restore brings it back with the workshop.
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'DesignWorkshopAccessRequest_designWorkshopId_fkey'
  ) THEN
    ALTER TABLE "DesignWorkshopAccessRequest"
      ADD CONSTRAINT "DesignWorkshopAccessRequest_designWorkshopId_fkey"
      FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;

  -- CASCADE on the requester, matching "DesignWorkshopViewer"."userId" and deliberately NOT the
  -- RESTRICT the authorship relations carry. A request is not research anybody recorded; it is a
  -- person having once asked to be let in, which is worth nothing once the account is gone.
  -- RESTRICT would invent a brand-new reason an admin cannot delete an account, and one no screen
  -- anywhere offers to clear — see `_undeletable_detail` in api/routes/users.py.
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'DesignWorkshopAccessRequest_requestedById_fkey'
  ) THEN
    ALTER TABLE "DesignWorkshopAccessRequest"
      ADD CONSTRAINT "DesignWorkshopAccessRequest_requestedById_fkey"
      FOREIGN KEY ("requestedById") REFERENCES "User"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;

  -- SET NULL on the decider, matching "DesignWorkshopViewer"."grantedById". The decision must
  -- outlive the admin who made it; "who refused this" is worth having and is not worth an outage.
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'DesignWorkshopAccessRequest_decidedById_fkey'
  ) THEN
    ALTER TABLE "DesignWorkshopAccessRequest"
      ADD CONSTRAINT "DesignWorkshopAccessRequest_decidedById_fkey"
      FOREIGN KEY ("decidedById") REFERENCES "User"("id")
      ON DELETE SET NULL ON UPDATE CASCADE;
  END IF;
END
$$;
