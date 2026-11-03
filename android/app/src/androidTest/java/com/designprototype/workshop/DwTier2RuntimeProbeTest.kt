package com.designprototype.workshop

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.designprototype.workshop.data.DW_TIER2_PLANS
import com.designprototype.workshop.data.DwTier2Engine
import com.designprototype.workshop.data.DwTier2LoadFailed
import com.designprototype.workshop.data.DwTier2Verb
import com.designprototype.workshop.data.dwTier2ModelFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DwTier2RuntimeProbeTest {

    @Test
    fun theRuntimeLoadsAndAFileThatIsNotAModelIsALoadFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val abis = Build.SUPPORTED_ABIS.toList()
        log("TIER 2 RUNTIME PROBE — ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, abis=$abis")

        val loads = DwTier2Engine.nativeLibraryLoads()
        log("  liblitertlm_jni loads: $loads")
        if ("arm64-v8a" in abis || "x86_64" in abis) {
            assertTrue("LiteRT-LM ships a build for this processor and it must load", loads)
        } else {
            log("  NOT STAGED: no LiteRT-LM build for $abis, so nothing here can be measured")
            return
        }

        val model = DW_TIER2_PLANS.first()
        val file = dwTier2ModelFile(context, model.modelId)
        if (file.exists()) {
            log("  a real model is installed at ${file.path}; the bogus-file half is skipped to keep it")
            return
        }
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(64 * 1024) { (it % 251).toByte() })
        try {
            val outcome = runCatching {
                runBlocking { DwTier2Engine.generate(context, model, DwTier2Verb.PROOFREAD, "test", null) }
            }
            val failure = outcome.exceptionOrNull()
            log("  bogus file outcome: ${failure?.javaClass?.simpleName}: ${failure?.message}")
            assertTrue(
                "a file that is not a model must come back as DwTier2LoadFailed, got $failure",
                failure is DwTier2LoadFailed
            )
        } finally {
            file.delete()
            runBlocking { DwTier2Engine.release() }
        }
    }

    private fun log(line: String) = Log.i("DWTIER2PROBE", line).let { }
}
