-- THE SANCTION ORDER REGISTER: one table, purely additive, nothing existing altered.
--
-- A ministry officer records five facts — order number, order date, sanctioned amount, and the
-- designer's name and Gmail address — and this row is what remembers them. Everything else the
-- entry produces (an AccessRoster admission, a DesignerRoster empanelment, a User, a
-- DesignerProfile, a DesignWorkshop and its viewer row) already has a table; see
-- app/services/sanction_orders.py for the order those writes happen in and for the transaction
-- boundary.
--
-- PURELY ADDITIVE. One new table, three foreign keys OUT of it, not one column added, dropped or
-- retyped on any existing table, not one constraint relaxed and not one row rewritten. Rolling back
-- is `DROP TABLE "SanctionOrder";` and nothing else: no other table references it, and the three
-- FKs point away from it.
--
-- NO `ALTER TYPE` STATEMENT LIVES HERE, so the one-statement-per-file rule this repository applies
-- to enum growth does not bite. The three role tokens this feature's gate depends on
-- (ASSISTANT_DIRECTOR, REGIONAL_DIRECTOR, MINISTRY_ADMIN) arrived in their own three migrations —
-- 20260913100000, 20260913100100, 20260913100200 — one statement each, for the reason those files
-- state: Prisma runs a migration inside one implicit transaction, and a value added by ADD VALUE
-- cannot be USED in the transaction that added it. This file sorts after all three on purpose.
--
-- ── THE MONEY COLUMN, AND WHY IT IS NUMERIC(14,2) AND NOT ANYTHING ELSE ──────────────────────
--
-- NEVER A FLOAT, and this is not a style preference. `DOUBLE PRECISION` is binary radix-2 and
-- cannot represent 0.10 exactly, so a sum of sanctions reconciled against a ministry ledger drifts,
-- and the drift grows with the number of rows — which for a financial register is the one direction
-- of error nobody can accept. NUMERIC is exact decimal arithmetic in Postgres and it is what every
-- money column in this schema already is: `ProductDocumentation.costOfMaking` and `.sellingPrice`
-- are `DECIMAL(12,2)` and so is `ToolDocumentation.replacementCost`.
--
-- WHY 14 AND NOT 12. The existing money columns price ONE PRODUCT — 12 digits is ₹9,999,999,999.99
-- and a handloom stole does not need it. A sanction order is an institutional figure: DCH design
-- workshop sanctions are quoted in lakhs, a cluster programme in crores, and a state-level umbrella
-- sanction in tens of crores. 14,2 tops out at ₹999,999,999,999.99 (≈ ₹1 lakh crore), which is
-- larger than the whole scheme has ever been, and the two extra digits cost nothing: NUMERIC is
-- variable-width, so the precision is a CONSTRAINT, not an allocation. Widening later is an ALTER
-- that rewrites the table; starting wide is free.
--
-- WHY SCALE 2. Paise. A sanction order is written to the rupee in practice, but an order that says
-- ₹4,50,000.50 must store what it says, and a scale of 0 would round a ministry figure silently on
-- write, which is the worst possible place for a rounding rule.
--
-- WHY THERE IS NO CURRENCY COLUMN. Every sanction order this product will ever see is issued in
-- Indian Rupees by an Indian ministry, and the registry already declares the same assumption
-- globally: `FieldType.MONEY` in app/services/stage_schema.py reads "INR, stored as a decimal
-- string". A nullable `currency` column reading 'INR' on every row is a column no reader would
-- branch on, which means it would be WRONG and unnoticed the first time a row held anything else —
-- a second answer that nothing reads is worse than one answer that everything does. If a second
-- currency ever arrives it is an ALTER plus a backfill of a known constant, which is the cheapest
-- future change available and is available precisely because this column is exact.
--
-- THE AMOUNT MUST BE POSITIVE, and that is a CHECK here rather than only a Pydantic `gt=0`,
-- following the precedent of 20260822120000_dw_review_rating_ledger, which constrains its score
-- range the same way. A validator protects one door; a constraint protects the table, including a
-- psql session and any future importer. A zero-rupee sanction order is not a sanction order, and a
-- negative one is a typed minus sign.
--
-- ── THE TWO UNIQUE INDEXES ON THE NUMBER, AND WHY ONE IS NOT ENOUGH ──────────────────────────
--
-- "sanctionOrderNo" is the ministry's own spelling, unique because a sanction number names one
-- instrument. That alone does NOT stop duplicates: `SO/2026/42`, `SO-2026-42` and `so 2026 42` are
-- three spellings of one order and Postgres calls them three values. "sanctionOrderKey" is the same
-- number upper-cased with every non-alphanumeric removed, and IT is the constraint that bites. The
-- normalisation is `normalise_sanction_order_no` in app/services/sanction_orders.py and is
-- character-for-character `identity.normalise_empanelment_no`, which is where the rule was first
-- needed and argued;
-- tests/test_sanction_orders.py::test_the_sanction_key_normalisation_matches_the_empanelment_one
-- pins the two equal, so a future divergence has to be typed on purpose.
--
-- THERE IS NO SQL TWIN OF THAT NORMALISATION IN THIS FILE, unlike migration
-- 20260830170000_auth_identity_and_password_links, which carries one. That migration had rows to
-- backfill; this table is created empty, so Python is the only writer that will ever exist and a
-- second implementation in SQL would be a copy with nothing to keep it honest.
--
-- ── ON DELETE, THREE TIMES, EACH ARGUED ──────────────────────────────────────────────────────
--
--   * "designWorkshopId" -> RESTRICT. The API's delete is a SOFT delete (`deletedAt`), so this
--     fires only on a hard purge — and a hard purge of a workshop the ministry funded has to be
--     refused by the database and not merely discouraged by a route. UNIQUE as well as NOT NULL:
--     one order, one workshop, and a second order cannot claim a workshop somebody else's budget
--     paid for.
--   * "designerUserId" -> RESTRICT, matching every authorship FK on User. The designer named on a
--     sanction order is part of what the order SAYS. It is not a grant that can be withdrawn.
--     app/api/routes/users.py's `_NAMED_ON_RELATIONS` — a SECOND list beside `_CREATOR_RELATIONS`,
--     because the sentence that list renders begins "This account created …" and a designer named
--     on an order created nothing — is what makes the resulting 409 name it.
--   * "createdById" -> RESTRICT, matching `DesignWorkshop.createdById`. Who authorised the spend
--     outlives the officer's employment. `_CREATOR_RELATIONS` gains a row for this table so the 409
--     an admin gets names it instead of saying "something is in the way".
--
-- No row anywhere is written by this file; production holds no sanction orders because the concept
-- does not exist yet.

-- CreateTable
CREATE TABLE "SanctionOrder" (
    "id" TEXT NOT NULL,
    "sanctionOrderNo" TEXT NOT NULL,
    "sanctionOrderKey" TEXT NOT NULL,
    "sanctionOrderDate" TIMESTAMP(3) NOT NULL,
    "sanctionAmount" DECIMAL(14,2) NOT NULL,
    "designerUserId" TEXT NOT NULL,
    "designerEmail" TEXT NOT NULL,
    "designWorkshopId" TEXT NOT NULL,
    "createdById" TEXT NOT NULL,
    "accountCreated" BOOLEAN NOT NULL DEFAULT false,
    "notes" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "SanctionOrder_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "SanctionOrder_amount_is_positive" CHECK ("sanctionAmount" > 0)
);

-- CreateIndex
-- The ministry's own spelling. An auditor searches for what is printed on the order.
CREATE UNIQUE INDEX "SanctionOrder_sanctionOrderNo_key" ON "SanctionOrder"("sanctionOrderNo");

-- CreateIndex
-- The constraint that actually stops a duplicate — see the header.
CREATE UNIQUE INDEX "SanctionOrder_sanctionOrderKey_key" ON "SanctionOrder"("sanctionOrderKey");

-- CreateIndex
-- One order, one workshop. Also serves "does this workshop have a sanction order", which the
-- workshop record page asks once per open.
CREATE UNIQUE INDEX "SanctionOrder_designWorkshopId_key" ON "SanctionOrder"("designWorkshopId");

-- CreateIndex
-- The officer list's default sort: newest order first.
CREATE INDEX "SanctionOrder_sanctionOrderDate_idx" ON "SanctionOrder"("sanctionOrderDate");

-- CreateIndex
-- "orders I recorded", and the scan Postgres runs for the RESTRICT when an admin deletes an account.
CREATE INDEX "SanctionOrder_createdById_idx" ON "SanctionOrder"("createdById");

-- CreateIndex
-- "orders issued to this designer", and the other RESTRICT scan.
CREATE INDEX "SanctionOrder_designerUserId_idx" ON "SanctionOrder"("designerUserId");

-- AddForeignKey
ALTER TABLE "SanctionOrder" ADD CONSTRAINT "SanctionOrder_designerUserId_fkey" FOREIGN KEY ("designerUserId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SanctionOrder" ADD CONSTRAINT "SanctionOrder_designWorkshopId_fkey" FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SanctionOrder" ADD CONSTRAINT "SanctionOrder_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
