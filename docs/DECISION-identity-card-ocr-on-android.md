# Reading an Aadhaar card on the handset

> ## ⟲ THIS DECISION WAS REVERSED. The recogniser ships.
>
> **What this document originally said:** do not add an on-device text recogniser; the cost is
> ~3.8× the APK and it buys the *typing*, not the *checking*. Everything under "The measurement"
> below was the case for that, and every number in it was re-measured for this reversal and stands.
>
> **What was decided instead:** the bundled ML Kit Latin recogniser goes in, and the read happens on
> the handset with no connection. **The user read the argument below and overruled it.** This
> document keeps the argument in full rather than being rewritten to agree with the outcome — a
> decision record that quietly deletes the case it lost is worse than no record, because the next
> person cannot tell whether the trade was weighed or never seen.
>
> **The measured cost, from real `assembleRelease` runs before and after (bytes, not estimates):**
> **6,636,115 → 26,195,264 bytes.** +19,559,149 bytes, **3.95×**. See "What it actually cost".
>
> ### Why the trade reads differently to somebody who values an offline read above APK bytes
>
> The original argument's load-bearing sentence was: *"it buys typing, not checking — about ten
> seconds per artisan."* That is true **only where there is a connection**. Where there is not, the
> server reader does not save ten seconds; it does not run at all. The button was greyed out, and the
> sentence under it said so. So the honest comparison is not "ten seconds against 18 MB" — it is
> "a reader that exists against a reader that does not", on the handset, in the courtyard, which is
> the only place this application is ever used for this task.
>
> The original document weighed a cost that lands on **everybody** (the download) against a benefit
> it scoped to **the online case**. Reweighed with the field in view:
>
> - **The download is once per release; the courtyard is every artisan, every day.** The APK cost is
>   paid in a district town on someone's home wifi or a shop's connection, where it is a slow
>   download. The absence is paid twenty times an afternoon in a village where there is no
>   alternative at all.
> - **"Type it in" is not a neutral fallback for THIS field.** Twelve digits read off a laminated
>   card in courtyard light, into a phone keyboard, by somebody also running a workshop. Verhoeff
>   catches the typo — and then the designer is asked to type it again. The failure mode of typing is
>   not a wrong number, it is a *blank* number, because the third attempt is when the field gets
>   skipped. A blank deduplication key is the outcome this whole feature exists to avoid.
> - **The APK is not where this application's bytes were going to be saved anyway.** The R8 lane
>   found 11.91 MB of dead classes. That was the compressible fat; the recogniser is not competing
>   with it for the same budget twice.
> - **And on-device is the STRICTER reader**, which nobody expected. Building it surfaced a real
>   defect in the server's extraction — it will clip a card's 16-digit VID into a 12-digit "Aadhaar
>   number" printed nowhere on the card (see "The VID defect"). The phone refuses that. So the
>   change buys correctness as well as availability, and the correctness argument was not available
>   to whoever wrote the original recommendation because the defect had not been found yet.
>
> **What has NOT changed:** the unbundled variant is still refused, for the reason below that has not
> moved an inch. Nothing auto-commits. The number is still proposed and confirmed by a person.
>
> The rest of this document is the original, kept intact, with the new measurements and the new
> findings added in marked sections.

---

## The original recommendation, as written on 2026-08-08

**Original decision (superseded):** do **not** add an on-device text recogniser to the Android app.
Neither ML Kit Text Recognition variant is acceptable here, and there is no third option that is. The
handset gets the *attachment* half — photograph the card or pick a photograph of it, on the artisan
form and on the stage form — and the reading itself stays on `POST /design-workshops/ocr/identity`,
which is now actually wired up (it was not; see "The fallback was dead" below).

Recorded because the brief asked for the opposite, and a "no" that is not written down with its
numbers gets re-litigated by the next person from memory.

## The measurement

Artifact sizes read from `dl.google.com/dl/android/maven2` on 2026-08-08, by `curl -I` for the
`Content-Length` and by unzipping the AAR for the per-ABI native payload — not from memory and not
from the ML Kit documentation's "increases app size by about…" prose.

| Option | Artifact | Size | Works offline on first use |
|---|---|---|---|
| ML Kit, unbundled | `com.google.android.gms:play-services-mlkit-text-recognition:19.0.1` | 0.07 MB | **No** |
| | `com.google.android.gms:play-services-mlkit-text-recognition-common:19.1.0` | 0.42 MB | |
| | `com.google.mlkit:common:18.11.0` | 0.41 MB | |
| | **its own three artifacts, before R8** | **0.91 MB** | |
| ML Kit, bundled | `com.google.mlkit:text-recognition:16.0.1` (the Latin API shim) | 1.32 MB | Yes |
| | `com.google.mlkit:text-recognition-bundled-common:17.0.0` (**the model**) | **17.13 MB** | |

The bundled model is not an asset that a build could trim — it is compiled into one native library,
and the AAR carries four copies of it, one per ABI:

| `text-recognition-bundled-common-17.0.0.aar` entry | Uncompressed |
|---|---|
| `jni/x86_64/libmlkit_google_ocr_pipeline.so` | 11.09 MB |
| `jni/x86/libmlkit_google_ocr_pipeline.so` | 11.03 MB |
| `jni/arm64-v8a/libmlkit_google_ocr_pipeline.so` | **10.55 MB** |
| `jni/armeabi-v7a/libmlkit_google_ocr_pipeline.so` | **6.47 MB** |
| `classes.jar` | 0.94 MB |
| `third_party_licenses.txt` | 2.27 MB |
| all four ABIs | **39.13 MB** |
| the two ARM ABIs only | **17.02 MB** |

Uncompressed is the number that matters, not the 4.2 MB the `.so` compresses to inside the AAR:
`minSdk = 26` means AGP packages native libraries with `extractNativeLibs="false"`, which requires
them to be **stored, not deflated**, in the APK. A megabyte of `.so` is a megabyte of download.

## What that does to this APK

`docs/R8-MEASUREMENT.md` measured the release APK at **6,389,483 bytes — 6.09 MB**, and verified it
on a Galaxy M32. This app ships **no native code at all** today; ML Kit would be the first.

> **Correction, 2026-08-09, measured off the baseline APK rather than assumed:** it does ship native
> code — `libandroidx.graphics.path.so`, 37,392 bytes across four ABIs. Immaterial to the argument
> here, but it is what proves the packaging behaviour the argument depends on: every `lib/` entry in
> the built APK is **STORED**, so this app was already paying x86 tax, at 20,044 bytes, before ML Kit
> was proposed. See `docs/R8-MEASUREMENT.md`.

| Build | APK | Multiple of today |
|---|---|---|
| Today | 6.09 MB | — |
| Bundled ML Kit, ARM ABIs only (`abiFilters`, no x86 — so no emulator) | ~23.1 MB | **3.8×** |
| Bundled ML Kit, as this module is configured today (all ABIs) | ~45.2 MB | **7.4×** |

R8 cannot touch any of it. R8 is a Java/Kotlin shrinker; it saved 11.91 MB of *classes* and it will
save exactly zero bytes of `libmlkit_google_ocr_pipeline.so`. The 66% reduction that decision bought
would be spent four times over on one library, and spent permanently — the in-app updater downloads
the **whole APK** for every release, so this is not an install-day cost, it is the cost of every
update this application ever ships, on prepaid data, forever.

---

# ⟲ What it actually cost, measured

Two real `assembleRelease` runs on this machine, same tree, same R8 configuration, the only
difference being the recogniser. Sizes are `ls -la` on `app/build/outputs/apk/release/app-release.apk`
**in bytes**. The estimates above were close; these are the numbers.

| Build | APK (bytes) | vs. before |
|---|---|---|
| Before — merge base `af4add7`, no recogniser, all ABIs | **6,636,115** | — |
| After — bundled ML Kit Latin, `abiFilters` = the two ARM ABIs | **26,195,264** | **+19,559,149 · 3.95×** |

> **⟲ THE NUMBER THAT SHIPS IS 26,211,648 BYTES, AND IT IS NEITHER OF THE TWO IN THIS DOCUMENT.**
> Two lanes measured this independently and reported **26,195,264** (row above) and **26,080,576**
> (the table under "What it actually costs"). Both are honest measurements of their own branch;
> neither is a measurement of the tree that merges them. Measured on the merged tree by
> `os.path.getsize` on `app/build/outputs/apk/release/app-release.apk` after a real
> `:app:assembleRelease` (`grep -c '^e: '` = 0):
>
>     26,211,648 bytes
>
> against the 6,636,115 baseline that is **+19,575,533 bytes, 3.95×** — the shape both lanes gave,
> to the byte-count of the artifact that actually installs. The ABIs were read out of the APK's own
> central directory with `zipfile`: `arm64-v8a` and `armeabi-v7a`, nothing else. When a number in
> this document and a number off the APK disagree, the APK is right.

Where those 19,559,149 bytes went, read out of the APK with `unzip -v` (native libraries are
`Stored`, 0% — see `extractNativeLibs="false"` below, confirmed in the packaged manifest):

| APK entry | Stored bytes |
|---|---|
| `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so` | 11,064,544 |
| `lib/armeabi-v7a/libmlkit_google_ocr_pipeline.so` | 6,781,940 |
| **ML Kit native, both ARM ABIs** | **17,846,484** |
| everything else — ML Kit's Java/Kotlin after R8, plus resources | 1,712,665 |

So **91% of the cost is one native library, twice.** R8 shrank ML Kit's classes down to 1.7 MB and
could do nothing at all about the rest, exactly as the original argument said it would not.

### The one lever that was pulled: `abiFilters`

`buildTypes.release` carries `ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }`, behind a
`releaseAllAbis` escape hatch in gitignored `local.properties`; `debug` is deliberately left
unfiltered so the x86_64 emulator still works. **It is NOT in `defaultConfig`, and the difference is
not cosmetic** — see the comment at that spot in `android/app/build.gradle.kts`. Both lanes wrote an
ARM filter, one into `defaultConfig` and one into `release`, git merged both cleanly because they
sit in different regions of the file, and AGP unions the two sets: the escape hatch could no longer
widen anything while still printing the line saying it had, and `debug` silently lost the emulator.
The merge kept one block.

Measured sizes of the two x86 copies inside the AAR, which are **not** in the APK because of it:

| Avoided | Bytes |
|---|---|
| `jni/x86/libmlkit_google_ocr_pipeline.so` | 11,561,048 |
| `jni/x86_64/libmlkit_google_ocr_pipeline.so` | 11,626,128 |
| **total avoided** | **23,187,176** |

Without that one block the APK would be roughly **49.4 MB** — arithmetic on two measured stored
sizes, exact to within ZIP entry overhead, because stored entries do not compress. **The filter saves
more than three times what the entire application weighed before this change.** What it costs is
stated in the build file rather than left to be discovered: the release APK will not install on an
x86_64 emulator. CI does not run instrumented tests (`.github/workflows/android-build.yml:122`,
`docs/CI.md:220`), and every handset this has ever been installed on is ARM.

### The next lever, not pulled, with its number

An arm64-only artifact would be **≈19,406,072 bytes** — the measured APK less the two measured
`armeabi-v7a` entries. That is a further 6.8 MB, and it is available two ways: an App Bundle through
Play, or `splits { abi { … } }` producing one APK per ABI. Both are out of this lane because the
in-app updater downloads *the* APK and would have to learn to pick one, and shipping a build the
updater cannot serve is worse than shipping 6.8 MB. It is the largest remaining saving and it is
written down here so the next person does not have to find it again.

### One correction to the original document

It said "this app ships **no native code at all** today". Measured in the after-APK, that is not
quite right: `lib/*/libandroidx.graphics.path.so` is present at 10,096 bytes (arm64) and 7,252
(armeabi-v7a). It arrives with `androidx.compose.ui` and has nothing to do with ML Kit, so it was in
the before-APK too. It does not change any conclusion — it is 17 KB against 17.8 MB — but the claim
as written was wrong and a decision document that is right about the big number and casually wrong
about a small one teaches people to check the wrong things.

---

# ⟲ The script question, settled: Latin ships, Devanagari does not

`com.google.mlkit:text-recognition` (Latin) is in. `com.google.mlkit:text-recognition-devanagari` is
deliberately out. Both measured from `dl.google.com/dl/android/maven2` by `curl -I`:

| Artifact | `Content-Length` |
|---|---|
| `com.google.mlkit:text-recognition:16.0.1` | 1,383,148 |
| `com.google.mlkit:text-recognition-devanagari:16.0.1` | 2,015,832 |

Their POMs were read rather than guessed at: **both depend on the same
`text-recognition-bundled-common:17.0.0`**, so Devanagari's marginal cost is its own artifact plus
`play-services-mlkit-text-recognition-devanagari`, not a second copy of the 17 MB model.

**But the size is not the reason it is out. The reason is that the extraction could not use its
output.**

The number is ASCII digits or it is nothing, at three independent layers, each of which carries the
same comment and was written by somebody who had already thought about this:

1. `IdentityCardText.scanDigitRuns` scans `'0'..'9'` and explicitly **not** `Char.isDigit`;
2. `ArtisanIdentity.aadhaarError` refuses a non-ASCII digit **by name** — "remove any letters or
   symbols";
3. the server's `_AADHAAR_RUN` regex is `[0-9]`.

All three exist for one reason: `Artisan.aadhaarNumber` is a **deduplication key under a unique
index**, and a Devanagari `१२३४५६७८९०१२` stored beside `123456789012` is one artisan recorded as two
people — precisely the duplicate the column exists to prevent. So a Devanagari model would recognise
glyphs that this pipeline then discards, for 2,015,832 bytes and a second inference pass on a
mid-range handset while a designer stands there waiting. `IdentityCardTextTest` pins that refusal:
Devanagari and fullwidth numerals produce **zero** candidates and **zero** rejections.

It is also not a new assumption introduced by this lane. The server reader has always extracted with
an ASCII-digit regex and that path is the one in production — so "the number line is printed in
Western digits" is already load-bearing here, and it is load-bearing *successfully*.

**If a card is ever found whose number is printed only in Devanagari,** the fix is two parts — the
model to see the glyphs *and* a transliteration step before `ArtisanIdentity` — and the second part
does not exist. Adding the model on its own would change no outcome whatsoever.

### And the Pehchan card, which is where the honest answer is "I could not establish this"

The brief asked whether a Pehchan card differs, and to say how that was established rather than
assume. It was not established, and that is the finding:

- there is no Pehchan card specimen, photograph, or printed-layout specification anywhere in this
  repository, and no device on this machine to photograph one with;
- **the server does not parse it out of text either.** `identity_ocr.pehchan_candidates` receives
  values a *vision model* has already **labelled** as the Pehchan number and only normalises them.
  There is no text-extraction rule on the server to port.

So there is nothing to port and nothing to check a guess against. Which means the on-device reader
**does not attempt a Pehchan number at all** — see below — and the Devanagari question does not arise
for it, because no rule is being written for it in any script.

---

# ⟲ What actually shipped

**On-device first, server second, and the designer is told which answered.**

- **The phone is asked first**, always, connection or not. That is the feature.
- **If the phone finds nothing and there is a connection, the server is asked as well.** The fallback
  is kept deliberately: a bundled 10 MB model gives up on a creased, glared, badly-lit card that a
  large vision model still reads. Dropping it would trade one class of failure for another rather
  than removing it.
- **Every candidate carries its source to the button**, printed beside the number as "read on this
  phone" or "read by the server". The two readers do not have the same accuracy and do not fail the
  same way, and a person being asked to proofread twelve digits is entitled to know which produced
  them.
- **An on-device candidate shows no confidence figure at all.** ML Kit returns no calibrated
  per-number confidence for this, and "0.9 because it passed the checksum" would put a reassuring
  number beside digits whose entire purpose on that screen is to be doubted.

**One filter, both readers.** Everything from either reader goes through the same
`identityChoices` — same normalisation, same Verhoeff refusal, same deduplication, same kind gate.
A second confirm path is exactly how two readers drift until one of them stops honouring the masking
rule while both screens still look right.

**A digit string is not an Aadhaar number.** A run must be a *maximal* run of digits-and-single-
separators, exactly twelve digits, not starting 0 or 1, and Verhoeff-valid. A date of birth, a pin
code, a mobile number, an enrolment number and a VID are all refused, each with a test.

**Layout evidence orders candidates and never admits or excludes one.** When more than one number
survives, the one printed the way the card prints its number — on a line of its own, in a face larger
than the surrounding text — is offered first. Every survivor is still shown. That is what makes an
unverified size threshold safe to ship: a wrong guess costs a second glance down a list, and cannot
cost a designer a number they never saw. `IdentityCardTextTest` asserts that explicitly.

**Pehchan is server-only, and the control says so before a photograph is taken.** An Aadhaar number
proves itself; a Pehchan number has no checksum and no fixed length, so picking one out of raw
recognised text would be a guess with nothing downstream able to catch it. Guessing wrong there is
worse than not offering, because unlike a misread Aadhaar digit there is no validator that refuses
it. For an Aadhaar or a card-number field the buttons now work with no signal; for a Pehchan-only
field they are still disabled offline, with the reason in the disabled state.

**Everything the previous lane guaranteed still holds.** Nothing auto-commits. A candidate failing
Verhoeff is refused rather than warned about. The full number appears only in the confirm panel and
the edit box — `DwIdentityLocalReadTest` asserts that no message this lane added contains a run of
four or more digits. The photograph is still deleted on every exit and the scratch directory swept on
mount and dispose.

---

# ⟲ The VID defect, found while building this

`backend/app/services/identity_ocr.py` matches:

```
_AADHAAR_RUN = re.compile(r"(?<![0-9])((?:[0-9][ \-]?){11}[0-9])(?![0-9])")
```

Every Aadhaar card also prints a **sixteen-digit VID**, grouped 4-4-4-4. Against
`9174 9963 5947 4681` that pattern matches `9174 9963 5947` — the trailing lookahead inspects the
**space** after the twelfth digit, not the four digits after that — and yields a twelve-digit number
that is **printed nowhere on the card**. It then only has to satisfy Verhoeff, which one arbitrary
twelve-digit string in ten does.

**The client cannot defend against this.** A fabricated number only reaches the phone if it already
passed Verhoeff, so re-checking it there catches nothing. The designer is shown a plausible number,
confirms it against a card that does not contain it, and it becomes a deduplication key belonging to
nobody.

The on-device reader does not reproduce it: `IdentityCardText` requires the **whole** run to be
twelve digits, so a sixteen-digit run yields nothing — deliberately stricter than the server, with
the case named in the code and pinned by
`IdentityCardTextTest.the sixteen-digit VID beside the number yields nothing at all`, whose fixture
uses a VID prefix that *is* Verhoeff-valid so the test would fail if the rule were relaxed.

**This is reported, not fixed** — the backend parser is another lane, and its tests pin the current
behaviour. It is the strongest single argument for the on-device reader being first in the order.

---

# ⟲ R8 would have deleted the recogniser's boot path

Rule 1 of this repository, and it very nearly bit. ML Kit boots by reading `<meta-data>` off its own
`MlKitComponentDiscoveryService` and calling `Class.forName` on class names spelled inside the
meta-data **key**. `com.google.mlkit:text-recognition` pulls in three:

```
com.google.mlkit.common.internal.CommonComponentRegistrar        com.google.mlkit:common
com.google.mlkit.vision.common.internal.VisionCommonRegistrar    com.google.mlkit:vision-common
com.google.mlkit.vision.text.internal.TextRegistrar              play-services-mlkit-text-recognition-common
```

Nothing references any of them from code. AGP keeps the *service* — it is an `android:name` on a
manifest component — but a class name inside a meta-data attribute is a string, and R8 does not read
strings.

Checked rather than assumed, the same way this repo checked Retrofit and OkHttp: every `proguard.txt`
in the resolved ML Kit graph was unzipped out of the Gradle cache and read (`com.google.mlkit:common`,
`text-recognition-bundled-common`, `play-services-basement`, `play-services-base`; the others ship
none). **None keeps a `ComponentRegistrar`.** The three classes were also checked for
`@androidx.annotation.Keep`, which `play-services-basement` *does* keep generically — none carries it.

The failure this would have produced is the one this repository's build file already warns about: not
a build error, not a missing feature, but `TextRecognition.getClient()` throwing at the first tap on
a build that assembled and installed perfectly — in a courtyard, on the one path this lane exists to
make work offline, carrying 17.8 MB of shipped, downloaded, unreachable recogniser.

`app/proguard-rules.pro` now keeps them, and the release build's own output confirms it:
`r8-kept.txt` (`-printseeds`) lists all three classes **and their no-arg constructors** as seeds, and
the only rule that can match them is the new one. `mapping.txt` shows
`MlKitIdentityCardRecognizer`, `IdentityCardText`, `TextRecognition`, `TextRecognizerOptions` and
`InputImage` all present in the shrunk build.

> **⟲ Correction: "the only rule that can match them is the new one" is false.**
> `com.google.firebase:firebase-components:16.1.0` is pulled in by
> `play-services-mlkit-text-recognition-common`, `com.google.mlkit:common` **and**
> `com.google.mlkit:vision-common` (`:app:dependencies --configuration releaseRuntimeClasspath`), and
> its consumer `proguard.txt` is three lines, the third being
> `-keep class * implements com.google.firebase.components.ComponentRegistrar`. It appears in this
> build's own merged `app/build/outputs/mapping/release/configuration.txt` under that artifact's
> banner. The claim that every `proguard.txt` in the graph had been read and none kept a registrar
> did not hold — one does, and it is the one named after the interface the rule itself cites.
>
> The rule stays: `-keep class *` alone does not name the no-arg constructor that
> `Class.forName(name).newInstance()` needs, and that is what the body adds. What changes is the
> claim. The seeds, the identity mapping and the `r8-removed.txt` entries quoted above are all real
> and were re-confirmed on the merged tree — they just do not prove that this file caused them.
>
> The failure this section imagines — a crash at the first tap on a build that installed perfectly —
> **did happen**, and had nothing to do with R8. See "IT HAS NOW BEEN RUN ON THE HANDSET".

---

# ⟲⟲ IT HAS NOW BEEN RUN ON THE HANDSET — 2026-08-09 — AND IT DID NOT WORK

Read this section before the one below it, which was written when there was no device and is kept
because its list of what-was-owed is exactly what got checked.

**Device:** Samsung SM-M325F (Galaxy M32), Android 13, 5.8 GB RAM, arm64-v8a, over wireless adb.
**Build:** the shrunk, R8-minified **release** APK measured above, debug-signed so it could be
installed, pointed at a local API through `adb reverse tcp:8000 tcp:8000`.
**Card:** a rendered test specimen, banner-labelled as one, carrying the repository's own
Verhoeff-valid fixture `2345 6789 0124` printed large and alone, **plus** `VID : 2345 6789 0124 5678`
— a sixteen-digit run whose first twelve digits are that same valid number — plus a date of birth, a
pin code, a mobile number and an enrolment number.

## The recogniser never ran. Not on this card — on any card, on any device, ever.

`MlKitIdentityCardRecognizer.decodeBounded` measured the image with

```kotlin
context.contentResolver.openInputStream(source)?.use {
    BitmapFactory.decodeStream(it, null, bounds)
} ?: return null
```

The elvis binds to the value of `use { … }`, not to the stream, and `BitmapFactory.decodeStream`
with `inJustDecodeBounds = true` **returns null by contract** — that is the whole point of the flag;
the answer arrives in `bounds`. So `decodeBounded` returned null on every call, `scan` threw "That
photograph could not be opened on this device.", and `DwIdentityCardControl.send` caught it and fell
through to the server. **The 19.5 MB of bundled model shipped and was unreachable in 100% of reads.**

Nothing in this repository could have found it. `BitmapFactory` is an `android.jar` stub in a JVM
test; R8 keeps the code because it *is* called; and the symptom on a designer's screen is "the card
would not read", which is indistinguishable from a bad photograph and **invisible wherever there is
a connection, because the server answers instead**. It was found by installing the release build and
watching `dumpsys meminfo`: `libmlkit_google_ocr_pipeline.so` never appeared in `.so mmap`, because
no ML Kit class was ever touched. Fixed in `IdentityCardRecognizer.kt`, which now carries the whole
story at the function.

## After the fix, from the device's own logs and UI tree

Same handset, same release build, rebuilt with the one-line fix:

```
04:57:09.165  wm_on_activity_result_called: MainActivity, ACTIVITY_RESULT
04:57:09.425  DecoupledTextDelegate: Start loading thick OCR module.
04:57:09.427  DynamiteModule: Considering local module com.google.mlkit.dynamite.text.latin:10000
                              and remote module com.google.mlkit.dynamite.text.latin:0
04:57:09.427  DynamiteModule: Selected local version of com.google.mlkit.dynamite.text.latin
04:57:09.630  native: tflite_model_pooled_runner.cc:625] Loading
              mlkit-google-ocr-models/gocr/gocr_models/line_recognition_legacy_mobile/Latn_ctc/optical/conv_model.fb
04:57:09.632  native: … /optical/lstm_model.fb
04:57:09.790  tflite: Replacing 44 out of 46 node(s) with delegate (TfLiteXNNPackDelegate)
```

**"Selected LOCAL version … remote module :0"** is the offline claim, in Google's own words: the
model came out of this APK's `assets/`, not out of Play Services. And the whole read — decode, a
first-ever cold model load, and recognition — ran between `09.165` and `09.79`: **under one second**
on the target handset, which was previously unmeasured.

What the artisan form then showed, read out of the `uiautomator` tree rather than off a screenshot:

```
Check this against the card before using it
Use 2345 6789 0124
Aadhaar · read on this phone
```

- **One candidate, not two.** The VID line contributed nothing, on real recognised text and not
  only in a unit test — the maximal-run rule holds where the server's regex does not.
- **"read on this phone"**, so the server was never asked. (It could not have answered: this local
  backend returns "Identity-card scanning is switched off", which is precisely the message the
  broken build produced instead of a number.)
- Tapping the button put `2345 6789 0124` into the Aadhaar box and dismissed the panel.

## The safety properties, attacked on the device

- **No number reaches a log.** `adb logcat -d -b all` across the whole read: **zero** occurrences of
  `234567890124`, `2345 6789 0124` or `2345-6789-0124`. Not a grep of the source — a capture of the
  device during the read, which is the only test that counts.
- **A Verhoeff failure cannot reach the field.** Both readers converge on `identityChoices`, which
  gates on `ArtisanIdentity.isAadhaar` → `aadhaarError`, whose third rule is the checksum. There is
  no other route from a reader to `onUse`.
- **The parsing tests are real.** Breaking `scanDigitRuns` so a run stops at twelve digits — which
  is exactly the server's VID defect — turned two tests red with the fabricated number in the
  failure message (`the sixteen-digit VID beside the number yields nothing at all` and `a
  thirteen-digit run is not a twelve-digit number with a digit after it`). Restored, green again.

## ML Kit phones home, and the privacy paragraph above should say so

During the read the app logged `TransportRuntime.SQLiteEventStore: Storing event … name=FIREBASE_ML_SDK
for destination cct`, queued for `firebaselogging.googleapis.com`. That is ML Kit's own usage
telemetry — **not** the card, not the recognised text, and it does not block or delay the read, which
completes with no network at all. But "the card image stops leaving the phone" is the correct claim
and "nothing leaves the phone" is not, and this document should not be read as making the second one.

## What is STILL not verified on hardware

- **A genuinely radio-off read.** This phone is reached over wireless adb, and Samsung refuses
  `input`, `svc` and `cmd` binder calls from any process detached from the adb session — so the
  handset cannot be taken offline and driven at the same time. What is proven instead is the part
  that carries the claim: the model is loaded from the APK (`Selected local version`), and the
  candidate is labelled `read on this phone`, so no network was involved in producing it. The
  remaining untested surface is the wording of the offline helper sentences, and
  `usable = enabled && !working && (readableOnDevice || online)` — in which `readableOnDevice` is
  true for an Aadhaar field, so connectivity cannot disable the buttons.
- **The scratch directory after a camera capture, and after a mid-read process kill.** A release
  build is not debuggable, so `run-as` cannot read `filesDir`. The **picker** path was the one
  exercised and it copies nothing at all — the bytes are read straight from the content Uri.
- **Accuracy on a real laminated card in courtyard light.** One rendered specimen is not a corpus.
  What is now known is that the pipeline runs, in under a second, and that its output survives the
  filter; nothing here says how often a real card is read correctly.

---

# ⟲ What was still not proven when this lane handed over

**No recognition had ever been run.** ML Kit cannot run in a JVM unit test — it is a native pipeline
behind a Play Services `Task` — and the machine this lane was written on had no device and no
emulator (`adb` was not installed). So:

- **every claim about accuracy is unmade.** Whether ML Kit reads a laminated card at an angle in
  courtyard light, whether it groups the twelve digits onto one line rather than splitting them
  across two, how often it produces nothing at all — none of it is known here. The tests feed in
  lines *written by hand* to represent what a card should produce; they are not a recording of a real
  read.
- **the parsing IS proven.** `IdentityCardTextTest` (18 cases) and `DwIdentityLocalReadTest` (11)
  hold the filter, the refusals, the ordering, the provenance and the masking rule without a device.
  688 JVM tests pass, 0 fail.

**Before this ships, on a Galaxy M32, with a real card:** photograph an Aadhaar card in aeroplane mode
and confirm a number is offered and labelled "read on this phone"; confirm the same card in signal
still works; confirm a card the phone cannot read falls through to the server and is labelled "read
by the server"; and — the R8 check that matters — do all of it on a **release** build, because the
registrar keep above is the difference between working and a crash that no desk build reproduces.
This is the same hardware gap the handover already records against the offline claim itself.

Also unmeasured: **how long a read takes** on that handset, and how much heap the decode costs. The
decode is bounded to a 2,200 px longest edge for exactly that reason, which is a judgement rather than
a measurement.

> **⟲ Three corrections to the paragraphs above, made when the device work was actually done.**
>
> 1. **The list was right and it was worth insisting on.** Doing it found a defect that made the
>    whole feature dead — see the section above this one. Everything the handover said could only be
>    checked on hardware was the thing that was broken.
> 2. **The registrar keep rule was *not* "the difference between working and a crash".**
>    `com.google.firebase:firebase-components:16.1.0` is inside ML Kit's own dependency graph and
>    ships `-keep class * implements ComponentRegistrar` as a consumer rule; it is in this build's
>    merged `configuration.txt`. The rule in `proguard-rules.pro` adds the no-arg constructor the
>    library rule does not name, which is worth keeping — but the registrars were never at risk of
>    being deleted, and saying they were sent the next reader looking in the wrong place. The real
>    hazard was three lines away, in `decodeBounded`.
> 3. **The counts.** `IdentityCardTextTest` holds **20** cases, not 18, and the suite on the merged
>    tree is **718** passing, 0 failing — the two lanes' numbers (688 and 657) each describe their
>    own branch.
>
> **The read takes under a second** on the M32, cold model load included. Peak heap is still
> unmeasured; the 2,200 px bound remains a judgement.

## Why the small one is still disqualified

The unbundled variant is 0.91 MB of Java that R8 would shrink further, and it is the obviously
attractive row. It gets there by not shipping the model: Play Services downloads it on first use.

That is the failure `docs/DECISION-qr-scanning-on-android.md` already rejected, for a reason that has
not changed — the reader exists to be used in a courtyard on a handset that has had no signal for two
days, and first use is precisely the moment the model is not there. It fails silently, as "the card
would not read", which a designer answers by photographing the card four more times.

It is worth naming the mitigation and why it was not taken, because it is a real design and somebody
will propose it. `ModuleInstallClient.areModulesAvailable()` can be asked whether the OCR module is
present, and `deferredInstall` can fetch it at sign-in — when there IS signal — so the failure stops
being silent and stops landing at the worst moment. Two things kill it. First, it makes the
capability conditional on a moment of connectivity the application cannot guarantee ever happened on
*this* handset: a phone flashed the night before and signed in on the bus has no model, and no way to
get one before the workshop. Second, it makes card reading depend on Play Services being present and
current, and "the reader is missing on this particular phone" is a support conversation nobody in a
village can have. A capability that is there on some handsets and not others is worse than one that
is honestly absent everywhere, because the roster gets filled in differently depending on which phone
somebody picked up.

## Why not the bundled one, when it is the correct answer on every other axis

> **⟲ This is the section that was overruled.** It is kept verbatim because it is the strongest form
> of the case against, and because the reversal above answers it point by point — chiefly that
> "it buys typing, not checking" is a sentence that only holds where there is a connection.

It works offline, it is Google's own recogniser, and it would do exactly what the brief asked for.
It costs 3.8× the APK at best and 7.4× as this module is configured, and it buys **typing, not
checking**.

That last point is the one that decides it. The rule this lane exists to enforce is that an OCR
result is a *candidate* a human confirms against the card in their hand — a misread digit in a
deduplication key is worse than an empty box. So the recogniser never removes the reading step; it
removes the **typing** step, about ten seconds per artisan. And the Verhoeff checksum that catches a
misread catches a typo with exactly the same power, so the accuracy argument is a wash too: a
mistyped Aadhaar number and a misread one die on the same check.

Ten seconds per artisan against a 3.8× APK — where the download that fails at one bar is a designer
who cannot install the app at all on the morning of a workshop, and there is no manual fallback for
*that* — is not a trade this application should take. `DECISION-qr-scanning-on-android.md` refused
9.44 MB of bundled ML Kit for the barcode scanner, which was a *better* deal than this one: it bought
the whole capability rather than a shortcut to it, and there a 0.58 MB pure-Java alternative existed.
Here there is none.

## What else was looked at

**The card's own QR code.** Every Aadhaar card carries one and the app could decode it for well
under a megabyte, which would be the ideal answer. It does not work: by UIDAI's published Secure QR
specification the code carries a *reference id* — the last four digits of the number plus a
timestamp — precisely so that scanning a card cannot yield the full number. Whatever else it gives,
it cannot give the twelve digits this field stores. (Read off the specification, not verified
against a card here — there is no card and no scanner on this machine. It is the one claim in this
document that was not measured, and it only rules an option out.)

**Tesseract.** `numFound: 0` on Maven Central for `tesseract4android`; it is published through
JitPack, which builds from source on demand. Putting a build-time dependency on a service that
compiles a native library on request, into the release path of a repository whose premise is working
without a network, is a worse problem than the one it solves — before considering that it also ships
`libtesseract` and `libleptonica` per ABI plus a language data blob, and that its accuracy on a
laminated card photographed at an angle is exactly the thing that cannot be verified on a build
machine with no device and no corpus.

**A purpose-built digit recogniser.** Rejected without measurement, which is the honest reason to
reject it: there is no device here, no corpus of card photographs, and therefore no way to produce
an accuracy number. An unmeasurable recogniser writing into the repository's deduplication key is
not a feature, it is a defect with a camera icon.

## The regression being accepted

> **⟲ NO LONGER TRUE FOR AN AADHAAR NUMBER.** This was the cost the original decision accepted, and
> removing it is the entire purpose of the reversal. Offline, an Aadhaar or card-number field now
> reads on the phone. It remains true for a **Pehchan-only** field, for the reason given above:
> a Pehchan number has no checksum, so there is nothing to recognise it by. The paragraph below
> describes the state before 2026-08-09.

**On a handset with no signal, the number is typed.** That is the whole of it, and it is what the
app did yesterday. The stage form and the artisan form both say so, in the disabled control, before
a photograph is taken rather than after a two-minute timeout.

What was added instead, at zero bytes:

- **Both ways in.** Photograph the card *or* pick a photograph already on the phone — the second one
  matters more than it looks, because a designer who photographed the card and then lost signal can
  read it later without asking the artisan for the card again.
- **The reader reaches the artisan form.** `Aadhaar number` and `Artisan Pehchan Card number` on
  `ArtisanForm` had no camera path at all; the OCR control existed only on the design-workshop stage
  field. Those two boxes are where the deduplication key is actually entered.
- **Every candidate is checked on the device** against the same Verhoeff rule the server applies —
  one shared implementation in `data/ArtisanIdentity.kt`, so the handset and the API cannot come to
  different conclusions about the same twelve digits. A candidate that fails it is *refused*, not
  offered with a warning, because the server has already applied the same filter and anything that
  survives the wire and fails here is a transport or shape problem, not a card.
- **The photograph is never kept.** See `DwIdentityOcr.kt` for the rule and the sweep that cleans up
  after a process death mid-flow.

## The condition under which this should be revisited

> **⟲ Overtaken, and the condition was never one this application could meet.** It was not waited
> for — see "Overruled" — and when the delivery chain was actually read rather than assumed, an App
> Bundle turned out to *break* the publisher: `publishAppUpdate` reads
> `applicationInfo.sourceDir`, which under a bundle install is the base split alone. So Play
> delivery is not a threshold this app crosses; it is a different update path.
>
> The saving the condition was reaching for is still real and still collectable: arm64-only takes
> the measured APK from 26,080,576 bytes to 19,271,029. The blocker is the in-app updater, which
> downloads *the* APK — see "The next lever, not pulled".

If this application is ever distributed as an **App Bundle through Play** rather than as a
side-loaded APK, Play delivers only the installing device's ABI and the bundled model costs
**+10.55 MB on an arm64 handset** with no x86 copies and no `abiFilters` trickery. At that point the
trade is 6.09 → ~16.6 MB, the update cost stops being borne four times over, and this decision is
worth taking again with a device in hand to measure recognition accuracy on real cards. Nothing
below that threshold changes the arithmetic.

---

## The fallback was dead, and that is what actually got fixed

While measuring the above, the existing server path was checked rather than assumed — rule 4 — and
it does not work on either client.

`IdentityOcrResult.payload()` in `backend/app/services/identity_ocr.py` returns, verbatim (printed by
running it, not read off the source):

```json
{
  "aadhaarCandidates": [
    {"value": "234567890124", "kind": "AADHAAR", "confidence": 0.8, "masked": "XXXX XXXX 0124"}
  ],
  "pehchanCandidates": [],
  "rejectedAadhaarCount": 0,
  "provider": "gemini",
  "requiresConfirmation": true
}
```

There is no `number` key. Both clients read one:

- `DwIdentityOcrDto` (`android/…/data/DwReferenceStore.kt`) declared `number`, `documentType`,
  `name`, `confidence`, `message`. Its `Json` is configured `ignoreUnknownKeys = true`, so a perfect
  read decoded to `number = ""` and the panel said *"No number could be read from that photograph."*
- `DwIdentityOcrResult` (`frontend/lib/designWorkshops.ts`) declared the same five, and
  `IdentityCardReader.tsx` did `(result.number ?? "").replace(/\D/g, "")` — same outcome.

So on both surfaces, every successful read was reported to the designer as a failure, and the only
visible symptom was a card that "would not scan". Both clients now decode the shape the server
actually sends, and a unit test on each side pins that shape against the exact JSON above so it
cannot drift again.

---

## The reader was then offered to accounts the endpoint refuses (found in verification, fixed)

Moving the control onto the **artisan form** moved it out from behind the permission that had been
covering it, and the two rules do not nest:

| | Rule | Shape | Admits |
|---|---|---|---|
| Artisan form (`/artisans/new`, Android `EntryMode.ARTISAN`) | `require_record_creator` / `canCreateRecords` | rank threshold | Researcher **and above** |
| `POST /design-workshops/ocr/identity` | `_require_designer` / `can_run_design_workshops` | **a SET** | Designer, Admin, Master Admin |

A **PROFESSOR** satisfies the first and fails the second while *outranking* a designer — the one
non-monotonic predicate in `backend/app/core/deps.py`, and it is deliberate. Measured rather than
reasoned about: `backend/tests/test_design_workshop_gate.py` was run here and asserts the 403 for
`RESEARCHER` and `PROFESSOR` by name (9 passed).

Neither client caught it on its own. The web probes with `serverOffersRoute`, which issues a **GET**
against this **POST-only** route and reads anything other than 404 as "present" — and a GET answers
**405 from the router before any dependency runs**, measured against the running API with no token
at all:

```
curl -o /dev/null -w "%{http_code}" -X POST http://localhost:8000/api/design-workshops/ocr/identity  -> 401
curl -o /dev/null -w "%{http_code}"      http://localhost:8000/api/design-workshops/ocr/identity  -> 405
```

So the probe says "yes" to every signed-in account alive, and Android has no probe at all.

**Why it is not merely a button that errors.** The 403 arrives *after* the request. An ungated
control means a researcher photographs somebody's Aadhaar card and the image is uploaded to a
third-party vision model before anything refuses it — the photograph is taken and transmitted, and
only then declined. Hiding the control is the only point at which that is preventable client-side.

Fixed by mirroring the server's set, never re-deriving it:

- Android — `MainActivity.ArtisanForm` computes `canReadIdentityCards` from
  `FieldPermissions.canRunDesignWorkshops(repository.cachedUser())` and wraps both call sites.
  `remember`ed with no key, because a role cannot change while the screen is mounted, so the control
  cannot appear and disappear between frames. Fails closed on no cached user.
- Web — `IdentityCardCapture` reads `useAuth()` and `canRunDesignWorkshops(user)`, and renders
  nothing otherwise. The check sits with every other hook and folds into the existing early return,
  and the route probe is skipped entirely for an account that could not use the answer.
- Pinned by `frontend/e2e/identity-ocr-unit.spec.ts`, which asserts that the same Professor passes
  `canCreateRecords` and fails `canRunDesignWorkshops` — the two rules not nesting is the whole
  reason the control needs a guard of its own.

The stage form was never exposed: the whole design-workshop destination is already behind
`canRunDesignWorkshops` in `AppNavigation.kt` and `ROUTE_GUARDS`.

---

# Overruled — 2026-08-09. The bundled recogniser ships.

The user read the case above and decided the other way: **bundled ML Kit goes in, so the read happens
on the device and needs no connection.** This section says why the same facts read differently to
somebody who values an offline read above APK bytes, and what the decision costs when it is measured
rather than estimated.

Nothing above has been altered. The arithmetic in it is correct and it is still the bill.

## What the "no" got wrong was not the arithmetic — it was where the comparison was standing

The refusal turns on one sentence: the recogniser "buys **typing, not checking**", about ten seconds
per artisan, and a misread digit and a mistyped digit die on the same Verhoeff check.

That is true **on a desk with a connection**, where the server reader is standing right there and the
only thing an on-device reader adds is speed. It is not true in the courtyard, and the courtyard is
the only place this control is ever used. With no signal, `POST /design-workshops/ocr/identity` is
not a slower reader — it is **no reader**. The disabled control and the honest sentence the app puts
under it are an accurate description of nothing happening at all.

So the trade was never "ten seconds against 17 MB". It is "the capability existing at the moment it
is wanted, against 17 MB", and the document above priced the first term at its value in the one place
the feature is not needed. That is the whole of the disagreement, and once it is named the "no" does
not survive it. Every other line of the analysis above — the model is unshrinkable, R8 cannot touch
it, the update cost is borne on every release — remains true and is now the thing to minimise rather
than the thing to refuse.

## And there is a second gain the size argument never weighed: the card image stops leaving the phone

Today a read means uploading a photograph of somebody's Aadhaar card to a third-party vision model —
`"provider": "gemini"` in the payload printed above. This document already treats that as serious
enough to gate the control on it: *"a researcher photographs somebody's Aadhaar card and the image is
uploaded to a third-party vision model before anything refuses it — the photograph is taken and
transmitted, and only then declined."*

An on-device read removes that transmission entirely for every read it serves. The most sensitive
identifier in the country stops being sent to a third party to be read, and the app stops needing a
network round trip, a provider quota and a provider cooldown to do it. That gain is not measured in
megabytes and does not appear anywhere in the table above, and on this application's own stated
values it is worth more than the bytes it costs.

## What is NOT claimed by the overrule

- **Accuracy on real cards is still unmeasured**, exactly as the section above says. There is no
  device, no corpus of card photographs and no way to produce a number here. Bundled ML Kit is
  Google's own recogniser rather than a home-made one, which is a reason to expect it to work, not
  evidence that it does.
- **The candidate is still a candidate.** The rule the original lane exists to enforce is unchanged:
  an OCR result is confirmed by a human against the card in their hand, and `ArtisanIdentity`'s
  Verhoeff check still refuses anything that fails it. Moving the reader on-device changes where the
  reading happens, not who is responsible for it.
- **The server reader is not removed.** It is the path for the web client, which has no bundled
  model, and it is what the `Gemini` provider is still for.
- **The Aadhaar QR code is still no help.** Nothing about the overrule changes the UIDAI Secure QR
  specification: it carries a reference id, not the twelve digits.
- **The barcode decision does not fall with it.** `docs/DECISION-qr-scanning-on-android.md` chose
  ZXing over 9.44 MB of bundled ML Kit barcode scanning. Text recognition and barcode scanning are
  separate bundled models in separate native libraries; adding one does not make the other free. It
  would share `com.google.mlkit:common` and `vision-common` only, which is the small part.

## What it actually costs, measured — and it is not 7.4×

The table above priced this at **~45.2 MB, 7.4× today's APK**, from unzipping the AAR. That was the
right shape and it was 1.8 MB optimistic. Measured instead — five real `assembleRelease` runs, sizes
read off the files, full table and per-group attribution in **`docs/R8-MEASUREMENT.md`**:

| Release APK | Bytes | Size | Multiple of today |
|---|---|---|---|
| Today, before ML Kit | 6,636,115 | 6.33 MB | 1.00× |
| Bundled ML Kit **as this module was configured** — all four ABIs | 49,307,952 | 47.02 MB | **7.43×** |
| Bundled ML Kit, **as it now ships** — `abiFilters` = the ARM pair | **26,080,576** | **24.87 MB** | **3.93×** |

**22.15 MB of the estimated bill was never a real cost.** `android/app/build.gradle.kts` set no
`abiFilters` at all, so every build packaged native libraries for all four ABIs — including `x86` and
`x86_64`, which are **emulator** architectures that no handset this app is carried into a village on
can run. Half the model was being shipped to devices that cannot exist in the field. That is now
filtered in the release build, measured at 23,227,376 bytes off the APK, and the debug build keeps
all four ABIs so the emulator still works for a developer with no phone.

So the honest headline is **6.33 → 24.87 MB, 3.93×**, not 7.4×. That is still the largest single
increase this application has ever taken and it should be stated plainly rather than softened: the
update every designer downloads on prepaid data goes from 6 MB to 25 MB, on every release, for ever.
The recogniser has to be worth that, and the decision above is that an identity read which works in a
courtyard with no signal is.

**Two more levers exist and are measured but not taken** — dropping `armeabi-v7a` (a further 6.49 MB,
refused without a roster of what handsets are actually in the field) and `useLegacyPackaging`
(a further 9.52 MB off every *download*, in exchange for 7.52 MB of permanent on-device storage).
Both are written up with their numbers in `docs/R8-MEASUREMENT.md` so that neither has to be
re-derived by the next person who looks at this.

**The "revisit if it ever ships through Play" condition above is now moot for the size reason**, and
worth correcting rather than leaving: it was checked, and this application cannot use Play delivery
without replacing its whole update path. `GET /api/app/download` is one redirect to one object,
`WorkshopRepository.publishAppUpdate` publishes by reading `applicationInfo.sourceDir` — the base
split alone under a bundle install — and the update prompt the handset shows has no "Later" button.
The condition should now read: revisit if the *delivery chain* is ever replaced.
