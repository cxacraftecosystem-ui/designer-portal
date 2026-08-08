package com.designprototype.workshop.data

import com.designprototype.workshop.report.summary as richSummary
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which rows of a workshop get a card, what is printed on one, and what a scanned code points at —
 * the handset's port of `frontend/app/(protected)/design-workshops/[id]/codes/page.tsx`.
 *
 * [DwWorkshopCodes] makes the payload and [DwQrEncode] draws it; NEITHER OF THEM KNOWS WHAT A
 * WORKSHOP IS. This file is the join between them and the draft store, and it is separate from the
 * screen for the same reason [DwSubmissionReadiness] is separate from the panel that shows it: every
 * decision below is testable without a device, and a card that comes out wrong is a red test rather
 * than a sheet of forty tags nobody can scan.
 *
 * ── WHY IT READS THE REGISTRY AND NEVER A STAGE NUMBER ────────────────────────────────────────
 *
 * Which entity holds prototypes is answered by `entity.name == "DwPrototype"` (the Prisma model the
 * registry declares) and which holds the roster by "the collection carrying a REF to `Artisan`".
 * Both are properties the registry already publishes, so renumbering a stage, renaming it or moving
 * the roster changes nothing here. When neither is found the caller says so in a sentence rather
 * than drawing an empty sheet — "this workshop has no prototypes yet" and "this build no longer
 * understands the registry" are different problems with different fixes, and a screen that confuses
 * them sends a designer looking for rows they never entered.
 *
 * ── THE ONE PLACE THIS PORT CANNOT BE A TRANSCRIPTION ─────────────────────────────────────────
 *
 * The web reads a row's identity straight off the row object: `_entryId ?? _clientKey`, both keys
 * present in the JSON. **On this client the client key is not in the object.** [StageDraft] holds one
 * `rows` list for every collection of a stage, so [DraftRow.id] is `entityKey#clientKey` and the
 * suffix IS the `_clientKey` the sync sends (see [DW_ROW_KEY_SEPARATOR] and `buildStageBody`). Handing
 * [workshopCodeIdForRow] a bare `row.values` therefore answers NULL for every row that has not synced
 * — which is precisely the row a tag is for, because the afternoon a prototype exists is the
 * afternoon the maker is holding it and the connection is a fortnight away. [codeRow] is the bridge,
 * and it is the reason this file exists rather than the screen calling the two modules directly.
 *
 * PURE — no Compose, no Android, no network, no clock — in the sense of [DwMarketAnalysis], and for
 * the same reason: tags are printed in a courtyard with no signal, and the decisions have to be
 * checkable without one.
 */

/**
 * The Prisma model whose rows get a tag. Declared by the registry; MATCHED, never assumed.
 *
 * The literal is the web's `PROTOTYPE_MODEL` and must stay equal to it: a card printed by the browser
 * and a card printed by the handset have to point at the same kind of record.
 */
const val DW_PROTOTYPE_MODEL: String = "DwPrototype"

/** The error correction level every code is printed at. See [renderWorkshopCard] for why not L. */
val DW_CARD_ECC_LEVEL: DwQrEccLevel = DwQrEccLevel.Q

/** Where one kind of card comes from: which stage, and which collection inside it. */
data class DwCardSource(val stage: StageDto, val entity: EntityDto)

/**
 * The collection entity whose rows are prototypes, wherever the registry has put it.
 *
 * FIRST MATCH IN REGISTRY ORDER, exactly as the web's loop is, so both clients pick the same entity
 * if the model is ever declared twice.
 */
fun findPrototypeSource(schema: SchemaResponse): DwCardSource? {
    for (stage in schema.stages) {
        for (entity in stage.entities) {
            if (entity.cardinality == "COLLECTION" && entity.name == DW_PROTOTYPE_MODEL) {
                return DwCardSource(stage, entity)
            }
        }
    }
    return null
}

/**
 * The roster: the FIRST collection carrying a REF to an `Artisan`.
 *
 * Identified by that reference rather than by name because the reference is the only thing that makes
 * a roster row addressable at all — a row typed in by hand has no artisan id behind it, and a card
 * printed from one would point at nothing.
 *
 * FIRST, in registry order, and that is load-bearing rather than incidental: stage 6's
 * `existingProduct` also carries an `artisanRef` to `Artisan`, so a search that returned the last
 * match, or scanned entities before stages, would print "artisan cards" for a baseline product table.
 * Stage 3's `participant` comes first in the registry and is the roster. The web's loop has the same
 * order and the same consequence.
 */
fun findRosterSource(schema: SchemaResponse): DwCardSource? {
    for (stage in schema.stages) {
        for (entity in stage.entities) {
            if (entity.cardinality != "COLLECTION") continue
            if (entity.fields.any { it.type == "REF" && it.refModel == "Artisan" }) {
                return DwCardSource(stage, entity)
            }
        }
    }
    return null
}

/** The key of the field on a roster row that holds the artisan's repository id. */
fun artisanRefKey(entity: EntityDto): String? =
    entity.fields.firstOrNull { it.type == "REF" && it.refModel == "Artisan" }?.key

/** The source for one kind of card, or null when this build's registry has no such collection. */
fun workshopCardSource(recordType: DwWorkshopRecordType, schema: SchemaResponse): DwCardSource? =
    when (recordType) {
        DwWorkshopRecordType.PROTOTYPE -> findPrototypeSource(schema)
        DwWorkshopRecordType.ARTISAN -> findRosterSource(schema)
    }

// --------------------------------------------------------------------------------------
// The rows a card is printed from
// --------------------------------------------------------------------------------------

/**
 * A draft row as the code modules read it: its stored values PLUS the client key this client keeps in
 * the row's id rather than inside the object. See the file header — without it every unsynced row
 * refuses with NO_ID, which is most of the rows on the day the tags are wanted.
 *
 * The key is added only when the object does not already carry one. A row seeded from the server
 * arrives with the server's own `_clientKey` inside `data` (`_stages_payload` puts it there), and the
 * server's spelling is the one the server will match on; overwriting it with the local id would print
 * a tag the sync could not pair with the row it came from.
 */
fun DraftRow.codeRow(): DwDataRow {
    val clientKey = id.substringAfter(DW_ROW_KEY_SEPARATOR, "")
    if (clientKey.isEmpty() || values.containsKey("_clientKey")) return values
    return values + ("_clientKey" to JsonPrimitive(clientKey))
}

/**
 * The rows to print tags for: this device's draft where it holds the stage, the server's copy where
 * it does not.
 *
 * THE SAME RULE [StageIndexScreen] APPLIES TO A SCORE, and for the same reason. A prototype entered
 * in a courtyard an hour ago is not on the server, so the local draft has to win wherever it knows
 * anything — otherwise the tag a designer needs today is the one tag the sheet does not hold. But a
 * workshop somebody else started and this handset has never opened has an EMPTY draft, and printing
 * "no prototypes have been recorded yet" over a workshop holding twenty-five is the silent-emptiness
 * failure this repository keeps hitting: the designer concludes the tags cannot be made at all.
 *
 * "Knows anything" is judged per STAGE, not per entity, because a stage is what [StageScreen] seeds
 * and saves as a unit: a stage the designer has opened holds everything the server had for it plus
 * their edits, so falling back per-entity inside a touched stage would resurrect rows they deleted.
 */
fun workshopCardRows(
    source: DwCardSource,
    draft: WorkshopDraft?,
    remoteStages: Map<String, StageBucketDto>,
): List<DwDataRow> {
    val stored = draft?.stages?.get(source.stage.key)
    val touchedLocally = stored != null && (stored.values.isNotEmpty() || stored.rows.isNotEmpty())
    if (touchedLocally) return stored.rowsFor(source.entity.key).map { it.codeRow() }
    // A `JsonObject` IS a `Map<String, JsonElement>`, so the server's rows are already [DwDataRow]s —
    // and they arrive carrying `_entryId` and `_clientKey` inside the object, which is the shape the
    // web reads and the shape [codeRow] has to reconstruct for a local row.
    return remoteStages[source.stage.key]?.collections?.get(source.entity.key).orEmpty()
}

// --------------------------------------------------------------------------------------
// One card
// --------------------------------------------------------------------------------------

/**
 * One card's worth of input — ONLY what may be printed.
 *
 * The caller supplies a title and at most two supporting lines, and this shape has nowhere to put
 * anything else. That is deliberate and it is the same guarantee `WorkshopCodeSheet.tsx` makes: a
 * card is a public object the moment it leaves the room, so no identity number can reach it — no
 * Aadhaar, no Pehchan, no artisan card number — and a struct with no field for one cannot become the
 * place one leaks.
 *
 * [key] is a stable identity for the list, and it is NOT the id: an unsaved row does not have one.
 */
data class DwCardSpec(
    val key: String,
    val recordType: DwWorkshopRecordType,
    /** The record's identifier, or null when the row does not have one yet. */
    val id: String?,
    /** The line a human reads across a courtyard: an artisan's name, a prototype's name. */
    val title: String,
    /** At most two supporting lines — a serial number, a village, a prototype code. Never an id. */
    val lines: List<String>,
)

/** One card, drawn or refused. */
sealed interface DwCardRender {
    val spec: DwCardSpec

    data class Ok(
        override val spec: DwCardSpec,
        /** The canonical payload, as the QR carries it. */
        val code: String,
        /** The same code in groups of four, as it is printed under the symbol. */
        val printed: String,
        val symbol: DwQrSymbol,
    ) : DwCardRender

    data class Refused(override val spec: DwCardSpec, val message: String) : DwCardRender
}

/**
 * How a designer recognises one row — the declared label field, then the first prose anybody typed,
 * and only then "Prototypes 3".
 *
 * A SECOND COPY OF `rowTitleOf`, which [DwSubmissionReadiness] holds privately, and the duplication is
 * stated rather than hidden: that one is private to a module the data layer must not reach into, and
 * exporting it would widen the surface of a file another change is in the middle of. They are the same
 * three rules in the same order, ported from `rowTitle` in `lib/designWorkshops.ts`; if either moves,
 * both move, and the honest fix is to fold them into one once both have landed.
 *
 * RICH_TEXT goes through the report's own `summary`. It stores an object, so a plain value read
 * returns nothing for it, and a prototype whose only prose is a narrative would be headed
 * "Prototypes 3" — a card nobody can identify by eye, which is the one thing the face of a card is for.
 */
fun dwCardRowTitle(entity: EntityDto, row: DwDataRow, index: Int): String {
    if (entity.labelField.isNotEmpty()) {
        // Read straight off the row rather than through the field spec, as the web does: a labelField
        // naming a key this build's registry has dropped still titles the row.
        val labelled = DwPy.strip(DwValues.text(row[entity.labelField]))
        if (labelled.isNotEmpty()) return labelled
    }
    for (field in entity.liveFields) {
        when (DwFieldType.of(field.type)) {
            DwFieldType.RICH_TEXT ->
                richSummary(row[field.key], 80).takeIf { it.isNotEmpty() }?.let { return it }
            DwFieldType.TEXT, DwFieldType.LONG_TEXT ->
                DwPy.strip(DwValues.text(row[field.key])).takeIf { it.isNotEmpty() }?.let { return it }
            else -> Unit
        }
    }
    return "${entity.title} ${index + 1}"
}

/**
 * Every card of one kind, in the order the rows are stored — which is also the order they appear on
 * the stage screen, so a designer cutting a sheet up can work down both lists together.
 *
 * Rows with no identifier are NOT dropped. They become a card carrying the reason instead of a
 * symbol, because a sheet that silently held 29 tags for a workshop with 30 prototypes is cut up,
 * tied on, and discovered on the day the report is due.
 */
fun workshopCardSpecs(
    recordType: DwWorkshopRecordType,
    source: DwCardSource,
    rows: List<DwDataRow>,
): List<DwCardSpec> = rows.mapIndexed { index, row ->
    val key = (row["_entryId"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        ?: (row["_clientKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        ?: "row-$index"
    when (recordType) {
        DwWorkshopRecordType.PROTOTYPE -> DwCardSpec(
            key = key,
            recordType = recordType,
            id = workshopCodeIdForRow(row),
            title = dwCardRowTitle(source.entity, row, index),
            // The designer's own prototype code, when they gave one — it is what is written on the
            // object already, and a tag that agrees with the object is a tag somebody trusts.
            //
            // `materials` is a TAGS field and therefore an ARRAY, so it goes through `DwValues.list`.
            // `DwValues.text` would join it too, but through a path that also stringifies an object,
            // and a line that silently printed "{}" is worse than one that never prints.
            lines = listOf(
                DwValues.text(row["prototypeCode"]),
                DwValues.list(row["materials"]).joinToString(", "),
            ).filter { it.isNotBlank() },
        )

        DwWorkshopRecordType.ARTISAN -> DwCardSpec(
            key = key,
            recordType = recordType,
            // The ARTISAN's repository id, not the roster row's. A card is scanned to open a person's
            // record, and the roster row is this workshop's copy of them; a hand-typed participant has
            // no artisan behind them and gets a refusal saying so.
            id = artisanRefKey(source.entity)?.let { DwValues.text(row[it]) },
            title = dwCardRowTitle(source.entity, row, index),
            // Serial and village, and nothing else. Not the artisan card number, not the phone — see
            // the header on [DwCardSpec].
            lines = listOf(
                DwValues.text(row["serialNo"]).let { if (it.isBlank()) "" else "S. No. $it" },
                DwValues.text(row["village"]),
            ).filter { it.isNotBlank() },
        )
    }
}

/**
 * Turn one card's input into a symbol, or into the sentence that says why not.
 *
 * A RESULT AND NEVER A THROW, because the caller is a sheet building thirty tags at once: one
 * prototype whose row has never been saved must produce one refusal on one tag, not an exception that
 * empties the page a designer is about to print.
 *
 * Level Q (a quarter of the symbol recoverable) rather than the usual L, because these are printed on
 * paper that spends a fortnight in a workshop: a thumbprint, a smear of dye or a fold across a corner
 * is the normal condition of a tag by day ten, not the exceptional one.
 */
fun renderWorkshopCard(spec: DwCardSpec): DwCardRender {
    val encoded = encodeWorkshopCode(spec.recordType, spec.id)
    if (encoded is DwEncodeResult.Refused) return DwCardRender.Refused(spec, encoded.message)
    val ok = encoded as DwEncodeResult.Ok
    return try {
        DwCardRender.Ok(
            spec = spec,
            code = ok.code,
            printed = formatWorkshopCodeForPrint(ok.code),
            symbol = DwQrEncode.encode(ok.code, DW_CARD_ECC_LEVEL),
        )
    } catch (e: DwQrEncodeException) {
        // The encoder's own sentence, which is the web's word for word. Two spellings of one refusal
        // read as two different faults to a designer holding both sheets.
        DwCardRender.Refused(spec, e.message ?: "This code could not be drawn.")
    }
}

// --------------------------------------------------------------------------------------
// Reading one back
// --------------------------------------------------------------------------------------

/** A row on this device that a scanned or typed code points at. */
data class DwCardHit(val label: String, val detail: String)

/**
 * What the LOCAL copy of this workshop can say about a reference, or null when it can say nothing.
 *
 * NULL IS NOT A REFUSAL, and the distinction is the whole reason this returns a nullable rather than
 * a sealed result. A prototype tag that matches nothing here has nowhere else to be looked for, so the
 * caller refuses it; an artisan card that is not on this workshop's roster may still be a person in
 * the repository, so the caller may ask the server. Deciding that here would put a network call in a
 * pure function and would take the choice away from the one screen that knows whether there is signal.
 *
 * The refusal sentence itself is [unresolvedWorkshopCodeMessage] and is deliberately the same for
 * "there is no such record" and "there is one and it is not yours" — see its own KDoc.
 */
fun findWorkshopCodeInDraft(
    ref: DwWorkshopCodeRef,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    remoteStages: Map<String, StageBucketDto>,
): DwCardHit? {
    val source = workshopCardSource(ref.recordType, schema) ?: return null
    val rows = workshopCardRows(source, draft, remoteStages)

    if (ref.recordType == DwWorkshopRecordType.PROTOTYPE) {
        val index = rows.indexOfFirst { workshopCodeMatchesRow(ref, it) }
        if (index < 0) return null
        val code = DwValues.text(rows[index]["prototypeCode"])
        return DwCardHit(
            label = dwCardRowTitle(source.entity, rows[index], index),
            detail = buildString {
                append(source.stage.title)
                append(" · row ${index + 1}")
                if (code.isNotBlank()) append(" · $code")
            },
        )
    }

    val refKey = artisanRefKey(source.entity) ?: return null
    val index = rows.indexOfFirst { DwValues.text(it[refKey]) == ref.id }
    if (index < 0) return null
    return DwCardHit(
        label = dwCardRowTitle(source.entity, rows[index], index),
        detail = "On this workshop's roster · ${source.stage.title}",
    )
}
