# Scanning a QR code on the handset: ZXing, not ML Kit — and in the end, neither, and now ZXing after all

> ## STATUS, 2026-08-16: BUILT. ZXing SHIPS, AND BOTH READ SURFACES SCAN AND ACCEPT A PICTURE.
>
> This supersedes the 2026-08-15 banner, which is kept immediately below because the shape of this
> file's history is most of what makes it useful.
>
> * **Shipping:** `com.google.zxing:core:3.5.3` in `android/app/build.gradle.kts`.
> * **Where:** one shared control, `ui/DwQrScanControl.kt`, mounted on BOTH read surfaces —
>   `WorkshopCodesScreen.kt` (Cards & tags) and `RecordCodeLookup.kt` (Search). Each offers a camera
>   photograph AND a picture the designer already holds. The decoding lives in `data/DwQrDecode.kt`.
> * **The typed box is untouched on both surfaces** and is still never hidden. That was this
>   document's load-bearing condition and it remains satisfied.
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
decodes with it, and `ui/DwQrScanControl.kt` is mounted on both surfaces. See the status banner.*

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
| "The typed code remains on every surface and is the guaranteed path" | `frontend/components/designworkshop/WorkshopCodeScanner.tsx`, `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/WorkshopCodesScreen.kt`, `android/app/src/main/java/com/designprototype/workshop/ui/RecordCodeLookup.kt`. This is now doing more work than it was when it was written: with no camera on Android at all, it is not a safety net, it is the entire feature. A surface that ever hides the typed field invalidates the decision rather than merely degrading it. |

**Review triggers:** ~~any barcode or QR dependency appearing in `android/app/build.gradle.kts`~~
— *this one fired on 2026-08-16 and the document was updated rather than left to rot; it stands for
any FURTHER change to that dependency*; a change to either scanner header above; the arrival of
CameraX or any live-preview scanning, which is the one capability deliberately not built here;
a change to `DW_QR_SAMPLE_LADDER`, whose rungs depend on the 2-pixels-per-module floor measured in
`DwQrDecodeTest`.

**Known unverified:** "ML Kit is genuinely better at live-frame detection" is received wisdom about
the libraries, not a measurement made here. Nobody has run the two side by side on a bent card in
courtyard light. ~~and with no decoder in the build there is nothing on the handset to run~~ — that
half is now false: there IS a decoder on the handset, so the comparison has become possible for the
first time. Nobody has run it. Nothing about the camera path has been exercised on real hardware at
all; every claim in `DwQrDecodeTest` is about rendered pixels, and the file says so.
