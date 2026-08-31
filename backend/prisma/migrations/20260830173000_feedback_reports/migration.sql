-- The grievance / suggestion / recommendation / bug register.
--
-- ADDITIVE ONLY. Nothing is altered and nothing is dropped: the existing `Feedback` table is the
-- one-per-account satisfaction survey and keeps every row it has, because widening it to many rows
-- would have broken the upsert both clients' feedback screens are built on. See the two models'
-- doc comments in schema.prisma for the argument in full.
--
-- EVERY CLOSED LIST IS TEXT, NOT A POSTGRES ENUM, following the `workshopKind` precedent: a new
-- category is then a deploy rather than an ALTER TYPE against a pooled database, and a retired
-- category keeps printing on the rows already filed under it instead of becoming a read error on
-- the oldest grievances. `services/feedback_vocabulary` is the validator.
--
-- `status` carries a DEFAULT so a row inserted by anything that does not know about the workflow is
-- still in a legible state rather than NULL. The two actor columns are ON DELETE SET NULL for the
-- reason the schema states: the record that a named person read a grievance must outlive their
-- account, and it must never become a new reason an administrator cannot delete a user.

CREATE TABLE "FeedbackReport" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "kind" TEXT NOT NULL,
    "severity" TEXT,
    "area" TEXT,
    "subject" TEXT NOT NULL,
    "details" TEXT NOT NULL,
    "client" TEXT,
    "clientVersion" TEXT,
    "platform" TEXT,
    "pagePath" TEXT,
    "status" TEXT NOT NULL DEFAULT 'SUBMITTED',
    "acknowledgedById" TEXT,
    "acknowledgedAt" TIMESTAMP(3),
    "resolvedById" TEXT,
    "resolvedAt" TIMESTAMP(3),
    "responseNote" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "FeedbackReport_pkey" PRIMARY KEY ("id")
);

-- The four reads this table has, and nothing speculative. `[status, createdAt]` is the
-- administrator's inbox, `[userId, createdAt]` a person's own list on the settings card,
-- `[kind, createdAt]` the research cut, and `[createdAt]` the keyset walk the dataset export does.
CREATE INDEX "FeedbackReport_status_createdAt_idx" ON "FeedbackReport"("status", "createdAt");
CREATE INDEX "FeedbackReport_userId_createdAt_idx" ON "FeedbackReport"("userId", "createdAt");
CREATE INDEX "FeedbackReport_kind_createdAt_idx" ON "FeedbackReport"("kind", "createdAt");
CREATE INDEX "FeedbackReport_createdAt_idx" ON "FeedbackReport"("createdAt");

ALTER TABLE "FeedbackReport" ADD CONSTRAINT "FeedbackReport_userId_fkey"
    FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "FeedbackReport" ADD CONSTRAINT "FeedbackReport_acknowledgedById_fkey"
    FOREIGN KEY ("acknowledgedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "FeedbackReport" ADD CONSTRAINT "FeedbackReport_resolvedById_fkey"
    FOREIGN KEY ("resolvedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
