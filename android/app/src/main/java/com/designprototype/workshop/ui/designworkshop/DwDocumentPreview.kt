package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.UUID

private sealed interface DocState {
    val onDevice: File?

    data object Loading : DocState {
        override val onDevice: File? get() = null
    }

    data class Page(
        val bitmap: Bitmap,
        val pages: Int,
        override val onDevice: File,
    ) : DocState

    data class Picture(val model: Any) : DocState {
        override val onDevice: File? get() = model as? File
    }

    data class NotDrawn(
        val reason: String,
        override val onDevice: File? = null,
    ) : DocState

    data class Model(
        val format: String,
        override val onDevice: File? = null,
    ) : DocState
}

internal sealed interface DwOpenTarget {
    data class OnDevice(val file: File) : DwOpenTarget

    data class Remote(val url: String) : DwOpenTarget
}

internal fun dwOpenTarget(justPicked: File?, onDevice: File?, remoteUrl: String?): DwOpenTarget? {
    val here = justPicked ?: onDevice
    if (here != null) return DwOpenTarget.OnDevice(here)
    return remoteUrl?.let { DwOpenTarget.Remote(it) }
}

internal fun dwWithheldFileNote(noun: String): String =
    "$noun is stored, but this account may not open the file itself."

internal fun dwUndrawnDocumentNote(noun: String, openableHere: Boolean): String =
    if (openableHere) {
        "Stored and downloadable. A PDF or a 3D model is shown inside the app; this one opens " +
            "in whatever program handles it on your device."
    } else {
        dwWithheldFileNote(noun)
    }

private fun looksLikePdf(name: String?, mimeType: String?): Boolean {
    val mime = mimeType?.trim()?.lowercase().orEmpty()
    if (mime == "application/pdf") return true
    if (mime.isNotEmpty() && mime != "application/octet-stream") return false
    return name?.trim()?.lowercase()?.endsWith(".pdf") == true
}

private fun looksLikeImage(name: String?, mimeType: String?): Boolean {
    if (mimeType?.startsWith("image/") == true) return true
    val lower = name?.trim()?.lowercase() ?: return false
    return listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".gif").any { lower.endsWith(it) }
}

private suspend fun renderFirstPage(file: File, widthPx: Int): DocState = withContext(Dispatchers.IO) {
    runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount < 1) {
                    return@use DocState.NotDrawn("This PDF has no pages.", onDevice = file)
                }
                renderer.openPage(0).use { page ->
                    val scale = widthPx.toFloat() / page.width.toFloat()
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    DocState.Page(bitmap, renderer.pageCount, onDevice = file)
                }
            }
        }
    }.getOrElse {
        DocState.NotDrawn("Can't preview here. Open it to read.", onDevice = file)
    }
}

private fun docPreviewDir(context: Context): File =
    File(context.cacheDir, "dw-doc-preview").apply { mkdirs() }

internal fun dwDocCacheName(mediaId: String): String {
    val safe = mediaId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.takeLast(60)
    return "${mediaId.hashCode().toUInt()}-${safe.ifEmpty { "id" }}"
}

internal const val DW_DOC_PREVIEW_CACHE_BYTES = 24L * 1024L * 1024L

internal const val DW_DOC_PART_SUFFIX = ".part"

internal const val DW_DOC_PART_STALE_MILLIS = 15L * 60L * 1000L

internal fun dwTrimDocPreviewCache(keep: File) {
    val everything = keep.parentFile?.listFiles()?.filter { it.isFile }.orEmpty()
    val now = System.currentTimeMillis()

    val cached = ArrayList<File>(everything.size)
    for (file in everything) {
        if (!file.name.endsWith(DW_DOC_PART_SUFFIX)) {
            cached += file
            continue
        }
        if (now - file.lastModified() > DW_DOC_PART_STALE_MILLIS) file.delete()
    }

    var total = cached.sumOf { it.length() }
    if (total <= DW_DOC_PREVIEW_CACHE_BYTES) return
    val candidates = cached
        .filter { it != keep }
        .sortedBy { it.lastModified() }
    for (file in candidates) {
        if (total <= DW_DOC_PREVIEW_CACHE_BYTES) return
        val size = file.length()
        if (file.delete()) total -= size
    }
}

private suspend fun cacheRemote(context: Context, mediaId: String, url: String): File? =
    withContext(Dispatchers.IO) {
        runCatching {
            val target = docPreviewDir(context).resolve(dwDocCacheName(mediaId))
            if (target.exists() && target.length() > 0L) return@runCatching target
            val partial = File(target.parentFile, "${target.name}.${UUID.randomUUID()}$DW_DOC_PART_SUFFIX")
            try {
                URL(url).openStream().use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                if (!partial.renameTo(target)) error("could not move the fetched document into place")
            } catch (failure: Throwable) {
                partial.delete()
                throw failure
            }
            dwTrimDocPreviewCache(target)
            target
        }.getOrNull()
    }

@Composable
internal fun DwDocumentPreview(
    mediaId: String,
    noun: String,
    localFile: File?,
    remoteUrl: String?,
    displayName: String?,
    mimeType: String? = null,
) {
    val context = LocalContext.current
    var state by remember(mediaId, localFile, remoteUrl) { mutableStateOf<DocState>(DocState.Loading) }
    var openFailed by remember(mediaId, localFile, remoteUrl) { mutableStateOf(false) }

    LaunchedEffect(mediaId, localFile, remoteUrl, displayName, mimeType) {
        val name = displayName ?: localFile?.name
        when {
            mediaId.isBlank() && localFile == null -> state = DocState.NotDrawn("No $noun on file.")

            looksLikeImage(name, mimeType) -> {
                val model: Any? = localFile ?: remoteUrl
                state = if (model != null) DocState.Picture(model)
                else DocState.NotDrawn(dwWithheldFileNote(noun))
            }

            dwModelFormatOf(name, mimeType) != null ->
                state = DocState.Model(dwModelFormatOf(name, mimeType)!!, onDevice = localFile)

            looksLikePdf(name, mimeType) -> {
                val file = localFile ?: remoteUrl?.let { cacheRemote(context, mediaId, it) }
                state = when {
                    file != null -> renderFirstPage(file, widthPx = 900)
                    remoteUrl == null ->
                        DocState.NotDrawn(dwWithheldFileNote(noun))
                    else -> DocState.NotDrawn("Offline — can't load the $noun.")
                }
            }

            else -> state = DocState.NotDrawn(
                reason = dwUndrawnDocumentNote(
                    noun = noun,
                    openableHere = localFile != null || remoteUrl != null,
                ),
                onDevice = localFile,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        val model = state as? DocState.Model
        if (model != null) {
            DwModelViewer(
                format = model.format,
                displayName = displayName ?: localFile?.name ?: noun,
                localFile = localFile,
                fetch = remoteUrl?.let { url -> suspend { cacheRemote(context, mediaId, url) } },
            )
        } else Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
        ) {
            when (val shown = state) {
                is DocState.Loading -> FieldCircularProgressIndicator(modifier = Modifier.size(22.dp))
                is DocState.Page -> Image(
                    painter = BitmapPainter(shown.bitmap.asImageBitmap()),
                    contentDescription = "$noun, first page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
                is DocState.Picture -> AsyncImage(
                    model = shown.model,
                    contentDescription = noun,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
                is DocState.Model -> Unit
                is DocState.NotDrawn -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.field.muted,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(shown.reason, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (mediaId.isNotBlank() || localFile != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    buildString {
                        append(displayName ?: localFile?.name ?: noun)
                        (state as? DocState.Page)?.let { append(" · ${it.pages} page${if (it.pages == 1) "" else "s"}") }
                    },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                val target = dwOpenTarget(
                    justPicked = localFile,
                    onDevice = state.onDevice,
                    remoteUrl = remoteUrl,
                )
                if (target != null) {
                    OutlinedButton(onClick = {
                        val uri = when (target) {
                            is DwOpenTarget.OnDevice -> FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                target.file,
                            )
                            is DwOpenTarget.Remote -> Uri.parse(target.url)
                        }
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, if (looksLikePdf(displayName, mimeType)) "application/pdf" else "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .onSuccess { openFailed = false }
                            .onFailure { openFailed = true }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Text("Open", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            Box(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                }
            ) {
                if (openFailed) {
                    Text(
                        "No app can open this file. Install a document viewer.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}
