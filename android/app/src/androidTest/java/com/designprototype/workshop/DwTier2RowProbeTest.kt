package com.designprototype.workshop

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.designprototype.workshop.data.DW_TIER2_ARTIFACTS
import com.designprototype.workshop.data.DW_TIER2_CATALOGUE
import com.designprototype.workshop.data.DW_TIER2_RUNTIME_PRESENT
import com.designprototype.workshop.data.DW_TIER2_LIST_INTRO
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.dwModelFitLabel
import com.designprototype.workshop.data.dwProbeDevice
import com.designprototype.workshop.data.dwRecommendTiers
import com.designprototype.workshop.data.dwTier2InstallMayBeOffered
import com.designprototype.workshop.data.dwTier2RowSentence
import com.designprototype.workshop.data.dwTier2VerifyFile
import java.io.File
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith

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

    private fun theReading(reading: DwDeviceMeasurement) {
        head("THE READING — dwProbeDevice, on this phone, now")
        log("  totalRamBytes      : ${reading.totalRamBytes}")
        log("  availableRamBytes  : ${reading.availableRamBytes}")
        log("  lowRamDevice       : ${reading.lowRamDevice}")
        log("  freeStorageBytes   : ${reading.freeStorageBytes}")
        log("  abis               : ${reading.abis}")
        log("  thermal            : ${reading.thermal}")
        val meminfo = runCatching { File("/proc/meminfo").readLines() }.getOrNull().orEmpty()
        meminfo.filter { it.startsWith("MemTotal") || it.startsWith("MemAvailable") || it.startsWith("MemFree") }
            .forEach { log("  /proc/meminfo      : ${it.trim()}") }
        log("  Build.SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString(",")}")
    }

    private fun theRows(reading: DwDeviceMeasurement) {
        head("THE ROWS — verdicts from dwModelFit, sentences as a designer would read them")
        val recommendation = dwRecommendTiers(reading, DwConnection.UNMETERED)
        val choices = recommendation.tier2Choices
        log("  DW_TIER2_CATALOGUE size : ${DW_TIER2_CATALOGUE.size}")
        log("  tier2Choices size       : ${choices.size}")
        log("  DW_TIER2_RUNTIME_PRESENT: $DW_TIER2_RUNTIME_PRESENT")

        val intro = DW_TIER2_LIST_INTRO
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

        log("")
        log("  WHOLE SECTION, EVERY WORD ON SCREEN: $total")
    }

    private fun theGate(reading: DwDeviceMeasurement) {
        head("THE GATE — may an install control be drawn on this phone?")
        val choices = dwRecommendTiers(reading, DwConnection.UNMETERED).tier2Choices
        choices.forEach { choice ->
            DwConnection.entries.forEach { connection ->
                log(
                    "  ${choice.plan.modelId} / $connection : " +
                        "${dwTier2InstallMayBeOffered(choice, connection)}"
                )
            }
        }
    }

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
            log("    ${it.fileName}  ${it.bytes} bytes  url=${it.url}")
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

    private fun words(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

    private fun head(title: String) {
        log("")
        log("-- $title ".padEnd(100, '-'))
    }

    private fun rule() = log("=".repeat(100))

    private fun log(line: String) = Log.i("DWTIER2PROBE", line).let { }
}
