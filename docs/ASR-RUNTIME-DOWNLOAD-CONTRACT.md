# The offline speech engine as an opt-in install — what the handset expects, and what the server must serve

Written 2026-08-12, alongside `android/app/src/main/java/com/designprototype/workshop/data/DwAsrRuntime.kt` and
`android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwAsrRuntimeUi.kt`. **The client half is built and tested. The server
half does not exist**, and this document is the specification for whoever builds it, because that is a
`backend/` change and the lane that wrote the client could not make it.

> **UPDATED 2026-08-13: THE SERVER HALF NOW EXISTS FOR THE MODEL.** `GET /api/asr-models` and
> `GET|HEAD /api/asr-models/{id}/files/{name}` are built, tested and exercised against the real
> 365 MB artifact — see `docs/ASR-MODEL-HOSTING.md`, which is this document's sibling and was written
> in the same change, as the rule at the bottom of this file requires. **The ENGINE half still does
> not exist and no longer needs to**: §8 records that a downloaded `.so` cannot be loaded at all, so
> the engine is vendored into the APK. Everything below stands as written except §1, which carries its
> own amendment.

Read `docs/ASR-RUNTIME-MEASUREMENT.md` first. Every size in this document comes from there, off eight
real packaged APKs, and none of it is re-derived here.

---

## 0. The decision this implements, in the words it was given in

> *"Have the users have the choice to install sherpa-onnx by their choice, we would pose it on first
> install. Because the app is for designers empanelled by the government, they just go in field for
> the workshops at times."* — and *"also in the settings page."*

So the engine is **not in the APK**. It is an offer: made once on the dashboard at first run,
standing permanently in Settings, accepted or ignored. The reason is the second sentence — **only some
of these designers go to the field, and only sometimes.** A workshop run at the office over Wi-Fi
never needs an offline engine; a courtyard in a district village with no bars needs nothing else.

Two measurements make that the only viable shape rather than merely a nice one:

| | |
|---|---|
| Bundling it | ARM-pair APK **26,244,416 → 79,552,612 bytes, +53,308,196, 3.03×** (default AAR) or **+39,811,828, 2.52×** (static-linked). This app's updater fetches the whole APK on every release behind a dialog with **no "Later" button** (`MainActivity.kt`), so that is a compulsory triple-sized download for the whole fleet, most of whom would never use it. |
| Depending on it | **Not on Maven Central at all** — six coordinates, six live 404s through the Gradle resolver against the repositories this build declares. Upstream ships the Android build as a GitHub release asset. **A build-time dependency was never available**, whatever anybody decided about size. |

---

## 1. THE CONTRACT IS DELIBERATELY SMALLER THAN AN API. There is no endpoint

> **AMENDED 2026-08-13. THERE IS NOW AN ENDPOINT — FOR THE MODEL, NOT THE ENGINE — AND THIS SECTION'S
> ARGUMENT IS NOT WEAKENED BY IT.** `GET /api/asr-models` and
> `GET|HEAD /api/asr-models/{id}/files/{name}` are built and tested
> (`backend/app/api/routes/asr_models.py`, `docs/ASR-MODEL-HOSTING.md`). The reason it does not
> contradict what follows is the distinction this section itself draws: **the digest the handset
> verifies against is still the constant compiled into the APK.** The manifest publishes a digest
> COMPUTED from the bytes on the deployment's disk, and it is used in exactly one direction — the
> server refuses to serve a file whose bytes do not match what its own catalogue pins, and a client
> may refuse a fetch before spending the bytes. Nothing accepts a file *because* the manifest said so.
>
> So the amendment is narrow and worth stating precisely: **"there is no endpoint" becomes "there is
> no endpoint that anything trusts".** The row in "How this document is kept true" has been updated to
> say what would actually void the argument, which is a client that verifies against the manifest
> instead of against its own constant. A test reads both catalogues and asserts they agree, so that
> drift is caught in CI.
>
> Why an endpoint became necessary at all, which §1 as written could not have known: **the model
> cannot be fetched from upstream by a handset.** `ai4bharat` repos are `gated: auto` and answer 401
> to an unauthenticated `HEAD`; the k2-fsa asset is a `.tar.bz2`, which nothing in the APK can open.
> The engine half of this document is untouched — it is vendored into the APK (§8), so it has no
> download at all.

**The handset asks the server nothing.** The URL and the digests are constants compiled into the APK
(`DW_ASR_ARTIFACTS` in `DwAsrRuntime.kt`), so the only thing the deployment has to do is **serve
those exact bytes at those exact URLs**.

That is not laziness, it is the security property. If an endpoint told the phone which file to fetch
and what it should hash to, then whoever controls the endpoint controls what code runs inside an app
holding artisans' Aadhaar numbers — and the digest would be verifying the file against its own
sender, which verifies nothing. **The digest has to travel in the APK, signed with the APK**, and once
it does, an endpoint has nothing left to add.

The consequences are worth stating plainly, because they are the cost of that choice:

- **A new engine version needs an app release.** There is no way to push one to installed phones.
  That is acceptable: the engine changes rarely, and this app already forces an APK update.
- **The URL must be immutable.** Re-pointing it at different bytes does not "upgrade" anybody; it
  makes every installed release refuse the download with a digest mismatch, and the sentence a
  designer reads tells them to contact whoever administers the deployment. Publish a new path per
  version instead.

---

## 2. What the server must serve

### 2.1 One file per ABI, at a durable public HTTPS URL

| | requirement | why |
|---|---|---|
| Transport | **HTTPS only.** | `DwAsrArtifact`'s constructor refuses any URL that does not start with `https://`, so a cleartext URL cannot be expressed in the app at all. |
| Host | **This deployment's own storage** — the same CloudFront distribution the APK is served from (`public_url_for_key`, `backend/app/services/s3.py`). | A third-party host is a party that can change the bytes without this deployment knowing. It is also unreachable on the IPv6-only mobile networks the CloudFront default in `android/app/build.gradle.kts` exists for — Jio and Airtel, which is the field fleet. |
| Auth | **None.** No token, no signature, no expiry. | The URL is a constant in a shipped APK; a presigned URL would expire and a session token sent to a storage host ends up in somebody else's access logs. The bytes are not secret — the integrity check is what matters, not the confidentiality. |
| Stability | **Immutable.** One path, one set of bytes, for ever. | See §1. |
| `Content-Length` | **Exact.** | The client caps the transfer at the pinned `downloadBytes` and fails at the first byte beyond it, so a wrong length is at worst a wasted attempt. A missing length is survivable; a stream that never ends is not, and the cap is what stops it filling a phone holding a fortnight of unsynced fieldwork. |
| `Content-Type` | `application/zip`. | Advisory only. The client does not read it; the digest decides. |

Suggested key layout, mirroring how the APK is stored:

```
app-assets/asr-engine/sherpa-onnx-1.13.5-static/arm64-v8a.zip
app-assets/asr-engine/sherpa-onnx-1.13.5-static/armeabi-v7a.zip
```

The version and the variant are in the PATH, which is what makes the immutability rule easy to keep:
a new upstream release is a new directory, and nothing anybody has installed is affected by it.

### 2.2 The file is a ZIP, and its entries are read by name

The client opens it with `java.util.zip.ZipFile` and asks for **each pinned library name in turn**. It
never enumerates the archive, so:

- Entries must be at the **top level**, named exactly as pinned (`libsherpa-onnx-jni.so`, …).
- **No directories, no prefixes.** `jni/arm64-v8a/libsherpa-onnx-jni.so` will not be found.
- Anything else in the archive is ignored, harmlessly.
- A missing pinned entry fails the install with a sentence telling the designer the file being served
  is not the file the app expects.

**Why by name and not by walking the archive:** the names inside a downloaded archive are
attacker-controlled strings, and an entry called `../databases/workshop.db` would write over this app's
own database. Reading only our own constants means no such string ever reaches a path. `DwAsrLibrary`
additionally refuses to hold a name containing `/`, `\` or `..`, so the rule holds for future callers
and not only for the code written today.

**Both path fragments are checked, not just the file name.** `DwAsrArtifact.abi` is the other one — it
names the directory the libraries are written into (`filesDir/asr-engine/<abi>/`) and the file the
container is downloaded as (`engine-<abi>.zip`) — and its constructor rejects `/`, `\` and `..` for the
same reason. Being a constant compiled into the APK makes a bad value a release builder's typo rather
than an attack; it does not make it harmless, and there was no argument for guarding one of the two and
trusting the other.

### 2.3 The two digests, and why there are two

| digest | of what | checked when |
|---|---|---|
| `DwAsrArtifact.sha256` | the served `.zip`, byte for byte | once, immediately after the download is written to disk |
| `DwAsrLibrary.sha256` | each extracted `.so`, byte for byte | **on every run of the app**, before the engine may be used |

The container's digest can only be checked while the container exists, and the container is deleted
after unpacking. What gets *loaded* afterwards is a `.so` on disk, so that is what has to be
verifiable — on the fourth launch, after a reboot, after an OS update, and after anybody with a cable
and a debuggable build has had a go at it. `DwAsrRuntimeStatus` cannot be constructed in the
`INSTALLED` state unless every library's digest was taken **in that run** and matched.

---

## 3. The release builder's procedure

This is the human half of the contract and it is where the trust actually lives.

1. **Fetch the upstream AAR** from the `k2-fsa/sherpa-onnx` GitHub release. Prefer
   `sherpa-onnx-static-link-onnxruntime-<version>.aar` (37,749,854 bytes at v1.13.5): the measurement
   document's recommendation 2, because it is +39,811,828 packaged against +53,308,196 for the default
   one. Record which you used.
2. **Extract `jni/<abi>/` for `arm64-v8a` and `armeabi-v7a`.** Nothing else. In particular
   `libsherpa-onnx-c-api.so` and `libsherpa-onnx-cxx-api.so` are for C/C++ callers this app does not
   have — excluding them was measured at 8,388,948 bytes off the ARM pair (row F against row C).
3. **Zip each ABI's libraries flat**, one archive per ABI, entries at the top level.
4. **Upload to the deployment's storage** at an immutable path, and confirm the public URL fetches the
   bytes back — from a phone on mobile data, not only from a workstation, because the IPv6 question in
   §2.1 is only answered on a handset.
5. **Take the digests of what you uploaded, not of what you built** — download the file back and hash
   that:
   ```sh
   sha256sum arm64-v8a.zip                       # → DwAsrArtifact.sha256
   unzip -o arm64-v8a.zip -d unpacked/ && sha256sum unpacked/*.so   # → each DwAsrLibrary.sha256
   stat -c %s arm64-v8a.zip unpacked/*.so        # → downloadBytes and each library's bytes
   ```
6. **Write the row into `DW_ASR_ARTIFACTS`**, fill in `upstreamVersion` and `provenance` in full
   sentences, and cut an app release. Every field is required and the constructor refuses anything it
   cannot check, so a half-filled row does not compile.
7. **Run the install once against the real URL, on a real handset.** The download/unpack/verify path in
   `DwAsrRuntimeUi.kt` has **never been executed against a server** — see §6.

### What all of this establishes, and what it does not

**It establishes:** the bytes on the phone are the bytes the release builder pinned. Nothing can be
substituted at the storage host, in a proxy, or by a captive portal without the install failing with a
sentence.

**It does NOT establish provenance, and the app says so.** The digest is not a signature. It cannot
tell anybody the file is really k2-fsa's build of sherpa-onnx rather than something that was on the
release builder's laptop. Between the phone and upstream sit: whatever was downloaded in step 1, over
whatever connection, verified against nothing (**upstream publishes no signature this document knows
of — unverified**); a manual repack; and a storage bucket. **Trusting this engine means trusting this
app's release process**, and the honest mitigation is that step 1 is a human decision recorded in
`provenance`, not an automated fetch nobody reviews.

The measurement document already refused one shortcut here for the same reason and it is worth keeping
in view: `com.bihe0832.android:lib-sherpa-onnx` is on Maven Central, is an individual developer's
repackage of this engine, and **was seen and declined** rather than missed.

---

## 4. `AppRelease` has no checksum column, and that is relevant here

The app already self-distributes its own APK through this deployment (`AppRelease` in
`backend/prisma/schema.prisma`, `backend/app/api/routes/app_release.py`). Its columns are:

```prisma
model AppRelease {
  id  versionCode  versionName  objectKey  url  notes  publishedById  publishedAt
}
```

**There is no checksum column, and no integrity check anywhere in that path.** `downloadApk` in
`WorkshopRepository.kt` fetches the URL and hands the file straight to the system installer. That is
not the same exposure as this lane's — Android's package installer refuses an APK whose signature does
not match the installed app's, so the platform is doing the verification the row does not carry — but
it is a relevant observation for whoever adds the engine endpoint, in two directions:

- **Do not copy that shape.** A native library has no equivalent platform check. `System.load` opens
  whatever is at the path.
- **If a table is ever added for engine artifacts**, give it a `sha256` column *and understand what it
  is for*: it would be an operational record of what was published, useful for auditing a bucket, and
  it must never become the value the handset verifies against. That value has to be in the APK. A
  server that supplies both the file and its expected digest has authenticated nothing.

Adding `sha256` to `AppRelease` itself would also be worth doing on its own merits — as a record of
what a version was, so a bucket can be audited against the row — and is a `backend/` decision this
lane cannot take.

---

## 5. What the app does with it, in one page

State machine (`DwAsrRuntimeState`), all of it per-handset rather than per-build:

```
UNKNOWN ──(read filesDir, hash every pinned library)──► NOT_INSTALLED
   │                                                        │
   │                                                   (designer taps Install)
   │                                                        ▼
   │                                                   DOWNLOADING ──(percent)──┐
   │                                                        │                   │
   │                        container digest mismatch ◄──────┤◄──────────────────┘
   │                        or a library's digest mismatch   │
   │                                   │                     ▼
   └──(read fails)──► UNKNOWN          ▼            every library verified IN THIS RUN
                                    FAILED ──(retry)──►  INSTALLED
                                (files deleted)              │
                                                     (designer taps Remove)
                                                             ▼
                                                        NOT_INSTALLED
```

Offer decision (`dwAsrOffer`), in order of durability — the first refusal found is the one rendered,
because the useful sentence is the one that says whether anything the designer could do would change
the answer:

1. nothing pinned in this build → `NOTHING_PUBLISHED_TO_INSTALL` ← **today, on every handset**
2. installed / fetching / not looked at → `ALREADY_INSTALLED` / `IN_PROGRESS` / `UNKNOWN`
3. `Build.SUPPORTED_ABIS` empty → `PROCESSOR_UNMEASURED`; no artifact for any reported ABI →
   `NO_BUILD_FOR_THIS_PROCESSOR`
4. **no measured ASR model → `NO_MODEL_TO_FEED_IT`** ← the deliberate disable, see §7
5. `StatFs` silent → `STORAGE_UNMEASURED`; not enough room → `NOT_ENOUGH_STORAGE`
6. no connection → `NO_CONNECTION`
7. otherwise → `INSTALL`, or `RETRY` after a failure

Storage required before an install is offered: `downloadBytes + installedBytes + 256 MiB`. Both sizes
at once, because the container and the libraries unpacked out of it are on the phone together while the
unpack runs — the archive is deleted once every `.so` has been written, and **before** their digests are
taken, so a failed digest costs the whole fetch again rather than only the unpack. That is the deliberate
side of the trade: 24 MB is affordable to fetch twice on a handset that has this storage gate precisely
because it may be short of space, and a corrupted transfer has almost certainly corrupted the archive
rather than the copy out of it. The 256 MiB margin is a **chosen** number and is deliberately smaller
than the 1 GiB margin `DwDeviceTier.kt` keeps for a multi-gigabyte model — a 1 GiB bar would refuse a
24 MB file on a phone reporting 900 MB free, and a screen that lies about small numbers is not believed
about large ones.

**Nothing is ever kept from a failed or abandoned attempt.** Every failure path deletes the container
*and* the extracted libraries, including cancellation (a designer leaving the screen mid-unpack) and the
branch where the written file cannot be read back at all. A fetch killed with the process — a force-stop,
a low-memory kill, a flat battery — leaves the container behind with no code running to delete it, so the
next time a surface reads the engine directory it sweeps `incoming/` first. That sweep is the only reason
those bytes are ever reclaimed: nothing else in the app reads that directory.

### The size is named before the tap, per ABI

The one place this app can do this. `dwDownloadCostSentence` refuses to print a size for a Google
language pack because `triggerModelDownload` reports none and any figure would be invented; **our
artifact has been weighed**, so plan §2.1's *"show the real size"* applies here:

| processor | engine bytes | on screen |
|---|---|---|
| `arm64-v8a` | 34,721,464 − 11,074,640 = **23,646,824** | "24 MB" |
| `armeabi-v7a` | 22,941,324 − 6,789,192 = **16,152,132** | "16 MB" |
| `x86`, `x86_64` | **unmeasured** for the static-linked variant | the word "unmeasured" |

**The processor named beside the figure is the one the figure was measured for, which is not always the
handset's first ABI.** `Build.SUPPORTED_ABIS` is primary-first but not homogeneous: an ARC / Houdini
device reports `[x86_64, x86, armeabi-v7a]` and runs this app's 32-bit code, so it is owed the
armeabi-v7a figure — and naming its *first* ABI would print "on this phone's processor (x86_64) the
engine is 16 MB", attaching an ARM measurement to the one processor this document marks unmeasured.
`dwAsrMeasuredAbi` picks the named processor and `dwAsrArtifactFor` picks the artifact by the same walk
over the handset's own list, so the size, the name and the file fetched cannot disagree.

Row A → row E of the per-ABI table, static-linked. Every `lib/` entry in those APKs reads **STORED**
at `minSdk = 26` with `extractNativeLibs="false"`, read out of the merged manifest, **so these are the
bytes on the phone as well as the bytes in the archive.** If the deployment serves them compressed the
real download is smaller and the figure over-states the cost, which is the survivable direction —
`docs/ASR-RUNTIME-MEASUREMENT.md` §5 measured that gap once, at 57,662,788 stored bytes compressing to
24,581,961 for the ARM pair together. Once a real artifact is pinned, its own `downloadBytes` is
printed instead.

---

## 6. What is NOT verified, in that word

| | |
|---|---|
| The download / unpack / verify path against a real server | **never executed.** `DW_ASR_ARTIFACTS` is empty, so `install()` is unreachable from any surface. The pure half is tested on the desktop JVM (33 tests); this half awaits a staging URL |
| Whether these libraries load on a Galaxy M32 | **unmeasured** — as it was in `ASR-RUNTIME-MEASUREMENT.md` §6, and nothing in this lane went near a handset |
| Time to fetch 24 MB on a district-town connection | **unmeasured.** It decides whether the fetch can stay tied to the screen that started it (it currently is — see §8) |
| Whether upstream publishes a signature for its release assets | **unverified**; §3 assumes not |
| The IndicConformer model: id, quantisation, size, RSS, WER, latency | ~~**unmeasured**, all of it~~ **MOSTLY MEASURED, 2026-08-13, and the answer is that this model does not reach a handset.** id `ai4bharat/indic-conformer-600m-multilingual` (MIT, ONNX); fp32 **2,432,855,148 bytes** for one language's graph plus the shared encoder; **Odia CER 5.1 / WER 16.7, Hindi CER 6.9 / WER 20.9** (FLEURS studio, greedy CTC); **RTF ≈ 0.22** on a desktop CPU at 2 threads. **RSS on a handset is still unmeasured and cannot be measured**: 2.43 GB of fp32 weights will not load against the SM-M325F's `MemAvailable`, and dynamic int8 produces a model that decodes the empty string. See `docs/ASR-RUNTIME-MEASUREMENT.md` §6 |

### What the release build DID do to this code — measured, not assumed

`:app:assembleRelease` was run and read the way `docs/R8-MEASUREMENT.md` requires: sizes off the
packaged APK with `os.path.getsize`, entries out of the APK's own central directory with `zipfile`,
class fates out of `mapping.txt` and `r8-removed.txt` rather than inferred. `:app:packageRelease`
executed (not `UP-TO-DATE`), ARM pair only (`arm64-v8a`, `armeabi-v7a` — the filter is intact).

| | bytes |
|---|---|
| `ASR-RUNTIME-MEASUREMENT.md` row A — the same tree, before this lane | 26,244,416 |
| this tree, with the whole opt-in install feature in it | **26,260,800** |
| **the entire feature's cost in packaged APK** | **+16,384** (one 16 KiB alignment step) |
| `.dex` | 13,324,236 → 13,363,076, **+38,840** |
| bundling the engine instead, for comparison | **+53,308,196** |

Re-measured the same way after the §9 review, because a measurement that is not re-taken is a claim: the
packaged APK is **still 26,260,800 bytes to the byte** (the six fixes are 604 bytes of dex against the
13,362,472 that lane recorded, and did not cross another alignment step), the ABI pair is still
`arm64-v8a`, `armeabi-v7a`, `k2fsa` still appears zero times in `mapping.txt`, and `DwAsrLibrary` and
`DwAsrVerification` are still absent from it. The trap below is unchanged and still has to be re-read
when a row is pinned.

**`k2fsa` appears zero times in `mapping.txt`, which confirms mechanically that no binding was added
and therefore that no keep rule is needed yet.** That is the same reading that found all 123 upstream
classes removed in the earlier lane, pointed at our own code instead.

Two classes are **absent from the shipped dex under any identity** — not in `mapping.txt`, listed
without a trailing colon in `r8-removed.txt`, which is that document's rule for a class removed whole:

- `DwAsrLibrary` — never instantiated, because the catalogue that would instantiate it is empty.
- `DwAsrVerification` — consistent with either unreachability or R8's enum unboxing; **the two were not
  distinguished**, and it does not matter until a row is pinned.

Also listed as removed, at method level: `dwAsrVerify`, `dwAsrIsSha256`, `dwAsrStorageNeededBytes`. **A
method in that list may have been deleted OR inlined into its callers, and this lane did not
distinguish the two** — but `dwAsrAllVerified` and `dwAsrMayLoad` both survive in the dex, and
`dwAsrVerify` is reachable only through them, so inlining is the reading the evidence supports. Nine
other functions survive by name, including the whole offer decision and every sentence.

**THE TRAP THIS LEAVES FOR THE NEXT LANE**, and it is the mirror image of the one in
`ASR-RUNTIME-MEASUREMENT.md` §4: the verification code is thinner in the release build than in the
debug one *because nothing can be installed yet*. When a row is pinned, all of it becomes reachable and
comes back — but **that must be re-read rather than assumed**, with the same two commands, because a
verifier that R8 removed as unreachable while the code path around it survived would be a download with
no integrity check at all, and everything in this document rests on it.

---

## 7. The offer is DISABLED TODAY, AND THAT IS THE ARGUED CALL

Installing the engine is **not** installing a model. `ASR-RUNTIME-MEASUREMENT.md` §3 read the AAR's
own central directory: **there is no `assets/` entry at all.** The whole 53 MB is machinery. So a
designer who spent 24 MB today would find dictation exactly as it was, and would have no way to tell
whether they had been charged for nothing or the app was broken.

Both of this repository's rules apply and they point opposite ways. *"A control that cannot work is
worse than an absent one"* (`DwPackOffer.NO_CONNECTION`) argues for hiding it; *"an absent one cannot
be found later"* argues for showing it. The tie is broken on which failure is worse for the designer:

- **An absent card cannot be found**, and the question it answers — *"can this app dictate with no
  signal at all?"* — is one a designer asks **before** a field trip. Silence answers it wrongly.
- **A disabled card with the reason on it** answers in the place they looked, says the missing thing is
  a measurement rather than their phone, and names what would change it.
- **The failure the first rule guards against does not arise, because there is no tap.** The CARD is
  present; the BUTTON is not drawn. `dwAsrMayInstall` is false, and `DwAsrRuntimeTest` asserts that no
  handset shape on any connection can draw it while the catalogue is empty.

**What lifts the disable is a measurement, not a code change:** a Tier 1 model in
`DW_TIER1_CATALOGUE` — which requires its artifact id, quantisation, on-disk size and peak RSS
measured on a real handset, plus the WER and latency bar plan §2.2 sets before offering it at all. The
install offer reads that same catalogue rather than a flag of its own, so there is exactly one place
the answer lives.

---

## 8. What the next lane must add, in order

1. **A Kotlin binding**, by one of the three routes `ASR-RUNTIME-MEASUREMENT.md` §1 lists — vendor the
   AAR, add a build-time fetch, or wait for upstream to publish to Maven Central. Each is somebody's
   decision. Nothing in this lane's code names a `com.k2fsa.sherpa.onnx` class, because that class is
   not on the compile classpath: the coordinates 404.
2. ~~**The load**, over `DwAsrArtifact.libraries` **in list order** (`libonnxruntime.so` before
   `libsherpa-onnx-jni.so`, or the second cannot resolve against the first), gated on `dwAsrMayLoad`
   and nothing else. Nothing is loaded today: a `JNI_OnLoad` that fails can abort the process rather
   than throw, and doing that speculatively on a settings screen would be finding out the most
   expensive way available.~~

   > **THIS STEP IS NOT POSSIBLE AS WRITTEN, MEASURED ON THE HANDSET 2026-08-12.** It is struck
   > through rather than deleted because it is the load-bearing assumption of this whole document and
   > somebody reading only the summary would otherwise re-derive it.
   >
   > Every entry class in `com.k2fsa.sherpa.onnx` has a static initialiser calling
   > `System.loadLibrary("sherpa-onnx-jni")`. `System.loadLibrary` resolves through
   > `ClassLoader.findLibrary`, which searches **only the classloader's own native-library
   > directories** — the APK's `lib/<abi>` and `base.apk!/lib/<abi>`, plus `/system/lib64`.
   > `filesDir` is not among them, and cannot be added to them. `System.load(absolutePath)` will
   > happily load a downloaded `.so`, but it registers it under its **path**, so the binding's own
   > `loadLibrary` still throws `UnsatisfiedLinkError` before any of this app's code runs.
   >
   > The full search path was printed off the fleet's SM-M325F by `DwAsrEngineProbeTest` and is
   > quoted in `docs/DEVICE-TIER-MEASUREMENT.md`. **This was not reasoned out from documentation; it
   > was read off the device.**
   >
   > **What that costs this document.** §1's security argument, §2's contract and §3's release
   > procedure are all still correct *as a design for shipping verified bytes to a handset* — and
   > they are all still exactly right for a **model**, which is data read from a path and which
   > `data/DwAsrModel.kt` now pins two real digests for. What they cannot yet do is deliver an
   > **engine**, because nothing can load one from where they would put it. The two routes left are a
   > reflective patch of `DexPathList`'s native-library path, or a fork of the upstream binding that
   > calls `System.load` on an absolute path; both are somebody's decision and neither was taken.
   >
   > **So the engine is vendored into the APK instead**, as of 2026-08-12, at the +39,811,828 bytes
   > `ASR-RUNTIME-MEASUREMENT.md` priced for the static-link ARM pair — the cost §0 of this document
   > argued against, now being paid, because the alternative it proposed does not exist. `§0`'s
   > argument about the fleet is untouched and still true; what changed is that it no longer has a
   > shape available to it.
3. **R8 KEEP RULES in `android/app/proguard-rules.pro`.** R8 removed **all 123**
   `com.k2fsa.sherpa.onnx` classes whole in the measurement — zero kept, zero in `mapping.txt` — so a
   release build of any binding added in step 1 fails with `ClassNotFoundException` or
   `UnsatisfiedLinkError` exactly where the debug build worked, in a courtyard, on a handset nobody can
   attach a debugger to. Two rules are needed and R8 can infer neither: the binding's classes by name,
   and the JNI entry points the `.so` looks up as strings. **No rule was added today** — a keep rule
   for classes that are not in the build keeps nothing and would rot quietly until somebody trusted it.
4. **A model**, which is plan step 5 and the thing that lifts §7's disable.
5. **Then, if 24 MB turns out to be slow enough to matter**, move the fetch off the composition's
   scope. It is currently tied to the screen that started it, and the sentence a designer reads says so
   in those words ("stay on this screen while it finishes — leaving stops it"). The honest reason it
   was left that way is that the alternative is a foreground service or `WorkManager` for a code path
   that cannot yet run at all.

---

## 9. What an adversarial review of this changed, the same day

Six defects, all of them in the half no test can reach or in a sentence a designer reads. None was a
hole in the integrity path — nothing can reach the disk at a library's path without the container having
matched a digest compiled into the APK, and nothing loads at all — but three of them made a promise the
code did not keep, which in this repository is the same class of fault.

| | what was wrong | now |
|---|---|---|
| **Leaked download, 1** | The branch where the written container cannot be read back returned without deleting it, under a sentence advising the designer to free some space. Up to 24 MB kept for good, in a directory nothing else reads. | Cleans up like every other failure, and the sentence says the bytes were deleted |
| **Leaked download, 2** | A fetch killed with the process left the container in `incoming/` **for ever**: `readInstalled` reads the ABI directory and `installNow` only deletes the one name it is about to write, so no code path ever looked there again — while `DwAsrOffer.RETRY` told the designer "nothing was kept" | `refresh` sweeps `incoming/` before reading, which is the first moment after a process death that the app looks at the directory at all |
| **Half-unpacked install** | Cancellation (a designer leaving the screen mid-unpack) deleted the archive and left part-written `.so` files beside whole ones, against the running card's own words — *"a part-finished download is thrown away rather than kept, so nothing is left half-installed"* | Cancellation deletes both |
| **Stale library from an older pin** | The ABI directory was written into, never emptied first, so a `.so` pinned by an earlier release could sit in the directory the loader will be pointed at, matching no pinned digest and therefore hashed by nothing and deleted by nothing — while `readInstalled` correctly reported INSTALLED for the names this build pins | The directory is emptied before the unpack, so it holds only files this run wrote and verified |
| **A figure on the wrong processor** | The cost sentence named `abis.first()`, which on an ARC / Houdini handset reporting `[x86_64, x86, armeabi-v7a]` printed the ARM 16 MB figure against the name `x86_64` — an invention of exactly the kind this feature refuses. The number was right; the sentence was not | `dwAsrMeasuredAbi` names the processor the figure was measured for. §5 |
| **The collapsed first-run card** | It is the whole of what a designer who taps "Not now" ever reads, and it ended at *"This app can install a speech engine of its own to close that gap"* — an available download, with nothing beside it. Nothing is published and no model measured | The pitch names the model an engine also needs and sends them to the card's own answer for this phone, worded as a standing precondition so it does not rot the day a row is pinned |

Two smaller things were also corrected rather than argued with. `DwAsrArtifact.abi` is now checked to be
a bare path segment like `DwAsrLibrary.fileName` always was (§2.2) — the comment that justified the
omission said a compiled constant "cannot be a path fragment", which is a restatement and not an
argument. And the storage arithmetic's own comment claimed the archive was kept until the libraries were
verified; it is deleted before, and §5 now says so along with what that costs.

`docs/DEVICE-TIER-MEASUREMENT.md`'s own table of the Tier 1 answers was corrected in the same pass: it
counted five, listed six, omitted two that `dwTier1Offer` produces (`ABI_UNMEASURED` and
`FREE_STORAGE_UNMEASURED`), and said the last four reused refusals the file already had when one of them
— `RUNTIME_UNMEASURED` — is new in this lane. It now lists all eight and marks which two are new, and
the two it had omitted are exercised by `DwDeviceTierTest` rather than only claimed to be.

Green at **tests=831 skipped=0 failures=0 errors=0** afterwards, up from 828: three new tests in
`DwAsrRuntimeTest`, plus assertions added to two existing ones.

---

## How this document is kept true

This is a **specification for code that does not exist yet**, which makes its failure mode the
opposite of a measurement document's. It cannot go stale by the tree moving underneath it; it goes
stale the moment somebody builds the server half and this file is not the thing they built. So the
rule is: **whoever serves the first artifact edits this file in the same commit**, and until then every
row below is checkable against the client that is already built.

| Claim class | Kept true by |
|---|---|
| That there is **no endpoint the client TRUSTS**, and why | Amended 2026-08-13, see §1. A manifest now exists (`GET /api/asr-models`) and the argument survives because **nothing verifies against it**: the digest the handset checks is `DwAsrModelFile.sha256` in `android/…/data/DwAsrModel.kt`, compiled into the APK. What would void the argument is a client that verifies against the manifest instead of its own constant — that is the change that must not be made quietly. `backend/tests/test_asr_model_download.py` reads the Kotlin catalogue and asserts the server's digests, sizes, file names and language list match it, so the two cannot drift silently; and the server refuses to serve any file whose bytes do not hash to what it pins, so the manifest can only ever cause a fetch to be refused. |
| That the ENGINE still has no endpoint | `DW_ASR_ARTIFACTS` in `android/app/src/main/java/com/designprototype/workshop/data/DwAsrRuntime.kt` is empty and the engine is vendored into the APK (§8), so there is no engine download at all. The endpoint above serves models only — `backend/app/services/asr_artifacts.py` has no engine row and its catalogue type has no ABI field to put one in. |
| That nothing is installable today | `DW_ASR_ARTIFACTS` is empty, and `dwAsrMayInstall` is false for every handset shape and connection. `DwAsrRuntimeTest` asserts both, so the day somebody pins a row the tests say so rather than the screen quietly going live. |
| That the URL cannot be cleartext | `DwAsrArtifact`'s `init` — `require(url.startsWith("https://"))`. It is a constructor check, so no code path in the app can express a cleartext fetch. |
| That a blank digest fails closed | `dwAsrVerify` returns `NO_PINNED_DIGEST` for an empty expectation, never `VERIFIED`. `expected == actual` on two empty strings is `true`, which is the one plausible line that would turn the whole feature into decoration. Pinned by test. |
| That the digest is taken from the file on disk | `dwAsrSha256OfFile` in `DwAsrRuntimeUi.kt` opens the written file. A stream digest proves what was received, not what was stored, and the two differ on a short write, a full volume, or a file replaced afterwards. |
| That `INSTALLED` cannot be reached unverified | `DwAsrRuntimeStatus`'s `init` refuses the state unless every pinned library has a matching digest taken in that run, compared as a multiset — `containsAll` would let one digest counted twice stand in for a library nobody hashed. |
| That the digest is **not** a signature | It binds the bytes to what the release builder pinned and establishes no upstream provenance. `DW_ASR_VERIFY_SENTENCE` is asserted to contain none of "signed / signature / audited / certified / official / safe", so the screen cannot start claiming more assurance than exists. |
| Every byte figure quoted here | `docs/ASR-RUNTIME-MEASUREMENT.md`, and nowhere else. None is re-derived in this document. A figure appearing here that is not in that one is an invention. |
| The R8 trap | `mapping.txt` after `assembleRelease`: `k2fsa` appears zero times. Whoever pins the first artifact must add keep rules **before** the first release build, or it fails at `UnsatisfiedLinkError` where debug worked. |
| That the engine arrives without a voice | `DW_TIER2_CATALOGUE`/`DW_TIER1_CATALOGUE` are empty and there is no measured ASR model anywhere in this system — see `docs/DEVICE-TIER-MEASUREMENT.md`'s two open questions, both of which need the handset. `NO_MODEL_TO_FEED_IT` is what the offer says about it, and it outranks the storage and connection refusals deliberately. |
