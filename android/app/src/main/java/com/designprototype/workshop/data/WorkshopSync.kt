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
    val attempts: Int = 0,
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
) {
    /** Nothing outstanding at all: the record exists remotely and every stage and file has landed. */
    val isFullySynced: Boolean
        get() = remoteId != null && pendingStages == 0 && pendingMedia == 0 &&
            failedStages == 0 && failedMedia == 0

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
            else -> buildList {
                if (remoteId == null) add("not created on the server")
                if (pendingStages > 0) add("$pendingStages stage${plural(pendingStages)}")
                if (pendingMedia > 0) add("$pendingMedia file${plural(pendingMedia)}")
            }.joinToString(", ").replaceFirstChar { it.uppercase() } + " waiting to upload"
        }
}

private fun plural(count: Int): String = if (count == 1) "" else "s"

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
 * What to write on an item the server REFUSED, including the one fact the designer cannot infer.
 *
 * A bare 500 renders through [apiErrorMessage] as "HTTP 500 Internal Server Error", which reads like
 * anything at all — including like a bad connection, which is the belief this whole split exists to
 * end. So an answered 5xx says out loud that waiting for signal will not help. A 4xx already carries
 * the server's own `detail`, written for the person reading it, and is left alone.
 *
 * Call once per failure: [apiErrorMessage] consumes the buffered error body.
 */
private fun Throwable.refusalMessage(fallback: String): String {
    val text = apiErrorMessage(fallback)
    val code = (this as? HttpException)?.code() ?: return text
    if (code < 500) return text
    // Stopped first. [apiErrorMessage] hands back whatever the server wrote, or — for the 500 that
    // carried no body, which is the shape this branch was written for — Retrofit's own "HTTP 500
    // Internal Server Error", and neither ends in one. Without this the two ran together as "HTTP
    // 500 Internal Server Error The server answered, so…", a single unpunctuated clause a designer
    // skims and abandons before the half that tells them waiting for signal cannot help.
    val lead = if (text.endsWith('.') || text.endsWith('!') || text.endsWith('?')) text else "$text."
    return "$lead The server answered, so a better connection will not help — this will keep being " +
        "refused until whatever caused it is corrected. Use Try again once it has been."
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
        schema.stages.forEach { spec ->
            val stored = draft.stages[spec.key]
            val record = draft.sync.stages[spec.key]
            if (record?.permanent == true) {
                failedStages++
                problems += "Stage ${spec.number} (${spec.title}): ${record.failure.orEmpty()}"
                return@forEach
            }
            val empty = stored == null || (stored.values.isEmpty() && stored.rows.isEmpty())
            if (empty && record?.signature.isNullOrBlank()) return@forEach
            // Built with the same authority the push will use, so the signature compared below is the
            // signature that would actually be sent. `replaceCollections` is part of the payload's
            // meaning and part of its digest, so a status pass that guessed differently would report
            // every stage as pending for ever.
            val built = buildStageBody(spec, stored, mediaById, isAuthoritative(stored, record))
            if (built.unresolved.isNotEmpty()) {
                pendingStages++
                return@forEach
            }
            if (signatureOf(built.body) != record?.signature) pendingStages++
        }
        // A non-permanent note (the server dropped a field it did not recognise, files still to
        // upload) is worth showing but is not a failure, so it is listed after the refusals rather
        // than counted with them.
        draft.sync.stages.forEach { (key, record) ->
            if (!record.permanent && record.failure != null) {
                val spec = schema.stages.firstOrNull { it.key == key }
                problems += "Stage ${spec?.number ?: "?"} (${spec?.title ?: key}): ${record.failure}"
            }
        }

        WorkshopSyncStatus(
            workshopId = draft.workshopId,
            remoteId = remoteIdOf(draft),
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
                    lastError = null,
                    stages = draft.sync.stages.mapValues {
                        it.value.copy(failure = null, failedAt = null, permanent = false)
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

        // NOTHING OUTSTANDING MEANS NOTHING WRITTEN, and this early exit is what makes it safe for
        // the 45-second fallback timer to call this for every workshop on the device for ever. A
        // pass that stamped "last attempted" on twenty drafts every three-quarters of a minute would
        // rewrite twenty JSON documents a minute, all day, on flash storage the photographs also
        // need — for a fact nothing displays.
        if (statusOf(context, schema, draft).isFullySynced) return true

        // ── 1. The record itself ─────────────────────────────────────────────────────────────────
        if (remoteIdOf(draft) == null) {
            if (draft.sync.createFailure != null) return true // Waiting on a person, not the network.
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
                noteSync(context, workshopId) {
                    it.copy(
                        createFailure = e.refusalMessage("The server refused to create this workshop."),
                        createFailedAt = Instant.now().toString(),
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
                    sync = it.sync.copy(createFailure = null, createFailedAt = null, lastError = null),
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
                    e.refusalMessage("the server refused this file.")
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
        for (spec in schema.stages.sortedBy { it.number }) {
            val record = draft.sync.stages[spec.key]
            if (record?.permanent == true) continue // The server's final answer; waiting on a person.

            val stored = draft.stages[spec.key]
            val empty = stored == null || (stored.values.isEmpty() && stored.rows.isEmpty())
            // An empty stage that has never been sent has nothing to say. An empty stage that HAS
            // been sent is a stage the designer emptied, and it must still go — `replaceCollections`
            // is what carries a deletion, and skipping it would leave the deleted rows alive on the
            // server to reappear in the report.
            if (empty && record?.signature.isNullOrBlank()) continue

            val built = buildStageBody(spec, stored, mediaById, isAuthoritative(stored, record))
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
                            attempts = it.attempts + 1,
                        )
                    }
                }
                continue
            }

            val signature = signatureOf(built.body)
            if (signature == record?.signature) {
                // Already up there, byte for byte. Clear a stale note (the files it was waiting on
                // have since landed) so the designer is not shown a problem that no longer exists.
                if (record.failure != null) {
                    noteStage(context, draft.workshopId, spec.key) {
                        it.copy(failure = null, failedAt = null)
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
                noteStage(context, draft.workshopId, spec.key) {
                    it.copy(
                        failure = e.refusalMessage("the server refused this stage."),
                        failedAt = Instant.now().toString(),
                        permanent = true,
                        attempts = it.attempts + 1,
                    )
                }
                continue
            }

            recordStageSent(context, draft.workshopId, spec.key, signature, result)
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
        recordStageSent(context, workshopId, spec.key, signature, result)
        bump()
        return StagePush.Sent(result)
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

    /** It went up just now. [result] carries anything the server dropped. */
    data class Sent(val result: StageSaveResultDto) : StagePush

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
 * So authority is passed in by the caller, from [StageDraft.serverBaseline] and from whether the
 * server has already acknowledged a payload built from THIS draft — the two ways a phone can honestly
 * know what the server holds. Everything else sends `replaceCollections = false`, which merges: the
 * designer's work still lands, in full, and nothing they have not seen is destroyed.
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
            )
        }
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
 * Whether the draft may claim `replaceCollections` for this stage.
 *
 * TWO WAYS A PHONE CAN HONESTLY KNOW WHAT THE SERVER HOLDS, and no third:
 *
 *  * [StageDraft.serverBaseline] — the draft was seeded from a successful read of the server's copy,
 *    or the workshop has no server record at all so there is nothing to be missing; or
 *  * the server has already acknowledged a payload built from this very draft, recorded as a
 *    signature. From that moment the server's copy of the stage IS this draft's, and a row that has
 *    since disappeared from the draft has genuinely been deleted here.
 *
 * A stage that satisfies neither is one this device has never seen the server's version of. It still
 * syncs — every value the designer typed is sent — it simply does not also assert that everything it
 * lacks should be destroyed.
 *
 * THAT LAST SENTENCE WAS FALSE FOR A SINGLETON UNTIL `StageEntryBody.merge` EXISTED, and it is worth
 * recording why rather than quietly correcting it. The authority this function grants was spent only
 * on `replaceCollections` and `emptiedEntities`, and the server applies both to COLLECTIONS; a
 * singleton's `data` was replaced wholesale on every write, by any client, authoritative or not. So a
 * draft that had never read the stage did assert exactly that everything it lacked should be
 * destroyed — silently, in place, with no RecordRevision behind it. The flag is now also driven from
 * here (see the singleton arm of `buildStage`), which is what finally makes the sentence true.
 */
internal fun isAuthoritative(stored: StageDraft?, record: StageSyncRecord?): Boolean =
    stored?.serverBaseline == true || !record?.signature.isNullOrBlank()

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
 */
private fun signatureOf(body: StageSaveBody): String {
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
    result: StageSaveResultDto,
) {
    val dropped = result.droppedKeys.takeIf { it.isNotEmpty() }
    noteStage(context, workshopId, stageKey) {
        it.copy(
            signature = signature,
            syncedAt = Instant.now().toString(),
            failure = dropped?.let { keys ->
                "the server did not recognise ${keys.size} field${plural(keys.size)} and did not store " +
                    "them (${keys.joinToString(", ").take(160)}). This phone is running a newer field " +
                    "registry than the server."
            },
            failedAt = if (dropped == null) null else Instant.now().toString(),
            permanent = false,
        )
    }

    // THE SERVER NOW HOLDS THIS DRAFT'S VERSION OF THE STAGE, which settles both of the facts that
    // decide what the NEXT payload may claim:
    //
    //  * the deletions this draft was still asserting have been carried out, so `emptiedEntities` is
    //    cleared. Left standing it would be re-asserted on every subsequent save — and would then
    //    delete a row entered on the web in the meantime, which is the very loss this field exists to
    //    prevent, arriving by the other door.
    //  * the draft and the server agree, so the draft is a baseline from here on even if it was
    //    seeded blank after a failed download. It has earned the authority it was refused.
    //
    // Bookkeeping, not an edit: no value the designer typed is touched.
    WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
        val stage = draft.stages[stageKey] ?: return@updateBookkeeping draft
        if (stage.emptiedEntities.isEmpty() && stage.serverBaseline) return@updateBookkeeping draft
        draft.copy(
            stages = draft.stages + (stageKey to stage.copy(emptiedEntities = emptyList(), serverBaseline = true))
        )
    }
}
