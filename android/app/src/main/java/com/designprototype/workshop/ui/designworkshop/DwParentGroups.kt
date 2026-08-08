package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DW_ROW_KEY_SEPARATOR
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.dwRefId
import com.designprototype.workshop.data.rowsFor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * A child collection split into the parent records its rows belong to — the phone's half of
 * `report_builder.ReportBuilder._parent_groups`.
 *
 * ── WHAT THIS PRINTS THAT THE HANDSET NEVER PRINTED ─────────────────────────────────────────────
 *
 * `EntityDto.parent` has been declared in the registry, validated by the server's `stage_schema` and
 * shipped to this app inside `design-workshop-schema.json` since the schema existed, and no Kotlin
 * read it. So a report exported in a courtyard printed "Cost sheets" as one table and then "Material
 * cost lines" as a SECOND flat table holding every line of every sheet interleaved in entry order —
 * and `costMaterialLine.costSheetRef`, the field that says which line belongs to which sheet, is
 * `reportRole=HIDDEN`, so it was not in that table either. The file handed to the visiting officer
 * therefore contained no way whatsoever to work out which materials cost which product, which is the
 * one question a cost sheet exists to answer, in a document read as the basis of a sanctioned amount.
 * The server's copy of the same workshop, regenerated at the office a week later, had it. Two files,
 * one desk, different documents.
 *
 * ── null IS WHAT KEEPS THE OTHER NINETEEN STAGES BYTE-IDENTICAL ─────────────────────────────────
 *
 * Only five of the registry's 43 entities declare a parent. For every other collection this returns
 * `null` on its first line and the caller falls straight through to the same single `renderCollection`
 * call it has always made — so "a stage with no parent renders exactly as before" is a property of the
 * control flow rather than a promise, and `DwParentGroupTest` pins it by building the whole registry
 * twice, once with `parent` stripped (which IS the pre-change path) and once as shipped.
 *
 * ── THE LINK IS FOUND BY MODEL, NOT BY FIELD NAME ───────────────────────────────────────────────
 *
 * The child's REF field whose `refModel` is the parent entity's `name` is the link. Matching a key
 * like `costSheetRef` would be a spelling convention nothing enforces, while model names are unique
 * and validated. A parent that no field of the child points at is a registry mistake and not a
 * document to mangle, so the collection is printed flat exactly as before rather than grouped by a
 * guess.
 */
internal data class DwParentGroup(
    /** The heading this group prints under — the parent's own row heading, or the orphan note. */
    val heading: String,
    /** The child rows, in the order they were captured. Never empty. */
    val rows: List<Map<String, JsonElement>>,
)

/**
 * [rows] regrouped under their parents, or `null` when this entity has no parent to group by.
 *
 * @param parentHeading how a PARENT row titles itself — passed in rather than reached for so this
 *   file has no opinion of its own about heading text. `ReportScreen` hands it `rowHeading`, the very
 *   function that heads the parent's own sub-sections, which is what guarantees a group is headed by
 *   the exact words the cost sheet above it is headed by. Its `Int` is the parent's 0-based ordinal,
 *   so a sheet that names no product falls back to "Cost sheets 2" in both places.
 * @param refLabel resolves a reference id to the name of the record it points at — `DwRefLabels.label`.
 */
internal fun dwParentGroups(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    entity: EntityDto,
    rows: List<Map<String, JsonElement>>,
    parentHeading: (EntityDto, Map<String, JsonElement>, Int) -> String,
    refLabel: (String) -> String,
): List<DwParentGroup>? {
    if (entity.parent.isBlank()) return null

    // THE PARENT IS NOT ALWAYS IN THE CHILD'S OWN STAGE. Stage 14's iterations hang off stage 13's
    // prototypes, so a lookup that searched only the stage being rendered would find no parents at
    // all and print every iteration as an orphan. Entity keys are globally unique — the server's
    // `stage_schema.validate` enforces that rather than leaving it to convention — so one sweep of
    // the registry finds both the parent and the stage whose draft holds its rows.
    var parentEntity: EntityDto? = null
    var parentStageKey = ""
    for (stage in schema.stages) {
        val found = stage.entities.firstOrNull { it.key == entity.parent } ?: continue
        parentEntity = found
        parentStageKey = stage.key
        break
    }
    val parent = parentEntity ?: return null

    val link = entity.fields.firstOrNull {
        DwFieldType.of(it.type) == DwFieldType.REF && it.refModel == parent.name
    } ?: return null

    val parentRows = draft?.stages?.get(parentStageKey)?.rowsFor(parent.key).orEmpty()

    // ── Which parent, if any, owns each id a child can be holding ───────────────────────────────
    //
    // A ROW ON THIS DEVICE ANSWERS TO TWO IDS AND THE SERVER'S COPY ANSWERS TO ONE, which is the one
    // place this port cannot be a transcription of `_parent_groups`. A picker fills in the id the
    // server serves for the row (`_entryId`), but a cost sheet created in a courtyard has no server
    // id yet, so a line entered against it holds the local `DraftRow` key instead. Indexing only
    // `_entryId` would leave every workshop authored offline — the workshops this whole export path
    // exists for — reporting each of its own lines as unattributed. `DwRefLabels` indexes both ids
    // for the same reason, and the two must agree or a line would be headed by a sheet's name in the
    // table and filed under "no cost sheet recorded" below it.
    //
    // A PARENT WITH NO ID OF ITS OWN CLAIMS NOTHING — the blank guard below, and it was a real bug on
    // the server. A sheet that has not synced carries no `_entryId`, a line whose picker was never
    // opened carries no ref, and to a map those are the same empty string: joining on it printed
    // every unattributed line in the workshop as that one product's material cost. A fabricated
    // number in the column an officer sanctions against, produced by the code that exists to prevent
    // fabrications. `cost_integrity.summarise_sheets` guards the identical join and the two must not
    // disagree about what is attributable.
    val ownerOf = HashMap<String, Int>()
    parentRows.forEachIndexed { index, parentRow ->
        val localKey = parentRow.id.substringAfter(DW_ROW_KEY_SEPARATOR, "")
        val entryId = (parentRow.values["_entryId"] as? JsonPrimitive)?.content.orEmpty()
        for (id in listOf(localKey, entryId)) {
            if (id.isBlank()) continue
            // First parent wins, mirroring the server's `buckets.pop` — a second row somehow
            // answering to the same id must not steal lines from the row that already claimed them.
            if (!ownerOf.containsKey(id)) ownerOf[id] = index
        }
    }

    // ── Every child into exactly one bucket, walking the children ONCE ──────────────────────────
    //
    // One pass over the children rather than one pass per parent, so a row's position inside its
    // group is the order it was captured in even where a parent is reached through both of its ids.
    val claimed = List(parentRows.size) { ArrayList<Map<String, JsonElement>>() }
    val named = LinkedHashMap<String, ArrayList<Map<String, JsonElement>>>()
    val orphans = ArrayList<Map<String, JsonElement>>()
    for (row in rows) {
        val ref = dwRefId(row[link.key]).trim()
        // A blank ref looks up the blank key ON PURPOSE and must find nothing: the guard above is the
        // single place that decides an id-less parent owns no lines, so removing it here would be a
        // second opinion that could disagree with it. It is also the shape that lets the test for
        // the guard actually fail without the guard.
        val owner = ownerOf[ref]
        when {
            owner != null -> claimed[owner].add(row)
            ref.isEmpty() -> orphans.add(row)
            else -> named.getOrPut(ref) { ArrayList() }.add(row)
        }
    }

    // THE PARENT'S OWN ORDER, taken from the parent collection and not from the children. The groups
    // then run down the page in the same sequence as the rows of the table above them; ordering by
    // the children would list the sheets in whatever order somebody happened to type their first
    // line, and a reader comparing the two tables would have to search.
    val groups = ArrayList<DwParentGroup>()
    parentRows.forEachIndexed { index, parentRow ->
        val taken = claimed[index]
        // A sheet nobody itemised is reported by its absence from the breakdown, never by a heading
        // with nothing under it — an empty heading reads as "this product cost nothing".
        if (taken.isNotEmpty()) {
            groups += DwParentGroup(parentHeading(parent, parentRow.values, index), taken)
        }
    }

    for ((ref, taken) in named) {
        // An id no row of this draft holds. It still labels itself when the reference resolves
        // through the picker's cache; when it does not, the row it named was deleted after these
        // lines cited it, which is the same state as naming nothing.
        val label = refLabel(ref)
        if (label.isNotBlank()) groups += DwParentGroup(label, taken) else orphans += taken
    }

    if (orphans.isNotEmpty()) {
        // AN ORPHAN IS PRINTED, LAST, UNDER A HEADING THAT SAYS SO. Filing it under a parent it does
        // not belong to would be a fabrication, and dropping it would delete fieldwork somebody did —
        // a line that cost real money, missing from a total, in a file already delivered.
        //
        // Locale.ROOT because the label is lower-cased for prose, not for the reader's locale: a
        // handset set to Turkish lower-cases "I" to a dotless "ı" and the heading would come out
        // "No ıteration recorded" in a document submitted in English.
        groups += DwParentGroup("No ${link.label.lowercase(Locale.ROOT)} recorded", orphans)
    }
    return groups
}
