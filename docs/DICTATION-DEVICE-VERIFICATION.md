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
work in a village with no signal, and the app cannot do it for them.

## The other half of the same defect

The message shown when both engines are exhausted **already existed** in the codebase before this
fix. It sat on the network-failure path — which a language error never takes — so it could not be
reached by the case it was written for. Another instance of this repository's commonest defect
(complete code with no route to it), this time in a string rather than a module.
