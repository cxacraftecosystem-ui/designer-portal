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

/**
 * WHAT A PERSON HAS TYPED INTO A FORM AND NOT YET SAVED, KEPT ON DISK.
 *
 * ── THE GAP THIS CLOSES ─────────────────────────────────────────────────────────────────────────
 *
 * The record forms (artisan, product, tool, process, craft, workshop, interview), the media upload
 * screen, app feedback and the custom-questionnaire answer screen held every field in a Compose
 * `remember`. Save was safe — offline it goes to [OfflineOutbox] — but BEFORE Save, twenty minutes of
 * typing lived only in the process. Android killing the app in the background (a camera intent on a
 * low-memory phone is enough), a swipe-away, a battery death or an app update took it all, and the
 * photographs with it, because captures were written to `cacheDir`, which the platform reclaims
 * without notice. The design-workshop stages never had this problem ([WorkshopDraftStore] saves on
 * an 800 ms debounce); this brings every other form up to the same rule.
 *
 * ── MECHANISM ────────────────────────────────────────────────────────────────────────────────────
 *
 * The idiom [OfflineOutbox], [StagedJournal] and [WorkshopDraftStore] share: kotlinx JSON, one lock,
 * an atomic temp -> `fd.sync()` -> rename write, and a corrupt file read as "no draft" (set aside,
 * never deleted). One small file per form, under the signed-in account, so a second designer on a
 * shared phone is never offered the first one's half-typed artisan — and the first one gets theirs
 * back when they sign in again. Sign-out deliberately does NOT delete drafts: nothing typed is lost
 * across a sign-out/sign-in.
 *
 * A draft is a flat `Map<String, String>`. Forms decide their own keys; a key starting with
 * [URI_FIELD_PREFIX] holds newline-separated content Uris, which [durableCopy] has already moved into
 * app-private storage so they are still readable after the process that picked them is gone.
 */
@Serializable
data class FormDraft(
    val schema: Int = FORM_DRAFT_SCHEMA,
    val formKey: String,
    val ownerUserId: String? = null,
    val savedAt: String = "",
    val fields: Map<String, String> = emptyMap(),
)

const val FORM_DRAFT_SCHEMA = 1

/** A draft field whose value is newline-separated content Uris (captured or picked media). */
const val URI_FIELD_PREFIX = "uris:"

object FormDraftStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    /** Captures and copies older than this, referenced by no draft, are reclaimed. */
    const val MEDIA_GRACE_MS: Long = 14L * 24 * 60 * 60 * 1000

    fun root(context: Context): File = File(context.filesDir, "form-drafts")

    /** Where the record forms' camera/recorder now write — durable, unlike `cacheDir`. */
    fun capturesDir(context: Context): File = File(context.filesDir, "field-captures").apply { mkdirs() }

    private fun mediaDir(root: File): File = File(root, "media")

    /** A directory name for an account that is safe on any filesystem and reveals nothing. */
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
            // Set aside, never deleted: whatever is in it is somebody's typing.
            runCatching { file.renameTo(File(file.parentFile, "${file.name}.damaged-${System.currentTimeMillis()}")) }
            return@synchronized null
        }
        // A draft written by a NEWER build is left alone rather than half-read and overwritten.
        if (draft.schema > FORM_DRAFT_SCHEMA) return@synchronized null
        // Another account's draft under this account's directory cannot happen by construction; the
        // check is here so a copied file cannot surface somebody else's work either.
        if (draft.ownerUserId != null && ownerUserId != null && draft.ownerUserId != ownerUserId) {
            return@synchronized null
        }
        draft
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
                    fields = fields,
                )
            ).toByteArray()
            try {
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    // Windows (the test host) will not rename over an existing file; Android will.
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

    /** Every Uri string any draft on this device (any account) still points at. */
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

    /**
     * Delete captures and copies nothing refers to any more, once they are older than [graceMs].
     *
     * The grace is what makes this safe beside a form that is open right now: a capture is younger
     * than two weeks for as long as anybody could plausibly still be filling the form it belongs to,
     * and the moment a form is saved its files are copied into the outbox (or uploaded) and this
     * copy is no longer the only one. [isReferenced] is asked with the file itself so the caller can
     * match it however its Uris are spelled.
     */
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

    /** [sweep] over the real directories; run once per process from a background scope. */
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

    /**
     * A Uri for [uri] that will still open after this process dies.
     *
     * The app's own FileProvider Uris (camera captures, recordings) already point into app storage and
     * come back unchanged. Anything else — a gallery pick, a file from another app — is readable only
     * under a grant that ends with the process, so it is copied into `form-drafts/media/<uuid>/` under
     * its original name (the name is what the upload's filename and the gallery row are built from).
     * Null when the source cannot be read at all, in which case the original stays in the form and
     * simply is not promised across a restart.
     */
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

    /** Whether [uri] can still be opened — a restored draft drops the ones that cannot. */
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

/**
 * Whether a form's current values are worth keeping as a draft: anything that differs from what the
 * form opened with. Equal to the baseline means "nothing typed", and the draft is cleared rather than
 * kept, so opening and closing a form leaves nothing behind.
 *
 * An ABSENT key and an EMPTY one are the same answer. A form grows keys after it opens (an interview's
 * questions arrive after the screen does), and a box that appeared empty is not something a person
 * typed. Pinned by `FormDraftStoreTest`.
 */
internal fun formDraftIsWorthKeeping(current: Map<String, String>, baseline: Map<String, String>): Boolean =
    (current.keys + baseline.keys).any { key -> (current[key] ?: "") != (baseline[key] ?: "") }

/**
 * The part of a restored draft the form could not take yet, because the boxes it belongs in have not
 * been drawn (an interview's questions load after the screen opens). Kept, and written back with every
 * save, so a save that runs before they arrive does not erase them. Pinned by `FormDraftStoreTest`.
 */
internal fun formDraftLeftovers(restored: Map<String, String>, formKeys: Set<String>): Map<String, String> =
    restored.filterKeys { it !in formKeys }

/** Split a [URI_FIELD_PREFIX] field back into its Uri strings. */
internal fun formDraftUriList(value: String?): List<String> =
    value?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
