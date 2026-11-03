package com.designprototype.workshop.ui

import com.designprototype.workshop.data.userMessage

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import com.designprototype.workshop.data.DW_ARTISAN_CARD_UNSYNCED
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import com.designprototype.workshop.data.formatWorkshopCodeForPrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

private val CODE_ECC = DwQrEccLevel.Q

private const val QUIET_MODULES = 4

private const val PNG_MODULE_PX = 12

private sealed interface CodeRender {
    data class Ok(val code: String, val printed: String, val symbol: DwQrSymbol) : CodeRender

    data class Refused(val message: String) : CodeRender
}

private fun renderRecordCode(recordType: DwWorkshopRecordType, id: String?): CodeRender =
    when (val encoded = encodeWorkshopCode(recordType, id)) {
        is DwEncodeResult.Refused -> CodeRender.Refused(encoded.message)
        is DwEncodeResult.Ok -> runCatching {
            CodeRender.Ok(encoded.code, formatWorkshopCodeForPrint(encoded.code), DwQrEncode.encode(encoded.code, CODE_ECC))
        }.getOrElse { CodeRender.Refused(it.userMessage("This code could not be drawn.")) }
    }

private fun renderCardToken(token: String, shortCode: String): CodeRender =
    runCatching { CodeRender.Ok(token, shortCode, DwQrEncode.encode(token, CODE_ECC)) as CodeRender }
        .getOrElse { CodeRender.Refused(it.userMessage("This code could not be drawn.")) }

private data class SavedCode(val displayLocation: String, val shareUri: android.net.Uri?, val fileName: String)

private suspend fun writeCodePng(
    context: Context,
    repository: WorkshopRepository,
    entry: CodeRender.Ok,
): SavedCode = withContext(Dispatchers.IO) {
    val name = com.designprototype.workshop.data.Naming.fileName(
        entity = "Workshop", subject = entry.code, what = "Record-Code", ext = "png",
    )
    val extent = entry.symbol.size + QUIET_MODULES * 2
    val side = extent * PNG_MODULE_PX
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val tmp = File(context.cacheDir, name)
    try {
        val canvas = AndroidCanvas(bitmap)
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

@Composable
internal fun DwQrSymbolImage(symbol: DwQrSymbol, label: String, modifier: Modifier = Modifier) {
    val extent = symbol.size + QUIET_MODULES * 2
    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(2.dp))
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

@Composable
fun RecordCodeSection(
    repository: WorkshopRepository,
    recordType: DwWorkshopRecordType,
    recordId: String?,
    title: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isArtisan = recordType == DwWorkshopRecordType.ARTISAN
    var artisanRender by remember(recordType, recordId) { mutableStateOf<CodeRender?>(null) }
    if (isArtisan) {
        LaunchedEffect(recordId) {
            artisanRender = if (recordId.isNullOrBlank()) {
                CodeRender.Refused(DW_ARTISAN_CARD_UNSYNCED)
            } else {
                try {
                    val card = repository.artisanCard(recordId)
                    renderCardToken(card.token, card.shortCode)
                } catch (e: HttpException) {
                    if (e.code() == 404) renderRecordCode(recordType, recordId)
                    else CodeRender.Refused("The card couldn't be loaded. Try again.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CodeRender.Refused("Offline. The card shows once you're online.")
                }
            }
        }
    }
    val render = if (isArtisan) {
        artisanRender ?: CodeRender.Refused("Loading…")
    } else {
        remember(recordType, recordId) { renderRecordCode(recordType, recordId) }
    }
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
                .onFailure { problem = it.userMessage("The code could not be saved as a picture.") }
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
                    if (isArtisan) "Artisan card" else "${recordType.label} code",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Scan to open this ${recordType.label.lowercase()} on any device. Contains no personal details.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
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
                        contentDescription = null,
                        tint = MaterialTheme.field.onWarningContainer,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        render.message,
                        color = MaterialTheme.field.onWarningContainer,
                        style = MaterialTheme.typography.bodySmall,
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
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 15.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                "The last four characters are a check.",
                                color = MaterialTheme.field.muted,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 14.sp,
                            )
                        }
                    }

                    ActionRow {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.OpenInFull, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Expand")
                        }
                        OutlinedButton(
                            onClick = { share() },
                            enabled = !sharing,
                            modifier = Modifier.heightIn(min = 48.dp),
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
                            FieldCircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text("Drawing the code on this device…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    saved?.let { file -> SavedCodeCard(file) }
                }
            }

            problem?.let {
                Text(it, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
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
                    Text(heading, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
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
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Scan it, or type the characters. Spaces and capitals don't matter.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp,
                    )
                }
            },
        )
    }
}

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
        Text("Saved", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(file.displayLocation, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
        if (file.shareUri != null) {
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, file.shareUri)
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
                "Find it in Downloads.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
