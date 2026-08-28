# Scanning a QR code on the handset: ZXing, not ML Kit — and in the end, neither, then ZXing after all, and now BOTH

> ## STATUS, 2026-08-28: THE LIVE CAMERA IS ML KIT. ZXING STAYS, AND IS NO LONGER THE ONLY READER.
>
> This supersedes the 2026-08-22 banner below, which is kept for the reason every earlier banner in
> this file is kept: the shape of the history is most of what makes the file useful.
>
> **What changed and who changed it.** The owner reported on 2026-08-27: *"QR scan on android devices
> does not pick up the region of interest and scan while the camera is on, that was supposed to be
> fixed as well by now"*, and *"I do not mind MLKit, use it if it guarantees the behaviour."* This
> document's own **"The honest trade"** section had already written down that ML Kit reads a bent,
> angled or glared live frame better than ZXing and had accepted that as a regression. The accepted
> regression became the reported defect, so it was re-traded.
>
> **What ships now, per door.** Four ways a code gets in and they no longer share one decoder:
>
> * **Live camera** — `com.google.mlkit:barcode-scanning` (BUNDLED model), QR format only, reading
>   the whole frame; `data/DwQrFrameReader.kt`, class `MlKitQrFrameReader`.
> * **A photograph this app took** — ZXing, `ZxingQrImageDecoder`, walking `DW_QR_SAMPLE_LADDER`.
>   Unchanged.
> * **A picture the designer already had** — the same ZXing decoder. Unchanged.
> * **Characters typed by hand** — no decoder at all. Unchanged, and still never hidden anywhere.
>
> **What it cost, measured.** Two real `:app:packageRelease` runs on this machine differing only by
> this change: **77,009,672 bytes with it, 67,738,370 without — a delta of +9,271,302.** It
> reconciles entry by entry: 8,191,160 of `libbarhopper_v3.so` for the two shipped ABIs, 880,888 of
> `.tflite` models, 169,955 of dex after R8, and 29,299 of zip overhead. **R8 cannot shrink 96% of
> that** — the native library and the models are STORED entries. `android/app/build.gradle.kts` has
> the table and the two conclusions that follow from it, including the one that surprises: restricting
> the detector to QR saves inference time, not bytes, because two of the three packaged models are
> one-dimensional barcode models that ship regardless.
>
> **The unbundled variant is still disqualified, and the owner's words reinforce the reason rather
> than weakening it.** `play-services-mlkit-barcode-scanning` downloads its model on first use, and
> first use is a courtyard with no signal. A model that must be fetched cannot "guarantee the
> behaviour". §*Why the small ML Kit is disqualified* below stands entirely.
>
> **ZXing was NOT deleted, and that is the design constraint that shaped the change.** ZXing is pure
> Java, so `DwQrLiveFrameTest` runs the real shipping decoder on a desktop over symbols this app's own
> `DwQrEncode` produced. ML Kit cannot run in a JVM test at all. Deleting ZXing would have traded the
> only accuracy evidence this repository can produce for accuracy nobody here can measure. It is
> retained as `ReferenceQrFrameReader` — the live path's fallback on a device where ML Kit cannot
> start, said on screen when it engages — and it is still the decoder for both picture routes.
>
> **The region of interest is honoured by REFUSING, not by cropping.** ML Kit takes no crop parameter.
> It is given the whole frame and a sighting whose bounding-box centre falls outside the reticle is
> refused, with a sentence on screen saying so. Cropping was the other option and was rejected because
> the reported defect is a FALSE NEGATIVE and cropping is what manufactures false negatives.
>
> **A fourth mount was added at the same time and it may have been half the complaint.**
> `DwReferenceField.DwReferenceScanPanel` — the reference picker inside a stage — had the still
> control only, so its one camera route handed off to the SYSTEM camera app: no reticle, no region of
> interest, no live detection. `ui/DwQrLiveScanner.kt`'s own header had named that gap and called the
> mount "the next wave's one-line change". It stayed that way. It is mounted now.

> ## STATUS, 2026-08-22: BUILT. ZXing SHIPS, AND EVERY CODE SURFACE SCANS AND ACCEPTS A PICTURE.
>
> This supersedes the 2026-08-15 banner, which is kept immediately below because the shape of this
> file's history is most of what makes it useful.
>
> * **Shipping:** `com.google.zxing:core:3.5.3` in `android/app/build.gradle.kts`.
> * **Where:** one shared control, `ui/DwQrScanControl.kt`, mounted on THREE surfaces —
>   `WorkshopCodesScreen.kt` (Cards & tags), `RecordCodeLookup.kt` (Search), and
>   `ui/designworkshop/DwReferenceField.kt` (`DwReferenceScanPanel`, the reference picker inside a
>   stage, added 2026-08-22). Each offers a camera photograph AND a picture the designer already
>   holds. The decoding lives in `data/DwQrDecode.kt`.
> * **The third surface LINKS rather than OPENS**, and it is the reason the count moved from two to
>   three. The first two answer "what record is this" and navigate to it; the picker judges the same
>   payload against a field's own `refModel`, workshop scope and cascade, and a code that resolves
>   fills a join key on the row being typed. The shared control is unchanged by that — it still hands
>   back a raw payload and knows nothing of the grammar — which is what let a third mount cost no
>   second copy of the refusal wording.
> * **The typed box is untouched on all three surfaces** and is still never hidden. That was this
>   document's load-bearing condition and it remains satisfied; on the picker it sits beside a
>   searchable dropdown that needs no camera either, so that surface has two lensless routes.
> * **The camera is a SHUTTER, not a live preview.** No CameraX, no frame loop — a photograph is
>   taken and decoded, the same shape `DwIdentityCardControl` uses. A live scanner is still the next
>   thing to add and CameraX is still what it costs.
>
> **What reopened the decision was not a new measurement.** It was a requirement: every QR surface is
> to accept a picture the designer already has, because a screenshot forwarded on WhatsApp or a
> photograph of a tag taken last week is often the only thing they hold. The argument that closed
> this file twice — *"the typed code is a shorter path to the same record"* — is true only while
> somebody is standing in front of the card. In the picked-image case nobody is, and there is no
> shorter path because there is no path at all. That is the hole the two file headers did not see,
> and both of them now record their own reversal in place rather than being deleted.
>
> **The verdict line below is now right again**, by a route nobody planned: the decision was ZXing,
> the outcome was nothing, and the implementation is ZXing. The comparison in *The measurement* is
> what settled it a second time, plus one argument that section could not have made — see
> *What the second look added*.

> ## SUPERSEDED BANNER — STATUS, 2026-08-15: DECIDED AND NEVER IMPLEMENTED. NO QR DECODER SHIPPED.
>
> *Kept verbatim. It was true for a year of this file's life and it is the state anybody reading a
> commit from before 2026-08-16 is looking at.*
>
> * **Decided:** ZXing, on the argument below.
> * **Built:** *nothing*. `grep -i zxing android/app/build.gradle.kts` returns no line; the only ML
>   Kit artifact in the build is `com.google.mlkit:text-recognition`, which is the identity-card
>   recogniser from a different decision and does not read barcodes. Both code-scanning entry points
>   deliberately open no camera and offer the typed code as the route, not as a fallback —
>   `WorkshopCodesScreen.kt` and `RecordCodeLookup.kt` say so in their own headers.
> * **Outstanding:** there is no QR decoding on Android and none is planned. Nobody is part-way
>   through this.
>
> The comparison below is still the right comparison and is why this file is kept: anybody who ever
> adds a scanner has to answer it first, and it disqualifies the cheapest option for a reason that has
> not changed. Only the verdict line rotted. §1 of *How this document is kept true* has the full
> account of how "ZXing" became "no scanner".
>
> **Why this banner exists at all:** the correction used to live only at the bottom of the file, 75
> lines below the sentence it corrects. A reader who takes the decision line at face value — and that
> is what a decision line is for — never reaches it, and a planner then treats QR scanning as a
> shipped capability. A status that contradicts the headline belongs beside the headline.

**Decision:** use `com.google.zxing:core` for on-device QR decoding. Do **not** use either ML Kit
barcode variant. Recorded because the obvious choice (ML Kit, Google's own, better on live frames)
is the wrong one here for a reason that is specific to this application and not obvious from the
library comparison. *(Superseded in practice — see the status banner above.)*

## The measurement

Artifact sizes read from the publishing repositories on 2026-08-08, not from memory:

| Option | Artifact | Size | Works offline on first use |
|---|---|---|---|
| ML Kit, unbundled | `com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1` | **0.50 MB** | **No** |
| ML Kit, bundled | `com.google.mlkit:barcode-scanning:17.3.0` | **9.44 MB** | Yes |
| ZXing | `com.google.zxing:core:3.5.3` | **0.58 MB** | Yes |

## Why the small ML Kit is disqualified

The unbundled variant is the one that looks best on the table — half a megabyte, Google's detector,
the accuracy of the bundled model. It gets there by NOT shipping the model: Play Services downloads
it on first use.

That download is a network request, and this application's scanner exists to be used at the close of
a workshop, in a village, on a handset that has had no signal for two days. The first scan is
precisely the moment the model is not there. It would work perfectly on every developer's desk and
fail on the day it mattered — the failure mode this repository has been bitten by before, and the
one its offline-first design exists to prevent.

It is also a *silent* class of failure to diagnose: the scanner would simply not detect anything,
which reads as a broken camera or a bad card rather than as a missing model.

## Why not bundled ML Kit

9.44 MB for one feature, on an APK that ships to whatever handset was cheapest that year, over
mobile data in a district town. It is not wrong — it works offline and it detects better — but it is
sixteen times the size of ZXing for a marginal gain on a card that is being held still under a lens.

## What ZXing costs and what it gives up

0.58 MB, pure Java, no Play Services dependency at all, and it decodes from both a live camera frame
and a still image — so it serves the camera path and the "read the code out of a photograph I
already took" path from one dependency, matching what the web scanner now offers.

**The honest trade:** ML Kit is genuinely better at live-frame detection — a code at a steep angle,
in motion, partly damaged, or under glare. ZXing needs a steadier hand. That is a real regression
against the bundled option and it is accepted deliberately, because:

- the manual typed code remains on every surface and is the guaranteed path (see
  `WorkshopCodeScanner.tsx`, whose header treats "the scanner is missing" as a normal state);
- a photograph can be re-taken and re-read, so a failed live scan is not a dead end;
- and a scanner that is 9 MB smaller and cannot fail for want of a download is worth more here than
  one that reads a bent card on the first pass.

> **THIS IS THE PARAGRAPH THAT CAME BACK, 2026-08-28.** It named the regression correctly and the
> regression was reported as a defect on 2026-08-27, by the person the trade was made on behalf of.
> The three bullets all remain true and none of them turned out to be enough: the typed box is still
> there, a photograph can still be retaken, and both are extra work at the moment somebody is
> standing in a courtyard with a card in their hand. **The last bullet is the one that was wrong —
> not in its arithmetic, but in its weighting.** Nine megabytes is a real cost and it has now been
> paid; see the top banner and `android/app/build.gradle.kts` for the measured figures. What survived
> the reversal intact is the *first* half of the same reasoning: the download-on-first-use variant is
> still refused, because "cannot fail for want of a download" was never the weak clause.

## The larger optimisation this surfaced, which is NOT done

> **DONE since this was written — see §2 of *How this document is kept true*.** The heading and the
> tense below are 2026-08-08 and are left standing because they are the reasoning that led to the
> work, not a description of the build. Same rule as the status banner at the top: a section whose
> title says "NOT done" about something that is done gets a marker where the title is, because that
> is where it is read.

`android/app/build.gradle.kts` has **no `buildTypes` block at all**, so R8 shrinking and resource
shrinking are off for every build. The whole dependency set — Compose, media3, ExoPlayer, Coil,
Retrofit, Play Services credentials — ships unshrunk, and that is worth considerably more than any
scanner library either way.

It was NOT enabled as part of this decision, deliberately. Turning R8 on for an application using
`kotlinx-serialization` (generated serializers reached reflectively) and Retrofit (interfaces
reached by proxy) requires keep rules, and getting them wrong produces a release build that
typechecks, builds, installs, and then fails at runtime on a field handset. There is no proguard
rules file in this module and no device or emulator on the build machine (`adb` is not installed),
so the change cannot be verified where it would break. It should be done — with rules, and with a
release build exercised on real hardware — and it is written down here rather than attempted blind.

---

## What the second look added, 2026-08-16

The 2026-08-08 comparison held up: the unbundled ML Kit variant is still disqualified by the model
download, and 9.44 MB against 0.58 MB is still the wrong trade for a symbol held still under a lens.
Two things were added to it, and one of them would have decided the question on its own.

**ZXing is on the JVM test classpath and ML Kit is not.** `IdentityCardRecognizer`'s own header
states the cost of the other choice in plain terms: ML Kit "cannot run in a JVM unit test … every
claim about recognition ACCURACY is therefore a hardware claim that has not been made yet", on a
machine with no device and no emulator. Pure Java changes that completely.
`android/app/src/test/java/com/designprototype/workshop/data/DwQrDecodeTest.kt` renders symbols
produced by **this application's own `DwQrEncode`** — a hand-written ISO 18004 encoder with its own
Reed-Solomon, masking and version tables — and decodes them with the shipping reader and the shipping
hints. The printer and the reader are checked against each other on every build, not on a handset
nobody has. `DwQrEncodeTest` could only ever check the encoder against its own arithmetic; "an
independent decoder can read what I drew" is a different claim and it is the one a designer holding a
printed card actually needs.

**Two numbers came out of writing that test, both of which contradicted what had been assumed:**

| Assumed | Measured | What it changed |
|---|---|---|
| The symbol reads at 1 pixel per module | **It does not. The floor is 2.** | The decode ladder must keep an un-halved rung. Halving is only safe at ≥4 px/module in the original, so a code that is small in frame is destroyed by the fast first pass and rescued by the second. |
| A symbol with no quiet zone does not scan | **It scans fine when it fills the picture.** | The quiet zone's real job is holding the symbol away from *ink* — a card's printed rule — not away from the image border. The test now frames the symbol in black, which is what a card is, and the claim in `DwQrEncode.svgPath` is checkable at last. |

Both assertions were written the wrong way round first and the test caught both. They were rewritten
to pin the measured behaviour rather than deleted, and each carries a docstring saying what the old
assertion claimed and why it went.

**Still not measured, and still the honest gap:** anything involving a lens. There is no perspective,
no glare and no motion blur in a rendered matrix. "ML Kit is genuinely better at live-frame
detection" remains received wisdom, and now that there IS a decoder in the build it is finally
possible to run the comparison — on a bent card in courtyard light, which is where it matters.

## How this document is kept true

**Two of this document's claims are already false, and naming them is most of the maintenance
story.** It is a decision record from 2026-08-08; the argument in it is frozen and is not rewritten
to agree with later code, but a reader has to be told which sentences still describe the handset.

### 1. ~~THE DECISION WAS NOT IMPLEMENTED.~~ **RESOLVED 2026-08-16 — it is implemented now.**

*This section described the state of the tree from 2026-08-08 to 2026-08-16 and is kept because the
account of how a decision came to be un-built is the most useful thing in this file. Everything below
this paragraph is history: `com.google.zxing:core:3.5.3` is in the build file, `data/DwQrDecode.kt`
decodes with it, and `ui/DwQrScanControl.kt` is mounted on three surfaces — two as of 2026-08-16 and
the reference picker since 2026-08-22. See the status banner.*

*Re-checked against the tree on 2026-08-15: still true, still nothing. This finding is now also stated
in the banner at the top of the file — it was written here first and, being here, it was reaching
nobody who read the decision line and stopped.*

The opening line reads *"use `com.google.zxing:core` for on-device QR decoding."* `grep -i zxing
android/app/build.gradle.kts` returns **nothing**; the only ML Kit artifact in the build is
`com.google.mlkit:text-recognition`, which is the identity-card recogniser from a different decision
and does not read barcodes. What actually happened is that the argument in this file was carried one
step further by the code: if a downloaded model is unacceptable in a courtyard, and a typed code is a
shorter path to the same record anyway, then **the camera is not worth 0.58 MB either.** Both entry
points say so in their own headers and treat it as a decision rather than a gap:

- `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/WorkshopCodesScreen.kt`
  — *"IT DOES NOT OPEN THE CAMERA, and that is a decision rather than a gap … the manual box is not a
  fallback here, it is the route."*
- `android/app/src/main/java/com/designprototype/workshop/ui/RecordCodeLookup.kt` — the same
  reasoning, deliberately not repeated in full.

So the *comparison table below is still the right table* — it is what anybody adding a scanner should
read first, and it disqualifies the cheap option for a reason that has not changed. The **verdict
line is wrong**: the outcome was "no scanner", not "ZXing". Anyone acting on the first sentence would
add a dependency this application decided twice not to have.

### 2. The last section's premise is spent.

*The larger optimisation this surfaced, which is NOT done* states that `android/app/build.gradle.kts`
has **no `buildTypes` block at all** and that R8 shrinking is off for every build. True on 2026-08-08,
not true now: there is a `buildTypes` block, the release build sets `isMinifyEnabled = true` and
`isShrinkResources = true`, and `android/app/proguard-rules.pro` carries the app-specific keep rules
that section said would be required. It was done with rules and exercised on real hardware, exactly
as the section asked, and the numbers are in [R8-MEASUREMENT.md](R8-MEASUREMENT.md). The section is
kept because it is the reasoning that led to the work.

### What keeps the rest honest

| Claim class | Kept true by |
|---|---|
| The artifact sizes in the table | Read from the publishing repositories on 2026-08-08 at the pinned versions, and true only of those versions. Re-read from Maven Central before quoting one; never infer a current size from a version bump. |
| "The unbundled variant downloads its model on first use" | A property of the library, not of this repository — the one claim here that cannot rot under us. It is the load-bearing premise, so if it is ever wrong the whole decision reopens. |
| What actually ships | `android/app/build.gradle.kts`, plus the two file headers named above. **Check the code, not this heading** — that is the lesson of §1. |
| "The typed code remains on every surface and is the guaranteed path" | `frontend/components/designworkshop/WorkshopCodeScanner.tsx`, `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/WorkshopCodesScreen.kt`, `android/app/src/main/java/com/designprototype/workshop/ui/RecordCodeLookup.kt`, and — since 2026-08-22 — `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwReferenceField.kt` (`DwReferenceScanPanel`, where a code links a record onto a stage row rather than opening it). **This list is the evidence and it has to grow with the mounts**; it was written when there were two and stayed at two through a third, which is the same rot as §1 one size down. A surface that ever hides the typed field invalidates the decision rather than merely degrading it. |

**Review triggers:** ~~any barcode or QR dependency appearing in `android/app/build.gradle.kts`~~
— *this one fired on 2026-08-16, and again on 2026-08-28 when `com.google.mlkit:barcode-scanning`
was added; the document was updated both times rather than left to rot, and it stands for any
FURTHER change to those dependencies*; **a NEW MOUNT of `DwQrScanControl`** — fired on 2026-08-22,
and **a new mount of `DwQrLiveScanControl`**, which fired on 2026-08-28 when the reference picker
finally got the live scanner; ~~the arrival of CameraX or any live-preview scanning, which is the one
capability deliberately not built here~~ — *both arrived; that clause is spent*; a change to any
scanner header above; a change to `DW_QR_SAMPLE_LADDER`, whose rungs depend on the
2-pixels-per-module floor measured in `DwQrDecodeTest`; **and one new one: any change to which
reader the live camera uses, or to how the reticle is applied to it.** That last is written down
because the two readers apply the reticle in OPPOSITE directions — ML Kit reads everything and
refuses a sighting outside the box, ZXing is shown only the box — and somebody making them agree
without reading `data/DwQrFrameReader.kt` would reintroduce the 2026-08-27 defect.

### What the reference decoder actually stops reading at — measured on this machine, 2026-08-28

Before ML Kit was added, a throwaway JVM harness pushed a real `DwQrEncode` symbol (`DPW1:G:` plus a
25-character cuid and a check, 29 modules, level Q) through the shipping live decoder over a
516×516 synthetic frame — the crop a 1080-wide portrait viewfinder produces from a 1280×720 analysis
buffer — with one degradation applied at a time. **This says nothing whatsoever about ML Kit**, which
cannot run here. It says what the reader that was in the live path could and could not do.

| Degradation | Reads | Stops reading |
|---|---|---|
| Pixels per module, clean and flat-on | down to **2.0** | at **1.5** |
| Box blur at 6 px per module | radius **3** | radius **4** — about ⅔ of a module |
| Box blur at 3 px per module | radius **1** | radius **2** |
| Perspective tilt, sharp, 6 px per module | up to **0.3** | at **0.4** |
| Tilt with a little blur (radius 2) | up to **0.3** | at **0.4** |
| Glare: a saturating light ramp across the symbol | up to **0.8** | never, in range |
| Contrast squeezed about mid-grey | down to **0.3** | at **0.2** |

Three things follow, and the third is the useful one.

1. **Blur is what kills it.** Two-thirds of a module of defocus or hand-shake is enough, and that is
   an ordinary hand-held frame in courtyard light. The 2-pixels-per-module floor `DwQrDecodeTest`
   records is a *clean-image* floor and is not the binding constraint in the field.
2. **Glare is not the problem it was assumed to be.** `HybridBinarizer`'s local thresholding handled
   every ramp tested. The phrase "bent, angled or glared" in this file overstated one third of itself.
3. **`TRY_HARDER` made no difference on any row.** The live path drops that hint for the frame budget
   and `DwQrDecode.kt` describes it as the one place the two paths diverge in capability — on these
   degradations it does not diverge at all. Nothing was being given up.

**The fixture's limits, stated because the table would otherwise be over-read:** these are rendered
pixels with a synthetic box blur and a projective warp, one degradation at a time; a real lens
compounds them, and a real card curves. The numbers bound the decoder, not the courtyard. The harness
was deleted rather than kept, because a test that prints and asserts nothing is noise in a suite —
the method is written here so it can be re-run rather than re-invented.

**Known unverified:** "ML Kit is genuinely better at live-frame detection" is received wisdom about
the libraries, not a measurement made here. Nobody has run the two side by side on a bent card in
courtyard light. ~~and with no decoder in the build there is nothing on the handset to run~~ — that
half is now false: there IS a decoder on the handset, so the comparison has become possible for the
first time. **AND AS OF 2026-08-28 BOTH LIBRARIES ARE IN THE APK, so the comparison needs no new
build at all — and it is still nobody's measurement.** The premise was acted on because the owner
reported the symptom it predicts, which is evidence of a different and weaker kind, and this file
should say so rather than promote it. Nothing about the camera path has been exercised on real
hardware here; every claim in `DwQrDecodeTest` and `DwQrLiveFrameTest` is about rendered pixels, and
both files say so. **What a build CAN still check, and does:** the crop arithmetic, the row-stride
handling, both reticle maps, the acceptance test, and a full round trip from this app's own encoder
through the ZXing reference decoder. None of that covers ML Kit, and no test pretends it does.
