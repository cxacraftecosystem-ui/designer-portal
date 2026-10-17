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
 * The action buttons on a media card, tile or row — Retry and Download — drawn ONE way on every
 * surface (upload queue, outbox, record forms, stage attachments, media screens). The web's twin is
 * `MediaActionButton` (frontend/components/media/MediaActionButton.tsx).
 *
 * WHY (owner, 2026-10-17: "the buttons for retry and download on media card are not clearly visible").
 * They were 12sp TextButtons with 2dp of vertical padding: a purple word on a white card, easy to miss
 * and well under a thumb's 48dp. Now each is a [FilledTonalButton] with its OWN pale-purple ground, a
 * 1dp purple edge and deep-purple ink ([MediaActionColors]), carrying the icon AND the word, at least
 * 48dp tall — the same look as the web twin (purple-50 ground, purple border, purple-800 ink).
 *
 * It does NOT use the theme's secondaryContainer: this app maps that slot to surface-100, which is
 * about 1.05:1 against the white card in light and 1.13:1 against the dark card, so the button's
 * ground and edge vanished and it read as bold text. [MediaActionColorsTest] pins the edge at 3:1 or
 * better against the card and surface-50, and the ink at 4.5:1 or better on the ground, in both themes.
 */
@Composable
fun MediaActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The full name for TalkBack — "Download interview-3.m4a". */
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
        Text(label, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

/**
 * The media action button's colours, from the purple ramp, per theme. Plain values (no Compose
 * lookup) so a JVM unit test can measure every pair.
 *
 * Light: purple-50 ground, purple-600 edge (5.2:1 on the white card, 4.9:1 on surface-50),
 * purple-800 ink (9.4:1 on the ground). Dark: purple-950 ground, purple-500 edge (5.0:1 on the
 * dark card, 4.7:1 on surface-50), purple-200 ink (12:1 on the ground). Disabled keeps a visible
 * edge (line-200 is too faint to say "a button is here") but drops the ink to the placeholder rung.
 */
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

/** "Retry" on a failed upload — the same button as Download, so the two read as one pair. */
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

/**
 * The one Download control every upload surface draws — [MediaActionButton] with the download icon
 * and the word "Download", so it reads the same on every row.
 *
 * [unavailableReason] disables it and says why, both on screen (under the button, never beside it,
 * so a long sentence cannot squeeze the button) and to TalkBack. It is for one case only — the local
 * bytes are genuinely gone — and never for a file that is still uploading.
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
    Column(modifier = modifier) {
        MediaActionButton(
            label = "Download",
            icon = Icons.Filled.Download,
            onClick = onDownload,
            enabled = unavailableReason == null,
            accessibleLabel = unavailableReason?.let { "Download $fileName. $it" } ?: "Download $fileName",
        )
        if (unavailableReason != null) {
            Text(unavailableReason, color = MaterialTheme.field.muted, fontSize = 12.sp)
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

/**
 * What the purple "N file(s) attached" card says under its title, and whether it still draws a bar.
 *
 * Once every file that has not uploaded has FAILED, nothing is uploading any more, so "Uploading… 0%
 * (0/1 files done)" beside "Upload failed — tap Retry…" was two contradicting lines (release smoke
 * 2026-10-17, dp-final/26 and 47). The bar and the percentage go, and the line says how many files
 * did not upload; each file's own row still offers Retry and Download.
 */
data class AttachedUploadsStatus(val line: String, val showProgress: Boolean, val failed: Boolean)

fun attachedUploadsStatus(total: Int, done: Int, failed: Int, fraction: Float): AttachedUploadsStatus = when {
    done >= total -> AttachedUploadsStatus("All uploaded ✓ — ready to save", showProgress = false, failed = false)
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
