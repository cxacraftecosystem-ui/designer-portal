# Scanning a QR code on the handset: ZXing, not ML Kit

**Decision:** use `com.google.zxing:core` for on-device QR decoding. Do **not** use either ML Kit
barcode variant. Recorded because the obvious choice (ML Kit, Google's own, better on live frames)
is the wrong one here for a reason that is specific to this application and not obvious from the
library comparison.

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
