-- C27: the access log for Field Repository lookups made from this portal, and the counter behind
-- the per-account lookup limit. Additive and guarded; nothing writes it while
-- CROSS_PORTAL_IDENTITY_ENABLED is false. No identity number is ever stored in it.

CREATE TABLE IF NOT EXISTS "CrossPortalAccessLog" (
    "id" TEXT NOT NULL,
    "userId" TEXT,
    "action" TEXT NOT NULL,
    "outcome" TEXT NOT NULL,
    "artisanId" TEXT,
    "remoteArtisanId" TEXT,
    "matchedBy" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CrossPortalAccessLog_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "CrossPortalAccessLog_userId_action_createdAt_idx"
    ON "CrossPortalAccessLog"("userId", "action", "createdAt");
CREATE INDEX IF NOT EXISTS "CrossPortalAccessLog_artisanId_createdAt_idx"
    ON "CrossPortalAccessLog"("artisanId", "createdAt");
CREATE INDEX IF NOT EXISTS "CrossPortalAccessLog_createdAt_idx"
    ON "CrossPortalAccessLog"("createdAt");
