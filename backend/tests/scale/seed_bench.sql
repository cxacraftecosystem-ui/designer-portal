-- ============================================================================================
-- A LOAD-SHAPED DATABASE, BUILT IN SQL RATHER THAN THROUGH THE ORM.
--
-- WHY SQL AND NOT prisma, and not the write paths this API already has. This file exists to answer
-- "what does a query plan do when the table is big", and the only properties that matter to that
-- question are ROW COUNT and SELECTIVITY. Building 600k rows through Prisma would take about an
-- hour and would exercise the write path, which is not what is under test here.
-- INSERT ... SELECT generate_series produces the same statistics in about a minute.
--
-- WHY THESE NUMBERS. Production is pre-launch (2 users, 1 workshop as of 2026-08-27), so there is
-- no traffic to learn from and the volumes below are the TARGET rather than the present: 1,000
-- concurrently active designers, each running roughly six Design & Prototype workshops a season
-- (the figure the DesignWorkshop docstring in schema.prisma uses), 22 stages each, and the media a
-- fortnight in the field produces. Anything much smaller and every plan is a sequential scan that
-- happens to be fast, which measures nothing at all.
--
-- THIS IS NOT A SECOND SOURCE OF TRUTH FOR THE SCHEMA -- prisma/schema.prisma is the only one.
-- This script assumes the schema has ALREADY been applied to the target database (clone it with
-- pg_dump --schema-only from a migrated database) and only fills it.
--
-- Re-runnable: every table it owns is TRUNCATEd first.
--
-- Run it with:
--   docker exec -i design-workshop-postgres psql -U postgres -d bench_scale -f - < seed_bench.sql
-- ============================================================================================

\set ON_ERROR_STOP on

TRUNCATE TABLE "DwStageEntry", "DesignWorkshopViewer", "DesignWorkshop", "MediaFile",
               "QuestionnaireInterview", "ToolDocumentation", "ProductDocumentation",
               "Workshop", "Artisan", "Craft", "User" RESTART IDENTITY CASCADE;

-- --------------------------------------------------------------------------------------------
-- Accounts. 1,200 = the 1,000 concurrent target plus the staff who are not designers.
--
-- THE ROLE MIX MATTERS MORE THAN THE COUNT. records.owned_or_granted_where and
-- records.media_url_owners SHORT-CIRCUIT for PROFESSOR and above with no query at all, so a
-- benchmark seeded entirely with professors would measure the cheap branch and then report that
-- the expensive one does not exist. Designers are the population that pays for the workshop-tag
-- lookup, so they are the bulk here, exactly as they are in the field.
-- --------------------------------------------------------------------------------------------
INSERT INTO "User" (id, email, name, "passwordHash", role, "authProvider", "createdAt", "updatedAt")
SELECT
  'bench-user-' || i,
  'bench' || i || '@example.test',
  'Bench Designer ' || i,
  -- NULL rather than a bcrypt hash: nothing in this benchmark signs in with a password (tokens are
  -- minted directly), and a NULL hash is what a Google-provisioned account carries anyway.
  NULL,
  (CASE WHEN i % 100 = 0 THEN 'ADMIN'
        WHEN i % 25  = 0 THEN 'PROFESSOR'
        ELSE 'DESIGNER' END)::"UserRole",
  'LOCAL'::"AuthProvider",
  now() - (i || ' minutes')::interval,
  now()
FROM generate_series(1, 1200) AS i;

-- --------------------------------------------------------------------------------------------
-- The four record tables the dashboard counts and /search scans.
-- --------------------------------------------------------------------------------------------
INSERT INTO "Craft" (id, name, "createdAt", "updatedAt")
SELECT 'bench-craft-' || i, 'Craft ' || i, now(), now() FROM generate_series(1, 40) AS i;

INSERT INTO "Artisan" (id, name, place, status, "createdAt", "updatedAt", "createdById", "craftId")
SELECT
  'bench-artisan-' || i,
  'Artisan ' || i,
  'Village ' || (i % 400),
  (CASE WHEN i % 7 = 0 THEN 'PENDING' ELSE 'APPROVED' END)::"RecordStatus",
  now() - (i || ' seconds')::interval,
  now(),
  'bench-user-' || (1 + (i % 1200)),
  'bench-craft-' || (1 + (i % 40))
FROM generate_series(1, 60000) AS i;

INSERT INTO "Workshop" (id, title, date, place, status, "createdAt", "updatedAt", "createdById")
SELECT
  'bench-workshop-' || i,
  'Workshop ' || i,
  now() - (i || ' hours')::interval,
  'Place ' || (i % 300),
  (CASE WHEN i % 9 = 0 THEN 'PENDING' ELSE 'APPROVED' END)::"RecordStatus",
  now() - (i || ' seconds')::interval,
  now(),
  'bench-user-' || (1 + (i % 1200))
FROM generate_series(1, 12000) AS i;

INSERT INTO "ProductDocumentation"
  (id, "craftName", place, "artisanName", "productName", status, "createdAt", "updatedAt", "createdById")
SELECT
  'bench-product-' || i,
  'Craft ' || (i % 40),
  'Place ' || (i % 300),
  'Artisan ' || (i % 60000),
  'Product ' || i,
  (CASE WHEN i % 6 = 0 THEN 'PENDING' ELSE 'APPROVED' END)::"RecordStatus",
  now() - (i || ' seconds')::interval,
  now(),
  'bench-user-' || (1 + (i % 1200))
FROM generate_series(1, 25000) AS i;

INSERT INTO "ToolDocumentation"
  (id, "craftName", place, "artisanName", "toolkitName", status, "createdAt", "updatedAt", "createdById")
SELECT
  'bench-tool-' || i,
  'Craft ' || (i % 40),
  'Place ' || (i % 300),
  'Artisan ' || (i % 60000),
  'Toolkit ' || i,
  (CASE WHEN i % 8 = 0 THEN 'PENDING' ELSE 'APPROVED' END)::"RecordStatus",
  now() - (i || ' seconds')::interval,
  now(),
  'bench-user-' || (1 + (i % 1200))
FROM generate_series(1, 18000) AS i;

INSERT INTO "QuestionnaireInterview" (id, title, status, "createdAt", "updatedAt", "createdById")
SELECT
  'bench-interview-' || i,
  'Interview ' || i,
  (CASE WHEN i % 5 = 0 THEN 'PENDING' ELSE 'APPROVED' END)::"RecordStatus",
  now() - (i || ' seconds')::interval,
  now(),
  'bench-user-' || (1 + (i % 1200))
FROM generate_series(1, 8000) AS i;

-- --------------------------------------------------------------------------------------------
-- Design workshops: 6,000, which is 1,000 designers times six a season.
-- --------------------------------------------------------------------------------------------
INSERT INTO "DesignWorkshop"
  (id, title, "templateId", status, "workshopCode", "craftName", "clusterName", state, district,
   "createdAt", "updatedAt", "createdById", "deletedAt")
SELECT
  'bench-dw-' || i,
  'Design Workshop ' || i,
  'DCH_STANDARD',
  (CASE WHEN i % 4 = 0 THEN 'DRAFT' ELSE 'IN_PROGRESS' END)::"DesignWorkshopStatus",
  'DW-' || lpad(i::text, 6, '0'),
  'Craft ' || (i % 40),
  'Cluster ' || (i % 250),
  'State ' || (i % 28),
  'District ' || (i % 600),
  now() - (i || ' seconds')::interval,
  now(),
  'bench-user-' || (1 + (i % 1200)),
  -- A soft-deleted tail, because every read filters deletedAt IS NULL and a column that is
  -- uniformly NULL tells the planner it can stop thinking about that clause.
  (CASE WHEN i % 50 = 0 THEN now() ELSE NULL END)
FROM generate_series(1, 6000) AS i;

-- Co-designers, three per workshop. This is the table visible_to_clause reaches through on every
-- list, every workshop open, and every media-tag lookup.
INSERT INTO "DesignWorkshopViewer" ("designWorkshopId", "userId", "grantedById", "createdAt")
SELECT
  'bench-dw-' || i,
  'bench-user-' || (1 + ((i * 7 + k) % 1200)),
  'bench-user-100',
  now()
FROM generate_series(1, 6000) AS i, generate_series(0, 2) AS k
ON CONFLICT DO NOTHING;

-- 22 stages, and a little over one entity per stage on average (a stage carrying a collection has
-- several rows; most have the singleton alone). 30 rows a workshop, 180,000 in all.
INSERT INTO "DwStageEntry"
  (id, "designWorkshopId", "stageKey", "entityKey", ordinal, data, "createdAt", "updatedAt",
   "createdById", "deletedAt", "fieldProvenance")
SELECT
  'bench-entry-' || i || '-' || s,
  'bench-dw-' || i,
  'stage_' || (1 + (s % 22)),
  (CASE WHEN s < 22 THEN '_singleton' ELSE 'item_' || s END),
  s,
  jsonb_build_object(
    'notes', 'Field note for workshop ' || i || ' stage ' || s,
    'observations', repeat('x', 200)
  ),
  now() - (s || ' minutes')::interval,
  now(),
  'bench-user-' || (1 + (i % 1200)),
  NULL,
  -- A provenance stamp with a "by" and no "byName" is what makes entry_provenance
  -- resolve_display_names actually issue its User query on the stage read. Seeding it empty would
  -- have hidden a round trip the real read always pays.
  jsonb_build_object('notes', jsonb_build_object('by', 'bench-user-' || (1 + (i % 1200))))
FROM generate_series(1, 6000) AS i, generate_series(0, 29) AS s;

-- --------------------------------------------------------------------------------------------
-- Media. A quarter of a million files is what a fortnight of photography per workshop comes to,
-- and it is the largest table in the deployment -- which is what makes it the one where a missing
-- index is the difference between an index scan and reading the whole thing.
--
-- THE TAG PAIR IS THE POINT of two thirds of these rows: linkedRecordType='designWorkshop' with a
-- workshop id in linkedRecordId is the shape records._design_workshop_media_branches and
-- records.media_url_scope both filter on, and it is on the authorisation path of every media read.
-- A benchmark whose media carried only foreign keys would never touch that index.
-- --------------------------------------------------------------------------------------------
INSERT INTO "MediaFile"
  (id, "originalFilename", "mediaType", "mimeType", "sizeBytes", bucket, "objectKey", url,
   "linkedRecordType", "linkedRecordId", status, "createdAt", "updatedAt", "recordedAt",
   "uploadedById", "workshopId", "artisanId", "transcriptText")
SELECT
  'bench-media-' || i,
  'field-' || i || '.jpg',
  (CASE WHEN i % 10 = 0 THEN 'AUDIO' WHEN i % 10 = 1 THEN 'VIDEO' ELSE 'IMAGE' END)::"MediaType",
  'image/jpeg',
  1048576 + i,
  'bench-bucket',
  'bench/objects/' || i,
  'https://example.test/bench/' || i,
  (CASE WHEN i % 3 = 0 THEN 'artisan' ELSE 'designWorkshop' END),
  (CASE WHEN i % 3 = 0 THEN 'bench-artisan-' || (1 + (i % 60000))
        ELSE 'bench-dw-' || (1 + (i % 6000)) END),
  (CASE WHEN i % 11 = 0 THEN 'PENDING' ELSE 'APPROVED' END)::"RecordStatus",
  now() - (i || ' seconds')::interval,
  now(),
  now() - (i || ' seconds')::interval,
  'bench-user-' || (1 + (i % 1200)),
  (CASE WHEN i % 5 = 0 THEN 'bench-workshop-' || (1 + (i % 12000)) ELSE NULL END),
  (CASE WHEN i % 3 = 0 THEN 'bench-artisan-' || (1 + (i % 60000)) ELSE NULL END),
  (CASE WHEN i % 10 = 0 THEN 'Transcribed speech for recording ' || i ELSE NULL END)
FROM generate_series(1, 250000) AS i;

-- The planner is the thing under test and it plans from statistics. Without this, every
-- measurement taken afterwards is taken against tables Postgres still believes are empty.
ANALYZE;
