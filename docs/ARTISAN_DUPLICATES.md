# Duplicate artisans: finding, holding, merging

One person recorded twice is two half-records: the interviews sit on one, the photographs on the other,
and a count of artisans is wrong by one. This feature finds likely duplicates, asks before a new one is
made, and lets an admin merge a pair on one screen, reversibly for 30 days.

Code: `backend/app/services/artisan_duplicates.py` (scoring, the scan),
`backend/app/services/artisan_merge.py` (merge, undo, redirect, purge),
`backend/app/api/routes/artisan_duplicates.py` (the API). Web: `frontend/lib/duplicates.ts`,
`frontend/components/duplicates/`, `frontend/app/(protected)/admin/duplicates/`. Android:
`data/PossibleDuplicates.kt`, `ui/PossibleDuplicateSheet.kt`.

---

## 1. Scoring

The server is the only scorer. Neither client ports it, and an offline create is checked when it syncs.

- **Definitive:** the same Aadhaar fingerprint or the same Pehchan number scores 1.0. Two designer-portal
  records linked to the same Field Repository artisan score 0.98.
- **Otherwise** a pair is considered only when the names are at least half alike (trigram Jaccard of the
  normalised names, or 0.9 × the share of phonetic tokens that agree, whichever is higher). The score
  is 0.55 × that, plus: same state and district 0.15, place or village alike 0.10, same phone 0.15,
  same date of birth 0.10, same craft 0.05, same local name 0.05. Capped at 1.0.
- **Caps:** different fingerprints or different Pehchan numbers cap the score at 0.30, different genders
  at 0.40, and birth years more than three apart take 0.25 off.
- **Bands:** `VERY_LIKELY` from 0.90, `LIKELY` from 0.75 (the hold threshold), `POSSIBLE` from 0.60
  (the show threshold). Below 0.60 nothing is stored.

Names are normalised the way `cross_portal_codes.normalise()` does, with honorifics dropped (Shri, Smt,
Dr, …) and Mohd/Md read as Mohammad. The phonetic skeleton reads Lakshmi and Laxmi alike. No Postgres
extension is used. Every vector the scorer is held to is in `shared/duplicate-score-vectors.json`.

**Reasons are codes with short labels** ("Names 92% alike", "Same district"). No reason, label or
stored row ever carries an identifier value.

## 2. At save time

`POST /api/artisan-duplicates/check` takes what the form knows so far and answers up to five
candidates at 0.60 or more, filtered by the same visibility rule as the artisan list. The Aadhaar
travels in the body only and is fingerprinted, never stored. An `AADHAAR` reason appears only when the
caller typed that number. Candidates carry a birth year, never a date of birth, and never a masked
Aadhaar or a Pehchan number. 30 checks a minute per user.

`POST /api/artisans` takes `duplicateAcknowledged`, the ids the creator called "Different person"
(`[]` when nothing was shown). A create that sends the list and has an unacknowledged candidate at 0.75
or more is answered `409 artisan_possible_duplicate` with the candidates. A create without the key (an
older build) is never held. Either way the candidates are stored afterwards, with
`creatorSaidDifferent` set on the acknowledged ones. `ARTISAN_DUPLICATE_HOLD=false` switches the hold
off and leaves everything else on ([ENVIRONMENT.md](ENVIRONMENT.md)).

- **Web form:** checks on blur of name, district and phone, and again before saving when they
  changed. The "Already recorded?" card shows up to three rows with Open, and one "Different person"
  button.
- **Android form:** checks before a create when online and shows the same sheet, with Cancel and
  "Different person — save" on one line.
- **Offline, both clients:** the create is queued with `[]`. When the sync is held, the outbox row reads
  "Not saved yet. This looks like {name} ({place})." with **Same person** and **Different person**.
  Same person sends the queued files to that record and creates nothing. No field of the queued
  record is copied over. Different person sends it again with the candidates acknowledged. The hold is
  never read as a clash.

## 3. Review and merge (admin, web only)

**Admin → Duplicates** lists open pairs by score, with band pills and a Within / Field Repository
filter. The tabs are Open, Not duplicates and Merged. Not duplicates keeps that decision through every
later scan.

The pair screen shows both records field by field. A name, phone or similar field takes A or B, and
address, notes, do's and don'ts can also take Both. Aadhaar and Pehchan are locked to whichever side
has them, and `extraMetadata` is deep-merged. The survivor defaults to the record with more linked rows,
or to the older one on a tie.

**Blockers** refuse the merge: different fingerprints or Pehchan numbers, an un-backfilled legacy
Aadhaar, an Aadhaar legal hold, links to different Field Repository artisans, and an interview set that
would collide. That last one comes with a button that runs the existing interview merge first.

A merge runs in one transaction:
1. The deleted record's unique keys are cleared before the survivor is written.
2. Every reference is re-pointed and each moved key is logged. The references are products, tools,
   media, workshop and tool links, interview artisan lists, section status, revisions, review logs,
   comments, access scopes, tasks, printed record codes and design-workshop `artisanRef`s (with a
   `version` bump).
3. A revision is written on the survivor, and the deleted record is removed.

`MERGE_REPOINT` is checked against `schema.prisma` by `backend/tests/test_artisan_merge_registry.py`,
so a table added later cannot be missed. Access logs are not moved, because they record facts.

**Redirect.** `GET /api/artisans/{id}` on a merged id answers `404 {"code":"artisan_merged",
"survivorId":…}`. Every write that names an artisan id, queued offline writes included, lands on the
survivor. The web record page and the handset both open the survivor.

**Undo** (Merged tab, 30 days) recreates the record with its id and moves back the rows that still point
at the survivor. A survivor field edited since the merge keeps its new value and is reported. An undo
is refused when it is past 30 days, when the survivor was merged again since, or when the restored
fingerprint or Pehchan is now held by someone else.

## 4. Across the two portals

Records are never merged across databases. Cross-portal pairs offer **Link** and **Not the same**.

The daily scan sends the Field Repository the fingerprint and Pehchan number of every unlinked artisan
here. The endpoint is `POST /api/cross-portal/peer/artisans/match`, signed like every peer call, and
capped at 50 calls a day. It answers ids only, for consenting artisans only, and logs every match on its
side. This portal stores only the remote id. Details are fetched live, through the logged lookup, when
an admin opens the pair. No name, district or other fuzzy signal ever crosses. Merging a linked record
moves its link at the peer first, and undoes that move if the local transaction fails.

## 5. Data protection

- The merge snapshot holds the deleted record's row without `aadhaarNumber`: fingerprint and last
  four only, plus the `aadhaarVerify*` columns. It is purged after 30 days. [SECURITY.md](SECURITY.md)
  covers backups.
- Nothing merges automatically, at any score. Every merge records who did it, the reason and the field
  choices (keys only).

## How this document is kept true

Tests: `test_artisan_duplicates_scoring.py`, `test_artisan_duplicates_api.py`, `test_artisan_merge.py`,
`test_artisan_merge_registry.py`, `test_cross_portal_duplicates.py`, `test_record_save_contract.py`;
web `duplicates-form-unit`, `offline-possible-duplicate-unit`, `merge-table-unit`; Android
`OutboxPossibleDuplicateTest`.
