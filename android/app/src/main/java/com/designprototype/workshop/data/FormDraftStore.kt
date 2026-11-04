package com.designprototype.workshop.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Serializable
data class FormDraft(
    val schema: Int = FORM_DRAFT_SCHEMA,
    val formKey: String,
    val ownerUserId: String? = null,
    val savedAt: String = "",
    val fields: Map<String, String> = emptyMap(),
)

const val FORM_DRAFT_SCHEMA = 1

const val URI_FIELD_PREFIX = "uris:"

object FormDraftStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    const val MEDIA_GRACE_MS: Long = 14L * 24 * 60 * 60 * 1000

    fun root(context: Context): File = File(context.filesDir, "form-drafts")

    fun capturesDir(context: Context): File = File(context.filesDir, "field-captures").apply { mkdirs() }

    private fun mediaDir(root: File): File = File(root, "media")

    internal fun ownerSegment(ownerUserId: String?): String {
        val raw = ownerUserId?.takeIf { it.isNotBlank() } ?: return "_signed_out"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    internal fun fileFor(root: File, ownerUserId: String?, formKey: String): File {
        val safe = formKey.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)
        return File(File(root, ownerSegment(ownerUserId)), "$safe.json")
    }

    fun load(context: Context, ownerUserId: String?, formKey: String): FormDraft? =
        load(root(context), ownerUserId, formKey)

    internal fun load(root: File, ownerUserId: String?, formKey: String): FormDraft? = synchronized(lock) {
        val file = fileFor(root, ownerUserId, formKey)
        if (!file.isFile) return@synchronized null
        val draft = runCatching { json.decodeFromString<FormDraft>(file.readText()) }.getOrNull()
        if (draft == null) {
            runCatching { file.renameTo(File(file.parentFile, "${file.name}.damaged-${System.currentTimeMillis()}")) }
            return@synchronized null
        }
        if (draft.schema > FORM_DRAFT_SCHEMA) return@synchronized null
        if (draft.ownerUserId != null && ownerUserId != null && draft.ownerUserId != ownerUserId) {
            return@synchronized null
        }
        draft.copy(fields = AadhaarAtRest.openDraft(draft.fields))
    }

    fun save(context: Context, ownerUserId: String?, formKey: String, fields: Map<String, String>) =
        save(root(context), ownerUserId, formKey, fields)

    internal fun save(root: File, ownerUserId: String?, formKey: String, fields: Map<String, String>) {
        synchronized(lock) {
            val target = fileFor(root, ownerUserId, formKey)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.writing")
            val bytes = json.encodeToString(
                FormDraft(
                    formKey = formKey,
                    ownerUserId = ownerUserId,
                    savedAt = Instant.now().toString(),
                    fields = AadhaarAtRest.sealDraft(fields),
                )
            ).toByteArray()
            try {
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
                }
            } catch (e: Throwable) {
                runCatching { temp.delete() }
                throw e
            }
        }
    }

    fun clear(context: Context, ownerUserId: String?, formKey: String) = clear(root(context), ownerUserId, formKey)

    internal fun clear(root: File, ownerUserId: String?, formKey: String) {
        synchronized(lock) { runCatching { fileFor(root, ownerUserId, formKey).delete() } }
    }

    internal fun referencedUris(root: File): Set<String> = synchronized(lock) {
        root.listFiles()?.filter { it.isDirectory && it.name != "media" }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().toList() }
            .mapNotNull { file -> runCatching { json.decodeFromString<FormDraft>(file.readText()) }.getOrNull() }
            .flatMap { draft ->
                draft.fields.filterKeys { it.startsWith(URI_FIELD_PREFIX) }.values
                    .flatMap { it.split('\n') }
                    .filter { it.isNotBlank() }
            }
            .toSet()
    }

    internal fun sweep(dirs: List<File>, graceMs: Long, nowMs: Long, isReferenced: (File) -> Boolean): Int {
        var removed = 0
        dirs.forEach { dir ->
            dir.walkBottomUp().forEach { file ->
                when {
                    file == dir -> Unit
                    file.isDirectory -> if (file.list().isNullOrEmpty()) file.delete()
                    file.lastModified() > nowMs - graceMs -> Unit
                    isReferenced(file) -> Unit
                    file.delete() -> removed++
                }
            }
        }
        return removed
    }

    fun sweepUnreferencedMedia(context: Context): Int {
        val root = root(context)
        val referenced = referencedUris(root)
        val authority = "${context.packageName}.fileprovider"
        val referencedPaths = referenced.mapNotNull { text ->
            runCatching {
                val uri = Uri.parse(text)
                if (uri.authority != authority) return@runCatching null
                uri.path
            }.getOrNull()
        }
        return sweep(
            dirs = listOf(mediaDir(root), capturesDir(context)),
            graceMs = MEDIA_GRACE_MS,
            nowMs = System.currentTimeMillis(),
            isReferenced = { file ->
                val relative = file.relativeTo(context.filesDir).invariantSeparatorsPath
                referencedPaths.any { path -> path.endsWith("/$relative") }
            },
        )
    }

    fun durableCopy(context: Context, uri: Uri): Uri? {
        val authority = "${context.packageName}.fileprovider"
        if (uri.authority == authority) return uri
        if (uri.scheme == "file") return uri
        return runCatching {
            val name = displayName(context, uri)
                ?.replace(Regex("[/\\\\]"), "_")
                ?.takeIf { it.isNotBlank() }
                ?: "picked-${System.currentTimeMillis()}"
            val dir = File(mediaDir(root(context)), UUID.randomUUID().toString()).apply { mkdirs() }
            val target = File(dir, name)
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val n = input.copyTo(output, 64 * 1024)
                    output.flush()
                    output.fd.sync()
                    n
                }
            }
            if (copied == null || copied <= 0L) {
                target.delete(); dir.delete()
                null
            } else {
                FileProvider.getUriForFile(context, authority, target)
            }
        }.getOrNull()
    }

    fun isReadable(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && c.moveToFirst()) c.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
}

internal fun formDraftIsWorthKeeping(current: Map<String, String>, baseline: Map<String, String>): Boolean =
    (current.keys + baseline.keys).any { key -> (current[key] ?: "") != (baseline[key] ?: "") }

internal fun formDraftLeftovers(restored: Map<String, String>, formKeys: Set<String>): Map<String, String> =
    restored.filterKeys { it !in formKeys }

internal fun formDraftUriList(value: String?): List<String> =
    value?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
