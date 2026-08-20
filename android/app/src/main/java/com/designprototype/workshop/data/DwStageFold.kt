package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading the server's copy of a stage INTO a draft that already holds work, without losing either.
 *
 * ── WHY THIS EXISTS AT ALL: AUTHORITY HAD NO WAY BACK ────────────────────────────────────────────
 *
 * [StageDraft.stageSeen] is earned by one thing only, a successful `GET .../stages/{key}`, and
 * `StageScreen` used to attempt that read in exactly one situation: a stage this device held NOTHING
 * for. "The local draft wins whenever it holds anything" is the right rule for whose VALUES survive
 * — the work is done in a courtyard and the server's copy can only be older — but it was also, by
 * accident, the rule for whether the read happened at all. So a stage opened once without signal and
 * typed into could never be read again, and therefore could never become authoritative again, for the
 * life of the draft.
 *
 * That did not show while `recordStageSent` handed the authority out for free after any successful
 * save. Taking that away (see [StageDraft.stageSeen]) would have made "not authoritative" permanent
 * for every stage holding work — no clearance and no row deletion would ever reach the server again,
 * on any handset, for ever. This function is the other half of that change: the read is now attempted
 * on every open of a stage that has not been seen, and what comes back is FOLDED rather than adopted.
 *
 * ── THE FOLD IS "ADD WHAT THIS DEVICE HAS NEVER SEEN", AND NOTHING ELSE ──────────────────────────
 *
 * Every key and every row already in the draft is kept exactly as it is, values and all. The only
 * thing that changes is that keys and rows the SERVER holds and this device does not are added. Three
 * consequences, all of them intended:
 *
 *  * nothing the designer typed is overwritten, which is the local-wins rule kept intact;
 *  * after the fold the draft is a superset of the server's copy, which is precisely what makes
 *    [StageDraft.stageSeen] honest — "delete what I do not name" can no longer name anything the
 *    designer has not been shown;
 *  * a value the designer CLEARED on this device comes back — because on a stage this device had
 *    never read, that clearance never reached the server, so the server does still hold it and
 *    showing it is the truth rather than a resurrection. It can be cleared again, and this time the
 *    clearance propagates, because the fold has earned the authority that carries it.
 *
 * ── AND ONE THING THAT DOES NOT COME BACK: A COLLECTION THE DESIGNER EMPTIED ──────────────────────
 *
 * A row in an entity named by [StageDraft.emptiedEntities] is NOT folded back in. That is the one
 * asymmetry in this function and it is not an inconsistency, because the two cases differ in the only
 * way that matters here — WHETHER THE DESIGNER'S ACTION LEFT A RECORD:
 *
 *  * a cleared value leaves none. `StageScreen`'s `put` REMOVES a key whose value went blank rather
 *    than storing a null, so "cleared this morning" and "never typed" are the same absence on disk.
 *    The fold cannot honour an instruction it cannot read, and re-filling the box is the only
 *    behaviour available to it.
 *  * an emptied collection leaves one, deliberately and for exactly this reason. Deleting the LAST
 *    row of a collection is invisible in a payload — entries are built from `rowsFor(key)`, which is
 *    simply empty — so the deletion has to be STATED, and [StageDraft.emptiedEntities] is where the
 *    designer's action is written down. Folding the server's rows back in over that record would
 *    reverse the deletion: the next payload would name every row again, and `replaceCollections`
 *    would sweep nothing. The record would travel and mean nothing, which is what three comments in
 *    this repository claimed was not happening.
 *
 * ── NOR A SINGLE ROW THE DESIGNER DELETED OUT OF A COLLECTION THAT STILL HOLDS OTHERS ────────────
 *
 * [StageDraft.deletedRowKeys] is the same record one level down, and it is here for the same reason:
 * deleting one process step out of three is ALSO invisible in a payload, because entries are built
 * from `rowsFor(key)` and that list simply no longer contains it. The record was added (and is read
 * by `StageScreen.onRowsChange`, by `statusOf`'s `unsentDeletions` and by [dwOwedDeletions]) — and
 * this function, the one reader whose job is to decide which of the server's rows come back, did not
 * consult it. So the fold re-added the deleted row, `heldKeys` then named it in the next payload, and
 * `persistLocally`'s row filter dropped the key from `deletedRowKeys` because the row was in the draft
 * again: the deletion was reversed AND its record erased, by the one action the status screen tells
 * the designer to take ("Open the stage once with a connection"). That is the identical failure the
 * emptied-collection branch below was written to stop, arriving through the sibling field, and the two
 * halves must stay symmetrical — a rule stated once for `emptiedEntities` and not for `deletedRowKeys`
 * is how this defect existed at all.
 *
 * SO THE DESIGNER'S EXPLICIT ACTION WINS OVER AN INFERENCE, and the collateral is stated rather than
 * hidden: a row another device added into that entity since the deletion is swept with the rest. It is
 * SOFT-deleted server-side and recoverable by its client key, whereas the loss in the other direction
 * — a deliberate deletion silently reversed, with no record anywhere that it was ever made — is not
 * recoverable by anybody, because nobody knows to look. [DwStageFold.sweptRows] counts what this fold
 * is about to let the next save remove, so the designer can find out rather than merely be able to.
 *
 * It is therefore ANNOUNCED. [DwStageFold.added], [DwStageFold.addedRows] and [DwStageFold.sweptRows]
 * count what appeared and what will go, so the screen can say so; a stage that gains six process steps
 * between one open and the next, silently, is a designer being gaslit by their own tool.
 *
 * ── WHAT IT REFUSES TO DO ────────────────────────────────────────────────────────────────────────
 *
 * It never renumbers and never rewrites a value, and it deletes nothing itself — the sweep is the
 * SERVER's, on the next save, and all this does is decline to re-add rows the designer already
 * deleted. It does not touch [StageDraft.notes], [StageDraft.mediaIds], [StageDraft.completedAt] or
 * [StageDraft.emptiedEntities] — a deletion the designer recorded is still owed to the server and is
 * carried forward untouched, to go up on the first save after this fold, which is the first save
 * entitled to carry it and now the first save that can make it mean anything.
 */
data class DwStageFold(
    /** The draft to keep, with the server's unseen answers folded in and [StageDraft.stageSeen] set. */
    val draft: StageDraft,
    /** Core field keys that came down from the server and were not on this device. */
    val added: List<String>,
    /** Rows that came down from the server and were not on this device, by entity key. */
    val addedRows: Map<String, Int>,
    /** Custom field keys that came down from the server and were not on this device. */
    val addedCustom: List<String>,
    /**
     * Rows the server holds in a collection the designer EMPTIED, which this fold declined to add back
     * and the next save will therefore sweep. By entity key.
     *
     * THE COLLATERAL OF HONOURING A DELETION, MADE VISIBLE. These are rows this device has never shown
     * anybody: the designer deleted the collection while offline, and between then and this read
     * somebody else added to it. Honouring the deletion is the right answer — see the header — but a
     * designer is owed the chance to know that their deletion is about to take something with it, in
     * time to do something about it, rather than being able in principle to recover it afterwards from
     * a client key nobody has written down.
     *
     * Counted and named per entity rather than per row: the rows themselves are not drawn anywhere by
     * the time this is read, and a sentence quoting the contents of six rows the designer has already
     * decided to delete is a sentence nobody finishes.
     */
    val sweptRows: Map<String, Int> = emptyMap(),
    /**
     * Rows the server holds that the designer deleted INDIVIDUALLY on this phone — see
     * [StageDraft.deletedRowKeys] — which this fold declined to add back. By entity key.
     *
     * ITS OWN COUNTER AND NOT [sweptRows], because the two are different sentences to the designer.
     * A swept row is COLLATERAL: somebody else's row, in a collection this designer emptied, that
     * they have never been shown and are about to remove. A declined row is THEIR OWN deletion,
     * holding: nothing is being lost that they did not choose. Folding the two into one figure would
     * make the ordinary case (delete a row, reopen the stage with signal) raise the alarming sentence
     * written for the collateral case, and a warning that fires on the normal path is a warning
     * nobody reads by the time it matters.
     */
    val declinedRows: Map<String, Int> = emptyMap(),
) {
    val addedRowCount: Int get() = addedRows.values.sum()

    val sweptRowCount: Int get() = sweptRows.values.sum()

    val declinedRowCount: Int get() = declinedRows.values.sum()

    val isNothingNew: Boolean
        get() = added.isEmpty() && addedRows.isEmpty() && addedCustom.isEmpty() &&
            sweptRows.isEmpty() && declinedRows.isEmpty()

    /**
     * What to tell the designer, or null when the fold changed nothing they can see.
     *
     * Counts things rather than naming all of them, for the reason [WorkshopSyncStatus.summary] does:
     * a sentence that lists forty keys is a sentence nobody finishes. The FIRST few keys are named
     * because "3 answers appeared" with no hint of which three is not something anybody can check.
     *
     * TWO SENTENCES AND NOT ONE, because they point in opposite directions. What was ADDED is
     * reassurance — nothing you typed was touched, and here is what else the repository had. What will
     * be SWEPT is the opposite, and it is the half a designer may want to act on before the next save
     * carries it: it is said last, in its own words, and it is not folded into the same list. A fold
     * that only sweeps still speaks — the "added" clause is built only when there is something in it,
     * which the sweep-only case does not reach.
     */
    val notice: String?
        get() {
            if (isNothingNew) return null
            val parts = ArrayList<String>(3)
            if (added.isNotEmpty()) {
                parts += "${added.size} answer${if (added.size == 1) "" else "s"} " +
                    "(${added.take(4).joinToString(", ")}${if (added.size > 4) ", …" else ""})"
            }
            if (addedRows.isNotEmpty()) {
                val count = addedRowCount
                parts += "$count row${if (count == 1) "" else "s"} " +
                    "in ${addedRows.keys.joinToString(", ")}"
            }
            if (addedCustom.isNotEmpty()) {
                parts += "${addedCustom.size} of this workshop's own question" +
                    "${if (addedCustom.size == 1) "" else "s"} " +
                    "(${addedCustom.take(4).joinToString(", ")}${if (addedCustom.size > 4) ", …" else ""})"
            }
            return buildString {
                append("This stage has now been read from the server. ")
                if (parts.isNotEmpty()) {
                    append(parts.joinToString("; "))
                    append(" were already there and not on this phone, and have been added below. ")
                    append("Nothing you had typed here was changed. ")
                }
                if (sweptRows.isNotEmpty()) {
                    val rows = sweptRowCount
                    val them = if (rows == 1) "it" else "them"
                    append("You had deleted everything in ")
                    append(sweptRows.keys.joinToString(", "))
                    append(" on this phone, so ")
                    append("$rows row${if (rows == 1) "" else "s"} the server still holds ")
                    append(if (rows == 1) "there has" else "there have")
                    append(" NOT been added back, and the next save will delete $them on the server — ")
                    append("including anything added there by somebody else since you deleted. Your ")
                    append("deletion stands, which is what you asked for. ")
                    // THE REMEDY HAS TO BE ONE THE DESIGNER CAN ACTUALLY CARRY OUT, and "add the rows
                    // back here" is not: these are rows this phone has never shown them, so they cannot
                    // retype what they have not seen. What is true, and checked — `deletedAt` is
                    // stamped, the row is kept, and `clientKey` is a unique column on it — is that the
                    // deletion is RECORDED rather than erased. So the sentence names the fact that
                    // makes it fixable by somebody, instead of an action that would fail.
                    append("If you did not mean to delete $them, say so before this stage is ")
                    append("submitted: ")
                    append("the repository records a deletion rather than erasing the row, so ")
                    append(if (rows == 1) "it" else "they")
                    append(" can be brought back by whoever runs it.")
                }
                if (declinedRows.isNotEmpty()) {
                    // REASSURANCE, NOT AN ALARM — see [declinedRows] for why it is not the sentence
                    // above. The designer deleted these rows themselves and the server still had them
                    // because the deletion has not travelled yet; all this says is that the read did
                    // not undo it, which is the question a designer who reopens the stage is actually
                    // asking when they count the rows on screen.
                    val rows = declinedRowCount
                    if (isNotEmpty()) append(" ")
                    append("The $rows row${if (rows == 1) "" else "s"} you deleted in ")
                    append(declinedRows.keys.joinToString(", "))
                    append(" ${if (rows == 1) "has" else "have"} NOT come back: the server still ")
                    append("holds ${if (rows == 1) "it" else "them"} because that deletion has not ")
                    append("been sent yet, and the next save from this phone removes ")
                    append(if (rows == 1) "it" else "them")
                    append(" there.")
                }
            }.trim()
        }
}

/**
 * Fold a downloaded stage bucket into whatever this device already holds for that stage.
 *
 * [local] is null for a stage this device holds nothing for, which is the ordinary seeding case and
 * comes out as the server's copy verbatim.
 *
 * PURE, and that is the point: this is the one function in the lane whose mistake would be an
 * overwrite of a designer's own text — or, as it turned out, the silent reversal of their deletion — so
 * it is decided with no Context, no Compose and no okhttp anywhere near it, and pinned on the desktop
 * JVM by the twelve fold cases in `StageAuthorityEarnedByReadingTest` — from
 * `folding the server's copy in is what earns the reading` to
 * `a stage this device holds nothing for folds to the server's copy verbatim`. They live there rather
 * than in a file of their own because the fold is only half a fact: what it EARNS is
 * [StageDraft.stageSeen], and every one of them ends by asking what the next payload then looks like.
 * There is no `DwStageFoldTest`, and this line named one until a reviewer went looking for it.
 *
 * THE FOUR ADDED LAST ARE THE ONES THAT MATTER MOST, because the case that was already there —
 * `a deletion waiting to be sent survives the fold that finally lets it be sent` — folded an EMPTY
 * bucket, so it passed while the fold was restoring every row a designer had deleted. A test that
 * cannot fail for the defect it names is worth less than no test, because it is read as cover.
 */
fun dwFoldServerStage(
    spec: StageDto?,
    local: StageDraft?,
    bucket: StageBucketDto,
    stageKey: String,
): DwStageFold {
    val base = local ?: StageDraft(
        stageId = stageKey,
        title = spec?.title.orEmpty(),
        order = spec?.number ?: 0,
    )

    // ── The stage's own fields ───────────────────────────────────────────────────────────────────
    //
    // `containsKey` and NOT "is filled", and the reason is NOT the one this comment used to give. It
    // said "a key the draft holds as an empty string is a designer who cleared it" — which the stage
    // screen never produces: `StageScreen.put` REMOVES a key whose value went blank rather than
    // storing "" or a null, deliberately, so that hundreds of fields across 22 stages do not
    // accumulate a null apiece to be re-sent on every metered sync. A field the designer cleared this
    // morning is therefore ABSENT here, indistinguishable from one never typed, and this loop re-fills
    // it from the server. That is the documented behaviour and the header argues for it: the clearance
    // never reached the server, the server does still hold the value, and showing it is the truth.
    // What it is not is a clearance being honoured — the draft carries no record of one to honour.
    // (The one record a designer's deletion DOES leave is `emptiedEntities`, and that one is honoured,
    // in the row loop below.)
    //
    // What `containsKey` is actually for is every OTHER way an empty string reaches this draft: a
    // stage seeded by `fromRemote` from a server that holds "", a rich-text control that writes an
    // empty document, a draft written by another build. For those the question "has this device ever
    // had an opinion about this key" is the right one, an empty string IS an opinion, and overwriting
    // it with the server's paragraph would undo an edit rather than reveal one. `isFilled` would
    // treat all of them as absent and overwrite them, which is why this is not that.
    val added = ArrayList<String>()
    val values = LinkedHashMap<String, JsonElement>(base.values)
    bucket.singleton.forEach { (key, value) ->
        if (!values.containsKey(key)) {
            values[key] = value
            // The underscore keys are the protocol's own and are never shown to anybody, so they are
            // folded silently rather than counted as an answer that "appeared".
            if (!key.startsWith("_")) added += key
        }
    }

    // ── The designer's own questions ─────────────────────────────────────────────────────────────
    val addedCustom = ArrayList<String>()
    val custom = LinkedHashMap<String, JsonElement>(base.custom)
    bucket.custom.forEach { (key, value) ->
        if (!custom.containsKey(key)) {
            custom[key] = value
            if (!key.startsWith("_")) addedCustom += key
        }
    }

    // ── The repeating rows ───────────────────────────────────────────────────────────────────────
    //
    // MATCHED ON THE ROW'S OWN KEY, which is the same identity `save_stage` matches on: `_clientKey`
    // first, then the server's `_entryId`. A row this device created, synced and is holding is
    // therefore recognised in the server's answer and is NOT added a second time — without that, one
    // fold would double every row of every costing table the phone had ever sent, which is the exact
    // duplication `_clientKey` was introduced to stop.
    //
    // Appended AFTER the local rows rather than interleaved by ordinal. The server's ordinals describe
    // the server's list, and this device's describe a list the designer has been looking at and
    // reordering; splicing one into the other would reshuffle rows under a thumb. The next save sends
    // the order that is on screen, which is the order the report prints.
    // A collection the designer emptied. Its rows are NOT added back — see the header for the whole
    // argument, and `sweptRows` for what is said about the collateral. Held as a set because it is
    // asked once per entity in a loop that also runs for every stage on the workshop list's status
    // pass.
    val emptied = base.emptiedEntities.toSet()
    // AND THE SAME RECORD ONE LEVEL DOWN, read here for the reason the header gives at length: a row
    // deleted out of a collection that still holds others leaves nothing in the payload either, so
    // [StageDraft.deletedRowKeys] is the only place that deletion exists until it is acknowledged. A
    // set of `dwRowId(entityKey, rowId)` strings, which is exactly the shape the append loop below
    // computes for each of the server's rows — the two must keep using `dwRowId` rather than
    // formatting the key by hand, or a separator change silently stops honouring every deletion.
    val deleted = base.deletedRowKeys.toSet()
    val sweptRows = LinkedHashMap<String, Int>()
    val declinedRows = LinkedHashMap<String, Int>()
    val addedRows = LinkedHashMap<String, Int>()
    val rows = ArrayList(base.rows)
    val heldKeys = base.rows.mapTo(HashSet()) { it.id }
    // Held identities beyond the row id itself: a row seeded from the server keeps its `_entryId` in
    // its values, and a row whose client key was minted here carries that. Both are consulted, so a
    // row that has changed which of the two it is filed under is still recognised as the same row.
    val heldEntryIds = base.rows.mapNotNullTo(HashSet()) {
        (it.values["_entryId"] as? JsonPrimitive)?.content?.takeIf { id -> id.isNotBlank() }
    }
    bucket.collections.forEach { (entityKey, serverRows) ->
        // Only entities this build's registry declares for this stage. A row under a key the registry
        // has since dropped cannot be drawn, cannot be edited and would be sent straight back up as an
        // entity the server reports in `droppedKeys` — a phone reporting registry drift to itself.
        val declared = spec == null || spec.collections.any { it.key == entityKey }
        if (!declared) return@forEach
        if (entityKey in emptied) {
            // THE DESIGNER DELETED THIS COLLECTION, SO IT STAYS DELETED. Counted, not silently
            // dropped — and counted as WHAT THE NEXT SAVE WILL ACTUALLY REMOVE, which is not the same
            // as the number of rows the server sent. The sweep deletes the rows the payload does not
            // NAME, and the payload names every row this draft still holds; so a server row whose
            // client key or entry id is already in the draft survives the save, and claiming it was
            // about to be deleted would be a false alarm. That is reachable in the ordinary way: empty
            // a collection, then start it again with a fresh row before the stage is next read.
            //
            // BOTH IDENTITIES ARE CONSULTED, exactly as the append loop below consults them: a row
            // seeded from the server is held under its `_entryId` and one minted here under its client
            // key, and `buildStageBody` sends whichever the draft has — so either one being held means
            // the payload names the row and the sweep skips it.
            val unseen = serverRows.count { row ->
                val clientKey = (row["_clientKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                val entryId = (row["_entryId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                dwRowId(entityKey, clientKey ?: entryId ?: "") !in heldKeys &&
                    (entryId == null || entryId !in heldEntryIds)
            }
            if (unseen > 0) sweptRows[entityKey] = unseen
            return@forEach
        }
        var appended = 0
        var declined = 0
        serverRows.forEachIndexed { index, row ->
            val clientKey = (row["_clientKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val entryId = (row["_entryId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val identity = clientKey ?: entryId ?: "server-$index"
            if (dwRowId(entityKey, identity) in heldKeys) return@forEachIndexed
            if (entryId != null && entryId in heldEntryIds) return@forEachIndexed
            /*
              THE DESIGNER DELETED THIS ROW, SO IT STAYS DELETED — the sibling of the emptied branch
              above, and the whole point of reading `deletedRowKeys` at all. Without this test the
              fold re-added the row, the next payload named it, and `persistLocally`'s row filter then
              dropped the key from `deletedRowKeys` "because the row is back in the draft" — deletion
              reversed and its only record erased, in one online open of the stage.

              ALL THREE IDENTITIES ARE ASKED, not just `identity`, because the draft and the server
              may file one row under different halves of the same pair: the row was deleted while it
              was held under its `_clientKey`, and the server's copy of it may carry both keys (it
              mints an `_entryId` for every row it stores). Testing only `clientKey ?: entryId` would
              miss a row deleted while it was held under its `_entryId` on a draft seeded before a
              client key existed for it. `deletedRowKeys` is small — one entry per row the designer
              deleted and has not yet had acknowledged — so three set lookups cost nothing.

              NEVER "REPAIR" THIS BY DROPPING THE KEY WHEN THE ROW COMES BACK: the row coming back is
              the server telling us it has not been deleted THERE yet, which is precisely when the
              record is still owed.
            */
            val deletedHere = dwRowId(entityKey, identity) in deleted ||
                (clientKey != null && dwRowId(entityKey, clientKey) in deleted) ||
                (entryId != null && dwRowId(entityKey, entryId) in deleted)
            if (deletedHere) {
                declined++
                return@forEachIndexed
            }
            rows += DraftRow(id = dwRowId(entityKey, identity), values = row.toMap())
            appended++
        }
        if (appended > 0) addedRows[entityKey] = appended
        if (declined > 0) declinedRows[entityKey] = declined
    }

    return DwStageFold(
        draft = base.copy(
            stageId = base.stageId.ifBlank { stageKey },
            title = base.title.ifBlank { spec?.title.orEmpty() },
            order = if (base.order != 0) base.order else (spec?.number ?: 0),
            values = values,
            custom = custom,
            // TAKEN WHOLESALE FROM THE SERVER, not merged with what the draft held — unlike `values`
            // above, which folds key by key so an offline edit is never overwritten. These are not
            // answers and there is nothing of the designer's to protect: the server is the only
            // author of a stamp, so its latest word simply replaces the previous one. Merging would
            // preserve a stamp for a value the server has since re-attributed, which is the one
            // outcome worse than showing none.
            provenance = bucket.provenance,
            // EARNED HERE, on the same evidence and in the same breath as the read: the bucket's third
            // key IS the server's `_custom` row, so from this moment the device knows what that row
            // holds — including that it holds nothing. See [StageDraft.customSeen].
            customSeen = true,
            rows = rows,
            // Refreshed from the registry where there IS one, and left alone where there is not.
            // `ifEmpty { base }` would have been wrong in the one case that matters: a stage whose
            // registry declares no required field at all would keep whatever an older registry had
            // said, and the workshop list scores completeness off this list with no network.
            requiredKeys = if (spec == null) base.requiredKeys else
                spec.singleton?.liveFields.orEmpty().filter { it.required }.map { it.key },
            // THE FACT THIS WHOLE FUNCTION EXISTS TO EARN. After the fold this draft holds everything
            // the server holds, so "delete what I do not name" can no longer name anything the designer
            // has not been shown. It is set HERE and by `StageScreen.fromRemote`, and by nothing that
            // writes to the server.
            stageSeen = true,
            // UNTOUCHED, DELIBERATELY, AND NOW IT MEANS SOMETHING. A deletion the designer recorded is
            // still owed to the server and is, for the first time, carryable: the very next save is
            // entitled to state it, because this fold earned `stageSeen` above.
            //
            // IT IS KEPT RATHER THAN CLEARED HERE, and the two halves have to stay together. The row
            // loop above declines to add the server's rows back for these entities, so the next payload
            // does not name them and `replaceCollections` sweeps them; clearing the field here instead
            // would drop the instruction and leave the rows alive on the server for ever with nothing
            // on any screen saying so. `recordStageSent` is what clears it, scoped to the keys the
            // acknowledged payload actually carried.
            emptiedEntities = base.emptiedEntities,
            // CARRIED FOR THE SAME REASON AND WITH THE SAME FORCE as `emptiedEntities` above. The row
            // loop declined to add these rows back, so the next payload does not name them and
            // `replaceCollections` removes them server-side; clearing the list here would leave the
            // rows alive on the server with no record anywhere that anybody asked for them to go.
            // `recordStageSent` is the one place a deletion is cleared, and only for what an
            // acknowledged payload actually carried.
            deletedRowKeys = base.deletedRowKeys,
        ),
        added = added,
        addedRows = addedRows,
        addedCustom = addedCustom,
        sweptRows = sweptRows,
        declinedRows = declinedRows,
    )
}
