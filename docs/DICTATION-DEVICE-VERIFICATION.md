# The microphone fix, verified on hardware

**Device:** Samsung SM-M325F (Galaxy M32), Android 13. **Build:** debug APK from `main`, pointed at
the local API through `adb reverse`.

## What was wrong

Every tap of the microphone on a design-workshop stage field produced:

> Dictation stopped unexpectedly (code 13). Type the answer in, or try again.

Code 13 is `ERROR_LANGUAGE_UNAVAILABLE`. It fell to the generic `else` arm of `onError`, whose
advice cannot ever work — no number of further taps downloads a language pack.

The cause was a cost of preferring the offline engine that had never been handled. On API 33+ the
on-device recogniser is used whenever the device reports one, and it reports 13 for any language
whose pack the owner has not downloaded — **including Hindi**, which is this app's default and the
language most of these workshops are run in.

## The proof, from the device's own logs

With the fix installed, the microphone was tapped on a stage field with the language set to Hindi —
the exact configuration that failed before. The system log shows the on-device engine failing
exactly as diagnosed:

    E SodaSpeechRecognizer: Failed to get language pack of required locale: error 13
    W RecognitionClient: #onError space agsa_transcription_LANGUAGE_PACK_ERROR code 13!
    W RecognitionServiceImpl: Speech recognition error type LANGUAGE_PACK_ERROR with error code 13

**The Hindi pack is genuinely not installed on this handset.** That is the trigger, and it still
fires — the fix does not prevent it and could not.

What changed is what the app does next. It did **not** show "Dictation stopped unexpectedly". It
caught the 13, fell back to the network recogniser, and dictated: the field showed
*"Listening — speak now."* with live Devanagari partials streaming in as the recogniser heard
sound. `grep` over the app's own error strings returns nothing for the run.

So the failure the designer used to hit is now invisible to them, which is the correct outcome: the
engine that could not serve Hindi handed over to the one that could, and nobody had to know which
answered.

## What is still true, and deliberately so

The fallback needs a connection, because the network engine is a network service. A handset that is
BOTH missing the language pack AND offline still cannot dictate — and now says so in a sentence that
names the fix ("download it in the phone's speech or keyboard settings, choose another language, or
type the answer in") rather than telling the designer to try again.

That is the honest floor. Downloading a language pack is the only thing that makes Hindi dictation
work in a village with no signal.

## What changed after that: the app now offers the pack

The paragraph above used to end "…and the app cannot do it for them." That was true of the API the
code was using and not of the platform. From **API 33** Android will answer both halves of this
without being made to fail first:

- `SpeechRecognizer.checkRecognitionSupport(intent, executor, callback)` returns a
  `RecognitionSupport` carrying `installedOnDeviceLanguages`, `pendingOnDeviceLanguages`,
  `supportedOnDeviceLanguages` (supported but **not** downloaded) and `onlineLanguages`. That is how
  the app knows the Hindi pack is missing without producing a code 13 in a courtyard.
- `SpeechRecognizer.triggerModelDownload(intent)` asks for one. API 34 adds the
  `(intent, executor, ModelDownloadListener)` overload with `onProgress` / `onSuccess` /
  `onScheduled` / `onError`.

`triggerModelDownload` is documented as an **attempt**, not a promise — "This might trigger user
interaction to approve the download. Callers can verify the status of the request via
`checkRecognitionSupport`" — and on Android 13 it reports nothing at all. Both facts are said on
screen rather than papered over with a spinner. **No size is ever printed: the platform does not
report one**, in either call, and a figure beside somebody's prepaid data bundle would be invented.

**Where it is reached.** A dismissible card on the dashboard, once, after install; the offer that
opens when a language whose pack is missing is chosen in the dictation language list; and a permanent
list of all nineteen under Settings › Appearance & accessibility. Nothing downloads by itself,
nothing is ticked to begin with, and no download is offered where there is no connection to carry it.

**Below API 33 the answer is "unknown", in that word.** minSdk here is 26 and much of the fleet is
Android 8 and 9, where the platform cannot be asked at all. Those handsets keep exactly the floor
described above, and the settings list says so instead of guessing.

## The other half of the same defect

The message shown when both engines are exhausted **already existed** in the codebase before this
fix. It sat on the network-failure path — which a language error never takes — so it could not be
reached by the case it was written for. Another instance of this repository's commonest defect
(complete code with no route to it), this time in a string rather than a module.

---

## How this document is kept true

**It is not, and it should not be.** This is a hardware verification record: one handset
(Samsung SM-M325F, Android 13), one debug build, one afternoon, with the device's own logcat pasted
in. Its value is entirely that it is a transcript of something that happened — and a transcript that
somebody keeps editing to stay current is no longer evidence of anything.

**So do not update the readings. Re-verify, and write a new dated section.** The two 2026-08-09
logcat blocks are the point of the file; if the app's behaviour changes they become the record of
what it used to do, which is exactly what you want when the next code-13 report arrives.

What that leaves is a short list of things a reader might mistake for durable facts:

| Claim | What it actually is |
|---|---|
| "The Hindi pack is genuinely not installed on this handset" | True of that phone on that day. It has since changed — Hindi and English (India) were downloaded, and the current reading of the same handset is in [DICTATION-LANGUAGE-PACK-MEASUREMENT.md](DICTATION-LANGUAGE-PACK-MEASUREMENT.md) under *Re-measured 2026-08-13*. Read that one for the phone as it is; read this one for the failure the fix was written against. |
| "Below API 33 the answer is unknown" | A platform limitation, not a measurement, and durable while `minSdk` stays 26. |
| The described behaviour — catch 13, fall back to the network recogniser, offer the pack | **This is the part that can silently rot**, because it describes live code rather than a log. It lives in `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwDictation.kt` (the `onError` arms), `android/app/src/main/java/com/designprototype/workshop/data/DwLanguagePacks.kt`, and `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwLanguagePackUi.kt` for the offer surfaces. `DwLanguagePackTest` in `android/app/src/test/java/com/designprototype/workshop/data/` pins the pack-state logic against captured device fixtures; **nothing pins the fallback itself**, which needs a recogniser and therefore a device. |
| "Nothing downloads by itself, nothing is ticked to begin with" | A consent property, and the one worth re-reading `DwLanguagePackUi.kt` for after any change to that screen. A default that flips here costs a designer their data allowance without being asked. |

**Review triggers:** the `onError` handling in `DwDictation.kt`, anything in `DwLanguagePacks.kt`, or
a `minSdk` change.

**Re-verification is a device job.** `adb logcat` on an SM-M325F with the dictation language set to a
language whose pack is absent, which is the exact configuration reproduced above. There is no way to
do it from this machine, and a claim in this file that was not read off a device does not belong in
it.
