package com.designprototype.workshop.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.designprototype.workshop.transcript.data.UploadTranscribe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

const val WORKSHOP_DRAFT_SCHEMA_VERSION: Int = 2

@Serializable
data class DraftMedia(
    val id: String = "",
    val relativePath: String = "",
    val originalFilename: String = "",
    val mimeType: String = "application/octet-stream",
    val mediaType: String = "DOCUMENT",
    val sizeBytes: Long = 0L,
    val capturedAt: String = "",
    val stageId: String? = null,
    val fieldKey: String? = null,
    val caption: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val mirrored: Boolean = false,
    val exifDateTime: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sha256: String? = null,
    val remoteMediaId: String? = null,
    val uploadedAt: String? = null,
    val uploadFailure: String? = null,
    val uploadFailedAt: String? = null,
    val transcribe: Boolean? = null
) {
    val isConfirmedRemote: Boolean get() = !remoteMediaId.isNullOrBlank()
}

@Serializable
data class DraftRow(
    val id: String = "",
    val values: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class StageDraft(
    val stageId: String = "",
    val title: String = "",
    val order: Int = 0,
    val values: Map<String, JsonElement> = emptyMap(),
    val custom: Map<String, JsonElement> = emptyMap(),
    val provenance: DwStageProvenanceDto = DwStageProvenanceDto(),
    val customSeen: Boolean = false,
    val rows: List<DraftRow> = emptyList(),
    val mediaIds: List<String> = emptyList(),
    val requiredKeys: List<String> = emptyList(),
    val minimumRows: Int = 0,
    val minimumMedia: Int = 0,
    val updatedAt: String = "",
    val completedAt: String? = null,
    val notes: String = "",
    val stageSeen: Boolean = false,
    val emptiedEntities: List<String> = emptyList(),
    val copiedFrom: Map<String, String> = emptyMap(),
    val deletedRowKeys: List<String> = emptyList()
)

@Serializable
data class WorkshopDraft(
    val schemaVersion: Int = WORKSHOP_DRAFT_SCHEMA_VERSION,
    val workshopId: String = "",
    val title: String = "",
    val templateId: String = "",
    val workshopKind: String? = null,
    val designerUserId: String? = null,
    val designerUserIds: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
    val stages: Map<String, StageDraft> = emptyMap(),
    val media: List<DraftMedia> = emptyList(),
    val remoteId: String? = null,
    val ownerUserId: String? = null,
    val sync: DraftSyncState = DraftSyncState(),
    val consent: DraftConsent = DraftConsent()
)

data class StageCompleteness(
    val stageId: String,
    val title: String,
    val order: Int,
    val filled: Int,
    val required: Int,
    val markedComplete: Boolean
) {
    val isComplete: Boolean get() = filled >= required
}

data class DraftCompleteness(
    val stages: List<StageCompleteness>,
    val filled: Int,
    val required: Int
) {
    val completeStages: Int get() = stages.count { it.isComplete }
    val fraction: Float get() = if (required <= 0) 1f else filled.toFloat() / required.toFloat()
}

data class WorkshopDraftSummary(
    val workshopId: String,
    val title: String,
    val templateId: String,
    val updatedAt: String,
    val stageCount: Int,
    val mediaCount: Int,
    val completeness: DraftCompleteness,
    val damaged: Boolean = false
)

object WorkshopDraftStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()

    private val alert = AtomicReference<String?>(null)

    fun takeAlert(): String? = alert.getAndSet(null)

    private fun rootDir(context: Context): File = File(context.filesDir, "workshops").apply { mkdirs() }

    private fun trashDir(context: Context): File = File(context.filesDir, "workshops-trash").apply { mkdirs() }

    fun workshopDir(context: Context, workshopId: String): File =
        File(rootDir(context), safeName(workshopId)).apply { mkdirs() }

    private fun draftFile(context: Context, workshopId: String): File =
        File(workshopDir(context, workshopId), "draft.json")

    private fun mediaDir(context: Context, workshopId: String): File =
        File(workshopDir(context, workshopId), "media").apply { mkdirs() }

    fun mediaFile(context: Context, workshopId: String, relativePath: String): File =
        File(workshopDir(context, workshopId), relativePath)

    fun mediaFile(context: Context, workshopId: String, media: DraftMedia): File =
        mediaFile(context, workshopId, media.relativePath)

    private fun safeName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_')
            .take(120)
            .ifBlank { "unnamed" }

    private fun readLocked(context: Context, workshopId: String): WorkshopDraft? =
        readDraftIn(File(rootDir(context), safeName(workshopId)), workshopId)

    internal fun readDraftIn(dir: File, workshopId: String): WorkshopDraft? {
        val file = File(dir, "draft.json")
        if (!file.exists()) return null

        val text = runCatching { file.readText() }.getOrElse { error ->
            quarantine(file, workshopId, "could not be read")
            return null
        }
        if (text.isBlank()) {
            quarantine(file, workshopId, "was not saved completely")
            return null
        }

        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { error ->
            quarantine(file, workshopId, "could not be read")
            return null
        }
        val onDiskVersion = runCatching { root["schemaVersion"]?.jsonPrimitive?.int }.getOrNull() ?: 0
        val migrated = migrate(root, onDiskVersion)

        return runCatching { json.decodeFromJsonElement(WorkshopDraft.serializer(), migrated) }.getOrElse { error ->
            quarantine(file, workshopId, "could not be opened by this version of the app")
            null
        }
    }

    private fun migrate(root: JsonObject, fromVersion: Int): JsonObject {
        var document = root
        var version = fromVersion
        while (version < WORKSHOP_DRAFT_SCHEMA_VERSION) {
            document = when (version) {
                0 -> document
                1 -> document
                else -> return document
            }
            version++
            document = JsonObject(document + ("schemaVersion" to JsonPrimitive(version)))
        }
        return document
    }

    private fun quarantine(file: File, workshopId: String, reason: String) {
        val kept = File(file.parentFile, "draft.corrupt-${System.currentTimeMillis()}.json")
        val moved = runCatching { file.renameTo(kept) }.getOrDefault(false)
        alert.set(
            if (moved) {
                "A workshop draft $reason. Nothing has been deleted — a copy is kept with all media. Ask your admin before uninstalling."
            } else {
                "A workshop draft $reason and couldn't be set aside. Don't uninstall; ask your admin."
            }
        )
    }

    private fun futureDraftSibling(target: File, onDiskVersion: Int): File =
        File(target.parentFile, "draft.newer-v$onDiskVersion-${System.currentTimeMillis()}.json")

    private fun quarantineFutureDraft(file: File, workshopId: String, onDiskVersion: Int, kept: File): Boolean {
        val moved = runCatching { file.renameTo(kept) }.getOrDefault(false)
        alert.set(
            if (moved) {
                "A workshop draft is from a newer app version. Nothing has been deleted — it's kept; Install the newer version again to recover it."
            } else {
                "A workshop draft is from a newer app version, and could not be set aside, so this save was stopped. Nothing was overwritten; Install the newer version again."
            }
        )
        return moved
    }

    private fun writeLocked(context: Context, draft: WorkshopDraft, onDisk: WorkshopDraft?) =
        writeDraftIn(workshopDir(context, draft.workshopId), draft, onDisk)
            .also { BackgroundSync.requestSoonFromDraftWrite(context) }

    internal fun writeDraftIn(
        dir: File,
        draft: WorkshopDraft,
        onDisk: WorkshopDraft?,
        keptFile: (target: File, onDiskVersion: Int) -> File = ::futureDraftSibling
    ) {
        val target = File(dir, "draft.json")
        if (onDisk != null && onDisk.schemaVersion > WORKSHOP_DRAFT_SCHEMA_VERSION) {
            val moved = quarantineFutureDraft(
                target,
                draft.workshopId,
                onDisk.schemaVersion,
                keptFile(target, onDisk.schemaVersion)
            )
            if (!moved) {
                throw IOException(
                    "This draft is from a newer app version and could not be set aside, so this save was stopped. Nothing has been lost; Install the newer version again."
                )
            }
        }
        val temp = File(target.parentFile, "${target.name}.writing")
        val bytes = json.encodeToString(WorkshopDraft.serializer(), draft).toByteArray()
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }

    private fun signedInUserId(context: Context): String? =
        runCatching { TokenStore(context).getUser()?.id }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun load(context: Context, workshopId: String): WorkshopDraft? =
        withContext(Dispatchers.IO) { mutex.withLock { readLocked(context, workshopId) } }

    suspend fun save(context: Context, draft: WorkshopDraft): WorkshopDraft = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = Instant.now().toString()
            val existing = readLocked(context, draft.workshopId)
            val stamped = draft.copy(
                schemaVersion = WORKSHOP_DRAFT_SCHEMA_VERSION,
                createdAt = existing?.createdAt?.takeIf { it.isNotBlank() }
                    ?: draft.createdAt.takeIf { it.isNotBlank() }
                    ?: now,
                updatedAt = now,
                ownerUserId = draft.ownerUserId ?: existing?.ownerUserId ?: signedInUserId(context)
            )
            writeLocked(context, stamped, existing)
            stamped
        }
    }

    suspend fun update(
        context: Context,
        workshopId: String,
        transform: (WorkshopDraft) -> WorkshopDraft
    ): WorkshopDraft = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = Instant.now().toString()
            val current = readLocked(context, workshopId)
                ?: WorkshopDraft(workshopId = workshopId, createdAt = now, updatedAt = now)
            val transformed = transform(current)
            val next = transformed.copy(
                schemaVersion = WORKSHOP_DRAFT_SCHEMA_VERSION,
                workshopId = workshopId,
                createdAt = current.createdAt.takeIf { it.isNotBlank() } ?: now,
                updatedAt = now,
                ownerUserId = transformed.ownerUserId ?: signedInUserId(context)
            )
            writeLocked(context, next, current)
            next
        }
    }

    suspend fun updateBookkeeping(
        context: Context,
        workshopId: String,
        transform: (WorkshopDraft) -> WorkshopDraft
    ): WorkshopDraft? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readLocked(context, workshopId) ?: return@withLock null
            val next = transform(current).copy(
                schemaVersion = WORKSHOP_DRAFT_SCHEMA_VERSION,
                workshopId = workshopId,
                createdAt = current.createdAt,
                updatedAt = current.updatedAt
            )
            writeLocked(context, next, current)
            next
        }
    }

    suspend fun adoptIntoWorkshop(
        context: Context,
        workshopId: String,
        remoteId: String,
    ): WorkshopDraft? {
        val current = load(context, workshopId) ?: return null
        if (!localDraftNeedsAWorkshop(current)) return null
        return update(context, workshopId) { draft ->
            if (!localDraftNeedsAWorkshop(draft)) {
                draft
            } else {
                adoptedIntoWorkshop(draft, remoteId, Instant.now().toString())
            }
        }
    }

    suspend fun updateStage(context: Context, workshopId: String, stage: StageDraft): WorkshopDraft =
        update(context, workshopId) { draft ->
            val stamped = stage.copy(
                stageId = stage.stageId,
                updatedAt = Instant.now().toString()
            )
            draft.copy(stages = draft.stages + (stamped.stageId to stamped))
        }

    suspend fun list(context: Context): List<WorkshopDraftSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dirs = rootDir(context).listFiles()?.filter { it.isDirectory }.orEmpty()
            dirs.map { dir ->
                val id = dir.name
                val draft = readLocked(context, id)
                if (draft == null) {
                    WorkshopDraftSummary(
                        workshopId = id,
                        title = id,
                        templateId = "",
                        updatedAt = "",
                        stageCount = 0,
                        mediaCount = File(dir, "media").listFiles()?.size ?: 0,
                        completeness = DraftCompleteness(emptyList(), 0, 0),
                        damaged = true
                    )
                } else {
                    WorkshopDraftSummary(
                        workshopId = draft.workshopId.ifBlank { id },
                        title = draft.title,
                        templateId = draft.templateId,
                        updatedAt = draft.updatedAt,
                        stageCount = draft.stages.size,
                        mediaCount = draft.media.size,
                        completeness = completeness(draft)
                    )
                }
            }.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun delete(context: Context, workshopId: String): File? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = File(rootDir(context), safeName(workshopId))
            if (!source.exists()) return@withLock null
            val target = File(trashDir(context), "${safeName(workshopId)}-${System.currentTimeMillis()}")
            if (!source.renameTo(target)) {
                throw IOException("Couldn't remove this workshop from the phone. Nothing was deleted.")
            }
            target
        }
    }

    suspend fun trashed(context: Context): List<File> = withContext(Dispatchers.IO) {
        mutex.withLock {
            trashDir(context).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()
        }
    }

    suspend fun restore(context: Context, trashedDir: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!trashedDir.exists()) return@withLock false
            val originalId = trashedDir.name.substringBeforeLast('-')
            val target = File(rootDir(context), originalId)
            if (target.exists()) return@withLock false
            trashedDir.renameTo(target)
        }
    }

    suspend fun importMedia(
        context: Context,
        workshopId: String,
        uri: Uri,
        stageId: String? = null,
        fieldKey: String? = null,
        caption: String? = null
    ): DraftMedia = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
            )
            ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "capture-${System.currentTimeMillis()}"
        val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)

        val target = File(mediaDir(context, workshopId), UUID.randomUUID().toString() + (extension?.let { ".$it" } ?: ""))
        val input: InputStream = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Couldn't read the captured file.")

        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            input.use { source ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        total += read
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
        } catch (e: Throwable) {
            runCatching { target.delete() }
            throw e
        }

        val relativePath = "media/${target.name}"
        val descriptor = DraftMedia(
            id = UUID.randomUUID().toString(),
            relativePath = relativePath,
            originalFilename = originalName,
            mimeType = mimeType,
            mediaType = inferDraftMediaType(mimeType),
            sizeBytes = total,
            capturedAt = Instant.now().toString(),
            stageId = stageId,
            fieldKey = fieldKey,
            caption = caption,
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
            transcribe = if (UploadTranscribe.eligible(inferDraftMediaType(mimeType))) false else null
        ).let { base -> if (base.mediaType == "IMAGE") base.withImageMetadata(target) else base }

        update(context, workshopId) { draft ->
            val stage = stageId?.let { draft.stages[it] }
            draft.copy(
                media = draft.media + descriptor,
                stages = if (stage == null) draft.stages else {
                    draft.stages + (stage.stageId to stage.copy(mediaIds = stage.mediaIds + descriptor.id))
                }
            )
        }
        descriptor
    }

    private fun DraftMedia.withImageMetadata(file: File): DraftMedia {
        val bounds = runCatching {
            BitmapFactory.Options().also { options ->
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        }.getOrNull()

        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
        val mirrored = orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
            orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

        val coordinates = FloatArray(2)
        val hasLocation = exif?.let { runCatching { it.getLatLong(coordinates) }.getOrDefault(false) } ?: false

        return copy(
            width = bounds?.outWidth?.takeIf { it > 0 } ?: 0,
            height = bounds?.outHeight?.takeIf { it > 0 } ?: 0,
            rotationDegrees = rotation,
            mirrored = mirrored,
            exifDateTime = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif?.getAttribute(ExifInterface.TAG_DATETIME),
            latitude = if (hasLocation) coordinates[0].toDouble() else null,
            longitude = if (hasLocation) coordinates[1].toDouble() else null
        )
    }

    suspend fun setTranscribe(context: Context, workshopId: String, mediaId: String, on: Boolean): DraftMedia? {
        var changed: DraftMedia? = null
        update(context, workshopId) { draft ->
            draft.copy(
                media = draft.media.map { item ->
                    if (item.id == mediaId && !item.isConfirmedRemote) item.copy(transcribe = on).also { changed = it } else item
                }
            )
        }
        return changed
    }

    suspend fun removeMedia(context: Context, workshopId: String, mediaId: String) {
        var removedPath: String? = null
        val doomed = update(context, workshopId) { draft ->
            removedPath = draft.media.firstOrNull { it.id == mediaId }?.relativePath
            draft.copy(
                media = draft.media.filterNot { it.id == mediaId },
                stages = draft.stages.mapValues { (_, stage) ->
                    stage.copy(mediaIds = stage.mediaIds.filterNot { it == mediaId })
                }
            )
        }
        withContext(Dispatchers.IO) {
            val path = detachedFileToDelete(removedPath, doomed.media) ?: return@withContext
            val file = mediaFile(context, workshopId, path)
            if (file.isFile) runCatching { file.delete() }
        }
    }

    internal fun detachedFileToDelete(removedPath: String?, survivors: List<DraftMedia>): String? {
        val path = removedPath?.takeIf { it.isNotBlank() } ?: return null
        if (survivors.any { it.relativePath == path }) return null
        return path
    }

    suspend fun reclaimableMedia(context: Context, draft: WorkshopDraft): Pair<Int, Long> =
        withContext(Dispatchers.IO) {
            val files = draft.media
                .filter { it.isConfirmedRemote }
                .map { mediaFile(context, draft.workshopId, it) }
                .filter { it.isFile }
            files.size to files.sumOf { it.length() }
        }

    suspend fun releaseUploadedMedia(context: Context, workshopId: String): Pair<Int, Long> =
        withContext(Dispatchers.IO) {
            val draft = load(context, workshopId) ?: return@withContext 0 to 0L
            var released = 0
            var bytes = 0L
            draft.media.forEach { descriptor ->
                if (!descriptor.isConfirmedRemote) return@forEach
                val file = mediaFile(context, workshopId, descriptor)
                if (!file.isFile) return@forEach
                val length = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    released++
                    bytes += length
                }
            }
            released to bytes
        }

    suspend fun missingMedia(context: Context, draft: WorkshopDraft): List<DraftMedia> =
        withContext(Dispatchers.IO) {
            draft.media.filterNot { mediaFile(context, draft.workshopId, it).exists() }
        }

    fun completeness(draft: WorkshopDraft): DraftCompleteness {
        val stages = draft.stages.values.sortedBy { it.order }.map { stage ->
            val requiredKeys = stage.requiredKeys.count()
            val filledKeys = stage.requiredKeys.count { key -> isAnswered(stage.values[key]) }
            val rowsRequired = if (stage.minimumRows > 0) 1 else 0
            val rowsFilled = if (stage.minimumRows > 0 && stage.rows.size >= stage.minimumRows) 1 else 0
            val mediaRequired = if (stage.minimumMedia > 0) 1 else 0
            val mediaFilled = if (stage.minimumMedia > 0 && stage.mediaIds.size >= stage.minimumMedia) 1 else 0
            StageCompleteness(
                stageId = stage.stageId,
                title = stage.title,
                order = stage.order,
                filled = filledKeys + rowsFilled + mediaFilled,
                required = requiredKeys + rowsRequired + mediaRequired,
                markedComplete = stage.completedAt != null
            )
        }
        return DraftCompleteness(
            stages = stages,
            filled = stages.sumOf { it.filled },
            required = stages.sumOf { it.required }
        )
    }

    private fun isAnswered(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> value.content.isNotBlank()
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
    }

    internal fun displayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun inferDraftMediaType(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "IMAGE"
        mimeType.startsWith("video/") -> "VIDEO"
        mimeType.startsWith("audio/") -> "AUDIO"
        mimeType == "application/pdf" -> "PDF"
        else -> "DOCUMENT"
    }
}
