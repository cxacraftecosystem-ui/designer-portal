-- Cross-device create idempotency: `clientKey` on the four record models an outbox can replay —
-- `Workshop`, `ProductDocumentation`, `ToolDocumentation` and `Process`.
--
-- =============================================================================================
-- THE DUPLICATE THIS CLOSES, AND WHY THE TWO CLIENT-SIDE GUARDS COULD NOT CLOSE IT
-- =============================================================================================
--
-- A queued create is POSTed, this server writes the row, and the answer is lost on the way back --
-- a tunnel, a captive portal, the process killed by the OS while the request was in flight. The
-- client learned nothing, so the entry is still in its queue and the next pass sends it again.
--
-- Both clients already guard the case they CAN see. `frontend/lib/offline.ts` writes `createdId`
-- the moment an answer lands and `entryAlreadyCreated` reads it back; Android's
-- `PendingEntry.createdId` does the same, and `replayEntry` skips the create when it is set.
-- NEITHER CAN GUARD AN ANSWER THAT NEVER ARRIVED, because both are records of a reply. The web
-- outbox says so itself, in `persistProgress`: *"a few milliseconds of IndexedDB is as small as
-- that window gets without idempotency keys on the API."* This is that key, and the sentence is
-- quoted rather than paraphrased because it is the specification.
--
-- AND IT IS THE ONLY GUARD THAT CROSSES A DEVICE OR A PROFILE. `createdId` is a fact ONE browser
-- profile -- or one handset's queue file -- holds about its own send. A queue restored onto a
-- second handset, or drained after a sign-out and back in, holds no such record, and the same
-- fieldwork is filed twice under one designer's name in a register nobody reconciles.
--
-- =============================================================================================
-- ONLY FOUR MODELS, BECAUSE THE OTHER TWO ARE ALREADY PROTECTED
-- =============================================================================================
--
-- `Artisan` has `aadhaarNumber @unique` plus `artisans._guard_identity_conflicts`, a pre-write 409
-- naming the artisan that already holds the number -- so a replayed artisan create is refused by
-- the dedup key that exists for exactly that reason. `QuestionnaireInterview` has
-- `artisanSetKey @unique` and `questionnaire._DUPLICATE_SET_DETAIL`. Adding a second idempotency
-- mechanism to either would be two guards that can disagree about what a duplicate is. `Craft` is
-- untouched for the same shape of reason: `Craft.name` is `@unique`.
--
-- =============================================================================================
-- A PLAIN NULLABLE UNIQUE, WHICH *IS* THE PARTIAL INDEX -- NOT A SECOND, HAND-WRITTEN ONE
-- =============================================================================================
--
-- Postgres treats NULLs as distinct under a unique index, so a unique index on a nullable column
-- already permits any number of rows with no key: `CREATE UNIQUE INDEX … WHERE "clientKey" IS NOT
-- NULL` would build exactly the same guarantee. The difference is only that the partial form is
-- INEXPRESSIBLE in schema.prisma, and a constraint the schema cannot state is a constraint the
-- schema disagrees with the database about -- which is the condition every future `prisma migrate
-- diff` would try to "fix".
--
-- So this file writes the index Prisma itself would write for `clientKey String? @unique`,
-- including the name it would choose (`"<Table>_clientKey_key"`), and the schema states the same
-- thing. `Artisan.aadhaarNumber` settled this once already, in a column comment that says the
-- quiet part: existing rows "keep NULL -- readable, editable, and exempt from the unique index,
-- since Postgres permits any number of NULLs under one."
--
-- THE CONTRAST WORTH NAMING IS `DwStageEntry.clientKey`, which shares the spelling and not the
-- semantics: that column identifies one ROW WITHIN ONE WORKSHOP, is unique only in company
-- (`@@unique([designWorkshopId, entityKey, clientKey])`), and REFUSES a partial unique on purpose
-- because its null-keyed collection rows must coexist by the many. This one identifies one CREATE
-- REQUEST, globally.
--
-- =============================================================================================
-- NULLABLE, NO DEFAULT, NO BACKFILL -- WHICH IS THE WIRE CONTRACT, NOT AN OMISSION
-- =============================================================================================
--
-- Every row already in these four tables was created without a key and nothing can invent one for
-- it retroactively: a key identifies a REQUEST, and the requests are gone. So an absent key has to
-- go on meaning what it means today -- create the row, answer 201 -- which is exactly what every
-- fielded 0.0.7 APK and every cached web bundle sends. `NOT NULL DEFAULT ''` was considered and is
-- worse than useless: one empty string would then collide with the next, and the second create of
-- any kind on this deployment would be refused.
--
-- =============================================================================================
-- IDEMPOTENT, FOLLOWING THE PRECEDENT IN THIS DIRECTORY
-- =============================================================================================
--
-- `20260903090000_dw_stage_entry_version` and `20260903093000_app_release_size_bytes` both write
-- `ADD COLUMN IF NOT EXISTS`, and the reason is one this repository has already paid for:
-- migrations here are hand-authored and applied by piping .sql through psql, so a re-run of a
-- directory that was half-applied must not fail on the statement that did land. The index
-- statements carry `IF NOT EXISTS` for the same reason.
--
-- CREATING THE INDEX CANNOT FAIL ON EXISTING DATA. Every existing row gets NULL from the ADD
-- COLUMN, and NULLs do not collide, so there is no duplicate for the unique build to trip over --
-- unlike `20260822094000_dw_singleton_client_key`, which had to pick a winner per group first.

ALTER TABLE "Workshop"             ADD COLUMN IF NOT EXISTS "clientKey" TEXT;
ALTER TABLE "ProductDocumentation" ADD COLUMN IF NOT EXISTS "clientKey" TEXT;
ALTER TABLE "ToolDocumentation"    ADD COLUMN IF NOT EXISTS "clientKey" TEXT;
ALTER TABLE "Process"              ADD COLUMN IF NOT EXISTS "clientKey" TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS "Workshop_clientKey_key"
  ON "Workshop" ("clientKey");
CREATE UNIQUE INDEX IF NOT EXISTS "ProductDocumentation_clientKey_key"
  ON "ProductDocumentation" ("clientKey");
CREATE UNIQUE INDEX IF NOT EXISTS "ToolDocumentation_clientKey_key"
  ON "ToolDocumentation" ("clientKey");
CREATE UNIQUE INDEX IF NOT EXISTS "Process_clientKey_key"
  ON "Process" ("clientKey");
