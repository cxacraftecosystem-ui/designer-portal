-- Scanning a card becomes a real join path: a printed JOIN CARD, its redemptions, and a
-- capture-only PROVISIONAL foothold for the late-comer whose single-use card was already spent.
--
-- =============================================================================================
-- WHAT THIS CHANGES ABOUT WHAT A CODE IS WORTH — READ THIS FIRST
-- =============================================================================================
--
-- Every sentence already written in this repository about the scanned code is TRUE and stays true:
--
--   "The four check characters are FNV-1a over the payload, the algorithm ships to every browser,
--    and anyone can compute a valid check for any id ... It is a typo detector and nothing more.
--    It is not a signature and must never be described as one."
--
-- That is a statement about the RECORD-NAMING code, `DPW1:<letter>:<recordId>:CHCK`, and it remains
-- exactly as true after this migration as before it. A record code is a LOCATOR: authorisation is
-- decided server-side by the record's own rules and the code contributes nothing to that decision.
--
-- **NO ENDPOINT MAY EVER TREAT "PRESENTED A SYNTACTICALLY VALID RECORD CODE" AS GROUNDS FOR
-- ACCESS.** The moment one does, the FNV check becomes a credential and every browser holds the
-- forgery algorithm.
--
-- What this migration adds is a DIFFERENT ARTEFACT with a DIFFERENT GRAMMAR:
--
--   DPW2:J:<recordId>.<22-character secret>:CHCK
--
-- The `J` letter is deliberately ABSENT from `TYPE_LETTER` in `frontend/lib/workshopCodes.ts`, so a
-- join card is not a record code, cannot be resolved by the record-lookup path in any of the three
-- clients, and cannot be mistaken for one. The authority is the 22-character (110-bit) SECRET, not
-- the check characters — those are still a typo detector on the new grammar, doing the one job they
-- were always worth. The version is 2 rather than a new letter on version 1 because
-- `_SUPPORTED_CODE_VERSIONS` only ever grows and an OLD client meeting a v2 string already answers
-- the correct sentence: "That card was printed against a newer code format (2) than this server
-- reads. Update the app."
--
-- =============================================================================================
-- THE SECRET IS NOT IN THIS DATABASE
-- =============================================================================================
--
-- "RecordAccessToken"."secretHash" is SHA-256 of the secret; "secretLast4" is the tail an admin
-- needs to match a row against the card in somebody's hand. THE SECRET ITSELF IS RETURNED ONCE, AT
-- MINT, AND IS NEVER STORED. A database dump, a replica, a backup or a log line is therefore not a
-- bundle of live keys. This is the one place this schema holds a bearer credential and it holds it
-- the way a password table does.
--
-- There is NO SIGNATURE and no new key, and `JWT_SECRET` is specifically not reused: it is
-- constrained to HMAC by `_ALLOWED_JWT_ALGORITHMS`, offline verification would mean shipping it in
-- an APK where it mints session tokens for any subject including the master admin, and
-- `SECRETS_ENCRYPTION_KEY` is already derived from it. Unforgeability here is 110 bits of CSPRNG
-- output in a UNIQUE-indexed column, which is all the single-use / arrival-order / revocation
-- design can use anyway: every one of those requires server state per card no matter what.
--
-- =============================================================================================
-- WHERE THE PROVISIONAL STATE LIVES, AND WHY IT IS NOT A COLUMN ON "DesignWorkshopViewer"
-- =============================================================================================
--
-- **THIS IS THE LOAD-BEARING DECISION IN THIS MIGRATION.**
--
-- A `level` column on "DesignWorkshopViewer" was designed and rejected. `has_viewer_grant` reads
-- the EXISTENCE of a viewer row and is consulted from four places
-- (`design_workshops.load_workshop_or_404`, `design_ratings`, `routes/questionnaire_forms`,
-- `design_workshop_access`), and TWO MORE READS DO NOT GO THROUGH IT AT ALL:
-- `questionnaire_forms._visible_questionnaire_where` writes the relation filter by hand, and
-- `records._design_workshop_media_branches` follows `visible_to_clause` on the stated instruction
-- that "the day that widens again the audio widens with it". A level column means every one of
-- those six admits an UNADJUDICATED SCAN until each is individually taught the difference. Miss one
-- and a forged card reads another designer's fieldwork, or an artisan's recorded voice.
--
-- So the foothold is a SEPARATE TABLE, "DesignWorkshopProvisionalMember", and **NOTHING THAT
-- DECIDES READ ACCESS CONSULTS IT.** To every existing read a provisional member is a stranger,
-- which is the correct default and the only one that is safe. A later wave that owns
-- `services/design_workshops.py` opens the capture path explicitly, route by route.
-- `backend/tests/test_design_workshop_provisional_isolation.py` is the tripwire that keeps this
-- true: it asserts a provisional row does NOT satisfy `has_viewer_grant`.
--
-- Anyone who later "simplifies" this into a boolean on the viewer row has removed the security
-- property. The second argument is 20260808120000's own header: a viewer row is CURRENT FACT that
-- is DELETED when access ends, which is the opposite of what a provisional foothold needs.
--
-- =============================================================================================
-- THE SEAT, AND WHY THERE ARE TWO UNIQUE INDEXES IN TWO TABLES
-- =============================================================================================
--
-- "usesConsumed" is a SEAT ALLOCATOR and not a cached COUNT(*). It is maintained by ONE conditional
-- compare-and-swap UPDATE — `WHERE "id" = $1 AND "usesConsumed" = <the value just read> AND
-- "revokedAt" IS NULL` — whose row lock is what makes SERVER ARRIVAL ORDER decide who gets the full
-- grant. Under READ COMMITTED a concurrent redeemer blocks on that lock and then re-evaluates the
-- predicate against the committed row, so it matches zero rows and takes the provisional path.
-- There is no read-then-write and therefore no window of the kind that has already shipped a
-- double-filed government record here. "RecordAccessToken_within_maxUses_check" below is the
-- database-level backstop against a FUTURE code path that forgets.
--
-- The two unique indexes answer two different questions and neither can do the other's job:
--
--   * "DesignWorkshopAccessRequest"."designWorkshopId","requestedById" — one ASK per person per
--     workshop, the existing offline idempotency guarantee, untouched.
--   * "RecordAccessTokenRedemption"."tokenId","userId" — one SEAT per person per card. Without it,
--     one person with two handsets spends a multi-use card twice and a replayed offline delivery
--     spends a single-use card a second time.
--
-- =============================================================================================
-- THE HANDSET CLOCK IS UNTRUSTED, AND THAT IS ENFORCED BY WHICH COLUMN ANYTHING READS
-- =============================================================================================
--
-- "serverArrivedAt" IS THE AUTHORITY. "scannedAtClient" and "DesignWorkshopAccessRequest"
-- ."scannedAt" are EVIDENCE beside it and NOTHING may compare them to decide an outcome. Ordering
-- by a number a phone's settings screen can change hands the grant to whoever winds their clock
-- back furthest. "scannedAtElapsedSec"/"syncedAtElapsedSec" are Android's monotonic
-- `SystemClock.elapsedRealtime`, from which a clock-independent estimate can be derived
-- (`serverArrivedAt - (syncedAtElapsedSec - scannedAtElapsedSec)`); a reboot invalidates it, which
-- "bootId" is what makes visible. All of it is shown in the queue and none of it decides anything.
--
-- THE COROLLARY THE OWNER SHOULD SEE STATED: this makes FIRST-TO-SYNC the winner, not
-- first-to-scan. "First to scan" would require holding every card unresolved for a settling window
-- — nobody gets access until it closes — and then adjudicating on a clock nobody can trust. There
-- is no third option, and requirement 6 is what makes first-to-sync survivable: the late-comer is
-- not refused.
--
-- DELIBERATELY WEAKER THAN "DwReviewRating"."ratedAt", which `design_ratings._is_stale_delivery`
-- DOES compare to decide whether a write applies. There a wrong device clock costs one rating; here
-- it would win a single-use seat.
--
-- =============================================================================================
-- ADDITIVE, AND ROLLING BACK
-- =============================================================================================
--
-- Three new enum types, three new tables, three new nullable-or-defaulted columns on two existing
-- tables, seven indexes, three CHECKs and nine foreign keys. No existing column is dropped or
-- retyped, no existing constraint is relaxed, and every existing query plans exactly as it did:
-- the only columns added to existing tables are nullable, so every existing row is untouched and
-- there is no backfill.
--
-- THERE ARE NO DOWN-MIGRATIONS IN THIS PROJECT. By hand it is:
--
--   ALTER TABLE "DesignWorkshopViewer" DROP COLUMN "tokenId";
--   ALTER TABLE "DesignWorkshopAccessRequest" DROP COLUMN "scannedAt", DROP COLUMN "tokenId";
--   DROP TABLE "DesignWorkshopProvisionalMember";
--   DROP TABLE "RecordAccessTokenRedemption";
--   DROP TABLE "RecordAccessToken";
--   DROP TYPE "DwTokenRedemptionReason";
--   DROP TYPE "DwTokenRedemptionOutcome";
--   DROP TYPE "DwCodeRecordType";
--
-- and nothing else references any of them.
--
-- ⚠ THE THREE CHECK CONSTRAINTS CANNOT BE EXPRESSED IN schema.prisma AND LIVE HERE ONLY.
-- `prisma migrate` preserves them; `prisma db push` does not, and `migrate dev` will report drift
-- it cannot name. Use `migrate deploy`. 20260811090000, 20260812150000 and 20260822120000 already
-- carry CHECKs on the same terms.
--
-- IDEMPOTENT, for 20260822120000's stated reason: this lands in a wave where several agents are
-- applying migrations against one local Postgres, so a half-applied run followed by a re-run is a
-- realistic Tuesday. Everything below is either IF NOT EXISTS or wrapped in a catalogue check.
--
-- NO PERFORMANCE MEASUREMENT IS OFFERED, for the same reason 20260822190000 gives: these tables
-- hold zero rows on the day they are created and no existing query touches them, so any figure
-- here would be a measurement of an empty table. Each index is argued against the read it serves.

-- CreateEnum
-- THE TEN RECORD TYPES A `DPW` CODE CAN NAME, in `TYPE_LETTER`'s declaration order. An ELEVENTH
-- hand-kept copy of that list, which is why `tests/test_design_workshop_grant_tokens.py` pins it
-- against the letter table the grammar itself uses, exactly as `DwWorkshopCodesTest.kt` pins the
-- letters on Android.
--
-- ONLY DESIGN_WORKSHOP CAN GRANT MEMBERSHIP TODAY, and that is a fact about the schema rather than
-- a policy: of the ten, only designWorkshop ("DesignWorkshopViewer") and workshop
-- ("WorkshopAssignment") have a per-record membership table at all. The other eight are gated by
-- `records.owned_or_granted_where`, which is ACCOUNT-level, so there is nothing to be inducted
-- into and a code can only ever be a pointer. The mechanism is generic from day one because adding
-- this column later means an ALTER whose backfill default would lie; `mint_grant` REFUSES the other
-- eight rather than minting a card that silently admits nobody.
--
-- CREATE TYPE has no IF NOT EXISTS, hence the catalogue checks.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'DwCodeRecordType') THEN
    CREATE TYPE "DwCodeRecordType" AS ENUM (
      'ARTISAN', 'CRAFT', 'WORKSHOP', 'PRODUCT', 'PROCESS', 'TOOL',
      'QUESTIONNAIRE', 'MEDIA', 'DESIGN_WORKSHOP', 'PROTOTYPE'
    );
  END IF;
END
$$;

-- CreateEnum
-- WHAT ONE REDEMPTION GOT. Two values, and there is deliberately no REFUSED: a refusal writes
-- nothing at all, because a row per forged string is a table anybody can grow by posting random
-- bytes — a denial-of-service with an audit trail attached.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'DwTokenRedemptionOutcome') THEN
    CREATE TYPE "DwTokenRedemptionOutcome" AS ENUM ('FULL', 'PROVISIONAL');
  END IF;
END
$$;

-- CreateEnum
-- WHY A REDEMPTION LANDED WHERE IT DID, for the admin reading the queue. OK is the only one that
-- pairs with FULL. ALREADY_SPENT is the late-comer. EXPIRED is a genuine scan that reached the
-- server after the card's date, judged by SERVER arrival so a device clock cannot buy an extension.
-- INELIGIBLE is the one nobody expects and must not be silent: `replace_viewers` validates the
-- WHOLE resulting viewer set, so a perfectly good card can fail because a COLLEAGUE's empanelment
-- lapsed — and that becomes a provisional foothold plus a queue entry naming it, rather than a 422
-- in a courtyard about somebody else's roster row.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'DwTokenRedemptionReason') THEN
    CREATE TYPE "DwTokenRedemptionReason" AS ENUM ('OK', 'ALREADY_SPENT', 'EXPIRED', 'INELIGIBLE');
  END IF;
END
$$;

-- CreateTable
CREATE TABLE IF NOT EXISTS "RecordAccessToken" (
    -- An ordinary internal cuid, and — unlike the secret — NOT a credential. It is what the revoke
    -- button names and what "DesignWorkshopViewer"."tokenId" points at.
    "id" TEXT NOT NULL,

    "recordType" "DwCodeRecordType" NOT NULL,
    -- NO FOREIGN KEY, and that is the PRICE of one generic table instead of ten rather than an
    -- oversight. Affordable ONLY because a card confers nothing by itself: redemption re-reads the
    -- record (refusing a missing or soft-deleted one) and only then writes the membership table, so
    -- an orphaned token is inert. A hard DELETE of a workshop leaves dead cards; they admit nobody.
    "recordId" TEXT NOT NULL,

    -- SHA-256 (hex, lower case) OF THE SECRET, never the secret. UNIQUE below, which is where the
    -- unforgeability actually lives: 110 bits in an indexed column with no oracle but a
    -- rate-limited authenticated endpoint.
    "secretHash" TEXT NOT NULL,
    -- The last four characters, for the admin's list only. Four characters of a 110-bit secret is
    -- 20 bits — useless to a guesser, and exactly enough to match a row against a card.
    "secretLast4" TEXT NOT NULL,

    -- WHOSE CARD THIS IS. SET NULL below, matching "DesignWorkshopViewer"."grantedById": the trail
    -- must outlive the admin who made it, and losing the account does not lose the whole trail
    -- because every "DesignWorkshopViewer"."tokenId" still names this row.
    "issuedById" TEXT,

    -- SINGLE USE IS THE DATABASE DEFAULT (not a service convention); NULL means unlimited. ONE
    -- column rather than a policy enum beside a count: two spellings of "single use" is the "two
    -- places to look" this repository refuses. Only an ADMIN may send anything but 1 — the schema
    -- cannot see the actor's role, so that half is `mint_grant`'s.
    "maxUses" INTEGER DEFAULT 1,

    -- THE SEAT ALLOCATOR, NOT A CACHED COUNT(*). See the header for the one statement that
    -- maintains it and why its row lock is what makes arrival order decide.
    "usesConsumed" INTEGER NOT NULL DEFAULT 0,

    -- NOT NULL, unlike almost every other date in this schema. A card with no end date is a
    -- permanent key to a workshop, printed on paper, that nobody remembers exists.
    "expiresAt" TIMESTAMP(3) NOT NULL,

    "revokedAt" TIMESTAMP(3),
    "revokedById" TEXT,

    -- For the admin's list ("stage-4 batch, 20 cards printed 24 Aug"). NEVER shown to a scanner.
    "label" TEXT,

    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RecordAccessToken_pkey" PRIMARY KEY ("id")
);

-- AddCheck
-- The three invariants the compare-and-swap in the service assumes, stated in the DATABASE because
-- a service that stopped holding one of them would overspend a single-use card silently.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RecordAccessToken_maxUses_check') THEN
    ALTER TABLE "RecordAccessToken" ADD CONSTRAINT "RecordAccessToken_maxUses_check"
      CHECK ("maxUses" IS NULL OR "maxUses" >= 1);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RecordAccessToken_usesConsumed_check') THEN
    ALTER TABLE "RecordAccessToken" ADD CONSTRAINT "RecordAccessToken_usesConsumed_check"
      CHECK ("usesConsumed" >= 0);
  END IF;
  -- THE ONE THAT MATTERS: a seat cannot be handed out past the ceiling, even by a hand-run UPDATE
  -- and even by a future code path that forgets the predicate.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RecordAccessToken_within_maxUses_check') THEN
    ALTER TABLE "RecordAccessToken" ADD CONSTRAINT "RecordAccessToken_within_maxUses_check"
      CHECK ("maxUses" IS NULL OR "usesConsumed" <= "maxUses");
  END IF;
END
$$;

-- CreateIndex
-- THE LOOKUP, AND THE UNFORGEABILITY. Redemption's only read is `WHERE "secretHash" = $1`, an
-- equality hit on a btree — so a wrong guess costs exactly the same one indexed probe as a right
-- one, which is the part of the timing story that is actually flat.
CREATE UNIQUE INDEX IF NOT EXISTS "RecordAccessToken_secretHash_key" ON "RecordAccessToken"("secretHash");
-- The admin screen, column for column: every card for one record, newest first.
CREATE INDEX IF NOT EXISTS "RecordAccessToken_recordType_recordId_createdAt_idx"
  ON "RecordAccessToken"("recordType", "recordId", "createdAt");
-- The two account pointers, indexed for the onDelete actions they are on the wrong end of rather
-- than for a read — 20260822093000's reasoning applied to a new table.
CREATE INDEX IF NOT EXISTS "RecordAccessToken_issuedById_idx"  ON "RecordAccessToken"("issuedById");
CREATE INDEX IF NOT EXISTS "RecordAccessToken_revokedById_idx" ON "RecordAccessToken"("revokedById");

-- AddForeignKey
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RecordAccessToken_issuedById_fkey') THEN
    ALTER TABLE "RecordAccessToken" ADD CONSTRAINT "RecordAccessToken_issuedById_fkey"
      FOREIGN KEY ("issuedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RecordAccessToken_revokedById_fkey') THEN
    ALTER TABLE "RecordAccessToken" ADD CONSTRAINT "RecordAccessToken_revokedById_fkey"
      FOREIGN KEY ("revokedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
  END IF;
END
$$;

-- CreateTable
-- ONE ACCOUNT'S ONE REDEMPTION OF ONE CARD: the audit trail, and the untrusted-clock evidence.
-- A SEPARATE TABLE FROM "DesignWorkshopAccessRequest" because the two need DIFFERENT unique
-- indexes — (workshop, requester) is the right idempotency for an ASK and the wrong one for a SEAT.
-- See the header.
CREATE TABLE IF NOT EXISTS "RecordAccessTokenRedemption" (
    "id" TEXT NOT NULL,

    "tokenId" TEXT NOT NULL,
    "userId" TEXT NOT NULL,

    "outcome" "DwTokenRedemptionOutcome" NOT NULL,
    "reason" "DwTokenRedemptionReason" NOT NULL,

    -- WHAT THE HANDSET SAYS. EVIDENCE, NEVER AUTHORITY. All nullable: the web client has no
    -- monotonic clock to report and an online scan has nothing to reconstruct. Absent evidence is
    -- honest; a zero would not be.
    "scannedAtClient" TIMESTAMP(3),
    -- THE MONOTONIC CLOCK, IN SECONDS, at the scan and at the sync. INTEGER and not BIGINT, and
    -- seconds and not the milliseconds Android reports, for two reasons that point the same way: a
    -- 32-bit count of milliseconds overflows at 24.8 days of uptime, which a field handset genuinely
    -- reaches, so milliseconds would have needed BIGINT — and a NULLABLE BigInt cannot be generated
    -- by this project's Prisma Python client (0.15.0), where `prisma generate` fails with an empty
    -- error. Measured, not assumed: "MediaFile"."sizeBytes" is a NON-NULL BIGINT and generates fine.
    -- Seconds reach 68 years of uptime and this evidence is read by a human asking "hours or days".
    "scannedAtElapsedSec" INTEGER,
    "syncedAtElapsedSec" INTEGER,
    "bootId" TEXT,
    "clockJumpObserved" BOOLEAN NOT NULL DEFAULT false,

    -- WHAT THE SERVER SAYS, AND THE ONLY TIME ANYTHING IS DECIDED BY. Deliberately not
    -- DEFAULT CURRENT_TIMESTAMP: it is set explicitly from the one value the whole redemption was
    -- judged against, so the row cannot disagree with the decision it records.
    "serverArrivedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "RecordAccessTokenRedemption_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
-- THE SEAT GUARANTEE'S SECOND HALF. One row per (card, person): the same person redeeming twice —
-- a replayed offline delivery, a second handset — collides HERE and returns the FIRST outcome
-- without touching "usesConsumed". Enforced by Postgres and not by a check in the service, for the
-- reason 20260822190000's header gives: a read-then-write is two round trips with a window in the
-- middle.
CREATE UNIQUE INDEX IF NOT EXISTS "RecordAccessTokenRedemption_tokenId_userId_key"
  ON "RecordAccessTokenRedemption"("tokenId", "userId");
-- "Everybody this batch of cards let in", newest first — the screen that undoes a leaked print run.
-- Its leading column is also what the "tokenId" CASCADE walks.
CREATE INDEX IF NOT EXISTS "RecordAccessTokenRedemption_tokenId_serverArrivedAt_idx"
  ON "RecordAccessTokenRedemption"("tokenId", "serverArrivedAt");
CREATE INDEX IF NOT EXISTS "RecordAccessTokenRedemption_userId_idx"
  ON "RecordAccessTokenRedemption"("userId");

-- AddForeignKey
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RecordAccessTokenRedemption_tokenId_fkey') THEN
    ALTER TABLE "RecordAccessTokenRedemption" ADD CONSTRAINT "RecordAccessTokenRedemption_tokenId_fkey"
      FOREIGN KEY ("tokenId") REFERENCES "RecordAccessToken"("id") ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
  -- CASCADE, matching "DesignWorkshopViewer"."userId": a redemption is not research anybody
  -- recorded, and RESTRICT would add a reason an account cannot be deleted that no screen can clear.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RecordAccessTokenRedemption_userId_fkey') THEN
    ALTER TABLE "RecordAccessTokenRedemption" ADD CONSTRAINT "RecordAccessTokenRedemption_userId_fkey"
      FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
END
$$;

-- CreateTable
-- SOMEBODY WHO SCANNED A GENUINE JOIN CARD WHOSE SEAT WAS GONE, AND WAS NOT REFUSED.
--
-- ⚠ NOTHING THAT DECIDES READ ACCESS CONSULTS THIS TABLE, AND NO QUERY ANYWHERE JOINS IT TO
-- "DesignWorkshopViewer". See the header for the six reads a `level` column would have had to teach
-- and the one it would have missed. A provisional member is, to every existing read, a stranger.
CREATE TABLE IF NOT EXISTS "DesignWorkshopProvisionalMember" (
    "designWorkshopId" TEXT NOT NULL,
    "userId" TEXT NOT NULL,

    -- THE CARD THEY ARRIVED ON — honestly the SPENT one ("they came on Rekha's card, late").
    "viaTokenId" TEXT,

    -- WHY THEY ARE HERE, copied from the redemption so the capture gate never joins two tables to
    -- answer one question.
    "reason" "DwTokenRedemptionReason" NOT NULL,

    -- EVIDENCE, NEVER AUTHORITY — the same rule as "DesignWorkshopAccessRequest"."scannedAt".
    "scannedAtClient" TIMESTAMP(3),
    "serverArrivedAt" TIMESTAMP(3) NOT NULL,

    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- The pair IS the identity, matching "DesignWorkshopViewer": one foothold per person per
    -- workshop, so a second late scan collides rather than stacking a row.
    CONSTRAINT "DesignWorkshopProvisionalMember_pkey" PRIMARY KEY ("designWorkshopId", "userId")
);

-- CreateIndex
-- "Which workshops is this account holding provisional captures in" — the client's own
-- self-description read, and the one the capture gate makes. Not covered by the primary key above:
-- "userId" is its SECOND column.
CREATE INDEX IF NOT EXISTS "DesignWorkshopProvisionalMember_userId_idx"
  ON "DesignWorkshopProvisionalMember"("userId");
CREATE INDEX IF NOT EXISTS "DesignWorkshopProvisionalMember_viaTokenId_idx"
  ON "DesignWorkshopProvisionalMember"("viaTokenId");

-- AddForeignKey
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DesignWorkshopProvisionalMember_designWorkshopId_fkey') THEN
    ALTER TABLE "DesignWorkshopProvisionalMember" ADD CONSTRAINT "DesignWorkshopProvisionalMember_designWorkshopId_fkey"
      FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DesignWorkshopProvisionalMember_userId_fkey') THEN
    ALTER TABLE "DesignWorkshopProvisionalMember" ADD CONSTRAINT "DesignWorkshopProvisionalMember_userId_fkey"
      FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
  -- SET NULL: cards are REVOKED and not deleted, so this fires only on a hand-run DELETE, and a
  -- foothold whose card is gone is still a foothold somebody is capturing into.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DesignWorkshopProvisionalMember_viaTokenId_fkey') THEN
    ALTER TABLE "DesignWorkshopProvisionalMember" ADD CONSTRAINT "DesignWorkshopProvisionalMember_viaTokenId_fkey"
      FOREIGN KEY ("viaTokenId") REFERENCES "RecordAccessToken"("id") ON DELETE SET NULL ON UPDATE CASCADE;
  END IF;
END
$$;

-- AlterTable
-- THE INDUCTION PROVENANCE, requirement 4's slot beside "grantedById". WRITE-ONCE, set at
-- induction, never overwritten, NULL for every row an admin made by hand.
--
-- "grantedById" answers WHOSE card ("services/design_workshop_grants.redeem" passes
-- `token.issuedById` to `replace_viewers` as `granted_by_id`); this answers WHICH card, which is
-- what makes "revoke everybody this batch let in" an answerable question.
--
-- BE HONEST ABOUT THE LIMIT, because this trail will be read as more than it is: a token names its
-- ISSUER, not necessarily the person who physically handed the card over. SINGLE-USE is what
-- collapses those two into one fact — one seat, one redemption, so issuing and inducting are the
-- same event. A MULTI-USE card deliberately does NOT collapse them: it says "somebody holding one
-- of Rekha's cards let this person in", and no column here can say who. That is the honest reason
-- multi-use is admin-only and off by default.
--
-- ⚠ IT IS NOT A SECOND SOURCE OF ACCESS. `has_viewer_grant` reads the EXISTENCE of the row and
-- nothing on it, so a NULL here and a token here are the same grant. Do not add a predicate that
-- reads it: that is the "two places to look when somebody has access they should not" that
-- `services/design_workshop_access.py`'s header refuses.
ALTER TABLE "DesignWorkshopViewer"
  ADD COLUMN IF NOT EXISTS "tokenId" TEXT;

CREATE INDEX IF NOT EXISTS "DesignWorkshopViewer_tokenId_idx" ON "DesignWorkshopViewer"("tokenId");

DO $$
BEGIN
  -- SET NULL, matching "grantedById" beside it and for the same reason: losing the token row must
  -- not lock a working co-designer out of the workshop they are halfway through.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DesignWorkshopViewer_tokenId_fkey') THEN
    ALTER TABLE "DesignWorkshopViewer" ADD CONSTRAINT "DesignWorkshopViewer_tokenId_fkey"
      FOREIGN KEY ("tokenId") REFERENCES "RecordAccessToken"("id") ON DELETE SET NULL ON UPDATE CASCADE;
  END IF;
END
$$;

-- AlterTable
-- THE REDEMPTION AS THE ADMIN QUEUE SEES IT. Everything else this row needs it already has:
-- "designWorkshopId", "requestedById", "status", "source", "scannedCode", "note", the decision
-- columns, "createdAt" AS THE SERVER-ARRIVAL STAMP, and the unique index that collapses a replayed
-- offline delivery to one row. A late-comer's row is PENDING — which is both true and free: the
-- existing queue (`status = 'PENDING'` ordered "createdAt" asc) picks them up with NO query change,
-- and that is exactly where requirement 6 wants them. So "DwAccessRequestStatus" gains no
-- PROVISIONAL value and "DwAccessRequestSource" gains no TOKEN value — "tokenId" IS NOT NULL is a
-- strictly more informative discriminator because it names WHICH card, and a new enum value
-- silently grows a case in every exhaustive match over "source" in three clients. A redemption IS
-- a scan.
--
-- ⚠ NEVER STORE A LIVE SECRET IN "scannedCode". Its own comment says the code is "stored in its
-- canonical DPW1:G:...:CHCK form" and justifies keeping the whole string with "it carries no
-- identity data by construction" — TRUE of a v1 record code and FALSE of a v2 join card, which is
-- a bearer credential. `redeem` therefore stores a REDACTED form,
-- `DPW2:J:<workshopId>.…<last4>:<CHCK>` — enough for an admin to match the card in front of them,
-- useless if this table leaks. That column's schema comment is updated in the same change; leaving
-- it standing is how the next reader stores the whole thing.
ALTER TABLE "DesignWorkshopAccessRequest"
  -- The LATEST card this person presented for this workshop. MUTABLE, unlike the viewer column
  -- above: the unique index means a second scan has no second row to write, so the re-scan upgrade
  -- path updates this in place. Also the discriminator for "a card decided this, not a person" —
  -- "decidedById" is already nullable for a DELETED admin, so without this the two are
  -- indistinguishable on the screen.
  ADD COLUMN IF NOT EXISTS "tokenId" TEXT,
  -- WHEN THE HANDSET SAYS IT WAS SCANNED. EVIDENCE, NEVER AUTHORITY. See the header: nothing may
  -- compare this column to decide an outcome, because "who was first" is settled by arrival at
  -- "RecordAccessToken"."usesConsumed" and by nothing else.
  ADD COLUMN IF NOT EXISTS "scannedAt" TIMESTAMP(3);

CREATE INDEX IF NOT EXISTS "DesignWorkshopAccessRequest_tokenId_idx"
  ON "DesignWorkshopAccessRequest"("tokenId");

DO $$
BEGIN
  -- SET NULL: cards are revoked and not deleted, so this fires only on a hand-run delete, and a
  -- redemption whose card is gone is still a redemption that happened. This table is history and is
  -- kept for ever.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DesignWorkshopAccessRequest_tokenId_fkey') THEN
    ALTER TABLE "DesignWorkshopAccessRequest" ADD CONSTRAINT "DesignWorkshopAccessRequest_tokenId_fkey"
      FOREIGN KEY ("tokenId") REFERENCES "RecordAccessToken"("id") ON DELETE SET NULL ON UPDATE CASCADE;
  END IF;
END
$$;
