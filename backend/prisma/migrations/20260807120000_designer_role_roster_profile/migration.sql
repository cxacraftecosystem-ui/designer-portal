-- The DESIGNER tier, the roster that gates its sign-in, and the profile a designer fills in once.
--
-- Additive and reversible. The enum gains a value (Postgres cannot remove one, but an unused value
-- is inert), and two new tables appear; nothing existing is altered or dropped.
--
-- WHY THE ENUM VALUE IS ADDED IN ITS OWN STATEMENT, FIRST. Before Postgres 12, and inside any
-- transaction on every version up to 14, a value added by ALTER TYPE ... ADD VALUE cannot be USED
-- in the same transaction — `unsafe use of new value "DESIGNER" of enum type "UserRole"`. Prisma
-- sends a migration file as one multi-statement query, which Postgres wraps in an implicit
-- transaction, so a later statement in this file that mentioned 'DESIGNER' would fail the deploy.
-- Nothing below does, deliberately: the roster is keyed by email and carries no role column, and
-- promoting an account to DESIGNER is an application action, not a migration.

-- AlterEnum
ALTER TYPE "UserRole" ADD VALUE IF NOT EXISTS 'DESIGNER';

-- CreateTable
CREATE TABLE "DesignerRoster" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "fullName" TEXT,
    "institution" TEXT,
    "notes" TEXT,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "revokedAt" TIMESTAMP(3),
    "firstSeenAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "addedById" TEXT,

    CONSTRAINT "DesignerRoster_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DesignerProfile" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "displayName" TEXT,
    "localName" TEXT,
    "designation" TEXT,
    "institution" TEXT,
    "department" TEXT,
    "qualification" TEXT,
    "specialisation" TEXT,
    "experienceYears" INTEGER,
    "biography" TEXT,
    "phone" TEXT,
    "email" TEXT,
    "website" TEXT,
    "addressLine" TEXT,
    "city" TEXT,
    "state" TEXT,
    "pincode" TEXT,
    "photoMediaId" TEXT,
    "signatureMediaId" TEXT,
    "empanelmentNo" TEXT,
    "empanelmentDate" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DesignerProfile_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
-- The roster is looked up by email on EVERY sign-in, so this index is on the hot path of the login
-- request, not merely of the admin screen.
CREATE UNIQUE INDEX "DesignerRoster_email_key" ON "DesignerRoster"("email");
CREATE INDEX "DesignerRoster_isActive_idx" ON "DesignerRoster"("isActive");
CREATE INDEX "DesignerRoster_addedById_idx" ON "DesignerRoster"("addedById");

-- CreateIndex
CREATE UNIQUE INDEX "DesignerProfile_userId_key" ON "DesignerProfile"("userId");

-- AddForeignKey
-- SET NULL on the admin who added a row: losing who empanelled somebody is preferable to blocking
-- the removal of a departed account, and the roster row itself must survive either way.
ALTER TABLE "DesignerRoster" ADD CONSTRAINT "DesignerRoster_addedById_fkey" FOREIGN KEY ("addedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
-- CASCADE: a profile is part of its account and has no meaning without it.
ALTER TABLE "DesignerProfile" ADD CONSTRAINT "DesignerProfile_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
