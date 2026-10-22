-- U5 (UNIFY_2026-10-21.md section 3.1): a v2 session is a dated event, and the same artisans may sit
-- on several days (DESIGN B1, "create always creates"). The global one-interview-per-artisan-set key
-- belonged to the retired global form, so it goes with the switch-on commit and no earlier: until
-- then the old form's idempotent create still relies on it. The column stays, indexed, for the shims.
DROP INDEX IF EXISTS "QuestionnaireInterview_artisanSetKey_key";
CREATE INDEX IF NOT EXISTS "QuestionnaireInterview_artisanSetKey_idx" ON "QuestionnaireInterview"("artisanSetKey");
