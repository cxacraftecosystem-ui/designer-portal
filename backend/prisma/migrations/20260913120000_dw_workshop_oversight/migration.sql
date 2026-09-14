-- WHO IS ACCOUNTABLE FOR A DESIGN & PROTOTYPE WORKSHOP: the Assistant Director and the Regional
-- Director, one of each, named per workshop by a Ministry Admin or an admin.
--
-- ADDITIVE ONLY. Nothing is altered and nothing is dropped. The inspector table is untouched and
-- `INSPECTION_ROLES` in app/services/design_workshop_inspectors.py keeps its single member — see
-- that module's import-time RuntimeError at the foot of the file, which this change deliberately
-- leaves meaning exactly what it meant before. A `capacity` column on that table was the
-- obvious-looking home and is refused for six reasons written out in the header of
-- app/services/design_workshop_oversight.py; the decisive two are that its primary key
-- (designWorkshopId, userId) IS its identity, and that every route on its prefix is a GET —
-- asserted by walking the real dependency tree in tests/test_dw_inspector_scope_gate.py. An
-- Assistant Director who could record nothing is not a supervisor; an Assistant Director who could
-- would put a non-GET on a prefix that test hard-fails for, BY DESIGN.
--
-- AN ENUM AND NOT TEXT, which is the opposite of the `workshopKind` / `FeedbackReport` precedent,
-- because this column is HALF THE PRIMARY KEY: a typo'd capacity is a third row that neither the AD
-- lookup nor the RD lookup finds, i.e. an officer assigned to a workshop nobody can see they were
-- assigned to. That is `DwAccessRequestStatus`'s argument, not `workshopKind`'s.
--
-- CREATE TYPE AND CREATE TABLE IN ONE FILE IS SAFE AND `ALTER TYPE … ADD VALUE` WOULD NOT BE. A type
-- created in this transaction may be used by DDL in it; a VALUE added to an existing type may not be
-- used by DML in it. A third capacity is therefore a migration of its own carrying nothing but the
-- ALTER TYPE, as this repository's enum rule requires — the same two-step
-- 20260913100000_assistant_director_role and its two siblings had to make for `UserRole`.
--
-- THE THREE DIRECTORATE `UserRole` VALUES MUST ALREADY EXIST WHEN THE SERVICE CODE DEPLOYS. This
-- file does not depend on them — no role token appears in it — but
-- app/services/design_workshop_oversight.py filters
-- `role IN ('ASSISTANT_DIRECTOR','REGIONAL_DIRECTOR','MINISTRY_ADMIN')`, which is an ERROR against
-- an enum that has not heard of them: a 500 on GET /officers rather than a 403. Their three
-- ALTER TYPE migrations are 20260913100000, 20260913100100 and 20260913100200, all of which sort
-- before this one, so an ordinary `migrate deploy` cannot get this wrong.

CREATE TYPE "DwOversightCapacity" AS ENUM ('ASSISTANT_DIRECTOR', 'REGIONAL_DIRECTOR');

CREATE TABLE "DesignWorkshopOversight" (
    "designWorkshopId" TEXT NOT NULL,
    "capacity" "DwOversightCapacity" NOT NULL,
    "userId" TEXT NOT NULL,
    "assignedById" TEXT,
    "assignedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DesignWorkshopOversight_pkey" PRIMARY KEY ("designWorkshopId","capacity")
);

-- "Which workshops does this officer monitor" — the oversight list's scope clause. The primary key
-- above cannot answer it: `userId` is not in it. No composite with `capacity`: an officer holds one
-- role, so the capacity is implied by who is asking, and no route issues that query. An index is
-- never added ahead of its query.
CREATE INDEX "DesignWorkshopOversight_userId_idx" ON "DesignWorkshopOversight"("userId");
-- The actor pointer, indexed for the ON DELETE SET NULL below rather than for a read.
CREATE INDEX "DesignWorkshopOversight_assignedById_idx" ON "DesignWorkshopOversight"("assignedById");

ALTER TABLE "DesignWorkshopOversight" ADD CONSTRAINT "DesignWorkshopOversight_designWorkshopId_fkey"
    FOREIGN KEY ("designWorkshopId") REFERENCES "DesignWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DesignWorkshopOversight" ADD CONSTRAINT "DesignWorkshopOversight_userId_fkey"
    FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DesignWorkshopOversight" ADD CONSTRAINT "DesignWorkshopOversight_assignedById_fkey"
    FOREIGN KEY ("assignedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
