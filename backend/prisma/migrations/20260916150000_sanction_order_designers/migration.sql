-- Every designer one sanction order names: one join table, one import ledger, two nullable columns
-- on "SanctionOrder", one backfill. Nothing existing is altered, dropped or retyped.
--
-- ADDITIVE ONLY, AND THAT IS WHAT MAKES IT SAFE AGAINST A POPULATED PRODUCTION DATABASE. Not one
-- existing column changes type or nullability, not one constraint is relaxed, not one existing index
-- is touched, and no existing row is rewritten — the backfill below only INSERTs. "SanctionOrder"'s
-- three designer scalars ("designerUserId", "designerEmail", "accountCreated") keep their meaning
-- and every one of their readers: they are THE LEAD, exactly as `DesignWorkshopCreate.designerUserId`
-- is the lead beside `designerUserIds`. Every filter, index, search clause and payload that reads
-- them today reads the same value after this migration.
--
-- Written by hand and then checked against the models in schema.prisma. The `IF NOT EXISTS` clauses
-- and the `DO $$ … pg_constraint … $$` blocks are not shapes `prisma migrate diff` emits; they are
-- here for the reason 20260913130200_dw_inspection_feedback gives and 20260915100000_tool_craft_links
-- repeats — this deployment's migrations are applied by a runner that can be interrupted and re-run.
--
-- =============================================================================================
-- WHAT THE SINGLE DESIGNER COLUMN COULD NOT SAY
-- =============================================================================================
--
-- A sanction order is routinely issued for a team. Until this table the register could name one
-- designer, so the second and third were either left off the instrument entirely — with no account,
-- no empanelment and no viewer row, i.e. unable to open the workshop their own order paid for — or
-- recorded as a second sanction order under a number the ministry never issued.
--
-- THE SCALARS ARE NOT WIDENED AND NOT RETIRED. `_account_the_register_already_knows` reads
-- "designerEmail" on this table, `list_sanction_orders`' search clause reads it and joins through
-- "designerUser", `sanction_payload` reads all three, and `@@index([designerUserId])` serves the
-- ON DELETE RESTRICT scan. More to the point, exactly ONE name reaches the report: stage 1 declares
-- a single `designerName` box and `report_docx` writes `<dc:creator>`, a field the OOXML
-- core-properties part cannot express as a list. There has to be a lead, and a lead derived by
-- sorting a collection is a lead nobody chose.
--
-- =============================================================================================
-- IT FOLLOWS "DesignWorkshopViewer" ON SHAPE AND "SanctionOrder" ON DELETE BEHAVIOUR
-- =============================================================================================
--
-- SHAPE: a composite primary key over the pair and no surrogate "id", because the pair IS the
-- identity — a re-add collides rather than stacking — and because no second table ever needs to
-- point at one of these rows. That is "DesignWorkshopViewer"'s choice rather than "ToolArtisan"'s.
--
-- DELETE: "designerUserId" is RESTRICT, and NOT the CASCADE "DesignWorkshopViewer"."userId" carries.
-- That table's own comment argues Cascade because "a viewer row is not authorship". This one IS
-- authorship-adjacent — it records that the ministry named this person on an instrument that
-- authorised money — and it matches "SanctionOrder"."designerUserId" beside it, which has been
-- RESTRICT since 20260913110000 for exactly that reason. routes/users.py's `_NAMED_ON_RELATIONS`
-- moves onto this table in the same commit, so the 409 an admin meets still names how many sanction
-- orders are in the way; it is a REPLACEMENT of the "SanctionOrder" row in that tuple and not an
-- addition, because after the backfill below the lead has a row in BOTH tables and counting both
-- would tell an admin a designer is named on two orders when they are named on one.
--
-- "sanctionOrderId" is CASCADE and that is safe: there is no DELETE on the register —
-- tests/test_sanction_order_gate.py::test_the_register_offers_no_way_to_delete_a_sanction_order
-- asserts `"DELETE" not in methods` on the router — so it fires only on a hand-run purge, where
-- taking the join rows along with the order is right.
--
-- =============================================================================================
-- THE BACKFILL, WHICH IS THE WHOLE REASON THIS IS NOT JUST A CREATE TABLE
-- =============================================================================================
--
-- Every sanction order already recorded gains its lead's row, at position 0. Without it, an existing
-- order would render with an EMPTY designer list on the officer's screen the moment the register
-- starts reading the collection — a ministry order that appears to name nobody, on the one screen
-- whose job is to say who it was issued to — and nothing would distinguish it from an order whose
-- designers had been removed.
--
-- IT IS SAFE ON A POPULATED DATABASE, term by term: every source column is NOT NULL on
-- "SanctionOrder" ("designerUserId", "designerEmail", "accountCreated", "createdAt" all are), the
-- foreign keys are already satisfied by the row it copies from (the order's own designer FK points
-- at the same "User"), and the composite primary key cannot collide because there is exactly one
-- source row per order. `ON CONFLICT DO NOTHING` is belt and braces for the re-run case.
--
-- "createdAt" IS THE ORDER'S OWN, NOT now(). Copying CURRENT_TIMESTAMP would stamp every backfilled
-- row with one instant and make "position 0, then oldest first" arbitrary across the whole table —
-- the same argument 20260816090000_platform_access_roster makes for backfilling "joinedAt" from
-- "User"."createdAt", and 20260915100000_tool_craft_links repeats for its link rows.
--
-- =============================================================================================
-- NOT `CONCURRENTLY`
-- =============================================================================================
--
-- Prisma sends a migration file as one multi-statement query inside an implicit transaction, so
-- `CREATE INDEX CONCURRENTLY` fails with PG 25001 / P3018 and leaves a failed `_prisma_migrations`
-- row blocking every later migration — 20260726200000_index_coverage sets that out at length. Every
-- index here is built on a table this file has just created, so there is nothing to build them
-- concurrently with.
--
-- =============================================================================================
-- ORDERING AGAINST THE OTHER UNCOMMITTED MIGRATION IN THIS RELEASE
-- =============================================================================================
--
-- 20260915100000_tool_craft_links is in the working tree and un-applied. Prisma applies by lexical
-- directory order, so it runs first — and the two are order-INDEPENDENT anyway: that one touches
-- "ToolDocumentation" and "Craft", this one touches "SanctionOrder" and "User", and neither reads a
-- table the other writes. Do not renumber either.
--
-- Rolling back:
--
--   DROP TABLE "SanctionOrderDesigner";
--   DROP TABLE "SanctionOrderImport";
--   ALTER TABLE "SanctionOrder" DROP COLUMN "sourceFilename", DROP COLUMN "sheetRow";
--
-- and nothing else: no other table references either new one, and every foreign key points away from
-- them. Worth stating plainly because this repository's deploy shape gives it teeth — pushing `main`
-- deploys with no test gate, and migrations have no automatic rollback.

CREATE TABLE IF NOT EXISTS "SanctionOrderDesigner" (
    "sanctionOrderId" TEXT NOT NULL,
    "designerUserId" TEXT NOT NULL,
    "designerEmail" TEXT NOT NULL,
    "accountCreated" BOOLEAN NOT NULL DEFAULT false,
    "position" INTEGER NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "SanctionOrderDesigner_pkey" PRIMARY KEY ("sanctionOrderId","designerUserId")
);

CREATE INDEX IF NOT EXISTS "SanctionOrderDesigner_designerUserId_idx" ON "SanctionOrderDesigner"("designerUserId");
CREATE INDEX IF NOT EXISTS "SanctionOrderDesigner_designerEmail_idx" ON "SanctionOrderDesigner"("designerEmail");

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'SanctionOrderDesigner_sanctionOrderId_fkey'
  ) THEN
    ALTER TABLE "SanctionOrderDesigner" ADD CONSTRAINT "SanctionOrderDesigner_sanctionOrderId_fkey"
      FOREIGN KEY ("sanctionOrderId") REFERENCES "SanctionOrder"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
END
$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'SanctionOrderDesigner_designerUserId_fkey'
  ) THEN
    ALTER TABLE "SanctionOrderDesigner" ADD CONSTRAINT "SanctionOrderDesigner_designerUserId_fkey"
      FOREIGN KEY ("designerUserId") REFERENCES "User"("id")
      ON DELETE RESTRICT ON UPDATE CASCADE;
  END IF;
END
$$;

-- AFTER both constraints, so a row that cannot satisfy them is refused rather than inserted, and
-- after the primary key, so ON CONFLICT has a constraint to name.
INSERT INTO "SanctionOrderDesigner"
       ("sanctionOrderId","designerUserId","designerEmail","accountCreated","position","createdAt")
SELECT s."id", s."designerUserId", s."designerEmail", s."accountCreated", 0, s."createdAt"
FROM "SanctionOrder" s
ON CONFLICT ("sanctionOrderId","designerUserId") DO NOTHING;

-- WHICH UPLOAD RECORDED THIS ORDER. Both NULLABLE with no default, because every row that already
-- exists predates the importer and was typed on the officer's form — and "typed by hand" is what a
-- NULL here means, permanently, not a value waiting to be backfilled. The per-order columns are the
-- shape "AnnualPlanEntry"."sourceFilename"/"sheetRow" already uses; the ledger table below answers
-- the other direction ("what did this upload do") and the two are not substitutes.
ALTER TABLE "SanctionOrder" ADD COLUMN IF NOT EXISTS "sourceFilename" TEXT;
ALTER TABLE "SanctionOrder" ADD COLUMN IF NOT EXISTS "sheetRow" INTEGER;

CREATE TABLE IF NOT EXISTS "SanctionOrderImport" (
    "id" TEXT NOT NULL,
    "sourceFilename" TEXT,
    "sheetName" TEXT,
    "rowsRead" INTEGER NOT NULL DEFAULT 0,
    "recorded" INTEGER NOT NULL DEFAULT 0,
    "skipped" INTEGER NOT NULL DEFAULT 0,
    "refused" INTEGER NOT NULL DEFAULT 0,
    "problems" JSONB,
    "uploadedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "SanctionOrderImport_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "SanctionOrderImport_uploadedById_createdAt_idx" ON "SanctionOrderImport"("uploadedById", "createdAt");

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'SanctionOrderImport_uploadedById_fkey'
  ) THEN
    ALTER TABLE "SanctionOrderImport" ADD CONSTRAINT "SanctionOrderImport_uploadedById_fkey"
      FOREIGN KEY ("uploadedById") REFERENCES "User"("id")
      ON DELETE SET NULL ON UPDATE CASCADE;
  END IF;
END
$$;
