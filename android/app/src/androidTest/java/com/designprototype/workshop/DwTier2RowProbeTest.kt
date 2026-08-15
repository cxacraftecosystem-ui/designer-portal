package com.designprototype.workshop

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.designprototype.workshop.data.DW_TIER2_ARTIFACTS
import com.designprototype.workshop.data.DW_TIER2_CATALOGUE
import com.designprototype.workshop.data.DW_TIER2_RUNTIME_PRESENT
import com.designprototype.workshop.data.DW_TIER2_UNJUDGED
import com.designprototype.workshop.data.DW_TIER2_UNJUDGED_LABEL
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.dwModelFitLabel
import com.designprototype.workshop.data.dwProbeDevice
import com.designprototype.workshop.data.dwRecommendTiers
import com.designprototype.workshop.data.dwTier2InstallMayBeOffered
import com.designprototype.workshop.data.dwTier2ListIntro
import com.designprototype.workshop.data.dwTier2RowSentence
import com.designprototype.workshop.data.dwTier2UnjudgedSentence
import com.designprototype.workshop.data.dwTier2VerifyFile
import java.io.File
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WHAT THE TIER 2 ROWS SAY ON THIS PARTICULAR HANDSET, AND WHETHER IT IS TRUE OF IT.
 *
 * A MEASUREMENT, not a regression guard — the same shape as `DwDeviceTierProbeTest`, and it asserts
 * nothing. Its output is a table in logcat and the table is the point. The desktop suite already
 * checks the arithmetic against invented rows; what no desktop test can answer is what THIS phone's
 * own `/proc/meminfo`, `StatFs` and `Build.SUPPORTED_ABIS` make of the two weighed Gemma 4 rows, and
 * what the sentence a designer would read actually says once those numbers are in it.
 *
 * It renders nothing on purpose. A Compose test needs a RESUMED activity, and an activity cannot
 * resume behind a secure keyguard — so a UI test cannot run on a locked fleet handset at all, while
 * this can: reading memory, storage and ABIs needs no unlock. **The rendering half is therefore
 * checked separately, on an unlocked device, by dumping the real view hierarchy.**
 *
 * ── HOW TO RUN IT WITHOUT SIGNING THE DESIGNER OUT ────────────────────────────────────────────
 *
 * **DO NOT USE `connectedDebugAndroidTest`** — AGP uninstalls the app afterwards and takes the
 * signed-in session and any unsynced drafts with it. Build and install the two APKs, then drive the
 * installed test APK directly:
 *
 *   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 *   adb install -r app/build/outputs/apk/debug/app-debug.apk
 *   adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *   adb shell am instrument -w \
 *     -e class com.designprototype.workshop.DwTier2RowProbeTest \
 *     com.designprototype.workshop.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s DWTIER2PROBE
 *
 * ── WHAT IT PRINTS AND WHY EACH LINE IS THERE ─────────────────────────────────────────────────
 *
 *  * The raw reading, so it can be diffed against `adb shell cat /proc/meminfo` taken by hand in the
 *    same minute. `availMem` and `MemAvailable` are different kernel figures and are expected to
 *    differ; a large gap in `totalMem` would mean the probe is not reading this phone.
 *  * Every row's fit, its notes, and its two headrooms as SIGNED numbers — a negative headroom is the
 *    whole reason `dwHeadroomBytes` refuses to clamp.
 *  * The exact sentence each row draws, and its word count, because the per-row bound is 90 words and
 *    the screen this replaced was measured at 1,207.
 *  * Whether an install control may be drawn, for all three connection states. It must be false
 *    everywhere while [DW_TIER2_RUNTIME_PRESENT] is false.
 *  * What [dwTier2VerifyFile] says about anything actually sitting in this app's storage — the only
 *    on-device check of the sideload claim, and the one place a pushed file becomes visible to code.
 *    **NOTHING IN THE APP CALLS THAT PREDICATE**, verified by search: a sideloaded model is invisible
 *    to every screen, so this probe is currently the only thing on the phone that ever looks at it.
 *
 * COST: it hashes every file in that directory. With the real 2.59 GB E2B artifact present that took
 * **16.9 s** on the fleet's SM-M325F (2026-08-13), against 1.7 s with only a 100 MB slice there. That
 * is the price of the one check worth having — a digest not taken is a substituted model not caught.
 */
@RunWith(AndroidJUnit4::class)
class DwTier2RowProbeTest {

    @Test
    fun whatTheTier2RowsSayOnThisHandset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        rule()
        log("TIER 2 ROW PROBE — ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")
        rule()

        val reading = dwProbeDevice(context)
        theReading(reading)
        theRows(reading)
        theGate(reading)
        theFilesOnThisPhone(context)
        rule()
    }

    /** The phone's own numbers, printed raw so a hand reading can be diffed against them. */
    private fun theReading(reading: DwDeviceMeasurement) {
        head("THE READING — dwProbeDevice, on this phone, now")
        log("  totalRamBytes      : ${reading.totalRamBytes}")
        log("  availableRamBytes  : ${reading.availableRamBytes}")
        log("  lowRamDevice       : ${reading.lowRamDevice}")
        log("  freeStorageBytes   : ${reading.freeStorageBytes}")
        log("  abis               : ${reading.abis}")
        log("  thermal            : ${reading.thermal}")
        // The kernel's own figures beside the framework's, since they are different reads and the
        // difference is the thing a hand-taken `cat /proc/meminfo` would otherwise look like a fault.
        val meminfo = runCatching { File("/proc/meminfo").readLines() }.getOrNull().orEmpty()
        meminfo.filter { it.startsWith("MemTotal") || it.startsWith("MemAvailable") || it.startsWith("MemFree") }
            .forEach { log("  /proc/meminfo      : ${it.trim()}") }
        log("  Build.SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString(",")}")
    }

    /** Every row, its verdict, its numbers and the exact words. */
    private fun theRows(reading: DwDeviceMeasurement) {
        head("THE ROWS — verdicts from dwModelFit, sentences as a designer would read them")
        val recommendation = dwRecommendTiers(reading, DwConnection.UNMETERED)
        val choices = recommendation.tier2Choices
        log("  DW_TIER2_CATALOGUE size : ${DW_TIER2_CATALOGUE.size}")
        log("  tier2Choices size       : ${choices.size}")
        log("  DW_TIER2_UNJUDGED size  : ${DW_TIER2_UNJUDGED.size}")
        log("  DW_TIER2_RUNTIME_PRESENT: $DW_TIER2_RUNTIME_PRESENT")

        val intro = dwTier2ListIntro(choices.size, DW_TIER2_UNJUDGED.size)
        var total = words(intro) + words("Language models")
        log("")
        log("  HEADING + INTRO (${words("Language models") + words(intro)} words)")
        log("    \"Language models\"")
        log("    \"$intro\"")

        choices.forEach { choice ->
            val sentence = dwTier2RowSentence(choice, reading)
            val n = words(sentence) + words(choice.plan.modelId) + words(dwModelFitLabel(choice.fit))
            total += n
            log("")
            log("  ROW ${choice.plan.modelId}  —  $n words")
            log("    fit                    : ${choice.fit}  (label: \"${dwModelFitLabel(choice.fit)}\")")
            log("    notes                  : ${choice.notes}")
            log("    peakRssBytes           : ${choice.plan.peakRssBytes}")
            log("    onDiskBytes            : ${choice.plan.onDiskBytes}")
            log("    freeRamHeadroomBytes   : ${choice.freeRamHeadroomBytes}")
            log("    freeStorageHeadroom    : ${choice.freeStorageHeadroomBytes}")
            log("    suggested              : ${choice.suggested}")
            log("    SENTENCE (${words(sentence)} words in the sentence alone):")
            log("      \"$sentence\"")
        }

        DW_TIER2_UNJUDGED.forEach { model ->
            val sentence = dwTier2UnjudgedSentence(model)
            val n = words(sentence) + words(model.modelId) + words(DW_TIER2_UNJUDGED_LABEL)
            total += n
            log("")
            log("  UNJUDGED ROW ${model.modelId}  —  $n words")
            log("    verdict label          : \"$DW_TIER2_UNJUDGED_LABEL\"")
            log("    onDiskBytes            : ${model.onDiskBytes}")
            log("    SENTENCE (${words(sentence)} words in the sentence alone):")
            log("      \"$sentence\"")
        }

        log("")
        log("  WHOLE SECTION, EVERY WORD ON SCREEN: $total")
    }

    /** No control may be drawn while nothing can load a model. Checked, not assumed. */
    private fun theGate(reading: DwDeviceMeasurement) {
        head("THE GATE — may an install control be drawn on this phone?")
        val choices = dwRecommendTiers(reading, DwConnection.UNMETERED).tier2Choices
        choices.forEach { choice ->
            DwConnection.entries.forEach { connection ->
                log(
                    "  ${choice.plan.modelId} / $connection : " +
                        "${dwTier2InstallMayBeOffered(choice, connection)} " +
                        "(with a runtime present it would be " +
                        "${dwTier2InstallMayBeOffered(choice, connection, runtimePresent = true)})"
                )
            }
        }
    }

    /**
     * What is actually on this phone, and what the one verify predicate says about it.
     *
     * Both directories are looked at because a sideload lands where a person put it: `filesDir` is
     * where the app's own download would write, and the external one is what an `adb push` can reach
     * without `run-as`.
     */
    private fun theFilesOnThisPhone(context: android.content.Context) {
        head("THE FILES — what is here, and what dwTier2VerifyFile says about it")
        val roots = listOfNotNull(
            File(context.filesDir, "dwtier2"),
            context.getExternalFilesDir(null)?.let { File(it, "dwtier2") },
        )
        roots.forEach { dir ->
            log("  ${dir.absolutePath} exists=${dir.exists()}")
            dir.listFiles()?.forEach { file ->
                val digest = runCatching { sha256(file) }.getOrNull()
                log("    ${file.name}  ${file.length()} bytes  sha256=$digest")
                log("      verdict: ${dwTier2VerifyFile(file.name, file.length(), digest)}")
            }
        }
        log("  pinned artifacts this build would accept:")
        DW_TIER2_ARTIFACTS.forEach {
            log("    ${it.fileName}  ${it.bytes} bytes  approval=${it.needsUpstreamApproval}")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Words as a reader counts them: whitespace-separated runs. */
    private fun words(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

    private fun head(title: String) {
        log("")
        log("-- $title ".padEnd(100, '-'))
    }

    private fun rule() = log("=".repeat(100))

    private fun log(line: String) = Log.i("DWTIER2PROBE", line).let { }
}
