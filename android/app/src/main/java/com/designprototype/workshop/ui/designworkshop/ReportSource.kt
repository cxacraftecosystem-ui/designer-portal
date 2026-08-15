package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StageListDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * WHICH COPY OF THE WORKSHOP THE REPORT WILL BE BUILT FROM, and the sentence that says so.
 *
 * ── THE DEFECT THIS EXISTS TO CLOSE ───────────────────────────────────────────────────────────
 *
 * [ReportScreen] used to prepare its export from `WorkshopDraftStore.load` and nothing else — local
 * storage only. That is correct for a device that captured the workshop itself and catastrophic for
 * one that did not: a designer exported the flagship workshop from a handset that had opened three
 * of its twenty-two stages and got a VALID .docx carrying the ministry cover page, the table of
 * contents, and ten paragraphs, over a workshop holding 270 rows on the server. Nothing on the
 * screen and nothing in the file said the other nineteen stages had never been downloaded. That file
 * is the copy handed to a visiting officer, so a silently short one is the most expensive thing this
 * product can produce.
 *
 * The capability to fix it was already here — `WorkshopRepository.designWorkshopStages` — with
 * exactly one caller in the whole app ([WorkshopCodesScreen]). This is the second.
 *
 * ── THE MERGE IS NOT AN OVERWRITE ─────────────────────────────────────────────────────────────
 *
 * The premise of the app is that the work is done in a courtyard with no signal and reaches the
 * server later, so the device routinely holds stages the server has never seen. THE LOCAL COPY OF A
 * STAGE ALWAYS WINS. The server is read only to fill in a stage this device holds NOTHING for —
 * which is the identical rule [StageScreen] loads a stage by and [workshopCardRows] prints tags by,
 * and it is deliberately the same rule rather than a third one. Choosing per stage rather than per
 * field is also theirs, and for their reason: a stage is opened, edited and saved as a unit, so
 * falling back field-by-field inside a stage the designer has touched would resurrect answers they
 * had deleted.
 *
 * ── NOTHING HERE IS WRITTEN TO DISK ───────────────────────────────────────────────────────────
 *
 * The merged draft is built for one export and discarded. An export is not a sync: persisting the
 * server's copy from here would be a second, undeclared sync path — one that runs on a screen the
 * designer opened to print something, that has none of `WorkshopSyncEngine`'s ordering or media
 * bookkeeping, and that would rewrite `stageSeen` on stages this screen knows nothing about.
 * The cost of not persisting is one HTTP read per visit to the report screen.
 */
internal data class ReportSource(
    /**
     * The draft to build the document from — local work, with the server's stages filled in around
     * it. Null only when neither side holds anything at all.
     */
    val draft: WorkshopDraft?,
    /** Stage keys taken from the server because this device held nothing for them. */
    val filledFromServer: List<String>,
    /** Stage keys this device holds work for. The server's copy of these was NOT read over them. */
    val keptFromDevice: List<String>,
    /**
     * The warning to show ABOVE the export buttons when the file cannot contain more than this
     * device holds — null when the server answered and there is nothing to warn about.
     *
     * Three states, in the designer's own words, ported from [WorkshopCodesScreen]: the workshop is
     * not on the server yet; there is no connection; the server answered. A designer exporting in a
     * courtyard has to be told which of those they are in BEFORE the document is handed over —
     * that sentence is the difference between a known-partial report and an embarrassment.
     */
    val deviceOnlyNote: String?,
    /** Always said, in all three states: what the next export will actually be built from. */
    val builtFromLine: String,
    /**
     * The workshop EXISTS on the server and could not be read, so this file may be short — the one
     * fact here that is written into the DOCUMENT, through `ReportPlan.serverCopyUnread` and
     * `fieldCopyNote`, and not only onto the screen.
     *
     * NARROWER THAN `deviceOnlyNote != null` ON PURPOSE. A workshop with no server record at all is
     * device-only and COMPLETE — there is nothing anywhere else it could be missing — so it gets the
     * screen's note (send it to the server if work was recorded elsewhere) and nothing on the cover.
     * Printing "some of this may be missing" over a file that is whole is how a provenance line
     * stops being believed.
     */
    val serverCopyUnread: Boolean,
)

/**
 * Decide the source, merge it, and word it.
 *
 * [remoteId] null means the workshop exists only on this device, so no read was attempted; [remote]
 * null after a non-null [remoteId] means the read was ATTEMPTED AND FAILED. Those two are kept
 * apart here for the same reason [StageScreen] keeps them apart: a failed read is not an empty
 * workshop, and telling a designer their workshop is empty when the request merely timed out is how
 * a person is talked into believing there is nothing to hand over.
 */
internal fun reportSourceFor(
    schema: SchemaResponse,
    workshopId: String,
    local: WorkshopDraft?,
    remoteId: String?,
    remote: StageListDto?,
): ReportSource {
    val specs = schema.stages.associateBy { it.key }
    val remoteStages = remote?.stages.orEmpty()

    val merged = LinkedHashMap(local?.stages.orEmpty())
    val filled = ArrayList<String>()
    val kept = ArrayList<String>()

    remoteStages.forEach { (stageKey, bucket) ->
        val stored = merged[stageKey]
        // THE ONE RULE, and it is [workshopCardRows]'s to the letter: a stage the device holds any
        // work for is the device's stage. `stored != null` alone is not enough — a stage row can be
        // left behind by a screen that was opened and closed without a keystroke, and treating that
        // shell as "work" is what would keep the server's twenty rows out of the report.
        // `custom` counted alongside the other two, for the reason `WorkshopSync.statusOf` counts
        // it: eight of the twenty-two stages declare no singleton at all, so a stage whose only
        // content is the designer's own answers is ordinary rather than exotic. Left out, this
        // handset's custom answers would be judged "not work", the server's copy of that stage would
        // be adopted over them, and a report generated on the phone would print the office's answers
        // in place of the ones typed in the courtyard that morning.
        val touchedLocally = stored != null && stored.holdsWork()
        if (touchedLocally) {
            kept += stageKey
            return@forEach
        }
        if (bucket.singleton.isEmpty() && bucket.collections.values.all { it.isEmpty() } &&
            bucket.custom.isEmpty()
        ) return@forEach
        val downloaded = stageDraftFromRemote(stageKey, bucket, specs[stageKey], stored)
        // Emptied on the phone and nowhere else yet, so what came back was a stage the designer has
        // already deleted the whole of. Counted as neither side's, because it is in the file as
        // neither — saying "downloaded from the server just now" over a stage that prints nothing is
        // how the built-from line stops being a count of the document.
        //
        // ASKED WITH THE SAME PREDICATE AS THE DECISION ABOVE, and it has to be: the question here
        // ("does this stage put anything in the document?") is word for word the question
        // [holdsWork] answers, and the two were separately spelled-out booleans that agreed only by
        // accident. They stopped agreeing the moment `stageDraftFromRemote` began carrying this
        // device's `_`-prefixed provenance across the fill-in — an inline `values.isEmpty()` here
        // would then read a carried `_recordingPlace` as content and report an emptied stage as
        // "downloaded from the server just now" over a stage the file contains nothing of.
        if (!downloaded.holdsWork()) return@forEach
        merged[stageKey] = downloaded
        filled += stageKey
    }

    // Stages held here and absent from the server — unsynced fieldwork — are already in `merged`
    // untouched, and they are the whole reason this is a merge.
    local?.stages.orEmpty().forEach { (stageKey, stored) ->
        if (stageKey in kept || stageKey in filled) return@forEach
        if (stored.holdsWork()) kept += stageKey
    }

    val draft = when {
        local != null -> local.copy(stages = merged)
        merged.isEmpty() -> null
        // No draft file on this handset at all: the report is built from the server's copy alone.
        // The title stays empty and `reportPlanFor` falls back to its own — the cover's craft,
        // cluster and state come from stage 1, which is in `merged` like every other stage.
        else -> WorkshopDraft(workshopId = workshopId, remoteId = remoteId, stages = merged)
    }

    val deviceOnlyNote = when {
        remoteId == null ->
            "This workshop has not been created on the server yet, so this report can only contain " +
                "what is saved on this device. Send it to the server first if work recorded " +
                "elsewhere has to appear in the document."
        // NAMED AS WHAT WAS OBSERVED, NOT AS A DIAGNOSIS. All this code saw is that the read
        // returned nothing; it did not see a radio. A refused token, a 500, a captive portal and a
        // genuinely empty sky all arrive here identically, and "There is no connection" shown to a
        // designer looking at four bars is a notice that stops being believed — which costs the
        // NEXT one, the one that is true. [StageScreen] says "there is no connection, or the
        // request failed" on exactly this evidence and this follows it.
        remote == null ->
            "This workshop could not be read from the server — there is no connection, or the " +
                "request failed. So this report can only contain what is saved on this device: " +
                "anything recorded on another phone or on the web is NOT in it. Say so before " +
                "handing this copy to an officer."
        else -> null
    }

    return ReportSource(
        draft = draft,
        filledFromServer = filled,
        keptFromDevice = kept,
        deviceOnlyNote = deviceOnlyNote,
        builtFromLine = builtFromLine(deviceOnlyNote == null, filled.size, kept.size),
        serverCopyUnread = remoteId != null && remote == null,
    )
}

/**
 * Whether this device's copy of a stage holds anything the DOCUMENT could carry.
 *
 * ── AN UNDERSCORE KEY IS NOT WORK, AND COUNTING IT KEPT A WHOLE STAGE OUT OF THE REPORT ───────────
 *
 * The merge above is all-or-nothing per stage: a stage this device holds work for is the device's,
 * and the server's copy of it is not read. That is the right rule, and it was being fed the wrong
 * question. `values.isNotEmpty()` counted keys that can never leave this phone and can never print:
 * `WorkshopSync.wireData` strips every `_`-prefixed key from the payload by design (they are the
 * protocol's, not the designer's), and the on-device writer's `renderEntity` walks
 * `entity.liveFields`, so an underscore key is unreachable by construction on both paths.
 *
 * The one that actually gets written is `_recordingPlace` — "where were you when this stage was
 * filled in?", offered on all 22 stages by `DwRecordingPlaceCard`. So: a designer opens stage 14 in a
 * courtyard where the server read fails, the screen seeds a blank state, they answer only the
 * recording place, and stage 14's draft now holds exactly one key. Build the report and this
 * function said "the device's stage", returned before merging, and the office's full stage 14 —
 * every row of it — was left out of the file, while the built-from line counted it as kept from the
 * device. Nothing on the screen or in the document said so.
 *
 * `StageScreen` recovers from the same shape on its next online open (its `holdsWork` branch folds
 * the server's values back in); this merge has no fold and is all-or-nothing, which is why the
 * consequence lands here. Rows and custom answers are counted whole: a row's presence IS work, and
 * its `_clientKey`/`_entryId` are carried inside the row rather than beside it.
 *
 * ── "NOT WORK" IS NOT "NOTHING", AND THAT DISTINCTION IS LOAD-BEARING ─────────────────────────────
 *
 * `ReportSourceShippedRegistryTest` shipped green with a fixture whose only local content was an
 * underscore key, standing for "one stage the device holds work for"; this predicate turned it red.
 * The predicate is the one that is right — a stage the document cannot print a syllable of must not
 * be allowed to suppress the server's twenty rows — but the objection inside that fixture is real
 * too: `_recordingPlace` IS an answer a designer typed, `DwLocationField` documents it as surviving
 * in the draft the report is generated from, and the migration written down there (copy the key into
 * a real registry field when one exists) depends on it not being quietly dropped along the way.
 *
 * Both are honoured, and the split is the whole point: an underscore key does not DECIDE the merge,
 * and it is not ERASED by it either — `stageDraftFromRemote` carries this device's underscore values
 * across the fill-in. So the courtyard stage above gets the office's twenty rows AND keeps its record
 * of where it was written. Anyone tempted to "simplify" this back to `values.isNotEmpty()` is
 * choosing the near-empty .docx this whole file exists to stop; anyone tempted to drop the carry in
 * `stageDraftFromRemote` is choosing to delete an answer on the designer's behalf.
 */
private fun StageDraft.holdsWork(): Boolean =
    values.keys.any { !it.startsWith("_") } || rows.isNotEmpty() || custom.isNotEmpty()

/**
 * One line naming both halves of the merge, so "which copy is this?" is answered on the screen the
 * file is generated from rather than by opening the file and counting what is missing.
 */
private fun builtFromLine(serverAnswered: Boolean, fromServer: Int, fromDevice: Int): String = when {
    !serverAnswered && fromDevice == 0 ->
        "Nothing has been recorded for this workshop on this device yet."
    !serverAnswered ->
        "Built from the $fromDevice stage(s) saved on this device."
    fromServer == 0 && fromDevice == 0 ->
        "The server holds nothing for this workshop yet, and neither does this device."
    fromServer == 0 ->
        "Built from the $fromDevice stage(s) saved on this device. The server was read just now " +
            "and holds nothing this device does not already have."
    fromDevice == 0 ->
        "Built from the $fromServer stage(s) downloaded from the server just now."
    else ->
        "Built from the $fromServer stage(s) downloaded from the server just now, plus the " +
            "$fromDevice stage(s) saved on this device — this device's copy is kept wherever both " +
            "hold the same stage."
}

/**
 * One server bucket as a [StageDraft], carrying the fields the report reads and nothing it does not.
 *
 * Photographs are NOT invented here. A media id in a server answer names a `MediaFile` on the
 * server, and `imageFor` resolves ids against this device's own `draft.media`; a stage downloaded
 * here therefore prints its words and skips its pictures. Fabricating [StageDraft.mediaIds] out of
 * server ids would instead point the on-device renderer at an id space it cannot read, which is the
 * id-space confusion `DraftMedia.remoteMediaId` exists to keep apart.
 *
 * AND THE SKIPPED PICTURES ARE COUNTED AND SAID, which this comment used to claim ("one picture and
 * one warning") while no warning existed anywhere. Every token `imageFor` cannot resolve is now
 * collected by `buildWorkshopDocument` and stated three times in one sentence — beside the saved
 * file on the export screen, in the document itself (in the 'Photographic record' annexure where the
 * template carries one, at the foot of the document where it does not), and in the row this app
 * posts to the office's export log. That is the whole difference between "this workshop was never
 * photographed" and "this handset does not hold the pictures", and a downloaded stage is exactly the
 * case that produces the second while reading as the first.
 *
 * A COLLECTION THIS DEVICE HAS EMPTIED IS NOT READ BACK IN. Eight of the twenty-two stages —
 * sketches, prototypes, iterations, cost sheets — hold nothing BUT collections, so deleting their
 * last row leaves a stage with no values and no rows, which is precisely the shape the merge above
 * treats as "this device holds nothing here" and fills from the server. Without this filter the one
 * document a designer hands to an officer would print, under its ministry cover page, exactly the
 * rows they deleted in front of them — because the deletion is still sitting in
 * [StageDraft.emptiedEntities] waiting for signal. This function already carries that list forward;
 * honouring it is the difference between carrying a record of the act and acting on it.
 */
private fun stageDraftFromRemote(
    stageKey: String,
    bucket: StageBucketDto,
    spec: StageDto?,
    /** The empty local shell, when there is one — its bookkeeping is kept rather than dropped. */
    existing: StageDraft?,
): StageDraft {
    val emptied = existing?.emptiedEntities.orEmpty().toSet()
    return StageDraft(
        stageId = stageKey,
        title = spec?.title ?: existing?.title.orEmpty(),
        order = spec?.number ?: existing?.order ?: 0,
        // THE SERVER'S ANSWERS, PLUS THIS DEVICE'S OWN UNDERSCORE KEYS — never the other way round.
        //
        // The `_` prefix is the sync protocol's marker for "this never leaves the phone"
        // (`WorkshopSync.wireData` strips every one of them from a PUT), so the server's bucket
        // cannot carry one and there is nothing here for it to overwrite. What it CAN carry is
        // `_recordingPlace`: the "where were you standing when you wrote this stage down?" answer
        // `DwRecordingPlaceCard` offers on all 22 stages, which [DwLocationField] documents as
        // surviving in the draft the report is generated from.
        //
        // [holdsWork] deliberately does not count that key as work, so a stage holding only it is
        // filled in from the server — and without this line the fill-in would take the designer's
        // provenance answer with it. Not counting an answer is a merge decision; deleting it is a
        // different act, and this file is not entitled to it.
        values = bucket.singleton.toMap() +
            existing?.values.orEmpty().filterKeys { it.startsWith("_") },
        // THE DESIGNER'S OWN ANSWERS, CARRIED. Without this line the offline export prints the
        // server's core fields for a downloaded stage and silently nothing at all from its custom
        // section — which is the exact shape of the divergence this whole lane exists to remove,
        // arrived at from the report side.
        custom = bucket.custom.toMap(),
        // Read from the server's own bucket, so the fact is earned here exactly as it is in
        // `StageScreen.fromRemote` — see [StageDraft.customSeen]. This draft is assembled for the
        // document and is never written to disk, so nothing syncs off the back of it; it is set
        // anyway, because the day something does persist this record the flag must already be true
        // rather than quietly false on a stage whose container this device HAS read.
        customSeen = true,
        rows = bucket.collections.entries
            .filter { (entityKey, _) -> entityKey !in emptied }
            .flatMap { (entityKey, rows) ->
                rows.mapIndexed { index, row ->
                    DraftRow(id = dwRowId(entityKey, remoteRowId(row, index)), values = row.toMap())
                }
            },
        // Whatever this device already had attached to the stage stays attached: the photographs are
        // this handset's and the server's answer says nothing about them.
        mediaIds = existing?.mediaIds.orEmpty(),
        requiredKeys = spec?.singleton?.liveFields.orEmpty().filter { it.required }.map { it.key },
        completedAt = existing?.completedAt,
        notes = existing?.notes.orEmpty(),
        // TRUE, AND HONESTLY SO: this stage was seeded from a successful read of the server's copy —
        // the same claim `StageScreen` makes on the same evidence. It is inert either way, because
        // nothing built here is ever written to disk or sent.
        stageSeen = true,
        emptiedEntities = existing?.emptiedEntities.orEmpty(),
    )
}

/**
 * A stable row id for a downloaded row.
 *
 * DETERMINISTIC, WHERE [StageScreen]'s `fromRemote` USES A RANDOM UUID, and the difference is not an
 * oversight. There the id becomes the row's identity for a later save, so a fresh one is harmless.
 * Here the draft is thrown away at the end of the export and the id is only ever a grouping key —
 * while `recordDesignWorkshopExport` sends a SHA-256 of the finished file, and the web's report
 * history answers "the revised copy you sent was the same file as last time" from that checksum
 * alone. Seeding row ids from a random source would make two exports of unchanged data differ.
 */
private fun remoteRowId(row: JsonObject, index: Int): String =
    (row["_clientKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        ?: (row["_entryId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        ?: "row-$index"
