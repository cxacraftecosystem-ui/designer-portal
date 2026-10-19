# Search

One search box finds artisans, crafts, clusters, workshops, design workshops, interviews,
transcripts, reports, products, tools, processes and media, on this portal and in the Field
Repository. Each portal indexes only its own database; the other portal's results arrive over the
cross-portal signed channel (C27, [ENVIRONMENT.md](ENVIRONMENT.md)), for the people allowed to see
them and only for records the other portal may share.

**Everything ships OFF.** With `UNIFIED_SEARCH_ENABLED` off, `GET /api/search/unified` answers 404
and both clients use the legacy `GET /api/search` exactly as before (§6).

| | |
|---|---|
| Backend | `backend/app/services/search_index.py` (what a record becomes), `search_indexer.py` (queue drain, embeddings), `search_query.py` (the ranking SQL), `search_embeddings.py` (the AI flag), `unified_search.py` (permissions, peer call, merge); `backend/app/api/routes/search.py` (`/search/unified`, `/search/remote/{type}/{id}`), `routes/cross_portal_search.py` (peer routes) |
| Shared with the Field Repository, byte for byte | `backend/app/services/cross_portal.py`, `cross_portal_client.py`, `cross_portal_peer.py`, `search_embeddings.py` |
| Web | `frontend/lib/unifiedSearch.ts`, `frontend/app/(protected)/search/page.tsx`, `frontend/app/(protected)/remote/[type]/[id]/page.tsx` |
| Android | `ui/AppSearch.kt`, `ui/AppSearchSources.kt`, `ui/RemoteRecordScreen.kt`, `data/UnifiedSearch.kt` |
| Schema | `20261025090000_search_document` |
| Operator script | `backend/scripts/search_reindex.py` |
| Tests | `backend/tests/test_search_index_builders.py`, `test_search_triggers.py`, `test_search_unified.py`, `test_search_embeddings.py`, `test_cross_portal_search_peer.py`, `test_cross_portal_search_caller.py`, `test_cross_portal_shared_modules.py`; `frontend/e2e/unified-search-unit.spec.ts`, `remote-record-unit.spec.ts`; `AppSearchTest.kt`, `UnifiedSearchParsingTest.kt` |

---

## 1. The index

`SearchDocument` holds one row per record and type: a title, a subtitle, an allowlisted body (at
most 100,000 characters), the place, the craft ids and shared craft codes, the workshop and artisan
ids it names, its design workshop, an `audience`, a `shareable` flag and the web path of its
read-only view. `tsv` is a generated column (`simple` and `english` over the title, `simple` over
subtitle and place, both over the body); a trigram index covers `lower(title)`. Where pgvector is
installed the migration also adds `embedding vector(768)` with an HNSW index; Prisma does not
declare that column.

**Every builder is an allowlist** (`search_index.BUILDERS`). Aadhaar, Pehchan, phone, e-mail, date of
birth and the stated address are never named, and free text has e-mail addresses and runs of ten or
more digits removed (`search_index.scrub`). Interview answers to identity questions are skipped.

| Type | What is indexed | Audience | Opens at |
|---|---|---|---|
| artisans | name, local name, craft, place | ALL | `/artisans/{id}` |
| crafts | name, local name, category, description, place | ALL | `/crafts/{id}` |
| clusters | each spelling of `clusterName` across design workshops and the annual plan, crafts, districts and states; keyed by the normalised name | ALL | `/design-workshops?cluster={key}` |
| workshops | title, type, place, description | ALL | `/workshops/{id}` |
| designWorkshops | title, code, cluster, craft, venue, and the live stage text with stage labels | DW | `/design-workshops/{id}` |
| interviews | title, language, place, notes, text answers | ALL | `/questionnaire/interviews/{id}` |
| transcripts | file name, caption, summary, transcript | DW when the file belongs to a design workshop | `/media/{id}` |
| reports | file name, format, workshop | DW | `/design-workshops/{workshopId}` |
| products | product, local name, craft, artisan, remarks | ALL | `/products/{id}` |
| tools | toolkit, English name, craft, artisan, remarks | ALL | `/tools/{id}` |
| processes | name and steps | ALL | `/processes/{id}` |
| media | file name, caption, kind | DW when the file belongs to a design workshop | `/media/{id}` |

**Audience.** `ALL` is readable by every signed-in account, as the record lists are. `DW` is read by
professor and above (`deps.can_view_design_workshop_data`) or by an account the workshop is visible
to (`records._design_workshop_media_ids`).

**Shareable** (may be shown to the Field Repository): the record names no artisan, or every artisan
it names has a `CrossPortalArtisanLink` (the artisan's consent recorded on this portal). A record
naming an artisan only as free text is never shareable, and design-workshop data never is. A
process names its product's artisan. A file (media and transcripts) names its own artisan and the
artisans of the product, tool or interview it hangs off, by the typed column or, where only the tag
was filled, by `linkedRecordType`/`linkedRecordId`.

### Keeping it current

`search_enqueue()` triggers sit on every source table. An insert or update lists the record in
`SearchIndexQueue`; a delete removes its document in the same statement, so the index never outlives
a record. A stage row lists its design workshop, a plan row or a workshop's cluster name lists the
cluster, a process step its process, a response or an interview-artisan link its interview, a link
row its artisan. The queue worker (`app/worker.py`) drains 100 items every 15 seconds
(`search_indexer.drain`) and deletes the rows it read only after building them, so a worker killed
mid-batch loses nothing. It expands each record to the documents that take their artisans from it
(`search_index.dependents_of`: an artisan to its products, their processes, tools, interviews and
every file on any of them; a product to its processes and files; a tool or an interview to its
files), and then embeds up to 32 changed documents when embeddings are on. The migration only lists
every record in the queue; the build happens in the worker after `UNIFIED_SEARCH_ENABLED` is
switched on. Until then the worker only compacts the queue to one row per record every ten minutes
(`search_indexer.compact_queue`), so it never outgrows the number of records.

`python -m scripts.search_reindex [--type media] [--drain]` (from `backend/`) lists records again
after a builder changes.

## 2. Ranking

One SQL statement per search (`search_query.build_sql`) returns the page of every asked type and
each type's total, so a search holds one of the production pool's connections for one query. Three
candidate lists, each capped at 200 per type and each filtered by the same permission and filter
predicates:

1. **Full text**: `tsv @@ (websearch_to_tsquery('english', q) || websearch_to_tsquery('simple', q))`
   by `ts_rank_cd`. `english` stems ("dyes" finds "dyeing"); `simple` matches any script.
2. **Trigram**: `word_similarity(lower(q), lower(title)) >= 0.45`, for names and typos ("indgo"
   finds "indigo"). The threshold is spelled out because pg_trgm's `<%` default of 0.6 misses
   one-letter typos in short words.
3. **Meaning** (only in hybrid mode): nearest embeddings by cosine distance.

They are fused by reciprocal rank, `score = Σ 1/(60 + rank)`, ties broken by id. Snippets are
`ts_headline` plain text; highlights are character ranges computed in Python, so no HTML travels.

**Embeddings (the AI flag).** `SEARCH_EMBEDDINGS_ENABLED`, the AI master switch
`AI_FEATURES_ENABLED` (`app.ai_features.settings`, the one variable that stops every paid AI call), a
provider key, and the `embedding` column (probed once per process) must all be present; otherwise the response says `"mode": "text"`
and no provider is called. The query is embedded once with a 1.5 s timeout and a 256-entry cache;
any failure is text mode. Documents are embedded from title, subtitle and the first 8,000
characters of the body. A batch the provider refuses is retried one document at a time: a
document refused alone is marked (`embeddedHash = 'failed:' || contentHash`) and skipped until its
text changes; when every one is refused the provider is down, and the worker waits 30 seconds,
doubling to an hour, before the next call. **People's words are embedded only with
consent** (the one consent store, `services/dictation_consent`): a transcript asks
`transcription_verdict` for its recording, an interview `interview_verdict`, and a design workshop
(its stage text) `workshop_verdict`; one that may not leave is marked
(`embeddedHash = 'consent:' || contentHash`) and asked again whenever a new `Consent` row lands
(`test_search_embedding_consent.py`). The vectors never leave this portal. With `SEARCH_EMBEDDING_PROVIDER=gemini` and the
OpenAI model name left as it is, `gemini-embedding-001` is asked for the same 768 dimensions.

## 3. API

**`GET /api/search/unified`** (signed in). `q` (2 to 200 characters after control characters are
removed), `types` (repeatable or comma-separated), `scope=all|local`, `limit` per group (5, max 20),
`type` + `page` for one group's pages, and the filters `craftId`, `place`, `dateFrom`, `dateTo`,
`workshopIds`.

```json
{"query": "indigo", "mode": "text",
 "groups": [{"type": "artisans", "label": "Artisans", "total": 14, "localTotal": 9, "remoteTotal": 5,
   "items": [{"portal": "designer", "local": true, "type": "artisans", "id": "ck1",
     "title": "Ramesh Kumar", "subtitle": "Indigo dyeing · Bagru", "snippet": "…",
     "highlights": [[0, 6]], "place": "Bagru", "craftCodes": ["CR-0142"],
     "recordDate": "2026-09-02T00:00:00Z", "href": "/artisans/ck1", "openUrl": null, "score": 0.016393}]}],
 "remote": {"portal": "fieldRepository", "label": "Field Repository", "status": "ok"},
 "typesRefused": []}
```

Groups come in the vocabulary order and empty ones are left out. Within a group local and remote
items are interleaved by position (`1/(60 + position)` on each side), local first on a tie.
With `type` and `page`, the page holds that page of each side (up to `2 × limit` rows), so nothing
falls between two pages.
`typesRefused` lists design workshops and reports when the account can see none.

`remote.status`:

| Status | When |
|---|---|
| `ok` | The Field Repository answered. |
| `off` | `scope=local`, or cross-portal search is not configured. |
| `notPermitted` | The account's role is not in `CROSS_PORTAL_SEARCH_ROLES` (field contributors and crowdsource volunteers). No call is made. |
| `filteredOut` | `workshopIds` was given (workshop ids are local), or the craft has no shared code. No call is made. |
| `unavailable` | The peer timed out (2.5 s), refused the token, or sent an answer not signed for this request. Local results still return. |

**`GET /api/search/remote/{type}/{id}`** (signed in, a cross-portal role): one Field Repository
record as a read-only card, `{portal, type, id, title, subtitle, fields: [{label, value}], snippet,
recordDate, openUrl}`. 404 for anything missing, refused or not shareable; 503 when the peer cannot
be reached. Logged as `RECORD_VIEW`.

### Peer routes (the Field Repository asking this portal)

`POST /api/cross-portal/peer/search` and `POST /api/cross-portal/peer/search/record`, admitted by the
shared token and a fresh nonce (`cross_portal_peer.require_search_peer`) and answered with a body
signed over the request. POST keeps the query out of URLs and proxy logs.

1. The requester's e-mail is matched to an account here that the allow-list does not refuse. A match
   brings that account's read rules; no match gets the floor (`audience = 'ALL'`). Either way only
   `shareable` documents are considered.
2. Consent is re-checked live in the same SQL: every artisan a record names must still have a link,
   so an unlink takes effect before the indexer catches up.
3. Each item carries only `PEER_ITEM_KEYS` plus `linkedArtisanIds` (the Field Repository's ids for the
   linked artisans, so it can apply its own consent once more). Never a media URL, storage key,
   phone, e-mail, Aadhaar, Pehchan, date of birth or address.
4. Every search and record view is written to `CrossPortalAccessLog` (`SEARCH` / `RECORD_VIEW`, the
   asking account, the result count). The query text is never logged.
5. 120 searches per 10 minutes per asking account, then a signed 429.

## 4. Clients

**Web.** The one box is the `/search` page ("Search" in the island). With a query it shows the
unified groups, five per group, with "Show all" paging one group; remote rows carry a portal badge
and open `/remote/{type}/{id}`, a read-only card with an "Open in Field Repository" button. Without
a query, or with the artisan or media-type filters, the page browses through `GET /api/search` as
before. `lib/unifiedSearch.ts` falls back to that route whenever `/search/unified` answers 404.

**Android.** The search header (`AppSearchHeader`) asks `search/unified` with five per group and
falls back to the old fan-out on 404. Remote rows carry a portal chip and open
`RemoteRecordScreen`, whose "Open in Field Repository" launches the browser. Offline, the cached
items stay as they are; nothing remote is cached.

## 5. Data protection

- What crosses is a short text projection; opening the full record means signing in on the other
  portal, where that account's own permissions apply.
- Consent is enforced at index time (`shareable`), live in the peer SQL, and again by the Field
  Repository on designer results that name its artisans.
- The answering portal trusts the e-mail the peer server asserts, which rests on the shared token.
  A match can only bring in that local account's own rights; every request is logged and rate
  limited.
- Embeddings are computed from allowlisted text only and stay in this portal.

## 6. Rollout

1. Deploy with every flag off. The migration adds two tables, four nullable log columns, the
   triggers, and a queue listing every record; nothing heavy runs.
2. `UNIFIED_SEARCH_ENABLED=true`; the worker drains the queue (a few thousand rows take minutes).
3. The cross-portal values on both servers, then `CROSS_PORTAL_SEARCH_ENABLED=true` on both.
4. Embeddings last. EXPLAIN the ranking query against a production-sized clone first.

## 7. How this document is kept true

| Claim | Kept true by |
|---|---|
| No identity value reaches the index; the shareable rule | `test_search_index_builders.py` |
| Insert/update enqueue, delete removes at once, links reach dependents | `test_search_triggers.py` |
| Ranking, grouping, paging, filters, the DW gate, 404 while off | `test_search_unified.py` |
| Text mode without the flag, key or column; no provider call | `test_search_embeddings.py` |
| Peer admission, signing, floor, live consent, projection, log, rate limit | `test_cross_portal_search_peer.py` |
| Remote status values, timeouts, signatures, record 404/503 | `test_cross_portal_search_caller.py` |
| The shared modules match the Field Repository's | `test_cross_portal_shared_modules.py` with `CROSS_PORTAL_PEER_REPO` set |
| The variables are documented | [ENVIRONMENT.md](ENVIRONMENT.md) |

**Review triggers:** a new record type or a new identity field on a source table (add it to a builder
or to nothing), a change to `CROSS_PORTAL_SEARCH_ROLES`, and any change to the three shared modules
(make it in both repositories).
