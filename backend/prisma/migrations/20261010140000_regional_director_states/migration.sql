-- THE SCOPE A REGIONAL DIRECTOR NEEDS TO CORRECT THEIR OWN STATE'S ANNUAL-PLAN ROWS.
--
-- One new table and nothing else: no existing column, constraint or index changes. A Regional
-- Director with no row here reads and corrects nothing on the annual plan, so applying this
-- migration changes nobody's access until a Ministry Admin assigns a state.

-- CreateTable
CREATE TABLE "RegionalDirectorState" (
    "userId" TEXT NOT NULL,
    "state" TEXT NOT NULL,
    "assignedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RegionalDirectorState_pkey" PRIMARY KEY ("userId","state")
);

-- CreateIndex
CREATE INDEX "RegionalDirectorState_state_idx" ON "RegionalDirectorState"("state");

-- CreateIndex
CREATE INDEX "RegionalDirectorState_assignedById_idx" ON "RegionalDirectorState"("assignedById");

-- AddForeignKey
ALTER TABLE "RegionalDirectorState" ADD CONSTRAINT "RegionalDirectorState_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RegionalDirectorState" ADD CONSTRAINT "RegionalDirectorState_assignedById_fkey" FOREIGN KEY ("assignedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
