# Where the Designer app stands

Written at the end of the session of 2026-08-07, for you to read before picking the work back up.
Everything below is what I verified myself, not what an agent claimed. Where I could not verify
something, it says so.

## The short version

The rich-text feature is now actually reachable by a designer, the report download works again,
and every stage-20 setting the form offers is honoured by the pipeline. Tables, dictation,
one-per-line lists and a typeface picker all landed and are covered by tests. What remains is
inline media inside a prose field, and a real on-hardware test of the Android offline loop.

Verified at the end of this session: **backend 1101 passed, 2 skipped** · frontend typecheck and
lint clean · **Android compiles with 0 `e:` lines** · 6 Playwright specs passing against the local
stack.

## The two bugs that mattered

**The report could not be downloaded at all, and the app blamed the network.**
`render_report` rebuilt its metadata with `ReportMeta(**{**meta.__dict__, ...})`, but `ReportMeta`
is `@dataclass(frozen=True, slots=True)` and **a slotted instance has no `__dict__`**. Every one of
those three lines raised `AttributeError` the moment the request carried the field it guarded — and
the web report page sends `pageSize`, `headerText` and `footerText` as soon as stage 20 has them
saved. So the designers who had filled the settings in most carefully were the only ones who could
never produce a file.

It reached a person as a lie, which is the part worth remembering. `isTransient` in the web client
counts any 5xx as "probably the network", so the screen said the DOCX *"cannot be generated without
a connection"* while the server was up, answering, and failing on that line. Fixed with
`dataclasses.replace`; the message now splits on whether the server actually spoke. Nothing caught
it because **no test called `render_report` at all** — there are three now, plus
`frontend/e2e/report-download.spec.ts`, which requires a real file to arrive.

**The API container would not start on Windows.** `docker/backend/entrypoint.sh` had CRLF line
endings, so its shebang read `#!/bin/sh\r`; Linux looked for an interpreter of that literal name and
reported `exec /usr/local/bin/entrypoint.sh: no such file or directory` — a message that names the
SCRIPT while the thing actually missing is the INTERPRETER. Fixed in three places so it cannot come
back: the file, a new `.gitattributes` pinning `*.sh` to `eol=lf`, and a `sed` in `backend/Dockerfile`
that strips CRs at build time. `infra/terraform/user_data.sh` had the same latent bug.

## Done this session

**The rich-text editor is reachable.** The whole stack was built and correct — the model, the
2000-line web editor, the Kotlin port, both renderers — but the registry declared **zero**
`RICH_TEXT` fields, so `FieldInput`'s `case "RICH_TEXT"` was unreachable and every narrative field
rendered as a plain textarea. 98 fields are now `RICH_TEXT`: the 81 `NARRATIVE` ones (which have a
first-class rich path through `to_report_blocks`) and the 17 `BULLETS` ones. `TABLE_COLUMN` and
`KEY_VALUE` prose fields were deliberately left as `LONG_TEXT` — a cell holds runs and loses
paragraph breaks, so promoting them would cost structure and buy nothing.

**Every stage-20 setting is now read.** `includePhotographs`, `numberHeadings`,
`includeTableOfContents`, `photoColumns`, `includeMediaAnnexure`, `includeCompletenessAnnexure` and
`excludedStages` were stored and ignored; so were `reportTitle`, `reportSubtitle`,
`organisationLine`, `headerText`, `footerText` and `pageSize`. `report_templates.apply_report_settings`
and an extended `report_meta` close all of it, with the precedence the pipeline already used
(request wins, then stage 20, then the template untouched). **The preview applies them too** — it
previously built the bare template, so a designer approved one document and submitted another.

**Tables in prose**, end to end: `BlockKind.TABLE` in `rich_text.py` and `RichText.kt`, rows of
cells of spans, mapped onto the report model's existing `TableBlock` so no renderer needed a new
drawing path. Insertable from the web toolbar, Tab/Enter move between cells.

**Dictation on rich fields** (web + Android), inserting into the document model at the caret through
the same `insertText` a keystroke uses — so it cannot flatten existing formatting, which is why
`FieldInput` had deliberately refused to offer it.

**One-per-line fields open as numbered lists.** The `BULLETS` fields whose help says "One deliverable
per line" now start inside a list item, so Enter makes item 2.

**A typeface picker** (`fontPreset`, 8 families). See the honest limit below.

**Two more latent bugs found on the way.** Seven `doc.heading(...)` call sites did not pass
`numbered=`, which defaults to `True` — so with numbering off, sub-headings still incremented and
printed as `0.1`. And the bundled Android schema asset was stale by 8 fields added the night before.

## Things you should know

**A typeface reaches the .docx and NOT the .pdf, on purpose.** `report_docx` writes the family into
`w:rFonts` and Word resolves it. `report_pdf` must embed a face that can actually draw Odia,
Devanagari and the rupee sign, and chooses it by probing the filesystem — a server without Georgia
installed cannot embed Georgia. Rather than substitute in silence, the generator raises a WARNING
naming the mismatch. If you want the PDF to honour it, that is a change to `report_pdf._candidates`
and it must not lose the Indic coverage.

**A table is the one block this editor does not edit through the model.** A `DocPoint` is
`{block, offset}` and cannot address a CELL, so cell editing is left to the browser and read back by
`readTableElement` on the next `input`. Enter and Tab are still intercepted, to move between cells
rather than let an engine nest a `<div>` inside a `<td>`. Extending the selection model is the
proper fix if tables ever need model-level undo granularity inside a cell.

**Promoting a field to RICH_TEXT changes the stored SHAPE.** `coerce_value` reads a plain string as
unformatted prose, so no data is lost — but the stored value becomes `{"blocks": […]}`, and three
tests that compared it to a raw string had to read through `to_plain`. Expect that if you promote
more fields.

**`tests/test_report_parity.py` now guards the rich-text port too** — block kinds, marks and bounds.
That gap mattered more than the report-block one: an unknown kind degrades to PARAGRAPH and the
parser reads only `spans`, so a phone that did not know `TABLE` would open a field, find nothing,
and write the grid away. The guard was checked against a doctored source to prove it is not vacuous.

**Still no JVM test source set on Android.** The Kotlin work is verified by compiling and by the
parity tests reading the source as text. That remains the main testing gap on that surface.

## Added after the first pass of this note

**The report map was drawing a lie of omission, and the fix is now general.** The map rendered all
along — it is in the preview as live SVG and in the .docx as an embedded PNG — but `place_atlas`
held THIRTEEN places across seven states, the clusters the baseline corpus happened to name. Every
artisan of a workshop outside them failed to resolve and folded onto the state capital, so the
figure asserted that a Bargarh cluster came from Bhubaneswar 300 km away. It rendered cleanly,
which is why it survived.

Curating another state would have moved the same failure to the next one. `address.py` already
names all **795 districts** of India (LGD-sourced, versioned, aliased) and `geography.DistrictAnchors`
already positions them from every pinned `Location` the repository holds — `/map` has used it for
years. The report simply never received them. `WorkshopData.district_points` now carries them,
loaded by `attach_district_anchors` exactly as `references` is, and `_geocode` falls through
**curated town -> district anchor -> state seat**, matching the district inside a longer address.
Districts in Nagaland, Manipur, Kerala, Tamil Nadu, Bihar and Assam — none curated — resolve
precisely; `tests/test_report_map_districts.py` pins it and warns that making it pass by curating
those states defeats it.

A district still needs SOMETHING pinned in it to get an anchor. `scripts/build_boundaries.py`
already joins 752 of the 795 from a published source, so emitting a district-centroid table from
that join would give every district a cold position; it needs a `--fetch`.

**Workshop kind.** `WorkshopType` (`DESIGN_PROTOTYPE` / `OTHER`, defaulting to OTHER) on
`Workshop`, migration `20260807180000_workshop_type`. A dropdown at the top of the workshop form
sets it; `GET /workshops?workshopType=` filters on it; and the design-workshop create form offers
those workshops as a source, filling blank cover fields and linking `workshopId`. It fills only
BLANK boxes — a designer who has already typed a cluster name keeps it, the same rule stage-1
reference hydration follows. Android's DTOs carry the field; its FORM still needs the dropdown.

**Also fixed:** references printed raw cuids in eleven report columns and five row labels
(`assemble_workshop_data` dropped `row.id`, so an intra-workshop ref could never resolve); the
report colour could be chosen but never saved, and the picker re-rendered every sheet on each drag
of the colour well; H1/H2 and H3/H4 were two pixels apart in the editor; tables had no row/column
controls; and derived fields (`durationDays`, `amount`) promised "leave blank to derive" while
nothing derived them.

## Left to do

- **Inline media inside a prose field** — attaching an image to a particular field so it wraps with
  the text. Not started. Needs an IMAGE block in the rich-text model, resolution through
  `MediaResolver`, and rendering in both writers plus the preview and the Kotlin port.
- **The Android offline loop has still never been run on hardware.** No device and no emulator on
  this machine (`adb` is not installed). "Create a workshop in airplane mode, photograph a
  prototype, export a .docx" is the product's central claim and is still owed a real test.
- **Ruff is not clean repo-wide** — the `B008` findings on FastAPI `Depends` defaults are the
  framework's own idiom and pre-existing; `pyproject.toml` still has no `[tool.ruff.lint]` section.


## Test accounts — and why they exist

`backend/scripts/seed_test_accounts.py` seeds one local account per role, all password
`LocalDev123!`:

    volunteer@example.org   contributor@example.org   researcher@example.org
    designer@example.org    professor@example.org     admin2@example.org

They exist because every permission here is a PAIR — a predicate in `app/core/deps.py` and its
mirror in `frontend/lib/permissions.ts` — and the failure that matters is not the allow path but
the REFUSE path. A UI guard over an open endpoint hides the link and leaves the URL, the API and
the Android client wide open. That shipped TWICE this season, and both times the reason it survived
was that the only local credential was the master admin, so nobody could open the app AS a
researcher and find the page still there.

The observed matrix, which is the first time these were checked rather than reasoned about:

| role | POST /design-workshops | /designers/me/profile | /questionnaires | /media/jobs |
|---|---|---|---|---|
| volunteer / contributor / researcher | 403 | 403 | 403 | 200 |
| **designer** | 201 | 200 | 200 | 200 |
| **professor** | **403** | **403** | **403** | 200 |
| admin | 201 | 200 | 200 | 200 |

Professor refused is deliberate — `can_run_design_workshops` is the one capability in `deps.py`
that is a SET rather than a rank threshold. `/media/jobs` open to everyone is also correct: it is
owner-scoped server-side.

The script writes the designer's `DesignerRoster` row as well, because `User.role = DESIGNER` is
NOT what admits a designer — without the roster row the sign-in is refused with "your access has
been suspended", which is the most confusing possible test account. It uses `@example.org` and not
`@test.local`: pydantic's EmailStr runs `email-validator`, which refuses `.local` as a non-public
TLD, and the first version of the script produced six accounts that were created successfully and
could never sign in, failing with a 422 on the EMAIL field rather than anything about a password.

## Two gaps an audit found in this session's own work

Both were the same shape, and neither was caught by the suite:

- **`can_run_design_workshops` gated nothing.** The predicate was changed to a set and the PAGES
  were gated, but the design-workshop write routes still used `assert_can_create_records`
  (Researcher and above). A RESEARCHER or PROFESSOR could create a workshop, write all 22 stages
  and generate the ministry report while the web showed them no way in. Now enforced on create,
  update and stage-save; `tests/test_design_workshop_gate.py` covers it, including a source check so
  a sixth write route cannot forget the line. The suite going 1213 -> 1222 with no failure when the
  gate was added is the proof nothing had been testing it.
- **A stale docstring** on `get_my_profile` still read "Any role, deliberately" after the route was
  narrowed — the code was doing exactly what its own comment called the failure.

## Environment

- Local stack: `docker compose --profile api up -d` gives Postgres (**:55442**), MinIO (**:9010**,
  console **:9011**) and the API (**:8000**). The web app runs from `frontend/` with `npm run dev`
  on **:3000**.
- Local sign-in: `admin@example.com` / `LocalDev123!` (MASTER_ADMIN). The master admin
  `ankits1802@gmail.com` was left untouched.
- A fully populated workshop — 22 stages, 270 rows, Sambalpuri Bandha at Barpali — is seeded at
  `/design-workshops/cmsik2jg8000eh8xc1lcy661a`. It has no media, so the report's completeness
  warnings about missing photographs are expected.
- The Playwright specs need the stack up: `E2E_EMAIL`, `E2E_PASSWORD`, and `E2E_WORKSHOP_ID` for
  `report-download.spec.ts`.
- The Kotlin compile daemon still fails under memory pressure and prints `BUILD SUCCESSFUL` having
  compiled nothing. Always use
  `./gradlew.bat --no-daemon -Pkotlin.compiler.execution.strategy=in-process :app:compileDebugKotlin`
  and check for `e:` lines regardless of the last line.
- **Regenerate the bundled Android registry whenever the server registry changes** — the command is
  in the header of `data/StageSchema.kt`. It was stale by 8 fields when this session started.

## The 7-lens defect hunt, and what verifying it actually cost

54 raw findings across seven lenses; 44 of medium-or-above were each handed to an independent
skeptic told to REFUTE them. 42 survived, 2 were refuted. 29 were fixed by the three surface
fixers, and the residue is listed at the foot of this section.

**The two most valuable findings were introduced by the fixes themselves**, which is the argument
for verifying a hunt rather than filing its report:

1. **The sweep fix broke deletion.** Narrowing `replaceCollections` to `touched | emptiedEntities`
   correctly stopped a partial payload from wiping collections it never named — but the web had no
   way to SEND `emptiedEntities`, and an emptied collection contributes no entries, so it names
   itself nowhere. Deleting the last row of a collection silently stopped reaching the server.
   Fixed at both web call sites (`stages/[stageKey]/page.tsx`, `designWorkshopStore.ts`) by sending
   `removedFrom`, which the draft store already tracked.

   Proven live, before and after an image rebuild:

   | payload | stale image | rebuilt |
   |---|---|---|
   | names only `buyerLink` | `removed=2`, costSheet **wiped** | `removed=1`, costSheet survives |
   | + `emptiedEntities` | — | `removed=2`, costSheet deleted, buyerLink kept |

2. **The font fix was insufficient as first written.** `_complex_candidates()` was ONE list whose
   first hit won for every non-Latin run, so on Debian `NotoSansDevanagari` bound before
   `NotoSansOriya` and Odia would still have printed as boxes. Binding is now per-script. Verified
   in the running image: ODIA → `NotoSansOriya`, `Script.OTHER` (the ✓/✗ stage 12 asks for) →
   `DejaVuSans`, `missing_glyphs: []`, `missing_scripts: []`.

**THE RUNNING CONTAINER IS NOT THE CODE.** The first live reproduction of the sweep fix FAILED, and
the fix was correct — the image was four hours stale. `docker compose build api` before believing
any backend result. This cost a full misdiagnosis cycle.

### PDF contents: fixed, with a residual

The reported bug was every contents page number wrong by 9–10 pages. On the flagship's 160-page
PDF, **42 entries now match exactly**. A residual **off-by-one remains on ten deeply nested
sub-headings** (`14.x.2. Annotations`) that fall near a page boundary — the contents says 85 where
the heading is on 86. Not diagnosed further. Note for whoever picks it up: the contents runs pages
2–11 and each entry is laid out over TWO lines (title, then a bare page number, then dots), so a
one-line `title .... page` regex silently matches nothing and reports success.

### Still open

- **`EntitySpec.parent` is still unread by any renderer.** Child collections print as flat tables
  with no link to their parent, so a reader cannot tell which material lines belong to which cost
  sheet. Two agents have now declined it as too risky for the budget they had; it rewrites
  `_render_stage`, the path every stage of every report goes through. It is genuinely worth doing
  and genuinely worth doing carefully. Nothing is half-changed.
- `scripts/build_boundaries.py` still does not write `backend/app/data/boundaries/`, the copy that
  lands in the image. `tests/test_report_map_assets.py` fails loudly if they diverge, so the trap is
  fenced but not removed.
- Android: `report_templates` + `apply_report_settings` have no Kotlin port, so the on-device report
  ignores the chosen template and 21 of the 22 stage-20 settings.

### Test-data residue — deliberately NOT cleaned

The local database holds roughly 1,900 workshops and 430 users of test residue. Deleting it is
irreversible and is the repository owner's call, so nothing was removed.

## Two environment traps that cost real time

**`docker compose` MUST be run from the repository root.** Run from `backend/`, it finds
`backend/.env` and injects that file's `DATABASE_URL` — which points at the HOST port
(`127.0.0.1:55442`, correct for pytest, wrong inside a container) — and the API comes up
`unhealthy`, retrying a database it cannot reach. Compose walks up to find `docker-compose.yml`
but reads `.env` from the working directory, so the wrong file wins silently. This bit twice in
one session. Check with:

    docker inspect design-workshop-api --format '{{range .Config.Env}}{{println .}}{{end}}' | grep DATABASE_URL
    # want: @postgres:5432   not: @127.0.0.1:55442

**Never name a scratch script after a stdlib module.** An agent left `inspect.py` in the scratch
directory; every Python process launched from there imported it instead of `inspect`, so
`@dataclass(slots=True)` died with `AttributeError: module 'inspect' has no attribute 'unwrap'`
and unrelated report-block debug output appeared in the middle of other commands' stdout. Renamed.

## Stage 9's Advanced tier is now computed, not asserted

`backend/app/services/market_analysis.py` (+47 tests). Pure arithmetic over the rows stages 8 and
9 already hold — no database, no network, no model — which is what lets `analyse()` run unchanged
in a report render, in the browser and on the handset. Exposed at
`GET /design-workshops/{id}/market-analysis`, which exists only for the report renderer and for a
device that has not synced stage 8; the clients are meant to compute it locally.

What it produces, verified against the flagship workshop:

- Price distributions, respondents and competitors kept apart (they answer different questions).
- A verdict on every declared price band. The flagship's four bands come back SOUND, SOUND, SOUND
  and **LOW** — "the band ₹600–1,500 sits below the evidence for ACCESSORY: 5 of 11 observations
  are above it, median ₹1,500."
- Every competitor placed against what buyers said they would pay.
- SWOT points with no supporting response, flagged.
- Products discussed together, as co-occurrence clusters.

**Three refusals are as load-bearing as the arithmetic** and are pinned by test: quantiles are
withheld below 5 observations; a band gets `UNVERIFIABLE` rather than a criticism below 8; and a
sample dominated by one respondent group says so before it shows a figure. A tool that produces
confident numbers from three data points is worse than no tool, because the confidence is what
reaches the ministry.

### Two bugs the work found in itself

1. **The tokeniser was broken for every Indic script**, which is most of the corpus. `[^\W\d_]+`
   looks Unicode-correct; Python's `\w` follows `str.isalnum()`, which is False for combining
   marks, so the virama in ରଙ୍ଗ and the matra in ଦାମ are word BOUNDARIES. Odia words shattered into
   fragments the length filter then discarded, making "unsupported by the survey" the automatic
   verdict for exactly the fieldwork this app exists to collect. Now a scan over categories L and M
   — the ports write it as `[\p{L}\p{M}]+`.
2. **Competitor positioning said "too few buyer price expectations" for all five competitors** on a
   40-response survey. Respondent price expectations carry no category (stage 8 asks what they
   would pay, not what for), so a strict category lookup found nothing — while `judge_band` two
   functions above already pooled them. It now falls back to the pooled sample and DECLARES that it
   did, because "against all buyers asked" is a weaker claim than "against buyers asked about this
   category".

### `registry_version()` was blind to derivations

The bundled Android asset carried 2 derived fields where the registry had 5 — missing exactly the
three cost-sheet ones — and its version string matched the live registry CHARACTER FOR CHARACTER,
because the digest covered key/type/tier/required/enum/deprecated and stopped there. So the
staleness check that exists to catch this reported agreement, and on a handset those totals simply
never computed. The digest now covers `derived_kind`/`derived_from`
(`test_the_version_changes_when_a_derivation_changes`), and the asset is regenerated.

The regeneration command in `StageSchema.kt`'s header was ALSO broken on Windows: it redirected
stdout, which is cp1252, and the registry contains ✓ — so it died with UnicodeEncodeError having
already truncated the file. It now writes the file with an explicit encoding.

## A product question the work surfaced: a design workshop is visible ONLY to whoever created it

`design_workshops.load_workshop_or_404` refuses any workshop where `createdById != user.id` unless
the caller is an admin, answering 404 rather than 403 so a cuid is never confirmed. That part is
deliberate and well reasoned.

The consequence may not be. A real design workshop has a designer, often a second designer, a
master craftsperson and a reviewing officer — and stage 1 captures `designerName` as free text
while access is decided solely by who pressed "create". Today:

- a second designer cannot open a colleague's workshop at all, on any device;
- the seeded `designer@example.org` cannot see the flagship workshop, because a different account
  created it — which is why the market-findings spec runs as `admin2@example.org`;
- there is no way to hand a workshop over when a designer leaves mid-season, short of an admin.

Nothing was changed. Widening this is a permission decision (a per-workshop collaborator list, or
visibility to the linked `Workshop`'s team) and it belongs to the repository owner, not to a patch.

## Market analysis: computed on the edge, and PROVEN equal to the server

`frontend/lib/marketAnalysis.ts` is a port of `market_analysis.py`, and the parity is measured, not
asserted: **76 case rows through both implementations, 0 differing values**, compared structurally
and type-aware (`true` is not interchangeable with `1`). Four traps the port had to handle, each
commented where it is handled:

| Trap | Why the obvious code is wrong |
|---|---|
| `pyRound` | Python rounds half to EVEN; JS `toFixed` rounds half away. They differ on every exact half — `62%` vs `63%`, `₹624` vs `₹625`. |
| Number grammar | `Number()` accepts `"0x1A"` as 26 and `""` as 0; Python's `float()` accepts neither. |
| Arrays | `String([5])` is `"5"` in JS and `"[5]"` in Python. Both sides now refuse non-scalars outright. |
| `\p{M}` in the tokeniser | Without it Odia words shatter and every non-Latin response scores as having no content. |

The panel computes from the local IndexedDB draft and reaches the endpoint ONLY when stage 8 was
never downloaded — "downloaded and empty" and "never downloaded" are held apart, because conflating
them would report a forty-person survey as no evidence. Proven by a spec that ABORTS the endpoint
and asserts zero requests to it, not merely that the failure was survived.

## A grant that stopped at the workshop's edge

`DesignWorkshopViewer` admits a second designer to a workshop and to writing its stages. But a
questionnaire is scoped on `Questionnaire.ownerId` alone, so the co-designer opened the workshop,
read stage 7 telling them a survey instrument exists, and found an empty questionnaire list — the
two halves of one piece of fieldwork disagreeing about who is working on it. The colleague's
reasonable conclusion is that the form was never uploaded.

Closed in `app/api/routes/questionnaire_forms.py`: a questionnaire attached to a design workshop the
caller may see is visible to them, and so are its sittings and its `.xlsx`.

**The sittings come with it deliberately.** A sitting carries a respondent's name and answers — but
so does stage 8's `surveyResponse` collection, which a granted co-designer can already read AND
EDIT through the stage form. Withholding the questionnaire's copy of the same interview while
showing the stage's copy protects nothing and only makes the questionnaire look empty. Likewise the
workbook: letting them read the answers on the page and refusing the download of the same answers is
a distinction the data cannot support, and one they would route around by copying the page.

Three boundaries are held and each is pinned by a test:
- an UNATTACHED questionnaire stays the owner's alone — the grant reaches the workshop's fieldwork,
  not the whole of a colleague's filing cabinet;
- `mineOnly` still means MINE, because it asks about authorship, not about what may be read;
- an ungranted designer sees neither the row, nor the sittings, nor the workbook. The FORM stays
  readable by any designer, which is the documented policy and is unchanged.

`_visible_questionnaire_where` returns a fragment for `where["AND"]` rather than assigning
`where["OR"]`, because the search box already owns `OR` — the identical trap the design-workshop list
hit when grants were added there. A test searches for a term that matches and one that does not, so
a filter that silently widened the result set would fail.

Verified by reverting: the three positive tests fail, the four refusal tests correctly still pass.

## Opaque ids: the guard covered REF and stopped there

`_value` in `report_builder.py` suppressed a bare cuid for REF fields, on the stated grounds that
"a bare cuid in a ministry's table is worse than a visible gap". That argument does not depend on
the field's declared TYPE, and the guard did. Extended to TEXT, and to `_row_label_text`, which
bypassed `_value` entirely and would have printed a cuid as a card heading.

**Correcting my own claim while making it.** I first reported this as a live defect in stage 21's
"Media quality flags" table. It is not live: `mediaQualityFlag.mediaId` is the ONLY TEXT field in
the registry whose name implies an id and which carries a printing role — and **no template prints
stage 21 at all**. So the fix is defence in depth, not a repair. The test says exactly that, and
asserts both halves, so the day somebody adds stage 21 to a template it fails and points at the
guard rather than shipping cuids into a submitted document.

Only a value that is ENTIRELY an opaque id is suppressed. Free text merely containing one keeps
every character — a note reading "duplicate of cmsjb6q…" is a designer's own words, and truncating
it would be the silent drop `test_no_presentation_silently_drops_a_filled_field` forbids.

### A product question this turned up

**Two stages are printed by NO template: `REPORT_GENERATION` (20) and `DATA_QUALITY_ARCHIVE` (21).**

For stage 20 that is plainly right — its fields configure the report rather than appearing in it.
Stage 21 is less obvious. It captures archive confirmation, preservation notes and media quality
flags; the source requirements document lists it as a workshop stage with Basic-tier capture, and
the completeness annexure counts it, so a designer fills it in and none of it reaches any report.
Either it is deliberately internal QA — in which case the completeness gate arguably should say so
— or it belongs in the archival annexure of the full templates. Nothing was changed: report
contents are the repository owner's decision, not a patch's.

## Comments that lied, found by documenting the code

A documentation pass turned up the most valuable category of defect this repo produces: a comment
asserting something the code does not do. It is the same class as the geocoder auto-write, where
three separate comments stated the rule the code had drifted from — and it is worse than no comment,
because a reader checking whether a thing is handled reads "yes" and stops.

Fixed:
- `cost_integrity.py` cited `report_builder._child_groups` twice. The method is `_parent_groups`.
- `report_builder._parent_groups` cited `cost_integrity.summarise_sheets`. There is no such
  function; the guard lives in `analyse_cost_integrity`. These two comments exist SPECIFICALLY to
  keep the two joins in step — the report must not print as a product's material cost a line the
  integrity check calls unattributed — so a broken citation defeats their only purpose.
- `workshop_cost_integrity`'s docstring claimed the calculation "also runs in the browser and on the
  handset, so a designer with no signal gets the same warning." **It does not.** There is no
  TypeScript port, the Kotlin `DwCostIntegrity` is called by nothing, and no UI consumes the
  endpoint — so **no designer sees a cost-integrity finding on any surface**. The docstring now says
  that, dated, with an instruction to delete the paragraph when the ports land rather than before.

Also corrected in `docs/`: `PERMISSIONS.md` described a "six-tier ladder" and its capability matrix
had **no column for DESIGNER**, a role `ROLE_RANK` has carried at 35 throughout. The existing
`ROLE_RANK` parity checker cannot catch this — it compares the backend to the web client, and both
were right; only the prose was wrong. `DESIGN_WORKSHOP.md` §10 asserted two capabilities did not
exist that do (eight web routes under `design-workshops/`, and the Android stage GET/PUT).

### A cross-port discrepancy worth chasing

The image-quality low-contrast evidence disagrees between the ports: `ImageQuality.kt` records
58.98 at contrast 9.03, `imageQuality.ts` records 57 at 9.0 for the same measurement. Both fall the
same side of the floor of 60, so no behaviour differs today — but two ports of one algorithm
producing different numbers for one input is exactly what the market-analysis parity harness exists
to prevent, and image quality has no such harness across all three surfaces.

### Still unverified

There is **no cross-language parity test for market analysis**. The 76-case TypeScript/Python diff
was run once, by hand, during development and was not committed as a test. `ImageQualityParityTest`
and `PlaceSearchParityTest` pin their ports by value; market analysis does not, and should.

## The flagship's missing cost sheets were the sweep bug's own damage

I reported this as "the costSheet collection is empty while 50 lines cite four ids that no longer
exist". **Wrong in the way that mattered.** The four sheets were never missing — they were
soft-deleted, and every one of the 50 `costSheetRef` values resolved to a real row. Across all 15 REF
fields in the workshop, `ref_row_absent` was 0 everywhere. They did not need reconstructing; they
needed un-deleting.

The timestamps name the culprit exactly:

| when | what |
|---|---|
| 06:28:46.115 | 6 × prototype **deleted** (sweep #1, a stage-13 PUT) |
| 06:29:28.674 | 28 material + 22 labour lines **created** |
| 06:29:28.874 | 4 × costSheet + 2 × buyerLink **deleted** — same transaction |

The lines were created by the very transaction that deleted the sheets they cite, which is why the
ids were correct rather than stale. This is `replaceCollections` scoped by the STAGE SPEC instead of
by the payload — **the critical bug found and fixed at the top of this session**
(`design_workshops.py:980`, regression-tested in
`test_a_payload_that_never_named_an_entity_cannot_delete_it`). A corrected code path does not undo
its own past writes, so the damage sat in the showcase record long after the code was right.

`backend/scripts/repair_flagship_cost_orphans.py` un-deletes, and is deliberately narrow: it restores
a collection ONLY when the whole collection is soft-deleted at a SINGLE timestamp — the sweep's
signature, and not the shape of a designer removing a row. It imports `COST_HEADS`/`sum_cost_heads`
from `cost_integrity` rather than restating them, so the values it writes are computed by the code
that grades them.

**Flagship orphans: 92 → 0.** Cost integrity `sheetCount` 0 → 4, orphan lines 50 → 0, 15 of 16 checks
agreeing. The report no longer contains the string "No cost sheet recorded" or "No prototype
recorded" at all.

Three further faults it found once the sheets were readable, none of which I had spotted: `productRef`
absent on all four sheets (a required Basic field — which is why they labelled themselves "Cost sheet
1–4"); packaging/finishing/transport double-counted against material lines at identical amounts, with
each sheet's own prose already saying they must not be added again; and `marginPercent` stored on
PRICE where `compute_margin` computes on COST.

The one deliberate discrepancy left for the integrity check to find is PT-01's margin of 55.0 against
an implied 122.2 — a REAL discrepancy (margin-on-price in a margin-on-cost field, with the sheet's own
narrative stating the convention) rather than a subtotal nudged a few rupees off its lines. Typing a
knowingly wrong number into a research record to make a demo look good is not a trade worth making.

### Host memory is a real constraint on this machine

Docker Desktop's engine crashed twice during this work from host memory exhaustion — 490 MB free of
24 GB, with ~7 GB of Java from a concurrent Gradle build plus several pytest runs. The script aborted
cleanly on connect (P1001) and wrote nothing, but OTHER agents running in that window may have seen
failures that were the machine, not the code. Also: `import prisma` costs ~305 s here, so every
script run is a five-minute affair.

## Verified state at the end of this session

Measured, not assumed. Re-verify after any resumed agent lands.

| Surface | Result |
|---|---|
| Backend | **1712 passed, 2 skipped, 0 failed** |
| Frontend | `tsc --noEmit` clean |
| Android | compiles clean (0 `e:` lines); **2 of 174 unit tests failing** — `DwParentGroupTest`, `ReportTemplateDocumentTest`, both half-finished work from agents killed by a session limit and now resumed |
| API image | rebuilt from current source, healthy, `DATABASE_URL=@postgres:5432` |

Live endpoint smoke test on the rebuilt image, flagship workshop:

    market-analysis  -> 200  observations=15  bands=4
    cost-integrity   -> 200  sheets=4  warnings=1  cautions=0
    questionnaires / viewers / eligible-viewers -> 200

`cost-integrity` is the seed repair proving itself: 0 sheets / 50 orphans / 2 cautions before,
4 sheets / 0 orphans / 1 warning after — and that one warning is the PT-01 margin discrepancy left
in deliberately so the check has something real to show.

### A pattern worth naming: concurrency manufactures phantom failures

FOUR times today a failure looked like a defect and was not:
1. `test_workshop_analytics.py` — 4 failures, caught mid-write by its own agent; 49/49 once it finished.
2. The dev server reading as down — it was mid-recompile under concurrent edits.
3. A 42-minute backend run reporting **136 errors** — an agent regenerated the Prisma client
   underneath it. Re-run clean: 1712 passed.
4. Docker's engine crashing twice — host memory exhaustion (490 MB free of 24 GB), not code.

The rule this earns: **a failure observed while agents are live is a hypothesis, not a finding.**
Re-run it alone before believing it. The corollary matters more — "these N failures are pre-existing
and not mine" is the sentence a real regression hides behind, which is why the e2e suite's
environmental noise is worth eliminating rather than annotating.

### Host memory is a live constraint

~3 GB free of 24, with 7 Gradle daemons holding ~4.3 GB. Docker died twice at ~490 MB. If the stack
drops, that is the cause; `gradle --stop` between phases is the remedy. Do not run a Gradle build and
a full pytest run and a Docker rebuild at the same time on this machine.

## Response compression — the largest measured win, and it was not the database

A performance audit measured the whole database layer and found **no N+1 anywhere** (query count
flat as a list grows 1 -> 20 -> 100), every plan indexed, and 2-25 ms of DB time per request. The
database is not the bottleneck. The bottleneck was **wire bytes**: the API ignored `Accept-Encoding`
entirely, and the production nginx has no `gzip` directive covering JSON (Ubuntu's default
`gzip_types` is `text/html` alone, so JSON would not have compressed even by accident).

`SelectiveGZipMiddleware` in `app/main.py`. Measured live on the rebuilt image:

| endpoint | before | after | ratio |
|---|---|---|---|
| `/artisans?pageSize=100` | 295,027 | 13,814 | **21.4x** |
| `/media?pageSize=100` | 303,804 | 16,776 | **18.1x** |
| workshop list (100) | 55,846 | 4,400 | **12.7x** |
| report preview | 839,122 | 114,416 | **7.3x** |
| `/schema` | 117,762 | 19,947 | **5.9x** |
| workshop detail | 332,927 | 101,839 | 3.3x |

Together **1.94 MB -> 271 KB, 7.2x**. The ratio is not the point; the link is. At 40 kB/s — a
mobile connection in a village, which is the deployment this application exists for — that is
**48.6 s -> 6.8 s**.

**It is an ALLOWLIST, not a denylist.** Starlette's own `GZipMiddleware` compresses anything above
its size floor, which here means re-compressing every generated `.docx` and `.pdf` — already ZIP
containers, yielding nothing — produced by the one endpoint measured as ALREADY CPU-bound
(`report/preview`: ~780 ms in the builder against ~12 ms of query time). Spending more CPU there to
save no bytes is the worst available trade. Adding a type to `_COMPRESSIBLE_TYPES` is a deliberate
act; forgetting one costs only bytes.

Added LAST so Starlette runs it OUTERMOST — the only position that works, because it must see the
finished body and be the last thing to touch `content-length`. Inside CORS it would compress a body
whose length header was then overwritten, and the client would hang waiting for bytes that never
arrive.

Eight tests, mostly about restraint rather than compression: the allowlist leaves a `.docx` alone,
a sub-1 KiB body is untouched (the gzip header alone is 18 bytes), an already-encoded body is never
encoded twice, 204/304 are untouched, and `Vary: Accept-Encoding` is APPENDED so CORS's
`Vary: Origin` survives — overwriting it would let a shared cache serve one origin's response to
another, a correctness bug far worse than the bytes saved.

**Backend after: 1724 passed, 2 skipped, 0 failed.**

### The audit's other findings, deliberately not acted on

- **`/artisans` and `/media` are >54% `null` by weight** — the routes return raw Prisma rows whose
  embedded `User` carries 40 unloaded relation placeholders out of 53 keys (~1,400 bytes/row).
  `passwordHash` IS correctly stripped; this is bloat, not a leak. `/questionnaires` projects fields
  explicitly and costs 370 bytes/row against `/artisans`'s 2,950. Changing the wire shape is a
  correctness question across three clients, not a speed one — and gzip now makes the bloat nearly
  free, which is exactly why `/artisans` compresses 21.4x.
- **`/schema` has no `ETag`/`Cache-Control`** despite its docstring's "cached by `version`"
  contract, so 118 KB crossed the wire on every cold start (now 20 KB). It issues zero queries; an
  ETag would make it a 304. Cheapest remaining win.
- **`notes` in the list payload must NOT be dropped naively.** `workshop_summary` serves list AND
  detail from one function. Android reads it nowhere, nothing renders it — BUT the web offline store
  consumes it off list rows and writes it back on offline PATCH, so dropping it would blank the
  local cached copy. Needs `DwSummary`/`DwDetail` split.
- One index added: `DwStageEntry.entityKey` (Seq Scan 312 buffers/4.3 ms -> Bitmap 62 buffers/0.3 ms).
  The agent recorded honestly that it is **not a user-visible win** at the endpoint (37 ms vs 40 ms)
  and justified it on I/O shape at archive scale instead — and corrected its own migration comment
  after re-measuring warm rather than shipping the flattering cold number.

## Final verified state

All three surfaces, measured after both workflows completed:

| Surface | Result |
|---|---|
| Backend | **1724 passed, 2 skipped, 0 failed** |
| Frontend | `tsc --noEmit` clean |
| Android | `compileDebugKotlin` + `testDebugUnitTest --rerun-tasks` **BUILD SUCCESSFUL**, 24/24 tasks executed, zero `e:` lines, zero test failures |
| API | rebuilt from current source, healthy, gzip live and measured |

An earlier `BUILD FAILED` on Android was stale-daemon state, not a defect — a clean re-run passes.
That was the FIFTH phantom failure of the session, after the analytics tests, the dev server, the
136-error pytest run and the two Docker crashes. The rule stands: a failure observed while agents are
live is a hypothesis, not a finding.

## What the Android survey found, and what is still not done

The on-device report is the weakest thing in the application, and the survey is precise about why:

- **The cover page is never emitted.** `CoverBlock` is declared AND fully rendered by both Kotlin
  writers — `DocxWriter.emitCover`, `PdfWriter.blockCover` with deliberate one-page layout — and
  `grep -rn 'CoverBlock('` returns ZERO construction sites. A report exported on the handset, which
  is the copy handed to the visiting officer at the close of the workshop, opens on a table of
  contents with no title page, no organisation line, no "submitted to", no date. The renderers are
  ready; only the emit is missing. **Cheap, and the highest-value Android item left.**
- **Stages 20 and 21 ARE printed on device.** The backend has
  `NON_PRINTING_STAGES = {REPORT_GENERATION, DATA_QUALITY_ARCHIVE}` with the comment "Printing them
  would be the report describing its own generation." Android has no such exclusion, so every
  on-device report ends with the designer's own template choice, accent hex and page size printed
  back at the ministry — plus the full export log with file names, SHA-256 checksums and byte sizes.
- **17 of the 22 stage-20 settings are inert on the handset.** Only `themeAccent`/`themePreset` are
  honoured. A designer picks DCH vs DIC vs photo catalogue and gets a byte-identical PDF; the choice
  changes one invisible DOCX metadata field. Turning off the table of contents, turning off
  photographs, un-numbering headings, excluding stages, setting a header/footer or Letter page size
  all print exactly the same document.
- **No annexures at all** — media, transcripts, completeness. The transcript annexure is the one that
  matters most: the recordings are already on the handset and already transcribed, so appending them
  needs no network. It is the edge-first case, and it is server-only.
- `NARRATIVE_ORDER` absent — sections print in capture order, not the reader's order.

Two porting agents (`report-grouping`, `grants-and-access`) and the cross-workshop capability agent
were killed by session limits and were NOT revived. The e2e-suite-health agent likewise. Their work
is not started, not half-applied — the tree is consistent.

---

# The session of 2026-08-08, picked up after the machine overheated

The previous session's MAIN transcript ends at 07:49, but its BACKGROUND AGENTS kept editing files
until 13:20, which is when the machine died. So the note above describes a tree that no longer
existed: it says `CoverBlock` has zero construction sites, and by 13:20 it had one, a test, and a
whole `ReportSettings.kt` honesty layer beside it. **Read file mtimes before trusting a handover
written mid-flight.**

## What the crash actually left behind

The tree COMPILED cleanly after the crash, which is why "does it compile" was the wrong question.
The suite did not pass: **8 failures across two files, both of them an agent's own scaffolding,
frozen mid-experiment.** Both were the same manoeuvre — deliberately reverting a fix to watch the
test go red — caught by the power cut before the revert was undone:

- `QuestionnaireListing.kt:211` carried `next = null // TEMPORARY REVERT-TO-RED: ... REMOVE.`
  directly under the line that computes the next page, so the generic paged walk read ONE page and
  stopped. Six failures, all of them the walk disagreeing with the design-workshop walk it was
  extracted from.
- `ImageQuality.kt`'s `isBlurred` had lost its contrast guard while KEEPING the comment that
  describes it ("The contrast guard comes FIRST and is not a tie-breaker"). Two failures, including
  the parity test that says the handset's findings match the web word for word. The web still had
  `if (measurement.contrast < MIN_CONTRAST_STDDEV) return false;` — so the port, not the reference,
  had been opened up. Undyed cotton on a white sheet would have been called blurred on the handset
  and not in the browser.

`DwFuzzScratchTest.kt` was also still present, self-labelled "SCRATCH ONLY — deleted before this
session ends". A session that ends in a power cut deletes nothing. It is gone now; its finding is
below.

**A grep for `REVERT-TO-RED` across all three surfaces now returns nothing.** That is the cheapest
check available after an interrupted session and it is worth running first.

## The compensated sum — what the fuzz found, and why it was invisible

The scratch fuzz had written both halves of its differential (`python-fuzz.json` at 12:44,
`kotlin-fuzz.json` at 13:19) and died one minute before comparing them. Compared: **280 cases, 3
differences, all of them one sentence** — the orphan-cost caution, disagreeing about money:

| case | server | handset |
|---|---|---|
| `h024` | ₹2,560.00 | ₹0.00 |
| `h051` | ₹125,631.51 | ₹7.00 |
| `h061` | ₹912.35 | ₹900.00 |

The per-line amounts were IDENTICAL on both sides — `as_number` and `asNumber` agree, and the
payload's `orphans[].amount` matched leaf for leaf. Only the TOTAL differed, which is what makes the
cause easy to miss: **CPython 3.12 gave `sum()` the Neumaier compensated algorithm, and
`backend/Dockerfile` pins `PYTHON_VERSION=3.12`.** `Iterable<Double>.sum()` and a `for` loop are
both naive accumulators. The two agree until a list mixes magnitudes far enough apart that the small
values fall off the bottom of the total as they are added — and then the extremes cancel, and the
server still holds the small values while the client has already lost them.

`DwPy.sum` and `pySum` now implement it, and **the compensation is folded back only while the
running total is finite**: the compensation term for a step that overflows is itself infinite, so
adding it back unconditionally turns a real overflow into `₹nan` against the server's `₹inf`. That
is not a refinement, it is the difference between right and wrong on the cases this is likeliest to
meet. Verified against CPython on 200,000 random lists (for the Kotlin) and 20,000 (for the
JavaScript), drawn from infinities, NaNs, ±0.0, subnormals and overflow pairs, with no mismatch.

**It was two bugs, not one.** The cost caution is where the fuzz found it, but `describe()` sums the
price sample the same way on both clients, so the stage 9 MEAN diverged too — and that one already
carried a comment about summing in SORTED order, which is to say somebody had thought about
associativity, matched the ORDER, and missed the ALGORITHM. The web port had the identical comment
and the identical defect; it is fixed there too.

### The regression cases, and the proof that they test anything

Three named cases in `dw-analysis-cases.json`, with expectations regenerated from the BACKEND by
`android/tools/regenerate_dw_analysis_golden.py` — never hand-written:

- `k27-orphan-total-compensated-sum` — the case the fuzz failed on.
- `k28-orphan-total-overflows` — constrains the FIX, not the bug: `[1e308, 1e308]` must stay `₹inf`.
- `m29-mean-compensated-sum` — the mean, where the naive answer is 0.00 and the server's is 250.00.

Each was proven to discriminate by breaking the implementation two ways and watching which case went
red: naive summation fails k27 and m29 AND NOTHING ELSE (3 leaf values across 2 of 62 cases);
unguarded compensation fails k28 and nothing else. The other 60 cases never moved, which is the
evidence that the fix changed nothing that was already correct. The web port is pinned by
`e2e/market-analysis-sum-unit.spec.ts`, a pure-Node spec that needs no server and runs in 2 seconds;
every case there carries the old naive answer beside the new one as a witness.

## The stale-row fold — a confirmed defect in the shipped web client

The dead session left three throwaway probes in `frontend/.probe-offline/`. They were
investigations, not tests, and two of them were chasing something real.

`adoptServerDetail`'s `!incoming` branch — the server answered for the whole workshop and said
nothing about this stage, which is how `_stages_payload` reports a stage with no entries — spread
`...current` and changed only `serverLoadedAt`. Its comment said "this device has now seen it and
holds the same emptiness". **It kept every row the local copy already held, and stamped the stage as
having read the server's copy.** A row deleted at the office survived on the handset, read as
downloaded and current, and the next push could put it back. The visible end of it is a printed
artisan card for somebody no longer on the roster.

Clearing is safe because the `dirtyAt` branch immediately above has already refused the fold for any
stage this device has edited — anything reaching the branch came down from the server to begin with.

Proven both ways against the live stack: without the fix the deleted row survives AND
`serverLoadedAt` is stamped; with it, the rows are gone and the stamp is honest. It is now
`e2e/design-workshop-stale-rows.spec.ts`, in the repo's own conventions rather than the probe's
hardcoded credentials, with controls asserting that the row really was folded in and that the stage
really was clean — without those, the spec would pass against the bug.

## STILL OPEN: a server refusal of a photograph is reported as being offline

The third probe found this and nothing was done about it. It is confirmed by reading, not yet by
running, and it is NOT fixed:

- `runSync` uploads one file per call — `uploadMediaBatch({ files: [file], ... })`.
- `uploadMediaBatch` throws when `failed.length === files.length`. For a one-file batch, that is ANY
  failure.
- So the branch beneath it in `designWorkshopStore.ts` — `noteMediaFailure(..., "The server refused
  this file.")`, which records the refusal and keeps going — **can never run.** Its comment
  ("`uploadMediaBatch` throws when nothing landed at all, so reaching here with a populated `failed`
  means the server answered and refused this file") is exactly false for a batch of one: nothing
  landing IS this file being refused.
- The throw is a plain `Error`, and `isUnreachable` in `lib/offline.ts` reads `if (error instanceof
  ApiError) return error.status === 408; return true;` — so a plain Error is "offline".

A photograph the server actively refuses (wrong type, too large, malformed) therefore tells the
designer they appear to be offline, stops the pass as `stoppedOffline`, and retries forever. The
refusal is never recorded and the stage is never unblocked. The fix is a design decision with blast
radius — `uploadMediaBatch` has other callers — which is why it is written down here rather than
guessed at. The three probe files are preserved outside the tree; the `media-refusal` one is the
reproduction to start from.

## Verified state at the end of this session

Every number below was read off the run, not inferred:

| Surface | Result |
|---|---|
| Backend | **1726 passed, 2 skipped, 0 failed** (host venv against the compose stack) |
| Frontend | `tsc --noEmit` clean, `eslint` clean on every changed file |
| Android | **220 tests, 0 failed, 0 skipped**, 24/24 tasks executed, zero `e:` lines |
| New specs | `market-analysis-sum-unit` 4/4, `design-workshop-stale-rows` 1/1 against the live stack |

Three traps worth carrying forward, all of which cost time here:

- **`BUILD SUCCESSFUL` and `exit 0` are not the same claim, and a pipeline's exit code is its LAST
  command's.** `gradlew ... | grep ...` exits 0 because `grep` did, while the suite underneath had 8
  failures. Redirect to a file and read the file. The same trap ate a `docker compose exec ... |
  tail` that was really reporting "No module named pytest".
- **`pytest` is not in the API image.** The runtime container is slim; the suite runs from
  `backend/.venv` on the host against the compose Postgres. `--timeout` is not available either,
  because `pytest-timeout` is not installed.
- **The case table's odd spellings are load-bearing in a way that bites editors, not just ports.** An
  anchor on what looks like `"evidence": ""` matches nothing, because it holds two U+0085 NEXT LINE
  characters. That case exists precisely because Python strips them and a JVM `trim()` does not.

---

# The parallel-agent session of 2026-08-08 (afternoon)

The repository is now under **git** and pushed to
`github.com/cxacraftecosystem-ui/designer-portal`. That is not bookkeeping — it changed what is
possible. Android Gradle takes a PROJECT-WIDE lock, so before git there was no way to run more than
one Android agent: they could write in parallel but only one could ever verify. With worktrees, five
lanes each got their own checkout, their own lock and their own build. All five landed and all five
merged with zero conflicts.

**Secrets were verified absent before the first push, not assumed.** Every real credential file
(`infra/terraform/fieldrepo-deploy.pem`, `.env`, `backend/.env`, `backend/.env.supabase.bak`,
`frontend/.env.local`) is gitignored and returns 404 on the remote. The `JWT_SECRET` in
`.env.docker.example` and `docker-compose.yml` is left in place deliberately: the file documents it
as a public local-only placeholder, `verify_jwt_configuration` refuses to boot on a weak secret, and
it appears in no live config. Rotate it if you disagree — it is a judgement call, not an oversight.

## Two worktree traps that will bite the next agent too

Both cost a wasted build, and both have the same cause: **a gitignored file does not exist in a
worktree**.

- `android/local.properties` holds `sdk.dir`. Without it every Gradle task dies with "SDK location
  not found" before compiling a line. All five lanes were heading for this.
- `backend/.env` holds `JWT_SECRET`, the AWS keys and `MASTER_ADMIN_EMAIL`. Without it pytest dies
  in COLLECTION with a pydantic ValidationError, which reads like a code fault and is not one.

Seed both into any new worktree before running anything. `frontend/node_modules` is the third —
a directory junction to the main tree's copy is enough.

## The two data-loss defects, which were the whole value of the day

Both were found by asking one question the test suites cannot ask: *does a prose-level gesture
destroy structured content it was never aimed at?*

### 1. "Clear formatting" deleted an inline photograph or a table (fixed, `7e343e2`)

`clearFormatting` ended every block it touched with `.copy(kind = PARAGRAPH, …)` unconditionally,
and `setBlockKindIn` did the same. `toJson` writes `media` ONLY for an IMAGE block and `rows` ONLY
for a TABLE block — so the moment either was re-kinded the next save omitted the field and the
picture or the whole grid was gone from the record, on every surface, permanently.

The gesture was not a destructive-looking one: stripping a stray bold run out of a caption, or
pressing Paragraph over a selection that ran through a table on its way to a sentence three blocks
away. `frontend/lib/richText.ts` has guarded this since it was written (`isStructuralKind`, applied
in both commands); Android had no equivalent and `grep -rn 'isStructuralKind' android/app/src`
returned nothing.

`BlockKind.isStructural` is the port. **The tests assert through `toJson`, not against the in-memory
block** — a block keeps its `rows`/`media` whatever its kind, so a test reading `doc.blocks[i].media`
would have passed against the bug. Four of the five fail with the guard disabled; the fifth is the
control proving prose is STILL re-kinded, which is what separates a real guard from a lazy "skip the
block entirely" fix that would leave a heading a heading.

### 2. A never-downloaded client deleted every answer it had not read (fixed, `6119378`)

The more expensive of the two, and the one the app actively promised would not happen.

`save_stage` replaces a singleton row's `data` WHOLESALE. A client that has never downloaded a stage
holds a blank form, and a blank form is indistinguishable on the wire from a stage somebody emptied.
So: a workshop is set up in the office with stage 1 complete, the designer opens that stage in a
courtyard, the download fails, the form comes up blank, they type the one thing they came to record,
and on the drive home the sync replaces the office's work with that one field.

The blast radius went past the stage. `_coerce_promoted` nulls every promoted column whose entity
was touched and is now blank, and craftName, clusterName, state, district, venue and the dates are
all promoted off `workshopSetup` — so the workshop also fell out of every "Ikat in Odisha" filter
and the report cover handed to the visiting officer printed blank. No `RecordRevision` is written
for stage entries, so none of it was recoverable.

**Both clients displayed the opposite promise**, and the Kotlin said so in a KDoc as well:

| where | the promise |
|---|---|
| web banner | "nothing you leave blank will overwrite an answer recorded elsewhere" |
| Android banner | "anything already on the server for this stage is NOT shown below and will not be replaced by it" |
| `WorkshopSync` KDoc | a draft that has never seen the server "does not also assert that everything it lacks should be destroyed" |

The last one was false for a singleton and was the sentence a maintainer would have trusted. The
authority `isAuthoritative` grants was being spent only on `replaceCollections`/`emptiedEntities`,
which the server applies to COLLECTIONS alone.

The fix is ONE WIRE PRIMITIVE rather than two client workarounds: a per-entry `merge` flag on
`StageEntryIn`. Keys absent from `data` are kept instead of deleted; `clean` still wins every key it
holds, so it fills gaps and never overrides. It is applied BEFORE `pending`, so the merged dict is
what reaches the row, the promoted columns and the `stored` block echoed back — three readers that
must not disagree about what was just written.

**It defaults to false.** A client that has read the row means it when it omits a key, and the second
half of the new test is what stops the fix over-reaching into that case. Web drives it from
`serverLoadedAt === null`, Android from `!isAuthoritative(...)`.

Disable the merge branch and the test fails with `KeyError: 'craftName'`, which is the office's work
gone.

## What the parallel lanes produced

| lane | result |
|---|---|
| `DwWorkshopCodes.kt` | 408-line port + 18 tests. Goldens generated by TRANSPILING the real TS and running it — not by trusting the Kotlin |
| `DwSubmissionReadiness.kt` | port + 10 tests, checked against `submission-readiness-unit.spec.ts` |
| `DwPhotoMeasure.kt` | port + 42 tests |
| `DwQrEncode.kt` | port + 11 tests. Verified against the TS under `node --experimental-strip-types`: 80/80 mask matrices, 80/80 penalties, 3/3 SVG paths character-for-character |
| report lane | media + completeness annexures, REACHED (every hop walked from the export button) |

**Four of the five are dark code and said so.** Nothing calls them yet; wiring was explicitly out of
scope. The adversarial reviewer confirmed it per worktree rather than taking their word.

## Where agents were RIGHT and my instructions were WRONG

Recorded because the instinct to defer to the brief is the failure mode here.

1. **The Android writers can already draw charts and maps.** I briefed a design agent that they
   could not. `PdfWriter.kt:1290-1291` and `DocxWriter.kt:1252-1253` already dispatch both, the
   rasterisers are ~2,300 lines, and the India boundary data is a **91 KB offline APK asset**, not a
   fetch. The real gap is that nothing ever CONSTRUCTS a `ChartBlock` or `MapBlock` — this repo's
   signature defect, for the fourth time today. That makes it ~350 lines of arithmetic, not an
   engine.
2. **`DwPy.round` was the wrong instruction for `photoMeasure`.** I told the lane to use it. The
   agent checked, found there is no `photo_measure` under `backend/` at all — so the TypeScript is
   the authority, not Python — and confirmed on the running TS that the web proposes 201 mm where
   `DwPy.round` gives 200. Obeying me would have SHIPPED the divergence. If a Python module is ever
   written, the web must change in the same commit or the three clients cannot agree.
3. **Artisan home-district pins should stay SERVER-ONLY.** The device's cached artisan record carries
   `village` and no district or state, and the server's district anchors are a running average over
   live `Location` rows, not a shippable table. Porting it would fold twenty Bargarh artisans onto
   Bhubaneswar — the exact defect the server fixed last month — into the copy least likely to be
   checked against anything.

## The verifier that caught its own fixer

Worth keeping as an argument for adversarial verification over a second opinion. The backend hygiene
agent reported "1585 passed, 141 errors", explaining the errors as an unavoidable absence of local
Postgres — "nothing listening on 5432". The verifier re-ran it and got **1726 passed, 2 skipped, in
2m55s**. The stack listens on **55442**; the fixer had checked the wrong port and its own quoted
error message named the right one. Its pass was fine; its verification was not, and it happened to
leave exactly the DB-backed code it had touched unexercised.

## Still open, ranked, with 28 findings recorded

A read-only six-lens sweep produced 31 findings; 28 survived adversarial refutation. The full list
with file:line evidence is in the session scratchpad. The ones that matter most:

- **SECURITY (HIGH)** — report generation and the transcript annexure fetch ANY `MediaFile` by
  client-supplied id, bypassing the entitlement check.
- **SECURITY (MEDIUM)** — `MediaFile.url` is taken verbatim from the upload payload, so a signed-in
  account can plant a URL the API then 307-redirects to.
- **BUG (HIGH, web)** — a row deleted while a background sync PUT is in flight loses its
  `removedFrom` flag, so the row returns on the next read and prints in the officer's report.
  `designWorkshopStore.ts:2032-2033` clears it unconditionally where the sibling `dirtyAt` on the
  very next line is guarded by a timestamp comparison.
- **BUG (MEDIUM, android)** — "Save and sync this stage now" starts an 800 ms timer instead of
  saving; leaving the screen inside that window discards the write.
- **PERFORMANCE (HIGH, backend)** — the report input load is up to ten sequential round trips where
  the dependency graph allows four.
- **PERFORMANCE (HIGH, android)** — the .docx writer holds every photograph's full original bytes on
  the heap at once, while its sibling PDF writer in the same package documents why that must not be
  done.
- **A11Y (HIGH, web)** — `CollabDialog` on six list pages and "Assign researchers" have no dialog
  role, no focus trap and no Escape handling.
