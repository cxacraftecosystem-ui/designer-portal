package com.designprototype.workshop.ui.designworkshop

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.data.DwCustomFieldDto
import com.designprototype.workshop.data.DwDerived
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.customFieldToFieldDto
import com.designprototype.workshop.data.dwCustomFieldDrawable
import com.designprototype.workshop.data.dwCustomUnsupportedNote
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.ui.MapPickerDialog
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text. Every file in this feature
// imports it. Without the import the bare `Text` in this file resolves to Material's, which inherits
// whatever family LocalTextStyle happens to carry and quietly sets headings in the body face — the
// exact failure FieldText.kt was written to make impossible.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.FieldDateField
import com.designprototype.workshop.ui.FieldTimeField
import com.designprototype.workshop.ui.ArtisanPhoneField
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.formatFieldDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDate

/**
 * ONE composable that can draw any of the registry's 496 fields, by dispatching on [FieldDto.type].
 *
 * THIS IS THE WHOLE POINT OF THE FEATURE. There is no per-stage form code anywhere in this app and
 * there must never be: the 22 stages carry 496 typed fields across 43 entities, the tiers within them
 * move between studies, and hand-writing the forms would make every registry edit an app release
 * while guaranteeing that the phone, the web form, the validator and the report writer each end up
 * with their own opinion of what stage 14 contains. They would drift, and the first anyone would
 * notice is a ministry report with an empty column. So a field added on the server appears here with
 * no client change, and the only thing this file knows about any particular field is its type.
 *
 * Everything it draws with already existed — [SearchableSelectField], [SearchableMultiSelectField],
 * [FieldDateField], [FieldTimeField], [ArtisanPhoneField], [MapPickerDialog]. Rebuilding any of them
 * would mean rebuilding the accessibility work, the locale-proof date entry and the measured ISD
 * column that each of those carries, and getting one of them subtly wrong in the one screen nobody
 * regression-tests.
 */

// --------------------------------------------------------------------------------------
// Media, as this feature sees it
// --------------------------------------------------------------------------------------

/**
 * One durable capture, already copied into the workshop's media directory.
 *
 * [absolutePath] points inside `filesDir`, never `cacheDir` and never at a content Uri. That is not
 * a preference: cacheDir is reclaimed by Android under storage pressure, silently and without a
 * callback, and a content Uri is a permission grant scoped to the task that received it, so both
 * resolve to nothing the morning after a designer photographs a loom. See [WorkshopDraftStore] for
 * the copy that puts the bytes somewhere that survives.
 */
@Immutable
data class DwMediaItem(
    val id: String,
    val displayName: String,
    val absolutePath: String,
    val mediaType: String,
    val sizeBytes: Long,
    /**
     * The SHA-256 [WorkshopDraftStore.importMedia] computed while it copied these bytes.
     *
     * Carried through so the capture card's duplicate check ([DwPhotoQualityAdvisories]) can say "the
     * same FILE is already attached here" from data the device already holds, rather than hashing
     * anything a second time. Null for a descriptor written before the store computed one, and null
     * must be read as "unknown" and never as "unique" — a legacy row with no hash may not be reported
     * as a duplicate OR as distinct.
     */
    val sha256: String? = null,
)

/**
 * How a field's media capture reaches the draft store.
 *
 * Passed down rather than reached for, so [FieldRenderer] has no idea which workshop it is drawing
 * and cannot accidentally write a photo into the wrong one — which is the failure a global store
 * handle invites the moment two workshops are open in the back stack.
 */
@Immutable
class DwMediaBridge(
    /** Resolve a stored media id to something drawable, or null when the bytes have gone missing. */
    val resolve: (String) -> DwMediaItem?,
    /**
     * Copy a whole picked/captured selection into the workshop's media directory and report the
     * new ids ONCE, in the order they were picked.
     *
     * TAKES A LIST, AND MUST. It used to take one Uri and be called in a loop, with each
     * per-Uri completion lambda closing over the `ids` snapshot read when the picker returned.
     * The imports run concurrently, so each callback wrote `staleIds + itsOwnId` over whatever
     * the previous one had written: pick five sketches and the field kept ONE. The other four
     * were fully imported — bytes copied, descriptors appended to the draft — but referenced by
     * no field, so the UI never drew a row for them, `detach` could never be called on them,
     * and they sat on the phone consuming storage with no way to remove them short of deleting
     * the workshop. Meanwhile the report printed one sketch of five.
     *
     * One call, one callback, one state write is what makes that impossible rather than
     * unlikely.
     */
    val attach: (List<Uri>, FieldDto, (List<String>) -> Unit) -> Unit,
    /** Forget one attachment and delete its bytes. */
    val detach: (String) -> Unit,
    /**
     * An empty file inside the workshop's own directory, for the camera or the recorder to fill.
     *
     * IT IS UNDER `filesDir`, NOT `cacheDir`, and that is the whole reason this is on the bridge
     * rather than a free function taking a Context. MainActivity's `createAppFile` puts captures in
     * `cacheDir/field-captures/`, which is correct there — those files are uploaded within seconds
     * and never read again. Here the file is the document. Android reclaims cacheDir under storage
     * pressure, silently and with no callback, and preferentially when the disk is tight, which on a
     * 32 GB field phone two weeks into a study is always. A camera intent that writes there can have
     * its output deleted between the shutter and the import.
     *
     * Only the caller knows WHICH workshop's directory that is, which is the same reason [attach] is
     * passed down rather than reached for: a renderer with a global handle would eventually write a
     * photograph into the wrong workshop with two of them open in the back stack.
     */
    val newCaptureFile: (String) -> File,
)

/**
 * The services a field needs that are not in the registry: the network, the workshop it belongs to,
 * and somewhere to say things.
 *
 * Bundled rather than passed as five parameters because every one of them is needed by three
 * different branches of the `when` below, and because a null bundle is exactly the state that
 * matters — it means "this field is being PREVIEWED rather than edited", and every control that
 * needs a server degrades to its offline or read-only form in one place instead of five.
 */
@Immutable
class DwFieldServices(
    val repository: WorkshopRepository,
    /** The id the references endpoint is asked about; null while the workshop is local-only. */
    val workshopId: String?,
    val onMessage: (String) -> Unit,
    val onError: (String) -> Unit,
    /**
     * How a REF picker opens the app's own artisan / product / tool / process form.
     *
     * Null is a real and ordinary state, not a missing dependency: only the host that owns the record
     * forms can supply one (see [DwInlineRecordHost]), so a stage rendered anywhere else simply does
     * not offer to create records, and the picker offers what it can honestly offer.
     */
    val inlineRecords: DwInlineRecordHost? = null,
) {
    val references: DwReferenceBridge = DwReferenceBridge(repository, workshopId, inlineRecords)
}

// --------------------------------------------------------------------------------------
// The renderer
// --------------------------------------------------------------------------------------

/**
 * Draw one field: its label, its help text, its unit, its input and its inline error.
 *
 * @param caption the field whose `captionFor` names [field]. It is drawn INSIDE this field's block,
 *   directly under the media it describes, and the caller must therefore keep it out of the ordinary
 *   field flow. A caption rendered as a separate input three rows below its photo is how a report
 *   comes to print the wrong description under a picture, permanently, in a file already delivered.
 */
@Composable
fun FieldRenderer(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    media: DwMediaBridge? = null,
    caption: FieldDto? = null,
    captionValue: JsonElement? = null,
    onCaptionChange: (JsonElement?) -> Unit = {},
    /**
     * What the inputs' local text buffers belong to, beyond the field key.
     *
     * THIS EXISTS BECAUSE OF A BUG THAT ONLY BITES ON EMPTY FIELDS. The scalar input keeps its own
     * text buffer (see [ScalarInput] for why it must) and re-seeds it from an effect keyed on the
     * stored value. Collection rows are rendered through the SAME composable slots, so opening
     * prototype 2 after prototype 1 hands the same `field.key` to the same slot — and when both rows
     * leave that field blank, the stored value is `null` in both, the effect's key does not change,
     * and the effect never fires. The buffer from row 1 stays on screen over row 2's data, and the
     * first keystroke writes row 1's half-typed answer into row 2.
     *
     * The caller passes the row's own id here, so the buffer is discarded when the row changes
     * regardless of whether the value did. Defaults to the field key for singleton entities, where
     * there is only ever one record and the problem cannot arise.
     */
    resetKey: Any = field.key,
    /** The network, the workshop id and the message sinks. Null means "previewed, not edited". */
    services: DwFieldServices? = null,
    /**
     * Every live field of the entity this one belongs to, by key.
     *
     * Needed for exactly two things and worth the parameter for both. A cascading REF has to name
     * its parent in words ("Choose Artisan first"), which means reading the parent's LABEL rather
     * than printing its key at a designer. And hydration has to know which keys the entity actually
     * declares, so a reference record carrying a column this entity does not have cannot write a
     * key into the row that the server will then drop and report as unrecognised on every sync.
     */
    siblings: Map<String, FieldDto> = emptyMap(),
    /**
     * The whole record this field sits in — the singleton's values, or the collection ROW's.
     *
     * A cascading dropdown is narrowed by a sibling's value, so the field cannot be drawn from its
     * own value alone. This is also what lets hydration tell an empty box from a typed answer.
     */
    rowValues: Map<String, JsonElement> = emptyMap(),
    /**
     * Write SEVERAL keys of this record at once.
     *
     * IT MUST BE A BATCH AND CANNOT BE A LOOP OVER [onChange]. The collection list's per-key setter
     * recomputes the whole row list from the `rows` snapshot its closure captured, so eight
     * sequential calls in one frame all start from the same stale snapshot and seven of them are
     * thrown away. Choosing an artisan would fill in one of eight fields, apparently at random —
     * the same class of failure that once turned a five-photograph selection into one photograph.
     */
    onPatch: (Map<String, JsonElement?>) -> Unit = {},
) {
    val type = remember(field.type) { DwFieldType.of(field.type) }
    val parentField = remember(field.refFilterBy, siblings) { siblings[field.refFilterBy] }
    val parentValue = rowValues[field.refFilterBy]

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ── RICH_TEXT ─────────────────────────────────────────────────────────────────────────
        //
        // Wired up as the TODO that stood here described: `RichTextEditor.kt` landed, `RICH_TEXT`
        // was added to `DwFieldType`, and the branch below is the one line it asked for. The order
        // mattered and was honoured — the enum constant was added only once the editor existed,
        // because a constant without a branch renders an empty `when` arm instead of degrading to
        // a text box, which loses the designer's prose silently.
        //
        // The 81 NARRATIVE fields in the server registry carry this type as of the same change.
        // A phone that has not yet fetched that registry still sees LONG_TEXT and still captures a
        // plain string, and the server reads a plain string as unformatted prose — so the two
        // builds interoperate in both directions and no fieldwork is lost either way.
        when (type) {
            // `value` is passed through untouched. It is `{"blocks":[…]}` for a field edited
            // since the promotion and a bare JSON string for one written before it; `fromJson`
            // inside the editor reads both, which is what keeps a season of prose intact.
            DwFieldType.RICH_TEXT ->
                RichTextEditor(
                    value = value,
                    onChange = onChange,
                    enabled = enabled,
                    label = fieldLabel(field),
                    help = field.help,
                    // A BULLETS field IS a list — its help says "One deliverable per line" and the
                    // report prints it as one — so open inside a numbered item rather than making
                    // the designer type "1. " to get the behaviour the label already promised.
                    listKind = if (field.reportRole == "BULLETS") BlockKind.ORDERED_ITEM else null,
                    // THE SAME BRIDGE EVERY MEDIA FIELD ON THIS STAGE USES, which is what makes a
                    // photograph placed inside prose an ordinary attachment: one descriptor in
                    // `draft.media`, so the sync engine uploads it and the on-device report writer
                    // resolves it, with no second media pipeline to keep in step. Null in a preview,
                    // and the editor then draws an IMAGE block without offering to place one.
                    media = media,
                    mediaField = field,
                    onMessage = { text -> services?.onMessage?.invoke(text) },
                    onError = { text -> services?.onError?.invoke(text) },
                )

            DwFieldType.LONG_TEXT ->
                ScalarInput(
                    field, value, onChange, enabled, error, minLines = 4, resetKey = resetKey,
                    services = services,
                )

            // The numeric three take `rowValues` for one reason: a derived field is computed from its
            // SIBLINGS, so it cannot be drawn from its own value alone. See [ScalarInput]'s note.
            DwFieldType.INT ->
                ScalarInput(field, value, onChange, enabled, error, keyboard = KeyboardType.Number, resetKey = resetKey, rowValues = rowValues)

            DwFieldType.DECIMAL, DwFieldType.PERCENT ->
                ScalarInput(field, value, onChange, enabled, error, keyboard = KeyboardType.Decimal, resetKey = resetKey, rowValues = rowValues)

            // Money gets the decimal pad rather than the number pad: a costing line is entered as
            // "1250.50" and a keyboard with no decimal point turns that into ₹125050.
            DwFieldType.MONEY ->
                ScalarInput(field, value, onChange, enabled, error, keyboard = KeyboardType.Decimal, resetKey = resetKey, rowValues = rowValues)

            DwFieldType.URL ->
                ScalarInput(field, value, onChange, enabled, error, keyboard = KeyboardType.Uri, resetKey = resetKey)

            DwFieldType.EMAIL ->
                ScalarInput(field, value, onChange, enabled, error, keyboard = KeyboardType.Email, resetKey = resetKey)

            DwFieldType.DATE -> DateField(field, value, onChange, error)

            DwFieldType.TIME -> {
                FieldCaption(field)
                FieldTimeField(
                    label = fieldLabel(field),
                    value = DwValues.text(value),
                    onValueChange = { text -> onChange(text.takeIf { it.isNotBlank() }?.let(::JsonPrimitive)) }
                )
                InlineError(error)
            }

            DwFieldType.BOOL -> BoolField(field, value, onChange, enabled, error)

            DwFieldType.ENUM -> {
                FieldCaption(field)
                SearchableSelectField(
                    label = fieldLabel(field),
                    options = remember(field.options) {
                        field.options.map { SelectOption(it.value, it.label) }
                    },
                    selectedValue = DwValues.text(value),
                    enabled = enabled,
                    // A required field still offers the blank row. Removing it would mean a designer
                    // who picked the wrong option in a courtyard has no way back to "unanswered", and
                    // a wrong answer scores as complete where a blank one would have been flagged.
                    includeNone = true,
                    onSelect = { token -> onChange(token.takeIf { it.isNotBlank() }?.let(::JsonPrimitive)) }
                )
                InlineError(error)
            }

            // A MULTI_ENUM that names a `refModel` instead of an `enum` is the multi-select over
            // RECORDS — the roster picker the requirement names: "for a particular workshop, the
            // multiselect dropdown of artisans". Reusing MULTI_ENUM rather than inventing a
            // REF_LIST field type is what keeps the phone from needing a registry the server does
            // not serve: the stored value is a JSON array of tokens either way, so the server
            // validates, stores and prints it unchanged, and the only difference is where the
            // labels come from.
            DwFieldType.MULTI_ENUM -> if (field.refModel.isNotBlank()) {
                DwReferenceMultiSelectField(
                    field = field,
                    value = value,
                    parentField = parentField,
                    parentValue = parentValue,
                    bridge = services?.references,
                    enabled = enabled,
                    error = error,
                    label = fieldLabel(field),
                    onChange = onChange,
                )
            } else {
                FieldCaption(field)
                SearchableMultiSelectField(
                    label = fieldLabel(field),
                    options = remember(field.options) {
                        field.options.map { SelectOption(it.value, it.label) }
                    },
                    selected = remember(value) { DwValues.list(value).toSet() },
                    enabled = enabled,
                    onSelectedChange = { chosen ->
                        // Re-ordered to the REGISTRY's order rather than the tick order, so two
                        // designers who selected the same three materials produce the same stored
                        // array and the report's list does not change shape between workshops.
                        onChange(DwValues.ofList(field.options.map { it.value }.filter { it in chosen }))
                    }
                )
                InlineError(error)
            }

            DwFieldType.TAGS -> TagsField(field, value, onChange, enabled, error, resetKey)

            // The REAL location card — the live fix, the map pin, and the state and district
            // dropdowns built from the canonical lists `GET /reference/address` serves — and not
            // the two typed boxes that used to be here. The boxes captured a coordinate and left a
            // human to type the administrative half from memory, which is exactly how this app
            // came to have fifteen live records placing Rajasthani artisans in West Bengal.
            //
            // The fallback below is not a lesser option offered out of caution: [DwGeoField] needs
            // a repository to fetch those canonical lists, so with no services bundle — a stage
            // being previewed rather than edited — there is nothing to populate the dropdowns
            // with, and two typed boxes plus the map picker is the whole of what can honestly be
            // drawn.
            DwFieldType.GEO -> if (services != null) {
                DwGeoField(
                    label = fieldLabel(field),
                    help = field.help,
                    required = field.required,
                    value = value,
                    repository = services.repository,
                    enabled = enabled,
                    error = error,
                    onChange = onChange,
                    onMessage = services.onMessage,
                )
            } else {
                GeoField(field, value, onChange, enabled, error, resetKey)
            }

            DwFieldType.PHONE -> {
                FieldCaption(field)
                // The ISD-prefix editor, reused whole. Its own inner caption reads "Phone" while the
                // registry's label sits above it — a small duplication accepted deliberately, because
                // the alternative is a second phone field without the measured dial column, the
                // country search and the foreign-resident confirmation this one already carries.
                ArtisanPhoneField(
                    value = DwValues.text(value),
                    error = error,
                    onValueChange = { text ->
                        onChange(text.trim().takeIf { it.isNotBlank() }?.let(::JsonPrimitive))
                    }
                )
            }

            // A foreign key to an Artisan, a Craft or another stage entry, drawn as a PICKER over
            // the records that exist — cascaded by `refFilterBy`, hydrating the row from the chosen
            // record, and served from a durable on-device cache so it works with no signal.
            //
            // This used to be a box for typing a raw id, on the argument that a picker "would need
            // a per-model lookup that cannot be served offline". The constraint was real and the
            // conclusion was wrong: the answer is to make the list a document the device owns (see
            // [com.designprototype.workshop.data.DwReferenceStore]) rather than to abandon the
            // picker. Nobody types a UUID into a phone in a courtyard, so what the box actually
            // collected was nothing — and the registry's own note records what that cost: "typing
            // thirty names in by hand produced thirty rows with no join key, which is why a
            // cluster's second workshop could never be compared with its first".
            //
            // The typed box survives as the no-services fallback, where a preview genuinely has no
            // way to look anything up and a field that admits what it wants beats a dead dropdown.
            DwFieldType.REF -> if (services != null) {
                DwReferenceSelectField(
                    field = field,
                    value = value,
                    parentField = parentField,
                    parentValue = parentValue,
                    bridge = services.references,
                    enabled = enabled,
                    error = error,
                    label = fieldLabel(field),
                    rowValues = rowValues,
                    writableFields = siblings,
                    onChange = onChange,
                    onHydrate = onPatch,
                )
            } else {
                ScalarInput(
                    field, value, onChange, enabled, error,
                    helpOverride = listOfNotNull(
                        field.help.takeIf { it.isNotBlank() },
                        field.refModel.takeIf { it.isNotBlank() }?.let { "Identifier of the linked $it record." }
                    ).joinToString(" "),
                    resetKey = resetKey,
                )
            }

            DwFieldType.IMAGE, DwFieldType.IMAGE_LIST, DwFieldType.FILE,
            DwFieldType.AUDIO, DwFieldType.VIDEO ->
                MediaField(
                    field = field,
                    type = type,
                    value = value,
                    onChange = onChange,
                    enabled = enabled,
                    error = error,
                    media = media,
                    caption = caption,
                    captionValue = captionValue,
                    onCaptionChange = onCaptionChange,
                    resetKey = resetKey,
                    services = services,
                    // For "measure a dimension from this photograph": the entity's OTHER fields are
                    // what a measurement can be proposed into, the row is what it would replace, and
                    // `onPatch` is the one door it may write through. See [DwPhotoMeasurePanel].
                    siblings = siblings,
                    rowValues = rowValues,
                    onPatch = onPatch,
                )

            DwFieldType.TEXT -> ScalarInput(
                field, value, onChange, enabled, error, resetKey = resetKey,
                services = services,
            )
        }
    }
}

// --------------------------------------------------------------------------------------
// The designer's own questions
// --------------------------------------------------------------------------------------

/**
 * ONE custom question, drawn by the renderer above where that is safe and read-only where it is not.
 *
 * ── THE DEGRADE IS THE POINT OF THIS FUNCTION, AND IT HAS TO HAPPEN HERE RATHER THAN INSIDE ───────
 *
 * [FieldRenderer]'s `when` has no `else`, so the type set is closed and the compiler forces an arm
 * for every constant — which is exactly what makes adding a constant safe. But the token never
 * reaches that `when` as itself: [DwFieldType.of] degrades ANY unrecognised token to
 * [DwFieldType.TEXT], deliberately, because for the 496-field registry the alternative is one new
 * server type blanking all 22 stages on every handset that has not updated. For a designer's own
 * question that same forgiveness is the whole of the failure: an unknown type is drawn as an ordinary
 * editable box with no note, no disabled state and no caption, so the designer TYPES AN ANSWER INTO
 * IT — a silent wrong answer, which is worse than the web's silent blank because the web at least
 * collected nothing.
 *
 * So the refusal happens one door up, before the renderer is called, and it asks two questions that
 * fail for different reasons ([dwCustomFieldDrawable]): is this one of the twelve types a custom
 * answer can safely ROUND TRIP as, and does this build know the token at all
 * ([DwFieldType.known] — the strict resolver, which exists for this one caller and must not replace
 * [DwFieldType.of]).
 *
 * ── WHAT THE READ-ONLY ARM PROMISES, AND WHO KEEPS THE PROMISE ────────────────────────────────────
 *
 * The sentence says the recorded answer is kept and unchanged. That is kept by two things and by
 * neither of them being this composable: no `onChange` is wired, and `buildStageBody` sends the WHOLE
 * `custom` bucket rather than the fields the screen happened to draw — so the stored value goes back
 * up under its own key, which the SERVER's definition does carry (what this build lacks is a control
 * for its type, not the question) and which `validate_custom_entry` therefore coerces and stores
 * rather than dropping.
 *
 * SAID PRECISELY, BECAUSE THE OBVIOUS VERSION OF IT IS WRONG: `plan_custom_write` carries a RETIRED
 * key forward from `previous` and does NOT do the same for an unrecognised one. Executed —
 * `plan_custom_write([loomsWorking], sent={'other': 1}, previous={'loomsWorking': 12}, merge=False)`
 * returns `data={}, dropped=('other',)` — an unknown key is dropped and reported, and a replace
 * writes the row without it. That is why this promise rests on the key being one the server knows.
 * The value is shown rather than hidden because a designer standing in a cluster needs to be able to
 * read out what was recorded.
 */
@Composable
fun DwCustomFieldRow(
    field: DwCustomFieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    services: DwFieldServices? = null,
    /** Every drawable live field of this section, by key — see [FieldRenderer]'s `siblings`. */
    siblings: Map<String, FieldDto> = emptyMap(),
    /** The whole custom bucket for this stage, so a derived or cascading control could read it. */
    rowValues: Map<String, JsonElement> = emptyMap(),
) {
    val spec = remember(field) { customFieldToFieldDto(field) }
    if (!dwCustomFieldDrawable(field.type)) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScalarInput(
                field = spec,
                value = value,
                // Deliberately inert. The box cannot be typed into (`enabled = false`), and this is
                // the second lock rather than the first: a future edit that re-enabled the control
                // would otherwise start writing values under a shape nothing downstream can read.
                onChange = {},
                enabled = false,
                error = null,
                // The help line is REPLACED rather than appended to. The designer's own help text
                // tells them how to answer a question they cannot answer here, and printing both
                // makes the honest sentence the second thing they read.
                helpOverride = dwCustomUnsupportedNote(field.type),
            )
        }
        return
    }
    FieldRenderer(
        field = spec,
        value = value,
        onChange = onChange,
        modifier = modifier,
        error = error,
        // NO MEDIA BRIDGE, and it cannot be needed: v1 declares no media type, so no arm that could
        // use one is reachable. Handing a bridge in anyway would be an invitation for v1.1 to add a
        // media type and have it appear to work while syncing a `dwlocal:` reference to nothing.
        media = null,
        resetKey = field.key,
        services = services,
        siblings = siblings,
        rowValues = rowValues,
    )
}

// --------------------------------------------------------------------------------------
// Shared chrome
// --------------------------------------------------------------------------------------

/**
 * The label as it is read: the registry's own words, a trailing asterisk when the field is required,
 * and the unit in brackets.
 *
 * The unit belongs IN the label rather than in a suffix inside the box. A suffix is clipped by the
 * value on a narrow phone, and a measurement captured without its unit — 40 what? centimetres,
 * inches, picks per inch? — is a number nobody downstream can use.
 */
private fun fieldLabel(field: FieldDto): String = buildString {
    append(field.label)
    if (field.unit.isNotBlank()) append(" (${field.unit})")
    if (field.required) append(" *")
}

/** The help line, above the input, or nothing at all when the registry supplies none. */
@Composable
private fun FieldCaption(field: FieldDto, override: String? = null) {
    val help = override ?: field.help
    if (help.isBlank()) return
    Text(help, color = MaterialTheme.field.muted, fontSize = 12.sp)
}

@Composable
private fun InlineError(error: String?) {
    if (error.isNullOrBlank()) return
    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
}

/**
 * Every text-shaped type, with a local buffer so typing is not fought by the store.
 *
 * THE BUFFER IS THE POINT. The stored value for a MONEY field is "1250.00" and for an INT it is a
 * number; feeding the stored value straight back into the box would rewrite "1250." to "1250.00"
 * under the cursor as the designer types the decimals, and would erase a half-typed "-" on a
 * negative. So the box owns the text and the store owns the value, and they are re-synchronised only
 * when the store moved to something the box does not already represent — which is what happens when
 * the caller switches to another collection row through the same composable.
 */
@Composable
private fun ScalarInput(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    minLines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
    helpOverride: String? = null,
    /** See [FieldRenderer]'s `resetKey`: the buffer belongs to a ROW, not merely to a field key. */
    resetKey: Any = field.key,
    services: DwFieldServices? = null,
    /** The whole record this field sits in — what a DERIVED field is computed from. */
    rowValues: Map<String, JsonElement> = emptyMap(),
) {
    var buffer by remember(field.key, resetKey) { mutableStateOf(DwValues.text(value)) }
    var localError by remember(field.key, resetKey) { mutableStateOf<String?>(null) }
    /**
     * The recogniser's running guess, drawn in the box but NOT yet in the store.
     *
     * Kept separate from [buffer] rather than appended to it as it grows, and the separation is the
     * point. A partial result is revised as the sentence continues — "the weaver" becomes "the
     * weavers of Bagru" — so appending each one would leave the box holding every draft of the
     * sentence concatenated. Held apart, the last partial is simply replaced by the next, and by
     * the final text when it arrives.
     */
    var spoken by remember(field.key, resetKey) { mutableStateOf("") }

    val dictationAvailable = rememberDictationAvailable()
    val canDictate = services != null && dictationAvailable &&
        dictatable(DwFieldType.of(field.type))
    // No `media` in this condition any more, and the removal is the point: the card control used to
    // need the stage's media bridge to get a file to photograph into, which tied it to a screen that
    // has one. It now owns its own scratch file (and deletes it), so the only thing it needs is a
    // repository to send the bytes through — which is what let the same control reach the artisan
    // form in MainActivity, where the Aadhaar number is actually entered and where there is no
    // media bridge at all.
    val canReadCard = services != null &&
        DwFieldType.of(field.type) == DwFieldType.TEXT && isIdentityNumberField(field)

    /**
     * What a DERIVED field computes to right now, shown while its box is still empty.
     *
     * `durationDays` has always said "Leave blank to derive it from the start and end dates" and
     * nothing on this surface derived it: the box stayed empty and so did the cover page of every
     * report generated before the next sync. [DwDerived] is the arithmetic; this is the only place it
     * reaches a designer's eyes.
     *
     * SHOWN AND NOT WRITTEN, deliberately, and the web makes the same choice for the same reason.
     * Writing the number into the box would make the field look filled, and the designer could no
     * longer clear it to mean "derive this for me" — they would have to remember the rule and retype
     * the figure. Blank still means derived, the server computes the same value from the same
     * declaration on save, and typing over it is still how you override it. So the field stays an
     * ordinary editable box; nothing here disables it.
     *
     * The wording is the browser's, character for character, because a designer who checks the
     * duration on a phone and then again on a laptop must not have to work out whether two
     * differently-phrased lines are telling them the same thing.
     */
    val derivedHint = remember(field, rowValues) {
        if (!DwDerived.isDerived(field)) null else DwDerived.value(field, rowValues)?.let { computed ->
            val shown = DwValues.text(computed)
            if (field.unit.isBlank()) "$shown (computed)" else "$shown ${field.unit} (computed)"
        }
    }

    LaunchedEffect(value) {
        val stored = DwValues.text(value)
        // Compare through the coercion, not by string equality: "1250.5" in the box and "1250.50" in
        // the store are the same answer, and re-seeding on that difference is exactly the cursor jump
        // this guard exists to prevent.
        val bufferAsStored = DwValues.text(DwValues.coerce(field, buffer).value)
        if (bufferAsStored != stored) buffer = stored
    }

    /** Commit typed or spoken text through exactly one coercion, so both paths validate alike. */
    fun commit(text: String) {
        buffer = text
        val coerced = DwValues.coerce(field, text)
        localError = coerced.error
        // A value that will not coerce is NOT pushed to the store, and that asymmetry is
        // deliberate: half-typed "12." must not land in the draft as the number 12, because
        // the designer's next keystroke would then be appending to a value the store has
        // already reinterpreted. The error says so; the draft keeps the last good answer.
        if (coerced.error == null) onChange(coerced.value)
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldCaption(field, helpOverride)
        OutlinedTextField(
            // While a partial is streaming the box shows what has been heard SO FAR, appended to
            // what was already there. Rendering it anywhere else — a strip below, a toast — is what
            // makes a designer distrust dictation: they cannot tell whether the words they can see
            // are the words that will be saved. Here they are literally in the box they will be
            // saved from.
            value = if (spoken.isBlank()) buffer else appendSpoken(buffer, spoken),
            onValueChange = { raw -> if (spoken.isBlank()) commit(raw) },
            label = { Text(fieldLabel(field)) },
            enabled = enabled,
            // Read-only for the seconds the recogniser is running. A keystroke landing in the middle
            // of a stream would be overwritten by the next partial, so the alternative is a box that
            // silently discards typing — which reads as a broken keyboard.
            readOnly = spoken.isNotBlank(),
            isError = error != null || localError != null,
            // SUPPORTING TEXT AND NOT A PLACEHOLDER, which is where the browser puts the same string.
            // Material3 hides a placeholder behind the label until the box has focus, so a placeholder
            // here would reveal the computed figure only to a designer who tapped a box they had every
            // reason not to tap — the help text told them to leave it blank. Below the box it is
            // simply visible, which is the whole point of computing it early.
            //
            // Only while the box is EMPTY: once they have typed their own figure the derivation is
            // overridden, and a line underneath still quoting a different number reads as an error.
            supportingText = derivedHint?.takeIf { buffer.isBlank() }?.let { hint -> { Text(hint) } },
            minLines = minLines,
            singleLine = minLines == 1 && spoken.isBlank(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            trailingIcon = if (!canDictate) null else {
                {
                    DwDictationButton(
                        enabled = enabled,
                        onPartial = { partial -> spoken = partial },
                        onCommit = { finalText ->
                            val merged = appendSpoken(buffer, finalText)
                            spoken = ""
                            commit(merged)
                        },
                        onError = { message ->
                            spoken = ""
                            services?.onError?.invoke(message)
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DwDictationHint(listening = spoken.isNotBlank())
        // `canReadCard` already carries the null check, and the compiler propagates that through the
        // local val — repeating it here only earns a "condition is always true" warning.
        if (canReadCard) {
            DwIdentityCardControl(
                targetLabel = field.label,
                kind = identityKindFor(field),
                repository = services!!.repository,
                enabled = enabled,
                // The ONLY route from the reader to the field, and it is reached from a tap on a
                // button that spells the number out. Nothing above ever calls this.
                onUse = { number -> commit(number) },
                onError = services.onError,
            )
        }
        InlineError(localError ?: error)
    }
}

/**
 * Spoken text joined to what is already in the box.
 *
 * A space is inserted only where one is missing, so dictating twice into the same field does not
 * produce "…in Bagru.The second" and does not produce a double space either. Both are trivial and
 * both end up in a ministry report verbatim, because nobody proof-reads four hundred narrative
 * fields.
 */
private fun appendSpoken(existing: String, spoken: String): String = when {
    spoken.isBlank() -> existing
    existing.isBlank() -> spoken
    existing.last().isWhitespace() -> existing + spoken
    else -> "$existing $spoken"
}

@Composable
private fun DateField(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    error: String?,
) {
    // Parsed here rather than inside the picker so a stored value this build cannot read degrades to
    // "no date" instead of throwing during composition — which would take down the whole stage screen
    // for one malformed string in one field of one collection row.
    val parsed = remember(value) {
        val text = DwValues.text(value)
        if (text.isBlank()) null else runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
    }
    FieldCaption(field)
    FieldDateField(
        label = fieldLabel(field),
        value = parsed,
        clearable = true,
        onValueChange = { day -> onChange(day?.let { JsonPrimitive(it.toString()) }) },
        // Confirms in words what the eight typed digits mean, because the box itself shows
        // dd/mm/yyyy on every handset regardless of locale and a reader cannot tell whether that is
        // the app's choice or the phone's.
        supportingText = parsed?.let { "Saved as ${formatFieldDate(it)}" }
    )
    InlineError(error)
}

/**
 * Yes / No, plus a way back to unanswered.
 *
 * A Switch would be wrong here and the difference matters to the data. A switch has two states, so an
 * untouched one reads as "No" — and "No, this cluster has no power supply" is a FINDING, while "we
 * never asked" is a gap. Conflating them means a report that asserts something nobody established.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoolField(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
) {
    val current = remember(value) { DwValues.bool(value) }
    Text(fieldLabel(field), color = MaterialTheme.field.muted, fontSize = 12.sp)
    FieldCaption(field)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = current == true,
            enabled = enabled,
            onClick = { onChange(if (current == true) null else JsonPrimitive(true)) },
            label = { Text("Yes") }
        )
        FilterChip(
            selected = current == false,
            enabled = enabled,
            onClick = { onChange(if (current == false) null else JsonPrimitive(false)) },
            label = { Text("No") }
        )
        if (current != null) {
            TextButton(onClick = { onChange(null) }, enabled = enabled) { Text("Clear") }
        }
    }
    InlineError(error)
}

/**
 * A free-form list with no canonical vocabulary behind it.
 *
 * Committed on the button and on nothing else. An earlier shape committed on every comma, which meant
 * a designer typing "Bagru, Sanganer" got the tag "Bagru" plus a half-typed "Sanganer" the moment
 * they paused — and tags are the one field type whose values are never validated against anything, so
 * a malformed one is never caught downstream.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsField(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    resetKey: Any = field.key,
) {
    val tags = remember(value) { DwValues.list(value) }
    var pending by remember(field.key, resetKey) { mutableStateOf("") }

    fun commit() {
        val cleaned = pending.trim()
        pending = ""
        if (cleaned.isEmpty() || tags.any { it.equals(cleaned, ignoreCase = true) }) return
        onChange(DwValues.ofList(tags + cleaned))
    }

    Text(fieldLabel(field), color = MaterialTheme.field.muted, fontSize = 12.sp)
    FieldCaption(field)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = pending,
            onValueChange = { pending = it.replace("\n", "") },
            label = { Text("Add") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = ::commit, enabled = enabled && pending.isNotBlank()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add")
        }
    }
    if (tags.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            tags.forEach { tag ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                        .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    Text(tag, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp)
                    IconButton(
                        onClick = { onChange(DwValues.ofList(tags.filterNot { it == tag })) },
                        enabled = enabled,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove $tag",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
    InlineError(error)
}

/**
 * A coordinate: two typed boxes and the existing map picker.
 *
 * Not [com.designprototype.workshop.ui.LocationEditor], despite the overlap, because that component
 * speaks `LocationRequest` — a record-form type carrying altitude, accuracy and a place name — and
 * needs a live GPS provider handed in. A registry GEO value is `{lat, lon, accuracy}` and nothing
 * else, so adapting between the two would mean inventing a place name for every reading and then
 * discarding it on the way back out.
 */
@Composable
private fun GeoField(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    resetKey: Any = field.key,
) {
    val point = remember(value) { DwValues.geo(value) }
    var latText by remember(field.key, resetKey) { mutableStateOf(point?.first?.let { trimCoordinate(it) } ?: "") }
    var lonText by remember(field.key, resetKey) { mutableStateOf(point?.second?.let { trimCoordinate(it) } ?: "") }
    var showMap by remember { mutableStateOf(false) }

    // Adopt an incoming coordinate only when it is a genuinely different point, exactly as
    // LocationEditor does: re-seeding on every emit rewrites "22.5" to "22.500000" under the cursor.
    LaunchedEffect(point) {
        val sameLat = point?.first == latText.trim().toDoubleOrNull()
        val sameLon = point?.second == lonText.trim().toDoubleOrNull()
        if (!sameLat || !sameLon) {
            latText = point?.first?.let { trimCoordinate(it) } ?: ""
            lonText = point?.second?.let { trimCoordinate(it) } ?: ""
        }
    }

    fun emit() {
        val lat = latText.trim().toDoubleOrNull()
        val lon = lonText.trim().toDoubleOrNull()
        when {
            lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 ->
                onChange(DwValues.geoOf(lat, lon))
            // Both boxes empty is the researcher clearing the reading. One box empty is a reading
            // half-typed, and writing a partial coordinate would store a point in the Gulf of Guinea.
            latText.isBlank() && lonText.isBlank() -> onChange(null)
            else -> Unit
        }
    }

    Text(fieldLabel(field), color = MaterialTheme.field.muted, fontSize = 12.sp)
    FieldCaption(field)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = latText,
            onValueChange = { latText = it; emit() },
            label = { Text("Latitude") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = lonText,
            onValueChange = { lonText = it; emit() },
            label = { Text("Longitude") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { showMap = true }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Pick on map")
        }
        if (point != null) {
            TextButton(onClick = { latText = ""; lonText = ""; onChange(null) }, enabled = enabled) {
                Text("Clear")
            }
        }
    }
    InlineError(error)

    if (showMap) {
        MapPickerDialog(
            initialLat = latText.trim().toDoubleOrNull() ?: point?.first,
            initialLng = lonText.trim().toDoubleOrNull() ?: point?.second,
            onDismiss = { showMap = false },
            onPick = { lat, lon ->
                latText = trimCoordinate(lat)
                lonText = trimCoordinate(lon)
                onChange(DwValues.geoOf(lat, lon))
                showMap = false
            }
        )
    }
}

private fun trimCoordinate(value: Double): String = String.format(java.util.Locale.ROOT, "%.6f", value)

// --------------------------------------------------------------------------------------
// Media
// --------------------------------------------------------------------------------------

/**
 * Capture for IMAGE / IMAGE_LIST / FILE / AUDIO / VIDEO, plus the caption field that belongs to it.
 *
 * THE CAPTION IS DRAWN HERE, under the picture, and not wherever the registry happens to list it.
 * `captionFor` exists precisely so the two are never separated: a lone "Caption" box further down the
 * form gets filled in about a different photo, and the mistake surfaces in a .docx already sent to a
 * ministry, where nothing about the file admits that the caption and the picture disagree.
 *
 * Every attachment goes through [DwMediaBridge.attach], which copies the bytes into the workshop's
 * media directory under filesDir before the id is stored. Storing the picker's Uri instead would give
 * the draft a photo reference that resolves to nothing after process death, because a content Uri is
 * a grant scoped to the task that received it — and the app's own camera captures land in cacheDir,
 * which Android empties, without warning, exactly when a field phone is short of space.
 */
@Composable
private fun MediaField(
    field: FieldDto,
    type: DwFieldType,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    media: DwMediaBridge?,
    caption: FieldDto?,
    captionValue: JsonElement?,
    onCaptionChange: (JsonElement?) -> Unit,
    resetKey: Any,
    services: DwFieldServices? = null,
    /** Every live field of this entity, by key — where a measured dimension could be proposed. */
    siblings: Map<String, FieldDto> = emptyMap(),
    /** The record this field sits in, so a proposal can say what it would replace. */
    rowValues: Map<String, JsonElement> = emptyMap(),
    /** The one door a measurement may be written through. See [FieldRenderer]'s `onPatch`. */
    onPatch: (Map<String, JsonElement?>) -> Unit = {},
) {
    val ids = remember(value, type) {
        if (type == DwFieldType.IMAGE_LIST) DwValues.list(value)
        else listOfNotNull(DwValues.text(value).takeIf { it.isNotBlank() })
    }

    Text(fieldLabel(field), color = MaterialTheme.field.muted, fontSize = 12.sp)
    FieldCaption(field)

    if (media == null) {
        // The bridge is absent only where a stage is being previewed rather than edited. Say so
        // rather than drawing a dead button: a capture control that silently does nothing is
        // indistinguishable, to the person holding the phone, from a broken camera.
        Text("Attachments can be added once this workshop is open for editing.",
            color = MaterialTheme.field.muted, fontSize = 12.sp)
        return
    }

    // The real capture surface — camera, gallery, recorder and file picker in one card, with
    // thumbnails, playback and removal. NOT a lone "Attach file" button, which sends a designer out
    // to the camera app and back through a gallery picker for every one of the photographs that
    // fifteen of the twenty-two stages ask for. See [DwMediaCaptureCard] for why it mirrors
    // MainActivity's `MediaCaptureSection` rather than calling it.
    DwMediaCaptureCard(
        field = field,
        type = type,
        ids = ids,
        media = media,
        enabled = enabled,
        onIdsChange = { next ->
            // ONE write per capture, whatever it was. Both shapes go through here so the
            // single-valued case cannot drift from the list case.
            onChange(
                if (type == DwFieldType.IMAGE_LIST) DwValues.ofList(next)
                else next.firstOrNull()?.let(::JsonPrimitive)
            )
        },
        onMessage = services?.onMessage ?: {},
        onError = services?.onError ?: {},
    )

    /*
     * "Measure a dimension from this photograph", offered on every image field of an entity that
     * records a length — which is four entities and thirteen fields in the bundled registry, and no
     * offer at all on the other thirty-nine. See [dwOffersPhotoMeasure] for why nothing here tries to
     * guess WHICH photograph has the ruler in it.
     *
     * ADDITIVE, like every other extra on this card. It sits after the attachment rows because it is
     * about them, and it cannot touch the import: by the time it composes the photograph is already
     * copied, hashed and in the draft, and the panel only ever READS the bytes. Its one write is
     * `onPatch`, from a button that spells out the figure it will put in the field.
     */
    if (dwOffersPhotoMeasure(field, siblings)) {
        val targets = remember(siblings) { dwMeasurableLengthFields(siblings) }
        // Resolved here rather than inside the panel so the panel never sees the bridge and cannot
        // reach the draft store by any route other than the patch above.
        val photos = ids.mapNotNull(media.resolve).filter { it.mediaType.equals("IMAGE", ignoreCase = true) }
        DwPhotoMeasurePanel(
            photos = photos,
            targets = targets,
            rowValues = rowValues,
            enabled = enabled,
            onPropose = { key, proposed -> onPatch(mapOf(key to proposed)) },
        )
    }

    /*
     * "Straighten a photographed sketch into a plate", offered on the FILE field a plate belongs in.
     *
     * TWO FIELDS IN THE BUNDLED REGISTRY, NOT ONE: stage 11's `sketch.lineArtFile` — the pairing the
     * feature was written for — and stage 16's `finalProduct.lineDrawing`, which is often a CAD
     * export this panel has nothing to do with and just as often a technical drawing made on paper
     * and photographed. [dwOffersSketchRectify] argues that second one, and DwSketchRectifyFieldTest
     * names both and refuses the registry's other six FILE fields by name. This comment said
     * "`sketch.lineArtFile` and nothing else", which was already untrue of the code beneath it and is
     * the sentence a reader would trust instead of running the test.
     *
     * THE SOURCE PHOTOGRAPHS COME FROM THE ENTITY'S OTHER FIELDS, not from this one: a FILE field
     * holds the destination, and what is being read is whatever was attached to `image`. That is also
     * why the panel is here rather than on the photograph — attaching to a single-valued IMAGE field
     * REPLACES its value, so a panel that wrote a plate onto `image` would detach the original
     * photograph, which docs/MEDIA_PIPELINE.md §5 refuses.
     *
     * Its one write is `onChange`, from a button that spells out what it will attach, after the
     * designer has looked at the plate it will attach.
     */
    if (dwOffersSketchRectify(field, siblings)) {
        val sourceFields = remember(siblings) { dwSketchSourceFields(siblings) }
        val sources = sourceFields.flatMap { imageField ->
            val imageIds = if (DwFieldType.of(imageField.type) == DwFieldType.IMAGE_LIST) {
                DwValues.list(rowValues[imageField.key])
            } else {
                listOfNotNull(DwValues.text(rowValues[imageField.key]).takeIf { it.isNotBlank() })
            }
            imageIds.mapNotNull(media.resolve)
                .filter { it.mediaType.equals("IMAGE", ignoreCase = true) }
                .map { DwSketchSource(fieldLabel = imageField.label, item = it) }
        }
        DwSketchRectifyPanel(
            field = field,
            sources = sources,
            media = media,
            currentFileName = ids.firstOrNull()?.let(media.resolve)?.displayName,
            enabled = enabled,
            onAttached = { id -> onChange(JsonPrimitive(id)) },
            onMessage = services?.onMessage ?: {},
            onError = services?.onError ?: {},
        )
    }

    if (caption != null) {
        Box(modifier = Modifier.padding(start = 12.dp, top = 2.dp)) {
            ScalarInput(
                field = caption,
                value = captionValue,
                onChange = onCaptionChange,
                enabled = enabled,
                error = null,
                minLines = 2,
                resetKey = resetKey,
            )
        }
    }

    InlineError(error)
}

// `mimeFilterFor` and `attachVerbFor` used to live here, beside a single "Attach file" button.
// Both moved into DwMediaCapture.kt, next to the capture ROUTES that replaced that button: the
// filter is still keyed on the field type (`galleryMimeFor`), but it now applies only to the two
// routes that open a picker, because the camera and the recorder are not filtered by a MIME type at
// all — they produce one. `attachVerbFor` is gone entirely: each route names itself
// ("Photograph", "Record audio", "From gallery"), which is what a card with four buttons needs and
// a card with one did not.

/** Sizes in the units a person reads, so "is this too big to send?" is answerable at a glance. */
internal fun humanSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

