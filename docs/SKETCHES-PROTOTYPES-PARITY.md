# Sketches & Prototypes: the client-by-client parity matrix

**What a designer can do with a sketch or a prototype, on each client, with the code that says so.**

Scope is one feature: stage 11 (`SKETCH_DEVELOPMENT`), stage 13 (`PROTOTYPE_DEVELOPMENT`), and the
chooser screen each client puts in front of them. First read as of **2026-08-27**; last swept
**2026-08-31**.

**This page is live, not a snapshot.** Rows carry their own `Verified «date»; re-check with «grep»`
stamps and those dates differ — a row is as old as its own stamp, never as old as this line. Two
things moved under it since the first read, and both are recorded where they happened rather than
here: the web gained PDF/EPS/DXF export (Matrix B, verdict flipped from a gap to **BOTH**), and the
`isTentative` flag shipped on both clients (Matrix A, a new row). A row with no stamp has not been
re-read since 2026-08-27.

---

## Why this page exists

Every audit of this repository has re-derived this comparison from scratch, and the register that
tried to hold it — §16 of `.claude/skills/field-repo-frontend/SKILL.md` — is a **dashboard tile**
list, not a feature matrix. Its own text records what that costs: it "carried eleven tiles under
'never invent a label', omitting six that already existed, so the honest reading of a missing tile
was 'this tile is not expected' — and a page whose tile was never added reads to its owner as a page
that was never built." The same section had to be rewritten again when a paragraph saying "there is
no ratings code anywhere under `android/app/src/main`" turned out to be false.

An over-claim in either direction is expensive here, and the expense is asymmetric:

* **Claiming a gap that does not exist** sends whoever reads it to build a second implementation of
  something the handset already ships, which is how one feature comes to have two stores.
* **Claiming parity that does not exist** ships a designer to a district with a phone that cannot do
  the thing they were told it could, and they find out with the sheet of paper in their hand.

So this page states the comparison once, cites the code for every row, and is pinned by
`frontend/e2e/sketches-parity-matrix-unit.spec.ts` so that a symbol it names cannot disappear
silently.

---

## How to read a row

**A row asks what a designer can DO on that client** — so a cell names the surface that offers the
capability, not only the arithmetic behind it. A module sitting in the tree that nothing mounts is
not a capability, and the corner-guess row below is exactly that case today.

**Verdicts.** One of five, and nothing else:

| Verdict | Means |
|---|---|
| **BOTH** | A designer can do it on either client. The shape may differ; the capability does not |
| **WEB ONLY (deliberate)** | Absent from the handset, and a comment in the tree argues for the absence. The argument is quoted below |
| **WEB ONLY (gap)** | Absent from the handset, and nothing in the tree records a decision about it |
| **ANDROID ONLY (deliberate)** | The mirror of the second row. **Two rows use it** — the cost refusal in Matrix B and the new-row affordance in Matrix D. This legend said "no row in this matrix uses it" until 2026-08-31, while the page itself named one of them at "The chooser is a chooser" |
| **ANDROID ONLY (gap)** | The mirror of the third row. No row uses it as of 2026-08-31 — the one that did, PDF/EPS/DXF export in Matrix B, was closed that day |

**Citations.** A pin is written `` `path#Symbol` `` — a repository path, then the name of something
declared in it. **Paths and symbol names, never line numbers**, because the pins in
`docs/REPORT-DATA-WIRING.md` had to be removed when they rotted onto unrelated code, and that episode
is what produced the checker's citation-drift test. Every pin on this page is verified to exist, in
code rather than in a comment, by the spec named above.

**A cell saying a client does not have something carries a date and a re-check command**, because an
absence is a claim about the state of the world, and this repository's rule is that such a claim is
worthless without one command that settles it.

---

## Matrix A — the sketch, and the plate made from it (stage 11)

| What a designer can do | Web | Android | Verdict |
|---|---|---|---|
| Attach the photograph of the sheet to `sketch.image` | `frontend/components/designworkshop/FieldInput.tsx#MediaField` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwMediaCapture.kt#DwMediaCaptureCard` | **BOTH** |
| Be offered the straightening panel on the destination FILE field, never on the photograph | `frontend/components/designworkshop/stageFieldRoles.ts#offersSketchRectify` · `frontend/components/designworkshop/stageFieldRoles.ts#sketchSourceFields` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyField.kt#dwOffersSketchRectify` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyField.kt#dwSketchSourceFields` | **BOTH** |
| Correct the perspective of a sheet photographed at an angle | `frontend/lib/sketchRectify.ts#rectifyHomography` · `frontend/lib/sketchRectify.ts#rectifyPlane` · `frontend/lib/sketchRectify.ts#orderCorners` · `frontend/lib/sketchRectify.ts#rectifiedSize` · `frontend/lib/sketchRectify.ts#bilinearSample` | `android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt#rectifyHomography` · `android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt#rectifyPlane` · `android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt#orderCorners` · `android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt#rectifiedSize` · `android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt#bilinearSample` | **BOTH** |
| Extract line art by local thresholding — black pencil, white paper | `frontend/lib/sketchRectify.ts#sauvolaThreshold` · `frontend/lib/sketchRectify.ts#sauvolaWindow` · `frontend/lib/sketchRectify.ts#integralPlanes` | `android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt#sauvolaThreshold` · `android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt#sauvolaWindow` · `android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt#integralPlanes` | **BOTH** |
| Straighten and keep the tones, refusing the threshold | `frontend/lib/sketchRectify.ts#PlateOptions` · `frontend/lib/sketchRectify.ts#makePlate` | `android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt#DwPlateOptions` · `android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt#makePlate` | **BOTH** |
| Say the sheet was A-series when the pixels cannot tell | `frontend/components/designworkshop/SketchRectifyField.tsx#ASPECTS` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyField.kt#SHEET_SHAPES` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyField.kt#DwSheetShape` | **BOTH** |
| Place a corner without a pointer — arrow keys on one, nudge buttons on the other | `frontend/components/designworkshop/SketchRectifyField.tsx#ArrowLeft` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyField.kt#DwSketchNudgePad` | **BOTH** |
| See the plate before anything is attached, and decline it | `frontend/components/designworkshop/SketchRectifyField.tsx#SketchRectifyField` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyField.kt#DwSketchRectifyPanel` | **BOTH** |
| Have the approved plate encoded and filed as a second artifact, leaving the photograph untouched | `frontend/lib/sketchRectify.ts#encodePlatePng` · `frontend/lib/sketchRectify.ts#greyToRgba` · `frontend/lib/sketchRectify.ts#derivedPlateName` | `android/app/src/main/java/com/designprototype/workshop/data/DwSketchPlate.kt#platePng` · `android/app/src/main/java/com/designprototype/workshop/data/DwSketchPlate.kt#bitmapOf` · `android/app/src/main/java/com/designprototype/workshop/data/DwSketchPlate.kt#greyPlaneOf` | **BOTH** |
| Have a button propose where the sheet is, and refuse to propose when it cannot tell | `frontend/components/designworkshop/SketchRectifyField.tsx#guessSheetCorners` · `frontend/lib/sketchRectify.ts#GUESS_MIN_FILL` · `frontend/lib/sketchRectify.ts#GUESS_MIN_FRAME_SHARE` · `frontend/lib/sketchRectify.ts#GUESS_MIN_EDGE_SUPPORT` · `frontend/lib/sketchRectify.ts#GUESS_MIN_CONTRAST` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyField.kt#dwGuessSheetCorners` over `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyGuess.kt#dwGuessSheetCorners` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyGuess.kt#DW_GUESS_MIN_FILL` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyGuess.kt#DW_GUESS_MIN_FRAME_SHARE` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyGuess.kt#DW_GUESS_MIN_EDGE_SUPPORT` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyGuess.kt#DW_GUESS_MIN_CONTRAST` | **BOTH** |
| Mark a sketch **tentative**, and have it come to the top of every list | `frontend/lib/sketchTentative.ts#TENTATIVE_FIELD_KEY` · `frontend/lib/sketchTentative.ts#tentativeField` · `frontend/lib/sketchTentative.ts#isTentativeRow` · `frontend/lib/sketchTentative.ts#tentativeFirst`, read by `frontend/components/sketches/UploadTabHost.tsx#UploadTabHost` and `frontend/components/sketches/ReviewPanel.tsx#ReviewPanel` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserRows.kt#DW_TENTATIVE_FIELD_KEY` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserRows.kt#dwTentativeField` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserRows.kt#dwIsTentativeRow` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserRows.kt#dwTentativeFirst`. Neither client transcribes the word: both read the label off the registry field. Verified 2026-08-31; re-check with `grep -rn "isTentative" frontend/lib android/app/src/main/java` | **BOTH** |

The corner-guess row **changed on 2026-08-27, while this page was being written** — see "The corner
guess" below, which is worth reading before anyone quotes the argument that used to sit against it. The
capability is the same on both clients; what a press DOES is not. The browser moves the four handles
onto the proposal; the handset draws the proposal and waits for a second press before anything moves.

**The tentative row records a shape the owner chose on 2026-08-30, not merely a capability.** The
instruction read two ways — an additional *Tentative Sketches* upload field, or a flag on the
existing row — and it was settled as one checkbox per sketch. `stage_definitions.py` carries the
argument: a second gallery would be two lists over one concept, and every reader of sketches (the
report's figure block, the design review's ranking, the chooser, both on-device report writers) would
then have to decide which list it meant, forever. Do not "restore" the second gallery. The flag is
`report_role=HIDDEN` deliberately — it is a working state, and printing "Tentative: Yes" in a
ministry document would put a designer's private hedging into the record — while the ORDER it
produces does reach the report, because the report reads rows in the order the stage holds them.

The row that gets misread most often is the line-art one, and it is misread in both directions.
**"Line art" here is a local threshold, not a vector trace** — a raster plate, black on white,
produced on the handset by exactly the operation the browser performs beside it. It is genuine parity,
and it is not Matrix B.

---

## Matrix B — tracing a photograph into curves (stage 11's line-art slot)

**Rewritten 2026-08-28. Every row of this matrix used to read WEB ONLY (gap) and every one of them
was wrong by the time it was read.** The handset's trace surface landed after this page was written,
and the engine under it was replaced a day later: the JavaScript bundle the handset ran in an
`androidx.javascriptengine` isolate is deleted, and `android/core-imaging`, `core-vector`,
`core-pipeline` and `core-export` — the same upstream engine vendored as Kotlin, hashed in
`android/UPSTREAM-MANIFEST-KOTLIN.txt` — are compiled into the APK. The two vendorings are held to
each other by `:core-pipeline`'s own `ParityTest`, which replays the shared fixtures under
`docs/fixtures/` through the Kotlin and asserts the TypeScript's numbers.

| What a designer can do | Web | Android | Verdict |
|---|---|---|---|
| Trace a photograph into vector curves | `frontend/components/sketches/upload/SketchTraceField.tsx#SketchTraceField` · `frontend/lib/trace/traceClient.ts#Tracer` · `frontend/lib/trace/spawnTraceWorker.ts#spawnTraceWorker` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTracePanel.kt#DwSketchTracePanel` over `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwTraceKotlinRuntime.kt#DwTraceKotlinRuntime`, mounted where `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTracePanel.kt#dwOffersSketchTrace` says so | **BOTH** |
| Choose a style or subject preset, then open every control behind it | `frontend/components/sketches/upload/traceParamTable.ts#PARAM_COUNT` · `frontend/components/sketches/upload/traceParamTable.ts#SLIDERS` · `frontend/components/sketches/upload/traceParamTable.ts#TOGGLES` · `frontend/components/sketches/upload/traceParamTable.ts#CHOICES` · `frontend/components/sketches/upload/traceParamTable.ts#ESSENTIAL_KEYS` · `frontend/components/sketches/upload/traceRuntime.ts#loadTracePresets` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceParams.kt#DW_TRACE_PARAM_COUNT` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceParams.kt#DW_TRACE_SLIDERS` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTracePresets.kt#DwTraceStylePicker` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTracePresets.kt#DwTraceSubjectPicker`. Neither client transcribes the preset names: both read `Styles.ALL`/`Subjects.ALL` from the engine — `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwTraceKotlinPresets.kt` records the two registers' measured differences | **BOTH** |
| Crop what the trace reads, without touching the photograph | `frontend/components/sketches/upload/FramePanel.tsx#FramePanel` · `frontend/lib/trace/imageEdit.ts#cropPixels` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceCropPanel.kt#DwTraceFramePanel` over `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceCrop.kt#dwTraceCropRgba` | **BOTH** |
| Sharpen what the trace reads, with a threshold | `frontend/lib/trace/imageEdit.ts#SharpenSettings` · `frontend/lib/trace/imageEdit.ts#sharpenPixels` · `frontend/lib/trace/imageEditClient.ts#ImageEditor` | The pipeline's own sharpener is on both clients — `preprocess.unsharpAmount` and `preprocess.unsharpSigma`, offered as two sliders under `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceParams.kt#DW_TRACE_GROUP_SHARPEN`. **The THRESHOLD is not:** it belongs to the web's separate pre-trace image editor, which passes it to the vendored four-argument `unsharpMask`. That function is in the APK (`android/core-imaging/src/main/java/com/offlinetracer/imaging/Contrast.kt#Contrast`) and nothing calls it with a non-zero threshold, because the handset has no image-editor lane. Verified 2026-08-28; re-check with `grep -rn "unsharpThreshold\|SharpenSettings" android/app/src/main/java` | **WEB ONLY (gap)** |
| Choose the resolution the trace runs at | `frontend/components/sketches/upload/traceParamTable.ts#PARAM_COUNT` (the `preprocess.workingLongEdge` row) | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceParams.kt#DW_TRACE_NUMBER_CHOICES` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwTraceKotlinParams.kt#dwTraceParamsOf`. The handset additionally DISABLES an option above what this device has been measured for, rather than hiding it — `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceEngine.kt#DwTraceAvailability` | **BOTH** |
| Be refused a run this device cannot finish, with the remedy named | Absent, and reasonably so — a laptop is not refused. Verified 2026-08-28; re-check with `grep -rn "maxWorkingLongEdge\|costRefusal" frontend/components/sketches frontend/lib/trace` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTracePanel.kt#dwTraceCostRefusal` bars a resolution or an edge engine above the device's ceiling; `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwTraceKotlinRuntime.kt#dwTraceKotlinMemoryRefusal` measures the heap after the decode and before the first stage and refuses in a sentence naming both figures. **Neither ceiling has been measured on a handset** — `DwTraceAvailability.measuredOn` is null and the panel says so | **ANDROID ONLY (deliberate)** |
| Drag a divider between the photograph and the traced drawing | `frontend/components/sketches/upload/comparisonPlates.ts#buildComparisonPlates` · `frontend/components/ui/reveal1.tsx#Reveal1` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceCompare.kt#DwSketchTraceCompare` over `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTracePlates.kt#DwSketchTracePlates` | **BOTH** |
| Save the drawing to the device as SVG or PNG | `frontend/components/sketches/upload/traceExport.ts#EXPORT_FORMATS` · `frontend/components/sketches/upload/traceExport.ts#exportSvgFile` · `frontend/components/sketches/upload/traceExport.ts#exportPngFile` · `frontend/components/sketches/upload/geometryToSvg.ts#buildSvg` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceExport.kt#DW_TRACE_EXPORT_FORMATS` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceExportCard.kt#DwSketchTraceExportCard` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceExportFile.kt#dwSaveTraceExport` | **BOTH** |
| Save the drawing as a PDF, an EPS or a DXF | Offered, since 2026-08-31: `frontend/components/sketches/upload/traceExport.ts#EXPORT_FORMATS` carries all five ids, the worker's flat geometry is rehydrated into a `VecDocument` by `frontend/components/sketches/upload/geometryToDocument.ts#documentFrom`, and `frontend/lib/trace/engine/exportFormats.ts#exportDocument` writes it. The three are download-only — `attachable` is false on each, because the record is a shared archive the handset reads. Verified 2026-08-31; re-check with `grep -n "id:" frontend/components/sketches/upload/traceExport.ts` — fewer than five rows means one was withdrawn | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwTraceKotlinExporter.kt#DwTraceKotlinExporter` calls `:core-export`'s three writers directly, through `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwTraceKotlinExporter.kt#dwTraceKotlinDocumentOf` | **BOTH** |

**That row read `ANDROID ONLY (gap)` from 2026-08-27 to 2026-08-31, and it is the only gap this page
has recorded being CLOSED.** Its argument is kept rather than deleted, because the diagnosis was
right and is the reason the fix was cheap: both vendorings carried all four vector writers and only
the handset offered three of them, so `EXPORT_FORMATS` was "a statement about the portal's panel and
not about the portal's engine", and closing it would be "a change to that table plus a reachable
`exportDocument`, not a port".

That is what shipped. The one thing the row did not name is the piece that actually cost anything:
the worker sends geometry as six flat typed arrays and `exportDocument` takes a `VecDocument`, so the
missing part was an ADAPTER — `frontend/components/sketches/upload/geometryToDocument.ts#documentFrom`, about sixty lines, the exact
inverse of the worker's serialiser. `traceExport.ts`'s own header records that an earlier audit
finding read "adding them is a table entry each plus a button" and missed it: "the table entry and
the button are real and are the cheap half; without the adapter they attach to nothing." **A row that
names the writers as present is not yet a row that says the capability is one edit away.**

Two losses on the handset's three, measured in the vendored writers rather than assumed:
`EpsWriter.kt` writes `%%Creator: Offline Tracer` outside the `includeMetadata` guard, so an EPS
carries the engine vendor's name in one header comment and no option removes it (the PDF and the DXF
are clean); and `ExportOptions` has no title field, so `DwTraceExportRequest.provenanceNote` is
carried and dropped for all three rather than reaching `/Title` and `%%Title:` as its own KDoc
claims. `DwTraceKotlinExporterTest` pins both.

---

## Matrix C — the prototype (stage 13)

| What a designer can do | Web | Android | Verdict |
|---|---|---|---|
| Attach a 3D model file and a turntable series | `backend/app/services/stage_definitions.py#modelFile` · `backend/app/services/stage_definitions.py#turntablePhotos`, rendered by `frontend/components/designworkshop/FieldInput.tsx#MediaField` | the same registry, shipped as `android/app/src/main/assets/design-workshop-schema.json#modelFile` · `android/app/src/main/assets/design-workshop-schema.json#turntablePhotos`, rendered by `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwMediaCapture.kt#DwMediaCaptureCard` | **BOTH** |
| Be told, before choosing, that the printed report draws the turntable and only names the model | `frontend/components/sketches/upload/PrototypeModelField.tsx#PrototypeModelField` · `frontend/components/sketches/upload/PrototypeModelField.tsx#MODEL_FORMATS` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt#DW_PROTOTYPE_3D_IN_THE_REPORT` | **BOTH** |
| Be told what a turntable needs to be worth printing | `frontend/components/sketches/upload/PrototypeModelField.tsx#TURNTABLE_MINIMUM` · `frontend/components/sketches/upload/PrototypeModelField.tsx#TURNTABLE_COMFORTABLE` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt#DW_TURNTABLE_CAPTURE_ADVICE` | **BOTH** |

The last two rows are **guidance, and the two clients put it in different places** — a panel above the
file picker on the web, sentences on the chooser on the handset. What they are telling the designer is
a fact neither client can change: `backend/app/services/report_builder.py` places media on the page by
`IMAGE` and `IMAGE_LIST` only, so a `.glb` reaches an officer as a count and a noun.

---

## Matrix D — getting to stages 11 and 13

| What a designer can do | Web | Android | Verdict |
|---|---|---|---|
| Open stage 11 or 13 from inside a workshop | `frontend/app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx#DesignWorkshopStagePage` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/StageScreen.kt#StageScreen` | **BOTH** |
| Reach the two stages without remembering which workshop they are in | `frontend/app/(protected)/sketches-and-prototypes/page.tsx#SketchesAndPrototypesHubPage` · `frontend/components/sketches/SketchesWorkspace.tsx#SketchesWorkspace` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt#SketchesAndPrototypesScreen` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt#SKETCH_STAGE` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt#PROTOTYPE_STAGE` | **BOTH** |
| Find that destination in the menu, behind the same predicate | `frontend/components/DynamicIslandNav.tsx#NAV_ITEMS` · `frontend/lib/permissions.ts#ROUTE_GUARDS` | `android/app/src/main/java/com/designprototype/workshop/ui/AppNavigation.kt#SKETCHES_AND_PROTOTYPES` | **BOTH** |
| Tell "you are on none" apart from "I could not ask" | `frontend/app/(protected)/sketches-and-prototypes/page.tsx#ChooseWorkshopThenSketches` · `frontend/lib/offline.ts#isUnreachable` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt#DW_SKETCH_CHOOSER_NO_WORKSHOPS` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt#DW_SKETCH_CHOOSER_OFFLINE` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt#DW_SKETCH_CHOOSER_REFUSED` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt#DW_SKETCH_CHOOSER_NOTHING_LOST` | **BOTH** |
| Attach a file to a chosen sketch or prototype without opening its stage | `frontend/components/sketches/UploadTabHost.tsx#UploadTabHost` · `frontend/components/sketches/stageRows.ts#readStageRows` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserUpload.kt#DwSketchChooserUploadTab` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserRows.kt#dwChooserAppendRow` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserRows.kt#dwChooserWriteMedia` | **BOTH** |
| Pick which workshop, once, instead of scanning a list of all of them | `frontend/app/(protected)/sketches-and-prototypes/page.tsx#ChooseWorkshopThenSketches` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt#SketchesAndPrototypesScreen` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserRows.kt#dwChooserDefaultWorkshop` | **BOTH** |
| Work under an Upload tab and a Review tab | `frontend/components/sketches/SketchTabs.tsx#SketchTabs` · `frontend/components/sketches/SketchesWorkspace.tsx#SketchesWorkspace` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserTabs.kt#DwSketchChooserTabStrip` · `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserReview.kt#DwSketchChooserReviewTab` | **BOTH** |
| Add a NEW sketch or prototype row without opening its stage | `frontend/components/sketches/UploadTabHost.tsx#UploadTabHost` picks an existing row and does NOT add one. Verified 2026-08-28; re-check with `grep -n "Add a sketch\|Add a prototype" frontend/components/sketches/UploadTabHost.tsx` | `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserRows.kt#dwChooserNewRow` | **ANDROID ONLY (deliberate)** |

Only the handset gives its list answers **names**, which is why only the handset can pin them:
`android/app/src/test/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserSentenceTest.kt`
asserts that the empty-state sentence is the only one entitled to send anybody to an administrator.
The browser writes the same answers as inline strings and nothing holds them apart. Naming them is the
cheaper half of closing that, and it is a web change, which ships in one push.

---

## The arguments behind the gaps, and where each one stands

One is settled, one closed on the day this page was written, and one was never written down at all.

### The chooser is a chooser

`android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt`:

> "A sketch is a `DwSketch` row under stage 11 and a prototype is a `DwPrototype` row under stage 13.
> That is where their fields, their plates, their captions and their report figures are filed, and
> `InlineRecordDialog`'s header already refuses the obvious alternative by name: there must not be 'a
> second, parallel way to add a prototype'. A screen here that let a designer add a sketch would be
> one feature with two stores, and the one it wrote to would be the one the report did not read."

**Quote it for exactly what it says.** It refuses a second **store**, not a second control. The web's
upload tab was never the thing it forbade — `frontend/components/sketches/UploadTabHost.tsx` picks an
existing row and writes through the same draft store the stage form uses.

**AND ON 2026-08-28 THE HANDSET TOOK THE SAME SHAPE.** This paragraph used to end "the answer is no.
**Do not turn the chooser into an editor**; the parity spec asserts that it mounts no capture card",
and both halves of that are now out of date. The owner asked for the capability in terms —
*"Provide an option to add a Sketch or Prototype directly to the selected workshop from this screen …
A workshop can contain multiple sketches and multiple prototypes"* — and the screen was rewritten
around a single workshop with **Upload** and **Review** tabs, which is the web's own shape.

**The refusal above is honoured rather than overruled, and here is the check.** Every write from the
Upload tab goes to the one place a sketch has always lived:

* the row through `WorkshopDraftStore.updateStage`, whose own KDoc reads *"This is what a stage
  screen should call"*, into the same `StageDraft.rows` the stage form edits;
* the bytes through `WorkshopDraftStore.importMedia`, into the workshop's own media directory,
  exactly as `StageScreen`'s bridge does;
* the hop to the repository through `WorkshopSyncEngine.pushStage`, the one place a stage becomes a
  payload.

There is no parallel collection, no second table and no new endpoint. A sketch added from the tab is
byte for byte the row a designer would have made by walking to stage 11, which is why `ReportFigures`
finds it.

**The spec that guarded this changed with it, and the old assertion would have passed anyway** —
which is the part worth remembering. It read `SketchesAndPrototypesScreen.kt` and failed if that file
named `DwMediaCaptureCard`; the capture card now lives in `DwSketchChooserUpload.kt`, a file the test
never opened. A green run would have gone on reporting about a screen that no longer exists in that
shape. It now asserts the property the rule was always about: **writes through the ONE path, and
mints no second store.**

One thing the handset does that the web does not: it can **add a new row**, where the web can only
pick an existing one. That is the single `ANDROID ONLY (deliberate)` row in Matrix D, and it is
deliberate because the alternative on a phone is telling a designer with a drawing in their hand to
go and open a stage form first.

### The corner guess — a gap that closed while this page was being written

**Do not quote the argument that used to sit here.** For most of this repository's life,
`android/app/src/main/java/com/designprototype/workshop/data/DwSketchRectify.kt` argued the omission
at length, and its header still carries the argument, because the argument set a condition rather
than closing a door:

> "The web module carries an AUTOMATIC CORNER GUESS … It is NOT here, and the web module's own comment
> says why it can be left out without taking the feature with it: 'THE MANUAL PATH IS THE REAL FEATURE
> AND THIS IS A CONVENIENCE ON TOP OF IT.' … If it is ever wanted, it goes in as a whole with its
> gates and its tests, or not at all; a half-ported guess with the gates left off is the one version
> that must never exist."

On **2026-08-27** the condition was met.
`android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyGuess.kt`
ports the whole of it — Otsu, the morphological closing, the flood fill, the diagonal extremes and
every confidence gate — the panel calls it, and
`android/app/src/test/java/com/designprototype/workshop/ui/designworkshop/DwSketchRectifyGuessTest.kt`
carries the web spec's cases across. The row is **BOTH**.

**The handset asks twice and the browser asks once, deliberately.** A guess that fires on the web
moves the four handles; on the handset it draws an outline and a percentage, and nothing moves until
"Use these corners" is pressed. Both clients refuse to propose at all below the gates, which is the
property the gates exist for: "A wrong quadrilateral presented as a suggestion is worse than no
suggestion, because dragging four handles back off a confident wrong answer is more work than placing
them from nothing."

**This row is why the page has a test.** The written argument for the absence, the port, the wiring
and the test all existed within one day of each other, and for part of that day the tree held a
complete Kotlin module that nothing called — which is neither the old answer nor the new one. A
register kept by hand would have recorded whichever of the three states its author happened to look
at. Settle it in one command rather than trusting this paragraph:

```bash
grep -rn "dwGuessSheetCorners" android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/
```

### The gap that nothing argued for — closed, and worth keeping the shape of

**This section recorded the largest asymmetry in the feature. It is gone, and the record of it is the
useful part.** As written on 2026-08-27 it said:

> "**No comment on either client records a decision about the tracer's absence from the handset.**
> Searched across `frontend/lib/trace/`, the upload components and the Kotlin tree: the Android
> sources discuss handset *performance* and say nothing about a port. … **The largest asymmetry in
> this feature is the one nobody wrote down.** … Whoever scopes the Kotlin port should record the
> decision here first, whichever way it goes."

Every clause of that is now false, and it went false fast. The handset's trace surface landed, the
JavaScript-bundle engine under it was deleted a day later in favour of the native `DwTraceKotlin*`
runtime, and Matrix B — every row of which read `WEB ONLY (gap)` — was rewritten to **BOTH**. The
registry note this section quoted as the nearest thing to a decision no longer exists either;
`stage_definitions.py` replaced it, and its replacement deliberately names no engine, on the argument
that *which* engine runs is not a fact a designer in a courtyard can use and is the half that moves.

**What the episode is worth keeping for is the failure mode, which was not a wrong verdict but a
stale one.** Nothing here was careless: the rows were read, cited and correct on the day. They simply
described the most actively-worked area of the tree, and a page that says "read as of «date»" with no
per-row stamps cannot tell a reader which rows have outlived their reading. That is why every row now
carries its own `Verified «date»; re-check with «grep»` and why the header says the page is live
rather than a snapshot. **A cited row is not a fresh row.**

One correction the closure does not license: `backend/app/services/stage_definitions.py` still
carries a note saying this document "records the tracer as absent from the handset and quotes the old
note as its evidence". That was true when it was written and is not true now — the tracer row has
read **BOTH** since 2026-08-28. The note is in a file this page cannot edit; it is named here so the
next reader does not take it as the newer fact, which is exactly the courtesy that note was
extending to this page.

---

## The direction of every gap

**No row is ANDROID ONLY (gap) as of 2026-08-31, and two are ANDROID ONLY (deliberate).** This
section read "no row in this matrix is ANDROID ONLY, and that is a finding rather than an omission",
and it was wrong on the day it was written: the cost refusal in Matrix B and the new-row affordance
in Matrix D both carried that verdict, and "The chooser is a chooser" below names the second of them
in as many words. **A summary that contradicts the rows above it is worse than no summary**, because
it is the part somebody quotes.

What the corrected count actually shows is narrower and still worth having: **the handset holds no
capability the browser lacks by accident.** Its two leads are argued for on the page — a phone can be
too small to finish a trace and a laptop cannot, and the chooser may open a new row because it is the
only surface a designer reaches without the stage. The unargued asymmetries all ran the other way,
and the last of those closed on 2026-08-31. The rectify half is a name-for-name port —
`orderCorners`, `rectifyPlane`, `sauvolaWindow` and the rest are spelled identically on both sides,
and the corner guess is the same port under a `dw` prefix — and the chooser is deliberately smaller
than the web's workspace. **The prefix is worth knowing before searching for a port that is there.**

The handset's lead in this area is **adjacent, not inside**:
`android/app/src/main/java/com/designprototype/workshop/data/DwPhotoMeasure.kt` measures an object
against a photographed grid, and the argument for its living on the phone is the one
`DwSketchRectify.kt` reuses — "the phone IS the camera … a second attempt costs ten seconds." That is
a different feature with a different registry role, and it is not in this matrix.

Also deliberately outside it, with pointers so that nobody reads their absence as a gap:

| Adjacent feature | Where it lives | Why it is not a row here |
|---|---|---|
| Rating and ranking a colleague's sketches | `frontend/components/sketches/ReviewPanel.tsx` and `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DesignReviewScreen.kt` | A destination of its own on the handset and a tab on the web, and gated by a different door — the pool round is read by designers the workshop loader turns away |
| Measuring a length from a photograph | `frontend/components/designworkshop/PhotoMeasureField.tsx` and `android/app/src/main/java/com/designprototype/workshop/data/DwPhotoMeasure.kt` | A different registry role, offered on numeric fields across many stages |
| Bulk photograph import | `frontend/lib/photoIntake.ts` and `android/app/src/main/java/com/designprototype/workshop/data/DwPhotoIntake.kt` | Not stage-specific |

---

## How this document is kept true

| What can go wrong | What catches it |
|---|---|
| A symbol this page cites is renamed or deleted on either client | `frontend/e2e/sketches-parity-matrix-unit.spec.ts` reads this file, extracts every `` `path#Symbol` `` pin, and fails unless the path exists and the symbol is declared in it **outside comments**. Run `npm run test:unit` from `frontend/`, or `npx playwright test sketches-parity-matrix-unit --reporter=line` |
| A row is added with a verdict nobody defined, or with no citation at all | The same spec: verdicts are checked against the five above, and every row must carry at least one pin |
| A row claims a client lacks something and nobody can re-check it | The same spec: a row whose verdict is not **BOTH** must carry an ISO date and a runnable command in the absent client's cell |
| The chooser grows an editor | The same spec asserts that `SketchesAndPrototypesScreen.kt` mounts no capture card and performs no draft write |
| A cited path is renamed | `node docs/tools/check-docs.mjs`, which verifies every repository path named anywhere in `docs/` |
| The page is emptied, or its table shape changed, so that every assertion passes vacuously | The same spec asserts floors on how many pins and rows it found, and that both clients are represented among them |

**What none of that can catch, and what a human must do.** No test can tell whether a *verdict* is
right — only whether the symbols behind it still exist. Re-read this page when any of these happens:

* a file appears under `frontend/lib/trace/` or `frontend/components/sketches/upload/`, or a Kotlin
  file named `DwSketch*` — these are the feature's two halves and both matrices move with them;
* `SketchesAndPrototypesScreen.kt` grows anything that is not a navigation control;
* the Kotlin port of the vector tracer is scoped, at which point Matrix B changes wholesale and the
  decision belongs in the section above;
* `backend/app/services/stage_definitions.py` changes stage 11's or stage 13's fields, since Matrix C
  is registry-driven on both clients.

**Counts are deliberately absent.** This page names `PARAM_COUNT` rather than saying how many controls
the trace panel has, for the reason `docs/README.md` gives: a count is wrong the first time anybody
adds a slider, and it is wrong silently. `frontend/components/sketches/upload/SketchTraceField.tsx`
records that lesson against itself — its header "claimed twenty-nine while the table held thirty-two."

**Related registers, which this page does not replace.** §16 of
`.claude/skills/field-repo-frontend/SKILL.md` is the dashboard-tile and wording register and stays
binding for web UI work; `docs/DESIGN_WORKSHOP.md` is the stages and the report pipeline;
`docs/COMPUTED_FINDINGS.md` is the three-language port parity for the arithmetic beside stages 9 and
17. If §16 and this page ever disagree about the sketches row, §16's own instruction settles it: "read
the tree, not this sentence."

One correction that belongs beside that pointer and is **not this page's file to make**. §16 heads its
list with "Four of the twenty have **no Android dashboard tile to agree with**", and then names Design
workshop as one of the four while saying in the same breath that "Android draws the bespoke
`DesignWorkshopCard`" for it — so the handset does have a tile there. The claim that actually holds for
all four is the one the rest of the sentence makes: **none of the four is an `EntryMode`**. Read on
2026-08-27; re-check with `grep -n "no Android dashboard tile" .claude/skills/field-repo-frontend/SKILL.md`.
