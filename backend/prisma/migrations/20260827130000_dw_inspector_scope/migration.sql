-- THE FIFTH SCOPE: which design & prototype workshops one INSPECTOR may READ, and read only.
--
-- A new tier sits between DESIGNER (35) and PROFESSOR (40) for somebody who INSPECTS and REVIEWS a
-- designer's work without running workshops themselves. They are deliberately NOT added to
-- `deps.DESIGN_WORKSHOP_ROLES`, which stays "the people who sign the report", so every one of the
-- eighteen routes behind `_require_designer` refuses them by construction and
-- `load_workshop_or_404` answers 404. This table is the only thing that gives them anything, and
-- what it gives is a READ.
--
-- PURELY ADDITIVE. One new table. No column is added, dropped or retyped on any existing table, no
-- constraint anywhere is relaxed, and no row anywhere is written — production is pre-launch (2
-- users, 1 workshop as of 2026-08-27; re-check with
-- `SELECT count(*) FROM "User"; SELECT count(*) FROM "DesignWorkshop";`) and there is nothing to
-- backfill. Rolling this back is `DROP TABLE "DesignWorkshopInspector";` and nothing else — no
-- other table references it.
--
-- ⚠ THIS MIGRATION DELIBERATELY DOES NOT CONTAIN `ALTER TYPE "UserRole" ADD VALUE 'INSPECTOR'`.
--
-- That statement belongs to the migration that adds the tier to `ROLE_RANK` and to the seven
-- hand-kept mirrors, and it must be the FIRST statement of whichever file carries it — see
-- `20260807120000_designer_role_roster_profile`, whose header explains that Prisma sends a
-- migration as one implicit transaction and that a value added by `ALTER TYPE ... ADD VALUE`
-- cannot be USED later in the same transaction ("unsafe use of new value ... of enum type").
--
-- Splitting them is not tidiness, it is what makes both files deployable in either order. NOTHING
-- BELOW MENTIONS THE ENUM: this table is keyed by user id, and the rule that only an INSPECTOR may
-- hold a row here is an APPLICATION rule enforced in `services/design_workshop_inspectors.py`,
-- exactly as `20260807120000`'s roster is keyed by email and carries no role column. So this file
-- deploys cleanly on a database that has never heard of INSPECTOR — it simply describes a scope
-- that nobody is yet eligible to hold, which is the correct fail-closed order.
--
-- THE THREE `ON DELETE` CHOICES, each copied from `DesignWorkshopViewer` with its reasoning:
--
--   * designWorkshopId -> CASCADE. A purged workshop must not leave inspections pointing at a row
--     that is gone. This fires only on a HARD delete; the API's delete is a soft one (`deletedAt`),
--     so an inspector's scope survives a soft delete and a restore brings the inspection back with
--     the workshop. (The scope still shows them nothing while it is deleted — the loader in
--     `services/design_workshop_inspectors.py` filters `deletedAt: NULL`, because an inspector is
--     not an admin and has no restore button to press.)
--
--   * userId -> CASCADE, and deliberately NOT the RESTRICT every *authorship* FK on User carries.
--     An inspection row is not authorship: it records that an account which no longer exists was
--     once asked to read something. Restricting would invent a brand-new reason an account cannot
--     be deleted — see `_undeletable_detail` in app/api/routes/users.py — and one no admin could
--     clear, because no screen offers "take this person off every inspection".
--
--   * assignedById -> SET NULL, matching `DesignWorkshopViewer.grantedById` and
--     `WorkshopAssignment.assignedById`. The inspection must outlive the admin who ordered it:
--     losing that admin must not end an inspection halfway through, and "who ordered it" is not
--     worth an outage.
--
-- THE PRIMARY KEY IS THE (workshop, user) PAIR rather than a synthetic id, matching
-- `DesignWorkshopViewer` and for the same reason: nothing ever addresses one of these rows on its
-- own. There is no PATCH and no DELETE by row id; the only write is a whole-set replace. So the
-- pair is the identity, a re-assignment collides instead of stacking a second row, and the key
-- doubles as the index for the "may this inspector open this workshop" lookup.
--
-- THERE IS NO STATUS COLUMN AND THERE MUST NEVER BE ONE. `DesignWorkshopViewer`'s schema comment
-- forbids "a second column deciding access" and that rule is inherited here: the ROW is the grant.
-- Removing an inspector DELETES the row. Nothing was ever requested and nobody was ever refused, so
-- there is no decision to keep a tombstone for.

-- CreateTable
CREATE TABLE "DesignWorkshopInspector" (
    "designWorkshopId" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "assignedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DesignWorkshopInspector_pkey" PRIMARY KEY ("designWorkshopId","userId")
);

-- CreateIndex
-- The OTHER direction: "which workshops may this inspector see", which is the inspection list's
-- scope clause. The primary key above cannot serve it — userId is its SECOND column — so without
-- this the inspection list would sequentially scan the table on every request an inspector makes.
CREATE INDEX "DesignWorkshopInspector_userId_idx" ON "DesignWorkshopInspector"("userId");

-- CreateIndex
-- The account pointer, indexed for the ON DELETE SET NULL it is on the wrong end of rather than for
-- a read: deleting an admin makes Postgres find every inspection they ordered.
CREATE INDEX "DesignWorkshopInspector_assignedById_idx" ON "DesignWorkshopInspector"("assignedById");

-- AddForeignKey
ALTER TABLE "DesignWorkshopInspector" ADD CONSTRAINT "DesignWorkshopInspector_designWorkshopId_fkey" FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DesignWorkshopInspector" ADD CONSTRAINT "DesignWorkshopInspector_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DesignWorkshopInspector" ADD CONSTRAINT "DesignWorkshopInspector_assignedById_fkey" FOREIGN KEY ("assignedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
