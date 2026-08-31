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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.data.DwFieldStampDto
import com.designprototype.workshop.data.DwCustomFieldDto
import com.designprototype.workshop.data.DwDerived
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.customFieldToFieldDto
import com.designprototype.workshop.data.dwCustomFieldDrawable
import com.designprototype.workshop.data.dwCustomUnsupportedNote
// The one place the fallback for an absent `maxItems` is spelled out. TAGS and MULTI_ENUM are
// multi-valued fields and the server holds them to the same ceiling it holds a gallery to, so this
// file reads it for the same reason the capture card does — see [dwListCeilingClause].
import com.designprototype.workshop.data.dwEffectiveMaxItems
import com.designprototype.workshop.data.DwTextFormats
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.ui.MapPickerDialog
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectCreateAction
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.WorkshopListKind
import com.designprototype.workshop.ui.WorkshopListState
import com.designprototype.workshop.ui.workshopListNotice
// The two-typeface `Text`, shadowing androidx.compose.material3.Text. Every file in this feature
// imports it. Without the import the bare `Text` in this file resolves to Material's, which inherits
// whatever family LocalTextStyle happens to carry and quietly sets headings in the body face — the
// exact failure FieldText.kt was written to make impossible.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.requiredMarked
import com.designprototype.workshop.ui.dwWithoutRequiredMark
import com.designprototype.workshop.ui.FieldDateField
import com.designprototype.workshop.ui.FieldTimeField
import com.designprototype.workshop.ui.ArtisanPhoneField
// The artisan record form's numbered Do's/Don'ts control and its stored-string codec, reused whole
// for every LONG_TEXT field the report prints as bullets. See [DwNumberedPointsInput].
import com.designprototype.workshop.ui.NumberedListInput
import com.designprototype.workshop.ui.joinNumbered
import com.designprototype.workshop.ui.splitNumbered
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.formatFieldDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDate

/**
 * ONE composable that can draw any of the registry's fields, by dispatching on [FieldDto.type].
 *
 * NO TOTAL FIELD COUNT IS WRITTEN IN THIS FILE, ON PURPOSE — AND THE HISTORY IS THE ARGUMENT. This comment
 * has said 496 and then 570, each measured honestly from
 * `android/app/src/main/assets/design-workshop-schema.json` at the time; both were wrong within days,
 * because the registry owner regenerates that asset whenever a field lands, and it moved TWICE during
 * the single session in which this note was written. A number in a comment beside a generated file is
 * stale before the next commit, and the next reader is then misled by a figure that looks measured.
 * The claims below carry their whole argument without one; where a count is genuinely load-bearing it
 * belongs in a test that reads the asset, which is what `DwBulletListFieldTest` and
 * `DwPhotoMeasureFieldTest` do.
 *
 * THE TWO FIGURES BELOW ARE NOT AN EXCEPTION TO THAT. `dwBulletListRole`'s arm and
 * [dwNumericTextField] each name the fields declaring ONE attribute, dated and marked as a fact
 * about the asset rather than a rule the code reads; neither enumerates anything at runtime, and
 * the property that has to stay true is swept over the whole registry by `DwBulletListFieldTest`.
 * A TOTAL is the figure that has no such test behind it, and it is the one this file refuses.
 *
 * THIS IS THE WHOLE POINT OF THE FEATURE. There is no per-stage form code anywhere in this app and
 * there must never be: the 22 stages carry hundreds of typed fields across dozens of entities, both
 * counts moving with every registry edit, the tiers within them
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
    /**
     * **THE SERVER'S `MediaFile` ID, OR NULL WHILE THESE BYTES ARE ON THIS PHONE AND NOWHERE ELSE.**
     *
     * `DraftMedia.remoteMediaId`, carried through so a control that needs the SERVER's copy can tell
     * whether there is one — which the AI media verbs do, because they run on the server's copy and
     * an id on a request body is a claim (`_verb_source_media` answers one 404 covering both "not
     * attached to this workshop" and "not yours to read", so an id cannot be used to find out whether
     * a file exists). Sending this device's own UUID would be a claim about a file the server has
     * never seen; `dwVerbMediaRefusal` is the rung that says so in a sentence instead.
     *
     * It is NOT what the local report writer or the thumbnail resolve by — those read [id], because
     * the bytes on this phone are filed under it. Two id spaces, translated on the wire and nowhere
     * else, exactly as `DraftMedia.remoteMediaId` documents.
     */
    val remoteMediaId: String? = null,
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
    /**
     * The LOCAL draft id of the workshop these attachments belong to.
     *
     * Not the sync id — [DwFieldServices.workshopId] is that one and is null until the workshop
     * exists on the server. This is the key the draft store files everything under, and it is here
     * for [DwQualityFlagLog], which pins a capture-time finding to the workshop it was raised in so
     * that stage 21's archive table can be offered the row days later on a different screen.
     */
    val workshopId: String,
    /** Resolve a stored media id to something drawable, or null when the bytes have gone missing. */
    val resolve: (String) -> DwMediaItem?,
    /**
     * Where a chosen photograph waits while it is measured, and where a refusal is remembered.
     *
     * OWNED BY THE STAGE AND NOT BY THE CARD, which is the whole reason it is passed down here
     * rather than remembered inside [DwMediaCaptureCard]: a collection row collapsing takes the card
     * out of the composition mid-check, and a screening cancelled there would leave photographs
     * neither imported nor refused with nothing on screen saying they had ever been chosen. See
     * DwPhotoScreening.kt's header.
     */
    val screening: DwScreeningStore,
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
    /**
     * WHO LAST SET THIS FIELD, as the server reported it on the read this stage was folded from.
     *
     * Null renders nothing, which is what lets this be threaded one screen at a time: a caller that
     * does not pass it behaves exactly as it did before this parameter existed. See
     * [DwFieldStampDto.attribution] for the two sentences and why there are only two.
     */
    stamp: DwFieldStampDto? = null,
) {
    val type = remember(field.type) { DwFieldType.of(field.type) }
    val parentField = remember(field.refFilterBy, siblings) { siblings[field.refFilterBy] }
    val parentValue = rowValues[field.refFilterBy]

    /*
     * THE ATTRIBUTION LINE IS APPENDED TO THIS COLUMN, ONCE, RATHER THAN ADDED TO EACH BRANCH.
     *
     * The `when (type)` below has more than a dozen arms — every scalar shape, media, GEO, REF, the
     * rich-text editor — and threading a line into all of them would mean the next arm somebody adds
     * silently lacks it. Placed at the end of this column it is structurally impossible to miss:
     * whatever the branch drew, the stamp is the last thing in the field's own column.
     */
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
        // The server registry's NARRATIVE fields carry this type as of the same change. (It said "the
        // 81 NARRATIVE fields"; the asset has declared more than that for some time. The count is not
        // the point and is not replaced with a fresher one — see this file's header.)
        // A phone that has not yet fetched that registry still sees LONG_TEXT and still captures a
        // plain string, and the server reads a plain string as unformatted prose — so the two
        // builds interoperate in both directions and no fieldwork is lost either way.
        when (type) {
            // `value` is passed through untouched. It is `{"blocks":[…]}` for a field edited
            // since the promotion and a bare JSON string for one written before it; `fromJson`
            // inside the editor reads both, which is what keeps a season of prose intact.
            DwFieldType.RICH_TEXT -> {
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
                /*
                  THE REFUSAL MARK, WHICH THIS ARM ALONE DID NOT DRAW.

                  [FieldRenderer] takes an `error` and every other arm of this `when` either renders
                  [InlineError] itself (TIME, ENUM, MULTI_ENUM, LONG_TEXT through `ScalarInput`) or
                  hands it to a component that does (DateField, BoolField, TagsField, DwGeoField,
                  ArtisanPhoneField, DwReferenceSelectField, MediaField). RICH_TEXT passed ten
                  arguments to the editor and silently dropped the eleventh. `EntitySection` routes
                  the refusal in and its KDoc argues the mark must be ON THE BOX rather than only in
                  the card above the form — so the contract was stated and one arm broke it.

                  NOT REACHABLE TODAY, AND FIXED ANYWAY. The only source of `errors` is a server
                  refusal, and no server path can key one to a RICH_TEXT field from this client:
                  `coerce_value`'s RICH_TEXT branch returns `to_json(from_json(raw)), None`,
                  `rich_text.from_json` is documented "never raising" and has no length bound, and
                  no client sends `submit`, so the "X is required" refusal cannot be produced either.
                  What existed was a silently broken contract in the largest type in the registry,
                  which goes live the moment any bound, conditional rule or required-enforcement
                  reaches a narrative field — and it would go live SILENTLY, since nothing fails and
                  the refusal card would still list the field while the box beside it stayed
                  unmarked. One line now, or a field-day of confusion the week that rule lands.

                  Inside the braces rather than after the `when`, because the `when` sits in the
                  shared [Column] and a mark drawn for every arm would double up on the ten that
                  already draw their own.
                */
                InlineError(error)
            }

            /*
             * ── A BULLETS LONG_TEXT IS A LIST, AND NOW GETS A LIST'S CONTROL ──────────────────
             *
             * The signal was already being read one arm up: RICH_TEXT opens inside an ORDERED_ITEM
             * when `reportRole == "BULLETS"`, on the argument that "a BULLETS field IS a list — its
             * help says 'One deliverable per line' and the report prints it as one — so open inside a
             * numbered item rather than making the designer type '1. '". That argument is about the
             * ROLE, not about the type, and it was not applied here. So `participant.dos` — the same
             * fact the artisan record form collects through the numbered control this arm now calls,
             * two taps away in the same app — was one undifferentiated box whose only statement of
             * its own structure was the words "One point per line" in its help text. The newline
             * boundaries are load-bearing: `report_builder` splits this string one bullet per line.
             *
             * FOUR FIELDS IN THE BUNDLED ASSET AS THIS WAS WRITTEN, counted from it rather than
             * guessed: `participant.dos`, `participant.donts`, `traditionalProcess.documentedSteps`
             * and `tool.usedByArtisans`. Every one of them says "one per line" or "one point per
             * line" in its own help, so the control affords exactly what the registry already
             * promised — which is the property `DwBulletListFieldTest` pins for the whole set rather
             * than for those four. The set is OPEN BY DESIGN: the condition is the role, so a BULLETS
             * field added on the server gets this control with no client change at all.
             *
             * ONE KNOWN COSMETIC COST, NAMED SO THE NEXT READER DOES NOT FILE IT AS A BUG.
             * `documentedSteps` is hydrated from `_step_lines`, which numbers its own lines, so a
             * carried value draws as "1. 1. Soaking · …" — the row's ordinal beside the record's own.
             * Stripping the leading ordinal would be this client editing the record's text, which is
             * a far worse trade than a doubled numeral a designer can read past. Drawing an unordered
             * dot instead would avoid it and cost more: the record form and the browser both number
             * these rows, so this surface alone would be the odd one out for the same field, which is
             * the divergence that actually costs.
             *
             * `reportRole` is already published to both clients, so this moves no registry version.
             */
            DwFieldType.LONG_TEXT -> if (field.reportRole == "BULLETS") {
                DwNumberedPointsInput(
                    field, value, onChange, enabled, error, resetKey = resetKey, services = services,
                )
            } else {
                ScalarInput(
                    field, value, onChange, enabled, error, minLines = 4, resetKey = resetKey,
                    services = services,
                )
            }

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
            DwFieldType.MULTI_ENUM -> {
                /*
                 * A MULTI_ENUM IS A CAPPED FIELD, on both of the branches below, and it read no
                 * ceiling at all until 2026-08-26 — see [dwListCeilingClause] for the whole argument
                 * and for why the enforced ceiling and the printed one are two different values.
                 *
                 * THE SHEET APPLIES A WHOLE SELECTION AT ONCE, which is why this is not the capture
                 * card's `take(room)`. [SearchableMultiSelectField] hands back everything ticked when
                 * the designer presses Apply, so the growth can be several at a time, and a change
                 * that does not grow the list at all — unticking one of five held under a ceiling of
                 * three — has to pass through untouched or the designer is trapped. That whole rule
                 * is [dwCapListGrowth], which is where it can be tested.
                 */
                val declaredCap = field.maxItems.takeIf { it > 0 }
                val ceiling = dwEffectiveMaxItems(field.maxItems)
                val held = remember(value) { DwValues.list(value) }
                // Keyed on `resetKey` as well, exactly as [ScalarInput]'s buffer is: the notice
                // belongs to a ROW, so a composable reused for the next row must not carry it over.
                var capNotice by remember(field.key, resetKey) { mutableStateOf<String?>(null) }

                /**
                 * What may be committed of [next], with the refusal recorded as a sentence.
                 *
                 * Returns rather than commits, so each branch below writes through its own `onChange`
                 * in its own shape — the reference branch passes the element it was handed straight
                 * on where nothing was dropped, which is what keeps a cleared field arriving as null
                 * rather than as an empty array it would have to be re-derived into.
                 */
                fun keepWhatFits(next: List<String>): List<String> {
                    val kept = dwCapListGrowth(held, next, ceiling)
                    val dropped = next.size - kept.size
                    capNotice = if (dropped == 0) {
                        null
                    } else {
                        // STRIPPED, because this is PROSE and not a label. The clause reads
                        // "Photographs holds at most 8 entries"; interpolating the marked form put
                        // an asterisk mid-sentence, which reads as a typo rather than as a
                        // requirement — and the reader is being told about a ceiling, not asked to
                        // fill anything in. Colouring it would not have helped: red punctuation in
                        // the middle of a sentence is still punctuation nobody asked for.
                        "${dwListCeilingClause(dwWithoutRequiredMark(fieldLabel(field)), declaredCap)}. " +
                            "$dropped of the " +
                            "${next.size} you chose ${if (dropped == 1) "was" else "were"} not kept — " +
                            "untick something first if you need ${if (dropped == 1) "it" else "them"} instead."
                    }
                    return kept
                }

                if (field.refModel.isNotBlank()) {
                    DwReferenceMultiSelectField(
                        field = field,
                        value = value,
                        parentField = parentField,
                        parentValue = parentValue,
                        bridge = services?.references,
                        enabled = enabled,
                        error = error,
                        label = fieldLabel(field),
                        // Wrapped rather than passed through: the roster picker writes an array of
                        // record ids and is as capable of overrunning the ceiling as the enum list
                        // is. Where nothing is dropped the ORIGINAL element goes on untouched, so
                        // this wrapper cannot change what "cleared" means on a field it did not cap.
                        onChange = { next ->
                            val chosen = DwValues.list(next)
                            val kept = keepWhatFits(chosen)
                            if (kept.size == chosen.size) onChange(next) else onChange(DwValues.ofList(kept))
                        },
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
                            // The ceiling is applied to THAT order, so what a full field keeps is the
                            // same on two handsets rather than depending on tick order.
                            val ordered = field.options.map { it.value }.filter { it in chosen }
                            onChange(DwValues.ofList(keepWhatFits(ordered)))
                        }
                    )
                    InlineError(error)
                }
                DwListCapHint(declaredCap, held.size)
                DwListCapNotice(capNotice)
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
                /*
                 * THE ISD-PREFIX EDITOR, REUSED WHOLE. Its own inner caption reads "Phone" while the
                 * registry's label sits above it — a small duplication accepted deliberately, because
                 * the alternative is a second phone field without the measured dial column, the
                 * country search and the foreign-resident confirmation this one already carries.
                 *
                 * ── AND THE ONE THING REUSING IT DID NOT BRING WITH IT ──────────────────────────
                 *
                 * [ArtisanPhoneField] shows only the error it is HANDED — it has no rule of its own,
                 * because on the record form the rule is applied by the caller. So this arm passed
                 * the SERVER's message and nothing else, and a nine-digit number typed into a stage
                 * was accepted here in silence, exactly as it was on the web (where the same control
                 * is mounted with `mirror = false`, dropping the native pattern that blocks it on the
                 * record page). `coerce_value` had no phone rule on either path either, so a nine-
                 * digit number reached a roster printed for a ministry.
                 *
                 * The declared `text_format = PHONE_IN` fixes that at the server, and this line is
                 * what puts the same sentence under the box while the designer is still standing in
                 * front of the person whose number it is. It is measured on the STORED string, which
                 * is what [DwValues.coerce] and `coerce_value` both see — so the message under the
                 * box and the refusal from the repository are answers to the same question.
                 *
                 * DERIVED FROM `value` RATHER THAN SET ON CHANGE, so a HYDRATED number that is
                 * already malformed is flagged the moment the stage opens rather than only after
                 * somebody happens to edit it. Hydration copies the artisan record's phone number
                 * verbatim, and nothing in this repository has ever stopped a bad one being stored
                 * there.
                 */
                val phoneProblem = DwTextFormats.error(field.format, DwValues.text(value))
                ArtisanPhoneField(
                    value = DwValues.text(value),
                    // The server's answer wins where there is one: it is about the value actually
                    // stored, and it may name a fault this rule cannot see (a conditional
                    // requirement, a stage-wide refusal).
                    error = error ?: phoneProblem,
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

            /*
             * "NAME OF WORKSHOP" GETS THE NAMES ALREADY ON RECORD, AND STILL TAKES ANYTHING TYPED.
             *
             * The one TEXT field in the registry that is the design workshop's OWN title, which is
             * promoted onto `DesignWorkshop.title` and printed on the report cover. The whole
             * argument — including the standing objection to putting a dropdown here and why it does
             * not reach a control that cannot refuse an answer — is on [dwOwnWorkshopTitleRole] and
             * [DwWorkshopNameField].
             *
             * GATED ON `services`, LIKE REF AND GEO ABOVE, AND FOR THE SAME REASON. A null bundle
             * means this field is being PREVIEWED rather than edited, so there is no repository to
             * ask for the names and the honest control is the box this field has always been —
             * which loses nothing, because the box was always the whole answer here.
             */
            DwFieldType.TEXT -> if (services != null && dwOwnWorkshopTitleRole(field)) {
                DwWorkshopNameField(
                    field = field,
                    value = value,
                    onChange = onChange,
                    enabled = enabled,
                    error = error,
                    resetKey = resetKey,
                    services = services,
                    rowValues = rowValues,
                )
            } else {
                // The number pad for the one TEXT shape whose content is digits — see
                // [dwNumericTextField], which is also what takes the microphone off it.
                ScalarInput(
                    field, value, onChange, enabled, error, resetKey = resetKey,
                    keyboard = if (dwNumericTextField(field)) KeyboardType.Number else KeyboardType.Text,
                    services = services,
                )
            }
        }

        // Last in the column, under whatever the branch above drew. `attribution()` returns null for
        // an unstamped field — the ordinary state on every row written before the column existed —
        // and a label reading "Unknown" on all of them would train a designer to stop reading it at
        // all, at which point it cannot do its one job on the rows that DO carry an author.
        stamp?.attribution()?.let { line ->
            Text(line, color = MaterialTheme.field.muted, fontSize = 11.sp)
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
 * [DwFieldType.TEXT], deliberately, because for a registry this size the alternative is one new
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
 * The TEXT fields whose content is digits, so their box opens the number pad and offers no microphone.
 *
 * ── IT READS A DECLARATION NOW, AND THE KEY LIST IT REPLACES IS THE ARGUMENT ──────────────────────
 *
 * Every other predicate on this surface reads a DECLARATION: [dwMeasurableLengthFields] asks the
 * registry's `unit`, [dwOffersPhotoMeasure] asks the types. This one used to be the exception — an
 * exact set of two key names, `pincode` and `recordPincode`, written because there seemed to be no
 * declared attribute to ask. There is one, and it was already on the wire: `FieldSpec.text_format`,
 * emitted as [FieldDto.format] and validated against by [DwTextFormats.error], where `PINCODE` means
 * precisely "this box holds an Indian PIN code". So the predicate asks that instead.
 *
 * THE KEY LIST DID NOT SURVIVE THE FIRST PIN CODE ADDED AFTER IT. The registry declared a fourth,
 * `workshopPlan.designerPincode`; nothing widened the set, and that box opened the ALPHABETIC
 * keyboard and kept the dictation microphone — in a courtyard, on the surface the designer is
 * actually holding, while the app's own record form gives the same fact
 * `KeyboardOptions(keyboardType = KeyboardType.Number)` two taps away. Nothing failed, because a
 * predicate that stops matching does not raise: the ordinary control is drawn and the loss is
 * invisible to everyone but the designer. Reading the declaration is what makes the FIFTH PIN-code
 * field arrive correct with no edit to this file, which is the property the whole of this renderer
 * is built on — see this file's header.
 *
 * ── WHY IT IS SAFE TO ACT ON THIS DECLARATION ─────────────────────────────────────────────────────
 *
 * It is bounded on both sides. A keyboard hint WRITES NOTHING and REFUSES NOTHING — Android's number
 * pad is a soft-keyboard preference, not a filter, so a pasted or hardware-typed value still lands —
 * and there is deliberately NO digits-only clamp here, because stripping keystrokes would refuse
 * input a designer might be entitled to enter, which is the expensive class of wrong answer. The
 * microphone's removal is the same shape: it subtracts a control whose best possible answer on this
 * field is wrong.
 *
 * `PINCODE` ALONE, NOT EVERY `text_format`. EMAIL and the two identity formats are answered on a
 * keyboard that has letters on it, and PHONE_IN is drawn by [ArtisanPhoneField] with its own ISD
 * column; PINCODE is the one format whose content is digits and nothing else.
 *
 * WHAT AN ASSET WITHOUT THE DECLARATION COSTS, said plainly rather than left for someone to find: a
 * cached registry old enough to carry these boxes with no `text_format` gets the ordinary keyboard
 * and keeps the microphone, exactly as the whole app did before this predicate existed. That is the
 * forgiving direction — a control not subtracted, never a control wrongly imposed — and it is the
 * same choice [DwTextFormats.error] makes for a format token it has never heard of, for the same
 * reason: on a handset with no signal the server is still the authority, and a client that invents
 * the declaration is how the two surfaces come to disagree.
 *
 * FOUR FIELDS DECLARE IT in the bundled `design-workshop-schema.json`, measured when this was
 * written: `workshopPlan.designerPincode`, `participant.pincode`, `tool.recordPincode` and
 * `existingProduct.recordPincode`, all four TEXT. That is recorded as a fact about the asset and NOT
 * as a rule this code depends on — nothing below enumerates anything, so the figure going stale
 * costs a reader a small surprise and costs a designer nothing. The paragraph that stood here before
 * said "exactly three fields have a pincode-shaped key" and WAS load-bearing, and it went false in
 * the same tree that added the fourth; the count that has to stay true lives in
 * `DwBulletListFieldTest`, which sweeps EVERY PINCODE-declaring field in the asset through this
 * predicate rather than naming any of them.
 *
 * NOT the place to fix `maxLength`. Three of the four declare 10 and `designerPincode` declares 12,
 * where an Indian PIN code is 6, so the box accepts and the report prints a longer value than the
 * record page could have produced. That is a registry declaration, it moves `registry_version()`,
 * and a client that quietly enforced 6 against a server that allows 10 would be the
 * two-surfaces-disagree defect wearing a helpful hat.
 */
internal fun dwNumericTextField(field: FieldDto): Boolean =
    DwFieldType.of(field.type) == DwFieldType.TEXT && field.format == "PINCODE"

// --------------------------------------------------------------------------------------
// "Name of workshop" — the design workshop's OWN title, offered as a list and ACCEPTED as typed
// --------------------------------------------------------------------------------------

/**
 * How many names this control asks for.
 *
 * THE SAME 80 THE BROWSER ASKS FOR, and that is the whole justification: `WORKSHOP_OPTION_PAGE_SIZE`
 * is what `StageWorkshopNameField.tsx` sends for this identical field, so the two clients offer a
 * designer the same names. A different number here would be the two clients disagreeing about which
 * precedents exist, on the field that names a ministry document.
 *
 * DELIBERATELY NOT `WorkshopListScreen`'s `NAME_OFFER_PAGE_SIZE`, WHICH IS 20 FOR A REASON THAT DOES
 * NOT HOLD HERE. That one feeds the create dialog's ANCHORED MENU, which "builds every row eagerly
 * inside a scrolling column, which is right for twenty and is not where two hundred belong". This
 * feeds the sheet's `LazyColumn`, which composes what is on screen — and, more to the point, the
 * sheet has a FILTER BOX, and a box is only worth having over as much of the corpus as it can reach.
 * See [DwWorkshopNameField] for the rest of that argument.
 */
private const val DW_NAME_OFFER_PAGE_SIZE = 80

/**
 * How long a keystroke in the name box waits before it becomes a request.
 *
 * `WorkshopListScreen`'s number, deliberately, because it is the same act against the same route:
 * a designer typing a cluster name into a box that lists design workshops. Two surfaces that felt
 * different while doing one thing would read as one of them being broken. Long enough that a
 * five-letter word is one request rather than five on a metered field connection, short enough that
 * the list does not feel like it has stopped answering.
 */
private const val DW_NAME_SEARCH_DEBOUNCE_MS = 350L

/**
 * Is this box the design workshop's OWN name — `workshopSetup.workshopTitle`, stage 1?
 *
 * ── THE OBJECTION THIS ANSWERS, WHICH WAS RIGHT ABOUT THE CONTROL IT REFUSED ────────────────────
 *
 * The browser's `stageFieldRoles.ts` says by name that this key must never be in
 * `WORKSHOP_TITLE_FIELD_KEYS`: *"it is not a reference to a `Workshop` row at all, and a dropdown
 * there would refuse a workshop that has no `Workshop` record yet, which is most of them on the day
 * they start."* Every clause of that is still true and it still forbids what it forbade — a CLOSED
 * picker of `Workshop` rows on this box. It does not reach [DwWorkshopNameField], for the two
 * reasons `ownWorkshopTitleRole` sets out on the other client: the list is `DesignWorkshop` TITLES
 * rather than `Workshop` rows, and **nothing here can refuse an answer** — whatever is in the box is
 * committable in one tap, so a workshop nobody has ever filed is answered exactly as fast as one
 * with ten years of history.
 *
 * MATCHED BY EXACT KEY AND ON THE RAW TOKEN, mirroring the web arm for arm. The raw `type` and not
 * [DwFieldType.of], because `of` degrades an unrecognised token to TEXT — deliberately, so one new
 * server type cannot blank 22 stages on a handset that has not updated — and a field whose type this
 * build has never heard of is not a field this build should be putting a workshop list on. The
 * entity is not checked because `workshopTitle` is declared once in the registry, on `workshopSetup`,
 * and it is the field `PROMOTED_COLUMNS` copies onto `DesignWorkshop.title`; the looser match is
 * affordable precisely because the control refuses nothing, so the worst outcome of a false positive
 * is a box that offers some names beside it.
 */
internal fun dwOwnWorkshopTitleRole(field: FieldDto): Boolean =
    field.type == "TEXT" && !field.deprecated && field.key == "workshopTitle"

/**
 * What the create row says for what is in the sheet's box, or null to draw no row for that term.
 *
 * THE TERM IS QUOTED AND NEVER SUMMARISED — the browser's rule, kept word for word: a reader has to
 * be able to see the exact string that would be stored, the capitals, the punctuation, the double
 * space they did not mean to type, and a paraphrase is the one shape that cannot show them. "Use"
 * rather than "Create", because nothing is created by answering this box.
 *
 * NULL ON AN EMPTY BOX, which is what keeps this row off the anchored menu as well: there is nothing
 * to use, and a button reading *Use “” as the name* is a button that can only do harm. See
 * [SelectCreateAction], whose contract this is.
 */
internal fun dwWorkshopNameCreateLabel(query: String): String? =
    query.trim().takeIf { it.isNotEmpty() }?.let { "Use “$it” as the name" }

/** The names on offer, and how many were held back because this field could not store them. */
@Immutable
internal data class DwWorkshopNameOffer(val names: List<String>, val withheld: Int)

/**
 * The distinct titles in an answer, in the SERVER'S order, with the unstorable ones counted out.
 *
 * DEDUPLICATED BECAUSE ONLY THE NAME IS STORED. Two workshops may legitimately share a title, and
 * offering the same string twice is a control that appears to distinguish two answers it cannot.
 *
 * ORDER IS THE SERVER'S AND IS NEVER RE-SORTED. `GET /design-workshops` answers newest first, which
 * is the workshop a designer naming one today almost always means; sorting alphabetically would bury
 * this season's between two from 2019.
 *
 * A TITLE LONGER THAN THE FIELD STORES IS WITHHELD AND COUNTED, never silently dropped: `coerce`
 * refuses an over-length string, so offering one would offer an option that turns the row into a
 * refused answer on save. [maxLength] of 0 means the registry declared no bound.
 */
internal fun dwWorkshopNamesOnRecord(titles: List<String>, maxLength: Int): DwWorkshopNameOffer {
    val kept = LinkedHashSet<String>()
    var withheld = 0
    for (raw in titles) {
        val title = raw.trim()
        if (title.isEmpty()) continue
        if (maxLength > 0 && title.length > maxLength) {
            withheld += 1
            continue
        }
        kept += title
    }
    return DwWorkshopNameOffer(kept.toList(), withheld)
}

/**
 * The rows, with the name already on this workshop FIRST and always present.
 *
 * A PICKER THAT CANNOT DRAW ITS OWN CURRENT VALUE READS AS BLANK, and the obvious repair for a blank
 * box is to answer it again — which on the field that names a ministry document overwrites a true
 * answer with a guess. One page is at most [DW_NAME_OFFER_PAGE_SIZE] titles ordered newest first, so
 * a workshop named two seasons ago is very often not among them. Same rule and same reason as the
 * browser's, and as `useRecordOffPage` for the pickers that hold an id.
 *
 * NO HINT ON THE OFFERED ROWS, unlike the browser's, which prints the day or "N workshops share this
 * name". A 48dp handset row shares its width with a name that is frequently forty characters of
 * scheme and cluster, and the second line buys nothing here: the value stored is the bare title
 * either way, and every row in this list is a title somebody already chose. The hint on the FIRST
 * row is different in kind — it says where that row came from, so nobody reads their own answer as a
 * workshop the server has just offered.
 */
internal fun dwWorkshopNameOptions(current: String, names: List<String>): List<SelectOption> {
    val typed = current.trim()
    val rows = mutableListOf<SelectOption>()
    if (typed.isNotEmpty() && names.none { it == typed }) {
        rows += SelectOption(value = typed, label = typed, hint = "already on this workshop")
    }
    names.forEach { rows += SelectOption(value = it, label = it) }
    return rows
}

/**
 * WHAT THE LIST IS, AND WHAT IT LEFT OUT — R3 and R4 in one line, and the browser's words.
 *
 * A designer cannot tell "the workshops I can open" from "every workshop there is" by looking at a
 * dropdown, and a narrowing nobody announced is absence reading as non-existence. The type clause is
 * printed only when a type is chosen, because a sentence about a narrowing that is not applied is a
 * sentence about nothing.
 *
 * DELIBERATELY NOT `workshopCapLine`, WHICH IS THE RIGHT SENTENCE FOR A DIFFERENT CONTROL. That one
 * ends *"Open Design workshops to search the whole list, then come back"* — it names a SCREEN,
 * because a picker that holds an id has nowhere else to reach the rest from. This control's answer
 * is in the box in front of the designer: what reaches the rest here is typing, and sending somebody
 * off to another screen to look up a string they are about to type by hand would be advice that
 * costs more than the problem. `WorkshopListScreen`'s create dialog prints the same pair for the
 * same field.
 */
internal fun dwWorkshopNameOfferLine(
    workshopKind: String,
    shown: Int,
    total: Int,
    /**
     * Whether there is a term in the picker's box — i.e. whether these rows are the SERVER'S answer
     * to it rather than the newest page of the whole list.
     *
     * DEFAULTED SO THE UNSEARCHED SENTENCE IS THE ONE THAT NEEDS NO ARGUMENT, and because the two
     * facts genuinely differ: unsearched, the reader needs to know the box reaches past what is on
     * screen; searched, they need to know it already has.
     */
    searching: Boolean = false,
): String =
    buildString {
        append(
            if (workshopKind.isBlank()) "Names from workshops you can open."
            else "Names from workshops of this type."
        )
        // "Type a new one if it is not here." STOOD ALONE HERE AND DID TWO JOBS, one of which it was
        // not entitled to. It is the escape — and it was also, silently, the entire answer to a box
        // that could only filter one page: the reader was never told a name did not exist, they were
        // offered the one they were typing. That kept the control from lying and it did not make the
        // box work. The box now asks the server, so the two facts are separated and both are said.
        append(
            if (searching) " All of them searched. Type a new one if it is not here."
            else " Type to search all of them, or type a new one."
        )
        // BOTH NUMBERS OR NEITHER. "Showing 80" alone leaves a reader guessing whether that is most
        // of their workshops or a sixth of them, which is the difference between trusting the offer
        // and going to look somewhere else. While searching, both numbers are about the MATCHES —
        // the server counts what its own filter found — which is the fact worth printing there too.
        if (shown > 0 && total > shown) append(" Showing $shown of $total.")
    }

/**
 * The one sentence under an EMPTY name list, which is a different fact once the server is filtering.
 *
 * [workshopListNotice] is this app's single decider for what an unanswered workshop list says, and
 * it is still the decider here for every state it was written for: a read in flight, a read that
 * failed online, a read that failed with no signal, and an account genuinely on no design workshop
 * ("Ask an admin" — never "create one", because the remedy is a grant and a designer told otherwise
 * goes and makes a duplicate).
 *
 * WHAT IT CANNOT KNOW IS THAT A TERM WAS TYPED. `Listed(count = 0)` used to mean one thing — this
 * account is on no workshops — and now means either that or "the server searched every workshop you
 * can open and none is called that". Printing the scope sentence for the second is the exact defect
 * this control's whole lane is about, one layer along: absence read as non-existence, except now it
 * is the account's whole access being denied on the strength of a misspelt cluster name.
 *
 * ORDER IS LOAD-BEARING. The term is consulted ONLY on `Listed`, so a read that FAILED with a term
 * in the box still says the server could not be reached — never "no name matches", which would
 * report a dead connection as a fact about the repository. That is the same trap
 * [SearchableSelect.pickerEmptyLine]'s `listIsEmpty` guard exists for, and the reason this decision
 * is the caller's: only the caller holds the state machine that can tell the two apart.
 *
 * NULL MEANS "SAY NOTHING", exactly as [workshopListNotice]'s does — there are rows to draw.
 */
internal fun dwWorkshopNameNotice(
    state: WorkshopListState,
    online: Boolean,
    query: String,
): String? {
    val term = query.trim()
    if (state is WorkshopListState.Listed && state.count == 0 && term.isNotEmpty()) {
        // TERSE, AND IT DOES NOT NAME THE NEXT MOVE, because the next move is already drawn directly
        // beneath it: the create row reads *Use "…" as the name* for this same term. A second
        // sentence telling the designer to type what they have just typed is the sort of copy the
        // owner's "the user is not a dunce" rules out.
        return "No workshop name matches “$term”."
    }
    return workshopListNotice(state, WorkshopListKind.DESIGN, online)
}

/**
 * The names that could not be offered because this field could not store them. Null for none.
 *
 * STATED RATHER THAN DROPPED. It cannot happen with today's titles, and the sentence is here because
 * the day it does, a designer must not be left hunting for a name that is on screen nowhere and
 * refused by nothing. The browser prints this line word for word.
 */
internal fun dwWorkshopNamesWithheldLine(withheld: Int, maxLength: Int): String? =
    if (withheld <= 0) null
    else "$withheld name${if (withheld == 1) "" else "s"} not offered: over $maxLength characters."

/**
 * "Name of workshop" — the creatable combo, and the handset's half of a control the web already has.
 *
 * ── WHY THIS FIELD IS NOT AN ORDINARY BOX ANY MORE ─────────────────────────────────────────────
 *
 * A stage entry is a FROZEN COPY that nothing re-resolves, and this string is promoted onto
 * `DesignWorkshop.title` and printed on the cover of a document a ministry receives. "Bagru Block
 * Print Workshop 2025" and "Bagru block-printing workshop, 2025" are one fortnight to a reader and
 * two different strings to every group-by; the one in the ministry's file is whichever was typed. A
 * workshop that runs every year, or in three clusters at once, is named three ways by three
 * designers unless the names already on record are in front of them while they type.
 *
 * The web shipped that control and this client did not, so a designer moving between the two met two
 * different controls for one box — and met the plain one on the client they use in the courtyard.
 * `WorkshopListScreen`'s create dialog has offered the same names since the same wave; what was
 * missing was the field the report cover actually reads.
 *
 * ── IT REFUSES NOTHING, WHICH IS THE PROPERTY THE STANDING OBJECTION DEMANDS ───────────────────
 *
 * See [dwOwnWorkshopTitleRole] for the objection and why it does not reach this control. The
 * mechanism is [SelectCreateAction]: whatever is in the sheet's box is committable in one tap, and
 * the row NAMES IT BACK, so a workshop that exists nowhere is answered as fast as one with a
 * history.
 *
 * ── THE FILTER BOX, AND THE RULE IT LOOKED LIKE IT WAS BREAKING ────────────────────────────────
 *
 * `SearchableSelectField.searchable`'s own note says to pass `false` over ONE SERVER-TRUNCATED PAGE,
 * because "a filter box over a page filters the page" and typing the title of a workshop on page
 * four answers "nothing matches" about a workshop that exists.
 *
 * THE BOX IS NOW WIRED TO `GET /design-workshops?search=`, WHICH IS THE BROWSER'S OWN ANSWER and
 * what `StageWorkshopNameField.tsx` has always done. [SearchableSelectField.onSearch] is the seam —
 * the sheet hands every keystroke back here, the read below re-asks the server with it, and the
 * sheet stops filtering the reply locally (it must: the server matches the code, the craft and the
 * cluster, and this list prints only the title, so a local second pass would delete the hits the
 * server had just found). The rule above is therefore satisfied rather than excepted: the box
 * reaches the whole corpus this account may open, at any number of workshops.
 *
 * ── THE TWO CLAUSES THAT HELD IT UP BEFORE THAT, WHICH ARE STILL TRUE AND NO LONGER LOAD-BEARING ─
 *
 * They were the argument for shipping the box over one page, and they are kept because they are why
 * that was survivable rather than a defect waiting to be found:
 *
 *   · the page is [DW_NAME_OFFER_PAGE_SIZE] — the browser's number for this same field — so for any
 *     account with fewer workshops than that the box was filtering the WHOLE corpus and the rule had
 *     nothing to bite on; where it was not, [dwWorkshopNameOfferLine] printed both numbers; and
 *   · the box never answers "nothing matches" ALONE. The create row is drawn for the same term and
 *     reads *Use “…” as the name*, so the reader is never told a name does not exist — they are
 *     offered the answer they were typing anyway.
 *
 * The first is now a statement about what is DRAWN rather than what is reachable, and
 * [dwWorkshopNameOfferLine] says which of the two it is. The second is unchanged and still the
 * reason an empty answer here costs a designer nothing.
 *
 * ── AND IT NEVER STANDS DOWN ───────────────────────────────────────────────────────────────────
 *
 * R2 — *a field may only be mandatory where it is answerable* — is satisfied here without disabling
 * anything, because the box IS the answer. A failed read leaves the control fully usable and the
 * sentence underneath says what the list is doing rather than what the designer may not do.
 * `enabled` is the caller's alone (a locked stage, a save in flight).
 */
@Composable
private fun DwWorkshopNameField(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    resetKey: Any,
    services: DwFieldServices,
    rowValues: Map<String, JsonElement>,
) {
    /**
     * The `WORKSHOP_KIND` token answered on this same entry, or "" where none is chosen.
     *
     * **NARROWS THE OFFER, NEVER THE ANSWER.** With a type chosen the list is the workshops of that
     * type, because that is the set whose naming conventions are worth copying — a Skill Upgradation
     * sitting and a Design Intervention are named to different patterns and mixing them is how a
     * designer copies the wrong precedent. The create row is untouched by it either way.
     */
    val workshopKind = DwValues.text(rowValues["workshopKind"])

    /*
      KEYED ON `resetKey` LIKE EVERY OTHER BUFFER ON THIS RENDERER. Collection rows share composable
      slots, so state keyed on the field alone survives a row change — see [FieldRenderer]'s own
      `resetKey` note. `workshopTitle` is a singleton field today and this costs nothing; it is here
      so that the day it is not, this control does not become the one that remembers the wrong row.
    */
    var list by remember(field.key, resetKey) {
        mutableStateOf<WorkshopListState>(WorkshopListState.Loading)
    }
    var offer by remember(field.key, resetKey) { mutableStateOf(DwWorkshopNameOffer(emptyList(), 0)) }
    /**
     * Whether the phone reached the server at all, when [list] is Failed.
     *
     * NOT A NETWORK PROBE: `WorkshopRepository.isTransient`'s verdict on the throwable, which is the
     * same classification the offline outbox uses to decide whether an entry is worth retrying. One
     * idea of "offline" per app. Meaningless in every other state and ignored by the notice there.
     */
    var online by remember(field.key, resetKey) { mutableStateOf(true) }
    /** The coercion's refusal for the last thing committed — an over-length name and nothing else. */
    var localError by remember(field.key, resetKey) { mutableStateOf<String?>(null) }
    /** The recogniser's running guess, drawn under the control and NOT yet in the store. */
    var spoken by remember(field.key, resetKey) { mutableStateOf("") }
    /**
     * WHAT IS TYPED IN THE PICKER'S SEARCH BOX — hoisted out of the sheet so the SERVER can answer it.
     *
     * This is the whole of the divergence this control carried: the box filtered the one page it had
     * been handed, so over [DW_NAME_OFFER_PAGE_SIZE] workshops it answered "nothing matches" about
     * workshops that exist. See the header for why that was survivable and why it is not the shape
     * to ship.
     *
     * KEYED LIKE EVERY OTHER BUFFER HERE, so a collection row change does not leave the previous
     * row's term filtering this one's list.
     */
    var nameQuery by remember(field.key, resetKey) { mutableStateOf("") }

    val current = DwValues.text(value)

    /**
     * Commit a picked row, a typed term or a dictated phrase through ONE coercion.
     *
     * A value that will not coerce is NOT pushed to the store, exactly as [ScalarInput] refuses one:
     * `coerce` mirrors the server's `coerce_value`, so an over-length title refused here is a title
     * the save would have refused anyway — with the difference that the sentence names the box while
     * the designer is still looking at it.
     */
    fun commit(text: String) {
        val coerced = DwValues.coerce(field, text)
        localError = coerced.error
        if (coerced.error == null) onChange(coerced.value)
    }

    /*
      RE-READ WHENEVER THE CHOSEN TYPE CHANGES, and once on open.

      A FAILED READ IS NOT AN EMPTY ANSWER. Holding it as `emptyList()` is what turns a dropped
      connection into a confident claim that this account is on no design workshop; the state carries
      the difference and `workshopListNotice` prints it. Nothing else changes — the control stays
      usable, because typing was always the answer here.
    */
    LaunchedEffect(workshopKind, nameQuery, field.key, resetKey) {
        // DEBOUNCED, AND ONLY FOR TYPING. Keying on `nameQuery` means every keystroke cancels the
        // run in flight and starts a new one, so without this a five-letter cluster name is five
        // list requests on a metered field connection and four replies nobody will look at. The type
        // above changes by a tap on a picker rather than by typing, so it has no run of intermediate
        // values to swallow and must not be made to wait — the same split, and the same number,
        // `WorkshopListScreen` uses for its own search and type filters.
        if (nameQuery.isNotBlank()) delay(DW_NAME_SEARCH_DEBOUNCE_MS)
        // A NEW TERM MUST NOT LEAVE THE PREVIOUS ANSWER ON SCREEN LOOKING LIKE ITS OWN. The failure
        // arm below deliberately keeps the names it has, which is right for a dropped connection and
        // wrong here — those rows are the answer to a word the designer has since edited.
        list = WorkshopListState.Loading
        val answer = try {
            services.repository.designWorkshops(
                page = 1,
                pageSize = DW_NAME_OFFER_PAGE_SIZE,
                // THE SERVER IS THE FILTER NOW. `GET /design-workshops?search=` matches the code,
                // the craft and the cluster as well as the title, over the WHOLE corpus this account
                // may open — which is what makes the box in the sheet honest at any number of
                // workshops. Folded to null by `designWorkshops`, so an empty box asks the same
                // unfiltered question it always asked. See [SearchableSelectField.onSearch] for why
                // the sheet must then stop filtering these rows again locally.
                search = nameQuery.trim().takeIf { it.isNotEmpty() },
                // OMITTED when no type is chosen: `designWorkshops` folds a blank to null, and a
                // blank token on the wire is a filter that matches nothing — an empty list over a
                // full corpus.
                workshopKind = workshopKind.takeIf { it.isNotBlank() },
            )
        } catch (cancelled: CancellationException) {
            // NEVER SWALLOWED. This effect is KEYED, so changing the type above cancels the run in
            // flight; catching that like any other throwable would report a failed read for a
            // request nobody was waiting for, and leave the previous type's names under the new
            // type's heading.
            throw cancelled
        } catch (failure: Throwable) {
            online = !services.repository.isTransient(failure)
            list = WorkshopListState.Failed
            // The names already held are deliberately NOT cleared WHEN THE BOX IS EMPTY: on a
            // re-read whose answer failed, blanking what is on screen takes away the one thing that
            // still works.
            //
            // WITH A TERM IN THE BOX THEY ARE CLEARED, and that is the same rule rather than an
            // exception to it. Once the SERVER is the filter, the rows on screen are the answer to
            // whatever word was last answered successfully — so leaving them under a new term draws
            // eighty workshops as though they matched "bagru", which is worse than an empty list
            // beside a sentence saying the server could not be reached. Nothing is lost either way:
            // typing was always the answer on this control, and the create row still offers it.
            if (nameQuery.isNotBlank()) offer = DwWorkshopNameOffer(emptyList(), 0)
            return@LaunchedEffect
        }
        val built = dwWorkshopNamesOnRecord(answer.items.map { it.title }, field.maxLength)
        offer = built
        list = WorkshopListState.Listed(count = built.names.size, total = answer.total)
    }

    val options = remember(current, offer) { dwWorkshopNameOptions(current, offer.names) }
    val notice = dwWorkshopNameNotice(list, online, nameQuery)
    val listed = list as? WorkshopListState.Listed

    val canDictate = rememberDictationAvailable() && dictatable(DwFieldType.of(field.type))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldCaption(field)
        SearchableSelectField(
            label = fieldLabel(field),
            options = options,
            selectedValue = current,
            // No blank row: the title is what every list, search result and report cover shows this
            // workshop by, and a "None" row on it would offer to leave a document unnamed.
            includeNone = false,
            // FORCED ON, ahead of the count. The box is where a new name is typed and the create row
            // reads it back; without it this control could only ever offer what already exists, which
            // is the closed picker the standing objection refuses. See the header for the page size
            // that makes a local filter honest here.
            searchable = true,
            enabled = enabled,
            // Never a claim the state does not support — `workshopListNotice` is the app's one
            // decider and it says nothing at all while there are rows to draw.
            emptyMessage = notice,
            createAction = SelectCreateAction(
                label = ::dwWorkshopNameCreateLabel,
                onClick = ::commit,
            ),
            // THE BOX ASKS THE SERVER. This is what closes the divergence the header used to end on
            // — "the honest end state is the browser's: the box wired to `GET
            // /design-workshops?search=`, which needs a server-query seam this handset's picker has
            // not got". The seam is [SearchableSelectField.onSearch]; passing it also stops the
            // sheet filtering these rows a second time, which matters because the server matches the
            // code, the craft and the cluster and this list only prints the title.
            onSearch = { typed -> nameQuery = typed },
            onSelect = { picked -> if (picked.isNotBlank()) commit(picked) },
        )
        Text(
            dwWorkshopNameOfferLine(
                workshopKind = workshopKind,
                shown = offer.names.size,
                total = listed?.total ?: 0,
                searching = nameQuery.isNotBlank(),
            ),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        notice?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        dwWorkshopNamesWithheldLine(offer.withheld, field.maxLength)?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (canDictate) {
            /*
              THE MICROPHONE STAYS, AND IT IS UNDER THE CONTROL RATHER THAN INSIDE IT.

              This field was an ordinary [ScalarInput] until this control landed, so it had a mic in
              the box's trailing icon, and taking a capability away from the client used in the
              courtyard would be a worse trade than any amount of tidiness. What cannot follow it
              here is the PARTIAL: [ScalarInput] renders the recogniser's running guess inside the
              box the designer is watching, "because a transcript that appears somewhere other than
              where it will be saved is a transcript nobody trusts", and a dropdown trigger has no
              box to stream into. So the guess is drawn in its own line directly beneath, which is
              the nearest honest place, and the trigger reads the committed name the moment it lands.

              APPENDED, not replaced, exactly as the browser's `appendDictated` appends: a designer
              adding "two thousand twenty six" to a name already in the box is finishing an answer,
              not starting one. `commit` runs the same coercion the picked and typed paths run, so an
              over-length result is refused with the same sentence rather than a third one.
            */
            Row(verticalAlignment = Alignment.CenterVertically) {
                DwDictationButton(
                    enabled = enabled,
                    onPartial = { partial -> spoken = partial },
                    onCommit = { finalText ->
                        val merged = appendSpoken(current, finalText)
                        spoken = ""
                        commit(merged)
                    },
                    onError = { message ->
                        spoken = ""
                        services.onError(message)
                    },
                )
                if (spoken.isNotBlank()) {
                    Text(
                        appendSpoken(current, spoken),
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            DwDictationHint(listening = spoken.isNotBlank())
        }
        // `localError` first: it is about the commit that was just refused, so it is the newest fact
        // and the one the designer is acting on. `error` is the repository's answer about the value
        // it stored, which is older and most likely to have been superseded.
        InlineError(localError ?: error)
    }
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
    // [dwNumericTextField] subtracts, and only ever subtracts. `dictatable` is right that a TEXT box
    // is usually a name or a place a soft keyboard fights — but a recogniser hands back WORDS, so on
    // a six-digit PIN code the microphone's best possible answer is "three zero three zero zero
    // seven", which coerces cleanly (it is inside `maxLength`) and is not a PIN code. The browser's
    // own TEXT branch already excludes URL, EMAIL and PHONE from dictation on exactly this ground.
    val canDictate = services != null && dictationAvailable &&
        dictatable(DwFieldType.of(field.type)) && !dwNumericTextField(field)
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

    /**
     * The declared format's refusal, DERIVED FROM WHAT IS IN THE BOX rather than set on commit.
     *
     * ── WHY IT IS NOT LEFT TO `localError`, WHICH ALREADY CARRIES COERCION FAILURES ─────────────
     *
     * `localError` is written by [commit], so it only exists once the designer has TYPED. That is
     * right for the failures it was built for — "12." is not a number, and nobody arrives at a stage
     * with a half-typed decimal in the box — and it is wrong for a format, because a malformed value
     * can be there before anybody touches it. `hydrate_entries` copies an artisan record's email
     * address and phone number into a participant row verbatim, and nothing in this repository has
     * ever stopped a malformed one being stored on the record: `ArtisanCreate.email` is a bare
     * `str | None`. So a hydrated row can open holding a value the next save will refuse, and until
     * this line the box said nothing until the designer happened to edit it — at which point the
     * refusal reads as a fault they just introduced.
     *
     * Derived, so it also survives the value being reverted: `save_stage` restores a refused key from
     * `previous`, and a rule computed from the box re-answers against whatever comes back instead of
     * living only as long as the response that carried it.
     */
    val formatProblem = remember(field.format, buffer) {
        buffer.trim().takeIf { it.isNotEmpty() }?.let { DwTextFormats.error(field.format, it) }
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
            label = { Text(requiredMarked(fieldLabel(field))) },
            enabled = enabled,
            // Read-only for the seconds the recogniser is running. A keystroke landing in the middle
            // of a stream would be overwritten by the next partial, so the alternative is a box that
            // silently discards typing — which reads as a broken keyboard.
            readOnly = spoken.isNotBlank(),
            isError = error != null || localError != null || formatProblem != null,
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
                // READ OFF THE REGISTRY, never inferred from the field's name. The server masks this
                // field's value on every save when it declares the flag, so the control SAYS so above
                // the candidate buttons — otherwise the designer taps a twelve-digit number, finds
                // four digits stored against it, and cannot tell a masking rule from a bug that ate
                // the answer they just proofread.
                //
                // THE CONTROL NO LONGER MASKS THE VALUE ITSELF, and that is the whole of what this
                // flag does here now. It used to hand `ArtisanIdentity.mask(...)` to `onUse`, which
                // meant `commit` below coerced a MASK: `DwTextFormats.error` matched the mask shape
                // and the declared AADHAAR format enforced nothing, on the one route that can put
                // twelve digits into this box in a single tap. Format before mask is the ordering
                // `scalarText` calls forced rather than tidy, and `commit` is where both happen — so
                // the full number comes back from the control and goes through the one door.
                storeMasked = field.storeMasked,
                // The ONLY route from the reader to the field, and it is reached from a tap on a
                // button that spells the number out. Nothing above ever calls this.
                onUse = { number -> commit(number) },
                onError = services.onError,
            )
        }
        /*
         * ONE LINE, THREE POSSIBLE FAULTS, IN THE ORDER THEY BECAME TRUE.
         *
         * `localError` first: it is about the keystroke that was just refused, so it is the newest
         * fact and the one the designer is acting on. `formatProblem` next: what is in the box will
         * not save. `error` last: what the repository said about the value it stored, which is the
         * oldest of the three and the one most likely to have been superseded by an edit.
         */
        InlineError(localError ?: formatProblem ?: error)
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

/**
 * A LONG_TEXT field the report prints as bullets, drawn as the record forms' numbered points.
 *
 * THE CONTROL IS THE ARTISAN FORM'S OWN, not a stage-side lookalike: [NumberedListInput] moved out of
 * `MainActivity.kt` for this call. A second numbered-list control would be a second opinion about the
 * three-way contract this string sits in — the record API writes it newline-joined, that control and
 * `MultiNoteInput` read it back into rows, and `report_builder` splits it one bullet per line — and
 * the two would disagree about a trailing blank line inside one release.
 *
 * NOTHING IS MIGRATED AND NOTHING IS COERCED ON THE WAY IN. A hydrated value arrives already in
 * exactly the shape this control reads, because the record column it was copied from was written by
 * the same codec. So a row saved before this arm existed opens as rows, and a row saved through it
 * opens on an older build as the same string in a textarea.
 *
 * THE WRITE GOES THROUGH [DwValues.coerce], like the typed path it replaces, so a `maxLength` on a
 * BULLETS field would refuse here exactly as it refuses in [ScalarInput] rather than being enforced
 * only on the server. None of the four such fields in the bundled asset declares one; the point is
 * that the day one does, this box does not become the loose one.
 */
@Composable
private fun DwNumberedPointsInput(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    resetKey: Any = field.key,
    services: DwFieldServices? = null,
) {
    /**
     * The rows on screen, which the store does not own.
     *
     * Held locally for the same reason [ScalarInput] holds its text buffer: a designer who has just
     * pressed "Add point" is looking at a trailing EMPTY row, and [joinNumbered] drops blanks, so
     * re-deriving the rows from the stored string on every recomposition would delete that row out
     * from under the cursor. Keyed on [resetKey] as well as the field key, because collection rows
     * are drawn through the same slots — see [FieldRenderer]'s `resetKey`.
     */
    var rows by remember(field.key, resetKey) { mutableStateOf(splitNumbered(DwValues.text(value))) }
    var localError by remember(field.key, resetKey) { mutableStateOf<String?>(null) }
    /**
     * Which row the recogniser is speaking into, or -1. Paired with [spoken].
     *
     * **ONE ROW AT A TIME, AND THIS PAIR IS WHY.** A single `Int` and a single `String` can hold ONE
     * row's partial, so two live recognisers are not merely undesirable here — they are not
     * representable. Every microphone below is therefore gated on this value: while it is >= 0, only
     * the row it names may start another.
     *
     * WITHOUT THE GATE, tapping row 1's microphone and then row 2's was a broken keyboard in both
     * rows. Row 2's first `onPartial` moved this to 2, which cleared row 1's overlay and made row 1
     * writable again while its recogniser was still running — the exact case `NumberedListInput`'s
     * `rowOverlay` KDoc says the overlay exists to prevent — and then whichever recogniser finished
     * first set this back to -1, dropping the other row's overlay mid-sentence and letting its
     * eventual commit land with nothing on screen having said it was ever listening.
     */
    var spokenRow by remember(field.key, resetKey) { mutableStateOf(-1) }
    var spoken by remember(field.key, resetKey) { mutableStateOf("") }

    // Adopt an incoming value only when it differs AS STORED — a hydration landing on the row, or a
    // fold from the server. Comparing the joined form rather than the row list is what stops the
    // empty row a designer just added from being read as a difference and immediately discarded.
    LaunchedEffect(value) {
        val stored = DwValues.text(value)
        if (joinNumbered(rows) != stored) rows = splitNumbered(stored)
    }

    fun emit(next: List<String>) {
        rows = next
        val coerced = DwValues.coerce(field, joinNumbered(next))
        localError = coerced.error
        // A value that will not coerce is not pushed to the store, exactly as [ScalarInput] does it:
        // the rows on screen keep the designer's words and the draft keeps the last good answer.
        if (coerced.error == null) onChange(coerced.value)
    }

    val dictationAvailable = rememberDictationAvailable()
    val canDictate = services != null && dictationAvailable && dictatable(DwFieldType.LONG_TEXT)

    /*
      THE HELP LINE IS DRAWN BY [FieldCaption], THE SAME COMPOSABLE EVERY OTHER FIELD ON THIS STAGE
      USES, AND NOT PASSED THROUGH THE SHARED CONTROL AS `helper`.

      NOT BECAUSE THE COLOUR WAS WRONG — IT WAS NOT, AND THAT IS WORTH WRITING DOWN because it was
      reported as a visible defect and it does not reproduce. `NumberedListInput`'s helper `Text` reads
      the legacy top-level `Muted`, which is `legacyPalette.value.scheme.onSurfaceVariant`; that
      resolves to `FieldPalette.Ink500Light`/`Ink500Dark`, and `MaterialTheme.field.muted` is
      `FieldTokens.muted`, which is `Ink500Light`/`Ink500Dark`. The same two colours, and
      `DesignWorkshopTheme` repoints `legacyPalette` at the active scheme in the same composition, so
      they cannot even come apart between light and dark. Nobody was looking at two greys.

      WHAT IS WRONG IS THAT TWO PALETTES HAD TO AGREE FOR THAT TO BE TRUE. Theme.kt calls the legacy
      names transitional and says new code should read `MaterialTheme.field`; the day either `muted` or
      `onSurfaceVariant` is retuned on its own, this one help line on this one stage would move and
      nothing would fail. Drawing it through the composable every other arm draws it through — in the
      same position, above the label, as `DateField` and the rest place it — removes the coincidence
      rather than relying on it. The shared control keeps its `helper` parameter for the record form,
      whose own header is on the legacy names throughout.
    */
    FieldCaption(field)
    NumberedListInput(
        label = fieldLabel(field),
        // `fieldLabel` has already added the asterisk if the registry says the field is required.
        required = false,
        // The stage form's own block-label treatment, so this field does not read as a different
        // kind of question from the BOOL and TAGS blocks above and below it.
        mutedLabel = true,
        items = rows,
        error = localError ?: error,
        helper = null,
        enabled = enabled,
        rowOverlay = { index ->
            if (index != spokenRow || spoken.isBlank()) null
            else appendSpoken(rows.getOrElse(index) { "" }, spoken)
        },
        // WHILE ONE ROW IS SPEAKING, NO ROW MAY BE REMOVED AND NO ROW MAY BE INSERTED ABOVE ANOTHER.
        // The shared control's parameter carries the argument: a row's identity in that loop is its
        // INDEX, so a deletion or an Enter above the dictating row hands the in-flight commit a
        // different point than the one the designer spoke into.
        rowsLocked = spokenRow >= 0,
        // THE DOUBLE BRACES ARE NOT A TYPO. `else { … }` is a BLOCK, so a lambda passed on the else
        // branch has to be the block's own last expression — the same shape [ScalarInput] uses for
        // its `trailingIcon`. Written as `else { index -> … }` this is a block beginning with a
        // destructuring arrow and does not compile.
        rowTrailing = if (!canDictate) null else {
            { index ->
                DwDictationButton(
                    // ONE MICROPHONE LIVE AT A TIME. Not a mode and not a new rule — it is what the
                    // single [spoken]/[spokenRow] pair has always assumed, made honest. The row that
                    // is speaking keeps its own button live so it can be stopped; every other row's
                    // is greyed until it finishes, which is also the answer to "why did my second tap
                    // do nothing" — greyed says waiting, where a live button that fought the first
                    // recogniser said nothing at all.
                    enabled = enabled && (spokenRow < 0 || spokenRow == index),
                    // ONE MICROPHONE PER ROW, which is the whole reason [rowTrailing] exists. A
                    // single button for the block cannot know which point a phrase belongs to, and
                    // its only defensible guess — the last row — is wrong exactly when a designer
                    // goes back to fill in point two.
                    onPartial = { partial ->
                        spokenRow = index
                        spoken = partial
                    },
                    onCommit = { finalText ->
                        val merged = appendSpoken(rows.getOrElse(index) { "" }, finalText)
                        spoken = ""
                        spokenRow = -1
                        emit(rows.toMutableList().also { current ->
                            if (index < current.size) current[index] = merged else current.add(merged)
                        })
                    },
                    onError = { message ->
                        spoken = ""
                        spokenRow = -1
                        services?.onError?.invoke(message)
                    },
                )
            }
        },
        onChange = ::emit,
    )
    DwDictationHint(listening = spoken.isNotBlank())
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
    Text(requiredMarked(fieldLabel(field)), color = MaterialTheme.field.muted, fontSize = 12.sp)
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

// --------------------------------------------------------------------------------------
// The ceiling the two LIST controls are held to
// --------------------------------------------------------------------------------------

/**
 * THE CEILING CLAUSE FOR A LIST FIELD — the number stated only where the registry declared one.
 *
 * ── WHY TAGS AND MULTI_ENUM NEED THIS AT ALL ─────────────────────────────────────────────────────
 *
 * `maxItems` is not a media key. docs/DESIGN_WORKSHOP.md names the three types it governs in one
 * breath — "IMAGE_LIST, TAGS, MULTI_ENUM" — and `coerce_value` applies `spec.max_items or
 * DEFAULT_MAX_ITEMS` to whichever of them a stage carries, under a comment headed "A REFUSAL, NOT A
 * TRUNCATION" (backend/app/services/stage_schema.py:1822). `save_stage` then restores the rejected
 * key from `previous`. So a control that lets a list grow past the ceiling does not cost the designer
 * the surplus entries — it costs them that field's whole write at the next sync, silently, with the
 * stage screen still showing what they typed. These two controls read no ceiling at all until
 * 2026-08-26, which is the third and last of the write paths audit finding 12 named on this client.
 * The browser's half of the same finding landed in `FieldInput.tsx` in the same pass, and its TAGS
 * and MULTI_ENUM controls now hold the identical `declaredCap`/`cap` split — so the two clients
 * refuse the same list at the same number, which is the only thing that makes this ceiling a
 * contract rather than a handset behaviour.
 *
 * ── AND WHY THE NUMBER IS SEPARATE FROM THE CEILING ──────────────────────────────────────────────
 *
 * docs/DESIGN_WORKSHOP.md:229-232 forbids both halves at once: a client "must neither read the
 * absence as no limit nor print a number it did not read". [declaredCap] is what the registry said
 * and null where it said nothing; the ceiling ENFORCED in that second case is the server's
 * [com.designprototype.workshop.data.DW_DEFAULT_MAX_ITEMS], which this client never read off the wire
 * and which the server may change without a `registry_version()` bump — so it is enforced and never
 * printed, and the sentence says the field is FULL and stops. That is the same split
 * [dwCapNotice] makes for a gallery, worded for a list of words rather than a list of files.
 *
 * NO FIELD COUNT IS WRITTEN HERE, deliberately, and the reason is this file's own header: two
 * measured counts in these comments were stale within days. What matters is not how many TAGS or
 * MULTI_ENUM fields declare a cap today but that the ones that do not are still held to something —
 * `DwListCapCeilingTest` asserts that property against the bundled registry, where a count belongs.
 */
internal fun dwListCeilingClause(label: String, declaredCap: Int?): String =
    if (declaredCap == null) {
        "$label is full"
    } else {
        "$label holds at most $declaredCap entr${if (declaredCap == 1) "y" else "ies"}"
    }

/**
 * The selection a list control may actually commit: everything already held, plus as much of what was
 * newly chosen as fits under [ceiling].
 *
 * IT CAPS GROWTH AND NEVER SHORTENS WHAT IS ALREADY STORED, and the second half is as deliberate as
 * the first. A cap is not part of `registry_version()`, so a field may perfectly well be holding five
 * entries on the day its declared ceiling becomes three — the values were valid when they were
 * written. Trimming them here would be this client deleting a designer's fieldwork to satisfy a rule
 * that arrived afterwards, without being asked and without anything to point at. What it does instead
 * is refuse to make it worse: any change that does not grow the list is passed through untouched, so
 * unticking one of the five still works, and the designer can bring it under the ceiling themselves.
 * (Until they do, the server refuses that one field at save exactly as it did before this function
 * existed — the overflow is not created here and cannot be repaired here.)
 *
 * The result keeps [next]'s order, which both callers have already put in REGISTRY order, so two
 * designers who tick the same three options store the same array.
 */
internal fun dwCapListGrowth(held: List<String>, next: List<String>, ceiling: Int): List<String> {
    if (next.size <= ceiling || next.size <= held.size) return next
    val keep = LinkedHashSet(next.filter { it in held })
    for (candidate in next) {
        if (keep.size >= ceiling) break
        keep.add(candidate)
    }
    return next.filter { it in keep }
}

/**
 * The refusal, spoken where a designer who just tapped is looking.
 *
 * A [Box] with an ASSERTIVE live region and not a bare [Text], mirroring the capture card's notice:
 * the sentence appears in response to a tap and TalkBack announces nothing for text that merely
 * arrives, so on a handset held by someone who cannot see it the refusal would otherwise be the
 * silence the whole ceiling repair exists to prevent. The Box stays in the layout when the notice is
 * null so the region has a stable node to announce into.
 */
@Composable
private fun DwListCapNotice(notice: String?) {
    Box(
        modifier = Modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Assertive
        },
    ) {
        notice?.let { sentence ->
            Text(sentence, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

/**
 * The always-visible "up to N" line, drawn ONLY where the registry declared the N.
 *
 * The other half of docs/DESIGN_WORKSHOP.md:229-232 and the reason [dwListCeilingClause] takes a
 * nullable: on a field the registry said nothing about, the enforced ceiling is the server's default
 * and drawing "up to 200" would be this client inventing a number the server owns. Nothing is drawn
 * there instead, which costs the designer nothing they can act on — the only moment that ceiling can
 * bite is an entry two hundred deep, and [DwListCapNotice] speaks then.
 */
@Composable
private fun DwListCapHint(declaredCap: Int?, held: Int) {
    if (declaredCap == null) return
    val room = (declaredCap - held).coerceAtLeast(0)
    Text(
        if (room == 0) {
            "Full at $declaredCap. Remove one to add another."
        } else {
            "Up to $declaredCap — $room more can be added."
        },
        color = MaterialTheme.field.muted,
        fontSize = 11.sp,
    )
}

/**
 * A free-form list with no canonical vocabulary behind it.
 *
 * Committed on the button and on nothing else. An earlier shape committed on every comma, which meant
 * a designer typing "Bagru, Sanganer" got the tag "Bagru" plus a half-typed "Sanganer" the moment
 * they paused — and tags are the one field type whose values are never validated against anything, so
 * a malformed one is never caught downstream.
 *
 * IT STOPS AT THE FIELD'S CEILING, declared or defaulted, for the reason [dwListCeilingClause] gives:
 * `coerce_value` refuses an over-long array rather than trimming it, so a list allowed to grow past
 * the cap loses the field's whole write at sync rather than its tail.
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
    /** What the registry declared, or null where it declared nothing — the only value that may be
     * PRINTED. [ceiling] is what is ENFORCED, and for a list field that is never nothing. */
    val declaredCap = field.maxItems.takeIf { it > 0 }
    val ceiling = dwEffectiveMaxItems(field.maxItems)
    /**
     * Why the last Add did not take. Cleared by the next one that does, and by a removal.
     *
     * Keyed on [resetKey] as well as the field key, for the reason [ScalarInput]'s buffer is: this
     * belongs to a ROW and not merely to a field. A composable reused for the next row of a
     * collection would otherwise carry "…is full" over onto a row that is empty — a sentence about a
     * state that stopped being true when the row changed underneath it.
     */
    var capNotice by remember(field.key, resetKey) { mutableStateOf<String?>(null) }

    fun commit() {
        val cleaned = pending.trim()
        /*
         * THE CEILING IS TESTED BEFORE THE BOX IS CLEARED, which is why this sits above the line that
         * clears it rather than beside the duplicate check below. A refusal that also swallowed the
         * word just typed would make the designer retype it after removing a tag, on a phone, in a
         * courtyard — so `pending` is left alone and the sentence quotes the word it refused.
         *
         * A DUPLICATE IS NOT GROWTH and falls through to the ordinary no-op below: refusing it as
         * "full" would report a tag as dropped that is already sitting in the list.
         */
        if (cleaned.isNotEmpty() && tags.none { it.equals(cleaned, ignoreCase = true) } && tags.size >= ceiling) {
            // Stripped for the same reason as the multi-select's notice above: a sentence, not a
            // label.
            capNotice = "${dwListCeilingClause(dwWithoutRequiredMark(fieldLabel(field)), declaredCap)}. " +
                "“$cleaned” was not " +
                "added — remove one first if you need it instead."
            return
        }
        pending = ""
        if (cleaned.isEmpty() || tags.any { it.equals(cleaned, ignoreCase = true) }) return
        capNotice = null
        onChange(DwValues.ofList(tags + cleaned))
    }

    Text(requiredMarked(fieldLabel(field)), color = MaterialTheme.field.muted, fontSize = 12.sp)
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
    // The hint reads the DECLARED cap and the notice fires on the ENFORCED one, which is the split
    // docs/DESIGN_WORKSHOP.md:229-232 requires — see [dwListCeilingClause]. The Add button is left
    // enabled at the ceiling on purpose: a button that goes dead with no sentence beside it is the
    // silent refusal this pair exists to replace.
    DwListCapHint(declaredCap, tags.size)
    DwListCapNotice(capNotice)
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
                        // The notice goes with the removal that answers it: leaving "…is full" on
                        // screen beside a list that now has room asserts a state that has just
                        // stopped being true.
                        onClick = {
                            capNotice = null
                            onChange(DwValues.ofList(tags.filterNot { it == tag }))
                        },
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

    Text(requiredMarked(fieldLabel(field)), color = MaterialTheme.field.muted, fontSize = 12.sp)
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

    Text(requiredMarked(fieldLabel(field)), color = MaterialTheme.field.muted, fontSize = 12.sp)
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
        // THE SCREENING SLOT: this field, in THIS ROW. `resetKey` is the row's own id for a
        // collection and the field key for a singleton — the identity that already exists on this
        // renderer precisely because collection rows share composable slots (see [FieldRenderer]'s
        // `resetKey`). Keyed on the field alone, nine prototype rows would share one screening queue
        // and one refusal notice, and row 2's rejected photograph would be reported under row 7.
        slotKey = "${field.key}#$resetKey",
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
            // THE TECHNIQUE IS DROPPED HERE ON PURPOSE. A stage field's provenance is written by
            // `entry_provenance.merge_entry_provenance`, which has no `measurementMethods` key to
            // put it under — that mechanism reaches records only. The record forms are what read
            // this third argument; see `DwMeasurementMarkers`. When the stage half lands (the
            // `DIMENSION_FIELDS` note in `measurement_provenance.py` describes it as one more key
            // beside `{by, byName, at}`, no migration), this is where it is already waiting.
            onPropose = { key, proposed, _ -> onPatch(mapOf(key to proposed)) },
        )
    }

    /*
     * "Straighten a photographed sketch into a plate", offered on the FILE field a plate belongs in.
     *
     * TWO FIELDS IN THE BUNDLED REGISTRY, NOT ONE: stage 11's `sketch.lineArtFile` — the pairing the
     * feature was written for — and stage 16's `finalProduct.lineDrawing`, which is often a CAD
     * export this panel has nothing to do with and just as often a technical drawing made on paper
     * and photographed. [dwOffersSketchRectify] argues that second one, and DwSketchRectifyFieldTest
     * names both. NO COUNT OF THE REFUSALS IS WRITTEN HERE, and none is written there either: the
     * test sweeps EVERY field of EVERY entity in the bundled asset and asserts the offered set is
     * exactly those two, which refuses every other FILE field in the registry however many the
     * registry grows to. This sentence has been wrong twice already — once as "`sketch.lineArtFile`
     * and nothing else", untrue of the code beneath it, and once as "the registry's other six FILE
     * fields", untrue of the asset in the very session that declared two more of them.
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
    /*
     * THE PHOTOGRAPHS BOTH DERIVATION PANELS READ, BUILT ONCE SO THE TWO CANNOT DRIFT APART.
     *
     * `DwSketchRectifyPanel` straightens a photographed sketch into a plate; `DwSketchTracePanel`
     * traces one into line art. They are offered on the SAME field and read the SAME images, and
     * that is structural rather than coincidental: `dwOffersSketchTrace` DELEGATES to
     * `dwOffersSketchRectify` instead of re-deriving the answer, for the reason its own KDoc gives —
     * a second regex would be a second copy of "which fields are safe to attach a derived artefact
     * to", and the two would eventually disagree.
     *
     * A second copy of the SOURCE list would be the same mistake one layer down, so there is one.
     * Not remembered, because the previous shape was not either: only `dwSketchSourceFields` was, and
     * it is a filter over a map that is already in memory.
     */
    val sketchDerivationSources =
        if (dwOffersSketchRectify(field, siblings)) {
            dwSketchSourceFields(siblings).flatMap { imageField ->
                val imageIds = if (DwFieldType.of(imageField.type) == DwFieldType.IMAGE_LIST) {
                    DwValues.list(rowValues[imageField.key])
                } else {
                    listOfNotNull(DwValues.text(rowValues[imageField.key]).takeIf { it.isNotBlank() })
                }
                imageIds.mapNotNull(media.resolve)
                    .filter { it.mediaType.equals("IMAGE", ignoreCase = true) }
                    .map { DwSketchSource(fieldLabel = imageField.label, item = it) }
            }
        } else {
            emptyList()
        }

    if (dwOffersSketchRectify(field, siblings)) {
        DwSketchRectifyPanel(
            field = field,
            sources = sketchDerivationSources,
            media = media,
            currentFileName = ids.firstOrNull()?.let(media.resolve)?.displayName,
            enabled = enabled,
            onAttached = { id -> onChange(JsonPrimitive(id)) },
            onMessage = services?.onMessage ?: {},
            onError = services?.onError ?: {},
        )
    }

    /*
     * "Trace this sketch into line art", offered on the same FILE field as the rectify panel above.
     *
     * WHAT MAKES THIS DIFFERENT FROM EVERY OTHER PANEL ON THIS CARD: the arithmetic is not written
     * for this app. It is the vendored engine — `android/core-imaging`, `core-vector`,
     * `core-pipeline` and `core-export`, 101 Kotlin files taken verbatim from upstream with a SHA-256
     * each in `android/UPSTREAM-MANIFEST-KOTLIN.txt`, and the same upstream the web runs as the
     * TypeScript in `frontend/lib/trace/engine/`. A hand-written port would be a second
     * implementation of Otsu, Canny and Bezier fitting, and two of those do not agree to the digit
     * about a document that goes to a ministry. What holds these two vendorings together is
     * `:core-pipeline`'s `ParityTest`, which replays the shared fixtures under `docs/fixtures/`
     * through the Kotlin and asserts the TypeScript's numbers.
     *
     * This used to be one minified JavaScript bundle in `assets/`, run in an
     * `androidx.javascriptengine` isolate. The owner replaced that route with the Kotlin one and it
     * is deleted — see `DwSketchTrace.kt`'s header for what went and what it bought.
     *
     * IT RUNS ON THIS DEVICE AND MAKES NO NETWORK CALL. That is the whole reason it exists rather
     * than a server endpoint: the sketch is traced in the courtyard where it was drawn, with no
     * signal, and the designer sees the result before anything is attached.
     *
     * Its one write is `onChange`, from a button that names the file it will attach — the same
     * single door the rectify panel uses, and for the same reason: attaching to a single-valued
     * field REPLACES its value, so neither panel may ever be offered on `sketch.image` itself.
     *
     * ── `exportCard` IS THIS HOST'S, AND LEAVING IT NULL MEANT THERE WAS NO EXPORT AT ALL ────────
     *
     * The comment that used to sit here said the panel "draws its own export affordance when no host
     * slot is supplied". **That was never true of the code beneath it.** `DwSketchTracePanel` draws
     * the block only `if (traced != null && exportCard != null)`, and this is the only call to that
     * panel in the tree — so for as long as the sentence stood, a designer could trace a sheet on a
     * handset and had no way to save it, send it, or choose what ground it was written on. Five
     * finished, tested surfaces were reachable from nothing: the five-format table, the route to the
     * public Downloads folder, the share sheet, the per-format losses, and the one `DwTraceTier.EXPORT`
     * control the parameter table declares. That is this repository's own rule about a capability that
     * exists and cannot be reached, and it had been broken here by a defaulted argument and a comment
     * that read as a decision.
     *
     * THE SLOT IS ADAPTED HERE RATHER THAN THE PANEL TAKING THE CARD, for the reason
     * [DwTraceExportSlot] gives: the card needs a `WorkshopRepository` and a `DwTraceExporter` and the
     * tuning surface needs neither, so a panel that composed it would be a panel nobody can preview.
     * This host has a repository — `services.repository`, the same one every other networked control
     * on this card reaches for — and a null `services` means the field is being PREVIEWED rather than
     * edited, which is exactly the state that should offer no file writing at all.
     */
    if (dwOffersSketchTrace(field, siblings)) {
        val exportRepository = services?.repository
        DwSketchTracePanel(
            field = field,
            sources = sketchDerivationSources,
            runtime = rememberDwTraceRuntime(),
            media = media,
            // The record's own category seeds the subject. `dwTraceSubjectFor` falls back to the
            // default for anything it does not recognise, including absence, so an entity with no
            // category field is not a special case here.
            recordCategory = DwValues.text(rowValues["category"]).takeIf { it.isNotBlank() },
            currentFileName = ids.firstOrNull()?.let(media.resolve)?.displayName,
            enabled = enabled,
            onAttached = { id -> onChange(JsonPrimitive(id)) },
            onMessage = services?.onMessage ?: {},
            onError = services?.onError ?: {},
            exportCard = { slot ->
                // NOT A NULL SLOT WHEN THERE IS NO REPOSITORY, BUT A SLOT THAT DRAWS NOTHING. The two
                // are the same on screen and only one of them can be read: the reason the block is
                // absent is written here, next to the condition, instead of being a defaulted argument
                // three files away that the next reader has to go and look up.
                if (exportRepository != null) {
                    DwSketchTraceExportCard(
                        repository = exportRepository,
                        // Straight off the finished trace. The slot carries the panel's own state —
                        // the background it read from `appliedParams`, which photograph was traced,
                        // and the one busy flag — and the result carries everything about the drawing.
                        traceSvg = slot.result.svg,
                        geometry = slot.result.geometry,
                        documentWidth = slot.result.width,
                        documentHeight = slot.result.height,
                        documentBackground = slot.documentBackground,
                        sourceName = slot.sourceName,
                        shapeCount = slot.result.shapeCount,
                        nodeCount = slot.result.nodeCount,
                        frameNote = slot.result.frameNote,
                        isPreview = slot.result.isPreview,
                        exporter = rememberDwTraceExporter(),
                        busy = slot.busy,
                        onBusyChange = slot.onBusyChange,
                        onError = services?.onError ?: {},
                        // The "White background" chips. `output.background` is a leaf of the engine's
                        // parameter tree read at the twelfth stage, so the card asks and the PANEL
                        // patches and re-traces — which is why this is a callback and not a value.
                        onBackgroundChange = slot.onBackgroundChange,
                        onNeedFullResolution = slot.onNeedFullResolution,
                    )
                }
            },
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

