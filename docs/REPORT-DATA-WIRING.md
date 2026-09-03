# Wiring artisan, product, process, tool and questionnaire data into the report

Research done 2026-08-08, read-only, so the implementation is execution rather than exploration.
Every claim below was read rather than assumed.

> **The `file:line` pins that sentence used to promise were removed on 2026-08-15, because they had
> stopped being true.** Five of them had drifted onto unrelated code — `REFERENCE_MODELS` cited 58
> lines above the table, `ReferencedRecord` 11 above the class, `REFERENCE_HYDRATION` cited in the
> wrong module altogether — while `docs/tools/check-docs.mjs` reported them green, because all it
> asked was whether the number was inside the file. Symbol names replace them: a symbol that moves is
> still greppable, and a line number that moves is a confident lie. The checker now tests the two
> against each other (see its `checkCitations`), which is why this file no longer offers it anything
> to rot.

## How reference data reaches the report today, and why it is built this way

A REF field stores an id and nothing else. **The display fields are COPIED onto the stage entry at
save time** by `REFERENCE_HYDRATION` — declared in `backend/app/services/stage_schema.py` and applied
by `backend/app/services/design_workshops.py`, which imports it — and the report reads the copy.
`ReportBuilder` performs no lookup for prose and does so deliberately: the `ReferencedRecord`
docstring in `backend/app/services/report_builder.py` states the reason at length, that a submitted
report must never re-resolve a name through a live table, because a record edited or deleted after
submission would silently change a document already handed to a ministry officer.

**That constraint is correct and must survive this work.** "Wiring more data into the report" means
copying more at SAVE time, not resolving more at RENDER time.

### The source can be a row of this workshop (added 2026-09-03)

**This document's framing — that reference data reaches the report from records in OTHER TABLES — is
now only half true.** Twelve of the mappings copy from a shared record resolved through a Prisma
delegate named on `REFERENCE_MODELS`. **Two copy from another row of the SAME workshop**: a
`DwStageEntry` the designer filled in at an earlier stage, resolved by `hydrate_entries`' internal
path, which reads `dwstageentry` scoped to the workshop being saved and respects `deletedAt`. They
are `finalProduct.prototypeRef` (9 pairs — picking a prototype at stage 16 fills nine boxes) and
`prototypeValidation.prototypeRef` (3 pairs). Both point at `DwPrototype`, which is deliberately not
a `REFERENCE_MODELS` key.

**Only the LOADING branch differs.** Same only-fill-blanks rule, same clear-and-rewrite on a
re-pointed ref, same `coerce_value`, same provenance stamp, same publication to both clients through
`field_to_dict`'s `refHydration`. The argument for which internal refs carry and which deliberately
do not — the rule is that the two boxes must ask the same question about the same physical subject,
and the thirteen live `Dw…` refs it leaves un-hydrated are each named — is in `stage_schema.py`'s
internal-carry section. Read it there rather than reasoning by symmetry from this file.

**One limit follows and is worth knowing here**: media never crosses on an internal carry, costs do
not cross (an arithmetic limit, not a refusal about the value), and `entry_provenance.canonical_divergence`
is gated on `model in REFERENCE_MODELS` — so an admin audit sees an internal field's provenance
stamp but never the stored-vs-canonical comparison it gets for an artisan.

`ReferencedRecord` (in `report_builder.py`) carries what hydration cannot: **a photograph of a record
whose own mapping seeds none.** `prototype.productRef` → `{"name": "productName"}` and
`existingProduct.artisanRef` → `{"name": "artisanName"}` are one field each, so for those two rows
the referenced record's picture reaches the page by no other route.

> **The reason is NOT "hydration must never seed a gallery". This paragraph asserted that it was,
> corrected 2026-08-20 — and the wrong wording had been copied here on 2026-08-19 out of a code
> docstring that had itself already been corrected, which is the failure mode this whole document
> exists to stop. Verify against the mapping, not against a remembered sentence.** The rule
> `hydrate_entries` states is: **a gallery is SEEDED WHEN EMPTY and NEVER OVERWRITTEN.** Under it,
> `existingProduct.productRef` maps `photo` → `productPhotos` deliberately — its `stage_definitions`
> declaration is written out longhand instead of through `photos()` for the sake of one extra
> sentence saying the documented product's own photograph is seeded there, so that a designer who is
> not told does not add the same picture a second time. **Only `prototype`'s gallery is left
> unseeded.** And the reasons `stage_schema.py` gives at the two bare-name mappings are different
> again from either: `existingProduct.artisanRef` copies only a name because that row documents a
> PRODUCT and declares no box for village, phone or specialisation, and the stage-3 roster already
> carries all three against the same artisan; `prototype.productRef` stops at a name because a
> prototype is defined by how it DIFFERS from the product it derives from. Anyone who "restores" the
> never-seed rule by deleting the `photo` → `productPhotos` mapping costs every new product row the
> catalogue photograph its own help text promises, while only-fill-blanks leaves every row saved
> before the deletion holding theirs — an inconsistency that appears nowhere in the report. The
> `ReferencedRecord` docstring spends a paragraph on exactly that; read it before touching the map.

> **Its `district`/`state` fields are no longer the only home for where an artisan lives, and that
> sentence used to say they were — corrected 2026-08-19.**
> `REFERENCE_HYDRATION["participant.artisanRef"]` now copies `state`, `district`, `pincode`,
> `address` and `subjectLocation` onto the stage-3 roster row at save time, alongside the village,
> so the frozen copy the invariant demands DOES exist for any row saved since that widening.
> `ReferencedRecord.district`/`.state` still earn their place for rows saved BEFORE it and for rows
> a designer typed by hand with no artisan picked at all — but a renderer that reaches for the live
> table "because the roster has no district" is now reaching past a copy that is sitting right
> there. Read `participant.artisanRef` in `stage_schema.py` before believing any sentence that says
> the roster records only a village.

## The registry: seven models, and the one that is not pooled

> **This heading read "five models, and one that does not exist" until 2026-09-03. It was already
> stale at six, and it is now SEVEN** — the sentence below is kept because the *point* of it was
> which model was absent, and that model is now present.

`REFERENCE_MODELS` (in `design_workshops.py`) registers **Artisan, ProductDocumentation,
ToolDocumentation, Process, Craft, QuestionnaireInterview and Questionnaire.**

**`Questionnaire` is the seventh and the first that is NOT POOLED.** Every other model here is served
under `records.viewable_where`, which is empty — the pooling rule is that every signed-in account may
read every artisan, product, tool, process, craft and interview. `Questionnaire` carries `ownerId`, a
nullable `designWorkshopId` and an admin-set `isShared`, so its options are narrowed by the asking
ACCOUNT through `ReferenceModel.viewer_where`, not by the workshop. `reference_options` composes that
clause with the others and **refuses outright (401) when the request names no account**, because an
account-scoped list served to nobody is a leak wearing an empty filter.

The predicate is `questionnaire_forms.visible_questionnaire_where`, which is the same four-clause rule
`GET /questionnaires` is narrowed by — **moved out of the route and into the service on 2026-09-03**
so there is one definition rather than two copies, with a one-line alias left behind at the route.
`design_workshops` cannot import a route module (that route imports it), so the move was the
alternative to a second copy.

The original finding below is kept as history: **Questionnaire data reached the report by no path at
all on 2026-08-08**, and that is no longer true — see the DECIDED AND BUILT section, and now
`surveyPlan.questionnaireRef`, which carries the TITLE and nothing else.

## The actual gaps, ranked

### 1. Process contributes only a name — the largest gap — **CLOSED, verified 2026-08-15**

> `REFERENCE_HYDRATION["processStep.processRef"]` now copies three fields, not one: `name`,
> `notes -> description` ("What happens", the copy that turns a one-word row into a paragraph) and
> `productName -> documentedFor`. `steps` and `preProcessAvailable` are deliberately not copied
> **onto a step ROW** — they are copied once onto the stage-5 singleton instead, which is the
> subsection below; the reason for each is written above the mapping in `stage_schema.py` rather
> than repeated here. **The paragraph below is the 2026-08-08 finding and is kept because it is the
> argument that got the work done — it is not a description of the mapping today.** It was found
> still standing on 2026-08-15 while its own line citations were being repaired, which is the
> warning: a stale citation and a stale claim rot in the same file for the same reason, and the
> checker can only ever see the first of them.

`REFERENCE_HYDRATION["processStep.processRef"]` was `{"name": "name"}`, and the model's own `data`
lambda was `{"name": r.name}` (the `Process` entry in `REFERENCE_MODELS`). So a process step in the
report printed "Dyeing" and nothing else, while the `Process` record it points at holds the
documented sequence.

Compare what its siblings copied **on 2026-08-08**. All four have widened a long way since, so the
middle column is history and not a description of the mapping today; the right-hand column is the
count as of 2026-08-27, taken by importing `REFERENCE_HYDRATION` from
`backend/app/services/stage_schema.py` and printing `len()` of each entry.

| mapping | fields copied, 2026-08-08 | pairs today |
|---|---|---|
| `participant.artisanRef` | name, localName, specialisation, experienceYears, gender, phone, village, photo — **8** | **26** |
| `tool.toolRef` | name, localName, material, usedFor, cost, photo — **6** | **33** |
| `existingProduct.productRef` | name, category, material, price, use, photo — **6** | **29** |
| `processStep.processRef` | name — **1** | **3** |

> **The table above is four mappings out of fourteen, and its right-hand column is dated 2026-08-27.**
> The registry as a whole is **14 mappings carrying 136 field pairs** — counted 2026-09-03 by
> `ast.literal_eval` over the literal, not by eye; the five one-pair mappings are written on a single
> line each and are the ones a manual count misses. Do not treat the four rows here as the registry.

The traditional-process stage is one of the report's substantive narrative sections, and it is the
thinnest of the five by an order of magnitude.

**Work:** widen the `Process` model's `data` lambda and its hydration mapping to the fields the
`Process` table actually holds. Read the Prisma model first — do not invent field names.

#### How that work actually landed: a second mapping, on the stage-5 singleton (current state, 2026-08-19)

This is not in the paragraph above because it did not exist when the paragraph was written, and it
is the half a reader is most likely to miss: **there are TWO process mappings, not one.**

`REFERENCE_HYDRATION["traditionalProcess.processRef"]` hydrates stage 5's one-per-workshop
`traditionalProcess` singleton — the overview that prints ABOVE the steps table — and it fills six
boxes: `documentedProcessName`, `documentedProcessNotes`, `documentedFor`, `documentedSteps`,
`preProcessAvailable` and `documentedOn`. The singleton's `processRef` field exists for exactly this
purpose; it was added because the note on `processStep.processRef` had already identified the right
home for the sub-steps and the pre-process flag and then observed that "a singleton has no ref field
to hydrate from".

The `steps` value is not a list on the wire. The `Process` model's `data` lambda in
`REFERENCE_MODELS` builds it through `_step_lines` (in `design_workshops.py`), which flattens the
source's ordered `ProcessStep` rows into ONE newline-separated string — position, name, a GROUP
marker, and the note after an em dash. That shape is load-bearing: `documentedSteps` is declared
`LONG_TEXT` with `report_role=BULLETS`, which the renderer splits on newlines into one bullet per
step. A TAGS field was tried first and printed the whole documented sequence as a single run-on line
under a heading that promises a list.

**Do not close the loop by widening `processStep.processRef` instead.** Adding `steps` or
`preProcessAvailable` there prints the entire sequence inside one of its own steps, and prints it
again on every row that names the same process — the exact defect both notes exist to describe.
`stage_schema.py` ends its note with the instruction: widen the singleton.

### 2. Two mappings copy a bare name, and only PART of that is deliberate

`existingProduct.artisanRef` → `{"name": "artisanName"}` and
`prototype.productRef` → `{"name": "productName"}`, both in `REFERENCE_HYDRATION`.

> **There are FIVE one-pair mappings now, not two — 2026-09-03.** The other three are
> `traditionalProcess.productRef` and `processStep.productRef` (both `name → documentedFor`) and the
> new `surveyPlan.questionnaireRef` (`name → questionnaireName`). The heading is kept because this
> section's *argument* is about the two it names; the count is not the registry's. **A one-pair
> mapping is an ATTRIBUTION rather than a mirror** — the row says who or what the record was made
> from, and never becomes a copy of it — which is the reason all five sit in `StageRecordEmbed`'s
> `NOT_EMBEDDED` rather than its `MIRROR_POINTS`, and it is stated there in each one's own words.
> Do not read a one-pair mapping as an unfinished wide one.

On 2026-08-08 the `ReferencedRecord` docstring explained the PHOTO omission as "those entities have a
gallery of the designer's OWN photographs and hydration must never overwrite them", and this section
repeated it. **That sentence has since been struck in the docstring itself and is struck here** — see
the correction under "How reference data reaches the report today" above: a gallery is seeded when
empty and never overwritten, `existingProduct.productRef` seeds `productPhotos` on purpose, and only
`prototype`'s gallery is left unseeded. What survives is the narrow true part: the photo reasoning,
whatever its grounds, **covers the photo and nothing else.** Whether `category`, `material`, `price` and `use`
should also be copied onto a prototype row was left here as an open product question, with the
instruction to decide it explicitly rather than widen by symmetry.

> **DECIDED, and decided the way this section asked — verified 2026-08-15.** Both mappings are still
> one field, and the reasoning now sits above them in `stage_schema.py`: `existingProduct.artisanRef`
> copies only a name because the entity declares no box for village, phone or specialisation and the
> stage-3 roster already carries all three against the same artisan, where a second copy could only
> disagree with the first; `prototype.productRef` stops at a name because a prototype is defined by
> how it DIFFERS from its parent, and `materials` and `price` are exactly the fields the workshop
> exists to change. Read it there. The point of this note is that "open question" was the state on
> 2026-08-08 and is not the state now.

### 3. Questionnaire data reaches the report by no path at all — DECIDED AND BUILT

**Status: closed on the server.** `SpecialSection.ANNEXURE_QUESTIONNAIRES`,
`app/services/report_questionnaires.py`, one branch in `ReportBuilder.build`,
`questionnaire_forms.report_items()` and `design_workshops.attach_report_questionnaires()`. The
argument is in that module's docstring; the summary is below because the paragraph this replaces
named the wrong source and would send the next reader down it.

**THERE ARE TWO QUESTIONNAIRE SYSTEMS AND THE OBVIOUS ONE IS THE WRONG ONE.**

`QuestionnaireInterview` / `QuestionnaireResponse`, behind `/api/questionnaire/interviews` and listed
by the Android client, is the org-wide artisan documentation instrument. Its foreign key is
`workshopId -> Workshop` (the `QuestionnaireInterview` model in `schema.prisma`) — the LEGACY
documented-workshop model, not
`DesignWorkshop`. It reaches a design workshop only through the nullable `DesignWorkshop.workshopId`
two hops away, set only when a design workshop happens to be held at a documented one, and it carries
its own review-queue permission model.

`Questionnaire` / `QuestionnaireFormSection` / `QuestionnaireFormQuestion` / `QuestionnaireFormEntry`
/ `QuestionnaireFormAnswer` is the DESIGNER's own instrument, built from the `.xlsx` pro-forma and
answered in-app. It carries **`Questionnaire.designWorkshopId`** and
**`DesignWorkshop.questionnaires`**, both on the `DesignWorkshopQuestionnaires` relation in
`schema.prisma` — a direct, first-class, already populated
link to the exact record the report is about. That is the source.

**Shape: an annexure, not a registry entity.** A REF stores one id and `REFERENCE_HYDRATION` copies
scalars onto the stage entry at PICK time. A questionnaire is sittings × questions, sized by the
fieldwork rather than by the registry, and hydration would have frozen the answers as they stood the
moment the designer chose the form — omitting every answer recorded afterwards, which is the whole of
the fieldwork.

**No stage-20 toggle**, and that is a decision rather than an omission: `includeTranscripts` exists
because transcripts happen automatically to a designer, whereas attaching a questionnaire to a
workshop is the designer asking for it. A toggle would also have changed `registry_version()`,
forcing a regeneration of `android/app/src/main/assets/design-workshop-schema.json`.

**Permissions mirror the server and were not invented.** Sittings are readable by the questionnaire's
owner, an admin, or anyone working on the design workshop it is attached to
(`_works_on_this_questionnaires_workshop`, in `backend/app/api/routes/questionnaire_forms.py`).
Report generation requires `load_workshop_or_404` in `design_workshops.py` — a subset of that set.

**Android carries the section and cannot fill it**, declared in `UNSUPPORTED_SECTIONS`: the answers
have no local copy at all, and `WorkshopRepository`'s "Custom questionnaires" block falls back to the
device for nothing, on purpose. The remaining question there is whether to give the handset a
read-only cache of the answers, which is a data decision and not a report one.

## Both surfaces

Whatever lands on the server must land on Android too, because the handset builds its own report
from the local draft. The Kotlin mirror of hydration is what makes the on-device copy agree with the
office copy — a field copied on the server but not on the handset is exactly the cross-surface
divergence this project has been fixing all day.

## The rule that makes all of this safe

Hydration copies at save time; the report never re-resolves. Any new field must therefore be:

1. added to the model's `data` lambda in `REFERENCE_MODELS`,
2. added to the mapping in `REFERENCE_HYDRATION`,
3. mirrored on Android,
4. and covered by a test asserting the copied value survives a round trip — because a field that is
   hydrated but not printed, or printed but not hydrated, fails silently in a document nobody
   re-reads.

**There is a fifth surface as of 2026-09-03, and it is enforced rather than remembered.** A NEW
MAPPING must be declared in the web's `frontend/components/designworkshop/StageRecordEmbed.tsx` —
either in `MIRROR_POINTS`, if the row earns a whole record form mounted over it, or in
`NOT_EMBEDDED` with the reason it does not. `frontend/e2e/stage-record-embed-unit.spec.ts` reads the
two lists as halves of one set and fails on a mapping that is in neither, so a new hydration mapping
cannot slip past silently. Today that is 4 mirrored and 10 refused against the registry's 14. The
step exists because "no form here" and "nobody has got to it yet" are indistinguishable from an empty
list — and two of the refusals were found by that pin rather than by the brief.

---

## How this document is kept true

**This was read-only research, done on 2026-08-08, so that the implementation would be execution
rather than exploration — and the implementation has since happened.** That makes it a hybrid, and a
reader has to know which half they are in:

- **The survey of gaps (§*The actual gaps, ranked*)** is a snapshot of what was missing on a date.
  One of its three entries is already marked *DECIDED AND BUILT*. The other two are only as current
  as the last person who checked.
- **The rule at the foot** — *hydration copies at save time; the report never re-resolves* — is not a
  snapshot. It is the invariant the whole report path depends on and it outlives this document.

| Claim class | Kept true by |
|---|---|
| The invariant, and the four steps a new field must take | `REFERENCE_HYDRATION` in `backend/app/services/stage_schema.py` (it is applied, not declared, by `design_workshops.py` — the two were in one module when this was written) and `REFERENCE_MODELS` in `backend/app/services/design_workshops.py`, `ReferencedRecord` and `ReportBuilder` in `backend/app/services/report_builder.py`, and the Kotlin mirror in `android/app/src/main/java/com/designprototype/workshop/report/`. Step 4 of the rule — *covered by a test asserting the copied value survives a round trip* — is the mechanical part, and it is the whole reason the rule is safe: a field hydrated but not printed, or printed but not hydrated, fails silently in a document nobody re-reads. |
| "Questionnaire data reaches the report by no path at all" | **Already superseded within the document itself** — the section is marked DECIDED AND BUILT. Confirm against `backend/app/services/report_questionnaires.py` and `SpecialSection.ANNEXURE_QUESTIONNAIRES` rather than reading the heading. |
| "There are two process mappings" and the six boxes the singleton fills | `REFERENCE_HYDRATION["traditionalProcess.processRef"]` and `REFERENCE_HYDRATION["processStep.processRef"]` in `backend/app/services/stage_schema.py`, and `_step_lines` in `backend/app/services/design_workshops.py`. **Read both mappings, not one.** This row exists because the §1 blockquote said for eleven days that `steps` and `preProcessAvailable` "are deliberately still not copied" after they had started being copied — true of the ROW mapping, false of the registry as a whole, and a reader planning to close the gap would either have rebuilt `_step_lines` or widened the row mapping and reintroduced the run-on-sequence-per-row defect. |
| Anything this document says about WHICH galleries hydration seeds | **The mapping in `stage_schema.py`, read this minute — never a docstring, and never this file's own previous wording.** `grep -n '"photo"' backend/app/services/stage_schema.py` settles it in one command: it returns exactly three seeding mappings — `participant.artisanRef` and `tool.toolRef` (both `photo` → `photo`) and `existingProduct.productRef` (`photo` → `productPhotos`, the gallery one) — and `prototype.productRef` is not among them. **This row exists because the failure has now happened twice in three days**: `ReferencedRecord`'s docstring carried "hydration must never seed a gallery", was corrected in the tree with a capitalised note saying so, and the pre-fix sentence was then copied INTO this document from the very docstring that had struck it. A sentence of the form "hydration must never seed X's gallery" is the shape to distrust; the rule is `hydrate_entries`' — seeded when empty, never overwritten — and the consequence of acting on the wrong one is deleting a live mapping, which only-fill-blanks then makes unrecoverable row by row. |
| "Where the artisan lives is only on `ReferencedRecord`" | `REFERENCE_HYDRATION["participant.artisanRef"]` in `backend/app/services/stage_schema.py` — count its keys. It carries `state`, `district`, `pincode`, `address` and `subjectLocation` as well as `village`. **All five code sites are corrected, and every one of them was OPENED and read on 2026-08-20 before this sentence was written. Correct none of them.** They are: `ReferencedRecord` and `_artisan_points` in `backend/app/services/report_builder.py`; `load_report_references` in `backend/app/services/design_workshops.py`; `renderMap`'s KDoc in `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/ReportFigures.kt` (**not** `report/ReportFigures.kt` — this row once cited no path at all and the obvious guess is the wrong directory); and the test formerly called `test_artisan_homes_come_from_the_referenced_artisan_record`, which **no longer exists under that name** — it is `test_a_roster_row_that_states_no_address_falls_back_to_the_referenced_artisan_record` in `backend/tests/test_report_figures.py`, and the rename IS the correction, so a reader hunting the old symbol finds nothing and concludes the file was missed. **THE OLD SENTENCE IS STILL GREPPABLE IN ALL FIVE, BECAUSE EACH ONE QUOTES IT INSIDE ITS OWN RETRACTION — so a grep hit is evidence of a correction, not of staleness, and it is the whole trap this row now exists to spring.** Read the paragraph the hit sits in. `load_report_references` says "This bullet used to say 'No roster field holds a district — the participant row records a village as free text' … and reading that as true is what kept the ministry's map wrong"; `renderMap` says "The premise this paragraph used to rest on has gone false and is recorded here so nobody argues from it again … Both halves are now wrong"; `ReferencedRecord` says "This bullet used to say 'No participant field holds a district: the roster records a village as free text'. That is no longer true"; `_artisan_points` says "It was the other way round, on a stated reason that had gone false". **This row has now been wrong three times in two waves, always in the same direction — naming a corrected file as stale, which sends the next reader to revert a fix. Re-verify by OPENING each site, never by grepping for the retracted string.** |
| "`REFERENCE_MODELS` registers exactly five" | **It registered seven when this row was last read, on 2026-09-03 — the sentence had already been stale at six before that.** Count them in `backend/app/services/design_workshops.py`. **Do not restate the number anywhere else** — it is the shape of count `docs/tools/check-docs.mjs` exists to keep out of prose, and it is stated here only because the point of the sentence is which model is *absent*, which has now been answered twice in the same direction. The seventh, `Questionnaire`, is also the first entry whose rows are not pooled: it carries `viewer_where`, so its option list is narrowed by the asking account and `reference_options` refuses a request that names none. |
| The four-step rule's **fifth** surface, and the counts in it | `MIRROR_POINTS` and `NOT_EMBEDDED` in `frontend/components/designworkshop/StageRecordEmbed.tsx`, and the pin in `frontend/e2e/stage-record-embed-unit.spec.ts` that reads them as one set. **The pin is what keeps this true, not this row** — a mapping absent from both lists fails that spec. The 4-and-10 figures here are what the two arrays held on 2026-09-03; re-derive them from the arrays, never from this table. Note that the module's own header prose still describes eleven REF fields with four mirrored and seven refused, which the arrays no longer say — a frontend correction, recorded here so the next reader does not take the prose over the data. |
| "Two mappings copy a bare name" / any count of mappings or pairs | `REFERENCE_HYDRATION` in `backend/app/services/stage_schema.py`, and **count it with `ast.literal_eval`, not by eye**: five of its mappings are written on one line each and a manual pass or a naive regex misses every one of them. That is exactly how "13 mappings / 135 pairs" was reported for a registry holding **14 and 136** on 2026-09-03. |
| The `file:line` citations | **There are none left, as of 2026-08-15, and that is the fix rather than an evasion.** Every pin in this file had rotted or was about to: `REFERENCE_MODELS` cited 58 lines above the table, `ReferencedRecord` 11 above the class, `REFERENCE_HYDRATION` cited in a module it no longer lives in, `load_workshop_or_404` 15 lines above the function, three `schema.prisma` pins into unrelated models. Two earlier attempts at this row *described* the rot instead of removing it — one of them stating a "now at" figure that was wrong within the day — which left every wrong number in the body exactly where a reader would follow it. Symbol names replace them all. **Do not add new line pins here**: these files are under active work, a citation drifts by however much code is inserted above it, and that number only ever grows. `docs/tools/check-docs.mjs` now catches drift wherever a symbol is named beside a pin, but it cannot catch a pin that names nothing, and it is not a substitute for not pinning. |
| The Android mirror claim | `android/app/src/main/java/com/designprototype/workshop/report/ReportSettings.kt` and `ReportTemplates.kt`, plus `android/app/src/main/assets/design-workshop-schema.json` — the last of which is generated from the server's registry, so a registry change that does not reach it is precisely the cross-surface divergence step 3 exists to prevent. |

**Review triggers:** `REFERENCE_MODELS`, `REFERENCE_HYDRATION`, `backend/app/services/report_builder.py`,
`backend/app/services/report_templates.py`, `registry_version()`, or anything under
`android/app/src/main/java/com/designprototype/workshop/report/`.

**Known unverified:** nothing here was executed. It is a reading of the code as it stood, and the
value of it was that the reading was done before the writing. Treat any gap it lists as needing
re-confirmation before somebody plans work around it.
