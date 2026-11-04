package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.designprototype.workshop.data.WorkshopRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

private const val DW_TRACE_EXPORT_CACHE_DIR = "dw-trace-export"

data class DwTraceExportSaved(
    val savedTo: String,
    val shareUri: Uri?,
    val storedName: String,
    val mime: String,
    val sizeBytes: Long,
)

suspend fun dwSaveTraceExport(
    context: Context,
    repository: WorkshopRepository,
    bytes: ByteArray,
    fileName: String,
    mime: String,
): DwTraceExportSaved = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val staging = File(appContext.cacheDir, DW_TRACE_EXPORT_CACHE_DIR).apply { mkdirs() }
    val tmp = File(staging, fileName)
    try {
        FileOutputStream(tmp).use { fos ->
            val buffered = BufferedOutputStream(fos)
            buffered.write(bytes)
            buffered.flush()
            fos.fd.sync()
        }
        val savedTo = repository.persistFileToDownloads(appContext, tmp, fileName, mime)
        DwTraceExportSaved(
            savedTo = savedTo,
            shareUri = repository.shareUriForSavedFile(appContext, savedTo),
            storedName = savedTo.substringAfterLast('/').ifBlank { fileName },
            mime = mime,
            sizeBytes = bytes.size.toLong(),
        )
    } finally {
        runCatching { tmp.delete() }
    }
}

fun dwTraceExportShareIntent(saved: DwTraceExportSaved): Intent? {
    val uri = saved.shareUri ?: return null
    return Intent(Intent.ACTION_SEND).apply {
        type = saved.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, saved.storedName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

const val DW_TRACE_EXPORT_NO_SHARE_SENTENCE: String =
    "Open it from the Downloads folder to send it — any app's file picker or share sheet will find " +
        "it there."

const val DW_TRACE_EXPORT_SHARE_CAVEAT: String =
    "Pick nearby share, Bluetooth or a cable if you have no signal. A chat app will look like it " +
        "worked and then send nothing until you are back online — the share sheet does not tell this " +
        "app which one you chose, so it cannot warn you afterwards."
