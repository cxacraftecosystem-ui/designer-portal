package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.transcript.data.UploadTranscribe
import com.designprototype.workshop.transcript.ui.TranscribeChoiceItem
import com.designprototype.workshop.transcript.ui.TranscribeChoiceRows
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.data.userMessage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.designprototype.workshop.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.webkit.MimeTypeMap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.designprototype.workshop.data.AttachedImage
import com.designprototype.workshop.data.DW_DEFAULT_MAX_ITEMS
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwDictationRun
import com.designprototype.workshop.data.DwMediaUploadProgress
import com.designprototype.workshop.data.DwPhotoGate
import com.designprototype.workshop.data.DwQualityFlagLog
import com.designprototype.workshop.data.DwUploadState
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopSyncEngine
import com.designprototype.workshop.data.dwDeclaredMinItems
import com.designprototype.workshop.data.dwEffectiveMaxItems
import com.designprototype.workshop.ui.MediaViewerDialog
import com.designprototype.workshop.ui.RecordingIndicator
import com.designprototype.workshop.ui.MediaDownloadButton
import com.designprototype.workshop.ui.MediaDownloadNotice
import com.designprototype.workshop.ui.MediaDownloader
import com.designprototype.workshop.ui.MediaRetryButton
import com.designprototype.workshop.ui.rememberMediaDownloader
import com.designprototype.workshop.ui.rememberMediaImageLoader
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

private enum class DwCaptureRoute { PHOTO, VIDEO, AUDIO, GALLERY, FILES }

private fun routesFor(type: DwFieldType): List<DwCaptureRoute> = when (type) {
    DwFieldType.IMAGE, DwFieldType.IMAGE_LIST ->
        listOf(DwCaptureRoute.PHOTO, DwCaptureRoute.GALLERY)
    DwFieldType.VIDEO -> listOf(DwCaptureRoute.VIDEO, DwCaptureRoute.GALLERY)
    DwFieldType.AUDIO -> listOf(DwCaptureRoute.AUDIO, DwCaptureRoute.FILES)
    else -> listOf(DwCaptureRoute.FILES, DwCaptureRoute.PHOTO)
}

private fun galleryMimeFor(type: DwFieldType): String = when (type) {
    DwFieldType.IMAGE, DwFieldType.IMAGE_LIST -> "image/*"
    DwFieldType.VIDEO -> "video/*"
    DwFieldType.AUDIO -> "audio/*"
    else -> "*/*"
}

internal fun dwCapNotice(label: String, declaredCap: Int?, dropped: Int, chosen: Int): String {
    val one = dropped == 1
    val ceiling = if (declaredCap == null) {
        "$label is full"
    } else {
        "$label holds at most $declaredCap file${if (declaredCap == 1) "" else "s"}"
    }
    return "$ceiling. $dropped of the $chosen you chose ${if (one) "was" else "were"} not attached. " +
        "Remove something first if you need ${if (one) "it" else "them"} instead."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DwMediaCaptureCard(
    field: FieldDto,
    type: DwFieldType,
    ids: List<String>,
    media: DwMediaBridge,
    slotKey: String,
    enabled: Boolean,
    onIdsChange: (List<String>) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val multiple = type == DwFieldType.IMAGE_LIST
    val routes = remember(type) { routesFor(type) }
    val logScope = rememberCoroutineScope()

    val declaredCap = if (multiple) field.maxItems.takeIf { it > 0 } else null
    val cap = if (multiple) dwEffectiveMaxItems(field.maxItems) else null

    val declaredFloor = if (multiple) dwDeclaredMinItems(field.minItems) else null

    var capNotice by remember(field.key) { mutableStateOf<String?>(null) }

    val screening = media.screening.stateFor(slotKey)

    var pendingCapture by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var viewing by remember { mutableStateOf<DwMediaItem?>(null) }

    LaunchedEffect(media.workshopId) {
        DwMediaUploadProgress.seedFrom(context, media.workshopId)
    }

    fun attachedForGate(): List<AttachedImage> = ids.mapNotNull { id ->
        val item = media.resolve(id) ?: return@mapNotNull null
        if (!item.mediaType.equals("IMAGE", ignoreCase = true)) return@mapNotNull null
        AttachedImage(
            label = item.displayName,
            checksum = item.sha256,
            perceptualHash = media.screening.measurementFor(id)?.perceptualHash,
        )
    }

    fun admit(admitted: List<DwAdmittedPhoto>) {
        if (admitted.isEmpty()) return
        media.attach(admitted.map { it.candidate.uri }, field) { newIds ->
            if (newIds.isEmpty()) return@attach
            onIdsChange(if (multiple) ids + newIds else listOf(newIds.first()))
            media.screening.bankMeasurements(admitted, newIds)
            if (admitted.size == newIds.size) {
                val raisedAt = Instant.now().toString()
                val findings = admitted.flatMapIndexed { index, photo ->
                    photo.faults.map { fault ->
                        DwPhotoGate.CapturedFinding(
                            mediaId = newIds[index],
                            fileName = photo.candidate.displayName,
                            flag = fault.flag,
                            severity = fault.severity,
                            note = fault.message,
                            raisedAt = raisedAt,
                        )
                    }
                }
                if (findings.isNotEmpty()) {
                    logScope.launch(Dispatchers.IO) {
                        DwQualityFlagLog.record(context, media.workshopId, findings)
                    }
                }
            }
            admitted.forEach { photo -> photo.candidate.staging?.let { runCatching { it.delete() } } }
        }
    }

    val currentAdmit by rememberUpdatedState<(List<DwAdmittedPhoto>) -> Unit> { admitted ->
        admit(admitted)
    }

    fun adopt(uris: List<Uri>, staging: List<File> = emptyList()) {
        if (uris.isEmpty()) return
        val trimmed = if (cap == null || !multiple) uris else uris.take((cap - ids.size).coerceAtLeast(0))
        if (multiple && cap != null && trimmed.size < uris.size) {
            val dropped = uris.size - trimmed.size
            capNotice = dwCapNotice(
                label = field.label,
                declaredCap = declaredCap,
                dropped = dropped,
                chosen = uris.size,
            )
            if (trimmed.isEmpty()) {
                staging.forEach { file -> runCatching { file.delete() } }
                return
            }
        } else {
            capNotice = null
        }
        val gated = type == DwFieldType.IMAGE || type == DwFieldType.IMAGE_LIST || type == DwFieldType.FILE
        if (!gated) {
            currentAdmit(
                trimmed.mapIndexed { index, uri ->
                    DwAdmittedPhoto(
                        candidate = DwScreenCandidate(uri, "", staging.getOrNull(index)),
                        faults = emptyList(),
                        measurement = null,
                    )
                }
            )
            return
        }

        val candidates = trimmed.mapIndexed { index, uri ->
            DwScreenCandidate(
                uri = uri,
                displayName = when {
                    staging.getOrNull(index) != null -> "The photograph you just took"
                    else -> WorkshopDraftStore.displayName(context, uri)
                        ?: uri.lastPathSegment
                        ?: "The photograph you chose"
                },
                staging = staging.getOrNull(index),
            )
        }

        media.screening.screen(
            slot = slotKey,
            resolver = context.contentResolver,
            candidates = candidates,
            attached = attachedForGate(),
            onAdmitted = { admitted -> currentAdmit(admitted) },
        )
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingCapture
        pendingCapture = null
        if (file == null) return@rememberLauncherForActivityResult
        if (ok) {
            adopt(listOf(dwCaptureUri(context, file)), listOf(file))
        } else {
            runCatching { file.delete() }
        }
    }

    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val file = pendingCapture
        pendingCapture = null
        if (file == null) return@rememberLauncherForActivityResult
        if (ok) adopt(listOf(dwCaptureUri(context, file)), listOf(file)) else runCatching { file.delete() }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        adopt(if (multiple) uris else uris.take(1))
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pendingCapture
        if (!granted) {
            pendingCapture = null
            runCatching { file?.delete() }
            onError("Camera permission denied. Allow it in Settings, or pick from the gallery.")
            return@rememberLauncherForActivityResult
        }
        if (file != null) takePhoto.launch(dwCaptureUri(context, file))
    }

    fun startPhoto() {
        val file = media.newCaptureFile(".jpg")
        pendingCapture = file
        if (hasPermission(context, Manifest.permission.CAMERA)) {
            takePhoto.launch(dwCaptureUri(context, file))
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun startVideo() {
        val file = media.newCaptureFile(".mp4")
        pendingCapture = file
        if (hasPermission(context, Manifest.permission.CAMERA)) {
            takeVideo.launch(dwCaptureUri(context, file))
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
            onError("Allow the camera, then tap Record video again.")
        }
    }

    fun stopRecording() {
        val active = recorder
        val file = recordingFile
        recorder = null
        recordingFile = null
        recording = false
        runCatching {
            active?.stop()
        }.onFailure {
            runCatching { file?.delete() }
            runCatching { active?.release() }
            onError("That recording was too short to save.")
            return
        }
        runCatching { active?.release() }
        if (file == null) return
        if (file.length() <= 0L) {
            runCatching { file.delete() }
            onError("That recording captured no audio.")
            return
        }
        adopt(listOf(dwCaptureUri(context, file)), listOf(file))
        onMessage("Recording attached.")
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            onError("Microphone permission denied. Allow it in Settings, or attach a file.")
        }
    }

    fun toggleRecording() {
        if (recording) {
            stopRecording()
            return
        }
        if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val file = media.newCaptureFile(".m4a")
        runCatching {
            recorder = dwAudioRecorder(context, file).also { it.start() }
            recordingFile = file
            recording = true
            onMessage("Recording…")
        }.onFailure { error ->
            runCatching { file.delete() }
            recorder = null
            recordingFile = null
            recording = false
            onError(error.userMessage("Couldn't start recording."))
        }
    }

    val currentAdopt by rememberUpdatedState<(List<Uri>, List<File>) -> Unit> { uris, staging ->
        adopt(uris, staging)
    }
    DisposableEffect(Unit) {
        onDispose {
            if (recording) {
                val active = recorder
                val file = recordingFile
                val stopped = runCatching { active?.stop() }.isSuccess
                runCatching { active?.release() }
                if (stopped && file != null && file.length() > 0L) {
                    currentAdopt(listOf(dwCaptureUri(context, file)), listOf(file))
                } else {
                    runCatching { file?.delete() }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (declaredFloor != null) {
            DwGalleryFloor(
                floor = declaredFloor,
                label = field.label,
                counts = DwPhotoGate.GalleryCounts(
                    held = ids.size,
                    onDevice = ids.count { id ->
                        val item = media.resolve(id)
                        item != null && item.remoteMediaId.isNullOrBlank()
                    },
                    screening = screening.screening,
                ),
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            routes.forEach { route ->
                when (route) {
                    DwCaptureRoute.PHOTO -> CaptureButton(
                        icon = Icons.Filled.PhotoCamera,
                        label = "Photograph",
                        enabled = enabled,
                        onClick = ::startPhoto
                    )
                    DwCaptureRoute.VIDEO -> CaptureButton(
                        icon = Icons.Filled.Videocam,
                        label = "Record video",
                        enabled = enabled,
                        onClick = ::startVideo
                    )
                    DwCaptureRoute.AUDIO -> CaptureButton(
                        icon = Icons.Filled.Mic,
                        label = if (recording) "Stop recording" else "Record audio",
                        enabled = enabled,
                        destructive = recording,
                        onClick = ::toggleRecording
                    )
                    DwCaptureRoute.GALLERY -> CaptureButton(
                        icon = Icons.Filled.PhotoLibrary,
                        label = "From gallery",
                        enabled = enabled,
                        onClick = { pick.launch(galleryMimeFor(type)) }
                    )
                    DwCaptureRoute.FILES -> CaptureButton(
                        icon = Icons.Filled.AttachFile,
                        label = "Choose file",
                        enabled = enabled,
                        onClick = { pick.launch(galleryMimeFor(type)) }
                    )
                }
            }
        }

        if (recording) {
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }

        val roomToCeiling = declaredCap?.let { (it - ids.size).coerceAtLeast(0) }
        if (declaredCap != null && (declaredFloor != declaredCap || roomToCeiling == 0)) {
            val room = roomToCeiling ?: 0
            Text(
                if (room == 0) {
                    "${field.label} is full at $declaredCap file${if (declaredCap == 1) "" else "s"}. Remove one to attach another."
                } else {
                    "Up to $declaredCap file${if (declaredCap == 1) "" else "s"} — $room more can be attached."
                },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Assertive
            },
        ) {
            capNotice?.let { sentence ->
                Text(
                    sentence,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }

        if (screening.refused.isNotEmpty()) {
            DwPhotoRefusalNotice(refused = screening.refused)
        }

        if (declaredFloor == null && screening.screening > 0) {
            Text(
                "Checking ${screening.screening} photo${if (screening.screening == 1) "" else "s"}…",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (ids.isEmpty()) {
            Text(
                if (multiple) "No photographs yet." else "Nothing attached yet.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (declaredCap != null && multiple) {
            val gallery = remember(ids, media) {
                ids.mapNotNull(media.resolve).filter { it.mediaType.equals("IMAGE", ignoreCase = true) }
            }
            if (gallery.isNotEmpty()) {
                DwMediaCarousel(
                    items = gallery,
                    noun = field.label.lowercase(),
                    onOpen = { item -> viewing = item }
                )
            }
        }

        if (type == DwFieldType.FILE && ids.size == 1) {
            val document = media.resolve(ids.first())
            val onDevice = document?.let { File(it.absolutePath).takeIf(File::exists) }
            if (document != null && onDevice != null) {
                DwDocumentPreview(
                    mediaId = document.id,
                    noun = field.label.lowercase(),
                    localFile = onDevice,
                    remoteUrl = null,
                    displayName = document.displayName,
                )
            }
        }

        ids.forEach { id ->
            val item = media.resolve(id)
            DwAttachmentRow(
                item = item,
                mediaId = id,
                enabled = enabled,
                onOpen = { item?.let { viewing = it } },
                onRemove = {
                    media.detach(id)
                    onIdsChange(if (multiple) ids.filterNot { it == id } else emptyList())
                },
                onRetry = {
                    logScope.launch {
                        WorkshopSyncEngine.retryOneFile(context, media.workshopId, id)
                        onMessage("Will upload when you're online.")
                    }
                },
            )
            if (item != null) {
                DwMediaAiVerbsRow(item = item, enabled = enabled)
            }
        }
        val transcribeItems = ids.mapNotNull { id ->
            media.resolve(id)?.takeIf { it.remoteMediaId == null && UploadTranscribe.eligible(it.mediaType) }
        }
        if (transcribeItems.isNotEmpty()) {
            TranscribeChoiceRows(
                items = transcribeItems.map { TranscribeChoiceItem(it.id, it.displayName, it.mediaType) },
                enabled = enabled,
                isOn = { key -> transcribeItems.firstOrNull { it.id == key }?.transcribe == true },
                onChange = { key, on -> media.setTranscribe(key, on) },
            )
        }

        DwPhotoQualityAdvisories(ids = ids, media = media)
    }

    viewing?.let { item ->
        MediaViewerDialog(
            uri = Uri.fromFile(File(item.absolutePath)),
            mediaType = item.mediaType,
            onDismiss = { viewing = null }
        )
    }
}

internal enum class DwAttachmentTone {
    NEUTRAL,

    PROGRESS,

    WARNING,
}

internal data class DwAttachmentStatus(
    val line: String,
    val tone: DwAttachmentTone,
    val percent: Int?,
    val canRetry: Boolean,
)

internal fun dwAttachmentStatus(
    mediaType: String,
    sizeBytes: Long,
    bytesPresent: Boolean,
    backedUp: Boolean,
    state: DwUploadState?,
): DwAttachmentStatus {
    val what = "${mediaType.lowercase()} · ${humanSize(sizeBytes)}"
    if (!bytesPresent) {
        return DwAttachmentStatus(
            line = "This file is no longer on this device.",
            tone = DwAttachmentTone.WARNING,
            percent = null,
            canRetry = false,
        )
    }
    return when (state) {
        is DwUploadState.Sending -> DwAttachmentStatus(
            line = "Uploading… ${state.percent}% · ${humanSize(state.sent)} of ${humanSize(state.total)}",
            tone = DwAttachmentTone.PROGRESS,
            percent = state.percent,
            canRetry = false,
        )
        is DwUploadState.Refused -> DwAttachmentStatus(
            line = if (state.permanent) {
                "Not uploaded — ${state.reason}"
            } else {
                "Upload did not go through — ${state.reason}"
            },
            tone = DwAttachmentTone.WARNING,
            percent = null,
            canRetry = state.permanent,
        )
        DwUploadState.Sent -> DwAttachmentStatus(
            line = "$what · saved online",
            tone = DwAttachmentTone.NEUTRAL,
            percent = null,
            canRetry = false,
        )
        null -> if (backedUp) {
            DwAttachmentStatus("$what · saved online", DwAttachmentTone.NEUTRAL, null, false)
        } else {
            DwAttachmentStatus("$what · on this device only", DwAttachmentTone.NEUTRAL, null, false)
        }
    }
}

@Composable
private fun CaptureButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
private fun DwAttachmentRow(
    item: DwMediaItem?,
    mediaId: String,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val loader = rememberMediaImageLoader()

    val uploadUpdates = remember(mediaId) {
        DwMediaUploadProgress.states.map { it[mediaId] }.distinctUntilChanged()
    }
    val uploadSeed = remember(mediaId) { DwMediaUploadProgress.states.value[mediaId] }
    val uploadState by uploadUpdates.collectAsState(initial = uploadSeed)
    val fileUri = remember(item?.absolutePath) { item?.let { Uri.fromFile(File(it.absolutePath)) } }
    val exists = remember(item?.absolutePath) { item?.let { File(it.absolutePath).exists() } ?: false }
    val downloader = rememberMediaDownloader()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
                    .then(if (exists) Modifier.clickable(onClickLabel = "play", onClick = onOpen) else Modifier)
            ) {
                when {
                    !exists || fileUri == null -> Text("?", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyLarge)
                    item?.mediaType.equals("IMAGE", ignoreCase = true) -> AsyncImage(
                        model = fileUri,
                        contentDescription = item?.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    item?.mediaType.equals("VIDEO", ignoreCase = true) -> {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(fileUri).build(),
                            imageLoader = loader,
                            contentDescription = item?.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                    }
                    else -> Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.field.muted
                    )
                }
            }
            val status = dwAttachmentStatus(
                mediaType = item?.mediaType ?: "",
                sizeBytes = item?.sizeBytes ?: 0L,
                bytesPresent = item != null && exists,
                backedUp = !item?.remoteMediaId.isNullOrBlank(),
                state = uploadState,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item?.displayName ?: "Missing file",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    status.line,
                    color = when (status.tone) {
                        DwAttachmentTone.WARNING -> MaterialTheme.colorScheme.error
                        DwAttachmentTone.PROGRESS -> MaterialTheme.colorScheme.primary
                        DwAttachmentTone.NEUTRAL -> MaterialTheme.field.muted
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                status.percent?.let { percent ->
                    FieldLinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, end = 4.dp),
                    )
                }
                DwAttachmentDownload(item = item, exists = exists, fileUri = fileUri, downloader = downloader)
                if (status.canRetry && onRetry != null) {
                    ActionRow {
                        MediaRetryButton(
                            fileName = item?.displayName,
                            onRetry = onRetry,
                            enabled = enabled,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Filled.Close, contentDescription = "Remove attachment", tint = MaterialTheme.field.muted)
            }
        }
        MediaDownloadNotice(downloader, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DwAttachmentDownload(item: DwMediaItem?, exists: Boolean, fileUri: Uri?, downloader: MediaDownloader) {
    val context = LocalContext.current
    val name = item?.displayName ?: "attachment"
    val mime = remember(item?.displayName, item?.absolutePath, item?.mediaType) { item?.let { dwAttachmentMimeType(it) } }
    val remoteId = item?.remoteMediaId?.takeIf { it.isNotBlank() }
    when {
        item != null && exists && fileUri != null -> MediaDownloadButton(
            fileName = name,
            onDownload = { downloader.save(fileUri, item.displayName, mime) },
        )
        remoteId != null -> MediaDownloadButton(
            fileName = name,
            onDownload = {
                val repository = DwDictationRun.repository()
                if (repository != null) {
                    downloader.saveFromServer(name, mime) {
                        val location = repository.downloadDataMedia(context, remoteId, name)
                        repository.shareUriForSavedFile(context, location)
                    }
                }
            },
            unavailableReason = if (DwDictationRun.repository() == null) DW_DOWNLOAD_GONE else null,
        )
        else -> MediaDownloadButton(fileName = name, onDownload = {}, unavailableReason = DW_DOWNLOAD_GONE)
    }
}

private const val DW_DOWNLOAD_GONE = "This copy is no longer on this device."

private fun dwAttachmentMimeType(item: DwMediaItem): String {
    val ext = (item.displayName.substringAfterLast('.', "").ifBlank { item.absolutePath.substringAfterLast('.', "") })
        .lowercase()
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
    return when (item.mediaType.uppercase()) {
        "AUDIO" -> "audio/mp4"
        "VIDEO" -> "video/mp4"
        "IMAGE" -> "image/jpeg"
        "PDF" -> "application/pdf"
        else -> "application/octet-stream"
    }
}

internal fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun dwCaptureUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

internal fun dwAudioRecorder(context: Context, file: File): MediaRecorder {
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
    return recorder.apply {
        runCatching { setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION) }
            .onFailure { setAudioSource(MediaRecorder.AudioSource.MIC) }
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(1)
        setAudioSamplingRate(44_100)
        setAudioEncodingBitRate(96_000)
        setOutputFile(file.absolutePath)
        prepare()
    }
}
