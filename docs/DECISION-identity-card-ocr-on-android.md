# Reading an Aadhaar card on the handset: no recogniser ships, and why

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
under a megabyte, which would be the ideal answer. It does not work: UIDAI's Secure QR carries a
*reference id* — the last four digits of the number plus a timestamp — precisely so that scanning a
card cannot yield the full number. Whatever else it gives, it cannot give the twelve digits this
field stores.

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
    {"value": "234567890123", "kind": "AADHAAR", "confidence": 0.8, "masked": "XXXX XXXX 0123"}
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
