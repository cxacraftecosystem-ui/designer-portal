-- The short-list reason a reviewer picked, beside the free notes, as documentation-portal's ReviewLog
-- carries it. The shared questionnaire (app/questionnaire/sessions.py) reads it for a session's
-- "returned because" line; nothing on this portal writes it yet. Additive and nullable.
ALTER TABLE "ReviewLog" ADD COLUMN IF NOT EXISTS "reason" TEXT;
