-- Many-to-many between a tool and its crafts: one new table, one backfill, nothing else touched.
--
-- ADDITIVE ONLY. Not one column added, dropped or retyped on any existing table, not one constraint
-- relaxed, not one existing index changed. "ToolDocumentation"."craftId" and ."craftName" keep their
-- meaning and their readers: the column holds the FIRST of the selected crafts and the string holds
-- every selected name joined ", " in link order. Every filter, index, report, carry-forward and
-- data-browser branch that reads "craftId" today reads exactly the same value after this migration.
--
-- Written by hand and then checked against the model in schema.prisma. The `IF NOT EXISTS` clauses
-- and the `DO $$ … pg_constraint … $$` blocks are not shapes `prisma migrate diff` emits; they are
-- here for the reason 20260913130200_dw_inspection_feedback gives — this deployment's migrations are
-- applied by a runner that can be interrupted and re-run.
--
-- =============================================================================================
-- WHAT THE SINGLE "craftId" COLUMN COULD NOT SAY
-- =============================================================================================
--
-- A documented tool is routinely the same tool in three crafts. Until this table the record form
-- could name one craft, so the second and third were either re-entered as whole duplicate tools or
-- lost.
--
-- THE COLUMN IS NOT WIDENED AND NOT RETIRED, because the whole backend reads it as one id and one
-- name: `GET /tools?craftId=`, `@@index([craftId])`, `record_fields.TOOL`'s "Craft" cell, the data
-- browser's craft folders, `REFERENCE_MODELS`'s tool carry into a workshop stage, and the carry
-- context the record forms bank between saves. Counted on 2026-09-15, `backend/app` mentions
-- `craftName` on 129 lines and a `craftId` on 65 — re-count with::
--
--     grep -rn "craftName" backend/app --include=*.py | wc -l
--     grep -rn '"craftId"\|\.craftId\|craftId=' backend/app --include=*.py | wc -l
--
-- (not all of those are the tool's, and that is the point: the same two spellings mean the same
-- thing on `Artisan`, `ProductDocumentation` and `MediaFile`, so changing what they mean HERE would
-- have to be argued on each of the others too). `craftId` becomes the FIRST of the selection and
-- `craftName` the selected names joined ", " in that order; this table becomes all of it.
--
-- =============================================================================================
-- IT MIRRORS "ToolArtisan" COLUMN FOR COLUMN, AND THE ONE DIFFERENCE IS DELIBERATE
-- =============================================================================================
--
-- Same four columns, same unique, same single secondary index, same CASCADE on both sides as
-- 20260618150000_tool_artisan_links. Two join tables off one parent that disagree about their own
-- shape is how a later reader comes to believe one of them means something the other does not.
--
-- THE ONE DIFFERENCE: 20260618150000 also created "ToolArtisan_toolId_idx", which schema.prisma does
-- not declare and whose own model comment says is redundant ("it is the leading column of the unique
-- below"). That index is left where it is — dropping it is a separate decision with its own
-- migration — and it is NOT reproduced here, because this file must be what `prisma migrate diff`
-- would emit for the model text. Do not "make the two match".
--
-- =============================================================================================
-- THE BACKFILL, WHICH IS THE WHOLE REASON THIS IS NOT JUST A CREATE TABLE
-- =============================================================================================
--
-- Every tool that already names a craft gains its link row. Without it, the first save of any
-- existing tool through the new multi-select would find "craftLinks" empty, tick nothing, and the
-- researcher's obvious repair — pick the craft again — would be the one action that rewrites the
-- link. A tool whose craft "disappeared" is indistinguishable from a tool that never had one.
--
-- THE ID IS DERIVED, NOT RANDOM, so a re-run of a half-applied migration cannot mint a second row
-- for the same pair even if the unique index build had not landed. `'c' || substr(md5(…), 1, 24)` is
-- 25 characters beginning with 'c' — the shape `@default(cuid())` produces — over a pair that is
-- already unique. Prisma only generates ids for rows IT creates, so a hand-shaped id here is read
-- back exactly like any other. 20260816090000_platform_access_roster minted its grandfathering ids
-- in SQL too, but RANDOMLY — `'acc_' || replace(gen_random_uuid()::text, '-', '')` — because it
-- could lean on a unique `email` for its `ON CONFLICT`. This one has a unique pair to conflict on as
-- well, so the derivation is belt and braces: it holds even in the window where the index build
-- itself had not landed when the first attempt died.
--
-- "createdAt" IS THE TOOL'S OWN, NOT now(). Copying CURRENT_TIMESTAMP would stamp every backfilled
-- link with one instant, and "oldest first" over the whole table would then be arbitrary — the same
-- argument 20260816090000 makes for backfilling `joinedAt` from `User."createdAt"`.
--
-- =============================================================================================
-- NOT `CONCURRENTLY`
-- =============================================================================================
--
-- Prisma sends a migration file as one multi-statement query inside an implicit transaction, so
-- `CREATE INDEX CONCURRENTLY` fails with PG 25001 / P3018 and leaves a failed `_prisma_migrations`
-- row blocking every later migration — 20260726200000_index_coverage sets that out at length. Both
-- indexes here are built on a table this file has just created, so there is nothing to build them
-- concurrently with.
--
-- Rolling back:
--
--   DROP TABLE "ToolCraft";
--
-- and nothing else: no other table references it, and both foreign keys point away from it. Worth
-- stating plainly because this repository's deploy shape gives it teeth — pushing `main` deploys
-- with no test gate, and migrations have no automatic rollback.

CREATE TABLE IF NOT EXISTS "ToolCraft" (
    "id" TEXT NOT NULL,
    "toolId" TEXT NOT NULL,
    "craftId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ToolCraft_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "ToolCraft_toolId_craftId_key" ON "ToolCraft"("toolId", "craftId");
CREATE INDEX IF NOT EXISTS "ToolCraft_craftId_idx" ON "ToolCraft"("craftId");

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ToolCraft_toolId_fkey'
  ) THEN
    ALTER TABLE "ToolCraft" ADD CONSTRAINT "ToolCraft_toolId_fkey"
      FOREIGN KEY ("toolId") REFERENCES "ToolDocumentation"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
END
$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ToolCraft_craftId_fkey'
  ) THEN
    ALTER TABLE "ToolCraft" ADD CONSTRAINT "ToolCraft_craftId_fkey"
      FOREIGN KEY ("craftId") REFERENCES "Craft"("id")
      ON DELETE CASCADE ON UPDATE CASCADE;
  END IF;
END
$$;

-- AFTER the two constraints, so a row that cannot satisfy them is refused rather than inserted, and
-- after the unique, so ON CONFLICT has a constraint to name.
INSERT INTO "ToolCraft" ("id", "toolId", "craftId", "createdAt")
SELECT
    'c' || substr(md5(t."id" || ':' || t."craftId"), 1, 24),
    t."id",
    t."craftId",
    t."createdAt"
FROM "ToolDocumentation" t
WHERE t."craftId" IS NOT NULL
ON CONFLICT ("toolId", "craftId") DO NOTHING;
