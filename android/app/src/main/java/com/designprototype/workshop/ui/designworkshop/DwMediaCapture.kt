package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.ui.MediaViewerDialog
import com.designprototype.workshop.ui.RecordingIndicator
import com.designprototype.workshop.ui.rememberMediaImageLoader
// The two-typeface `Text`, shadowing androidx.compose.material3.Text. Without this import the bare
// `Text` below resolves to Material's, inherits whatever family LocalTextStyle carries, and quietly
// sets this card's headings in the body face — the exact failure FieldText.kt exists to prevent.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import java.io.File

/**
 * The capture surface a design workshop actually needs: camera, gallery, audio recorder and file
 * picker in one card, with thumbnails, playback and removal.
 *
 * ── WHY THIS IS NOT A BARE "ATTACH FILE" BUTTON ───────────────────────────────────────────────
 *
 * A system file picker is the right control for a sanction order that already exists as a PDF on the
 * phone. It is the wrong control for every other thing this registry asks for. Fifteen of the
 * twenty-two stages want photographs, and those photographs do not exist yet — they are of a loom
 * that is being demonstrated, in a courtyard, right now. Sending a designer out of the app into the
 * camera app, then back in through a gallery picker that opens on their family photographs, costs
 * four taps and a context switch per picture, and the pictures that do not get taken because of it
 * are the ones the report is missing.
 *
 * So the four capture routes are all here, on the field, and each one lands its bytes in the same
 * durable place.
 *
 * ── WHY THIS MIRRORS `MediaCaptureSection` RATHER THAN CALLING IT ─────────────────────────────
 *
 * `MediaCaptureSection` in MainActivity.kt is the same surface for the RECORD forms, and it is
 * welded to a pipeline this feature must not use: it eagerly streams every attachment to object
 * storage the moment it is picked, tracks per-file upload progress, and deletes the staged objects
 * again if the form is abandoned. That is exactly right for a researcher filing an artisan record
 * over an office connection, and exactly wrong here. A design workshop is filled in over two weeks
 * with no signal; the local draft IS the document. Uploading a 300 MB loom video the instant it is
 * shot would burn a field designer's data allowance on a file the report will read off the disk
 * anyway, and the "staged but unsaved objects are deleted on dispose" behaviour would throw away
 * captures every time a stage screen leaves the composition.
 *
 * What IS shared is everything that can be: [MediaViewerDialog] and [RecordingIndicator] from
 * ui/MediaPlayers.kt are called directly rather than re-implemented, because rebuilding the ExoPlayer
 * lifecycle or the amplitude meter would mean getting one of them subtly wrong in the one screen
 * nobody regression-tests.
 *
 * ── WHERE THE BYTES GO, AND WHY NOT WHERE `createAppFile` PUTS THEM ───────────────────────────
 *
 * MainActivity's `createAppFile` writes camera captures into `cacheDir/field-captures/`, which is
 * correct there because the file is uploaded within seconds and never read again. Here the file is
 * the document. Android reclaims cacheDir under storage pressure — silently, with no callback, and
 * preferentially when the disk is tight, which on a 32 GB field phone two weeks into a study is
 * always. So every capture in this card is created inside the workshop's own directory under
 * `filesDir` (see [DwMediaBridge.newCaptureFile]) and is then imported through
 * [DwMediaBridge.attach], which copies it into the workshop's media directory, hashes it and
 * registers the descriptor. The staging file is deleted only after the import has reported success.
 */

/** What this field is allowed to hold, and therefore which buttons the card offers. */
private enum class DwCaptureRoute { PHOTO, VIDEO, AUDIO, GALLERY, FILES }

private fun routesFor(type: DwFieldType): List<DwCaptureRoute> = when (type) {
    // A photograph is taken far more often than it is found, so the camera comes first in the row.
    DwFieldType.IMAGE, DwFieldType.IMAGE_LIST ->
        listOf(DwCaptureRoute.PHOTO, DwCaptureRoute.GALLERY)
    DwFieldType.VIDEO -> listOf(DwCaptureRoute.VIDEO, DwCaptureRoute.GALLERY)
    DwFieldType.AUDIO -> listOf(DwCaptureRoute.AUDIO, DwCaptureRoute.FILES)
    // FILE keeps the camera. Half the documents this registry asks for — a sanction order, an
    // attendance sheet, a signed certificate — exist only as paper in the room, and a field that
    // offers no way to photograph them is a field that stays empty.
    else -> listOf(DwCaptureRoute.FILES, DwCaptureRoute.PHOTO)
}

private fun galleryMimeFor(type: DwFieldType): String = when (type) {
    DwFieldType.IMAGE, DwFieldType.IMAGE_LIST -> "image/*"
    DwFieldType.VIDEO -> "video/*"
    DwFieldType.AUDIO -> "audio/*"
    else -> "*/*"
}

/**
 * The whole card for one media field.
 *
 * [ids] are the media ids already stored in the field's value, in the order they were attached, and
 * [onIdsChange] reports the new list ONCE per capture. Never one call per file: the callers of this
 * screen recompute their row list from a snapshot captured when the lambda was created, so two
 * sequential writes in the same frame both start from the same stale snapshot and the second erases
 * the first. That is the bug that once turned a five-photograph selection into one photograph and
 * four orphaned files nothing in the UI could show or remove.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DwMediaCaptureCard(
    field: FieldDto,
    type: DwFieldType,
    ids: List<String>,
    media: DwMediaBridge,
    enabled: Boolean,
    onIdsChange: (List<String>) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val multiple = type == DwFieldType.IMAGE_LIST
    val routes = remember(type) { routesFor(type) }

    /**
     * The capture currently in flight, kept OUTSIDE the launcher callback.
     *
     * `TakePicture` reports only a boolean; the file it wrote is the one we handed it, and if that
     * reference is lost the photograph is on disk with nothing pointing at it. Process death between
     * launching the camera and its result would still lose it — which is why the file lives under
     * filesDir rather than cacheDir, so the sweep that reclaims it is ours to write rather than
     * Android's to perform without warning.
     */
    var pendingCapture by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var viewing by remember { mutableStateOf<DwMediaItem?>(null) }

    /** Import a finished capture and, only once the bytes are safely copied, drop the staging file. */
    fun adopt(uris: List<Uri>, staging: List<File> = emptyList()) {
        if (uris.isEmpty()) return
        media.attach(uris, field) { newIds ->
            if (newIds.isEmpty()) return@attach
            onIdsChange(if (multiple) ids + newIds else listOf(newIds.first()))
            // The import made its own durable copy under media/, so the staging file is now a
            // duplicate of a file the draft owns. Deleting it AFTER the callback and never before is
            // what stops a failed import from taking the only copy of a photograph with it.
            staging.forEach { file -> runCatching { file.delete() } }
        }
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingCapture
        pendingCapture = null
        if (file == null) return@rememberLauncherForActivityResult
        if (ok) {
            adopt(listOf(dwCaptureUri(context, file)), listOf(file))
        } else {
            // The designer backed out of the camera. The zero-byte placeholder we created for it must
            // go, or the workshop directory fills with empty files nothing references.
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
        // A single-valued field keeps the FIRST of an over-selection rather than the last, because
        // "the first thing I tapped" is what a designer expects to survive — and the rest are never
        // imported at all, so they cannot become files the UI has no row for and no way to delete.
        adopt(if (multiple) uris else uris.take(1))
    }

    /**
     * The camera permission, asked at the moment the camera is wanted.
     *
     * It is asked rather than assumed even though the launch-time batch usually has it, because this
     * app is installed on shared field handsets where somebody denied it once for a different form.
     * A `TakePicture` launched without it does not fail quietly: the manifest declares CAMERA, so the
     * platform throws SecurityException and the process dies holding an unsaved stage.
     */
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pendingCapture
        if (!granted) {
            pendingCapture = null
            runCatching { file?.delete() }
            onError("The camera permission was refused, so nothing can be photographed here. Grant it in Settings, or attach a picture from the gallery instead.")
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
            // Video needs the same permission and the same file; the photo launcher's callback would
            // be the wrong one to resume through, so the recorder simply asks and the designer taps
            // again. One extra tap, once, on a handset where the permission was previously refused.
            cameraPermission.launch(Manifest.permission.CAMERA)
            onError("Grant the camera permission and tap Record video again.")
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
            // MediaRecorder.stop() throws when it is stopped before any frame was written — a tap
            // on Stop within a fraction of a second of Start. The output file in that case is a
            // zero-length MP4 that no player will open, so it is discarded rather than attached: an
            // unplayable "recording" in a report is worse than a missing one, because it looks like
            // evidence until somebody tries to listen to it.
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
            onError("The microphone permission was refused, so nothing can be recorded here. Grant it in Settings, or attach an existing audio file.")
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
            onError(error.message ?: "This device would not start an audio recording.")
        }
    }

    /**
     * A recorder left running when the card goes away is a microphone left open — and, worse, a
     * recording nothing points at.
     *
     * Compose disposes this card whenever the collection row is collapsed or the stage scrolls it
     * out of the tree, which a designer does mid-recording without thinking about it. Without this
     * the MediaRecorder keeps the hardware, the notification stays up, and the next field that wants
     * the microphone gets an IllegalStateException.
     *
     * THE PARTIAL RECORDING IS ATTACHED, not merely stopped. An earlier shape here stopped the
     * recorder and left the .m4a sitting in the captures directory referenced by nothing: the
     * designer's audio was on the disk, invisible in the UI, impossible to remove and absent from
     * the report. Attaching it works because [DwMediaBridge.attach] runs on the STAGE screen's
     * coroutine scope, not this card's — the card is gone, the stage is not.
     *
     * `rememberUpdatedState` rather than capturing `adopt` directly, because a `DisposableEffect`
     * keyed on `Unit` closes over the FIRST composition's lambda, whose `ids` list is the one this
     * field held on its first frame. Attaching through that would write a stale id list back over
     * every attachment made since, which is the same lost-update this file's other comments are
     * about, arriving by a different door.
     */
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
            // The live amplitude meter, so the designer can see that the microphone is actually
            // hearing something. A recording that captured silence because the phone was face-down on
            // a charpoy is discovered at transcription time, weeks later, when the artisan has gone.
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }

        if (ids.isEmpty()) {
            Text(
                if (multiple) "No photographs yet." else "Nothing attached yet.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        ids.forEach { id ->
            val item = media.resolve(id)
            DwAttachmentRow(
                item = item,
                enabled = enabled,
                onOpen = { item?.let { viewing = it } },
                onRemove = {
                    media.detach(id)
                    onIdsChange(if (multiple) ids.filterNot { it == id } else emptyList())
                }
            )
        }

        // Blur, resolution and duplicate advice, measured on this device from the bytes that were
        // just imported. It sits AFTER the rows because it is about them, and it is added here rather
        // than inside `adopt` because it must be incapable of touching the import: by the time this
        // composes, the photograph is already copied, hashed and in the draft. See
        // [DwPhotoQualityAdvisories] for why a finding can never be a refusal.
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
            fontSize = 13.sp,
            maxLines = 1
        )
    }
}

/**
 * One attachment: a thumbnail, what it is, and the two things a designer can do with it.
 *
 * NOT `ui.MediaThumb`, and the reason is colour rather than laziness. That composable hardcodes the
 * record forms' dark palette (`Color(0xFF181715)`, `Canvas`, `SurfaceCard`) because it was written
 * for screens that are always dark. This feature reads its colours from `MaterialTheme.field`, which
 * follows the device's light/dark setting, and dropping a black card into a light stage form is the
 * kind of inconsistency that reads as a bug. The pieces that carry real behaviour — the video-frame
 * decoder and the player — ARE reused, so nothing about playback is re-implemented here.
 */
@Composable
private fun DwAttachmentRow(
    item: DwMediaItem?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val loader = rememberMediaImageLoader()
    val fileUri = remember(item?.absolutePath) { item?.let { Uri.fromFile(File(it.absolutePath)) } }
    val exists = remember(item?.absolutePath) { item?.let { File(it.absolutePath).exists() } ?: false }

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
                .then(if (exists) Modifier.clickable(onClick = onOpen) else Modifier)
        ) {
            when {
                !exists || fileUri == null -> Text("?", color = MaterialTheme.field.muted, fontSize = 16.sp)
                item?.mediaType.equals("IMAGE", ignoreCase = true) -> AsyncImage(
                    model = fileUri,
                    contentDescription = item?.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                item?.mediaType.equals("VIDEO", ignoreCase = true) -> {
                    // The video-frame decoder from ui/MediaPlayers.kt: a still lifted out of the clip
                    // itself, so a designer with nine loom videos can tell them apart without opening
                    // each one. A generic film icon on all nine makes the list unusable.
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item?.displayName ?: "Missing file",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                // A missing file is REPORTED, never quietly dropped from the list. The descriptor is
                // the only surviving record that the photograph existed at all, and a store that
                // silently forgot it would destroy the caption along with it.
                if (item == null || !exists) {
                    "The bytes for this attachment are no longer on this device."
                } else {
                    "${item.mediaType.lowercase()} · ${humanSize(item.sizeBytes)} · tap to play"
                },
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                maxLines = 2
            )
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Filled.Close, contentDescription = "Remove attachment", tint = MaterialTheme.field.muted)
        }
    }
}

// --------------------------------------------------------------------------------------
// Platform plumbing
// --------------------------------------------------------------------------------------

internal fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * A `content://` handle onto a file inside `filesDir`, for handing to the camera.
 *
 * The manifest's FileProvider already declares `<files-path name="capture_files" path="."/>`, so
 * every path under filesDir is grantable without touching the manifest. A `file://` Uri would throw
 * FileUriExposedException the moment it crossed to the camera app on any API this build supports.
 */
internal fun dwCaptureUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/**
 * The audio recorder, configured the way the rest of this app configures one.
 *
 * A deliberate copy of MainActivity's `createAudioRecorder`, which is private to that file and
 * belongs to a 13,000-line screen this module must not import. The SETTINGS are what matter and they
 * are identical on purpose: mono 44.1 kHz AAC at 96 kbps through the VOICE_RECOGNITION source, which
 * is the platform's speech-tuned capture path (noise suppression and gain control, without the
 * aggressive echo cancellation of the call path). A workshop recording that comes out at a different
 * bitrate from a questionnaire recording is a transcription queue with two answers about quality.
 */
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
