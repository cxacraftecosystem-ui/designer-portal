# Putting the speech model on a phone with a cable

**Two commands and about ninety seconds.** This is how the offline speech model gets onto a handset
without spending 292 MB of somebody's data allowance. It is not a debug backdoor: the bytes go through
exactly the same fingerprint check as a download would, because it is the same code.

> **AMENDED 2026-08-13, AND AGAIN ON 2026-08-20 WHEN THE SECOND HALF OF THE 08-13 AMENDMENT WENT
> FALSE.** The sentence here originally said the cable was *"the only route that works at all"*. The
> deployment has served the artifact over HTTP since 2026-08-13 —
> `GET /api/asr-models/{id}/files/{name}`, resumable, digest-gated, designer-entitled
> (`docs/ASR-MODEL-HOSTING.md`), exercised against **this exact 365 MB file** and proved to reassemble
> from two Range requests to the same digest.
>
> The 08-13 amendment then said **"no surface in the app fetches from the endpoint yet"**, on the
> grounds that `DW_ASR_MODEL_ARTIFACTS` pins only the GitHub `.tar.bz2`. **That is no longer true, and
> the sentence is struck rather than deleted because this is the most-read operator document and the
> claim is the exact thing it exists to answer.**
> One surface does fetch from the endpoint: `DwAsrModelSource.DEPLOYMENT_ENDPOINT` →
> `DwAsrModelController.downloadFromEndpoint`, built on `DwAsrModelEndpoint.kt`, which is a route
> beside the pinned-container catalogue rather than a row inside it — so `DW_ASR_MODEL_ARTIFACTS` is
> still the one `.tar.bz2` row and that fact no longer implies what it used to.
>
> **The bzip2 argument below is SUPERSEDED, not pending.** It reasons about whether the upstream
> archive should be republished as a `.zip` so the app could open it. Serving the two files
> UNPACKED, per file, deletes the question outright: there is no archive on that route, so
> `DwAsrContainerFormat` and the bzip2 refusal simply do not apply to it. Do not open the
> republish-as-`.zip` work item; it has no remaining purpose.
>
> **What has NOT changed is that the cable is the route a designer can actually use today**, and that
> is now for a different reason: the endpoint is dark until an operator sets `ASR_MODEL_DIR`
> (`docs/ASR-MODEL-HOSTING.md` §3), and until then every handset that asks is told nothing is
> published and falls through to the card below. `Settings.asr_model_dir` defaults to `None`, both
> `.env.example` files leave `ASR_MODEL_DIR` commented out, and no deployment manifest in this
> repository sets it — checked, not assumed. And the cable stays useful afterwards for the case it
> was written for, which no endpoint addresses: a phone whose designer has no data allowance to spend
> at all.

---

## The two commands

The app looks in two places, in this order. **Use the first one.**

```bash
# 1. The app's own external files directory. adb can write it, the app can read it with no
#    permission at all, and it is deleted when the app is uninstalled.
adb shell mkdir -p /sdcard/Android/data/com.designprototype.workshop/files/dwasr
adb push model.int8.onnx tokens.txt \
  /sdcard/Android/data/com.designprototype.workshop/files/dwasr/
```

```bash
# 2. OR, if the files are already staged for DwAsrEngineProbeTest, leave them where they are —
#    this path is checked too, so nobody has to push 350 MB twice.
adb push model.int8.onnx tokens.txt /data/local/tmp/dwasr/
adb shell chmod -R 755 /data/local/tmp/dwasr
```

Then, in the app: **Settings › Appearance & accessibility › Offline speech model → "Install the model
from this phone"**.

That is the whole procedure. What follows is why each part of it is the way it is.

---

## Where to get the two files

They are **not in this repository** — 365 MB of somebody else's model has no business in git — and
they are not invented. They come out of one published archive:

```bash
curl -L -O https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/\
sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2

sha256sum sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2
# cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed
# 292,571,207 bytes

tar xjf sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2
sha256sum model.int8.onnx tokens.txt
# e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c  model.int8.onnx  (365,352,120 bytes)
# a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31  tokens.txt       (    86,423 bytes)
```

Those three digests are pinned in the app — the container's in `DW_ASR_MODEL_ARTIFACTS`
(`data/DwAsrModelInstall.kt`), the two files' in `DW_ASR_MODELS` (`data/DwAsrModel.kt`). **If your
`sha256sum` disagrees with any of them, stop.** The app will refuse the file anyway; the point of
checking here is to find out on a laptop rather than after a 292 MB download on a field connection.

---

## Why a cable at all, and why the download button does not work yet

> **SCOPE NARROWED 2026-08-20. EVERYTHING IN THIS SECTION IS TRUE OF THE UPSTREAM `.tar.bz2` AND OF
> NOTHING ELSE.** It was written when that archive was the only thing the app could be pointed at, so
> it reads as a statement about the app. It is not one any more: the deployment-endpoint route
> (`DwAsrModelEndpoint.kt` → `DwAsrModelController.downloadFromEndpoint`) fetches the two files
> unpacked and never meets an archive, and the table below is the reason the CONTAINER route is still
> the one that cannot run. Read "the app cannot fetch this file" as "nothing in this build can open a
> `.tar.bz2`", which is what it always meant and is still exactly true.

**The app cannot fetch this file.** It knows the URL, it knows the size, it knows the digest — and
upstream publishes it as a `.tar.bz2`, and **Android's class library has no bzip2 decoder.**
`java.util.zip` is Deflate and GZIP; that is all there is.

The card says so, in those words, rather than offering a fetch that would die at the unpack having
spent the bundle to get there. `DwAsrContainerFormat` in `data/DwAsrModelInstall.kt` carries that as
a fact about the build (`readableInThisBuild`) rather than as a policy, and it lists the three things
that were considered and not done:

| considered | why not |
|---|---|
| add a bzip2 library | a dependency on a **compulsory** download for the whole fleet — this app's updater fetches the whole APK on every release behind a dialog with no "Later" button. That trade belongs to whoever owns it, not to this lane |
| hand-roll a bzip2 decoder | several hundred lines of bit-twiddling whose output is fed straight to a native graph executor. A decompressor nobody has reviewed is a worse supply-chain decision than the one `ASR-RUNTIME-MEASUREMENT.md` §1 already declined |
| **republish the same bytes as a `.zip`** on this deployment's own storage | ~~**this is the answer.**~~ **SUPERSEDED — a fourth option was built instead: serve the two files UNPACKED, per file.** See below |

~~**To switch the download on:** publish the two files in a `.zip` on the deployment's storage, then in
`DW_ASR_MODEL_ARTIFACTS` set `url`, `sha256`, `downloadBytes` and `container = ZIP`.~~ **Struck
2026-08-20, and struck rather than deleted because it is a work item somebody could still pick up by
mistake.** The deployment does not republish an archive at all: `GET /api/asr-models/{id}/files/{name}`
serves `model.int8.onnx` and `tokens.txt` as themselves, so there is no container to choose a format
for and the whole bzip2-versus-zip question stops applying on that route. The client half is
`DwAsrModelController.downloadFromEndpoint`, not `downloadAndInstall`.

`downloadAndInstall` and the `container = ZIP` path above are nevertheless **left in place and are not
dead code to be tidied away**: they are the route for any future artifact that genuinely does arrive
as one archive, and the endpoint route is dark on the fleet until an operator provisions
`ASR_MODEL_DIR`. Both are wired; neither can run today.

---

## The check is the same check. This is the important part

A sideload route that skipped verification would be **the route a developer uses every day and the
only one feeding unchecked bytes to a native graph executor** — precisely the wrong one to make weak.
So there is no flag anywhere that skips it. Both routes end in the same function,
`dwAsrReadInstalledModel` (`ui/designworkshop/DwAsrModelInstallUi.kt`), which:

1. **copies** the staged files into `filesDir/asr-model/<modelId>/` — it never reads them where they
   lie, because the staging directory is writable by something other than this app (that is what
   makes it reachable by a cable), so a file verified there could differ by the time the recogniser
   opened it;
2. **hashes each copy on disk, after writing**, with the app's own `dwAsrSha256OfFile`;
3. **compares** through the app's own `dwAsrVerify` against the digest compiled into the APK;
4. **deletes everything and says so** if any file does not match
   (`DW_ASR_MODEL_MISMATCH_SENTENCE`) — one of two matching is not most of a yes.

A file substituted in the staging directory between the push and the tap fails exactly as a
substituted download would. And the verification is **re-taken on every run of the app**, not
remembered: "it matched when we installed it" is a claim about a file as it was before the phone
rebooted, before the app updated, and before anybody with a cable rewrote it.

### Why a wrong `tokens.txt` is the dangerous one

A substituted **graph** usually fails to load. A substituted **vocabulary** does not fail at all — it
produces fluent, confident text in the wrong alphabet, in a field on a stage screen, where nobody
would think to check it against audio that no longer exists. That is why both files are pinned
separately and why the check is all-or-nothing.

---

## Which staging directory, and why there are two

| path | `adb push` without root | app can read it | notes |
|---|---|---|---|
| `/sdcard/Android/data/com.designprototype.workshop/files/dwasr` | yes | yes, no permission needed | **the reliable one.** Removed when the app is uninstalled |
| `/data/local/tmp/dwasr` | yes | **only where the directory is traversable by other UIDs** | where `DwAsrEngineProbeTest` already stages. On the fleet's SM-M325F `/data/local/tmp` is `drwxrwx--x shell shell`, and the `x` for others is what lets an app traverse it; a build with `0770` there would not |

The second is checked so that a handset already set up for the engine probe does not have to be
pushed 350 MB twice. **A directory that cannot be read is not an error** — it is simply the absence
of a staged copy, which is the ordinary state of every phone in the fleet.

Both are **staging only**. The model is installed into `filesDir`, which is private to the app, and
the recogniser is only ever pointed at that copy.

---

## Removing it again

**Settings › Offline speech model → "Remove it".** 365 MB is a great deal of a district-office
handset, and a designer who installed it before a field trip must be able to have the space back
without uninstalling the app and losing everything unsynced with it.

Note that `adb uninstall` / a `connectedAndroidTest` run **also** removes it, because AGP uninstalls
the app afterwards and that wipes `filesDir` — the staged copy in `/data/local/tmp` survives, the
copy in `/sdcard/Android/data/...` does not.

---

## What you get once it is installed, and what you do not

Read this before planning a field trip around it. All of it is measured on the fleet's own
SM-M325F and recorded in `docs/DEVICE-TIER-MEASUREMENT.md`.

| | |
|---|---|
| **Hindi** | offered. **24.2% word error rate** on FLEURS studio read speech |
| **Odia** | **measured and NOT offered. 53.3% WER** — more than half the words wrong. The rung is not given to Odia and the settings card says so; Odia dictation goes on going to the server, which has the craft keyterm list |
| every other language | **unmeasured, in that word.** Meta *claim* 1,600+ for this model family; nobody has run them here, so this app neither claims them nor denies them |
| **speed** | **slower than real time: 1.078× – 2.967×** the length of the audio, across twelve timed decodes. A ninety-second recording can take four and a half minutes. Plus **up to 8.5 seconds** to open the model, paid on every dictation |
| **memory** | **1.26 GB peak resident set.** On this handset that is `DwModelFit.TIGHT`, not comfortable — the app says what that is expected to cost and asks for a second, named confirmation before installing |
| **accuracy in a courtyard** | **unmeasured.** FLEURS is a studio speaker reading. Every number above is a ceiling |

**The studio figures are the good case.** Nobody has yet recorded an artisan in a courtyard with this
model and scored it, and until somebody does, that row stays the word "unmeasured".

---

## How this document is kept true

**This is a procedure, and a procedure is kept true by somebody running it.** Nothing here is
generated and nothing is asserted by a test — the only proof that these two commands still work is a
cable, a handset and ninety seconds. If you are reading it because you are about to sideload a model,
you are the maintenance mechanism; if a step is wrong, fix the step here before you fix it in your
shell history.

| Claim class | Kept true by |
|---|---|
| The two `adb push` destinations | `android/app/src/main/java/com/designprototype/workshop/data/DwAsrModelInstall.kt` and `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwAsrModelInstallUi.kt` — the staging directories are string constants there. If a constant changes and this page does not, the push lands somewhere the app never looks and the failure is silent ("no model found"), which is the worst shape this document can take. |
| The menu path (**Settings › Appearance & accessibility › Offline speech model**) | `android/app/src/main/java/com/designprototype/workshop/ui/AppearanceScreen.kt`. A human read; nothing mechanical connects a menu label to a sentence here. |
| The SHA-256 digests and byte counts | Re-run `sha256sum` on the two files. They are pinned to a specific upstream release; if the publisher re-cuts the release under the same tag these become wrong, and a wrong digest here reads as "your download is corrupt". |
| "The bytes go through exactly the same fingerprint check as a download would" | This is the load-bearing safety claim of the whole page. It is true only while the install path and the download path share one verifier. Re-read `DwAsrModelInstall.kt` against `android/app/src/main/java/com/designprototype/workshop/data/DwDownload.kt` if either is touched. |
| "No surface in the app fetches from the endpoint yet" | `DW_ASR_MODEL_ARTIFACTS` in `android/app/src/main/java/com/designprototype/workshop/data/DwAsrModelInstall.kt`. **This is the sentence most likely to go stale**, because the server side already works (`docs/ASR-MODEL-HOSTING.md`) and the only thing keeping the cable mandatory is that constant still pointing at the GitHub `.tar.bz2`. The day it points at `/api/asr-models/{id}/files/{name}`, the amendment block at the top of this document is wrong and this page becomes optional rather than the only route. |
| The accuracy / speed / memory table at the foot | **Not maintained here.** Copied from `docs/DEVICE-TIER-MEASUREMENT.md`, which is a dated measurement record on one handset. Re-measure there; do not update the numbers here without updating that document, or the two disagree and neither is trusted. |

**Review triggers:** any change to `DwAsrModelInstall.kt`, `DwAsrModelInstallUi.kt`, `DwDownload.kt`,
`DW_ASR_MODEL_ARTIFACTS`, or the Appearance settings screen; a new upstream model release.

**Known unverified:** everything in *What you get once it is installed* is FLEURS studio speech on one
SM-M325F. The row that says "accuracy in a courtyard — unmeasured" is the honest state of this
document and must not be filled in from anything but a courtyard recording.
