-- The last time each account actually got in (User.lastLoginAt), beside the first (firstLoginAt).
--
-- ADDITIVE ONLY: one nullable column, no backfill. Nothing on the server records past sign-ins, so
-- there is nothing true to backfill it from; it fills in from each account's next sign-in, and the
-- screens read an empty value beside a firstLoginAt as "signed in, first on <date>". `IF NOT EXISTS`
-- because the migration runner can be interrupted and re-run.

ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "lastLoginAt" TIMESTAMP(3);
