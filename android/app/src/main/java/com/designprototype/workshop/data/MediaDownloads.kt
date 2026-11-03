package com.designprototype.workshop.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import java.util.Calendar
import java.util.TimeZone

object MediaDownloads {

    private val EXTENSION_BY_MIME: Map<String, String> = mapOf(
        "audio/webm" to "webm",
        "audio/ogg" to "ogg",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/aac" to "aac",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/flac" to "flac",
        "audio/3gpp" to "3gp",
        "audio/amr" to "amr",
        "video/webm" to "webm",
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/3gpp" to "3gp",
        "video/x-matroska" to "mkv",
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/svg+xml" to "svg",
        "image/tiff" to "tiff",
        "application/pdf" to "pdf",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/zip" to "zip",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "application/json" to "json",
        "model/gltf-binary" to "glb",
    )

    private val PLACEHOLDER_NAMES = setOf("blob", "untitled")

    private val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]+")
    private val EXTENSION = Regex("\\.[A-Za-z0-9]{1,8}$")

    fun baseMimeType(mimeType: String?): String =
        mimeType.orEmpty().substringBefore(';').trim().lowercase()

    fun extensionForMime(mimeType: String?): String? {
        val base = baseMimeType(mimeType)
        if (base.isEmpty()) return null
        return EXTENSION_BY_MIME[base]
    }

    private fun stemForMime(mimeType: String?): String {
        val base = baseMimeType(mimeType)
        return when {
            base.startsWith("audio/") -> "voice-note"
            base.startsWith("video/") -> "video"
            base.startsWith("image/") -> "photo"
            else -> "file"
        }
    }

    fun fileDateStamp(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return "%04d-%02d-%02d-%02d%02d%02d".format(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
            c.get(Calendar.SECOND),
        )
    }

    fun downloadFileName(
        displayName: String?,
        mimeType: String?,
        capturedAtMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val extension = extensionForMime(mimeType)
        val cleaned = displayName.orEmpty()
            .replace(UNSAFE_NAME, "-")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimStart('.')
        val stem = cleaned.replace(EXTENSION, "")
        if (cleaned.isNotEmpty() && stem.lowercase() !in PLACEHOLDER_NAMES) {
            if (EXTENSION.containsMatchIn(cleaned) || extension == null) return cleaned
            return "$cleaned.$extension"
        }
        val stamp = fileDateStamp(capturedAtMillis?.takeIf { it > 0L } ?: nowMillis, zone)
        return "${stemForMime(mimeType)}-$stamp.${extension ?: "bin"}"
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
fun saveToDownloads(context: Context, source: Uri, displayName: String, mimeType: String): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Downloads could not be opened for writing.")
    try {
        copyMediaInto(context, source, target)
    } catch (error: Throwable) {
        runCatching { resolver.delete(target, null, null) }
        throw error
    }
    values.clear()
    values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(target, values, null, null)
    return target
}

fun copyMediaInto(context: Context, source: Uri, target: Uri) {
    val resolver = context.contentResolver
    val input = resolver.openInputStream(source)
        ?: throw IllegalStateException("This copy is no longer on this device.")
    input.use { from ->
        val output = resolver.openOutputStream(target, "w")
            ?: throw IllegalStateException("The file could not be written.")
        output.use { to -> from.copyTo(to) }
    }
}

fun mediaDisplayNameOf(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
    }
    return uri.lastPathSegment?.takeIf { it.isNotBlank() }
}
