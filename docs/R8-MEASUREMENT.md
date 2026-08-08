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
