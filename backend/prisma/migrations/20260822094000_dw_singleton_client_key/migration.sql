-- Give every singleton stage row a reserved clientKey, so the unique index that already exists
-- starts enforcing "one row per (workshop, entity)".
--
-- WHAT WAS UNENFORCED. `DwStageEntry` carries `@@unique([designWorkshopId, entityKey, clientKey])`,
-- and fourteen registry entities plus the reserved `_custom` container are supposed to have exactly
-- one row per workshop. The index could never enforce that, because Postgres treats NULLs as
-- DISTINCT under a unique index and the web sets no client key at all. So uniqueness was a
-- read-then-write in `save_stage`, and the read and the transaction are seconds apart on a link
-- where one round trip measures 756 ms. Two designers sharing a workshop — which
-- `DesignWorkshopViewer` exists to allow — could both find no singleton and both insert one.
--
-- The duplicate is not the damage. The damage is that `entry_rows` returns the two in no guaranteed
-- order and completeness, `assemble_workshop_data` and the stage payload each take last-write-wins
-- over that order, so WHICH answer is scored, printed into the .docx and shown on the form can
-- differ between two reads of unchanged data — and half the fieldwork lives in a row nothing ever
-- updates.
--
-- WHY A SENTINEL VALUE AND NOT A PARTIAL INDEX. A partial unique index on `clientKey IS NOT NULL`
-- is the textbook answer and is the wrong one here: it would break
-- `tests/test_stage_sync.test_many_rows_without_a_client_key_coexist` (the browser creates every
-- collection row with a null key) and the duplicate-key recovery path in `save_stage`, which
-- deliberately writes a null key to save a designer's work after a collision. A reserved VALUE
-- costs nothing and breaks neither.
--
-- WHY THE STAGE KEY IS IN THE VALUE. The index does not include `stageKey`. A registry entity key
-- is unique across the whole registry, so for the fourteen singletons a bare constant would do —
-- but the reserved `_custom` container uses the same literal `_custom` on EVERY stage of a
-- workshop, so a bare constant would have made stage 3's container collide with stage 9's inside
-- one workshop and the index would have refused the second stage's custom answers outright. The
-- value written is therefore `'__dw_singleton__:' || "stageKey"`, which is what
-- `design_workshops.singleton_client_key` produces.
--
-- THE FOURTEEN ENTITY KEYS ARE A SNAPSHOT OF THE REGISTRY, TAKEN 2026-08-22, and a migration is the
-- one place a snapshot is the right thing: this statement has to mean the same thing for ever, and
-- reading a Python registry from SQL is not possible anyway. A singleton added later gets its key
-- from the application on its first save, and needs no migration.
--
-- THIS BACKFILL IS A ONE-OFF AND THE APPLICATION NO LONGER DEPENDS ON IT BEING THE ONLY MECHANISM.
-- When it was written, `save_stage` set the key on INSERT and nothing else did — so a row seeded by
-- `seed_designer_prefill` (which runs on `POST /design-workshops` and creates the `workshopSetup`
-- singleton on very nearly every new workshop) was born with a NULL key and kept it for the
-- workshop's whole life, and a database restored from a dump older than this file would come back
-- unenforced. Both are closed in the application now: the prefill writes the reserved key on create,
-- and `design_workshops._reserved_key_upgrade` makes an existing unkeyed singleton adopt it on its
-- next ordinary save. This statement is still worth running — it fixes the rows in place rather than
-- waiting for each to be edited — but it is no longer the whole guarantee.
--
-- ── WHAT THIS MIGRATION DELIBERATELY DOES NOT DO ────────────────────────────────────────────────
--
-- IT DOES NOT DEDUPLICATE. Where a workshop already holds two rows for one singleton, this gives
-- the reserved key to ONE of them and leaves the other exactly as it is — same data, same
-- deletedAt, null key. Nothing is deleted, nothing is merged, no key of anybody's fieldwork is
-- dropped: the only column this migration writes is `clientKey`, and only where it was NULL.
--
-- That is a deliberate stop, not an oversight. A lossless dedup has to merge the two `data`
-- documents (each row can hold keys the other does not) and then retire the loser, and neither half
-- could be exercised here: Docker is down on the machine this was written on, so no statement in
-- this file has been run against a live database. Shipping an untested destructive statement
-- against a table that holds weeks of fieldwork is not a trade worth making for rows that are, at
-- worst, exactly as ambiguous after this migration as they were before it.
--
-- WHAT THE APPLICATION DOES ABOUT THEM INSTEAD, so they are not left to rot: `save_stage` now
-- prefers the row holding the reserved key ahead of any other when it matches a singleton. Before,
-- it took the first live row in an unordered read, which is the coin toss that let the pair drift
-- apart; now every save writes to the same one of the two, so an existing pair CONVERGES rather
-- than alternating. The loser keeps whatever it held and stays readable.
--
-- TO FIND ANY THAT REMAIN (this returns nothing on a healthy database):
--
--   SELECT "designWorkshopId", "stageKey", "entityKey", count(*), array_agg(id)
--     FROM "DwStageEntry"
--    WHERE "deletedAt" IS NULL
--      AND "entityKey" IN ('workshopSetup','introduction','workshopPlan','clusterBackground',
--                          'traditionalProcess','surveyPlan','surveySummary','marketAnalysis',
--                          'designBrief','outcomes','closing','reportSettings','archive',
--                          'followUpSummary','_custom')
--    GROUP BY 1, 2, 3
--   HAVING count(*) > 1;
--
-- ── SAFETY ──────────────────────────────────────────────────────────────────────────────────────
--
-- IDEMPOTENT. The NOT EXISTS clause skips any group that already holds the reserved key, so a
-- second run selects nothing. Re-runnable after a partial failure for the same reason.
-- NON-DESTRUCTIVE. One UPDATE, one column, only over rows where that column IS NULL.
-- IT CANNOT VIOLATE THE INDEX IT IS ARMING. `rank_in_group = 1` picks exactly one row per
-- (workshop, entity, stage), and the NOT EXISTS guarantees no other row in that group already
-- holds the value.
-- THE ROW IT PICKS is the one a read would have preferred anyway: live before soft-deleted, then
-- most recently updated, then highest id as a total tie-break so the choice is deterministic.

WITH candidate AS (
  SELECT e.id,
         row_number() OVER (
           PARTITION BY e."designWorkshopId", e."entityKey", e."stageKey"
           ORDER BY (e."deletedAt" IS NULL) DESC, e."updatedAt" DESC, e.id DESC
         ) AS rank_in_group
    FROM "DwStageEntry" e
   WHERE e."clientKey" IS NULL
     AND e."entityKey" IN ('workshopSetup','introduction','workshopPlan','clusterBackground',
                           'traditionalProcess','surveyPlan','surveySummary','marketAnalysis',
                           'designBrief','outcomes','closing','reportSettings','archive',
                           'followUpSummary','_custom')
     AND NOT EXISTS (
           SELECT 1
             FROM "DwStageEntry" o
            WHERE o."designWorkshopId" = e."designWorkshopId"
              AND o."entityKey"        = e."entityKey"
              AND o."clientKey"        = '__dw_singleton__:' || e."stageKey"
         )
)
UPDATE "DwStageEntry" t
   SET "clientKey" = '__dw_singleton__:' || t."stageKey"
  FROM candidate c
 WHERE t.id = c.id
   AND c.rank_in_group = 1;
