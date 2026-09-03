package com.designprototype.workshop.data

/**
 * WHAT THE RECORDS QUEUE IS ALLOWED TO SAY ABOUT ITSELF.
 *
 * ── THE DEFECT THIS FILE ENDS, WHICH WAS ALREADY FIXED ONCE, NEXT DOOR ────────────────────────
 *
 * The offline outbox drew ONE number under a cloud-off icon and ONE sentence:
 *
 *     "3 entries saved on this device — uploading when you're online."
 *
 * [OfflineOutbox.count] counted every row in the queue, refusals included, and `syncOutbox` steps
 * over a refused entry for ever. So a record the server has permanently rejected — a duplicate
 * artisan, a field the API will not take, a permission this account does not have — sat inside a
 * sentence promising it was on its way, and stayed there for the rest of the installation's life.
 * The words for what that costs are already in `WorkshopRepository.notifyUser`'s KDoc: it "is a lie
 * for an entry the server has refused for good, and which stays a lie for ever". The fix applied at
 * the time was a Toast, and a Toast in a courtyard lasts five seconds — and by design fires only
 * when the REASON changes, so on the second app open it says nothing at all.
 *
 * The design-workshop half of this app solved the identical problem properly and did it first. See
 * `dwDeviceSyncBanner` in `data/WorkshopSync.kt`: it computes a `waiting` boolean that is FALSE when
 * the only outstanding items are ones a connection will not move, gates the cloud-off icon on it,
 * and gives every other class of outstanding work its own sentence naming its own remedy. That was
 * written against a measured failure on an SM-M325F. This file is that treatment applied to the
 * records queue, so that one app does not hold two different ideas of what "saved on this device"
 * means depending on which screen you are looking at.
 *
 * ── WHY IT IS PURE ────────────────────────────────────────────────────────────────────────────
 *
 * No Context, no Compose, no clock, no IO — the same discipline `dwDeviceSyncBanner` keeps and for
 * the same reason. Every sentence a designer with no signal will read is decided here and pinned by
 * `OutboxBannerTest`, rather than being assembled inside a composable where the only way to check it
 * is to take a handset into a village and turn aeroplane mode on.
 */

/**
 * The queue, split by what a connection can do about it.
 *
 * Read in ONE pass under ONE lock (see [OfflineOutbox.counts]), because two separate reads can land
 * either side of a sync that emptied the queue and the banner would then draw a total that never
 * existed on the device at any moment.
 */
data class OutboxCounts(
    /** Entries a working connection WILL send. */
    val waiting: Int,
    /** Entries the server has refused for good. A sync pass will not touch these again. */
    val refused: Int,
    /**
     * Entries captured on this phone by a DIFFERENT ACCOUNT, which this session will not send.
     *
     * ── THE THIRD THING A CONNECTION DOES NOT MOVE, AND IT ARRIVED AS "WAITING" ───────────────
     *
     * `syncOutbox` steps over an entry whose [PendingEntry.ownerUserId] is not the signed-in
     * account, because sending it would file another designer's fieldwork under this one's name (see
     * that field). Nothing is refused on such an entry and nothing has failed, so it carried a null
     * `failure` and landed in [waiting] — inside the sentence "sending now" under a cloud-off icon,
     * for an entry no amount of signal will ever move. That is the defect this whole file exists to
     * end, reaching the banner by a third door; `dwDeviceSyncBanner` had already closed the same one
     * on the design-workshop side, twice, and its `waiting` flag is the pattern followed here.
     *
     * COUNTED RATHER THAN HIDDEN, exactly as [refused] is. The entries are real fieldwork sitting on
     * the flash storage of a shared handset, and a designer looking for a fortnight they captured
     * yesterday needs the number to be visible while the wrong person is signed in — a queue that
     * silently drops to zero is how somebody concludes the work was lost and stops looking.
     *
     * DEFAULTED, so every existing construction and every pinned sentence is unchanged.
     */
    val otherAccount: Int = 0,
) {
    val isEmpty: Boolean get() = waiting == 0 && refused == 0 && otherAccount == 0
}

/**
 * The queue split into the three things a designer can do about it, from the entries themselves.
 *
 * PURE, AND SEPARATE FROM [OfflineOutbox.counts] FOR THIS FILE'S STANDING REASON: the numbers decide
 * which of three incompatible stories the banner tells about one phone, and a decision in this
 * repository is checkable on a desktop JVM. `counts` keeps the read and the lock — the part that
 * needs a Context — and this keeps the classification, so `OutboxOwnerAccountTest` can assert the
 * partition against hand-written entries instead of against a handset with two accounts on it.
 *
 * THE THREE ARE A PARTITION AND NOT THREE FILTERS. Every entry lands in exactly one, and the order
 * of the tests is the order of what a person has to deal with first: a refusal is reported as a
 * refusal whoever captured it, because that is the one a person can act on and because the tray
 * lists it. Overlapping counts would put one record inside two sentences and make the total larger
 * than the queue, which is the arithmetic [OfflineOutbox.counts] already refuses to let two separate
 * reads produce.
 *
 * [signedInUserId] null means nobody is signed in, and then nothing is another account's — the same
 * answer [dwDraftIsForAnotherAccount] gives, asked rather than re-spelled so this and the drain
 * cannot form two opinions about one entry.
 */
internal fun outboxCountsOf(entries: List<PendingEntry>, signedInUserId: String?): OutboxCounts {
    var waiting = 0
    var refused = 0
    var elsewhere = 0
    entries.forEach { entry ->
        when {
            entry.failure != null -> refused++
            dwDraftIsForAnotherAccount(entry.ownerUserId, signedInUserId) -> elsewhere++
            else -> waiting++
        }
    }
    return OutboxCounts(waiting = waiting, refused = refused, otherAccount = elsewhere)
}

/**
 * The refused entries THIS SESSION may actually try again. (2026-09-03)
 *
 * ONE OWNER FOR A TEST TWO CALLERS HAVE TO AGREE ON, and they used to have none: `clearAllFailures`
 * decided which refusals to unmark, and `WorkshopRepository.retryAllOutboxFailures` decided which
 * ones to COUNT as tried, from two separate walks of the same queue. That is the shape of defect this
 * package has already paid for twice ([OfflineOutbox.count] against [outboxCountsOf]); the copy that
 * is not updated is always the one on the surface somebody is looking at.
 *
 * BOTH TERMS ARE LOAD-BEARING. `failure != null` is what makes it a refusal; the owner test is what
 * keeps the bulk button from doing the one thing that cannot be undone. `syncOutbox` skips an entry
 * another account captured, so unmarking it sends nothing and merely deletes the server's own
 * sentence — after which `outboxFailureRows` stops listing the entry at all, because it lists a row
 * only while it carries a refusal, and a fortnight of somebody else's fieldwork goes back to being
 * invisible work no pass will ever move.
 *
 * Asked with [dwDraftIsForAnotherAccount] rather than a test spelled out here, for [outboxCountsOf]'s
 * reason: the drain, the banner, the tray and this must not form four opinions about one boundary.
 */
internal fun outboxRetryableFailures(
    entries: List<PendingEntry>,
    signedInUserId: String?,
): List<PendingEntry> = entries.filter {
    it.failure != null && !dwDraftIsForAnotherAccount(it.ownerUserId, signedInUserId)
}

/** One line of the banner, and whether it is the amber one. */
data class OutboxBannerLine(val text: String, val warn: Boolean)

/**
 * What to draw, or null for "draw nothing".
 *
 * @property showCloudOff gate for the cloud-off icon. FALSE when nothing outstanding is waiting on
 *   the network — a refusal drawn under a cloud icon tells the designer to go and find a signal,
 *   which is the one thing that will not help.
 * @property actionable there is something a PERSON can do, so the banner is worth being tappable.
 *   Driven by [OutboxCounts.refused] alone: an entry that is merely waiting needs nobody.
 */
data class OutboxBanner(
    val showCloudOff: Boolean,
    val actionable: Boolean,
    val lines: List<OutboxBannerLine>,
)

/** "1 entry" / "4 entries", so no call site has to spell the plural itself. */
internal fun outboxEntryCount(n: Int): String = if (n == 1) "1 entry" else "$n entries"

/**
 * The banner for a queue holding [counts], on a device that is [online] or not.
 *
 * WHY THE CONNECTION STATE IS AN INPUT. "Uploading when you're online" was said to a designer whose
 * phone had four bars and whose queue was not moving, because the entries in it had been refused.
 * The waiting line now states which of the two situations the phone is actually in, so a queue that
 * is NOT draining on a working connection reads as something to look at rather than as a promise.
 *
 * WHY THE REFUSAL LINE NAMES THE REMEDY AND EXPLICITLY DENIES THE OTHER ONE. `dwDeviceSyncBanner`
 * learned this and says so in words — "a sync will NOT move them" — because a designer whose work is
 * not arriving walks to the top of the hill to find a signal, and does it again the next day.
 * Telling them the walk is pointless is the only useful sentence available.
 */
fun outboxDeviceBanner(counts: OutboxCounts, online: Boolean): OutboxBanner? {
    if (counts.isEmpty) return null
    val lines = mutableListOf<OutboxBannerLine>()
    if (counts.waiting > 0) {
        lines.add(
            OutboxBannerLine(
                text = if (online) {
                    "${outboxEntryCount(counts.waiting)} saved on this device — sending now."
                } else {
                    "${outboxEntryCount(counts.waiting)} saved on this device — will be sent when " +
                        "you have a signal."
                },
                warn = false,
            )
        )
    }
    if (counts.refused > 0) {
        lines.add(
            OutboxBannerLine(
                text = "${outboxEntryCount(counts.refused)} the server would not accept. A sync will " +
                    "NOT move ${if (counts.refused == 1) "it" else "them"} — tap to read why and try " +
                    "again. Nothing has been deleted.",
                warn = true,
            )
        )
    }
    // ITS OWN LINE, AND NOT A CLAUSE ON EITHER OF THE TWO ABOVE. The remedy is neither a signal nor
    // a correction: it is a different person signing in. Folded into the waiting line it would
    // promise a send that cannot happen; folded into the refusal line it would send a designer to a
    // tray that does not list it, because nothing about it was refused. See
    // [OutboxCounts.otherAccount].
    if (counts.otherAccount > 0) {
        lines.add(
            OutboxBannerLine(
                text = "${outboxEntryCount(counts.otherAccount)} captured on this phone by another " +
                    "account. Sign in as them to send ${if (counts.otherAccount == 1) "it" else "them"} " +
                    "— nothing has been deleted.",
                warn = true,
            )
        )
    }
    return OutboxBanner(
        // The icon follows the WAITING half only. This is the gate `dwDeviceSyncBanner` computes for
        // the same reason, and getting it wrong in the other direction is what shipped here.
        showCloudOff = counts.waiting > 0,
        // REFUSALS ALONE, and another account's entries deliberately do NOT make this true. Tapping
        // opens the tray, and the tray lists refusals (`outboxFailureRows` filters on `failure`); a
        // banner that invited a tap and then showed an empty screen would be one more dead end
        // wearing the costume of a remedy, which is the phrase this queue already uses about the two
        // buttons it had to stop offering.
        actionable = counts.refused > 0,
        lines = lines,
    )
}

/**
 * One refused entry, as the tray lists it: what it was, why it will not send, what it is carrying.
 *
 * A PURE PROJECTION of [PendingEntry] rather than the entry itself, so the tray's wording is pinned
 * by a JVM test and so the tray never holds [PendingEntry.payloadJson]. That payload is the record's
 * whole body; a screen that held it would sooner or later print a field out of it, and the fields in
 * there include an artisan's identity answers.
 */
data class OutboxFailureRow(
    val entryId: String,
    val label: String,
    val kind: String,
    val reason: String,
    val mediaCount: Int,
    /** True when this entry is waiting on a newer build rather than on a person. See `blocksRetry`. */
    val awaitingUpdate: Boolean,
    /**
     * True when the register already holds a record occupying this one's identity — an answered 409
     * rather than one more anonymous refusal. See `PendingEntry.conflict`.
     *
     * ON THE ROW AND NOT IN A SECOND STRUCTURE BESIDE IT. The tray needs the flag everywhere it
     * draws a row, and a parallel `Set<String>` of ids read alongside this list is one more thing
     * that can be taken from a different moment than the rows it describes — the argument
     * [OfflineOutbox.counts] makes about its own numbers. One projection, one read, one moment.
     *
     * MUTUALLY EXCLUSIVE WITH [awaitingUpdate] by construction: one is a 409 and the other a 422
     * carrying `extra_forbidden`. A skew clears when either build is updated; a clash clears only
     * when a PERSON resolves it, which is why the tray offers an escape rather than a retry.
     */
    val conflict: Boolean,
    /**
     * True when the record this entry carries IS ALREADY ON THE SERVER — `PendingEntry.createdId`
     * set, kind 5 of the six on that field: saved, with files still outstanding.
     *
     * ON THE ROW FOR [conflict]'S REASON, and needed for one screen in particular: the discard
     * dialog. Without it `outboxDiscardConfirmation` promised "nothing about it has reached the
     * server" over a row whose own reason line reads "It was saved, but 2 file(s) were refused" —
     * and a designer who believes the dialog re-enters a record the register already holds.
     *
     * MUTUALLY EXCLUSIVE WITH [conflict] by construction: `replayEntry`'s 409 leg runs only while
     * `createdId` is null, because a clash means no record of ours was written.
     */
    val savedOnServer: Boolean,
    /**
     * WHAT THIS ENTRY POINTS AT AND THE SERVER DOES NOT HAVE, in the words a person uses for it —
     * "design & prototype workshop", "artisan", "the craft it corrects". Empty on every other row.
     *
     * NOUNS AND NOT COLUMN NAMES, because the row's whole value is that the designer can tell which
     * box on the form to reopen, and `designWorkshopId` names no box. The mapping lives in
     * `REFERENCE_FIELD_NOUNS` beside the columns rather than in the tray, so a control added later
     * cannot end up with two names on two screens.
     *
     * MORE THAN ONE IS AN HONEST AMBIGUITY, never a guess — `records.require_record` and
     * `design_workshops.load_workshop_or_404` both answer the byte-identical "Record not found", so
     * an artisan carrying both a workshop and a design workshop genuinely cannot be narrowed further.
     * See `PendingEntry.danglingField`.
     */
    val danglingNouns: List<String> = emptyList(),
    /**
     * The dangling columns this tray can actually offer a picker for — the two workshop links.
     *
     * A SUBSET OF [danglingNouns]'s columns AND SOMETIMES A SMALLER ONE, which is the honest shape
     * rather than an oversight. The record a correction is aimed at cannot be re-picked (it is the
     * `{id}` in the PATCH path, not a field), and the registers — artisan, craft, product, tool —
     * have no list on this screen. A row whose only dangling candidate is one of those still gets
     * its sentence and still keeps its entry; what it does not get is a button that opens an empty
     * picker, which would be a second dead end wearing the costume of a remedy.
     */
    val repickKeys: List<String> = emptyList(),
    /**
     * CAPTURED ON THIS PHONE BY A DIFFERENT ACCOUNT — this session may look at it and may not act on
     * it. (2026-09-03)
     *
     * ── WHAT THE TRAY DID WITHOUT IT, WHICH WAS WORSE THAN OFFERING A BUTTON THAT FAILED ─────────
     *
     * [outboxFailureRows] filtered on `failure != null` and asked nothing about ownership, so a
     * refusal recorded against designer A's entry was listed to designer B under the same three
     * buttons as B's own. Tapping *Try again* ran `clearFailure`, which sets `failure`, `failedAt`,
     * `skewRun`, `conflict` and `danglingField` all to null — and `syncOutbox` then SKIPS the entry
     * on the owner check, so nothing was sent. The row simply vanished from the tray (no failure, no
     * row) taking the server's own sentence with it, and the entry went back to being invisible work
     * that no pass will ever move. A destroyed a reason, sent nothing, and could no longer see the
     * entry that had it. *Try all again* did the same thing to every such row at once.
     *
     * NOT HIDDEN, FOR [OutboxCounts.otherAccount]'S REASON. It is real fieldwork on the flash storage
     * of a shared handset, and a row that disappears is how somebody concludes their work was lost.
     * It is listed, with the server's reason still under it and [DW_DRAFT_OTHER_ACCOUNT_ROW] saying
     * whose it is and what moves it — the same one line the workshop list already uses, reused rather
     * than re-worded so one app does not describe one boundary two ways.
     *
     * DEFAULTED FALSE, so every existing construction and every pinned row is unchanged, and so a
     * caller with nobody signed in gets the behaviour it has always had.
     */
    val otherAccount: Boolean = false,
)

/**
 * How a queued entry's type reads to the person holding the phone.
 *
 * The wire values are lower-case route names — "artisan", "questionnaire", [OFFLINE_EXPORT_RECORD] —
 * and one of them is not a record at all. A tray that printed them raw would show a designer the
 * word "dwexport" and leave them to work out that the thing that failed was a bookkeeping row about
 * a report they had already delivered by hand.
 */
fun outboxKindLabel(type: String, isUpdate: Boolean): String {
    val noun = when (type) {
        "artisan" -> "Artisan"
        "craft" -> "Craft"
        "product" -> "Product"
        "tool" -> "Toolkit"
        "process" -> "Process"
        "workshop" -> "Workshop"
        "questionnaire" -> "Interview"
        OFFLINE_MEDIA_ONLY -> "Photographs and recordings"
        OFFLINE_EXPORT_RECORD -> "Report delivery note"
        // Named for what a designer did rather than for the table it lands in: they judged a
        // colleague's piece. "Rating" alone would sit in a tray beside six record types and read as
        // one more of them.
        OFFLINE_DESIGN_RATING -> "Design review rating"
        else -> type
    }
    return if (isUpdate) "$noun — a correction" else noun
}

/**
 * The record type as it appears MID-SENTENCE, which is not what [outboxKindLabel] answers.
 *
 * "Artisan — a correction" is a heading; this is the noun in *"it could be the artisan it corrects, or
 * the design & prototype workshop it is filed under"*. Lower case, no dash, no clause — a sentence
 * that spliced a heading into itself would read as though the app were quoting its own UI at somebody
 * standing in a courtyard.
 *
 * It exists for exactly one caller: the correction arm of `outboxDanglingSentence`, where the record
 * this entry is a correction TO is one of the things that may have gone missing (a `require_record`
 * on the PATCH path runs before any key in the payload is looked at). Telling a designer to re-pick a
 * workshop for an artisan an admin deleted at the office is the remedy that cannot work, so the
 * record itself has to be nameable as a suspect.
 */
fun outboxRecordNoun(type: String): String = when (type) {
    "artisan" -> "artisan it corrects"
    "craft" -> "craft it corrects"
    "product" -> "product it corrects"
    "tool" -> "toolkit it corrects"
    "process" -> "process it corrects"
    "workshop" -> "workshop it corrects"
    "questionnaire" -> "interview it corrects"
    // Every other type reaches this only through a shape that cannot be a correction — a media-only
    // entry has no payload to hold a reference, an export note and a rating carry their own ids — so
    // this is a fallback rather than a case, and it says the true thing rather than inventing a noun
    // for a type nobody has named yet.
    else -> "record it corrects"
}

/**
 * WHAT A RETRY ACTUALLY ACHIEVED, split so that no sentence can attribute one entry's success to
 * another.
 *
 * A retry drains the WHOLE queue — that is deliberate and worth keeping, because a person who has
 * just found a signal wants everything to go, not one row. But the pass therefore moves entries the
 * person did not ask about, and reporting its total as though it were the answer about the entry they
 * tapped is how "“A” was sent." came to be printed above A, still listed, still refused.
 *
 * @property requestedSent the entry the person tapped (or, for "try all", at least one of the refused
 *   ones) is gone from the queue, which for this queue means the server took it.
 * @property refusedSent how many of the entries this retry was ABOUT went.
 * @property refusedTried how many entries this retry was about — 1 for a single row.
 * @property othersSent entries the same pass drained that this retry was NOT about. Worth saying,
 *   because a designer watching the banner's count drop by three after tapping one row deserves to
 *   know where the other two went; never worth counting as success for the row they tapped.
 */
data class OutboxRetryResult(
    val requestedSent: Boolean,
    val refusedSent: Int,
    val refusedTried: Int,
    val othersSent: Int,
    /**
     * The device had a connection, so the queue was actually tried.
     *
     * FALSE MEANS NOTHING WAS SENT ANYWHERE, and it needs its own sentence for the same reason the
     * rest of this file exists: `syncOutbox` returns 0 without attempting anything when there is no
     * signal, which is indistinguishable at the call site from "attempted and refused again". The old
     * message told a designer standing in a courtyard with no bars that "the reason under it is what
     * came back this time" — over a request that was never made.
     */
    val attempted: Boolean = true,
)

/** "1 other entry" / "3 other entries", for the tail of a retry sentence. */
private fun otherEntries(n: Int): String = if (n == 1) "1 other entry" else "$n other entries"

/**
 * What the tray says after ONE refused entry was tried again.
 *
 * PURE and pinned by `OutboxBannerTest`, because the failure it replaces was a sentence assembled
 * inside a composable out of a number that did not mean what it was read as. The success half names
 * the entry; the failure half must NOT, because the reason is already printed verbatim under the row
 * and repeating the label above it only makes the screen say the same thing twice.
 */
fun outboxRetryMessage(label: String, result: OutboxRetryResult): String {
    if (!result.attempted) {
        return "This phone has no connection, so nothing was tried. “$label” is still here and " +
            "nothing has been deleted — try again when you have a signal."
    }
    val tail = if (result.othersSent > 0) {
        " ${otherEntries(result.othersSent)} in the queue went at the same time."
    } else {
        ""
    }
    return if (result.requestedSent) {
        "“$label” was sent.$tail"
    } else {
        "“$label” still did not go. The reason under it is what came back this time.$tail"
    }
}

/** What the tray says after every refused entry was tried again. See [outboxRetryMessage]. */
fun outboxRetryAllMessage(result: OutboxRetryResult): String {
    if (!result.attempted) {
        return "This phone has no connection, so nothing was tried. They are all still here and " +
            "nothing has been deleted — try again when you have a signal."
    }
    val head = when {
        result.refusedTried == 0 -> "There was nothing refused to try."
        result.refusedSent == 0 ->
            "Tried ${outboxEntryCount(result.refusedTried)} again; none went. The reason under each " +
                "one is what came back this time."
        result.refusedSent == result.refusedTried ->
            "Tried ${outboxEntryCount(result.refusedTried)} again; all of them went."
        else ->
            "Tried ${outboxEntryCount(result.refusedTried)} again; ${result.refusedSent} went and " +
                "${result.refusedTried - result.refusedSent} still did not."
    }
    return if (result.othersSent > 0) {
        val were = if (result.othersSent == 1) "was" else "were"
        "$head ${otherEntries(result.othersSent)} that $were only waiting for a signal went too."
    } else {
        head
    }
}

/**
 * The rows for the tray, most recently refused first, projected from the queue.
 *
 * [signedInUserId] IS WHAT DECIDES WHETHER A ROW MAY BE ACTED ON (2026-09-03), and it is threaded in
 * exactly as [outboxCountsOf] already takes it — through `WorkshopRepository`'s `cachedUser()`, which
 * is the same token store the drain itself reads, so the tray and the pass cannot form two opinions
 * about who is signed in. Null means nobody is, and then nothing is another account's; see
 * [OutboxFailureRow.otherAccount] for what listing one as actionable destroyed.
 *
 * DEFAULTED, so every existing caller and every pinned row is unchanged.
 */
fun outboxFailureRows(
    entries: List<PendingEntry>,
    signedInUserId: String? = null,
): List<OutboxFailureRow> =
    entries
        .filter { it.failure != null }
        .sortedByDescending { it.failedAt ?: it.createdAt }
        .map { entry ->
            OutboxFailureRow(
                entryId = entry.id,
                label = entry.label.ifBlank { "(untitled)" },
                kind = outboxKindLabel(
                    entry.type,
                    isUpdate = entry.targetId != null && entry.type != OFFLINE_MEDIA_ONLY,
                ),
                reason = entry.failure.orEmpty(),
                mediaCount = entry.media.size,
                awaitingUpdate = entry.skewRun != null,
                // BOTH HALVES, and neither implies the other. `failure` is what stopped the pass and
                // is already the filter above; `conflict` is what this entry IS. An entry carrying
                // the flag with no refusal recorded never reaches here at all — it is one a person
                // has just asked to retry, caught mid-way through `clearFailure`.
                conflict = entry.conflict,
                // `createdId` and nothing else. `uploadedMedia` cannot add a case: uploads start
                // only after the create leg has written this field (`replayEntry`), so a non-empty
                // one without it is unreachable. A media-only entry has it set to its `targetId`
                // before any byte moves, which is also correct here — that record exists.
                savedOnServer = entry.createdId != null,
                // TURNED INTO WORDS HERE, in the same pure projection as everything else on this
                // row, so the tray cannot word the same fact differently from the sentence printed
                // under it — the two are read together, three lines apart, by somebody deciding
                // whether to press a red button.
                danglingNouns = entry.danglingKeys.map { key ->
                    referenceFieldNoun(key, recordNoun = outboxRecordNoun(entry.type))
                },
                repickKeys = entry.danglingKeys.filter { it in WORKSHOP_LINK_KEYS },
                // ASKED WITH THE DRAIN'S OWN FUNCTION, never with a test written here. `syncOutbox`
                // decides whether to send this entry with `dwDraftIsForAnotherAccount`, and a tray
                // that reached the answer any other way would sooner or later offer a button for an
                // entry the pass had already decided to skip — which is precisely the state this
                // flag was added to end. See [OutboxFailureRow.otherAccount]. (2026-09-03)
                otherAccount = dwDraftIsForAnotherAccount(entry.ownerUserId, signedInUserId),
            )
        }
