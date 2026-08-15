package com.designprototype.workshop.data

import android.content.Context
import com.designprototype.workshop.report.remapInlineMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The other half of offline retention: getting a design workshop OFF the phone, intact, once there
 * is signal again — and never at the cost of what is on the phone.
 *
 * WHY THIS FILE HAD TO EXIST. [WorkshopDraftStore] is a genuinely durable local document: atomic
 * writes, quarantine rather than deletion on damage, media copied into `filesDir` where Android
 * cannot evict it. What it had no notion of at all was UPLOAD. A workshop captured in a courtyard
 * stayed in that courtyard for ever: there was no path from a local draft to the server other than
 * one button on the list that created the header and pushed the stages, sent no photographs, kept no
 * record of what had already landed, and retried everything from the top on the next attempt. "Your
 * work is safe until it is uploaded" was therefore only half true — the retention half was solid and
 * the "until uploaded" half did not exist.
 *
 * ── THE ORDER, AND WHY IT CANNOT BE REARRANGED ───────────────────────────────────────────────────
 *
 * For each workshop with anything outstanding:
 *
 *   1. CREATE the remote record if the draft is still local-only, and write `remoteId` back to disk
 *      BEFORE anything else moves. A create is the one step that is not safely repeatable: repeating
 *      it makes a SECOND workshop, and the pass that repeats it is by definition the pass that ran
 *      on a bad connection — which is once per attempt for as long as the signal stays bad.
 *   2. UPLOAD every media file the server has not yet acknowledged, recording each returned id
 *      against its [DraftMedia] the instant it arrives.
 *   3. PUT each stage whose wire payload differs from the one last acknowledged, recording the
 *      payload's signature as each one lands.
 *
 * Media comes before stages because a stage's media field carries a MediaFile id and the server
 * resolves it against the media table (`media_resolver`, backend/app/services/design_workshops.py).
 * A stage pushed before its photographs would carry this device's own UUIDs, which resolve to
 * nothing there — a stage that reports itself synced and prints an empty frame in the report the
 * ministry receives.
 *
 * ── IDEMPOTENCY IS THE BACKEND'S. THIS FILE INVENTS NOTHING ──────────────────────────────────────
 *
 * Every collection row travels with a `_clientKey` and `PUT /design-workshops/{id}/stages/{key}`
 * matches on `(entityKey, clientKey)` ACROSS SOFT-DELETED ROWS, so a replayed save UPDATES rather
 * than duplicating and a re-added row is resurrected with its id — and therefore with everything
 * that references it — intact. That is proven in backend/tests/test_stage_sync.py
 * (`test_a_repeated_sync_updates_rather_than_duplicating`,
 * `test_re_adding_a_deleted_client_key_resurrects_the_row`), which is why this engine has no
 * de-duplication scheme of its own: it guarantees only that a row's key is stable for the life of
 * the row ([DraftRow.id], minted at creation and never rewritten) and lets the server match. A
 * second scheme here would be a second opinion about which rows are the same rows, and the one that
 * was wrong would duplicate a costing table on every reconnect.
 *
 * ── RESUMABILITY IS WRITE-AS-YOU-GO, NOT A TRANSACTION ───────────────────────────────────────────
 *
 * Every step is written back into the draft the moment it lands: `remoteId` after the create,
 * [DraftMedia.remoteMediaId] after each `/media/complete`, a per-stage signature after each PUT. A
 * pass that is killed — the process, the battery, the designer swiping the app away on the bus —
 * resumes from what has actually happened rather than from the top. This is the rule
 * `frontend/lib/offline.ts` states at length for the web outbox (`created` / `createdId` /
 * `uploadedBatches`) and the reason it gives is exactly the one that applies here: the interruption
 * happens because the signal is bad, and a bad signal is the whole reason there is anything to sync.
 *
 * ── TRIAGE: ONE REFUSED ITEM MUST NOT STRAND THE FORTNIGHT BEHIND IT ─────────────────────────────
 *
 * THE QUESTION IS "DID THE SERVER ANSWER", AND IT IS NOT [WorkshopRepository.isTransient]. That one
 * answers "is it worth trying again", which is the OUTBOX's question and says yes to every 5xx —
 * right for a queue that only has to decide whether to keep an entry, and wrong for a pass that also
 * has to tell a designer why nothing moved. A 5xx means the server was reached and then failed, so
 * reporting it as a lost connection sends somebody with four bars of signal out of the building to
 * look for a better one while a real refusal wears an offline message. See [isConnectionFailure],
 * which is that split, and `isUnreachable` in `frontend/lib/offline.ts`, which is the same split on
 * the web — made there first, for the same defect, on this same endpoint.
 *
 * So: NOTHING REACHED THE SERVER (no signal, a socket dropped, a 408 from a proxy that never
 * completed the request), or the server asked for time (429) or for a new credential (401) — the
 * pass stops where it is, nothing is marked failed, and everything is tried again next time. THE
 * SERVER ANSWERED AND REFUSED — whatever the status — the reason is recorded on that ITEM alone,
 * shown to the designer with the stage named, and the pass carries on with the rest.
 *
 * ── NOTHING LOCAL IS EVER DELETED BY A SYNC ──────────────────────────────────────────────────────
 *
 * Not the draft, not a stage, and above all not a photograph. The bytes are released only by
 * [WorkshopDraftStore.releaseUploadedMedia], which a person has to choose, and even then only for
 * files the server has acknowledged an id for. See the KDoc there for why the default is to keep
 * them for ever: the phone is where the offline report is generated, and a workshop whose pictures
 * were uploaded and then swept still exports — silently, with the pictures missing.
 *
 * ── NO SCHEDULER, DELIBERATELY ───────────────────────────────────────────────────────────────────
 *
 * There is no WorkManager here and none is added. This module has no such dependency, and the app
 * already drains its outbox from a plain coroutine at sign-in, on a periodic fallback and on the
 * connectivity callback (MainActivity.kt, around the `syncOutbox` loop). [syncDesignWorkshops] is
 * called from the tail of [WorkshopRepository.syncOutbox], so it inherits all three triggers with no
 * second timer, no second network callback and no second sign-in gate to keep in step. What that
 * does NOT buy is syncing while the app is not running; see the openIssues note for this track.
 */

// --------------------------------------------------------------------------------------
// What the draft remembers about its own sync
// --------------------------------------------------------------------------------------

/**
 * One stage's sync bookkeeping.
 *
 * [signature] is a digest of the EXACT payload the server acknowledged — not a timestamp, and not a
 * dirty flag. Three reasons, and the third is the one that matters:
 *
 *  - a timestamp needs every writer of a stage to remember to stamp it, and the stage screen builds
 *    its [StageDraft] from scratch on every debounce (`persistLocally`), so a stamp would be dropped
 *    by the very writes it is meant to notice;
 *  - a dirty flag has the same problem from the other side, and adds a second thing that can be
 *    wrong about whether the work is safe;
 *  - a payload digest is self-healing in the one case that is easy to get wrong. When a photograph
 *    finally uploads, the stage's wire payload changes (the local id is replaced by the server's),
 *    so the signature stops matching and the stage re-sends itself with the real reference. Nothing
 *    has to notice and remember to do that.
 */
@Serializable
data class StageSyncRecord(
    /** SHA-256 of the JSON body the server last accepted for this stage. Blank = never sent. */
    val signature: String = "",
    val syncedAt: String? = null,
    /**
     * Why this stage has not gone up, in words a designer can act on.
     *
     * Set for BOTH kinds of hold-up and distinguished by [permanent]: "three files are still on this
     * device" is temporary and clears itself, while "the server does not know this stage" will not
     * change until somebody does something. Both are shown, because a stage that is silently not
     * syncing is indistinguishable from one that is.
     */
    val failure: String? = null,
    val failedAt: String? = null,
    /** True when no retry will change the answer. The pass steps over it; the designer is told. */
    val permanent: Boolean = false,
    /**
     * The app run that recorded a refusal ONLY AN UPDATE CAN CLEAR. Null on every other failure.
     *
     * See [blocksRetry] for the whole policy. In short: `permanent` is the right marking for a
     * refusal a DESIGNER can fix and the wrong one for a SCHEMA refusal, where this build of the app
     * and this build of the API disagree about the shape of the request. Marking that permanent means
     * the phone can never recover from a skew even after the skew has gone.
     *
     * Defaulted, so a draft written by an earlier build decodes with null and behaves exactly as it
     * did — sticking until a person taps Try again.
     */
    val skewRun: String? = null,
    val attempts: Int = 0,
    /**
     * How many ANSWERS the repository refused inside a save it otherwise accepted.
     *
     * A 200 that carries [StageSaveResultDto.errors] is not a failed save and must not be recorded as
     * one: the other twenty fields landed, and marking the stage refused would stop the pass sending
     * anything else on it. But it is not a clean save either — this device holds a value the
     * repository does not, the payload's signature MATCHES so nothing will ever re-send it, and
     * without this counter [WorkshopSyncStatus.isFullySynced] answered true and the list row said
     * "Backed up to the server" over an edit that never landed. See [DwStageRefusal].
     *
     * Counted rather than listed here because the sentences are already in [failure]; two copies of
     * one fact is two things to keep in step. Cleared by the next save of this stage that comes back
     * clean, which is what correcting the answer produces.
     */
    val refusedFields: Int = 0,
    /**
     * Whether [failure] describes FILES STILL ON THIS DEVICE rather than anything the server said.
     *
     * It exists because `pushStages` clears a stale note when a stage's signature already matches,
     * and it was clearing ANY note — so the one thing `recordStageSent` deliberately keeps (the
     * dropped-key drift signal, and now the refusals) survived exactly as long as it took the 45-second
     * fallback timer to come round, then vanished from the status screen with nothing having changed.
     * The clear is for a hold-up that resolves itself when an upload lands; this is what says which
     * notes those are.
     */
    val waitingOnFiles: Boolean = false,
    /**
     * ENOUGH TO REDRAW THE REFUSAL CARD AFTER THE SCREEN THAT DREW IT IS GONE — see
     * [DwStageRefusalRecord].
     *
     * THE DEFECT THIS CLOSES IS THE APP'S OWN ADVICE. The card and the marks on the boxes lived only in
     * `StageScreen`'s `remember(stageKey)` state, so leaving the stage and coming back erased every one
     * of them — while the note [failure] carries, written by [recordStageSent] and the one thing that
     * DID outlive the screen, says *"open the stage to see which answers, and what the repository
     * holds."* A designer who followed the instruction the app itself gave them arrived at a stage with
     * nothing on it: [refusedFields] still counted, so the workshop went on saying answers were
     * refused, and the surface built to say WHICH was blank. The evidence survived exactly as long as
     * the composition did, which is until the designer did what they were told.
     *
     * Written beside [refusedFields] and cleared by the same event, so the count and the addressing
     * cannot disagree about whether there is anything to show. What it stores is the server's error map
     * and the ORDERING of the entries that were sent — not the drawn card and not the entries
     * themselves; the decode is re-run against the registry this build actually has. See
     * [DwStageRefusalRecord] for why those two and nothing else, and why what the repository HOLDS is
     * deliberately not among them.
     *
     * Additive and defaulted, the same rung [StageDraft.custom] and [StageDraft.customSeen] were added
     * on: a draft written by an earlier build decodes with null and behaves exactly as it did.
     */
    val refusal: DwStageRefusalRecord? = null,
)

/** Everything the draft knows about its own journey to the server. See [WorkshopDraft.sync]. */
@Serializable
data class DraftSyncState(
    /** When the last pass finished with NOTHING outstanding. The honest answer to "is my work safe". */
    val lastSuccessAt: String? = null,
    /**
     * The last transient reason a pass stopped — no signal, a 502, a timeout.
     *
     * Informational and overwritten freely, because it describes the network rather than the data.
     * Kept apart from the per-item failures for exactly that reason: showing "no connection" in the
     * same list as "the server refused this photograph" trains the designer to ignore both.
     */
    val lastError: String? = null,
    /** Set when the server refused to CREATE this workshop at all. Nothing else can proceed. */
    val createFailure: String? = null,
    val createFailedAt: String? = null,
    /** [StageSyncRecord.skewRun] for the create. The costlier half: it strands the whole fortnight. */
    val createSkewRun: String? = null,
    val stages: Map<String, StageSyncRecord> = emptyMap(),
)

// --------------------------------------------------------------------------------------
// What the designer is shown
// --------------------------------------------------------------------------------------

/**
 * One workshop's answer to the only question a designer in a courtyard actually asks: is my work
 * safe yet, and if not, what is holding it up?
 *
 * Computed from the draft alone, with no network call anywhere in it. A status that needs a server
 * to be honest is a status that lies at exactly the moment it matters — and the moment it matters is
 * the moment the designer is deciding whether they can pack up and leave the cluster.
 */
data class WorkshopSyncStatus(
    val workshopId: String,
    val remoteId: String?,
    /** Stages whose payload differs from the one the server acknowledged. */
    val pendingStages: Int,
    /** Files whose bytes exist here and nowhere else. */
    val pendingMedia: Int,
    /** Bytes of those files, so "12 photographs" can also say how big a send that is. */
    val pendingMediaBytes: Long,
    /** Stages the server refused for good. */
    val failedStages: Int,
    /** Files the server refused for good. The bytes are still here; see [DraftMedia.uploadFailure]. */
    val failedMedia: Int,
    /** Every refusal and hold-up, already written as a sentence. Longest-standing first. */
    val problems: List<String>,
    val lastSuccessAt: String?,
    val lastError: String?,
    /** Files the server HAS acknowledged whose bytes are also still here — what "free up space" frees. */
    val releasableMedia: Int,
    val releasableBytes: Long,
    /**
     * Answers the repository REFUSED inside saves it otherwise accepted — see
     * [StageSyncRecord.refusedFields].
     *
     * Counted apart from [failedStages] because they are not the same event and do not have the same
     * remedy. A failed stage did not save; a refused answer saved everything around it and left the
     * repository holding its previous value. Folding them together would either say a stage failed
     * when it did not, or go on saying nothing at all — which is what this app did.
     */
    val refusedAnswers: Int = 0,
    /**
     * Stages holding a row deletion THAT PHYSICALLY CANNOT TRAVEL YET — see [StageDraft.emptiedEntities].
     *
     * ── THE DEFECT THIS CLOSES, WHICH IS THE SAME DEFECT AS [refusedAnswers] ONE FIELD ALONG ──────
     *
     * Deleting the last row of a collection is INVISIBLE in a stage payload: entries are built from
     * `rowsFor(key)`, which is simply empty, so the deletion has to be stated separately. It is stated
     * in `emptiedEntities` — and [buildStageBody] sends that list ONLY when the draft is authoritative,
     * because asserting a deletion while disclaiming the knowledge that justifies one is the wrong half
     * of the trade. Correct, and it leaves a hole: for an unauthoritative stage the deletion is recorded
     * on this device, carried faithfully from save to save, and NAMED IN NO PAYLOAD.
     *
     * Nothing counted it. `statusOf` never read `emptiedEntities` and `isFullySynced` had no term for
     * it, so the payload's signature matched, no stage was pending, and a workshop holding a deletion
     * that cannot be sent scored fully synced with the list row reading "Backed up to the server". The
     * designer deleted six process steps in a courtyard, was told their work was safe, and the six rows
     * are still in the report.
     *
     * A COUNT OF STAGES, not of rows or of entity keys. The rows are gone from the draft — that is what
     * the deletion means — so there is nothing left here to count, and the remedy is per stage anyway:
     * one online open of the workshop reads the stage, [dwFoldServerStage] earns the authority, and the
     * next save carries it. One sentence per stage, naming the stage, is what a designer can act on.
     */
    val unsentDeletions: Int = 0,
) {
    /**
     * Nothing outstanding at all: the record exists remotely and every stage and file has landed.
     *
     * [refusedAnswers] IS PART OF THIS, and leaving it out is how "a refusal looks like a success"
     * survived. A partly-refused save records a signature that matches, so the stage is not pending
     * and never will be; without this term the workshop scored fully synced and the list row said
     * "Backed up to the server" over an answer the repository had declined to store.
     *
     * [unsentDeletions] IS PART OF IT FOR THE IDENTICAL REASON, and was found by looking for the same
     * shape one field along: work this device is holding that no future payload will carry, so the
     * signature matches for ever and nothing is pending. "Backed up" has to mean the repository holds
     * what this phone holds, and a deletion the repository has not been told about is a way in which it
     * does not.
     *
     * A STAGE THE REGISTRY NO LONGER DECLARES is held by the same term, through [pendingStages] — see
     * [dwStrandedStages] for why it is counted there rather than given a counter of its own.
     */
    val isFullySynced: Boolean
        get() = remoteId != null && pendingStages == 0 && pendingMedia == 0 &&
            failedStages == 0 && failedMedia == 0 && refusedAnswers == 0 && unsentDeletions == 0

    val hasFailures: Boolean get() = failedStages > 0 || failedMedia > 0

    /** One line for a list row. Deliberately counts things, never percentages. */
    val summary: String
        get() = when {
            remoteId == null && pendingStages == 0 && pendingMedia == 0 -> "On this device only"
            isFullySynced -> "Backed up to the server"
            hasFailures -> buildList {
                if (failedStages > 0) add("$failedStages stage${plural(failedStages)} refused")
                if (failedMedia > 0) add("$failedMedia file${plural(failedMedia)} refused")
            }.joinToString(", ")
            // Said in its own words rather than through the "waiting to upload" branch below, which
            // would be false: nothing is waiting, the save has already happened and the repository
            // has already declined it. Nothing will change until the answer is corrected.
            //
            // AND THE SAME ARM CARRIES THE DELETIONS, for a plainer reason as well as that one. With
            // nothing pending and nothing refused, an unsent deletion fell through to `else` — where
            // every clause of the `buildList` is false, so the joined string is EMPTY and the row read
            // " waiting to upload" with a leading space and no subject. A blank sentence over a
            // workshop that is not backed up, in the one place a designer looks to decide whether they
            // can leave.
            pendingStages == 0 && pendingMedia == 0 && (refusedAnswers > 0 || unsentDeletions > 0) ->
                buildList {
                    if (refusedAnswers > 0) add("$refusedAnswers answer${plural(refusedAnswers)} refused")
                    if (unsentDeletions > 0) {
                        // STAGES, and the words say so. It counts stages holding a deletion, not rows
                        // — the rows are gone from the draft, which is what the deletion means, so
                        // there is nothing left to count — and "3 deleted rows" over three stages
                        // holding six between them would be a number nobody could reconcile.
                        add("$unsentDeletions stage${plural(unsentDeletions)} with a deletion not sent")
                    }
                }.joinToString(", ") + " — the rest is backed up"
            else -> buildList {
                if (remoteId == null) add("not created on the server")
                if (pendingStages > 0) add("$pendingStages stage${plural(pendingStages)}")
                if (pendingMedia > 0) add("$pendingMedia file${plural(pendingMedia)}")
            }.joinToString(", ").replaceFirstChar { it.uppercase() } + " waiting to upload"
        }
}

private fun plural(count: Int): String = if (count == 1) "" else "s"

/**
 * THE DEVICE-WIDE BANNER'S TWO SENTENCES, DECIDED HERE AND NOT IN THE COMPOSABLE.
 *
 * ── THE DEFECT THIS CLOSES, MEASURED ON THE HANDSET ──────────────────────────────────────────────
 *
 * [WorkshopSyncStatus.summary] was taught to say "$n answers refused — the rest is backed up" rather
 * than fall through to its "waiting to upload" arm, with the reason written on that branch: *nothing
 * is waiting, the save has already happened and the repository has already declined it.* The banner
 * above the same list went on saying the opposite. Its caller counted `failedStages + failedMedia`
 * and never [WorkshopSyncStatus.refusedAnswers], so a workshop whose ONLY outstanding item was a
 * refused answer contributed a workshop to the count and nothing to any of the numbers — and the
 * headline fell through to its `ifBlank` default.
 *
 * Reproduced on SM-M325F / Android 13 against the live API: DESIGN_BRIEF saved with
 * `intendedPriceLow = "1.2.3"` and `intendedPriceHigh = "-500"`, both refused, nothing else pending,
 * the phone on Wi-Fi. The row read "2 answers refused — the rest is backed up"; ten pixels above it
 * the banner read **"Waiting to upload / Across 1 workshop(s) on this device. Everything is saved
 * here and editable offline; it uploads whenever there is a connection."** under a CLOUD-OFF icon.
 * Every clause of that is false: nothing is waiting, there IS a connection, and no amount of signal
 * will ever move those two answers. It is the same wrong story [isConnectionFailure] exists to stop
 * telling — a designer sent out of a building to look for a bar of signal over an answer the
 * repository has already considered and declined — told by the one surface that is drawn above
 * every row, and it contradicted the row beneath it while the call site's own comment claimed the
 * two "can never disagree about the same fact".
 *
 * ── WHY IT IS PURE ───────────────────────────────────────────────────────────────────────────────
 *
 * Because this is a decision — which of two incompatible stories to tell about the same phone — and
 * decisions in this repository are checkable on a desktop JVM. The composable is left holding only
 * the drawing. [bytesText] is passed in already formatted so this function needs nothing from the ui
 * package; it is a label, never a number this reasons about.
 */
data class DwDeviceSyncBanner(
    /** The counts, in the units a person can compare against what they captured. */
    val headline: String,
    /** What that means and what will happen next. */
    val detail: String,
    /**
     * Whether ANYTHING here is genuinely waiting for a connection.
     *
     * False when the only outstanding items are ones a connection does not move by itself — a refused
     * answer, or a deletion that needs a stage READ before it can be stated — and it decides the icon:
     * a cloud with a line through it is a claim about the network, and drawing it over either of those
     * is the claim this whole banner was making wrongly. Same rule, same reason, as
     * [WorkshopSyncChip]'s.
     */
    val waiting: Boolean,
)

/**
 * What the banner above the workshop list should say, or null when it should not be drawn at all.
 *
 * [workshops] is how many workshops have anything outstanding at all — refusals included, which is
 * why a refusal-only device still gets a banner rather than silence.
 */
fun dwDeviceSyncBanner(
    workshops: Int,
    stages: Int,
    files: Int,
    bytesText: String,
    failures: Int,
    refusedAnswers: Int,
    /**
     * Stages holding a row deletion no payload can carry yet — see [WorkshopSyncStatus.unsentDeletions].
     *
     * THE FIFTH THING A ROW CAN BE OUTSTANDING FOR, AND IT ARRIVED THE SAME WAY THE FOURTH DID. Making
     * it a term of [WorkshopSyncStatus.isFullySynced] is what puts such a workshop into the caller's
     * `outstanding` list — and with no counter here it would have contributed a workshop to
     * [workshops] and nothing to any number, so this banner would draw a workshop it cannot name and
     * fall through to "Waiting to upload … it uploads whenever there is a connection". That is
     * word-for-word the defect this function was written to end, one field along. Defaulted so the
     * existing callers and their pinned sentences are unchanged.
     */
    unsentDeletions: Int = 0,
): DwDeviceSyncBanner? {
    if (workshops == 0 && failures == 0 && refusedAnswers == 0 && unsentDeletions == 0) return null
    // FALSE IN EXACTLY TWO SITUATIONS, and they are the two things a connection alone does not fix:
    // an answer the repository has already read and declined, and a deletion that needs this phone to
    // READ a stage before it can be stated. Everything else on this banner — a stage whose payload has
    // not been sent, a photograph whose bytes are only here, a workshop with no server record at all —
    // really is waiting for a connection, and the cloud-off icon is honest over it.
    //
    // A DELETION SITS ON THIS SIDE OF THE LINE EVEN THOUGH ITS REMEDY NEEDS SIGNAL, and the distinction
    // is what the icon actually claims: that the pass will carry it away by itself, the next time there
    // is a bar. It will not. `pushStages` only ever PUSHES, so no background pass will ever read a
    // stage, and the deletion sits there through a hundred passes on perfect Wi-Fi until a person opens
    // the stage. An icon promising otherwise is the same sentence that sent a designer out of a
    // building looking for signal.
    val waiting = stages > 0 || files > 0 || (refusedAnswers == 0 && unsentDeletions == 0)
    val headline = buildList {
        if (stages > 0) add("$stages stage${plural(stages)}")
        if (files > 0) add("$files file${plural(files)}, $bytesText")
        if (failures > 0) add("$failures refused outright")
        if (refusedAnswers > 0) add("$refusedAnswers answer${plural(refusedAnswers)} refused")
        if (unsentDeletions > 0) {
            add("$unsentDeletions stage${plural(unsentDeletions)} with a deletion not sent")
        }
    }.joinToString(" · ").ifBlank {
        // Reached only when a workshop is outstanding for a reason none of the four counters names —
        // it exists nowhere but this phone. That IS waiting for a connection, so the old default is
        // still the true sentence for it, and it is now the ONLY thing that reaches it.
        "Waiting to upload"
    }
    val across = "Across $workshops workshop${plural(workshops)} on this device."
    val one = refusedAnswers == 1
    val them = if (one) "it" else "them"
    /*
      THE DELETION'S OWN SENTENCE, AND WHAT MAKES IT DIFFERENT FROM EVERY OTHER CLAUSE HERE.

      Every other thing on this banner is moved by a sync pass. This one is not moved by ANY number of
      passes: `emptiedEntities` only travels on a payload that also claims `replaceCollections`, that
      claim is [StageDraft.stageSeen], and `stageSeen` is earned by a `GET .../stages/{key}` that only
      the stage SCREEN ever makes. So the instruction a designer needs is not "wait for signal" and not
      "press sync" — it is "open the stage", and it is the only instruction on this surface that names a
      screen. Saying anything vaguer leaves a deleted row alive on the server for ever while the phone
      reports the workshop backed up, which is the defect being closed.
    */
    val dOne = unsentDeletions == 1
    val deletionSentence = "$unsentDeletions stage${plural(unsentDeletions)} " +
        (if (dOne) "holds a row deletion" else "hold row deletions") +
        " that ${if (dOne) "has" else "have"} not reached the server, and a sync will NOT move " +
        "${if (dOne) "it" else "them"}: this phone has to READ " +
        "${if (dOne) "that stage" else "those stages"} once before it can tell the server what to " +
        "delete. Open the workshop, then the stage, with a connection — the deletion goes up on the " +
        "save straight after."
    val detail = buildString {
        append(
            when {
                // NOTHING IS WAITING. Said in its own words, because the sentence below is the one that
                // sent a designer looking for signal over an answer no amount of signal will move.
                refusedAnswers > 0 && !waiting -> "$across Everything else is on the server. " +
                    (if (one) "That answer" else "Those answers") +
                    " will NOT upload by ${if (one) "itself" else "themselves"} — the repository has " +
                    "already read $them and declined, and kept what it already held. Open the " +
                    "workshop, then the stage, to see which and correct $them."
                // Both at once: the first half is true of the pending work and false of the refusals,
                // so the refusals get their own clause rather than being covered by "it uploads when
                // there is a connection".
                refusedAnswers > 0 -> "$across Everything is saved here and editable offline; the " +
                    "stages and files above upload whenever there is a connection. The " +
                    "$refusedAnswers refused answer${plural(refusedAnswers)} will not — the " +
                    "repository has already declined $them and needs $them corrected."
                // The same two arms again for a device whose only stuck item is a deletion. They are
                // arms and not an appended clause because the FIRST half differs: with nothing pending
                // "everything else is on the server" is true and is the reassurance that stops a
                // designer re-checking twenty stages.
                unsentDeletions > 0 && !waiting ->
                    "$across Everything else is on the server. $deletionSentence"
                unsentDeletions > 0 -> "$across Everything is saved here and editable offline; the " +
                    "stages and files above upload whenever there is a connection. $deletionSentence"
                else -> "$across Everything is saved here and editable offline; it uploads whenever " +
                    "there is a connection."
            }
        )
        // BOTH KINDS OF STUCK WORK AT ONCE. The refusal arms above have already run and said nothing
        // about the deletion, and the two have different remedies — correct an answer, versus open a
        // stage — so neither can stand in for the other. Appended rather than given a fifth arm, so
        // there is one copy of this sentence.
        if (refusedAnswers > 0 && unsentDeletions > 0) {
            append(" ")
            append(deletionSentence)
        }
    }
    return DwDeviceSyncBanner(headline = headline, detail = detail, waiting = waiting)
}

/** The whole device in one line, for a banner above the list. */
data class SyncPassResult(
    val workshopsCreated: Int = 0,
    val stagesSent: Int = 0,
    val mediaUploaded: Int = 0,
    val refused: Int = 0,
    /** True when the pass gave up because the network went away. Nothing was lost or marked failed. */
    val stoppedOffline: Boolean = false,
    /** True when there was no connection (or no session) to start with, so nothing was attempted. */
    val skipped: Boolean = false,
) {
    val didAnything: Boolean get() = workshopsCreated > 0 || stagesSent > 0 || mediaUploaded > 0
}

// --------------------------------------------------------------------------------------
// Triage
// --------------------------------------------------------------------------------------

/**
 * Did this failure happen to the CONNECTION, rather than being something the server decided?
 *
 * THE COMPANION QUESTION TO [WorkshopRepository.isTransient], AND NOT THE SAME ONE — the port of
 * `isUnreachable` in `frontend/lib/offline.ts`, which the web added for this exact defect on this
 * exact endpoint. `isTransient` asks "is it worth trying again" and every 5xx is a yes to it, which
 * is correct for the record outbox (its only decision is whether to keep the entry) and wrong here,
 * because this pass also STOPS on a yes and puts "the connection dropped" on the screen.
 *
 * What that cost: `save_stage` answers deterministically-bad data with a 500 — a lone surrogate in a
 * name and a non-finite decimal both reproduce that way — so one stage the repository will never
 * accept told the designer their signal was gone on a phone showing four bars, skipped every
 * workshop queued behind it (the pass breaks on the first stop and `syncAll` sorts oldest-first),
 * and came back to the identical rejection on every future connection, for ever, with nothing on any
 * screen naming the stage.
 *
 * The three statuses that stay on the connection's side of the line, and why:
 *
 *  - 401: the credential expired, not the item. Every item would fail this way and re-signing in
 *    fixes all of them at once — the one 4xx [WorkshopRepository.isTransient] also excuses.
 *  - 408: a proxy saying the request never completed, so the server decided nothing.
 *  - 429: the server explicitly asking for time.
 *
 * Everything that is not an [HttpException] — no route to host, a socket dropped mid-transfer, a
 * payload that will not parse — is deferred to [WorkshopRepository.isTransient] rather than
 * re-decided here, so there is still exactly ONE answer to "is this the network" for the shapes both
 * files see.
 */
private fun WorkshopRepository.isConnectionFailure(error: Throwable): Boolean {
    val http = error as? HttpException ?: return isTransient(error)
    return when (http.code()) {
        401, 408, 429 -> true
        else -> false
    }
}

/**
 * THIS RUN OF THE APP. One value for the life of the process, and its only job is to be different
 * next time. See [blocksRetry].
 *
 * A process, not an install and not a build number: Android tears the process down when the app has
 * been in the background for a while and always when the APK is replaced, so "a new run" covers both
 * an update and simply opening the app again the next morning.
 */
internal val APP_RUN: String = UUID.randomUUID().toString()

/**
 * Must this recorded refusal stop the pass from trying the item again on its own?
 *
 * ── WHY [StageSyncRecord.permanent] WAS NOT ENOUGH ───────────────────────────────────────────────
 *
 * `permanent` means "the server answered, so a better connection will not help", and it was used for
 * two refusals that are nothing alike:
 *
 *  * one the DESIGNER can fix — a rejected answer, a workshop an admin deleted, a duplicate. Sending
 *    it again unchanged really would get the same answer for ever, and the status line is right to
 *    wait for a person. `skewRun` is null and this returns true, exactly as before.
 *
 *  * a SCHEMA refusal ([ApiRefusal.schemaSkew]) — this build of the app and this build of the API
 *    disagree about the shape of the request. Nobody typed anything wrong and no edit can clear it;
 *    what clears it is an UPDATE TO ONE OF THE TWO. Calling that permanent means the phone can never
 *    recover from a skew even once the skew has gone. The web hit exactly this on 2026-08-08 with the
 *    then-new `merge` key, which this file also sends (`buildStageBody`, `merge = !authoritative`),
 *    and the stored refusal outlived the fix: the API had learned `merge` and answered 200 to the
 *    very PUT the banner was still refusing to make.
 *
 * ── THE TRIGGER, AND WHY IT IS THE APP RUN ───────────────────────────────────────────────────────
 *
 * A schema refusal is re-attempted ONCE PER APP RUN. Not once per pass: this engine is driven from
 * sign-in, a 45-second fallback timer and the connectivity callback (see the header), and on a
 * handset walking in and out of coverage in a village that is dozens of passes an hour — against a
 * server that really is too old, 22 stages × every flap is a prepaid data bill for 422s nobody reads.
 *
 * And not on a version-name change, which is the obvious answer and the wrong one: a skew closes when
 * EITHER side is updated, and this phone cannot see the API's build. There is no version on any
 * response, and the one server digest it does hold — the schema `version` — is documented in
 * `backend/app/services/stage_schema.py::registry_version` as a digest of registry keys, types,
 * tiers, derivations and hydration, which the wire schema is not part of. Measured: the 2026-08-08
 * skew was closed by adding `merge` to `StageSaveIn`, which moved no registry digest at all, so a
 * version check would have gone on refusing the stage for ever. A new app run is the coarser signal
 * that covers both — every APK update starts one — and its cost is bounded by something a person
 * does rather than by the weather.
 *
 * Kept in step with `blocksRetry` in `frontend/lib/offline.ts`, which is the same three lines.
 */
internal fun blocksRetry(permanent: Boolean, skewRun: String?): Boolean {
    if (!permanent) return false
    if (skewRun.isNullOrBlank()) return true
    return skewRun == APP_RUN
}

/** [blocksRetry] for a stage, including the "never refused at all" case. */
private fun StageSyncRecord?.blocksRetry(): Boolean = blocksRetry(this?.permanent == true, this?.skewRun)

/**
 * What "Try again" leaves on ONE stage record — see the long note at the call site in `retryWorkshop`
 * for why a server-reported note survives the button and a self-clearing one does not.
 *
 * `internal` and top-level ON PURPOSE, like [blocksRetry] above it. The rule used to live as an
 * `if/else` inside a `mapValues` inside an `updateBookkeeping` inside a suspend function, so asking it
 * one question needed a Context, a filesystem, a coroutine and a whole draft on disk — and so nothing
 * asked it, which is how it came to key on the one field that is 0 in the case it most had to keep.
 */
internal fun retriedStageRecord(record: StageSyncRecord): StageSyncRecord =
    if (record.refusedFields > 0 || record.refusal != null) {
        record.copy(permanent = false, skewRun = null)
    } else {
        record.copy(
            failure = null, failedAt = null, permanent = false, skewRun = null,
            waitingOnFiles = false,
        )
    }

/**
 * What to write on an item the server REFUSED, including the one fact the designer cannot infer.
 *
 * A bare 500 renders through [apiErrorMessage] as "HTTP 500 Internal Server Error", which reads like
 * anything at all — including like a bad connection, which is the belief this whole split exists to
 * end. So an answered 5xx says out loud that waiting for signal will not help. A 4xx already carries
 * the server's own `detail`, written for the person reading it, and is left alone.
 *
 * Returns [ApiRefusal] rather than a string so the caller also learns whether the refusal was a
 * dialect mismatch. That has to come out of the SAME call — Retrofit's error body is consumed by
 * reading it, so asking twice would answer "not a schema refusal" to every failure on the device.
 *
 * Call once per failure.
 */
private fun Throwable.refusal(fallback: String): ApiRefusal {
    val refusal = apiRefusal(fallback)
    val text = refusal.message
    val code = (this as? HttpException)?.code() ?: return refusal
    if (code < 500) return refusal
    // Stopped first. [apiErrorMessage] hands back whatever the server wrote, or — for the 500 that
    // carried no body, which is the shape this branch was written for — Retrofit's own "HTTP 500
    // Internal Server Error", and neither ends in one. Without this the two ran together as "HTTP
    // 500 Internal Server Error The server answered, so…", a single unpunctuated clause a designer
    // skims and abandons before the half that tells them waiting for signal cannot help.
    val lead = if (text.endsWith('.') || text.endsWith('!') || text.endsWith('?')) text else "$text."
    return refusal.copy(
        message = "$lead The server answered, so a better connection will not help — this will keep " +
            "being refused until whatever caused it is corrected. Use Try again once it has been."
    )
}

/**
 * The sentence for a refusal nobody on this phone can act on, and the run stamp that ends it.
 *
 * Wording deliberately mirrors the web's (`frontend/lib/designWorkshopStore.ts`) — a designer moves
 * between the two apps mid-workshop and must not be told two different stories about one refusal.
 * The last clause is the load-bearing one: it is a promise that this policy is what keeps.
 *
 * INTERNAL rather than private because the records outbox (`WorkshopRepository.syncOutbox`) says the
 * same thing about the same refusal. A second copy of these words is a second chance for one of them
 * to be corrected and the other left telling a researcher to go and fix an answer that is not wrong.
 */
internal fun skewSentence(what: String, said: String): String =
    "$what could not be read by the repository: $said Nothing you typed is wrong and nothing has been " +
        "thrown away — this app and the repository are out of step, and no edit will clear it. Your " +
        "work is safe on this device, and it will be sent by itself the next time you open the app " +
        "after either has been updated; you do not have to do anything."

/**
 * What one stage still owes the server as a DELETION — nothing about values, rows or files.
 *
 * Both records are read together because they are one fact to a designer ("I deleted things here and
 * the repository has not been told") and because every caller wants both: the counter that names the
 * stage on the status screen, and the gate below that decides whether a push has anything to say.
 */
internal class DwOwedDeletions(
    /** Collections emptied outright — see [StageDraft.emptiedEntities]. */
    val entities: List<String>,
    /** Individual rows removed from a collection that still holds others — see [StageDraft.deletedRowKeys]. */
    val rows: List<String>,
) {
    val any: Boolean get() = entities.isNotEmpty() || rows.isNotEmpty()
}

/**
 * The deletions a stage is holding, intersected with the entities that stage actually declares.
 *
 * INTERSECTED, in both halves, for the reason [buildStageBody] intersects the same list before it puts
 * it on the wire: a key left behind by a registry that has since moved on is not a deletion instruction
 * for anything. Counting one would hold a stage unsynced for ever over an entity no build can draw, and
 * sending one would ask the server to sweep a collection this release does not know about.
 *
 * PURE, and extracted from the two walks that used to ask this inline, because those two walks have to
 * agree: `statusOf` tells the designer whether their deletion is safe and `pushStages` decides whether
 * it is ever sent. When they disagreed, the disagreement was silent and pointed the wrong way — see
 * [dwStageSaysNothing].
 */
internal fun dwOwedDeletions(spec: StageDto, stored: StageDraft?): DwOwedDeletions {
    val declared = spec.collections.mapTo(HashSet()) { it.key }
    return DwOwedDeletions(
        entities = stored?.emptiedEntities.orEmpty().filter { it in declared }.distinct(),
        rows = stored?.deletedRowKeys.orEmpty()
            .filter { it.substringBefore(DW_ROW_KEY_SEPARATOR, "") in declared }
            .distinct(),
    )
}

/**
 * Whether a draft on this device was captured by an account OTHER than the one signed in now.
 *
 * PURE AND SEPARATE so the rule can be pinned by a test with no Context, no store and no network —
 * `OwnerFilterWireTest`'s neighbour, `DwDraftOwnershipTest`. The rule itself is one line; what needs
 * writing down is the two cases that must NOT stop a sync, because getting either wrong strands real
 * fieldwork rather than merely leaking it:
 *
 *  * `ownerUserId == null` — the draft pre-dates the stamp, or was created by a path that does not
 *    set it (a workshop opened from the server has its draft written by the stage screen). Refusing
 *    those would be a silent, total sync stop on every handset upgraded into this build.
 *  * `signedInUserId == null` — nobody is signed in, so there is nothing to compare against; the pass
 *    cannot run at all in that state and the caller checks `hasToken` first anyway.
 *
 * See [WorkshopDraft.ownerUserId] for what the mismatch costs when it is not enforced: A's fortnight
 * created on the server under B's credentials, with B as `createdById`.
 */
internal fun dwDraftIsForAnotherAccount(ownerUserId: String?, signedInUserId: String?): Boolean =
    ownerUserId != null && signedInUserId != null && ownerUserId != signedInUserId

/**
 * The stages a draft holds that the resolved registry does not declare AND that hold work.
 *
 * ── THE DEFECT: BOTH PASSES WALK THE REGISTRY, SO NEITHER COULD SEE THIS ─────────────────────────
 *
 * `statusOf` and `pushStages` enumerate `schema.stages` and look each one up in the draft. There was
 * no residual term for the other direction, `draft.stages.keys - schema.stages.keys`, so a stage the
 * draft holds under a key this registry does not declare contributed nothing to `pendingStages`,
 * nothing to `problems`, was never built and never sent — and `isFullySynced` was true over it, with
 * the list row reading "Backed up to the server" about answers that could not leave the phone by any
 * route.
 *
 * It cannot be conjured: every key in a draft was written by `persistLocally` from SOME resolved
 * registry. It takes the server's stage list renaming or dropping a key that drafts on this handset
 * already hold, or a registry cache this build cannot decode (`readCacheFile` deletes it and `load`
 * falls back to the bundled asset) on a device that then stays offline. Narrow, and permanent while
 * it lasts, which is the combination that makes it worth a line rather than a shrug.
 *
 * ONLY A STAGE THAT ACTUALLY HOLDS SOMETHING. An empty record under a retired key is a stage somebody
 * opened once and never typed into; naming it would put a permanent sentence on the status screen of
 * every handset that survived a registry change, for no answers at all.
 *
 * Extracted and pure for the reason [dwOwedDeletions] is: it is asked once rather than copied, and it
 * can be pinned on a desktop JVM with no Context — `DwStrandedStageTest`.
 */
internal fun dwStrandedStages(schema: SchemaResponse, draft: WorkshopDraft): List<StageDraft> {
    val declared = schema.stages.mapTo(HashSet()) { it.key }
    return draft.stages.entries
        .filter { (key, stored) ->
            key !in declared &&
                // Only a stage that actually holds something: an empty record under a retired key is
                // a stage somebody opened once, and naming it would put a permanent sentence on the
                // status screen of every handset that survived a registry change.
                //
                // [StageDraft.holdsAnswers] rather than the inline `values.isNotEmpty()` this used to
                // be, and the difference is the whole of "for no answers at all": a stage whose only
                // content is `_recordingPlace` holds a key no build can ever put on the wire, so the
                // sentence this function writes about it could never be discharged by any action the
                // designer took. That is worse than the permanent sentence the comment above already
                // refuses, not better — it is the same sentence with no remedy at all.
                stored.holdsAnswers()
        }
        .map { it.value }
}

/**
 * Whether a stage has NOTHING for the server, asked once for the status pass and the push pass alike.
 *
 * ── THE DEFECT THIS FUNCTION IS NAMED AFTER: A DELETION IS NOT "NOTHING" ─────────────────────────
 *
 * `statusOf` and `pushStages` each carried their own copy of one test — "this stage holds no values, no
 * rows and no custom answers, and the server has never acknowledged a payload for it, so there is
 * nothing to say about it" — and neither copy consulted the deletion records. That is not a corner: a
 * stage whose entire content WAS a collection (eight of the twenty-two declare no singleton at all)
 * holds exactly nothing the moment the designer deletes its rows, so the deletion-only stage is
 * precisely the arm that early-return swallows.
 *
 * The walk it cost: designer opens a costing stage in signal, gets six rows and types nothing, so no
 * signature is ever recorded; that afternoon, out of signal, they delete all six; the save writes
 * `rows = []`, `stageSeen = true`, `emptiedEntities = ["costLine"]` and the PUT throws, recording
 * nothing. Every later pass then found the stage empty and unsent, `continue`d, counted no pending
 * stage — and `isFullySynced` was true, so the list row read "Backed up to the server" while the six
 * superseded rows stayed in the repository and printed in the .docx the ministry receives. The
 * `unsentDeletions` counter did not see it either: it sits behind `!isAuthoritative`, and this stage IS
 * authoritative — it was read that morning — which is exactly what makes the deletion SENDABLE.
 *
 * ── SO THE DELETION DEFEATS THE EARLY RETURN ONLY WHEN IT CAN ACTUALLY TRAVEL ────────────────────
 *
 * Three conditions, and all three are load-bearing:
 *
 *  * the workshop exists remotely, because with no server record there is nothing anywhere that could
 *    still be holding the rows;
 *  * the draft is authoritative, because [buildStageBody] puts `emptiedEntities` on the wire ONLY
 *    alongside `replaceCollections`, so an unauthoritative payload carries no deletion at all. Letting
 *    that stage through would build an empty merge body, PUT it, record its signature and change
 *    nothing — while flatly contradicting the sentence `statusOf` prints beside it ("Nothing else about
 *    this stage is held up"). The unauthoritative case is the one `unsentDeletions` counts, and it is
 *    counted rather than pushed because a push cannot fix it; only opening the stage can;
 *  * something is actually owed, so this cannot make every empty stage on the device pending.
 *
 * What the caller gets from a `false` here is a fall-through to the ordinary signature comparison,
 * which already answers "different" for a stage whose recorded signature is absent — so the deletion is
 * reported as PENDING, is built by `buildStageBody` with `replaceCollections = true` and the entity
 * named, and `recordStageSent` clears the record scoped to what the acknowledged body carried.
 */
internal fun dwStageSaysNothing(
    spec: StageDto,
    stored: StageDraft?,
    record: StageSyncRecord?,
    workshopIsRemote: Boolean,
): Boolean {
    // `custom` counted alongside `values` and `rows`, and it is not a tidy-up: EIGHT of the twenty-two
    // stages declare no singleton entity at all, so "a stage whose only answers are custom" is the
    // ordinary case for a designer extending sketch development or costing, not an edge case. Left
    // out, such a stage is judged empty, is never reported as pending, and never syncs.
    //
    // ASKED THROUGH [StageDraft.holdsAnswers], WHICH IS ALSO WHAT MAKES AN UNDERSCORE KEY NOT AN
    // ANSWER. This was `stored.values.isEmpty() && …` inline, and `values` is where the recording
    // place lands — a key `wireData` strips from every payload by construction. So a stage whose only
    // content was "where were you when this was filled in?" was judged to have something to say, and
    // the pass spent a metered PUT on a body carrying an empty singleton, no rows and no custom
    // answers, creating a server-side stage record for a stage nobody had answered. Once per such
    // stage, because the recorded signature then matches — a wasted request and a phantom record
    // rather than a loop, which is why it survived this long.
    val empty = stored == null || !stored.holdsAnswers()
    if (!empty) return false
    // An empty stage that HAS been sent is a stage the designer emptied, and it must still go —
    // `replaceCollections` is what carries a deletion, and skipping it would leave the deleted rows
    // alive on the server to reappear in the report.
    if (!record?.signature.isNullOrBlank()) return false
    // And an empty stage that has NEVER been sent still speaks if it is holding a deletion it is
    // entitled to state. See the KDoc for why all three halves of this are required.
    if (workshopIsRemote && isAuthoritative(stored, record) && dwOwedDeletions(spec, stored).any) {
        return false
    }
    return true
}

// --------------------------------------------------------------------------------------
// The engine
// --------------------------------------------------------------------------------------

object WorkshopSyncEngine {

    /**
     * One pass at a time across the whole device, not one per workshop.
     *
     * The sign-in coroutine, the 12-second fallback timer, the connectivity callback and a "Sync
     * now" tap can all arrive within the same second (they routinely do — the callback fires as the
     * timer comes round). Two passes over one draft would both read "no remoteId yet" and both
     * create the workshop, which is the duplicate-record failure the write-back ordering exists to
     * prevent, defeated by concurrency instead of by a crash.
     */
    private val pass = Mutex()

    private val _busyWorkshop = MutableStateFlow<String?>(null)

    /** The workshop currently being sent, or null. Lets a row say "Sending…" on the row itself. */
    val busyWorkshop: StateFlow<String?> = _busyWorkshop.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /**
     * Bumped after every durable change a pass makes.
     *
     * A background pass that finishes uploading nine photographs while the list is on screen must be
     * visible without the designer pulling to refresh — the whole point of the indicator is that
     * nobody has to guess. Screens collect this and recompute their status from disk; it carries no
     * data itself, deliberately, so there is exactly one source of truth and it is the draft.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    // ── Status ───────────────────────────────────────────────────────────────────────────────────

    /**
     * What is outstanding for one workshop, computed from the draft and the registry with no network
     * call. See [WorkshopSyncStatus] for why that constraint is not negotiable.
     */
    suspend fun statusOf(
        context: Context,
        schema: SchemaResponse,
        draft: WorkshopDraft,
    ): WorkshopSyncStatus = withContext(Dispatchers.Default) {
        val mediaById = draft.media.associateBy { it.id }
        // Read from disk only — never fetched here. A status pass runs on a timer with no screen
        // attached, and it must report what a push would ACTUALLY send, which is decided by the
        // definition this device is holding right now.
        val definition = DwCustomSectionStore.load(context, draft.workshopId)
        val problems = ArrayList<String>()

        draft.sync.createFailure?.let {
            problems += "This workshop could not be created on the server: $it"
        }

        var pendingMedia = 0
        var pendingBytes = 0L
        var failedMedia = 0
        var releasable = 0
        var releasableBytes = 0L
        draft.media.forEach { descriptor ->
            val file = WorkshopDraftStore.mediaFile(context, draft.workshopId, descriptor)
            // `isFile` for the reason given in `uploadPending`: a blank relative path resolves
            // to the workshop directory, and counting that as a present photograph would report
            // bytes that can never be uploaded and space that can never be freed.
            val onDisk = file.isFile
            when {
                descriptor.uploadFailure != null -> {
                    failedMedia++
                    problems += "“${descriptor.originalFilename}” was refused: ${descriptor.uploadFailure}"
                }
                descriptor.isConfirmedRemote -> if (onDisk) {
                    releasable++
                    releasableBytes += file.length()
                }
                else -> {
                    pendingMedia++
                    if (onDisk) pendingBytes += file.length()
                }
            }
        }

        var pendingStages = 0
        var failedStages = 0
        var unsentDeletions = 0
        val remoteId = remoteIdOf(draft)
        schema.stages.forEach { spec ->
            val stored = draft.stages[spec.key]
            val record = draft.sync.stages[spec.key]
            if (record?.permanent == true) {
                failedStages++
                problems += "Stage ${spec.number} (${spec.title}): ${record.failure.orEmpty()}"
                return@forEach
            }
            /*
              A DELETION THAT CANNOT TRAVEL, COUNTED — see [WorkshopSyncStatus.unsentDeletions].

              Asked with the SAME two questions [buildStageBody] asks before it decides to send the
              list, so this cannot drift from what a push would actually carry: intersected with the
              entities this stage declares (a key left behind by a registry that has moved on is not a
              deletion instruction for anything), and gated on the authority that is the only thing
              standing in its way.

              ASKED BEFORE THE `empty` RETURN BELOW, which is the whole point. A stage whose only
              content WAS a collection is empty once its rows are deleted, so the early return — "this
              stage holds nothing and has never been sent, there is nothing to say about it" — is
              exactly the arm a deletion-only stage falls down, and it is why nothing counted this.

              Gated on the workshop existing remotely because with no server record there is nothing
              anywhere that could still hold the rows, and warning about one would fire on every stage
              of every workshop captured in a courtyard. It CANNOT stick: one online open of the stage
              reads it, [dwFoldServerStage] earns the authority, the next save carries the list and
              `recordStageSent` clears the keys it carried.

              ── AND THE OTHER SIDE OF `isAuthoritative`, WHICH THIS BLOCK IS NOT ────────────────────

              Read into `owed` ABOVE the gate, and read again by [dwStageSaysNothing] below, because one
              deletion record means two different things on the two sides of this `if` and the second of
              them used to be lost completely. Unsendable, it is a PROBLEM, named here. SENDABLE — the
              stage was read this morning, so the draft is authoritative and `buildStageBody` will put
              `emptiedEntities` on the wire — it is not a problem at all, it is a PENDING STAGE, and
              until [dwStageSaysNothing] existed the `empty` return below swallowed it: not counted
              here, not counted there, `isFullySynced` true, "Backed up to the server" printed over six
              rows the designer had deleted and no payload would ever remove.
            */
            val owed = dwOwedDeletions(spec, stored)
            if (remoteId != null && !isAuthoritative(stored, record)) {
                if (owed.any) {
                    // ONE STAGE, COUNTED ONCE, however many ways it owes a deletion. The figure is a
                    // count of STAGES held up — `WorkshopSyncStatus.unsentDeletions` is rendered as
                    // "N stages", so incrementing per kind would report two stages where there is one.
                    unsentDeletions++
                    val what = buildString {
                        if (owed.entities.isNotEmpty()) {
                            append("you deleted everything in ${owed.entities.joinToString(", ")}")
                        }
                        if (owed.rows.isNotEmpty()) {
                            if (isNotEmpty()) append(", and ")
                            else append("you deleted ")
                            val n = owed.rows.size
                            append("$n row${plural(n)} from ")
                            append(
                                owed.rows
                                    .map { it.substringBefore(DW_ROW_KEY_SEPARATOR, "") }
                                    .distinct()
                                    .joinToString(", ")
                            )
                        }
                    }
                    problems += "Stage ${spec.number} (${spec.title}): $what on this phone, and that " +
                        "deletion has NOT reached the server — this stage has not been read from this " +
                        "device yet, so it cannot yet be told what to delete. Nothing else about this " +
                        "stage is held up. Open the stage once with a connection and it goes up on the " +
                        "save straight after."
                }
            }
            // ONE FUNCTION, NOT TWO COPIES OF A TEST. This gate and the one in `pushStages` were
            // written out twice, byte-identical — and stayed byte-identical right up to the point
            // where being wrong mattered: neither copy read the deletion records, so a stage holding
            // nothing but a SENDABLE deletion was skipped here (not pending) and skipped there (not
            // sent), and the workshop scored "Backed up to the server" over rows still in the report.
            // Sharing it is what makes "what the status pass says" and "what the push pass does" one
            // decision. See [dwStageSaysNothing] for all of the argument.
            if (dwStageSaysNothing(spec, stored, record, workshopIsRemote = remoteId != null)) {
                return@forEach
            }
            // Built with the same authority AND the same definition the push will use, so the
            // signature compared below is the signature that would actually be sent. Both are part of
            // the payload's meaning and therefore of its digest, so a status pass that guessed
            // differently would report every stage as pending for ever.
            val built = buildStageBody(
                spec, stored, mediaById, isAuthoritative(stored, record),
                customHeld = dwCustomHeldFor(definition, spec.key),
            )
            if (built.unresolved.isNotEmpty()) {
                pendingStages++
                return@forEach
            }
            if (signatureOf(built.body) != record?.signature) pendingStages++
        }
        /*
          AND THE OTHER DIRECTION OF THE WALK ABOVE: WHAT THE DRAFT HOLDS THAT THE REGISTRY DOES NOT
          DECLARE — see [dwStrandedStages].

          The loop above enumerates `schema.stages` and looks each one up in the draft, and so does
          `pushStages`. Neither had a residual term, so a stage key the draft holds and this registry
          does not was invisible to both: never built, never pending, never a problem, `isFullySynced`
          true and the row reading "Backed up to the server" over answers with no route off the phone.

          COUNTED AS PENDING, and that is a considered choice rather than the lazy one. It is the same
          treatment the `built.unresolved` case a few lines up already gets — a stage that cannot be
          built right now but whose answers are still owed to the server — and it means every surface
          that already speaks for a pending stage speaks for this one too, in words that are true for
          the ordinary trigger: the registry cache was lost or damaged, so it fell back to the bundled
          asset, and opening the app with a connection refetches the registry and the stage syncs on
          the pass after. A counter of its own would have to be threaded through `dwDeviceSyncBanner`
          and its caller as well, and until it was, this workshop would count towards the banner's
          `workshops` and towards none of its numbers — the exact "Waiting to upload" fall-through
          that function was written to end.

          THE PROBLEM LINE IS WHERE THE HONEST QUALIFICATION LIVES, because the other trigger — the
          server RENAMING or DROPPING a stage key — is not fixed by signal alone and the pending
          sentence would be optimistic about it.

          COUNTED HERE AND NOT ALSO IN `pushStages`. That pass can do nothing about it (there is no
          spec to build a payload from) and two copies of one test is the shape of the defect
          [dwStageSaysNothing] is named after; `syncOneWorkshop` consults this status before it decides
          a workshop is finished, so one count here is load-bearing in both.
        */
        dwStrandedStages(schema, draft).forEach { stored ->
            pendingStages++
            problems += "“${stored.title.ifBlank { stored.stageId }}”: this phone's copy of the " +
                "stage list no longer describes this stage, so the answers it holds cannot be sent " +
                "— nothing has been thrown away. Open the app with a connection so it can fetch the " +
                "current stage list; if the stage stays here after that, this workshop was built " +
                "with a stage the repository has since retired and someone will have to move the " +
                "answers by hand."
        }

        // A non-permanent note (the server dropped a field it did not recognise, an answer it
        // refused, files still to upload) is worth showing but is not a failed stage, so it is listed
        // after the refusals rather than counted with them.
        var refusedAnswers = 0
        draft.sync.stages.forEach { (key, record) ->
            if (record.permanent) return@forEach
            refusedAnswers += record.refusedFields
            if (record.failure != null) {
                val spec = schema.stages.firstOrNull { it.key == key }
                problems += "Stage ${spec?.number ?: "?"} (${spec?.title ?: key}): ${record.failure}"
            }
        }

        WorkshopSyncStatus(
            workshopId = draft.workshopId,
            remoteId = remoteId,
            pendingStages = pendingStages,
            pendingMedia = pendingMedia,
            pendingMediaBytes = pendingBytes,
            failedStages = failedStages,
            failedMedia = failedMedia,
            problems = problems,
            lastSuccessAt = draft.sync.lastSuccessAt,
            lastError = draft.sync.lastError,
            releasableMedia = releasable,
            releasableBytes = releasableBytes,
            refusedAnswers = refusedAnswers,
            unsentDeletions = unsentDeletions,
        )
    }

    // ── The pass ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Send everything this device is holding, oldest workshop first.
     *
     * Oldest first because a workshop finished a fortnight ago is the one whose designer has already
     * left the cluster and can no longer re-capture anything; the one being typed into right now is
     * the one that can most afford to wait for the next pass, forty-five seconds later.
     *
     * Never throws. It is called from a background coroutine with no screen attached, and a sync
     * that crashes the caller would take the outbox drain down with it.
     */
    suspend fun syncAll(context: Context, repository: WorkshopRepository): SyncPassResult {
        // Checked before the lock: a device with no signal must not queue a pass behind a pass that
        // is already discovering there is no signal.
        if (!repository.hasToken() || !ConnectivityObserver.isOnline(context)) {
            return SyncPassResult(skipped = true)
        }
        return pass.withLock {
            val tally = Tally()
            try {
                val schema = repository.designWorkshopSchema(context)
                // Sorted by the draft's own stamp, ascending, so the fortnight-old workshop goes
                // first. `list` already reads every draft; the ids are all this needs.
                val ids = WorkshopDraftStore.list(context).sortedBy { it.updatedAt }.map { it.workshopId }
                for (id in ids) {
                    _busyWorkshop.value = id
                    val keepGoing = try {
                        syncOneWorkshop(context, repository, schema, id, tally)
                    } finally {
                        _busyWorkshop.value = null
                    }
                    // Transient: stop the whole pass. Everything behind this workshop shares the
                    // connection that has just been shown to be gone, and hammering it would mark
                    // twenty workshops as troubled for one reason that is really "the tunnel".
                    if (!keepGoing) {
                        tally.stoppedOffline = true
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                tally.stoppedOffline = true
            }
            bump()
            tally.toResult()
        }
    }

    /**
     * Send ONE workshop now, at the designer's explicit request, clearing every permanent failure it
     * is carrying first.
     *
     * The clearing is the point of the button. A refusal is recorded so the pass stops retrying and
     * says why; the moment a person has done something about it — signed in as the right account,
     * had a quota raised, corrected a stage — the only thing standing between the fortnight and the
     * server is a flag this device set. Retry is therefore "assume the world changed", which is
     * exactly what the designer is asserting by tapping it.
     *
     * IT IS NOW FOR THOSE CASES ONLY. A refusal whose cause is that this app and the API are out of
     * step clears itself on the next app run ([blocksRetry]), because a designer has no way of
     * knowing when tapping this would help and no reason to think a refusal that blamed their answers
     * would be cleared by tapping it at all.
     */
    suspend fun retryWorkshop(
        context: Context,
        repository: WorkshopRepository,
        workshopId: String,
    ): SyncPassResult {
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
            draft.copy(
                media = draft.media.map { it.copy(uploadFailure = null, uploadFailedAt = null) },
                sync = draft.sync.copy(
                    createFailure = null,
                    createFailedAt = null,
                    createSkewRun = null,
                    lastError = null,
                    stages = draft.sync.stages.mapValues { (_, record) ->
                        /*
                          A REFUSED ANSWER IS NOT SOMETHING "TRY AGAIN" CAN CHANGE, so this leaves it
                          exactly where it is. The button means "assume the world changed" — a quota
                          was raised, the right account was signed in, an admin restored a workshop —
                          and every one of those is a fact OUTSIDE the payload. A per-field refusal is
                          inside it: the same bytes get the same answer, and the stage will not even
                          be re-sent, because its signature already matches.

                          Clearing it here would have wiped the sentence while [refusedFields] kept
                          the workshop out of "backed up" — a status that says something is wrong and
                          has lost the line saying what. The answer is corrected in the stage form,
                          and the save that carries the correction is what clears this.

                          AND THE TEST IS [refusal], NOT [refusedFields] ALONE, because the two do not
                          agree on the case that matters most here. A save that reported ONLY
                          `droppedCustomKeys` — the designer edited their own sections on the web and
                          this phone still holds an answer to a question those sections no longer ask —
                          writes the sentence, writes a non-null [refusal] carrying the dropped keys,
                          and leaves `refusedFields` at 0, because nothing was REFUSED. Keyed on the
                          count alone this fell down the `else` arm and deleted that sentence, which is
                          the only line naming an answer the repository did not store. It is not a note
                          "Try again" can act on either: the remedy is to open the workshop once with a
                          connection so the definitions are re-read, which is what the sentence says.

                          Worse than a lost sentence, because `statusOf` scores a dropped custom key as
                          nothing at all: `failedStages` counts only `permanent` records and
                          `refusedAnswers` sums only `refusedFields`, so with the sentence gone the row
                          reads "Backed up to the server" over a question whose answer was never
                          stored. That gap is pre-existing and deliberately left alone here (it needs a
                          counter of its own on [WorkshopSyncStatus]); what is fixed is this button
                          being the thing that closes the last window onto it.
                        */
                        retriedStageRecord(record)
                    },
                ),
            )
        }
        bump()
        if (!repository.hasToken() || !ConnectivityObserver.isOnline(context)) {
            return SyncPassResult(skipped = true)
        }
        return pass.withLock {
            val tally = Tally()
            try {
                val schema = repository.designWorkshopSchema(context)
                _busyWorkshop.value = workshopId
                if (!syncOneWorkshop(context, repository, schema, workshopId, tally)) {
                    tally.stoppedOffline = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                tally.stoppedOffline = true
            } finally {
                _busyWorkshop.value = null
            }
            bump()
            tally.toResult()
        }
    }

    /** Running totals for one pass. A plain holder so the steps below can stay expressions. */
    private class Tally {
        var created = 0
        var stages = 0
        var media = 0
        var refused = 0
        var stoppedOffline = false

        fun toResult() = SyncPassResult(
            workshopsCreated = created,
            stagesSent = stages,
            mediaUploaded = media,
            refused = refused,
            stoppedOffline = stoppedOffline,
        )
    }

    /**
     * One workshop, in the three steps the file header sets out. Returns false when the pass must
     * stop because the network went away — and in that case NOTHING has been marked as failed.
     */
    private suspend fun syncOneWorkshop(
        context: Context,
        repository: WorkshopRepository,
        schema: SchemaResponse,
        workshopId: String,
        tally: Tally,
    ): Boolean {
        var draft = WorkshopDraftStore.load(context, workshopId) ?: return true

        /*
          A DRAFT ANOTHER ACCOUNT CAPTURED IS NOT THIS ACCOUNT'S TO SEND — see [WorkshopDraft.ownerUserId].

          The field was written by the create path and read by NOTHING, which made it a permission
          boundary that was recorded to disk and then never enforced. Two designers share one field
          handset (the case the field's own KDoc is written for): A captures a fortnight offline, signs
          out; B signs in, and `MainActivity`'s sign-in effect calls `syncOutbox` within the second.
          Every one of A's local-only workshops was then CREATED ON THE SERVER UNDER B'S TOKEN — B is
          `createdById`, the records land in B's list, and A has to be granted access to their own
          fieldwork. The `remoteId` written back into A's draft points at B's record.

          NULL MEANS "PRE-DATES THE FIELD" AND IS ALLOWED THROUGH, deliberately. Drafts written before
          the stamp existed, and drafts a stage screen created for a workshop opened from the server,
          carry no owner; refusing those would strand real fieldwork behind a rule they could never
          satisfy — a silent, total sync stop, which is worse than the leak this guards. Only a
          POSITIVE mismatch stops the pass, and only when somebody is actually signed in.

          IT SKIPS THE WORKSHOP RATHER THAN STOPPING THE PASS (`true`, not `false`): the connection is
          fine and every other workshop on the device should still go. And it is said out loud on the
          status line, because "did not sync" and "up to date" are otherwise the same silence.

          THE OTHER HALF OF THIS FINDING IS NOT HERE: the workshop LIST still draws A's drafts to B,
          because `WorkshopDraftStore.list` enumerates every directory and its caller does not filter.
          Sending is the half that files research under the wrong account and is fixed here; the
          display half needs the list screen, which this change deliberately does not touch.
        */
        if (dwDraftIsForAnotherAccount(draft.ownerUserId, repository.cachedUser()?.id)) {
            val note = "This workshop was captured on this phone by a different account, so it is " +
                "not sent while you are signed in. Sign in as the designer who recorded it and it " +
                "goes up on the next pass. Nothing has been deleted."
            // Written only when it CHANGES. This runs for every workshop on the device every
            // forty-five seconds; stamping the same sentence each time would rewrite the JSON
            // document of a draft that is deliberately going nowhere, on the flash storage the
            // photographs need — the same argument the `isFullySynced` early exit below makes.
            if (draft.sync.lastError != note) {
                noteSync(context, workshopId) { it.copy(lastError = note) }
            }
            return true
        }

        // NOTHING OUTSTANDING MEANS NOTHING WRITTEN, and this early exit is what makes it safe for
        // the 45-second fallback timer to call this for every workshop on the device for ever. A
        // pass that stamped "last attempted" on twenty drafts every three-quarters of a minute would
        // rewrite twenty JSON documents a minute, all day, on flash storage the photographs also
        // need — for a fact nothing displays.
        if (statusOf(context, schema, draft).isFullySynced) return true

        // ── 1. The record itself ─────────────────────────────────────────────────────────────────
        if (remoteIdOf(draft) == null) {
            // Waiting on a person, not the network — UNLESS it is waiting on an update instead, in
            // which case this run is the one that gets to find out whether the update has landed.
            if (blocksRetry(draft.sync.createFailure != null, draft.sync.createSkewRun)) return true
            val created = try {
                repository.createDesignWorkshop(
                    // Only the title and the template. Every other column the workshop list shows —
                    // craft, cluster, state, district, dates — is PROMOTED server-side out of stage
                    // 1 as that stage saves (`PROMOTED_COLUMNS`, backend/app/services/
                    // design_workshops.py), so sending a second copy from here would give one fact
                    // two writers and let them disagree.
                    DesignWorkshopCreateBody(
                        title = draft.title.ifBlank { "Untitled design workshop" },
                        templateId = draft.templateId.ifBlank { "DCH_STANDARD" },
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (repository.isConnectionFailure(e)) {
                    noteSync(context, workshopId) {
                        it.copy(lastError = e.apiErrorMessage("The server could not be reached."))
                    }
                    return false
                }
                tally.refused++
                // THE COSTLIER HALF OF THE SAME SPLIT. `DesignWorkshopCreateBody` is an `APIModel`
                // too, so a handset that has learned a new header field before the API has gets
                // `extra_forbidden` here — and holding that for ever strands not one stage but the
                // whole fortnight, header, stages and photographs alike, behind a refusal nobody can
                // act on. Same answer as the stage arm: say what happened, and let the next app run
                // find out whether the skew has closed.
                val refusal = e.refusal("The server refused to create this workshop.")
                noteSync(context, workshopId) {
                    it.copy(
                        createFailure = if (refusal.schemaSkew) {
                            skewSentence("What this copy of the app sent for this workshop", refusal.message)
                        } else {
                            refusal.message
                        },
                        createFailedAt = Instant.now().toString(),
                        createSkewRun = if (refusal.schemaSkew) APP_RUN else null,
                    )
                }
                return true
            }
            val createdId = created.id.takeIf { it.isNotBlank() }
            if (createdId == null) {
                // A 2xx that names nothing did not come from this API — a captive portal answering
                // the POST with its own sign-in page is the field case, and this app already knows
                // they exist. Retrying is the dangerous reading: if the create DID land behind the
                // portal, every pass from here would make another workshop. Recorded for a person.
                tally.refused++
                noteSync(context, workshopId) {
                    it.copy(
                        createFailure = "the server accepted this workshop but did not say what it " +
                            "saved, so it cannot be confirmed. Nothing on this device has been " +
                            "changed. If you are on a wi-fi network that asks you to sign in, " +
                            "connect properly, check whether the workshop already exists, and use " +
                            "Try again.",
                        createFailedAt = Instant.now().toString(),
                        // Explicitly cleared, not merely left alone: this refusal is one only a
                        // PERSON can settle, and inheriting a `createSkewRun` from an earlier pass
                        // would let the next app run POST the workshop a second time behind the
                        // portal — the duplicate this branch exists to prevent.
                        createSkewRun = null,
                    )
                }
                return true
            }
            // WRITTEN BACK BEFORE A SINGLE BYTE OR STAGE MOVES. From here on the record exists, and a
            // pass killed during the upload comes back to the upload — never to the create, which
            // would leave a second, orphaned workshop on the server that nothing would ever reconcile.
            draft = WorkshopDraftStore.updateBookkeeping(context, workshopId) {
                it.copy(
                    remoteId = createdId,
                    sync = it.sync.copy(
                        createFailure = null,
                        createFailedAt = null,
                        createSkewRun = null,
                        lastError = null,
                    ),
                )
            } ?: return true
            tally.created++
            bump()
        }
        val remoteId = remoteIdOf(draft) ?: return true

        // ── 2. The photographs, before the stages that reference them ────────────────────────────
        if (!uploadPending(context, repository, draft, remoteId, tally)) return false

        // Re-read: every confirmed id landed on disk during the step above, and the stage payloads
        // are built out of them. Building from the pre-upload copy would send the local UUIDs the
        // upload was there to replace.
        draft = WorkshopDraftStore.load(context, workshopId) ?: return true

        // ── 3. The stages ────────────────────────────────────────────────────────────────────────
        if (!pushStages(context, repository, draft, remoteId, schema, tally)) return false

        // Only now, and only when there is genuinely nothing left, is the workshop "safe". Recording
        // it optimistically at the top of the pass is how an app comes to say "backed up" about a
        // fortnight whose photographs never left the phone.
        val after = WorkshopDraftStore.load(context, workshopId) ?: return true
        if (statusOf(context, schema, after).isFullySynced) {
            noteSync(context, workshopId) {
                it.copy(lastSuccessAt = Instant.now().toString(), lastError = null)
            }
        }
        return true
    }

    // ── Media ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Upload every file the server has not acknowledged, one at a time, writing the id it answers
     * with straight into the draft.
     *
     * ONE AT A TIME rather than the three-abreast the record outbox uses, and that is a considered
     * difference. Those are an interview's dozen small clips on an office connection; these are a
     * fortnight of full-resolution photographs and loom video over whatever a village has, and three
     * parallel PUTs on that link make all three time out instead of making any of them finish. The
     * cost of going in series is latency the designer is not waiting on; the cost of the alternative
     * is a pass that never completes a single file.
     *
     * Returns false — stop the pass — only for a transient failure, with nothing marked.
     */
    private suspend fun uploadPending(
        context: Context,
        repository: WorkshopRepository,
        draft: WorkshopDraft,
        remoteId: String,
        tally: Tally,
    ): Boolean {
        val outstanding = draft.media.filter { !it.isConfirmedRemote && it.uploadFailure == null }
        if (outstanding.isEmpty()) return true

        outstanding.forEachIndexed { index, descriptor ->
            val file = WorkshopDraftStore.mediaFile(context, draft.workshopId, descriptor)
            // `isFile`, not `exists()`. A descriptor whose `relativePath` is somehow blank resolves
            // to the workshop DIRECTORY, which exists — and opening a directory as a stream throws
            // an IOException, which the triage below reads as "the network went away". That would
            // stop this pass, and every pass after it, at the same corrupt descriptor for ever, with
            // the app insisting the problem was the signal.
            if (!file.isFile) {
                // The descriptor outlived its bytes — a restore that did not bring the media
                // directory, or storage that failed underneath us. Recorded rather than retried for
                // ever, and recorded rather than SWEPT: the descriptor is the only surviving record
                // that the photograph existed, what it was captioned and which stage it answered,
                // and a designer who finds the file on a backup can still put it back.
                noteMediaFailure(
                    context, draft.workshopId, descriptor.id,
                    "the file is no longer in this workshop's media folder on this device, so there " +
                        "is nothing to upload. Nothing has been deleted by the app — if you have a " +
                        "backup of this phone, restore it before removing the attachment."
                )
                tally.refused++
                return@forEachIndexed
            }

            val uploaded = try {
                repository.uploadDesignWorkshopMedia(
                    context = context,
                    workshopRemoteId = remoteId,
                    file = file,
                    originalFilename = descriptor.originalFilename.ifBlank { file.name },
                    mimeType = descriptor.mimeType,
                    mediaType = descriptor.mediaType,
                    caption = descriptor.caption,
                    // The capture time this device recorded, not the moment the upload happened. A
                    // photograph taken on day two and uploaded on day fourteen is dated day two, or
                    // the report's chronology is the chronology of the bus journey home.
                    recordedAt = descriptor.capturedAt.takeIf { it.isNotBlank() },
                    latitude = descriptor.latitude,
                    longitude = descriptor.longitude,
                    workshopTitle = draft.title,
                    batchIndex = index + 1,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (repository.isConnectionFailure(e)) {
                    noteSync(context, draft.workshopId) {
                        it.copy(lastError = e.apiErrorMessage("The upload could not be completed."))
                    }
                    return false
                }
                noteMediaFailure(
                    context, draft.workshopId, descriptor.id,
                    // `.message` only: a media upload is multipart form-data rather than an
                    // `APIModel` body, so `extra_forbidden` cannot arise here and there is no skew to
                    // record. The bytes stay on the device either way — see [DraftMedia.uploadFailure].
                    e.refusal("the server refused this file.").message
                )
                tally.refused++
                return@forEachIndexed
            }

            val serverId = uploaded.id.takeIf { it.isNotBlank() }
            if (serverId == null) {
                // A 2xx that names nothing is not an acknowledgement. A captive portal answering the
                // request with its own page is the field case, and this app already knows they exist.
                // Treating it as success would record a reference to nothing AND — if the bytes were
                // ever released on the strength of it — throw the photograph away.
                noteMediaFailure(
                    context, draft.workshopId, descriptor.id,
                    "the server accepted the upload but did not say what it stored, so it cannot be " +
                        "confirmed. The file is still on this device. If you are on a wi-fi network " +
                        "that asks you to sign in, connect properly and try again."
                )
                tally.refused++
                return@forEachIndexed
            }

            // ─────────────────────────────────────────────────────────────────────────────────────
            // THE ONE PLACE A REMOTE MEDIA ID IS EVER WRITTEN, and it happens only here, only with
            // an id in hand, and BEFORE anything is allowed to consider these bytes duplicated. The
            // local file is not touched — not now and not by any later step of this pass. See
            // [WorkshopDraftStore.releaseUploadedMedia] for the only path that ever removes it, and
            // why it is a decision a person makes rather than something a sync does.
            //
            // NonCancellable, because a file that IS on the server has to be recorded even while
            // this pass is being torn down. The window between `/media/complete` returning and this
            // write landing is the only place a duplicate upload can still be born, and letting a
            // cancellation fall inside it would put the same 12-megapixel photograph on the server
            // twice — once per interrupted pass, on the connection that keeps being interrupted.
            // ─────────────────────────────────────────────────────────────────────────────────────
            withContext(NonCancellable) {
                WorkshopDraftStore.updateBookkeeping(context, draft.workshopId) { stored ->
                    stored.copy(
                        media = stored.media.map { item ->
                            if (item.id != descriptor.id) item else item.copy(
                                remoteMediaId = serverId,
                                uploadedAt = Instant.now().toString(),
                                uploadFailure = null,
                                uploadFailedAt = null,
                            )
                        }
                    )
                }
            }
            tally.media++
            bump()
        }
        return true
    }

    // ── Stages ───────────────────────────────────────────────────────────────────────────────────

    /** Push every stage whose payload differs from the one the server last acknowledged. */
    private suspend fun pushStages(
        context: Context,
        repository: WorkshopRepository,
        draft: WorkshopDraft,
        remoteId: String,
        schema: SchemaResponse,
        tally: Tally,
    ): Boolean {
        val mediaById = draft.media.associateBy { it.id }
        // Off disk, once per workshop rather than per stage. Never fetched here: a sync pass that
        // reached the network for a definition would be deciding what to send on the strength of a
        // request that may fail, and the honest input is what this device is holding.
        val definition = DwCustomSectionStore.load(context, draft.workshopId)
        for (spec in schema.stages.sortedBy { it.number }) {
            val record = draft.sync.stages[spec.key]
            // The server's final answer; waiting on a person — unless it is waiting on an update.
            if (record.blocksRetry()) continue

            val stored = draft.stages[spec.key]
            // THE SAME FUNCTION `statusOf` ASKS, and it is shared rather than copied because the two
            // used to be copies and the copies were wrong together: an empty stage holding a deletion
            // it was entitled to send was skipped here for ever, while `statusOf` — reading the
            // identical inlined test — reported nothing pending and the row said "Backed up to the
            // server". `workshopIsRemote = true` is not a guess: this function is only ever called
            // with the id the server issued. See [dwStageSaysNothing].
            if (dwStageSaysNothing(spec, stored, record, workshopIsRemote = true)) continue

            val built = buildStageBody(
                spec, stored, mediaById, isAuthoritative(stored, record),
                customHeld = dwCustomHeldFor(definition, spec.key),
            )
            if (built.unresolved.isNotEmpty()) {
                // HELD BACK, NOT TRIMMED. `save_stage` writes the cleaned entry WHOLESALE, so a
                // payload with the media key omitted does not merely fail to add the new photograph
                // — it DELETES whatever id the server already holds under that key. A stage waiting
                // on one upload is a delay; a stage that quietly erased last week's photograph is a
                // hole in the report that nobody finds until it has been submitted.
                val count = built.unresolved.size
                val note = "$count attached file${plural(count)} " +
                    (if (count == 1) "is" else "are") + " still only on this device, so this stage " +
                    "has not been sent yet. It sends itself as soon as they upload — nothing has " +
                    "been thrown away."
                // Written only when it CHANGES. A photograph the server has permanently refused
                // holds its stage back for good, and re-stamping the identical sentence on every
                // pass would rewrite the draft twice a minute for as long as that stayed true.
                if (record?.failure != note) {
                    noteStage(context, draft.workshopId, spec.key) {
                        it.copy(
                            failure = note,
                            failedAt = Instant.now().toString(),
                            permanent = false,
                            // What the clear below is allowed to sweep, and the only thing it is.
                            waitingOnFiles = true,
                            // This is a hold-up, not a refusal, and it REPLACES whatever was recorded
                            // before — a leftover run stamp would describe a refusal that is no
                            // longer what is standing in the way.
                            skewRun = null,
                            attempts = it.attempts + 1,
                        )
                    }
                }
                continue
            }

            val signature = signatureOf(built.body)
            if (signature == record?.signature) {
                /*
                  Already up there, byte for byte. Clear a stale note — but ONLY the one this clear
                  was written for: the files it was waiting on have since landed, so the hold-up is
                  over and the designer must not be shown a problem that no longer exists.

                  IT USED TO CLEAR ANY NOTE, AND THAT ERASED THE TWO IT MUST NOT. `recordStageSent`
                  deliberately keeps a non-permanent note for the keys the server did not recognise —
                  the only client/server registry-drift signal this repository has — and now also for
                  the answers the server refused. Both are written against a payload whose signature
                  MATCHES by construction, so this branch is reached on the very next pass and wiped
                  them: 45 seconds after a save, with nothing having changed, the status screen lost
                  the one line that said an answer had not landed. `waitingOnFiles` is what tells the
                  two apart, and it is set by exactly one writer.
                */
                if (record.failure != null && record.waitingOnFiles) {
                    noteStage(context, draft.workshopId, spec.key) {
                        it.copy(failure = null, failedAt = null, waitingOnFiles = false)
                    }
                }
                continue
            }

            val result = try {
                repository.saveDesignWorkshopStage(remoteId, spec.key, built.body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The stage the server ANSWERED about is refused on its own, and the other
                // twenty-one go up. `statusOf` prints this note under "Stage 7 (Prototyping): …",
                // which is the naming the old behaviour never did: a 5xx stopped the pass, wrote
                // "the connection dropped" against the whole device, and left the designer with no
                // way to know which of 22 stages had caused it.
                if (repository.isConnectionFailure(e)) {
                    noteSync(context, draft.workshopId) {
                        it.copy(lastError = e.apiErrorMessage("The stage could not be sent."))
                    }
                    return false
                }
                tally.refused++
                /*
                  A SCHEMA REFUSAL IS RECORDED AND SHOWN, BUT NOT HELD FOR EVER.

                  "the server refused this stage" is the right story for an answer the validator
                  rejected. It is the wrong one when the repository could not read the payload at all
                  — this build speaking a dialect that build does not know — because then nothing the
                  designer can reach is wrong and no edit will clear it. Marked permanent it would
                  survive the update that fixed it, which is exactly what the web saw: the API had
                  learned `merge` and the phone was still refusing to send. `skewRun` buys the next
                  app run one attempt, with nobody tapping anything. See [blocksRetry].
                */
                val refusal = e.refusal("the server refused this stage.")
                noteStage(context, draft.workshopId, spec.key) {
                    it.copy(
                        failure = if (refusal.schemaSkew) {
                            skewSentence("What this copy of the app sent for this stage", refusal.message)
                        } else {
                            refusal.message
                        },
                        failedAt = Instant.now().toString(),
                        permanent = true,
                        skewRun = if (refusal.schemaSkew) APP_RUN else null,
                        attempts = it.attempts + 1,
                    )
                }
                continue
            }

            recordStageSent(context, draft.workshopId, spec.key, signature, built, result)
            tally.stages++
            bump()
        }
        return true
    }

    /**
     * Push ONE stage on behalf of the screen the designer is typing into.
     *
     * The stage screen saves to the device first and then, opportunistically, to the server. It used
     * to build and send that PUT itself, which quietly meant two different ideas of what a stage
     * payload is: the screen's carried this device's own media UUIDs (which resolve to nothing on
     * the server) and had no notion of holding a stage back until its photographs had landed. There
     * is now one builder, and this is the door into it, so a stage sent from the screen and the same
     * stage sent by the background pass are byte-identical — which is also what lets the screen's
     * success be recorded as a signature the pass will honour instead of re-sending.
     *
     * Never throws: a failed sync must not fail the local save that already succeeded.
     */
    suspend fun pushStage(
        context: Context,
        repository: WorkshopRepository,
        workshopId: String,
        spec: StageDto,
    ): StagePush {
        val draft = WorkshopDraftStore.load(context, workshopId) ?: return StagePush.NothingToSend
        val remoteId = remoteIdOf(draft) ?: return StagePush.NoRemoteYet
        val stored = draft.stages[spec.key] ?: return StagePush.NothingToSend
        val built = buildStageBody(
            spec,
            stored,
            draft.media.associateBy { it.id },
            isAuthoritative(stored, draft.sync.stages[spec.key]),
            customHeld = dwCustomHeldFor(
                DwCustomSectionStore.load(context, workshopId), spec.key,
            ),
        )
        // Held back rather than trimmed, for the reason spelled out in [pushStages]: a payload with
        // the media key missing does not merely omit the new photograph, it deletes the one the
        // server already holds. The background pass records the hold-up and completes it.
        if (built.unresolved.isNotEmpty()) return StagePush.HeldBack(built.unresolved.size)

        val signature = signatureOf(built.body)
        if (signature == draft.sync.stages[spec.key]?.signature) return StagePush.AlreadySent

        val result = try {
            repository.saveDesignWorkshopStage(remoteId, spec.key, built.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Deliberately NOT recorded as a stage failure. This is a debounced auto-save firing
            // every few seconds while somebody types in a courtyard; marking the stage refused on
            // the first timeout would paint the screen red for the ordinary condition this whole app
            // is built around. The background pass is what triages, and it reads from the same disk.
            return StagePush.NotSent
        }
        recordStageSent(context, workshopId, spec.key, signature, built, result)
        bump()
        // The ENTRIES go back with the result, and that is not a convenience. `save_stage` keys a
        // collection row's errors by the entry's INDEX IN THE ARRAY THAT WAS SENT, so the only thing
        // that can decode them is the array itself. Re-deriving it on the screen would be a second
        // builder and a second ordering, and the first message after the first collection would land
        // on the wrong row — which is precisely the note `buildStageEntries` carries on the web.
        return StagePush.Sent(result, built.body.entries)
    }
}

/**
 * What became of one stage the screen asked to push.
 *
 * Spelled out rather than returned as a nullable result because the states are not interchangeable
 * on screen: "already on the server" and "there is no server record yet" both mean nothing was sent,
 * and telling a designer the first when the truth is the second is how a workshop that exists only
 * on one phone comes to be believed backed up.
 */
sealed interface StagePush {
    /** On the server, byte for byte, already. Nothing to do and nothing wrong. */
    data object AlreadySent : StagePush

    /**
     * It went up just now. [result] carries anything the server dropped OR REFUSED.
     *
     * [entries] is the payload that was accepted, in the order it was sent, because that order is the
     * only key to [StageSaveResultDto.errors] — see [dwDecodeStageRefusals].
     */
    data class Sent(
        val result: StageSaveResultDto,
        val entries: List<StageEntryBody> = emptyList(),
    ) : StagePush

    /** Waiting on [files] attachments that are still only on this device. */
    data class HeldBack(val files: Int) : StagePush

    /** The workshop itself has not been created on the server yet — see the list screen's action. */
    data object NoRemoteYet : StagePush

    /** Nothing stored for this stage. */
    data object NothingToSend : StagePush

    /** Tried and did not land. Not an error the screen should shout about; the pass will retry. */
    data object NotSent : StagePush
}

// --------------------------------------------------------------------------------------
// Building the wire payload
// --------------------------------------------------------------------------------------

/**
 * `encodeDefaults = true` because the signature is a digest of the payload's MEANING, and an omitted
 * default is still a value the server acts on: `replaceCollections` decides whether rows are swept,
 * and a body that differed only in that flag must not hash the same as one that did not carry it.
 */
private val signatureJson = Json { encodeDefaults = true }

/**
 * The id to send this workshop's data to, or null while it exists only on this device.
 *
 * TWO PLACES CARRY THAT ID AND BOTH ARE LEGITIMATE, which is the trap this function exists to close.
 * A workshop STARTED on the phone is filed under a `local-…` key and gets [WorkshopDraft.remoteId]
 * filled in when the create lands. A workshop that already existed on the server and was merely
 * opened here is filed under the SERVER's id, and its draft — first written by the stage screen's
 * auto-save, which knows nothing about creates — has `remoteId` null for ever.
 *
 * Reading only `remoteId` therefore misses the entire second case: every workshop set up on the web
 * and captured in the field would be judged "not created on the server yet", so the engine would try
 * to CREATE a duplicate of it, and the fortnight of stages captured against the original would never
 * be sent to it. The screens have always resolved it this way (see `syncId` in StageScreen); this is
 * the same rule, in the one place the sync reads it.
 */
private fun remoteIdOf(draft: WorkshopDraft): String? =
    draft.remoteId?.takeIf { it.isNotBlank() }
        ?: draft.workshopId.takeIf { it.isNotBlank() && !isLocalOnlyWorkshop(it) }

/** A stage's payload, plus the local media references that stopped it from being sendable. */
internal data class BuiltStage(val body: StageSaveBody, val unresolved: List<String>)

/**
 * One stage as the server expects it, with every local media id translated to the server's.
 *
 * `_clientKey` travels INSIDE `data`, which is where `save_stage` looks for it, and it is the row's
 * own durable id ([DraftRow.id] minus its entity prefix) rather than anything minted here. That is
 * the entire de-duplication scheme: with it, a stage re-sent for the fourth time UPDATES its four
 * sketch rows; without it, each send inserts four more and soft-deletes the previous four, so a
 * costing table grows by four lines every time the debounce fires.
 *
 * ── `replaceCollections` IS EARNED, NOT ASSUMED ───────────────────────────────────────────────────
 *
 * It was hard-coded `true` here, on the reasoning that "the phone owns the whole stage locally — the
 * draft IS the document". That is true of a draft the phone actually filled in and false of the one
 * the stage screen manufactures when its offline read fails: `runCatching{…}.getOrNull()` hands back
 * null in a courtyard, a blank `StageState()` was seeded with no message, and the first typed field
 * produced a payload carrying that field, ZERO rows for every collection, and a claim that those
 * were now exactly the rows. A stage holding a fortnight of process steps, tools and raw materials
 * was swept by a save that had never seen any of them.
 *
 * So authority is passed in by the caller, from [StageDraft.stageSeen] — the ONE way a phone can
 * honestly know what the server holds, which is to have read it. Everything else sends
 * `replaceCollections = false`, which merges: the designer's work still lands, in full, and nothing
 * they have not seen is destroyed.
 *
 * `emptiedEntities` is the other half. With the sweep scoped to what the payload names, a collection
 * whose last row was deleted is invisible — it contributes no entries — so the deletion has to be
 * stated. It is stated from what the DESIGNER did (see [StageDraft.emptiedEntities]), never from what
 * the draft happens to be missing.
 */
internal fun buildStageBody(
    spec: StageDto,
    stored: StageDraft?,
    mediaById: Map<String, DraftMedia>,
    /**
     * Whether this draft is entitled to say "these are now exactly the rows" — it holds the server's
     * copy of this stage, or the server's copy came from it.
     */
    authoritative: Boolean,
    /**
     * Whether this device holds this workshop's custom definition, and whether that definition asks
     * anything at all on this stage. See the `_custom` arm below — it is the whole of the safety.
     */
    customHeld: Boolean = false,
): BuiltStage {
    val unresolved = LinkedHashSet<String>()
    val entries = ArrayList<StageEntryBody>()

    spec.singleton?.let { entity ->
        entries += StageEntryBody(
            entityKey = entity.key,
            data = JsonObject(wireData(entity, stored?.values.orEmpty(), mediaById, unresolved)),
            // A DRAFT THAT HAS NEVER SEEN THE SERVER'S COPY SENDS A MERGE, NOT A REPLACEMENT.
            //
            // `authoritative` already decides whether this draft may say "these are now exactly
            // the rows", and it spent that authority only on `replaceCollections`/`emptiedEntities`
            // — which the server applies to COLLECTIONS alone. The singleton went up as a plain
            // replace either way, so a stage whose download had failed came up blank, the designer
            // typed the one thing they came to record, and the office's seven fields were deleted
            // by the sync on the drive home. The banner on `StageScreen` promised the opposite.
            merge = !authoritative,
        )
    }
    spec.collections.forEach { entity ->
        stored?.rowsFor(entity.key).orEmpty().forEachIndexed { index, row ->
            entries += StageEntryBody(
                entityKey = entity.key,
                // The server's own row id where this row has already been stored under one. It is
                // matched before the client key, so a row whose key was somehow lost still updates
                // in place rather than arriving as a new one.
                entryId = (row.values["_entryId"] as? JsonPrimitive)?.content,
                ordinal = index,
                data = JsonObject(
                    wireData(entity, row.values, mediaById, unresolved) +
                        ("_clientKey" to JsonPrimitive(row.id.substringAfter(DW_ROW_KEY_SEPARATOR)))
                ),
                // A ROW IS NOT SAFER THAN A SINGLETON, AND THIS ARM WAS THE ONE THAT WENT ON
                // REPLACING WHOLESALE.
                //
                // `authoritative` was spent on `replaceCollections`/`emptiedEntities` — WHICH ROWS
                // SURVIVE — and on the singleton's own `merge`. Nothing ever asked it about the
                // CONTENTS of a row that does survive, so every collection entry left here with
                // `merge` at its default of false, which is a claim to be sending every key there
                // IS. `save_stage` then writes the row's `data` wholesale (design_workshops.py, the
                // `updates.append` arm), records no `RecordRevision`, and answers `saved=1
                // errors={} removed=0`.
                //
                // The walk needs no failed download and no sweep. The phone CREATES the row in a
                // courtyard — `{stepNumber, name}`, `_clientKey` minted here — and syncs it. The
                // office opens the same row on the web and fills in `localName`, `description`,
                // `timeTaken`, `performedBy`, `problems`. The designer corrects the step's name on
                // the phone, which has still never read this stage, and the next save deletes all
                // five. Reproduced on the running API against a live Postgres row: before
                // `{"name": "Warping", "problems": …, "localName": "Tana", "timeTaken": 6.5,
                // "stepNumber": 1, "description": …, "performedBy": …}`, after
                // `{"name": "Warping (revised)", "stepNumber": 1}`.
                //
                // The same three words as the singleton one loop up, for the same reason and with
                // the same cost: a designer who clears one CELL of a row on a stage this device has
                // never read does not clear it on the server until the stage has been read once.
                // That cost is not new and is not silent — it is exactly what `StageScreen`'s own
                // download note already promises ("clearing an answer or deleting a row here does
                // NOT clear or delete it on the server"), a promise this arm was the reason the app
                // could not keep. A row this device HAS read still replaces, because for it an
                // absent key is a real deletion.
                merge = !authoritative,
            )
        }
    }
    /*
      THE DESIGNER'S OWN ANSWERS, IN A RESERVED ENTRY OF THEIR OWN, AFTER THE REGISTRY'S ENTITIES.

      `_custom` is not a registry entity and never will be — it is a `DwStageEntry` row of its own,
      one per (workshop, stage) — so it cannot come out of either loop above, which walk `spec`. That
      is also what makes it unreachable by the collection sweep: `emptiedEntities` is intersected with
      `spec.collections` three lines down AND again with the registry's own collection keys
      server-side, and `_custom` is in neither list.

      `wireData` IS DELIBERATELY NOT USED. It keys media and rich-text translation off the ROW'S
      REGISTRY ENTITY's fields, and there is no entity here; v1 declares no media type at all
      (`custom_sections.V1_FIELD_TYPES`), so there is nothing to translate and calling it would only
      invent an entity to look the fields up in. The `_`-prefixed strip is done by hand, for
      `wireData`'s own reason: those keys are the protocol's, the server reports any key it does not
      know in `droppedCustomKeys`, and passing them through would put a line in every response for
      something working exactly as designed.

      ── THE OMISSION RULE, WHICH IS THE WHOLE OF THE SAFETY ────────────────────────────────────────

      `plan_custom_write` treats "no entry at all" and "an entry carrying `{}`" as two DIFFERENT
      instructions: `sent=None` writes NO ROW, and `{}` is a designer clearing every answer and IS
      written. So a phone holding nothing must send nothing. A `{ "_custom": {} }` from a handset that
      simply never fetched the definition would read on the server as "the designer cleared every
      custom answer" — and since a stage save replaces the row wholesale and writes no
      `RecordRevision`, the office's answers would be gone in place, with the save reporting success.

      Two clauses, and they are not the same clause:

        * ANSWERED — anything in the bucket that `isFilled` — always goes, whether or not this device
          holds the definition. The designer typed it; it must reach the server, and the server
          validates it against ITS definition either way (an unrecognised key comes back in
          `droppedCustomKeys` and nothing is destroyed). This is deliberately wider than "the
          definition is held": a cache deleted by a decode failure must not strand a fortnight of
          answers on the handset.
        * EMPTY goes only when this device has READ THE SERVER'S OWN CONTAINER for this stage
          ([StageDraft.customSeen]) AND is authoritative for the stage AND holds a definition that
          asks something here. That is the "designer cleared the last answer" case, and it is the only
          case in which an empty container is an instruction rather than an absence of one. Gating it
          on the definition asking something on THIS stage is also what keeps the one-off re-push
          bounded: without it, the release carrying this code would write an empty `_custom` row for
          all 22 stages of every workshop that has a definition, including the twenty-one it says
          nothing about.

      ── AUTHORITY OVER THE STAGE IS NOT AUTHORITY OVER THIS ROW, AND CONFLATING THEM DESTROYED DATA ─

      `customSeen` is the third clause and it is the one that was missing. When this was written
      [isAuthoritative] was satisfied by `serverBaseline` OR by a signature — and `recordStageSent`
      stamped BOTH after any successful save, including a merge save from a stage seeded blank because
      its download failed. So a phone reached `authoritative = true` having never read this stage at
      all, and — because the custom answers live in a row of their own that a payload carrying no
      `_custom` entry does not touch — having never seen a single one of the designer's own answers.
      Holding the DEFINITION says only which questions exist; it says nothing about which are answered.
      Executed against the server's own planner:
      `plan_custom_write([loomsWorking], sent={}, previous={'loomsWorking': 12}, merge=False)` returns
      `data={}` — the office's 12 deleted, in place, with no `RecordRevision` and a successful save.
      The walk that got there: office answers on the web, designer opens the stage in a courtyard
      where the download fails, types one core field, the save lands on the drive home and stamps the
      baseline, and the very next PASS — no further typing needed, because a merge body and a replace
      body have different signatures — sends the clearance.

      BOTH OF THOSE DOORS ARE NOW SHUT ONE LEVEL UP: authority is [StageDraft.stageSeen] alone, earned
      by a read and never by a write. This clause is kept regardless, and not folded into it, because
      it answers a DIFFERENT question about a DIFFERENT row: `stageSeen` says the stage was read, and
      for every read this build makes that is the same request that returns `StageBucketDto.custom`,
      so the two move together today. They are not the same fact, they were not always set by the same
      code, and a draft written by a build between the two carries one without the other — so the row
      that can be destroyed by omission asks about itself.

      `merge = !customAuthority`, and it is the SAME argument as the singleton arm's one door along:
      a draft that has never seen the server's copy of this container sends "these are the keys I
      HAVE", `plan_custom_write` shallow-merges them over `previous` (`custom_sections.py:1011-1012`),
      and the seven answers the office typed on the web survive the one answer this phone recorded in
      a courtyard. A REPLACE is claimed only where the phone has actually read the row, which is the
      only evidence a client can have about a row it did not write. `merge` is omitted from the wire
      when false because `ApiClient.retrofit` leaves `encodeDefaults` off — see [StageEntryBody.merge],
      which is also why a server predating the field does not 422 every save.
    */
    val custom = stored?.custom.orEmpty().filterKeys { !it.startsWith("_") }
    val customAnswered = custom.values.any { DwValues.isFilled(it) }
    val customAuthority = authoritative && stored?.customSeen == true
    if (customAnswered || (customAuthority && customHeld)) {
        entries += StageEntryBody(
            entityKey = CUSTOM_ENTITY_KEY,
            data = JsonObject(custom),
            merge = !customAuthority,
        )
    }

    // Intersected with the entities this stage actually declares, so a key left behind by a registry
    // that has since moved on cannot be sent as a deletion instruction for something else.
    val collectionKeys = spec.collections.map { it.key }.toSet()
    val emptied = stored?.emptiedEntities.orEmpty().filter { it in collectionKeys }.distinct()

    return BuiltStage(
        StageSaveBody(
            entries = entries,
            replaceCollections = authoritative,
            // Named only alongside a claim of authority. `emptiedEntities` is read by the server only
            // when `replaceCollections` is true, and sending it otherwise would be asserting a
            // deletion while disclaiming the knowledge that justifies one.
            emptiedEntities = if (authoritative) emptied else emptyList(),
            submit = false,
        ),
        unresolved.toList(),
    )
}

/**
 * Whether this device holds a DEFINITION that asks anything at all on this stage.
 *
 * True only when this device has actually read the definition ([DwCustomCopy.UNKNOWN] is refused) AND
 * that definition asks something on this stage. Both halves are load-bearing and they fail
 * differently: without the first, a phone that never fetched anything would tell the server "the
 * designer cleared every custom answer" on the first save of any stage; without the second, the
 * release carrying this code would write an empty `_custom` row for all 22 stages of every workshop
 * that has a definition, re-pushing twenty-one stages that have nothing to say.
 *
 * **IT IS TWO OF THE THREE GATES ON AN EMPTY CONTAINER AND NEVER ALL OF THEM.** Holding the
 * definition says which questions EXIST; it says nothing whatever about which are ANSWERED, and the
 * answers live in a row this device may never have read. The third gate is [StageDraft.customSeen],
 * asked in [buildStageBody] beside this one — see the argument written out there, and the walk that
 * deleted the office's answers without it.
 *
 * It asks [customFieldsForStage] and therefore counts RETIRED fields as "asks something", which is
 * correct: a retired field is retired precisely because it has an answer, so a stage carrying one is
 * a stage whose container the server is still holding.
 */
internal fun dwCustomHeldFor(definition: DwCustomCache?, stageKey: String): Boolean =
    definition.isHeld && customFieldsForStage(definition, stageKey).isNotEmpty()

/**
 * Whether the draft may claim `replaceCollections`, and send its singleton as a replacement.
 *
 * ONE WAY A PHONE CAN HONESTLY KNOW WHAT THE SERVER HOLDS, AND NO SECOND: it has READ it.
 * [StageDraft.stageSeen] is that read and nothing else sets it — not a save, not a successful sync,
 * not a signature. A stage that has not been read still syncs in full; every value the designer typed
 * is sent. It simply does not ALSO assert that everything it lacks should be destroyed.
 *
 * ── THE SECOND CLAUSE THIS FUNCTION USED TO HAVE, AND WHY DELETING IT IS NOT A NARROWING ─────────
 *
 * It also granted authority when "the server has already acknowledged a payload built from this very
 * draft, recorded as a signature. From that moment the server's copy of the stage IS this draft's."
 * That sentence is true of a payload that claimed to be the WHOLE truth of the stage — and a payload
 * can only make that claim if the draft was ALREADY authoritative when it was built. So the clause
 * could only ever add authority in the one case where the payload was a MERGE, which is exactly the
 * case in which its premise is false: after a merge the server holds `previous ∪ sent`, a SUPERSET of
 * this draft, and `previous` is the part this device has never seen.
 *
 * The clause was therefore redundant where it was right and wrong where it did anything, which is why
 * it is gone rather than narrowed. Together with `recordStageSent` no longer stamping the draft, that
 * closes the walk this defect was found by: office types a core field on the web → designer opens the
 * stage in a courtyard → the download fails → a blank stage is seeded → one field is typed → the save
 * lands on the drive home with `merge = true`, correctly, AND STAMPED THE BASELINE → from that moment
 * the stage was "authoritative", so the very next payload — with no further typing at all, on the next
 * background pass, because the signature no longer matched — went up as a full replacement holding
 * only that one field, and `save_stage` replaced the singleton's `data` wholesale with no
 * `RecordRevision` behind it.
 *
 * THE SIGNATURE STILL DOES ITS OWN JOB, which is not this one. It answers "have these exact bytes
 * already been accepted", so a merge-acknowledged stage is not re-sent on every pass; it just no
 * longer doubles as evidence about what the repository contains. A merge-acknowledged save earns
 * exactly that and nothing more.
 *
 * `record` is still taken so every caller keeps passing the pair and a future reader sees at the call
 * site that the signature was considered and rejected — it is deliberately unread. See the KDoc on
 * [StageDraft.stageSeen] for what this costs a draft written before the rename, and `dwFoldServerStage`
 * for how one online open of the stage pays it back.
 */
@Suppress("UNUSED_PARAMETER")
internal fun isAuthoritative(stored: StageDraft?, record: StageSyncRecord?): Boolean =
    stored?.stageSeen == true

/**
 * One record's values as they go on the wire: protocol keys stripped, media references translated.
 *
 * Reads [EntityDto.fields] rather than `liveFields` on purpose. A DEPRECATED media field still holds
 * a photograph the designer attached before the registry moved on, and skipping it would send that
 * field's local UUID untranslated — a reference to nothing, in the one place nobody is looking.
 *
 * ── A RICH_TEXT FIELD CARRIES MEDIA TOO, AND NOT IN ITS OWN VALUE ─────────────────────────────
 *
 * A photograph placed INSIDE a narrative puts its id in an IMAGE block, several levels down in the
 * document JSON, under a key whose field type is RICH_TEXT and not IMAGE. Translating only the
 * media-typed keys sent that document up holding this device's own UUID — a reference the server
 * cannot resolve, in a field nothing validates. Nothing would have failed: the stage would save, the
 * status would read clean, and the .docx the ministry is handed would come out of
 * `to_report_blocks` with the photograph silently dropped, because `resolve_media` answers None for
 * an id no MediaFile row has. The designer would find out from the officer.
 *
 * The server has looked inside these documents since the feature was written
 * (`design_workshops._media_ids` walks the RICH_TEXT fields through `rich_text.media_ids`); it was
 * the phone that only ever looked at the top level.
 */
private fun wireData(
    entity: EntityDto,
    values: Map<String, JsonElement>,
    mediaById: Map<String, DraftMedia>,
    unresolved: MutableSet<String>,
): Map<String, JsonElement> {
    val mediaKeys = entity.fields
        .filter { DwFieldType.of(it.type).isMedia }
        .map { it.key }
        .toSet()
    val richKeys = entity.fields
        .filter { DwFieldType.of(it.type) == DwFieldType.RICH_TEXT }
        .map { it.key }
        .toSet()
    val out = LinkedHashMap<String, JsonElement>()
    values.forEach { (key, value) ->
        // `_entryId`, `_ordinal`, `_clientKey` are the protocol's, not the designer's. The server
        // drops any unknown key and reports it in `droppedKeys`, so passing these through would put
        // a line in every response for something working exactly as designed — and train whoever
        // reads that list to ignore it, which is the one thing it must not become.
        if (key.startsWith("_")) return@forEach
        if (key in richKeys) {
            out[key] = swapInlineMediaRefs(value, mediaById, unresolved)
            return@forEach
        }
        if (key !in mediaKeys) {
            out[key] = value
            return@forEach
        }
        swapMediaRefs(value, mediaById, unresolved)?.let { out[key] = it }
    }
    return out
}

/**
 * A narrative's inline photographs translated from this device's ids to the server's.
 *
 * ── THE PROSE IS NEVER TRIMMED, WHICH IS WHERE THIS DIFFERS FROM [swapMediaRefs] ──────────────
 *
 * An unresolved reference in a media FIELD drops the value: the field holds nothing but that
 * reference, so there is nothing else to lose, and the stage is held back until the upload lands. A
 * narrative is not like that. Dropping it would throw away a page of a designer's writing to avoid
 * sending one id, so the document goes through UNCHANGED and the id is recorded as unresolved —
 * which holds the whole stage back (see `pushStages`) until the photograph has uploaded and the
 * translation can be made properly. Nothing is sent wrong and nothing is thrown away; the stage is
 * simply late, by exactly as long as one upload.
 *
 * An id that is not one of this device's descriptors is left alone for the reason [swapMediaRefs]
 * gives: it is a server id already, from a stage seeded from the server or a workshop being edited
 * on a second device, and treating it as unresolvable would hold that stage back for ever waiting on
 * an upload that has already happened.
 *
 * Returns the value UNCHANGED — the same instance — when no id moves, so a narrative with no
 * photograph in it cannot change the payload's signature and re-upload a stage nobody touched.
 */
private fun swapInlineMediaRefs(
    value: JsonElement,
    mediaById: Map<String, DraftMedia>,
    unresolved: MutableSet<String>,
): JsonElement = remapInlineMedia(value) { token ->
    val descriptor = mediaById[token]
    when {
        descriptor == null -> null
        descriptor.isConfirmedRemote -> descriptor.remoteMediaId
        else -> {
            unresolved += token
            null
        }
    }
} ?: value

/**
 * Translate a media field's value from this device's ids to the server's.
 *
 * A token that is not one of this device's descriptors is passed through UNCHANGED, and that is
 * deliberate rather than defensive: it is a server id already — a stage this handset seeded from the
 * server because it had no local copy, or a workshop being edited on a second device. Treating it as
 * unresolvable would hold that stage back for ever waiting on an upload that has already happened.
 */
private fun swapMediaRefs(
    value: JsonElement?,
    mediaById: Map<String, DraftMedia>,
    unresolved: MutableSet<String>,
): JsonElement? = when {
    value == null || value is kotlinx.serialization.json.JsonNull -> null
    value is JsonArray -> value
        .mapNotNull { swapMediaRefs(it, mediaById, unresolved) }
        .takeIf { it.isNotEmpty() }
        ?.let { JsonArray(it) }
    value is JsonPrimitive -> {
        val token = value.content
        val descriptor = if (token.isBlank()) null else mediaById[token]
        when {
            token.isBlank() -> null
            descriptor == null -> value
            descriptor.isConfirmedRemote -> JsonPrimitive(descriptor.remoteMediaId)
            else -> {
                unresolved += token
                null
            }
        }
    }
    else -> value
}

/**
 * A digest of the exact bytes the server was sent.
 *
 * SHA-256 of the encoded body rather than a cheaper hash, because a collision here does not mean a
 * wasted request — it means a stage that CHANGED is judged already sent, and a designer's corrections
 * sit on the phone for ever while the UI says everything is safe. That is worth a few microseconds.
 *
 * `internal` RATHER THAN PRIVATE SO A TEST CAN REPRODUCE THE CONDITION THE DEFECTS NEED. "The recorded
 * signature already matches, so nothing is pending and nothing will ever be re-sent" is the premise
 * both [StageSyncRecord.refusedFields] and [WorkshopSyncStatus.unsentDeletions] exist for, and a test
 * that cannot construct a matching signature cannot set that premise up — it would assert the
 * arithmetic while the stage sat in the `pending` arm, which is not where either defect lives. Same
 * visibility, and the same reason, as [buildStageBody] and [isAuthoritative] beside it.
 */
internal fun signatureOf(body: StageSaveBody): String {
    val encoded = signatureJson.encodeToString(StageSaveBody.serializer(), body)
    return MessageDigest.getInstance("SHA-256")
        .digest(encoded.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

// --------------------------------------------------------------------------------------
// Writing progress back to the draft
// --------------------------------------------------------------------------------------
//
// Every one of these goes through [WorkshopDraftStore.updateBookkeeping] rather than `update`, so a
// sync never stamps `updatedAt`. See the KDoc there: a background pass that touched the stamp would
// reshuffle the workshop list under the designer's thumb and mark every generated report stale
// without a single field having changed.

private suspend fun noteSync(
    context: Context,
    workshopId: String,
    transform: (DraftSyncState) -> DraftSyncState,
) {
    WorkshopDraftStore.updateBookkeeping(context, workshopId) { it.copy(sync = transform(it.sync)) }
}

private suspend fun noteStage(
    context: Context,
    workshopId: String,
    stageKey: String,
    transform: (StageSyncRecord) -> StageSyncRecord,
) {
    noteSync(context, workshopId) { state ->
        val current = state.stages[stageKey] ?: StageSyncRecord()
        state.copy(stages = state.stages + (stageKey to transform(current)))
    }
}

/** Record a file the server refused. The bytes are not touched — see [DraftMedia.uploadFailure]. */
private suspend fun noteMediaFailure(
    context: Context,
    workshopId: String,
    mediaId: String,
    reason: String,
) {
    WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
        draft.copy(
            media = draft.media.map { item ->
                if (item.id != mediaId) item
                else item.copy(uploadFailure = reason, uploadFailedAt = Instant.now().toString())
            }
        )
    }
}

/**
 * Record that a stage landed, under the signature of what was actually sent.
 *
 * `droppedKeys` is kept as a NON-permanent note rather than swallowed. It is the only way a phone
 * learns it is running a newer field registry than the server: the stage saved, but some of what the
 * designer typed did not, and a field that silently vanishes on every sync is a data loss nobody
 * notices until the report is short a column.
 */
private suspend fun recordStageSent(
    context: Context,
    workshopId: String,
    stageKey: String,
    signature: String,
    /** The payload that was accepted. Its own bytes decide what this acknowledgement may claim. */
    built: BuiltStage,
    result: StageSaveResultDto,
) {
    val dropped = result.droppedKeys.takeIf { it.isNotEmpty() }
    /*
      CUSTOM DRIFT GETS ITS OWN SENTENCE AND IS NEVER FOLDED INTO THE ONE ABOVE — plan §4.

      They are different facts with different remedies. `droppedKeys` means this build's FIELD
      REGISTRY is ahead of the server's, which nobody in a cluster can do anything about and which is
      worth reporting once. `droppedCustomKeys` means the DESIGNER edited their own sections on the
      web and this phone is still holding the previous definition — which they can fix themselves, in
      one tap, by opening the workshop with a bar of signal. Printing the registry sentence for that
      would send them to report a bug that does not exist, and doing it on every save of every
      workshop that has a custom section is how the one signal that matters gets ignored.

      Both can be true at once, so both are said, joined rather than one winning.
    */
    val droppedCustom = result.droppedCustomKeys.takeIf { it.isNotEmpty() }
    val notes = listOfNotNull(
        dropped?.let { keys ->
            "the server did not recognise ${keys.size} field${plural(keys.size)} and did not store " +
                "them (${keys.joinToString(", ").take(160)}). This phone is running a newer field " +
                "registry than the server."
        },
        droppedCustom?.let { keys ->
            "this workshop's own sections no longer ask ${keys.size} " +
                "question${plural(keys.size)} this phone still holds an answer for, so " +
                (if (keys.size == 1) "it was" else "they were") +
                " not stored (${keys.joinToString(", ").take(160)}). The sections have been edited " +
                "since this phone last read them — open this workshop once with a connection."
        },
    ).takeIf { it.isNotEmpty() }?.joinToString(" ")

    /*
      A 200 THAT REFUSED SOME OF THE ANSWERS IS NOT A CLEAN SAVE, AND WAS BEING RECORDED AS ONE.

      `StageSaveResultDto.errors` was decoded off every save this app has ever made and read by
      nothing. `save_stage` writes every other field on the entry and puts the PREVIOUSLY STORED value
      back under a refused key, so the repository is left holding the old answer while this phone goes
      on showing the typed one — and because the payload's signature matches, nothing will ever send
      it again. The stage then scored `isFullySynced` and the list row said "Backed up to the server".

      Counted here, so the status is honest, and turned into sentences a designer can act on by
      [dwDecodeStageRefusals] on the screen that has the payload to decode them against. The count is
      what this file needs; the addressing is what the form needs, and neither is a copy of the other.
    */
    val refused = result.errors.values.sumOf { entry ->
        (entry as? JsonObject)?.size ?: 1
    }
    val refusalNote = refused.takeIf { it > 0 }?.let { count ->
        "the repository refused $count of the answers in this stage and kept what it already held " +
            "for ${if (count == 1) "it" else "them"}. Everything else on the stage was saved, and " +
            "nothing you typed has been thrown away — open the stage to see which answers, and what " +
            "the repository holds."
    }
    /*
      THE SWEEP TRIPWIRE. `StageSaveResultDto.removed` was decoded off every stage save this app has
      ever made and read by NOTHING — the same silence that let `errors` hide a refused answer, one
      field along, and the same remedy.

      THAT SILENCE IS HOW THE `replaceCollections` BLOCKER SURVIVED. The API answered `removed: 3` to
      a payload that had asked for no sweep at all — three rows this phone had never downloaded,
      soft-deleted on a stage it had correctly judged it had no authority over — and no surface on the
      phone repeated the number. The defect was found by reading the wire, not by using the app.

      ASKED ONLY OF A PAYLOAD THAT DISCLAIMED THE SWEEP, which is what makes it a tripwire rather than
      noise. When `replaceCollections` is true this device has read the stage and a deletion is what it
      MEANT; the count is then expected and unpredictable from here, because the phone cannot know how
      many rows the server holds. When it is false the payload made no claim about which rows survive,
      so a non-zero `removed` is the server deleting something nobody on this device asked it to — and
      there is no legitimate reason for that number to be anything but zero.

      It reports rather than repairs, deliberately: the rows are already gone by the time this runs,
      and inventing a recovery from a count with no keys in it would be a guess written into a
      repository. What it buys is that the next occurrence is visible on the handset the day it
      happens, instead of surviving another build.
    */
    val sweptNote = result.removed
        .takeIf { it > 0 && !built.body.replaceCollections }
        ?.let { count ->
            "the server deleted $count row${plural(count)} from this stage that this device did not " +
                "ask it to delete. This save claimed no authority over which rows exist, so it should " +
                "have deleted none. Nothing you typed was lost, but rows entered elsewhere may have " +
                "been — open this workshop with a connection and check this stage against the server."
        }

    val allNotes = listOfNotNull(notes, refusalNote, sweptNote)
        .takeIf { it.isNotEmpty() }?.joinToString(" ")

    noteStage(context, workshopId, stageKey) {
        it.copy(
            signature = signature,
            syncedAt = Instant.now().toString(),
            failure = allNotes,
            failedAt = if (allNotes == null) null else Instant.now().toString(),
            permanent = false,
            // The stage is on the server, so whatever skew once refused it is over. Cleared rather
            // than left standing: a record that still names an app run reads as unfinished business.
            skewRun = null,
            refusedFields = refused,
            /*
              AND THE ADDRESSING, SO THE STAGE THE NOTE ABOVE SENDS THE DESIGNER TO CAN ANSWER THEM.

              The sentence in `refusalNote` ends "open the stage to see which answers, and what the
              repository holds" — and until this field existed, opening the stage showed nothing at all,
              because the card lived in composition state that the act of leaving destroyed. Written
              here, from the payload that was actually accepted, because this is the one place that
              holds both halves at once: the response's error map and the body whose entry ORDER those
              error keys index into.

              NULL WHEN THERE IS NOTHING TO SHOW, and that is what makes a correction clear the card:
              the save carrying the corrected answer comes back with an empty error map and writes null
              over whatever was here. Exactly like [refusedFields] going to 0 beside it — one event,
              both facts, so a red mark on a box can never outlive the count that justifies it.

              `droppedCustomKeys` rides along because the card makes a claim about it: its heading said
              "Everything else in this stage was saved" while this same response reported answers that
              were not stored either. See [DwStageRefusalReport.droppedCustomKeys].
            */
            refusal = DwStageRefusalRecord(
                errors = result.errors,
                sent = built.body.entries.map(::dwSentEntryOf),
                at = Instant.now().toString(),
                droppedCustomKeys = result.droppedCustomKeys,
            ).takeIf { !it.isEmpty },
            // These notes are the SERVER's, not "files are still on this device", so the stale-note
            // clear in `pushStages` must not sweep them away on the next pass. See [waitingOnFiles].
            waitingOnFiles = false,
        )
    }

    /*
      WHAT AN ACKNOWLEDGEMENT ACTUALLY EARNS, WHICH IS LESS THAN THIS USED TO CLAIM.

      It used to do two things unconditionally: clear `emptiedEntities`, and set `serverBaseline = true`
      with the comment "the draft and the server agree, so the draft is a baseline from here on even
      if it was seeded blank after a failed download. It has earned the authority it was refused."

      THE SECOND SENTENCE IS FALSE FOR A MERGE SAVE and was the defect. `merge = true` asks the server
      to KEEP the keys this payload does not carry, so what the repository holds afterwards is
      `previous ∪ sent` — a SUPERSET of this draft, whose `previous` half this device has never seen.
      Stamping there told the next save "you have read the server's copy", and the next save then sent
      the singleton as a full replacement holding only the fields this handset happened to have. It
      needed no further typing: the signature of a merge body differs from the signature of a replace
      body, so the very next background pass rebuilt the stage as a replacement and sent it.

      So authority is not written here at all any more — [StageDraft.stageSeen] is earned by a read and
      by nothing else, and the walk above now ends with the second save merging exactly like the first.

      THE FIRST — clearing the deletions — IS KEPT BUT SCOPED TO WHAT THE PAYLOAD ACTUALLY CARRIED.
      `buildStageBody` sends `emptiedEntities` only when the draft is authoritative, so an
      unauthoritative save carried NONE of them; clearing them anyway discarded the one record that a
      designer had deleted the last row of a collection, and the rows stayed alive on the server for
      ever with nothing on any screen saying so. That was survivable while every save promoted the
      draft to a baseline one line below (the next save would carry them); with that promotion gone it
      would be a permanent, silent loss, so the two changes have to land together. Only the keys this
      body named are cleared, and anything the designer emptied since is kept for the next save — which
      is the rule `unsentAfterPush` states for the same field on the web.

      Bookkeeping, not an edit: no value the designer typed is touched.
    */
    val acknowledgedDeletions = built.body.emptiedEntities
    /*
      AND THE INDIVIDUAL ROWS, CLEARED ON THE SAME EVIDENCE AND SCOPED THE SAME WAY — see
      [StageDraft.deletedRowKeys].

      WHAT DISCHARGES A ROW DELETION IS THE SWEEP, so this asks the payload's own two facts rather
      than the response's. `replaceCollections` is the claim that the named entities are now exactly
      these rows; when the server accepted a body carrying it, every row of those entities that the
      payload did NOT name has been deleted — which is precisely what a partial deletion is. The
      entities covered are the ones the payload NAMES, because the sweep is scoped to them.

      GATED ON `replaceCollections`, WITHOUT WHICH NOTHING WAS SWEPT AT ALL. An unauthoritative save
      merges: it carries no claim about which rows exist, so the deleted row is still alive on the
      server and the key is still owed. Clearing it here would be the same permanent, silent loss
      that clearing `emptiedEntities` unconditionally used to be, one level down — the record would be
      gone, `statusOf` would stop counting it, and the row would print in the report for ever.
    */
    val sweptEntities: Set<String> =
        if (built.body.replaceCollections) {
            built.body.entries.mapTo(HashSet()) { it.entityKey } + acknowledgedDeletions
        } else {
            emptySet()
        }
    if (acknowledgedDeletions.isEmpty() && sweptEntities.isEmpty()) return
    WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
        val stage = draft.stages[stageKey] ?: return@updateBookkeeping draft
        val remaining = stage.emptiedEntities.filterNot { it in acknowledgedDeletions }
        val remainingRows = stage.deletedRowKeys.filterNot { key ->
            key.substringBefore(DW_ROW_KEY_SEPARATOR, "") in sweptEntities
        }
        if (
            remaining.size == stage.emptiedEntities.size &&
            remainingRows.size == stage.deletedRowKeys.size
        ) {
            return@updateBookkeeping draft
        }
        draft.copy(
            stages = draft.stages + (
                stageKey to stage.copy(
                    emptiedEntities = remaining,
                    deletedRowKeys = remainingRows,
                )
            )
        )
    }
}
