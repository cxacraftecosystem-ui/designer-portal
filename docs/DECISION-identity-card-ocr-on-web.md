# Reading an identity card in the browser: no recogniser ships, and what "the same capability" means here

**Decision:** the browser reads the card itself **where the browser already can**, at zero added
bytes, through the Shape Detection API's `TextDetector`, feature-detected exactly the way
`WorkshopCodeScanner` feature-detects `BarcodeDetector`. **No JavaScript or WebAssembly recogniser
is bundled, lazily imported, or fetched from a CDN.** Where the browser cannot — which, measured, is
every browser on the Windows laptop this client is used on — the reader stays
`POST /design-workshops/ocr/identity`, and the work went into making that route survive the web
client's actual constraint, which is a slow metered connection rather than no connection at all.

Recorded because the brief asked for the Android capability translated to the web, and the Android
argument that won there **does not transfer**, for a reason that is specific to a browser and is
measurable rather than arguable. A "no" without its numbers gets re-litigated from memory.

---

## 1. The zero-byte route: measured, not read off a specification

`TextDetector` was probed on this machine on 2026-08-09 by launching each browser and asking it,
not by consulting a support table. Windows 11 Pro 26200.

| Browser | launched | `TextDetector` | `BarcodeDetector` | `FaceDetector` |
|---|---|---|---|---|
| Chrome 151 | headless | absent | absent | absent |
| Chrome 151 | **headed** | absent | absent | absent |
| Chrome 151 | headed, `--enable-experimental-web-platform-features` | absent | absent | absent |
| Edge 151 | headless | absent | absent | absent |
| Edge 151 | **headed** | absent | absent | absent |
| Edge 151 | headed, `--enable-experimental-web-platform-features` | absent | absent | absent |
| Chromium 151 (Playwright) | headless | absent | absent | absent |
| Chromium 151 | `--enable-experimental-web-platform-features` | absent | absent | absent |
| Chromium 151 | `--enable-blink-features=ShapeDetection` | absent | absent | absent |

Firefox and WebKit are **not measured**: Playwright's builds of them are not installed on this
machine, and a claim about a browser that was not launched has no place in this table. Chrome's own
capabilities article says barcode detection is available "on macOS, ChromeOS, and Android", that
face and text detection are "available behind a flag", and that text detection "is not considered
stable enough across either computing platforms or character sets to be standardized at the moment,
which is why text detection has been moved to a separate informative specification".

**The second column is the finding that matters, and it is about the QR scanner rather than this
lane.** `WorkshopCodeScanner`'s header treats a missing `BarcodeDetector` as a normal state and
keeps the typed code on every surface. That was the right call and this table is how right: on a
Windows laptop the camera route of the QR scanner does not exist either, and the typed box is not
the fallback, it is the product. The same is now true of the local card reader, and it is said in
`lib/identityCardLocal.ts`'s header rather than left for somebody to discover.

So the local route is shipped — it costs nothing, it is the only reader that sends the photograph
nowhere and the only one that needs no connection — and it is **not** presented as the answer. The
server reader is.

### Feature-detecting a `TextDetector` is not the same act as feature-detecting a `BarcodeDetector`

`BarcodeDetector` has `getSupportedFormats()`, so the QR scanner can ask a detector that exists
whether it can do the job — "having the constructor is not having the format". `TextDetector` has no
such method, and a build can expose the constructor and then throw `NotSupportedError` from
`detect()` on the platform underneath. The honest equivalent is therefore to **run one**, once, on
an 8×8 blank bitmap and cache the answer for the tab: an empty array is a working detector that saw
no text, and an exception is no detector at all.

---

## 2. Tesseract.js: the real bytes, and where they come from

Measured on 2026-08-09 off the packages themselves — `npm pack`, then `stat`, `gzip -9` and
`brotli -q 11` on each file — not from the project's README.

`tesseract.js@7.0.0` pins `tesseract.js-core@^7.0.0`. In a browser it loads the single-file
`*.wasm.js` builds, not the bare `.wasm` — see `worker-script/browser/getCore.js` — and it picks
**one** of three at runtime from `wasm-feature-detect`.

| Asset a browser fetches | raw | gzip | brotli |
|---|---|---|---|
| `dist/tesseract.esm.min.js` | 63,220 | 10,652 | 8,363 |
| `dist/worker.min.js` | 111,307 | 33,805 | 27,550 |
| `tesseract-core-relaxedsimd-lstm.wasm.js` | 3,905,767 | 1,455,779 | 1,123,820 |
| `tesseract-core-simd-lstm.wasm.js` | 3,899,472 | 1,454,316 | 1,121,440 |
| `tesseract-core-lstm.wasm.js` | 3,896,484 | 1,453,570 | 1,121,324 |
| `eng.traineddata` (decompressed) | 5,199,098 | 2,931,173 | 2,595,501 |

The language file is served pre-gzipped; its `Content-Length` on the CDN the library defaults to,
read with `curl -I`, is **2,952,873 bytes**:

```
https://cdn.jsdelivr.net/npm/@tesseract.js-data/eng/4.0.0_best_int/eng.traineddata.gz  ->  2952873
```

**One visitor, one machine, one cold cache:** wrapper + worker + one core + the language data =
`10,652 + 33,805 + 1,455,779 + 2,952,873` = **4,453,109 bytes over the wire**.

**A self-hosted deployment**, which cannot know in advance which core a visitor's CPU needs, has to
carry all three plus the data: **14,829,123 bytes** committed to this repository and shipped with
every deployment.

Against what the application weighs today, read off a real production build in this worktree
(`next build --webpack`, Next 16.2.9) by summing the scripts each prerendered page tells the browser
to fetch:

| Route | scripts | raw | **gzip** |
|---|---|---|---|
| `/artisans/new` — the form with the two identity boxes | 33 | 1,182,150 | **382,477** |
| `/dashboard` | 21 | 871,393 | 280,552 |
| `/login` | 11 | 733,816 | 233,270 |

**4,453,109 / 382,477 = 11.6×.** One card reader would be eleven and a half times the entire
artisan form — every script, every dependency, the framework included — over a phone hotspot in a
guest house.

### And it is a download on first use, which this project has already refused twice

Not inferred from the documentation: measured by running it. `createWorker("eng")` wrote a
`5,199,098`-byte `eng.traineddata` into the working directory on the first call, and the browser
build's defaults for both `corePath` and `langPath` are `cdn.jsdelivr.net`. Out of the box,
Tesseract.js is a third-party CDN fetch at the moment the button is first pressed.

`DECISION-qr-scanning-on-android.md` rejected unbundled ML Kit for exactly that, and
`DECISION-identity-card-ocr-on-android.md` rejected it again: "first use is precisely the moment the
model is not there". Self-hosting the assets removes the third party and the CDN, and moves the same
bytes onto our own origin — where they are still fetched on first use unless they are in the
first-load bundle, at which point every designer on every page pays them.

### What it actually reads, on the easiest input it will ever get

Four synthetic cards were rendered in a canvas — flat, high-contrast, no lamination, no glare, no
angle — and recognised in Node with the same engine. This is **not a corpus** and no claim about
real-card accuracy rests on it; there is no card and no scanner on this machine, which is the same
honest limit `DECISION-identity-card-ocr-on-android.md` recorded when it refused to guess at a
purpose-built digit recogniser. It does establish two things.

```
card-mono.png        2927ms conf=94  12-digit runs=["912345678901","234567890124"]
card-sans-2x.png     4093ms conf=93  12-digit runs=["912345678901","234567890124"]
card-sans-plain.png  1903ms conf=93  12-digit runs=["912345678901","234567890124"]
card-sans.png        2287ms conf=94  12-digit runs=["912345678901","234567890124"]
```

First: **one to four seconds per card**, plus 1.2–1.5 s of worker start, on a desktop CPU with the
model already local. Second, and far more important: the recogniser returned **two** twelve-digit
runs, and only one of them is the number. `912345678901` is the first twelve digits of the printed
VID. That is section 5.

---

## 3. Why the Android argument does not transfer

The reasoning that overruled the APK-size objection on the handset was:

> a reader that needs a connection is absent exactly when the roster is being filled in.

On a handset that is true and decisive. The application is installed; it starts, runs, records an
artisan and queues the save with no signal for two days. A bundled recogniser is the difference
between the capability existing and not existing.

**In a browser, the application itself is a network resource.** There is no service worker and no
offline shell in this client — `grep -rn "serviceWorker\|workbox\|manifest.webmanifest"` over `app`,
`components`, `lib` and `public` returns nothing, and `frontend/public` contains one directory,
`boundaries`. A designer with no connection does not have a card reader missing; they have no
application at all. The offline tolerance this client does have (`lib/offline.ts`, the IndexedDB
outbox, `OfflineWatcher`) is for a tab that was **already open** when the signal dropped.

So the window a bundled recogniser would buy in the browser is: the tab was loaded while online, the
connection then dropped, and a card has to be read before it comes back. In that window the designer
can type twelve digits — and `IdentityCardCapture` already refuses to queue an identity photograph
in it, deliberately, because "a queued identity photograph is one nobody remembers is on the
laptop". Eleven and a half times the page weight, for every designer on every cold cache, to remove
about ten seconds of typing inside that window, is not a trade this application should take.

The privacy argument is stronger than the offline one and it is why the zero-byte local route ships
at all: a locally-read card is never sent to a third-party vision model. But it is not worth 4.45 MB
per visitor either, because there is a cheaper way to spend on the same problem — section 4.

---

## 4. What was built instead, at zero bundle bytes

**The photograph is redrawn before it leaves the tab** (`lib/identityCardImage.ts`, called from
`readIdentityCard`, the one function every send passes through).

* **Scaled to a 2000-pixel longest edge at JPEG q0.92.** Measured in Chromium on a 4032×3024 frame:
  **1,923,159 → 203,351 bytes, 9.46×.** The web client's constraint is a metered hotspot, not an
  absent network; Android answered "no signal" by putting the recogniser on the device, and the
  browser's honest answer to "slow signal" is to send an order of magnitude less. It also puts the
  server's own `IDENTITY_OCR_MAX_IMAGE_BYTES` ceiling (8 MB) out of reach of one modern photograph.
* **The EXIF block stops being sent at all.** A canvas re-encode builds a new JPEG from pixels
  alone. A card photographed in the field routinely carries `GPSLatitude`/`GPSLongitude` — where the
  artisan was standing — plus the device make, model, serial and the exact second. Every one of
  those bytes was previously posted to Gemini or OpenAI along with the card. The uploaded part is
  also renamed `identity-card.jpg`, because `IMG_20260809_141233.jpg` states when the card was
  photographed and travels in the multipart body for no reason.
* **2000 px and q0.92, not smaller.** The digits are the whole payload and JPEG ringing lands
  hardest on high-contrast edges, which is exactly what a printed digit is. The point is to stop
  sending megabytes of sensor noise and metadata, never to make the number harder to read.
* Every failure path returns the original file. A browser without `createImageBitmap`, a canvas that
  refuses `toBlob` — none of those is a reason to tell a designer their card could not be read.

**The two readers are a choice made before the camera opens**, not a fallback afterwards. Where both
exist, one checkbox — *"Read it on this computer — the photograph is not sent anywhere"* — defaults
to ticked, because the default is what a designer gets without deciding and the safer thing to do by
accident is the one that sends nothing. Falling back silently would upload the picture the moment
the local read came up empty, which is the opposite of what ticking it meant, and it would force
`IdentityCardCapture` to hold the `File` after the read, which its header promises it does not. The
checkbox is shown **only where there is a choice**; a checkbox with one reachable state teaches a
researcher to stop reading checkboxes.

**Both controls now render when EITHER reader can answer.** Previously the artisan-form control
returned `null` unless the server route probe came back true, so a deployment with no vision
provider configured had no card reader at all. It still has a browser.

**The offline refusal now applies only to the server route.** Reading in the tab sends nothing and
queues nothing, so refusing on `!navigator.onLine` would have withheld the one thing reading in the
tab buys.

Who is offered the control is **unchanged**: `canRunDesignWorkshops`, the set the endpoint admits.
A local read sends nothing, so the transport argument for that gate does not apply to it — but who
may read an artisan's identity card is an organisational rule and not a transport one, and this
client's standing rule is to mirror `deps.py` and never invent a permission.

### What all of that cost, measured the same way as the table above

Two production builds in this worktree, before and after, summing the scripts each prerendered page
tells the browser to fetch:

| Route | before (gzip) | after (gzip) | added |
|---|---|---|---|
| `/artisans/new` | 382,477 | 383,895 | **+1,418** |
| `/dashboard` | 280,552 | 280,903 | +351 |
| `/login` | 233,270 | 233,270 | 0 |

The local reader, the pure parsing rule, the photograph preparation and both controls' new copy
together weigh **1,418 gzipped bytes** on the page that has the identity boxes — 0.37% of what that
page already weighed, against the 4,453,109 bytes the rejected option would have added. The 351
bytes on `/dashboard` are `lib/identityCardImage.ts` arriving through `lib/designWorkshops.ts`,
which that page already imports.

---

## 5. The defect this lane found, which is worth more than the feature

**A card prints its VID in groups, and the server was mining the front of it for an Aadhaar number.**

`backend/app/services/identity_ocr.py` matched candidates with

```
(?<![0-9])((?:[0-9][ \-]?){11}[0-9])(?![0-9])
```

whose lookarounds see only the immediately adjacent **character**. On a card that prints
`VID : 2345 6789 0124 0831`, the character after the twelfth digit is a **space**, the lookahead is
satisfied, and `234567890124` is returned as an Aadhaar candidate from text carrying no Aadhaar
number at all. Run against the module before the fix:

```
aadhaar_candidates('Ramesh Kumar Meena\nVID : 2345 6789 0124 0831\nBagru, Jaipur\n')
  ->  (['234567890124'], 0)
```

One accepted candidate, **zero rejections** — nothing anywhere says a decoy was involved.

**Verhoeff does not save this, and neither does the human.** Sampled over 200,000 Verhoeff-valid
sixteen-digit numbers, **10.02% have a twelve-digit prefix that also satisfies Verhoeff**. So about
one card in ten offers the front of its own VID as a checksum-valid number — and the confirmation
step, which is this whole feature's safety net, cannot catch it: the panel prints `2345 6789 0124`
grouped 4-4-4 and the designer finds those exact twelve digits, in that order, printed on the card
in their hand. They confirm. A number belonging to somebody else becomes an artisan's deduplication
key, and it is masked to `XXXX XXXX 0124` on every surface afterwards so nobody ever reads it back
and notices.

`test_a_longer_digit_run_is_not_mined_for_twelve_digit_windows` existed, guarded exactly this, and
**passed** — because it used a *contiguous* sixteen-digit run, where the lookahead does work. Cards
are printed in groups.

The rule is now "a **whole** separator-joined token of exactly twelve digits", in
`identity_ocr.aadhaar_candidates` and in the browser's `lib/identityCardText.ts`. A VID, a fourteen-
digit enrolment number, a six-digit pin code and a date are refused **before** any checksum runs and
counted nowhere — so `rejectedAadhaarCount` keeps meaning "the card was found and misread", which is
the only case in which "photograph it again in better light" is useful advice. `result_from_reply`
also joins its structured fields with a newline rather than a space, for the same reason a space is
not a safe join: a space is a separator *inside* a printed number, so joining two fields with one
invented an adjacency that was never on the card.

Verified: `backend/tests/test_workshop_transcripts.py` — **34 passed**, including a new
`test_a_grouped_vid_does_not_yield_its_own_first_twelve_digits`.

---

## 6. Do the clients agree, and how that is kept true

There are **three** ports of one rule — server, browser and handset, all three live as of 2026-08-09
— and this repository has already been bitten twice today by the fourth kind of drift, a client
decoding keys the server never sent.

| Where | What it parses | Status |
|---|---|---|
| `backend/app/services/identity_ocr.py::aadhaar_candidates` | text from a vision model | **the source of truth** |
| `frontend/lib/identityCardText.ts::identityCandidatesFromText` | text from `TextDetector` | ported, pinned |
| `android/…/data/IdentityCardText.kt::read` (`scanDigitRuns`) | text from bundled ML Kit, on the handset | **ported 2026-08-09, NOT pinned** |

*Row three was corrected on 2026-08-15; it read "Android — does not exist yet" and had been wrong
since the day the row was written. The Android decision was reversed the same week
([`DECISION-identity-card-ocr-on-android.md`](DECISION-identity-card-ocr-on-android.md), 2026-08-09),
`com.google.mlkit:text-recognition` went into the APK, and the parser landed with it. Leaving the row
as written and correcting it in a footnote — which is what was done first — put an absence claim in
the one table this document offers as the place to look before porting the rule a fourth time.*

**"NOT pinned" is the live gap and the word to read in that row.** The Kotlin port carries both
things §5 says it must — the token has to be a **whole** run of exactly twelve digits, and a run that
was never twelve digits is not counted as a rejection — and `IdentityCardTextTest.kt` asserts each
rule case by case. What it does **not** have is the web port's guard: no fixture in it is the verbatim
output of `aadhaar_candidates` run under the backend venv, so nothing fails when the server's rule and
the handset's rule stop agreeing. Two of the three ports are checked against the source of truth and
one is checked against its author's reading of it.

The web port is not kept true by care. `e2e/identity-card-web-unit.spec.ts` carries a twelve-row
agreement table whose right-hand side is the **verbatim output of the server's own function**,
printed by running it under the backend venv on 2026-08-09 — not transcribed from reading the
Python. One of the rows is the text Tesseract actually returned from a rendered card in section 2,
VID and enrolment number and pin code included. A change to either side that makes them disagree
fails in Node, in seconds, with no server and no browser.

**Android had no such parser when this section was written**, because it had no recogniser:
`DECISION-identity-card-ocr-on-android.md` refused one and the handset read cards through the same
server endpoint. **That reversed on 2026-08-09**, in the same week and for the offline reason §3
explains does not transfer to a browser: ML Kit was bundled, and the moment it returned `Text` on the
device Android needed this rule. It took it from this table rather than from a fresh reading of the
regex, which is what the next sentence asked for and is the reason the sentence was worth writing.
Two things it had to carry, both of which are the difference between "a digit filter" and "the rule":
the token must be a **whole** run of exactly twelve digits (section 5), and the rejection **count**
must exclude everything that was never twelve digits, or the handset will tell a designer to
re-photograph a card that was photographed perfectly. Both are in `IdentityCardText.kt` — the first as
`scanDigitRuns` returning MAXIMAL runs so a grouped sixteen-digit VID is one token, the second as the
`digits.length != AADHAAR_LENGTH` arm returning **before** the counter, not after it.

The wire shape is untouched by this lane. `identityChoices` and `DwIdentityOcrResult` still read
`aadhaarCandidates`/`pehchanCandidates`, and `e2e/identity-ocr-unit.spec.ts` still pins them against
the server's verbatim payload.

---

## 7. What the local reader deliberately does not do

**It offers Aadhaar numbers only.** The server's `pehchan_candidates` reads a *structured* model
reply — it asks a vision model for the artisan ID and normalises the answer — and there is no
equivalent to port. A PM Vishwakarma artisan ID has no checksum and no shape beyond "4–32 letters
and digits", so picking one out of free recognised text would nominate the artisan's name, the word
GOVERNMENT and the card's serial with equal confidence, into a field that has no way of ever
detecting the mistake. The Pehchan box therefore never offers the local reader, and the stage
reader's checkbox says in as many words that the server route is the only one that reads a Pehchan
card.

**It never auto-commits, and a candidate that fails the checksum is refused rather than warned
about** — unchanged, and the same on both routes, because both converge on the same filter and the
same `aadhaarValidationError`. There is exactly one Aadhaar checksum on this client and neither new
module has an opinion of its own about what a valid number is; `aadhaarProblem` is passed in.

**Nothing logs a number.** No console call on any path in either new module, and every string a
surface prints outside the confirm panel is a count or a mask.

---

## 8. The regression being accepted

**On a browser without a text recogniser — which is every browser measured here — reading a card
still needs a connection, and the photograph still goes to a third-party vision model.** That is
what the application did yesterday, and it is the honest state of the web platform rather than a
choice this lane made. The two things that changed for that designer are that the photograph is now
about a ninth of the bytes and carries none of its metadata, and that the reader appears even when
no vision provider is configured (where it now uses the browser, if the browser can).

**Where the local reader does exist, it is worse at reading.** A vision model handles a laminated
card at an angle under a courtyard light far better than a platform text detector, and there is no
measurement here to say how much better, because there is no card and no corpus on this machine.
That is why the choice is a checkbox with both states reachable and a sentence saying which one
reads a worn card better, rather than a silent preference.

**A slower first read on the server route.** Decoding and re-encoding a 12-megapixel photograph in a
canvas costs a moment before the upload starts. It is repaid many times over by sending 9.46× fewer
bytes on the connection this client actually has.

---

## 9. The condition under which this should be revisited

Two, and they are independent.

1. **If this client ever gains a service worker and an offline shell.** That is the browser's
   analogue of the handset being installed, and it is the premise the Android decision rests on. At
   that point "the application is there but the reader is not" becomes a state that can exist, the
   engine can be cached deliberately while online rather than fetched on first use in a courtyard,
   and the 4.45 MB is worth measuring against a capability rather than against ten seconds of
   typing. Nothing below that threshold changes the arithmetic.
2. **If `TextDetector` ships unflagged on the platforms this client runs on.** The code path is
   already there and costs nothing; the table in section 1 is the measurement to re-run. It should
   be re-run with a real card and a real camera before anything in section 8 is described as fixed.

---

## How this document is kept true

Two halves, kept true two different ways, and confusing them is how a decision record rots.

**The decision and its argument (§1–§3, §7–§9) are frozen.** They record what was weighed on
2026-08-09 and are not updated to match later code. If the "no" is overturned, add a block on top
saying so — do not edit the case it defeated.

**The claims about live code (§4–§6) can and do rot**, and §6 is the only part of this file with a
mechanical guard behind it.

| Claim class | Kept true by |
|---|---|
| The `TextDetector` / `BarcodeDetector` / `FaceDetector` availability table (§1) | **A dated probe on one Windows 11 machine, 2026-08-09.** Nothing re-runs it. It is the measurement named by revisit condition 2, so re-launch the browsers rather than trusting the table; browser support moves and this table cannot. Firefox and WebKit are marked *not measured* and must stay that way until somebody launches them. |
| Tesseract.js bundle sizes and accuracy (§2) | Read from the published package on 2026-08-09. Re-check against npm before repeating a number. |
| That the two parsers agree (§6) | **`frontend/e2e/identity-card-web-unit.spec.ts`** — a twelve-row table whose right-hand side is the verbatim output of `aadhaar_candidates` in `backend/app/services/identity_ocr.py`, printed by running it. This is the one claim in the document that fails loudly and in seconds when it stops being true. `frontend/e2e/identity-ocr-unit.spec.ts` does the same for the wire shape. **Nothing does this for the third port.** The Android column of that table is kept true by reading — re-read `IdentityCardText.kt` against `aadhaar_candidates` whenever either moves, until somebody pins it. |
| The zero-bundle-bytes claim (§4) | `frontend/components/designworkshop/IdentityCardReader.tsx` and `frontend/lib/identityCardText.ts` must stay free of any imported recogniser. A `grep -ri tesseract frontend/` is the whole check, and it is worth running rather than assuming, because this decision is exactly the kind that gets undone by one convenient `npm i`. **It does not return nothing, and expecting nothing is how a check gets ignored:** there is exactly one hit, a prose comment in `frontend/e2e/identity-card-web-unit.spec.ts` explaining where a fixture's text came from. One hit, in that file, is the passing state. A hit in `frontend/package.json`, `frontend/lib/` or `frontend/components/` is the failure. |

**The §6 third row: corrected in place on 2026-08-15, and why "left as written" was the wrong
call.** The row used to read *"Android — does not exist yet"*, on the grounds that
[`DECISION-identity-card-ocr-on-android.md`](DECISION-identity-card-ocr-on-android.md) refused a
recogniser. That decision was reversed on 2026-08-09 and the recogniser shipped; Android has had its
own port of this rule since, in
`android/app/src/main/java/com/designprototype/workshop/data/IdentityCardText.kt`
(`scanDigitRuns`). The first attempt at this correction left the row untouched and wrote the truth
*here*, seventy lines below it, on the argument that §6 is an account of what was true when it was
written. **That argument does not hold, and the distinction is worth stating because it is the one
this whole document turns on:** §1–§3 and §7–§9 are frozen because they are an *argument*, and an
argument that is edited to agree with later code stops being evidence of anything. The §6 table is
not an argument. It is a **register of where the rule currently lives**, offered to whoever ports it
next — which is precisely why the paragraph under it says *"the place to get it is this table and not
a fresh reading of the regex."* A register that records an absence which ended six days ago sends
that reader to write a fourth port of a rule that already has three, and no footnote seventy lines
down stops them, because a register is consulted by looking at the row. Frozen means "do not rewrite
the case you lost"; it has never meant "do not correct a fact".

**What is still owed, and it is unchanged by the correction:** the third column of the agreement
guard. The Kotlin port has no equivalent of `identity-card-web-unit.spec.ts` pinning it against the
server's verbatim output — `IdentityCardTextTest.kt` asserts the rules as its author read them — so
two of the three ports are checked against the source of truth and one is not. That is the sentence
the row's **NOT pinned** is short for.

**Review triggers:** `backend/app/services/identity_ocr.py`, `frontend/lib/identityCardText.ts`,
`frontend/components/designworkshop/IdentityCardReader.tsx`, or any new dependency under
`frontend/package.json` that could contain a recogniser.
