package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwDecodeResult
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwReferenceList
import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.DwReferenceResponseDto
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.DwWorkshopCodeRef
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.decodeWorkshopCode
import com.designprototype.workshop.data.dwRefId
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.ui.DwQrLiveScanControl
import com.designprototype.workshop.ui.DwQrScanControl
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectCreateAction
import com.designprototype.workshop.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A REF field as a picker over the records that already exist, instead of a box for typing an id.
 *
 * ── WHAT THE OLD TEXT BOX COST ────────────────────────────────────────────────────────────────
 *
 * A REF is a join key. Rendered as a text box holding a raw UUID, the honest outcome is that nobody
 * fills it — and the registry's own commentary says what happens next: "typing thirty names in by
 * hand produced thirty rows with no join key, which is why a cluster's second workshop could never
 * be compared with its first". The name is captured, the link is not, and the loss is invisible
 * because the report prints perfectly well off the name alone. It surfaces years later as a research
 * dataset in which no workshop can be connected to the artisan records it was about.
 *
 * ── THE CASCADE ───────────────────────────────────────────────────────────────────────────────
 *
 * `refFilterBy` names a sibling field on the SAME row. `existingProduct.productRef` filters by
 * `artisanRef`, so once the artisan is chosen the product dropdown holds that artisan's products and
 * nothing else. Both halves are load-bearing. Without the cascade the product list is every product
 * in the cluster — several hundred in a mature one — and a designer scrolling that list gives up and
 * types the name, which is the same data loss by a longer route.
 *
 * Until the parent is chosen this field says so and offers nothing. That is deliberate: an unfiltered
 * list shown "helpfully" before the artisan is picked is a list from which the wrong artisan's
 * product gets selected, and a wrong join key is materially worse than a missing one because nothing
 * downstream can tell it is wrong.
 *
 * AND WHEN THE PARENT MOVES, THE CHILD GOES WITH IT — see [dwCascadeClearedMessage]. Withholding the
 * list before the parent is chosen and clearing the choice when the parent changes are the same rule
 * read forwards and backwards; this client honoured the first for a release and not the second, so a
 * process picked under product A sat on a row that had since been changed to product B, offerable by
 * nothing and refused by nothing.
 *
 * ── OFFLINE ───────────────────────────────────────────────────────────────────────────────────
 *
 * A designer in a courtyard with no signal still has to be able to pick an artisan, so the list is
 * never fetched on demand and waited for. [WorkshopRepository.designWorkshopReferences] answers from
 * [com.designprototype.workshop.data.DwReferenceStore] — a JSON document per (model, scope, filter)
 * under `filesDir` — before it touches the network, and the network is only ever a refresh that may
 * silently fail. WORKSHOP-scoped lists are cached against their workshop; ALL-scoped ones are shared
 * across every workshop on the handset, which is what lets a brand-new offline workshop pick from
 * the artisan register a previous workshop downloaded. The card says when the copy was last
 * refreshed, because a designer looking at a nine-name list needs to know whether that is the roster
 * or merely what this phone last saw.
 *
 * ── SCANNING A CARD INTO THE PICKER ─────────────────────────────────────────────────────────
 *
 * Every repository record this app can link carries a printed code — `ui/RecordCodeCard.kt` draws one
 * on the record's own screen and `WorkshopCodesScreen` prints sheets of them — and the case the
 * feature exists FOR is a designer in a courtyard holding a card somebody else printed. Searching a
 * dropdown for a name you cannot spell, on a phone, standing up, is the moment the join key is
 * abandoned and the name is typed into the free-text box instead: the loss this whole file is about.
 *
 * A SCAN IS A PICK AND NOTHING MORE. [DwReferenceScanPanel] turns a code into an id and hands it to
 * the SAME `choose` the dropdown's own `onSelect` calls, so [hydratedValues], [hydrationPatch] and the
 * `lastHydration` mis-pick repair behave identically however the record was chosen. Nothing about
 * hydration is reimplemented on the scanned path, and nothing may be: two routes onto one row is how
 * one of them comes to fill it differently from the other.
 *
 * THE DROPDOWN IS NEVER HIDDEN OR REPLACED, and neither is the typed code inside the panel.
 * `docs/DECISION-qr-scanning-on-android.md` names the typed code as the guaranteed path — it needs no
 * permission, no lens and no library, and it is the only route that works on a card whose QR is
 * smudged while the characters printed under it are not — and picking by name needs no camera either.
 *
 * WHICH FIELDS OFFER IT is decided by [dwScannableRecordType] and by nothing else. A field whose
 * `refModel` has no letter in the code grammar has no card that could be scanned into it, so it shows
 * no scan control at all rather than one that can only refuse.
 *
 * THE THREE REFUSALS, each of which is a different next action: the wrong KIND of card
 * ([dwWrongRecordTypeMessage]); a real record this field's workshop scope excludes
 * ([dwReferenceOutOfScopeMessage]); and a code that resolves to nothing this box can offer
 * ([dwUnresolvedScanMessage], which must never distinguish a record that is absent from one that is
 * forbidden). They are `scanTypeRefusal`, `outOfScopeRefusal` and `unresolvedRefusal` in
 * `frontend/components/designworkshop/StageReferenceField.tsx`, meaning for meaning.
 *
 * BEING OFFLINE IS NOT ONE OF THE THREE and is deliberately kept apart from the third —
 * [DW_SCAN_OFFLINE_MESSAGE], [DW_SCAN_LOOKUP_FAILED_MESSAGE] and [DW_SCAN_UNSENT_WORKSHOP_MESSAGE],
 * none of which changes anything at all on the row. [dwScanCascadeMovedMessage] is a fourth of the
 * same kind: the answer arrived, and about a row that has since moved under it.
 *
 * THE CASCADE IS ASKED TWICE, WHICH IS THE ONE RULE HERE THAT IS ABOUT TIME RATHER THAN MEANING.
 * A cached record is linked without a request only where the narrowing can be PROVEN on the device
 * ([dwScanLocalStep]), and an answer that came back from the server is dropped if the parent moved
 * while it was in the air ([dwScanCascadeMovedMessage]). Both exist for one outcome: a product read
 * under artisan A must never land on a row that now names artisan B.
 */

/**
 * What an inline record form reported when it closed.
 *
 * `saved == false` is a designer who backed out, and it must change nothing at all — not the field,
 * not the list, not a refresh. The three `created…` fields are populated ONLY when a brand-new record
 * came back with a server id; an edit reports `saved = true` and nothing else, because the record it
 * changed is already linked.
 *
 * A CREATE THAT ONLY REACHED THE DEVICE REPORTS `saved = true` AND A BLANK ID, and that combination
 * is the one the caller must handle rather than assume away. Every record form in this app queues to
 * `filesDir` when there is no connection and uploads on reconnect — which means it has no server id
 * yet, and a REF field is a join key, so there is nothing to store. Reporting a blank id lets the
 * picker say so instead of writing a placeholder that would look exactly like a real link.
 */
@Immutable
data class DwInlineRecordOutcome(
    val saved: Boolean,
    val createdId: String = "",
    val createdLabel: String = "",
    val createdHint: String = "",
)

/**
 * Open the app's OWN record form for a repository model, without leaving the stage being filled in.
 *
 * ── THE PROBLEM THIS REMOVES ──────────────────────────────────────────────────────────────────
 *
 * Half the fields of a 22-stage design workshop are references — the artisan who wove the prototype,
 * the product it was copied from, the tool it was made on. A picker can only offer records that
 * already exist, so a designer who reached stage 13 and found the artisan missing had to abandon a
 * half-filled stage, walk out to the artisan form, fill in a full screen of it, come back, find their
 * place and re-open the row. In a courtyard, with the artisan standing in front of them, that is the
 * moment the app stops being used and a name gets typed into a text box instead — which is the exact
 * loss [DwReferenceStore]'s own commentary describes: thirty rows with no join key.
 *
 * ── IT MOUNTS THE REAL FORM, NOT A SIMPLER ONE ────────────────────────────────────────────────
 *
 * The implementation hands back `ArtisanForm` / `ProductForm` / `ToolForm` / `ProcessForm` exactly as
 * the create and edit screens mount them. That is deliberate and it is the whole design. An artisan
 * record carries a Verhoeff-checked Aadhaar number — the repository's deduplication key — a craft, a
 * mandatory location and non-empty Do's and Don'ts. A four-box "quick create" would be a SECOND
 * answer to what an artisan is, it would drift from the first, and the records it made would be the
 * ones quietly missing the fields nobody could see were missing. The same decision as the web's
 * `components/designworkshop/InlineRecordDialog.tsx`, for the same reason.
 *
 * ── WHY IT IS A HANDLE PASSED DOWN, AND NOT A CALL ────────────────────────────────────────────
 *
 * The forms are private to `MainActivity`, and they need the craft and artisan registers, the
 * signed-in account's admin view and the app's message sink — none of which a field renderer has or
 * should acquire. So the host is supplied the same way [DwFieldServices] and `DwMediaBridge` already
 * supply everything else a field needs from outside the registry: handed down from the one place that
 * has it. A null host means "this surface cannot create records", and the picker simply does not
 * offer to — which is the correct behaviour for a previewed stage.
 */
@Immutable
class DwInlineRecordHost(
    /**
     * [recordId] null creates; non-null edits that record. [seed] is what the picker already knows
     * about the record being created — see [DwInlineSeed]. [onFinished] fires exactly once, when the
     * form is done with — saved or abandoned.
     */
    val open: (
        model: String,
        recordId: String?,
        seed: DwInlineSeed,
        onFinished: (DwInlineRecordOutcome) -> Unit,
    ) -> Unit,
)

/**
 * What the picker that opened a record form already knows about the record being created.
 *
 * ── THE DEFECT THIS EXISTS FOR ────────────────────────────────────────────────────────────────
 *
 * A product created from `existingProduct.productRef` is created from a row that has ALREADY named
 * its artisan, in the picker directly above. Nothing carried that across, so the form opened with the
 * carry bag's artisan — the LAST artisan this designer documented anywhere, which is not the artisan
 * on the row they pressed "Create a new product" from — or with nothing at all. `artisanId` is
 * optional on save while `artisanName` is a required free-text box, so the product saved happily
 * filed under nobody; the server then narrows this very picker on that column, so the record was
 * invisible in the control that made it, seconds after the designer created it.
 *
 * ── NOT SET FOR A ROSTER CASCADE, AND THAT IS THE WHOLE OF THE RULE ───────────────────────────
 *
 * Two fields in the registry cascade and they hold DIFFERENT KINDS OF ID. `existingProduct.productRef`
 * filters by `existingProduct.artisanRef`, which is an `Artisan` id. `prototype.productRef` filters by
 * `prototype.artisanRef`, which is a `DwParticipant` ROSTER ENTRY id — the maker was chosen from stage
 * 3's list of who was in the room. The SERVER resolves the second back to an artisan
 * (`_artisan_id_behind`) and deliberately spares the clients that rule; this one cannot follow it, and
 * filing a product under a roster-entry id would be worse than filing it under nobody. So the FILTER
 * FIELD'S OWN `refModel` decides, exactly as `StageReferenceSelect`'s `inlineSeed` decides on the web.
 *
 * ── EVERY SEEDED VALUE IS VISIBLE AND EDITABLE ────────────────────────────────────────────────
 *
 * Nothing here is written into a hidden box. A seed lands in the same control a designer would have
 * used, showing the same name, and they can change it before saving. The comment this replaces —
 * "asserting a parent this picker never saw the form choose would be a claim about whose product it
 * is" — was right about a claim made BEHIND a form, and it stays right: what changed is that the
 * picker now DOES see the form choose it, and shows it.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────────────────────
 *
 * No identity number of any kind, for `sanitizeCarryContext`'s reason: a seed is copied from a stage
 * row that everyone who can open the workshop can read. Nothing regulated may travel this way.
 *
 * The linked `Workshop` is not here either, and the web's seed carries it. That half is NOT
 * implemented on this client: `StageScreen` is handed a design-workshop ID and nothing else — it
 * never loads the summary that names the linked `Workshop` — so seeding it would mean a fetch from
 * inside a field renderer, which is a decision about where that id comes from rather than a wiring
 * change. Until then the workshop picker keeps `applyMostRecentSubmittable`'s default, which is what
 * it has always done and is visible and editable in the same dropdown.
 */
@Immutable
data class DwInlineSeed(
    /** The artisan the row cascades from, ONLY where the filter field's own `refModel` is `Artisan`. */
    val artisanId: String = "",
    /**
     * That artisan's name AS THE ROW ALREADY SHOWS IT — hydration's frozen copy, so it is the
     * server's own spelling and not anything this file invented. It fills the REQUIRED free-text box
     * on the product and tool forms, which would otherwise open blank beside an artisan the designer
     * has already chosen.
     */
    val artisanName: String = "",
) {
    val isEmpty: Boolean get() = artisanId.isBlank() && artisanName.isBlank()

    companion object {
        /** The picker knows nothing worth seeding — a roster cascade, or no cascade at all. */
        val NONE = DwInlineSeed()
    }
}

/**
 * The reference models this picker may offer to CREATE, and the word it calls each one.
 *
 * The `Dw…` models are absent and cannot be here. A `DwSketch`, a `DwPrototype` or a `DwParticipant`
 * is another ROW of this same workshop, created by adding a row to its own stage — offering to
 * "create" one from a picker would put a second, parallel way of adding a prototype into the app, and
 * the two would disagree about what a prototype is by the second release.
 *
 * Kept in step with `INLINE_CREATABLE` in `components/designworkshop/InlineRecordDialog.tsx` — a
 * model creatable on one client and not the other is a designer who can finish a stage on a laptop
 * and not on the phone they actually carry.
 *
 * ── CRAFT IS ABSENT ON BOTH CLIENTS, AND THE TWO USED TO GIVE DIFFERENT REASONS FOR IT ────────────
 *
 * This comment gave one reason (the `require_craft_manager` rank gate, Professor and above, so a
 * button offered to every designer buys most of them a 403 after filling a form in) while
 * `InlineRecordDialog.tsx` gives another (a taxonomy created from inside a stage fractures the craft
 * vocabulary), and both asserted they were "kept in step" — which they are only in outcome. Both
 * reasons are real and either alone is sufficient, so the decision stands; what did not stand was
 * the pretence that one file's argument was the other's.
 *
 * WHAT WAS ACTUALLY OUT OF STEP was the REMEDY, not the refusal. The web picker offers a link to the
 * crafts register when the craft is missing, added because "stage 1 is the first control a designer
 * ever touches in this app, and until this line it was the only picker in the product that offered
 * nothing at all when the craft was missing or misspelt: the remedy existed on /crafts and nothing
 * said so." That was still true of the handset — the surface the designer is actually holding in the
 * room, where an empty list said only "No records on this device yet", a claim about THIS PHONE'S
 * CACHE that sends somebody looking for a tower when the craft has never been documented anywhere.
 * [dwCraftRegisterNote] is the handset's half of that remedy: a sentence, not a button, because a
 * button here would be the change this comment refuses. Adding `"Craft"` to the map above still
 * requires the web's entry to move in the same change.
 */
private val INLINE_CREATABLE: Map<String, String> = mapOf(
    "Artisan" to "artisan",
    "ProductDocumentation" to "product",
    "ToolDocumentation" to "tool",
    "Process" to "process",
)

/**
 * What to tell a designer at stage 1 whose craft is not in the list — see [INLINE_CREATABLE]'s note.
 *
 * IT NAMES THE ROUTE AND THE ROUTE'S GATE WITHOUT CLAIMING THE READER HAS IT. "Add craft" is a real
 * destination in this app's own menu (`NavDestination.ADD_CRAFT`, gated on
 * `FieldPermissions.canManageCrafts`, mirroring `require_craft_manager`), so the sentence can point at
 * it — but this picker has no user in scope and inventing one to decide the wording would be a second
 * copy of the rank rule. Naming the gate instead is true for every reader: a Professor knows where to
 * go, and everybody else knows who to ask, which is strictly better than the old sentence that named
 * neither.
 *
 * THE SECOND HALF IS THE ONE THAT UNBLOCKS THE STAGE, and it is the reason this cannot just be a
 * placeholder. `craftRef` is optional and the craft's NAME box is required, so the designer's real
 * next move is to type the name rather than to find the register — and nothing on this screen said
 * so. The name box is resolved through [FieldDto.refHydration], the server's own dictionary from the
 * Craft record's keys onto this entity's, rather than by matching a key name here; when that lookup
 * finds nothing the clause is simply dropped, so a wrong answer costs a shorter sentence and never a
 * pointer at a box that does not exist. The `pendingHydration` note inside [DwReferenceSelectField]
 * records what guessing keys on this surface cost the last time.
 *
 * ── IT IS NOT THE WEB'S SENTENCE, AND THAT IS DELIBERATE. WHOEVER READS ONE FILE SHOULD KNOW ─────
 *
 * `StageReferenceField.tsx` declares TWO sentences and picks between them by rank —
 * `CRAFT_REGISTER_LINK` for somebody `canManageCrafts` answers true for, `CRAFT_REGISTER_BLOCKED`
 * ("ask the master admin") for everybody else — and its doc block asks the handset to carry "these
 * words, not a second phrasing of them". This is a second phrasing, on purpose, for a reason that is a
 * property of this file rather than a preference:
 *
 *  · **THIS PICKER HAS NO USER IN SCOPE.** [DwReferenceSelectField] takes a field, a value, a bridge
 *    and a row; there is no `useAuth` equivalent to reach for, and threading one in to choose between
 *    two sentences would put a second copy of the rank rule on this surface — which is what the
 *    paragraph above refuses. One sentence therefore has to be true at BOTH ranks.
 *  · **THE WEB'S BLOCKED SENTENCE IS NOT TRUE AT BOTH RANKS.** "Ask the master admin" is the wrong
 *    next move for a Professor, who can add the craft themselves; and the web reaches it by HIDING the
 *    route from anybody below that rank, so somebody who could get the craft added by asking a
 *    colleague one rank up is not told the register exists. Naming the destination and its gate says
 *    something unconditionally true to every reader, which is the property the shared-string rule is
 *    protecting in the first place.
 *
 * SO THE CLAIM EACH SENTENCE MAKES IS THE SAME CLAIM — crafts come from the register, not from here,
 * and the stage saves regardless — and only the routing advice differs, because the two surfaces know
 * different things about who is reading. The web's own anchor USED to be gated on `refModel ===
 * "Craft" && !disabled` and on nothing else, which sent every sub-Professor designer to a page they
 * can only read; it is gated on `craftManager` now, and naming the gate in words is how this surface
 * avoids the same trap without reading a rank. **The web's doc block still says the handset offers
 * neither sentence, which stopped being true when this one landed** — that half of the reconciliation
 * is in `frontend/`, is not this lane's to write, and is handed off.
 */
private fun dwCraftRegisterNote(field: FieldDto, writableFields: Map<String, FieldDto>): String {
    val nameBox = field.refHydration["craftName"]?.let { writableFields[it]?.label }
    val fallback = "Linking one is optional — the stage still saves without it."
    val typeItIn = nameBox?.let {
        "Linking one is optional: type the craft into “$it” above and the stage still saves."
    } ?: fallback
    return "Craft not listed, or listed under a different spelling? Crafts are added on the crafts " +
        "register — “Add craft” in this app's menu, which is open to a Professor and above — and " +
        "never from this picker. $typeItIn"
}

/** Everything the picker needs that the renderer cannot work out from the field alone. */
@Immutable
class DwReferenceBridge(
    val repository: WorkshopRepository,
    /** The id the references endpoint is asked about. Null while the workshop is local-only. */
    val workshopId: String?,
    /** Null on a surface that cannot open a record form — a previewed stage. See [DwInlineRecordHost]. */
    val inlineRecords: DwInlineRecordHost? = null,
)

/**
 * The cascading single-select.
 *
 * [parentValue] is the current value of the field named by `refFilterBy`, read out of the SAME row.
 * [onHydrate] receives the chosen record's `data` as one batch. It must be a batch: writing the
 * eight hydrated keys with eight sequential calls to a per-key setter would have each call recompute
 * from the row snapshot its closure captured, so seven of the eight writes would be discarded and
 * the designer would watch a picker fill in one field out of eight.
 */
@Composable
internal fun DwReferenceSelectField(
    field: FieldDto,
    value: JsonElement?,
    parentField: FieldDto?,
    parentValue: JsonElement?,
    bridge: DwReferenceBridge?,
    enabled: Boolean,
    error: String?,
    label: String,
    /** The whole row this field sits in, so hydration can tell an empty box from a typed answer. */
    rowValues: Map<String, JsonElement>,
    /**
     * The fields this entity actually declares, by key. Anything the registry's mapping names that
     * is not in here is not ours to write, and the TYPE is needed as well as the key: a value bound
     * for an IMAGE_LIST has to arrive as a list.
     */
    writableFields: Map<String, FieldDto>,
    onChange: (JsonElement?) -> Unit,
    onHydrate: (Map<String, JsonElement?>) -> Unit,
) {
    val context = LocalContext.current
    val selectedId = remember(value) { dwRefId(value) }
    val parentId = remember(parentValue) { dwRefId(parentValue) }
    val needsParent = field.refFilterBy.isNotBlank() && parentId.isBlank()

    var list by remember(field.key) { mutableStateOf<DwReferenceList?>(null) }
    var truncated by remember(field.key) { mutableStateOf(false) }
    /**
     * What the LAST chosen reference wrote into the row, so a re-pick can correct it.
     *
     * The hydration rule below needs this. Overwriting everything on every pick would throw away a
     * spelling the designer corrected by hand; overwriting nothing would leave the first artisan's
     * village standing under the second artisan's name after a mis-pick is fixed, which is a
     * fabricated record. Remembering what WE wrote separates the two cases exactly.
     *
     * It is composition-scoped and does not survive process death, and that is acceptable: the only
     * consequence of losing it is that a stale value is left for the designer to correct rather than
     * corrected for them, which is the safe direction to fail in.
     */
    var lastHydration by remember(field.key) { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }

    /**
     * Records made from inside this picker, kept beside the fetched list rather than merged into it.
     *
     * A record created a second ago is not in [list] — that list was fetched before it existed — and
     * without a row for it the trigger falls back to "Linked record a3f10c2b", which reads as a link
     * to something this phone cannot name and invites the designer to clear it and pick again.
     *
     * SEPARATE, AND NEVER WRITTEN INTO [list], because the refresh below replaces `list` wholesale. A
     * server whose reference query has not yet caught up — a WORKSHOP-scoped artisan list, say, that
     * only holds artisans attached to this workshop's Workshop record — would otherwise take the name
     * back off the screen a moment after it appeared.
     */
    var locallyCreated by remember(field.key) { mutableStateOf<List<DwReferenceOption>>(emptyList()) }
    /**
     * Bumped after a record is created or edited here, to re-run the fetch below.
     *
     * The refresh is what makes the new record a REAL option — the server's own label, hint and,
     * above all, its `data` map, which is what hydration writes into the row. It also writes the
     * record through to [DwReferenceStore], so the next stage, and the next launch, know its name.
     */
    var refreshTick by remember(field.key) { mutableIntStateOf(0) }
    /**
     * A newly created id whose row is still waiting to be filled in from the server's copy.
     *
     * HYDRATION CANNOT BE DONE FROM THE CREATE RESPONSE, and this is why the wait exists.
     * [DwReferenceOption.data] is keyed by the REFERENCE MODEL's own vocabulary — an `Artisan`
     * payload's keys, not the entity's — and [FieldDto.refHydration] is the dictionary onto the
     * boxes. THIS SENTENCE USED TO SAY `data` WAS ALREADY KEYED BY THE ENTITY'S FIELD KEYS, and
     * believing it is exactly how the artisan's name reached the product column of stage 6: on
     * `existingProduct` the reference's `data["name"]` is the ARTISAN's, while the entity's own
     * `name` means the PRODUCT. Assembling the record's `data` here from a create response would
     * be a second, client-side copy of the reference models, and the moment it drifted an
     * inline-created artisan would fill the row differently from the same artisan picked off the
     * list an hour later. So the id is stored immediately (the join key is never at risk) and the
     * row is filled in when the refreshed list arrives carrying the server's own answer.
     */
    var pendingHydration by remember(field.key) { mutableStateOf("") }
    /**
     * The record a SCAN resolved, kept beside the fetched list for the same reason as
     * [locallyCreated] and stored apart from it because it has a different origin.
     *
     * A colleague's card names a record this device's list need not contain — it was documented at
     * another cluster, or simply after this phone last refreshed — and without a row for it the
     * trigger falls back to "Linked record a3f10c2b", which reads as a link to something the app
     * cannot name and invites the designer to clear it and pick again. The option the server sent
     * back for the scan carries the real label, hint and `data`, so it is kept.
     *
     * NEVER WRITTEN INTO [list], which the refresh replaces wholesale, and never written to
     * [com.designprototype.workshop.data.DwReferenceStore] either: a by-id answer is one row filed
     * under the key the whole register lives at, and caching it would replace a fifty-name artisan
     * list with a one-name one — see `WorkshopRepository.designWorkshopReferenceById`.
     *
     * ONE AT A TIME, because only the last scan can be the one on screen.
     *
     * IT CARRIES THE PARENT IT WAS RESOLVED UNDER, and that is the whole of [DwScannedAside]. The
     * record was proved in scope FOR THAT PARENT — the by-id request sent the cascade with the id,
     * which is the only reason the answer could be trusted — so once the row's artisan changes it is
     * a row about somebody else's work sitting in a narrowed list, indistinguishable from an option
     * the server offered, one tap from being chosen. The server's own `filter_by` path raises rather
     * than widening for exactly this reason. It is still NOT cleared when the designer merely unlinks:
     * the record is a real option under that parent and taking its name off the dropdown would help
     * nobody, and changing back to that parent brings it back with it.
     */
    var scannedOption by remember(field.key) { mutableStateOf<DwScannedAside?>(null) }

    // Keyed on the parent's value as well as the model: changing the artisan must re-ask for that
    // artisan's products rather than leaving the previous artisan's list on screen under a new name.
    LaunchedEffect(field.refModel, field.refScope, parentId, bridge?.workshopId, needsParent, refreshTick) {
        if (bridge == null || field.refModel.isBlank() || needsParent) return@LaunchedEffect
        bridge.repository.designWorkshopReferences(
            context = context,
            workshopId = bridge.workshopId,
            model = field.refModel,
            scope = field.refScope,
            filterValue = parentId,
        ) { fetched, wasTruncated ->
            list = fetched
            truncated = wasTruncated
        }
    }

    /**
     * Whether the clear below has fired and not yet been answered, so the row can SAY so.
     *
     * Composition-scoped like [lastHydration], and losing it on process death is the safe direction
     * for the same reason: the id is already gone from the row, so all that is lost is the sentence
     * explaining why the box is empty — and an empty cascaded box under a parent the designer just
     * changed is the one blank on this form that explains itself.
     */
    var cascadeCleared by remember(field.key) { mutableStateOf(false) }

    /**
     * The parent this field's current choice was made under — see [dwCascadeClearedMessage].
     *
     * SEEDED FROM THE FIRST COMPOSITION'S PARENT AND NOT FROM BLANK, which is the whole of the
     * false-positive guard. A saved row arrives with the artisan and the product both stored and
     * agreeing; a draft rehydrating from disk and a stage re-read do the same. Starting at `""` would
     * read every one of those as "the parent just changed" and clear a link the designer made a
     * fortnight ago, on a form they have only opened. The browser's effect seeds its own `lastFilter`
     * ref identically and says so.
     */
    val lastParentId = remember(field.key) { mutableStateOf(parentId) }

    /**
     * DROP A CHILD ITS PARENT NO LONGER ADMITS — the handset's counterpart to the browser's clear.
     *
     * Keyed on [parentId] ALONE, deliberately. Including the child's value would re-run this on the
     * clear it has just performed; the browser's effect excludes it from its dependency list for the
     * same reason and with the same comment.
     *
     * THE ID IS ALL THIS CLEARS, and the boxes it filled in are left standing for the save to settle.
     * That is not an oversight and it is not the browser's rule being copied blind: those boxes may
     * hold a spelling the designer corrected by hand, [hydrationPatch] cannot tell that from a value
     * it wrote, and the SERVER can — `design_workshops._clear_cascade_orphans` sees `previous`
     * alongside the incoming row and pops exactly the mapping's own targets when a re-pointed parent
     * is about to rewrite one of them. One writer for that decision, on the side that has the
     * evidence, and both clients get it identically. [dwCascadeClearedMessage] says so on the row
     * rather than letting the values change under the designer without explanation.
     *
     * NO PROMPT, matching the browser: the embedded record form below is re-keyed by this clear too,
     * and the press that moved the parent has already asked about every registered form innermost
     * first. Asking again here would be a second prompt for one act.
     */
    LaunchedEffect(parentId) {
        val moved = lastParentId.value != parentId
        // Recorded whether or not anything is cleared: the NEXT change has to be measured against the
        // parent on the row now, not against the one this field was first composed under.
        lastParentId.value = parentId
        if (!dwCascadeClearsChild(field.refFilterBy, moved, selectedId)) return@LaunchedEffect
        onChange(null)
        // A create still waiting to be described was made under the OLD parent, and the refresh
        // landing a moment later would fill this row in from a record the cascade has just ruled
        // out. `choose("")` supersedes it on the same grounds.
        pendingHydration = ""
        cascadeCleared = true
    }

    /**
     * Fill the row in from a record created here, once the server has described it.
     *
     * Runs the SAME [hydrationPatch] the picker's own `onSelect` runs, with the same [lastHydration]
     * bookkeeping, so an inline-created artisan lands on the row identically to one chosen from the
     * list — which is the requirement.
     *
     * IT NEVER TIMES OUT, and the temptation to give up after one refresh has to be resisted. The
     * repository answers a reference request TWICE — the cached list first, the network's a moment
     * later — and the cached one cannot possibly contain a record made two seconds ago. Anything that
     * disarmed this on the first empty answer would disarm it on the cache, every time, and the fresh
     * list carrying the record would arrive to find nothing waiting for it. So it stays armed until
     * the record is described or the designer does something that supersedes it: picking another
     * record, clearing the link, or leaving the stage.
     */
    LaunchedEffect(list, pendingHydration) {
        val awaited = pendingHydration
        if (awaited.isBlank()) return@LaunchedEffect
        val option = list?.items?.firstOrNull { it.id == awaited } ?: return@LaunchedEffect
        val incoming = hydratedValues(option, field.refHydration, writableFields)
        val patch = hydrationPatch(incoming, lastHydration, rowValues, writableFields)
        lastHydration = incoming
        pendingHydration = ""
        if (patch.isNotEmpty()) onHydrate(patch)
    }

    val options = remember(list, parentId, selectedId, locallyCreated, scannedOption) {
        val narrowed = list?.narrowedTo(parentId).orEmpty()
        // The server's copy WINS over ours the moment it exists: it carries the real hint and the
        // real label, and two rows for one record is a picker showing an artisan twice. The same
        // rule settles a scanned record that the list has since caught up with, and `distinctBy`
        // settles the case where the scan resolved a record this picker had also just created.
        val known = narrowed.map { it.id }.toSet()
        // The scanned stand-in only stands in on the row it was resolved for — see [DwScannedAside].
        val scanned = scannedOption?.takeIf { it.underParent == parentId }?.option
        val aside = (listOfNotNull(scanned) + locallyCreated).distinctBy { it.id }
        buildReferenceOptions(aside.filterNot { it.id in known } + narrowed, selectedId)
    }

    /** The word for this model, or null when it is not one a picker may create — see [INLINE_CREATABLE]. */
    val noun = INLINE_CREATABLE[field.refModel]
    val inlineHost = bridge?.inlineRecords?.takeIf { noun != null && enabled }

    /**
     * What this picker already knows about a record created from it — see [DwInlineSeed].
     *
     * The name is read out of THIS ROW through the PARENT field's own hydration mapping, which is the
     * same dictionary the picker above used to write it there. Asking the mapping rather than
     * guessing a key is what keeps this from inventing a second, client-side copy of the reference
     * models — the mistake [pendingHydration]'s KDoc records in full.
     */
    fun inlineSeed(): DwInlineSeed {
        if (parentField?.refModel != "Artisan" || parentId.isBlank()) return DwInlineSeed.NONE
        val nameKey = parentField.refHydration.entries.firstOrNull { it.key == "name" }?.value
        val name = nameKey?.let { DwValues.text(rowValues[it]).trim() }.orEmpty()
        return DwInlineSeed(artisanId = parentId, artisanName = name)
    }

    /**
     * Open the record form, and take up whatever it reports.
     *
     * Rebuilt on every composition rather than remembered, so the closure it hands the host reads
     * this composition's [rowValues] and [selectedId] rather than a snapshot from some earlier one.
     * That matters for the hydration bookkeeping: a stale `lastHydration` would decide it may
     * overwrite a value the designer has since typed by hand.
     */
    fun openInlineRecord(recordId: String?) {
        val host = inlineHost ?: return
        // NOTHING IS SEEDED INTO AN EDIT. The record already has its own answers, and a seed landing
        // on top of them would silently re-file somebody else's product under this row's artisan.
        host.open(field.refModel, recordId, if (recordId == null) inlineSeed() else DwInlineSeed.NONE) { outcome ->
            // Backed out. Nothing was written, so nothing here may change — least of all the field,
            // which at this point still holds a perfectly good link.
            if (!outcome.saved) return@open
            // A create OR an edit changes what the server would serve for this list, and an edit is
            // the case that would otherwise leave a corrected name showing its old spelling.
            refreshTick++
            val newId = outcome.createdId
            if (newId.isBlank()) return@open
            locallyCreated = listOf(
                DwReferenceOption(
                    id = newId,
                    label = outcome.createdLabel,
                    hint = outcome.createdHint,
                    // STILL LEFT BLANK, though the reason has narrowed. It used to be "asserting a
                    // parent this picker never saw the form choose would be a claim about whose
                    // product it is"; the picker now DOES offer the parent, visibly, as a seed (see
                    // [DwInlineSeed]) — but a seed is a DEFAULT and the designer may have changed it
                    // in the form, and this option is built from an outcome that reports the id, the
                    // label and the hint and not the artisan finally saved. `narrowedTo` keeps an
                    // option with no filter value — its own comment says why — so blank shows the
                    // record in a cascading list until the refreshed list arrives carrying the
                    // server's own answer, which is the only authority on what it was filed under.
                    filterValue = "",
                )
            ) + locallyCreated.filterNot { it.id == newId }
            onChange(JsonPrimitive(newId))
            pendingHydration = newId
        }
    }

    /**
     * TAKE UP A RECORD — the one path onto this field, whoever asked for it.
     *
     * The dropdown's `onSelect` calls this, and so does the scan panel. That is the requirement and
     * not a tidy-up: hydration, the `lastHydration` mis-pick repair and the deferred-hydration wait
     * are three interlocking rules, and a second copy of them for the scanned path would be a second
     * set of answers about what a chosen record may overwrite.
     *
     * [known] is the record's own option when the caller already holds it — a scan resolves one by
     * id, and it will not be in [list] if the server found it outside what this device has fetched.
     * Passing it hydrates the row NOW instead of parking the id in [pendingHydration] and waiting for
     * a list refresh that, on the handset this feature is for, may be a village and a week away.
     *
     * Declared inside the composable and rebuilt every composition rather than remembered, so it
     * reads THIS composition's [rowValues] and [lastHydration]. A stale pair would decide it may
     * overwrite a value the designer has since typed by hand.
     */
    fun choose(chosen: String, known: DwReferenceOption? = null) {
        // The designer has answered the cascade — by picking from the new list or by clearing the
        // field themselves — so the sentence explaining the empty box has been read and is retired.
        cascadeCleared = false
        if (chosen.isBlank()) {
            onChange(null)
            // Unlinking supersedes a create still waiting to be described. Left armed, the refresh
            // landing a moment later would fill the row in from a record the designer has just
            // deliberately unlinked.
            pendingHydration = ""
            return
        }
        onChange(JsonPrimitive(chosen))
        val option = known ?: list?.items?.firstOrNull { it.id == chosen }
        if (option == null) {
            /*
             * A record this device holds an id for but no DESCRIPTION of — one created here seconds
             * ago, or one another handset made and this cache has never seen.
             *
             * Hydration is deferred rather than run, and running it would be actively destructive:
             * with an empty `data` map [hydrationPatch]'s incoming set is empty, so its second loop
             * CLEARS every key the previous pick wrote and puts nothing in their place. The designer
             * would watch the name and village they could see vanish. Waiting leaves the row exactly
             * as it is until the server can say what belongs on it.
             */
            pendingHydration = chosen
            return
        }
        // A pick supersedes a create still waiting to be filled in. Without this, hydration from the
        // record made a moment ago would land on the row AFTER the designer changed their mind and
        // chose somebody else — one artisan's village under another's name.
        pendingHydration = ""
        val incoming = hydratedValues(option, field.refHydration, writableFields)
        val patch = hydrationPatch(incoming, lastHydration, rowValues, writableFields)
        lastHydration = incoming
        if (patch.isNotEmpty()) onHydrate(patch)
    }

    /** The kind of card this field can be filled from, or null when none is printed for its model. */
    val scannable = remember(field.refModel) { dwScannableRecordType(field.refModel) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (field.help.isNotBlank()) {
            Text(field.help, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }

        if (needsParent) {
            // Named, not hinted. "Choose an artisan first" with no artisan named is a sentence a
            // designer reads three times looking for the control it means.
            Text(
                "Choose ${parentField?.label ?: "the field above"} first — this list narrows to that " +
                    "record, and picking from an unfiltered list is how the wrong one gets linked.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        SearchableSelectField(
            label = label,
            options = options,
            selectedValue = selectedId,
            enabled = enabled && !needsParent && bridge != null,
            includeNone = true,
            placeholder = when {
                needsParent -> "Waiting for ${parentField?.label ?: "the field above"}"
                options.isEmpty() -> "No records on this device yet"
                else -> "Search and choose"
            },
            // Withheld while the cascade is unanswered, and that is not tidiness. The product form
            // opened from an "existing products" row would be a product created against no artisan at
            // all, which is the wrong record made from the right instinct.
            createAction = if (noun != null && inlineHost != null && !needsParent) {
                SelectCreateAction("Create a new $noun") { openInlineRecord(null) }
            } else {
                null
            },
            onSelect = { chosen -> choose(chosen) }
        )

        // A polite live region rather than a toast, for the reason the scan panel's refusals give:
        // this is something the designer has to act on, and a message that leaves after four seconds
        // leaves an unexplained empty box behind it.
        if (cascadeCleared) {
            Text(
                dwCascadeClearedMessage(parentField?.label.orEmpty()),
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        /*
         * THE SAME PICK, FROM A CARD IN THE DESIGNER'S HAND.
         *
         * Under the dropdown rather than over it, because the dropdown is the route that always
         * works and this one is the shortcut when it applies — the same order `RecordCodeLookupPanel`
         * argues for from the opposite side, where the scanner leads because the panel exists for
         * codes and nothing else.
         *
         * Withheld while a cascade is unanswered, exactly as "Create a new product" is: a scan that
         * ran before the artisan was chosen would be judged against an unfiltered list, which is the
         * wrong-join-key failure this file's own header calls materially worse than a missing one.
         * Withheld on a disabled field for the ordinary reason, and withheld entirely when the model
         * has no printed card — see [dwScannableRecordType].
         */
        if (scannable != null && bridge != null && enabled && !needsParent) {
            DwReferenceScanPanel(
                field = field,
                bridge = bridge,
                wanted = scannable,
                list = list,
                parentId = parentId,
                // Named only where there IS a cascade, so the refusal that mentions it is never shown
                // on a field that does not have one — see [dwUnresolvedScanMessage].
                cascadeLabel = if (field.refFilterBy.isNotBlank()) parentField?.label.orEmpty() else "",
                enabled = enabled,
                onPick = { option ->
                    scannedOption = DwScannedAside(option, parentId)
                    choose(option.id, option)
                },
            )
        }

        /*
         * FIX THE RECORD FROM HERE TOO, without giving up the stage.
         *
         * Noticing that the artisan's village is wrong while filling stage 13 is the common case, and
         * the only remedy used to be to leave. The record is loaded by the form from the server rather
         * than seeded from this picker's option — an option carries a label and a hint and nothing
         * else, and a form seeded from that would blank every field it does not hold and then save the
         * blanks.
         *
         * Offered only for a link that exists, which includes one this device cannot name: an id
         * cached on the row from another handset is exactly the record a designer wants to look at.
         */
        if (noun != null && inlineHost != null && selectedId.isNotBlank()) {
            TextButton(
                onClick = { openInlineRecord(selectedId) },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                modifier = Modifier.heightIn(min = 40.dp)
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text("Edit this $noun", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }

        /*
         * The crafts register, named on the one picker that cannot offer to create.
         *
         * WHILE NOTHING IS LINKED, and not only while the LIST is empty. A list with rows in it and
         * not the craft the designer is looking at is the same dead end as an empty one — it is the
         * misspelt-craft case the web branch names — and once a craft IS linked the sentence is
         * noise. `selectedId` is therefore the right condition and `options.isEmpty()` is not.
         *
         * A sentence and not a button: see [INLINE_CREATABLE], which refuses the button on two
         * independent grounds, either of which would have to be answered on BOTH clients at once.
         */
        if (field.refModel == "Craft" && selectedId.isBlank()) {
            Text(
                dwCraftRegisterNote(field, writableFields),
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        ReferenceProvenance(list = list, truncated = truncated, bridge = bridge, visible = !needsParent)
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

/**
 * The multi-select over the same lists.
 *
 * The one the requirement names outright — "for a particular workshop, the multiselect dropdown of
 * artisans" — and it is reached by a MULTI_ENUM field that carries a `refModel` instead of an
 * `enum`. Doing it that way rather than inventing a REF_LIST field type is what keeps the phone from
 * needing a registry the server does not serve: a MULTI_ENUM stores a JSON array of tokens either
 * way, so the server validates, stores and prints it with no change at all, and the only difference
 * is where the tokens' labels come from.
 *
 * Nothing is hydrated from a multi-select, and that is not an omission. Hydration writes one record's
 * values into the row; nine artisans have nine names, and there is no field on the row for them to
 * go into. The ids are the answer.
 *
 * ── AND THERE IS NO CARD READER ON IT, WHICH IS A SCOPE BOUNDARY AND NOT A JUDGEMENT ─────────
 *
 * A roster is the likeliest place in the app to be holding a card — it is the control a designer
 * opens to tick everyone in the room — so the absence is worth stating rather than leaving to be
 * discovered. Two facts stopped it landing in the same change as the single picker's:
 *
 *  · THE SHIPPED REGISTRY DECLARES NO SUCH FIELD. Every `refModel` in
 *    `app/src/main/assets/design-workshop-schema.json` is on a `REF`, so this composable is
 *    unreachable from the registry this APK carries; a reader added here could not be exercised by
 *    anything, on any surface, and an untested reader on the roster is worse than none.
 *  · THE BROWSER HAS NO ROSTER READER EITHER. `StageReferenceField.tsx` mounts `WorkshopCodeScanner`
 *    inside `StageReferenceSelect` and nowhere else — its own `StageReferenceMultiPicker` carries
 *    none — and a control that exists on one client and not the other is the parity failure
 *    [INLINE_CREATABLE] is written to prevent.
 *
 * If a MULTI_ENUM ever does carry a `refModel`, the pieces are already shared and none of them is
 * single-select-specific: [dwScannableRecordType] gates it, [dwScanLocalStep] and
 * [dwScanServerAnswer] decide it, and the pick becomes one more id in the array — the same commit
 * `createAction` below already makes. Do it in the same change that adds the field, and add the
 * browser's half with it.
 */
@Composable
internal fun DwReferenceMultiSelectField(
    field: FieldDto,
    value: JsonElement?,
    parentField: FieldDto?,
    parentValue: JsonElement?,
    bridge: DwReferenceBridge?,
    enabled: Boolean,
    error: String?,
    label: String,
    onChange: (JsonElement?) -> Unit,
) {
    val context = LocalContext.current
    val selected = remember(value) { DwValues.list(value).toSet() }
    val parentId = remember(parentValue) { dwRefId(parentValue) }
    val needsParent = field.refFilterBy.isNotBlank() && parentId.isBlank()

    var list by remember(field.key) { mutableStateOf<DwReferenceList?>(null) }
    var truncated by remember(field.key) { mutableStateOf(false) }
    /** Records made from inside this picker. Same job, same reason, as the single-select's copy. */
    var locallyCreated by remember(field.key) { mutableStateOf<List<DwReferenceOption>>(emptyList()) }
    var refreshTick by remember(field.key) { mutableIntStateOf(0) }

    LaunchedEffect(field.refModel, field.refScope, parentId, bridge?.workshopId, needsParent, refreshTick) {
        if (bridge == null || field.refModel.isBlank() || needsParent) return@LaunchedEffect
        bridge.repository.designWorkshopReferences(
            context = context,
            workshopId = bridge.workshopId,
            model = field.refModel,
            scope = field.refScope,
            filterValue = parentId,
        ) { fetched, wasTruncated ->
            list = fetched
            truncated = wasTruncated
        }
    }

    val options = remember(list, parentId, selected, locallyCreated) {
        val fetchedIds = list?.items.orEmpty().map { it.id }.toSet()
        val narrowed = locallyCreated.filterNot { it.id in fetchedIds } + list?.narrowedTo(parentId).orEmpty()
        // Every id ALREADY STORED gets an option even when the list no longer holds it, so a
        // selection made last week survives being reopened on a device whose cache has moved on.
        // Without this the multi-select would silently show eight of nine chosen artisans, and the
        // first edit would write the eight back and drop the ninth.
        val known = narrowed.map { it.id }.toSet()
        buildReferenceOptions(narrowed, "") +
            selected.filterNot { it in known }.map { orphan ->
                SelectOption(orphan, orphanLabel(orphan), "on this row, not in this device's list")
            }
    }

    val noun = INLINE_CREATABLE[field.refModel]
    val inlineHost = bridge?.inlineRecords?.takeIf { noun != null && enabled && !needsParent }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (field.help.isNotBlank()) {
            Text(field.help, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
        if (needsParent) {
            Text(
                "Choose ${parentField?.label ?: "the field above"} first.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }
        SearchableMultiSelectField(
            label = label,
            options = options,
            selected = selected,
            enabled = enabled && !needsParent && bridge != null,
            emptyMessage = if (bridge == null) {
                "This list is available once the workshop is open for editing."
            } else {
                "No records for this on the device yet. Connect once and reopen this stage."
            },
            /*
             * THE SAME ESCAPE THE SINGLE PICKER HAS, and this is the likeliest place of all to need
             * it: this is the ROSTER — the control a designer opens to tick everyone in the room —
             * so it is where they most often discover that one of them has no record yet.
             *
             * The new record is COMMITTED to the field rather than left ticked for a later "Done",
             * which is where this parts company with the web's roster picker. There, a tick makes a
             * ROW of a collection and committing one the designer had not confirmed would be an
             * invention. Here the field holds a JSON array of ids, so adding one id IS what the tick
             * plus Done would have produced — and the sheet has been dismissed to open the form, so
             * a draft selection held inside it no longer exists to add to.
             */
            createAction = if (noun != null && inlineHost != null) {
                SelectCreateAction("Create a new $noun") {
                    // NOTHING TO SEED FROM A MULTI-SELECT, and the web's roster picker seeds nothing
                    // either. This control has no `rowValues` and its cascade — where it has one at
                    // all — is the roster's, whose ids are `DwParticipant` entries and not artisans:
                    // the very case [DwInlineSeed] refuses to guess at.
                    inlineHost.open(field.refModel, null, DwInlineSeed.NONE) { outcome ->
                        if (!outcome.saved) return@open
                        refreshTick++
                        val newId = outcome.createdId
                        if (newId.isBlank()) return@open
                        locallyCreated = listOf(
                            DwReferenceOption(id = newId, label = outcome.createdLabel, hint = outcome.createdHint)
                        ) + locallyCreated.filterNot { it.id == newId }
                        // Appended, not re-sorted into the list's order the way `onSelectedChange`
                        // below does. There is no fetched list holding it yet to take an order from,
                        // and the next pass through the sheet puts it in its place anyway.
                        onChange(DwValues.ofList((selected + newId).toList()))
                    }
                }
            } else {
                null
            },
            onSelectedChange = { chosen ->
                // Written back in the LIST's order rather than the tick order, so two designers who
                // chose the same three artisans produce the same stored array and the report's list
                // does not change shape between workshops.
                onChange(DwValues.ofList(options.map { it.value }.filter { it in chosen }))
            }
        )
        ReferenceProvenance(list = list, truncated = truncated, bridge = bridge, visible = !needsParent)
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

/**
 * How old the list is, and whether it is all of it.
 *
 * Both facts change what a designer should do about a name they cannot find. A list last refreshed
 * an hour ago that does not contain Ram Kumar means Ram Kumar has no artisan record and one should
 * be created; the same list refreshed nine days ago means nothing of the kind. Leaving them to guess
 * is how a duplicate artisan record gets made in the field.
 */
@Composable
private fun ReferenceProvenance(
    list: DwReferenceList?,
    truncated: Boolean,
    bridge: DwReferenceBridge?,
    visible: Boolean,
) {
    if (!visible) return
    if (bridge == null) {
        Text(
            "Records can be linked once this workshop is open for editing.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
        return
    }
    if (list == null) {
        Text(
            "This device has not downloaded this list yet. Connect once and reopen the stage; " +
                "until then the link cannot be made here.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
        return
    }
    val age = friendlyAge(list.fetchedAt)
    Text(
        buildString {
            append("${list.items.size} record${if (list.items.size == 1) "" else "s"} on this device")
            if (age != null) append(", last refreshed $age")
            append(".")
            if (bridge.workshopId == null) {
                append(" This workshop has not been sent to the server, so the list cannot refresh yet.")
            }
        },
        color = MaterialTheme.field.muted,
        fontSize = 11.sp
    )
    if (truncated) {
        Text(
            "The server sent only part of this list. Search narrows what you can see here, not what " +
                "was sent — if the record you want is missing, refresh with a connection before " +
                "concluding it does not exist.",
            color = MaterialTheme.colorScheme.error,
            fontSize = 11.sp
        )
    }
}

// --------------------------------------------------------------------------------------
// Scanning a card into the picker
// --------------------------------------------------------------------------------------

/**
 * The kind of card that can be scanned into a field linking [refModel], or null when none can.
 *
 * THE MAP IS THE INTERSECTION OF TWO TABLES THIS FILE DOES NOT OWN, and it is written out entry by
 * entry rather than derived, because neither table is derivable from the other:
 *
 *  · `REFERENCE_MODELS` in `backend/app/services/design_workshops.py` — Artisan,
 *    ProductDocumentation, ToolDocumentation, Process, Craft — is every repository model a REF field
 *    may name. A `refModel` outside it is a `Dw…` entity of this very workshop, answered by
 *    `_in_record_options` from the workshop's own rows.
 *  · [DwWorkshopRecordType] is every kind of record the code grammar has a letter for, which is what
 *    decides whether a card exists to be scanned in the first place.
 *
 * SO THE ABSENCES ARE THE INTERESTING HALF. `DwSketch`, `DwParticipant`, `DwCostSheet` and
 * `DwFinalProduct` have no letter, so nothing anywhere prints a card for them and a scan control on
 * those pickers could only ever refuse; `DwPrototype` DOES have one — a prototype tag is the thing
 * `WorkshopCodesScreen` prints thirty of on the morning a workshop starts — and it resolves through
 * the same endpoint, which matches a stage row on its `_clientKey` as well as its id precisely so
 * that a tag printed before the row ever reached the server still reads back.
 *
 * `Workshop` IS DELIBERATELY ABSENT even though the grammar has a `W`, and so are `questionnaire`
 * and `media`. There is no `Workshop`, `QuestionnaireInterview` or `MediaFile` entry in
 * `REFERENCE_MODELS`, so no REF field can declare one, so a branch for any of them here would be a
 * claim about a picker that cannot exist. Add one in the same change that adds the reference model,
 * never before.
 *
 * ⚠ KEEP THIS IN EXACT STEP WITH `SCANNED_TYPE_REF_MODEL` in
 * `frontend/components/designworkshop/StageReferenceField.tsx`. A model whose picker takes a card in
 * the browser and refuses one on the handset is a designer who can finish a stage at a desk and not
 * in the courtyard the card is for — the same parity rule, for the same reason, as [INLINE_CREATABLE]
 * one screen up.
 */
internal fun dwScannableRecordType(refModel: String): DwWorkshopRecordType? = when (refModel) {
    "Artisan" -> DwWorkshopRecordType.ARTISAN
    "Craft" -> DwWorkshopRecordType.CRAFT
    "ProductDocumentation" -> DwWorkshopRecordType.PRODUCT
    "ToolDocumentation" -> DwWorkshopRecordType.TOOL
    "Process" -> DwWorkshopRecordType.PROCESS
    "DwPrototype" -> DwWorkshopRecordType.PROTOTYPE
    else -> null
}

/**
 * A scanned record held for the dropdown to name, TOGETHER WITH THE PARENT IT WAS RESOLVED UNDER.
 *
 * The pair and not the option alone, because in-scope is not a property of the record: a product
 * card resolves against this row's artisan, and the same card is out of scope the moment that artisan
 * is corrected. Storing the parent lets the stand-in be dropped from the list by the one comparison
 * the server would have made — see the field's own KDoc in [DwReferenceSelectField].
 */
@Immutable
private data class DwScannedAside(val option: DwReferenceOption, val underParent: String)

/** What the picker does about a code once there is nothing left to find out. */
internal sealed interface DwScanAnswer {
    /**
     * Choose this record, through the picker's ordinary selection path and no other.
     *
     * The whole option travels and not just its id, so hydration runs from the record's own `data`
     * immediately instead of going through `pendingHydration` and waiting for a list refresh that,
     * for an out-of-cache record, may be a village and a week away.
     */
    data class Pick(val option: DwReferenceOption) : DwScanAnswer

    /** Say this, and change nothing whatever on the row. */
    data class Refused(val message: String) : DwScanAnswer
}

/** How far the DEVICE ALONE could get with a code. */
internal sealed interface DwScanStep {
    data class Settled(val answer: DwScanAnswer) : DwScanStep

    /** The device holds no record for this id; only the repository can say what it names. */
    data class AskServer(val ref: DwWorkshopCodeRef) : DwScanStep
}

/** "a" or "an" for a noun about to be dropped into the middle of a refusal. */
private fun article(noun: String): String =
    if (noun.firstOrNull()?.lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

/**
 * WHAT THIS BOX HOLDS, in the word a refusal can put in a sentence.
 *
 * The record-type label wherever the model is one a code can name — the SAME word
 * [com.designprototype.workshop.data.workshopRecordTypeLabel] gives the two scanners that open
 * records, so a designer who has read "Product" on Search meets "product" here — and the field's own
 * label otherwise, because a `DwSketch` has no name in the code grammar at all and the box's label is
 * the only true thing left to call it. `pickerNoun` in `StageReferenceField.tsx` is the same rule.
 *
 * `lowercase()` and never `toLowerCase()`, whose DEFAULT locale maps `I` to a dotless ı on a handset
 * set to Turkish and would describe an "Interview" to a designer with a character the web never
 * writes. `unresolvedWorkshopCodeMessage` carries the same note for the same reason.
 */
internal fun dwPickerNoun(field: FieldDto): String =
    dwScannableRecordType(field.refModel)?.label?.lowercase() ?: field.label.lowercase()

/**
 * REFUSAL (a): THE CODE NAMES THE WRONG KIND OF RECORD. Null when it names the right one.
 *
 * ASKED BEFORE THE NETWORK, because there is nothing to ask — and it is therefore the one refusal a
 * phone with no signal can always give. An artisan's id looked up in the product table is not a near
 * miss; the empty answer would come back as "no product matches that code", which is true, useless,
 * and read by the person holding the card as a damaged tag. They would photograph it again.
 *
 * THE SENTENCE NAMES BOTH TYPES AND THE BOX, which is the whole of its value: the designer is holding
 * one card and looking at one field, and only the pair says which of the two is the mistake.
 *
 * The no-model branch is not reachable from this file's own control — [dwScannableRecordType] gates
 * it — and it is kept because the honest answer for a `Dw…` row is a different sentence, not a
 * crash: those rows carry no printed code at all, so there is no card to go and find.
 * `scanTypeRefusal` in `StageReferenceField.tsx` is the same function, branch for branch.
 */
internal fun dwWrongRecordTypeMessage(field: FieldDto, ref: DwWorkshopCodeRef): String? {
    val wanted = dwScannableRecordType(field.refModel)
    if (wanted == ref.recordType) return null
    val scanned = ref.recordType.label.lowercase()
    if (wanted == null) {
        return "That code names ${article(scanned)} $scanned. “${field.label}” holds a row recorded in " +
            "this design workshop, and rows of that kind carry no printed code — choose one from the " +
            "list instead."
    }
    val noun = dwPickerNoun(field)
    return "That code names ${article(scanned)} $scanned, and “${field.label}” takes " +
        "${article(noun)} $noun. Read the $noun’s own card or tag, or search for it in the list."
}

/**
 * REFUSAL (b): the record is real, and this WORKSHOP-scoped box still excludes it.
 *
 * THE NORMAL CASE FOR A SCANNED CARD, not an edge one: the WORKSHOP-scoped REF fields are scoped
 * against a repository model, and the card in the designer's hand was printed by whoever documented
 * the record — at their cluster, under their workshop. The server answers that with `outOfScope` and
 * hands the row over under its own key precisely so a client can say so.
 *
 * IT IS SAID, AND THE SCOPE IS NOT WIDENED. Offering the row would point a stage at a record this
 * picker's own list can never show, and the report's table would then cite work from another cluster
 * with nothing on screen having admitted it. So the row is NAMED — that is what tells the designer
 * the right card was read — and left unchosen, and the sentence carries the remedy.
 *
 * NAMING IT GIVES NOTHING AWAY. The server's probe carries the same read predicate the record list
 * routes carry, so a row this designer may not read produces no row at all and this sentence is
 * never reached for it; the flag is false for a forbidden record and for one that does not exist
 * alike. The words are `outOfScopeRefusal`'s in `StageReferenceField.tsx`, deliberately: two
 * surfaces reporting one server flag in two voices is how a designer comes to believe they are two
 * different situations.
 */
internal fun dwReferenceOutOfScopeMessage(option: DwReferenceOption): String =
    "That code names “${option.label}”, which is documented under a different workshop — and this " +
        "box offers only records linked to this design workshop’s workshop. Nothing on this row has " +
        "been changed. Link that record to this workshop and read the code again, or choose one of " +
        "the records that already belong to it."

/**
 * REFUSAL (c): ONE SENTENCE FOR "NO SUCH RECORD" AND FOR "NOT YOURS", AND IT MUST STAY ONE.
 *
 * The API answers 404 rather than 403 for a record the caller may not read, and the references
 * endpoint's by-id path composes the same read predicate for the same reason, so an absent record
 * and an unreadable one arrive as the identical empty answer. Do not add a branch that tells them
 * apart — a scanner that did would let somebody enumerate the repository one photographed card at a
 * time.
 *
 * NOT `unresolvedWorkshopCodeMessage`, AND THE DIFFERENCE IS ONLY THE REMEDY. That sentence sends
 * the reader to a SCREEN — "open the workshop that made it", "search for the artisan by name
 * instead" — which is right for the two scanners that open records and wrong for a box that fills a
 * row in. The RULE is what is carried over, not the words, exactly as `unresolvedRefusal` in
 * `StageReferenceField.tsx` sets out.
 *
 * THE CASCADE IS NAMED WHERE THERE IS ONE, and that gives nothing away: the filter is sent on every
 * request this picker makes, so the possibility is true of every code read at a cascaded box and the
 * sentence is the same for all of them. It has to be said, because the server's out-of-scope probe
 * KEEPS the artisan clause — a product that belongs to somebody else's artisan lands here and not in
 * refusal (b) — and "it may not be in the repository" would be a lie told about a record the designer
 * can see two rows up. This client cannot narrow it further: telling the two apart needs
 * `_artisan_id_behind`, the server-side rule [DwInlineSeed] records as the one the clients are
 * deliberately spared.
 */
internal fun dwUnresolvedScanMessage(field: FieldDto, cascadeLabel: String): String {
    val noun = dwPickerNoun(field)
    val reasons = listOfNotNull(
        "it may not be in the repository",
        "it may belong to work this account cannot open",
        cascadeLabel.takeIf { it.isNotBlank() }?.let { "it may not belong to the ${it.lowercase()} chosen on this row" },
    )
    return "No $noun this box can offer matches that code — ${reasons.joinToString(", or ")}. Nothing on " +
        "this row has been changed; search for it by name in the list instead."
}

/**
 * NO SIGNAL. A by-id resolve is the one thing on this control that cannot be answered locally.
 *
 * NOT ONE OF THE THREE REFUSALS, and it must not be mistaken for the third: "no record matches" and
 * "the repository could not be asked" send a designer to opposite places, and the second is the
 * NORMAL state of this app. So it says which it is, it says the card is fine — the check digit has
 * already agreed with the identifier by the time this is reached — and above all it says the row was
 * not touched, because the one thing a designer must never have to wonder after a failed read is
 * whether something was quietly linked anyway.
 */
internal const val DW_SCAN_OFFLINE_MESSAGE =
    "There is no connection, so the repository could not be asked which record that code names. The " +
        "code itself checked out, so the card is fine and nothing on this row has been changed — read " +
        "it again when there is signal, or search for the record by name in the list."

/**
 * The server answered, and not with an answer. Says what did NOT happen, which is the useful half.
 *
 * SPLIT FROM [DW_SCAN_OFFLINE_MESSAGE] ON RETROFIT'S OWN LINE: an `HttpException` means the server
 * was reached and then failed, and telling a designer their signal is at fault sends them out of the
 * building while the real bug wears an offline message. Same split, same reason, as
 * `RecordCodeLookup.lookUpRecordCode` and as the browser's `isUnreachable`.
 */
internal const val DW_SCAN_LOOKUP_FAILED_MESSAGE =
    "That code could not be looked up just now. Nothing on this row has been changed — try reading it " +
        "again, or search for the record by name in the list."

/**
 * The same situation, for a workshop the server has never heard of and so cannot be asked about.
 *
 * THE ONE ANSWER WITH NO COUNTERPART IN THE BROWSER, because the browser has no local-only workshop:
 * a design workshop created offline on this handset has no id the references endpoint would
 * recognise, so there is no request to fail and no signal that would help. Reporting it as "no
 * connection" would send a designer looking for a tower for a condition a tower cannot fix.
 */
internal const val DW_SCAN_UNSENT_WORKSHOP_MESSAGE =
    "That code checked out, but this workshop has not been sent to the server yet, so a code can only " +
        "be matched against the records already on this device — and this is not one of them. Nothing " +
        "on this row has been changed. Send the workshop when there is signal, then read it again."

/**
 * THE PARENT MOVED AND THE CHILD IT NARROWED IS NO LONGER OFFERABLE, so it is dropped and said.
 *
 * The counterpart of the browser's clear in `StageReferenceField.tsx`, and this client had NOTHING
 * in its place. The handset honoured the narrowing — it withholds the dropdown while the parent is
 * blank and sends `filterBy` so the server cannot offer another product's process — and then let the
 * already-chosen child stand when the parent changed underneath it. Pick product A, pick process P
 * of A, change the product to B, and `processRef` still held P: a stored pair the server does not
 * refuse (`coerce_value` checks type and length, never coherence) and `reference_options` would
 * never have OFFERED, with `hydrationPatch` rewriting `documentedFor` to B beside `name`,
 * `description` and the rest still copied from A's process. Nothing on screen said so.
 *
 * WHY IT WAS SURVIVABLE AND STOPPED BEING SO. The only cascade used to be
 * `existingProduct.artisanRef -> productRef` on a collection row, where the mismatch was one product
 * name in one cell. The same rule now governs stage 5's substantive narrative and the stored
 * `processRef` join key, so the same silence produces a paragraph about one product's process
 * printed under another's name.
 *
 * ── THE DECISION IS A PURE FUNCTION AND THE EFFECT IS THREE LINES ────────────────────────────────
 *
 * For the reason `DwReferenceScanTest`'s own header gives: everything worth pinning here is decided
 * before a pixel is drawn, and there is no Compose runtime in this module's unit tests. So the rule
 * lives in [dwCascadeClearsChild], which takes the declaration, whether the parent moved and what the
 * child holds — and the `LaunchedEffect` does nothing but observe the parent, call it, and act.
 *
 * THE "MOVED" ARGUMENT IS A COMPARISON THE CALLER MAKES, and it must be made against the parent this
 * field was LAST COMPOSED under rather than against a blank. A saved row arrives with parent and child
 * both stored and agreeing, and so does a draft rehydrating from disk or a stage being re-read;
 * starting from `""` would read every one of those as a change and clear a link made a fortnight ago
 * on a form the designer has only opened. The browser's effect seeds its own `lastFilter` ref
 * identically and says so.
 */
internal fun dwCascadeClearsChild(
    refFilterBy: String,
    parentMoved: Boolean,
    selectedId: String,
): Boolean = refFilterBy.isNotBlank() && parentMoved && selectedId.isNotBlank()

/**
 * The sentence that goes with the clear — see [dwCascadeClearsChild].
 *
 * IT SAYS WHAT WILL HAPPEN TO THE BOXES, and that last clause is the part a shorter message would
 * drop. The clear takes the ID and leaves the values, and the SAVE takes the values
 * (`design_workshops._clear_cascade_orphans`), so between the two the row shows a process's name with
 * no process linked. Saying so is what stops that reading as a bug the designer should work around by
 * retyping the boxes — which would defeat the clear, because a typed value is not a value hydration
 * may overwrite.
 */
internal fun dwCascadeClearedMessage(cascadeLabel: String): String {
    val moved = cascadeLabel.takeIf { it.isNotBlank() }
        ?.let { "The ${it.lowercase()} on this row" }
        ?: "The record this list narrows to"
    return "$moved changed, so the choice made under the previous one was cleared — this list now " +
        "holds only records filed under the new one. Pick from it; what the old choice filled in is " +
        "cleared when the row is saved."
}

/**
 * THE ROW MOVED WHILE THE LOOKUP WAS IN THE AIR, so the answer that came back is about a question
 * this row no longer asks.
 *
 * NOT TIDINESS, AND THE FIELD PATH IS WHY. The by-id resolve is the one thing on this control that
 * takes a round trip, and on the towers this app is built for that trip is seconds rather than
 * milliseconds. A designer who reads a product tag on a row naming artisan A and corrects the artisan
 * to B during those seconds would otherwise have A's product linked to B's row — and HYDRATED onto
 * it, so B's row would then carry A's product's measurements. That is one artisan's work under
 * another's name, which is the failure this file's header and the server's own `filter_by` clause
 * both exist to prevent. It is now the SECOND guard on that path rather than the only one —
 * [DwReferenceSelectField] clears the child when its parent changes, as the browser does — and it is
 * still needed, because the two catch different halves: the clear fires on the row moving, this
 * fires on an ANSWER arriving about a row that has already moved, and neither implies the other.
 *
 * IT COVERS EVERY ANSWER AND NOT ONLY THE PICK, which is where it differs in REACH — not in rule —
 * from `commitScan` in `StageReferenceField.tsx`, whose refusals are rendered by a separate scanner
 * dialog. Here one live region carries all of them, and a refusal computed against the artisan who
 * was on the row a moment ago would name the wrong record for the wrong reason. The two surfaces
 * agree on the thing that matters: a stale answer is dropped and SAID, never written.
 */
internal fun dwScanCascadeMovedMessage(cascadeLabel: String): String {
    val moved = cascadeLabel.takeIf { it.isNotBlank() }
        ?.let { "The ${it.lowercase()} on this row" }
        ?: "The record this list narrows to"
    return "$moved changed while that code was being looked up, so the answer that came back was " +
        "about the record chosen before it. Nothing on this row has been changed — read the code " +
        "again now that the row is settled."
}

/**
 * Everything a code can be judged on WITHOUT the network.
 *
 * The order is the whole of it. Decode first, because a payment QR photographed by mistake and a
 * mistyped character are refused by [decodeWorkshopCode] and by nothing here — a second opinion on
 * the grammar is how a scanned code and a typed one come to be judged differently. Then the record
 * TYPE, which needs nothing but the letter. Then this device's own cached list, which is the answer
 * in the courtyard: a card for a record the phone already holds is linked with no request at all,
 * hydrated from the same `data` the dropdown would have used.
 *
 * THE CACHE STEP IS THIS CLIENT'S OWN AND THE BROWSER HAS NO EQUIVALENT, which is the one place the
 * two surfaces differ in MECHANISM rather than in meaning. `StageReferenceField.tsx` fetches its list
 * every time the picker opens and resolves every scan against the server; this app's list is a
 * document on disk with no expiry, deliberately, because a designer in a courtyard with no signal
 * still has to be able to pick.
 *
 * ── AND IT IS TAKEN ONLY WHERE THE CACHE IS ACTUALLY AUTHORITATIVE ─────────────────────────────
 *
 * THE PREMISE THIS USED TO REST ON WAS FALSE FOR CASCADED FIELDS and is worth writing down, because
 * it reads as obviously true. It said: the cached list is the answer the server gave for this field's
 * own model, scope AND CASCADE, so a record in it is a record this field may offer. The first two
 * hold — [com.designprototype.workshop.data.DwReferenceStore.anyForModel] fences its merge to one
 * model and one owner. The cascade does not, twice over:
 *
 *  · `_reference_option` in `design_workshops.py` emits `id`, `label`, `sublabel` and `data` and
 *    NOTHING ELSE, so [DwReferenceOption.filterValue] is blank on every option that ever came off the
 *    wire — and [DwReferenceList.narrowedTo] KEEPS an option with no filter value (its own comment
 *    says why: dropping them would empty every cascading dropdown in the app). Narrowing here is
 *    therefore inert against real payloads, however honest it looks.
 *  · That fallback merge deliberately joins the model's files ACROSS FILTERS, which is what makes
 *    `productRef` work offline at all. So a WORKSHOP-scoped cascaded list legitimately holds artisan
 *    B's products while this row names artisan A.
 *
 * Together those two put artisan B's product card one silent tap from artisan A's row, with no
 * request made and therefore no `filterBy` for the server to refuse it on. So the cache answers a
 * cascaded box only where the narrowing is PROVEN — [DwReferenceList.filteredBy] naming this very
 * parent, which the repository sets only for a list the server itself answered under that filter, or
 * the option carrying the parent on its own back — and otherwise the code goes to the server, and to
 * [DW_SCAN_OFFLINE_MESSAGE] when there is no signal to reach it with. An uncascaded box is unaffected
 * and still answers from the cache, which is the courtyard case this step exists for.
 *
 * REFUSING TO GUESS IS THE CHEAP SIDE OF THIS TRADE. The worst outcome of going to the server is a
 * sentence saying the row was left alone; the worst outcome of guessing is a report that attributes
 * one artisan's work to another, which nothing downstream can detect.
 */
internal fun dwScanLocalStep(
    scanned: String,
    field: FieldDto,
    list: DwReferenceList?,
    parentId: String,
): DwScanStep {
    val ref = when (val decoded = decodeWorkshopCode(scanned)) {
        is DwDecodeResult.Refused -> return DwScanStep.Settled(DwScanAnswer.Refused(decoded.message))
        is DwDecodeResult.Ok -> decoded.ref
    }
    dwWrongRecordTypeMessage(field, ref)?.let {
        return DwScanStep.Settled(DwScanAnswer.Refused(it))
    }
    val cached = list?.narrowedTo(parentId)?.firstOrNull { it.id == ref.id }
    if (cached != null && dwCascadeIsProven(field, list, cached, parentId)) {
        return DwScanStep.Settled(DwScanAnswer.Pick(cached))
    }
    return DwScanStep.AskServer(ref)
}

/**
 * May a cached row be linked into THIS row without asking the server whether it belongs under it?
 *
 * Only where the answer is knowable on the device. A box with no `refFilterBy` has no cascade to
 * belong to and the fence above already settles model, scope and owner. A cascaded box needs one of
 * the two positive proofs, and the absence of a contradiction is not one of them: see
 * [dwScanLocalStep]'s own note on why the ordinary cached list is silent on the question rather than
 * agreeable about it.
 */
private fun dwCascadeIsProven(
    field: FieldDto,
    list: DwReferenceList,
    option: DwReferenceOption,
    parentId: String,
): Boolean {
    if (field.refFilterBy.isBlank()) return true
    // Unreachable from the panel, which is unmounted while the cascade is unanswered — and the safe
    // answer for a caller that is not the panel, since an unanswered cascade narrows nothing at all.
    if (parentId.isBlank()) return false
    // The option says so itself — nothing on the wire populates this today, and it is honoured so
    // that the day a server does, the device stops having to ask.
    if (option.filterValue == parentId) return true
    // Or the LIST says the server answered it under this very parent. The repository stamps this
    // only on an answer fetched under that filter; its merged-cache fallback deliberately leaves it
    // blank, because that branch merges across filters.
    return list.filteredBy == parentId
}

/**
 * What the server's answer to a by-id lookup means, as a row to choose or a sentence to read.
 *
 * THE ID ITSELF IS THE PROOF, wherever it is present: an option carrying the scanned id IS the record
 * on the card, and it reached `options` only by passing this field's scope and its cascade.
 *
 * A PROTOTYPE TAG LEGITIMATELY CARRIES AN ID THE OPTION DOES NOT, WHICH IS THE ONE CASE WITHOUT THAT
 * PROOF. `workshopCodeIdForRow` prints the row's client key while the row has never reached the server —
 * a tag has to be printable the afternoon the prototype is made, and a workshop can go a fortnight
 * without signal — and `_in_record_options` matches EITHER spelling while answering with the row's
 * server id. So the one option a narrowed answer holds IS the row that was read.
 *
 * `truncated` IS WHAT SAYS THE ANSWER WAS NARROWED, and it is why the request asks for a page of one.
 * An id clause matches at most one row, so a by-id answer can never honestly be truncated — an API
 * deployed before the by-id half does not refuse an unknown query parameter, it IGNORES it and
 * returns the ordinary list, and with `limit = 1` that shows up here as `truncated = true` the moment
 * the workshop holds a second prototype. Taking a row out of THAT list would tag the stage with
 * whatever sorts first: a wrong record chosen confidently, which is the failure a scanned identifier
 * exists to end. An old server plus a workshop holding exactly one prototype is the residual gap, and
 * it closes itself the moment the API is the one that answers `recordId`. Branch for branch, and for
 * the same reasons, this is `scanLookupOutcome` in `StageReferenceField.tsx`.
 */
internal fun dwScanServerAnswer(
    field: FieldDto,
    ref: DwWorkshopCodeRef,
    payload: DwReferenceResponseDto,
    cascadeLabel: String,
): DwScanAnswer {
    payload.options.firstOrNull { it.id == ref.id }?.let { return DwScanAnswer.Pick(it) }
    if (ref.recordType == DwWorkshopRecordType.PROTOTYPE && !payload.truncated && payload.options.size == 1) {
        return DwScanAnswer.Pick(payload.options.first())
    }
    // BOTH HALVES REQUIRED. The server derives the flag FROM the row, so they cannot disagree; a flag
    // with no row would be a client this build does not know how to render, and the sentence below is
    // the honest answer for that too.
    val excluded = payload.outOfScopeOption
    if (payload.outOfScope && excluded != null) {
        return DwScanAnswer.Refused(dwReferenceOutOfScopeMessage(excluded))
    }
    return DwScanAnswer.Refused(dwUnresolvedScanMessage(field, cascadeLabel))
}

/** What to say when a scan worked, for the designer who needs to see WHICH record it chose. */
private fun dwScanConfirmation(option: DwReferenceOption, wanted: DwWorkshopRecordType): String {
    val name = option.label.trim().takeIf { it.isNotEmpty() }
        ?: return "That code was read, and this row now links the ${wanted.label.lowercase()} it names."
    return "Linked from the code you read: “$name”."
}

/**
 * The scan control, the typed code, and the one sentence either of them produces.
 *
 * ── IT IS FOLDED AWAY UNTIL IT IS ASKED FOR, AND THAT IS A LAYOUT DECISION WITH A REASON ───────
 *
 * [DwQrScanControl] is two buttons and a paragraph, and the typed box below it is a field, a button
 * and two more lines. A stage row can carry four REF fields; unfolded under every one of them, that
 * is a form in which the pickers are the small print. A single labelled button is discoverable — it
 * says "Scan a card or tag" in words, on screen, next to the picker it fills — while costing one line
 * on the rows where nobody is holding a card.
 *
 * ── WHAT IT NEVER DOES ────────────────────────────────────────────────────────────
 *
 * It never writes the field itself. [onPick] is the picker's own `choose`, and every refusal path
 * returns without calling it, so a refused, unreadable or unanswerable code leaves the row exactly as
 * it was — including the link that is already on it, which a designer scanning a second card is
 * entitled to keep until a better one is found.
 *
 * It never asks the server twice for one press, and it never asks at all when the answer is already
 * on the device. [dwScanLocalStep] settles the code first.
 *
 * ── THE CONNECTIVITY CHECK IS BEFORE THE REQUEST AND NOT AFTER IT ─────────────────────────
 *
 * Both the check and the catch are here, and neither is redundant. `WorkshopRepositoryApi`'s own note
 * says why the check comes first: "a request that hangs for a two-minute timeout in a village is
 * indistinguishable, to the designer holding the phone, from an app that has crashed". The catch is
 * for everything the check cannot see — a tower that answers and then does not, a captive portal, a
 * 500 — and it does NOT flatten them: an [HttpException] means the server answered, which is a
 * different sentence and a different next action from no signal at all.
 */
@Composable
private fun DwReferenceScanPanel(
    field: FieldDto,
    bridge: DwReferenceBridge,
    wanted: DwWorkshopRecordType,
    list: DwReferenceList?,
    parentId: String,
    /** The label of the field this one cascades from, or blank when it cascades from nothing. */
    cascadeLabel: String,
    enabled: Boolean,
    onPick: (DwReferenceOption) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read through `rememberUpdatedState` because the resolve below outlives the press that started
    // it: a stale `onPick` would hand a record to a closure holding an earlier composition's row, and
    // the hydration bookkeeping inside `choose` decides from that row what it may overwrite.
    val pick = rememberUpdatedState(onPick)
    val currentList = rememberUpdatedState(list)
    val currentParent = rememberUpdatedState(parentId)

    var expanded by remember(field.key) { mutableStateOf(false) }
    var busy by remember(field.key) { mutableStateOf(false) }
    var typed by remember(field.key) { mutableStateOf("") }
    var refusal by remember(field.key) { mutableStateOf<String?>(null) }
    var confirmed by remember(field.key) { mutableStateOf<String?>(null) }

    fun settle(answer: DwScanAnswer) {
        when (answer) {
            is DwScanAnswer.Pick -> {
                confirmed = dwScanConfirmation(answer.option, wanted)
                pick.value(answer.option)
            }

            is DwScanAnswer.Refused -> refusal = answer.message
        }
    }

    fun resolve(input: String) {
        if (input.isBlank() || busy) return
        // Cleared BEFORE the work and not after it. The seconds a lookup takes are exactly when the
        // previous code's confirmation would otherwise still be sitting under this one's spinner,
        // reading as though it described the card now in the designer's hand.
        refusal = null
        confirmed = null
        when (val step = dwScanLocalStep(input, field, currentList.value, currentParent.value)) {
            is DwScanStep.Settled -> settle(step.answer)
            is DwScanStep.AskServer -> {
                val target = bridge.workshopId?.takeUnless { it.isBlank() || isLocalOnlyWorkshop(it) }
                if (target == null) {
                    refusal = DW_SCAN_UNSENT_WORKSHOP_MESSAGE
                    return
                }
                if (!bridge.repository.isOnline(context)) {
                    refusal = DW_SCAN_OFFLINE_MESSAGE
                    return
                }
                busy = true
                // READ ONCE, and spent on both the request and the check that lands with it. Reading
                // `currentParent` a second time would let the request and the guard below disagree
                // about which parent this answer was ever about.
                val askedUnder = currentParent.value
                scope.launch {
                    val answer = try {
                        val payload = bridge.repository.designWorkshopReferenceById(
                            workshopId = target,
                            model = field.refModel,
                            // The scope and the cascade travel WITH the id, never instead of it — the
                            // server needs the scope to have a workshop clause to lift, which is the
                            // only way `outOfScope` can ever be true, and it keeps the cascade so that
                            // a by-id answer cannot offer one artisan's work under another's name.
                            scope = field.refScope,
                            filterValue = askedUnder,
                            recordId = step.ref.id,
                        )
                        dwScanServerAnswer(field, step.ref, payload, cascadeLabel)
                    } catch (e: HttpException) {
                        // The server was REACHED and then failed. Blaming the signal would send a
                        // designer out of the building while the real bug wears an offline message.
                        DwScanAnswer.Refused(DW_SCAN_LOOKUP_FAILED_MESSAGE)
                    } catch (e: Exception) {
                        // Never reached at all: a timeout, a dropped tower, a captive portal. The
                        // connectivity check above catches most of these before the wait; this is
                        // everything it cannot see.
                        DwScanAnswer.Refused(DW_SCAN_OFFLINE_MESSAGE)
                    }
                    busy = false
                    /*
                     * WHATEVER THE ANSWER SAYS, IT IS SHOWN — even if the designer folded the panel
                     * away while it was in the air.
                     *
                     * The whole refusal vocabulary in this file rests on one promise: after reading a
                     * card the designer always learns what did or did not happen to the row. A
                     * collapse used to break it in the worst direction available — `settle` set a
                     * confirmation nothing was rendering and linked the record anyway, so the row
                     * acquired a link with nothing on screen naming it. Re-opening a panel somebody
                     * folded away is the small rudeness; a silent write is not.
                     */
                    expanded = true
                    /*
                     * THE CASCADE MAY HAVE MOVED UNDER THE REQUEST — see [dwScanCascadeMovedMessage].
                     *
                     * Checked HERE and not inside `settle`, which the cache path also uses and which
                     * cannot go stale: nothing suspends between reading the parent and answering from
                     * the device, so a guard there would be a claim about a race that path does not
                     * have.
                     */
                    if (askedUnder != currentParent.value) {
                        confirmed = null
                        refusal = dwScanCascadeMovedMessage(cascadeLabel)
                        return@launch
                    }
                    settle(answer)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(
            onClick = {
                expanded = !expanded
                // Folding it away clears what it said. A refusal about a card read a minute ago,
                // reappearing when the panel is opened for a different one, is a sentence about
                // nothing the designer is currently holding.
                if (!expanded) {
                    refusal = null
                    confirmed = null
                }
            },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            modifier = Modifier.heightIn(min = 40.dp),
        ) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                if (expanded) "Hide the code reader" else "Scan a card or tag",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
            )
        }

        if (expanded) {
            Text(
                "Read the code on the ${wanted.label.lowercase()}'s own card or tag to link that " +
                    "record here. Nothing about the record is inside the code: it holds a reference " +
                    "and a check digit, and nothing else.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            /*
             * THE LIVE CAMERA, ADDED 2026-08-28, AND ITS ABSENCE HERE WAS HALF THE REPORTED DEFECT.
             *
             * `ui/DwQrLiveScanner.kt`'s own header named this surface as the one that did not get the
             * live scanner and called the mount "the next wave's one-line change". It then stayed
             * that way, so this — the reference picker inside a stage, which is where a card is read
             * most often in a workshop — had exactly one camera route: `TakePicture()`, which hands
             * off to the SYSTEM camera app. That has no reticle, no region of interest and no live
             * detection at all: a camera is on and nothing is being scanned. A designer meeting only
             * this surface would report precisely the complaint of 2026-08-27, and would be right,
             * with no defect anywhere in the live scanner's own arithmetic.
             *
             * ABOVE the still control and not instead of it, on the reasoning both other surfaces
             * give: neither is a fallback for the other, and the picked-picture route is the only one
             * that can read a code somebody was SENT.
             */
            DwQrLiveScanControl(
                enabled = enabled && !busy,
                onText = { text ->
                    typed = text
                    resolve(text)
                },
                onRefusal = { message ->
                    confirmed = null
                    refusal = message
                },
            )
            DwQrScanControl(
                enabled = enabled && !busy,
                onText = { text ->
                    // Put IN THE BOX as well as resolved, so a designer who read the wrong card can
                    // see what was read and correct a character, rather than meeting a refusal about
                    // a string the app never showed them.
                    typed = text
                    resolve(text)
                },
                onRefusal = { message ->
                    confirmed = null
                    refusal = message
                },
            )
            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    refusal = null
                    confirmed = null
                },
                label = { Text("Record code") },
                // The letter this field's own kind of record is printed with, so the shape on screen
                // is the shape on the card in the designer's hand rather than a generic example.
                placeholder = { Text("DPW1 :${wanted.letter}: …") },
                singleLine = true,
                enabled = enabled && !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { resolve(typed) },
                    enabled = enabled && typed.isNotBlank() && !busy,
                    // The 48dp floor this app applies wherever a control was thought about — see
                    // ISLAND_TOUCH_TARGET in ui/AppNavigation.kt.
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Link this code")
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
            Text(
                "Spaces and capitals do not matter. The four characters at the end are a check — if " +
                    "they do not match, the app says so rather than linking the wrong record.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            // Polite live regions rather than a snackbar, for the reason `RecordCodeLookupPanel`
            // gives: a snackbar leaves after a few seconds and both outcomes here are things the
            // designer has to act on or check.
            refusal?.let {
                Text(
                    it,
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            confirmed?.let {
                Text(
                    it,
                    color = MaterialTheme.field.onSuccessContainer,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(MaterialTheme.field.successContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// --------------------------------------------------------------------------------------
// Plumbing
// --------------------------------------------------------------------------------------

/**
 * The dropdown rows, with the already-stored id guaranteed to be one of them.
 *
 * A row saved a week ago holds an id whose record may not be in this device's cache — a different
 * handset created it, or the cache was written before it existed. Without a synthetic option for it,
 * [SearchableSelectField] finds no match and draws the placeholder, so the designer sees an
 * apparently EMPTY required field over a perfectly good link. The natural repair is to pick
 * something, and that rewrites a correct join key with a different one.
 */
private fun buildReferenceOptions(items: List<DwReferenceOption>, selectedId: String): List<SelectOption> {
    val options = items.map { option ->
        SelectOption(
            value = option.id,
            label = option.label.ifBlank { orphanLabel(option.id) },
            hint = option.hint.takeIf { it.isNotBlank() }
        )
    }
    if (selectedId.isBlank() || options.any { it.value == selectedId }) return options
    return listOf(
        SelectOption(
            value = selectedId,
            label = com.designprototype.workshop.data.DwReferenceStore.labelFor(selectedId)
                ?: orphanLabel(selectedId),
            hint = "already linked on this row"
        )
    ) + options
}

/** A recognisable stand-in for an id whose record this device cannot name. Never a bare UUID. */
private fun orphanLabel(id: String): String = "Linked record ${id.take(8)}"

/**
 * The chosen record's values, resolved onto the BOXES they belong in.
 *
 * The record's `data` speaks the reference model's vocabulary and the row speaks the entity's, and
 * [FieldDto.refHydration] — served by the registry, never guessed here — is the dictionary between
 * them. An empty dictionary yields nothing, which is the fail-closed answer the server's own note
 * asks for: a box left blank costs one retype and is filled at save anyway; a box filled from the
 * wrong key costs a wrong value nobody can see is wrong.
 *
 * EVERY VALUE GOES THROUGH [DwValues.coerceHydrated] ON THE WAY, which is `hydrate_entries`' own
 * `coerce_value(target, value)` call restated here. It is what wraps the documented product's single
 * photograph into a GALLERY, what turns a plain string bound for a RICH_TEXT box into a document,
 * and — the case that matters most as the mapping widens — what DROPS a value the target field
 * cannot legally hold instead of writing it. Its KDoc sets out at length what writing an
 * out-of-vocabulary enum token cost on this surface: a red mark on a row the designer never touched,
 * over a value they cannot correct because the dropdown does not offer it either.
 *
 * A DROPPED VALUE IS A BLANK BOX, NEVER A CLEARED ONE. This function only ever reports what the new
 * record has to say; [hydrationPatch] decides what that does to the row, and its clearing rule keys
 * off what the PREVIOUS pick wrote. So a value refused here leaves whatever was in the box alone.
 *
 * ── NO UNIT CONVERSION HAPPENS HERE, AND NONE MAY EVER BE ADDED ─────────────────────────────────
 *
 * `ProductDocumentation` stores its measurements in INCHES and `existingProduct` declares its boxes
 * in CENTIMETRES, so somebody has to convert, and that somebody is `REFERENCE_MODELS[...].data` on
 * the server — the one place both clients read the number from. A conversion added on this side
 * would be applied on top of the server's, and the handset would report a product 2.54 times the
 * size the browser reports for the same record, in a document that goes to a ministry. The value in
 * `option.data` is already in the unit the target field declares; copy it, do not reason about it.
 */
internal fun hydratedValues(
    option: DwReferenceOption,
    mapping: Map<String, String>,
    writable: Map<String, FieldDto>,
): Map<String, JsonElement> {
    val out = LinkedHashMap<String, JsonElement>()
    mapping.forEach { (sourceKey, targetKey) ->
        if (targetKey.startsWith("_")) return@forEach
        val target = writable[targetKey] ?: return@forEach
        if (target.deprecated) return@forEach
        val raw = option.data[sourceKey] ?: return@forEach
        out[targetKey] = DwValues.coerceHydrated(target, raw) ?: return@forEach
    }
    return out
}

/**
 * Which of the chosen record's values may be written into the row.
 *
 * THREE RULES, and each one prevents a specific way of destroying an answer:
 *
 *  - A key whose value in the row is EMPTY is filled. That is the whole feature.
 *  - A key whose value equals what the PREVIOUS pick wrote is overwritten. This is the mis-pick
 *    repair: choose the wrong artisan, notice, choose the right one, and the wrong village goes with
 *    the wrong name instead of standing under it.
 *  - Anything else is left alone. A designer who corrected a transliterated name by hand must not
 *    have that correction silently reverted by a re-pick, and a reference record is not more
 *    authoritative than the person standing in front of the artisan.
 *
 * A LIST TARGET IS ONLY EVER SEEDED, NEVER REPLACED, which is the one place the rules differ from
 * the three above and is `hydrate_entries`' rule restated: the documented product's photograph is a
 * starting point for a gallery, and a re-pick that swapped it for another catalogue shot would
 * destroy the only copy of the photographs the designer took in the room.
 *
 * The incoming values arrive already resolved by [hydratedValues], so this stays a pure function of
 * the record and what we last wrote, which is the part that is testable.
 */
internal fun hydrationPatch(
    incoming: Map<String, JsonElement>,
    lastHydration: Map<String, JsonElement>,
    current: Map<String, JsonElement>,
    writable: Map<String, FieldDto>,
): Map<String, JsonElement?> {
    val patch = LinkedHashMap<String, JsonElement?>()

    incoming.forEach { (key, next) ->
        val existing = current[key]
        val multi = writable[key]?.let { DwFieldType.of(it.type).isMulti } ?: false
        val mayWrite = !DwValues.isFilled(existing) || (!multi && existing == lastHydration[key])
        if (!mayWrite) return@forEach
        patch[key] = next
    }

    // Whatever the PREVIOUS record filled in and this one has nothing to say about is cleared, but
    // only where it is still exactly what we wrote. Leaving it would attach the old artisan's phone
    // number to the new artisan's name — a fabricated record that looks entirely plausible, which is
    // the worst kind this form can produce.
    lastHydration.forEach { (key, written) ->
        if (key in incoming || key !in writable) return@forEach
        if (current[key] == written) patch[key] = null
    }
    return patch
}

/** "today", "3 days ago", "on 12 Mar" — never a timestamp, which nobody reads at a glance. */
private fun friendlyAge(iso: String): String? {
    if (iso.isBlank()) return null
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val then = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    val days = java.time.temporal.ChronoUnit.DAYS.between(then, today)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 14L -> "$days days ago"
        else -> "on " + then.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}
