package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.StandardValues
import com.designprototype.workshop.ui.CalendarField
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.destructiveAction
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import coil3.compose.AsyncImage
import com.designprototype.workshop.ui.FormDraftRestoredNote
import com.designprototype.workshop.ui.rememberFormDraft
import com.designprototype.workshop.RegisterUnsavedGuard
import com.designprototype.workshop.data.AddressReferenceDto
import com.designprototype.workshop.data.DesignerProfileDto
import com.designprototype.workshop.data.DesignerProfileUpdateBody
import com.designprototype.workshop.data.LocationRequest
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.toLocationRequest
import com.designprototype.workshop.ui.ArtisanPhoneField
import com.designprototype.workshop.ui.DESIGNER_LOCATION_SUBJECT
import com.designprototype.workshop.ui.FieldDateField
import com.designprototype.workshop.ui.ExperienceFields
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.LocationFieldsSection
import com.designprototype.workshop.ui.RecordProseField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.artisanPhoneValidationError
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.formatFieldDate
import com.designprototype.workshop.ui.listIsAnswerable
import com.designprototype.workshop.ui.addressListNotice
import com.designprototype.workshop.ui.rememberAddressReference
import com.designprototype.workshop.ui.recordDocFromStored
import com.designprototype.workshop.report.toPlain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

@Immutable
internal data class ProfileForm(
    val displayName: String = "",
    val localName: String = "",
    val designation: String = "",
    val institution: String = "",
    val department: String = "",
    val qualification: String = "",
    val specialisation: String = "",
    val experienceYears: String = "",
    val experienceMonths: String = "",
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
    val cvMediaId: String = "",
    val empanelmentNo: String = "",
    val empanelmentDate: LocalDate? = null,
    val empanelmentGrace: Boolean = false,
    val location: LocationRequest? = null,
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
    experienceMonths = experienceMonths?.toString().orEmpty(),
    biography = biography.orEmpty(),
    phone = StandardValues.phoneForEditing(phoneCountryCode, phone),
    email = email.orEmpty(),
    website = website.orEmpty(),
    addressLine = addressLine.orEmpty(),
    city = city.orEmpty(),
    state = state.orEmpty(),
    pincode = pincode.orEmpty(),
    photoMediaId = photoMediaId.orEmpty(),
    signatureMediaId = signatureMediaId.orEmpty(),
    cvMediaId = cvMediaId.orEmpty(),
    empanelmentNo = empanelmentNo.orEmpty(),
    empanelmentGrace = empanelmentNoMissing == true,
    empanelmentDate = empanelmentDate
        ?.take(10)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    location = location?.toLocationRequest(),
)

internal fun designerProfileHasUnsavedEdits(typed: ProfileForm, saved: ProfileForm): Boolean =
    typed != saved

internal val DESIGNER_PROFILE_REQUIRED_LABELS: Map<String, String> = linkedMapOf(
    "displayName" to "Name as printed",
    "qualification" to "Qualification",
    "phone" to "Phone",
    "email" to "Email",
)

private fun ProfileForm.requiredValue(column: String): String = when (column) {
    "displayName" -> displayName
    "qualification" -> qualification
    "phone" -> phone
    "email" -> email
    else -> ""
}

internal fun designerProfileMissingRequired(form: ProfileForm): List<String> =
    DESIGNER_PROFILE_REQUIRED_LABELS.keys.filter { form.requiredValue(it).isBlank() }

internal fun designerProfileRequiredRefusal(missing: List<String>): String {
    val labels = missing.mapNotNull { DESIGNER_PROFILE_REQUIRED_LABELS[it] }
    if (labels.isEmpty()) return ""
    val named = when (labels.size) {
        1 -> labels.first()
        else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
    }
    return if (labels.size == 1) {
        "$named is required on a designer profile — it is printed on every report generated under " +
            "this name, so it cannot be left blank."
    } else {
        "$named are required on a designer profile — they are printed on every report generated " +
            "under this name, so they cannot be left blank."
    }
}

internal fun designerEmailRefusal(email: String): String? {
    val text = email.trim()
    if (text.isEmpty()) return null
    val fault = "Enter a valid email, e.g. meera@nid.ac.in."
    if (text.any { it.isWhitespace() }) return fault
    val at = text.indexOf('@')
    if (at <= 0 || at != text.lastIndexOf('@')) return fault
    val domain = text.substring(at + 1)
    if (domain.length < 3 || !domain.contains('.')) return fault
    if (domain.startsWith('.') || domain.endsWith('.') || domain.contains("..")) return fault
    return null
}

private const val ADDRESS_LINE_MAX = 300

internal val DESIGNER_PROFILE_DICTATED: Set<String> = linkedSetOf(
    "displayName",
    "localName",
    "designation",
    "institution",
    "department",
    "qualification",
    "specialisation",
    "biography",
    "addressLine",
    "city",
)

internal val DESIGNER_PROFILE_NOT_DICTATED: Map<String, String> = linkedMapOf(
    "experienceYears" to
        "A closed list of whole numbers, 0 to 70, answered by picking rather than by typing. A " +
            "recogniser spells digits out in words — \"twelve\" — and there is no text box here for " +
            "the words to land in, so a spoken answer would be discarded with nothing on screen " +
            "to say why.",
    "experienceMonths" to
        "The months half of the same pair, and the same closed list of whole numbers, 0 to 11. " +
            "Worse than the years box for dictation rather than better: \"six\" and \"sixteen\" " +
            "differ by one syllable and only one of them is inside the range, so the near-miss is " +
            "the likely outcome and it is invisible once stored.",
    "phone" to
        "Digits, inside ArtisanPhoneField's own dial-code column and shape rule. Spoken digits are " +
            "the least reliable thing a recogniser returns, and artisanPhoneValidationError would " +
            "refuse most of what came back — a control that reliably produces a refusal.",
    "email" to
        "A recogniser writes \"at\" for the @ sign and punctuates a domain, so this box would " +
            "reliably produce a value designerEmailRefusal then refuses. It also saves in one PUT " +
            "with twenty other columns, so a refusal here costs the designer twenty correct answers.",
    "website" to
        "The same as the e-mail box and worse: a spoken URL arrives with spaces in it and its dots " +
            "spelled out as words.",
    "state" to
        "A closed vocabulary of thirty-six names answered by picking, not by typing. The corpus is " +
            "grouped by this column, so a near-miss transcription is the one error that would " +
            "quietly file a designer in a state they have never worked in.",
    "pincode" to
        "Six digits, filtered at the keystroke like the years box. A mis-heard digit inside a " +
            "fixed-length code is invisible — it is still six digits and still looks like a PIN code.",
    "empanelmentNo" to
        "An identifier transcribed from a government order. A mis-heard character is not an " +
            "annoyance, it is a wrong number printed on every report signed under this name, and " +
            "nothing downstream can tell it from a right one. The same class as the artisan form's " +
            "Aadhaar and Pehchan boxes, which have no microphone for the same reason.",
    "empanelmentDate" to
        "A calendar field, excluded by name in the instruction. FieldDateField reads dd/mm/yyyy; a " +
            "recogniser answers \"the third of February twenty nineteen\", and both readings of an " +
            "ambiguous spoken date are valid dates, so the mistake is not reportable.",
    "photoMediaId" to
        "A media slot — a camera button and a gallery picker. There is no text here to speak.",
    "signatureMediaId" to
        "A media slot, as the photograph above it. There is no text here to speak.",
    "cvMediaId" to
        "A media slot: a document picker, a preview and a Remove button. There is no text here to " +
            "speak, and the filename comes from the file.",
    "location" to
        "Not a box at all — it is the whole address card: two closed dropdowns, a map picker, a " +
            "GPS reading and its accuracy radius. Its one free-text answer, the village, has a " +
            "microphone of its own inside that card, which is where the classification for it " +
            "belongs. A microphone at this level would have nothing to write into.",
    "empanelmentGrace" to
        "Not a box at all, and not a value anybody types: it is the SERVER'S answer to \"may " +
            "this profile be saved without an empanelment number\", read off " +
            "DesignerProfileDto.empanelmentNoMissing and used to draw one banner and to soften " +
            "one refusal. It is classified here rather than filtered out of the test because a " +
            "field in neither table is indistinguishable from a box somebody forgot to " +
            "classify, which is the whole point of the pair.",
)

private fun dictates(column: String): Boolean = column in DESIGNER_PROFILE_DICTATED

internal fun ProfileForm.toBody(
    stored: ProfileForm,
    storedAddressLine: String,
): DesignerProfileUpdateBody = DesignerProfileUpdateBody(
    displayName = displayName,
    localName = localName,
    designation = designation,
    institution = institution,
    department = department,
    qualification = qualification,
    specialisation = specialisation,
    experienceYears = experienceYears.trim().toIntOrNull(),
    experienceMonths = experienceMonths.trim().toIntOrNull(),
    biography = biography,
    phone = phone,
    email = email,
    website = website,
    addressLine = if (recordDocFromStored(addressLine) == recordDocFromStored(storedAddressLine)) {
        storedAddressLine
    } else {
        addressLine
    },
    city = city,
    state = state,
    pincode = pincode,
    photoMediaId = photoMediaId,
    signatureMediaId = signatureMediaId,
    cvMediaId = cvMediaId,
    empanelmentNo = empanelmentNo,
    empanelmentDate = empanelmentDate?.toString(),
    location = location.takeIf { it != stored.location },
)

internal enum class ProfileMediaSlot { PHOTOGRAPH, SIGNATURE, CV }

@Composable
fun DesignerProfileScreen(
    repository: WorkshopRepository,
    targetUserId: String? = null,
    targetLabel: String? = null,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    val viewer = remember(repository) { repository.cachedUser() }
    val canEdit = remember(viewer, targetUserId) { mayEditDesignerProfile(viewer, targetUserId) }
    val editingSomebodyElse = viewer != null && targetUserId != null && targetUserId != viewer.id

    var form by remember(targetUserId) { mutableStateOf(ProfileForm()) }
    var saved by remember(targetUserId) { mutableStateOf(ProfileForm()) }
    var storedAddressLine by remember(targetUserId) { mutableStateOf("") }
    var loading by remember(targetUserId) { mutableStateOf(true) }
    var saving by remember(targetUserId) { mutableStateOf(false) }
    var uploading by remember(targetUserId) { mutableStateOf<ProfileMediaSlot?>(null) }
    var loadFailed by remember(targetUserId) { mutableStateOf(false) }
    var experienceError by remember(targetUserId) { mutableStateOf<String?>(null) }
    var enforceRequired by remember(targetUserId) { mutableStateOf(false) }
    val referenceState = rememberAddressReference(repository)
    val reference: AddressReferenceDto = referenceState.reference
    var addressNeedsCoordinate by remember(targetUserId) { mutableStateOf(false) }
    var localPreview by remember(targetUserId) { mutableStateOf<Map<ProfileMediaSlot, File>>(emptyMap()) }
    var remotePreview by remember(targetUserId) { mutableStateOf<Map<ProfileMediaSlot, String>>(emptyMap()) }
    var cvDescriptor by remember(targetUserId) { mutableStateOf<Pair<String?, String?>>(null to null) }

    LaunchedEffect(targetUserId) {
        loading = true
        loadFailed = false
        runCatching { repository.designerProfile(targetUserId, context) }
            .onSuccess { stored ->
                val loaded = stored?.toForm() ?: ProfileForm()
                form = loaded
                saved = loaded
                storedAddressLine = stored?.addressLine.orEmpty()
            }
            .onFailure { error ->
                loadFailed = true
                onError(error.apiErrorMessage("Could not load this designer profile."))
            }
        loading = false
    }

    LaunchedEffect(form.photoMediaId, form.signatureMediaId, form.cvMediaId) {
        val wanted = listOfNotNull(
            form.photoMediaId.takeIf { it.isNotBlank() }?.let { ProfileMediaSlot.PHOTOGRAPH to it },
            form.signatureMediaId.takeIf { it.isNotBlank() }?.let { ProfileMediaSlot.SIGNATURE to it },
            form.cvMediaId.takeIf { it.isNotBlank() }?.let { ProfileMediaSlot.CV to it },
        )
        var resolved = remotePreview
        val rows = coroutineScope {
            wanted.map { (slot, id) -> async { slot to repository.mediaItemOrNull(id) } }.awaitAll()
        }
        rows.forEach { (slot, found) ->
            val item = found ?: return@forEach
            if (slot == ProfileMediaSlot.CV) cvDescriptor = item.originalFilename to item.mimeType
            val url = item.url ?: return@forEach
            resolved = resolved + (slot to url)
        }
        remotePreview = resolved
    }

    val profileDraft = rememberFormDraft(
        key = if (!loading && !loadFailed && canEdit) "designer-profile:$targetUserId" else null,
        snapshot = {
            mapOf(
                "displayName" to form.displayName, "localName" to form.localName,
                "designation" to form.designation, "institution" to form.institution,
                "department" to form.department, "qualification" to form.qualification,
                "specialisation" to form.specialisation, "experienceYears" to form.experienceYears,
                "experienceMonths" to form.experienceMonths, "biography" to form.biography,
                "phone" to form.phone, "email" to form.email, "website" to form.website,
                "addressLine" to form.addressLine, "city" to form.city, "state" to form.state,
                "pincode" to form.pincode, "empanelmentNo" to form.empanelmentNo,
            )
        },
        restore = { v ->
            form = form.copy(
                displayName = v["displayName"] ?: form.displayName,
                localName = v["localName"] ?: form.localName,
                designation = v["designation"] ?: form.designation,
                institution = v["institution"] ?: form.institution,
                department = v["department"] ?: form.department,
                qualification = v["qualification"] ?: form.qualification,
                specialisation = v["specialisation"] ?: form.specialisation,
                experienceYears = v["experienceYears"] ?: form.experienceYears,
                experienceMonths = v["experienceMonths"] ?: form.experienceMonths,
                biography = v["biography"] ?: form.biography,
                phone = v["phone"] ?: form.phone,
                email = v["email"] ?: form.email,
                website = v["website"] ?: form.website,
                addressLine = v["addressLine"] ?: form.addressLine,
                city = v["city"] ?: form.city,
                state = v["state"] ?: form.state,
                pincode = v["pincode"] ?: form.pincode,
                empanelmentNo = v["empanelmentNo"] ?: form.empanelmentNo,
            )
        },
    )

    fun saveProfile() {
        if (!mayEditDesignerProfile(repository.cachedUser(), targetUserId)) {
            onError(
                "You can only edit your own profile."
            )
            return
        }
        val missing = designerProfileMissingRequired(form)
        val empanelmentFault = if (!form.empanelmentGrace && form.empanelmentNo.isBlank()) {
            "Empanelment number is required."
        } else {
            null
        }
        val emailFault = designerEmailRefusal(form.email)
        val phoneFault = artisanPhoneValidationError(form.phone)
        val years = form.experienceYears.trim()
        val parsedYears = years.toIntOrNull()
        val yearsFault = if (years.isNotEmpty() && (parsedYears == null || parsedYears !in 0..70)) {
            "Years must be 0–70."
        } else {
            null
        }
        val months = form.experienceMonths.trim()
        val parsedMonths = months.toIntOrNull()
        val monthsFault = if (months.isNotEmpty() && (parsedMonths == null || parsedMonths !in 0..11)) {
            "Months must be 0–11."
        } else {
            null
        }
        val addressFault = if (addressNeedsCoordinate) {
            "Add a map point (GPS, pin or coordinates) to save the address — or clear the fields under '${DESIGNER_LOCATION_SUBJECT.heading}'."
        } else {
            null
        }
        experienceError = yearsFault ?: monthsFault
        if (missing.isNotEmpty() || emailFault != null || phoneFault != null || yearsFault != null ||
            monthsFault != null || addressFault != null || empanelmentFault != null
        ) {
            enforceRequired = true
            onError(
                listOfNotNull(
                    designerProfileRequiredRefusal(missing).takeIf { it.isNotEmpty() },
                    empanelmentFault,
                    emailFault,
                    phoneFault,
                    yearsFault,
                    monthsFault,
                    addressFault,
                ).joinToString(" ")
            )
            return
        }
        saving = true
        scope.launch {
            runCatching {
                repository.saveDesignerProfileOrQueue(appContext, targetUserId, form.toBody(saved, storedAddressLine))
            }
                .onSuccess { stored ->
                    if (stored == null) {
                        saved = form
                        profileDraft.discard()
                        onMessage(
                            "Offline — saved on this phone; it uploads automatically."
                        )
                        return@onSuccess
                    }
                    val confirmed = stored.toForm()
                    form = confirmed
                    saved = confirmed
                    storedAddressLine = stored.addressLine.orEmpty()
                    profileDraft.discard()
                    onMessage("Designer profile saved.")
                }
                .onFailure { error ->
                    onError(error.apiErrorMessage("Could not save the designer profile."))
                }
            saving = false
        }
    }

    RegisterUnsavedGuard(dirty = canEdit && designerProfileHasUnsavedEdits(form, saved), draft = profileDraft) { saveProfile() }

    var pendingSlot by remember { mutableStateOf<ProfileMediaSlot?>(null) }
    var pendingCapture by remember { mutableStateOf<Pair<Uri, File>?>(null) }

    fun uploadInto(slot: ProfileMediaSlot, durable: File) {
        uploading = slot
        scope.launch {
            runCatching {
                repository.uploadMedia(
                    context = appContext,
                    uri = durableFileUri(appContext, durable),
                    linkedRecordType = null,
                    linkedRecordId = null,
                    caption = slot.caption(),
                    location = null,
                    titleHint = slot.caption(),
                    processingRequests = emptyList(),
                )
            }.onSuccess { uploaded ->
                localPreview = localPreview + (slot to durable)
                form = when (slot) {
                    ProfileMediaSlot.PHOTOGRAPH -> form.copy(photoMediaId = uploaded.id)
                    ProfileMediaSlot.SIGNATURE -> form.copy(signatureMediaId = uploaded.id)
                    ProfileMediaSlot.CV -> form.copy(cvMediaId = uploaded.id)
                }
                onMessage("${slot.caption()} uploaded. Save to keep it.")
            }.onFailure { error ->
                onError(error.apiErrorMessage("Could not upload the ${slot.midSentence()}."))
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
                .onFailure {
                    if (it is CancellationException) throw it
                    onError("Couldn't read that file. Try again.")
                }
        }
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingSlot = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { copyIntoProfileMedia(appContext, uri) }
                .onSuccess { durable -> uploadInto(ProfileMediaSlot.CV, durable) }
                .onFailure {
                    if (it is CancellationException) throw it
                    onError("Couldn't read that document. Try again.")
                }
        }
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val slot = pendingSlot
        val capture = pendingCapture
        pendingSlot = null
        pendingCapture = null
        if (!success || slot == null || capture == null) {
            capture?.second?.takeIf { it.length() == 0L }?.delete()
            return@rememberLauncherForActivityResult
        }
        uploadInto(slot, capture.second)
    }

    val profileCamera = com.designprototype.workshop.ui.rememberPermissionGate(onError)

    fun startCapture(slot: ProfileMediaSlot) {
        profileCamera.run(com.designprototype.workshop.ui.CAMERA_PERMISSION, com.designprototype.workshop.ui.CAMERA_REFUSED_SENTENCE) {
            val durable = newProfileMediaFile(appContext, slot.filePrefix(), ".jpg")
            pendingSlot = slot
            pendingCapture = durableFileUri(appContext, durable) to durable
            takePhoto.launch(pendingCapture!!.first)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            if (editingSomebodyElse) "Designer profile" else "My designer profile",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            if (editingSomebodyElse) {
                "Editing ${targetLabel ?: "another designer"}'s profile as admin. Printed on their reports."
            } else {
                "Printed on the cover and signature of your reports."
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }

            loadFailed -> Text(
                "Couldn't load this profile. Go back and reopen online — don't edit the empty fields.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodyMedium
            )

            else -> {
                if (!canEdit) {
                    ReadOnlyNotice(viewer)
                }
                FormDraftRestoredNote(profileDraft)

                if (form.empanelmentGrace) {
                    Text(
                        "Add your empanelment number — required on reports.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                MaterialTheme.shapes.small
                            )
                            .padding(12.dp)
                    )
                }
                ProfileSection("Name and standing") {
                    ProfileText(
                        "Name as printed", form.displayName, canEdit,
                        help = "As shown on the cover and signature.",
                        required = true,
                        dictate = dictates("displayName"),
                        resetKey = targetUserId,
                        error = requiredRefusal(enforceRequired, form.displayName, "Name as printed")
                    ) { form = form.copy(displayName = it) }
                    ProfileText(
                        "Name in local script", form.localName, canEdit,
                        help = "Printed exactly as typed.",
                        dictate = dictates("localName"),
                        resetKey = targetUserId
                    ) { form = form.copy(localName = it) }
                    ProfileText(
                        "Designation", form.designation, canEdit,
                        dictate = dictates("designation"),
                        resetKey = targetUserId
                    ) {
                        form = form.copy(designation = it)
                    }
                    ProfileText(
                        "Institution", form.institution, canEdit,
                        dictate = dictates("institution"),
                        resetKey = targetUserId
                    ) {
                        form = form.copy(institution = it)
                    }
                    ProfileText(
                        "Department", form.department, canEdit,
                        dictate = dictates("department"),
                        resetKey = targetUserId
                    ) {
                        form = form.copy(department = it)
                    }
                    ProfileText(
                        "Qualification", form.qualification, canEdit,
                        required = true,
                        dictate = dictates("qualification"),
                        resetKey = targetUserId,
                        error = requiredRefusal(enforceRequired, form.qualification, "Qualification")
                    ) {
                        form = form.copy(qualification = it)
                    }
                    ProfileText(
                        "Specialisation", form.specialisation, canEdit,
                        dictate = dictates("specialisation"),
                        resetKey = targetUserId
                    ) {
                        form = form.copy(specialisation = it)
                    }
                    ExperienceFields(
                        years = form.experienceYears,
                        months = form.experienceMonths,
                        enabled = canEdit,
                        maxYears = 70,
                        error = experienceError,
                        onYearsChange = { form = form.copy(experienceYears = it); experienceError = null },
                        onMonthsChange = { form = form.copy(experienceMonths = it); experienceError = null }
                    )
                }

                ProfileSection("Profile paragraph") {
                    Text(
                        "Printed in stage 3 of every report.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    RecordProseField(
                        label = "Biography",
                        value = form.biography,
                        onValueChange = { form = form.copy(biography = it) },
                        enabled = canEdit,
                        minLines = 5,
                        rich = true,
                        dictate = dictates("biography"),
                        resetKey = targetUserId,
                    )
                }

                ProfileSection("Contact") {
                    ArtisanPhoneField(
                        value = form.phone,
                        error = artisanPhoneValidationError(form.phone)
                            ?: requiredRefusal(enforceRequired, form.phone, "Phone"),
                        required = true,
                        onValueChange = { if (canEdit) form = form.copy(phone = it) }
                    )
                    ProfileText(
                        "Email", form.email, canEdit,
                        keyboard = KeyboardType.Email,
                        help = "Printed on reports; can differ from your sign-in email.",
                        required = true,
                        dictate = dictates("email"),
                        error = if (!enforceRequired) {
                            null
                        } else {
                            designerEmailRefusal(form.email)
                                ?: requiredRefusal(true, form.email, "Email")
                        }
                    ) { form = form.copy(email = it) }
                    ProfileText(
                        "Website", form.website, canEdit,
                        keyboard = KeyboardType.Uri,
                        dictate = dictates("website")
                    ) {
                        form = form.copy(website = it)
                    }
                }

                ProfileSection("Postal address") {
                    Text(
                        "Printed on your reports.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    RecordProseField(
                        label = "Address line",
                        value = form.addressLine,
                        onValueChange = { next -> form = form.copy(addressLine = next) },
                        enabled = canEdit,
                        rich = true,
                        keepFormatting = true,
                        dictate = dictates("addressLine"),
                        resetKey = targetUserId,
                        below = {
                            if (designerProfileAddressText(form.addressLine).length >= ADDRESS_LINE_MAX) {
                                Text(
                                    "Full — $ADDRESS_LINE_MAX characters max.",
                                    color = MaterialTheme.field.muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                    )
                    val stateRows = stateSelectOptions(form.state, reference)
                    val stateNotice = addressListNotice(
                        "states", stateRows.size, referenceState, bundled = referenceState.bundled
                    )
                    SearchableSelectField(
                        label = "State / union territory",
                        options = stateRows,
                        selectedValue = form.state,
                        enabled = canEdit && listIsAnswerable(stateRows),
                        emptyMessage = stateNotice,
                        searchable = true,
                        onSelect = { picked ->
                            form = form.copy(state = picked)
                        }
                    )
                    stateNotice?.takeIf { listIsAnswerable(stateRows) }?.let { line ->
                        Text(line, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    DistrictOrTown(
                        state = form.state,
                        value = form.city,
                        enabled = canEdit,
                        reference = reference,
                        resetKey = targetUserId,
                        onValueChange = { form = form.copy(city = it) }
                    )
                    ProfileText(
                        "PIN code", form.pincode, canEdit,
                        keyboard = KeyboardType.Number,
                        dictate = dictates("pincode")
                    ) {
                        form = form.copy(pincode = it.filter { ch -> ch.isDigit() }.take(6))
                    }
                }

                ProfileSection("District and map point") {
                    Text(
                        "Used to place you on the map. The address above is what reports print.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp
                    )
                    if (canEdit) {
                        LocationFieldsSection(
                            repository = repository,
                            value = form.location,
                            onChange = { next -> form = form.copy(location = next) },
                            required = false,
                            isEdit = true,
                            subject = DESIGNER_LOCATION_SUBJECT,
                            referenceState = referenceState,
                            onStatedAddressNeedsCoordinate = { addressNeedsCoordinate = it },
                            onMessage = onMessage
                        )
                    } else {
                        StoredLocationSummary(form.location)
                    }
                }

                ProfileSection("Empanelment") {
                    ProfileText(
                        "Empanelment number", form.empanelmentNo, canEdit,
                        required = true,
                        error = if (form.empanelmentGrace) {
                            null
                        } else {
                            requiredRefusal(enforceRequired, form.empanelmentNo, "Empanelment number")
                        },
                        dictate = dictates("empanelmentNo")
                    ) {
                        form = form.copy(empanelmentNo = it)
                    }
                    CalendarField(
                        label = "Empanelment date",
                        value = form.empanelmentDate,
                        clearable = true,
                        onValueChange = { day -> if (canEdit) form = form.copy(empanelmentDate = day) },
                        supportingText = form.empanelmentDate?.let { "Saved as ${formatFieldDate(it)}" }
                    )
                }

                ProfileSection("Photograph, signature and CV") {
                    Text(
                        "Photo and signature appear in reports. The CV is not. Reports name it, but a report file cannot carry a document, so send the CV alongside the report.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp
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

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CV", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "PDF, .docx, .odt or a scan.",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (uploading == ProfileMediaSlot.CV) {
                            Text("Uploading…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        DwDocumentPreview(
                            mediaId = form.cvMediaId,
                            noun = "CV",
                            localFile = localPreview[ProfileMediaSlot.CV],
                            remoteUrl = remotePreview[ProfileMediaSlot.CV],
                            displayName = cvDescriptor.first ?: localPreview[ProfileMediaSlot.CV]?.name,
                            mimeType = cvDescriptor.second
                        )
                        ActionRow {
                            OutlinedButton(
                                onClick = {
                                    pendingSlot = ProfileMediaSlot.CV
                                    pickDocument.launch(
                                        arrayOf(
                                            "application/pdf",
                                            "application/msword",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                            "application/vnd.oasis.opendocument.text",
                                            "image/*"
                                        )
                                    )
                                },
                                enabled = canEdit && uploading == null,
                            ) {
                                Icon(
                                    Icons.Filled.AttachFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (form.cvMediaId.isBlank()) "Attach CV" else "Replace CV")
                            }
                            if (form.cvMediaId.isNotBlank()) {
                                TextButton(modifier = Modifier.destructiveAction(), 
                                    onClick = {
                                        form = form.copy(cvMediaId = "")
                                        localPreview = localPreview - ProfileMediaSlot.CV
                                        remotePreview = remotePreview - ProfileMediaSlot.CV
                                        cvDescriptor = null to null
                                    },
                                    enabled = canEdit && uploading == null
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Remove")
                                }
                            }
                        }
                    }
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
                        "Clearing a box clears the value, except the * fields and your district/map point.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

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
                    "Only its owner or an admin can change it."
                }
            )
        },
        color = MaterialTheme.field.onWarningContainer,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

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
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(color = MaterialTheme.field.hairline)
            content()
        }
    }
}

internal fun designerProfileOneLine(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ')

internal fun designerProfileAddressText(stored: String?): String =
    designerProfileOneLine(toPlain(recordDocFromStored(stored)))

@Composable
private fun ProfileText(
    label: String,
    value: String,
    enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
    help: String? = null,
    required: Boolean = false,
    error: String? = null,
    dictate: Boolean = false,
    resetKey: Any? = null,
    below: @Composable () -> Unit = {},
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
        help?.let { Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall) }
        RecordProseField(
            label = if (required) "$label *" else label,
            value = value,
            onValueChange = { next ->
                onValueChange(designerProfileOneLine(next))
            },
            enabled = enabled,
            dictate = dictate,
            keyboardType = keyboard,
            errorText = error,
            resetKey = resetKey,
            below = below,
        )
    }
}

@Composable
private fun StoredLocationSummary(location: LocationRequest?) {
    if (location == null) {
        Text(
            "No district or map point yet.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    val stated = listOfNotNull(
        location.village?.takeIf { it.isNotBlank() },
        location.district?.takeIf { it.isNotBlank() },
        location.state?.takeIf { it.isNotBlank() },
        location.pincode?.takeIf { it.isNotBlank() }
    ).joinToString(", ")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Stated: " + stated.ifEmpty { "not recorded" },
            color = MaterialTheme.field.body,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Captured at ${readableCoordinate(location.latitude)}, ${readableCoordinate(location.longitude)} (the device's location).",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun readableCoordinate(value: Double): String = String.format(Locale.UK, "%.6f", value)

private fun requiredRefusal(enforce: Boolean, value: String, label: String): String? =
    if (enforce && value.isBlank()) "$label is required" else null

@Composable
private fun DistrictOrTown(
    state: String,
    value: String,
    enabled: Boolean,
    reference: AddressReferenceDto,
    resetKey: Any?,
    onValueChange: (String) -> Unit,
) {
    val districts = remember(state, reference) { reference.districts?.byState?.get(state).orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ProfileText(
            "Town / city", value, enabled,
            dictate = dictates("city"),
            resetKey = resetKey,
            onValueChange = onValueChange,
        )
        if (districts.isNotEmpty()) {
            SearchableSelectField(
                label = "…or pick the district",
                options = remember(districts) { districts.map { SelectOption(it, it) } },
                selectedValue = districts.firstOrNull { it.equals(value, ignoreCase = true) }.orEmpty(),
                enabled = enabled,
                onSelect = { picked -> if (picked.isNotBlank()) onValueChange(picked) }
            )
        } else if (state.isNotBlank()) {
            Text(
                "No districts listed for $state. Type the town above.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

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
    val model: Any? = localFile ?: remoteUrl
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(slot.caption(), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            ) {
                when {
                    busy -> FieldCircularProgressIndicator(modifier = Modifier.size(20.dp))
                    model != null -> AsyncImage(
                        model = model,
                        contentDescription = slot.caption(),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp)
                    )
                    mediaId.isNotBlank() -> Text(
                        "On file",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    else -> Text("None", color = MaterialTheme.field.placeholder, style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionRow {
                    OutlinedButton(onClick = onCapture, enabled = enabled) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Camera")
                    }
                    OutlinedButton(onClick = onPick, enabled = enabled) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Gallery")
                    }
                }
                if (mediaId.isNotBlank()) {
                    ActionRow {
                        TextButton(modifier = Modifier.destructiveAction(), onClick = onClear, enabled = enabled) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}

internal fun ProfileMediaSlot.caption(): String = when (this) {
    ProfileMediaSlot.PHOTOGRAPH -> "Photograph"
    ProfileMediaSlot.SIGNATURE -> "Signature"
    ProfileMediaSlot.CV -> "CV"
}

internal fun ProfileMediaSlot.midSentence(): String = when (this) {
    ProfileMediaSlot.PHOTOGRAPH -> "photograph"
    ProfileMediaSlot.SIGNATURE -> "signature"
    ProfileMediaSlot.CV -> "CV"
}

private fun ProfileMediaSlot.filePrefix(): String = when (this) {
    ProfileMediaSlot.PHOTOGRAPH -> "designer-photo-"
    ProfileMediaSlot.SIGNATURE -> "designer-signature-"
    ProfileMediaSlot.CV -> "designer-cv-"
}

private fun profileMediaDir(context: Context): File =
    File(context.filesDir, "designer-profile/media").apply { mkdirs() }

private fun newProfileMediaFile(context: Context, prefix: String, suffix: String): File =
    File(profileMediaDir(context), "$prefix${UUID.randomUUID()}$suffix")

private fun durableFileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private suspend fun copyIntoProfileMedia(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val extension = context.contentResolver.getType(uri)
        ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        ?: "jpg"
    val target = File(profileMediaDir(context), "designer-picked-${UUID.randomUUID()}.$extension")
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Couldn't read the selected file.")
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
