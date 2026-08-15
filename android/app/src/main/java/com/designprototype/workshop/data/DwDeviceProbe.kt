package com.designprototype.workshop.data

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock

/**
 * THE SIX PLATFORM CALLS BEHIND [DwDeviceMeasurement], AND NOTHING ELSE.
 *
 * ── THE ANDROID HALF OF `DwDeviceTier.kt` ─────────────────────────────────────────────────────
 *
 * The decision — which row of docs/DEVICE-TIER-MEASUREMENT.md this handset is, and what may be
 * offered on it — is pure Kotlin over plain numbers in [DwDeviceTier], where the desktop JVM can
 * test it. Everything here is the part that cannot be: six reads that need a `Context`, each one
 * wrapped so that a failure becomes an honest null rather than a zero. Same split as
 * `DwLanguagePacks.kt` / `ui/designworkshop/DwLanguagePackUi.kt`, and for the reason this repository
 * keeps rediscovering — the untestable half is the half that is wrong, so it is kept small.
 *
 * ── A FAILED READ IS `null`, AND NEVER `0` ────────────────────────────────────────────────────
 *
 * Every `runCatching` below that yields a NUMBER or a FLAG ends in `getOrNull()`, and that is the
 * single most important line of this file. A zero would be a MEASUREMENT — "this device has no
 * memory", "this device has no storage" — and the recommender would act on it with complete
 * confidence, refusing every tier on a flagship whose `ActivityManager` lookup happened to fail. A
 * null is the absence of an answer, is rendered as the word "unmeasured", and refuses the decision
 * it feeds instead of poisoning it. Exactly what a null [DwRecognitionSupport] means one file over.
 *
 * THE TWO THAT DO NOT END IN `getOrNull()` CARRY THEIR OWN ABSENCE INSTEAD, and neither is an
 * exception to the rule. The ABI read falls back to an EMPTY LIST, which [dwPlanFits] refuses on as
 * [DwTierRefusal.ABI_UNMEASURED] rather than treating as "matches nothing"; the thermal read falls
 * back to [DwThermalState.UNMEASURED], which is a separate value from [DwThermalState.NONE] for
 * exactly this reason. In both cases the "did not answer" state is a value in the type, so a null
 * would have been a second spelling of it.
 *
 * ── THE SIGNAL THIS FILE REFUSES TO READ ──────────────────────────────────────────────────────
 *
 * **`ActivityManager.getMemoryClass()` IS NOT CALLED HERE, AND THAT IS DELIBERATE.** It is the
 * DALVIK HEAP cap for Java objects. A LiteRT or ONNX model allocates NATIVELY, outside that cap and
 * outside its accounting, so it would report something like 192 MB on a handset that could hold a
 * 2 GB model and would say nothing useful at all about the one that could not. It is the obvious
 * call with "memory" in the name and the next reader will reach for it; gating on it would be
 * measuring the wrong thing confidently, which is the failure mode this repository keeps writing up.
 *
 * THAT WAS AN ARGUMENT UNTIL 2026-08-12 AND IS NOW A MEASUREMENT. On the fleet's SM-M325F it returns
 * **256 MB** where `totalMem` is **5,927,968,768 bytes** — a factor of 22.1, or 11.0 against the
 * `largeHeap` cap this app actually runs at. The shell agrees from the other side:
 * `dalvik.vm.heapgrowthlimit = 256m`, `dalvik.vm.heapsize = 512m`. Read in `DwDeviceTierProbeTest`,
 * which lives in `androidTest/` precisely so that this file, and the shipped APK, stay clean of it.
 *
 * **`/proc/meminfo` IS NOT PARSED EITHER**, though the plan lists it beside `availMem`. The
 * platform's own `MemoryInfo.availMem` is derived from that file; reading it a second time by hand
 * would give this app two answers to one question that could disagree, and the hand-rolled one would
 * be the one with no test on a real handset behind it.
 *
 * "COULD DISAGREE" WAS ALSO MEASURED ON 2026-08-12, AT ONE INSTANT ON THAT HANDSET: `MemTotal`
 * matched `totalMem` byte for byte, and `MemAvailable` came back **292,343,808 bytes below**
 * `availMem` — 23.7% of the hand-parsed figure, and more than half the free-RAM margin the fit
 * arithmetic keeps. The two are not the same question and must not both be in this file.
 *
 * ── NOTHING HERE IS CACHED ────────────────────────────────────────────────────────────────────
 *
 * [dwProbeDevice] does the whole reading every time it is called, and callers are expected to call
 * it again rather than keep an answer. Free memory and free storage move through a working day —
 * see [dwProbeIsStale], which is what a screen holding a reading uses to notice it has gone off.
 */

/**
 * Read this handset, now. Called whenever a surface that shows the answer appears.
 *
 * IT NEVER THROWS. Every read that can fail is caught and becomes a null — or an empty ABI list, or
 * [DwThermalState.UNMEASURED] — in the returned [DwDeviceMeasurement].
 *
 * WHAT IT COSTS WAS TIMED ON A HANDSET ON 2026-08-12, AND THIS PARAGRAPH USED TO SAY IT HAD NOT BEEN.
 * On the fleet's SM-M325F (Android 13), idle and on the charger, ten consecutive calls per run, six
 * runs: **the first call costs 9.1–12.9 ms, and every call after it 0.8 ms to 12.1 ms.** Raw figures,
 * run by run, in docs/DEVICE-TIER-MEASUREMENT.md, taken by `DwDeviceTierProbeTest`.
 *
 * **THE SECOND HALF OF THAT RANGE IS THE POINT, AND IT TOOK FOUR MORE RUNS TO FIND.** This comment
 * first said "every call after it 0.8–2.6 ms", off two runs that both showed a tidy expensive-first-
 * call-then-flat shape. That shape was not the handset's; it was two runs. Later runs produced 7.9,
 * 8.3, 11.5 and 12.1 ms calls that were NOT the first call in their run — one of them the tenth and
 * last, costing more than its own run's first call. **So the expensive call is not reliably the first
 * one, and "the first call is the one a settings card experiences, the rest are free" is not a safe
 * reading of this probe.** A range from n runs is a floor on the spread, never a property.
 *
 * WHAT THAT DOES AND DOES NOT CHANGE. The rule below is still NOT triggered: every call ever observed
 * is inside one 60 Hz frame (16.7 ms), so this stays on the main thread. But [dwProbeIsStale] is two
 * minutes and the settings card re-reads the handset every time it appears, which makes re-probes the
 * common case — and a re-probe can cost as much as a first probe. Anybody moving this call, or adding
 * a second caller, should read the table in the measurement document rather than the summary here.
 * NONE OF THE SIX RUNS WAS TAKEN ON A PHONE UNDER REAL LOAD, WHICH REMAINS UNMEASURED.
 *
 * What can be said from the calls themselves, and what the timing bears out: five of the six are
 * binder round trips to `system_server` or reads of a static field, and one — `StatFs` — is a
 * `statfs(2)` on the app's own data volume, which is the only one that touches a filesystem.
 * `Context.filesDir` will also create that directory if it is somehow missing, which is a write,
 * though by the time any settings screen exists it has been there since first launch.
 *
 * Its one caller runs it inside a `LaunchedEffect`, so on the main thread; a slow read would delay
 * that one card's first frame and nothing else. THE 9–13 ms FIRST CALL IS INSIDE ONE 60 Hz FRAME BUT
 * NOT BY MUCH, and it was measured on a phone that was idle and on the charger — so the rule below
 * is not triggered and is not repealed either. If a handset ever turns up on which this is not cheap,
 * THE MEASUREMENT BELONGS IN docs/DEVICE-TIER-MEASUREMENT.md and the call belongs on
 * `Dispatchers.IO` — in that order, because moving it first would hide the evidence.
 */
fun dwProbeDevice(context: Context): DwDeviceMeasurement {
    // The application context, so a reading taken from a screen cannot keep an Activity alive; none
    // of these services needs anything narrower.
    val app = context.applicationContext

    /*
     * TOTAL AND AVAILABLE MEMORY, FROM ONE CALL, so that the pair describes one moment. That is what
     * `getMemoryInfo` returns and there is no second call to make; two reads taken a frame apart
     * would be two moments, and the card prints these two figures in one sentence.
     *
     * IT ALSO MEANS THE TWO GO MISSING TOGETHER, which `dwPlanFits` relies on: a failed read leaves
     * BOTH null, so there is no reading in which the total is unknown and the free figure is not.
     *
     * `totalMem` is the memory the kernel can see, which is below the number on the handset's box —
     * the firmware's reservations are taken before Android is told anything. HOW FAR BELOW IS
     * UNMEASURED, and the band edges in [dwDeviceClass] do not depend on knowing: they depend only
     * on the direction, and are placed so a reservation can push a handset down a row and never up.
     */
    val activity = runCatching { app.getSystemService(ActivityManager::class.java) }.getOrNull()
    val memory = runCatching {
        activity?.let { manager ->
            ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        }
    }.getOrNull()

    /*
     * ANDROID'S OWN VERDICT ON THE HANDSET, which is worth more than the byte count beside it: this
     * is the flag Android Go sets, and the OEM configuration behind it knows things about the device
     * that a memory total does not. Null when the service lookup failed — NOT false, because a
     * handset that would have said "yes, I am a low-memory device" must not be promoted to a
     * comfortable one by a failed lookup.
     */
    val lowRam = runCatching { activity?.isLowRamDevice }.getOrNull()

    /*
     * FREE STORAGE ON THE VOLUME A MODEL WOULD LIVE ON — the app's own files directory, not the
     * external one, because that is where it would be written and the two can be different volumes
     * with wildly different amounts free.
     *
     * `availableBytes`, NOT `freeBytes`: the free figure includes blocks reserved for root that this
     * app can never have, and sizing a multi-gigabyte download against space it cannot use is how a
     * download dies at 98% having spent the whole data bundle to get there.
     */
    val storage = runCatching { StatFs(app.filesDir.absolutePath).availableBytes }.getOrNull()

    /*
     * WHICH RUNTIME BUILD WOULD BE FETCHED. `SUPPORTED_ABIS` is ordered by the platform's own
     * preference, primary first, and is kept in that order — a 64-bit handset that also runs 32-bit
     * code lists `arm64-v8a` first and that is the build it should get.
     */
    val abis = runCatching { Build.SUPPORTED_ABIS?.toList().orEmpty() }.getOrDefault(emptyList())

    return DwDeviceMeasurement(
        totalRamBytes = memory?.totalMem,
        availableRamBytes = memory?.availMem,
        lowRamDevice = lowRam,
        freeStorageBytes = storage,
        abis = abis,
        thermal = dwThermalState(app),
        charging = dwCharging(app),
        // The MONOTONIC clock. `System.currentTimeMillis` moves when NTP corrects it or a designer
        // changes the date, and an age computed across such a jump comes out negative or hours wrong
        // — which would either hide a stale reading or throw away a fresh one.
        takenAtElapsedMs = SystemClock.elapsedRealtime(),
    )
}

/**
 * How hot the phone says it is, or [DwThermalState.UNMEASURED] where it cannot be asked.
 *
 * `PowerManager.getCurrentThermalStatus()` ARRIVED IN API 29 AND `minSdk` HERE IS 26. On Android 8
 * and 9 — still a real share of this fleet — there is no way to ask, and the only truthful answer is
 * "unmeasured". It is emphatically not [DwThermalState.NONE]: a silence read as a clean bill of
 * health would let a sustained job start on the handsets least able to take one.
 *
 * The version check is on the OUTSIDE of the call rather than inside a `runCatching`, so the API-29
 * method is never looked up on a device that does not have it.
 */
private fun dwThermalState(context: Context): DwThermalState {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return DwThermalState.UNMEASURED
    val power = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
        ?: return DwThermalState.UNMEASURED
    val status = runCatching { power.currentThermalStatus }.getOrNull() ?: return DwThermalState.UNMEASURED
    return when (status) {
        PowerManager.THERMAL_STATUS_NONE -> DwThermalState.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> DwThermalState.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> DwThermalState.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> DwThermalState.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> DwThermalState.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> DwThermalState.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> DwThermalState.SHUTDOWN
        // A status this build of Android has and this app does not know about. UNMEASURED rather
        // than NONE, for the same reason as everything else in this file: an unrecognised answer is
        // not an answer, and the one direction it must not be resolved in is "the phone is cool".
        else -> DwThermalState.UNMEASURED
    }
}

/**
 * Whether the phone is on the socket. Null when `BatteryManager` would not say.
 *
 * ADVISORY ONLY — nothing in [DwDeviceTier] blocks on it, and [dwTier2PowerAdvice] explains why: a
 * designer in a courtyard has no socket, and a capability that only ever works at the guest house is
 * not the offline capability any of this is for.
 */
private fun dwCharging(context: Context): Boolean? =
    runCatching { context.getSystemService(BatteryManager::class.java)?.isCharging }.getOrNull()
