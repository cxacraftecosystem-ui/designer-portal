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

### 3. Questionnaire data reaches the report by no path at all

Not in `REFERENCE_MODELS`, not referenced anywhere in the builder or the templates. The interviews
exist (`/api/questionnaire/interviews` is live and the Android client already lists them), and stage
7/8 are about the survey — so the report describes a survey whose actual responses live in a table
it never opens.

**This is the largest piece of the three and needs a design decision before code:** a questionnaire
is not a single record a REF points at, it is a set of responses. Options are a new special section
(like the annexures), a new entity in the registry, or an annexure of its own. That choice belongs
to whoever owns the report format, not to an implementer.

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
