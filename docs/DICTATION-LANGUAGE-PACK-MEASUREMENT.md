# What the handset will actually admit to downloading

Measured 2026-08-09 on the fleet's own phone by `android/app/src/androidTest/.../DwLanguagePackProbeTest`.
Raw logcat, not inference. The complaint that started it: **the language-pack list offers a download
for Hindi and English and for none of the other seventeen.**

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
passed while the screen misreported seventeen languages. It now holds the thirty entries above.

## The consequence worth acting on

Offline dictation in the languages these workshops are actually run in **is not reachable through
Google's packs**. Odia is the language of the state this project works in, and it is not in the
catalogue and shows no sign of arriving. Any offline ASR in the other seventeen has to come from a
model this app ships or fetches itself.
