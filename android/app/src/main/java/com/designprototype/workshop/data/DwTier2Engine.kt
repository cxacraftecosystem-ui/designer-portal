package com.designprototype.workshop.data

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **THE TIER 2 RUNTIME: GOOGLE'S LiteRT-LM, LOADING A VERIFIED GEMMA 4 FILE FROM `filesDir`.**
 *
 * One engine at a time, held for as long as somebody is using it and released by [release] — a 1.8 GB
 * resident set is not something to keep behind a settings screen. The model file is opened only after
 * [dwTier2VerifyFile] has answered VERIFIED for it (the controller writes nothing else into
 * [dwTier2ModelFile]'s directory under a final name), and on the CPU backend: the GPU path on this
 * fleet's Mali-G52 is unmeasured, and the verdict a phone was offered the model under was computed
 * from the CPU figure.
 *
 * ── A LOAD THAT FAILS IS DATA ───────────────────────────────────────────────────────────────────
 *
 * `Engine.initialize()` is separate from construction, which is what makes a failure recordable: it
 * becomes a [DwLoadFailureNote] in [DwTier2FailureStore], the model is never tried again on this
 * handset ([dwTier2ModelToRun] and `dwRecommendTiers` both read the store), and the caller falls back
 * to the cloud verb that has always existed.
 */
internal object DwTier2Engine {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null

    /**
     * Whether `liblitertlm_jni.so` loads in this process — the name LiteRT-LM's own loader asks for.
     * False on a processor the AAR has no build for (armeabi-v7a), which is why such a phone is never
     * offered a model: [DwModelPlan.abi] says the same thing before anybody gets this far.
     */
    fun nativeLibraryLoads(): Boolean = runCatching { System.loadLibrary(DW_TIER2_JNI_LIBRARY) }.isSuccess

    /**
     * Run [verb] over [passage] with [model] and return what it wrote. Throws [DwTier2LoadFailed] when
     * the model would not load, and anything else the runtime throws when generation fails.
     */
    suspend fun generate(
        context: Context,
        model: DwModelPlan,
        verb: DwTier2Verb,
        passage: String,
        targetLanguage: String?,
    ): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val ready = ensureLoaded(context, model)
            val config = ConversationConfig(
                Contents.of(dwTier2SystemInstruction(verb, targetLanguage)),
                emptyList(),
                emptyList(),
                // Greedy decoding: a proofread or a translation is a transformation, and the same
                // passage should come back the same way twice.
                SamplerConfig(1, 1.0, 0.0, 0),
            )
            ready.createConversation(config).use { conversation ->
                val reply: Message = conversation.sendMessage(passage)
                reply.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
            }
        }
    }

    private fun ensureLoaded(context: Context, model: DwModelPlan): Engine {
        val file = dwTier2ModelFile(context, model.modelId)
        engine?.let { if (loadedPath == file.path) return it }
        closeQuietly()
        val built = Engine(
            EngineConfig(
                file.path,
                Backend.CPU(),
                null,
                null,
                // The context the memory figure behind this phone's verdict was taken at.
                model.contextCapTokens ?: DW_TIER2_MEASURED_CONTEXT_TOKENS,
                null,
                context.cacheDir.path,
            )
        )
        try {
            built.initialize()
        } catch (failure: Throwable) {
            runCatching { built.close() }
            throw DwTier2LoadFailed(model, failure.message ?: failure.javaClass.simpleName)
        }
        engine = built
        loadedPath = file.path
        return built
    }

    /** Free the model's memory. Safe to call at any time; the next [generate] loads it again. */
    suspend fun release() {
        lock.withLock { closeQuietly() }
    }

    private fun closeQuietly() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }
}

/** The JNI library LiteRT-LM 0.18.0 loads (`NativeLibraryLoader.JNI_LIBNAME`, read out of the AAR). */
internal const val DW_TIER2_JNI_LIBRARY: String = "litertlm_jni"

/** The model would not load on this phone. Carries what the runtime said, for the failure note. */
internal class DwTier2LoadFailed(val model: DwModelPlan, val detail: String) :
    Exception("${model.modelId} did not load: $detail")

/** Where a verified model lives: `filesDir/dwtier2/<file name>`. Nothing else is ever written there. */
internal fun dwTier2ModelFile(context: Context, modelId: String): File {
    val artifact = requireNotNull(dwTier2ArtifactFor(modelId)) { "No pinned artifact for $modelId." }
    return File(File(context.filesDir, DW_TIER2_DIR), artifact.fileName)
}

/**
 * The Tier 2 load failures recorded on this handset. **Kept, because a failure is evidence.**
 *
 * Plan §2.1: *"If a load fails on a device the table said was fine, that is data. Record it, fall back
 * a tier, and tell the designer what changed."* A small SharedPreferences list, one line per failure.
 */
internal object DwTier2FailureStore {
    private const val PREFS = "dw_tier2_failures"
    private const val KEY = "notes"

    fun read(context: Context): List<DwLoadFailureNote> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            .orEmpty()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 2) return@mapNotNull null
                DwLoadFailureNote(
                    tier = DwAiTier.TIER_2,
                    modelId = parts[0],
                    contextCapTokens = parts[1].toIntOrNull(),
                    detail = parts.getOrElse(2) { "" },
                )
            }

    fun record(context: Context, model: DwModelPlan, detail: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val line = "${model.modelId}\t${model.contextCapTokens ?: ""}\t${detail.take(300)}"
        val next = prefs.getStringSet(KEY, emptySet()).orEmpty() + line
        prefs.edit().putStringSet(KEY, next).apply()
    }

    /** Forget the failures for [modelId] — when the designer removes the file and fetches it again. */
    fun forget(context: Context, modelId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getStringSet(KEY, emptySet()).orEmpty().filterNot { it.startsWith("$modelId\t") }
        prefs.edit().putStringSet(KEY, next.toSet()).apply()
    }
}
