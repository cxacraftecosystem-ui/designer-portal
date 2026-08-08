# Wiring artisan, product, process, tool and questionnaire data into the report

Research done 2026-08-08, read-only, so the implementation is execution rather than exploration.
Every claim below carries a file:line and was read rather than assumed.

## How reference data reaches the report today, and why it is built this way

A REF field stores an id and nothing else. **The display fields are COPIED onto the stage entry at
save time** by `REFERENCE_HYDRATION` (`backend/app/services/design_workshops.py:393`), and the
report reads the copy. `ReportBuilder` performs no lookup for prose and does so deliberately —
`report_builder.py:105-128` states the reason at length: a submitted report must never re-resolve a
name through a live table, because a record edited or deleted after submission would silently
change a document already handed to a ministry officer.

**That constraint is correct and must survive this work.** "Wiring more data into the report" means
copying more at SAVE time, not resolving more at RENDER time.

`ReferencedRecord` (`report_builder.py:105`) carries the two things hydration cannot: a photograph
of a record the mapping does not seed, and the artisan's district/state, which no roster field holds.

## The registry: five models, and one that does not exist

`REFERENCE_MODELS` (`design_workshops.py:277`) registers exactly five:
**Artisan, ProductDocumentation, ToolDocumentation, Process, Craft.**

**Questionnaire is not among them**, and `grep -rn questionnaire` over `report_builder.py` and
`report_templates.py` returns nothing. Questionnaire data does not reach the report by any path.

## The actual gaps, ranked

### 1. Process contributes only a name — the largest gap

`REFERENCE_HYDRATION["processStep.processRef"]` is `{"name": "name"}`
(`design_workshops.py:419`), and the model's own `data` lambda is
`{"name": r.name}` (`design_workshops.py`, Process entry). So a process step in the report prints
"Dyeing" and nothing else, while the `Process` record it points at holds the documented sequence.

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
`prototype.productRef` → `{"name": "productName"}` (`design_workshops.py:420, 429`).

`report_builder.py:114-120` explains the PHOTO omission and it is deliberate: those entities have a
gallery of the designer's OWN photographs and hydration must never overwrite them. **That reasoning
covers the photo and nothing else.** Whether `category`, `material`, `price` and `use` should also
be copied onto a prototype row is an open product question — the answer may legitimately be no,
because a prototype is not the product it derives from. Decide it explicitly; do not widen these two
by symmetry with the others.

### 3. Questionnaire data reaches the report by no path at all — DECIDED AND BUILT

**Status: closed on the server.** `SpecialSection.ANNEXURE_QUESTIONNAIRES`,
`app/services/report_questionnaires.py`, one branch in `ReportBuilder.build`,
`questionnaire_forms.report_items()` and `design_workshops.attach_report_questionnaires()`. The
argument is in that module's docstring; the summary is below because the paragraph this replaces
named the wrong source and would send the next reader down it.

**THERE ARE TWO QUESTIONNAIRE SYSTEMS AND THE OBVIOUS ONE IS THE WRONG ONE.**

`QuestionnaireInterview` / `QuestionnaireResponse`, behind `/api/questionnaire/interviews` and listed
by the Android client, is the org-wide artisan documentation instrument. Its foreign key is
`workshopId -> Workshop` (`schema.prisma:942`) — the LEGACY documented-workshop model, not
`DesignWorkshop`. It reaches a design workshop only through the nullable `DesignWorkshop.workshopId`
two hops away, set only when a design workshop happens to be held at a documented one, and it carries
its own review-queue permission model.

`Questionnaire` / `QuestionnaireFormSection` / `QuestionnaireFormQuestion` / `QuestionnaireFormEntry`
/ `QuestionnaireFormAnswer` is the DESIGNER's own instrument, built from the `.xlsx` pro-forma and
answered in-app. It carries **`designWorkshopId` (`schema.prisma:1034`)** and
**`DesignWorkshop.questionnaires` (`schema.prisma:1512`)** — a direct, first-class, already populated
link to the exact record the report is about. That is the source.

**Shape: an annexure, not a registry entity.** A REF stores one id and `REFERENCE_HYDRATION` copies
scalars onto the stage entry at PICK time. A questionnaire is sittings × questions, sized by the
fieldwork rather than by the registry, and hydration would have frozen the answers as they stood the
moment the designer chose the form — omitting every answer recorded afterwards, which is the whole of
the fieldwork.

**No stage-20 toggle**, and that is a decision rather than an omission: `includeTranscripts` exists
because transcripts happen automatically to a designer, whereas attaching a questionnaire to a
workshop is the designer asking for it. A toggle would also have changed `registry_version()`,
forcing a regeneration of `android/.../design-workshop-schema.json`.

**Permissions mirror the server and were not invented.** Sittings are readable by the questionnaire's
owner, an admin, or anyone working on the design workshop it is attached to
(`_works_on_this_questionnaires_workshop`, `api/routes/questionnaire_forms.py:111`). Report
generation requires `load_workshop_or_404` (`design_workshops.py:85`) — a subset of that set.

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
