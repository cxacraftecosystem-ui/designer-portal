package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
// The app-level unsaved-work guard, which owns the Save / Discard / Keep editing prompt and is wired
// into the system back gesture, the header arrow and every menu row. Reused rather than reinvented:
// a second prompt with its own wording is two answers to one question.
import com.designprototype.workshop.RegisterUnsavedGuard
import com.designprototype.workshop.data.AddressReferenceDto
import com.designprototype.workshop.data.DesignerProfileDto
import com.designprototype.workshop.data.DesignerProfileUpdateBody
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.ui.AddressReferenceCache
import com.designprototype.workshop.ui.ArtisanPhoneField
import com.designprototype.workshop.ui.FieldDateField
import com.designprototype.workshop.ui.FieldPermissions
// The shared record-form prose box: on-device dictation and the rich editor, both opt-in.
import com.designprototype.workshop.ui.RecordProseField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text. Without this import the bare
// `Text` below resolves to Material's, which inherits whatever family LocalTextStyle happens to
// carry and quietly sets every heading on this screen in the body face.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.formatFieldDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.UUID

/**
 * The signed-in designer's own `DesignerProfile` — every column of it — and the one screen an admin
 * uses to correct somebody else's.
 *
 * WHY THIS SCREEN IS WORTH ITS SIZE. These twenty values are typed once and then copied onto the
 * cover, the signature block and the "Designer's profile" paragraph of EVERY report the designer
 * generates. A report is a document delivered to a ministry under a named individual's empanelment
 * number; a cover page that reads "Designer:" followed by nothing is not a cosmetic defect, it is a
 * submission that comes back. So the screen renders the whole row rather than a convenient subset,
 * and the save sends the whole row rather than a diff (see [DesignerProfileUpdateBody]).
 *
 * ── WHO MAY WRITE ────────────────────────────────────────────────────────────────────────────────
 *
 * The owner, and an admin. Nobody else — and that rule is ENFORCED here rather than expressed by
 * hiding the Save button. Hiding a control is a statement about a layout; a recomposition triggered
 * by anything at all (a role refresh landing, a configuration change, a future caller passing a
 * different `targetUserId`) can put the control back, and a disabled button is one `enabled` typo
 * away from being live. [saveProfile] therefore re-derives the permission from the cached account at
 * the moment of the tap and refuses in words. The server refuses too; this exists so that the refusal
 * a user actually sees explains itself instead of arriving as a 403 after they typed for ten minutes.
 *
 * ── OFFLINE ──────────────────────────────────────────────────────────────────────────────────────
 *
 * Deliberately NOT offline-capable, unlike the 22 stages next door. A stage is fieldwork done in a
 * courtyard and its local draft IS the document. A profile is a single row typed once, at a desk, and
 * a local copy of it would introduce exactly one new failure: two devices holding different
 * biographies and the later sync silently winning. A failure here is reported and the form keeps what
 * was typed, so a retry costs nothing.
 *
 * ── LEAVING WITH SOMETHING TYPED ─────────────────────────────────────────────────────────────────
 *
 * That paragraph is about PERSISTING a draft across sessions, and it stands. It says nothing about
 * the back press inside one session, which is a different failure with a different fix: the screen
 * registers with the app's [RegisterUnsavedGuard], so the system back gesture, the header arrow and
 * every menu row route through the same Save / Discard / Keep editing prompt the record forms use.
 * Nothing is written to disk and no second copy of the row exists — the text stays in memory and the
 * departure is simply questioned.
 *
 * It is worth its five lines because of where it runs. A browser Back is a deliberate click on a
 * small target; a handset's is an edge swipe that fires on a thumb-slip, and this screen holds the
 * longest free-text box in the app (`minLines = 5`) — the paragraph that prints under "Designer's
 * profile" in stage 3 of every report. The web guards the same form the same way
 * (`useLeaveGuard(dirty, …)` in `DesignerProfileForm.tsx`), including the detail that its "Save"
 * saves and STAYS rather than leaving: a designer who chose Save asked for their words to be kept,
 * not for the screen to close.
 */

/**
 * Everything the form holds, as text, because that is what the boxes edit.
 *
 * INTERNAL and a `data class`, both load-bearing: the unsaved-work rule is a comparison against the
 * snapshot the server last confirmed (see [designerProfileHasUnsavedEdits]), so the generated
 * `equals` IS the rule, and it is reachable from a test.
 */
@Immutable
internal data class ProfileForm(
    val displayName: String = "",
    val localName: String = "",
    val designation: String = "",
    val institution: String = "",
    val department: String = "",
    val qualification: String = "",
    val specialisation: String = "",
    /**
     * Kept as TEXT, not as an Int.
     *
     * A number-typed box backed by an Int has no representation for "the user has cleared the box on
     * the way to typing a different number", so the field either snaps back to 0 mid-edit or refuses
     * the deletion. It is parsed once, on save, where the 0..70 bound the server enforces is checked
     * with it.
     */
    val experienceYears: String = "",
    val biography: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val addressLine: String = "",
    val city: String = "",
    val state: String = "",
    val pincode: String = "",
    val photoMediaId: String = "",
    val signatureMediaId: String = "",
    val empanelmentNo: String = "",
    val empanelmentDate: LocalDate? = null,
)

internal fun DesignerProfileDto.toForm(): ProfileForm = ProfileForm(
    displayName = displayName.orEmpty(),
    localName = localName.orEmpty(),
    designation = designation.orEmpty(),
    institution = institution.orEmpty(),
    department = department.orEmpty(),
    qualification = qualification.orEmpty(),
    specialisation = specialisation.orEmpty(),
    experienceYears = experienceYears?.toString().orEmpty(),
    biography = biography.orEmpty(),
    phone = phone.orEmpty(),
    email = email.orEmpty(),
    website = website.orEmpty(),
    addressLine = addressLine.orEmpty(),
    city = city.orEmpty(),
    state = state.orEmpty(),
    pincode = pincode.orEmpty(),
    photoMediaId = photoMediaId.orEmpty(),
    signatureMediaId = signatureMediaId.orEmpty(),
    empanelmentNo = empanelmentNo.orEmpty(),
    // A stored value this build cannot parse degrades to "no date" instead of throwing during
    // composition, which would take the whole screen down over one malformed string in one column.
    empanelmentDate = empanelmentDate
        ?.take(10)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
)

/**
 * Is there typing (or an uploaded photograph) on this screen that the server does not have?
 *
 * A DIFF AGAINST THE LAST CONFIRMED SNAPSHOT, not a flag raised by the controls. The web raises its
 * flag from `onInput` and then has to remember to raise it BY HAND for every control that fires no
 * input event — its own state dropdown, its date picker and both media slots each carry a manual
 * `markDirty`, and that list is exactly the kind that goes stale when a field is added. Here every
 * box writes into one immutable [ProfileForm], so comparing it with what was loaded cannot miss a
 * control: a field added next year is covered the day it is added.
 *
 * It also gets the retraction right for free. A designer who types a word into the biography and
 * deletes it again has nothing unsaved, and a prompt at that point teaches them to dismiss the
 * prompt — which must still mean something ten minutes later when there IS a paragraph in the box.
 *
 * [saved] is re-seeded from the SERVER's answer after every save, so any normalisation it applied (a
 * trimmed website, a lower-cased email) is part of the comparison rather than a permanent phantom
 * difference that would leave the screen dirty forever and prompt on every single departure.
 */
internal fun designerProfileHasUnsavedEdits(typed: ProfileForm, saved: ProfileForm): Boolean =
    typed != saved

private fun ProfileForm.toBody(): DesignerProfileUpdateBody = DesignerProfileUpdateBody(
    displayName = displayName,
    localName = localName,
    designation = designation,
    institution = institution,
    department = department,
    qualification = qualification,
    specialisation = specialisation,
    experienceYears = experienceYears.trim().toIntOrNull(),
    biography = biography,
    phone = phone,
    email = email,
    website = website,
    addressLine = addressLine,
    city = city,
    state = state,
    pincode = pincode,
    photoMediaId = photoMediaId,
    signatureMediaId = signatureMediaId,
    empanelmentNo = empanelmentNo,
    empanelmentDate = empanelmentDate?.toString(),
)

/** What the two capture controls are currently doing, so one upload cannot be started twice. */
private enum class ProfileMediaSlot { PHOTOGRAPH, SIGNATURE }

@Composable
fun DesignerProfileScreen(
    repository: WorkshopRepository,
    /**
     * Whose profile this is. NULL means the signed-in account's own, and null is not the same thing
     * as passing the viewer's own id: null routes to `/designers/me/profile`, which the server binds
     * to the bearer token and which therefore cannot be pointed at anybody else even by a client bug.
     */
    targetUserId: String? = null,
    /** Who [targetUserId] is, for the header, when an admin arrived here from the roster. */
    targetLabel: String? = null,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    val viewer = remember(repository) { repository.cachedUser() }
    // Derived once from the cached account and the target, and re-derived inside the save so that a
    // stale composition can never be the thing that authorises a write. See the class KDoc.
    val canEdit = remember(viewer, targetUserId) { mayEditDesignerProfile(viewer, targetUserId) }
    val editingSomebodyElse = viewer != null && targetUserId != null && targetUserId != viewer.id

    var form by remember(targetUserId) { mutableStateOf(ProfileForm()) }
    /**
     * The last state of the form the SERVER has confirmed — what was loaded, or what came back from
     * the most recent save. The unsaved-work rule is the difference between this and [form]; see
     * [designerProfileHasUnsavedEdits].
     */
    var saved by remember(targetUserId) { mutableStateOf(ProfileForm()) }
    var loading by remember(targetUserId) { mutableStateOf(true) }
    var saving by remember(targetUserId) { mutableStateOf(false) }
    var uploading by remember(targetUserId) { mutableStateOf<ProfileMediaSlot?>(null) }
    var loadFailed by remember(targetUserId) { mutableStateOf(false) }
    var experienceError by remember(targetUserId) { mutableStateOf<String?>(null) }
    var reference by remember { mutableStateOf(AddressReferenceDto()) }
    /**
     * The durable local copy of a photograph captured or picked in THIS session, keyed by slot.
     *
     * Shown in preference to re-fetching the uploaded file, so the designer sees the picture they
     * just took immediately and on a connection that cannot serve it back. It is a `File` under
     * `filesDir` and never a picker Uri: a content Uri is a permission grant scoped to the task that
     * received it, so the preview would go blank the moment the process is recycled.
     */
    var localPreview by remember(targetUserId) { mutableStateOf<Map<ProfileMediaSlot, File>>(emptyMap()) }
    /** The stored file's fetchable URL, when the server is willing to serve one back. */
    var remotePreview by remember(targetUserId) { mutableStateOf<Map<ProfileMediaSlot, String>>(emptyMap()) }

    // ── Load ─────────────────────────────────────────────────────────────────────────────────────
    LaunchedEffect(targetUserId) {
        loading = true
        loadFailed = false
        runCatching { repository.designerProfile(targetUserId) }
            .onSuccess { stored ->
                // Null is the ORDINARY state of a designer who has signed in and never opened this
                // screen: the row is created by the first save. An empty form is the correct
                // rendering of it, and reporting it as an error would greet every new designer with
                // a red line on the page they were sent here to fill in.
                //
                // Both halves are seeded together, always. A load that moved `form` and left `saved`
                // behind would make a freshly-opened profile announce unsaved changes on the way out
                // of a screen nobody had typed in.
                val loaded = stored?.toForm() ?: ProfileForm()
                form = loaded
                saved = loaded
            }
            .onFailure { error ->
                loadFailed = true
                onError(error.apiErrorMessage("Could not load this designer profile."))
            }
        loading = false
    }

    // The state list, read from the cache first so the dropdown is populated on the first frame of a
    // phone that has ever been online, then refreshed. A fetch that comes back empty-handed — which
    // is what a captive-portal HTML page decodes to — is discarded rather than allowed to blank a
    // list that was working.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AddressReferenceCache.read(appContext) }?.let { reference = it }
        val fresh = runCatching { repository.addressReference() }.getOrNull() ?: return@LaunchedEffect
        if (fresh.statesAndUnionTerritories.isEmpty() && fresh.states.isEmpty()) return@LaunchedEffect
        reference = fresh
        withContext(Dispatchers.IO) { AddressReferenceCache.write(appContext, fresh) }
    }

    // Resolve whatever the stored media ids point at, so a profile filled in on the web shows its
    // photograph here. A null URL is not an error and must not be reported as one: the server
    // withholds it whenever this account may not download that uploader's files.
    LaunchedEffect(form.photoMediaId, form.signatureMediaId) {
        val wanted = listOfNotNull(
            form.photoMediaId.takeIf { it.isNotBlank() }?.let { ProfileMediaSlot.PHOTOGRAPH to it },
            form.signatureMediaId.takeIf { it.isNotBlank() }?.let { ProfileMediaSlot.SIGNATURE to it },
        )
        var resolved = remotePreview
        wanted.forEach { (slot, id) ->
            val url = runCatching { repository.mediaItem(id) }.getOrNull()?.url ?: return@forEach
            resolved = resolved + (slot to url)
        }
        remotePreview = resolved
    }

    fun saveProfile() {
        // RE-DERIVED AT THE MOMENT OF THE TAP, from the cached account rather than from the captured
        // `canEdit`. The disabled button above is a courtesy; this is the rule.
        if (!mayEditDesignerProfile(repository.cachedUser(), targetUserId)) {
            onError(
                "You can only edit your own designer profile. Ask an administrator to change " +
                    "somebody else's."
            )
            return
        }
        val years = form.experienceYears.trim()
        if (years.isNotEmpty()) {
            val parsed = years.toIntOrNull()
            if (parsed == null || parsed < 0 || parsed > 70) {
                // Bounded exactly as the server bounds it, and as the registry's `designerExperience`
                // field is, because this value is COPIED into that field when a workshop is created.
                // A profile that accepted 400 years would prefill a stage the stage's own validator
                // then rejects, and the designer would be told their workshop has an error in a box
                // they never typed in.
                experienceError = "Years of experience must be a whole number between 0 and 70."
                return
            }
        }
        experienceError = null
        saving = true
        scope.launch {
            runCatching { repository.saveDesignerProfile(targetUserId, form.toBody()) }
                .onSuccess { stored ->
                    // Re-seeded from the SERVER's answer rather than left as typed, so any
                    // normalisation it applied (a trimmed website, a lower-cased email) is what the
                    // designer is looking at when they walk away — and so the unsaved-work
                    // comparison is against what was actually stored, not against what was typed.
                    val confirmed = stored.toForm()
                    form = confirmed
                    saved = confirmed
                    onMessage("Designer profile saved.")
                }
                .onFailure { error ->
                    onError(error.apiErrorMessage("Could not save the designer profile."))
                }
            saving = false
        }
    }

    // ── Leaving with something typed ─────────────────────────────────────────────────────────────
    //
    // The app's ONE unsaved-work mechanism, reused whole: the system back gesture, the header arrow
    // and every menu row already route through it, so registering here is the entire fix and there
    // is no second dialog and no second wording. `onSave` is the screen's own validated save — the
    // same one the button runs, refusing in words for an account that may not write, and holding a
    // years-of-experience outside 0..70 on the form instead of leaving.
    //
    // `canEdit` is in the condition rather than left to the diff. Every control on this screen is
    // already disabled for a read-only viewer, so the diff cannot move; the clause is here so that a
    // future control that forgets `enabled = canEdit` cannot offer somebody a Save they will only be
    // refused.
    RegisterUnsavedGuard(dirty = canEdit && designerProfileHasUnsavedEdits(form, saved)) { saveProfile() }

    // ── Media capture ────────────────────────────────────────────────────────────────────────────
    //
    // Two slots, one path. Whatever the source — camera or gallery — the bytes are first copied into
    // `filesDir/designer-profile/media/` and only then uploaded from that copy. Uploading straight
    // from the picker's Uri would work most of the time and fail exactly when it matters: the grant
    // is scoped to the task that received it, so an upload that is still running when the process is
    // recycled reads from a Uri it no longer holds. And the camera cannot be pointed at cacheDir,
    // which Android reclaims silently under storage pressure — the failure being a signature that
    // uploaded fine on Tuesday and is a blank frame in Friday's report.
    var pendingSlot by remember { mutableStateOf<ProfileMediaSlot?>(null) }
    var pendingCapture by remember { mutableStateOf<Pair<Uri, File>?>(null) }

    fun uploadInto(slot: ProfileMediaSlot, durable: File) {
        uploading = slot
        scope.launch {
            runCatching {
                repository.uploadMedia(
                    context = appContext,
                    uri = durableFileUri(appContext, durable),
                    // Deliberately unlinked to any record. A profile photograph belongs to a PERSON,
                    // and tagging it onto whichever workshop happened to be open would file it in
                    // that workshop's media list, where a later cleanup of the workshop would take
                    // the designer's signature with it.
                    linkedRecordType = null,
                    linkedRecordId = null,
                    caption = slot.caption(),
                    location = null,
                    titleHint = slot.caption(),
                    // Empty and not null: null lets the repository infer, and inference is what
                    // queues an AUDIO file for transcription. Nothing here is ever audio, but an
                    // explicit empty list means a future change to that inference cannot start
                    // billing transcription for a signature scan.
                    processingRequests = emptyList(),
                )
            }.onSuccess { uploaded ->
                localPreview = localPreview + (slot to durable)
                form = when (slot) {
                    ProfileMediaSlot.PHOTOGRAPH -> form.copy(photoMediaId = uploaded.id)
                    ProfileMediaSlot.SIGNATURE -> form.copy(signatureMediaId = uploaded.id)
                }
                onMessage("${slot.caption()} uploaded. Save the profile to keep it.")
            }.onFailure { error ->
                // The durable copy is deliberately LEFT ON DISK on a failure. It costs a few hundred
                // kilobytes and it is the difference between "tap upload again" and "go back and
                // photograph the signed sheet again", which on a scanned signature may not be
                // possible at all.
                onError(error.apiErrorMessage("Could not upload the ${slot.caption().lowercase()}."))
            }
            uploading = null
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri == null || slot == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { copyIntoProfileMedia(appContext, uri) }
                .onSuccess { durable -> uploadInto(slot, durable) }
                .onFailure { onError("That file could not be read. Try picking it again.") }
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val slot = pendingSlot
        val capture = pendingCapture
        pendingSlot = null
        pendingCapture = null
        if (!success || slot == null || capture == null) {
            // A cancelled capture leaves a zero-byte file the camera opened and never wrote. Removed
            // here rather than swept later, because the sweep would have to guess which empty files
            // are abandoned captures and which are uploads still in flight.
            capture?.second?.takeIf { it.length() == 0L }?.delete()
            return@rememberLauncherForActivityResult
        }
        uploadInto(slot, capture.second)
    }

    fun startCapture(slot: ProfileMediaSlot) {
        val durable = newProfileMediaFile(appContext, slot.filePrefix(), ".jpg")
        pendingSlot = slot
        pendingCapture = durableFileUri(appContext, durable) to durable
        takePhoto.launch(pendingCapture!!.first)
    }

    // ── Render ───────────────────────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            if (editingSomebodyElse) "Designer profile" else "My designer profile",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )
        Text(
            if (editingSomebodyElse) {
                "You are editing ${targetLabel ?: "another designer"}'s profile as an administrator. " +
                    "These values are printed on every report they generate."
            } else {
                "Typed once. Every design & prototype workshop report you generate prints these " +
                    "values on its cover, in its signature block and in its designer's profile."
            },
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }

            // A failed LOAD is not the same as an empty profile, and the difference has to be on
            // screen: an empty form after a failed read invites the designer to retype twenty values
            // over the top of ones the server still holds, and the save would then be a real edit.
            loadFailed -> Text(
                "This profile could not be read, so nothing below is safe to edit — the boxes are " +
                    "empty because the request failed, not because the profile is. Go back and open " +
                    "this screen again once you have a connection.",
                color = MaterialTheme.field.warning,
                fontSize = 13.sp
            )

            else -> {
                if (!canEdit) {
                    ReadOnlyNotice(viewer)
                }

                ProfileSection("Name and standing") {
                    ProfileText(
                        "Name as printed", form.displayName, canEdit,
                        help = "How your name appears on the cover and in the signature block."
                    ) { form = form.copy(displayName = it) }
                    ProfileText(
                        "Name in local script", form.localName, canEdit,
                        help = "Printed verbatim, in whatever script you type it in."
                    ) { form = form.copy(localName = it) }
                    ProfileText("Designation", form.designation, canEdit) {
                        form = form.copy(designation = it)
                    }
                    ProfileText("Institution", form.institution, canEdit) {
                        form = form.copy(institution = it)
                    }
                    ProfileText("Department", form.department, canEdit) {
                        form = form.copy(department = it)
                    }
                    ProfileText("Qualification", form.qualification, canEdit) {
                        form = form.copy(qualification = it)
                    }
                    ProfileText("Specialisation", form.specialisation, canEdit) {
                        form = form.copy(specialisation = it)
                    }
                    OutlinedTextField(
                        value = form.experienceYears,
                        onValueChange = {
                            // Digits only, at the keystroke. The number pad still offers a minus on
                            // some OEM keyboards, and "-5" would reach a server bound of 0..70 as a
                            // 422 whose message names a field the designer cannot see.
                            form = form.copy(experienceYears = it.filter { ch -> ch.isDigit() }.take(2))
                            experienceError = null
                        },
                        label = { Text("Years of experience") },
                        singleLine = true,
                        enabled = canEdit,
                        isError = experienceError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    experienceError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }

                ProfileSection("Profile paragraph") {
                    Text(
                        "Printed under \"Designer's profile\" in stage 3 of every report.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                    /*
                     * THE LARGEST PROSE BOX IN THIS APP OUTSIDE A STAGE SCREEN, AND THE ONE WHERE
                     * FORMATTING DEMONSTRABLY REACHES A READER.
                     *
                     * `DesignerProfile.biography` is a plain `String?` column that `designers.py`
                     * copies into the registry key `designerProfile`, which `stage_definitions.py`
                     * declares as RICH_TEXT with `report_role=NARR` — so a rich renderer for this
                     * text ALREADY EXISTS and already runs, on the "Designer's profile" page of
                     * every report. It works today only because of the string-as-prose rule
                     * (`RichText.fromJson` reads a bare string as an unformatted document), which
                     * is exactly the property `recordStoredFromDoc` preserves by writing flattened
                     * text back into the column. Nothing about the storage changes here; what
                     * changes is that a paragraph break or a list typed on the phone survives into
                     * the report instead of being a run-on line.
                     *
                     * It also gets the microphone. Five lines about your own practice, written on a
                     * phone, is the definition of a box somebody abandons after two sentences.
                     */
                    RecordProseField(
                        label = "Biography",
                        value = form.biography,
                        onValueChange = { form = form.copy(biography = it) },
                        enabled = canEdit,
                        minLines = 5,
                        rich = true,
                        dictate = true,
                        // Re-seed when a different designer's profile is opened into this
                        // composition — an admin stepping through the roster is the case. Without it
                        // the editor would keep the first profile's document open under the second
                        // profile's name. `targetUserId` is what identifies whose profile this is;
                        // null (the signed-in account's own) is a stable key of its own.
                        resetKey = targetUserId,
                    )
                }

                ProfileSection("Contact") {
                    // The ISD-prefix editor, reused whole rather than rebuilt. Its own inner caption
                    // reads "Phone" while nothing sits above it here, so there is no duplication —
                    // and rebuilding it would mean rebuilding the measured dial column, the country
                    // search and the foreign-resident confirmation it already carries.
                    ArtisanPhoneField(
                        value = form.phone,
                        error = null,
                        onValueChange = { if (canEdit) form = form.copy(phone = it) }
                    )
                    ProfileText(
                        "Email", form.email, canEdit,
                        keyboard = KeyboardType.Email,
                        help = "The address printed on the report, which need not be your sign-in address."
                    ) { form = form.copy(email = it) }
                    ProfileText("Website", form.website, canEdit, keyboard = KeyboardType.Uri) {
                        form = form.copy(website = it)
                    }
                }

                ProfileSection("Address") {
                    ProfileText("Address line", form.addressLine, canEdit) {
                        form = form.copy(addressLine = it)
                    }
                    // The served list, with a stored value kept at the FRONT until the list arrives —
                    // the same rule the record forms' state dropdown follows. Without it, a profile
                    // that already holds a state shows "Select" over it on a phone that has not
                    // fetched the reference yet, which reads as "not answered" and invites the
                    // designer to answer it again, differently.
                    SearchableSelectField(
                        label = "State / union territory",
                        options = stateSelectOptions(form.state, reference),
                        selectedValue = form.state,
                        enabled = canEdit,
                        onSelect = { picked ->
                            // Changing the state invalidates a district chosen under the old one.
                            // Kept rather than cleared, deliberately: clearing would silently delete
                            // a town the designer typed, and a district that no longer matches the
                            // state is visible to them and fixable, where a blank box is neither.
                            form = form.copy(state = picked)
                        }
                    )
                    DistrictOrTown(
                        state = form.state,
                        value = form.city,
                        enabled = canEdit,
                        reference = reference,
                        onValueChange = { form = form.copy(city = it) }
                    )
                    ProfileText("PIN code", form.pincode, canEdit, keyboard = KeyboardType.Number) {
                        form = form.copy(pincode = it.filter { ch -> ch.isDigit() }.take(6))
                    }
                }

                ProfileSection("Empanelment") {
                    ProfileText("Empanelment number", form.empanelmentNo, canEdit) {
                        form = form.copy(empanelmentNo = it)
                    }
                    FieldDateField(
                        label = "Empanelment date",
                        value = form.empanelmentDate,
                        clearable = true,
                        onValueChange = { day -> if (canEdit) form = form.copy(empanelmentDate = day) },
                        // Confirms in words what the eight typed digits mean: the box shows
                        // dd/mm/yyyy on every handset regardless of locale, and a reader cannot tell
                        // whether that is the app's choice or the phone's.
                        supportingText = form.empanelmentDate?.let { "Saved as ${formatFieldDate(it)}" }
                    )
                }

                ProfileSection("Photograph and signature") {
                    Text(
                        "Both are printed: the photograph on the designer's profile page, the " +
                            "signature in the block an officer counter-signs.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                    ProfileMediaRow(
                        slot = ProfileMediaSlot.PHOTOGRAPH,
                        mediaId = form.photoMediaId,
                        localFile = localPreview[ProfileMediaSlot.PHOTOGRAPH],
                        remoteUrl = remotePreview[ProfileMediaSlot.PHOTOGRAPH],
                        busy = uploading == ProfileMediaSlot.PHOTOGRAPH,
                        enabled = canEdit && uploading == null,
                        onCapture = { startCapture(ProfileMediaSlot.PHOTOGRAPH) },
                        onPick = { pendingSlot = ProfileMediaSlot.PHOTOGRAPH; pickImage.launch("image/*") },
                        onClear = {
                            form = form.copy(photoMediaId = "")
                            localPreview = localPreview - ProfileMediaSlot.PHOTOGRAPH
                            remotePreview = remotePreview - ProfileMediaSlot.PHOTOGRAPH
                        }
                    )
                    ProfileMediaRow(
                        slot = ProfileMediaSlot.SIGNATURE,
                        mediaId = form.signatureMediaId,
                        localFile = localPreview[ProfileMediaSlot.SIGNATURE],
                        remoteUrl = remotePreview[ProfileMediaSlot.SIGNATURE],
                        busy = uploading == ProfileMediaSlot.SIGNATURE,
                        enabled = canEdit && uploading == null,
                        onCapture = { startCapture(ProfileMediaSlot.SIGNATURE) },
                        onPick = { pendingSlot = ProfileMediaSlot.SIGNATURE; pickImage.launch("image/*") },
                        onClear = {
                            form = form.copy(signatureMediaId = "")
                            localPreview = localPreview - ProfileMediaSlot.SIGNATURE
                            remotePreview = remotePreview - ProfileMediaSlot.SIGNATURE
                        }
                    )
                }

                if (canEdit) {
                    Button(
                        onClick = ::saveProfile,
                        enabled = !saving && uploading == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (saving) "Saving…" else "Save profile")
                    }
                    Text(
                        "An empty box CLEARS that value on the server. Nothing here is left behind " +
                            "when you delete it.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// --------------------------------------------------------------------------------------
// The rule
// --------------------------------------------------------------------------------------

/**
 * May [viewer] write the profile of [targetUserId] (null meaning their own)?
 *
 * A FUNCTION AND NOT A REMEMBERED BOOLEAN, so that the screen's disabled controls and the save's own
 * refusal are provably the same rule rather than two readings of it that can drift by one clause.
 * Both call this.
 *
 * The owner needs the DESIGNER rank as well as ownership: a `DesignerProfile` is only ever printed on
 * a design-workshop report, and only a designer can generate one, so a volunteer filling in twenty
 * boxes nothing would ever read back is a form the app should not accept — and the server refuses it
 * anyway. An admin needs no rank beyond being an admin; correcting an empanelment number for
 * somebody who has lost their phone is precisely the job.
 */
private fun mayEditDesignerProfile(viewer: UserDto?, targetUserId: String?): Boolean {
    if (viewer == null) return false
    if (FieldPermissions.isAdmin(viewer)) return true
    val ownProfile = targetUserId == null || targetUserId == viewer.id
    return ownProfile && FieldPermissions.canRunDesignWorkshops(viewer)
}

@Composable
private fun ReadOnlyNotice(viewer: UserDto?) {
    Text(
        buildString {
            append("This profile is read-only for you. ")
            append(
                if (viewer == null) {
                    "Sign in again and reopen this screen."
                } else {
                    "Only the designer it belongs to, or an administrator, can change it."
                }
            )
        },
        color = MaterialTheme.field.onWarningContainer,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

// --------------------------------------------------------------------------------------
// Chrome
// --------------------------------------------------------------------------------------

@Composable
private fun ProfileSection(title: String, content: @Composable () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(color = MaterialTheme.field.hairline)
            content()
        }
    }
}

@Composable
private fun ProfileText(
    label: String,
    value: String,
    enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
    help: String? = null,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
        help?.let { Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp) }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * The town or city, which is a `city` column with a DISTRICT vocabulary behind it.
 *
 * Two controls for one column, and the pairing is the point. The register the app already ships is a
 * controlled list of districts, and picking from it is what makes a profile's address comparable with
 * every artisan record in the corpus — but a designer lives in Bagru, not in "Jaipur", and a select
 * with no escape would either force the wrong answer or leave the box permanently empty. So the
 * searchable district select FILLS the box, and the box itself stays typeable. Whichever way the value
 * arrives, one column is written.
 *
 * The select is hidden entirely when the register serves no districts for the chosen state, rather
 * than rendered empty: a picker that opens onto nothing reads as a broken control, and the honest
 * reading is that this deployment's reference data does not go that deep yet.
 */
@Composable
private fun DistrictOrTown(
    state: String,
    value: String,
    enabled: Boolean,
    reference: AddressReferenceDto,
    onValueChange: (String) -> Unit,
) {
    val districts = remember(state, reference) { reference.districts?.byState?.get(state).orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Town / city") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        if (districts.isNotEmpty()) {
            SearchableSelectField(
                label = "…or pick the district",
                options = remember(districts) { districts.map { SelectOption(it, it) } },
                // Reflects the box only when the box holds a district BY NAME, so typing "Bagru"
                // leaves the picker showing nothing selected rather than claiming a district the
                // designer did not choose.
                selectedValue = districts.firstOrNull { it.equals(value, ignoreCase = true) }.orEmpty(),
                enabled = enabled,
                onSelect = { picked -> if (picked.isNotBlank()) onValueChange(picked) }
            )
        } else if (state.isNotBlank()) {
            Text(
                "This deployment does not have a district list for $state, so type the town above.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
    }
}

/** The served state list, with a stored value kept at the front until the list arrives. */
private fun stateSelectOptions(current: String, reference: AddressReferenceDto): List<SelectOption> {
    val served = reference.statesAndUnionTerritories.ifEmpty { reference.states }
    val known = served.any { it.equals(current, ignoreCase = true) }
    val all = if (current.isNotBlank() && !known) listOf(current) + served else served
    return all.map { SelectOption(it, it) }
}

@Composable
private fun ProfileMediaRow(
    slot: ProfileMediaSlot,
    mediaId: String,
    localFile: File?,
    remoteUrl: String?,
    busy: Boolean,
    enabled: Boolean,
    onCapture: () -> Unit,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    // The local copy wins over the server's URL. It is the picture the designer just took, it renders
    // with no network, and it is the only one that exists in the seconds between the upload finishing
    // and the profile being saved.
    val model: Any? = localFile ?: remoteUrl
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(slot.caption(), color = MaterialTheme.field.muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            ) {
                when {
                    busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    model != null -> AsyncImage(
                        model = model,
                        contentDescription = slot.caption(),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp)
                    )
                    // A stored id with nothing to draw is REPORTED rather than shown as an empty
                    // frame: it means the file exists but this account may not download it, which is
                    // a permission fact and not a missing photograph.
                    mediaId.isNotBlank() -> Text(
                        "On file",
                        color = MaterialTheme.field.muted,
                        fontSize = 10.sp
                    )
                    else -> Text("None", color = MaterialTheme.field.placeholder, fontSize = 10.sp)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCapture, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Camera")
                    }
                    OutlinedButton(onClick = onPick, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Gallery")
                    }
                }
                if (mediaId.isNotBlank()) {
                    TextButton(onClick = onClear, enabled = enabled) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove")
                    }
                }
            }
        }
    }
}

private fun ProfileMediaSlot.caption(): String = when (this) {
    ProfileMediaSlot.PHOTOGRAPH -> "Photograph"
    ProfileMediaSlot.SIGNATURE -> "Signature"
}

private fun ProfileMediaSlot.filePrefix(): String = when (this) {
    ProfileMediaSlot.PHOTOGRAPH -> "designer-photo-"
    ProfileMediaSlot.SIGNATURE -> "designer-signature-"
}

// --------------------------------------------------------------------------------------
// Durable capture
// --------------------------------------------------------------------------------------

/**
 * Where a profile photograph or signature lands before it is uploaded.
 *
 * `filesDir`, and never `cacheDir`. The app's ordinary capture path (`createAppFile`,
 * MainActivity.kt) writes into `cacheDir/field-captures/`, which Android reclaims under storage
 * pressure — silently, with no callback, and preferentially when space is tight, which on a 32 GB
 * field phone two weeks into a study is always. A signature scanned on Tuesday and re-uploaded from
 * cache on Friday is a blank frame in the report, and nothing anywhere says why.
 */
private fun profileMediaDir(context: Context): File =
    File(context.filesDir, "designer-profile/media").apply { mkdirs() }

private fun newProfileMediaFile(context: Context, prefix: String, suffix: String): File =
    File(profileMediaDir(context), "$prefix${UUID.randomUUID()}$suffix")

/**
 * The `content://` Uri for a durable file, through the app's own FileProvider.
 *
 * A `file://` Uri would be rejected by the camera on API 24+ (FileUriExposedException) and, on the
 * upload path, would give `ContentResolver.getType` nothing to answer with — so every photograph
 * would be uploaded as `application/octet-stream` and stored as a DOCUMENT rather than an IMAGE,
 * which is what decides whether the report writer will lay it out as a picture at all.
 */
private fun durableFileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/**
 * Copy a picked file out of its content Uri and into [profileMediaDir], returning the durable copy.
 *
 * On [Dispatchers.IO] and with an explicit `fd.sync()`: without the sync the bytes are only in the
 * page cache, and a flat battery leaves a file the directory says is 400 KB and the disk says is
 * zeros. There is exactly one photograph; it is worth the fsync.
 */
private suspend fun copyIntoProfileMedia(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val extension = context.contentResolver.getType(uri)
        ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        ?: "jpg"
    val target = File(profileMediaDir(context), "designer-picked-${UUID.randomUUID()}.$extension")
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Unable to read the selected file — the grant may have lapsed.")
    try {
        input.use { source ->
            FileOutputStream(target).use { out ->
                source.copyTo(out, DEFAULT_COPY_BUFFER)
                out.flush()
                out.fd.sync()
            }
        }
    } catch (e: Throwable) {
        runCatching { target.delete() }
        throw e
    }
    target
}

private const val DEFAULT_COPY_BUFFER = 64 * 1024
