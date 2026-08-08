# Reading an Aadhaar card on the handset

> **THIS DECISION WAS OVERRULED ON 2026-08-09 AND THE BUNDLED RECOGNISER SHIPS.** The user read the
> argument below and decided the other way. Everything below stands unedited — the measurements were
> right and they are still the cost — and what the overrule changes, why it reads differently from
> the other side, and what the decision actually costs when it is measured instead of estimated, is
> recorded at the end under **"Overruled"**. A decision document that quietly deletes the case it
> lost is worth less than none.

## The original recommendation, as written on 2026-08-08

**Decision:** do **not** add an on-device text recogniser to the Android app. Neither ML Kit Text
Recognition variant is acceptable here, and there is no third option that is. The handset gets the
*attachment* half — photograph the card or pick a photograph of it, on the artisan form and on the
stage form — and the reading itself stays on `POST /design-workshops/ocr/identity`, which is now
actually wired up (it was not; see "The fallback was dead" below).

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
