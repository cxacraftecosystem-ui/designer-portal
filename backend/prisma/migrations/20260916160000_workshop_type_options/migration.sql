-- "Type of workshop" becomes DATA: one new table, seeded with the six tokens the registry already
-- knows, and nothing else in the database touched.
--
-- ADDITIVE ONLY, AND THAT IS WHAT MAKES IT SAFE AGAINST A POPULATED PRODUCTION DATABASE. Not one
-- existing column is added, dropped or retyped, not one constraint relaxed, not one existing index
-- changed, and not one existing row rewritten — the seed below only INSERTs, into a table this file
-- has just created. `DesignWorkshop.workshopKind`, `AnnualPlanEntry.workshopKind` and
-- `Workshop.workshopType` keep their meanings and every one of their readers; there is no foreign
-- key from any of them onto this table and none is added. A deployment that applies this migration
-- and then serves the OLD application code behaves exactly as it did before it ran.
--
-- Written by hand and then checked against `model WorkshopTypeOption` in schema.prisma. The
-- `IF NOT EXISTS` clauses are not a shape `prisma migrate diff` emits; they are here for the reason
-- 20260913130200_dw_inspection_feedback gives and 20260915100000_tool_craft_links and
-- 20260916150000_sanction_order_designers repeat — this deployment's migrations are applied by a
-- runner that can be interrupted and re-run. (There is no `DO $$ … pg_constraint … $$` block in this
-- file, and its absence is not an omission: this table has NO foreign keys, for the reason the model
-- comment sets out at length — a record stores the workshop it belongs to, never the type.)
--
-- =============================================================================================
-- THE TABLE IS NOT CALLED "WorkshopType", AND POSTGRES IS THE ONE WHO DECIDED THAT
-- =============================================================================================
--
-- This slice was specified as "the WorkshopType table". That name CANNOT BE CREATED on this
-- database. `enum WorkshopType { DESIGN_PROTOTYPE, OTHER }` has existed in schema.prisma since the
-- design/ordinary split was added to `Workshop`, Prisma emits it as a real Postgres enum, and
-- Postgres keeps enums and tables in ONE type namespace — every table implicitly creates a composite
-- type of its own name. Run on this database on 2026-09-16:
--
--     BEGIN; CREATE TABLE "WorkshopType" ("id" TEXT NOT NULL); ROLLBACK;
--     ERROR:  type "WorkshopType" already exists
--     HINT:   A relation has an associated type of the same name, so you must use a name that
--             doesn't conflict with any existing type.
--
-- So this is not a naming preference that a later reader may tidy up: renaming this table to
-- "WorkshopType" is a migration that fails on every deployment, and fails AFTER the transaction has
-- opened, leaving a failed `_prisma_migrations` row that blocks every later migration. The word is
-- taken a third time in the web client (`frontend/lib/types.ts` exports
-- `type WorkshopType = "DESIGN_PROTOTYPE" | "OTHER"`), which is the other half of why the whole
-- stack spells the row `WorkshopTypeOption`: the record form's picker holds both meanings at once.
--
-- The API prefix, the admin route and the module names are all still `workshop-types` /
-- `workshop_types` / `workshopTypes`. Only the identifier Postgres refuses is different.
--
-- =============================================================================================
-- THE SEED, WHICH IS THE WHOLE REASON THIS IS NOT JUST A CREATE TABLE
-- =============================================================================================
--
-- Six rows, carrying THE SAME SIX KEYS as `stage_schema.ENUMS["WORKSHOP_KIND"]` and the same six
-- labels, character for character, as that dict holds them on 2026-09-16. Without the seed the new
-- "Type of workshop" dropdown opens EMPTY on the day this deploys — a required control with no
-- members, on every record form at once — and, worse, the 24 `DesignWorkshop` rows that already
-- carry a `workshopKind` would have no row to resolve their token against, so a workshop filed under
-- `SKILL_UPGRADATION` would render as a workshop with no type rather than as one with a type nobody
-- has written a label for yet. An existing value that stops resolving is indistinguishable from an
-- existing value that was lost.
--
-- THE KEYS ARE THE REGISTRY'S. That is a SEED and not a SYNCHRONISATION — nothing copies one list
-- into the other in either direction, now or later, and `stage_schema.py` is not touched by this
-- change. The two vocabularies answer different questions (the model comment sets both out), and
-- they are seeded equal only so that every token already written to a column resolves to a label on
-- the day the screen appears.
--
-- THE IDS ARE DERIVED, NOT RANDOM, so a re-run of a half-applied migration cannot mint a second row
-- for the same key even in the window where the unique index build had not landed when the first
-- attempt died. `'c' || substr(md5(…), 1, 24)` is 25 characters beginning with 'c' — the shape
-- `@default(cuid())` produces — over a value that is already unique. Prisma only generates ids for
-- rows IT creates, so a hand-shaped id here is read back exactly like any other.
-- 20260915100000_tool_craft_links made the same choice for the same reason and argues it at length.
--
-- `ON CONFLICT ("key") DO NOTHING` IS THE OTHER HALF OF THAT, AND IT IS NOT ONLY ABOUT RE-RUNS. It
-- is also what makes this migration safe to apply to a database where an administrator has ALREADY
-- corrected a label through the admin screen and the migration is being replayed (a restored backup,
-- a re-pointed environment): the seed never overwrites a row that exists, so an edit made by a
-- person is never reverted by a deploy. DO NOT change it to `DO UPDATE`.
--
-- =============================================================================================
-- `routesToDesignWorkshop` — ONE ROW TRUE, FIVE FALSE
-- =============================================================================================
--
-- The flag the record form's picker reads to decide whether the chosen workshop is written to
-- `Record.designWorkshopId` (TRUE) or to `Record.workshopId` (FALSE). `DESIGN_PROTOTYPE_DEVELOPMENT`
-- is the one TRUE row, because `DesignWorkshop` is the table that holds design & prototype
-- workshops; the other five are ordinary field-documentation workshops on the `Workshop` table.
--
-- NO CHECK CONSTRAINT ENFORCES "EXACTLY ONE", DELIBERATELY. A second design-workshop-backed
-- programme is something the ministry can announce, and a constraint here would make announcing it a
-- migration on every deployment before an administrator could file under it — precisely the friction
-- 20260830150000_design_workshop_kind refused for the same vocabulary. The column default is FALSE,
-- which is the safe direction: a row added by an administrator who does not understand the flag
-- points at the ordinary `Workshop` table, where a mis-filed record is visible and editable, rather
-- than at the 22-stage design workshop table, where it would not belong at all.
--
-- =============================================================================================
-- NOT `CONCURRENTLY`
-- =============================================================================================
--
-- Prisma sends a migration file as one multi-statement query inside an implicit transaction, so
-- `CREATE INDEX CONCURRENTLY` fails with PG 25001 / P3018 and leaves a failed `_prisma_migrations`
-- row blocking every later migration — 20260726200000_index_coverage sets that out at length. Both
-- indexes here are built on a table this file has just created, so there is nothing to build them
-- concurrently with, and the table holds six rows.
--
-- =============================================================================================
-- ORDERING AGAINST THE OTHER MIGRATIONS IN THIS RELEASE
-- =============================================================================================
--
-- Three other slices are being written into this working tree at the same time. Prisma applies by
-- lexical directory order, and this file is order-INDEPENDENT of anything they add: it creates one
-- table that nothing else in the schema references, reads no table any of them writes, and writes no
-- table any of them reads. If one of them lands a migration with an EARLIER timestamp than this one
-- after this has already been applied to a developer database, that is the ordinary "migration added
-- behind the head" case and Prisma applies it on the next deploy; nothing here needs renumbering.
--
-- Rolling back:
--
--   DROP TABLE "WorkshopTypeOption";
--
-- and nothing else: no other table references it, and it has no foreign keys of its own. Worth
-- stating plainly because this repository's deploy shape gives it teeth — pushing `main` deploys
-- with no test gate, and migrations have no automatic rollback. Note what rolling back COSTS, which
-- is not nothing: any type an administrator added through the admin screen after this deployed is in
-- this table and nowhere else, and the DROP takes it with them.

CREATE TABLE IF NOT EXISTS "WorkshopTypeOption" (
    "id" TEXT NOT NULL,
    "key" TEXT NOT NULL,
    "label" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "routesToDesignWorkshop" BOOLEAN NOT NULL DEFAULT false,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "WorkshopTypeOption_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "WorkshopTypeOption_key_key" ON "WorkshopTypeOption"("key");
CREATE INDEX IF NOT EXISTS "WorkshopTypeOption_isActive_sortOrder_idx" ON "WorkshopTypeOption"("isActive", "sortOrder");

-- AFTER the unique index, so `ON CONFLICT` has a constraint to name, and after the primary key, so a
-- row that cannot satisfy it is refused rather than inserted.
--
-- `updatedAt` is written explicitly because `@updatedAt` is a PRISMA-side default with no DDL behind
-- it: the column is NOT NULL with no database default, so an INSERT that omitted it would fail. Both
-- stamps are `CURRENT_TIMESTAMP` here rather than copied from anywhere, and unlike the backfills in
-- 20260915100000 and 20260916150000 there is nothing older to copy FROM — these six rows are being
-- created now, by this migration, and were never anything before it.
INSERT INTO "WorkshopTypeOption"
       ("id", "key", "label", "sortOrder", "isActive", "routesToDesignWorkshop", "createdAt", "updatedAt")
SELECT
    'c' || substr(md5('WorkshopTypeOption:' || seed.key), 1, 24),
    seed.key,
    seed.label,
    seed.sort_order,
    true,
    seed.routes_to_design_workshop,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM (VALUES
    ('DESIGN_PROTOTYPE_DEVELOPMENT', 'Design & Prototype Development', 10, true),
    ('SKILL_UPGRADATION',            'Skill Upgradation',              20, false),
    ('DESIGN_INTERVENTION',          'Design Intervention',            30, false),
    ('CLUSTER_DEVELOPMENT',          'Cluster Development',            40, false),
    ('EXPOSURE_EXHIBITION',          'Exposure / Exhibition',          50, false),
    ('OTHER',                        'Other',                          60, false)
) AS seed(key, label, sort_order, routes_to_design_workshop)
ON CONFLICT ("key") DO NOTHING;
