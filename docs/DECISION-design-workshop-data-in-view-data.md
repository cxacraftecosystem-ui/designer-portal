# Design-workshop data in View Data, and who may take it out

**Decided 2026-08-30 (access) and implemented 2026-08-31.**
**Owner ruling, verbatim:** *"professor can view data for design workshops as well, admins and
master admins can download and view it too."*

This document records what the gap was, what the split is, and — for each of the seven choices the
implementation had to make — what was decided and what was rejected. It is not a summary of the
code; the code carries its own reasoning at each site. It is the argument, in one place, so the next
person can tell whether a rule was weighed or merely inherited.

---

## 1. The gap, measured rather than asserted

`grep -c designWorkshop` answered **zero** in every one of `api/routes/data_browser.py`,
`api/routes/export.py`, `api/routes/datasets.py`, `services/record_fields.py`,
`services/xlsx_report.py` and `services/csv_export.py`.

So the screen whose entire purpose is *"browse everything we hold"* held the seven legacy repository
tables and **not one field** of the twenty-two-stage record this product is named after — 44 entity
tables, 639 columns.

Three consequences a researcher actually met:

1. **The whole-repository archive is named `design-workshop-dataset.zip` and contains no design
   workshop.** That is not an omission. It is an artefact that asserts something false about a
   corpus, in the one place nobody reads critically — a year later, opening the file, there is no
   way to tell whether the workshops were left out or were never recorded.
2. **`linkedRecordType="designWorkshop"` was not in `data_browser._TYPED_TAGS`.** So every stage
   photograph and every dictation recording *did* appear in View Data — swept by the `misc` clause
   (`linkedRecordType NOT IN (…)`) into "Miscellaneous", beside the genuinely unattached files. The
   bytes were browsable; what they were evidence **of** was not.
3. **`GET /search` had five buckets and its "workshops" one is the LEGACY `Workshop` table** — a
   different model from `DesignWorkshop`, with no join between them. Neither the workshop, nor its
   stages, nor its fields, nor its custom sections was reachable from the screen labelled "Search".

---

## 2. The access split, and why it is two predicates

| act | who | predicate |
|---|---|---|
| VIEW design-workshop data in View Data and Search | PROFESSOR, ADMIN, MASTER_ADMIN | `can_view_design_workshop_data` |
| DOWNLOAD / EXPORT it (.xlsx, the manifest the browser zips) | ADMIN, MASTER_ADMIN | `can_export_design_workshop_data` |

Both live in `backend/app/core/deps.py`, beside their neighbours — one home for permissions, never
two — and both are mirrored in `frontend/lib/permissions.ts`.

**Neither has a `Depends` twin, and that is deliberate.** Every other capability in `deps.py` has one
because it gates a whole ROUTE. These do not: `/data` stays mounted behind
`require_dataset_downloader` and `/search` behind `get_current_user`, so every account that reaches
those screens today still reaches them. What these two decide is how much of the answer the screen
contains, so they are consulted once per request — through `data_browser.Scope` and in
`search.py` — where the listers, the manifest walk, the report and the bucket plan all see the same
value and none of them can forget to ask. A `Depends` mounted on `/data` would refuse a granted
researcher the seven legacy tables they have always had.

### 2.1 It is NARROWER than the gate on the screen it appears on

`/data` is mounted behind `require_dataset_downloader`, which is *"Professor and above, **or** the
grantable `canDownloadDataset` flag"*. That flag is the whole difference: an admin hands it to a
**researcher** who needs the seven legacy tables for a piece of work, and it carries no seniority
with it.

Design-workshop stage data is a fortnight of a named designer's fieldwork, including artisan
dictation, consent decisions and unpublished prototype work. It is therefore gated on **rank alone**
and the grant does not reach it. A researcher holding `canDownloadDataset` browses View Data exactly
as they did before this change and simply never meets a design-workshop folder, sheet or bucket.

That is why one gate could not serve: `require_dataset_downloader` governs viewing and downloading
**together** for the legacy tables, and this needed a viewing rule that is narrower at one end and a
downloading rule that is narrower still.

### 2.2 It is a NEW capability beside `DESIGN_WORKSHOP_ROLES`, never a widening of it

`DESIGN_WORKSHOP_ROLES` is `{DESIGNER, ADMIN, MASTER_ADMIN}` — a set, not a rank floor, that
**excludes PROFESSOR on purpose**, with a long argument at `can_run_design_workshops` about seniority
not being the same thing as being a designer.

The new view set is almost its opposite: it **includes** professor and **excludes** designer. That
is not a contradiction; they are different acts.

- Running a workshop is **writing** inside somebody's fortnight of work — every stage save, the
  custom sections, the capture aids, the AI layers, the consent record.
- This is **reading** a table of what a corpus of workshops recorded, through a research surface.

A professor who gains this gains READ of research data and gains **nothing** inside any workshop. A
designer is not in it because a designer reaches their own workshops through `load_workshop_or_404`
— a per-record grant — whereas this predicate opens *every* workshop in the repository to a research
reader. The two doors are different sizes.

### 2.3 Why the view set is written as a SET and not `has_rank(user, "PROFESSOR")`

`PROFESSOR`, `ADMIN` and `MASTER_ADMIN` happen to be the top three rungs today, so a rank floor
would give the same answer. It is a set because the **rule** is a set: the owner named three roles,
not a threshold — and the tier immediately below professor is `INSPECTOR` (rank 37), somebody who
inspects **one** workshop under a grant. A floor would hand them every workshop in the repository
the day a rank is renumbered. A set has to be edited by a person who meant to.

### 2.4 Saying it on screen, because a 403 on a button is not a rule

There is a real population — professors — that can see rows on screen and may not export them. Every
surface that offers an export beside those rows says so where it applies:

- **`GET /data/report?format=json`** (what the page renders) returns the design-workshop sheets in
  full, plus `designWorkshopsVisible` / `designWorkshopsDownloadable`. The Data-tables panel prints
  *"Design workshop tables are on screen only. The .xlsx carries the rest."* beside the download
  button — and only when the reader is actually looking at such sheets, because a permission notice
  about data that is not on the screen reads as a fault.
- **`GET /data/report?format=xlsx`** drops the design-workshop sheets for a non-exporter, builds the
  workbook anyway, and puts **one sheet in their place** naming each withheld sheet and the number of
  rows it held. **Refusing the whole file was rejected**: a professor has always been able to
  download this workbook, and 403-ing it now would take away the seven legacy tables in order to
  protect data that is not in them — a regression dressed as a permission. **Dropping them silently
  was rejected too**: a workbook outlives the page it came from — it is archived, mailed on, opened a
  year later by somebody who never saw the sentence on screen — and a file that silently lacks a
  section it could have carried reads as a repository with no design workshops in it. The notice
  appears only when there was something to drop, so a granted researcher (who has no design-workshop
  sheets at all) is told nothing about a permission they do not have.
- **`GET /data/manifest`** (the list of files the browser zips) uses `Scope.for_download()`, in
  which viewing *is* exporting. A professor's archive is byte-for-byte the one they got before this
  change.
- **`GET /search`** drops the sixth bucket for a caller who may not read it and names it in
  `typesRefused`, so the page can say *"Design workshops are not searched at your access level"*
  rather than rendering a heading with nothing under it.

Media **bytes** are deliberately untouched by this split. `/data/media/{id}/download` is governed by
`records.owned_or_granted_where`, which is empty for professor and above and always has been; the
ruling is about the DATA — the stage answers — and re-gating a photograph a professor could already
open would be a change nobody asked for.

---

## 3. Sheets: which of 44 entity tables become tabs

**Decided: one sheet per entity that HAS ROWS, capped at 16, plus an always-present index page
naming all 44 with their row counts.** The full argument lives at
`services/design_workshop_data.sheet_plan`; the short form:

| grouping | verdict |
|---|---|
| One sheet per **stage** (22 tabs) | **Rejected.** A stage is not a table. Stage 13 holds three separate collections — the prototypes, their stage logs and material usage — so a per-stage sheet either repeats one collection's rows against every row of another or leaves most of every row blank. **Twelve** of the twenty-two stages hold two or more entities and 29 of the 44 entities are collections; both figures counted from `tables()`. This is the common case, not the corner. |
| One sheet per **entity**, all 44, always | **Rejected.** Most are empty on any real subtree: a workshop that has reached stage 8 has answered nothing in stages 9–22. The reader would open tab after tab to find a header row and nothing else. |
| One sheet per entity **with rows**, capped, plus an index | **Chosen.** Every tab in the workbook has data in it, the reader learns the shape of the whole registry from one page, and the cap cannot hide anything. |

**The index page is the whole point of the cap being acceptable.** Rule 10 — *a list that quietly
stops is indistinguishable from a place with no records* — applied to a workbook: without it, a
reader who found no "Market survey response" tab would conclude the workshops did no market survey,
when the truth might be that the tab budget ran out three entities earlier. So every entity is named
with its stage, its row count and one of three verdicts: **its own sheet**, **not shown — only 16
tables fit one workbook**, or **no rows found**. Two rows that are not registry entities are listed
for the same reason: the designer's own questions, and any entity key written against a newer
registry than this server runs.

**The designer's own questions get a sheet only when the path names ONE workshop**, and that is a
cost decision stated rather than hidden. Their columns are defined per workshop in
`DwCustomSection`/`DwCustomField`, so naming the columns of four hundred workshops means four
hundred definition loads on one request — and the columns would not be shared anyway: two designers
write "Dye bath?" and mean different questions, so the merged sheet would be a thousand columns wide
with one cell filled per row. At the root the rows are **counted** on the index page, with the
sentence that says where to read them.

---

## 4. The tree: a root of its own, not a branch under `by-workshop`

**Decided: a fourth taxonomy, `by-design-workshop`.**

The obvious-looking alternative — hanging design workshops under the existing `by-workshop` root —
silently loses most of them. `Workshop` and `DesignWorkshop` **are different tables**, joined only by
a **nullable** `DesignWorkshop.workshopId`; nothing goes the other way. A design workshop hung under
`by-workshop` would need a parent most of them do not have, so the ones that do not would end up
under a "No workshop" pseudo-folder. A taxonomy whose rows are mostly in the bucket for rows that do
not fit is not a taxonomy. (The column is real and is used: `record_filters`' `workshopIds` scope
narrows the search bucket through it.)

It is also the honest answer to what a researcher is asking. The other three roots ask *"where in
the repository does this file sit"*; this one asks *"what did this fortnight of fieldwork produce"*,
and its levels are the **stages**, which no other root has.

```
by-design-workshop                            one folder per design workshop
  <wid>                                       details.txt, 'stages', the designer's own
                                              questions, and the workshop's loose media
    stages/<stageKey>                         one <Entity>.txt per entity with rows, plus the
                                              photographs those rows cite
```

**The stage is a folder and the entity is a file.** The stage is what a designer, a report and a
ministry all order the fortnight by, so it is the level a person navigates; the entity is the
*table*, and a table is a document rather than a place. A folder per entity would mean four clicks
to read one answer and 44 empty folders on a workshop that has reached stage 8.

**Only stages with answers are listed, and the workshop's details panel says how many of the 22
those are** ("8 of 22 stages answered", "Rows recorded: 41"). Showing all 22 would be 22 doors of
which most open on nothing; showing only the answered ones *without saying so* would be
indistinguishable from a workshop that has no other stages.

`_locate_path` resolves `type=designWorkshop`, and resolves a design-workshop **media** file to
`by-design-workshop/<wid>/stages/<stageKey>` — the folder that names what it is evidence of.

**The root is hidden entirely from an account that may not read it**, and typing the path answers
404 rather than 403: an account that may not read this taxonomy has no business learning that it
exists and holds records.

---

## 5. Media identity: the leak, and where the answer comes from

`linkedRecordType="designWorkshop"` (both spellings — the clients send camelCase, and
`POST /media/{id}/relink` lower-cases whatever it is handed) now sits in `_TYPED_TAGS` and has a
typed branch of its own. Three surfaces changed:

- **The tree.** A design-workshop file is listed under the stage whose row cites it, and named
  `Stage-<nn>-<the field's own label>-…` through the same `media_naming` path every other folder
  uses. What no stage row cites — a miscellaneous upload filed under the workshop from that form's
  dropdown — is listed in the workshop folder.
- **The report.** `_media_link_label` no longer answers "Miscellaneous" for every stage photograph
  in the repository. It names the workshop and the stage, and `_media_context` fills the "Workshop"
  column from the design workshop, which the `workshop` relation (the legacy table) leaves blank on
  every one of these rows.
- **`/data/locate`.** These files used to match none of the six owners and fall through to the
  by-type bucket — "Images", the folder holding every photograph in the repository, which locates
  nothing.

**The stage is DERIVED from the workshop's own rows, not read off the file, and it cannot be
otherwise.** `MediaFile` carries the workshop twice (the tag pair, and `designWorkshopId` for a
miscellaneous upload) and carries the stage **nowhere**: a media field stores its ids inside
`DwStageEntry.data`, so the only record that a given file answers stage 11's "Sketch photographs" is
the stage row itself. Adding a stage column to `MediaFile` was rejected — it would be a second copy
of that fact, written by the upload path, able to disagree with the stage row the moment a designer
moves a photograph between fields, which is the shape `MediaFile.designWorkshopId`'s own note in
`schema.prisma` warns about.

**One thing was deliberately NOT tightened for everybody.** Moving these files out of "Miscellaneous"
is right for a reader who has the design-workshop branch to find them in, and wrong for one who does
not — they would simply have lost files that were listed yesterday, with nothing on screen to say
why. So `_user_type_where` returns the pre-2026-08-31 `misc` clause to an account without the
capability. **Nobody sees less than before**; the accounts entitled to the design-workshop surface
see the same files better filed.

---

## 6. Search: a sixth bucket, and what it does NOT search

`GET /search` gains `designWorkshops`. The map keeps five: `GET /map/points` groups by
`locationId`/`place`, and `DesignWorkshop` has neither column — its geography is
`state`/`district`/`venue`, three free-text strings promoted out of stage 1 — so adding it to the
shared `RECORD_TYPES` would hand Prisma a `group_by` over a model with no such fields, which is a
500 rather than an empty bucket. `SEARCH_TYPES` is therefore **derived** from `RECORD_TYPES` rather
than restated beside it, and `resolve_types` grew an `allowed` parameter defaulting to the narrower
vocabulary.

`resolve_types` also stopped lower-casing the token and now **folds case for the comparison and
returns the vocabulary's own spelling**. That was canonicalisation while every bucket name was
lower-case; `designWorkshops` is the first that is not, and lowering it would have rejected the one
spelling the API itself publishes. (It also keeps Android working: `WorkshopRepository.search`
lower-cases `types` before sending them.)

### 6.1 Stage field VALUES are not searchable, and that is said on the wire

> **STATUS, 2026-08-31: CLOSED. Option 2 was built.** `DwStageEntry.searchText` exists, `save_stage`
> and `seed_designer_prefill` maintain it, `backend/scripts/backfill_stage_search_text.py` fills the
> rows that predate it, and the `designWorkshops` bucket matches the workshop's own columns **OR** a
> stage answer — naming the stage it matched in, so a hit can be acted on. The sentence this section
> ends by praising has been retired, because a sentence describing a limit the server no longer has
> tells a researcher not to trust an answer that is now correct.
>
> **The argument below is left exactly as it was written, including the part it lost.** This is a
> decision record: what changes is the status line, never the reasoning. Three things are worth
> knowing about how the decision came out, and none of them contradicts what follows.
>
> * **Option 2 was chosen for a reason §6.1 did not weigh.** Below, the two options differ mainly in
>   cost. The deciding argument turned out to be RECALL: a trigram index over `data::text` indexes
>   the stored TOKEN, so a designer who picked "Design & Prototype Development" from a dropdown has
>   `DESIGN_PROTOTYPE_DEVELOPMENT` in the row and the one string the product showed them finds
>   nothing. A maintained column can hold the RENDERED answers — this repository already had the
>   renderer — so it is a better search and not merely a cheaper one.
> * **The objection below — "a second copy … which can disagree with `data` the moment a write path
>   forgets it" — is answered structurally, not by care.** `DwStageEntry.data` has exactly two
>   writers, both write the column in the same statement, and
>   `tests/test_design_workshop_search_text.py::test_no_third_writer_of_a_stage_entry_has_appeared`
>   parses `backend/app` and fails on a third.
> * **Option 1 is not dead; it is now cheaper and better.** The day a deployment permits
>   `CREATE EXTENSION pg_trgm`, one `CREATE INDEX … gin_trgm_ops` over `searchText` indexes RENDERED
>   text rather than raw JSON. The two options were never really alternatives — one is the
>   foundation of the other. Until then there is deliberately **no index**: the match is
>   `ILIKE '%term%'`, which no btree can answer, and an index that could not be used would be
>   decoration the next reader trusts. The migration
>   (`20260831120000_dw_stage_entry_search_text`) carries that measurement.
>
> One limit remains and is still said on the wire: the column carries TEXT answers. Numbers, dates,
> coordinates, attachments, and — for a sharper reason — contact details and identity numbers are
> out of it. See §6.4.

No call site in this repository applies a text filter to `DwStageEntry.data` — reads are by workshop
id, by stage key and by entity key, and not one `contains` over `data`. So a search for "indigo"
finds a workshop whose **title** or **craft name** says indigo, and does not find the workshop whose
stage 5 dye-bath answer says it.

**This was not fixed here, and the cost of fixing it is written down rather than left as a
to-do.** Two ways exist and both are migrations:

1. **A `pg_trgm` index over `data::text`.** Needs the extension enabled on the deployment (a
   superuser step the `psql`-piping migration path can do, but which the managed instance would have
   to allow) and a GIN index roughly the size of the largest JSON column in the schema.
2. **A generated `searchText` column maintained by `save_stage`.** An ordinary index, but a
   migration, a backfill over every existing row, and a second copy of every answer — which can
   disagree with `data` the moment a write path forgets it.

Either is a decision about the schema, and neither is worth doing before somebody has asked to
search inside stage answers. What matters is that the limit is **stated**: the route returns
`designWorkshopSearchScope` — *"Matched on the workshop's own columns. Answers inside the 22 stages
are not searched."* — and the page prints it under the bucket's heading. An empty result must never
be readable as "no workshop recorded that".

### 6.2 What the shared filters do to the new bucket

- **`craftId` / `artisanId`** reach through `artisansLinked` / `productsLinked` / `toolsLinked`. A
  design workshop carries neither column: `craftName` is a promoted **string**, so testing a cuid
  against it would match nothing at all — a filter that silently empties a bucket.
- **`mediaType`** does **not** narrow it, matching the other four record buckets: in
  `build_record_wheres` that filter writes `media_where` alone.
- **`place`** matches `state`/`district`/`venue`/`clusterName`.
- **The date range** reads `startDate` and falls back to `createdAt` — the same rule the legacy
  workshop bucket applies with its own fallback column.
- **`workshopIds`** takes the ordinary column reading, correctly: a `DesignWorkshop` is not a
  `Workshop`, it is a different table carrying a nullable foreign key to one.
- **`deletedAt: null`** is unconditional and first. Nothing in this product hard-deletes a design
  workshop, and a search box that returned one would be the single surface that resurrects deleted
  work — to the widest audience.

### 6.3 A refusal that says so, not a zero

A caller who asks for `types=designWorkshops` without the capability gets the bucket **dropped** and
**named** in `typesRefused`. It is not a 422, because `designWorkshops` is a real member of the
vocabulary and a 422 naming a valid token teaches a client its spelling is wrong. It is not a silent
`0`, because a bucket that comes back empty is indistinguishable from a repository with nothing in
it — the wrong-answer-dressed-as-right failure that `resolve_types` refuses a typo over.

### 6.4 What the stage index deliberately does NOT carry

**Added 2026-08-31 with the column, because an exclusion nobody wrote down is indistinguishable from
a bug.** `design_workshop_data.SEARCHABLE_FIELD_TYPES` admits TEXT, LONG_TEXT, RICH_TEXT, ENUM,
MULTI_ENUM, TAGS and URL, and nothing else. Each omission is a rule:

| left out | why |
|---|---|
| media ids, REF ids | the stored value is a cuid, or a `dwlocal:` blob on somebody's phone. Nobody types a cuid, and the NAMES behind a REF are not lost — `hydrate_entries` copies them onto sibling text fields of the same row, and those are indexed. |
| GEO, BOOL | a coordinate pair; and "Yes"/"No", which would make every workshop in the repository a match for the word "no". |
| INT, DECIMAL, MONEY, PERCENT, DATE, TIME | the RENDERED form is not what a person types — `format_value` prints 6500 as "₹ 6,500.00" and 2026-02-10 as "10 Feb 2026". A column carrying them would look like it covered numbers and dates while failing the two obvious queries. Half-searching is worse than saying it is not searched; matching them properly is a range filter over a typed column, which is a different feature. |
| **PHONE, EMAIL, `aadhaarNumber`, `artisanCardNo`** | **a ruling made elsewhere depends on it.** See below. |

**The last row is the one that would be wrong to relax.** The banner above
`access.REVISION_REDACTED_FIELDS` accepts that clearing `Artisan.phone` leaves the number in every
stage row that referenced her — including the masked last four of both identity numbers — on the
stated ground that *"a `DwStageEntry` is not indexed by identity number, is not what an admin opens
when tracing a duplicate … It is a RESIDUE, not a ledger."* That sentence is the entire basis on
which the residue is tolerated. A searchable copy of those values would falsify it in one commit:
the residue becomes exactly the identity index the comment says does not exist, and every professor
gains a reverse lookup — "which workshops is this person in" — that no surface in this product
offers. The two identity keys are excluded **by key name** because both are declared TEXT and no
type rule can see them; `artisanCardNo` is not in `records._IDENTITY_KEYS` and is named here as well,
which is the same reason `_redact_sensitive`'s by-name walk does not reach it either.

Pinned by `test_a_participants_contact_details_are_never_searchable`,
`test_every_identity_key_records_knows_is_excluded_here` and
`test_no_contact_typed_field_anywhere_in_the_registry_is_searchable`.


---

## 7. The three filters the web was missing, and the scope Android was missing

Two parity gaps, both closed:

- **`craftId`, `artisanId` and `mediaType`** have been on `GET /search` all along and Android has
  had real pickers for all three; the web sent none of them. They now live in the shared
  `SearchFilters` value (so `searchFilterParams` sends them and the map could use them), while the
  **controls** are rendered through an `extraFilters` slot on `SearchFilterBar` — Android's shape
  exactly, and for its stated reason: the pickers need the craft and artisan **lists**, and a shared
  bar that fetched them would make the map and the View Data panel pay two requests for controls
  they do not draw. The sentence under them is Android's, verbatim: *"Craft, artisan and media type
  narrow only the buckets that carry them."*
- **`workshopIds` occurred zero times in `SearchScreen.kt`** though `WorkshopRepository.search` has
  taken it all along and every other cross-workshop screen on the handset offers it. A researcher
  could scope the map to last week's workshop, tap through to a record, come here to look for a
  second one, and be handed the whole repository with nothing on screen to say the scope had been
  dropped. `WorkshopScopeSelect` is now on the screen with `defaultToMostRecent = false`, matching
  the web's `/search` and for its stated reason: this is the general way *in* to the corpus and its
  default has always been "everything".

---

## 8. The archive filename

`design-workshop-dataset.zip` was **renamed to `repository-dataset.zip`**, and the note printed
after the download names where the design-workshop data is instead.

Making the name true was the other option and it is not available from this lane:
`backend/app/api/routes/export.py` builds that archive and is owned elsewhere. What is available is
the honest name plus a place to go — the "By design workshop" folder in the tree, and the
design-workshop tabs of the Data tables report beside it. A reader who wanted the workshops is told
where they are, rather than left to conclude there are none.

**Leaving it was not an option.** A filename is the one piece of metadata that survives every other
context, and this one asserted something false about a research corpus.

---

## How this document is kept true

**This is a decision record, so the argument in it is frozen and is not rewritten to agree with later
code.** What has to stay true is the *status line*: which rule is actually in force. That lives in two
constants and nowhere else — `DESIGN_WORKSHOP_DATA_VIEW_ROLES` and
`DESIGN_WORKSHOP_DATA_EXPORT_ROLES` in `backend/app/core/deps.py`. If they and this document
disagree, **the code is right and this file is stale**: fix the table in §2, and leave the argument
under it exactly as it was, including whichever case it lost.

| Claim | How to check |
|---|---|
| Professor, Admin and Master Admin may VIEW; Admin and Master Admin may EXPORT | the two frozensets in `deps.py`. Pinned per tier by `test_the_view_set_is_exactly_the_three_roles_the_owner_named` and `test_the_export_set_is_admin_and_master_admin` in `backend/tests/test_design_workshop_data_access.py`, and mirrored on the web by `frontend/e2e/design-workshop-search-filters-unit.spec.ts` |
| Exporting is STRICTLY narrower than viewing | `test_exporting_is_strictly_narrower_than_viewing` — a change that made the two sets equal would pass every other assertion in that file |
| A granted researcher reaches `/data` and never a design workshop | `test_a_granted_researcher_reaches_data_and_not_design_workshops`. The grant is `canDownloadDataset`; the capability is rank |
| The designer set is untouched | `test_the_new_capability_does_not_widen_the_designer_set`, plus the whole of `backend/tests/test_design_workshop_gate.py` |
| The web mirrors both sets | `test_the_web_declares_the_same_view_set` / `..._export_set`, which read `frontend/lib/permissions.ts` off disk |
| Nobody sees fewer media files than before | `test_misc_keeps_its_old_reading_for_an_account_without_the_capability` |
| The map still has five buckets and search six | `test_the_map_vocabulary_is_still_five_and_search_is_six`, and the web twin `the map keeps five buckets and search gets six` |
| The data browser attributes no stage field to anybody (the exemption in §2's neighbour) | "Reader 9" in `backend/tests/test_entry_provenance_readers.py` — two tests that fail the day this surface prints an author |
| The tab cap cannot hide a table | `test_the_sheet_plan_names_every_table_it_could_not_show`, and `dw.sheet_plan`'s own argument |
| A stage answer is searchable, and the hit says WHICH stage | `test_a_stage_answer_is_findable_from_the_search_box` / `test_a_hit_on_a_stage_answer_NAMES_the_stage` in `backend/tests/test_stage_search_text.py` |
| `searchText` cannot drift from `data` | `test_no_third_writer_of_a_stage_entry_has_appeared` parses `backend/app` and fails on a third writer; `test_re_saving_a_row_REPLACES_the_column_rather_than_leaving_the_old_words` catches the stale copy |
| A contact detail or identity number is never searchable (§6.4) | `test_a_participants_contact_details_are_never_searchable`, `test_every_identity_key_records_knows_is_excluded_here`, `test_no_contact_typed_field_anywhere_in_the_registry_is_searchable` |
| The backfill is genuinely idempotent | `test_the_backfill_computes_exactly_what_the_writer_wrote` — if the script and `save_stage` disagreed by a separator, every run would rewrite the whole table and report it as touched |
| The retired sentence stays retired | `test_the_scope_sentence_no_longer_claims_the_stages_are_unsearched` |

**Review triggers:** any change to either frozenset; a tier added to `ROLE_RANK` (the two sets are
SETS, so the ladder gives no answer for a new tier and somebody has to decide); ~~a text index over
`DwStageEntry.data` landing, which retires §6.1 and the sentence the route prints~~ — **fired
2026-08-31: `DwStageEntry.searchText` landed, §6.1 carries its status line and the sentence has been
rewritten**; a `pg_trgm` index arriving over that column, which is §6.1's option 1 and changes only
the cost, never the answer; a field type or field key entering `SEARCHABLE_FIELD_TYPES` /
`UNSEARCHABLE_FIELD_KEYS`, which changes what the route's own sentence has to say and, for the
second list, what `access.py` is entitled to keep claiming about the residue; the data browser
gaining an author column, which retires the exemption in §5's neighbour and makes it a reader that
owes `entry_provenance`; and `backend/app/api/routes/export.py` gaining design-workshop content,
which retires §8 and lets the archive be named after them again.
