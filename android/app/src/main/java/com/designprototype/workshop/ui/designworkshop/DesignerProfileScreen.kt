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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
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
// The address card the six field-record forms use, whole. `DesignerProfile` is the seventh owner of
// `Location`, so the district and the map point are captured by the control that already knows how
// — with its coarse-fix guard, its flag-never-rewrite rule and its offline sentences — rather than
// by a seventh reimplementation of an address.
import com.designprototype.workshop.ui.LocationFieldsSection
// The shared record-form prose box: on-device dictation and the rich editor, both opt-in.
import com.designprototype.workshop.ui.RecordProseField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text. Without this import the bare
// `Text` below resolves to Material's, which inherits whatever family LocalTextStyle happens to
// carry and quietly sets every heading on this screen in the body face.
import com.designprototype.workshop.ui.Text
// The shape rule the artisan form and `FieldRenderer`'s PHONE arm already apply to a stored number
// (10 digits for +91, 4–14 otherwise). Reused rather than restated: the web's `PhoneField` enforces
// exactly this through its mirror's `pattern` AND surfaces it inline, so a handset that only checked
// "not blank" would accept a phone the browser refuses on the same record.
import com.designprototype.workshop.ui.artisanPhoneValidationError
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.formatFieldDate
import com.designprototype.workshop.ui.listIsAnswerable
import com.designprototype.workshop.ui.addressListNotice
import com.designprototype.workshop.ui.rememberAddressReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * The signed-in designer's own `DesignerProfile` — every column of it — and the one screen an admin
 * uses to correct somebody else's.
 *
 * WHY THIS SCREEN IS WORTH ITS SIZE. These two dozen values are typed once and then copied onto the
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
    /**
     * The MONTHS half, 0..11, and text for the same reason [experienceYears] is.
     *
     * "" IS "NOT STATED" AND IT IS NOT 0. The picker offers a blank row above the numbers and this
     * is what that row writes; [toBody] turns it into an explicit JSON null, which un-answers the
     * question on the server. A designer who has never touched the control must not have "and no
     * odd months" recorded on their behalf, and a designer who deliberately picked 0 must have it
     * kept — so the two cannot be allowed to collapse into one another anywhere on this path.
     */
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
    /** The designer's CV. A media id like the two above; usually a PDF, sometimes a scanned sheet. */
    val cvMediaId: String = "",
    val empanelmentNo: String = "",
    val empanelmentDate: LocalDate? = null,
    /**
     * The designer's STATED ADDRESS AND MAP POINT — the `Location` row this profile now relates to.
     *
     * ── IT DOES NOT REPLACE THE FOUR FLAT COLUMNS ABOVE, AND BOTH ARE ON SCREEN ────────────────
     *
     * `addressLine`, `city`, `state` and `pincode` are still where every live designer's address
     * actually is: the migration that added the relation backfilled NOTHING, because
     * `Location.latitude`/`longitude` are NOT NULL and a row cannot be manufactured for an address
     * that never had a coordinate without inventing one. They are also the four values the report
     * prefill copies into stage 3. What the `Location` row adds is the DISTRICT and the POINT,
     * which no flat column can hold. Until the retiring migration moves the values across, a
     * profile may carry an address in either place — so this screen renders both, and a reader
     * that showed one of them would show some designers a blank where their address is.
     *
     * SEEDED FROM THE STORED ROW ON LOAD, and that is not optional. `attach_location` writes a
     * BRAND NEW `Location` row out of whatever body it is handed and never patches the stored one,
     * so a card that opened empty over a profile that HAS a district would PATCH that district away
     * the moment somebody touched the map — successfully, with nothing on screen to say so. That is
     * the defect [LocationDto.toLocationRequest] exists to prevent, and why it carries every column.
     *
     * NULL MEANS "NO POINT", NEVER "REMOVE THE ADDRESS". A `LocationRequest` cannot exist without a
     * coordinate, so this stays null for every designer who has not given one; [toBody] then omits
     * the key and the stored row is left alone. The API refuses an explicit null outright.
     */
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
    phone = phone.orEmpty(),
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
    // A stored value this build cannot parse degrades to "no date" instead of throwing during
    // composition, which would take the whole screen down over one malformed string in one column.
    empanelmentDate = empanelmentDate
        ?.take(10)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    // EVERY COLUMN OF THE STORED ROW, through the shared converter. A field this misses is not
    // rejected on the next save, it is ERASED — see [ProfileForm.location].
    location = location?.toLocationRequest(),
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

// --------------------------------------------------------------------------------------
// The four boxes that must be answered
// --------------------------------------------------------------------------------------

/**
 * The mandatory columns, in screen order, each under THE LABEL THIS HANDSET DRAWS ABOVE IT.
 *
 * ── WHY THE HANDSET HAS THE RULE AT ALL, WHEN THE SERVER ALREADY REFUSES ────────────────────────
 *
 * `DesignerProfileUpdate._mandatory_columns_may_not_be_cleared` (backend/app/schemas/designers.py)
 * refuses a SUPPLIED null-or-blank name, qualification, phone or e-mail, and
 * [designerProfileUpdateJson] folds an empty box to an explicit JSON `null` — so a handset save with
 * the name deleted is ALREADY refused, as a 422 arriving after the request, naming a column. That is
 * the rule working; it is not the refusal a person should meet. The designer has scrolled past six
 * cards to reach Save, the box at fault is somewhere above the fold, and a round trip is what they
 * get for it. This is the same rule met in the box, before anything is sent.
 *
 * ── THE LABELS ARE THIS CLIENT'S, NOT THE SERVER'S, AND THAT IS DELIBERATE ──────────────────────
 *
 * `REQUIRED_PROFILE_COLUMNS` on the server (and `DESIGNER_PROFILE_LABELS` on the web) call the first
 * one "Name". The box a designer is looking at HERE says "Name as printed" — this screen has always
 * named it that, because on your own profile "Name" is ambiguous between the account and the cover
 * page. A refusal has to name the box the reader can see, so the local sentence uses the local
 * label and the server's sentence keeps the server's. The four COLUMNS are identical, which is what
 * has to match, and `DesignerProfileRequiredFieldsTest` pins the keys against the server's list.
 *
 * A `LinkedHashMap`, because the order is read: the refusal names the missing boxes in the order
 * they are passed on the way down the screen, so "Name as printed and Email" sends the designer
 * upwards once rather than twice.
 */
internal val DESIGNER_PROFILE_REQUIRED_LABELS: Map<String, String> = linkedMapOf(
    "displayName" to "Name as printed",
    "qualification" to "Qualification",
    "phone" to "Phone",
    "email" to "Email",
)

/** One mandatory box's current contents, by column name. Keyed the way the API keys them. */
private fun ProfileForm.requiredValue(column: String): String = when (column) {
    "displayName" -> displayName
    "qualification" -> qualification
    "phone" -> phone
    "email" -> email
    else -> ""
}

/**
 * Which of [DESIGNER_PROFILE_REQUIRED_LABELS] are empty, as column names, in screen order.
 *
 * BLANK AND NOT MERELY EMPTY, matching `designerProfileUpdateJson`'s blank-to-null fold on the wire
 * and `update_profile`'s `value.strip() or None` on the server: a box holding one space is stored as
 * null, so accepting it here would be a rule a space defeats.
 */
internal fun designerProfileMissingRequired(form: ProfileForm): List<String> =
    DESIGNER_PROFILE_REQUIRED_LABELS.keys.filter { form.requiredValue(it).isBlank() }

/**
 * The sentence a designer is shown when they press Save with one of the four empty.
 *
 * THE SERVER'S OWN SENTENCE, generalised to a list. In the singular it is word for word what
 * `_mandatory_columns_may_not_be_cleared` raises, so the refusal a designer meets in the box and the
 * refusal that would have come back over the wire are the same claim rather than two — with only the
 * label swapped for this client's, per [DESIGNER_PROFILE_REQUIRED_LABELS]. The plural is this
 * client's: naming the boxes one at a time would mean four taps on Save to discover four blanks.
 */
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

/**
 * Is this an address at all? Null when it is, or when the box is empty.
 *
 * ── DELIBERATELY LOOSER THAN THE SERVER, NEVER TIGHTER ──────────────────────────────────────────
 *
 * `DesignerProfileUpdate.email` is an `EmailStr`, so the SERVER owns the verdict and this client
 * must not hold a second opinion — the failure that would cost something is refusing an address the
 * API would happily have stored, because the designer has no way round it. So this asks only the
 * question the browser's `type="email"` asks on the other client: one `@`, something on each side of
 * it, and a dot in the domain. Every one of those is a condition `email-validator` also imposes, so
 * anything this refuses the server would refuse too; addresses this admits and the server does not
 * still come back as a 422 naming the field, which is the correct division.
 *
 * The empty box is NOT this function's business — [designerProfileMissingRequired] owns it, and
 * answering here as well would put two sentences under one box.
 */
internal fun designerEmailRefusal(email: String): String? {
    val text = email.trim()
    if (text.isEmpty()) return null
    val fault = "An email address needs one @ with a domain after it — meera@nid.ac.in, for example."
    if (text.any { it.isWhitespace() }) return fault
    val at = text.indexOf('@')
    if (at <= 0 || at != text.lastIndexOf('@')) return fault
    val domain = text.substring(at + 1)
    if (domain.length < 3 || !domain.contains('.')) return fault
    if (domain.startsWith('.') || domain.endsWith('.') || domain.contains("..")) return fault
    return null
}

/**
 * `DesignerProfileUpdate.addressLine`'s `max_length`, mirrored so the ceiling is met in the box.
 *
 * IT MATTERS BECAUSE THIS BOX NOW HAS A MICROPHONE. Typing past a column bound is slow and visible;
 * a committed dictation phrase is a state write that arrives all at once, and an over-long value
 * 422s the WHOLE body — the designer loses every other correct answer because they spoke
 * one sentence too many, and the refusal names a box that looks fine on screen.
 */
private const val ADDRESS_LINE_MAX = 300

// --------------------------------------------------------------------------------------
// Which boxes carry a microphone
// --------------------------------------------------------------------------------------

/**
 * THE BOXES ON THIS SCREEN THAT DICTATE, as API column names, in screen order.
 *
 * ── THE RULE TURNED ROUND ON 2026-08-28, AND THIS IS THE RECORD OF IT ───────────────────────────
 *
 * This screen shipped with two microphones and a comment beside the second one reading "THE SECOND
 * MICROPHONE ON THIS SCREEN, AND THE LAST": dictation where the answer was PROSE — the biography
 * and the address — and nowhere else. The owner replaced that rule: *"On My Designer Profile, add
 * the existing microphone/dictation functionality to all applicable fields. Follow the same
 * dictation behavior already used throughout the other record pages. Exclude calendar fields and any
 * other special fields where dictation is not applicable. All remaining applicable fields should
 * provide the mic dictation button."*
 *
 * So the default is now the other way round: a free-text box has a microphone unless there is a
 * reason it must not, and that reason is written down in [DESIGNER_PROFILE_NOT_DICTATED] rather than
 * left as an absence. **A later reader has to be able to tell a decision from an omission**, and a
 * column in neither table is neither — which is why the two together must name every column on
 * [ProfileForm], and why `DesignerProfileScreenTest` fails when they do not.
 *
 * ── WHY A TABLE AND NOT TEN FLAGS WRITTEN OUT AT TEN CALL SITES ─────────────────────────────────
 *
 * Every box on this screen asks [dictates] rather than answering for itself, so the classification
 * and the screen cannot drift: a microphone cannot appear under the e-mail box without an edit to
 * this list, and a column added next year cannot quietly acquire or miss one. It is also what makes
 * the WEB comparable — `frontend/components/designers/DesignerProfileForm.tsx` carries the identical
 * ten, and the test reads them out of its source and compares, because "follow the same dictation
 * behavior" is a claim about two clients and nobody re-reads a `.tsx` while editing Kotlin.
 *
 * ── WHAT DICTATION IS, HERE ─────────────────────────────────────────────────────────────────────
 *
 * `RecordProseField(dictate = …)` — the record forms' control, on-device rungs only. It never
 * uploads a clip: a designer profile has no design workshop behind it and therefore no recorded
 * consent for a voice to leave the handset, which is the argument `ui/RecordProseText.kt` makes for
 * every record form and which holds here unchanged.
 */
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

/**
 * THE COLUMNS WITH NO MICROPHONE, each against the reason it has none.
 *
 * A MAP AND NOT A LIST, because the reason is the point. "This box has no microphone" is an
 * observation anybody can make from the screen; the only thing worth writing down is WHY, and a
 * reason parked in a comment above one call site is a reason the next person deletes along with the
 * call site. Every value here is a full sentence and the test requires it to be one — an empty
 * string would be a way of satisfying "every column is classified" while classifying nothing.
 *
 * Read with [DESIGNER_PROFILE_DICTATED]: together they are all twenty-three columns of
 * [ProfileForm].
 */
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
)

/**
 * Does the box for [column] draw a microphone?
 *
 * FAILS CLOSED, deliberately. A column in neither table answers false — no microphone — rather than
 * throwing, because this is read during composition and an exception here would take the whole
 * screen down over a mistyped string. The mistyped string is caught instead by
 * `DesignerProfileScreenTest`, which requires the two tables to cover every column on [ProfileForm]
 * and every literal handed to this function to be one of them.
 */
private fun dictates(column: String): Boolean = column in DESIGNER_PROFILE_DICTATED

/**
 * The wire body, against [stored] — the snapshot the server last confirmed.
 *
 * THE PARAMETER EXISTS FOR ONE KEY. Every other column is sent on every save, with an explicit null
 * for an emptied box, because that is the only way "clear this" can be said (see
 * [designerProfileUpdateJson]). The location cannot work that way: `attach_location` CREATES a row
 * rather than updating one, so re-sending an unchanged location would mint a duplicate `Location`
 * and orphan the stored one on every single save of an unrelated box — a designer correcting a
 * typo in their department would leave a trail of identical address rows behind them. Sending it
 * only when it has actually moved is the same rule `locationForBody` applies on the record forms,
 * and the comparison is the data class's own `equals` rather than a hand-written field-by-field
 * one, which is what stops it going stale the day a column is added to `LocationRequest`.
 */
private fun ProfileForm.toBody(stored: ProfileForm): DesignerProfileUpdateBody = DesignerProfileUpdateBody(
    displayName = displayName,
    localName = localName,
    designation = designation,
    institution = institution,
    department = department,
    qualification = qualification,
    specialisation = specialisation,
    experienceYears = experienceYears.trim().toIntOrNull(),
    // `toIntOrNull()` on "" is null, which is the explicit JSON null that un-answers the question.
    // "0" parses to 0 and is stored as 0. The two reach the server as different bodies because they
    // are different answers.
    experienceMonths = experienceMonths.trim().toIntOrNull(),
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
    cvMediaId = cvMediaId,
    empanelmentNo = empanelmentNo,
    empanelmentDate = empanelmentDate?.toString(),
    location = location.takeIf { it != stored.location },
)

/**
 * What the three capture controls are currently doing, so one upload cannot be started twice.
 *
 * CV joined the two images on 2026-08-25. It shares every part of their path — the durable copy under
 * `filesDir`, the unlinked upload, the "save the profile to keep it" contract — and differs only in
 * what it is PICKED with (a document picker, not the gallery) and what it is DRAWN as (a rendered
 * first page, not an `Image`). Making it a third member of this enum rather than a parallel mechanism
 * is what keeps the `uploading`, `localPreview` and `remotePreview` maps a single source of truth.
 *
 * INTERNAL rather than private, and for the same reason [ProfileForm] is: the two strings hung off
 * this enum ([caption] and [midSentence]) are the screen's answer to the defect the owner reported,
 * and an answer that no test can call is an answer that gets re-broken by the next person who
 * notices there are "two functions doing the same thing". Nothing outside this file constructs one.
 */
internal enum class ProfileMediaSlot { PHOTOGRAPH, SIGNATURE, CV }

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
    /**
     * Has Save been pressed and refused? Only then do the four mandatory boxes turn red.
     *
     * THE SAME SWITCH `StageScreen` CALLS `enforceRequired`, and for the reason written there: with
     * it on from the first frame, a profile that has never been filled in opens as a wall of red on
     * the screen a designer was sent to in order to fill it in, and red that is present before you
     * have done anything is red that stops meaning anything. It latches ON for the rest of the
     * sitting once a save has actually been refused, and each box's own error then clears itself the
     * moment that box is answered — so nothing has to be un-marked by hand and no field added next
     * year can be forgotten by this bookkeeping.
     */
    var enforceRequired by remember(targetUserId) { mutableStateOf(false) }
    /*
     * ONE FETCH FOR THE WHOLE SCREEN, and it is the address card's own.
     *
     * This screen used to carry a byte-for-byte copy of `rememberAddressReference`'s effect, from
     * before the card was mounted here. Two copies meant two requests for one near-constant payload
     * and — once the fetched-at stamp existed, which is what lets an offline list carry a date — two
     * places to remember to write it, with nothing to catch the one that was forgotten. The flat
     * `state` box below and the card share this.
     */
    val referenceState = rememberAddressReference(repository)
    val reference: AddressReferenceDto = referenceState.reference
    /**
     * The card is holding a state, district, village or pincode that CANNOT BE SAVED, because there
     * is no coordinate under it. Raised by the card; see its `onStatedAddressNeedsCoordinate`.
     *
     * `Location.latitude`/`longitude` are NOT NULL, so those four answers have nowhere to live until
     * a point exists — and until then they are parked INSIDE the card, which means this screen's
     * `form.location` is null and the save would omit the key entirely. Four typed answers would
     * vanish with a 200 and nothing on screen at the moment it mattered. The card says so in a
     * notice; this is what lets the SAVE say so too, which is the moment somebody is looking.
     */
    var addressNeedsCoordinate by remember(targetUserId) { mutableStateOf(false) }
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
    /**
     * The CV's filename and mime type, as the server reports them.
     *
     * NEEDED ONLY BY THE CV, WHICH IS WHY IT IS NOT A THIRD MAP OVER ALL THREE SLOTS. The two images
     * are drawn by an `AsyncImage` that neither needs nor consults a mime type; the CV has to decide
     * between rendering a PDF page, drawing a scanned sheet as a picture, and saying that a .docx
     * cannot be shown inline — a decision `DwDocumentPreview` makes from exactly these two strings.
     */
    var cvDescriptor by remember(targetUserId) { mutableStateOf<Pair<String?, String?>>(null to null) }

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

    // Resolve whatever the stored media ids point at, so a profile filled in on the web shows its
    // photograph here. A null URL is not an error and must not be reported as one: the server
    // withholds it whenever this account may not download that uploader's files.
    LaunchedEffect(form.photoMediaId, form.signatureMediaId, form.cvMediaId) {
        val wanted = listOfNotNull(
            form.photoMediaId.takeIf { it.isNotBlank() }?.let { ProfileMediaSlot.PHOTOGRAPH to it },
            form.signatureMediaId.takeIf { it.isNotBlank() }?.let { ProfileMediaSlot.SIGNATURE to it },
            form.cvMediaId.takeIf { it.isNotBlank() }?.let { ProfileMediaSlot.CV to it },
        )
        var resolved = remotePreview
        wanted.forEach { (slot, id) ->
            /*
              A CANCELLATION IS RETHROWN AND NOT READ AS "no url", because this effect's keys change
              IN PLACE: every successful upload assigns a new media id, which re-keys this block while
              the previous resolve is still in flight.

              Swallowed, the cancelled run does not stop — `getOrNull()` hands it null, `return@forEach`
              skips to the next id, each of those throws too, and the run reaches `remotePreview =
              resolved` at the bottom carrying the map it captured BEFORE the upload. That write can
              land after the replacement run's, putting the URL of the file that was just REPLACED
              back on screen; and `cvDescriptor` is written from inside the same loop, so the filename
              row can end up naming the old document over the new one's page. That is the same defect
              `DwDocumentPreview`'s cache key was just fixed for, arriving by a second route.
            */
            val item = runCatching { repository.mediaItem(id) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: return@forEach
            // The CV's name and type are kept even when the URL is withheld: "cv.pdf is stored, but
            // this account may not open the file itself" is a far better sentence than a blank frame,
            // and it needs the filename the entitlement answer still carries.
            if (slot == ProfileMediaSlot.CV) cvDescriptor = item.originalFilename to item.mimeType
            val url = item.url ?: return@forEach
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
        /*
          EVERY FAULT IS COLLECTED AND REPORTED AT ONCE, and that changed here on 2026-08-27.
          Previously the years bound returned on its own and said nothing but a line under its own
          box. With four mandatory boxes spread over three cards, one-fault-per-tap would mean
          pressing Save four times to be told four things, each time scrolling back down to find the
          button — and a line under a box 800dp above the Save the designer is looking at is a
          refusal nobody sees at all, which is why every one of these ALSO goes to the host's
          snackbar rather than only into the form.
        */
        val missing = designerProfileMissingRequired(form)
        val emailFault = designerEmailRefusal(form.email)
        // The stored value's shape, which is a different question from "is it blank" and is asked of
        // the composed "+CC number" string — see `artisanPhoneValidationError`. Blank answers null
        // there, so the two rules compose instead of both firing on an empty box.
        val phoneFault = artisanPhoneValidationError(form.phone)
        val years = form.experienceYears.trim()
        // Bounded exactly as the server bounds it, and as the registry's `designerExperience` field
        // is, because this value is COPIED into that field when a workshop is created. A profile
        // that accepted 400 years would prefill a stage the stage's own validator then rejects, and
        // the designer would be told their workshop has an error in a box they never typed in.
        val parsedYears = years.toIntOrNull()
        val yearsFault = if (years.isNotEmpty() && (parsedYears == null || parsedYears !in 0..70)) {
            "Years of experience must be a whole number between 0 and 70."
        } else {
            null
        }
        /*
         * THE MONTHS BOUND IS 0..11 AND THE CEILING IS 11 RATHER THAN 12 ON PURPOSE. Twelve is not a
         * bigger month, it is a year the box beside this one already holds. The column carries
         * `CHECK (experienceMonths BETWEEN 0 AND 11)` and the API bounds it identically — but a
         * CHECK violation reaches this client as a bare 500 naming no field, so the value is judged
         * here, in the box, where it can be corrected.
         *
         * The picker cannot produce anything outside the range. This is for the one value that can:
         * a number that came off the server and is kept at the front of the list rather than
         * silently dropped, which is the same rule the state box follows for an unknown state.
         */
        val months = form.experienceMonths.trim()
        val parsedMonths = months.toIntOrNull()
        val monthsFault = if (months.isNotEmpty() && (parsedMonths == null || parsedMonths !in 0..11)) {
            "Months of experience must be a whole number between 0 and 11 — twelve months is a " +
                "year, and the box beside this one holds the years."
        } else {
            null
        }
        /*
         * FOUR ANSWERS WITH NOWHERE TO GO, REFUSED HERE RATHER THAN DROPPED SILENTLY.
         *
         * A state, a district, a village or a pincode typed into the address card is stored on the
         * `Location` row, and that row cannot exist without a coordinate — `latitude` and
         * `longitude` are NOT NULL for all seven owners of that table, and the API's `LocationInput`
         * makes them required floats with no default. Manufacturing one is the precise failure the
         * two-group split exists to end: fifteen artisans documented in Rajasthan, Gujarat,
         * Uttarakhand and Andhra Pradesh carry Kharagpur coordinates because the schema once had
         * nowhere else to put "where the subject is".
         *
         * So the cost is stated instead of hidden, in the words the web card uses in the same
         * situation. Refusing costs one tap; the alternative is a save that reports success and
         * keeps none of it.
         */
        val addressFault = if (addressNeedsCoordinate) {
            "The state, district and village are stored with the coordinates, so this profile needs " +
                "a point before they can be saved. Press 'Use current GPS' under 'Captured " +
                "coordinates', drop a pin on the map, or type the two numbers — or clear the " +
                "address fields under '${DESIGNER_LOCATION_SUBJECT.heading}' to save the rest."
        } else {
            null
        }
        experienceError = yearsFault ?: monthsFault
        if (missing.isNotEmpty() || emailFault != null || phoneFault != null || yearsFault != null ||
            monthsFault != null || addressFault != null
        ) {
            // Latched here and nowhere else: the boxes turn red because a save was actually
            // refused, never because the profile has not been filled in yet.
            enforceRequired = true
            onError(
                listOfNotNull(
                    designerProfileRequiredRefusal(missing).takeIf { it.isNotEmpty() },
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
            // `saved` is the snapshot the server last confirmed, and it is passed so the location is
            // sent ONLY when it has actually moved — see [ProfileForm.toBody].
            runCatching { repository.saveDesignerProfile(targetUserId, form.toBody(saved)) }
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
                    ProfileMediaSlot.CV -> form.copy(cvMediaId = uploaded.id)
                }
                onMessage("${slot.caption()} uploaded. Save the profile to keep it.")
            }.onFailure { error ->
                // The durable copy is deliberately LEFT ON DISK on a failure. It costs a few hundred
                // kilobytes and it is the difference between "tap upload again" and "go back and
                // photograph the signed sheet again", which on a scanned signature may not be
                // possible at all.
                // `midSentence()` AND NOT `caption().lowercase()`, which is what this was and which
                // printed "Could not upload the cv." — lower-casing an acronym does not sentence-case
                // it, it reads as a typo. The web carried the identical defect on the identical
                // string ("the designer cv did not upload") and fixed it the same way: a caption and
                // a mid-sentence noun are two jobs, so each keeps the case it should have.
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
                /*
                  THE CANCELLATION GUARD BELONGS ON BOTH PICKERS — this one and the document picker
                  under it. `onError` is the HOST's snackbar and not this screen's state, so a
                  designer who picks a file and immediately goes back cancels this copy and is then
                  told "That file could not be read" on the screen they landed on, about a file
                  nothing was wrong with. A large scanned sheet takes long enough for that to be an
                  ordinary sequence rather than a race.
                */
                .onFailure {
                    if (it is CancellationException) throw it
                    onError("That file could not be read. Try picking it again.")
                }
        }
    }
    /**
     * The CV picker.
     *
     * `OpenDocument` AND NOT `GetContent`, which is what the two image slots use, and the difference
     * is not stylistic: `GetContent` takes ONE mime string, and this box legitimately accepts a PDF, a
     * Word document, an OpenDocument text file and a photograph of a printed sheet. `OpenDocument`
     * takes an array, and it also returns a Uri backed by a persistable grant rather than a one-shot
     * one — which matters less here than it looks, because the bytes are copied into `filesDir`
     * immediately either way (see `copyIntoProfileMedia`), but it is the correct contract for
     * "choose a document" and it is what the system file browser is wired to.
     *
     * THE IMAGE WILDCARD IS IN THE LIST DELIBERATELY. A designer whose CV exists only as a scanned or
     * photographed sheet — common in this fieldwork — would otherwise be told their own CV is the
     * wrong kind of file with no way to attach it. The web's slot makes the same allowance for the
     * same reason.
     */
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingSlot = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { copyIntoProfileMedia(appContext, uri) }
                .onSuccess { durable -> uploadInto(ProfileMediaSlot.CV, durable) }
                // Cancellation rethrown for the reason given on the image picker above: a copy
                // abandoned by leaving the screen must not raise a snackbar on the next one.
                .onFailure {
                    if (it is CancellationException) throw it
                    onError("That document could not be read. Try picking it again.")
                }
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
                        help = "How your name appears on the cover and in the signature block.",
                        required = true,
                        // A MANDATORY BOX AND A DICTATED ONE, which the record forms already pair:
                        // `RequiredInput` in `MainActivity.kt` defaults `dictate` to true precisely
                        // because the required boxes — a name, a place, a title — are the ones with
                        // the most typing friction on a form somebody fills in standing up.
                        dictate = dictates("displayName"),
                        resetKey = targetUserId,
                        error = requiredRefusal(enforceRequired, form.displayName, "Name as printed")
                    ) { form = form.copy(displayName = it) }
                    // THE BOX WHERE THE MICROPHONE EARNS THE MOST ON THIS SCREEN.
                    // `DW_DICTATION_LANGUAGES` carries nineteen — Hindi, Odia, Gujarati, Manipuri
                    // and the rest — and the record forms remember the last one chosen for the
                    // life of the process, so a designer sets the recogniser's language once and
                    // speaks their own name in the script it belongs in, instead of installing a
                    // keyboard to type six characters.
                    // Whatever comes back is stored and printed verbatim, exactly as typing is.
                    ProfileText(
                        "Name in local script", form.localName, canEdit,
                        help = "Printed verbatim, in whatever script you type it in.",
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
                    // Both of these are proper nouns typed out in full — "National Institute of
                    // Design", "Department of Textile Design" — and both are printed verbatim on a
                    // report cover. That is exactly the length of answer somebody would rather speak
                    // than thumb in, which is the whole of the case for a microphone on them.
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
                    // The shared control, so the artisan form and this one ask the identical
                    // question in identical words. 70 rather than 90 is the only thing that differs
                    // here, and it is the ceiling the API and the registry's `designerExperience`
                    // field both enforce — this value is COPIED into that field when a workshop is
                    // created, so a profile that accepted 400 would prefill a stage its own
                    // validator then rejects, and the designer would be told their workshop has an
                    // error in a box they never typed in.
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
                        // Asked of the same table as every other box, though this branch does not
                        // read it: the rich editor carries `DwDictationButton` in its own toolbar and
                        // `RecordProseField` therefore ignores `dictate` when `rich` is set. Stated
                        // anyway, so that the classification and the screen cannot disagree — a
                        // reader checking "does the biography dictate?" gets one answer from the
                        // table and the same answer from the call site.
                        dictate = dictates("biography"),
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
                    // search and the foreign-resident confirmation it already carries. `required`
                    // puts this app's " *" on that inner caption rather than a second caption above
                    // it, which is why the flag had to be added to the control instead of drawn here.
                    //
                    // TWO RULES, ONE SLOT, AND THE SHAPE ONE WINS. `artisanPhoneValidationError` is
                    // the rule the artisan form and every stage PHONE field already apply, and the
                    // web enforces the identical one through its mirror's `pattern`; it answers null
                    // for a blank box, so "this is not a phone number" and "this box is empty" can
                    // never both be shown, and the mandatory rule takes the empty case.
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
                        help = "The address printed on the report, which need not be your sign-in address.",
                        required = true,
                        // Required AND silent, which is the one combination the record forms do not
                        // have: `RequiredInput` gives every mandatory box a microphone by default,
                        // and this is the exception the reason for which is in
                        // [DESIGNER_PROFILE_NOT_DICTATED] — a dictated address is a value the check
                        // below reliably refuses, and this form saves every column in one PUT.
                        dictate = dictates("email"),
                        /*
                          BOTH E-MAIL RULES ARE BEHIND THE LATCH, WHILE THE PHONE'S SHAPE RULE ABOVE
                          IS NOT, AND THAT ASYMMETRY IS THE WEB'S OWN. Its `PhoneField` computes
                          `phoneValidationError(combined)` on every keystroke and prints it live; its
                          e-mail box is a plain `type="email"`, and the browser says nothing about a
                          half-typed address until the form is submitted. Live here would mean
                          "meera@nid.ac.in" is marked as broken for the first fourteen keystrokes of
                          typing it correctly — a phone number is a fixed count of digits and an
                          address is not, which is why one can be judged mid-word and the other
                          cannot. Shape before blank: `designerEmailRefusal` is silent on an empty
                          box, so the two can never both fire.
                        */
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

                /*
                 * RENAMED FROM "Address" ON 2026-08-30, because there are now two of them and an
                 * unqualified heading over one of two is the reading a person gets wrong. These four
                 * are the POSTAL address — the columns on `DesignerProfile` itself, the ones the report
                 * prefill copies into stage 3, and the only place any live designer's address
                 * currently is. The card below holds the district and the map point, which no column
                 * here can hold. Both are live until the retiring migration moves the values across.
                 */
                ProfileSection("Postal address") {
                    Text(
                        "What is printed on your reports. The district and the map point are asked " +
                            "for separately, below.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                    /*
                     * DICTATION HERE IS NO LONGER THE LAST WORD ON THIS SCREEN, AND THE COMMENT
                     * THAT STOOD IN THIS PLACE SAID IT WAS.
                     *
                     * It read "THE SECOND MICROPHONE ON THIS SCREEN, AND THE LAST", and argued that
                     * dictation belonged where the answer was PROSE — the biography and this
                     * address — and nowhere else. That was the rule this screen shipped with, it was
                     * a defensible one, and it is not the rule any more: the owner asked on
                     * 2026-08-28 for "the existing microphone/dictation functionality" on "all
                     * applicable fields", excluding "calendar fields and any other special fields
                     * where dictation is not applicable". The default inverted, and the list now
                     * lives in [DESIGNER_PROFILE_DICTATED] beside the reasons the other eleven
                     * columns were left out, where a later reader can tell a decision from an
                     * omission.
                     *
                     * THE HALF OF THE OLD ARGUMENT THAT SURVIVED IS THE EXCLUSION LIST. Its examples
                     * were right and are kept in substance: a recogniser writes "at" for the @ sign,
                     * spells digits out in words and punctuates a URL, so a microphone under the
                     * e-mail, phone, PIN code, website and date boxes would be a control that
                     * reliably produces a value the field then refuses. What did not survive is the
                     * inference from it — that a name, an institution or a town is the same kind of
                     * box. Those are free proper nouns, and they are precisely what somebody
                     * standing in a courtyard would rather speak than thumb in.
                     *
                     * ── WHY IT IS NOT A BARE BOX: STILL TRUE, AND STILL THE OBSTACLE ────────────
                     *
                     * `RecordDictationButton` and `rememberRecordDictationAvailable` are private to
                     * `RecordProseField.kt`, so a bare `OutlinedTextField` has no microphone and no
                     * route to one. The answer was, and remains, to use that component rather than
                     * fork the recogniser — and it is now [ProfileText] itself that forwards to it,
                     * so this box and the twelve beside it are one control instead of two.
                     *
                     * ── NEWLINES ARE STILL FOLDED TO SPACES; THE FOLD HAS MOVED ────────────────
                     *
                     * `RecordProseField` has no `singleLine` parameter (its boxes are paragraphs),
                     * so the IME offers a newline key that the old bare box did not.
                     * `DesignerProfile.addressLine` has never held newlines — `ArtisanForm` uses a
                     * textarea for ITS address and `Artisan.address` legitimately does, but this one
                     * is copied into a registry field and typeset on a report cover, and the web
                     * deliberately kept it single-line for that reason. The rule is unchanged; the
                     * fold simply lives in [ProfileText] now, where all thirteen single-line
                     * boxes on this screen get it from one place instead of from thirteen.
                     */
                    ProfileText(
                        "Address line", form.addressLine, canEdit,
                        dictate = dictates("addressLine"),
                        // Re-seed when an admin steps to the next designer in the roster, exactly as
                        // the biography above does.
                        resetKey = targetUserId,
                        below = {
                            /*
                              THE CEILING, SAID ON SCREEN THE MOMENT IT IS REACHED — never clamped
                              quietly. A box that silently stops accepting words is indistinguishable
                              from a microphone that has stopped working, and rule 10 of the frontend
                              contract ("truncation, caps and skipped work must be stated on screen")
                              is the same rule on this client. `muted` and not the error colour:
                              nothing is wrong and nothing was lost — what is in the box is exactly
                              what will be saved. The sentence is the web's, verbatim.
                            */
                            if (form.addressLine.length >= ADDRESS_LINE_MAX) {
                                Text(
                                    "This box is full — it holds $ADDRESS_LINE_MAX characters, " +
                                        "which is what the column stores. Anything spoken or typed " +
                                        "beyond that is not added.",
                                    color = MaterialTheme.field.muted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    ) { next ->
                        // The ceiling is applied to the WHOLE value rather than to what arrives,
                        // because a committed dictation phrase is appended to what is already in the
                        // box before it reaches this lambda. The newline fold that used to sit
                        // beside it has moved into `ProfileText`, which every box here now shares.
                        form = form.copy(addressLine = next.take(ADDRESS_LINE_MAX))
                    }
                    // The served list, with a stored value kept at the FRONT until the list arrives —
                    // the same rule the record forms' state dropdown follows. Without it, a profile
                    // that already holds a state shows "Select" over it on a phone that has not
                    // fetched the reference yet, which reads as "not answered" and invites the
                    // designer to answer it again, differently.
                    val stateRows = stateSelectOptions(form.state, reference)
                    /*
                     * THE SENTENCE THIS BOX HAS NEVER HAD (DROPDOWN_DESIGN.md A3).
                     *
                     * With no reference on the device this control drew an empty menu over the word
                     * "Select", and a picker that opens on nothing reads as "there are none" — which
                     * this repository names as its single most repeated bug class. The truthful
                     * reading on a handset in a workshop with no signal is "this device has not been
                     * given the list yet", and the two are opposite facts with opposite next moves.
                     *
                     * The wording is `addressListNotice`'s, shared with the address card below,
                     * because both boxes on this one screen are asking about the same list and two
                     * spellings of one fact is two facts to whoever reads them.
                     */
                    val stateNotice = addressListNotice("states", stateRows.size, referenceState)
                    SearchableSelectField(
                        label = "State / union territory",
                        options = stateRows,
                        selectedValue = form.state,
                        // Stood down when there is nothing to pick, which is also what makes
                        // `SearchableSelectField` print the sentence beneath the control: a disabled
                        // trigger cannot be opened, so the menu's own empty arm is out of reach.
                        enabled = canEdit && listIsAnswerable(stateRows),
                        emptyMessage = stateNotice,
                        // Pinned open rather than left to the option count, so the control does not
                        // change shape with what the network did. See DROPDOWN_DESIGN.md 3.6.
                        searchable = true,
                        onSelect = { picked ->
                            // Changing the state invalidates a district chosen under the old one.
                            // Kept rather than cleared, deliberately: clearing would silently delete
                            // a town the designer typed, and a district that no longer matches the
                            // state is visible to them and fixable, where a blank box is neither.
                            form = form.copy(state = picked)
                        }
                    )
                    stateNotice?.takeIf { listIsAnswerable(stateRows) }?.let { line ->
                        // Only the CACHED case reaches here: the control is enabled, so the
                        // primitive draws nothing of its own and the date would otherwise be lost.
                        Text(line, color = MaterialTheme.field.muted, fontSize = 12.sp)
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

                /*
                 * ══════════════════════════════════════════════════════════════════════════════
                 * THE SECOND ADDRESS, AND WHY THERE ARE TWO ON ONE SCREEN
                 * ══════════════════════════════════════════════════════════════════════════════
                 *
                 * `DesignerProfile` had a flat `addressLine, city, state, pincode` and NO DISTRICT
                 * and NO COORDINATES, while every other record in this system uses `Location` — which
                 * splits an address into where the DEVICE was and where the SUBJECT is, on purpose,
                 * because fifteen live artisan records carry Kharagpur coordinates for artisans in
                 * Bagru, Kutch and Rudraprayag. The profile is now the seventh owner of that table,
                 * which is what "like the rest of the record pages" was always meant to mean.
                 *
                 * BOTH ARE ON SCREEN BECAUSE BOTH ARE LIVE. The migration backfilled NOTHING:
                 * `Location.latitude`/`longitude` are NOT NULL, so a row cannot be manufactured for
                 * an address that never had a coordinate without INVENTING the coordinate, which is
                 * the exact failure the table's own docstring exists to end. So every existing
                 * designer's address is still in the four boxes above, those four are still what the
                 * report prefill copies into stage 3, and this card holds the two facts nothing else
                 * can — the district and the point. A screen that showed only one of the two would show
                 * some designers a blank where their address is.
                 *
                 * ── THE ONE THING THIS MOUNT MUST NEVER GET WRONG ─────────────────────────────
                 *
                 * `isEdit = true`, ALWAYS, WITH NO CONDITION IN FRONT OF IT. A profile is always an
                 * edit of an existing row — the server upserts it on read, so there is no create path
                 * anywhere in this feature — and the subject of this address is the person holding the
                 * phone. Left to capture on its own, a profile opened at a conference, on a train or
                 * at somebody else's institution would file its owner wherever they happened to be
                 * sitting, and nothing downstream could tell that from a district they chose. The
                 * card forwards this to `LocationCaptureCard`, where `true` short-circuits the
                 * automatic fix outright; its grace period is a heuristic and this is the rule. The
                 * web spells the same switch `initial !== undefined`, where merely OMITTING the prop
                 * turns capture on.
                 */
                /*
                 * THE SECTION TITLE IS NOT `DESIGNER_LOCATION_SUBJECT.heading`, AND IT WAS.
                 *
                 * `LocationFieldsSection` draws its OWN `GroupHeading(subject.heading, …)` as its
                 * first row — that is how the card names itself on the six record forms, where
                 * nothing else is heading it. Passing the same constant to `ProfileSection` printed
                 * "Where you are based" twice, one line apart, in the same 15sp SemiBold: a heading,
                 * a paragraph, the identical heading again, another paragraph. A reader meeting that
                 * has to work out whether they are looking at one section or two, and the honest
                 * answer — that the second one is the card introducing itself — is not visible from
                 * the screen.
                 *
                 * SO THE SECTION NAMES WHAT IT ADDS AND THE CARD GOES ON NAMING ITSELF. The other
                 * section on this screen is "Postal address"; this one is the two facts those four
                 * columns cannot hold, so it says so, and the pair now reads as two different
                 * questions rather than as one question asked twice. Suppressing the card's heading
                 * instead would mean a parameter on a composable six other forms call, to fix a
                 * duplication that exists only here.
                 */
                ProfileSection("District and map point") {
                    Text(
                        "Your district and your map point are stored the way every other record in " +
                            "this system stores an address, which is what makes them comparable with " +
                            "it. The four boxes above are the postal address printed on your " +
                            "reports; they have not moved and they are still what the report reads.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    /*
                     * THE CARD IS FOR SOMEBODY WHO MAY WRITE. A READER GETS THE VALUES INSTEAD.
                     *
                     * `LocationFieldsSection` has no `enabled` flag and should not grow one for this
                     * — it is eight controls, a permission flow and a live GPS stream, and a version of
                     * it that draws all of that inert would be a screen full of dead buttons in
                     * front of somebody who only came to read a district. Every other control here
                     * takes `enabled = canEdit`; this takes the other half of the same rule, which
                     * is that a read-only viewer must still SEE what is stored. Blocking the write
                     * is not enough on its own: the save re-derives the permission at the moment of
                     * the tap and the server refuses as well.
                     */
                    if (canEdit) {
                        LocationFieldsSection(
                            repository = repository,
                            value = form.location,
                            onChange = { next -> form = form.copy(location = next) },
                            // NOT required. A designer must be able to save their name, phone and
                            // e-mail without giving a coordinate; `forbid_clearing_location` asks
                            // nothing of an update either, and a required address on the one screen
                            // a designer is sent to in order to fill in their details would be a
                            // wall in front of the work.
                            required = false,
                            // READ THE PARAGRAPH ABOVE BEFORE CHANGING THIS.
                            isEdit = true,
                            subject = DESIGNER_LOCATION_SUBJECT,
                            // The screen's own fetch, shared, so the flat state box above and this
                            // card cannot disagree about what this device has been given.
                            referenceState = referenceState,
                            onStatedAddressNeedsCoordinate = { addressNeedsCoordinate = it },
                            onMessage = onMessage
                        )
                    } else {
                        StoredLocationSummary(form.location)
                    }
                }

                ProfileSection("Empanelment") {
                    // NO MICROPHONE, AND THIS IS THE ONE EXCLUSION ON THE SCREEN THAT IS ABOUT
                    // DATA RATHER THAN ABOUT FRICTION. An empanelment number is transcribed from a
                    // government order and printed on every report signed under this name; a
                    // mis-heard character produces a number that is still the right shape and still
                    // looks correct, and nothing downstream can tell it from a right one. Same class
                    // as the artisan form's Aadhaar and Pehchan boxes. See
                    // [DESIGNER_PROFILE_NOT_DICTATED].
                    ProfileText(
                        "Empanelment number", form.empanelmentNo, canEdit,
                        dictate = dictates("empanelmentNo")
                    ) {
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

                // The section title is the web's, verbatim. Wording and information architecture come
                // from whichever client the owner last approved, and a researcher moving between the
                // two apps mid-workshop must find the same heading over the same three controls.
                ProfileSection("Photograph, signature and CV") {
                    /*
                      ══════════════════════════════════════════════════════════════════════════════
                      THIS SENTENCE PROMISED AN ANNEXURE, AND IT WAS FALSE — MEASURED, NOT SUSPECTED
                      ══════════════════════════════════════════════════════════════════════════════

                      It read "All three are printed: … and the CV as an annexure." NO BRANCH OF THIS
                      CODEBASE PUTS A FILE IN AN ANNEXURE. `report_annexures` is transcripts only, and
                      `_render_media_annexure` gathers through the image path, which admits IMAGE and
                      IMAGE_LIST and nothing else — and `report_templates` records that refusal as a
                      DELIBERATE decision with its reasons written out, so this was never a gap
                      waiting to be filled. It was a sentence contradicting a settled design.

                      WHAT ACTUALLY HAPPENS is that the report NAMES the file: a FILE field declares
                      no report role, so `format_value` prints the label and a count — "1 document
                      attached" — and `build_report` now emits a warning beside the generated file
                      saying the bytes are not inside it and to send them alongside it.

                      SO THE THREE SURFACES NOW SAY ONE THING. The registry's own help text on
                      `designerCv` was corrected to "The report NAMES it rather than carrying it, so
                      send the file alongside the report"; the export warning says a report file
                      cannot carry a document and to send it alongside; and this is the third, which
                      is the only one the designer reads BEFORE they upload. A designer who submits a
                      ministry report believing the CV travelled inside it — because this screen told
                      them so — finds out from the ministry, and it is the photograph and the
                      signature (which DO travel) that make the claim credible.

                      SPLIT INTO TWO SENTENCES ON PURPOSE. "All three are printed" was doing the
                      damage by grouping: the two that are printed have to be named as the two, or the
                      correction reads as a footnote to a promise that still stands.
                    */
                    /*
                      ⚠ AND THE SIGNATURE HALF WAS ITSELF FALSE FOR ONE REVISION, WHICH IS WORTH
                      LEAVING ON THE RECORD. Correcting the CV claim above, this sentence gained
                      "the signature in the block an officer counter-signs" — trading one false
                      promise for another in the same edit.

                      `report_model.SignatureBlock` carries `signatories: tuple[tuple[str, str]]` —
                      a name and a role, two strings — and all four writers draw those names over
                      ruled lines. There is no image slot in it on either side of the wire, and
                      `designerSignature` is declared `report_role=GALLERY`, so the picture prints
                      with the report's photographs under its own heading. The registry's own
                      corrected help says exactly that, and this now agrees with it.

                      Whether the signature SHOULD reach the signature block is an open owner
                      decision about a ministry document — it needs an image on `SignatureBlock`,
                      which is `report_model.py` plus `ReportModel.kt` plus four writers plus a
                      re-pin of the bundled asset. Until that decision is taken, the box says what
                      is true.
                    */
                    Text(
                        "Two of these are printed in the report: the photograph on the designer's " +
                            "profile page, and the signature with the report's photographs under " +
                            "its own heading. The CV is not. The report NAMES it — " +
                            "\"1 document attached\" — but a report file cannot carry a document, " +
                            "so send the CV alongside the report.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
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

                    /*
                     * THE CV. A document row rather than a third image row, for the three reasons the
                     * web's `DocumentSlot` gives: an `AsyncImage` cannot draw a PDF, a 72dp square is
                     * not a shape a page of text is readable in, and the picker has to accept
                     * documents rather than refuse everything that is not an image.
                     *
                     * NO CAMERA BUTTON. The two rows above offer one because photographing a signed
                     * sheet in the room is the ordinary way those two arrive. A CV is a file that
                     * already exists on a device somewhere; offering a camera would invite a designer
                     * to photograph a printed CV one page at a time into a single-file column that
                     * would keep only the last page. A scanned or photographed CV is still perfectly
                     * attachable — the image wildcard is in the picker's list and the preview draws it — it
                     * just comes in as a file the designer already has.
                     */
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CV", color = MaterialTheme.field.muted, fontSize = 12.sp)
                        Text(
                            "One document — PDF, .docx, .odt, or a scan. A PDF is shown here as soon " +
                                "as it uploads; anything else opens in the app that handles it.",
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp
                        )
                        if (uploading == ProfileMediaSlot.CV) {
                            Text("Uploading…", color = MaterialTheme.field.muted, fontSize = 12.sp)
                        }
                        DwDocumentPreview(
                            mediaId = form.cvMediaId,
                            noun = "CV",
                            localFile = localPreview[ProfileMediaSlot.CV],
                            remoteUrl = remotePreview[ProfileMediaSlot.CV],
                            displayName = cvDescriptor.first ?: localPreview[ProfileMediaSlot.CV]?.name,
                            mimeType = cvDescriptor.second
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                modifier = Modifier.weight(1f)
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
                                TextButton(
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
                    /*
                      THIS SENTENCE BECAME HALF FALSE ON 2026-08-27 AND HAD TO MOVE WITH THE RULE.
                      It read "An empty box CLEARS that value on the server. Nothing here is left
                      behind when you delete it." — which is still exactly right for most of the
                      boxes and is wrong for the four marked with an asterisk: the API refuses a body
                      that asks to blank any of them (`_mandatory_columns_may_not_be_cleared`), so
                      emptying one does not clear it, it stops the save. A screen that promises a
                      designer their deletion will be honoured and then refuses it is worse than one
                      that never promised, and this is the line they read immediately before pressing
                      Save.

                      A SECOND EXCEPTION ARRIVED WITH THE ADDRESS CARD, and it is a different one.
                      The location is not a box and it does not clear: `attach_location` CREATES a
                      `Location` row and never updates one, so "remove my address" has no honest
                      implementation — it would orphan the stored row and leave the profile with no
                      district rather than with a corrected one. The API refuses an explicit null
                      outright (`forbid_clearing_location`). A designer who moves REPLACES it. That
                      has to be said here rather than discovered, because it is the one place on this
                      screen where deleting what is on screen does not delete what is stored.
                    */
                    Text(
                        "An empty box CLEARS that value on the server — nothing here is left behind " +
                            "when you delete it. Two exceptions: the four boxes marked * cannot be " +
                            "emptied, because every report you generate is signed with them; and " +
                            "your district and map point can be REPLACED but not removed, because " +
                            "they are stored as a row of their own that a save rewrites rather than " +
                            "deletes.",
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

/**
 * ONE LINE, WHICHEVER WAY THE WORDS ARRIVED. The half of `singleLine` that had to be kept.
 *
 * [RecordProseField] has no `singleLine` parameter — its boxes are paragraphs — so every box on this
 * screen that forwards to it now has an IME with a newline key, and a dictation control that can
 * commit a phrase containing one. Not one of these columns has ever held a newline: each is copied
 * into a registry field and typeset on a report cover, and the web keeps every one of them
 * single-line. Changing the stored shape of a column on ONE client is the exact drift the parity
 * rule exists to prevent.
 *
 * BOTH CHARACTERS, and that is not belt-and-braces. A recogniser's committed text and an IME's
 * Return key do not agree on what a line break is, and a fold that handled only `\n` would let a
 * lone carriage return through — which is invisible in a text box, survives the save, and turns up
 * as a broken line in a report cover somebody has already sent.
 *
 * REPLACED WITH A SPACE, NEVER DROPPED. "12 Nagar\nJaipur" becoming "12 NagarJaipur" is a wrong
 * address that still looks like an address; with the space it is the same address on one line.
 *
 * INTERNAL rather than private, so this is a rule a test can call rather than a lambda nobody can
 * reach. It is applied in exactly one place ([ProfileText]) and must stay that way.
 */
internal fun designerProfileOneLine(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ')

/**
 * ONE SINGLE-LINE BOX ON THIS PROFILE, with the record forms' microphone where the column takes one.
 *
 * ── WHY IT IS NO LONGER A BARE `OutlinedTextField`, CHANGED 2026-08-28 ──────────────────────────
 *
 * It was one, and the comment on the address box below said exactly why that mattered:
 * `RecordDictationButton` and `rememberRecordDictationAvailable` are both private to
 * `RecordProseField.kt`, so a bare box had "no microphone and no route to one", and the choice was
 * to use the shared component or to fork the recogniser. Ten boxes on this screen now need a
 * microphone (see [DESIGNER_PROFILE_DICTATED]), so this forwards to [RecordProseField] exactly as
 * `MainActivity`'s `TextInput` and `RequiredInput` already do for the ~200 boxes on the record
 * forms. **No speech plumbing is copied into this file.** A second copy of a dictation control is a
 * second copy to get wrong, and the half that always drifts is the wording of a refusal.
 *
 * ── THE THREE THINGS THAT HAD TO BE RE-SUPPLIED HERE ────────────────────────────────────────────
 *
 * 1. **THE ASTERISK.** [RecordProseField] has no `required` flag and must not grow one — it has no
 *    idea whether a value is required, and pushing the concept down would put a form's rule inside a
 *    text box. `RequiredInput` in `MainActivity.kt` answers this the same way, by putting the mark
 *    in the LABEL; the mark is this app's existing trailing asterisk, the one
 *    `FieldRenderer.fieldLabel` appends for every required field across all 22 stages.
 *
 * 2. **THE HELP LINE, ABOVE THE BOX.** [RecordProseField] draws its own `help` UNDER the box at
 *    11.sp. This screen has always drawn it above at 12.sp, and its help sentences are instructions
 *    read BEFORE typing ("How your name appears on the cover…") rather than footnotes read after.
 *    So the line stays where it was and `help` is not forwarded.
 *
 * 3. **NEWLINES FOLDED TO SPACES, WHICH IS THE ONE THING `singleLine` DID.** [RecordProseField] has
 *    no `singleLine` parameter — its boxes are paragraphs — so the IME now offers a newline key that
 *    this control did not. None of these columns has ever held a newline: each is copied into a
 *    registry field and typeset on a report cover, and the web keeps every one of them single-line.
 *    Changing the stored shape on one client only is the exact drift the parity rule exists to
 *    prevent, so the fold happens HERE, once, for a typed Return and a dictated one alike — rather
 *    than at the thirteen call sites this control has, twelve of which would eventually forget
 *    it.
 *
 *    WHAT IS NOT RESTORED, SAID PLAINLY RATHER THAN LEFT TO BE DISCOVERED: a value longer than the
 *    box now WRAPS onto a second line instead of scrolling sideways. That is a real change, and it
 *    is the better half of the trade on a handset — a box that scrolls sideways hides the end of
 *    what is in it, and this screen's whole job is values a designer has to be able to proof-read
 *    before a ministry does. The address box has behaved this way since it moved to
 *    [RecordProseField] and nothing came of it. Restoring the old behaviour exactly would need a
 *    `singleLine` parameter on [RecordProseField], which is a file this lane does not own; it is
 *    reported as a handoff rather than done by forking the control.
 */
@Composable
private fun ProfileText(
    label: String,
    value: String,
    enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
    help: String? = null,
    /**
     * Draw the app's own required marker — a trailing " *" on the label.
     *
     * THE ASTERISK IS THIS APP'S EXISTING SPELLING OF "required" and not a new one:
     * `FieldRenderer.fieldLabel` appends exactly `" *"` for every required field across all 22
     * stages, so a designer meets one mark for one meaning wherever they are. The web draws the same
     * mark from `Field`'s `required` prop.
     *
     * Marked whether or not the box is editable, matching the web's read-only view: an admin reading
     * a colleague's profile needs to see which blank is the one that will stop the next save.
     */
    required: Boolean = false,
    /** The refusal under this box, or null. Also turns the box red. */
    error: String? = null,
    /**
     * Draw the record forms' on-device microphone.
     *
     * ANSWERED BY [dictates] AT EVERY CALL SITE, never written out by hand — see
     * [DESIGNER_PROFILE_DICTATED] for why the classification is a table rather than thirteen
 * arguments written out by hand.
     * The default is OFF so that a box added without an entry in either table is silent rather than
     * quietly acquiring a control nobody classified.
     */
    dictate: Boolean = false,
    /**
     * Re-seed the dictation buffer when a DIFFERENT designer's profile is opened into this
     * composition — an admin stepping through the roster is the case. Without it, a half-heard
     * phrase or a dictation refusal raised under one profile would still be on screen under the
     * next one's box. `targetUserId` is what identifies whose profile this is; null (the signed-in
     * account's own) is a stable key of its own. The biography and the address have keyed on it
     * since they got their microphones.
     */
    resetKey: Any? = null,
    /** Drawn under the box, below any dictation sentence — the address box's ceiling notice. */
    below: @Composable () -> Unit = {},
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
        help?.let { Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp) }
        RecordProseField(
            label = if (required) "$label *" else label,
            value = value,
            onValueChange = { next ->
                // THE FOLD, applied to the WHOLE value and not to what arrives: a committed
                // dictation phrase is appended to what is already in the box before it reaches this
                // lambda, so a filter that looked only at the newly spoken words would let a Return
                // typed earlier through. See [designerProfileOneLine].
                onValueChange(designerProfileOneLine(next))
            },
            enabled = enabled,
            dictate = dictate,
            keyboardType = keyboard,
            // Material's own supporting-text slot rather than a `Text` underneath, so TalkBack reads
            // the refusal WITH the box instead of as a stray paragraph after it. It also paints the
            // box in the error colour, which is why the message and the tint cannot get out of step.
            errorText = error,
            resetKey = resetKey,
            below = below,
        )
    }
}

/**
 * The stored location, in words, for somebody who may not edit it.
 *
 * THE TWO GROUPS ARE KEPT APART HERE TOO, and that is the whole reason this is not one line of
 * comma-separated values. The stated address is a STATEMENT BY A PERSON about where they work; the
 * coordinates are a reading taken by a device, which is very often a desk in another state. Fifteen
 * live artisan records exist because those two were once printed as one fact, and an admin reading a
 * colleague's profile to correct it is exactly the reader who must not be handed them merged.
 *
 * "Not recorded" rather than an empty card: a blank where a value would be is indistinguishable from
 * a value that failed to load, and this screen already refuses that trade for its photographs.
 */
@Composable
private fun StoredLocationSummary(location: LocationRequest?) {
    if (location == null) {
        Text(
            "No district or map point is recorded on this profile. The postal address above is " +
                "separate and may well be filled in.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
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
            fontSize = 12.sp
        )
        Text(
            "Captured at: ${readableCoordinate(location.latitude)}, " +
                "${readableCoordinate(location.longitude)} — where the device was, not necessarily " +
                "where the designer works.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )
    }
}

/**
 * Six decimal places, in a fixed locale.
 *
 * THE SAME RULE `LocationFields.trimCoordinate` APPLIES, and written out again here rather than
 * shared, which is worth a sentence. That function is file-PRIVATE and must stay so: `LocationCapture`
 * declares its own of the same name in the same package, and widening either makes the pair a
 * conflicting overload that stops both resolving. Merging the two is a change to two files this lane
 * does not own, so the rule is restated with its reason instead of quietly forked: [Locale.UK] and
 * never the device's, because a handset set to a comma-decimal locale renders 22,310000 — not a
 * number this API parses, and not one a reader can paste into a map.
 */
private fun readableCoordinate(value: Double): String = String.format(Locale.UK, "%.6f", value)

/**
 * The refusal under one mandatory box: the app's own "X is required", or nothing.
 *
 * SHORT HERE, LONG IN THE SNACKBAR. `StageSchema.validate` already refuses an empty required field
 * with the field's label followed by "is required", so this is the app's existing sentence rather
 * than a fifth phrasing of one idea; the reason a blank name matters — that it is printed on every
 * report — is a paragraph, and a paragraph under each of four boxes is four paragraphs nobody reads.
 * It is said once, in the refusal that follows the tap ([designerProfileRequiredRefusal]).
 *
 * [enforce] is the latch: nothing is marked until a save has actually been refused.
 */
private fun requiredRefusal(enforce: Boolean, value: String, label: String): String? =
    if (enforce && value.isBlank()) "$label is required" else null

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
    /**
     * Whose profile this is, so a half-heard town name is dropped when an admin steps to the next
     * designer in the roster rather than reappearing under somebody else's box. See [ProfileText].
     */
    resetKey: Any?,
    onValueChange: (String) -> Unit,
) {
    val districts = remember(state, reference) { reference.districts?.byState?.get(state).orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        // A TOWN'S NAME IS A FREE PROPER NOUN, SO IT TAKES A MICROPHONE — unlike the district picker
        // beneath it, which is a closed vocabulary answered by choosing rather than by typing, and
        // unlike the state above it. The web's `city` box makes the identical split for the
        // identical reason, and this box is the escape hatch from the district list rather than a
        // second copy of it: whichever way the value arrives, one column is written.
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

/**
 * The slot as a HEADING, and as the `caption` stored on the uploaded `MediaFile` row.
 *
 * Sentence case for the two nouns, upper case for the acronym — which is why [midSentence] exists
 * rather than a `lowercase()` at the one call site that needed the other form.
 */
internal fun ProfileMediaSlot.caption(): String = when (this) {
    ProfileMediaSlot.PHOTOGRAPH -> "Photograph"
    ProfileMediaSlot.SIGNATURE -> "Signature"
    ProfileMediaSlot.CV -> "CV"
}

/**
 * The same slot NAMED INSIDE A SENTENCE — "Could not upload the photograph." / "… the CV."
 *
 * A separate table and not `caption().lowercase()`. "Photograph" and "Signature" are ordinary nouns
 * that lower-case correctly; "CV" is an acronym, and lower-casing it produces "cv", which reads as a
 * typo rather than as a word mid-sentence. That is exactly the defect the owner reported on the web
 * ("Attach cv"), arriving here by the other door — and the reason the two forms are two functions is
 * that no rule can derive one from the other without knowing which strings are acronyms.
 */
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
