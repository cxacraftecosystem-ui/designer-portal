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

internal object DwTier2Engine {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null

    fun nativeLibraryLoads(): Boolean = runCatching { System.loadLibrary(DW_TIER2_JNI_LIBRARY) }.isSuccess

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

    suspend fun release() {
        lock.withLock { closeQuietly() }
    }

    private fun closeQuietly() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }
}

internal const val DW_TIER2_JNI_LIBRARY: String = "litertlm_jni"

internal class DwTier2LoadFailed(val model: DwModelPlan, val detail: String) :
    Exception("${model.modelId} did not load: $detail")

internal fun dwTier2ModelFile(context: Context, modelId: String): File {
    val artifact = requireNotNull(dwTier2ArtifactFor(modelId)) { "No pinned artifact for $modelId." }
    return File(File(context.filesDir, DW_TIER2_DIR), artifact.fileName)
}

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

    fun forget(context: Context, modelId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getStringSet(KEY, emptySet()).orEmpty().filterNot { it.startsWith("$modelId\t") }
        prefs.edit().putStringSet(KEY, next.toSet()).apply()
    }
}
