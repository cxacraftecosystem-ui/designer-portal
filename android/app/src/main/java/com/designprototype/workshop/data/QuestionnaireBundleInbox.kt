package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID

@Serializable
data class ReceivedQuestionnaire(
    val id: String,
    val filename: String,
    val receivedAt: String,
    val title: String = "",
    val sectionCount: Int = 0,
    val questionCount: Int = 0,
    val sourceVersion: Int = 0,
    val handoffDigest: String = "",
    val localPath: String = "",
    val remoteId: String? = null,
    val sectionsDone: Int = 0,
    val currentSectionRemoteId: String? = null,
    val sectionCreateStarted: Boolean = false,
    val questionsDone: Int = 0,
    val adoptedAt: String? = null,
    val failure: String? = null,
    val bundleId: String? = null,
    val kind: String? = null,
) {
    val adopted: Boolean get() = adoptedAt != null
    val started: Boolean get() = remoteId != null
}

object QuestionnaireBundleInbox {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private fun dir(context: Context): File =
        File(context.filesDir, "inbox/questionnaires").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(dir(context), "index.json")

    private fun read(context: Context): List<ReceivedQuestionnaire> {
        val file = indexFile(context)
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ReceivedQuestionnaire>>(text) }.getOrElse {
            runCatching {
                file.renameTo(File(file.parentFile, "index.damaged-${System.currentTimeMillis()}.json"))
            }
            emptyList()
        }
    }

    private fun write(context: Context, rows: List<ReceivedQuestionnaire>) {
        val target = indexFile(context)
        val temp = File(target.parentFile, "${target.name}.writing")
        val bytes = json.encodeToString(rows).toByteArray()
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }

    suspend fun put(
        context: Context,
        filename: String,
        bytes: ByteArray,
        bundle: QuestionnaireBundle,
        bundleId: String? = null,
    ): ReceivedQuestionnaire = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!bundleId.isNullOrBlank()) read(context).firstOrNull { it.bundleId == bundleId }?.let { return@withLock it }
            val id = UUID.randomUUID().toString()
            val target = File(dir(context), "$id.$QUESTIONNAIRE_BUNDLE_EXTENSION")
            FileOutputStream(target).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            val row = ReceivedQuestionnaire(
                id = id,
                filename = safeDownloadName(filename) ?: "questionnaire.$QUESTIONNAIRE_BUNDLE_EXTENSION",
                receivedAt = Instant.now().toString(),
                title = bundle.title,
                sectionCount = bundle.sections.count { it.questions.isNotEmpty() },
                questionCount = bundle.questionCount,
                sourceVersion = bundle.sourceVersion,
                handoffDigest = questionnaireHandoffDigest(bundle),
                localPath = target.absolutePath,
                bundleId = bundleId?.ifBlank { null },
                kind = bundle.kind,
            )
            write(context, read(context) + row)
            row
        }
    }

    suspend fun all(context: Context): List<ReceivedQuestionnaire> = withContext(Dispatchers.IO) {
        mutex.withLock { read(context).sortedByDescending { it.receivedAt } }
    }

    suspend fun update(context: Context, row: ReceivedQuestionnaire) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == row.id }) {
                write(context, current.map { if (it.id == row.id) row else it })
            }
            Unit
        }
    }

    suspend fun bytes(context: Context, row: ReceivedQuestionnaire): ByteArray? =
        withContext(Dispatchers.IO) {
            val file = File(row.localPath)
            if (!file.isFile) null else runCatching { file.readBytes() }.getOrNull()
        }

    suspend fun remove(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val doomed = mutex.withLock {
            val current = read(context)
            val row = current.firstOrNull { it.id == id } ?: return@withLock null
            write(context, current.filterNot { it.id == id })
            row
        } ?: return@withContext false
        runCatching { File(doomed.localPath).delete() }
        true
    }
}

fun receivedQuestionnaireStatus(row: ReceivedQuestionnaire): String = when {
    row.adopted ->
        "Added to your questionnaires${readableStamp(row.adoptedAt)?.let { " on $it" }.orEmpty()}."
    row.failure != null ->
        "Stopped: ${row.failure}"
    row.remoteId != null ->
        "Partly added — ${row.sectionsDone} of ${row.sectionCount} sections are on your account. Continue — do not start again, or you'll get two copies."
    else ->
        "Not added yet. ${plural(row.questionCount, "question")} in ${plural(row.sectionCount, "section")}."
}
