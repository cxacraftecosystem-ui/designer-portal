# What the handset will actually admit to downloading

Measured on the fleet's own phone by
`android/app/src/androidTest/java/com/designprototype/workshop/DwLanguagePackProbeTest.kt`.
Raw logcat, not inference. The complaint that started it: **the language-pack list offers a download
for Hindi and English and for none of the other seventeen.**

**TWO READINGS, FOUR DAYS APART, AND THE SECOND ONE IS THE PHONE AS IT IS NOW.** Everything from
*The device* to *The consequence worth acting on* is the **2026-08-09** reading, kept unchanged
because it is what `DwPackState.NO_OFFLINE_PACK` was derived from and it is the only reading in which
any of our nineteen is downloadable at all. The **2026-08-13** re-measurement is at the foot of this
file, under *Re-measured 2026-08-13*. Read that one for the current state of the handset; read this
one for why the code is shaped as it is.

Both are fixtures in `DwLanguagePackTest`: `galaxyM32BeforeTheDownloads` and `galaxyM32Today`.

## The device

| | |
|---|---|
| model | Samsung SM-M325F (Galaxy M32) |
| Android | 13 (API 33) |
| locale | `en-GB` |
| `voice_recognition_service` | `com.google.android.tts/…GoogleTTSRecognitionService` |
| `isOnDeviceRecognitionAvailable` | true |
| `isRecognitionAvailable` | true |

## The answer

`checkRecognitionSupport`, on-device recogniser, no `EXTRA_LANGUAGE` — the call the app makes:

```
installed = [en-GB]
pending   = []
supported = [en-US, de-DE, es-ES, fr-FR, it-IT, en-AU, en-IE, en-SG, ja-JP, de-AT, de-BE, de-CH,
             en-CA, en-IN, es-US, fr-BE, fr-CA, fr-CH, hi-IN, id-ID, it-CH, ko-KR, pt-BR, th-TH,
             cmn-Hans-CN, cmn-Hant-TW, pl-PL, ru-RU, tr-TR, vi-VN]
online    = []
```

**Thirty languages. Two of our nineteen: `hi-IN` and `en-IN`.** There is no Bengali, Tamil, Telugu,
Marathi, Gujarati, Kannada, Malayalam, Punjabi, Odia, Assamese, Urdu, Sanskrit, Konkani, Nepali,
Manipuri, Kashmiri or Sindhi in Google's on-device catalogue on this handset.

**So the download list is not a bug and cannot be widened.** The app is reporting the platform
correctly. No code change can make the other seventeen downloadable, because there is nothing to
download.

## Two theories, both wrong

Worth recording, because both were plausible from the source and both were checked:

**(a) "The probe carries no `EXTRA_LANGUAGE`, so the answer is scoped to the default locale."**
False. Asked once per language with `EXTRA_LANGUAGE` and `EXTRA_LANGUAGE_PREFERENCE` pinned, all
nineteen calls returned the **byte-identical** thirty-entry list. The answer is a device-wide
inventory, exactly as `probeIntent`'s comment claimed. Asking nineteen times buys nothing.

**(b) "The app prefers the on-device engine; the general one knows more."**
False, and worse. `createSpeechRecognizer` returned **`ERROR 14` (`ERROR_CANNOT_CHECK_SUPPORT`)** on
every call, with and without a language. The general engine on this handset cannot be asked at all.

## The real defect: a verdict from evidence never gathered

`online` is empty above. `DwLanguagePacks.kt` already documents why — a recogniser built with
`createOnDeviceSpeechRecognizer` returns an empty online list **by construction**, so empty means
*"not asked"*, not *"nothing online"*.

`dwPackState`'s final branch nevertheless read it as a finding:

```kotlin
else -> DwPackState.UNSUPPORTED   // "cannot do this language at all, offline or on"
```

Seventeen languages got that verdict, and the sentence it printed was:

> This phone's speech recogniser does not offer Odia. Type the answer in, or pick another language.

**That is false on this handset.** Dictation in those languages works — the code-13 fallback hands
them to the network recogniser and the words come back. The app was telling designers to abandon a
control that works, in the state whose language it was telling them to abandon.

It also contradicts the rule stated at the top of that same file: *"nothing below ever infers a state
it was not told."*

### The fix

A new state, `DwPackState.NO_OFFLINE_PACK`, for *no pack here and the online question was never
asked*. `UNSUPPORTED` survives only where an engine actually returned a populated `online` list and
the language was absent from it — i.e. where the claim has been measured. Neither is downloadable;
the correction is to the sentence, not to the button.

## The fixture that hid it

`DwLanguagePackTest` had a fixture named `galaxyM32` asserting this phone could fetch on-device packs
for **Odia, Bengali, Tamil, Telugu, Marathi and Gujarati**. It offers none of them. The fixture was
invented, and it made the suite agree with a device that does not exist — which is why the tests
passed while the screen misreported seventeen languages.

It was replaced with the thirty entries above, and **since 2026-08-13 it is two fixtures rather than
one**, because the handset moved: `galaxyM32BeforeTheDownloads` holds this reading, and
`galaxyM32Today` holds the one below. Neither is derived from the other and neither is derived from
the code — the lesson of this whole section is that a fixture nobody measured is a fixture that
agrees with a phone that does not exist.

## The consequence worth acting on

Offline dictation in the languages these workshops are actually run in **is not reachable through
Google's packs**. Odia is the language of the state this project works in, and it is not in the
catalogue and shows no sign of arriving. Any offline ASR in the other seventeen has to come from a
model this app ships or fetches itself.

---

# Re-measured 2026-08-13

Same handset, same probe, driven this time **without `connectedDebugAndroidTest`** — that task makes
AGP uninstall the app afterwards, which clears app data and signs the designer out. The
already-installed test APK was driven directly instead:

```
adb shell am instrument -w \
  -e class com.designprototype.workshop.DwLanguagePackProbeTest \
  com.designprototype.workshop.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s DWPACKPROBE
```

`OK (1 test)`, 5.014 s. Verified afterwards: app still installed, session intact.

## What changed: the two packs were downloaded

```
device sdk=33 model=SM-M325F locale=en_GB
onDeviceRecognitionAvailable=true
recognitionAvailable=true

installed = [hi-IN, en-IN, en-GB]
pending   = []
supported = [en-US, de-DE, es-ES, fr-FR, it-IT, en-AU, en-IE, en-SG, ja-JP, de-AT, de-BE, de-CH,
             en-CA, es-US, fr-BE, fr-CA, fr-CH, id-ID, it-CH, ko-KR, pt-BR, th-TH,
             cmn-Hans-CN, cmn-Hant-TW, pl-PL, ru-RU, tr-TR, vi-VN]
online    = []
```

`hi-IN` and `en-IN` have **moved from `supported` to `installed`** — that is what a completed
`triggerModelDownload` looks like through this API, and it is the only confirmation Android 13 ever
gives (there is no download callback on that platform at all). The supported list is down from thirty
entries to **twenty-eight, and now contains no Indian language whatsoever.**

| | 2026-08-09 | 2026-08-13 |
|---|---|---|
| installed, ours | — | **`hi-IN`, `en-IN`** |
| downloadable, ours | `hi-IN`, `en-IN` | **none** |
| `NO_OFFLINE_PACK`, ours | 17 | **17** |
| `supported` size | 30 | **28** |

## What did not change

Both theories are still false, re-checked on this run:

* `EXTRA_LANGUAGE` pinned, one call per language — the **byte-identical** device-wide list, nineteen
  times over. The answer is an inventory, not a query.
* The GENERAL engine answered **`ERROR 14` (`ERROR_CANNOT_CHECK_SUPPORT`) to all thirty-eight calls**
  and cannot be asked at all.

And the seventeen are still unreachable through packs **on any device**, because there is nothing to
download: Google's on-device catalogue on this handset carries no Bengali, Marathi, Telugu, Tamil,
Gujarati, Kannada, Malayalam, Punjabi, Odia, Assamese, Urdu, Sanskrit, Konkani, Nepali, Manipuri,
Kashmiri or Sindhi.

## The probe was printing a rule the app had retired

Worth recording as a defect in the instrument rather than in the app. `DwLanguagePackProbeTest`
carries its own copy of `dwPackState` — deliberately, so that it measures behaviour rather than
importing the thing it is checking — and that copy **had never been given the `NO_OFFLINE_PACK`
branch this document's own *The fix* section added.** So this run printed:

```
   bn-IN    UNSUPPORTED
   or-IN    UNSUPPORTED
   ...
```

for seventeen languages where the app itself shows **`No offline pack`**. The probe was reproducing
the exact defect the fix removed, on the same page as the evidence for it — and it would have been
cited as proof the defect was still live. Fixed 2026-08-13; the copied rule now ends
`online.isEmpty() -> "NO_OFFLINE_PACK"` before falling through to `UNSUPPORTED`, matching
`dwPackState`.

## What the screen does with this reading

`dwPackRowWorthShowing` admits INSTALLED, DOWNLOADING and DOWNLOADABLE and nothing else, so on this
reading the "Offline dictation" card draws:

| | rows | controls |
|---|---|---|
| before the row filter | 19 | 2 tickable, 17 inert with a paragraph each (1,207 words) |
| today | **2** — `Hindi`, `English (India)`, both *Works offline* | **no download control at all** — both INSTALLED ⇒ `dwPackOffer` = INSTALLED ⇒ `dwMayAsk` false for all nineteen. Only *Check again*. |

Both of those numbers are now pinned by tests against `galaxyM32Today`
(`DwLanguagePackTest`), which had **no coverage of the row filter at all** before this date.

---

## How this document is kept true

**The readings are not maintained. The code derived from them is.** That split is the whole
maintenance story, and this file is already built around it: two dated readings of one handset, kept
side by side rather than one overwriting the other, precisely because the earlier one is the only
state in which any of the nineteen languages was downloadable and it is what `DwPackState` was
derived from.

**Never edit a reading.** If the phone changes, re-run the probe and append a third dated section, as
2026-08-13 was appended to 2026-08-09. A measurement record that gets updated in place stops being
evidence and becomes an opinion with a timestamp.

| Claim class | Kept true by |
|---|---|
| Both readings | **Nothing. They are dated transcripts.** Re-run `android/app/src/androidTest/java/com/designprototype/workshop/DwLanguagePackProbeTest.kt` on the SM-M325F — it needs a device, so it is not in CI and never will be. |
| That the readings still describe *some* real handset's answer | They describe **one** handset (SM-M325F, Android 13, `en-GB`, Google TTS recognition service). Nothing here generalises to the fleet, and the fleet has Android 8 and 9 handsets that cannot be asked the question at all. |
| The behaviour derived from the readings — `dwPackState`, `dwPackOffer`, `dwMayAsk`, `dwPackRowWorthShowing` | **`DwLanguagePackTest` in `android/app/src/test/java/com/designprototype/workshop/data/`**, against the two fixtures `galaxyM32BeforeTheDownloads` and `galaxyM32Today`, which are these readings turned into data. This is the mechanical half: change the logic and a fixture disagrees in `:app:testDebugUnitTest`, on any machine, with no phone. |
| The "19 rows / 2 rows" table at the foot | The same tests. They were added on 2026-08-13 because the row filter had **no coverage at all** before that date — the section above says so, and it is the reason to distrust any similar count in this file that is not named as pinned. |
| The fixtures still matching the readings | **A human read, and it is the seam most likely to drift.** A fixture is a transcription of a logcat block; if somebody edits a fixture to make a test pass, the fixture stops describing the phone and every conclusion in this document is quietly detached from hardware. Diff a fixture against the reading it came from before changing it. |

**Review triggers:** any change to `android/app/src/main/java/com/designprototype/workshop/data/DwLanguagePacks.kt`,
to the probe test, or to either fixture in `DwLanguagePackTest`.

**Known unverified:** everything about handsets that are not this one. The probe answers what one
Galaxy M32 admits to; the seventeen languages it will not offer are *unoffered on this phone*, which
is not the same statement as *unsupported*, and this document is careful about the difference in
several places. Keep it careful.
