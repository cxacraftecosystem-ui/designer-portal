# Measuring a record's dimensions: on-device geometry becomes the primary route, the vision model becomes a labelled fallback

> ## STATUS, 2026-08-27: DECIDED, AND WIRED ON BOTH CLIENTS.
>
> *This banner read "THE ANDROID ADAPTER IS BUILT AND DELIBERATELY UNWIRED" until later the same day,
> when the mounts landed. Its own re-check below is what caught it.*
>
> * **Decided:** the deterministic, on-device photo-geometry measurement is the PRIMARY route on the
>   product and tool record forms. The vision-model route is **kept**, and relabelled as the fallback
>   it now is.
> * **Built:** `android/app/src/main/java/com/designprototype/workshop/ui/RecordMeasureField.kt` — the
>   adapter that lets the existing on-device panel mount on a record form. Its tests are in
>   `android/app/src/test/java/com/designprototype/workshop/ui/RecordMeasureFieldTest.kt`.
> * **Wired**, 2026-08-27. Both mounts are in
>   `android/app/src/main/java/com/designprototype/workshop/MainActivity.kt` — one on the product form
>   and one on the tool form, each placed ABOVE `GridMeasurementSection` so the deterministic route is
>   the one reached for first, with a comment at each site saying why that order. The section *The
>   Android edit, written out* below is the patch that was applied and is no longer maintained; read
>   `MainActivity.kt` instead. Re-check with
>   `grep -rn "RecordMeasureField" android/app/src/main/java/` — one hit would mean the declaration
>   alone and a stale banner.
> * **Nothing is deleted.** `GridMeasurementSection` stays, `POST /media/analyze-measurement` stays,
>   and the server's vision path stays. This is a change of which route is offered first and of what
>   each one is called on screen.

**Decision:** on a product or tool record form, offer the deterministic measurement first and the
vision-model measurement second, and say on screen which is which. Recorded because the obvious
reading — "the AI one is the good one, the manual one is the fallback" — is backwards here for
reasons that are specific to this application and are not visible from either control.

## The two routes, side by side

| | On-device photo geometry | Vision model |
|---|---|---|
| Where the arithmetic is | `frontend/lib/photoMeasure.ts`, ported to `android/app/src/main/java/com/designprototype/workshop/data/DwPhotoMeasure.kt` | a provider's checkpoint, behind `POST /media/analyze-measurement` |
| Cost per measurement | none | a paid API call, every attempt, including the ones that misread |
| Works with no signal | **yes — there is no network call in the path at all** | no: the route is network-only. No queue, no outbox, no retry |
| Re-derivable by a later reader | **yes.** Same marks, same number, for ever | no. The same image through the same endpoint next month may legitimately answer differently, because the provider swapped a checkpoint |
| Reports how wrong it might be | **yes** — a propagated error bar, and the answer is rounded to the precision that error bar reaches | no. A bare number |
| Refuses rather than guessing | yes — a reference too short, a rectangle seen edge-on, or a mark near the horizon are each refused with the reason | no. It answers |
| Needs the designer to do something | yes: place four or six marks and type one known length | no |
| Method recorded on the row | `PHOTO_GEOMETRY`, and it may fill a field without an acceptance step | `VISION_MODEL`, and it requires acceptance by a named person |

The last row is not this document's invention. `backend/app/services/measurement_provenance.py`
declares that vocabulary and argues the asymmetry: what separates the two is not accuracy, it is what
a **later reader can do** — re-derive it, ask the person, or neither.

`MeasurementMethod.requires_acceptance` is true for the vision model alone, and its docstring says
why: *"A typed number is a person's own act — there is nothing to accept, they did it. Arithmetic is
re-runnable from the marks, so a reader who doubts it can check it. A model's estimate is neither, so
the only thing that can stand behind it is somebody's signature."*

That is the whole argument for the swap. A workshop's dimensions are multiplied into a cost sheet and
printed in a document handed to a Development Commissioner's office, and by the time anybody doubts a
figure the prototype is three districts away. A number that can be re-derived from marks on a
photograph that is still attached to the record is a fundamentally different object from a number
nobody can ever check again — and it happens to also be the free one and the one that works in a
courtyard.

## Why the good route was not already on these forms

Not because anybody chose the model. Because of an accident of where each control was mounted.

* `DwPhotoMeasurePanel`, in
  `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwPhotoMeasureField.kt`,
  is mounted by `FieldRenderer` on design-workshop STAGE fields. It is **registry-driven**: it reads
  a `FieldDto`, asks the registry's declared `unit`, and writes through an `onPropose` keyed by field
  key.
* `GridMeasurementSection`, in
  `android/app/src/main/java/com/designprototype/workshop/MainActivity.kt`, is mounted on the product
  and tool record forms. A record form has **columns**, not a registry: `lengthInches` is a property
  of a request body and a `String` in Compose state, and nothing anywhere declares that it is a
  length in inches.

So the black box is confined to exactly the two surfaces that could not speak the registry's
language, while the deterministic panel sat three files away being used on stages. **Closing that
asymmetry is a translation problem, not a measurement problem**, and it is the only thing this
decision costs.

The same split exists on the web, with the same two files:
`frontend/components/designworkshop/PhotoMeasureField.tsx` on stage fields, and
`frontend/components/media/GridMeasurement.tsx` on `frontend/components/forms/ProductForm.tsx` and
`frontend/components/forms/ToolForm.tsx`.

## The insight that makes this cheap: the grid sheet is already a reference

The vision route asks the designer to photograph the object on a **1-inch grid sheet**. A 1-inch grid
is a scale bar. The designer marks across N squares, types `N` inches once, and
`DwPhotoMeasure.measureBySameScale` returns the dimension **with its uncertainty** — no model, no
request, no per-call cost, and no signal.

So the photograph the expensive route wants is *already* a reference photograph. It only ever needed
measuring rather than inferring. And it is already in the form's own attach-media batch, because
`GridMeasurementSection` routes its grid captures into `MediaCaptureState.uris` so they are visible in
the upload list and saved on the record. The deterministic panel needs **no new capture, no new
upload and no new permission** — it reads that batch.

`DwPhotoMeasurePanel` also carries reference presets for the things a designer in this programme
actually has to hand — a scale card, a steel rule, an A4 sheet, a ₹5 coin — and the A4 sheet appears
in both the scale-bar list and the known-rectangle list, because it is the commonest of each.

## What the adapter has to supply — and the one thing it must never do

The panel takes four things. A record form can satisfy all four, and three of them are pure renaming:

| The panel wants | A stage field has | A record form has | The adapter's answer |
|---|---|---|---|
| `targets: List<DwMeasureTarget>` | registry `FieldDto`s with a declared `unit` | a column name and a Compose `String` | a synthetic `FieldDto` per column, `type = "DECIMAL"`, `unit = "in"`, `minValue = 0.0` — run through the registry's own `dwMeasurableLengthFields` so the unit test is not duplicated |
| `rowValues: Map<String, JsonElement>` | the stage row | the form's boxes | `JsonPrimitive` of each non-blank box |
| `onPropose: (String, JsonElement?) -> Unit` | `onPatch` into the draft | a Compose setter | the coerced value's own text, assigned to the box |
| `photos: List<DwMediaItem>` | already-imported files with an `absolutePath` | `content:` Uris in `MediaCaptureState.uris` | **a scratch working copy** — this is the only real cost, and it is discussed below |

**THE THING IT MUST NEVER DO IS ARITHMETIC.** `DwPhotoMeasure.kt`'s own header names
`frontend/lib/photoMeasure.ts` as the authority, and the two are pinned value-for-value against each
other. A scale factor, a pixels-per-inch, a unit conversion or a rounding computed in an adapter would
be the beginning of a **third** implementation of the same plane geometry — one that differs from the
other two silently, only in the fourth digit, in a number printed on a costing sheet nobody can
re-measure. `RecordMeasureField.kt` therefore contains no arithmetic on a length whatsoever: the
inches↔millimetres conversion is `DwPhotoMeasure.convertLength` inside the panel, and the rounding is
`DwPhotoMeasure.roundToUncertainty`, also inside the panel.

The unit test is the same one map, too. `dwRecordMeasureTargets` builds `FieldDto`s and then hands
them to `dwMeasurableLengthFields`, which asks `DwPhotoMeasure.LENGTH_UNITS` — the very map the
conversion goes through — rather than deciding for itself. That round trip looks redundant and is the
whole safety property: without it, a unit the module cannot convert could become a destination on
record forms while remaining impossible on stages.

### The one genuine cost: a decodable copy

`DwImageDecode.decodeForDisplay` takes a filesystem path, because it is `BitmapFactory.decodeFile`
underneath. A stage field always has one — `WorkshopDraftStore.importMedia` has already copied every
attachment into the workshop's media directory. A record form has not: `MediaCaptureState.uris` holds
whatever the picker or the camera intent returned, and a `content:` Uri is a permission grant rather
than a file.

So the adapter makes a **scratch** copy under `cacheDir`, and it is deliberately weaker than the two
durable copiers this repository already has. `WorkshopDraftStore.importMedia` and
`OfflineQueue.stageMedia` hash, `fd.sync()` and write a descriptor, because the bytes they hold are
the only copy of a photograph nobody can re-take. These bytes are not: the original is still in the
batch and is still what gets uploaded. Spending an fsync per photograph — seconds, on the storage the
camera is also writing to — to protect bytes that are already safe elsewhere would be cargo-culting
the discipline rather than applying it. A `file:` Uri is used where it lies and never copied at all,
a Uri is resolved once rather than once per attachment, and the copies are deleted when the form
leaves composition.

### It proposes; it does not write

Inherited, not re-implemented. `DwPhotoMeasurePanel` already ends every path at a button that names
the figure it will write, refuses to show any reading until every mark has been moved, and warns
"Currently X. This replaces it." The adapter adds no write path of its own.

Note that this is *stricter than the law requires* for this method, and stays that way on purpose:
`MeasurementMethod.PHOTO_GEOMETRY` may fill a field with no acceptance step, because it is
reproducible. The button is here for a different reason, which the panel states itself — the moment
the figure lands in a column its error bar is gone for ever, because the schema has a column for the
dimension and none for the doubt. So the doubt is spent on screen, while somebody can still act on it.

## The Android edit, written out

> **APPLIED 2026-08-27. NOT MAINTAINED FROM HERE ON.** This section is a patch, and the table in *How
> this document is kept true* says a patch is true until it lands. It has landed, so it is kept as the
> record of what was applied and what it was anchored on — not as a description of the file. The tool
> form additionally gained a "Height (inches)" box and its `onHeight` moved off the unit-less column,
> neither of which is written out below because both came later the same day. **Read `MainActivity.kt`.**

Three edits, all in
`android/app/src/main/java/com/designprototype/workshop/MainActivity.kt`. Anchored on source text
rather than line numbers, because that file is long and moves.

**1. The import**, beside the other `com.designprototype.workshop.ui.*` imports:

```kotlin
import com.designprototype.workshop.ui.PRODUCT_MEASURE_DIMENSIONS
import com.designprototype.workshop.ui.RecordMeasureField
import com.designprototype.workshop.ui.TOOL_MEASURE_DIMENSIONS
```

**2. `ProductForm`.** Immediately BEFORE the existing `GridMeasurementSection(` call that follows
`TextInput("Height (inches)", height, …)`:

```kotlin
        // THE DETERMINISTIC ROUTE, FIRST — see docs/DECISION-photo-geometry-over-vision-measurement.md.
        // It measures off any photograph already attached to this record (the grid shots included, a
        // 1-inch grid being a perfectly good scale bar), runs entirely on this device, costs nothing
        // per reading, and reports how wrong it might be. The grid section below it is the fallback.
        RecordMeasureField(
            dimensions = PRODUCT_MEASURE_DIMENSIONS,
            current = mapOf(
                "lengthInches" to length,
                "breadthInches" to breadth,
                "heightInches" to height,
            ),
            photos = media.uris,
            purposes = media.purposes,
        ) { column, text ->
            // ASSIGNED VERBATIM, NOT THROUGH `numToText`. The panel rounds to the precision its own
            // error bar reaches, and `numToText(12.0)` is "12" — which upgrades a claim about a tenth
            // of an inch into a claim about an inch. See `dwRecordProposalText`.
            when (column) {
                "lengthInches" -> length = text
                "breadthInches" -> breadth = text
                "heightInches" -> height = text
            }
        }
```

**3. `ToolForm`.** Immediately BEFORE the existing `GridMeasurementSection(` call that follows
`TextInput("Radius", radius, …)`:

```kotlin
        // As on the product form. TWO dimensions and not three: the tool's Height box is bound to the
        // unitless `ToolCreateRequest.height`, and this handset cannot yet send `heightInches`. See
        // TOOL_MEASURE_DIMENSIONS for the argument and RecordMeasureFieldTest for the test that fails
        // the day that stops being true.
        RecordMeasureField(
            dimensions = TOOL_MEASURE_DIMENSIONS,
            current = mapOf(
                "lengthInches" to length,
                "breadthInches" to breadth,
            ),
            photos = media.uris,
            purposes = media.purposes,
        ) { column, text ->
            when (column) {
                "lengthInches" -> length = text
                "breadthInches" -> breadth = text
            }
        }
```

**4. Relabel the fallback**, so a designer can tell the two apart. In `GridMeasurementSection`, the
heading `Text("Document using grid", …)` becomes `Text("Document using grid — needs a connection", …)`
and the hint paragraph beneath it gains a sentence pointing at the control above it:

> If you have no signal, or you would rather have a figure with an error bar on it, use "Measure from
> a photograph" above — it does the same job on this device with no connection and at no cost.

## What this deliberately does not do

Four things, each of which was blocked on a file this workstream does not own. **Two of the four —
items 1 and 2 — closed on 2026-08-27, the day they were written**, and are struck through rather
than deleted because the sequence is the point. Items 3 and 4 are the ones still standing; each line
names the command that re-checks it.

1. ~~**It does not send a method marker.**~~ **CLOSED, 2026-08-27, the same day it was written**
   (struck through when the miss was caught, later than item 2 was). What it used to say was:
   *"An accepted reading still saves with no `measurementMethods` on the body, which the server reads
   as `UNRECORDED` … Neither step has landed: `grep -rn measurementMethods
   backend/app/services/access.py backend/app/schemas/records.py` returns nothing."* Both steps have
   landed, in the prescribed order — which is fixed and is not the obvious one:
   `access.REVISION_SKIP_FIELDS` must gain the key FIRST, then the four schemas, then either client,
   because a sendable key with no skip entry writes a `RecordRevision` nobody made into an
   append-only table. `MARKER_BODY_KEY` is in that set in `backend/app/services/access.py`, all four
   of `ProductCreate` / `ProductUpdate` / `ToolCreate` / `ToolUpdate` declare the field in
   `backend/app/schemas/records.py`, and BOTH clients now send it — `measurementMethods =
   markers.body(…)` twice in `MainActivity.kt` (the product save and the tool save), and
   `measurementMethodsFor(…)` from `frontend/components/forms/measurementMethods.ts` on both web
   forms. The command above is still the re-check; run on 2026-08-27 it answered ten lines, not
   nothing.
2. ~~**A tool's height still has nowhere honest to land on this handset.**~~ **CLOSED, 2026-08-27,
   the same day it was written.** Every clause of this item has since landed, and it is struck through
   rather than deleted because the sequence is the point: the column arrived first, then the wire, then
   the screen, and the item was accurate at each step until the next one shipped. What it used to say
   was outstanding — *"`MainActivity.kt`'s tool form still draws only \"Height\", `TOOL_MEASURE_DIMENSIONS`
   still lists two dimensions, and `GridMeasurementSection`'s `onHeight` still writes into the unit-less
   column"* — is now false in all three parts: the form draws a "Height (inches)" box beside Length and
   Breadth, `TOOL_MEASURE_DIMENSIONS` lists the full triple, and `onHeight` writes `heightInches`. The
   unit-less `height` box stays, still saves, and now has no machine writer on either client — which is
   the whole point of the pair. Re-check with `grep -n "TOOL_MEASURE_DIMENSIONS" -A 5
   android/app/src/main/java/com/designprototype/workshop/ui/RecordMeasureField.kt` (three entries) and
   `grep -n "onHeight = {" android/app/src/main/java/com/designprototype/workshop/MainActivity.kt`.

3. **The fallback's failures are still indistinguishable.** `GridMeasurementSection` collapses "no
   connection" and "the provider refused" into one `Analysis failed — enter it manually`, though the
   server distinguishes them — a 503 names the missing setting. That is a defect by this
   repository's own rule and it is in a locked file.
4. **The grid photograph is never re-read when signal returns.** The evidence survives (it is in the
   media batch); the analysis is simply lost. Deliberate on the server's side, and a client decision
   nobody has taken.

## How this document is kept true

**This is a decision record.** The comparison table and the argument are dated prose about a trade
made on 2026-08-27, and they are not to be edited into agreement with later code — add a block on top
if the decision is overturned, as `docs/DECISION-qr-scanning-on-android.md` does twice.

| Claim class | Kept true by |
|---|---|
| The decision and the argument for it | **Nothing, on purpose.** Overturn it in a new block rather than by editing this one. |
| "The adapter is built and wired" | `grep -rn "RecordMeasureField" android/app/src/main/java/`. Hits in BOTH `RecordMeasureField.kt` and `MainActivity.kt` mean it is mounted; hits in the declaration alone would mean the mounts were reverted and the status banner is stale. |
| The properties claimed of each route | `android/app/src/main/java/com/designprototype/workshop/data/DwPhotoMeasure.kt` and `frontend/lib/photoMeasure.ts` for the geometry; `backend/app/api/routes/media.py` for the vision route. The METHOD vocabulary is owned by `backend/app/services/measurement_provenance.py` and quoted here — change it there. |
| The four outstanding items | Each carries its own re-check command in *What this deliberately does not do*. Run them before repeating anything from that section — items 1 and 2 are struck through because running theirs is what closed them. |
| The mount code in *The Android edit, written out* | **Not maintained. It was applied on 2026-08-27.** It is a patch, and a patch is true until it lands. Read `MainActivity.kt` rather than this section — the tool form has since gained a "Height (inches)" box and a redirected `onHeight` that the patch below does not mention. |

**Review triggers:** any change to `DwPhotoMeasure.kt` or `photoMeasure.ts`; the
`measurementMethods` schema landing; `heightInches` reaching `ApiModels.kt`; a change of provider or
pricing behind `POST /media/analyze-measurement`.

> **TWO OF THESE FIRED ON 2026-08-27 AND THE REVIEW WAS DONE.** `heightInches` reached
> `ApiModels.kt`, and the `measurementMethods` schema landed: `MARKER_BODY_KEY` is in
> `access.REVISION_SKIP_FIELDS` and all four of `ProductCreate` / `ProductUpdate` / `ToolCreate` /
> `ToolUpdate` declare the field — in the prescribed skip-list-then-schema order. The review they
> triggered is what closed outstanding item 2 above and rewrote this document's status banner; item
> 1, whose whole subject is that same schema landing, was missed in that pass and struck through
> afterwards — the trigger fired and the list did not follow it. A fired trigger is recorded here
> rather than left in the list, because one that has fired and says nothing reads exactly like one
> that has not.

**Known unverified:** no on-hardware timing was taken for the working-copy decode on a record form
specifically. The figure the stage panel quotes for the same decode — a few hundred milliseconds,
approaching a second on a loaded handset — is what to expect, and it is measured in
`DwImageDecode`'s own header rather than here.
