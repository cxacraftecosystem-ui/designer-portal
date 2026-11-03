package com.designprototype.workshop

import android.app.ActivityManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.designprototype.workshop.data.DW_ASR_ARTIFACTS
import com.designprototype.workshop.data.DW_EIGHT_GB_CEILING_BYTES
import com.designprototype.workshop.data.DW_FOUR_GB_CEILING_BYTES
import com.designprototype.workshop.data.DW_LOW_RAM_CEILING_BYTES
import com.designprototype.workshop.data.DW_MODEL_FREE_RAM_MARGIN_BYTES
import com.designprototype.workshop.data.DW_MODEL_FREE_STORAGE_MARGIN_BYTES
import com.designprototype.workshop.data.DW_PROBE_FRESH_FOR_MS
import com.designprototype.workshop.data.DW_TIER1_CATALOGUE
import com.designprototype.workshop.data.DW_TIER1_RUNTIME_PRESENT
import com.designprototype.workshop.data.DW_TIER2_CATALOGUE
import com.designprototype.workshop.data.DW_TIER2_RUNTIME_PRESENT
import com.designprototype.workshop.data.DwAiTier
import com.designprototype.workshop.data.DwAsrRuntimeState
import com.designprototype.workshop.data.DwAsrRuntimeStatus
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.DwThermalState
import com.designprototype.workshop.data.DwTierOffer
import com.designprototype.workshop.data.dwAsrArtifactFor
import com.designprototype.workshop.data.dwAsrMayInstall
import com.designprototype.workshop.data.dwAsrMayLoad
import com.designprototype.workshop.data.dwAsrOffer
import com.designprototype.workshop.data.dwBytesLabel
import com.designprototype.workshop.data.dwDeviceClass
import com.designprototype.workshop.data.dwDeviceClassLabel
import com.designprototype.workshop.data.dwDeviceReadoutSentence
import com.designprototype.workshop.data.dwProbeDevice
import com.designprototype.workshop.data.dwProbeIsStale
import com.designprototype.workshop.data.dwRecommendTiers
import com.designprototype.workshop.data.dwTier2PowerAdvice
import com.designprototype.workshop.data.dwTier2RunWindow
import com.designprototype.workshop.data.dwTierDownloadMayBeOffered
import com.designprototype.workshop.data.dwTierOfferSentence
import com.designprototype.workshop.ui.designworkshop.dwConnection
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DwDeviceTierProbeTest {

    private val context by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    }

    @Test
    fun whatThisHandsetReportsAndWhatTheRecommenderDoesWithIt() {
        rule()
        log("DEVICE-TIER PROBE — every signal DwDeviceProbe reads, off this handset, through our own code")
        rule()

        identity()
        val reading = theProbeItself()
        theBandEdges(reading)
        theRefusedSignal(reading)
        theOtherRefusedSignal()
        theStorageArgument()
        theRecommendation(reading)
        theRulesWithNoCallerYet(reading)

        rule()
        log("END OF READOUT. Anything not printed above is unmeasured and must stay the word.")
        rule()
    }

    private fun identity() {
        head("1. THE DEVICE")
        log("  manufacturer     = ${Build.MANUFACTURER}")
        log("  model            = ${Build.MODEL}")
        log("  device/product   = ${Build.DEVICE} / ${Build.PRODUCT}")
        log("  android release  = ${Build.VERSION.RELEASE}  (API ${Build.VERSION.SDK_INT})")
        log("  build fingerprint= ${Build.FINGERPRINT}")
        log("  locale           = ${java.util.Locale.getDefault()}")
        log("  SUPPORTED_ABIS   = ${Build.SUPPORTED_ABIS?.toList()}   (platform preference order)")
        log("  SUPPORTED_32_BIT = ${Build.SUPPORTED_32_BIT_ABIS?.toList()}")
        log("  SUPPORTED_64_BIT = ${Build.SUPPORTED_64_BIT_ABIS?.toList()}")
        log("  build constants  : DW_TIER1_RUNTIME_PRESENT=$DW_TIER1_RUNTIME_PRESENT " +
            "DW_TIER2_RUNTIME_PRESENT=$DW_TIER2_RUNTIME_PRESENT")
        log("  catalogues       : tier1=${DW_TIER1_CATALOGUE.size} tier2=${DW_TIER2_CATALOGUE.size} " +
            "asrArtifacts=${DW_ASR_ARTIFACTS.size}  (all empty means every offer below is a refusal)")
    }

    private fun theProbeItself(): DwDeviceMeasurement {
        head("2. dwProbeDevice(context) — the app's own reading")

        val timings = ArrayList<Long>(10)
        var last: DwDeviceMeasurement? = null
        repeat(10) {
            val start = System.nanoTime()
            val m = dwProbeDevice(context)
            timings += (System.nanoTime() - start)
            last = m
        }
        val reading = last!!

        log("  totalRamBytes    = ${reading.totalRamBytes}  (${dwBytesLabel(reading.totalRamBytes)})")
        log("  availableRamBytes= ${reading.availableRamBytes}  (${dwBytesLabel(reading.availableRamBytes)})")
        log("  lowRamDevice     = ${reading.lowRamDevice}")
        log("  freeStorageBytes = ${reading.freeStorageBytes}  (${dwBytesLabel(reading.freeStorageBytes)})")
        log("  abis             = ${reading.abis}")
        log("  thermal          = ${reading.thermal}   tooHotToStart=${reading.thermal.tooHotToStart}")
        log("  charging         = ${reading.charging}")
        log("  takenAtElapsedMs = ${reading.takenAtElapsedMs}  (SystemClock.elapsedRealtime)")
        log("  filesDir         = ${context.filesDir.absolutePath}")

        val answered = listOf(
            "totalRamBytes" to (reading.totalRamBytes != null),
            "availableRamBytes" to (reading.availableRamBytes != null),
            "lowRamDevice" to (reading.lowRamDevice != null),
            "freeStorageBytes" to (reading.freeStorageBytes != null),
            "abis" to reading.abis.isNotEmpty(),
            "thermal" to (reading.thermal != DwThermalState.UNMEASURED),
            "charging" to (reading.charging != null),
        )
        val silent = answered.filterNot { it.second }.map { it.first }
        log("  FIELDS ANSWERED  = ${answered.count { it.second }} of ${answered.size}" +
            if (silent.isEmpty()) "   (a complete reading — every signal the probe reads came back)"
            else "   <- INCOMPLETE. Silent: $silent. Those are refusals, never zeroes.")

        log("  --- what it cost, ten consecutive calls, microseconds ---")
        log("      each   = ${timings.map { it / 1000 }}")
        log("      first  = ${timings.first() / 1000} us   " +
            "upper median (6th of 10 sorted) = ${timings.sorted()[5] / 1000} us   " +
            "max = ${timings.sorted().last() / 1000} us (usually the first call itself)")

        log("  --- availMem across those same ten calls (the reason nothing here may be cached) ---")
        val frees = (1..10).map { dwProbeDevice(context).availableRamBytes }
        log("      $frees")
        log("      min=${frees.filterNotNull().minOrNull()} max=${frees.filterNotNull().maxOrNull()} " +
            "spread=${(frees.filterNotNull().maxOrNull() ?: 0) - (frees.filterNotNull().minOrNull() ?: 0)} bytes")

        val overTimeStart = SystemClock.elapsedRealtime()
        val overTime = (1..6).map { call ->
            if (call > 1) Thread.sleep(2_000)
            dwProbeDevice(context).availableRamBytes
        }
        val overTimeWindowMs = SystemClock.elapsedRealtime() - overTimeStart
        log("      six more, two seconds apart, over a MEASURED ${overTimeWindowMs}ms window " +
            "(first probe to last, otherwise idle phone):")
        log("      $overTime")
        val lo = overTime.filterNotNull().minOrNull() ?: 0
        val hi = overTime.filterNotNull().maxOrNull() ?: 0
        log("      min=$lo max=$hi spread=${hi - lo} bytes (${dwBytesLabel(hi - lo)})")

        log("  --- the sentence a designer would read on the settings card ---")
        log("      ${dwDeviceReadoutSentence(reading)}")

        log("  --- staleness, against DW_PROBE_FRESH_FOR_MS = $DW_PROBE_FRESH_FOR_MS ---")
        val now = SystemClock.elapsedRealtime()
        log("      taken=${reading.takenAtElapsedMs} now=$now age=${now - reading.takenAtElapsedMs}ms " +
            "stale=${dwProbeIsStale(reading.takenAtElapsedMs, now)}")
        log("      same reading judged two minutes on: " +
            "stale=${dwProbeIsStale(reading.takenAtElapsedMs, now + DW_PROBE_FRESH_FOR_MS + 1)}")
        return reading
    }

    private fun theBandEdges(reading: DwDeviceMeasurement) {
        head("3. WHICH ROW OF THE TABLE — dwDeviceClass, against the real number")
        val total = reading.totalRamBytes
        log("  reported totalMem     = $total bytes")
        if (total != null) {
            log("      = ${"%.3f".format(total / 1024.0 / 1024.0)} MiB = ${"%.3f".format(total / 1024.0 / 1024.0 / 1024.0)} GiB")
            log("  DW_LOW_RAM_CEILING    = $DW_LOW_RAM_CEILING_BYTES (${DW_LOW_RAM_CEILING_BYTES / 1024 / 1024} MiB) " +
                "-> total is ${if (total < DW_LOW_RAM_CEILING_BYTES) "BELOW" else "above"} it")
            log("  DW_FOUR_GB_CEILING    = $DW_FOUR_GB_CEILING_BYTES (${DW_FOUR_GB_CEILING_BYTES / 1024 / 1024} MiB) " +
                "-> total is ${if (total < DW_FOUR_GB_CEILING_BYTES) "BELOW" else "above"} it")
            log("  DW_EIGHT_GB_CEILING   = $DW_EIGHT_GB_CEILING_BYTES (${DW_EIGHT_GB_CEILING_BYTES / 1024 / 1024} MiB) " +
                "-> total is ${if (total < DW_EIGHT_GB_CEILING_BYTES) "BELOW" else "above"} it")

            log("  --- reported vs the number on the box, on THIS handset only ---")
            listOf(3, 4, 6, 8).forEach { boxGb ->
                val box = boxGb.toLong() * 1024L * 1024L * 1024L
                val short = box - total
                log("      if sold as $boxGb GB: reserved/invisible = $short bytes " +
                    "(${"%.1f".format(short / 1024.0 / 1024.0)} MiB, ${"%.2f".format(100.0 * short / box)}% of ${boxGb} GiB)")
            }
        }
        val deviceClass = dwDeviceClass(reading)
        log("  dwDeviceClass(reading) = $deviceClass   label=\"${dwDeviceClassLabel(deviceClass)}\"")
        log("  isLowRamDevice checked FIRST, before any arithmetic: lowRamDevice=${reading.lowRamDevice}")
    }

    private fun theRefusedSignal(reading: DwDeviceMeasurement) {
        head("4. getMemoryClass() — THE SIGNAL THE PROBE REFUSES, MEASURED ANYWAY")
        log("  The claim under test, from DwDeviceProbe.kt and DEVICE-TIER-MEASUREMENT.md: it is the")
        log("  Dalvik heap cap, a natively-allocated model sits outside it, and it 'will report")
        log("  something like 192 MB on a device that can comfortably hold a 2 GB model'.")
        val am = context.getSystemService(ActivityManager::class.java)
        val memoryClass = runCatching { am.memoryClass }.getOrNull()
        val largeMemoryClass = runCatching { am.largeMemoryClass }.getOrNull()
        val jvmMax = Runtime.getRuntime().maxMemory()
        val total = reading.totalRamBytes
        log("  ActivityManager.getMemoryClass()      = $memoryClass MB  " +
            "(${memoryClass?.let { it.toLong() * 1024L * 1024L }} bytes)")
        log("  ActivityManager.getLargeMemoryClass() = $largeMemoryClass MB  " +
            "(${largeMemoryClass?.let { it.toLong() * 1024L * 1024L }} bytes)")
        log("  Runtime.getRuntime().maxMemory()      = $jvmMax bytes (${dwBytesLabel(jvmMax)}) " +
            "— the heap this very process is capped at")
        log("  MemoryInfo.totalMem (what the probe DOES read) = $total bytes (${dwBytesLabel(total)})")
        if (memoryClass != null && total != null) {
            val heapBytes = memoryClass.toLong() * 1024L * 1024L
            log("  RATIO: totalMem / getMemoryClass = ${"%.1f".format(total.toDouble() / heapBytes)}x")
            log("  A model of ${dwBytesLabel(1_500_000_000L)} would be " +
                "${"%.1f".format(1_500_000_000.0 / heapBytes)}x the heap cap and " +
                "${"%.2f".format(1_500_000_000.0 / total)}x this phone's total memory — the two answers")
            log("  a gate would give, side by side. Whether the gap is 'an order of magnitude' is now")
            log("  a number in this log rather than a prediction in a comment.")
        }
        log("  NOTE: read from androidTest/, never from src/main/. The guard that pins that is")
        log("  grep -rni \"memoryclass\" android/app/src/main --include=*.kt | grep -v \"^[^:]*:[0-9]*: *\\*\"")
        log("  — comment lines dropped, and case-insensitive because the source spells it with a")
        log("  capital M. It returns four lines unfiltered and none filtered. (This log used to")
        log("  claim `grep -rn \"getMemoryClass\" android/app/src/main` returned nothing; it returns")
        log("  four, and a readout is not a place to print something a reader can disprove.)")

        if (largeMemoryClass != null && total != null) {
            val largeBytes = largeMemoryClass.toLong() * 1024L * 1024L
            log("  This app sets android:largeHeap=\"true\", so the cap in force is the large one:")
            log("      totalMem / getLargeMemoryClass = ${"%.1f".format(total.toDouble() / largeBytes)}x")
            log("      Runtime.maxMemory() == getLargeMemoryClass: ${jvmMax == largeBytes}")
        }
    }

    private fun theOtherRefusedSignal() {
        head("4b. /proc/meminfo BY HAND vs MemoryInfo — THE SECOND REFUSED SIGNAL, AT ONE INSTANT")
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val handParsed = runCatching {
            java.io.File("/proc/meminfo").readLines().mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) parts[0].trimEnd(':') to (parts[1].toLongOrNull() ?: 0L) else null
            }.toMap()
        }.getOrNull().orEmpty()

        val memTotal = handParsed["MemTotal"]?.times(1024L)
        val memAvailable = handParsed["MemAvailable"]?.times(1024L)
        val memFree = handParsed["MemFree"]?.times(1024L)
        log("  platform  MemoryInfo.totalMem  = ${info.totalMem}")
        log("  by hand   MemTotal             = $memTotal   agree=${memTotal == info.totalMem}")
        log("  platform  MemoryInfo.availMem  = ${info.availMem}")
        log("  by hand   MemAvailable         = $memAvailable   agree=${memAvailable == info.availMem}")
        log("            difference           = ${memAvailable?.let { info.availMem - it }} bytes " +
            "(${dwBytesLabel(memAvailable?.let { info.availMem - it })})")
        log("  by hand   MemFree              = $memFree  (NOT the same question — free is not available)")

        log("  --- the two MemoryInfo fields dwProbeDevice discards ---")
        log("      threshold = ${info.threshold} (${dwBytesLabel(info.threshold)}) " +
            "— the platform's own low-memory line")
        log("      lowMemory = ${info.lowMemory}")
        log("      availMem - threshold = ${info.availMem - info.threshold} bytes " +
            "(${dwBytesLabel(info.availMem - info.threshold)}) — free memory above the platform's line")
        log("      DW_MODEL_FREE_RAM_MARGIN_BYTES is $DW_MODEL_FREE_RAM_MARGIN_BYTES, i.e. " +
            "${"%.1f".format(DW_MODEL_FREE_RAM_MARGIN_BYTES.toDouble() / info.threshold)}x the platform's own threshold.")
    }

    private fun theStorageArgument() {
        head("5. StatFs ON THE APP'S OWN files DIR — available vs free vs total")
        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        if (stat == null) {
            log("  StatFs threw. freeStorageBytes would be null and every storage check refuses.")
            return
        }
        val available = stat.availableBytes
        val free = stat.freeBytes
        val totalBytes = stat.totalBytes
        log("  path            = ${context.filesDir.absolutePath}")
        log("  totalBytes      = $totalBytes  (${dwBytesLabel(totalBytes)})")
        log("  freeBytes       = $free  (${dwBytesLabel(free)})    <- includes root-reserved blocks")
        log("  availableBytes  = $available  (${dwBytesLabel(available)})    <- what the probe reads")
        log("  root-reserved   = ${free - available} bytes  (${dwBytesLabel(free - available)})")
        log("  blockSize=${stat.blockSizeLong} blockCount=${stat.blockCountLong} " +
            "availableBlocks=${stat.availableBlocksLong}")
        log("  For scale: DW_MODEL_FREE_STORAGE_MARGIN_BYTES = $DW_MODEL_FREE_STORAGE_MARGIN_BYTES " +
            "(${dwBytesLabel(DW_MODEL_FREE_STORAGE_MARGIN_BYTES)}), so a model may be at most " +
            "${dwBytesLabel(available - DW_MODEL_FREE_STORAGE_MARGIN_BYTES)} on disk today.")
        log("  And DW_MODEL_FREE_RAM_MARGIN_BYTES = $DW_MODEL_FREE_RAM_MARGIN_BYTES " +
            "(${dwBytesLabel(DW_MODEL_FREE_RAM_MARGIN_BYTES)}) — both are CHOSEN numbers, not measured.")
    }

    private fun theRecommendation(reading: DwDeviceMeasurement) {
        head("6. dwRecommendTiers(...) — EVERY ANSWER, IN THE ORDER THE CODE PRODUCES THEM")

        val live = runCatching { dwConnection(context) }.getOrNull()
        log("  dwConnection(context) right now = $live")

        val artifact = dwAsrArtifactFor(reading.abis)
        val production = if (artifact == null) {
            DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED)
        } else {
            DwAsrRuntimeStatus(DwAsrRuntimeState.UNKNOWN, artifact)
        }
        log("  dwAsrArtifactFor(abis) = $artifact  -> production runtime status = ${production.state}")
        log("  dwAsrMayLoad(production) = ${dwAsrMayLoad(production)}")

        listOf(
            "AS THE SETTINGS SCREEN CALLS IT (runtime = NOT_INSTALLED)" to production,
            "AS A CALLER THAT PASSES NO RUNTIME WOULD GET IT (runtime = UNKNOWN)" to DwAsrRuntimeStatus(),
        ).forEach { (label, runtime) ->
            log("  --- $label ---")
            DwConnection.values().forEach { connection ->
                val r = dwRecommendTiers(reading, connection, runtime = runtime)
                log("    connection=$connection")
                log("      deviceClass    = ${r.deviceClass}")
                log("      tier1          = ${describe(r.tier1)}")
                log("      tier2          = ${describe(r.tier2)}")
                log("      tier3Available = ${r.tier3Available}")
                log("      downloadMayBeOffered: tier1=${dwTierDownloadMayBeOffered(r.tier1, connection)} " +
                    "tier2=${dwTierDownloadMayBeOffered(r.tier2, connection)}")
                log("      dwAsrOffer     = ${dwAsrOffer(runtime, reading, connection)}  " +
                    "mayInstall=${dwAsrMayInstall(dwAsrOffer(runtime, reading, connection))}")
            }
        }

        log("  --- THE SENTENCES THIS PHONE WOULD PRINT (production runtime, live connection) ---")
        val shown = dwRecommendTiers(reading, live ?: DwConnection.METERED, runtime = production)
        log("    TIER 1: ${dwTierOfferSentence(DwAiTier.TIER_1, shown.tier1)}")
        log("    TIER 2: ${dwTierOfferSentence(DwAiTier.TIER_2, shown.tier2)}")
    }

    private fun theRulesWithNoCallerYet(reading: DwDeviceMeasurement) {
        head("7. THE THERMAL AND CHARGING SIGNALS AT REST, AND WHAT THEY GATE")
        log("  thermal (getCurrentThermalStatus, API 29+; this device is API ${Build.VERSION.SDK_INT}) = " +
            "${reading.thermal}")
        log("  thermal.tooHotToStart = ${reading.thermal.tooHotToStart}  " +
            "(the bar is MODERATE — a CHOSEN threshold, never watched during a real inference run)")
        log("  charging = ${reading.charging}")
        log("  dwTier2PowerAdvice(charging) = ${dwTier2PowerAdvice(reading.charging)}")
        val offer = dwRecommendTiers(reading, DwConnection.UNMETERED).tier2
        log("  dwTier2RunWindow(realTier2Offer, capturing=false, thermal=${reading.thermal}) = " +
            "${dwTier2RunWindow(offer, false, reading.thermal)}")
        log("  dwTier2RunWindow(realTier2Offer, capturing=true,  thermal=${reading.thermal}) = " +
            "${dwTier2RunWindow(offer, true, reading.thermal)}")
        log("  (Both answer NOTHING_TO_RUN: the offer is a None, so the capture bar is never reached.")
        log("   That is the documented order, and it means the thermal reading above gates nothing")
        log("   today — it is recorded as a reading of the handset at rest, not as a decision.)")
    }

    private fun describe(offer: DwTierOffer): String = when (offer) {
        is DwTierOffer.Available ->
            "Available(${offer.plan.modelId} @ ${offer.plan.contextCapTokens} tokens, " +
                "headroom=${offer.headroomBytes})"
        is DwTierOffer.None -> "None(${offer.refusal})"
    }

    private fun head(title: String) {
        log("")
        log("-".repeat(100))
        log(title)
        log("-".repeat(100))
    }

    private fun rule() = log("=".repeat(100))

    private fun log(line: String) = Log.i("DWTIERPROBE", line).let { }
}
