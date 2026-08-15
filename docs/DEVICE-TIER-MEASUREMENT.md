# What each handset can actually run — the table, and the measurements that are meant to fill it

Written 2026-08-11 alongside the device-tier probe, when **nothing in it had been measured at all**.
That state was on purpose: plan §2.1 says the shape of the table is fixed now and *"every cell is
filled by measuring a real handset"*, and a shape with no numbers in it is a smaller lie than a shape
with plausible numbers somebody wrote from a distribution listing. Every still-unmeasured cell is
spelled **unmeasured** in those words, exactly as `docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md`
reports what the handset actually said rather than what the source suggested it would say.

**AMENDED 2026-08-12: THE FLEET'S OWN HANDSET HAS NOW BEEN PROBED, AND THE FIRST THING IT DID WAS
CONTRADICT THIS DOCUMENT.** Everything the probe reads is measured below, off a real device, through
this app's own code. The recommendation table's right-hand columns are **still** unmeasured and this
lane could not fill them — there is no model artifact in existence to weigh (see *What could not be
measured, and why* below). What changed is the left-hand column, the signals, and the row the fleet's
phone lands in — **which is not the row this document named after it**.

The probe that produces the left-hand column — the device class — **is** built and **is** tested:
`android/app/src/main/java/com/designprototype/workshop/data/DwDeviceTier.kt` is pure Kotlin over plain numbers, imports nothing from Android at
all, and is exercised on the desktop JVM by `DwDeviceTierTest`. The platform half is a separate,
deliberately thin file — `android/app/src/main/java/com/designprototype/workshop/data/DwDeviceProbe.kt`, six reads that need a `Context` — and
it has **no unit test**, which is the reason it is kept as small as it is; as of 2026-08-12 it has
something better and narrower, an instrumented probe that runs it on a handset and prints what came
back. What is not built is any Tier 2 model, because there is nothing to point it at until the two
questions at the foot of this document are answered.

The paragraph that used to stand here read: *"**And no handset has been probed.** `dwProbeDevice` has
never been executed against a real device or an emulator… The M32's own `totalMem`, `availMem`,
`isLowRamDevice`, free storage, ABI list and thermal reporting are all unmeasured."* **That is no
longer true and the next section is why.** What remains true, and is worth keeping: every device in
`DwDeviceTierTest` is still a *shaped* fixture named for the class of phone it stands for, not a
reading, and none of them is named after a real handset — the discipline `DwLanguagePackTest` learned
the hard way when a fixture named after this very handset asserted capabilities it did not have. The
measurement below is deliberately **not** copied back into those fixtures for that same reason.

---

# MEASURED 2026-08-12 — what the fleet's own handset actually reports

Raw logcat, not inference, in the shape `docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md` set. Taken by
`android/app/src/androidTest/java/com/designprototype/workshop/DwDeviceTierProbeTest.kt`, which calls
the real `dwProbeDevice(context)` and the real `dwRecommendTiers(...)` and prints every field. It
asserts nothing, on purpose: an assertion would be a claim about a handset written before the handset
was asked.

```
cd android && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.designprototype.workshop.DwDeviceTierProbeTest
adb -s <serial> logcat -d -s DWTIERPROBE:I
```

Instrumented run: **tests=1 skipped=0 failures=0 errors=0**, 15.197 s. The JVM suite was re-run
whole afterwards and is unchanged at **tests=871 skipped=0 failures=0 errors=0** (68 suites) — this
lane added one `androidTest` file and eight comment corrections across `DwDeviceTier.kt` and
`DwDeviceProbe.kt`, and changed **no shipped logic and no constant**.

**RE-MEASURED INDEPENDENTLY ON 2026-08-12 BY AN ADVERSARIAL REVIEW OF THIS LANE, ON THE SAME HANDSET,
AND THIS SECTION IS AMENDED WHERE THE RE-RUN DISAGREED.** Five further instrumented runs (the first
tests=1, 15.24 s, before any correction; the rest after each one) reproduced **every stable figure in
this document byte for byte** — `totalMem`, the device class, all three band edges, `getMemoryClass` /
`getLargeMemoryClass` / `Runtime.maxMemory`, the `MemoryInfo.threshold`, the 128 MiB `StatFs`
reservation, the total volume size, and all twelve `dwRecommendTiers` answers. The shell table was
re-run too and agrees. The figures that moved between runs are exactly the ones this file already
labels as instants (`availMem`, free storage, the `MemAvailable` gap, uptime, thermal degrees).

**SO NO READING HERE WAS WRONG. WHAT WAS WRONG WAS SIX CLAIMS *ABOUT* READINGS** — including two that
had been written into shipped KDoc, and a guard command that could not fail. Each is corrected in
place below and says so where it stands:

| what the re-run broke | where |
|---|---|
| **a figure that came from no command at all**: *"`SM-M325F` shipped as 4/64, 6/128 and 8/128, and the handset is a 6 GB one"*, stated as fact in this file **and in `DwDeviceClass.SMALL_4GB`'s KDoc**, three paragraphs after this file says what the phone was sold as "is not recorded here as fact" | *The correction*, and the recommendation table |
| "the 5,500 MiB edge is now measured" — true only if this handset is a 6 GB one, which nothing read off it establishes; the edge's chosen half is **still unmeasured** | *Which row of the table* |
| the steady-state probe cost bound of 2.6 ms — broken at 2.99 ms on the third run, then at **12.1 ms** on the fifth, on calls that were **not** the first in their run | *What the probe costs* |
| the `availMem` movement window is **ten** seconds, not twelve — six readings have five gaps between them | *`availMem` moves, and how much* |
| "the prediction was right and conservative" — right about the ratio, a third low on the figure it named | *The signal the probe refuses* |
| the corrected `getMemoryClass` guard matched nothing at all, so it could never fail; and one more *"4 GB M32"* survived inside this file, below the register listing "every place it still lives" | *How this document is kept true*, and the register above |

**The pattern in five of those six is the same one this lane was written to catch, committed by the
lane itself: an inference promoted to a measurement by being written down next to real numbers.**

The `6 GB` and `4 GB` labels below are shorthand for the rows of the table, not claims about any
handset's packaging. Every figure in this section came off the device; where something is inferred
rather than read, it says so.

## The device

| | |
|---|---|
| model | Samsung SM-M325F (Galaxy M32), `m32` / `m32dd` |
| SoC | `ro.boot.hardware = mt6769t` — the string the handset gave; its marketing name is **unmeasured** and is not repeated here |
| Android | 13 (API 33) |
| build | `samsung/m32dd/m32:13/TP1A.220624.014/M325FXXSDDYE3:user/release-keys` |
| locale | `en_GB` |
| state during the reading | idle, screen on, **on the charger**, cool (see thermal below) |

## THE CORRECTION, AND IT IS THE MOST VALUABLE THING IN THIS LANE

**Two theories, both wrong.**

**(a) "The M32 fleet is the 4 GB row."** The recommendation table's second row has read *"4 GB (the
M32 fleet)"* since this document was written, and `DwDeviceClass.SMALL_4GB`'s own KDoc said *"The
fleet's row: a handset sold as 4 GB, like the Galaxy M32 these workshops are run on."* Both have been
corrected in this pass; the KDoc now carries the reading and the reason.

**The M32 that these workshops are run on reports 5,927,968,768 bytes — 5,653.352 MiB, 5.521 GiB —
and `dwDeviceClass` puts it in `MID_6_TO_8GB`, the "6–8 GB class".** Its data volume reports
116,003,962,880 bytes total. **Measured; the rest is inference and is labelled as such:** a phone
cannot report 5.52 GiB of RAM unless it has more than 4 GB of it, so this is not a 4 GB handset,
whatever the variant is called. Which variant it is, and what `SM-M325F` was sold as, is **not
something this handset said** and is not recorded here as fact.

What follows from the measurement alone: the fleet's handset is not in the row the table named after
it. Nothing rendered differently — every offer is a refusal on every row today — but the moment a
Tier 2 model is measured, the row this phone reads its answer from is **not** the row anybody has
been writing that answer into.

The fix is **not** to relabel the row `SMALL_4GB` catches; it is to stop naming a row after a fleet
whose handsets have not been surveyed. One M32 has now been measured. What the others report is
**unmeasured**, and the table below says so in that word.

**AND THE CLAIM IS OLDER AND WIDER THAN THIS DOCUMENT, SO HERE IS EVERY PLACE IT STILL LIVES.** Four
are corrected here because they are this lane's own; the rest are listed rather than quietly edited,
because a claim that has spread to seven sites is a finding, and deleting it site by site with no
register is how it comes back.

**THE HEADING ABOVE SAID "EVERY PLACE" AND THE FIRST VERSION OF THIS TABLE HAD SIX ROWS, NOT SEVEN.**
The seventh is in this very file, two hundred lines below, and the grep that built the register walked
past it because the line break falls between "4 GB" and "M32". It was found on review by searching the
file with the newlines collapsed. **A register is a claim about a search, and it inherits every
weakness of the search that built it** — which is worth more than the row it added.

| where | what it says | done |
|---|---|---|
| this file's recommendation table | *"4 GB (the M32 fleet)"* | **corrected** |
| this file's *"safeguard that makes device-dependent tiering permissible"* section | *"a fleet where a 4 GB M32 and a 12 GB flagship run different tiers"* | **corrected 2026-08-12, on review, and it had been MISSED by the pass that wrote this register.** The line break falls between "4 GB" and "M32", so a line-oriented grep for `4 GB M32` does not match it. **This register called itself "every place it still lives" while a site in its own file was two hundred lines below it** |
| `DwDeviceClass.SMALL_4GB` KDoc | *"the Galaxy M32 these workshops are run on"* | **corrected**, and it now carries the reading |
| `DwDeviceTier.kt` file header | *"a 4 GB Galaxy M32 and a 12 GB flagship"* | **corrected** to "a 4 GB handset", since it is illustrating a principle |
| `docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md` §2.1 and its preamble | *"The fleet device is a 4 GB Galaxy M32"*, *"A 4 GB M32 and a 12 GB flagship"* | **not corrected here.** It is the origin of the claim and a record of a decision taken at a date; whoever amends the plan should carry this measurement into it |
| `WorkshopRepository.kt`'s upload-buffer argument | *"a 4 GB Galaxy M32 that is also holding Compose, a camera preview and a stage"* | **not corrected here** — another lane's file, and the argument's direction survives being wrong about the size (a 6 GB phone with 1.5 GB free is still a phone you do not hand a whole file to). It is wrong about the handset and should be fixed when that file is next touched |
| `backend/tests/test_ai_layers.py` docstring | *"a 4 GB M32 and a 12 GB flagship"* | **not corrected here** — outside this lane entirely |

**(b) "The `getMemoryClass()` paragraph is an argument."** It was, and this document said as much by
never putting a number in it. It is now a measurement. **The argument it made survives and comes out
stronger; the figure it named does not.** The prediction was *"something like 192 MB on a device that
can comfortably hold a 2 GB model"* — an implied gap of roughly 10×. Measured: **256 MB against
5,927,968,768 bytes, a factor of 22.1** — so the gap is more than twice what was predicted, while the
cap itself is a third *larger* than the 192 MB guessed, which is the direction that makes the gate
look less absurd rather than more. Scoring those two as one thing is how a document talks itself into
"the prediction was right"; see *The signal the probe refuses* below, where they are scored apart.

And a third, smaller, which is this document being wrong about itself: **two of the reproduction
commands in "How this document is kept true" do not do what they say.** Both were run. Both fail.
They are corrected at the foot of this file.

## Every signal the probe reads, off this handset

Through `dwProbeDevice(context)` — the app's own code, not a shell. That distinction is kept
throughout: shell readings are in their own table further down and are **not** the same evidence.

| signal | reported | notes |
|---|---|---|
| `totalMem` | **5,927,968,768 bytes** (5,653.352 MiB / 5.521 GiB) | identical across both runs and byte-identical to `/proc/meminfo`'s `MemTotal` |
| `availMem` | **1,533,587,456 bytes** (1.5 GB) at the moment of the reading | **not a property** — see the movement figures below |
| `isLowRamDevice()` | **false** | and no `ro.config.low_ram` property is set on the device |
| `StatFs.availableBytes` on `filesDir` | **41,247,846,400 bytes** (41.2 GB) | path `/data/user/0/com.designprototype.workshop/files` |
| `Build.SUPPORTED_ABIS` | **`[arm64-v8a, armeabi-v7a, armeabi]`** | 64-bit `[arm64-v8a]`, 32-bit `[armeabi-v7a, armeabi]`; primary is `arm64-v8a` |
| `getCurrentThermalStatus()` | **`NONE`** → `DwThermalState.NONE` | API 33, so it answers; `tooHotToStart = false` |
| `isCharging` | **true** | `dwTier2PowerAdvice` correctly returns `null` — a phone on the socket needs no advice |
| `takenAtElapsedMs` | 66,869,353 | `SystemClock.elapsedRealtime`, as documented |

The sentence a designer would actually read, printed by `dwDeviceReadoutSentence` from that reading:

> This phone reports 5.9 GB of memory in total, 1.5 GB of it free at this moment, and 41.2 GB of free
> storage. Android does not flag it as a low-memory device. Its processor is arm64-v8a.

### What the probe costs — timed for the first time

`DwDeviceProbe.kt` said in its own header that this *"HAS NOT BEEN TIMED ON A HANDSET, and this
comment will not invent a figure for it"*, which matters because its one caller runs it on the main
thread inside a `LaunchedEffect`. Ten consecutive calls, microseconds:

```
run 1  [ 9090, 1276, 1010, 1046, 1658, 1036, 1035, 1075,  904,   840]
run 2  [12850, 2328, 2089, 1389, 1259, 1504, 1474, 2207, 2621,  1951]
run 3  [ 9594, 2161, 2541, 1442, 1300, 1162, 1159, 1723, 2124,  2990]  ← review re-run
run 4  [12166, 2730, 2187, 1788, 1860, 1749, 1671, 1865, 1804,  1875]  ← review re-run
run 5  [10164, 2857, 2778, 1750, 1767, 7921, 8339, 4283, 2609, 12072]  ← review re-run
run 6  [10105, 1774, 2318, 2790, 1619,11532, 3188, 6508, 2780,  3028]  ← review re-run
```

**First call 9.1–12.9 ms. Every call after it 0.8 ms to 12.1 ms.** The first call pays for class
loading and the `filesDir` check and is the one a settings card actually experiences.

**THE STEADY-STATE FIGURE IS THE ONE THIS SECTION GOT WRONG, AND IT GOT WRONG TWICE IN ONE DAY.** It
was first written as "0.8–2.6 ms, median ~1–2 ms" off runs 1 and 2. Run 3 broke it at 2.99 ms and the
bound was widened to 3.0 ms. **Then runs 5 and 6 broke that too, and not marginally: a 7.9 ms, an
8.3 ms, an 11.5 ms and a 12.1 ms call, none of them the first call in its run.** Run 5's *last* call
cost 12,072 µs — more than its own first call. Two runs had shown a tidy warm-then-flat shape and it
was not the handset's shape; it was two runs.

| | over runs 1–2 (as first published) | over runs 1–6 |
|---|---|---|
| first call | 9.1–12.9 ms | 9.1–12.9 ms — **unchanged** |
| every call after it | 0.8–2.6 ms | **0.8–12.1 ms** |
| calls after the first exceeding 6 ms | none | **four, across two of the six runs** |

**What that does to the conclusion.** The rule in `DwDeviceProbe.kt` — *"if a handset ever turns up on
which this is not cheap, the measurement belongs in this document and the call belongs on
`Dispatchers.IO` — in that order"* — is **still not triggered**, because every call ever observed is
inside one 60 Hz frame (16.7 ms). But the earlier reading of that, "inside a frame but not by much,
and only on the first call", is wrong in the way that matters: **the expensive call is not reliably
the first one, so a re-probe on the main thread is not the cheap operation two runs made it look.**
`dwProbeIsStale` is two minutes and the card re-reads the handset each time it appears, so re-probes
are the common case, not the rare one. Whoever next touches that `LaunchedEffect` should read this
table rather than the sentence above it.

Conditions, because they are part of the measurement: all six runs were taken by the instrumented test
on a handset that was idle and on the charger, with the test harness itself running. Nothing else was
open. **No run was taken on a phone under real load, and that remains unmeasured.**

**A RANGE QUOTED FROM n RUNS IS ONLY EVER A FLOOR ON THE SPREAD.** Six runs of ten calls is fifty-four
steady-state samples and it is still a description of fifty-four samples, not a property of the
handset. This section has now been widened twice by the simple act of running it again.

### `availMem` moves, and how much

The reason nothing may be cached (`dwProbeIsStale`, two minutes) has never had a number under it.

| window | spread in `availMem`, one figure per run |
|---|---|
| ten back-to-back calls (~15 ms) | **888,832** / 372,736 / 761,856 bytes |
| six calls at 2 s intervals, idle phone — **a TEN-second window** | **14,446,592 (14 MB)** / 30,109,696 (30 MB) / 23,584,768 (24 MB) |

Between fourteen and thirty megabytes in ten seconds on an **idle** handset — which is the point, and
the point is stronger than one run made it look. `dwProbeIsStale` was confirmed against the real
reading: age 12,069 ms → `false`; the same reading judged 120,001 ms on → `true`.

**AND THE WINDOW IS TEN SECONDS, NOT THE TWELVE THIS TABLE SAID.** Six readings at two-second
intervals have **five** gaps between them, not six. The probe loop that took them slept *after* every
call including the last, so the run took twelve seconds of wall clock while the first and last
readings were only ten seconds apart — and the spread was then attributed to a window 20% longer than
the one it was observed over. Found on review by re-running it; `DwDeviceTierProbeTest` now sleeps
only *between* calls and **prints the window measured off `SystemClock.elapsedRealtime()`** rather
than asserting it from the loop count (the two runs since reported `10024ms` and `10030ms`). A window is a measurement
too, and this one had been arrived at by counting sleeps.

## Which row of the table — the band edges, against a real number

| | value | this handset |
|---|---|---|
| `DW_LOW_RAM_CEILING_BYTES` | 3,221,225,472 (3,072 MiB) | above it |
| `DW_FOUR_GB_CEILING_BYTES` | 5,767,168,000 (5,500 MiB) | **above it, by 160,800,768 bytes (153.35 MiB)** |
| `DW_EIGHT_GB_CEILING_BYTES` | 11,534,336,000 (11,000 MiB) | below it |

→ **`dwDeviceClass` = `MID_6_TO_8GB`, label "6–8 GB class".** `isLowRamDevice` is checked first and
is `false`, so the arithmetic ran.

**ONE HANDSET HAS NOW BEEN HELD AGAINST THE 5,500 MiB EDGE AND CLEARED IT BY 2.8%** — 5,653.352 MiB
against 5,500 MiB, by 160,800,768 bytes. Had this phone's firmware reserved 644 MiB instead of the
490.6 MiB it does, it would have been demoted into the 4 GB row — which is the direction the comment
calls safe, and it is, but the margin is a hundred and fifty megabytes rather than the comfortable gap
the choice reads like. **One handset is not a distribution.**

**AND IT IS NOT YET A MEASUREMENT OF THE THING THE EDGE WAS CHOSEN AGAINST, WHICH AN EARLIER DRAFT OF
THIS SECTION CLAIMED IT WAS.** The edge's comment says the chosen half rests on "the least a 6 GB
handset reports is UNMEASURED", so a reading only fills that gap **if this handset is a 6 GB one** —
and *nothing the handset said establishes that*. `totalMem` rules the 4 GB variant out arithmetically
(see the shortfall table below) and nothing rules the 8 GB variant out; the table below says so in as
many words. Six GB is the likelier of the two, but likelier is an inference, and an inference is not
what the word "measured" means in this file. **So the honest state of `DW_FOUR_GB_CEILING_BYTES` is:
one handset of unrecorded variant reports 5,653.352 MiB and lands above the edge; the least a 6 GB
handset reports remains unmeasured.** This paragraph replaces one that read "the 5,500 MiB edge is now
measured" — the same class of defect as the fixture named after a handset it did not describe.

### The reported-versus-box shortfall, which this document called unmeasured

`DwDeviceMeasurement.totalRamBytes` says *"ALWAYS SMALLER THAN THE NUMBER ON THE BOX… HOW FAR BELOW IS
UNMEASURED"*. **The reading is 5,927,968,768 bytes. The shortfall depends on what the box said, and
the box is not something the probe can read**, so the arithmetic is printed against several sizes and
only one of them can be the right one:

| if the handset is sold as | invisible to the kernel |
|---|---|
| 6 GB | **514,482,176 bytes — 490.6 MiB, 7.99% of 6 GiB** |
| 8 GB | 2,661,965,824 bytes — 2,538.6 MiB, 30.99% |
| 4 GB | −1,633,001,472 bytes — **impossible**, and that is the useful row: it rules the 4 GB variant out arithmetically rather than by looking anything up |

The 6 GB line is the plausible one and it is the one quoted elsewhere in this document as shorthand.
The guaranteed-ceiling rule that every edge depends on ("a handset sold as N GB cannot report more
than N GiB") holds for it: 5.521 GiB < 6 GiB. **One data point, on one Samsung, on one firmware. It is
not a general figure and must not be used as one** — and the 8 GB row is not excluded by anything the
handset said, only by the 6 GB row being far more likely.

## The signal the probe refuses, measured anyway

`ActivityManager.getMemoryClass()` is deliberately not read, on the argument that it is the Dalvik
heap cap and would be wrong by an order of magnitude for a natively-allocated model. **That argument
has now been checked rather than repeated.**

| | |
|---|---|
| `ActivityManager.getMemoryClass()` | **256 MB** (268,435,456 bytes) |
| `ActivityManager.getLargeMemoryClass()` | **512 MB** (536,870,912 bytes) |
| `Runtime.getRuntime().maxMemory()` | **536,870,912 bytes** — this app sets `android:largeHeap="true"`, so the large cap is the one in force |
| `MemoryInfo.totalMem` — what the probe **does** read | **5,927,968,768 bytes** |

**`totalMem` / `getMemoryClass()` = 22.1×. Against the large cap this app actually runs at, 11.0×.**
A hypothetical 1.5 GB model would be **5.6× the heap cap** and **0.25× the phone's total memory** —
the two answers a gate would give, side by side, pointing opposite ways. Gating on `getMemoryClass()`
would refuse this handset a model it has four times the memory for.

Confirmed independently from the shell: `dalvik.vm.heapgrowthlimit = 256m`, `dalvik.vm.heapsize =
512m`. The two properties are exactly the two figures above, which is what `getMemoryClass()` returns
and why it can say nothing about native allocation.

**The prediction's CONCLUSION held; its NUMBER did not, and the two must be scored separately.** The
sentence under test is *"`getMemoryClass()` will report something like 192 MB on a device that can
comfortably hold a 2 GB model"*.

| what the prediction said | what the handset said | |
|---|---|---|
| the cap is "something like 192 MB" | **256 MB** | **wrong, by a third, and wrong in the direction that WEAKENS the argument** — a bigger heap cap is a less absurd gate, not a more absurd one |
| the cap is uselessly small beside what the device can hold | 256 MB beside 5,927,968,768 | **held.** The implied ratio in the prediction was about 10× (192 MB against a 2 GB-class device); measured, it is **22.1×** |

So the paragraph is promoted from argument to measurement, and the *argument* comes out stronger than
it was written — but **an earlier draft of this section said the prediction "was right and, if anything,
conservative", which is only true of the ratio and is false of the figure it actually named.** A
guess of 192 against a real 256 is a third low, and recording that as "right" would be the same
rounding-towards-one's-own-case that this file was written to stop. The refusal itself needs no
correction: the gap is 22.1×, the shell agrees from the other side, and gating on the heap cap would
still refuse this handset a model it has four times the memory for.

The grep guard that protects the refusal holds — this call lives in `androidTest/` and never in
`src/main/` — **though the guard's own command had to be fixed twice before it could fail; see "How
this document is kept true".**

### And the second refused signal: `/proc/meminfo` parsed by hand

The same file argues that hand-parsing `/proc/meminfo` *"would give this app two answers to one
question that could disagree"*. Both answers were taken within a millisecond of each other:

| | platform | by hand | agree? |
|---|---|---|---|
| total | `MemoryInfo.totalMem` = 5,927,968,768 | `MemTotal` = 5,927,968,768 | **yes, byte-identical** |
| available | `MemoryInfo.availMem` = 1,526,317,056 | `MemAvailable` = 1,233,973,248 | **no — 292,343,808 bytes apart (292 MB)** |
| | | `MemFree` = 162,889,728 | a different question entirely |

**"Could disagree" is now "does disagree, by 292 MB, at one instant, on this handset" — 23.7% of the
hand-parsed figure.** The refusal to parse it was correct and is now correct *for a measured reason*:
an app holding both numbers would have had to choose, and a 292 MB disagreement is larger than half
the free-RAM margin the fit arithmetic keeps.

### Two `MemoryInfo` fields the probe reads and throws away

`getMemoryInfo` fills four fields; `DwDeviceMeasurement` carries two. The other two, measured:

- **`threshold` = 317,718,528 bytes (318 MB)** — the platform's own low-memory line on this handset.
- **`lowMemory` = false**, with `availMem − threshold` = 1,208,598,528 bytes of room above it.

That makes `DW_MODEL_FREE_RAM_MARGIN_BYTES` (512 MiB, a **chosen** number) **1.7× the platform's own
threshold** on this phone — the first time that constant has been compared with anything the platform
reports. It is not a reason to change it: the margin stands for the app's own retained bitmaps and
draft as well as for the system's line, and erring towards refusing is the documented direction.
Whether 1.7× is right is still **unmeasured**, and stays unmeasured until something loads a model.

## `StatFs`: available, free and the root reservation

`DwDeviceProbe.kt` reads `availableBytes` and not `freeBytes`, because "the free figure includes
blocks reserved for root that this app can never have". Measured on the app's own files directory:

| | bytes | |
|---|---|---|
| `totalBytes` | 116,003,962,880 | 116.0 GB volume |
| `freeBytes` | 41,381,859,328 | includes root-reserved blocks |
| `availableBytes` — what the probe reads | **41,247,846,400** | |
| difference | **134,217,728** | **exactly 128 MiB** |

The reservation is real and it is exactly 128 MiB. On this handset it is small beside the free space;
on a phone at the end of a workshop day with 200 MB left it is most of what a naive `freeBytes` would
have promised. `blockSize = 4096`, `blockCount = 28,321,280`, `availableBlocks = 10,070,225`.

## What `dwRecommendTiers` actually answers on this handset

Every combination was run: two runtime statuses × three connections. **The answer is the same in all
six**, which is itself the finding — the fleet's phone reaches today's refusals by the documented
route rather than by accident.

| | |
|---|---|
| `dwConnection(context)` live | `UNMETERED` |
| `dwAsrArtifactFor(abis)` | `null` (`DW_ASR_ARTIFACTS` is empty) → production status `NOT_INSTALLED` |
| `dwAsrMayLoad(production)` | `false` |
| `deviceClass` | `MID_6_TO_8GB` |
| **`tier1`** | **`None(NO_RUNTIME_IN_THIS_BUILD)`** |
| **`tier2`** | **`None(NO_MEASURED_MODEL)`** |
| `tier3Available` | `false` at `NONE`, `true` at `METERED` and `UNMETERED` |
| `dwTierDownloadMayBeOffered` | **`false` for both tiers on all three connections** |
| `dwAsrOffer` | `NOTHING_PUBLISHED_TO_INSTALL`, `dwAsrMayInstall = false`, on all three |

**The `RUNTIME_UNMEASURED` row of the Tier 1 table is confirmed unreachable on a real device, and for
the documented reason.** A caller that passes no runtime status at all — whose engine state is
therefore `UNKNOWN` — still gets `NO_RUNTIME_IN_THIS_BUILD` and not `RUNTIME_UNMEASURED`, because
`dwAsrOffer` checks the empty catalogue before it checks the unread disk. `DwAsrRuntime.kt` records
that this ordering was got wrong once and fixed; the handset agrees with the fixed version.

The two sentences this phone would actually print are the ones the code documents, verbatim in
logcat — the Tier 1 one naming the card above it and Android's own packs, the Tier 2 one naming this
document and the two open questions.

## The thermal and charging signals at rest — read on a real device for the first time

- `getCurrentThermalStatus()` → **`THERMAL_STATUS_NONE`** → `DwThermalState.NONE`, `tooHotToStart =
  false`. API 33, so the "unmeasured on API 26–28" branch was not exercised and remains unmeasured.
- `isCharging` → **true**; `dwTier2PowerAdvice(true)` → `null`, correctly silent.
- `dwTier2RunWindow(realOffer, capturing = false, NONE)` → **`NOTHING_TO_RUN`**
- `dwTier2RunWindow(realOffer, capturing = true, NONE)` → **`NOTHING_TO_RUN`**

Both answer `NOTHING_TO_RUN` because the offer is a `None`, so **the capture bar is never reached on
a real handset today** — the documented order, confirmed. The thermal reading above therefore gates
nothing; it is recorded as a reading of the handset at rest, not as a decision. The `MODERATE`
threshold in `DwThermalState.tooHotToStart` is still a **chosen** number and stays unmeasured until
something sustains a load long enough to move it.

## The shell readings — DIFFERENT EVIDENCE, kept apart on purpose

Everything above came from the platform's own APIs **through this app's code**. Everything below came
from `adb shell` and is recorded separately because it answers a slightly different question and was
taken at a different moment. They agree where they should.

| command | result |
|---|---|
| `adb shell head -5 /proc/meminfo` | `MemTotal: 5789032 kB` (= 5,927,968,768 bytes, **exactly `totalMem`**), `MemFree: 138932 kB`, `MemAvailable: 1363684 kB` |
| `adb shell df /data` | `/dev/block/dm-47`, 113,285,120 1K-blocks, 72,800,524 used, **40,353,524 available**, 65% |
| `adb shell getprop` | `dalvik.vm.heapgrowthlimit = 256m`, `dalvik.vm.heapsize = 512m`, `ro.boot.hardware = mt6769t`; **no `ro.config.low_ram`** |
| `adb shell dumpsys thermalservice` | `Thermal Status: 0`; AP 33.3 °C, SKIN 31.4 °C, PA 31.8 °C, BAT 28.6 °C; ThermalHAL 2.0 connected |

The thermal temperatures are the one thing here with no counterpart above: the platform's own API
gives a *status*, not degrees, and this app reads the status. The degrees are recorded because "the
handset was cool" is otherwise an assertion, and a thermal reading of `NONE` taken on a hot phone
would mean something quite different.

## What could not be measured, and why

**Not "not attempted" — impossible, and the reason is the same one for all four.**

| wanted | why it is still the word unmeasured |
|---|---|
| Tier 2 peak RSS at a context cap | ~~**There is no artifact to load.** No Gemma mobile export for Android is published, pinned or verified anywhere in this repository, and `DW_TIER2_CATALOGUE` is empty.~~ **HALF ANSWERED 2026-08-13 — the artifact half. Four mobile exports exist and two of them are on this machine's disk, weighed and hashed; see `TIER2-LANGUAGE-MODEL-MEASUREMENT.md`. `DW_TIER2_CATALOGUE` now holds two rows.** What is still unmeasured is the thing this row is actually about: **peak RSS on a handset in this fleet.** The figures in those rows are Google's, off a Galaxy S26 Ultra, and every sentence built from them says so. It is now *attemptable* rather than impossible — the blocker is that no runtime in this APK can load the file (Kotlin metadata, same document), not that there is nothing to load. |
| Tier 2 tokens/sec, time to first token | Same shape: Google publish 46.9 t/s decode (E2B, CPU, S26 Ultra) and 17.7 (E4B); nothing has been timed here, and a published throughput on a flagship is not a measurement of a Helio G85. |
| Tier 1 WER, or anything about a speech model | ~~**There is no engine and no model.** `docs/ASR-RUNTIME-MEASUREMENT.md` §1: sherpa-onnx is on neither `google()` nor `mavenCentral()` — six coordinates, six 404s — and §3 records that the AAR carries no `assets/` entry at all. `DW_ASR_ARTIFACTS` is empty. No IndicConformer export is pinned anywhere.~~ **ANSWERED THE SAME DAY — see *THE ENGINE RUNS* below.** The engine was vendored from the GitHub release asset and a model was found and pinned. Both halves of the sentence above were true when written and are now false, and the §1 finding they rest on is untouched: sherpa-onnx still does not resolve from either repository, which is *why* it had to be vendored rather than depended on. |
| "survives being backgrounded with the model loaded?" | ~~Needs a loaded model.~~ **PARTIALLY ANSWERED — see *THE ENGINE RUNS* below.** A model was loaded and the process survived Home and decoded again. What is still **unmeasured** is the question this row was really asking, because a process under instrumentation keeps `oom_score_adj = 0` and is therefore not a backgrounded app as the low-memory killer sees one. |
| Whether the rest of the M32 fleet is the 4 GB variant | One handset was in the room. The others have not been surveyed, and a distribution cannot be inferred from n = 1. |
| `DwThermalState.UNMEASURED` on a real API 26–28 handset | This device is API 33. That branch has still never run on hardware. |
| Whether `dwProbeDevice` is cheap on a *slow* handset | Timed on one idle, charging handset reporting 5.5 GiB, four runs. A smaller phone under load is a different question, and so is this one under load. |

**A device does not unblock a measurement that needs a file nobody has published.** Saying so plainly
is part of this result: steps 5, 7 and 8 of the plan's sequence remain blocked on an artifact, not on
hardware, and no amount of handset time changes that.

> **AND THE SENTENCE ABOVE WAS OVERTAKEN ON THE EVENING OF THE SAME DAY, WHICH IS WORTH MORE THAN THE
> SENTENCE WAS.** It is still true as logic and it was false as a description of the world within
> hours, because *"a file nobody has published"* was an assumption about the world rather than a
> reading of it, and nobody had gone and looked properly. Somebody then did: the sherpa-onnx project
> publishes a 498-asset model index, and one of those assets is a speech model that emits Odia. The
> lesson this file keeps relearning, in its third shape now — after a fixture that agreed with a
> handset that did not exist, and a register that called itself "every place it still lives" while
> missing a site in its own body — is that **an absence is a claim, and a claim needs a command
> beside it.** The command was `curl` against the GitHub releases API, and it took a minute.
>
> Steps 5 and 7 are unblocked to the extent recorded below. Step 8 (Tier 2, an SLM) is not: nothing
> in this paragraph is about Gemma, and question 1 at the foot of this document is still open.

---

# MEASURED 2026-08-12, EVENING — THE ENGINE RUNS ON THE FLEET'S HANDSET, AND HERE IS WHAT IT COSTS

**Audio in, text out, on the SM-M325F.** This section fills the four cells named above and it is
written in the same shape as the probe section at the head of this file: raw readout first, method
next to every figure, and the word **unmeasured** wherever a figure would be an invention.

Taken by `android/app/src/androidTest/java/com/designprototype/workshop/DwAsrEngineProbeTest.kt`,
which loads the real engine through the real verification gate and prints everything. **Two complete
runs**, twenty minutes apart, one driven by Gradle and one by `am instrument` directly against the
installed APKs; every transcript below is **byte-identical between them**, and the two peak-RSS
figures differ by 0.14%.

```
adb push model.int8.onnx tokens.txt   /data/local/tmp/dwasr/
adb push <wavs>                       /data/local/tmp/dwasr/wav/
adb shell chmod -R 755 /data/local/tmp/dwasr
cd android && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.designprototype.workshop.DwAsrEngineProbeTest
adb logcat -d -s DWASRPROBE:I
```

## What is running: the engine, and how it got into the build

| | |
|---|---|
| engine | `sherpa-onnx-static-link-onnxruntime-1.13.5.aar`, 37,749,854 bytes, SHA-256 `508b79be1aeef3cbb92b8d4325b9b1dad0fa9a4eb1991de0d3d1826b8a09c358` |
| how | **vendored** into `android/app/libs/` and reached through a `flatDir` in `settings.gradle.kts`. `ASR-RUNTIME-MEASUREMENT.md` §1's three routes, first one taken |
| which variant, and why | static-link, per that document's recommendation 2: +39,811,828 packaged bytes against +53,308,196 |
| R8 | keep rules for `com.k2fsa.sherpa.onnx.**` added to `proguard-rules.pro` **in the same pass**, before any release build — the trap both documents ordered to be sprung first |

**A pleasing cross-check nobody planned.** The `.so` sizes inside that AAR are `arm64-v8a`
**23,646,824** and `armeabi-v7a` **16,152,132** — byte for byte the constants
`DW_ASR_ENGINE_BYTES_ARM64` and `DW_ASR_ENGINE_BYTES_ARM32`, which were arrived at by *subtracting
two packaged APKs from each other*. Two completely different methods, the same two numbers, so §2 of
`ASR-RUNTIME-MEASUREMENT.md` is confirmed from a direction it did not use.

## What is running: the model, and why it is not the one the plan named

> **THIS SECTION'S HEADLINE WAS WRONG AND IS CORRECTED BELOW, 2026-08-13.** IndicConformer **is**
> available in a form this app may ship, it **does** load on the sherpa-onnx already in this APK, and
> **Odia is in it**. The search recorded here was real work and it is left standing, because the useful
> part is seeing *how* it missed: every row below looked at the **older per-language `.nemo`
> checkpoints** and at **third-party** conversions, and not one row looked at the official
> **`ai4bharat/indic-conformer-600m-multilingual`**, which publishes ONNX directly under MIT.

~~**Plan §2.2 named AI4Bharat IndicConformer and it is not available in a form this app may ship.**~~ It
was looked for properly; the search is recorded here so nobody repeats it:

| where | what was found |
|---|---|
| the official `k2-fsa/sherpa-onnx` `asr-models` release | **498 assets, no Indic model.** Read out of the GitHub API. The only `nemo-ctc` exports are English, French, German, Chinese, Russian and Persian |
| `ai4bharat/indicconformer_stt_or_hybrid_ctc_rnnt_large` (the Odia one) | **a 523,192,320-byte `.nemo` file and nothing else.** NeMo is a training-checkpoint format; sherpa-onnx cannot open one, and exporting it is a pipeline nobody here has run or reviewed |
| Hugging Face, third-party ONNX conversions of IndicConformer | ~~**three exist and none serves Odia.**~~ ~~**The "Malayalam-only" one serves all 22.** It emits **5633** classes and ships a **5633-line** `tokens.txt`; it was read as monolingual because its card names one language.~~ **BOTH OF THOSE ARE WRONG AND THE FIRST WAS RIGHT. Corrected 2026-08-13, later, by feeding it audio instead of reading its files** — see *the correction to the correction* below. `jeswinjestin/sherpa-onnx-nemo-ctc-indicconformer-malayalam` **is Malayalam-only in effect**: handed the same Odia and Hindi FLEURS clips the official model scores 16.7% and 20.9% WER on, it returns fluent **Malayalam script**, 100% WER, every utterance |
| Whisper | **ninety-nine languages, Odia not among them**, which `DwModelPlan.languages` already recorded |
| **the row nobody ran:** `ai4bharat/indic-conformer-600m-multilingual` | **MIT. 404 files, 2,556,502,676 bytes. ONNX, not `.nemo`.** A 600M Conformer for all 22 scheduled languages, exported as `encoder.onnx` + `ctc_decoder.onnx` + an RNNT branch. **This is the row that makes the headline false** |

`ASR-RUNTIME-MEASUREMENT.md` §1 declined an individual's repackage of the *engine* as *"a
supply-chain decision belonging to a person and not to this lane"*. **A repackaged model is the same
decision with the same owner**, so it was declined the same way. **That principle was right and is
untouched — it simply never needed to be invoked here**, because the official repo carries the ONNX
itself and no repackage is involved.

### The correction, measured 2026-08-13

| | |
|---|---|
| does sherpa-onnx load it? | **YES.** `encoder.onnx` is `audio_signal[B,80,T]`, `length[B]` → `outputs[B,1024,T']`; `ctc_decoder.onnx` is two nodes, a Conv 1×1 with weight `[5633,1024,1]` then a Transpose → `logprobs[B,T',5633]`. Concatenated that is **exactly** the NeMo-CTC contract, so the two graphs were merged into one and `OfflineRecognizer.from_nemo_ctc` opened it on **sherpa-onnx 1.13.5, the version vendored in this APK**. It decoded real audio |
| was a `.nemo` export pipeline needed? | **No.** The published ONNX was used as-is. The merge is a graph edit that appends two nodes; it materialises none of the weights |
| how are the 22 languages selected? | **`assets/language_masks.json`: a boolean mask per language over the shared 5633-class space, selecting exactly 257 columns** — a contiguous 256-wide block plus the single shared blank at 5632. 22 × 256 + 1 = 5633 exactly, and the blocks are disjoint. **Odia is block 14, columns 3584–3839** |
| is the mask optional? | **No, and this is the one thing that must not be guessed.** Decoded over the full unmasked 5633 space the model is acoustically right but spells the answer in *mixed scripts* — a Malayalam clip came back as `হाாய় ನमस्स्କାରారంം இது ஒரு ডెमो…`, drawing tokens from Bengali, Tamil, Kannada, Devanagari, Odia and Telugu blocks at once. The mask is what makes the output a language |
| shared encoder, fp32 | **2,428,824,576 bytes** across **366** external weight files |
| merged graph, all 22 languages | **26,072,246 bytes** (`sherpa_multi.onnx`) |
| merged graph, one language | **4,030,572 bytes** (head sliced 5633 → 257 rows) |
| **so what does the 22nd language cost?** | **22,041,674 bytes.** One language and all twenty-two differ by 22 MB against a 2.43 GB shared encoder. **Per-language downloads would re-send the same encoder 22 times** |
| fp32 decode speed | **RTF ≈ 0.22 on a desktop CPU**, 2 threads, greedy CTC — five times *faster* than real time. An earlier figure of 7.9 written during this same lane was **wrong and is retracted**: it timed the first decode after a cold load off the spinning `D:` disk, so it measured page faults. From the SSD the warm decodes read 0.218, 0.202, 0.230, 0.235, 0.215. Cold load 44.0 s, warm 11.4 s |
| **accuracy, and it is the reason to care** | **Odia CER 5.1%, WER 16.7%. Hindi CER 6.9%, WER 20.9%.** Greedy CTC, fp32, the same FLEURS utterances as the Omnilingual run. Scored **on identical references through one normaliser**: Odia **WER 52.8% → 13.9%** (2 shared references), Hindi **24.4% → 20.9%** (3). The old 53.3% Odia figure reproduces at 52.8% here, so the baseline holds. **Odia error falls ~3.8×** — and Odia is the language of the state these workshops are run in. Still studio speech, so still a ceiling |
| why is Omnilingual still pinned? | **Only because of memory — and that stopped being an open question on 2026-08-13.** Not availability — it loads. Not speed — RTF 0.22. Not accuracy — 3.8× better at Odia. The fp32 weights are **2,428,824,576 bytes** against **1,340,412 kB `MemAvailable`** on the fleet's own SM-M325F (**1,058,148 kB** when re-read the next morning), so they cannot load there at all. ~~int8 remains unmeasured~~ **int8 was measured and it does not transcribe**: `quantize_dynamic` default op set → **654,790,526 bytes**, decodes the **empty string** on all three Odia utterances; `op_types_to_quantize=["MatMul"]` → **883,021,360 bytes**, *larger*, decodes the single character `ପ`. Both load fine; both are useless. **So the 600M is refused on two measurements rather than one, and the route is the official 120M export.** |

What was pinned instead:

| | |
|---|---|
| model | `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12` |
| what it is | sherpa-onnx's own ONNX export of **Meta's Omnilingual ASR CTC 300M**, int8 |
| where from | the **same GitHub release index as the engine AAR**, which is the whole reason it was preferred over the IndicConformer conversions: trusting it is the trust decision this repository has already taken once, not a second one taken quietly |
| container | 292,571,207 bytes, SHA-256 `cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed` |
| `model.int8.onnx` | 365,352,120 bytes, SHA-256 `e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c` |
| `tokens.txt` | 86,423 bytes, SHA-256 `a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31` |

Both file digests are pinned in `DW_ASR_MODELS` (`data/DwAsrModel.kt`), taken off the bytes that were
downloaded, and **re-taken off the files on the phone in every run before anything is loaded** — the
verdict comes from the app's own `dwAsrVerify`, and both runs answered `VERIFIED` for both files.

## THE TRANSCRIPTS. Verbatim, beside what was actually said

Audio is **Google FLEURS `or_in` and `hi_in` dev**, 16 kHz mono, converted from the dataset's float32
WAVs to 16-bit PCM. It is read speech by a studio speaker, so **it is an easier test than a
courtyard** and the numbers below should be read as a ceiling rather than a field result. The
reference is the dataset's own normalised transcription. Nothing was cherry-picked: these are the
first three files out of each archive.

### Odia — the language this whole feature exists for

> **REFERENCE** ହାତୀ ଓ ଜିରାଫ୍ ଭଳି ପଶୁମାନଙ୍କର କାର୍ ଏବଂ ଭଲ ଦେଖାଯାଉଥିବା ମାନକ ସରଞ୍ଜାମକୁ ନିକଟରୁ ଦେଖିବା ପାଇଁ ପାଖକୁ ଆସିବାର ପ୍ରବୃତ୍ତି ଥାଏ
>
> **ENGINE** ହାତିଓ ଜିରାଭଳି ପସୁମାନଙ୍କର କାର ଏବଂ ଭଲ ଦେଖାଯାଅଥିବା ମାନକ ସରଞଜାମରୁ ନିକଟରୁ ଦେଖିବା ପାଇଁ ବାଖକୁ ଆସିବାର ପ୍ରବୃତ୍ତି ଥାଏ

> **REFERENCE** ଦୟାକରି ଏହି ସ୍ଥାନକୁ ଯଥାର୍ଥ ସାଧୁତା ଗମ୍ଭୀରତା ଏବଂ ସମ୍ମାନର ସହିତ ବିଚାର କରନ୍ତୁ ହୋଲକୋଷ୍ଟ କିମ୍ବା ନାଜିମାନଙ୍କ ବିଷୟରେ ଥଟ୍ଟା କରନ୍ତୁ ନାହିଁ
>
> **ENGINE** ଦୟାକରି ଏହି ସ୍ଥାନ ପୁଜଥାର୍ଥ ସାଧୁତା ଗମ୍ବିରତା ବୁୁଁସନ୍ମାନର ସହିତେ ବିଚାର କରନ୍ତୁ ହୋଲକୋଷ୍ଟ କିମ୍ବା ନାଜୀମାନଙ୍କ ବିଷରେ ଥଟା କରନ୍ତୁ ନାହିଁ

> **REFERENCE** 108 ପ୍ରକାର ଛପନ ଭୋଗ ହିନ୍ଦୁ ଧର୍ମରେ 56 ପ୍ରକାର ଖାଦ୍ୟ ସାମଗ୍ରୀ ଯଥା ମିଠା ଫଳ ବାଦାମ ବ୍ୟଞ୍ଜନ ଇତ୍ୟାଦି ଯାହା ଭଗବାନଙ୍କୁ ଅର୍ପଣ କରାଯାଏ ବାବା ଶ୍ୟାମଙ୍କୁ ଅର୍ପଣ କରଯାଇଥିଲା
>
> **ENGINE** ସହେ ଆଠେ ପ୍ରକାର ଛପନ ଭୋଗ ହିନ୍ଦୁ ଧର୍ମରେ ଛପନ ପ୍ରକାର ଖାଦ୍ୟସାମଗ୍ରୀ ଯାଥା ମିଠା ଫଳ ବାଦାମ ବେଂଜନ ଇତ୍ୟଦୀ ଯାହ ଭଗବନନଂକୁ ଅର୍ପଣ ପରାଯାଏ ବାବା ଶାମଙକୁ ଅର୍ପଣ ପରାଯାଏ ଥିଲା

### Hindi

> **REFERENCE** मेजरकेन खाना भूमध्य सागर में समान क्षेत्रों की तरह रोटी सब्जियों और मांस विशेष रूप से सूअर का मांस पर आधारित है और जैतून के तेल का उपयोग करता है।
>
> **ENGINE** मेजर किन खाना भू मध्य सागर में सामान क्षेत्रों की तरह रोटी सब्जियों और मास विशेष रूप से सुर का मास पर आधारित है और जैतून के तेल का उपयोग करता है

> **REFERENCE** वैज्ञानिकों का मानना है कि ओसेलोट्स गंध के द्वारा जानवरों का पीछा करते और खाते शिकार हैं यह सूँघते हुए कि वे ज़मीन पर कहाँ रहे होंगे।
>
> **ENGINE** ज्ञानिकों का मानना है कि ओसे लोट्स गंद के द्वारा जानवरों का पीछा करते और खाते हैं यह सूंते हुए कि वह ज़मीन पर कहाँ रहे होंगे

> **REFERENCE** आर्मंड वर्सास ने कहा मैंने अपनी बहन और उसकी दोस्त को खो दिया और रास्ते में व्हीलचेयर में दो दिव्यांग लोग थे और लोग उनपर उछल रहे थे और उन्हें धक्का दे रहे थे
>
> **ENGINE** आर्मड वर्षास ने कहा मैंने अपनी बहन और उनकी दोस्त को खोदिया और रास्ते में हुलचेर में दो दिब्यांग लोग थे और लोग उनपर उछल रहे थे और उन्हें धक्का दे रहे थे

### Scored, and the score is not good enough to offer yet

Levenshtein against the normalised reference, punctuation stripped, computed on this machine from the
strings above. **n = 3 utterances per language, which is a demonstration and not an evaluation.**

| | CER | WER | reference words |
|---|---|---|---|
| Odia | **15.2%** | **53.3%** | 60 |
| Hindi | **7.3%** | **24.2%** | 91 |

**READ THE WER, NOT THE CER.** A 15% character error rate on Odia looks tolerable and the word error
rate says what it actually means: **more than half the words are wrong.** The failures are real
Odia-shaped errors rather than noise — ହାତୀ→ହାତି, ପଶୁ→ପସୁ, a dropped ଫ୍ collapsing ଜିରାଫ୍ ଭଳି into
ଜିରାଭଳି, ପାଖକୁ→ବାଖକୁ, and "108" and "56" coming out as spelled-out words — which is exactly the
profile of a model that has heard the language but not enough of it. On the third Odia file it
inserts a whole phrase that is not there.

**So the honest verdict is: it hears Odia, and it is not yet good enough to put in front of a
designer.** Plan §2.2 sets a WER bar before offering it at all, and 53% on *read studio speech* does
not clear any bar somebody would set for a courtyard. Nothing in the app has been switched on off the
back of this, and ~~the two catalogues that would switch it on are still empty~~ — see *What was and
was not changed* below. **That last clause went stale within the day: `DW_TIER1_CATALOGUE` was filled
on 2026-08-13 with this same artifact at `languages = ["hi-IN"]`.** Odia is still not offered — the row
deliberately omits `or-IN` — but the catalogue is not empty, and the same stale claim had to be
corrected in `DwModelLanguages.kt`'s header, where it had also been left behind. See the section
immediately below.

### MEASURED 2026-08-13 — ELEVEN LANGUAGES ON THIS HANDSET, NOT TWO, AND ONE OF THEM FAILS IN A
### WAY A WER FIGURE DOES NOT DESCRIBE

The section above ends *"the two catalogues that would switch it on are still empty"*. **That
sentence is stale rather than wrong-at-the-time**: `DW_TIER1_CATALOGUE` was filled later the same
day with this very artifact (`languages = ["hi-IN"]`), and `DwModelLanguages.kt` carried the same
stale claim in its header until it was corrected on 2026-08-13. Odia is still not offered; the row
exists and deliberately omits `or-IN`.

**What was run.** One `DwAsrEngineProbeTest` invocation on the fleet's own SM-M325F (Android 13,
arm64-v8a), `am instrument` rather than `connectedDebugAndroidTest` so the app was not uninstalled
afterwards. The pinned model was copied into `filesDir` (365,438,543 bytes in 13,412 ms) and both
files hashed **on the phone, in that run**, to the digests compiled into the APK — `model.int8.onnx`
`e7c4e54e…52a2726c` and `tokens.txt` `a7a044c5…9a7d0b31`, both **VERIFIED**. Recogniser constructed
in **3,334 ms** through `OfflineOmnilingualAsrCtcModelConfig` — the shipped
`DwAsrModelFamily.OMNILINGUAL_ASR_CTC` branch, family printed by the probe as it loaded. Thirty-nine
WAVs decoded through one loaded recogniser, so every row below shares a handset, `numThreads = 2`, a
corpus split and a normaliser. Peak `VmHWM` over the whole run: **1,458,905,088 bytes**.

**Audio.** Google FLEURS, `validation` split, first three utterances of each language, 16 kHz mono
PCM16. Nine languages were new to this repository; Odia and Hindi were re-run as a control. Of
IndicConformer's 22, FLEURS covers 14 and has **no split at all** for Bodo, Dogri, Konkani, Kashmiri,
Maithili, Manipuri, Sanskrit and Santali — which is the ceiling on how far this method can be pushed.
See the bottom of this section for what that leaves unmeasured.

| language | tag | n | ref words | CER | WER | script emitted |
|---|---|---|---|---|---|---|
| Hindi ✅ offered | `hi-IN` | 3 | 91 | 7.5% | **24.4%** | Devanagari |
| Odia | `or-IN` | 3 | 60 | 13.8% | **51.4%** | Odia |
| Bengali | `bn-IN` | 3 | 53 | 10.6% | **43.2%** | Bengali-Assamese |
| Gujarati | `gu-IN` | 3 | 63 | 6.1% | **24.9%** | Gujarati |
| Kannada | `kn-IN` | 3 | 55 | 6.2% | **24.7%** | Kannada |
| Malayalam | `ml-IN` | 3 | 51 | 9.5% | **54.1%** | Malayalam |
| Nepali | `ne-IN` | 3 | 38 | 15.3% | **45.9%** | Devanagari |
| Punjabi | `pa-IN` | 3 | 94 | 10.7% | **24.9%** | Gurmukhi |
| Tamil | `ta-IN` | 3 | 58 | 8.2% | **52.0%** | Tamil |
| Telugu | `te-IN` | 3 | 46 | 18.0% | **54.6%** | Telugu |
| Urdu | `ur-IN` | 3 | 87 | 85.5% | **100.0%** | **Devanagari — and Perso-Arabic is the language's own** |

**The control reproduces.** Odia came back at CER 13.8 / WER 51.4 against the 15.2 / 53.3 recorded
above, and Hindi at 7.5 / 24.4 against 7.3 / 24.2 — measured off the dataset's `transcription` field
rather than the `dev.tsv` the earlier run used, which is the whole of the difference. **The recorded
figures stand and the earlier rows in `DW_TIER1_CATALOGUE.accuracy` were left exactly as they were.**

#### THE URDU ROW IS THE FINDING, AND IT IS NOT A WER PROBLEM

`tokens.txt` carries **155 tokens in Arabic script**, and `DwAsrModel.kt` has cited that number since
the model was pinned as evidence the artifact is *able* to write the script — while saying in the same
breath that being able to write a script is necessary and not sufficient. **Handed Urdu speech this
artifact emits not one Arabic character.** It answers in fluent Devanagari, transliterating the
utterance into Hindi, three times out of three:

- `dwlang-ur_0.wav` — CER 80.0, WER 100.0, {'Deva': 72}
  - REF: آپ اہرام کو تاریکی میں دیکھ سکتے ہیں اور آپ ان کو شو شروع ہونے سے پہلے کے سکون میں بھی دیکھ سکتے ہیں
  - HYP: आप अहराम को तारीखी में देख सकते हैं और इनको शो शुरू होने से पहले के सुकून में भी देख सकते हैं
- `dwlang-ur_1.wav` — CER 84.9, WER 100.0, {'Deva': 109}
  - REF: یہ پارک 19،500 کلومیٹر رقبے پر محیط ہے اور یہ 14 مختلف ماحولیاتی علاقوں میں تقسیم کیا ہوا ہے، جن میں سے ہر علاقہ ایک الگ جنگلی حیات کے لیے سازگار ہے۔
  - HYP: यह पार्क किलोमीटर रकबे पर मुहीत है कि चौदा मुख्तलिफ़ महाल याती इलाकों में तकसीम हुआ है जिनमें से हर एक की अलग जंगले हयात के लिए कारसास है
- `dwlang-ur_2.wav` — CER 91.6, WER 100.0, {'Deva': 146}
  - REF: پوائنٹ میرین اور فیئرماؤنٹ کے درمیان پھیلاؤ کے سبب بفیلو-پٹسبرگ ہائیوے پر الگ تھلگ اندرونی جنگلی علاقے کے درمیان سے کثرت سے گزرتے ہوئے گاڑی چلانا مشکل بھرا کام ہوتا ہے
  - HYP: पॉइंट मेरियन और फेयरमाउंट के दरम्यान फहलाओं के सबब बेफेलोपेस्ट्रकबर्ग घायवे पर अलग थलग अंदरूनी जंगली इलाके के दरम्यान से कसरत से गुज़रते हुए गाड़ी चलाना मुश्किल भरा काम होता है

It **heard** the sentence and wrote it in the other language's alphabet. Every word is a miss by
construction, so 100% is the honest WER rather than a high number that could be read as "nearly";
the CER of 85.5 is what a scorer reports when two scripts share nothing but their spaces. **So the
necessary-not-sufficient rule is no longer an argument in a comment — it is a measurement of this
exact file**, and `ur-IN` is a language this artifact cannot serve however many Arabic tokens it
holds. The rule's other direction settles one language with no audio at all: `tokens.txt` contains
**zero** Meetei Mayek characters, so Manipuri in its own script is not something this artifact can
produce. (It carries 32 Ol Chiki, so Santali is not excluded the same way — merely unmeasured.)

#### THREE LANGUAGES SCORE WHAT HINDI SCORES, WHICH IS A DECISION SOMEBODY HAS TO TAKE

Gujarati 24.9, Kannada 24.7 and Punjabi 24.9 against Hindi's 24.2 (24.4 on this run) — same corpus,
same handset, same n, inside a point of each other. The argument that admitted Hindi to `languages`
is that the rung sits BELOW Android's own pack and therefore only fires where the alternative is no
dictation at all. On this handset Android has **no** pack for Gujarati, Kannada or Punjabi
(DICTATION-LANGUAGE-PACK-MEASUREMENT.md: thirty listed, exactly `hi-IN` and `en-IN` ours), so for
those three the model is the only offline option there has ever been and the argument applies more
strongly than it does to Hindi. **This lane measured and did not decide**: a tag in `languages` turns
a settings row green and reorders the dictation ladder, which is a surface change. The numbers are in
`accuracy` so the decision is one line away.

#### THE TRANSCRIPTS. Verbatim, beside what was actually said

Everything the probe printed for the nine new languages, unedited. `hi` and `or` are in the section
above; `ur` is in its own block above.

**Bengali (`bn-IN`)** — CER 10.6, WER 43.2

- `dwlang-bn_0.wav` — CER 16.9, WER 54.5
  - REF: বাঁধের উপর 100 ফুট চওড়া একটি জায়গা থেকে জল ছিটকে বেরোচ্ছে
  - HYP: বাদের উপর কশফূব চওড়া একটি জায়গা থেকে জলছিটকে বেলোচ্ছে
- `dwlang-bn_1.wav` — CER 6.1, WER 33.3
  - REF: যদিও অধিকাংশ চিহ্ন শুধুমাত্র কাতালানে নির্দেশ করে কারণ এটি আইন এর মাধ্যমে প্রথম দাপ্তরিক ভাষা হিসাবে প্রতিষ্ঠিত হয়
  - HYP: যদিও অধিকাংশ চিন্হ শুনুমাত্র কাতালানএ নির্দেশ করে কারণ এটি আইনের মাধ্যমে প্রথম দাপ্তরিক ভাষা হিসেবে প্রতিষ্ঠিত হয়
- `dwlang-bn_2.wav` — CER 8.9, WER 41.7
  - REF: স্থানীয় কার্যক্রমের উদাহরণগুলোর মধ্যে শিকার করা মাছ ধরা ফটোগ্রাফি করা পাখি দেখা এবং পার্কগুলোতে ঘোরা ও বাস্তুতন্ত্র সম্পর্কিত তথ্য নিয়ে পড়াশোনা করা অন্তর্ভুক্ত রয়েছে
  - HYP: স্থানীয় কার্যক্রমের উদাহরণগুলোর মধ্য শ্ীকার করা মালধারা ফটোগ্রাফী করা পাখি দেখা এবং পাকগুলোতে ঘোডা ও বাস্ততন্ত্র সম্পর্কিত তথ্য নিয়ে পরাশনা করা অন্দর্ভুক্ত রয়েছে

**Gujarati (`gu-IN`)** — CER 6.1, WER 24.9

- `dwlang-gu_0.wav` — CER 6.0, WER 25.0
  - REF: તીબેટનો બૌદ્ધવાદ બુદ્ધનાં ઉપદેશો ઉપર આધારિત છે પરંતુ તેનો પ્રેમના મહાયાન પંથ દ્વારા અને ભારતીય યોગની ઘણી બધી પ્રયુક્તિઓ દ્વારા વિસ્તાર કરવામાં આવ્યો છે
  - HYP: તિબેટનો બૌધવાદ બૌધના ઉપદેશો પર આધારિત છે પરંતુ તેનો પ્રેમના મહાયાનપંથ દ્વારા અને ભારતીય યોગની ઘણી બધી પ્રયુક્તિઓ દ્વારા વિસ્તાર કરવામાં આવ્યો છે
- `dwlang-gu_1.wav` — CER 3.4, WER 6.2
  - REF: તમે પ્રવાહમાંથી બહાર આવો પછી પાછા તરવું એ સામાન્ય રીતે હોય તેના કરતાં વધારે મુશ્કેલ નથી
  - HYP: તમે પ્રવાહમાંથી બહાર આવો પછી પાછા તરવું એ સામારી રીતે હોય તેના કરતાં વધારે મુશ્કેલ નથી
- `dwlang-gu_2.wav` — CER 8.8, WER 43.5
  - REF: પુરુષોના સીટીંગ સુપર જીમાં મેક્સિકોના અર્લી વેલાસ્ક્યુઝે પંદરમાં ક્રમે પૂર્ણ કર્યું હતું પુરુષોના સ્ટેન્ડીંગ સુપર-જીમાં ન્યૂઝીલેન્ડના આદમ હોલે નવમા ક્રમે પૂર્ણ કર્યું હતું
  - HYP: પુરુષોના સિટિંગ સુપર જીમાં મેક્સિકોના આલી વેલા સ્ક્યુઝએ ંમાં ક્રમે પૂર્ણ કર્યું હતું પુરુષોના સ્ટેન્ડિંગ સુપર જીમાં ન્યુઝિલેન્ડના આદમ હોલે નવમાં ક્રમે પૂર્ણ કર્યું હતું

**Kannada (`kn-IN`)** — CER 6.2, WER 24.7

- `dwlang-kn_0.wav` — CER 9.6, WER 38.5
  - REF: ಸರಳ ಸುವಾಸನೆ ಭಕ್ಷ್ಯಗಳನ್ನು ಒದಗಿಸುವ ಜಾವಾನೀಸ್ ಪಾಕಪದ್ಧತಿಯನ್ನು ಈಗ ದ್ವೀಪಸಮೂಹದಾದ್ಯಂತ ವ್ಯಾಪಕವಾಗಿ ಬಳಸಲಾಗುತ್ತದೆ. ಜಾವಾನೀಸ್‌ನ ನೆಚ್ಚಿನ ಮಸಾಲೆಗಳು ಕಡಲೆಕಾಯಿ ಮೆಣಸಿನಕಾಯಿ ಸಕ್ಕರೆ ಅದರಲ್ಲೂ ವಿಶೇಷವಾಗಿ ಜಾವಾ ತೆಂಗಿನಕಾಯಿ ಸಕ್ಕರೆ ಮತ್ತು ವಿವಿಧ ಸುಗಂಧಿತ ಮಸಾಲೆಗಳಿಂದ ಕೂಡಿರುತ್ತದೆ
  - HYP: ಸರಳ ಸುವಾಸನಯ ಭಕ್ಷಗಳನ್ನು ಅದಗಿಸುವ ಜಾವಾನೀಸ್ ಪಾಕಪದ್ಧತಿಯನ್ನು ಈಗ ದ್ವೀಪ ಸಮೂಹ ದಾದ್ಯಂತ ವ್ಯಾಪಕವಾಗಿ ಬಳಸಲಾಗುತ್ತದೆ ಜಾವಾನೀಸ್ ನ ನೆಚ್ಚಿನ ಮಸಾಲೆಗಳು ಕಡಲಯಕಾಯಿ ಮೆಣಸಿನಕಾಯಿ ಸಕ್ಕರೆ ಬ್ರಯಾಕೆಟ್ನಲ್ಲಿ ಅದರಲ್ಲೂ ವಿಶೇಷವಾಗಿ ಜಾವಾ ತೆಂಗಿನಕಾಯಿ ಸಕ್ಕರೆ ಮತ್ತು ವಿವಿಧ ಸುಗಂಧಿತ ಮಸಾಲೆಗಳಿಂದ ಕೂಡಿರುತ್ತದೆ
- `dwlang-kn_1.wav` — CER 2.5, WER 12.5
  - REF: ಸ್ಥಳಕ್ಕೆ ಸಲ್ಲಬೇಕಾದ ಎಲ್ಲ ಘನತೆ ಸಮಗ್ರತೆ ಮತ್ತು ಗೌರವವನ್ನು ದಯವಿಟ್ಟು ನೀಡಿ. ಹೋಲೋಕಾಸ್ಟ್ ಅಥವಾ  ನಾಜಿಗಳ ಬಗ್ಗೆ ಯಾವುದೇ ಜೋಕ್‌ಗಳನ್ನು ಮಾಡಬೇಡಿ
  - HYP: ಸ್ಥಳಕ್ಕೆ ಸಲ್ಲಬೇಕಾದ ಎಲ್ಲಾ ಘನತೆ ಸಮಗ್ರತೆ ಮತ್ತು ಗೌರವವನ್ನು ದಯವಿಟ್ಟು ನೀಡಿ ಹೋಲೋಕಾಸ್ಟ್ ಅಥವಾ ನಾಜಿಗಳ ಬಗ್ಗೆ ಯಾವುದೇ ಜೋಗ್ಗಳನ್ನು ಮಾಡಬೇಡಿ
- `dwlang-kn_2.wav` — CER 6.6, WER 23.1
  - REF: ದೇಶದ ಪ್ರಸಿದ್ಧ ಗಾಯಕರು ಭಜನೆ ಅಥವಾ ಭಕ್ತಿಗೀತೆಗಳನ್ನು ಹಾಡಿ ಶ್ರೀ ಶ್ಯಾಮ್ ಅವರ ಪಾದ ಕಮಲಗಳಿಗೆ ಅರ್ಪಿಸಿದರು
  - HYP: ದೇಶದ ಪ್ರಸಿದ್ಧ ಗಾಯಕರು ಬಜನೆ ಅಥವಾ ಭಕ್ತಿಗೀತೆಗಳನ್ನು ಹಾಡಿ ಶ್ರೀಶಾ ಅವರ ಪಾದ ಕಮಲಗಳಿಗೆ ಅರ್ಪಿಸಿದರು

**Malayalam (`ml-IN`)** — CER 9.5, WER 54.1

- `dwlang-ml_0.wav` — CER 6.3, WER 52.9
  - REF: പോയിൻ്റ് മരിയനും ഫെയർമോണ്ടും തമ്മിലുള്ള ദൂരം ഒറ്റപ്പെട്ട ബാക്ക്‌വുഡ് ഭൂപ്രദേശങ്ങളിലൂടെ കടന്നുപോകുന്ന ബഫല്ലോ-പിറ്റ്സ്ബർഗ് ഹൈവേയിലെ ഏറ്റവും വെല്ലുവിളി നിറഞ്ഞ ഡ്രൈവിംഗ് സാഹചര്യങ്ങൾ സൃഷ്ടിക്കുന്നു
  - HYP: പോയിന്റ് മരിയനും ഫെയർമോണഡും തമ്മിലുള്ള ദൂരം ഒറ്റപ്പെട്ട ബാക്ക്വുഡ് ബു പ്രദേശങ്ങളിലൂടെ കടന്നുപോകുന്ന ബഫലോ പിറ്റ്സ്ബർഗ് ഹൈവയിലെ ഏറ്റവും വെല്ലുവിളി നിറഞ്ഞ ഡ്രൈവിംഗ് സാഹജര്യങ്ങൾ സൃഷ്ടിക്കുന്നു
- `dwlang-ml_1.wav` — CER 13.9, WER 64.3
  - REF: ഈ തത്വങ്ങള്‍ അഭിപ്രായപ്പെടുന്നത് ചില നിശ്ചിത ആവശ്യങ്ങള്‍ കൂടാതെ/അല്ലെങ്കില്‍ സ്വപങ്ങളുള്ളവരാണ്‌ അത് ആന്തരികവല്‍ക്കരിക്കുകയും പ്രായപൂര്‍ത്തിയിലേയ്ക്ക് എത്തിച്ചേരുകയും ചെയ്യൂ എന്നാണ്‌
  - HYP: ഈ തത്വങ്ങൾ അഭിപ്രായപ്പെടുന്നത് ചില നിശ്ചിത ആവശ്യങ്ങൾ കൂടാതെ അല്ലെങ്കിൽ സ്വജ്ഞങ്ങളുള്ളവരാണ് അത് ആന്തരീക വൽക്കരിക്കുകയും പ്രായപൂർത്തിയിലേക്ക് എത്തിച്ചേരുകയും ചെയ്യൂ എന്നാണ്
- `dwlang-ml_2.wav` — CER 8.4, WER 45.0
  - REF: മെട്രിക് സമ്പ്രദായത്തിൻ്റെ ഉപയോഗം ഏകാധിപത്യത്തിൽ നിന്ന് പ്രജാധിപത്യത്തിലേക്കുള്ള മാറ്റം ദേശീയത രാജ്യം ഭരണാധികാരിക്കല്ല മറിച്ച് ജനങ്ങൾക്ക് അവകാശപ്പെട്ടതാണ് എന്നതുപോലെയുള്ള സാമൂഹികവും രാഷ്ട്രീയവുമായ ധാരാളം പ്രഭാവങ്ങൾ അവിടെ ഉണ്ട്
  - HYP: മെട്രിക് സമ്പ്ൃത്തായത്തിന്റെ ഉപയോഗം ഏകാതിപത്യത്തിൽ നിന്ന് പ്രചാതിപത്യത്തിലക്കുള്ള മാറ്റം ദേശീയത രാജ്യം ഭരണാധികാരിക്കല്ല മറിച്ച് ജനങ്ങൾക്ക് അവകാശപ്പെട്ടതാണെന്ന് എന്നത്പോലെയുള്ള സാമൂഹികകവും രാഷ്ട്രീയോമായ ധാരാളം പ്രഭാവങ്ങൾ അവിടെയുണ്ട്

**Nepali (`ne-IN`)** — CER 15.3, WER 45.9

- `dwlang-ne_0.wav` — CER 21.2, WER 33.3
  - REF: उनले wifi ढोकाको घण्टी बनाएको भने
  - HYP: उनले वाइफाइ ढोकाको घण्टी बनाएका भने
- `dwlang-ne_1.wav` — CER 16.6, WER 54.5
  - REF: पूर्वमा मोजाम्बिकको सिमानामा पर्ने क्रुगर राष्ट्रिय निकुञ्ज knp दक्षिण अफ्रिकाको उत्तर पूर्वमा अवस्थित छ। उत्तरमा जिम्बाब्वे र दक्षिणी सिमाना क्रोकोडायल नदी छ।
  - HYP: पूर्वमा मोजिबिमको सिनममा पर्ने कुबेर राष्ट्र निकुन्ज दक्षिर अफ्रिकाको उत्तर पूर्वमा आवस्थित छ उत्तरमा जिम्बावे र दक्षिणी सिमानामा क्रोकोडायल नदिछ
- `dwlang-ne_2.wav` — CER 8.2, WER 50.0
  - REF: त्यसले मलाई अर्थ दिदैँन जस्तो देखिन्थ्यो यो पक्कै राम्रो थिएन
  - HYP: त्यसले मलाई अर्थ दिदैन जस्तु देखिन् थ्यो यो पक्कै राहमरो थिएन

**Punjabi (`pa-IN`)** — CER 10.7, WER 24.9

- `dwlang-pa_0.wav` — CER 10.7, WER 21.4
  - REF: ਉਪ-ਸੱਭਿਆਚਾਰ ਦੇ ਮੈਂਬਰ ਅਕਸਰ ਸ਼ੈਲੀ ਦੀ ਇੱਕ ਖ਼ਾਸ ਅਤੇ ਸੰਕੇਤਕ ਵਰਤੋਂ ਰਾਹੀਂ ਆਪਣੀ ਸਦੱਸਤਾ ਦਾ ਸੰਕੇਤ ਦਿੰਦੇ ਹਨ ਜਿਸ ਵਿੱਚ ਫੈਸ਼ਨ ਸ਼ਿਸ਼ਟਤਾ ਅਤੇ ਗੁਪਤ ਭਾਸ਼ਾ ਸ਼ਾਮਲ ਹੁੰਦੀ ਹੈ।
  - HYP: ਉ ਦੇ ਮੈਂਬਰ ਅਕਸਰ ਸ਼ੈਲੀ ਦੀ ਇੱਕ ਖਾਸ ਅਤੇ ਸੰਕੇਤਕ ਵਰਤੋਂ ਰਾਹੀਂ ਆਪਣੀ ਸਦਸਤਾ ਦਾ ਸੰਕੇ ਦਿੰਦੇ ਹਨ ਜਿਸ ਵਚ ਫੈਸ਼ਨ ਸਿਸ਼ਟਤਾਂ ਅਤੇ ਗੁਪਤ ਭਾਸ਼ਾ ਸ਼ਾਮਲ ਹੁੰਦੀ ਹੈ
- `dwlang-pa_1.wav` — CER 7.3, WER 22.6
  - REF: ਖੰਭਾਂ ਦੀ ਸੰਰਚਨਾ ਤੋਂ ਪਤਾ ਲਗਦਾ ਹੈ ਕਿ ਉਨ੍ਹਾਂ ਦੀ ਵਰਤੋਂ ਫਲਾਈਟ ਵਿੱਚ ਨਹੀਂ ਕੀਤੀ ਗਈ ਸੀ ਬਲਕਿ ਤਾਪਮਾਨ ਨਿਯਮ ਜਾਂ ਪ੍ਰਦਰਸ਼ਨ ਲਈ ਕੀਤੀ ਗਈ ਸੀ। ਸ਼ੋਧ ਕਰਤਾ ਨੇ ਸੁਝਾਅ ਦਿੱਤਾ ਹੈ ਕਿ ਭਾਵੇਂ ਇਹ ਇਕ ਯੁਵਾ ਡਾਇਨਾਸੂਰ ਦੀ ਪੂਛ ਹੋਵੇ ਨਮੂਨਾ ਬਾਲਗ ਪੰਛੀ ਦਾ ਖੰਭ ਦਿਖਾਉਂਦਾ ਹੈ ਅਤੇ ਬੋਟ ਦਾ ਹੇਠਲਾ ਨਹੀਂ।
  - HYP: ਖੰਬਾ ਦੀ ਸਰਚਨਾ ਤੋਂ ਪਤਾ ਲੱਗਦਾ ਹੈ ਕਿ ਉਨਾਂ ਦੀ ਵਰਤੋਂ ਫਲਾਈਟ ਵਿੱਚ ਨਹੀਂ ਕੀਤੀ ਗਈ ਸੀ ਬਲਕਿ ਤਾਪਮਾਨ ਨਿਯਮ ਜਾਂ ਪ੍ਰਦਰਸ਼ਨ ਲਈ ਕੀਤੀ ਗਈ ਸੀ ਸ਼ੋਧ ਕਰਤਾਂ ਨੇ ਸੁਜਾ ਦਿੱਤਾ ਕਿ ਭਾਵੇਂ ਇਹ ਇੱਕ ਯੂਵਾ ਡਾਇਨਾਸੁਰ ਦੀ ਪੂਛ ਹੋਵੇ ਨਮੂਨਾ ਬਾਲਗ ੰਚੀਦਾ ਖੰਭ ਦਿਖਾਉਂਦਾ ਹੈ ਅਤੇ ਬੋਟ ਦਾ ਹੇਠਲਾ ਨਹੀਂ
- `dwlang-pa_2.wav` — CER 14.1, WER 30.8
  - REF: ਰੋਗੀ ਨਾਈਜੀਰੀਆ ਗਿਆ ਸੀ ਜਿੱਥੇ ਈਬੋਲਾ ਵਾਇਰਸ ਦੇ ਕੁਝ ਮਾਮਲੇ ਸਾਹਮਣੇ ਆਏ ਹਨ।
  - HYP: ਰੋਗੀ ਨਜ਼ੀਡੀਆਗਿਆ ਸੀ ਜਿੱਥੇ ਇਬੋਲਾ ਵਾਇਰਸ ਦੇ ਕੁਝ ਮਾਮਲੇ ਸਮਨੇ ਆਏ ਹਨ

**Tamil (`ta-IN`)** — CER 8.2, WER 52.0

- `dwlang-ta_0.wav` — CER 7.1, WER 52.2
  - REF: இந்த விதிகள் திருத்தப்படுவதற்கு முன்னர் அனைத்து மாநிலங்களின் ஒருமித்த ஒப்புதலையும் கோரியது மற்றும் பெரும்பாலும் அவர்களின் பிரதிநிதிகள் அங்கு இல்லாததால் மாநில அரசு மத்திய அரசை மிகவும் சாதாரணமாக எடுத்துக் கொண்டது
  - HYP: இந்த விதிகள் திருத்தப்படுகதற்கு முன்னர் அனைத்து் மனிலங்களின் ஒருமித்த ஒப்புதலைக் கோறியது மற்றும் பெரும்பாலும் அவர்களின் பிரதிநேதிகள் அங்கு இலாததால் மாதில அரசு மத்தி அரசை மிகவும் சாதாரமாக எடுத்துக்கொண்டது
- `dwlang-ta_1.wav` — CER 4.6, WER 33.3
  - REF: "கருத்து கேட்டபோது "விசாரணையின் போது மைக் அதிகளவு பேசுகிறார்.. நான் விசாரணைக்காக தயாராகி கொண்டிருந்ததால் அவர் கூறியதை நான் கேட்கவில்லை" என்று மில்லர் கூறினார். 
  - HYP: கருத்து கேட்டபோது விசாரணையின்போது மை கதிகலவு பேசுகிறார் நான் விசாரனைக்காக தயாராகி கொண்டிருந்ததால் அவர் கூறியதை நான் கேட்கவிள்லை என்று மில்லர் கூறினார்
- `dwlang-ta_2.wav` — CER 12.9, WER 70.6
  - REF: பூச்சிகளே காற்றில் பறந்த முதல் விலங்குகள் அவற்றின் பறக்கும் திறன் பகைவர்களிடமிருந்து எளிதாகத் தப்பவும் உணவு மற்றும் இணைகளை திறமையாகக் கண்டறியவும் உதவுகிறது
  - HYP: பூச்சுகளே காற்றில் பரந்த முதல் விலங்குகள் அவற்றின் பரக்கும் திரன் பகைவர்களிடம்ிருந்து எழிதாக தப்புகும் மஉணபு மற்றும் இனைகளை் திரைமையாக கண்டரியவும் மஉதவுகிறது

**Telugu (`te-IN`)** — CER 18.0, WER 54.6

- `dwlang-te_0.wav` — CER 6.5, WER 47.4
  - REF: చాలా సందర్భాల్లో విదేశాల్లో ఒక గ్యాప్ ఇయర్ కోర్సులో చేరడం వల్ల మీ స్వంత దేశంలో తిరిగి ఉన్నత విద్యకు వెళ్లే మీ అవకాశాలను మెరుగుపరుచుకోవచ్చు
  - HYP: చాలా సందర్భాలో విదేశాలలో ఒక గ్యాబ్ ఇయర్ కోర్సు లో చేరడంవల్ల మీ స్వంత దేశంలో తిరిగి ఉన్నత విద్యకు వెళ్ళే మీ అవకాశాలను మెరుగు పరచుకోవచ్చు
- `dwlang-te_1.wav` — CER 11.6, WER 71.4
  - REF: అది నాకు అర్థం కాలేదు ఖచ్చితంగా న్యాయం కాదు
  - HYP: అదినాకఅర్థంకాలేదు పచ్చితంగా న్యాయం కాదు
- `dwlang-te_2.wav` — CER 36.0, WER 45.0
  - REF: point marion మరియు fairmont మధ్య సాగిన buffalo-pittsburgh రహదారిలో చాలా సవాలుగా ఉండే డ్రైవింగ్ పరిస్థితులు ఉంటాయి ఇది నిర్మాణుష్యమైన backwoods భూభాగం గుండా వెళుతుంది
  - HYP: పాయింట్ మిరియన్ మరియు ఫేర్మాంట్ మధ్య సాగిన బఫెల్లో పిట్స్బర్గ్ గ్రహదారిలో చాలా సవాలుగా ఉండే డ్రైవింగ్ పరిస్థితులు ఉంటాయి ఇది నిర్మాణుష్యమైన బ్యాక్ఫుడ్స్ బూబాగంగుండా వెళుతుంది

#### AND THE SAME ELEVEN THROUGH INDICCONFORMER-600M, WHICH BEATS IT ON EVERY ONE

`ASR-RUNTIME-MEASUREMENT.md` justifies preferring IndicConformer on **two** languages. Run on the
same eleven — same audio files, same references, same normaliser, language-sliced heads built from
`ai4bharat/indic-conformer-600m-multilingual` — it is better on **all eleven, on both metrics**. This
is a desktop measurement (fp32, greedy CTC, `sherpa_onnx` 1.13.5, the version vendored in the APK);
the 600M cannot load on the handset, which the document records elsewhere.

| language | Omnilingual int8 300M, on the handset | IndicConformer 600M fp32, desktop | WER falls by |
|---|---|---|---|
| Hindi | CER 7.5 / **WER 24.4** | CER 6.9 / **WER 20.9** | 1.2× |
| Odia | CER 13.8 / **WER 51.4** | CER 4.8 / **WER 16.7** | 3.1× |
| Bengali | CER 10.6 / **WER 43.2** | CER 4.8 / **WER 14.9** | 2.9× |
| Gujarati | CER 6.1 / **WER 24.9** | CER 2.8 / **WER 18.7** | 1.3× |
| Kannada | CER 6.2 / **WER 24.7** | CER 4.1 / **WER 17.5** | 1.4× |
| Malayalam | CER 9.5 / **WER 54.1** | CER 5.8 / **WER 35.8** | 1.5× |
| Nepali | CER 15.3 / **WER 45.9** | CER 14.5 / **WER 33.9** | 1.4× |
| Punjabi | CER 10.7 / **WER 24.9** | CER 2.3 / **WER 10.5** | 2.4× |
| Tamil | CER 8.2 / **WER 52.0** | CER 2.3 / **WER 12.4** | 4.2× |
| Telugu | CER 18.0 / **WER 54.6** | CER 11.4 / **WER 11.7** | 4.7× |
| Urdu | CER 85.5 / **WER 100.0** | CER 14.2 / **WER 27.4** | 3.6× |

Hindi barely moves (24.4 → 20.9) and Tamil, Bengali and Punjabi collapse (52.0 → 12.4, 43.2 → 14.9,
24.9 → 10.5). **The two-language sample understated the case rather than overstating it.** Urdu is the
sharpest row: 100 → 27.4, because IndicConformer's `ur` head writes Perso-Arabic and the Omnilingual
model will not.

#### THE LANGUAGE HEAD ACTUALLY SELECTS, AND HERE IS THE CONTROL THAT SHOWS IT

`DwAsrModelHead` claims selecting a language is choosing which file to open. Tested by decoding one
language's audio through **another** language's head — same encoder, same 4,030,572-byte graph shape,
different 257-row slice:

| audio → head | CER | WER | what came out |
|---|---|---|---|
| Tamil → Tamil | 2.3 | 12.4 | Tamil |
| Tamil → Telugu | 89.5 | 105.7 | Telugu script, Tamil words |
| Odia → Odia | 4.8 | 16.7 | Odia |
| Odia → Bengali | 87.3 | 105.1 | Bengali script |
| Hindi → Hindi | 6.9 | 20.9 | Devanagari |
| Hindi → Urdu | 84.6 | 106.3 | **valid Urdu of the same sentence** |
| Odia → all-22, unmasked | 69.4 | 95.3 | six scripts at once |
| Tamil → all-22, unmasked | 25.2 | 70.4 | mixed |
| Malayalam → all-22, unmasked | 48.4 | 96.8 | mixed |

**The head is a script-and-vocabulary selector over one shared acoustic model, and the Hindi→Urdu row
proves it more cleanly than the mismatched ones do.** Hindi and Urdu are the same spoken language in
two alphabets, so the Urdu head fed Hindi audio returns *correct Urdu* — WER 106 against a Devanagari
reference and perfectly readable to an Urdu speaker: `آرمڈ وارسس نے کہا میں نے اپنی بہن اور ان کی دوست کو کھو دیا …`. The mask is
therefore not cosmetic and not optional; unmasked, one Odia clip came back as
`హाାତी ও ஜिరాಾഫફ भଳି पશુମାନানଙ୍କର कار এবং भଲ দেیکेखاାଯାଉଥିବା মानक सरରঞ্जজামରୁ ن न…`

#### WHICH SCRIPT EACH OF INDICCONFORMER'S 22 HEADS WILL WRITE IN, off `vocab.json`

Read off the 256 tokens of each language's own block, not off the model card. **Three of these are
not what a reader would predict, and each is an Urdu-shaped trap waiting for whoever pins this
model:** Sindhi is **Devanagari** (not Perso-Arabic, which is how Sindhi is written in Pakistan and
commonly in India), Manipuri is **Meetei Mayek** (not the Bengali script it is most often printed in),
and Kashmiri is **Perso-Arabic** (which is right, and is the one of the three a reader might doubt).

| tag | language | block | columns | script of its 256 tokens | in this app's nineteen? |
|---|---|---|---|---|---|
| `as` | Assamese | 0 | 0–255 | Bengali-Assamese | yes |
| `bn` | Bengali | 1 | 256–511 | Bengali-Assamese | yes |
| `brx` | Bodo | 2 | 512–767 | Devanagari | **no — not offered** |
| `doi` | Dogri | 3 | 768–1023 | Devanagari | **no — not offered** |
| `kok` | Konkani | 4 | 1024–1279 | Devanagari | yes |
| `gu` | Gujarati | 5 | 1280–1535 | Gujarati | yes |
| `hi` | Hindi | 6 | 1536–1791 | Devanagari | yes |
| `kn` | Kannada | 7 | 1792–2047 | Kannada | yes |
| `ks` | Kashmiri | 8 | 2048–2303 | Perso-Arabic | yes |
| `mai` | Maithili | 9 | 2304–2559 | Devanagari | **no — not offered** |
| `ml` | Malayalam | 10 | 2560–2815 | Malayalam | yes |
| `mr` | Marathi | 11 | 2816–3071 | Devanagari | yes |
| `mni` | Manipuri | 12 | 3072–3327 | Meetei Mayek | yes |
| `ne` | Nepali | 13 | 3328–3583 | Devanagari | yes |
| `or` | Odia | 14 | 3584–3839 | Odia | yes |
| `pa` | Punjabi | 15 | 3840–4095 | Gurmukhi | yes |
| `sa` | Sanskrit | 16 | 4096–4351 | Devanagari | yes |
| `sat` | Santali | 17 | 4352–4607 | Ol Chiki | **no — not offered** |
| `sd` | Sindhi | 18 | 4608–4863 | Devanagari | yes |
| `ta` | Tamil | 19 | 4864–5119 | Tamil | yes |
| `te` | Telugu | 20 | 5120–5375 | Telugu | yes |
| `ur` | Urdu | 21 | 5376–5631 | Perso-Arabic | yes |

`en-IN` is in the app's nineteen and is **not** one of IndicConformer's 22, so a per-language
IndicConformer would have no head for it and `DwAsrModel.headFor("en-IN")` would return null — a
refusal, which is the designed behaviour. Bodo, Dogri, Maithili and Santali are the reverse: served by
the model, absent from the app's dropdown, which `DwDictation.kt` explains as *"no recogniser on any
shipping Android"* — true of Android's own packs and no longer the whole story once this app carries a
model of its own.

#### STILL UNMEASURED AFTER THIS, IN THAT WORD

- **Eight of the nineteen have had no audio through any model on any machine:** Assamese, English
  (India), Marathi, Sanskrit, Konkani, Manipuri, Kashmiri, Sindhi. Three of the eight — Assamese,
  Marathi, Sindhi — DO have a FLEURS split and are reachable: the datasets-server refuses those three
  configs (`Scan size limit exceeded`, their validation row group is larger than the server's 300 MB
  read cap) so the parquet has to be fetched whole, which is exactly how Malayalam and Bengali above
  were obtained and takes about a minute each. **Those three are the cheapest measurements left in
  this document.** Four — Sanskrit, Konkani, Manipuri, Kashmiri — have no FLEURS split and need
  another corpus. English (India) has none either: FLEURS English is `en_us`, and `en-US` audio would
  measure the wrong tag, which `dwTagCovers` is explicit about.
- **Courtyard audio: still nothing.** Every figure on this page is FLEURS studio read speech by
  professional speakers and is therefore a ceiling. Eleven languages of ceiling is still a ceiling.
- **n = 3 per language.** A demonstration, as the section above already says of its own two.
- **`armeabi-v7a`**: unmeasured, as everywhere else.
- IndicConformer on the handset for any of these eleven: the 600M cannot load there, so the
  comparison table's right-hand column is a desktop reading throughout.

## The four cells this file named, filled

All from the two runs. Where two figures are given they are run 1 and run 2.

| | |
|---|---|
| **On-disk size** | **365,438,543 bytes**, measured off the files in `filesDir` on the phone (365,352,120 + 86,423). Not memory-mapped by any arrangement of ours: the resident-set figures below are what actually happened |
| **Peak RSS** | **1,242,996,736 / 1,241,206,784 bytes** — `VmHWM` from `/proc/self/status`, the kernel's own high-water mark, so it does not depend on sampling at the right instant |
| | **1,240,014,848 bytes** — the same peak read the way this lane was asked to read it, `adb shell dumpsys meminfo` sampled every 6 s from the host during run 2 (`TOTAL Rss`). **Two independent methods, 0.1% apart** |
| | of which **Native Heap Rss peaked at 972,180 kB** while `dalvikPss` never exceeded 6,833 kB — see below, because that ratio settles an argument this document has been making without a number |
| **Time to first token** | **there are no incremental tokens to be first**, and saying otherwise would be inventing a measurement. This is a non-streaming CTC model: audio goes in whole and one result comes out. What can be measured is the whole latency, and it is **3,263 / 3,290 ms** to construct the recogniser (the model load, paid once) and then **11,289–27,165 ms** per utterance |
| **Real-time factor** | **1.119 – 1.272 across six utterances and both runs** — and **this band did not reproduce; see the correction below.** What survives every reading is the conclusion: the M32 decodes **slower than the audio plays**, at `numThreads = 2` |
| **Survives backgrounding** | **the process survived Home and decoded the same audio again to the same text**, in both runs. **But `oom_score_adj` stayed 0 across the transition**, because a process under instrumentation is held up by the runner — so this is evidence about the *activity* leaving the foreground and **not** evidence about the low-memory killer. The question this row was really asking is still **unmeasured**, and it needs a build where the app itself loads the model |

### CORRECTION, 2026-08-12 LATE EVENING — THE TIMINGS ARE NOT A PROPERTY OF THE HANDSET

**The transcripts reproduced exactly and the timings did not.** An adversarial re-run of
`DwAsrEngineProbeTest` on the same SM-M325F, the same six WAVs, the same pinned model and the same
`numThreads = 2` — driven straight through `am instrument` rather than Gradle — returned **all six
transcripts byte-identical** to the ones printed above, and `OK (1 test)` both times. The decode is
deterministic. **The clock is not.**

| | run 1 + 2 above | re-run 1 | re-run 2 |
|---|---|---|---|
| real-time factor | 1.119 – 1.272 | **1.901 – 2.967** | **1.078 – 2.156** |
| recogniser construction | 3,263 / 3,290 ms | **3,664 ms** | **8,510 ms** |
| peak RSS (`VmHWM`) | 1,242,996,736 / 1,241,206,784 | **1,258,713,088** | **1,165,746,176** |

**Across twelve utterances the real-time factor spans 1.078 – 2.967, not 1.119 – 1.272** — the
original band is the *best* case, understated by up to **2.3×** at the top. Peak RSS is the sturdy
figure of the three: four readings inside 1.17 – 1.26 GB, so every conclusion drawn from it below
stands. The timings are not, and **the row above must not be read as a property of the M32.**

**WHAT MOVED, AND THE HONEST ANSWER IS THAT IT IS STILL UNMEASURED.** The most likely cause is the
one this document already lists as **unmeasured** in `ASR-RUNTIME-MEASUREMENT.md` §6 — *thermal
behaviour under sustained decode*. Re-run 2 followed re-run 1 immediately on an already-warm handset
and paid 8,510 ms to construct a recogniser that had taken 3,263 ms cold, off files already in
`filesDir`; `cpu0` was observed at 774 MHz against an `cpuinfo_max_freq` of 1,800 MHz. Host load is
**not** the explanation and should not be offered as one: the decode runs on the phone's silicon, not
the developer machine's. Nothing here isolates thermal throttling from governor state or from another
process on the handset, so **which of them it is remains unmeasured, in that word.**

**WHY THIS MATTERS MORE THAN A TIDY-UP.** The next lane to write "a five-minute recording takes about
six minutes to transcribe" will size that sentence off this row. At 2.967 the same recording takes
close to **fifteen** minutes. A designer told six and made to wait fifteen in a courtyard is the
failure this correction exists to prevent.

### The RSS figure is the finding, and it is bad news

**1.24 GB of resident set on a handset that reported 1,533,587,456 bytes free.** Hold that against
this document's own arithmetic: `dwPlanFits` keeps `DW_MODEL_FREE_RAM_MARGIN_BYTES` = 512 MiB, so a
plan with this peak RSS needs **1.78 GB** free and the fleet's phone had **1.53 GB**.

**The app's own gate would have refused this model on the handset it just ran on.** It only ran
because the probe is a probe and goes round the gate deliberately. That is not an argument for moving
the margin — it is the first real datum the margin has ever been held against, and it says the 300M
int8 model is at the edge of this handset rather than inside it. A phone with a browser and a camera
preview already open would be a different reading, and that reading is **unmeasured**.

### And it settles the `getMemoryClass()` argument with a number

This file has argued at length that gating on `ActivityManager.getMemoryClass()` would be measuring
the wrong thing, because an ONNX model allocates **natively**, outside the Dalvik heap and outside its
accounting. Until now that was an argument with a ratio attached. Measured, on this run:

| | |
|---|---|
| `getMemoryClass()` on this handset | 256 MB |
| peak **native** PSS while decoding | **972,180 kB — 3.8× the entire heap cap** |
| peak **dalvik** PSS while decoding | 6,833 kB — **0.7% of the native figure** |

A gate reading the heap cap would have seen a process using 7 MB of the 256 MB it is allowed, at the
moment that process was holding **1.24 GB**. The refusal was right, and it is now right for a reason
somebody measured rather than for a reason somebody argued.

## THE FINDING THAT INVALIDATES A DESIGN, AND IT IS THE MOST IMPORTANT THING HERE

`docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md` §8 step 2 instructs the next lane to load the engine's `.so`
files **out of `filesDir` after downloading them**. **That cannot be done with this binding, and the
reason is a property of Android rather than of the design.**

Every entry class in `com.k2fsa.sherpa.onnx` carries a static initialiser calling
`System.loadLibrary("sherpa-onnx-jni")`. `System.loadLibrary` resolves through
`ClassLoader.findLibrary`, which searches only the directories the classloader was built with.
Printed by the probe on the handset rather than reasoned about:

```
nativeLibraryDirectories=[…/com.designprototype.workshop.test-…/lib/arm64,
                          …/com.designprototype.workshop-…/lib/arm64,
                          …/com.designprototype.workshop.test-…/base.apk!/lib/arm64-v8a,
                          …/com.designprototype.workshop-…/base.apk!/lib/arm64-v8a,
                          /system/lib64, /system/system_ext/lib64]
filesDir = /data/user/0/com.designprototype.workshop/files
```

**`filesDir` is not in that list and cannot be put in it.** `System.load(absolutePath)` does load a
`.so` from anywhere — but it records it under its *path*, so the binding's later `loadLibrary` still
throws `UnsatisfiedLinkError` before a line of our code runs. The routes left are a reflective patch
of `DexPathList`, or a fork of the binding, and both are somebody's decision rather than a detail.

A second thing that reading corrects, smaller but worth having: **the engine `.so` is never extracted
to a directory at all.** `minSdk = 26` gives `extractNativeLibs="false"`, so it is mapped straight out
of `base.apk!/lib/arm64-v8a` — the probe's own check of `applicationInfo.nativeLibraryDir` printed
`false`, which is not the engine missing but the engine living inside the APK.

**So the engine is in the APK in this build**, at the +39,811,828 bytes `ASR-RUNTIME-MEASUREMENT.md`
priced, and the opt-in-download half of `DwAsrRuntime.kt` is unreachable rather than wrong.
`DW_ASR_ARTIFACTS` stays **empty** and that is deliberate: there is no server serving an engine zip,
and inventing a URL to fill the row is precisely what that file's constructors exist to prevent.

## What was and was NOT changed in the app, and why the difference matters

| | |
|---|---|
| **added** | the vendored AAR + `flatDir`; R8 keep rules for the binding; `data/DwAsrModel.kt` with the two real pinned digests; `DwAsrEngineProbeTest`; `DwAsrRuntimeUi.dwAsrSha256OfFile` widened `private`→`internal` so the probe hashes with the app's own code rather than a copy |
| **NOT changed: `DW_TIER1_CATALOGUE` is still empty** | so `DwAsrOffer.NO_MODEL_TO_FEED_IT` and `DwTierRefusal.NO_MEASURED_MODEL` still answer on every handset. **A 53% Odia WER and a peak RSS the app's own `dwPlanFits` refuses are not a row.** Writing one would have made every card in Settings start describing a capability this handset does not have |
| **NOT changed: `DW_TIER1_RUNTIME_PRESENT` is still `false`** | and **this is now a defect rather than a fact.** That constant means "baked into the APK" and, as of this lane, the engine *is*. It was left alone because flipping it changes `dwTier1Offer`'s answer on every handset and rewrites sentences a designer reads, and doing that in the same pass as a model that is not good enough to offer would have put the screens and the world further apart, not closer. **Whoever picks this up must flip it and fix the tests in one pass** |
| **NOT changed: the dictation ladder's rung 1** | nothing in the shipped app reaches the engine. The only caller is the instrumented probe |

## What is still unmeasured after all this, in that word

| | |
|---|---|
| WER on real courtyard audio, in any language | **unmeasured.** FLEURS is studio read speech. Everything above is a ceiling |
| WER on a sample big enough to be an evaluation | **unmeasured.** n = 3 per language |
| Whether the `armeabi-v7a` engine loads at all | **unmeasured.** One arm64 handset was in the room |
| Peak RSS on a phone under real load | **unmeasured.** Idle, on the charger, nothing else open |
| Thermal behaviour under sustained decode | **unmeasured.** Six utterances is not sustained; the handset was on the charger and started cool |
| Whether the app survives backgrounding as the LMK sees it | **unmeasured**, for the `oom_score_adj` reason above |
| What `numThreads` other than 2 would do to RTF, heat and battery | **unmeasured.** One setting was run |
| Whether the fp32 model is better, and by how much | **unmeasured.** Only int8 was fetched |
| Whether any IndicConformer export could be made to work | ~~**unmeasured**~~ **ANSWERED 2026-08-13: YES, and it needed no "making" — the official ONNX loads as-is.** This row was right that it was the obvious next thing to try. `ai4bharat/indic-conformer-600m-multilingual`, merged into one graph, opens on sherpa-onnx 1.13.5 and decodes; **Odia is block 14** of a shared 5633-class vocabulary. What remains unmeasured is **int8 size and Odia WER**, not feasibility — see *the correction* above and `ASR-RUNTIME-MEASUREMENT.md` |

---

## Why the published size table cannot answer this

Plan §2.1 carries a correction that is the whole reason this document is separate from the plan. An
earlier draft concluded from the Gemma 4 distribution sizes that Tier 2 does not fit the fleet
handset. That was wrong, and wrong in an instructive way:

- **A download size is not a resident set.** An `ollama` tag's size is the artifact on disk in that
  distribution's format, not the memory a process needs to hold while it runs.
- **An Ollama artifact is not the deployment target.** The Android path is a mobile runtime —
  LiteRT / MediaPipe AI Edge, or ONNX Runtime — with a quantization prepared for it. That is a
  different artifact with different memory behaviour from the desktop/server one.

What the published table *does* still support, and what is worth keeping in view: `gemma4:12b` is
**7.6 GB** while `gemma4:e4b` is **9.6 GB**. The "E" names are *effective* parameter counts, so they
do not predict the footprint that has to be resident. **Sizing a deployment from the letter in the
name is how it lands on a phone that cannot hold it.**

**AND THE CORRECTION'S OWN PREDICTION CAME TRUE, MEASURED 2026-08-13.** This section said an Ollama tag
is not the deployment target and that the mobile artifact would be a different one with different
memory behaviour. The mobile exports have since been downloaded and weighed: **`gemma-4-E2B-it.litertlm`
is 2,588,147,712 bytes and `gemma-4-E4B-it.litertlm` is 3,659,530,240** — roughly a third of the
`ollama` tags this document once concluded the fleet handset could not hold, and by this app's own
arithmetic neither is refused on that handset. The earlier conclusion was drawn from the only numbers
available at the time and it was wrong by a factor of about three. *An absence is a claim, and a claim
needs a command beside it* — `hf download litert-community/gemma-4-E2B-it-litert-lm --dry-run` is that
command, and it takes under a second. Full table: `TIER2-LANGUAGE-MODEL-MEASUREMENT.md`.

## Two things that must be measured together, not separately

**Context length is a memory dial, not a model property.** The published 128K and 256K windows are
desktop affordances. KV-cache grows with context length and can exceed the weights themselves, so a
recommendation that names a model without naming its context cap has not said what will be run. Every
row below therefore has a **cap** column, and a measurement taken at an unrecorded cap is not a
measurement of anything.

**Peak RSS is the number, not on-disk size.** Weights may be memory-mapped, in which case the
resident set is smaller than the file and depends on the access pattern. Both are recorded below,
separately, because they answer different questions: on-disk size is what a designer's data bundle
pays for, peak RSS is what the low-memory killer reads.

---

## What the probe reads, and the one signal it deliberately refuses

The right-hand column is what each signal is **for**; every one of them now has a reading beside it
from the fleet's own handset, taken 2026-08-12 and recorded in full at the head of this document.

| signal | source | why | SM-M325F, 2026-08-12 |
|---|---|---|---|
| total RAM | `ActivityManager.MemoryInfo.totalMem` | the coarse device class | **5,927,968,768** |
| available RAM now | `MemoryInfo.availMem` | what is actually free at the moment of the job | **1,533,587,456** at that instant; moved 14–30 MB over a ten-second window on an idle phone, across three runs |
| low-RAM flag | `ActivityManager.isLowRamDevice()` | Android's own verdict; an immediate no | **false** |
| free storage | `StatFs` on the app's files dir | a multi-gigabyte model needs somewhere to live | **41,247,846,400** (`availableBytes`; `freeBytes` was 128 MiB higher) |
| ABI | `Build.SUPPORTED_ABIS` | which runtime build would be fetched | **`[arm64-v8a, armeabi-v7a, armeabi]`** |
| thermal status | `PowerManager.getCurrentThermalStatus()` | sustained inference on a mid-range phone throttles | **`NONE`** at rest, on the charger |
| charging | `BatteryManager.isCharging` | a queued job should prefer the wall socket | **true** |

**`getCurrentThermalStatus()` arrived in API 29 and `minSdk` here is 26.** On Android 8 and 9 there
is no way to ask, so the answer is `DwThermalState.UNMEASURED`, which is a separate value from
`NONE` — "the phone did not say" and "the phone said it is cool" are different facts, and a fleet
still carrying Android 9 would otherwise have its silence read as a clean bill of health on every
handset in it. `getThermalHeadroom()` is **not** called: it is API 30, it returns a forecast rather
than a state, and an earlier draft of this table listed it beside `getCurrentThermalStatus` when no
code ever read it.

**`ActivityManager.getMemoryClass()` is NOT read, and that is a decision rather than an omission.**
It is the *Dalvik heap* cap for Java objects. A LiteRT or ONNX model allocates **natively** — outside
that cap and outside its accounting — so `getMemoryClass()` will report something like 192 MB on a
device that can comfortably hold a 2 GB model, and will report nothing useful about the one that
cannot. Gating on it would be measuring the wrong thing confidently, which is the failure mode this
repository keeps finding and writing up.

**AND THAT IS NO LONGER AN ARGUMENT.** Measured on the fleet's handset 2026-08-12: `getMemoryClass()`
returns **256 MB** where `totalMem` is **5,927,968,768 bytes** — a factor of **22.1**, or 11.0 against
the `largeHeap` cap this app actually runs at. **The "something like 192 MB" above is the guess this
paragraph made before any handset was asked, and it is kept only as that: the measured cap is 256 MB,
a third higher.** The conclusion held and the gap came out wider than predicted; the figure did not.
See *The signal the probe refuses, measured anyway* above, where the two are scored separately, for
the full table and the `dalvik.vm.*` properties that confirm it from the shell.

**`/proc/meminfo` is not parsed either**, although plan §2.1 lists it beside `availMem`. The
platform's own `MemoryInfo.availMem` is derived from that file, and reading it a second time by hand
would give the app two answers to one question that could disagree — with the hand-rolled one being
the half with no handset behind it.

**"Could disagree" was measured on 2026-08-12 and is now "does".** Both figures taken within a
millisecond of each other on the fleet's handset: `MemTotal` matched `totalMem` byte for byte, and
`MemAvailable` came back **292,343,808 bytes (292 MB) below `availMem`** — 23.7% of the hand-parsed
number, and more than half the free-RAM margin the fit arithmetic keeps. An app holding both would
have had to choose between them.

---

## The recommendation table — SHAPE FIXED, EVERY CELL UNMEASURED

| device class | Tier 1 (ASR) | Tier 2 (SLM) | context cap | Tier 3 |
|---|---|---|---|---|
| low-RAM flag set, or < 3 GB | smallest ASR model only — **unmeasured** | **none**, and said so plainly | — | when online |
| 4 GB | **unmeasured** | **unmeasured** — expect none, or a ≤1 GB-class model | **unmeasured** | when online |
| 6–8 GB — **← the one M32 that has been measured lands here** | **MEASURED 2026-08-12 and the answer is "not yet"**: the engine runs and transcribes Odia and Hindi, at 15.2%/7.3% CER and **53.3%/24.2% WER** on n = 3 studio utterances each, **1.24 GB peak RSS** and a real-time factor of **1.08–2.97 across twelve utterances** — always slower than the audio plays, and with a spread that is not a stable property of the phone. See *THE ENGINE RUNS* and the timings correction under it. `DW_TIER1_CATALOGUE` is deliberately **still empty**: that WER is below any bar, and that peak RSS is one the app's own `dwPlanFits` refuses on this handset | **unmeasured** | **unmeasured** | when online |
| 12 GB+ | **unmeasured** | **unmeasured** | **unmeasured** | when online |

**THE SECOND ROW USED TO READ "4 GB (the M32 fleet)" AND THAT PARENTHESIS WAS WRONG.** The fleet's own
SM-M325F reports 5,927,968,768 bytes and `dwDeviceClass` puts it in the **third** row (measured
2026-08-12; full readout at the head of this file). **What that handset was sold as is not something
the handset said, and this file no longer states it.** The reading alone is enough for the correction
being made here: 5.521 GiB of reported memory cannot come from a 4 GB phone, so the fleet's handset is
not in the 4 GB row whatever its variant is called. The parenthesis has been deleted rather
than moved down a row: **how many of the rest of the fleet are 4 GB is unmeasured**, one phone is not
a survey, and naming a row after a fleet nobody has counted is how this went wrong the first time.
`DwDeviceClass.SMALL_4GB`'s KDoc carried the same claim and has been corrected in the same pass.

**This table says what a class of handset might one day be offered. It does not describe what the
app does today, and the Tier 1 column in particular must not be read that way** — there is no ASR
runtime in this APK at all, so every device, including the ones in the first row, is refused Tier 1
for the absence of an engine rather than for anything about the handset. `dwTier1Offer` checks the
engine before it checks the catalogue for exactly that reason: "no model has been measured" would
tell a designer that a model turning up is all that stands in the way, and it is not.

**AMENDED 2026-08-12: THE TIER 1 COLUMN NOW HAS A SECOND AXIS, BECAUSE THE ENGINE BECAME SOMETHING A
DESIGNER INSTALLS.** It is still absent from the APK and it is now never going in — the measurement
below priced it at 3.03× the packaged app on a delivery chain whose update prompt has no "Later"
button — so `android/app/src/main/java/com/designprototype/workshop/data/DwAsrRuntime.kt` makes it an **opt-in download**, offered once on the
dashboard at first run and standing permanently in Settings, per the decision recorded in
`docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md` §0. **Every row above therefore now depends on the handset as
well as on its class**, and a row can only ever be filled for a phone that has chosen to install it.

The sentence *"every device is refused Tier 1 for the absence of an engine"* remains **true today and
is no longer a constant**: it is reached because `DW_ASR_ARTIFACTS` is empty — nothing is published to
install and no digest is pinned — rather than because the code cannot say anything else. **Eight
answers are now expressible** where there used to be one, and each sends a designer somewhere
different. This is the whole of `dwTier1Offer`'s not-installed branch, read off the `when` rather than
summarised:

| the handset's situation | `DwTierRefusal` | new in this lane? | today |
|---|---|---|---|
| nothing published to install | `NO_RUNTIME_IN_THIS_BUILD` | no | **← every handset in the fleet** |
| an artifact exists and this phone could take it, is fetching it, or has no connection to fetch it with | `RUNTIME_NOT_INSTALLED` | **yes** | unreachable |
| the app could not read its own files | `RUNTIME_UNMEASURED` | **yes** | unreachable |
| the engine cannot be installed because no speech model has been weighed — **or** it is installed and none has | `NO_MEASURED_MODEL` (Tier 1's own sentence) | no | unreachable |
| no engine build for its processor | `ABI_NOT_BUILT_FOR` | no | unreachable |
| `Build.SUPPORTED_ABIS` came back empty | `ABI_UNMEASURED` | no | unreachable |
| `StatFs` would not say how much room there is | `FREE_STORAGE_UNMEASURED` | no | unreachable |
| not enough room for the engine | `NOT_ENOUGH_FREE_STORAGE` | no | unreachable |

**Two of the eight are new values** — `RUNTIME_NOT_INSTALLED` and `RUNTIME_UNMEASURED`, both marked
TIER 1 ONLY in the enum, because only Tier 1 has a runtime that can be absent from a handset rather
than from the build. **The other six reuse refusals this table's code already had**, deliberately: "no
build for your processor", "not enough room" and "nobody could read the storage" are the same news
whether the thing that will not fit is an engine or a model, and a second spelling of any of them
would put two sentences behind one fact. Every unreachable row is exercised by `DwDeviceTierTest`
against an openly invented artifact, so the day one is published the only new thing in the app is a row
of constants.

The Tier 3 column is the one that is not unmeasured, and it is not a promise about hardware: Tier 3
is the existing server provider chain (`backend/app/services/ai.py`), which has worked since long
before this document. It is listed so the table cannot be read as "this phone can do nothing".

### Where the left-hand column's edges are, and which of them is measured

**One of the three, as of 2026-08-12, against exactly one handset.** The device class is decided from
reported `totalMem` against three constants in `DwDeviceTier.kt`, and **every edge is still a chosen
number** — but they are not chosen freely. Reported total is always below the number on the handset's
box, and *how far* below on any particular phone was unmeasured when they were chosen, so each edge is
placed at or above the **ceiling** of the row underneath it, which the rule gives for free (a handset
sold as N GB cannot report more than N GiB):

| edge | value | what is given, and what is chosen | measured against a handset? |
|---|---|---|---|
| `DW_LOW_RAM_CEILING_BYTES` | 3 GiB (3,072 MiB) | entirely given — a 3 GB handset reports below 3 GiB whatever it reserves | **no.** No 3 GB or Go-edition handset has been probed |
| `DW_FOUR_GB_CEILING_BYTES` | 5,500 MiB | floor of 4,096 MiB given; the rest chosen, because the least a 6 GB handset reports was unmeasured | **ONE HANDSET HAS CLEARED IT, BUT THE CHOSEN HALF IS STILL UNMEASURED.** An SM-M325F of **unrecorded variant** reports 5,653.352 MiB and clears the edge by 153.35 MiB (2.8%). It only fills the gap the choice was made against *if it is a 6 GB phone*, and nothing the handset said establishes that |
| `DW_EIGHT_GB_CEILING_BYTES` | 11,000 MiB | floor of 8,192 MiB given; the rest chosen, likewise | **no.** No 8 GB or 12 GB handset has been probed |

The consequence is that a firmware reservation can only ever push a handset **down** a row, never up.
Down is the direction to be wrong in: erring low costs a designer a capability, erring high costs
them the work a low-memory killer ended halfway through. The first of these edges was 2,750 MiB in
the first draft, which sat *inside* the range a 3 GB handset reports and so classified one as a
4 GB-class phone — the wrong direction, on the one row whose Tier 2 cell is the word "none".

**The one handset held above the edge, and the margin is thinner than the choice reads.** 153 MiB on
an edge of 5,500 MiB. *If* that handset is a 6 GB one, its firmware takes 490.6 MiB — 7.99% of 6 GiB —
and a phone reserving 644 MiB, 10.5%, would have been demoted into the 4 GB row. That is the safe
direction and the rule worked as designed; it is recorded here so the next person tempted to move this
edge knows how little room it has. **Do not read one MediaTek Samsung on one firmware as the
distribution** — and note that "the least a 6 GB handset reports is unmeasured" is **still true**, because
what this one was sold as was never read off it. See the band-edge section at the head of this file.

## Per model, what a measurement must record

A row is not filled until every one of these is written down for the model **and its context cap**:

| | |
|---|---|
| model id and quantization | the exact artifact, not the family name |
| on-disk size | what the download costs the designer's bundle |
| memory-mapped? | and if so, the actual resident set |
| **peak RSS at the configured context cap** | the number the low-memory killer reads |
| time to first token | on that handset, not on a desktop |
| tokens/sec | likewise |
| **survives being backgrounded with the model loaded?** | see below |

The last one is not a nicety. A designer takes a photograph mid-summary; if that kills the process,
the summary and possibly the draft go with it.

---

## Rules the recommendation obeys, whatever the numbers turn out to be

These are settled and are written into `DwDeviceTier.kt` regardless of what fills the table. Each one
names the function that carries it, and says plainly whether anything calls that function yet:

- **Recommend; never auto-download.** `dwTierDownloadMayBeOffered` — *the only gate*, and it returns
  false for every handset in existence today, which is pinned by a test across every device class and
  every connection. The same rule the language packs already follow (`dwPackOffer`), for the same
  reason: a multi-gigabyte fetch on a prepaid bundle in a district town is a bill, not a feature.
  The settings card accordingly draws **no download control at all**, which is a decision rather than
  an omission — a control that cannot work is worse than an absent one.
- **Show the real size.** `dwTierOfferSentence` prints it. The language-pack screen refuses to print
  a size because `SpeechRecognizer.triggerModelDownload` reports none (see `dwDownloadCostSentence`);
  *our own* models have a known size, so this screen can and must state it before the tap — once
  there is a model to state a size for. **`dwBytesLabel` divides by 1000, not 1024**, so the letters
  mean what they say: the figure stands beside a prepaid bundle sold in decimal gigabytes, and a
  3,000,000,000-byte artifact printed as "2.8 GB" understates the bill in the one direction a size
  next to somebody's data allowance must not be wrong in. *No caller today* — both catalogues are
  empty, so this branch is unreachable.
- **A device that cannot run a tier says so, in words, once.** `dwTierRefusalSentence`, one sentence
  per refusal, each naming what would change it, all pinned as distinct by a test. Not a greyed-out
  control with no explanation, and not silence. `DwPackState`'s honest-unknown discipline is the
  model. Drawn today by the "AI on this phone" card in Settings.
- **Re-probe, do not cache for ever.** `dwProbeIsStale`, two minutes, and the card re-reads the
  handset each time it appears. Free RAM and free storage change; a recommendation made at install
  time is stale by the first workshop.
- **If a load fails on a device the table said was fine, that is data.** `dwFallbackAfterLoadFailure`
  — record it, fall back a tier, and tell the designer what changed rather than failing the job
  silently. *No caller today*: nothing in this build can load a model, so nothing can fail to.
- **Tier 2 never runs concurrently with capture.** `dwTier2RunWindow`, which refuses to start while
  the camera or a recording is open. A summarizer that kills the camera mid-workshop is a data-loss
  bug wearing a feature's clothes. *No caller today*: the job queue is step 7 of the plan's sequence
  and there is nothing to queue. The rule is written and tested now so that whoever builds that queue
  finds the bar already standing rather than having to remember it.

The three marked *no caller today* are rules with no way to reach them from a running app. They are
listed as implemented because the decision is made and testable, not because anything exercises them
on a handset — and that distinction is exactly the kind this document exists to keep.

## And the safeguard that makes device-dependent tiering permissible at all

Plan §2.1: **device-based tiering and provenance ship together or not at all.** A fleet where a 4 GB
handset and a 12 GB flagship run different tiers produces two classes of workshop record, and without
provenance they are indistinguishable on the page.

*(That sentence read "a 4 GB M32" until 2026-08-12 and was the same falsified claim this file's own
correction register is about — **in this file, below the register that called itself "every place it
still lives"**. It was missed because the line break falls between "4 GB" and "M32", so the grep that
found the others walked straight past it. It is corrected to "a 4 GB handset" because it is
illustrating a principle and needs no handset at all; the register now lists both sites in this
file. A register is only as good as the search that built it.)* That is why `DwAiLayer.tier` and
`DwAiLayer.modelId` are NOT NULL, and why the report annexure names the tier beside every layer it
prints. The provenance half is built; the tiering half cannot be finished until this table is.

---

## What would fill this document

Two questions. **The handset arrived on 2026-08-12 and answered neither of them**, which is the sharp
edge of this section rather than a footnote to it: a device unblocks the probe, and the probe was run
and is written up above. It cannot unblock a measurement whose subject is a file nobody has published.
Question 1 is not a handset question at all, and question 2 cannot be attempted until question 1 has
an answer to point it at.

1. ~~**Does Google publish a mobile export of a Gemma 4 model for Android at all, and what is its
   artifact id, quantization and on-disk size?**~~ **ANSWERED 2026-08-13. YES, four of them, and two
   need no approval at all.** The naming this document declined to repeat as fact — "Gemma 4 E2B/E4B" —
   turns out to be exactly right: `litert-community/gemma-4-E2B-it-litert-lm` and `-E4B-` are ungated
   and Apache-2.0, `gemma-4-E2B-it.litertlm` is **2,588,147,712 bytes** and `gemma-4-E4B-it.litertlm` is
   **3,659,530,240**, both hashed on the release machine, and the quantization is Google's mixed
   2/4/8-bit QAT mobile scheme rather than a plain int4. The Gemma **3n** pair
   (`google/gemma-3n-E2B-it-litert-lm`, `-E4B-`) exists too and is `gated=manual` — one click on the
   model page. Everything measured is in `TIER2-LANGUAGE-MODEL-MEASUREMENT.md`; the code that carries it
   is `android/…/data/DwTier2Models.kt`. **What is NOT answered is the runtime half of this question**,
   and it moved rather than closing: `DW_TIER2_RUNTIME_PRESENT` is still `false`, and the reason is no
   longer "nothing is published" — `com.google.ai.edge.litertlm:litertlm-android:0.16.0` is on
   `google()` and resolves — but that its Kotlin metadata is `mv=[2,3,0]` against this project's 2.0.21
   compiler, so the module does not compile with it. **The first cost of Tier 2 is a project-wide Kotlin
   upgrade, and nobody had priced it.**
2. **Loaded on a Galaxy M32 at a 2K context cap, what is the peak RSS, and does the app survive
   being backgrounded?** **Not attemptable, not merely unattempted.** `DwModelPlan` cannot be
   constructed without a measured peak RSS, and there is no artifact to load — so no peak RSS, no
   tokens/sec, no time to first token and no backgrounding result exists for any model on any handset.
   Note also that the row this decides is **not** the one this document used to say it was: the fleet
   handset that has been measured lands in the 6–8 GB row.

What the 2026-08-12 probe *can* say about question 2's arithmetic, and it is a boundary rather than an
answer: at the moment of that reading the fleet's handset had **1,533,587,456 bytes free**, so
`dwPlanFits` would have accepted a peak RSS of at most **996,716,544 bytes (~997 MB)** after the
512 MiB margin, and an on-disk size of at most **~40.2 GB** after the 1 GiB storage margin. Those are
properties of one instant on one idle, charging phone — not of the handset, and emphatically not of
the fleet. They are recorded so that whoever finally has an artifact knows roughly what they are
aiming under before they spend a day on it.

Until both are answered, `DwDeviceTier.kt` recommends **no Tier 2 model on any device** and says so
in words on every one of them. That is not a stub — it is the honest answer, and it is the same
answer `DwPackState.UNKNOWN` gives for a question the platform will not answer.

**Two of those sentences, not one, and this document is named by only the first.** Which a handset
gets depends on its row, and the ordering was argued over in `dwTier2Offer`:

- Every device except the first row gets `NO_MEASURED_MODEL`, which says the missing thing is a
  measurement rather than the phone, **names this document**, and names the two questions above. A
  12 GB flagship gets exactly the same sentence as the fleet's M32, because the obstacle is the same.
- A handset in the first row — `isLowRamDevice()` set, or reporting under 3 GiB — gets
  `DEVICE_TOO_SMALL` instead, and that sentence does *not* name this document. Both refusals are true
  of such a phone; it is given the one that does not invite it to come back after the next update,
  because for a Go-edition handset the answer then is still the "none, and said so plainly" in the
  first row of the table above. Telling it to wait for a measurement would be a false promise, which
  is worse than a plain no.

Tier 1 gets a third sentence again — `NO_RUNTIME_IN_THIS_BUILD`, on every device including the
low-RAM ones — and it is deliberately *not* the general one. **It is still the true sentence as of
2026-08-12, and `docs/ASR-RUNTIME-MEASUREMENT.md` is why**: step 4 of the plan's sequence went and
weighed the sherpa-onnx runtime, found it is published to neither `google()` nor `mavenCentral()`, and
measured what it would cost on the packaged APK anyway — +39,811,828 bytes for the cheapest shape of
it, with no model. Nothing shipped, so nothing here needed changing. This app has no speech engine of its
own, but the Settings card sits directly beneath "Offline dictation languages", which offers
Android's own on-device packs; a sentence reading "there is no engine in this build that could run a
model on this phone" two centimetres below a card promising offline dictation would read as one of
the two lying, and the reasonable response to that is to stop trusting the control that works. So the
Tier 1 sentence says whose engine is missing, and points at the packs measured in
`docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md` — two of our nineteen languages on the fleet's handset,
with the other seventeen, Odia among them, needing a connection until this app ships an engine.

**AND THE PARAGRAPH ABOVE PREDICTED ITS OWN AMENDMENT, WHICH LANDED ON 2026-08-12.** It used to end
*"if a runtime ever does land, this refusal becomes `NO_MEASURED_MODEL` and this paragraph changes in
the same pass, because 'there is no engine' and 'there is an engine and nothing to feed it' send a
designer to different places."* That is now built rather than promised, and it happened in the shape
the sentence did not anticipate: the runtime did not land **in** the build, it became a **download the
designer chooses** (`docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md`). Three things changed here:

- **Tier 1 now has its own `NO_MEASURED_MODEL` sentence**, distinct from Tier 2's. The missing artifact
  is a speech model, the document that records what is known about it is
  `docs/ASR-RUNTIME-MEASUREMENT.md` §3 — *no `assets/` entry in the AAR at all* — and Tier 2's closing
  clause, "this work is done on the server", would be a strange thing to say about dictation, which
  already happens there. `DwDeviceTierTest` pins that the two sentences differ.
- **The `NO_RUNTIME_IN_THIS_BUILD` sentence gained a clause naming the card above it**, because the
  old opening — *"that is work that has not been built, not a control missing from this screen"* —
  became false the moment a card offering the engine appeared directly above the tier card. There is a
  control on that screen now; it is disabled with the reason on it.
- **A third card now sits between the two named here.** Settings reads, top to bottom: Appearance,
  Accessibility, **Offline dictation languages**, **Offline speech engine**, **AI on this phone**. The
  Tier 1 sentence points *upwards* at both of the last two by name — "the offline dictation above" and
  "the card above" — so **moving either of them breaks a sentence rather than only a layout.**
  `ui/AppearanceScreen.kt` carries the same warning at the call sites.

---

## How this document is kept true

Almost everything here is a **decision** or an **absence**, and those are the claims that rot
quietly — a number at least looks like something to re-check. This file has already been wrong once
in exactly that way: it was written *before* the code it describes, and for a few hours it asserted
things about `DwDeviceTier.kt` that were not yet true of it. Since then the opt-in ASR lane changed
what Tier 1 can answer, and this document had to move with it in the same pass.

So: **every row below is a claim somebody can check with one command, or it is marked as needing the
handset.** A cell filled in without a device named beside it should be deleted, not trusted.

**TWO OF THESE COMMANDS WERE WRONG, AND THEY WERE ONLY FOUND WRONG BY BEING RUN (2026-08-12).** The
`getMemoryClass` and `/proc/meminfo` rows both said their grep "must return **nothing**". Both return
lines today and always have — the lines are the *comments in `DwDeviceProbe.kt` and `DwDeviceTier.kt`
explaining why the call is not made* (four of them for `getMemoryClass` as of this pass, three before
it; three for `/proc/meminfo`, two before it). A guard that has never passed is not a guard: anybody
running one as written would have seen those hits, concluded the document was already false, and either
"fixed" the paragraph or stopped running the check. The commands below now exclude comment lines, and
both were run and return nothing. **This is the same failure mode as a fixture that agrees with a
device that does not exist — a check that cannot pass teaches people to ignore it.**

**AND THE FIRST ATTEMPT AT FIXING THE `getMemoryClass` ROW PRODUCED THE OPPOSITE DEFECT, WHICH IS
WORSE, AND IT SURVIVED A "RUN IT AND SEE IT RETURN NOTHING" CHECK.** The replacement command searched
for `memoryClass` with a lower-case `m`. Every occurrence in the source is spelled `getMemoryClass`,
so **the pattern matched nothing in the repository at all** — not the comment lines it was meant to
skip, and not the call it exists to catch in the `getMemoryClass()` spelling that this document and
both KDocs use. It returned nothing, was recorded as passing, and the pass was credited to a comment
filter that had in fact done nothing. A guard that cannot fail is not a weaker guard than one that
cannot pass; it is a worse one, because the first advertises its own uselessness the moment anybody
runs it and the second never does. The row now uses `-i`, which finds four lines unfiltered and none
once comments are dropped, **and it was checked by injecting both spellings of a real call into a
scratch copy of `src/main` and watching it fire** — a "must return nothing" claim is only worth
writing down once somebody has seen the command return something.

| Claim class | Kept true by |
|---|---|
| Which signals the probe reads | `grep -n "totalMem\|availMem\|isLowRamDevice\|StatFs\|SUPPORTED_ABIS\|ThermalStatus\|isCharging" android/app/src/main/java/com/designprototype/workshop/data/DwDeviceProbe.kt` lists every read in one command. A signal named here and absent there is this document being wrong. |
| That `getMemoryClass()` is **not** read | **CORRECTED 2026-08-12, THEN CORRECTED AGAIN THE SAME DAY — the first correction produced a guard that could not fail.** Run `grep -rni "memoryclass" android/app/src/main --include=*.kt \| grep -v "^[^:]*:[0-9]*: *\*"`; it must return **nothing**. `-i` IS LOAD-BEARING: the first fix searched for `memoryClass` with a lower-case `m`, which **matches nothing in this repository at all**, because every occurrence is spelled `getMemoryClass` with a capital M. So the command returned nothing *whatever the filter did*, and "it was run and returns nothing" tested nothing. It was also blind in the direction that matters: Kotlin can spell this call **either** `am.memoryClass` (which the lower-case pattern catches) **or** `am.getMemoryClass()` (which it does not), and the second is the spelling this document, both KDocs and the probe test all name. With `-i` the pattern finds four lines unfiltered and none filtered, so the filter is doing real work; injecting both spellings into a scratch copy of `src/main` was checked to make it fire. If it ever returns a line, the strongest paragraph in this document has become false: it is the Dalvik heap cap, measured at **256 MB against 5.9 GB of total memory** on the fleet's handset, and a LiteRT or ONNX model allocates natively, outside it and outside its accounting. |
| That `/proc/meminfo` is not parsed by hand | **CORRECTED 2026-08-12**, same shape: `grep -rn "proc/meminfo" android/app/src/main --include=*.kt \| grep -v "^[^:]*:[0-9]*: *\*"`. `MemoryInfo.availMem` is derived from that file, and the two were measured **292 MB apart at one instant** on the fleet's handset — reading it twice really would give the app two answers to one question. |
| That the probe is measurable at all, and what it says | **`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.designprototype.workshop.DwDeviceTierProbeTest`**, then `adb logcat -d -s DWTIERPROBE:I`. Needs a handset attached. It asserts nothing and prints everything; the readout at the head of this document is its output. Re-run it on any new handset rather than reasoning about one. **A GREEN TICK FROM THIS TEST MEANS NOTHING ON ITS OWN AND MUST NEVER BE QUOTED AS EVIDENCE** — asserting nothing is deliberate (an assertion would be a claim about a handset written before the handset was asked), but it means a probe that failed every single read would print a page of the word "unmeasured" and still report BUILD SUCCESSFUL. **Read the logcat, and read the `FIELDS ANSWERED = n of 7` line** added on review 2026-08-12, which counts how much of the reading is a reading and names any signal that stayed silent. It was `7 of 7` on the fleet's handset. |
| The device-class edges | The three constants in `DwDeviceTier.kt`. All three are still **chosen**, and the chosen half of all three is still **unmeasured**: one handset (2026-08-12) cleared `DW_FOUR_GB_CEILING_BYTES` by 153 MiB, but it only measures "the least a 6 GB handset reports" if it is a 6 GB handset, and its variant was never read off it. Each carries its own guaranteed-floor argument: an edge sits at or above the most the row beneath it can report, so a firmware reservation can only push a handset *down* a row. `DwDeviceTierTest` pins that no edge sits below its neighbour's ceiling. |
| Which row a given handset lands in | The probe command above, on that handset. **Not by inference from the model name** — that is exactly what went wrong with "4 GB (the M32 fleet)", where the fleet's own phone reported far too much memory to be in the row named after it. Note that the *replacement* claim must obey the same rule: what the phone actually reports is measurable, what it was sold as is not, and this file states only the first. |
| That no Tier 2 model is recommended | **The reason changed on 2026-08-13 and the assertion did not.** `DW_TIER2_CATALOGUE` is no longer empty — it delegates to `DW_TIER2_PLANS`, two rows — so the guarantee now rests on `DW_TIER2_RUNTIME_PRESENT` being `false`: `dwTier2Offer` returns `NO_RUNTIME_IN_THIS_BUILD` on every handset, `dwTierDownloadMayBeOffered` is still asserted false on every fixture in `DwDeviceTierTest`, and `dwTier2InstallMayBeOffered` is asserted false for every row × every connection in `DwTier2ModelsTest`. Whoever flips that constant must give Tier 2 a fetch path in the same pass, or those tests go red. |
| That the Tier 2 rows carry Google's figure as Google's | `DwTier2ModelsTest` asserts every row's `measuredOn` contains both "S26 Ultra" and "published", that `languages` is `null`, and that the row sentence names Google and says nothing was measured on this phone. `DEVICE-TIER-MEASUREMENT.md` and `TIER2-LANGUAGE-MODEL-MEASUREMENT.md` must agree about which numbers are ours; only the second one holds the artifact table. |
| What Tier 1 answers | **Read `dwTier1Offer`.** It was a one-line constant return until 2026-08-12 and is not any more: since the engine became an opt-in install, "not in this build" and "not on this handset" are different facts and the function distinguishes them. Any summary of it in this file is a claim about code — check it before trusting it. See `docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md`. |
| That `DW_TIER1_RUNTIME_PRESENT` is `false` | `grep -n` it in `DwDeviceTier.kt`. It means **nothing is bundled**, and only that. It does not mean no engine can be present on a phone. |
| Every **unmeasured** cell in the recommendation table | Nothing mechanical, and nothing should. A number can only arrive from a handset **with an artifact to load on it**, and the two questions at the foot of this document name which measurement. The 2026-08-12 probe filled the left-hand column and could fill none of these, because there is nothing published to weigh — see *What could not be measured, and why*. |
| The thermal and charging readings, and the probe's cost | The probe command above. All three were **`NONE` / charging / 0.8–12.1 ms per call after a 9.1–12.9 ms first call** on the fleet's handset at rest on 2026-08-12, over six runs. **The steady-state upper bound was published as 2.6 ms off two runs, widened to 3.0 ms by the third, and broken to 12.1 ms by the fifth — and the expensive calls were not the first ones.** Treat any range here as a floor on the spread rather than a property of the handset. A reading taken on a hot phone or under load would be a different measurement and belongs beside that one, not instead of it. |
| That the test fixtures are shaped rather than measured | Their names — `fourGigClassPhone`, `goEditionPhone`, `phoneThatWouldNotAnswer`. **None is named after a real handset, deliberately**: `DwLanguagePackTest` learned that the hard way when a fixture named after the fleet's own M32 asserted capabilities the M32 does not have. |
| The engine transcript, the peak RSS and the real-time factor (*THE ENGINE RUNS*) | **`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.designprototype.workshop.DwAsrEngineProbeTest`**, then `adb logcat -d -s DWASRPROBE:I`. Needs the handset **and** the model side-loaded to `/data/local/tmp/dwasr` — the test's own KDoc has the `adb push` lines. Like the tier probe it asserts almost nothing and prints everything, so **a green tick is not evidence; read the logcat**. The one thing it *does* assert is the digest gate: it refuses to construct a recogniser if any pinned file on disk does not hash to the digest in `DW_ASR_MODELS`. |
| That the two model digests still describe the published artifact | `DwAsrModelTest` spells both out again by hand, independently of `DW_ASR_MODELS`, so an edit to one and not the other goes red. **If it fails, do not copy the new value across to make it pass** — re-download the artifact and hash it. The container's own digest is in `DwAsrModel.kt`'s KDoc, which is the fastest way to find out that upstream is serving different bytes at that URL today. |
| That pinning a model did **not** turn anything loose on the fleet | `DwAsrModelTest.pinningAModelDidNotMakeAnythingInstallable` — `DW_ASR_ARTIFACTS` empty, `dwAsrOffer` = `NOTHING_PUBLISHED_TO_INSTALL` and `dwAsrMayInstall` false on every connection. `DW_ASR_MODELS` and `DW_TIER1_CATALOGUE` are **different lists** and confusing them is the mistake this row exists to catch. |
| That `DW_TIER1_RUNTIME_PRESENT` still says `false` while the engine IS in the APK | **Nothing, and that is the point: it is a KNOWN-FALSE claim as of 2026-08-12, recorded rather than fixed.** See *What was and was not changed*. `grep -n "sherpa" android/app/build.gradle.kts` shows the engine on the compile classpath; the constant says it is not bundled. Whoever flips it must fix `DwDeviceTierTest` and `DwAsrRuntimeTest` in the same pass, because every Tier 1 sentence changes with it. |
| The rules the recommender obeys (recommend never auto-download; show the real size; say a refusal once, in words; re-probe rather than cache; a failed load is data) | `DwDeviceTier.kt`'s sentence constants and `DwDeviceTierTest`. These are the rows most likely to be quietly relaxed by a later lane in a hurry, because each of them costs a screen something. |
