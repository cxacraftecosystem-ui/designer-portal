# R8 on the handset build: measured

Both builds run on an idle machine (10.6 GB free) so the numbers can be trusted — an earlier
attempt was **stopped rather than reported**, because a Gradle build at 1.6 GB free prints
`BUILD SUCCESSFUL` having compiled nothing and a wrong number here is worse than no number.

| Release APK | Bytes | Size |
|---|---|---|
| Before (no `buildTypes` block at all) | 18,876,355 | **18.00 MB** |
| After (`isMinifyEnabled` + `isShrinkResources`) | 6,389,483 | **6.09 MB** |
| **Saved** | 12,486,872 | **11.91 MB — a 66% reduction** |

Both builds completed `BUILD SUCCESSFUL`, and the build file was restored from git rather than by
`sed`, so it cannot be left half-flipped.

## Nothing load-bearing was removed

Read out of `build/outputs/mapping/release/r8-removed.txt` rather than assumed. R8's usage format
distinguishes two things that are easy to conflate, and conflating them makes a safe result look
alarming: a line ending in `:` is a class that was **kept** with some members trimmed; a line with no
colon is a class removed **entirely**.

- Of our package: **80 whole classes** removed, **539 kept with members trimmed**.
- **Zero** serializers or wire DTOs removed (`grep -cE '\$\$serializer|Dto$|Body$|Payload$'` = 0).
- `StageEntryBody`, `StageSaveBody` and `WorkshopRepositoryApi` are all present, with 16, 17 and 175
  kept entries respectively — the whole sync path survives.
- The bulk of what went is library dead weight: 1,883 classes of `com.google.common` (Guava, pulled
  in transitively and barely used) and 1,640 of `com.google.android`.

## R8 INDEPENDENTLY CONFIRMED THE DARK-CODE FINDING, which is the interesting part

The whole-class removals from our own package include, in their entirety:

    DwWorkshopCodesKt, DwWorkshopCodeRef, DwWorkshopRecordType, DwEncodeResult, DwDecodeResult,
    DwEncodeRefusal, DwDecodeRefusal          — the whole workshopCodes port
    DwQrEncode, DwQrSymbol, DwQrEccLevel, DwQrRefusal, DwQrSvg, DwQrEncodeException
                                              — the whole qrEncode port
    DwPhotoMeasure, DwHomography, DwKnownRectangle, DwMeasureResult, DwPoint, DwScaleReference,
    DwSegment, DwRoundedValue                 — the whole photoMeasure port
    DwSubmissionReadiness, DwWorkshopReadiness, DwReadinessItem, DwReadinessCheck,
    DwReadinessAddress, DwReadinessStageGap   — the whole submissionReadiness port

All four modules ported earlier today, deleted whole. R8 is a reachability analyser, so this is an
objective second opinion — obtained from a completely different mechanism than the `grep` that first
found it — that **nothing in the application reaches any of them**. 1,140 lines with 81 passing
tests, provably unreachable.

**So this 6.09 MB is flattering and must not be quoted as a steady state.** Part of the saving is R8
deleting code that is meant to be used and merely is not wired yet. When the card sheet and the
measure screen land, the APK grows back by roughly the weight of those modules, and that growth is a
sign of the wiring working rather than a regression.

**Worth keeping as a technique:** if R8 removes a module somebody just wrote, nothing calls it. That
is a cheap, mechanical dark-code detector for a repository whose commonest defect is exactly that —
and it costs nothing beyond reading a file the release build already produces.

## Still owed before this merges

A shrunk build exercised on REAL HARDWARE through the offline loop. R8's failure mode is not a
compile error: it is a `SerializationException` or a `NoSuchMethodError` at the first sync, on a
build that installed and ran perfectly on a desk. There is no device and no emulator on this machine
(`adb` is not installed) — the same hardware gap the handover already records against the offline
claim itself. The keep rules and the evidence above make that test likely to pass; they do not
replace it.

---

## Verified on real hardware — 2026-08-08

**Device:** Samsung SM-M325F (Galaxy M32), Android 13, 5.8 GB RAM — a genuine mid-range handset of
the kind this app is carried into a village on, not an emulator.

**Build under test:** the shrunk release APK, **6.11 MB**, signed with the debug key so it could be
installed (see the note in `build.gradle.kts` — that signing config is for testing and must never
become the release one). Pointed at the local API through `adb reverse tcp:8000 tcp:8000`, because
writing test data into the production CloudFront backend to verify a build flag would be
indefensible; the first build was killed 13 tasks in when it was noticed defaulting there.

### What passed

| Exercised | Result |
|---|---|
| App launch, Compose UI, resource shrinking | Renders fully — type, layout, copy all intact |
| Retrofit dynamic proxy + request serialization | Login reached the server; a wrong password returned a real **401** |
| Response deserialization, 8+ endpoint DTOs | artisans, products, tools, processes, crafts, workshops, questionnaire sections and interviews — all **200 OK** and rendered |
| Bundled 496-field registry parse | 22 stages rendered with per-stage completeness computed |
| Paged listing + truncation | "Showing 500 of 8411 workshops. Search by title, craft or cluster to reach the rest." |
| **The offline loop** | With the API tunnel cut: a workshop was created, all 22 stages rendered from the bundled registry, completeness computed, and the banner said honestly "This workshop has not been created on the server yet." |
| Report screen offline | Template picker and all twelve accent colours, with per-stage missing-field warnings |
| Stage form offline | Date picker, Hindi dictation control, media capture — the whole field vocabulary |

**Zero `FATAL`, zero `SerializationException`, zero `NoSuchMethodError`, zero `ClassNotFoundError`,
zero `OutOfMemoryError`** across every step, checked from a cleared logcat each time.

That is the exact failure mode this test existed for. R8's risk was never a build error — it was a
serializer stripped by the shrinker and a crash at the first sync, on a build that installed
perfectly. Eight endpoints' worth of DTOs decoded on the device say it did not happen.

### What was NOT confirmed, and is not being claimed

**The .docx export writing a file.** The export buttons render and tapping them crashed nothing, but
no file appeared in `/sdcard/Download` or the app's external files directory, and a release build is
not debuggable so `run-as` cannot read app-internal storage. The most likely explanation is benign —
the test workshop had 0% of its required fields filled, and the screen carries a completeness
warning — but "probably fine" is not verification. The report WRITERS are covered by JVM unit tests;
what remains unproven on hardware is the file write at the end of a shrunk build.

**The offline test cut the API tunnel rather than switching the handset to airplane mode**, because
the device is on wireless adb and disabling its radios would have severed control of it. From the
app's side an unreachable server is the same event; from the OS's side connectivity still existed,
so any code branching on a system connectivity check took its online path and then failed, which is
the harsher of the two cases rather than the softer one.

**Recommendation:** merge. The size win is 66% and every mechanism R8 could plausibly have broken has
now been exercised on the target hardware. Re-run the export check once a workshop with real content
exists on a device.

---

## The debug-signing footgun this created, and how it was closed

Verifying R8 on a handset needed a signature, because an unsigned APK cannot be installed. The first
version of that change wired the DEBUG keystore into the release build type unconditionally — which
was fine in a throwaway worktree and wrong the moment it merged, because the debug keystore ships
with every Android SDK on earth. Anyone can produce an update for an APK signed with it, and nothing
in the build would have told the first person to cut a release from a clean checkout.

It is now opt-in, off by default:

    # android/local.properties (gitignored)
    debugSignRelease=true

Verified in both directions with `:app:signingReport` rather than by reading the code:

| `debugSignRelease` | release variant |
|---|---|
| `true` | `Config: debug`, store `~/.android/debug.keystore` |
| absent / `false` | `Config: null` — unsigned |

A clean checkout and CI therefore produce an unsigned release, which fails loudly at install time
instead of quietly at publish time. When the flag is on, the build prints a lifecycle warning naming
what it did and that the APK is not distributable.

Publishing still needs a real keystore whose credentials come from `local.properties` or the CI
secret store — never from a file in the repository.

---

# The size envelope after the bundled recogniser — 2026-08-09

`docs/DECISION-identity-card-ocr-on-android.md` recommended against a bundled on-device text
recogniser on size, and **the user overruled it**: the read happens on the handset and needs no
connection. This section is the true cost of that decision, measured, and what was done to make it
cost as little as it honestly can.

## First: 6.09 MB had already gone stale, exactly as predicted above

> "So this 6.09 MB is flattering and must not be quoted as a steady state."

Re-measured on the current tree (`1d8d7c1`), same command, same machine, same
`debugSignRelease=true` so the signature overhead is the same on both sides:

| Release APK | Bytes | Size |
|---|---|---|
| Recorded above (`perf/enable-r8`, unsigned) | 6,389,483 | 6.09 MB |
| Recorded above (same build, debug-signed, on the M32) | ~6,406,000 | 6.11 MB |
| **Today's tree, before ML Kit** | **6,636,115** | **6.33 MB** |

The 246,632-byte growth is the wiring landing — `RecordCodeCard`, `RecordCodeLookup`,
`ReportSource`, `ArtisanIdentity` and the rest of the ports that R8 was deleting whole when this
document was written. That is the growth the section above calls "a sign of the wiring working
rather than a regression", and it is why every figure below is stated against **6,636,115** rather
than against the older number.

## Second: this app was already shipping native code, and to architectures that cannot run it

The OCR decision document states "This app ships **no native code at all** today". Measured off the
baseline APK rather than assumed, that is not quite true:

| `lib/` entry in `app-release.apk` | Bytes in APK | Method |
|---|---|---|
| `lib/x86_64/libandroidx.graphics.path.so` | 10,760 | STORED |
| `lib/arm64-v8a/libandroidx.graphics.path.so` | 10,096 | STORED |
| `lib/x86/libandroidx.graphics.path.so` | 9,284 | STORED |
| `lib/armeabi-v7a/libandroidx.graphics.path.so` | 7,252 | STORED |

`android/app/build.gradle.kts` sets **no `abiFilters`**, so every build packages every ABI every
dependency offers — four of them. Today that is 20,044 bytes of x86/x86_64 shipped to devices that
cannot be the target, which is rounding error. It matters because it proves the mechanism is already
live and because **STORED** is the word that decides everything below.

## Why R8 cannot help with any of this, proven rather than asserted

`minSdk = 26` makes AGP write `extractNativeLibs="false"` into the merged manifest — read out of
`app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`, not
recalled from documentation:

    grep -o 'extractNativeLibs="[a-z]*"' …/AndroidManifest.xml   ->   extractNativeLibs="false"

That attribute requires native libraries to be **stored uncompressed** in the APK, which is why every
`lib/` row above reads STORED and why its "bytes in APK" equals its size on disk. R8 shrinks
Java/Kotlin classes; it has no opinion about a `.so`. The 66% saving recorded at the top of this
document buys nothing back here. **Filtering at package time is the only lever that exists.**

## An ABI split and an App Bundle were both considered, and the delivery chain rules both out

Checked before assuming, because "just use an App Bundle" is the reflex answer and it is wrong here.
**This application is side-loaded and there is no store anywhere in the chain to pick a variant per
device:**

1. The portal's download button is a fixed, versionless address —
   `frontend/components/settings/GetTheAppPanel.tsx:41`, `${API_BASE}/api/app/download`.
2. That address is one redirect to one object, chosen only by highest `versionCode` and knowing
   nothing whatever about the requesting device — `backend/app/api/routes/app_release.py:154`
   `download_latest_apk()`, over the row `_latest_release()` picks at line 59.
3. The handset's own updater fetches that same single file —
   `android/…/data/WorkshopRepository.kt:1504` `downloadApk()` — and hands it to the system
   installer at `MainActivity.kt:2413`.
4. The prompt it answers **has no "Later"** — `MainActivity.kt:2322`,
   `onDismissRequest = { /* required update: cannot be dismissed */ }`.

**An App Bundle buys nothing and breaks the publisher.** Nothing in that chain can consume an `.aab`.
Worse, `publishAppUpdate` (`WorkshopRepository.kt:1391`) publishes a build by reading
`File(context.applicationInfo.sourceDir)` at line 1395 — under a bundle install that path is the
**base split alone**, with the ABI split's native library in a sibling file that this code never
looks at. The master admin would upload a base APK with no model in it and every phone in the field
would install it. Play's per-ABI delivery, which the OCR decision document names as the condition
under which the trade changes, is unavailable until that whole chain is replaced.

**An ABI split makes two APKs for one URL and no chooser.** `splits { abi { … } }` would produce an
arm64 APK and an armeabi-v7a APK, and the release table holds one row per `versionCode` and one
object key. Whichever file reached the publisher becomes the one every handset gets; a phone of the
other ABI answers `INSTALL_FAILED_NO_MATCHING_ABIS` to a dialog it cannot dismiss, which is an app
that has stopped working in a village with no support channel. Giving the two APKs different
`versionCode`s makes it worse rather than better: the higher one wins `_latest_release()` for ever.

So the only shape this delivery chain can carry is **one universal APK that installs on every field
handset**, and the only way to make it smaller is to stop putting architectures in it that no field
handset has.

## The measurement

Five real `assembleRelease` runs on the same tree, same machine, same `debugSignRelease=true`, each
differing from the one above it in exactly one thing. Sizes read off the file with `os.path.getsize`,
not off a Gradle report, and the ABIs read out of the APK's own central directory rather than off the
build file. `lintVital*` is excluded from (b) onwards purely for wall-clock; lint produces no bytes.

| | Release APK | Bytes | Size | vs today | multiple |
|---|---|---|---|---|---|
| **a** | today, before ML Kit (four ABIs, as configured) | **6,636,115** | 6.33 MB | — | 1.00× |
| **b** | + bundled ML Kit, four ABIs — *what would have shipped* | **49,307,952** | 47.02 MB | +42,671,837 | **7.43×** |
| **c** | + bundled ML Kit, `abiFilters` = the ARM pair | **26,080,576** | 24.87 MB | +19,444,461 | **3.93×** |
| d | + bundled ML Kit, `abiFilters` = `arm64-v8a` alone | 19,271,029 | 18.38 MB | +12,634,914 | 2.90× |
| e | (c) with `useLegacyPackaging = true` | 16,103,260 | 15.36 MB | +9,467,145 | 2.43× |

**(b) → (c) is what this lane delivers and what `build.gradle.kts` now does: 23,227,376 bytes —
22.15 MB — that would otherwise have shipped, removed.** (a) → (c), +19,444,461 bytes, is what the
decision itself costs once that is taken off. Proven off the artifacts rather than off the
configuration: (b) carries
`lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libmlkit_google_ocr_pipeline.so` and (c) carries only the two
ARM entries.

### Where the 42.67 MB of (b) actually goes, grouped off the two APKs

| group | before | after | delta |
|---|---|---|---|
| `lib/x86_64` | 10,760 | 11,636,888 | +11,626,128 |
| `lib/x86` | 9,284 | 11,570,332 | +11,561,048 |
| `lib/arm64-v8a` | 10,096 | 11,074,640 | +11,064,544 |
| `lib/armeabi-v7a` | 7,252 | 6,789,192 | +6,781,940 |
| `assets/mlkit-*` (`.tflite`, also STORED) | 0 | 1,272,325 | **+1,272,325** |
| `*.dex` | 5,052,235 | 5,267,116 | +214,881 |
| `resources.arsc` + `res/` | 1,300,483 | 1,398,309 | +97,826 |

Cross-checked with a second tool rather than trusted to one: `unzip -v` on the shipped APK reports
`Stored … 0%` for every `lib/` entry, and the CRC-32s of the two ML Kit libraries (`5a02f1b7`,
`264f5dd0`) are **byte-identical to the entries inside the AAR**. Nothing in the build touched them —
`stripReleaseDebugSymbols` had nothing to strip, because Google ships them stripped. There is no
version of this build that makes those 17,846,484 bytes smaller.

**R8 did its job and it did not matter.** The five ML Kit artifacts bring roughly 1 MB of
`classes.jar` plus the whole `play-services-mlkit-text-recognition` shim, and the dex grew by
**214,881 bytes** — R8 ate almost all of it. 99.5% of the cost is in rows R8 is structurally unable to
touch.

### The earlier estimate was LOW, not high, and it is worth knowing why

`DECISION-identity-card-ocr-on-android.md` derived ~45.2 MB for the four-ABI build and ~23.1 MB for
the ARM pair by unzipping the AAR and adding the `.so` to a 6.09 MB baseline. Measured: **47.02 MB**
and **24.87 MB**. Two things the AAR arithmetic could not see:

- **`assets/mlkit-google-ocr-models/…`, 1,272,325 bytes.** The estimate added `6.09 + 39.13` — the
  baseline plus the four `.so` out of `text-recognition-bundled-common`. The other artifact, listed
  in the same table as "the Latin API shim, 1.32 MB", was then left out of the addition, and it is
  not a shim: `text-recognition-16.0.1.aar` carries **no native code and 1.3 MB of model assets** —
  `lstm_model.fb`, `tflite_langid.tflite`, `model.tflite` and the rest of
  `assets/mlkit-google-ocr-models/`. Confirmed by unzipping both AARs: bundled-common has **zero**
  `assets/` entries. They are ABI-independent (they ship once whatever `abiFilters` says) and STORED
  as well, so they are pure unshrinkable weight in every configuration below.
- the dex and resource growth above, and a baseline that had already moved from 6.09 to 6.33 MB.

The estimate's *shape* was right and its conclusion about R8 was exactly right. It was 1.8 MB
optimistic, which is the usual direction for a number derived from a listing.

## Two further levers, measured but NOT taken here

**Dropping `armeabi-v7a` saves another 6,809,547 bytes (6.49 MB)** — row (d). It is not taken, and
the reason is not sentiment. `minSdk = 26` reaches handsets from 2017, when 32-bit-only phones were
still being sold in this market; there is no device inventory in this repository to say none is in
the field; and the failure mode of guessing wrong is not a slow app but
`INSTALL_FAILED_NO_MATCHING_ABIS` behind an update dialog with no "Later" button, in a village, with
no support channel. 6.49 MB is not worth that without a roster of what the designers actually carry.
**If that roster exists, this is a one-line change and the number is measured and waiting.**

**`useLegacyPackaging = true` saves another 9,977,316 bytes (9.52 MB) off every download** — row (e).
It flips `extractNativeLibs` back to `"true"` (verified in the merged manifest), so the libraries are
DEFLATED in the APK — 17,863,832 bytes stored becomes 7,927,235 compressed — and the installer
unpacks them at install time. It is a genuine transfer rather than a free win:

| | (c), as shipped | (e), legacy packaging |
|---|---|---|
| download, every install **and every update** | 26,080,576 | **16,103,260** (−9,977,316) |
| permanent on-device footprint | 26,080,576 | **33,967,092** (+7,886,516) |

For an application that is side-loaded over prepaid mobile data and whose updater downloads the whole
APK every release, trading 7.52 MB of storage for 9.52 MB off *every* download is arguably the right
way round. It is deliberately left as its own decision rather than folded into this one: it changes
install-time behaviour on a build nothing here can put on a handset, and this document's own record
is that untested release-only behaviour is how this repository gets hurt. **The number is measured;
the choice is not this lane's to make.**

## Both branches of the filter were exercised, on the configuration actually committed

The block in `build.gradle.kts` has an `if`, and an untested `else` is how a build flag ships broken.
Two more `assembleRelease` runs on the committed tree — no ML Kit, so this is the state that merges
whether or not the recogniser lane lands the same day:

| `local.properties` | Release APK | ABIs in the built APK |
|---|---|---|
| `releaseAllAbis` absent (clean checkout, CI) | **6,599,672** | `arm64-v8a`, `armeabi-v7a` |
| `releaseAllAbis=true` | **6,636,115** | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

The widened build is **byte-identical to the pre-change baseline**, which is the cleanest possible
demonstration that the only thing this change alters is the ABI set. The narrowed one prints nothing
extra; the widened one prints, on the console, before any packaging happens:

    release: packaging ALL FOUR ABIs (releaseAllAbis=true) — about 22 MB of x86 and x86_64 native
    libraries no field handset can load. For emulator testing only; do not publish this APK.

Note that today's saving is 36,443 bytes, not the 20,044 of raw `.so` — stored native libraries are
page-aligned in the APK, so dropping an entry frees a little more than the library itself.

## Recommendation

1. **Ship (c).** `abiFilters = ["arm64-v8a", "armeabi-v7a"]` on release only — already in
   `android/app/build.gradle.kts`. 22.15 MB, no behaviour change on any device that can run the app.
2. **No ABI split and no App Bundle**, for the delivery-chain reasons above, until `/api/app/download`
   learns to pick a file per device — at which point re-read this section rather than reasoning from
   scratch.
3. **A developer with no handset is not blocked.** `debug` keeps all four ABIs, so the ordinary
   x86_64 emulator is untouched. For a *shrunk release* build with no phone in reach,
   `releaseAllAbis=true` in a gitignored `local.properties` widens it back and the build prints a
   lifecycle warning naming what it did and that the APK must not be published.

## Still owed, and it is the same debt as the top of this document

The (c) APK has never been installed on anything. This app now ships 17 MB of native code where it
previously shipped 37 KB, and the failure modes that adds are all install-time or first-call —
`INSTALL_FAILED_NO_MATCHING_ABIS` on an ABI nobody checked, or an `UnsatisfiedLinkError` the first
time the recogniser is opened. None of them is a compile error and none of them shows up in a green
build. **Before this ships: install the (c) APK on the M32 and open the identity-card control once.**
That single tap is what proves the whole native path, and it is the recogniser lane's own device
verification, so it need not be a separate trip.

---

# The merged tree, installed and run — 2026-08-09

That debt is paid, and paying it found the defect the paragraph above predicted the *shape* of and
not the *cause* of. Full account in `docs/DECISION-identity-card-ocr-on-android.md` under **"IT HAS
NOW BEEN RUN ON THE HANDSET"**; what belongs here is the size half.

## The number that ships is 26,211,648 bytes

Neither lane's figure describes the tree that merges them. Measured on the merged tree, real
`:app:assembleRelease`, `os.path.getsize` on the APK, `grep -c '^e: '` = 0:

| Release APK | Bytes | Size | vs the 6,636,115 baseline |
|---|---|---|---|
| lane 1's branch, reported | 26,195,264 | 24.98 MB | +19,559,149 |
| lane 2's branch, reported (row **c**) | 26,080,576 | 24.87 MB | +19,444,461 |
| **the merged tree, measured here** | **26,211,648** | **25.00 MB** | **+19,575,533 · 3.95×** |

All three are honest measurements of different trees, and the spread — 131,072 bytes, exactly 32
pages — is dex and alignment, not native code. ABIs read out of the APK's own central directory with
`zipfile`: `arm64-v8a`, `armeabi-v7a`, nothing else. Both `libmlkit_google_ocr_pipeline.so` entries
are STORED at 11,064,544 and 6,781,940 bytes with CRC-32 `5a02f1b7` and `264f5dd0` — byte-identical
to the AAR, as this document said, re-confirmed on the merged artifact.

## The two `abiFilters` blocks the merge produced, and why one had to go

Both lanes shipped an ARM filter. The recogniser lane put it in `defaultConfig`; this lane put it in
`buildTypes.release` behind the `releaseAllAbis` escape hatch. **Git merged both cleanly** — different
regions of one file, no textual conflict — and the result was wrong in two ways that no build error
would have shown:

1. **AGP unions the two sets.** A build-type `abiFilters` cannot subtract from `defaultConfig`'s, so
   with `releaseAllAbis=true` the release block adds nothing and the `defaultConfig` pair still
   applies. The escape hatch stopped widening anything **while still printing the lifecycle line
   saying it had** — the worst kind of broken flag, one that lies in the console.
2. **`defaultConfig` narrows `debug` too**, taking away the x86_64 emulator that this document's own
   recommendation (3) deliberately preserves, on a project whose CI runs no instrumented tests.

The merge keeps the `buildTypes.release` block alone, and `defaultConfig` now carries a comment
naming this defect so the next merge does not re-create it.

## The model really is the local one

The size argument's entire premise is that these megabytes buy a model that is *present*. From the
handset's own log during a read:

    DynamiteModule: Considering local module com.google.mlkit.dynamite.text.latin:10000
                    and remote module com.google.mlkit.dynamite.text.latin:0
    DynamiteModule: Selected local version of com.google.mlkit.dynamite.text.latin
    native: Loading mlkit-google-ocr-models/gocr/.../optical/conv_model.fb

Remote version `0` — there is no Play Services module — and the `.fb` weights come out of the
`assets/mlkit-google-ocr-models/` this document measured at 1,272,325 bytes. The bytes are doing the
job they were bought for. The whole read took **under one second** on the M32, cold load included.
