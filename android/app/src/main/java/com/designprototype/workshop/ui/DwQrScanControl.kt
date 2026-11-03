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
import androidx.compose.material.icons.filled.Settings
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
import com.designprototype.workshop.ui.designworkshop.dwCaptureUri
import com.designprototype.workshop.ui.designworkshop.hasPermission
import kotlinx.coroutines.launch
import java.io.File

private fun qrScratchDir(context: Context): File =
    File(context.filesDir, "qr-scratch").apply { mkdirs() }

private fun sweepQrScratch(context: Context) {
    runCatching { qrScratchDir(context).listFiles()?.forEach { it.delete() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DwQrScanControl(
    enabled: Boolean,
    onText: (String) -> Unit,
    onRefusal: (String) -> Unit,
    modifier: Modifier = Modifier,
    pictureLabel: String = "Use a picture",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var working by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<File?>(null) }
    var cameraBlocked by remember { mutableStateOf(false) }

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
                val blocked = dwCameraBlocked(context)
                cameraBlocked = blocked
                onRefusal(dwCameraRefusal(DwCameraUse.QR_CODE, blocked))
            }
        } else {
            cameraBlocked = false
            takePhoto.launch(dwCaptureUri(context, file))
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) read(uri, DwQrSource.PICTURE) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionRow {
            OutlinedButton(
                onClick = {
                    val file = File(qrScratchDir(context), "qr-${System.currentTimeMillis()}.jpg")
                    pending = file
                    if (hasPermission(context, Manifest.permission.CAMERA)) {
                        cameraBlocked = false
                        takePhoto.launch(dwCaptureUri(context, file))
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                enabled = enabled && !working,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (working) {
                    FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Reading…" else "Scan a code", style = MaterialTheme.typography.labelLarge)
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
                Text(pictureLabel, style = MaterialTheme.typography.labelLarge)
            }
            if (cameraBlocked) {
                OutlinedButton(
                    onClick = { context.dwOpenAppPermissionSettings() },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(DW_CAMERA_SETTINGS_BUTTON, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text(
            "Photograph the code or pick a picture. Works offline; lookup needs a connection.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
    }
}

@Composable
fun DwQrPictureButton(
    enabled: Boolean,
    onText: (String) -> Unit,
    onRefusal: (String) -> Unit,
    label: String = "Use a picture",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            working = true
            scope.launch {
                when (val result = dwReadQrPicture(context, uri, DwQrSource.PICTURE)) {
                    is DwQrReadResult.Found -> onText(result.text)
                    is DwQrReadResult.NothingFound -> onRefusal(result.message)
                    is DwQrReadResult.Unreadable -> onRefusal(result.message)
                }
                working = false
            }
        }
    }
    OutlinedButton(
        onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = enabled && !working,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        if (working) {
            FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(if (working) "Reading…" else label, style = MaterialTheme.typography.labelLarge)
    }
}
