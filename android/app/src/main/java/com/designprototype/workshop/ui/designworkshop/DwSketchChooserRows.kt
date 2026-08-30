package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StagePush
import com.designprototype.workshop.data.collections
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.entityKey
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.data.singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The pure half of the Sketches & prototypes chooser: which row, which stage, which workshop.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS FILE EXISTS AT ALL, GIVEN THAT THE SCREEN COULD HOLD ALL OF IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The screen now WRITES — it mints a sketch row and a prototype row into the stage's own draft — and
 * every decision on the way to that write is one a JVM test can hold to account with no Context, no
 * Compose and no filesystem: what a row is called on a picker, what a freshly minted row looks like,
 * where it is filed, and which workshop the screen opens on. That is the same split
 * `data/DwDesignRatings.kt` makes for the review half and `DwWorkshopCreation` makes for the create
 * half, and for the same stated reason: a plan can be asserted about on a laptop, where a coroutine
 * that writes to disk can only be asserted about by somebody reproducing a courtyard.
 *
 * It lives in the `ui.designworkshop` package rather than in `data` because it is the chooser's own
 * vocabulary — `data/` is shared by the stage screen, the sync engine and the report writer, and a
 * helper named after one screen has no business there.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE ROW IDENTITY, AND WHY IT IS NOT INVENTED HERE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A [DraftRow]'s `id` is `dwRowId(entityKey, rowId)` — the entity key, the separator, and a UUID this
 * device mints. `buildStageBody` (data/WorkshopSync.kt) sends the half after the separator as the
 * row's `_clientKey`, which is the identity `save_stage` matches a row on, and derives `ordinal` from
 * the ARRAY ORDER at send time. So a new row needs exactly one thing to be a real, syncable row: an
 * id in that shape. Nothing here writes `_clientKey`, `_entryId` or `_ordinal` into the values —
 * those three are the protocol's, `dwFoldServerStage` fills `_entryId` in when the repository answers
 * with one, and a client that wrote its own would be inventing a server identity.
 */

// --------------------------------------------------------------------------------------
// The two entities, by the registry's own keys
// --------------------------------------------------------------------------------------

/**
 * The registry entity a sketch is a row of. `design_ratings.RATEABLE_ENTITIES`' first member and the
 * web's `stageKeyForEntity` argument, spelled once so the two tabs and the write cannot drift.
 */
internal const val DW_CHOOSER_SKETCH_ENTITY: String = "sketch"

/** The registry entity a prototype is a row of. See [DW_CHOOSER_SKETCH_ENTITY]. */
internal const val DW_CHOOSER_PROTOTYPE_ENTITY: String = "prototype"

/**
 * The four media fields the web's Upload tab writes into, by registry key.
 *
 * THE KEYS ARE THE WEB'S, CHARACTER FOR CHARACTER — `UploadTabHost.tsx` declares `SKETCH_IMAGE`,
 * `SKETCH_LINE_ART`, `PROTOTYPE_MODEL` and `PROTOTYPE_TURNTABLE` as exactly these four strings. They
 * are keys and not labels: the LABEL is read off the registry at render time, so the card says
 * "Line art / vector file" because that is what the schema calls it today, and a label edited on the
 * server changes both clients at once. A hardcoded label here would be the fifth copy of a string the
 * registry already publishes, and the one that goes stale.
 *
 * ABSENCE IS A STATE AND NOT AN ERROR. A build talking to a registry that does not declare one of
 * these draws no card for it and says so — see `DW_SKETCH_CHOOSER_NO_MEDIA_FIELDS`. That is the same
 * answer the web gives ("This build's field registry does not declare the image, line-art and
 * 3D-model fields this tab writes into"), and it is not a permission.
 */
internal val DW_CHOOSER_SKETCH_FIELDS: List<String> = listOf("image", "lineArtFile")

/** The prototype half of [DW_CHOOSER_SKETCH_FIELDS] — the 3D model and the turn of photographs. */
internal val DW_CHOOSER_PROTOTYPE_FIELDS: List<String> = listOf("turntablePhotos", "modelFile")

// --------------------------------------------------------------------------------------
// Reading a row
// --------------------------------------------------------------------------------------

/** One row's text value for a key, trimmed, or "" for anything that is not a string. */
private fun DraftRow.chooserText(key: String): String {
    val value = values[key]
    // `is JsonNull` FIRST AND NOT A BARE CAST, which is the trap `DwDesignRatings.dwEntryId` already
    // documents: JsonNull IS a JsonPrimitive, so reading `.content` off one hands back the
    // four-character string "null" — a label that is not blank, matches nothing, and would sail past
    // every emptiness test below to be printed on a picker as the name of a sketch.
    if (value !is JsonPrimitive || value is JsonNull || !value.isString) return ""
    return value.content.trim()
}

/**
 * What a row is called on the picker: its own name, then its identifier, then an honest placeholder.
 *
 * THE THREE KEYS AND THEIR ORDER ARE THE WEB'S `rowLabel` (`frontend/components/sketches/
 * stageRows.ts`), read 2026-08-28, and they are in that order for a reason a reader can check: a
 * sketch that has been named is looked for by its name, and one that has not is looked for by the
 * number written on the paper. Re-ordering them would make the same workshop's picker read
 * differently on the two clients for the same rows.
 *
 * "Untitled 3" AND NOT A BLANK ROW. A picker option with an empty label is an option nobody can
 * choose deliberately and TalkBack reads as "button"; the position at least tells two of them apart,
 * and it is the position the designer can see on the stage form. It is 1-based because it is read by
 * a person.
 *
 * IT IS DELIBERATELY NOT `dwCollectionRowTitle`. That one is private to `StageScreen.kt` AND answers
 * a different question — it consults the registry's `labelField`, which is right on a form that has
 * the registry entity in hand and wrong here, where the two clients must agree on the label for the
 * same row without one of them having read a schema.
 */
internal fun dwChooserRowLabel(row: DraftRow, index: Int): String {
    for (key in listOf("name", "sketchNo", "prototypeCode")) {
        val value = row.chooserText(key)
        if (value.isNotEmpty()) return value
    }
    return "Untitled ${index + 1}"
}

/**
 * The stable key the picker addresses a row by — the same one the write below matches on.
 *
 * THE WHOLE [DraftRow.id], not the UUID half. `rowsFor` filters on the entity prefix, so two
 * entities' rows can share a UUID in principle and the prefixed form is the one that is unique
 * within a stage. The web's `rowKeyOf` returns `_clientKey ?? _entryId`, which is the same identity
 * asked of a store that files rows differently; here the id IS both, because `buildStageBody` splits
 * the client key back out of it at send time.
 */
internal fun dwChooserRowKey(row: DraftRow): String = row.id

// --------------------------------------------------------------------------------------
// Minting a row, and filing it
// --------------------------------------------------------------------------------------

/**
 * A brand-new, empty row of [entityKey], carrying [rowId] as its client key.
 *
 * EMPTY VALUES, DELIBERATELY. The stage form is where a sketch is described, and a row seeded here
 * with a guessed name would put a value into a real record that no person typed —
 * `entry_provenance` would then attribute it to the designer. The row exists so that a photograph
 * has somewhere to land and so that the stage form has a row to open; [dwChooserRowLabel] prints
 * "Untitled 1" for it until somebody names it, which is the truth.
 *
 * [rowId] is passed in rather than minted here so the caller can be a test. Callers on the screen
 * pass `UUID.randomUUID().toString()`.
 */
internal fun dwChooserNewRow(entityKey: String, rowId: String): DraftRow =
    DraftRow(id = dwRowId(entityKey, rowId), values = emptyMap())

/**
 * Append [row] to [entityKey]'s collection inside one stage's draft, leaving everything else alone.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * A `copy()` AND NOT A REBUILT `StageDraft`, WHICH IS THE WHOLE SAFETY OF THIS FUNCTION
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `StageScreen.persistLocally` rebuilds the WHOLE record on every debounced save, and its own
 * comments enumerate what that costs: `custom`, `customSeen`, `stageSeen`, `mediaIds`, `completedAt`,
 * `notes`, `emptiedEntities` and `deletedRowKeys` each have to be carried across by hand, and every
 * one of those lines exists because a plain constructor call silently reset it. It has to rebuild,
 * because it is writing a whole form.
 *
 * THIS IS NOT WRITING A FORM. It adds one row and nothing else, so it takes the record that is on
 * disk and copies it — which means there is no field for a future addition to `StageDraft` to be
 * forgotten in. A reader wondering "does this clear the designer's custom answers?" can see that it
 * cannot, rather than having to audit nine hand-copied fields.
 *
 * ── THE TWO RECORDS THAT MUST NOT BE TOUCHED, AND ARE NOT ────────────────────────────────────────
 *
 * `emptiedEntities` is the record that a designer DELETED this collection, and it survives a `copy()`
 * untouched. `persistLocally` filters it against what is on screen — "a collection that holds rows
 * again is not emptied" — and doing that here would be wrong twice over: the filter is only safe
 * because `dwStageReadPlan` guarantees a non-empty collection means the designer, and this function
 * has not read the plan. Leaving the key alone means the next save from the STAGE SCREEN decides,
 * which is where that decision has always been made. The cost is one stale key on a stage the
 * designer emptied and then added to from here; `persistLocally` clears it on the next open, and the
 * sweep declines to delete a row the same payload names, so nothing is lost either way.
 *
 * ── THE STAGE MAY NOT EXIST YET, AND THEN IT IS SEEDED FROM THE SPEC ─────────────────────────────
 *
 * A workshop this device has opened but never walked to stage 11 holds no `StageDraft` for it. The
 * seeded one copies `stageId`, `title` and `order` off the registry spec and `requiredKeys` off the
 * singleton's required fields, which is exactly what `persistLocally` puts there — those four are
 * what lets the workshop list score completeness with no registry and no network.
 *
 * **`stageSeen` IS LEFT AT ITS DEFAULT OF FALSE ON A SEEDED STAGE, AND THAT IS LOAD-BEARING.** It is
 * the flag `buildStageBody` reads as `merge = !authoritative`. A stage this device has never read
 * must go up as a MERGE, so the repository keeps every row this payload does not name — otherwise
 * one sketch added from this chooser would sweep away the seven a colleague filed on the web. Only
 * `dwFoldServerStage` may set it, and only by actually reading the repository's copy.
 */
internal fun dwChooserAppendRow(
    spec: StageDto,
    existing: StageDraft?,
    row: DraftRow,
): StageDraft {
    val base = existing ?: StageDraft(
        stageId = spec.key,
        title = spec.title,
        order = spec.number,
        requiredKeys = spec.singleton?.liveFields.orEmpty().filter { it.required }.map { it.key },
    )
    return base.copy(rows = base.rows + row)
}

/**
 * The rows of one entity, as the draft holds them — the picker's options, in ordinal order.
 *
 * A thin name over `StageDraft.rowsFor` so the chooser reads the rows through one function rather
 * than three call sites that could each grow a different filter. Null is an ordinary state: a stage
 * this device holds nothing for has no rows, which is not the same as a workshop with no sketches and
 * the screen says which.
 */
internal fun dwChooserRows(stage: StageDraft?, entityKey: String): List<DraftRow> =
    stage?.rowsFor(entityKey).orEmpty()

/** The registry entity that declares [entityKey] as a COLLECTION on [spec], or null. */
internal fun dwChooserEntity(spec: StageDto?, entityKey: String): EntityDto? =
    spec?.collections?.firstOrNull { it.key == entityKey }

/**
 * The media fields of [entity] this tab offers, in the order [keys] names them.
 *
 * ORDERED BY THE CALLER'S LIST AND NOT BY THE REGISTRY'S, because the two halves lead with different
 * things and the web's Upload tab does the same: a sketch leads with the photograph of the drawing,
 * a prototype leads with the turn of photographs — which is the one form of a prototype that reaches
 * the printed page (see `DW_PROTOTYPE_3D_IN_THE_REPORT`). A field the registry does not declare is
 * simply absent from the result; the caller says so on screen rather than drawing an empty card.
 */
internal fun dwChooserMediaFields(entity: EntityDto?, keys: List<String>): List<FieldDto> {
    val live = entity?.liveFields.orEmpty()
    return keys.mapNotNull { key -> live.firstOrNull { it.key == key } }
}

/**
 * Which media ids one row currently holds for one field.
 *
 * A media field stores either a single id (IMAGE, FILE) or an array of them (IMAGE_LIST), and both
 * shapes reach this device — the registry decides which. Read as a LIST in both cases so the capture
 * card, which always speaks lists, has one shape to work with. A value that is neither is treated as
 * nothing held, which is the conservative reading: showing a card as empty costs a designer a second
 * look at the stage form, where drawing a reference this build cannot parse would offer them a
 * "remove" button for something it cannot identify.
 */
internal fun dwChooserHeldMedia(row: DraftRow?, fieldKey: String): List<String> {
    val value = row?.values?.get(fieldKey) ?: return emptyList()
    return when {
        value is JsonArray ->
            value.mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
                    ?.takeIf { it.isNotBlank() }
            }

        value is JsonPrimitive && value !is JsonNull && value.isString ->
            listOfNotNull(value.content.takeIf { it.isNotBlank() })

        else -> emptyList()
    }
}

/**
 * Write [ids] back into [fieldKey] on the row [rowKey] names, and return the whole collection.
 *
 * SINGLE OR LIST DECIDED BY [asList], which the caller reads off the registry's field type rather
 * than guessing from the count. A one-element array in a single-file field is not what `coerce_value`
 * expects and a bare string in an IMAGE_LIST is not either, and the failure mode is silent on this
 * device and only visible in the document a ministry receives a fortnight later.
 *
 * AN EMPTY SELECTION REMOVES THE KEY rather than storing "" or `[]`, which is `StageScreen.put`'s own
 * rule — hundreds of fields across twenty-two stages would otherwise accumulate a null apiece to be
 * re-sent on every metered sync.
 *
 * A [rowKey] that names no row returns the list unchanged. That is reachable: the designer's chosen
 * row can be swept out from under them by a fold while the picker was open, and a write that
 * invented a row to satisfy the reference would be a row nobody asked for.
 */
internal fun dwChooserWriteMedia(
    rows: List<DraftRow>,
    rowKey: String,
    fieldKey: String,
    ids: List<String>,
    asList: Boolean,
): List<DraftRow> = rows.map { row ->
    if (dwChooserRowKey(row) != rowKey) {
        row
    } else {
        val values = when {
            ids.isEmpty() -> row.values - fieldKey
            asList -> row.values + (fieldKey to JsonArray(ids.map { JsonPrimitive(it) }))

            else -> row.values + (fieldKey to JsonPrimitive(ids.first()))
        }
        row.copy(values = values)
    }
}

/**
 * Write one SCALAR — a measured dimension — back into [fieldKey] on the row [rowKey] names.
 *
 * ── WHY THIS IS A SECOND FUNCTION AND NOT A FLAG ON [dwChooserWriteMedia] ─────────────────────
 *
 * They look alike and they are not the same write. That one takes a list of media ids and decides
 * between a bare string and an array by the REGISTRY's declared type; this one takes a value that has
 * already been through `DwValues.coerce` — the same coercion a typed answer goes through, so a
 * measurement cannot enter the draft in a shape typing could not have produced — and stores it
 * exactly as handed over. Folding the two together would mean one function whose behaviour depended
 * on which of two unrelated arguments was null, and the shape it writes is the thing `coerce_value`
 * refuses silently on this device and visibly in a document a ministry receives a fortnight later.
 *
 * A NULL VALUE REMOVES THE KEY rather than storing `null`, which is `StageScreen.put`'s own rule and
 * [dwChooserWriteMedia]'s: hundreds of fields across twenty-two stages would otherwise accumulate a
 * null apiece to be re-sent on every metered sync.
 *
 * A [rowKey] that names no row returns the list unchanged, for [dwChooserWriteMedia]'s stated reason:
 * a fold can sweep the chosen row out from under the designer, and a write that invented a row to
 * satisfy the reference would be a row nobody asked for — in a collection a report reads.
 */
internal fun dwChooserWriteScalar(
    rows: List<DraftRow>,
    rowKey: String,
    fieldKey: String,
    value: JsonElement?,
): List<DraftRow> = rows.map { row ->
    if (dwChooserRowKey(row) != rowKey) {
        row
    } else {
        val values = if (value == null || value is JsonNull) {
            row.values - fieldKey
        } else {
            row.values + (fieldKey to value)
        }
        row.copy(values = values)
    }
}

/**
 * Replace one entity's rows inside a stage draft, leaving every other entity's rows where they are.
 *
 * `StageDraft.rows` is ONE flat list holding every collection on the stage, keyed by the entity
 * prefix on each row's id — so a naive `copy(rows = mine)` would delete stage 13's
 * `prototypeStageLog` and `prototypeMaterial` rows the moment somebody attached a photograph to a
 * prototype. The filter is `entityKey()`, which is the same accessor `rowsFor` reads.
 *
 * THE REPLACED ROWS GO WHERE THE OLD ONES WERE — at the end, after the untouched entities — rather
 * than being interleaved back into their original absolute positions. That is safe because ordinal
 * is per-entity: `buildStageBody` walks `rowsFor(entity.key)` and numbers from zero within it, so
 * the only order that reaches the wire is the order WITHIN one collection, which this preserves
 * exactly.
 */
internal fun dwChooserReplaceRows(
    stage: StageDraft,
    entityKey: String,
    rows: List<DraftRow>,
): StageDraft = stage.copy(rows = stage.rows.filterNot { it.entityKey() == entityKey } + rows)

// --------------------------------------------------------------------------------------
// Which workshop the screen opens on
// --------------------------------------------------------------------------------------

/**
 * The workshop the chooser selects for the designer, out of what is actually on the wire.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * HOW "MOST RECENT" IS DERIVED, AND WHY IT IS ASKED OF THE SERVER FIRST
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The owner asked that the selection default to the most recently accessed design workshop rather
 * than being chosen every time. "Most recently accessed" is not a column any client can see:
 * `DesignWorkshopViewer.createdAt` — when this account was ADDED to a workshop — is not on
 * [DesignWorkshopDto] and no endpoint publishes it per row. `DesignWorkshopPicker.kt` records the
 * same finding and the same resolution, which is why this function takes the server's answer as its
 * first argument: `GET /design-workshops/default-for-me` decides, once, and both clients read it.
 * Its `reason` is `"GRANTED"` (the workshop you were most recently added to) or `"CREATED"` (the one
 * you most recently opened), and `designWorkshopPrefillNote` turns that into the sentence this
 * screen prints under the picker.
 *
 * ── THE FALLBACK, AND EXACTLY WHAT IT IS AND IS NOT ──────────────────────────────────────────────
 *
 * When the server has no answer — an older deployment with no such route, a request that failed, or
 * a genuine "you have no default" — the chooser falls back to the FIRST ROW OF THE LIST IT ALREADY
 * HAS. That is `GET /design-workshops` page 1, which `list_design_workshops` orders newest-first, so
 * the fallback is "the most recently created workshop you can open". It is a different question from
 * the one above and the screen must not print the server's sentence over it, which is why this
 * function returns only an id and the note is carried separately.
 *
 * `updatedAt` is deliberately NOT used to rank the fallback, though it is on the wire. It moves when
 * anybody edits any stage of the workshop — including a background sync from a colleague's handset —
 * so ordering by it would make the chooser's default hop between workshops for reasons the designer
 * cannot see and did not cause. A stable, explainable default beats a cleverer one that moves.
 *
 * ── AND A CHOICE ALREADY MADE ALWAYS WINS ────────────────────────────────────────────────────────
 *
 * [chosen] is what the designer picked in this session. It is honoured whenever it is still in the
 * list, so a load that re-runs — a "Try again", a returning screen — cannot move the selection out
 * from under somebody who is in the middle of attaching a photograph to a row of it. An id that is
 * no longer in the list is dropped rather than kept: the workshop's grant can be revoked between two
 * reads, and holding a selection the list no longer offers would leave the tabs below scoped to a
 * workshop whose every request now answers 404.
 */
internal fun dwChooserDefaultWorkshop(
    workshops: List<DesignWorkshopDto>,
    serverDefaultId: String?,
    chosen: String,
): String {
    if (workshops.isEmpty()) return ""
    if (chosen.isNotBlank() && workshops.any { it.id == chosen }) return chosen
    val fromServer = serverDefaultId?.trim().orEmpty()
    if (fromServer.isNotBlank() && workshops.any { it.id == fromServer }) return fromServer
    return workshops.first().id
}

// `dwChooserWorkshopHint` and `dwChooserWorkshopLabel` used to live here — the workshop-chooser's
// own copy of the label/hint pair `WorkshopOptions.kt` now provides as `designWorkshopLabel` and
// `designWorkshopHint`. Retired 2026-08-30 once `SketchesAndPrototypesScreen.kt` (their only
// caller) was moved onto those shared functions: the local copy had drifted from the one
// `DesignWorkshopField` draws — it carried no status word, so a SUBMITTED workshop and one still
// running read as the same kind of row here and not on any other picker in the app. See
// `WorkshopOptions.kt`'s own file header, which named this pair as two of the "three copies of the
// same hint builder... still in the tree" it was written to retire.

// --------------------------------------------------------------------------------------
// Keeping a selection, and saying what a save actually achieved
// --------------------------------------------------------------------------------------

/**
 * The row selection to hold after a re-read: the one the designer chose, or the first row.
 *
 * ONLY EVER FILLED IN, NEVER MOVED. The Upload tab re-reads after every write and after every "Try
 * again", and a designer halfway through attaching the third of four photographs to row four must
 * not be put back on row one by a refresh they did not ask for. So a selection that is still in the
 * list survives untouched.
 *
 * A SELECTION THAT IS NO LONGER IN THE LIST IS DROPPED rather than kept, and that is not tidying:
 * the row can genuinely go — a colleague deletes it, or `dwFoldServerStage` honours a deletion this
 * device was holding — and a picker still naming it would leave the capture cards beneath it writing
 * into a row that does not exist. Falling back to the first row is what the web's upload host does
 * (`setSketchRow(current => current || …)`), and it means a designer who never touched the picker
 * still has somewhere to attach to.
 */
internal fun dwChooserKeepSelection(chosen: String, rows: List<DraftRow>): String {
    if (rows.isEmpty()) return ""
    if (chosen.isNotBlank() && rows.any { dwChooserRowKey(it) == chosen }) return chosen
    return dwChooserRowKey(rows.first())
}

/**
 * What one save actually achieved — the local write, and whether the repository has it yet.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * FIVE ANSWERS AND NOT ONE "SAVED", FOR THE REASON `dwPushNote` GIVES ABOUT ARRANGEMENTS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The row is durable on this phone the moment `updateStage` returns — that is the whole reason the
 * add does not wait for a connection. What varies is whether the repository has it, and each of
 * these states has a different next move, so a single "Saved" would be true of all of them and
 * useful for none. `data/DwDesignRatings.dwPushNote` makes the same split for a reorder; this is its
 * sibling, and it is NOT that function because every one of its sentences says "arrangement", which
 * is the wrong noun for a sketch.
 *
 * A NULL [push] IS THE PUSH THAT THREW — the request itself failed rather than answering. It is
 * worded as the local success it is, with the sending left open, because the background sync pass
 * owns the retry and a sentence claiming failure would send a designer to re-do work that is
 * already safe.
 *
 * [what] NAMES THE THING so the sentence reads about a sketch rather than about "it". Passed in
 * rather than derived because this function does not know which half called it, and guessing from a
 * stage key would be a fourth place the two entities are spelled.
 */
internal fun dwChooserSaveNote(push: StagePush?, what: String): String = when (push) {
    is StagePush.Sent -> "$what is saved on this phone and has reached the repository."
    StagePush.AlreadySent -> "$what is saved. The repository already holds it."
    is StagePush.HeldBack ->
        "$what is saved on this phone. Sending it is waiting on ${push.files} attachment" +
            "${if (push.files == 1) "" else "s"} from this stage that are still only on this " +
            "device — the sync tray carries them, and this goes up with them."
    StagePush.NoRemoteYet ->
        "$what is saved on this phone. This workshop has not been created on the repository yet, " +
            "so there is nowhere to send it until it is."
    StagePush.NothingToSend ->
        "$what is saved on this phone. There is no local copy of this stage to send, so it stays " +
            "here until this phone has read that stage once."
    StagePush.NotSent, null ->
        "$what is saved on this phone, but sending it did not complete. It goes up with the next " +
            "sync — the sync tray follows it."
}

/**
 * What to say between the local write and the repository's answer.
 *
 * A SEPARATE SENTENCE FROM EVERY BRANCH OF [dwChooserSaveNote], and it has to be: those five each
 * report what the push DID, and at this moment the push has not been attempted. Reusing the
 * `NotSent` wording here — "sending it did not complete" — would tell a designer a request had
 * failed before it had been made, which is the same class of untruth as drawing a failed load as an
 * empty list.
 *
 * IT CLAIMS THE LOCAL WRITE AND NOTHING ELSE, which is exactly what is true: `updateStage` has
 * returned, so the row or the attachment is on this phone and survives the app being killed. The
 * ellipsis is the only promise about the repository, and it is upgraded or corrected the moment
 * `pushStage` answers.
 */
internal fun dwChooserSendingNote(what: String): String =
    "$what is saved on this phone. Sending it…"
