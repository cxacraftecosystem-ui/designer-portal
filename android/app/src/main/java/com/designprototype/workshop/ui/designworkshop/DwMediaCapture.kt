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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.designprototype.workshop.data.AttachedImage
import com.designprototype.workshop.data.DW_DEFAULT_MAX_ITEMS
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwPhotoGate
import com.designprototype.workshop.data.DwQualityFlagLog
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.dwDeclaredMinItems
import com.designprototype.workshop.data.dwEffectiveMaxItems
import com.designprototype.workshop.ui.MediaViewerDialog
import com.designprototype.workshop.ui.RecordingIndicator
import com.designprototype.workshop.ui.rememberMediaImageLoader
// The two-typeface `Text`, shadowing androidx.compose.material3.Text. Without this import the bare
// `Text` below resolves to Material's, inherits whatever family LocalTextStyle carries, and quietly
// sets this card's headings in the body face — the exact failure FieldText.kt exists to prevent.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

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
 * WHAT AN IMPORT DROPPED, IN WORDS — AND THE CEILING ONLY WHERE THE REGISTRY DECLARED IT.
 *
 * ONE CLAUSE CHANGES AND ONLY ONE, which is the same split `FieldInput.tsx`'s `refusalNotice` makes
 * for the same reason. With a DECLARED cap the sentence states the number, because that number came
 * off the registry and the hint under the capture buttons has been printing it all along. With none,
 * the ceiling is the server's [DW_DEFAULT_MAX_ITEMS] and the sentence says the field is FULL and
 * stops: docs/DESIGN_WORKSHOP.md:229-232 forbids a client printing a number it did not read, because
 * "a stated cap that is not the enforced cap is worse than no sentence at all".
 *
 * DROPPING THE SENTENCE ALONG WITH THE NUMBER WAS NOT AVAILABLE INSTEAD, and that trade is the
 * mistake this function exists to make unrepresentable. What the counted clause carries is the only
 * record anywhere of a refusal — `adopt` trims before `media.attach` copies a byte, so a photograph
 * that did not land has no row, no thumbnail, nothing in the draft and nothing on the server — so
 * saying nothing on an undeclared gallery would turn a loud trim into a silent drop of the
 * two-hundred-and-first photograph, which is exactly what [DwMediaCaptureCard]'s comment on `adopt`
 * refuses: *"the honest act is to take what fits and SAY what did not"*.
 *
 * IT COUNTS RATHER THAN NAMING FILES, where the web names them. Not a divergence worth closing: this
 * card is handed content Uris and trims them before anything is imported, so the only handle it has
 * on a refused photograph is that there was one — and a `content://` path is not a thing to show a
 * designer. The count is what they can act on.
 *
 * `internal` and a pure function of its inputs so [DwMediaCapCeilingTest] can hold it to both rules
 * on a desktop JVM, where no `@Composable` can be composed.
 */
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
    /**
     * WHICH SCREENING QUEUE AND WHICH REFUSAL NOTICE BELONG TO THIS CARD — this field, in this row.
     *
     * Collection rows share composable slots (see [FieldRenderer]'s `resetKey`), so the field key
     * alone would give nine prototype rows one queue between them and report row 2's refused
     * photograph under row 7. Supplied by the caller rather than derived here, because only the
     * caller knows which row it is drawing.
     */
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

    /**
     * THE CEILING THIS FIELD IS HELD TO, AND THE CEILING IT IS ALLOWED TO PRINT — TWO DIFFERENT
     * NUMBERS, AND CONFLATING THEM IS WHAT docs/DESIGN_WORKSHOP.md:229-232 FORBIDS IN AS MANY WORDS.
     *
     * [declaredCap] is what the registry said, or null where it said nothing. [cap] is what is
     * actually enforced, and for a multi-valued field that is never nothing: an absent `maxItems`
     * means the server's [DW_DEFAULT_MAX_ITEMS], not "no limit" — see [FieldDto.maxItems].
     *
     * READING THE ABSENCE AS NO LIMIT IS HALF OF THE FORBIDDEN PAIR, and it is the half this card did
     * until 2026-08-26. It did not cost the designer the surplus photographs, which would have been
     * survivable; it cost the whole stage write. `coerce_value` REFUSES an over-long array rather than
     * trimming it (stage_schema.py:1822) and `save_stage` restores the rejected key from `previous`,
     * so a gallery grown past the ceiling syncs as a field that simply did not save — with the bytes
     * already copied into the workshop's media directory.
     *
     * PRINTING A NUMBER THIS CLIENT DID NOT READ IS THE OTHER HALF, which is why [declaredCap]
     * survives as a value of its own rather than being folded into [cap]. The registry declares a cap
     * on two of its image lists and nothing on every other gallery, so drawing "up to 200" under the
     * rest would be this client inventing a number the server owns and may change without a
     * `registry_version()` bump: a stated cap that is not the enforced cap is worse than no sentence
     * at all. So the always-visible hint below reads [declaredCap], the trim in [adopt] reads [cap],
     * and the trim still SAYS what it dropped — naming the ceiling only where [declaredCap] gave it
     * one — because a silent drop is the one outcome the doc and [adopt]'s own comment both refuse.
     *
     * Only meaningful for a multi-valued field: a single IMAGE/FILE/AUDIO/VIDEO holds one by
     * construction, and [adopt] already replaces rather than appends for those.
     */
    val declaredCap = if (multiple) field.maxItems.takeIf { it > 0 } else null
    val cap = if (multiple) dwEffectiveMaxItems(field.maxItems) else null

    /**
     * HOW MANY THE REGISTRY SAYS THIS GALLERY MUST HOLD, OR NULL — the ceiling's mirror image, and
     * the mirroring is not symmetrical.
     *
     * An absent CEILING still enforces the server's default; an absent FLOOR is no floor at all, so
     * this is the one of the pair with no fallback and nothing to enforce. It is drawn and scored
     * and never validated: see [FieldDto.minItems] for why a floor on the write path would destroy a
     * village day's work on this client specifically.
     *
     * Only meaningful for a multi-valued field. A single IMAGE holds one by construction and a floor
     * of one would be the `required` flag wearing a different hat.
     */
    val declaredFloor = if (multiple) dwDeclaredMinItems(field.minItems) else null

    /** Why an import was cut short, when it was. Cleared by the next import that fits. */
    var capNotice by remember(field.key) { mutableStateOf<String?>(null) }

    /**
     * What the gate is doing for THIS slot, held by the stage rather than by this card.
     *
     * Read on every composition rather than remembered: it changes as each photograph is measured,
     * and the count it carries is drawn in the floor readout beside a number that must never include
     * it. See DwPhotoScreening.kt for why the state lives a level up.
     */
    val screening = media.screening.stateFor(slotKey)

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

    /**
     * What a candidate is compared against for duplication: everything this field already holds.
     *
     * A FUNCTION AND NOT A REMEMBERED VALUE, because it must be read at the moment the picker
     * returns rather than at the moment this card last composed — a designer who attached three
     * photographs and then picked a fourth would otherwise be judged against the gallery as it was
     * before the first three.
     *
     * TWO HANDLES, AND THE SECOND IS OFTEN ABSENT. The SHA-256 is on the descriptor
     * ([WorkshopDraftStore.importMedia] computed it during the copy), so an EXACT duplicate is a
     * lookup against data this handset already holds. The perceptual hash exists only for a
     * photograph something has MEASURED, so it comes from the screening store's bank and is null for
     * anything imported by an older build or attached in the browser. Null is "unknown" and never
     * "unique": a missing hash produces no claim in either direction, which is why an unmeasured
     * neighbour can never be half of a "same shot" warning.
     *
     * IT NAMES WHAT IS THERE, and the name is printed in the refusal: "the identical file is already
     * attached here as …". A designer who cannot see WHICH file they are being told they duplicated
     * has been given a verdict instead of a reason.
     */
    fun attachedForGate(): List<AttachedImage> = ids.mapNotNull { id ->
        val item = media.resolve(id) ?: return@mapNotNull null
        if (!item.mediaType.equals("IMAGE", ignoreCase = true)) return@mapNotNull null
        AttachedImage(
            label = item.displayName,
            checksum = item.sha256,
            perceptualHash = media.screening.measurementFor(id)?.perceptualHash,
        )
    }

    /**
     * Import everything the gate let through, and remember what it still had to say about each.
     *
     * ── ONE CALL, ONE CALLBACK, ONE STATE WRITE, exactly as before the gate existed ───────────
     *
     * ── AND A FINDING BECOMES A ROW ONLY WHERE THE POSITIONS ARE BEYOND DOUBT ─────────────────
     *
     * `media.attach` reports the ids it managed to write and nothing else: one unreadable file out
     * of five is skipped so the other four survive, and the answer is then four ids for five inputs
     * with no mapping between them. Lining those up by index would file one photograph's quality
     * flag against a different photograph's id — a durable wrong answer in a table an officer reads
     * as an observation, and the same defect the web names by hand as "one identity card's digits
     * under another card's photograph". So the mapping is taken only when the counts agree, which is
     * every ordinary import, and a partial one records nothing. What is lost then is a convenience
     * ([DwQualityFlagLog] is an aid and never a record); what is avoided is a false statement.
     */
    fun admit(admitted: List<DwAdmittedPhoto>) {
        if (admitted.isEmpty()) return
        media.attach(admitted.map { it.candidate.uri }, field) { newIds ->
            if (newIds.isEmpty()) return@attach
            onIdsChange(if (multiple) ids + newIds else listOf(newIds.first()))
            // The reading taken at the door, banked against the id the import just issued, so the
            // advisory card below the rows does not decode these same photographs a second time —
            // and, more importantly, cannot come to a different answer about one of them.
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
                    // Off the composition's thread because it touches SharedPreferences, and on a
                    // scope that dies with this card because losing an aid costs nothing a designer
                    // typed — see [DwQualityFlagLog]'s header on why every failure here is silent.
                    logScope.launch(Dispatchers.IO) {
                        DwQualityFlagLog.record(context, media.workshopId, findings)
                    }
                }
            }
            // The import made its own durable copy under media/, so the staging file is now a
            // duplicate of a file the draft owns. Deleting it AFTER the callback and never before is
            // what stops a failed import from taking the only copy of a photograph with it.
            admitted.forEach { photo -> photo.candidate.staging?.let { runCatching { it.delete() } } }
        }
    }

    /**
     * [admit], reached through the CURRENT composition's lambda.
     *
     * The screening is asynchronous and outlives this card by design, so the store calls back into
     * whatever composition is standing when the last photograph has been measured. Captured
     * directly, this lambda would close over the `ids` list this field held when the picker was
     * launched, and attaching through it would write a stale id list over every attachment made
     * since — the same lost update the `DisposableEffect` below is wrapped for, arriving by a
     * different door.
     */
    val currentAdmit by rememberUpdatedState<(List<DwAdmittedPhoto>) -> Unit> { admitted ->
        admit(admitted)
    }

    /** Judge a finished capture, and import only what the gate lets through. */
    fun adopt(uris: List<Uri>, staging: List<File> = emptyList()) {
        if (uris.isEmpty()) return
        /*
         * THE CAP IS APPLIED BEFORE THE IMPORT, NOT AFTER IT, AND THAT IS THE WHOLE POINT ON A PHONE.
         *
         * `media.attach` copies every byte into the workshop's media directory under filesDir before
         * it hands back an id. Trimming after that call would leave orphaned copies of the
         * photographs it refused — files the UI has no row for and therefore no way to delete —
         * filling a field handset that is usually short of space. Trimming the Uri list is free.
         *
         * TRIMMED HERE AND REFUSED ON THE SERVER, which is the same split the web makes: there is
         * somebody to tell, right now, before anything is copied, so the honest act is to take what
         * fits and SAY what did not. `coerce_value` has nobody to ask and must refuse the whole field.
         *
         * THE TRIM READS [cap] AND SO IT FIRES ON EVERY GALLERY, not only the two that declare a
         * number — an absent `maxItems` is the server's [DW_DEFAULT_MAX_ITEMS] and never "no limit".
         * THE NOTICE FIRES WITH IT, and it is handed [declaredCap] rather than [cap] so that the one
         * clause naming a number is dropped where the number is the server's: see [dwCapNotice], which
         * is the wording that lets both halves of docs/DESIGN_WORKSHOP.md:231-232 hold at once. Gating the
         * NOTICE ITSELF on a declared cap while trimming at 200 anyway would have been the trade that
         * paragraph exists to refuse — a silent drop of the two-hundred-and-first photograph.
         */
        val trimmed = if (cap == null || !multiple) uris else uris.take((cap - ids.size).coerceAtLeast(0))
        if (multiple && cap != null && trimmed.size < uris.size) {
            val dropped = uris.size - trimmed.size
            capNotice = dwCapNotice(
                label = field.label,
                declaredCap = declaredCap,
                dropped = dropped,
                chosen = uris.size,
            )
            // The staging files for what was refused are deleted here rather than left for a sweep:
            // nothing downstream will ever reference them, and a camera capture that was cut by the
            // cap is a file whose only purpose has just ended.
            if (trimmed.isEmpty()) {
                staging.forEach { file -> runCatching { file.delete() } }
                return
            }
        } else {
            capNotice = null
        }
        /*
         * ════════════════════════════════════════════════════════════════════════════════════════
         * AND ONLY NOW IS THE PHOTOGRAPH JUDGED — BEFORE `media.attach` COPIES A BYTE
         * ════════════════════════════════════════════════════════════════════════════════════════
         *
         * The owner's instruction of 2026-08-27 is that a shaky or poor-quality photograph must not
         * reach the server. On this client the only moment that can be made true is here: everything
         * downstream of `media.attach` is already in the draft, already counted by the field,
         * already walked by the sync pass. The gate therefore sits exactly where the ceiling trim
         * above it sits, one step later, and for the reason that comment already gives about
         * orphaned copies.
         *
         * IT IS ASYNCHRONOUS AND THE WAIT IS ON SCREEN. Measuring a 12 MP frame is a few hundred
         * milliseconds and sometimes approaches a second, so twenty-five of them is a real wait —
         * the floor readout says how many are still being checked and never counts them in the
         * gallery total, because a photograph that may yet be refused is not in the gallery.
         *
         * WHAT REACHES `media.attach` IS THE ADMITTED LIST, IN ONE CALL. Never one call per file:
         * this card's callers recompute their row list from a snapshot captured when the lambda was
         * created, so two writes in one frame both start from the same stale snapshot and the second
         * erases the first — the defect [DwMediaBridge.attach] is shaped against.
         */
        /*
         * A FIELD THAT CANNOT HOLD A PHOTOGRAPH SKIPS THE GATE ENTIRELY, AND THAT IS TWO SAVINGS.
         *
         * [DwImageDecode.screen] fails open for anything it cannot decode, so an audio recording or
         * a video would be admitted either way — but it would first be OPENED, and the screening
         * would be an asynchronous hop. Neither is free here. The recorder's own `DisposableEffect`
         * attaches a part-finished recording as this card is being disposed, and every hop between
         * that call and `media.attach` is another chance for the stage to go away underneath a file
         * that exists nowhere else. And a FILE field's picker offers everything, so a 300 MB loom
         * video handed to the gate would be read for its header before it was declined.
         *
         * IMAGE and IMAGE_LIST are gated because that is what they hold. FILE is gated BECAUSE OF
         * ITS CAMERA ROUTE: half the documents this registry asks for — a sanction order, an
         * attendance sheet, a signed certificate — exist only as paper in the room, and a photograph
         * of a sanction order too soft to read is exactly the failure the owner asked about. Nothing
         * is lost on a PDF or a .docx attached there: no bounds, no measurement, admitted.
         */
        val gated = type == DwFieldType.IMAGE || type == DwFieldType.IMAGE_LIST || type == DwFieldType.FILE
        if (!gated) {
            currentAdmit(
                trimmed.mapIndexed { index, uri ->
                    DwAdmittedPhoto(
                        // The display name is left blank rather than looked up: it is read only by a
                        // refusal sentence and by a finding's note, and this branch produces neither.
                        // Querying the content resolver for a string nothing prints would be a round
                        // trip per file for nothing.
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
                /*
                  WHAT A REFUSAL CALLS IT, AND A CAMERA CAPTURE IS NAMED BY WHERE IT CAME FROM.

                  A gallery pick has a name the designer chose it by. A capture this app just made
                  has "capture-8f3a1c...-.jpg", which is a UUID with a dot in it and tells nobody
                  anything — and it is the case that matters most, because a refusal for blur almost
                  always follows the shutter. "The photograph you just took — the sharpness reading
                  was 42 against a floor of 60" is a sentence a designer can act on without looking
                  anything up. The same string goes into the finding's note, so the note says where
                  the photograph came from rather than repeating a random identifier.
                */
                displayName = when {
                    staging.getOrNull(index) != null -> "The photograph you just took"
                    else -> WorkshopDraftStore.displayName(context, uri)
                        ?: uri.lastPathSegment
                        ?: "The photograph you chose"
                },
                // Paired by INDEX, which is safe only because the two lists that carry a staging
                // file — the camera and the recorder — hand over exactly one Uri each, and the trim
                // above returns early when it drops that one. A future caller passing several
                // staging files with several Uris owes this pairing a second look.
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
        /*
         * THE FLOOR, ABOVE THE BUTTONS AND FROM FIRST PAINT — because it is a requirement and not a
         * result.
         *
         * "All 25 are required" is worth nothing to a designer who reads it after the twentieth
         * photograph, standing in a courtyard they are about to leave. It is the one thing on this
         * card that has to be on screen BEFORE the first shutter, so it sits above the capture row
         * where the always-visible ceiling hint sits below it — the ceiling describes a limit you
         * meet by accident, the floor describes work you have to plan.
         *
         * DRAWN ONLY WHERE THE REGISTRY DECLARES ONE, which is two galleries today, and never
         * derived from a field key. A gallery whose floor somebody bothered to declare is a gallery
         * with a target; putting the app's behaviour in a `when` on `motifPhotos` instead would mean
         * the next such gallery silently not getting it — the same argument the carousel below makes
         * about the declared cap.
         */
        if (declaredFloor != null) {
            DwGalleryFloor(
                floor = declaredFloor,
                label = field.label,
                counts = DwPhotoGate.GalleryCounts(
                    // THE NUMERATOR IS WHAT THE FIELD HOLDS AND NOTHING IN FLIGHT IS ADDED TO IT. A
                    // photograph still being measured may yet be refused and the field's value has
                    // no reference to it; counting it would draw "25 of 25" over a gallery a save
                    // would post twenty-three of.
                    held = ids.size,
                    // ON THIS DEVICE ONLY: a descriptor this handset holds whose bytes the server
                    // has never been given (`remoteMediaId` is null until `/media/complete`
                    // answers). An id this device cannot resolve at all is EXCLUDED rather than
                    // counted — that is a photograph attached in the browser, which is the one case
                    // where the server certainly does have it.
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
            // The live amplitude meter, so the designer can see that the microphone is actually
            // hearing something. A recording that captured silence because the phone was face-down on
            // a charpoy is discovered at transcription time, weeks later, when the artisan has gone.
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }

        /*
         * THE CEILING, IN WORDS, WHEREVER ONE IS DECLARED — and it states what is LEFT rather than
         * only the total. "20 photographs" is a rule; "4 more can be attached" is an answer to the
         * question a designer standing in front of twenty-five motifs is actually asking. It is drawn
         * under the capture buttons rather than over them so it sits beside the count it describes.
         *
         * The buttons are deliberately NOT disabled at the ceiling. A designer who has filled a
         * gallery still needs the picker to replace something, and more importantly the same card
         * carries the retry path for a failed import — disabling it at the cap would strand that.
         * The trim in `adopt` is what enforces the number; this is what stops anybody meeting it by
         * surprise.
         *
         * IT READS [declaredCap] AND NOT [cap], WHICH IS WHY THE TWO EXIST SEPARATELY. Every gallery
         * is now trimmed to a ceiling, but only two of them have a ceiling this client is entitled to
         * PRINT: on the rest it is the server's [DW_DEFAULT_MAX_ITEMS], and "up to 200 files" would be
         * a number this client did not read, drawn permanently under a picker, which
         * docs/DESIGN_WORKSHOP.md:231-232 forbids in as many words. The default ceiling is not left silent
         * either: the only moment it can bite is an import, and [dwCapNotice] is spoken then, naming
         * the field and what did not land.
         */
        /*
         * AND WHERE A FLOOR IS DECLARED AT THE SAME NUMBER, THIS LINE STANDS DOWN UNTIL IT BITES.
         *
         * Both motif galleries declare 25 for BOTH bounds, so drawing the floor block above and
         * "Up to 25 files — 4 more can be attached" here would say one number twice in two voices,
         * three lines apart, on the field where the count matters most. The floor block already
         * states the total, what is left and what falling short costs.
         *
         * IT COMES BACK THE MOMENT THE GALLERY IS FULL, because "is full at 25. Remove one to attach
         * another" is the only actionable sentence of the pair — the floor block says the target is
         * met and cannot say what to do about a twenty-sixth. And it is drawn unchanged wherever the
         * two numbers DIFFER, where both are real and separate facts.
         */
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
                fontSize = 11.sp
            )
        }

        /*
          WHAT AN IMPORT DROPPED, AND HOW MANY THEY WERE — a COUNT and not a list of names, which is
          the one way this region differs from the web's twin. See [dwCapNotice] for why: this card
          trims content Uris before importing them, so at the moment it has something to say the only
          handle it holds on a refused photograph is that there was one. Nothing is broken and nothing already
          attached was lost, but the designer has to know that some of what they chose did not land —
          the "resolved promise read as total success" failure that `uploadMediaBatch`'s contract on
          the web is written against, arriving here by way of the cap.

          ════════════════════════════════════════════════════════════════════════════════════════════════
          THE SENTENCE IS SPOKEN, AND THE REGION EXISTS BEFORE THERE IS ANYTHING IN IT
          ════════════════════════════════════════════════════════════════════════════════════════════════

          This is the ONLY record anywhere of which files were refused. `adopt` trims the Uri list
          before `media.attach` copies a byte, so there is no row for a refused file, no thumbnail,
          nothing in the draft and nothing on the server: the sentence IS the receipt. It was a bare
          `Text`, which means a designer using TalkBack chose nine photographs, had four silently
          declined, and had no way at all to learn it — the cap notice is invisible to them and the
          gallery below simply holds five.

          THE REGION IS COMPOSED WHETHER OR NOT THERE IS A SENTENCE IN IT. Assistive technology
          announces a CHANGE inside a region that already existed, so a region created in the same
          breath as its first message is a region whose first message is never announced — and the
          first message is the only one most imports produce. `mergeDescendants` is what makes it
          work: a live region announces a change to ITS OWN semantics and this node has no text of
          its own, so merged, the child's sentence IS this node's text and replacing it is the change
          that gets announced. The same idiom, for the same two reasons, as [DwRankableList]'s move
          announcer and `DwReviewTextBox`'s dropped-characters notice; do not invent a second one.

          ASSERTIVE AND NOT POLITE, chosen by meaning. Polite waits for a pause, and the pause after
          a gallery import is a designer walking away believing all nine photographs are attached.
          This is a refusal with a remedy in it ("Remove something first"), it is about work that has
          just been discarded, and it has to interrupt. `DwRankableList` announces a reorder POLITELY
          for the opposite reason: nothing there was lost and the list on screen already says so.
        */
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Assertive
            },
        ) {
            capNotice?.let { sentence ->
                Text(
                    sentence,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        /*
         * WHAT THE GATE TURNED AWAY — a SECOND region beside the ceiling's, and deliberately not the
         * same one.
         *
         * They are two different refusals with two different remedies. The ceiling says "this
         * gallery is full, remove something"; the gate says "this photograph is out of focus, take
         * it again". One import can produce both — twenty-eight chosen for a gallery of
         * twenty-five, three of the surviving twenty-five soft — and merging them into one sentence
         * would tell a designer that eight photographs failed for reasons they would then have to
         * disentangle. Two regions, each naming its own cause.
         *
         * IT SURVIVES THIS CARD LEAVING THE SCREEN, which the cap notice does not and does not need
         * to: the trim is synchronous and lands before the designer can scroll, while the gate takes
         * a second per photograph. See DwPhotoScreening.kt for where the state lives.
         */
        if (screening.refused.isNotEmpty()) {
            DwPhotoRefusalNotice(refused = screening.refused)
        }

        /*
         * AND WHILE IT IS STILL THINKING, SAY SO — on every gallery, not only a floored one.
         *
         * The floor block draws this into its own sentence where there is a floor. Everywhere else
         * there is no bar and no count, so without this line a designer who picks nine photographs
         * watches nothing happen for eight seconds: no row, no spinner, no sentence. That is
         * indistinguishable from the picker having failed, and the reflex it trains is to pick them
         * all again.
         */
        if (declaredFloor == null && screening.screening > 0) {
            Text(
                "Checking ${screening.screening} photograph${if (screening.screening == 1) "" else "s"} " +
                    "before ${if (screening.screening == 1) "it is" else "they are"} attached…",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
            )
        }

        if (ids.isEmpty()) {
            Text(
                if (multiple) "No photographs yet." else "Nothing attached yet.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        /*
         * THE BROWSABLE VIEW OF A CAPPED GALLERY — the handset's half of the motif carousel.
         *
         * GATED ON THE DECLARED CAP, NOT ON A FIELD KEY, which is the same rule the web applies and
         * the difference between a feature and a special case: a gallery whose ceiling somebody
         * bothered to declare is a gallery meant to be LOOKED at, and the two motif galleries are the
         * two that declare one today. Keying it to `motifPhotos`/`contemporaryMotifPhotos` by name
         * would put the app's behaviour in a `when` instead of in the registry, and the next such
         * gallery would silently not get it.
         *
         * [declaredCap] AND EMPHATICALLY NOT [cap]. Since 2026-08-26 every gallery is trimmed to a
         * ceiling — an absent `maxItems` means the server's default rather than no limit — so [cap] is
         * non-null for every other gallery too, and gating on it would put a carousel under every image
         * list in the registry. What this reads is the DECLARATION, which is the signal.
         *
         * DRAWN ABOVE THE ATTACHMENT ROWS, not instead of them. The rows are where a file is named,
         * has its caption written and is removed; the carousel is where it is judged. Replacing one
         * with the other would take the Remove control and the AI-verb row with it.
         *
         * IMAGES ONLY, and resolvable ones only. `media.resolve` reads the LOCAL descriptor index, so
         * a photograph attached in the browser answers null here — that is a different thing to draw
         * and not an error (the same case `RichTextEditor` records), and the attachment row below
         * already says so for that file. A carousel that drew a "?" tile for it would say it twice.
         */
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

        /*
         * THE FIRST PAGE OF AN ATTACHED DOCUMENT — the other half of the 2026-08-25 instruction
         * [DwDocumentPreview] is named for, and until 2026-08-26 the half nothing mounted.
         *
         * That file's own header states the requirement as TWO documents, "the designer's CV on the
         * profile screen, and the market survey write-up on stage 8". Only the first had a call site
         * (DesignerProfileScreen.kt), so stage 8's `surveyDocument` — and every other FILE field in the
         * registry with it — attached, named and opened a PDF on the handset without ever showing a
         * page of it.
         * The web mounts the same component GENERICALLY from its own FILE branch
         * (FieldInput.tsx:3103), so the two clients disagreed about the very document the instruction
         * singled out.
         *
         * MOUNTED FROM THE REGISTRY, ON THE WEB'S CONDITION, NOT FROM A FIELD KEY. `field.type == FILE`
         * and exactly one attachment, so the next document field the registry declares gets a preview
         * with no change here — the same argument the carousel above makes about the declared cap.
         *
         * THE WEB'S "not a local ref" TEST INVERTS ON THIS CLIENT, and reading it literally would have
         * mounted nothing. In the browser a `dwlocal:` reference has no `MediaFile` row, so `GET
         * /media/{id}` would 404 and a perfectly readable document would be reported as unreadable;
         * the honest condition there is "the server has acknowledged it". Here the bytes ARE the local
         * copy — `media.resolve` reads the draft's own descriptor index and hands back a path under
         * filesDir — and a document attached in the BROWSER is the one that answers null, exactly as
         * the carousel's note above records. So the condition is "this device can resolve it", which is
         * the same question asked from the other end: is there a document to draw at all.
         *
         * AND "RESOLVE" MEANS THE BYTES, NOT THE ROW — see the note at the mount itself.
         * `media.resolve` consults the descriptor index and never touches the disk, so on its own it
         * answers yes for a document this handset has a RECORD of and no copy of. Both halves are
         * tested, or the honest "is there a document to draw" becomes "is there one named".
         *
         * `remoteUrl = null` FOR THE SAME REASON. This surface has no pre-signed link and needs none;
         * [DwDocumentPreview] prefers a local file over a URL anyway, and its cache is keyed on the id
         * it is given, so nothing here fetches. NO MIME EITHER: [DwMediaItem.mediaType] is this app's
         * own category ("IMAGE", "VIDEO", "FILE") and not a MIME type, and handing "FILE" to a
         * parameter that is tested against `application/pdf` would defeat the filename test that is
         * the card's actual evidence.
         *
         * `noun = field.label.lowercase()`, NOT SENT THROUGH A NOUN-STRIPPER, which the web checked
         * rather than assumed (FieldInput.tsx:3091-3102) when [DwMediaCarousel]'s noun was found to
         * stutter: [DwDocumentPreview] appends no noun of its own, so the label lands whole in "No
         * {noun} on file." and its four sibling sentences and nothing can be doubled. Lower case
         * because those sentences need it mid-sentence; the cost is an acronym read as "designer's cv",
         * and per-label casing is a judgement for whoever owns the copy rather than a derivation to
         * guess at from a call site.
         */
        if (type == DwFieldType.FILE && ids.size == 1) {
            val document = media.resolve(ids.first())
            /*
              THE BYTES, NOT MERELY THE DESCRIPTOR. [DwMediaBridge.resolve] looks the id up in the
              draft's descriptor index and never touches the disk, so it answers non-null for a
              document this handset has a RECORD of and not a copy of — a workshop pulled down from
              the server, or one whose media directory a cleanup reclaimed.

              Handed that path unchecked, [DwDocumentPreview] takes its `looksLikePdf` branch with a
              non-null `localFile`, the render fails inside its own `runCatching`, and the card says
              "This device could not render the document. Open it to read it." over a file the Open
              button cannot open either. That is the worst of the three sentences it could say: the
              component already carries an honest one for a document whose bytes are not here, and
              `remoteUrl = null` is what reaches it once `localFile` is null.
            */
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
                enabled = enabled,
                onOpen = { item?.let { viewing = it } },
                onRemove = {
                    media.detach(id)
                    onIdsChange(if (multiple) ids.filterNot { it == id } else emptyList())
                }
            )
            /*
              DESCRIBE THIS PHOTOGRAPH, OR SUBTITLE THIS RECORDING — DIRECTLY UNDER THE TILE IT IS
              ABOUT, and never as a row of its own further down.

              A caption is a sentence about THIS file and a designer accepting one is judging it
              against THIS picture, so the control has to be attached to the tile the same way the
              caption box is: `FieldRenderer`'s own rule for a `caption_for` field is that it is drawn
              "INSIDE this field's block, directly under the media it describes", because *"a caption
              rendered as a separate input three rows below its photo is how a report comes to print
              the wrong description under a picture, permanently, in a file already delivered."* The
              same argument, applied to the control that produces one.

              A DESCRIPTOR THIS DEVICE CANNOT RESOLVE GETS A SENTENCE AND NOT SILENCE, AND THERE ARE
              TWO WAYS TO BE ONE.

              `item` is null when the bytes have gone missing, which the row above already says. It is
              ALSO null for a file this phone never imported: `media.resolve` reads `StageScreen`'s
              `mediaIndex`, which is `draft?.media.orEmpty().associateBy { it.id }` — LOCAL descriptors
              only — so a photograph attached in the browser answers null here even though the server
              certainly holds it. That was the second cause, this comment claimed the first was the
              only one, and the consequence was the worst-shaped one available: the verbs were silently
              missing on exactly the files a verb could certainly have run over, with no control and no
              sentence saying why. `RichTextEditor`'s comment on the same resolver had already recorded
              the case — *"a picture placed on the web carries a server id and answers null, which is a
              different thing to draw and not an error."*

              WHICH OF THE TWO IT IS CANNOT BE TOLD APART HERE, so the sentence names both; see
              [DW_MEDIA_VERBS_NEED_THE_FILE_HERE], which also records what the fuller repair would be.
              It is drawn only for a field type whose files could carry a verb at all
              ([dwMediaFieldMayCarryVerbs]) — a FILE field's PDF gets no control and no sentence when it
              IS resolvable, and an unresolvable one must not acquire an explanation the ordinary case
              does not have. [DwMediaAiVerbsRow] applies the same rule from the bytes' own `mediaType`;
              see the note on `dwMediaVerbsFor`.
            */
            if (item != null) {
                DwMediaAiVerbsRow(item = item, enabled = enabled)
            } else if (dwMediaFieldMayCarryVerbs(type)) {
                Text(
                    DW_MEDIA_VERBS_NEED_THE_FILE_HERE,
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        // Blur, resolution and duplicate advice about photographs that are ALREADY here. It sits
        // AFTER the rows because it is about them, and it is still incapable of touching the import:
        // by the time this composes, the photograph is copied, hashed and in the draft.
        //
        // WHAT IT HAS LEFT TO SAY, NOW THAT THE GATE STANDS UPSTREAM. Nothing a designer picks today
        // reaches here blurred or under-resolution — the gate refused it at the door. What does
        // reach here is the near-duplicate the gate admits on purpose, and every photograph that
        // never passed the gate at all: attached by an older build, attached in the browser and
        // synced down, or derived by a panel of its own. For those the card's "keep it" advice is
        // exactly right, because there is genuinely nothing else to do about them.
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
