-- Two-step verification: when a pending (unconfirmed) setup secret was stored.
--
-- ADDITIVE ONLY: one nullable column. Every existing row reads "no pending setup recorded", and a
-- pending secret with no timestamp is treated as stale by `two_factor.pending_is_live`, so a setup a
-- person abandoned before this column existed is discarded rather than revived. `IF NOT EXISTS` for
-- the reason 20260913130200_dw_inspection_feedback gives: the migration runner can be interrupted
-- and re-run.

ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "twoFactorPendingSince" TIMESTAMP(3);
