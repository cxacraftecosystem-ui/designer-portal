package com.designprototype.workshop.ui

import com.designprototype.workshop.data.userMessage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.MediaDownloads
import com.designprototype.workshop.data.copyMediaInto
import com.designprototype.workshop.data.saveToDownloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SavedMediaDownload(val uri: Uri, val name: String, val mimeType: String)

@Stable
class MediaDownloader internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val createDocument: (String, Uri, String) -> Unit,
) {
    var saved by mutableStateOf<SavedMediaDownload?>(null)
        internal set

    fun save(source: Uri, displayName: String?, mimeType: String?, capturedAtMillis: Long? = null) {
        val mime = MediaDownloads.baseMimeType(mimeType).ifBlank { "application/octet-stream" }
        val name = MediaDownloads.downloadFileName(displayName, mime, capturedAtMillis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { saveToDownloads(context, source, name, mime) } }
                    .onSuccess { saved = SavedMediaDownload(it, name, mime) }
                    .onFailure { failed(it) }
            }
        } else {
            createDocument(name, source, mime)
        }
    }

    fun saveFromServer(displayName: String, mimeType: String?, fetch: suspend () -> Uri?) {
        val mime = MediaDownloads.baseMimeType(mimeType).ifBlank { "application/octet-stream" }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { fetch() } }
                .onSuccess { uri ->
                    if (uri != null) {
                        saved = SavedMediaDownload(uri, displayName, mime)
                    } else {
                        Toast.makeText(context, "Saved to Downloads as $displayName", Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure { failed(it) }
        }
    }

    internal fun copyChosen(source: Uri, target: Uri, name: String, mime: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { copyMediaInto(context, source, target) } }
                .onSuccess { saved = SavedMediaDownload(target, name, mime) }
                .onFailure { failed(it) }
        }
    }

    private fun failed(error: Throwable) {
        Toast.makeText(
            context,
            "This file couldn't be saved. ${error.userMessage("Try again.")}",
            Toast.LENGTH_LONG,
        ).show()
    }

    fun open(download: SavedMediaDownload) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(download.uri, download.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone opens this kind of file.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun rememberMediaDownloader(): MediaDownloader {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { PendingSave() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { target ->
        val request = pending.request
        pending.request = null
        if (target != null && request != null) {
            pending.downloader?.copyChosen(request.source, target, request.name, request.mime)
        }
    }
    return remember(context, scope) {
        MediaDownloader(context.applicationContext, scope) { name, source, mime ->
            pending.request = PendingSave.Request(source, name, mime)
            launcher.launch(name)
        }.also { pending.downloader = it }
    }
}

private class PendingSave {
    data class Request(val source: Uri, val name: String, val mime: String)

    var request: Request? = null
    var downloader: MediaDownloader? = null
}

@Composable
fun MediaActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibleLabel: String? = null,
) {
    val colors = MediaActionColors.forTheme(MaterialTheme.field.isDark)
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.ground,
            contentColor = colors.ink,
            disabledContainerColor = colors.disabledGround,
            disabledContentColor = colors.disabledInk,
        ),
        border = BorderStroke(1.dp, if (enabled) colors.edge else colors.disabledEdge),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { accessibleLabel?.let { contentDescription = it } },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

@Immutable
data class MediaActionColors(
    val ground: Color,
    val edge: Color,
    val ink: Color,
    val disabledGround: Color,
    val disabledEdge: Color,
    val disabledInk: Color,
) {
    companion object {
        val Light = MediaActionColors(
            ground = FieldPalette.Purple50,
            edge = FieldPalette.Purple600,
            ink = FieldPalette.Purple800,
            disabledGround = FieldPalette.Surface100Light,
            disabledEdge = FieldPalette.Ink300Light,
            disabledInk = FieldPalette.Ink500Light,
        )
        val Dark = MediaActionColors(
            ground = FieldPalette.Purple950,
            edge = FieldPalette.Purple500,
            ink = FieldPalette.Purple200,
            disabledGround = FieldPalette.Surface100Dark,
            disabledEdge = FieldPalette.Ink300Dark,
            disabledInk = FieldPalette.Ink500Dark,
        )

        fun forTheme(isDark: Boolean): MediaActionColors = if (isDark) Dark else Light
    }
}

@Composable
fun MediaRetryButton(fileName: String?, onRetry: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    MediaActionButton(
        label = "Retry",
        icon = Icons.Filled.Refresh,
        onClick = onRetry,
        enabled = enabled,
        accessibleLabel = fileName?.takeIf { it.isNotBlank() }?.let { "Retry $it" },
        modifier = modifier,
    )
}

@Composable
fun MediaDownloadButton(
    fileName: String,
    onDownload: () -> Unit,
    unavailableReason: String? = null,
    modifier: Modifier = Modifier,
    onTile: Color? = null,
) {
    if (onTile != null) {
        OutlinedButton(
            onClick = onDownload,
            enabled = unavailableReason == null,
            colors = onTileButtonColors(onTile),
            border = onTileBorder(onTile),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = modifier.semantics { contentDescription = "Download $fileName" },
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Download", style = MaterialTheme.typography.labelLarge)
        }
        return
    }
    Column(modifier = modifier) {
        MediaActionButton(
            label = "Download",
            icon = Icons.Filled.Download,
            onClick = onDownload,
            enabled = unavailableReason == null,
            accessibleLabel = unavailableReason?.let { "Download $fileName. $it" } ?: "Download $fileName",
        )
        if (unavailableReason != null) {
            Text(unavailableReason, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MediaDownloadNotice(downloader: MediaDownloader, modifier: Modifier = Modifier) {
    val saved = downloader.saved ?: return
    LaunchedEffect(saved) {
        delay(6_000)
        if (downloader.saved == saved) downloader.saved = null
    }
    Snackbar(
        modifier = modifier,
        action = {
            TextButton(onClick = { downloader.open(saved) }) { Text("Open") }
        },
        dismissAction = {
            TextButton(onClick = { downloader.saved = null }) { Text("Dismiss") }
        },
    ) {
        Text("Saved to Downloads as ${saved.name}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

@Composable
fun onTileButtonColors(ink: Color) = ButtonDefaults.outlinedButtonColors(
    contentColor = ink,
    disabledContentColor = ink.copy(alpha = 0.5f),
)

fun onTileBorder(ink: Color) = BorderStroke(1.dp, ink.copy(alpha = 0.7f))

data class AttachedUploadsStatus(val line: String, val showProgress: Boolean, val failed: Boolean, val done: Boolean = false)

@Composable
fun AttachedUploadsLine(status: AttachedUploadsStatus, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status.done) {
            com.designprototype.workshop.explorer.ui.ExplorerMarkIcon(com.designprototype.workshop.explorer.ui.ExplorerMarkKind.SAVED, color, 14.dp)
            Spacer(Modifier.width(4.dp))
        }
        Text(status.line, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

fun attachedUploadsStatus(total: Int, done: Int, failed: Int, fraction: Float): AttachedUploadsStatus = when {
    done >= total -> AttachedUploadsStatus("All uploaded — ready to save", showProgress = false, failed = false, done = true)
    failed > 0 && done + failed >= total -> AttachedUploadsStatus(
        if (failed == 1) "1 file didn't upload." else "$failed files didn't upload.",
        showProgress = false,
        failed = true,
    )
    else -> AttachedUploadsStatus(
        "Uploading… ${(fraction * 100).toInt()}% ($done/$total files done)",
        showProgress = true,
        failed = false,
    )
}
