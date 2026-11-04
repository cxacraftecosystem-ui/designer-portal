\set ON_ERROR_STOP on

TRUNCATE TABLE "DwStageEntry", "DesignWorkshopViewer", "DesignWorkshop", "MediaFile",
               "QuestionnaireInterview", "ToolDocumentation", "ProductDocumentation",
               "Workshop", "Artisan", "Craft", "User" RESTART IDENTITY CASCADE;

INSERT INTO "User" (id, email, name, "passwordHash", role, "authProvider", "createdAt", "updatedAt")
SELECT
  'bench-user-' || i,
  'bench' || i || '@example.test',
  'Bench Designer ' || i,
  NULL,
  (CASE WHEN i % 100 = 0 THEN 'ADMIN'
        WHEN i % 25  = 0 THEN 'PROFESSOR'
        ELSE 'DESIGNER' END)::"UserRole",
  'LOCAL'::"AuthProvider",
  now() - (i || ' minutes')::interval,
  now()
FROM generate_series(1, 1200) AS i;

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
  (CASE WHEN i % 50 = 0 THEN now() ELSE NULL END)
FROM generate_series(1, 6000) AS i;

INSERT INTO "DesignWorkshopViewer" ("designWorkshopId", "userId", "grantedById", "createdAt")
SELECT
  'bench-dw-' || i,
  'bench-user-' || (1 + ((i * 7 + k) % 1200)),
  'bench-user-100',
  now()
FROM generate_series(1, 6000) AS i, generate_series(0, 2) AS k
ON CONFLICT DO NOTHING;

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
  jsonb_build_object('notes', jsonb_build_object('by', 'bench-user-' || (1 + (i % 1200))))
FROM generate_series(1, 6000) AS i, generate_series(0, 29) AS s;

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

ANALYZE;
