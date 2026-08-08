-- Who, besides the person who pressed "create", may open one design & prototype workshop.
--
-- THE FAILURE THIS ENDS. `load_workshop_or_404` admitted `createdById` and admins and nobody else,
-- so a 22-stage record representing a fortnight of fieldwork was readable by exactly one account.
-- A real Design & Prototype Development Workshop is run by two designers alongside a master
-- craftsperson and a reviewing officer; the second designer could not open the workshop at all,
-- and a designer who left mid-season took the record with them — there was no handover short of an
-- admin editing the database by hand.
--
-- PURELY ADDITIVE. One new table. No column is added, dropped or retyped on any existing table and
-- no constraint anywhere is relaxed, so rolling this back is `DROP TABLE "DesignWorkshopViewer"`
-- and nothing else — no other table references it.
--
-- THE THREE `ON DELETE` CHOICES, EACH DELIBERATE AND EACH DIFFERENT:
--
--   * designWorkshopId -> CASCADE. A purged workshop must not leave grants pointing at a row that
--     is gone. Note this fires only on a HARD delete; the API's delete is a soft one (`deletedAt`),
--     so ordinary deletion leaves the grants intact and a restore brings the team back with the
--     workshop rather than silently returning it to its creator alone.
--
--   * userId -> CASCADE, and deliberately NOT the RESTRICT that every *authorship* FK on User
--     carries (DesignWorkshop.createdById, Artisan.createdById, ...). Those restrict because
--     deleting the account would erase who recorded the research. A viewer row is not authorship:
--     it records that an account which no longer exists was once allowed to READ something, which
--     is worth nothing to anybody. Restricting would instead invent a brand-new reason an account
--     cannot be deleted — see `_undeletable_detail` in app/api/routes/users.py — and one no admin
--     could clear, because no screen offers "take this person off every workshop". It also matches
--     the two access tables already here: WorkshopAssignment.userId and DataAccessGrant.granteeId
--     are both CASCADE.
--
--   * grantedById -> SET NULL, matching WorkshopAssignment.assignedById. The grant must outlive the
--     admin who made it: losing the granter mid-season must not lock a working co-designer out of
--     the workshop they are halfway through, and "who let them in" is not worth an outage.
--
-- The PRIMARY KEY is the (workshop, user) PAIR rather than a synthetic id, unlike WorkshopAssignment
-- which carries both an `id` and a UNIQUE on the pair. It can be, because nothing ever addresses one
-- of these rows on its own — there is no PATCH and no DELETE by row id; the only write is a
-- whole-set replace. So the pair is the identity, a re-grant collides instead of stacking a second
-- row, and the key doubles as the index for the "may this user open this workshop" lookup that
-- `load_workshop_or_404` now makes on every read by a non-creator.

-- CreateTable
CREATE TABLE "DesignWorkshopViewer" (
    "designWorkshopId" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "grantedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DesignWorkshopViewer_pkey" PRIMARY KEY ("designWorkshopId","userId")
);

-- CreateIndex
-- The OTHER direction: "which workshops may this user see", which is the list endpoint's scope
-- clause. The primary key above cannot serve it — userId is its SECOND column — so without this the
-- workshop list would sequentially scan the grant table on every request by a non-admin.
CREATE INDEX "DesignWorkshopViewer_userId_idx" ON "DesignWorkshopViewer"("userId");

-- AddForeignKey
ALTER TABLE "DesignWorkshopViewer" ADD CONSTRAINT "DesignWorkshopViewer_designWorkshopId_fkey" FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DesignWorkshopViewer" ADD CONSTRAINT "DesignWorkshopViewer_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DesignWorkshopViewer" ADD CONSTRAINT "DesignWorkshopViewer_grantedById_fkey" FOREIGN KEY ("grantedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
