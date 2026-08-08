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
 * bookkeeping, and that would rewrite `serverBaseline` on stages this screen knows nothing about.
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
        val touchedLocally = stored != null && (stored.values.isNotEmpty() || stored.rows.isNotEmpty())
        if (touchedLocally) {
            kept += stageKey
            return@forEach
        }
        if (bucket.singleton.isEmpty() && bucket.collections.values.all { it.isEmpty() }) return@forEach
        val downloaded = stageDraftFromRemote(stageKey, bucket, specs[stageKey], stored)
        // Emptied on the phone and nowhere else yet, so what came back was a stage the designer has
        // already deleted the whole of. Counted as neither side's, because it is in the file as
        // neither — saying "downloaded from the server just now" over a stage that prints nothing is
        // how the built-from line stops being a count of the document.
        if (downloaded.values.isEmpty() && downloaded.rows.isEmpty()) return@forEach
        merged[stageKey] = downloaded
        filled += stageKey
    }

    // Stages held here and absent from the server — unsynced fieldwork — are already in `merged`
    // untouched, and they are the whole reason this is a merge.
    local?.stages.orEmpty().forEach { (stageKey, stored) ->
        if (stageKey in kept || stageKey in filled) return@forEach
        if (stored.values.isNotEmpty() || stored.rows.isNotEmpty()) kept += stageKey
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
 * here therefore prints its words and skips its pictures, which is the documented contract — a
 * photograph whose bytes are missing costs the report one picture and one warning, never the export.
 * Fabricating [StageDraft.mediaIds] out of server ids would instead point the on-device renderer at
 * an id space it cannot read, which is the id-space confusion `DraftMedia.remoteMediaId` exists to
 * keep apart.
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
        values = bucket.singleton.toMap(),
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
        serverBaseline = true,
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
