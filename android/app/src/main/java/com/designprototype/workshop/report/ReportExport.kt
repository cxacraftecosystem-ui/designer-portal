package com.designprototype.workshop.report

import android.content.Context
import android.net.Uri
import com.designprototype.workshop.data.WorkshopRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest

object ReportExport {

    data class Result(
        val displayLocation: String,
        val shareUri: Uri?,
        val fileName: String,
        val mimeType: String,
        val droppedImages: List<String>,
        val sizeBytes: Long? = null,
        val checksumSha256: String? = null,
    )

    suspend fun exportDocx(
        context: Context,
        repository: WorkshopRepository,
        document: ReportDocument,
        loadImage: ImageLoader,
        fileName: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        installReportBoundaryAssets(context)
        publish(
            context = context,
            repository = repository,
            name = fileName ?: defaultName(document, "docx"),
            mimeType = DOCX_MIME,
        ) { out -> renderDocx(document, loadImage, out) }
    }

    suspend fun exportPdf(
        context: Context,
        repository: WorkshopRepository,
        document: ReportDocument,
        loadImage: ImageLoader,
        fileName: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        installReportBoundaryAssets(context)
        publish(
            context = context,
            repository = repository,
            name = fileName ?: defaultName(document, "pdf"),
            mimeType = PDF_MIME,
        ) { out -> renderPdf(document, loadImage, out) }
    }

    private fun publish(
        context: Context,
        repository: WorkshopRepository,
        name: String,
        mimeType: String,
        render: (OutputStream) -> List<String>,
    ): Result {
        val tmp = File(context.cacheDir, name)
        try {
            val dropped = FileOutputStream(tmp).use { fos ->
                val buffered = BufferedOutputStream(fos)
                val result = render(buffered)
                buffered.flush()
                fos.fd.sync()
                result
            }
            val sizeBytes = tmp.length().takeIf { it > 0L }
            val checksum = sha256Of(tmp)
            val location = repository.persistFileToDownloads(context, tmp, name, mimeType)
            return Result(
                displayLocation = location,
                shareUri = repository.shareUriForSavedFile(context, location),
                fileName = name,
                mimeType = mimeType,
                droppedImages = dropped,
                sizeBytes = sizeBytes,
                checksumSha256 = checksum,
            )
        } finally {
            tmp.delete()
        }
    }

    private fun sha256Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

    private fun defaultName(document: ReportDocument, ext: String): String =
        com.designprototype.workshop.data.Naming.exportFilename(
            "Workshop", "Report", ext, subject = document.meta.title.ifBlank { null },
        )
}
