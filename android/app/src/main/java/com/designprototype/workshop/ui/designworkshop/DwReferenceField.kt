package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwReferenceList
import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.dwRefId
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectCreateAction
import com.designprototype.workshop.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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

    val options = remember(list, parentId, selectedId, locallyCreated) {
        val narrowed = list?.narrowedTo(parentId).orEmpty()
        // The server's copy WINS over ours the moment it exists: it carries the real hint and the
        // real label, and two rows for one record is a picker showing an artisan twice.
        val known = narrowed.map { it.id }.toSet()
        buildReferenceOptions(locallyCreated.filterNot { it.id in known } + narrowed, selectedId)
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
            onSelect = { chosen ->
                if (chosen.isBlank()) {
                    onChange(null)
                    // Unlinking supersedes a create still waiting to be described. Left armed, the
                    // refresh landing a moment later would fill the row in from a record the designer
                    // has just deliberately unlinked.
                    pendingHydration = ""
                    return@SearchableSelectField
                }
                onChange(JsonPrimitive(chosen))
                val option = list?.items?.firstOrNull { it.id == chosen }
                if (option == null) {
                    /*
                     * A record this device holds an id for but no DESCRIPTION of — one created here
                     * seconds ago, or one another handset made and this cache has never seen.
                     *
                     * Hydration is deferred rather than run, and running it would be actively
                     * destructive: with an empty `data` map [hydrationPatch]'s incoming set is empty,
                     * so its second loop CLEARS every key the previous pick wrote and puts nothing in
                     * their place. The designer would watch the name and village they could see
                     * vanish. Waiting leaves the row exactly as it is until the server can say what
                     * belongs on it.
                     */
                    pendingHydration = chosen
                    return@SearchableSelectField
                }
                // A pick supersedes a create still waiting to be filled in. Without this, hydration
                // from the record made a moment ago would land on the row AFTER the designer changed
                // their mind and chose somebody else — one artisan's village under another's name.
                pendingHydration = ""
                val incoming = hydratedValues(option, field.refHydration, writableFields)
                val patch = hydrationPatch(incoming, lastHydration, rowValues, writableFields)
                lastHydration = incoming
                if (patch.isNotEmpty()) onHydrate(patch)
            }
        )

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
