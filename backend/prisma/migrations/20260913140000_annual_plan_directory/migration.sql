-- The ministry's annual directory of planned workshops — 200-300 rows a year, uploaded as one
-- Excel sheet and corrected by re-uploading the same sheet.
--
-- =============================================================================================
-- WHAT THIS TABLE IS, AND THE ONE THING IT IS NOT
-- =============================================================================================
--
-- Owner's requirement: "An annual directory of 200-300 planned workshops - containing details like
-- workshop number, date, state, district, and venue - will be uploaded via an Excel sheet." The
-- owner's paper marks that requirement “Phase 2”, which is a QUOTATION out of their document and
-- not a claim about this repository. What is true as of 2026-09-13: this table ships with an
-- upload, a list, an .xlsx export and a promote arm, and with no Android screen and no per-region
-- scope — check `grep -rn "annual-plan" backend/app/api/routes/ android/`.
--
-- A row here is a LINE IN A DOCUMENT. Somebody at the ministry intends that a workshop happen. It
-- holds no fieldwork, no stages, no media, no consent and no report, and it grants nobody access to
-- anything. `DesignWorkshop` is the table that holds a fortnight of work, and the two must never be
-- unioned, counted together or exported together: a ministry shown "287 workshops held this year"
-- when 284 of them have not started is the failure this paragraph exists to prevent. The boundary
-- is enumerated in `backend/app/services/annual_plan.py` and asserted, surface by surface, in
-- `backend/tests/test_annual_plan_is_not_a_workshop.py`.
--
-- =============================================================================================
-- THIS FILE CARRIES EVERY ONE OF ITS STATEMENTS, AND THAT IS NOT AN OVERSIGHT
-- =============================================================================================
--
-- The standalone-statement rule in this directory applies to `ALTER TYPE ... ADD VALUE` only:
-- Prisma runs one migration inside one implicit transaction, and a Postgres enum value added by
-- `ADD VALUE` cannot be USED in the same transaction that added it, so such a statement has to be
-- alone in its own migration (see `20260827140000_inspector_role/migration.sql`). This migration
-- adds no enum value — deliberately, see the next block — so the whole table, its indexes and its
-- five foreign keys ship as one atomic unit, which is what you want for a create: a half-created
-- table is a deploy that leaves the application importing a model the database does not have.
--
-- =============================================================================================
-- THE NATURAL KEY IS (planYear, workshopNoKey), AND THE SECOND HALF IS A FOLDED COPY
-- =============================================================================================
--
-- A corrected sheet has to land on the rows it already wrote. The ministry's workshop number is
-- what identifies a planned workshop across two versions of one spreadsheet — nothing else in the
-- row is stable, because the correction is usually to the venue, the district or the date. But the
-- number AS TYPED is not a key: the same reference arrives as "DPW/2026/017", "dpw/2026/017 " and
-- "DPW/2026/<NBSP>017" across three saves of one file, and three rows for one workshop is the
-- defect.
--
-- So the number is stored twice: `workshopNo` exactly as the sheet spells it, because that is what
-- a person reads and what goes onto the workshop's cover as its code; and `workshopNoKey` folded
-- (NFKC, whitespace collapsed, trimmed, upper-cased) for matching. That is the same split
-- `DesignerProfile.phoneKey`/`empanelmentKey` made, for the same stated reason.
--
-- THERE IS NO SQL TWIN OF THE FOLDING RULE HERE, AND THAT IS DELIBERATE RATHER THAN AN OMISSION.
-- Migration `20260830170000` had to spell its two normalisations in SQL as well as in Python
-- because it BACKFILLED existing rows, and `tests/test_auth_identity_and_password_links.py` pins
-- that the two copies agree character for character. This table is new and has no rows, so the only
-- writer of `workshopNoKey` is `annual_plan_xlsx.fold_workshop_no` in Python and there is no second
-- copy to drift from. Do not add one.
--
-- =============================================================================================
-- NOT A POSTGRES ENUM ANYWHERE, INCLUDING FOR STANDING
-- =============================================================================================
--
-- `workshopKind` is TEXT for the reason `DesignWorkshop.workshopKind` is TEXT (migration
-- `20260830150000`): the vocabulary lives in `stage_schema.ENUMS["WORKSHOP_KIND"]`, which is the
-- registry both clients read, and a second list in the database would be the one that refuses a
-- write for a member the registry already offers.
--
-- There is no `status` column and no `AnnualPlanEntryStatus` type. Standing is PLANNED / PROMOTED /
-- WITHDRAWN and all three are already readable off `withdrawnAt` and `designWorkshopId`. A stored
-- status would be a second source for a fact those two columns already carry, and the two would
-- disagree the first time a promotion failed halfway. `annual_plan.standing_of` derives it in one
-- place — the rule `services/questionnaire_forms.py` states as "two places deciding one rule is how
-- they drift".
--
-- =============================================================================================
-- designWorkshopId IS UNIQUE, AND THAT IS THE PROMOTION RULE MADE STRUCTURAL
-- =============================================================================================
--
-- A planned row becomes at most one workshop, and a workshop comes out of at most one planned row.
-- The route refuses a second promotion with a sentence naming the workshop that already exists — a
-- constraint violation cannot say WHICH — but a route is one door and this is the kind of rule that
-- gets a second door added to it, so the database refuses it too. The foreign key sits on THIS
-- table rather than as a column on `DesignWorkshop` because that table is read by 40-odd routes and
-- is the subject of `stage_schema.PROMOTED_COLUMNS`; a new column there invites the next reader to
-- promote it out of a stage, which is a registry change and an Android release.
--
-- =============================================================================================
-- NOTHING IS DELETED, AND THE ACCOUNT POINTERS ARE ALL SET NULL AND ALL INDEXED
-- =============================================================================================
--
-- A row absent from a later sheet is WITHDRAWN (a stamp and who did it), never removed: it may
-- already have become a workshop, and the directory is the record of what was intended as much as
-- of what is intended now. The four `User` pointers are SET NULL so removing an account never fails
-- halfway through — none of them belongs in `routes/users.py`'s `_NAMED_ON_RELATIONS` — and each is
-- indexed for that DELETE and not for any read, which is the reasoning `DwStageEntry.createdById`
-- and `DesignWorkshop.dictationConsentById` both record.

CREATE TABLE "AnnualPlanEntry" (
    "id" TEXT NOT NULL,
    "planYear" INTEGER NOT NULL,
    "workshopNo" TEXT NOT NULL,
    "workshopNoKey" TEXT NOT NULL,
    "plannedTitle" TEXT,
    "workshopKind" TEXT,
    "craftName" TEXT,
    "clusterName" TEXT,
    "state" TEXT,
    "district" TEXT,
    "venue" TEXT,
    "plannedStartDate" TIMESTAMP(3),
    "plannedEndDate" TIMESTAMP(3),
    "implementingAgency" TEXT,
    "sponsor" TEXT,
    "notes" TEXT,
    "sheetRow" INTEGER,
    "sourceFilename" TEXT,
    "revision" INTEGER NOT NULL DEFAULT 1,
    "withdrawnAt" TIMESTAMP(3),
    "withdrawnById" TEXT,
    "designWorkshopId" TEXT,
    "promotedAt" TIMESTAMP(3),
    "promotedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "createdById" TEXT,
    "updatedById" TEXT,

    CONSTRAINT "AnnualPlanEntry_pkey" PRIMARY KEY ("id")
);

-- The natural key. A corrected sheet finds its rows through this index or creates them; it cannot
-- do either twice. It is also the only constraint an upload can violate, which is why the parser
-- pre-empts an in-sheet duplicate (reporting BOTH Excel rows) before the write is attempted at all.
CREATE UNIQUE INDEX "AnnualPlanEntry_planYear_workshopNoKey_key"
    ON "AnnualPlanEntry"("planYear", "workshopNoKey");

-- One workshop per plan row, enforced by the database and not only by the promote route.
CREATE UNIQUE INDEX "AnnualPlanEntry_designWorkshopId_key"
    ON "AnnualPlanEntry"("designWorkshopId");

-- The directory screen's default read (one year, standing rows, date order) and its two filters.
CREATE INDEX "AnnualPlanEntry_planYear_withdrawnAt_idx"
    ON "AnnualPlanEntry"("planYear", "withdrawnAt");
CREATE INDEX "AnnualPlanEntry_planYear_plannedStartDate_idx"
    ON "AnnualPlanEntry"("planYear", "plannedStartDate");
CREATE INDEX "AnnualPlanEntry_state_district_idx"
    ON "AnnualPlanEntry"("state", "district");
-- The year picker: GET /api/annual-plan/years groups by this column.
CREATE INDEX "AnnualPlanEntry_planYear_idx" ON "AnnualPlanEntry"("planYear");

-- Indexed for the ON DELETE SET NULL each is on the wrong end of, not for a read.
CREATE INDEX "AnnualPlanEntry_withdrawnById_idx" ON "AnnualPlanEntry"("withdrawnById");
CREATE INDEX "AnnualPlanEntry_promotedById_idx" ON "AnnualPlanEntry"("promotedById");
CREATE INDEX "AnnualPlanEntry_createdById_idx" ON "AnnualPlanEntry"("createdById");
CREATE INDEX "AnnualPlanEntry_updatedById_idx" ON "AnnualPlanEntry"("updatedById");

ALTER TABLE "AnnualPlanEntry" ADD CONSTRAINT "AnnualPlanEntry_designWorkshopId_fkey"
    FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "AnnualPlanEntry" ADD CONSTRAINT "AnnualPlanEntry_withdrawnById_fkey"
    FOREIGN KEY ("withdrawnById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "AnnualPlanEntry" ADD CONSTRAINT "AnnualPlanEntry_promotedById_fkey"
    FOREIGN KEY ("promotedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "AnnualPlanEntry" ADD CONSTRAINT "AnnualPlanEntry_createdById_fkey"
    FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "AnnualPlanEntry" ADD CONSTRAINT "AnnualPlanEntry_updatedById_fkey"
    FOREIGN KEY ("updatedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
