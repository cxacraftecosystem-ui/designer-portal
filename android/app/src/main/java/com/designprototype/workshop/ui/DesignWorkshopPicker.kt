package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DesignWorkshopPageDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageSchemaStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopTypeOptionDto
import com.designprototype.workshop.data.loadAllottedDesignWorkshops
import com.designprototype.workshop.data.unfiledLinkReason
import kotlinx.coroutines.CancellationException

/*
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * TWO DROPDOWNS, NEVER THREE — the handset's half of `frontend/components/forms/WorkshopPicker.tsx`
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 *     "Type of workshop"   the administrator's list (`WorkshopTypeOption`, GET /workshop-types)
 *     "Workshop"           the workshops OF THAT TYPE, most recent first
 *
 * This file owns the FIRST box and the DesignWorkshop half of the second. The `Workshop` half is
 * `WorkshopField` in `MainActivity.kt` (it carries the submission pre-flight and the late-submission
 * dialog, which a design workshop has no equivalent of), and `RecordWorkshopField` in that same file
 * is the one composable a record form mounts — it draws the type box from here and then exactly one
 * of the two workshop boxes.
 *
 * ── WHAT THIS REPLACED, AND WHY THE THIRD BOX HAD TO GO ─────────────────────────────────────────
 *
 * Until this release a record form on this handset carried THREE controls for one question:
 * `WorkshopField` picking a `Workshop` and writing `workshopId`; a KIND box —
 * `stage_schema.ENUMS["WORKSHOP_KIND"]`, the vocabulary that answers *stage 1 of a design workshop's
 * own questionnaire* — and under it a second workshop box picking a `DesignWorkshop` and writing
 * `designWorkshopId`. The kind box saved nothing and its own hint said so out loud. So a designer
 * filing one tool met two boxes both called some kind of "workshop", whose difference is a schema
 * fact, plus a third that narrowed one of them and was stored nowhere.
 *
 * The owner's words: *"we do not need one separately for each of the type of the workshops"* and
 * *"Do not invent complexity."* There is now ONE name dropdown whose contents — and whose destination
 * column — depend on the type chosen above it. That decision is
 * [WorkshopTypeOptionDto.routesToDesignWorkshop], a per-row flag on the administrator's list, read
 * off the row and never off the key.
 *
 * ── THE TYPE IS NOT STORED ON THE RECORD, AND THAT SENTENCE IS WHY THE BOX IS READABLE ──────────
 *
 * No column holds it and none should. The workshop the designer picked already knows its own type —
 * `DesignWorkshop.workshopKind` is answered in stage 1, and a `Workshop` is an ordinary field
 * workshop by construction — so a second copy filed beside the record would be a denormalisation
 * that disagrees with its own source the first time somebody corrects that stage, with nothing ever
 * reading the two together to notice. The retired kind box said exactly this about itself and it was
 * the sentence that made that control understandable; it is said again under the new box, because
 * the box looks like something that is saved and is not.
 *
 * What DID change is that the type is no longer ONLY a lens. It decides WHERE the answer is written
 * (R3), so changing it is a real edit and arms the unsaved-changes guard. The lens/answer split
 * survives in the only place it ever mattered: what reaches a payload is a workshop id, never a type.
 *
 * ── THIS IS `WORKSHOP_KIND`'S NEIGHBOUR AND NOT ITS REPLACEMENT ─────────────────────────────────
 *
 * `backend/app/services/stage_schema.ENUMS["WORKSHOP_KIND"]` stays exactly where it is, untouched.
 * It answers *"what kind of design workshop is THIS one?"* inside a workshop's own 22-stage
 * questionnaire, and its `registry_version()` gates this handset's re-sync of the whole stage
 * registry. `WorkshopTypeOption` answers *"which list may a record form offer?"* — a question about
 * the shape of six forms, whose answer an administrator edits at runtime from `/admin/workshop-types`.
 * Two questions, two homes. The six rows were SEEDED with the registry's six keys so that every
 * `workshopKind` already stored resolves to a label, and nothing synchronises them afterwards.
 *
 * That seeding is also what makes [workshopTypeFloor] honest: see its note.
 *
 * ── THE LIST IS NOT NARROWED WITHIN THE TABLE, AND THAT IS THE MOST IMPORTANT LINE HERE ─────────
 *
 * R2 says the second box holds *"the workshops OF THAT TYPE"*, and on this data model THE TYPE'S
 * NARROWING **IS** THE CHOICE OF TABLE. It must not also be sent as a filter, in either direction,
 * and both halves of that were checked against the live database on 2026-09-16:
 *
 *  · `DesignWorkshop` holds 13,871 rows of which **24** carry `workshopKind =
 *    DESIGN_PROTOTYPE_DEVELOPMENT` and 13,847 carry NULL. Sending the type's key as `workshopKind`
 *    would therefore show a designer 24 workshops out of a register of thirteen thousand and say
 *    nothing about the rest — absence read as non-existence, in the one control least allowed to say
 *    it. The retired kind box needed an extra unnarrowed probe purely to stop the OUTBOX mislabelling
 *    that state; with the filter gone, so is the probe.
 *  · `Workshop` has **no per-type column at all**. Its `workshopType` enum carries only
 *    DESIGN_PROTOTYPE / OTHER and all 378 live rows are OTHER, so `?workshopType=DESIGN_PROTOTYPE`
 *    returns nothing. All five ordinary types therefore show the same `Workshop` list. That is a
 *    direct consequence of R1 (both tables stay, no data movement) and **must not be "fixed" by
 *    inventing a column** — the slice that built the type table is explicit about it.
 *
 * ── AND THE TWO HALVES ARE THE PICKERS THAT WERE ALREADY THERE ──────────────────────────────────
 *
 * Between them, `WorkshopField` and [DesignWorkshopField] carry the late-submission gate, the
 * pre-flight assignment warning, the four sentences that tell an empty list from a failed read from
 * an offline device, the capped-list notice with a real total, the "already on this record" recovery
 * of a workshop that is off the page, the stand-down rule for a list with nothing in it, and the
 * "not linked" row. Every one of those closed a defect that shipped. Nothing here re-derives them.
 */

// ═════════════════════════════════════════════════════════════════════════════════════════════════
// 1. THE TYPE VOCABULARY
// ═════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * The key of the one seeded type that routes at `DesignWorkshop`.
 *
 * **READ THE FLAG, DO NOT COMPARE AGAINST THIS.** It exists so a message can name the programme in
 * words, so [workshopTypeFloor] can author an offline row, and so a test can assert the seed. It is
 * NOT how a caller decides where to save: that is [WorkshopTypeOptionDto.routesToDesignWorkshop],
 * per row, because an administrator may add a second design-workshop-backed programme the day the
 * ministry announces one and no client should have to ship to learn about it. Nothing in the
 * database constrains "exactly one true row", deliberately.
 *
 * Byte-for-byte `DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY` in `frontend/lib/workshopTypes.ts`.
 */
internal const val DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY: String = "DESIGN_PROTOTYPE_DEVELOPMENT"

/**
 * The types this handset offers when `GET /workshop-types` has not answered.
 *
 * ── A LIST THAT DOES NOT ARRIVE IS A FLOOR, NOT A BROKEN FORM ───────────────────────────────────
 *
 * Without one, a record form opened in a courtyard with no signal would have an empty type box,
 * therefore no routing, therefore no workshop box worth reading — a form that cannot be filled in
 * because six labels did not load. That is the state this app is designed around, and it is the
 * ruling `WorkshopPicker.tsx` carries for the browser under the same heading.
 *
 * ── IT IS THE BUNDLED REGISTRY, WHICH IS WHAT THE SEED WAS MADE FROM ────────────────────────────
 *
 * The web's floor is `WORKSHOP_KIND_FLOOR`, a compiled-in array. This client has something better
 * and already resolved: [workshopKindOptions] over `StageSchemaStore`, which answers from memory,
 * then `filesDir`, then the APK asset, and RAISES rather than degrading to an empty registry — the
 * argument is written out at that function's own declaration and was re-verified link by link. The
 * six seeded rows carry the registry's six keys AND its six labels (migration
 * `20260916160000_workshop_type_options`; checked against `assets/design-workshop-schema.json` on
 * 2026-09-16, all six identical), so this is the same floor the browser has, read out of the file
 * the seed was written from rather than out of a second copy that can drift from it.
 *
 * ── THE ONE PLACE A KEY DECIDES ROUTING, AND WHY IT IS NOT THE BUG THE CONTRACT WARNS ABOUT ─────
 *
 * Every SERVED row is routed by its flag. A floor row has no server to read a flag from, and
 * somebody has to author it; the alternative — a floor with the flag false on all six — would
 * silently take the design-workshop branch off every offline record form, which is the branch a
 * designer standing in a courtyard most needs. So the key appears here, once, building a local
 * fallback, and the moment a served list arrives it is REPLACED WHOLE rather than merged into, so a
 * type an administrator has retired can never be resurrected by this function.
 *
 * `sortOrder` mirrors the seed's ordinals (10, 20, …) so a floor list and a served list order alike.
 */
internal fun workshopTypeFloor(schema: SchemaResponse?): List<WorkshopTypeOptionDto> =
    workshopKindOptions(schema).mapIndexed { index, option ->
        WorkshopTypeOptionDto(
            id = "floor:${option.value}",
            key = option.value,
            label = option.label,
            sortOrder = (index + 1) * 10,
            isActive = true,
            routesToDesignWorkshop = option.value == DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY,
        )
    }

/**
 * The server's own order, re-applied on the device.
 *
 * `sortOrder` is deliberately NOT unique on that table — a unique ordinal makes a swap a
 * three-statement dance through a temp value that half-completes on an interrupted request — so
 * `key` breaks the tie, exactly as the API's `ORDER BY "sortOrder", "key"` does. Without the
 * tiebreak two rows sharing a position would render in whatever order the wire happened to carry
 * them, and a dropdown whose rows move between two openings is a dropdown a designer re-reads.
 */
internal fun sortWorkshopTypes(types: List<WorkshopTypeOptionDto>): List<WorkshopTypeOptionDto> =
    types.sortedWith(compareBy({ it.sortOrder }, { it.key }))

/** The first ACTIVE type routing where the caller needs it, in the administrator's order, or null. */
internal fun firstTypeRouting(
    types: List<WorkshopTypeOptionDto>,
    toDesignWorkshop: Boolean,
): WorkshopTypeOptionDto? =
    sortWorkshopTypes(types.filter { it.isActive }).firstOrNull { it.routesToDesignWorkshop == toDesignWorkshop }

/**
 * Which type a NEW record opens on — R4, and `defaultWorkshopType` in `lib/workshopTypes.ts`.
 *
 * [preferDesignWorkshops] IS A CAPABILITY THE CALLER PASSES AND NOT A ROLE THIS FILE READS. The rule
 * is "a designer opens on Design & Prototype", and the predicate for it is
 * `FieldPermissions.canRunDesignWorkshops`, where every other one on this client lives. A copy here
 * would be a second place that decides what a designer is, and the two would disagree the first time
 * the ladder moved — which is what `test_role_ladder_parity.py` exists to stop.
 *
 * FALLS BACK TO THE FIRST TYPE rather than to null wherever it can, because the control is not
 * optional: a form that opened with no type selected would draw an empty workshop box, which reads
 * as *there are no workshops* rather than as *choose a type first*.
 */
internal fun defaultWorkshopType(
    types: List<WorkshopTypeOptionDto>,
    preferDesignWorkshops: Boolean,
): WorkshopTypeOptionDto? {
    val ordered = sortWorkshopTypes(types.filter { it.isActive })
    if (preferDesignWorkshops) {
        ordered.firstOrNull { it.routesToDesignWorkshop }?.let { return it }
    }
    return ordered.firstOrNull()
}

/**
 * Which column a record ALREADY names a workshop in, or null when it names none.
 *
 * `true` = `designWorkshopId`, `false` = `workshopId`, `null` = neither — and this is the most
 * load-bearing value in the file. Whenever it is not null the type box is pinned to a type with that
 * routing, so no amount of list-arriving, administrator-editing or default-computing can move where
 * an existing record saves. Only a PERSON can.
 *
 * Design first, and the order matters on exactly one kind of row: a legacy record carrying both
 * columns. There are none on this deployment — 0 of 774 artisans, 0 of 473 products, 0 of 92 tools,
 * 0 of 897 processes and 0 of 207 interviews hold both, measured 2026-09-16, which is the same count
 * `WorkshopPicker.tsx` records for the browser — and the design column is the narrower, grant-gated
 * one, so a row that somehow held both is opened on the link that is harder to have acquired by
 * accident.
 */
internal fun storedWorkshopRouting(workshopId: String?, designWorkshopId: String?): Boolean? = when {
    !designWorkshopId.isNullOrBlank() -> true
    !workshopId.isNullOrBlank() -> false
    else -> null
}

/**
 * THE TYPE THE BOX OPENS ON — the whole of the defaulting rule, as a pure function.
 *
 * Pure and re-read on every composition rather than written into state by an effect, and that shape
 * is the correctness argument rather than a preference. The alternative — seed state once when the
 * list arrives, guarded by a flag — has to answer *"what happens when a SECOND list arrives"*, which
 * happens on every single open (the floor is replaced by the served rows a moment later), and every
 * answer to that question is a branch that can move a filed record. Here the person's own choice
 * lives in [WorkshopTypePickerState.chosenKey] and, once set, this function is never consulted
 * again; until then the answer is a function of the list in hand and the record's stored routing, so
 * a later list can only ever correct the LABEL on the box and never the destination under it.
 *
 * ── AN EXISTING RECORD KEEPS THE WORKSHOP IT NAMES. THIS IS THE WHOLE POINT. ────────────────────
 *
 * When [storedRouting] is non-null the type is whichever ACTIVE type routes that way, so a product
 * filed last season under an ordinary workshop opens on the ordinary list with that workshop
 * selected, and one filed under a design workshop opens on the design list with that one selected.
 * [preferDesignWorkshops] is not consulted at all in that case: *"the most recent workshop the
 * account can reach"* is the default for a NEW record, and applying it to an existing one would
 * re-file historic records under whatever is newest — invisibly, because nothing on screen would say
 * a link had moved.
 *
 * ── AND IT NAMES THE PROGRAMME EVEN AFTER AN ADMINISTRATOR RETIRES IT ───────────────────────────
 *
 * If a record is filed under a design workshop and the administrator has since deactivated the one
 * type that routes there, [firstTypeRouting] answers null. Falling through to the ordinary list
 * would flip the routing and clear `designWorkshopId` on the next save — exactly the silent
 * re-filing this function exists to prevent — so the key is returned anyway,
 * [workshopTypeOptions] draws it as a row of its own, and [routesToDesignWorkshopFor] falls back to
 * the stored column. That is the ruling both workshop boxes already make one level down ("the
 * record's own workshop is always an option, however old it is"), applied to the box above them.
 */
internal fun openingWorkshopTypeKey(
    types: List<WorkshopTypeOptionDto>,
    storedRouting: Boolean?,
    preferDesignWorkshops: Boolean,
): String {
    if (storedRouting != null) {
        firstTypeRouting(types, storedRouting)?.let { return it.key }
        return if (storedRouting) DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY else ""
    }
    return defaultWorkshopType(types, preferDesignWorkshops)?.key.orEmpty()
}

/**
 * WHERE THE ANSWER GOES, READ OFF THE ROW AND NEVER OFF THE KEY.
 *
 * The fallback matters as much as the lookup. A key with no row in hand is either the floor being
 * replaced mid-composition or the retired-programme case above; in both, the record's own stored
 * column is a better authority than a guess. `false` is the last resort only when there is no stored
 * column either — a brand-new record on a list that has not arrived, where nothing can be mis-filed
 * because nothing is filed.
 */
internal fun routesToDesignWorkshopFor(
    types: List<WorkshopTypeOptionDto>,
    typeKey: String,
    storedRouting: Boolean?,
): Boolean = types.firstOrNull { it.key == typeKey }?.routesToDesignWorkshop ?: storedRouting ?: false

/**
 * The rows the type box draws: every ACTIVE type, plus the current one if it is not among them.
 *
 * THE MERGE IS THE REASON THIS IS A FUNCTION AND NOT A `map`. A picker whose `selectedValue` matches
 * no option draws its placeholder, and the documented consequence of a blank workshop control on
 * these forms is not a missing label — it is somebody repairing it by picking something else, which
 * re-files the record. Both workshop boxes below already recover the record's own WORKSHOP for that
 * exact reason (`offPageWorkshopRow`); this recovers the record's own PROGRAMME.
 *
 * The label of a row that is no longer served falls back to the floor's and then to the raw token —
 * never to "Unknown" and never to an empty string. That is `workshopTypeLabel`'s rule on the web and
 * `enum_label`'s on the server: a record filed under a type an administrator has deleted is still a
 * record with a type, and printing the token is the honest rendering of *this is what it says*.
 */
internal fun workshopTypeOptions(
    types: List<WorkshopTypeOptionDto>,
    currentKey: String,
    floor: List<WorkshopTypeOptionDto> = emptyList(),
): List<SelectOption> {
    val active = sortWorkshopTypes(types.filter { it.isActive })
    val rows = if (currentKey.isBlank() || active.any { it.key == currentKey }) {
        active
    } else {
        active + WorkshopTypeOptionDto(
            id = "retired:$currentKey",
            key = currentKey,
            label = floor.firstOrNull { it.key == currentKey }?.label ?: currentKey,
            sortOrder = Int.MAX_VALUE,
            isActive = false,
            routesToDesignWorkshop = floor.firstOrNull { it.key == currentKey }?.routesToDesignWorkshop ?: false,
        )
    }
    return rows.map { SelectOption(value = it.key, label = it.label) }
}

/**
 * The sentence under the type box. Two facts a reader cannot see, and BOTH of them earn their line.
 *
 * 1. THAT IT IS NOT SAVED. The box looks exactly like the ones that are. R3 asks for this sentence
 *    by name, and the control it replaces carried the same one for the same reason.
 * 2. WHICH LIST THIS IS. DROPDOWN_DESIGN R3 — a silently short list reads as *there are only these*,
 *    which this repository names as its most repeated bug class. A handset that has never reached
 *    `/workshop-types` is holding six labels that shipped with the APK and must say so, because an
 *    administrator may have added a seventh programme that simply is not here.
 *
 * WORD FOR WORD `WorkshopPicker.tsx`'S, both arms, and it must stay that way: a designer who meets
 * one wording on the laptop and another on the phone learns that neither is quite the rule.
 *
 * Note that the handset DOES have a state in which the second arm is true, which is the one place
 * this differs from the retired kind box. That box read the stage registry, which always resolves
 * off the bundled asset, so its own note correctly refused to copy the browser's "built-in" sentence
 * across — *"a permanent apology under a box that is right"*. This list is a TABLE behind a network
 * call, so an offline handset really is showing built-in labels and really does need to say so.
 */
internal fun workshopTypeHint(served: Boolean): String = if (served) {
    "Chooses which workshops the box below lists. It is not saved on this record — the workshop you " +
        "pick already carries its own type."
} else {
    "These are this app's built-in types of workshop — connect once to refresh them. They choose " +
        "which workshops the box below lists, and are not saved on this record."
}

// ═════════════════════════════════════════════════════════════════════════════════════════════════
// 2. THE TYPE BOX
// ═════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * The first of the two dropdowns: which type, and therefore which list and which column.
 *
 * ── IT HOLDS A CHOICE, NOT A DEFAULT ───────────────────────────────────────────────────────────
 *
 * [chosenKey] is null until a PERSON picks, and [typeKey] falls through to [openingWorkshopTypeKey]
 * while it is. That is the shape `WorkshopPicker.tsx` argues for at length and the reason is the
 * second list: every open of a record form offers the floor first and the served rows a moment
 * later, and a state variable seeded once from "the list" has to decide what the second arrival
 * does to it. Every answer to that is a branch that can move where a filed record saves.
 *
 * ── [touched] AND NOT "chosenKey != null" ──────────────────────────────────────────────────────
 *
 * The web reads `chosenTypeKey !== null` because on that client the only thing that writes it is a
 * person. Here the carry bag also writes it — see [applyCarriedRouting] — and this client's rule,
 * stated in three other places, is that THE APP FILLING A BOX IN IS NOT AN EDIT. A blank new form
 * announcing unsaved work before anybody types is what teaches a designer to click through a guard
 * that has to still mean something an hour later.
 */
@Stable
class WorkshopTypePickerState internal constructor(
    private val storedRouting: Boolean?,
    private val preferDesignWorkshops: Boolean,
) {
    /** The rows on offer: the bundled floor until [offer] is given a served list, then that list. */
    var types by mutableStateOf<List<WorkshopTypeOptionDto>>(emptyList())
        private set

    /** The bundled rows, kept so a retired key can still be given the label it shipped with. */
    var floor by mutableStateOf<List<WorkshopTypeOptionDto>>(emptyList())
        private set

    /** False while the box is showing the built-in list. Drives the second arm of [workshopTypeHint]. */
    var served by mutableStateOf(false)
        private set

    /** What a person (or the carry bag) picked, or null while nobody has. */
    var chosenKey by mutableStateOf<String?>(null)
        private set

    /** True once a PERSON moved it. A carried answer and a computed default both leave it false. */
    var touched by mutableStateOf(false)
        private set

    /** The key the box is showing. Never sent anywhere — see the file header. */
    val typeKey: String
        get() = chosenKey ?: openingWorkshopTypeKey(types, storedRouting, preferDesignWorkshops)

    /** True when the chosen type's workshops come from `DesignWorkshop`. Decides BOTH id columns. */
    val routesToDesignWorkshop: Boolean
        get() = routesToDesignWorkshopFor(types, typeKey, storedRouting)

    /** A person picked. This one moves where the record saves, so it arms the guard. */
    fun choose(key: String) {
        chosenKey = key
        touched = true
    }

    /**
     * THE CARRY BAG BROUGHT AN ORDINARY WORKSHOP, so the box follows it there.
     *
     * `useCarryContext`'s Android twin offers the sitting this researcher was last working in, and
     * applying a `workshopId` while the type box sat on Design & Prototype would put a workshop in a
     * state nothing on screen shows and nothing would save. So the box moves with the answer.
     *
     * IT DOES NOT ARM THE GUARD, and this is the one deliberate difference from `WorkshopPicker.tsx`,
     * whose `setWorkshopId` marks the form touched. On this client the carry apply is paired with
     * `applyDefault`, which moves the baseline along with the value precisely so that a prefill is
     * not read as an unsaved edit on the way out — see `WorkshopPickerState.applyDefault` and
     * `DesignWorkshopPickerState.applyDefault`, which have said so since before this control existed.
     * The difference is in what the unsaved-work GUARD does, never in what is saved.
     *
     * Nothing happens when no ordinary-workshop type is on offer: the administrator has retired all
     * five and the field half is unreachable anyway.
     */
    internal fun applyCarriedRouting() {
        firstTypeRouting(types, toDesignWorkshop = false)?.let { chosenKey = it.key }
    }

    /**
     * A list arrived. The floor first, then — if the server answers — the administrator's own rows.
     *
     * REPLACED WHOLE AND NEVER MERGED, which is what stops [workshopTypeFloor] resurrecting a type an
     * administrator has retired. A served list that is EMPTY is still served: the box then has no
     * rows, [typeKey] is `""`, and [routesToDesignWorkshop] falls back to the record's own stored
     * column — a working form with nothing to choose, which is the same answer the browser gives and
     * a state only reachable on a database where every type has been deactivated.
     */
    internal fun offer(rows: List<WorkshopTypeOptionDto>, served: Boolean) {
        if (served) {
            types = sortWorkshopTypes(rows)
            this.served = true
        } else {
            floor = rows
            if (!this.served) types = rows
        }
    }
}

/**
 * Loads the type list: the bundled floor on the first composition, the administrator's list beside it.
 *
 * ── THE FLOOR IS SEEDED SYNCHRONOUSLY, WHICH IS THE ONLY REASON THE BOX IS THERE ON FRAME ONE ───
 *
 * `StageSchemaStore.peek` touches neither disk nor network, so on any session where a stage screen
 * or the workshop list has already run — nearly all of them — the type box is populated on the first
 * frame. That matters more here than it did for the retired kind box: this box decides which of the
 * two workshop boxes is on screen, so an empty one for a frame is a form whose second control
 * appears, disappears and reappears while somebody is reading it.
 *
 * ── AND THE FLOOR IS CONFIRMED OFF DISK BEFORE THE NETWORK IS ASKED ABOUT ANYTHING ─────────────
 *
 * `StageSchemaStore.load` is the disk/asset path and is *"deliberately network-free so that opening
 * a stage screen cannot block on a request"* — its own words. `runCatching` because the one thing it
 * can still throw is a build shipped without the bundled asset, which is a packaging error rather
 * than a field condition; the honest handling on a record form is a smaller floor, not a crash in
 * the middle of an interview.
 *
 * ── THE SERVED LIST IS ONE REQUEST AND A FAILURE IS NOT AN ERROR ───────────────────────────────
 *
 * A record form must open on a bad connection. A refused or unreachable `/workshop-types` leaves the
 * floor on screen with the sentence that says so, and nothing about the form stops working.
 * `CancellationException` is rethrown, as every load on this client does, so a form the designer
 * navigated away from mid-fetch never writes state.
 *
 * NO `includeInactive`. A retired type absent from the dropdown is the entire meaning of retiring
 * one; that parameter belongs to the admin screen, which this handset does not have.
 */
@Composable
fun rememberWorkshopTypePicker(
    repository: WorkshopRepository,
    storedWorkshopId: String?,
    storedDesignWorkshopId: String?,
    preferDesignWorkshops: Boolean,
    resetKey: Any? = null,
): WorkshopTypePickerState {
    val appContext = LocalContext.current.applicationContext
    val state = remember(resetKey) {
        WorkshopTypePickerState(
            storedRouting = storedWorkshopRouting(storedWorkshopId, storedDesignWorkshopId),
            preferDesignWorkshops = preferDesignWorkshops,
        ).also { fresh -> fresh.offer(workshopTypeFloor(StageSchemaStore.peek()), served = false) }
    }

    LaunchedEffect(resetKey) {
        runCatching { workshopTypeFloor(StageSchemaStore.load(appContext)) }
            .onSuccess { rows -> if (rows.isNotEmpty()) state.offer(rows, served = false) }
            .onFailure { error -> if (error is CancellationException) throw error }
    }

    LaunchedEffect(resetKey) {
        runCatching { repository.workshopTypes() }
            .onSuccess { rows -> state.offer(rows, served = true) }
            .onFailure { error -> if (error is CancellationException) throw error }
    }

    return state
}

/**
 * The "Type of workshop" box and its one sentence. Mounted only by `RecordWorkshopField`.
 *
 * `searchable` IS NOT PASSED. Six rows are under the shared threshold anyway, and overruling a count
 * that is already right is how a threshold stops meaning anything — the same words
 * `WorkshopListScreen` uses at its own type filter. Note that this is the OPPOSITE call from the
 * workshop box below it, and the difference is real: that list is one server-truncated page and this
 * one is the whole vocabulary.
 *
 * `includeNone = false` and no "Any type" row. The retired kind box had one because it was a FILTER
 * and "no filter" was a real answer. This box decides where the record saves, so "no type" is not an
 * answer a form can act on — it would leave the second box with no list to draw.
 */
@Composable
internal fun WorkshopTypeField(
    state: WorkshopTypePickerState,
    saving: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Six rows, and still remembered: the whole file's convention is that an option list is built
    // from its inputs and not on every recomposition, and this form recomposes on every keystroke
    // into any of its text fields.
    val options = remember(state.types, state.typeKey, state.floor) {
        workshopTypeOptions(state.types, state.typeKey, state.floor)
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "Type of workshop",
            options = options,
            selectedValue = state.typeKey,
            includeNone = false,
            // R2 in `WorkshopOptions.kt`'s words: a control with nothing in it may not be opened.
            enabled = !saving && listIsAnswerable(options),
            onSelect = { picked -> state.choose(picked) },
        )
        Text(
            workshopTypeHint(state.served),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════════════════════════
// 3. THE DESIGN-WORKSHOP HALF OF THE SECOND BOX
// ═════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * The `DesignWorkshop` list, the selection, and why the box is empty when it is.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS IS A SEPARATE LIST FROM `WorkshopPickerState`'S AND NOT A SECOND KIND OF ROW IN IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `Workshop` is the ordinary field workshop, gated by `WorkshopAssignment` through
 * `resolve_workshop_access`, carrying a submission window and a late-submission dialog.
 * `DesignWorkshop` is the 22-stage design and prototype record, gated by `load_workshop_or_404`:
 * creator, admin, or a `DesignWorkshopViewer` grant. Two tables, two scopes, two access systems, and
 * R1 keeps both. `Artisan.designWorkshopId` in `schema.prisma` carries the argument at length; the
 * short version is that the link was already EXPRESSIBLE through a `Workshop` typed
 * `DESIGN_PROTOTYPE` and was not USABLE, because that hop is optional at both ends, is not
 * one-to-one, and would have put two access systems on one column.
 *
 * What changed in this release is only which of the two a designer SEES: the type box above decides,
 * and never more than one is on screen.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT IT DELIBERATELY DOES NOT DO
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * NO SUBMISSION PRE-FLIGHT. `WorkshopPickerState` asks `GET /workshops/{id}/submission-check` because
 * a `Workshop` has an assignment roster and a window, and a designer has to learn about both BEFORE
 * saving rather than after. A design workshop has neither: the only question is "may you open it",
 * which the save itself answers. A pre-flight here would be a request that could only ever say yes.
 * That is also why `RecordWorkshopField`'s `confirmSubmission` is a no-op on this branch — not a
 * weakening of the gate, but the absence of anything for it to be about.
 *
 * NO PREFILL FROM THE CACHE, THOUGH THE ROWS DO COME FROM IT. The web's `WorkshopSelect.tsx` states
 * the constraint this is built around: a stale copy of an access list is wrong in the PERMISSIVE
 * direction — a revoked grant still reads as a grant. The owner narrowed that on 2026-09-16 and
 * `DwLocalWorkshops` is the narrowing: the upcoming and ongoing workshops this account is allotted
 * to, per account, with the window re-tested against today on every read. What survives of the old
 * rule is the half about WRITING: a cached list may be OFFERED to a person who then chooses from it,
 * and a stale answer may never be written onto a record nobody looked at. So [markCached] fills the
 * rows and touches neither [selectedId] nor [baselineId], and the prefill below stays the server's.
 *
 * IT NEVER REFUSES A SAVE. A failed list leaves the box empty and the record saves unfiled, which is
 * better than blocking a capture in a courtyard on a list request.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE DEFAULT, AND WHY THE SERVER DECIDES IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The owner's instruction of 2026-08-28: *"Whenever a designer goes to create/record any particular
 * record type, the most recently allocated Design and Prototype Workshop should be populated by
 * default."* R4 restates it as "the most recent workshop the account can reach".
 *
 * "Most recently allocated" is `DesignWorkshopViewer.createdAt`, which NO CLIENT CAN SEE — it is not
 * on `DesignWorkshopDto` and no endpoint publishes it per row. Deriving a default here would mean
 * guessing from `createdAt` or `startDate`, which answers a different question and answers it
 * differently from the web. So `GET /design-workshops/default-for-me` decides, once, and both
 * clients read the answer.
 *
 * PREFILL ONLY ON A CREATE, AND ONLY WHILE UNTOUCHED. On an edit the stored value wins outright: a
 * form opened on a record filed last month must not silently re-file it under this month's workshop
 * because somebody fixed a typo in the notes. [isEdit] is what says which.
 *
 * AND A PREFILL IS NOT AN EDIT. [isDirty] compares against the BASELINE the prefill also moves,
 * exactly as `WorkshopPickerState.applyDefault` does.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * UN-FILING REACHES THE SERVER ON BOTH PATHS, AND THIS CLASS OWES ONE THING TO THAT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [value] returns null for "none", `ApiClient.json` has `explicitNulls = false` so the key is
 * OMITTED, and the API's `exclude_unset=True` reads an absent key as *leave the stored value alone*.
 * That is closed on both paths: online, `WorkshopRepository.patchBodyWithClearances` puts the
 * explicit null back for every column in `WORKSHOP_LINK_KEYS` the encoder dropped; offline, the
 * queued ENTRY carries the reason (`PendingEntry.unfiled`, read back as `clearedLinkKeys`) so a
 * replay sends a null only for a column this build wrote down a decision about.
 *
 * WHICH LEAVES THIS CLASS ONE OBLIGATION, AND IT IS [unfiledReason]. The queue cannot work out which
 * of the two absences an empty box was: by the time the entry drains, days later, the picker that
 * was empty in a courtyard with no signal is full again. Only the form knows, and only while it is
 * on screen. See `unfiledLinkReason`, which holds the rule for both boxes so the two cannot drift.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE TYPE FILTER THAT USED TO LIVE HERE IS GONE, AND THE STATE IT NEEDED WENT WITH IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * This class used to carry `kind`, `kindChoices`, `chooseKind`, `offerKinds`, `markReading`,
 * `everListedRows` and `noteUnnarrowedRows` — a `WORKSHOP_KIND` filter over this list and the
 * machinery that kept the OUTBOX honest under it. All of it is deleted, and the file header says
 * why: the type's narrowing is now the choice of TABLE, and sending a key as `workshopKind` as well
 * would narrow a 13,871-row register to the 24 rows that happen to carry that token.
 *
 * `everListedRows` deserves its own sentence, because removing a flag that was added to close a
 * defect needs an argument rather than a shrug. It existed because a filter could make [workshops]
 * shrink, so *"there are rows now"* and *"there were ever rows"* stopped being the same sentence, and
 * [unfiledReason] would then queue a record the designer simply never filed as
 * `UNFILED_NO_OPTIONS` — *"there was nothing to pick"* — about an account holding nine workshops.
 * With no filter the list is read once per form and [markFailed] deliberately keeps what it has, so
 * the rows can only ever grow and `workshops.isNotEmpty()` is exact again — which is what that flag
 * was approximating all along.
 */
class DesignWorkshopPickerState(initialId: String) {
    /** The workshops this account may file under. Empty until the list lands, or if it never does. */
    var workshops by mutableStateOf<List<DesignWorkshopDto>>(emptyList())

    var selectedId by mutableStateOf(initialId)

    /**
     * What [isDirty] compares against.
     *
     * MOVED BY THE PREFILL AND NEVER BY A TAP, which is the whole mechanism: the app filling a box in
     * must not mark the form edited, and the designer changing it must. Same split, same reason, as
     * `WorkshopPickerState.applyDefault`.
     */
    var baselineId by mutableStateOf(initialId)
        private set

    /** Why the box filled itself in, or null. Cleared the moment the designer picks. */
    var prefillNote by mutableStateOf<String?>(null)
        private set

    /**
     * What happened when the list was asked for — the three answers, told apart.
     *
     * This replaces a `listed: Boolean` that could only say "answered" or "not answered yet" and
     * therefore filed a FAILED read under the same word as a read still in flight. The picker then
     * had no way to tell the designer which, so it told them nothing, which reads as the third
     * thing again: there are none.
     */
    var listState by mutableStateOf<WorkshopListState>(WorkshopListState.Loading)
        private set

    /**
     * Whether the device reached the server at all, when [listState] is [WorkshopListState.Failed].
     *
     * NOT A NETWORK PROBE — see `workshopListNotice`. It is `WorkshopRepository.isTransient`'s
     * verdict on the throwable, which is the same classification the offline outbox uses to decide
     * whether an entry is worth retrying. One idea of "offline" per app; a second one would let this
     * screen call a dead tunnel a server fault while the queue behind it calls the same throwable
     * worth retrying.
     */
    var online by mutableStateOf(true)
        private set

    /**
     * What the SERVER said this account holds, which is not what arrived.
     *
     * The picker asks for one page (see [DESIGN_WORKSHOP_PAGE]); keeping `total` beside the rows is
     * the only thing that lets the cap sentence print both numbers, and *"Showing the 20 most
     * recent"* on its own leaves a designer guessing whether that is most of their workshops or a
     * sixth of them. Eleven call sites in this app have shipped the version that keeps `items` and
     * throws `total` away.
     */
    var total by mutableStateOf(0)
        private set

    /**
     * WHAT THE DEVICE'S OWN COPY SAID, and when it was written — `PENDING` until the disk is read.
     *
     * The rows in [workshops] may have come off `data/DwLocalWorkshops.kt` rather than off the wire,
     * and a list served from a nine-day-old file must not look identical to one that arrived a
     * second ago: what a MISSING workshop means is the whole difference between them. This is the one
     * value that makes `cachedListLine`'s dated sentence sayable, and [markCached] is where it lands.
     *
     * NOT A FOURTH ARM OF [listState], deliberately. That type is the NETWORK's story — asked,
     * failed, answered — and it mirrors `frontend/lib/workshopOptions.ts` arm for arm; the browser
     * has no such cache to have a fourth arm about. Where the two meet is `workshopListNotice`, which
     * takes both and rules that a read which ANSWERED outranks anything the disk has to say.
     */
    // INTERNAL and not public, because [RegisterLoad] is: the provenance vocabulary belongs to this
    // module's pickers and nothing outside it may start describing a list with it. The class itself
    // is public only because `AndroidMediaForm` and the record forms mount it from other packages.
    internal var cached by mutableStateOf(RegisterLoad())
        private set

    /** The value to put in a create/update body. Null when nothing is chosen — see the class note. */
    fun value(): String? = selectedId.ifBlank { null }

    /**
     * WHY THIS BOX IS EMPTY, when it is — the one fact a queued record cannot reconstruct later.
     *
     * `UNFILED_BY_CHOICE` when a person emptied it, `UNFILED_NO_OPTIONS` when there was never
     * anything to pick, null when something is chosen. The rule is `unfiledLinkReason`'s and not
     * this file's, because `WorkshopPickerState` in `MainActivity.kt` owes the identical answer for
     * `workshopId` and two copies of a rule that decides whether a link is DESTROYED is two rules.
     *
     * [workshops] and not the rendered options is the third argument on purpose: the options list
     * carries the off-page row and the "None" row, so it is non-empty in states where nothing was
     * ever ON OFFER. What is being asked here is whether the register answered, and that is the rows.
     *
     * ONLY ASKED WHEN THE TYPE ROUTES HERE. `RecordWorkshopLink.unfiledReasons` decides that; the
     * column the record is NOT filed in reports a clearance only when it actually held a workshop
     * when the form opened, which is the one case where a person moving the type really did unfile
     * something.
     */
    fun unfiledReason(): String? = unfiledLinkReason(
        selectedId = selectedId,
        baselineId = baselineId,
        hadOptions = workshops.isNotEmpty(),
    )

    /** True once the designer has changed the workshop away from the loaded/prefilled one. */
    fun isDirty(): Boolean = selectedId != baselineId

    /** A person picked. Retires the explanation, which was about a choice that is no longer the app's. */
    fun choose(id: String) {
        selectedId = id
        prefillNote = null
    }

    /** The app filled it in. Moves the baseline with it, so this is not an edit. */
    fun applyDefault(id: String, note: String?) {
        selectedId = id
        baselineId = id
        prefillNote = note
    }

    /**
     * THE DEVICE'S OWN COPY ANSWERED — before the network has, which is the whole point of it.
     *
     * Called first and at most once per load, from `dwLoadAllotted`'s `onCached`. [listState] is
     * deliberately NOT moved: the network has still not been asked or has still not answered, and
     * saying "Listed" over rows that came off a file would be the screen claiming a read it never
     * made. What tells the reader where these rows came from is [cached], which
     * `workshopListNotice` turns into `cachedListLine`'s dated sentence.
     *
     * NOTHING IS SELECTED HERE, and that is R6's surviving half rather than an omission: a cached
     * list may be OFFERED to a person who then chooses from it, and a stale answer may never be
     * WRITTEN onto a record nobody looked at. The prefill stays on the live path below.
     */
    internal fun markCached(rows: List<DesignWorkshopDto>, load: RegisterLoad) {
        workshops = rows
        cached = load
    }

    /** The read answered. An empty page is an ANSWER and is recorded as one. */
    internal fun markListed(page: DesignWorkshopPageDto) {
        workshops = page.items
        total = page.total
        listState = WorkshopListState.Listed(count = page.items.size, total = page.total)
    }

    /**
     * The read did not answer, and whether the phone ever reached the server.
     *
     * The rows already held are deliberately NOT cleared. On a re-open of a form whose list arrived
     * once, blanking what is on screen because a later request failed would take away the one thing
     * that still works. Since the cache landed, that is no longer the rare path but the ordinary
     * one: [markCached] has usually just put this account's allotted workshops in the box, and a
     * failed fetch must leave them exactly where they are — [cached] is what then tells the reader
     * they are the device's copy and how old it is.
     */
    internal fun markFailed(transient: Boolean) {
        online = !transient
        listState = WorkshopListState.Failed
    }
}

/**
 * How many workshops the picker asks for.
 *
 * The same 20 `SketchesAndPrototypesScreen` uses, and deliberately not the server's ceiling: a longer
 * list on a phone picker is a longer scroll to the same answer, and the row below the list names what
 * was left out and where to search for it.
 *
 * ── THE SENTENCE THAT USED TO END THIS NOTE WAS THE DEFECT ──────────────────────────────────────
 *
 * It read: *"`SearchableSelectField` grows its own filter box at eight options, so a designer on
 * twenty is not scrolling blind."* Twenty is over the threshold, so the box appeared — **over one
 * server-truncated page**. A designer who typed the title of a workshop sitting on page four was
 * answered `Nothing matches "…"` about a workshop that exists, in the one control whose whole job is
 * to say what exists. That is absence read as non-existence, produced by a control that looked like
 * it was helping.
 *
 * The web refuses to draw that box for exactly this reason (`DesignWorkshopSelect.tsx`,
 * `searchable={false}` plus a `capHint`), and this file refuses too: [DesignWorkshopField] passes
 * `searchable = false` and pays the debt that comes with it — **a caller that switches the box off
 * owes the reader the sentence naming what does reach the rest**, which is `workshopCapLine`'s,
 * printed with both numbers. DROPDOWN_DESIGN §3.6.
 *
 * KEEP THIS PAGE-SIZED IF IT EVER MOVES. With `searchable = false` the anchored menu builds every row
 * eagerly inside a scrolling column, which is right for twenty and is not where two hundred belong.
 */
private const val DESIGN_WORKSHOP_PAGE = 20

/**
 * Loads the design workshops this account may file under, and — on a CREATE — the server's default.
 *
 * ── ONE LIST READ, NOT ONE PER TYPE TAP ────────────────────────────────────────────────────────
 *
 * The effect is keyed on [resetKey] alone. The retired kind box put `state.kind` in this key, because
 * it narrowed this very read; the type box above does not narrow it — it chooses which of the two
 * lists is on screen — so a type tap costs no request at all. Both halves of the record form's
 * control load once, in parallel, exactly as the browser's two hooks do.
 *
 * ── WHAT THE OFFLINE ANSWER IS, AND WHOSE IT IS ────────────────────────────────────────────────
 *
 * THE DISK IS READ FIRST, ON EVERY OPEN, AND THE SERVER THEN REPLACES BOTH THE LIST AND THE FILE.
 * `loadAllottedDesignWorkshops` is that whole sequence and `data/DwLocalWorkshops.kt`'s header
 * carries the argument for why keeping this list at all is a narrowing of the old no-cache rule
 * rather than a repeal of it. Three things about it belong here, at the call site:
 *
 *  · THE ROWS ARRIVE BEFORE THE REQUEST DOES. A record form opened in a courtyard draws the
 *    workshops this account is allotted to immediately, rather than an empty dropdown for however
 *    long a dead connection takes to time out and then an empty dropdown for ever. That is the
 *    defect this wiring exists to close: the cache was written, tested and read by nothing.
 *  · THE SCREEN SAYS WHICH IT IS. `state.cached` travels to `workshopListNotice`, which prints
 *    `cachedListLine` — the count and the date the file was last refreshed — over a list that came
 *    off the disk, and says nothing at all over one that arrived live. A cached list and a live one
 *    must never look the same, because whether a MISSING workshop means "it is not yours" or
 *    "refresh first" is the whole difference between them.
 *  · THE PREFILL IS STILL THE SERVER'S. `designWorkshopDefaultForMe` below is untouched, and
 *    [DesignWorkshopPickerState.markCached] selects nothing. Offering and prefilling are not the
 *    same act: a stale OFFER is a row a person reads and chooses, a stale PREFILL is an id written
 *    onto every record made that afternoon by somebody who never looked at the box.
 *
 * `runCatching` separately for the list and the default, so a refused default does not cost the list.
 * They fail for different reasons: the list is a scoped read every designer passes, the default is a
 * newer endpoint an older deployment may not have at all.
 */
@Composable
fun rememberDesignWorkshopPicker(
    repository: WorkshopRepository,
    isEdit: Boolean,
    initialId: String?,
    resetKey: Any? = null,
): DesignWorkshopPickerState {
    val state = remember(resetKey) { DesignWorkshopPickerState(initialId.orEmpty()) }
    val context = LocalContext.current

    LaunchedEffect(resetKey) {
        /*
          THE DEVICE'S COPY, THEN THE SERVER'S — `dwLoadAllotted` owns that order for both pickers
          and both tables, so the field half in `MainActivity.kt` cannot come to disagree with this
          one about which answer wins or about when the file is replaced. What it does, in order:
          read this account's cached design workshops off the disk and hand them to `onCached`;
          issue the read below; and on an answer, replace the file with the upcoming and ongoing
          rows of it. A `CancellationException` is rethrown from in there rather than classified,
          which is what this arm did for itself before the function existed.

          THE PAGE AND NOT THE ROWS. `half.page` is the server's own answer — unfiltered, with its
          `total` — because the live picker draws an ended workshop with the word beside it and
          needs `total` for the cap sentence. `half.rows` is what the CACHE now holds, which is
          narrower by design, and is not what a screen shows.
        */
        val half = repository.loadAllottedDesignWorkshops(
            context = context,
            fetch = { repository.designWorkshops(page = 1, pageSize = DESIGN_WORKSHOP_PAGE) },
            rowsOf = { it.items },
            onCached = { rows, load -> state.markCached(rows, load) },
        )
        val page = half.page
        if (page != null) {
            state.markListed(page)
        } else {
            /*
              WHICH FAILURE, BECAUSE THE TWO HAVE DIFFERENT NEXT MOVES. `isTransient` is the
              outbox's own classification — an IOException or a 401/408/429/5xx means this device
              could not get an answer, and anything else means the server answered and refused. The
              first is "connect once and this list stays on the phone"; the second is "this is not
              showing what exists". Asking the same question a second way, with a connectivity
              probe, would give this app two ideas of what offline means.

              It is read back off `half.load` rather than re-asked here: that verdict was already
              made, in the one place both pickers make it, and `RegisterLoad.online` is the same
              field the four record registers carry it in.
            */
            state.markFailed(transient = !half.load.online)
        }
    }

    /*
      ── THE DEFAULT, ON A CREATE ONLY, AND EXACTLY ONCE ──────────────────────────────

      Issuing it on an edit would spend a round trip to be told something the branch below discards.

      IT CAN PREFILL A WORKSHOP THE TYPE BOX WOULD NOT HAVE CHOSEN FOR ITSELF, and that is correct
      rather than an oversight: the server answers with the workshop this account was most recently
      ALLOCATED, and second-guessing an allocation would file the record under a workshop nobody gave
      them. `designWorkshopOptions` keeps the prefilled row visible through `offPageWorkshopRow`,
      which is the same mechanism that protects an edit.
    */
    LaunchedEffect(resetKey) {
        if (!isEdit && state.selectedId.isBlank()) {
            runCatching { repository.designWorkshopDefaultForMe() }
                .onSuccess { answer ->
                    val id = answer.workshopId
                    // ANSWERED-AND-NONE IS AN ANSWER. A newly onboarded designer is on no workshop and
                    // nothing is prefilled and nothing is said, which is correct: there is no decision
                    // to explain.
                    if (!id.isNullOrBlank()) {
                        state.applyDefault(id, designWorkshopPrefillNote(answer.reason, answer.accessAt))
                    }
                }
                .onFailure { error -> if (error is CancellationException) throw error }
        }
    }
    return state
}

/**
 * One sentence saying WHY the box filled itself in.
 *
 * A dropdown that fills itself in and cannot say why reads as a bug, and the two doors need different
 * sentences: "the workshop you were most recently added to" sends a designer looking for an
 * allocation that really happened, and "the one you opened most recently" does not. Null for anything
 * this function does not recognise, so a future third `reason` prints nothing rather than a wrong
 * word — an unknown value must never be dressed as one of the two known ones.
 *
 * Internal rather than private so `WorkshopPickerTest` can pin the pairing without a screen.
 */
internal fun designWorkshopPrefillNote(reason: String?, accessAt: String?): String? {
    val day = formatIsoDay(accessAt)
    val tail = if (day == null) "" else " on $day"
    return when (reason) {
        "GRANTED" ->
            "Filled in because it is the design workshop you were most recently added to$tail. " +
                "Change it if this record belongs somewhere else."
        "CREATED" ->
            "Filled in because it is the design workshop you most recently opened$tail. " +
                "Change it if this record belongs somewhere else."
        else -> null
    }
}

/**
 * The day out of an ISO timestamp, or null.
 *
 * NOTHING IS ECHOED ON FAILURE, which is the opposite of what this app does with a date a designer
 * TYPED. This one is the server's, so a value that will not parse is a defect rather than something
 * to put in front of a reader — and the sentence above reads perfectly well without it.
 */
private fun formatIsoDay(iso: String?): String? {
    val text = iso?.takeIf { it.length >= 10 } ?: return null
    val day = text.substring(0, 10)
    return if (day.getOrNull(4) == '-' && day.getOrNull(7) == '-') day else null
}

/**
 * The `DesignWorkshop` half of the record form's SECOND box — drawn only when the type routes here.
 *
 * ── IT IS ONE BOX NOW, AND THAT IS THE WHOLE OF THIS RELEASE ON THIS FILE ──────────────────────
 *
 * This composable used to draw TWO controls: a "Type of workshop" box over a "Design & prototype
 * workshop" box. The type box moved out to [WorkshopTypeField], because it is no longer about this
 * list — it decides which of two lists a record form shows and which of two columns the answer lands
 * in, so it cannot belong to either one of them. What is left is a single dropdown plus the four
 * sentences that make an empty one legible.
 *
 * ── [label] AND [noneLabel] ARE THE ONE THING THE TWO MOUNTS SAY DIFFERENTLY ───────────────────
 *
 * On a record form this is the "Workshop" box and its empty row is "Not linked to a workshop" (R5) —
 * the defaults, and byte-for-byte what `WorkshopPicker.tsx` passes to `DesignWorkshopSelect`,
 * because the reader is answering ONE question and a label that changed with the type would be the
 * two-pickers-for-one-question problem this release exists to end, wearing one control.
 *
 * The media upload screen is the other mount and it says both differently, for a reason that is a
 * fact about the server rather than a preference: a `MediaFile` row has a `designWorkshopId` and NO
 * `workshopId` (`backend/app/schemas/media.py`), so that screen has one destination, no routing
 * decision to make, and therefore no type box above this one to say which list this is. Its own call
 * site carries that argument.
 */
@Composable
fun DesignWorkshopField(
    state: DesignWorkshopPickerState,
    saving: Boolean = false,
    label: String = "Workshop",
    noneLabel: String = NO_FIELD_WORKSHOP,
    modifier: Modifier = Modifier,
) {
    /*
      THE LABEL, THE HINT AND THE ORDER ARE `WorkshopOptions.kt`'S AND NOT THIS FILE'S.

      They used to be assembled here, and two more copies of the same assembly are still in the tree
      (`dwChooserWorkshopHint`, `designWorkshopOption`), each carrying a comment claiming to match
      this one. They did not all match: this one had no status word in it, so a SUBMITTED workshop
      and one still running read as the same kind of row, and nothing on the phone put the open ones
      first. Requirement 20 is that the two clients must not disagree about any of this; three
      copies on ONE client cannot honour it even in principle. See DROPDOWN_DESIGN §2.3, §2.5, §2.6.

      `narrowed` IS NOT PASSED, AND THAT IS THE TYPE FILTER LEAVING. It existed to reword the
      off-page row's hint when a `workshopKind` was in force on the read; no read this control issues
      carries one any more, so a stored workshop that is missing from the page is missing for the
      reason the original sentence gives — this device could not list it just now.
    */
    val options = remember(state.workshops, state.selectedId) {
        designWorkshopOptions(rows = state.workshops, offPageId = state.selectedId)
    }

    /*
      WHICH OF THE STATES THIS PICKER IS IN, IN WORDS — R3, and the reason this field exists in the
      shape it now has. `SearchableSelectField` cannot guess it: the primitive knows the list is
      empty and knows nothing whatever about WHY, and the five whys that have words have five
      different next moves. Only this composable holds `listState`, so only this composable can say.

      `cached` IS THE SIXTH WHY AND THE ONLY ONE THAT SPEAKS OVER A WORKING CONTROL. The rows may
      have come off `DwLocalWorkshops` while the request is still in flight or after it failed, and
      then the sentence is `cachedListLine` — the count and the day the file was refreshed — because
      a list from a nine-day-old file and a list from a second ago are not the same list. It is
      passed with the ROW COUNT and not just the provenance: the window is re-tested on every read,
      so a file with four workshops in it answers with none the morning after the last of them
      ended, and that state is "you are on nothing current", not "here are 0 workshops".
    */
    val notice = workshopListNotice(
        state = state.listState,
        kind = WorkshopListKind.DESIGN,
        online = state.online,
        cached = state.cached,
        cachedRows = state.workshops.size,
    )

    /*
      R2 — A FIELD MAY ONLY BE MANDATORY WHERE IT IS ANSWERABLE, and its Android half: a control with
      nothing in it may not be opened. This field is never REQUIRED, so there is no validator to
      stand down; what stands down is the trigger, which otherwise opens a popup whose entire content
      is the "none" row and which reads, to anybody who taps it, as the repository's answer.

      `options.isNotEmpty()` and not `state.workshops.isNotEmpty()`, deliberately: the off-page row
      counts. A record already filed under a workshop this device cannot list still has one true
      thing to show and one reversible choice to offer, and disabling over that would hide the row
      that keeps the trigger honest.
    */
    val enabled = !saving && listIsAnswerable(options)

    /*
      SAID ONCE, NOT TWICE. `SearchableSelectField` prints `emptyMessage` on the form itself when the
      list is empty AND the control is disabled — because in that state neither surface can be
      opened, so a sentence that lives only inside the popup can never be read. That is exactly the
      state below, so the same sentence printed again by this file would put it on screen twice.
    */
    val standDown = options.isEmpty() && !enabled

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = label,
            options = options,
            selectedValue = state.selectedId,
            placeholder = noneLabel,
            // `includeNone` is what puts the "not linked" row in the list, and it must stay: R5 —
            // a record may still have no workshop, a record filed by mistake has to be
            // de-selectable, and the clearance reaches the server on both paths (see the class
            // note). Nothing here forces somebody to invent a workshop to save work done outside
            // one.
            includeNone = true,
            enabled = enabled,
            /*
              THE FILTER BOX IS OFF, AND THE SENTENCE BELOW IS THE PRICE OF SWITCHING IT OFF.

              [options] is ONE SERVER-TRUNCATED PAGE of twenty. A box over it filters the page, so a
              designer typing the title of their twenty-first workshop was told nothing matched —
              about a workshop that exists, in the control that is least allowed to say so. §3.6
              rules that the threshold does not move and simply stops deciding for record-backed
              lists; `searchable = false` is this call site making that ruling, and `workshopCapLine`
              names the screen whose box does reach the whole table.
            */
            searchable = false,
            // The caller's sentence, never the primitive's. Null here means "the list arrived with
            // rows in it", which is the one state that needs no explanation.
            emptyMessage = notice,
            onSelect = { state.choose(it) },
        )
        state.prefillNote?.let { note ->
            Text(note, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        /*
          SAID ON THE CONTROL THAT COULD BE MISREAD AS A PERMISSION. A designer who believes this box
          narrows who may READ the record will use it as though it does, and it does not:
          `records.viewable_where` returns an empty filter and every signed-in account may already
          read every artisan, product, process, tool and interview in the repository. Stating it here
          costs one line and stops a filing label being trusted as an access rule.
        */
        Text(
            "Files this record under a design and prototype workshop so it appears in that " +
                "workshop's lists. It does not change who can read the record.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        /*
          RULE 10: EVERY CAP SAYS SO, WITH BOTH NUMBERS — and only when it bites, so an ordinary
          designer on four workshops never reads a sentence about a ceiling they cannot reach.

          It used to compare `size >= DESIGN_WORKSHOP_PAGE` and print the page size alone, which
          said "showing your 20 most recent" to a designer with exactly twenty workshops and nothing
          hidden, and said the same to one with a hundred and twenty. `total` is what the server
          reports for this account, so the sentence now states the arithmetic — and it is worded
          exactly as the web words it, because a designer who meets one wording on the laptop and
          another on the phone learns that the numbers are approximate.
        */
        workshopCapLine(state.workshops.size, state.total, WorkshopListKind.DESIGN)?.let { cap ->
            Text(cap, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        /*
          AND THE ONE SENTENCE ABOUT THE LIST ITSELF — the replacement for a gate that could only
          speak when the read had SUCCEEDED and was therefore silent in the two states that most
          needed a sentence.

          Skipped when the field has been stood down, because `SearchableSelectField` has already
          printed this exact string on the form for that case (see [standDown] above) and one fact
          may not appear twice under one control.
        */
        if (!standDown) {
            notice?.let { line ->
                Text(line, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}
