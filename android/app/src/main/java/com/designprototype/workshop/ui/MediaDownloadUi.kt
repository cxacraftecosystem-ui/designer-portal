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
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** A file this phone has just saved for the designer: where it went, so "Open" can show it. */
data class SavedMediaDownload(val uri: Uri, val name: String, val mimeType: String)

/**
 * Saves local media for one surface, and remembers the last save so [MediaDownloadNotice] can offer
 * "Open". Built by [rememberMediaDownloader]; see `data/MediaDownloads.kt` for why every save reads
 * the local copy and never waits on, or touches, the upload.
 */
@Stable
class MediaDownloader internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val createDocument: (String, Uri, String) -> Unit,
) {
    var saved by mutableStateOf<SavedMediaDownload?>(null)
        internal set

    /**
     * Save [source] (a `file://` or `content://` Uri this phone holds) to Downloads.
     *
     * API 29 and up: straight into the public Downloads collection through MediaStore. Below that
     * (minSdk is 26): the system's Create document screen, opened at Downloads with the name filled
     * in, which needs no storage permission and lets the person see where it lands.
     */
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

    /**
     * Save a file whose local bytes are gone but whose upload finished, through the server's own
     * download route. [fetch] does the work (on IO) and answers the saved file's Uri, or null when
     * it landed somewhere no viewer can be pointed at; then there is no "Open", only the sentence.
     */
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

    /** Hand the saved file to whatever app on this phone opens its type. */
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

/**
 * One [MediaDownloader] per surface. It owns its own Create document launcher (used below API 29
 * only), so a row can be dropped anywhere without a host threading one down to it.
 */
@Composable
fun rememberMediaDownloader(): MediaDownloader {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The save waiting on a destination, and the downloader to finish it. A plain holder, not state:
    // nothing draws it, and writing state from composition would schedule another composition.
    val pending = remember { PendingSave() }
    // The generic binary type, so the picker accepts any name; the real type rides on the file.
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

/**
 * The one Download control every upload surface draws: the repo's download icon and the word
 * "Download", as `AndroidUriPreview` has always drawn it, so it reads the same on every row.
 *
 * [unavailableReason] disables it and says why, both on screen and to TalkBack. It is for one case
 * only — the local bytes are genuinely gone — and never for a file that is still uploading.
 *
 * [onTile] draws it as an outlined button in that ink, for a row sitting on a coloured tile (the
 * purple "N files ready" card), where the theme's primary text button all but vanished (release
 * smoke test 2026-10-17, dp/58).
 */
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
            Text("Download", fontSize = 13.sp)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        TextButton(
            onClick = onDownload,
            enabled = unavailableReason == null,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.semantics {
                contentDescription = unavailableReason?.let { "Download $fileName. $it" } ?: "Download $fileName"
            },
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Download", fontSize = 12.sp)
        }
        if (unavailableReason != null) {
            Text(unavailableReason, color = MaterialTheme.field.muted, fontSize = 11.sp)
        }
    }
}

/**
 * "Saved to Downloads" with an Open action, under the row that saved it, for a few seconds.
 *
 * A snackbar drawn in place rather than through a `SnackbarHostState`: the rows that save live in
 * cards, dialogs and a 13,000-line activity, none of which has a host to lend, and threading one in
 * would put a parameter on every screen between a Scaffold and a file row.
 */
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
        Text("Saved to Downloads as ${saved.name}", fontSize = 12.sp, maxLines = 2)
    }
}

/** An outlined button's colours for a coloured tile: the tile's own ink, never the theme primary. */
@Composable
fun onTileButtonColors(ink: Color) = ButtonDefaults.outlinedButtonColors(
    contentColor = ink,
    disabledContentColor = ink.copy(alpha = 0.5f),
)

/** The matching outline: the ink at 70%, which keeps 3:1 against the tile in both themes. */
fun onTileBorder(ink: Color) = BorderStroke(1.dp, ink.copy(alpha = 0.7f))
