package com.designprototype.workshop.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwEncodeResult
import com.designprototype.workshop.data.DwQrEccLevel
import com.designprototype.workshop.data.DwQrEncode
import com.designprototype.workshop.data.DwQrSymbol
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.encodeWorkshopCode
import com.designprototype.workshop.data.formatWorkshopCodeForPrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * The QR code for one record, drawn on the record's own screen, with an expanded view and a file the
 * designer can share.
 *
 * ── WHY IT IS HERE AND NOT ONLY ON A PRINT SHEET ─────────────────────────────────────────────
 *
 * `ui/designworkshop/WorkshopCodesScreen.kt` writes an A4 PDF of thirty cards, which is the right
 * shape on the morning a workshop starts and the wrong one every other day. A designer holding one
 * tool wants the code for THAT tool — to tie to the tool, to send to a colleague, to hand to the
 * officer asking which record a photograph belongs to. That is one code, from the screen the record
 * is already open on, and it must not cost a walk to a printer.
 *
 * ── IT IS GENERATED IN REAL TIME AND NOTHING IS SAVED ────────────────────────────────────────
 *
 * There is no stored PNG, no cached bitmap and no column on the record. The payload is a pure
 * function of the record's type and its id ([encodeWorkshopCode]) and the symbol is a pure function
 * of the payload ([DwQrEncode]), so this recomputes both on every visit. `remember` holds the result
 * only for as long as the screen is composed — a cache against re-composition, never a cache that
 * outlives the screen.
 *
 * IF YOU ARE HERE TO "OPTIMISE" THIS INTO A STORED ASSET, READ THIS FIRST. Encoding a payload and
 * scoring eight masks is under a millisecond on the Galaxy M32 this app is tested on; a stored image
 * costs a column or a file, an invalidation rule, a migration for every record already recorded, and
 * space on a handset that is usually within a few hundred megabytes of full. And it can go stale,
 * which a function cannot: the id a code is built from is immutable, so a cached image can only ever
 * be as correct as this function and never more. There is nothing to win.
 *
 * ── WHAT IT WILL NOT DO ──────────────────────────────────────────────────────────────────────
 *
 * It draws no field of the record except the [title] its caller passes, and never an identity
 * number: [encodeWorkshopCode] refuses anything shaped like an Aadhaar or Pehchan number before this
 * file sees it, and the refusal is drawn as a sentence rather than swallowed. THE SHARED FILE IS
 * NAMED AFTER THE CODE, not the record — a file called `DPW1-A-CMSIK….png` can be sent to anybody
 * and a file called `ram-kumar.png` cannot. The code is the one string here that is opaque by
 * construction.
 *
 * ── WHY IT DOES NOT INVERT IN DARK MODE ──────────────────────────────────────────────────────
 *
 * The symbol is black on white in every theme. A light-on-dark QR is refused outright by a large
 * share of scanners, which look for a dark symbol inside a light quiet zone — so a code that
 * politely followed the theme would be a code that stopped working for exactly the designers who use
 * dark mode in a courtyard. Everything around it is themed as usual.
 */

/**
 * Error correction level Q — a quarter of the symbol recoverable.
 *
 * The level the print sheet uses, for the reason it gives: these end up on paper that spends a
 * fortnight in a workshop. Two surfaces drawing one record at two levels would also produce two
 * different-looking symbols for one record, which is the sort of thing that makes somebody doubt a
 * scan they should have trusted.
 */
private val CODE_ECC = DwQrEccLevel.Q

/** The light border a scanner uses to FIND a symbol. Four modules, as the standard asks. */
private const val QUIET_MODULES = 4

/**
 * Pixels per module in the shared PNG.
 *
 * 12 puts the largest symbol this app draws (version 4: 33 modules plus the quiet zone, 41 units) at
 * 492px, which prints at 26mm — `renderCardSheetPdf`'s box, the size below which a module stops
 * being readable by a handset camera in a courtyard. A PNG carries no physical size of its own, so
 * the only protection against one printed too small is to hand over enough pixels that scaling it
 * down has to be somebody's decision.
 */
private const val PNG_MODULE_PX = 12

/** What one record's code came to: a symbol, or the sentence saying why there is none. */
private sealed interface CodeRender {
    data class Ok(val code: String, val printed: String, val symbol: DwQrSymbol) : CodeRender

    data class Refused(val message: String) : CodeRender
}

private fun renderRecordCode(recordType: DwWorkshopRecordType, id: String?): CodeRender =
    when (val encoded = encodeWorkshopCode(recordType, id)) {
        is DwEncodeResult.Refused -> CodeRender.Refused(encoded.message)
        is DwEncodeResult.Ok -> runCatching {
            CodeRender.Ok(encoded.code, formatWorkshopCodeForPrint(encoded.code), DwQrEncode.encode(encoded.code, CODE_ECC))
        }.getOrElse { CodeRender.Refused(it.message ?: "This code could not be drawn.") }
    }

/** Where a shared code landed on this device. */
private data class SavedCode(val displayLocation: String, val shareUri: android.net.Uri?, val fileName: String)

/**
 * Draw the symbol into a bitmap, write it as a PNG, and publish it.
 *
 * DRAWN FROM THE MODULE MATRIX rather than captured off the screen. A screen capture is at the
 * device's pixel density, over whatever the theme is, with whatever is behind it — and half a pixel
 * of smoothing across a module boundary is what makes a camera hesitate. Filling integer rectangles
 * on a white ground cannot introduce one.
 *
 * WRITE COMPLETELY BEFORE PUBLISHING, and `fd.sync()` before either — the same discipline
 * `WorkshopCodesScreen.writeSheet` and `report/ReportExport` use, for the reason they give: the
 * publish step copies the temp file into a MediaStore row that becomes visible to every app the
 * moment IS_PENDING clears, and a copy whose bytes are still only in the page cache publishes a
 * truncated file that a viewer reports as damaged.
 */
private suspend fun writeCodePng(
    context: Context,
    repository: WorkshopRepository,
    entry: CodeRender.Ok,
): SavedCode = withContext(Dispatchers.IO) {
    // Named after the CODE and nothing else — see the file header. Colons are illegal in a file name
    // on every filesystem this could be copied onto, so they become hyphens.
    val name = "${entry.code.replace(':', '-')}.png"
    val extent = entry.symbol.size + QUIET_MODULES * 2
    val side = extent * PNG_MODULE_PX
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val tmp = File(context.cacheDir, name)
    try {
        val canvas = AndroidCanvas(bitmap)
        // The quiet zone is PAINTED, not left transparent: a transparent PNG dropped on a coloured
        // page or opened in a dark-mode viewer leaves the symbol without the light border a scanner
        // uses to find it at all.
        canvas.drawColor(AndroidColor.WHITE)
        val paint = Paint().apply {
            color = AndroidColor.BLACK
            isAntiAlias = false
        }
        for (row in 0 until entry.symbol.size) {
            for (column in 0 until entry.symbol.size) {
                if (!entry.symbol.matrix[row][column]) continue
                val left = (column + QUIET_MODULES) * PNG_MODULE_PX
                val top = (row + QUIET_MODULES) * PNG_MODULE_PX
                canvas.drawRect(
                    left.toFloat(),
                    top.toFloat(),
                    (left + PNG_MODULE_PX).toFloat(),
                    (top + PNG_MODULE_PX).toFloat(),
                    paint,
                )
            }
        }
        FileOutputStream(tmp).use { fos ->
            val buffered = BufferedOutputStream(fos)
            // Quality is ignored for PNG (it is lossless); the argument is required all the same.
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffered)
            buffered.flush()
            fos.fd.sync()
        }
        val location = repository.persistFileToDownloads(context, tmp, name, "image/png")
        SavedCode(location, repository.shareUriForSavedFile(context, location), name)
    } finally {
        tmp.delete()
        bitmap.recycle()
    }
}

/**
 * A QR symbol, drawn straight onto the canvas from [DwQrEncode]'s module matrix.
 *
 * NO LIBRARY AND NO BITMAP. `drawBehind` runs on the draw pass rather than composition, so a screen
 * holding one repaints rather than recomposes and nothing is allocated per frame.
 *
 * The module size is computed from the box and each run is drawn on absolute boundaries rather than
 * by accumulating a step, so rounding cannot open a hairline between two runs of one row — a seam
 * across a module is exactly the artefact that makes a camera hesitate.
 *
 * `internal` and shared with `ui/designworkshop/WorkshopCodesScreen.kt`: two implementations of
 * "draw this matrix" is two chances to get the quiet zone or the rounding wrong, in one feature a
 * designer moves between within a single workshop.
 */
@Composable
internal fun DwQrSymbolImage(symbol: DwQrSymbol, label: String, modifier: Modifier = Modifier) {
    val extent = symbol.size + QUIET_MODULES * 2
    Box(
        modifier = modifier
            // White, and NOT a theme colour: this is a depiction of paper. See the file header.
            .background(Color.White, RoundedCornerShape(2.dp))
            // The symbol is an image with meaning — a designer using TalkBack needs to know WHICH
            // record this one is, and "QR code" alone would say the same thing on every screen.
            .semantics { contentDescription = label }
            .drawBehind {
                val unit = size.minDimension / extent
                for (row in 0 until symbol.size) {
                    var column = 0
                    while (column < symbol.size) {
                        if (!symbol.matrix[row][column]) {
                            column++
                            continue
                        }
                        var run = 1
                        while (column + run < symbol.size && symbol.matrix[row][column + run]) run++
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset((column + QUIET_MODULES) * unit, (row + QUIET_MODULES) * unit),
                            size = Size(run * unit, unit),
                        )
                        column += run
                    }
                }
            }
    )
}

/**
 * The record's code, on the record.
 *
 * [title] is only ever used to NAME the symbol for a screen reader and to head the expanded view. It
 * is never encoded and never put in a file name — see the file header.
 *
 * SELF-CONTAINED ON PURPOSE: it reports where a shared file landed inside its own card rather than
 * through a host callback, so it can be dropped onto any record screen without that screen having to
 * grow a message channel for it. A snackbar would be the wrong home anyway — "Saved to Downloads/…"
 * is a fact a designer comes back to when they go looking for the file.
 */
@Composable
fun RecordCodeSection(
    repository: WorkshopRepository,
    recordType: DwWorkshopRecordType,
    recordId: String?,
    title: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val render = remember(recordType, recordId) { renderRecordCode(recordType, recordId) }
    var expanded by remember(recordType, recordId) { mutableStateOf(false) }
    var sharing by remember(recordType, recordId) { mutableStateOf(false) }
    var saved by remember(recordType, recordId) { mutableStateOf<SavedCode?>(null) }
    var problem by remember(recordType, recordId) { mutableStateOf<String?>(null) }

    val heading = title?.takeIf { it.isNotBlank() } ?: recordType.label
    val symbolLabel = "Code for $heading"

    fun share() {
        val entry = render as? CodeRender.Ok ?: return
        sharing = true
        problem = null
        scope.launch {
            runCatching { writeCodePng(context, repository, entry) }
                .onSuccess { saved = it }
                .onFailure { problem = it.message ?: "The code could not be saved as a picture." }
            sharing = false
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.surface50),
        border = BorderStroke(1.dp, MaterialTheme.field.hairline),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.QrCode2,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "${recordType.label} code",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Scan this to open this ${recordType.label.lowercase()} from any device running the app. It holds a " +
                    "reference and a check digit and nothing about the record itself — no name, no place, no identity " +
                    "number. It is drawn fresh every time this screen opens and is not stored anywhere.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            when (render) {
                is CodeRender.Refused -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.WarningAmber,
                        // Decorative: the sentence beside it carries the meaning, so the refusal
                        // survives greyscale and a screen reader that never sees the icon.
                        contentDescription = null,
                        tint = MaterialTheme.field.onWarningContainer,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        render.message,
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }

                is CodeRender.Ok -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        DwQrSymbolImage(render.symbol, symbolLabel, Modifier.size(104.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                render.printed,
                                color = MaterialTheme.field.body,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                // Monospace, because this string is read aloud and typed back: the
                                // grouping is only legible if the groups line up.
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                "The last four characters are a check. Typed into a lookup, they are what stops one " +
                                    "wrong character opening a different record.",
                                color = MaterialTheme.field.muted,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            // The 48dp floor this app applies wherever a control was thought about —
                            // see ISLAND_TOUCH_TARGET in ui/AppNavigation.kt.
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.OpenInFull, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Expand")
                        }
                        OutlinedButton(
                            onClick = { share() },
                            enabled = !sharing,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (sharing) "Saving…" else "Share (.png)")
                        }
                    }

                    if (sharing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text("Drawing the code on this device…", color = MaterialTheme.field.muted, fontSize = 12.sp)
                        }
                    }

                    saved?.let { file -> SavedCodeCard(file) }
                }
            }

            problem?.let {
                Text(it, color = MaterialTheme.field.warning, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }

    val ok = render as? CodeRender.Ok
    if (expanded && ok != null) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = { share() }, enabled = !sharing) { Text("Share (.png)") }
            },
            title = { Text("${recordType.label} code") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(heading, color = MaterialTheme.field.body, fontSize = 13.sp)
                    // A white plate under the symbol rather than the dialog's own surface: in dark
                    // mode the dialog is dark, and a dark quiet zone is a symbol most scanners never
                    // find. See the file header.
                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    ) {
                        DwQrSymbolImage(ok.symbol, symbolLabel, Modifier.size(240.dp))
                    }
                    Text(
                        ok.printed,
                        color = MaterialTheme.field.body,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Hold a camera over it, or type the characters into a lookup. Spaces and capitals do not matter.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            },
        )
    }
}

/**
 * Where the shared file landed, and the one control that sends it on.
 *
 * The same two-branch answer `WorkshopCodesScreen.DwSheetSavedCard` gives, for the same measured
 * reason: on Android 10 and above the file lands in the public Downloads collection, which every app
 * can already open, so there is no Uri to grant and nothing to share FROM this app — and saying
 * where it is beats offering a button that would have to re-copy the file to work.
 */
@Composable
private fun SavedCodeCard(file: SavedCode) {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text("Saved", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(file.displayLocation, color = MaterialTheme.field.body, fontSize = 11.sp)
        if (file.shareUri != null) {
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, file.shareUri)
                        // Without this the receiving app gets a Uri it has no permission to read, and
                        // the share silently produces an empty attachment.
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share the code"))
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
        } else {
            Text(
                "Open it from the Downloads folder, or attach it from any app's file picker.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
            )
        }
    }
}
