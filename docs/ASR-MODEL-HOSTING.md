# The deployment hosts the speech model — what an operator does, and what the endpoint promises

Written 2026-08-13, alongside `backend/app/services/asr_artifacts.py`,
`backend/app/api/routes/asr_models.py` and `backend/tests/test_asr_model_download.py`. This is the
server half that `docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md` §"How this document is kept true" says must
be written in the same commit as the first artifact served. It is, and that document's §1 has been
amended rather than left to be contradicted quietly.

Sister documents: `docs/ASR-MODEL-SIDELOAD.md` is the cable route this replaces for the field (it
stays, and stays useful — it is how a model gets onto a phone with no data allowance at all);
`docs/ASR-RUNTIME-MEASUREMENT.md` and `docs/DEVICE-TIER-MEASUREMENT.md` hold every byte figure quoted
here and none is re-derived.

---

## 0. Why a server at all

A field handset cannot fetch the model from where it is published, and this was established by
trying it, not by reasoning:

| route | what it answers |
|---|---|
| `ai4bharat/*` on HuggingFace | `gated: auto`. An **unauthenticated `HEAD`** of `assets/ctc_decoder.onnx` returns **401**. MIT licence, and gating is still gating: a phone cannot get the bytes and nobody is putting a HuggingFace token on a designer's handset |
| `k2-fsa/sherpa-onnx` GitHub release assets | Open, and a **`.tar.bz2`**. `DwAsrContainerFormat.TAR_BZ2` is `supported = false` — nothing in the APK can open one |
| **this deployment** | Every designer is already authenticated against it, and `adb reverse tcp:8000 tcp:8000` proves the fleet's SM-M325F reaches it |

So the deployment serves the artifact. One quantised copy is prepared once by whoever publishes it,
rather than 2.5 GB pulled per phone, and no designer is ever shown a token prompt.

---

## 1. WHERE THE BYTES LIVE

**A directory the API can read, named by `ASR_MODEL_DIR`, one subdirectory per artifact id:**

```
$ASR_MODEL_DIR/
└── sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/
    ├── model.int8.onnx      365,352,120 bytes
    └── tokens.txt                86,423 bytes
```

Unset by default. A deployment that has not been given the bytes reports every artifact as
unpublished and answers **503** on the byte routes — never a 200 with a short body.

**Nothing in this repository writes to that directory and the model is not in git.** How the bytes
arrive is the operator step in §3.

### Why not object storage, which is where the APK lives

`app_release.py` 307-redirects to a presigned S3 URL, so this is a departure from the house idiom and
owes an argument. Three reasons, in order of weight:

1. **The digest could then only be a stored copy, and a stored copy can drift.** The whole point of
   the manifest is that its SHA-256 is computed from the bytes the endpoint would serve. Hashing a
   365 MB S3 object per manifest read is not affordable, so the digest would live in a column beside
   the key — a second copy of a fact. Against a local file it is computed from the file and memoised
   on `(size, mtime, ctime)`, so **there is no second copy to drift from.**
2. **A redirect cannot answer "the file is not there" honestly.** A 307 hands the client whatever the
   bucket says: an XML `NoSuchKey`, or worse a 200 of a half-uploaded object. Serving the bytes
   ourselves means the size and the digest are checked before the first byte of the body is written.
3. **On the fleet, only the API is reachable from the handset.** `adb reverse` forwards port 8000 and
   nothing else, so a redirect to `http://minio:9000` resolves to nothing on the one device this
   feature has to be proved on.

**The cost, stated rather than hidden:** the bytes cross the API process, so a download occupies a
worker for its duration and is billed to the pod's egress twice. Acceptable at this scale — one fetch
per handset per model version, across a fleet of tens. The answer if it stops being acceptable is a
CDN in front of these paths, which is safe precisely because the paths are immutable (§2 below).

---

## 2. The endpoint

### 2.1 Three routes

| | |
|---|---|
| `GET /api/asr-models` | the catalogue, with what this deployment actually holds of each artifact |
| `GET /api/asr-models/{artifactId}` | one artifact, same shape |
| `GET`/`HEAD` `/api/asr-models/{artifactId}/files/{fileName}` | the bytes, resumable |

**The manifest answers 200 even when nothing is published.** "Which models exist, and is this one
here?" is always answerable, and answering it with an error leaves a phone unable to tell "not
published" from "your token expired". The byte route is the one that 503s.

**Paths are immutable.** The artifact id carries the version, so a new export is a new directory and
a new manifest row; nothing anybody has installed is affected by it. Re-pointing an existing path at
different bytes does not upgrade anybody — it makes every installed release refuse the download with
a digest mismatch, exactly as `ASR-RUNTIME-DOWNLOAD-CONTRACT.md` §1 already required.

### 2.2 What the manifest publishes, per file

```json
{
  "artifacts": [{
    "artifactId": "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
    "version": "2025-11-12",
    "quantisation": "int8",
    "languages": ["hi-IN"],
    "languageNote": "MEASURED ON THE FLEET'S OWN SM-M325F, not claimed: …",
    "available": true,
    "unavailableReason": null,
    "totalBytes": 365438543,
    "files": [{
      "fileName": "model.int8.onnx",
      "url": "/api/asr-models/…/files/model.int8.onnx",
      "mediaType": "application/octet-stream",
      "bytes": 365352120,
      "sha256": "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c",
      "available": true, "unavailableReason": null, "detail": null
    }]
  }],
  "digestSource": "Every sha256 here is computed from the bytes this endpoint serves, …"
}
```

`bytes` and `sha256` are taken off the file **on this deployment's disk** and are `null` when there is
no file to take them off. They are never filled in from the catalogue: a manifest that answered with
the size and digest it *wished* the file had would be describing something that is not there.

`bytes` comes off a fresh `stat` on every request. `sha256` comes off the file's contents, **memoised
on `(size, mtime_ns, ctime_ns)`** — so it is recomputed whenever the filesystem records a change, and
the one publishing pattern that can defeat that key is why §3 publishes by rename. `file_digest` in
`app/services/asr_artifacts.py` states the measured limit per platform; §4.1 has the reproduction.

`url` is a **path, not an absolute URL.** An absolute URL would have to be built from the `Host`
header, which is client-supplied, and that is how a manifest ends up pointing a fleet at somebody
else's server.

### 2.3 THE DIGEST IS NOT THE TRUST ANCHOR, AND THIS DOES NOT WEAKEN §1 OF THE CONTRACT

`ASR-RUNTIME-DOWNLOAD-CONTRACT.md` §1 argues there should be no endpoint, because *"a digest supplied
by the same host that supplies the bytes verifies the file against its own sender"*. **That argument
is correct and it still holds.**

The value the handset trusts is still the constant compiled into the APK — `DwAsrModelFile.sha256` in
`android/…/data/DwAsrModel.kt`, signed with the APK. The digest published here is a different thing
with three uses, none of which is being the trust anchor:

- **It is the server's own tripwire.** The Python catalogue pins the digest the release builder
  measured; the service computes the digest of the file on disk and **refuses to serve a byte** unless
  they match. A truncated copy or a substituted graph becomes a 503 naming what is wrong, instead of
  a 365 MB download that fails on the phone an hour later.
- **It lets a client fail before spending the bytes** — one JSON read instead of a fetch on a prepaid
  bundle.
- **It is what an operator audits the directory with**, without hashing anything by hand.

The manifest can therefore only ever cause a fetch to be **refused**, never to be accepted. A hostile
server that lied in it would still be caught by the APK's constant; one that told the truth while
serving other bytes would be caught by the same constant. **The day somebody makes a client verify
against the manifest instead of its own constant, that document's central argument really is void** —
and `tests/test_asr_model_download.py` asserts the two catalogues agree so the drift is caught in CI
rather than in a courtyard.

### 2.4 Range, and what was measured off the implementation

The client half is resumable by design: `DwDownload.kt` keeps a `.part` file, asks for
`Range: bytes=<partial>-`, and **refuses to append unless the answer is a 206 whose `Content-Range`
starts at exactly the offset it asked for** (`dwRangeHonoured`). A server that ignores `Range` answers
200 with the whole file, and appending that to a partial produces a corrupt bundle — so a server that
ignores Range makes the entire client half pointless.

Starlette 1.4.1's `FileResponse` implements the RFC 7233 half and is used rather than re-implemented.
Its exact behaviour, **asserted in tests rather than assumed from the RFC**:

| request | answer |
|---|---|
| no `Range` | `200`, `Accept-Ranges: bytes`, full `Content-Length` |
| `bytes=1000-` | `206`, `Content-Range: bytes 1000-2050/2051` |
| `bytes=10-19` | `206`, `Content-Range: bytes 10-19/2051` |
| `bytes=-16` (suffix) | `206`, the last 16 bytes |
| `bytes=0-999999` past EOF | `206`, **clamped** to `bytes 0-2050/2051` — satisfiable, the client just asked for more than exists |
| `bytes=2051-` (start at or past EOF) | **`416`**, `Content-Range: bytes */2051` — the size is in the response, so a client whose `.part` file is longer than the server's file can recover |
| `HEAD`, with or without `Range` | identical headers, empty body |
| several ranges (`bytes=0-9,100-109`) | `206 multipart/byteranges; boundary=…` — **a trap noted rather than a feature**: the status is 206, so `dwRangeHonoured` would accept it, but the body carries boundaries and per-part headers that `DwDownload.kt` does not parse. Whoever makes the client ask for several ranges must handle that |
| a malformed `Range` (`bytes=abc`, `items=0-1`, no `=`) | **`400`**, which is stricter than the RFC's "MAY ignore and send 200" — and the better answer here, because a 200 would have a resuming client throw away its partial and start 365 MB again |

Two things this route adds that `FileResponse` cannot know:

- **The verification gate in front of it.** Nothing reaches `FileResponse` until the file has been
  `stat`-ed **and hashed in this process** and matched against the published digest. The same
  `stat_result` is then handed to `FileResponse`, so `Content-Length`, the range arithmetic and the
  verified digest all describe one reading of the file rather than three.
- **`ETag` is the artifact's SHA-256**, in place of the `mtime`-and-size hash `FileResponse` derives
  by default. Two replicas that received the file at different times would otherwise hand out
  different validators for identical bytes, and a client resuming with `If-Range` against the other
  replica would be sent back to byte zero of 365 MB. A content digest is stable across replicas,
  re-copies and redeploys, and it is a genuinely strong validator: it changes exactly when the bytes
  do.

`Cache-Control: private, no-transform`. `no-transform` is the load-bearing half — it forbids an
intermediary from recompressing a body whose digest is the entire point.

**A latent defect was found and closed in the same change.** `SelectiveGZipMiddleware`'s passthrough
condition carried the comment *"a range response must keep its byte offsets"* while listing only 204
and 304 — **206 was not in it.** `text/plain` is on the compressible allowlist, so a range response
over a text type would have been gzipped with its `Content-Range` header untouched, describing offsets
into bytes the client never receives. It was unreachable while no route in this API served a range at
all; this is the first that does. Both the status and the presence of `Content-Range` are now checked,
`tests/test_response_compression.py` pins it, and independently **both files here are served as
`application/octet-stream` including `tokens.txt`**, which keeps them off the allowlist entirely.

### 2.5 Every refusal, and what an operator does about it

All of them are **503 with a sentence and no file bytes**. The order is cheap-and-specific first, so
the common answers cost nothing and the expensive one is reached only by a file that is present and
the right length.

| reason | means | fix |
|---|---|---|
| `NO_STORE_CONFIGURED` | `ASR_MODEL_DIR` is unset (blank counts as unset) | §3 |
| `NOT_ON_DISK` | configured, and the file is not there. The ordinary "not published yet" | §3 |
| `NOT_A_FILE` | a directory or a dangling symlink where the file should be | §3 |
| `UNREADABLE` | permissions, or the volume went away | check the mount and its ownership |
| `WRONG_SIZE` | **the truncated-upload case**, caught by one `stat` | finish the copy |
| `WRONG_DIGEST` | right length, wrong bytes. Costs a full read to reach | re-copy from the source in §3 |

`404`, not `503`, for an artifact id or a file name this build does not publish. The distinction is
the whole diagnostic value of the response: **404 means the client asked for something that exists in
no deployment; 503 means it asked correctly and this deployment has not been given the bytes.**

### 2.6 Entitlement

**`can_run_design_workshops` — Designer, Admin, Master Admin.** The same set that may run a design
workshop, reused rather than a new predicate. Worth being explicit that this is a **set and not a rank
threshold**, so a **Professor is refused despite outranking a designer** — that is the existing rule
(`deps.can_run_design_workshops` argues it at length) and inventing a laxer one for a file download
would make the offline half of dictation reachable by accounts the online half is not.

Fails closed. Unauthenticated is **401** before any of this runs; the refusal covers the manifest as
well as the bytes.

**Deliberately NOT behind the daily dictation cap (`services/dictation_cap.py`) or the Tier 3 consent
gate (`services/dictation_consent.py`).** Neither applies and either would be a category error: the
cap is a ceiling on provider *spend* and this endpoint spends nothing at a provider, while the consent
gate exists because Tier 3 dictation sends a recording of an artisan's voice off the handset. The
artifact travels the other way, and the model it delivers runs on the device and sends nothing
anywhere — which is the whole reason a designer wants it. A test reads this route's own imports and
asserts both are absent, so the coupling cannot arrive later by habit.

Not unauthenticated either, unlike `/app/download`. That one is anonymous because a browser cannot
attach a bearer token to a link navigation and the APK is world-readable in a bucket anyway; this
fetch is made by the app, which already holds a token.

---

## 3. THE OPERATOR STEP — putting the bytes there

The two files and their digests are the ones `docs/ASR-MODEL-SIDELOAD.md` already documents, from one
published archive. **Take the digests of what you published, not of what you built.**

```sh
curl -L -O https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/\
sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2

sha256sum sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2
# cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed   (292,571,207 bytes)

tar xjf sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2

ID=sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12
mkdir -p "$ASR_MODEL_DIR/$ID"

# PUBLISH BY RENAME, never by overwriting a file that is already there. Two reasons, and the second
# is the one that is easy to miss:
#   1. a rename is atomic, so no reader ever sees a half-written artifact;
#   2. the server's digest memo is invalidated by (size, mtime, ctime) off the file itself. An
#      IN-PLACE overwrite of the same size whose mtime is put back — which is what cp -p, rsync -t,
#      tar -xp and a restored backup all do — leaves that key unchanged on a platform where ctime is
#      the creation time rather than a change time. Windows is such a platform, measured: the
#      manifest went on publishing the old digest while the endpoint served the new bytes. A rename
#      moves the key on Linux AND on Windows, both measured, so this form is safe on both.
for f in model.int8.onnx tokens.txt; do
  cp "$f" "$ASR_MODEL_DIR/$ID/.$f.staged"
  mv "$ASR_MODEL_DIR/$ID/.$f.staged" "$ASR_MODEL_DIR/$ID/$f"
done

sha256sum "$ASR_MODEL_DIR/$ID"/*
# e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c  model.int8.onnx
# a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31  tokens.txt
```

Then **confirm through the API**, which is the only check that proves what a phone will see:

```sh
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8000/api/asr-models | jq '.artifacts[0].files'
curl -s -D- -o /dev/null -H "Authorization: Bearer $TOKEN" \
  -H "Range: bytes=365352000-" \
  "http://127.0.0.1:8000/api/asr-models/$ID/files/model.int8.onnx"
# expect: 206, Content-Range: bytes 365352000-365352119/365352120, Accept-Ranges: bytes
```

**If `sha256sum` disagrees with the digest above, stop.** The endpoint will refuse the file anyway;
the point of checking here is to find out on a workstation rather than after a 365 MB download on a
field connection.

### Per deployment shape

| | how the bytes get in |
|---|---|
| **local / `docker compose`** | `ASR_MODEL_DIR=/srv/asr-models` in `backend/.env` and a bind mount, or simply a path on the host when running uvicorn directly. This is the shape the handset was exercised against, over `adb reverse` |
| **the EC2 box** | a directory under the deployment root, populated by the commands above. Back it up or re-run them after a rebuild; **nothing in this repository recreates it** |
| **Kubernetes** | `infra/k8s/base/deployment-api.yaml` runs `readOnlyRootFilesystem: true` with **one `emptyDir` at `/tmp`, `sizeLimit: 512Mi`, and no PVC**, so this needs a deliberate addition. Two shapes work: an **initContainer** that pulls the artifact from the same bucket the APK lives in into a shared `emptyDir` — costs the pull on every pod start, and note the artifact is **349 MB against that 512 MiB limit**, which leaves almost nothing for the temp files the limit was put there to bound, so it needs its own volume rather than sharing `tmp` — or a **ReadOnlyMany PVC** mounted at `ASR_MODEL_DIR`, one copy, populated once by the operator. **Neither is committed here** — a manifest for infrastructure nobody has provisioned is a guess, and this document naming the two options is more honest than a YAML file that has never been applied |

**The artifact and the app release move together.** The app pins the digests, so publishing a new
artifact means a server release *and* an app release. That is not a new constraint — §1 of the
contract document already accepted it, and there was never a version of this that a server-side row
could ship on its own.

---

## 4. MEASURED AGAINST THE REAL 365 MB ARTIFACT — every number below was read off the wire

Not a synthetic stand-in. The real artifact was **pulled back off the fleet's own SM-M325F**, where a
previous lane had sideloaded it, and served through a real uvicorn with a real designer bearer token:

```sh
adb pull /data/local/tmp/dwasr/model.int8.onnx  # 365,352,120 bytes in 18.881s, 18.5 MB/s
adb pull /data/local/tmp/dwasr/tokens.txt       #      86,423 bytes
sha256sum C:/asrhost/<id>/*
# e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c  model.int8.onnx
# a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31  tokens.txt   ← both pinned values
ASR_MODEL_DIR=C:/asrhost python -m uvicorn app.main:app --host 127.0.0.1 --port 8011
```

| what was asked | what came back |
|---|---|
| `GET /api/asr-models` | `available: true`, `totalBytes 365438543`; per-file `bytes` 365,352,120 / 86,423 and `sha256` **identical to `sha256sum`**, computed not stored |
| `HEAD …/files/model.int8.onnx` | `200`, `content-length: 365352120`, `accept-ranges: bytes`, `etag: "e7c4e54e…2726c"`, `cache-control: private, no-transform`, `content-type: application/octet-stream`, `content-disposition: attachment; filename="model.int8.onnx"`, empty body |
| `GET` whole file | `200`, **365,352,120 bytes in 6.458 s, 56.6 MB/s** over loopback, and the body **hashes to `e7c4e54e…2726c`** |
| **a resume, in two requests** | `bytes=0-149999999` → `206`, 150,000,000 bytes; then `Range: bytes=150000000-` → `206`, `content-range: bytes 150000000-365352119/365352120`, 215,352,120 bytes. **`cat part1 part2 \| sha256sum` = `e7c4e54e…2726c`** — the two halves reassemble byte-for-byte |
| `Range: bytes=365352000-` | `206`, `content-range: bytes 365352000-365352119/365352120`, 120 bytes |
| `Range: bytes=0-1023` | `206`, `content-range: bytes 0-1023/365352120`, 1,024 bytes |
| `Range: bytes=365352120-` (at EOF) | **`416`**, `content-range: bytes */365352120` |
| **truncated on disk** (`head -c 1000`) | **`503`** with a **241-byte** body, not a 365 MB one; manifest flipped to `WRONG_SIZE` with `totalBytes: null`. Restored → available again |
| **same size, different bytes** (`tokens.txt` ← 86,423 zero bytes) | **`503`**, body: *"…is the right length on this deployment but not the right bytes…"*; manifest `WRONG_DIGEST`, that file's `sha256` and `bytes` **`null` rather than the catalogue's values**, while `model.int8.onnx` stayed `available: true` with its own digest. Restored → `available: true`, `totalBytes 365438543` |
| no `Authorization` header | `401` on the manifest **and** on the bytes |
| **FROM THE HANDSET**, `adb reverse tcp:8011 tcp:8011`, toybox `nc`, designer token, `Range: bytes=365351000-` | **`206 Partial Content`**, `content-length: 1120` (= 365,352,120 − 365,351,000), `etag: "e7c4e54e…2726c"`, `accept-ranges: bytes`. 2,109 bytes on the wire including headers |

The reverse tunnel was removed afterwards and `tcp:8000` was left as it was; the model files on the
device were not touched. `C:\asrhost` is a staging directory on the dev box and is deletable.

### 4.1 Re-exercised end to end on 2026-08-13 by a second pass — including the whole artifact onto the phone

Same staged directory, a fresh uvicorn on `:8021`, a freshly minted designer token. Everything in §4
above reproduced. What is new:

| what was asked | what came back |
|---|---|
| **THE WHOLE 365 MB ONTO THE HANDSET**, `adb reverse tcp:8021`, toybox `nc`, **in two range requests** — `bytes=0-199999999` then `bytes=200000000-` | `206` + `206`, **200,000,000 + 165,352,120 = 365,352,120 bytes**, concatenated on the phone with `cat`, and **`sha256sum` ON THE PHONE returned `e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c`** — the pinned digest, and the same value as the `etag` both responses carried. ~45 s per part over `adb-tls`, ~12.7 MB/s |
| **an interrupted transfer, then a resume** — `curl --limit-rate 3M --max-time 3` killed at **9,446,619 bytes**, then `Range: bytes=9446619-` | `206`, `content-range: bytes 9446619-365352119/365352120`, 355,905,501 bytes; **rejoined = 365,352,120 bytes hashing to `e7c4e54e…2726c`** |
| **a PROFESSOR token** (a real `PROFESSOR` row, HS256-signed) on the manifest **and** on the bytes | **`403`** on both — *"needs a Designer, Admin or Master Admin account"*. An `ADMIN` token got `200`. The refusal is measured, not only parametrised in a test |
| **is the cold 365 MB hash really off the event loop** | **Yes, measured.** `touch` the model to drop the memo, fire the manifest, and poll `/health` through it: manifest **1.547 s**, and eight `/health` requests during it answered in **2.8–3.3 ms each**. Warm manifest: 0.020 s. This closes the row below that said nobody had timed a second caller against a cold hash |
| **two concurrent whole-file GETs** | both `200`, both **365,352,120 bytes**, both hashing to `e7c4e54e…2726c`, **7.139 s / 7.116 s** against 4.407 s for one alone |
| `Range: bytes=abc` | **`400`**, body `Range header: range must be requested` — not a silent whole file |
| `Range: bytes=-120` (suffix) | `206`, `content-range: bytes 365352000-365352119/365352120` |
| `If-Range: "e7c4e54e…2726c"` + a range | `206` at the asked-for offset |
| `If-Range:` a **stale** ETag + a range | `200` **with the whole file** — RFC-correct, and safe only because `dwRangeHonoured` refuses to append anything that is not a 206 at the right offset. A client that appended this would corrupt its partial |
| `Range: bytes=0-9, 100-109` **with `Accept-Encoding: gzip`** | `206`, `content-type: multipart/byteranges; boundary=…`, **no `content-encoding`** |
| single range **with `Accept-Encoding: gzip`** | `206`, `content-range` intact, **no `content-encoding`** — the middleware fix holds on the wire, not only in its test. The manifest on the same connection *did* compress: 2,541 → 1,261 bytes, `vary: Accept-Encoding` |
| `…/files/..%5C..%5Ctokens.txt` (a single URL segment that decodes to a path) | **`404`**, *"has no file called “..\\..\\tokens.txt”"* — the catalogue lookup, never a join |
| `tokens.txt` **absent** from the store | manifest `NOT_ON_DISK` for that file with `sha256: null` while `model.int8.onnx` stayed `available: true`; bytes route **`503`** (and `HEAD` **`503`**, not a 200 with a length) |

**And one defect this pass found, which is in §2.2's account of the digest — see `file_digest`.** On
Windows `st_ctime` is the *creation* time, so the memo key `(size, mtime_ns, ctime_ns)` cannot see an
in-place same-size overwrite whose mtime is restored. Reproduced over HTTP: the manifest went on
publishing `sha256 a7a044…d0b31` with `available: true` and the matching `ETag` while serving 86,423
bytes that hash to `407320d1…88d6`. On Linux — the deployment platform — `st_ctime` is the kernel's
inode change time and cannot be restored by userspace, so the same sequence is caught; measured in the
project's own Postgres container. **§3 now publishes by rename for this reason**, which moves the key
on both platforms (measured), and two tests pin it.

### What is still NOT verified, in that word

| | |
|---|---|
| The routes' logic in every ugly shape | **Verified by test** — `backend/tests/test_asr_model_download.py`, driven over ASGI against a synthetic two-file artifact so it needs no database, no network and no 365 MB file |
| The server's catalogue against the APK's | **Verified by test** — the digests, sizes, file names, model id and language list are read out of `DwAsrModel.kt` and `DwDeviceTier.kt` and compared |
| A 206 is not gzipped | **Verified by test** — `backend/tests/test_response_compression.py` |
| **The app's own install path against this endpoint** | **STILL NOT VERIFIED, and re-confirmed as a gap on 2026-08-13 by reading the app.** `DW_ASR_MODEL_ARTIFACTS` pins one row: the GitHub `.tar.bz2`, `DwAsrContainerFormat.TAR_BZ2`, `supported = false`. There is no row for this endpoint and one cannot simply be added — `DwAsrModelArtifact` `require`s a single `https://` **container** URL, while this endpoint serves **per file** and, on the fleet, over plain HTTP through `adb reverse`. So the client half needs a per-file artifact shape before any app surface can fetch from here. The handset proof in §4.1 is `nc` + `sha256sum` on the phone, **not `DwDownload.kt`** |
| Memory and CPU of the API while serving 365 MB | **Still unprofiled**, though **two concurrent whole-file GETs were measured** (§4.1): both complete and correct, 7.14 s against 4.41 s for one. No RSS or CPU figure was taken |
| Time to fetch 365 MB on a district-town connection | **Unmeasured.** 56.6 MB/s is loopback and says nothing about a field link. The **~12.7 MB/s over `adb-tls`** in §4.1 is a USB/Wi-Fi debug link, not a field link either |
| The one-off cost of hashing 365 MB | **Measured: 1.941 s / 2.780 s / 3.236 s over three runs on this box** (188 / 131 / 113 MB/s, 1 MiB reads, while six sibling lanes were running), paid once per file per process and off the event loop in `asyncio.to_thread`. **Whether a concurrent request notices it is now measured too and the answer is no**: `/health` held 2.8–3.3 ms throughout a 1.547 s cold hash (§4.1) |
| IndicConformer served from here | **Not applicable, and no longer "yet".** The official multilingual export loads on the sherpa-onnx vendored in the APK and is far more accurate in Odia (WER 52.8% → 13.9% on identical references), but its encoder is 2,428,824,576 bytes of fp32 against the handset's measured `MemAvailable` of 1,340,412 kB — **it cannot load at all**. ~~and int8 is unmeasured~~ **int8 was measured on 2026-08-13 and it does not transcribe**: 654,790,526 bytes decodes the empty string on all three Odia utterances, and a MatMul-only variant at 883,021,360 bytes decodes one character. So there is no artifact to publish, in any quantisation this deployment can produce, and the route is the official 120M export (`docs/ASR-RUNTIME-MEASUREMENT.md` §6). **Nothing about this endpoint blocks it** — a per-language IndicConformer is `AsrArtifact` rows with one shared weight blob and one small graph per language, which is the shape this catalogue already takes |

---

## How this document is kept true

| Claim class | Kept true by |
|---|---|
| The routes, the refusals, every Range shape | `backend/tests/test_asr_model_download.py`. It is the specification as well as the guard |
| That the published digest is computed and not stored | The same file's *…cannot_drift…* test: it substitutes bytes of the **same length** and asserts the manifest reports the artifact unavailable and does **not** echo the catalogue's digest. **Read its body before trusting it: it calls `clear_digest_cache()` by hand**, so on its own it proves the *computation* is honest and not that the *memo* invalidates. The two tests that pin the memo are `…publishing_by_rename_is_noticed_even_when_the_timestamps_are_preserved` (both platforms, no manual clear) and `…an_in_place_overwrite_that_restores_the_timestamps_is_still_noticed` (POSIX only, and its `skipif` reason records the measured Windows gap) |
| That no refusal is ever a partial 200 | Every refusal test asserts the file's bytes are absent from the response body, not merely that the status is not 200 |
| That the server and the APK pin the same bytes | The two parity tests, which read the Kotlin. They **skip** when the Android tree is absent, in the idiom `test_design_workshop_gate.py` established |
| That the entitlement is the workshop set and not a rank | Parametrised over all seven roles, with PROFESSOR asserted **refused** |
| That the cap and the consent gate stay off this route | A test reads this route module's import lines |
| Every byte figure quoted here | `docs/ASR-RUNTIME-MEASUREMENT.md`, `docs/DEVICE-TIER-MEASUREMENT.md` and `docs/ASR-MODEL-SIDELOAD.md`, **except §4's table, which is this document's own measurement** and is reproducible with the commands in §3 against the real artifact. A figure anywhere else here that is not in one of those three is an invention |
| The `ASR_MODEL_DIR` contract, including blank-means-unset | `asr_artifacts.store_root` and its test |
