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

`ReferencedRecord` (in `report_builder.py`) carries the two things hydration cannot: a photograph
of a record the mapping does not seed, and the artisan's district/state, which no roster field holds.

## The registry: five models, and one that does not exist

`REFERENCE_MODELS` (in `design_workshops.py`) registers exactly five:
**Artisan, ProductDocumentation, ToolDocumentation, Process, Craft.**

**Questionnaire is not among them**, and `grep -rn questionnaire` over `report_builder.py` and
`report_templates.py` returns nothing. Questionnaire data does not reach the report by any path.

## The actual gaps, ranked

### 1. Process contributes only a name — the largest gap — **CLOSED, verified 2026-08-15**

> `REFERENCE_HYDRATION["processStep.processRef"]` now copies three fields, not one: `name`,
> `notes -> description` ("What happens", the copy that turns a one-word row into a paragraph) and
> `productName -> documentedFor`. `steps` and `preProcessAvailable` are deliberately still not
> copied, and the reason for each is written above the mapping in `stage_schema.py` rather than
> repeated here. **The paragraph below is the 2026-08-08 finding and is kept because it is the
> argument that got the work done — it is not a description of the mapping today.** It was found
> still standing on 2026-08-15 while its own line citations were being repaired, which is the
> warning: a stale citation and a stale claim rot in the same file for the same reason, and the
> checker can only ever see the first of them.

`REFERENCE_HYDRATION["processStep.processRef"]` was `{"name": "name"}`, and the model's own `data`
lambda was `{"name": r.name}` (the `Process` entry in `REFERENCE_MODELS`). So a process step in the
report printed "Dyeing" and nothing else, while the `Process` record it points at holds the
documented sequence.

Compare what its siblings copy:

| mapping | fields copied |
|---|---|
| `participant.artisanRef` | name, localName, specialisation, experienceYears, gender, phone, village, photo — **8** |
| `tool.toolRef` | name, localName, material, usedFor, cost, photo — **6** |
| `existingProduct.productRef` | name, category, material, price, use, photo — **6** |
| `processStep.processRef` | name — **1** |

The traditional-process stage is one of the report's substantive narrative sections, and it is the
thinnest of the five by an order of magnitude.

**Work:** widen the `Process` model's `data` lambda and its hydration mapping to the fields the
`Process` table actually holds. Read the Prisma model first — do not invent field names.

### 2. Two mappings copy a bare name, and only PART of that is deliberate

`existingProduct.artisanRef` → `{"name": "artisanName"}` and
`prototype.productRef` → `{"name": "productName"}`, both in `REFERENCE_HYDRATION`.

The `ReferencedRecord` docstring explains the PHOTO omission and it is deliberate: those entities
have a gallery of the designer's OWN photographs and hydration must never overwrite them. **That
reasoning covers the photo and nothing else.** Whether `category`, `material`, `price` and `use`
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
| "`REFERENCE_MODELS` registers exactly five" | Count them in `backend/app/services/design_workshops.py`. **Do not restate the number anywhere else** — it is the shape of count `docs/tools/check-docs.mjs` exists to keep out of prose, and it is stated here only because the point of the sentence is which model is *absent*. |
| The `file:line` citations | **There are none left, as of 2026-08-15, and that is the fix rather than an evasion.** Every pin in this file had rotted or was about to: `REFERENCE_MODELS` cited 58 lines above the table, `ReferencedRecord` 11 above the class, `REFERENCE_HYDRATION` cited in a module it no longer lives in, `load_workshop_or_404` 15 lines above the function, three `schema.prisma` pins into unrelated models. Two earlier attempts at this row *described* the rot instead of removing it — one of them stating a "now at" figure that was wrong within the day — which left every wrong number in the body exactly where a reader would follow it. Symbol names replace them all. **Do not add new line pins here**: these files are under active work, a citation drifts by however much code is inserted above it, and that number only ever grows. `docs/tools/check-docs.mjs` now catches drift wherever a symbol is named beside a pin, but it cannot catch a pin that names nothing, and it is not a substitute for not pinning. |
| The Android mirror claim | `android/app/src/main/java/com/designprototype/workshop/report/ReportSettings.kt` and `ReportTemplates.kt`, plus `android/app/src/main/assets/design-workshop-schema.json` — the last of which is generated from the server's registry, so a registry change that does not reach it is precisely the cross-surface divergence step 3 exists to prevent. |

**Review triggers:** `REFERENCE_MODELS`, `REFERENCE_HYDRATION`, `backend/app/services/report_builder.py`,
`backend/app/services/report_templates.py`, `registry_version()`, or anything under
`android/app/src/main/java/com/designprototype/workshop/report/`.

**Known unverified:** nothing here was executed. It is a reading of the code as it stood, and the
value of it was that the reading was done before the writing. Treat any gap it lists as needing
re-confirmation before somebody plans work around it.
