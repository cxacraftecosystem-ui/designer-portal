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
