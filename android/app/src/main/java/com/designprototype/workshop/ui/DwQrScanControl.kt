package com.designprototype.workshop.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwQrReadResult
import com.designprototype.workshop.data.DwQrSource
import com.designprototype.workshop.data.dwReadQrPicture
// REUSED, NOT REIMPLEMENTED. `dwCaptureUri` is the FileProvider handshake that stops a `file://` Uri
// throwing FileUriExposedException the moment it crosses to the camera app, and `hasPermission` is
// the check every other camera path in this app makes. A second copy of either is a second copy to
// get wrong on the one handset nobody tested.
import com.designprototype.workshop.ui.designworkshop.dwCaptureUri
import com.designprototype.workshop.ui.designworkshop.hasPermission
import kotlinx.coroutines.launch
import java.io.File

/**
 * Scan a QR code, or read one out of a picture the designer already has. ONE control, both surfaces.
 *
 * ── WHY ONE CONTROL AND NOT TWO ───────────────────────────────────────────────────────────────
 *
 * There are two places a code is read back — the workshop's Cards & tags screen and the record-code
 * panel on Search — and the web made the same call for the same reason: two copies of a scanner
 * drift, and the half that drifts is always the refusal wording. Here they would drift on the one
 * sentence that matters, the one that tells a designer whether the card is bad or the picture is.
 * Everything below is offered to both, identically, and the caller supplies only what to do with the
 * text.
 *
 * ── BOTH DOORS ARE ALWAYS OPEN, WHICH IS THE POINT OF THE FEATURE ─────────────────────────────
 *
 * The camera and the picture picker are offered unconditionally. Neither is a fallback for the
 * other, because they answer different situations: the camera is for a card in front of you, and
 * the picker is for a code you were SENT — a screenshot on WhatsApp, a photograph of a tag taken
 * last week, a card sheet printed in an office two districts away. In the second case there is no
 * card to point a camera at, which is exactly why a typed box does not cover it either.
 *
 * THE TYPED BOX IS NOT REMOVED FROM EITHER SURFACE. `docs/DECISION-qr-scanning-on-android.md` names
 * it as the guaranteed path, and it stays that: it needs no permission, no lens and no library, and
 * it is the only route that works when a card's QR is smudged but the characters under it are not.
 * A surface that ever hides it invalidates that decision rather than merely degrading it.
 *
 * ── THE PHOTOGRAPH IS DELETED, ALWAYS ─────────────────────────────────────────────────────────
 *
 * A scan writes a JPEG into a scratch directory, decodes it and deletes it — and the directory is
 * swept on mount and on dispose, because the per-scan delete does not run if the process is killed
 * between the shutter and the decode. The same discipline `DwIdentityCardControl` applies to a card
 * photograph, for a weaker but real reason: nobody asked to keep a picture of a tag, and a field
 * handset that quietly accumulates one per scan is one whose storage fills for no purpose.
 *
 * A PICKED picture is never copied at all — its bytes are read straight from the content Uri.
 */

/** The scratch directory a scanned photograph lives in for the second it exists. */
private fun qrScratchDir(context: Context): File =
    File(context.filesDir, "qr-scratch").apply { mkdirs() }

/** Delete everything left behind by a scan the process did not live to finish. */
private fun sweepQrScratch(context: Context) {
    runCatching { qrScratchDir(context).listFiles()?.forEach { it.delete() } }
}

/**
 * The two buttons and the sentence under them.
 *
 * [onText] receives the RAW decoded payload and nothing else. This control deliberately does not
 * know what a workshop code looks like: the grammar, the version gate and the check digit live in
 * `DwWorkshopCodes.decodeWorkshopCode`, which is the same parser the typed box uses. A second
 * opinion here is how a scanned code and a typed one come to be judged differently.
 *
 * [onRefusal] receives a sentence already written for the person reading it — "no code in that
 * picture", "this phone cannot open that file" — and must show it as given.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DwQrScanControl(
    enabled: Boolean,
    onText: (String) -> Unit,
    onRefusal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var working by remember { mutableStateOf(false) }
    /** The scratch file the camera is writing into, so it can be deleted whatever happens next. */
    var pending by remember { mutableStateOf<File?>(null) }

    DisposableEffect(Unit) {
        sweepQrScratch(context)
        onDispose { sweepQrScratch(context) }
    }

    fun discardPhoto() {
        pending?.let { file -> runCatching { file.delete() } }
        pending = null
    }

    fun read(source: Uri, from: DwQrSource) {
        working = true
        scope.launch {
            when (val result = dwReadQrPicture(context, source, from)) {
                is DwQrReadResult.Found -> onText(result.text)
                is DwQrReadResult.NothingFound -> onRefusal(result.message)
                is DwQrReadResult.Unreadable -> onRefusal(result.message)
            }
            // AFTER the decode and on every branch, including success. The photograph has done its
            // whole job by this point and there is no path on which keeping it helps.
            discardPhoto()
            working = false
        }
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pending
        if (ok && file != null) read(dwCaptureUri(context, file), DwQrSource.CAMERA) else discardPhoto()
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pending
        if (!granted || file == null) {
            discardPhoto()
            if (!granted) {
                // NAMES THE OTHER DOOR, which is the whole reason a refused permission is not a dead
                // end here. Both remaining routes still work and neither needs a lens.
                onRefusal(
                    "The camera permission was refused, so a code cannot be photographed. You can " +
                        "still choose a picture of the code that is already on this phone, or type " +
                        "the code printed under the QR."
                )
            }
        } else {
            takePhoto.launch(dwCaptureUri(context, file))
        }
    }

    // The system photo picker, not `GetContent` with a media permission: it hands back exactly the
    // one picture the designer chose and grants this app no standing access to the gallery.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) read(uri, DwQrSource.PICTURE) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val file = File(qrScratchDir(context), "qr-${System.currentTimeMillis()}.jpg")
                    pending = file
                    if (hasPermission(context, Manifest.permission.CAMERA)) {
                        takePhoto.launch(dwCaptureUri(context, file))
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                enabled = enabled && !working,
                // The 48dp floor this app applies wherever a control was thought about — see
                // ISLAND_TOUCH_TARGET in ui/AppNavigation.kt.
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Reading…" else "Scan a code", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = {
                    discardPhoto()
                    pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = enabled && !working,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use a picture", fontSize = 13.sp)
            }
        }
        Text(
            "Photograph the code, or pick a picture of one you were sent — a screenshot or a " +
                "forwarded photograph reads just as well. The photograph is not kept. Everything " +
                "here works with no connection; only opening the record needs one.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}
