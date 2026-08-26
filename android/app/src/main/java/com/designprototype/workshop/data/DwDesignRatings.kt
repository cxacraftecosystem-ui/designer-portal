package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Design review on the handset: the two rounds, the two orders, and who is allowed to know what.
 *
 * The owner's rule for this surface, in their words, is the same sentence the server module is built
 * around: *"designers rate peers' work qualitatively and quantitatively, leave suggestions, and RANK
 * sketches and prototypes by drag-and-drop AND by up/down arrows — sorted by score by default, with
 * the designer having the final say"*, over *"two review levels: workshop peers first, then the
 * whole pool of designers once prototypes are finalised"*, and *"admins and master admins see who
 * rated what, when and how; designers see the same for their own records only"*.
 *
 * This file is the PURE half of that on Android — every rule, every sentence and every ordering, as
 * functions over plain data. `ui/designworkshop/DesignReviewScreen.kt` draws it and
 * `ui/designworkshop/DwRankableList.kt` is the gesture. Nothing here touches Compose, the network or
 * the disk, which is what lets `DwDesignRatingsTest` assert the whole of it on a desktop JVM in
 * milliseconds — the same split `DesignWorkshopViewers.kt` and `DwSubmissionReadiness.kt` make, and
 * for the same reason: the failures this feature can have are silent ones.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE PERMISSION RULE IS THE SERVER'S, AND THIS FILE DOES NOT HOLD A SECOND OPINION ABOUT IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `backend/app/services/design_ratings.py` decides four separate things — who is in a round, who may
 * read the ledger, whether the rows carry a name, and who may rate — and it enforces every one of
 * them on the way OUT: `rating_payload` omits a `reviewerId` a caller may not have rather than
 * sending it for a client to hide, and `visible_rows` removes rows a caller may not see before the
 * response is built. So there is nothing for this file to filter and it deliberately filters
 * nothing. What it does instead is RENDER the two flags the server sends for exactly this purpose —
 * [SubjectLedgerDto.canReadLedger] and [SubjectLedgerDto.namesShown] — into the sentences a designer
 * reads. See [dwLedgerEmptyNote], whose whole job is to keep "nobody has rated this" and "not yours
 * to see" from looking alike.
 *
 * **AND THERE IS ONE RULE THE HANDSET CANNOT MIRROR, WHICH IS SAID HERE RATHER THAN GUESSED AT.**
 * `SELF_RATING_IS_REFUSED` subtracts the row's own author from the people who may rate it, and it
 * reads `DwStageEntry.createdById` — which is on NEITHER response. `ranked_payload` sends the label,
 * the score, the two positions and this caller's own rating; `subject_ledger`'s subject block sends
 * the id, the entity, the label and the workshop. No author id, on purpose: authorship of a stage row
 * is not a fact a rating surface publishes. So a designer rating their own sketch is refused by the
 * server with a 403 carrying its own sentence, and this client renders that sentence as written
 * instead of pre-empting it with an invented predicate. The web behaves identically, and the
 * alternative — deriving "mine" from the workshop's creator — is the exact shortcut
 * `design_ratings.is_own_record` refuses in capitals, because it made the admin who started a
 * workshop the one account that could not rate anything inside it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE TWO ORDERS, AND WHY THEY ARE LISTS OF IDS RATHER THAN SORTED ROWS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The server sends both on every row and sends them together deliberately:
 * [RankedSubjectDto.defaultPosition] is what the ratings say and [RankedSubjectDto.placedPosition]
 * is what the designers say. A working order is therefore kept here as a list of subject ids, so a
 * refresh of the SCORES leaves the ARRANGEMENT alone: the averages on the cards move and no card
 * does. Every function below is total on ids it cannot find — one that has gone is dropped and one
 * that is new is appended — because an arrangement made in a courtyard on Monday still has to make
 * sense against Friday's list.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHERE AN ARRANGEMENT IS WRITTEN, WHICH IS NOT HERE AND IS NOT A NEW TABLE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The placed order IS `DwStageEntry.ordinal`, and on this handset that number is derived from the
 * ROW ORDER of [StageDraft.rows] at send time (`buildStageBody`: `ordinal = index`). So a reorder
 * made on the review screen is an ordinary stage edit: [dwPlanArrangement] rearranges the draft's
 * rows for one entity and stamps them, `WorkshopDraftStore.updateStage` writes it, and
 * `WorkshopSyncEngine.pushStage` offers it to the repository — the same two calls the stage screen
 * makes, which means it inherits the whole protocol nobody should reimplement: `merge` on a stage
 * this device has never read, the per-stage failure record, and the signature check that decides
 * whether anything needs sending at all.
 *
 * It also means a reorder made with no signal is durable and sends itself later, which on this fleet
 * is the ordinary case rather than the exception.
 *
 * **THE STAMP IS THE REGISTRY'S, NOT A FLAG THIS FILE INVENTED.** `sketch.rankFixedBy` /
 * `rankFixedAt` and the identical pair on `prototype` are declared in the field registry and carried
 * in this build's schema asset. Blank means the computed score still governs; filled means somebody
 * took responsibility for this arrangement, and from that moment a later rating must change the
 * numbers on the cards and move none of them. That is the whole of "the designer having the final
 * say", and it is why returning to the default is a real write ([dwPlanArrangement] with a null
 * stamp) rather than a client-side pretence.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT DIFFERS FROM THE WEB, STATED RATHER THAN LEFT TO BE FOUND
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The frontend contract's rule for this port is that wording comes from whichever client the owner
 * approved and a genuine platform difference is COMMENTED, never paraphrased. Two differences:
 *
 *  1. **ONE SCREEN SERVES BOTH ROUNDS.** The web has two surfaces — the workshop's own Review tab
 *     (PEER, holding the stage rows) and the `/design-review` page (POOL, holding none) — because a
 *     laptop can afford a tab strip inside a record AND a top-level page. A handset has one column
 *     and one menu, and a designer who has to guess which of two screens holds the round they want
 *     has been given a worse product than one chooser. So the round is a control on this screen, and
 *     every sentence that differed between the two web surfaces is selected by [DwRatingRound]
 *     rather than merged into something vaguer.
 *  2. **THE ENTITY HINTS ARE ROUND-AWARE.** The web's pool page prints "The prototypes in this
 *     workshop's pool round"; its workshop tab prints no hint at all. Reusing the pool wording on
 *     the peer round would tell a designer their unfinished sketches had been opened to the country,
 *     which is the precise misreading that page's own header records having shipped once. So the
 *     pool strings are verbatim and the peer round says "peer round" instead.
 */

// --------------------------------------------------------------------------------------
// The vocabulary
// --------------------------------------------------------------------------------------

/**
 * Which of the owner's two review levels is being read.
 *
 * Every sentence that differs between the rounds hangs off this enum rather than off an `if` at the
 * call site, so a screen cannot describe one round in the other's words. The strings are the web's,
 * character for character, from `components/sketches/ReviewPanel.tsx`.
 *
 * ── [of] EXISTS BECAUSE THE WIRE FIELD IS A STRING AND MUST STAY ONE ────────────────────────────
 *
 * `DesignRatingDto.round` and `RoundRankingDto.round` are declared `String`, not this enum.
 * Decoding straight into a Kotlin enum makes a round token this build has never heard of a
 * `SerializationException` that fails the WHOLE response — so one new round on the server would
 * blank the review screen on every handset that had not updated, which is the same argument
 * `DwFieldType.of` makes for the 500-odd field types. [of] degrades an unknown token to null and the
 * caller decides; nothing here crashes on one.
 */
enum class DwRatingRound(
    /** The token the API accepts. Mirrors `design_ratings.RatingRound`. */
    val wire: String,
    /** The heading over the list. */
    val title: String,
    /** The paragraph under the heading. */
    val blurb: String,
    /** What to say when the round is readable and holds nothing. */
    val emptyNote: String,
) {
    PEER(
        wire = "PEER",
        title = "Peer review — this workshop",
        blurb = "The designers on this workshop rate each other's work and settle the order it " +
            "stands in.",
        emptyNote = "There is nothing to review in this workshop yet. Pieces appear here as they " +
            "are added to the stage they belong to.",
    ),
    POOL(
        wire = "POOL",
        title = "The wider pool of designers",
        blurb = "Pieces this workshop has declared finished, open to every designer on the " +
            "platform. They are listed in score order; rearranging them belongs to the workshop " +
            "that made them.",
        emptyNote = "Nothing in this workshop has been declared finished, so nothing is open to " +
            "the wider pool yet.",
    );

    companion object {
        /** The round behind a wire token, or null for one this build does not know. */
        fun of(raw: String?): DwRatingRound? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * The two entities that are ranked, and the whole list.
 *
 * NAMED RATHER THAN "anything in stages 11 and 13", exactly as `design_ratings.RATEABLE_ENTITIES`
 * is: stage 13 also carries `prototypeStageLog` and `prototypeMaterial`, which are child rows of a
 * prototype and not things a designer ranks against each other. The API refuses them by name with a
 * 422, so offering them here would be a control that can only produce a refusal.
 *
 * PROTOTYPES LEAD because they are what most rounds are about and what a workshop spends its second
 * half on — the web's order, kept.
 */
enum class DwRateableEntity(
    /** The registry entity key, and the `entityKey` query parameter. */
    val wire: String,
    /** The chip's label. The web's `ENTITIES` labels, verbatim. */
    val label: String,
) {
    PROTOTYPE("prototype", "Prototypes"),
    SKETCH("sketch", "Sketches");

    /**
     * What this chip is showing, in the words of the round it is showing it for.
     *
     * The POOL strings are the web page's, verbatim. The PEER strings are this screen's own, because
     * the web's peer surface prints no hint and the pool wording would be false there — see the
     * class header's second platform difference.
     *
     * NEITHER SAYS "declared finished", and that is the correction the web page's own header records
     * making: `pool_visible` returns the WHOLE collection to a member of the workshop and to any
     * admin — `peerRoundClosedAt` is consulted only for a stranger — and `ranked_payload` does not
     * put the gate on the wire at all, so nothing on this screen can mark which rows were opened.
     * Two of the three audiences therefore see pieces that have NOT been declared finished, and
     * telling them otherwise is how a designer comes to believe a colleague released a sketch they
     * are still working on.
     */
    fun hint(round: DwRatingRound): String = when (round) {
        DwRatingRound.POOL -> when (this) {
            PROTOTYPE -> "The prototypes in this workshop's pool round."
            SKETCH -> "The sketches in this workshop's pool round — including the ones it never " +
                "prototyped."
        }
        DwRatingRound.PEER -> when (this) {
            PROTOTYPE -> "The prototypes in this workshop's peer round."
            SKETCH -> "The sketches in this workshop's peer round — including the ones it never " +
                "prototyped."
        }
    }

    companion object {
        fun of(raw: String?): DwRateableEntity? =
            entries.firstOrNull { it.wire == raw?.trim() }
    }
}

/**
 * The scale, inclusive at both ends — `design_ratings.MIN_SCORE` and `MAX_SCORE`.
 *
 * 1 to 5 because that is the scale this product already uses for its only other quantitative
 * judgement, and a product with two different star scales teaches its users that neither means
 * anything. Also a CHECK constraint on the column, so a client that sent 6 would be refused rather
 * than believed.
 */
const val DW_MIN_SCORE: Int = 1
const val DW_MAX_SCORE: Int = 5

/** The five choices, in the order they are drawn. */
val DW_SCORES: List<Int> = (DW_MIN_SCORE..DW_MAX_SCORE).toList()

/**
 * The registry field that OPENS the pool round, read off the rated row itself.
 *
 * `design_ratings.POOL_OPENS_WHEN_FIELD`. The owner said *"once prototypes are finalised"* and the
 * registry answers it PER PIECE rather than per workshop, for the reason its own declaration gives:
 * prototypes finish one at a time, and a workshop-level flag would open the pool round on nine
 * unfinished prototypes the day the tenth was done.
 *
 * A SKETCH CARRIES THE SAME KEY. Both entities declare it in this build's schema asset, so nothing
 * here special-cases the entity — and blank still means closed, so the pool round opens on a sketch
 * only when somebody in the workshop dates it deliberately.
 */
const val DW_POOL_OPENS_WHEN_FIELD: String = "peerRoundClosedAt"

/** The two registry keys the override stamp is stored in, on both `sketch` and `prototype`. */
const val DW_RANK_FIXED_BY_FIELD: String = "rankFixedBy"
const val DW_RANK_FIXED_AT_FIELD: String = "rankFixedAt"

// --------------------------------------------------------------------------------------
// Reading a stored row
// --------------------------------------------------------------------------------------

/** One row's text value for a key, trimmed, or "" for anything that is not a string. */
private fun DraftRow.text(key: String): String {
    val value = values[key]
    if (value !is JsonPrimitive || value is JsonNull || !value.isString) return ""
    return value.content.trim()
}

/**
 * The server's id for this row, or null for a row that has never been sent.
 *
 * `?.takeIf { it !is JsonNull }` AND NOT A BARE `as? JsonPrimitive`, which is the trap
 * `DwPhotoIntake` already documents: [JsonNull] IS a [JsonPrimitive], so reading `.content` off it
 * hands back the four-character string "null" — an id that matches nothing, is not blank, and would
 * therefore pass every emptiness test in this file while pairing a rating with no row at all.
 */
fun DraftRow.dwEntryId(): String? =
    (values["_entryId"] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull && it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }

/**
 * Has this row been opened to designers outside the workshop?
 *
 * The port of `design_ratings.pool_is_open`: true only for a NON-EMPTY value in
 * [DW_POOL_OPENS_WHEN_FIELD]. It is a DATE field, so what is stored is whatever the client wrote — a
 * date string, or a blank the designer cleared. Anything blank, absent or unreadable means the peer
 * round is still running, which is the direction that FAILS CLOSED: an unreadable value costs a
 * designer a round they can open by filling the field in, where the opposite costs an unfinished
 * prototype shown to the whole country and cannot be undone.
 *
 * THIS IS NOT A GATE ON THIS SCREEN AND MUST NEVER BECOME ONE. The server decides what a pool reader
 * may see, per piece, before it ranks anything. This is read for one purpose only: to say how many
 * of the pieces THIS DEVICE holds have been opened, so a designer who finds the pool round empty is
 * told why instead of being left to wonder. See [dwPoolOpenCount].
 */
fun dwPoolIsOpen(row: DraftRow): Boolean = row.text(DW_POOL_OPENS_WHEN_FIELD).isNotEmpty()

/**
 * How many of the rows this device holds carry a pool-opening date.
 *
 * The sentence it feeds is the difference between "this workshop has opened nothing yet" and "the
 * round could not be read", which are the two facts an empty pool list can mean and which this
 * repository has shipped as each other more than once.
 */
fun dwPoolOpenCount(rows: List<DraftRow>): Int = rows.count { dwPoolIsOpen(it) }

/**
 * A short line describing one piece from its own stored row — the identifier the label omits.
 *
 * The ranking response builds its label from `name`, falling back to the identifier column, so a
 * piece with both shows only its name. Where this device holds the row, a reviewer choosing between
 * eight bamboo stools needs the sketch number as well. The four keys and their order are the web's
 * `rowSubtitle`.
 */
fun dwRowSubtitle(row: DraftRow?): String {
    if (row == null) return ""
    return listOf("sketchNo", "prototypeCode", "designerName", "makerName")
        .map { row.text(it) }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
}

/**
 * The stage that declares this entity, read out of the registry rather than hardcoded.
 *
 * Sketches are stage 11 and prototypes are stage 13 TODAY. Writing those keys into this client would
 * be another copy of a fact the registry already publishes, and the registry is the thing that
 * moves: `SKETCH_REVIEW` is marked optional and the source document proposed deleting it outright,
 * so the numbering around these two is not a constant anybody should lean on. The web reads it the
 * same way, through `stageKeyForEntity`.
 *
 * COLLECTION only. A singleton entity of the same key would not be a list anybody ranks, and
 * accepting one would hand [dwPlanArrangement] a stage whose rows are not these pieces.
 */
fun dwStageKeyForEntity(schema: SchemaResponse, entityKey: String): String? =
    schema.stages.firstOrNull { stage ->
        stage.entities.any { it.key == entityKey && it.cardinality == "COLLECTION" }
    }?.key

// --------------------------------------------------------------------------------------
// The two orders
// --------------------------------------------------------------------------------------

/**
 * May this caller write a new arrangement back?
 *
 * READ OFF THE PRESENCE OF [RankedSubjectDto.ordinal], which is not a trick but the same question
 * asked once. The server sends the raw ordinal only when the caller is the workshop's own party or
 * an admin, and the stage save that would persist a reorder is gated by `load_workshop_or_404`,
 * which admits the creator, an admin and anybody holding a viewer grant — the same set. So a row
 * that arrived with an ordinal is a row this caller could reorder, and a row without one is not.
 *
 * The alternative — comparing the signed-in account against the workshop's creator — is the shortcut
 * the server's own code refuses in as many words, because it silently demotes every viewer-granted
 * co-designer to a stranger.
 *
 * EVERY row, not any: a mixed answer cannot happen today (the flag is decided once per response) and
 * requiring all of them means a future partial answer disables the controls rather than offering an
 * arrangement that would be refused on half the list.
 */
fun dwMayArrange(items: List<RankedSubjectDto>): Boolean =
    items.isNotEmpty() && items.all { it.ordinal != null }

/** The score order: what the ratings say, straight off `defaultPosition`. */
fun dwScoreOrder(items: List<RankedSubjectDto>): List<String> =
    items.sortedWith(compareBy({ it.defaultPosition }, { it.subjectId })).map { it.subjectId }

/** The placed order: what the designers say, straight off `placedPosition` (the stage ordinal). */
fun dwPlacedOrder(items: List<RankedSubjectDto>): List<String> =
    items.sortedWith(compareBy({ it.placedPosition }, { it.subjectId })).map { it.subjectId }

/**
 * The order THIS DEVICE holds the rows in — the arrangement as the local draft has it.
 *
 * ── WHY THIS EXISTS BESIDE [dwPlacedOrder], WHICH LOOKS LIKE THE SAME THING ─────────────────────
 *
 * It is the same thing only while the repository and this device agree. `placedPosition` is the
 * ordinal AS THE SERVER CURRENTLY HOLDS IT, and a reorder made here is durable before it is
 * accepted: the rows are rearranged in the draft the moment it is made and go up on the next sync,
 * which on this fleet can be days later or never for a stage the repository refused.
 *
 * In that window the two disagree, and taking the server's side produces the worst screen this
 * feature can show: the list in its PRE-REORDER order underneath a banner reading "this order was
 * settled deliberately by you on <today>". The arrangement looks thrown away and the sentence above
 * it insists it was not. So where this device holds the rows, the rows win — they are the thing the
 * designer actually moved.
 *
 * Rows with no server id are skipped rather than given a placeholder: a row created on this device
 * and not yet pushed has no id the ranking response could ever name, so it cannot take part in an
 * order keyed by subject id. [dwReconcileOrder] then appends anything this list does not name.
 */
fun dwHeldOrder(rows: List<DraftRow>): List<String> = rows.mapNotNull { it.dwEntryId() }

/**
 * The order to open on, given what the server sent and whether the arrangement was fixed.
 *
 * THIS IS THE OWNER'S SENTENCE, IN ONE FUNCTION. No stamp means nobody has overruled the scores, so
 * the list opens in score order — which is what "sorted by the quantitative data by default" asks
 * for, and it re-sorts freely as ratings arrive because nobody has claimed the arrangement. A stamp
 * means a designer has, and from then on the list opens in THEIR order and a new rating changes the
 * numbers on the cards without moving one of them.
 *
 * [held] IS THIS DEVICE'S OWN ROW ORDER AND IT OUTRANKS THE SERVER'S ORDINAL — see [dwHeldOrder] —
 * and note that it is consulted ONLY when the list is fixed. On an unfixed list the scores govern by
 * the owner's own rule, and the local row order there is merely whatever sequence the stage screen
 * happens to hold; reading it would quietly make "the default order" mean "the stage's row order",
 * which is the one thing the default is not.
 *
 * A caller that holds no rows passes an empty list and gets the server's ordinal. That is the pool
 * round on a workshop this device has never opened.
 */
fun dwOpeningOrder(
    items: List<RankedSubjectDto>,
    fixed: DwFixedOrderStamp?,
    held: List<String> = emptyList(),
): List<String> {
    if (fixed == null) return dwScoreOrder(items)
    return if (held.isNotEmpty()) dwReconcileOrder(held, items) else dwPlacedOrder(items)
}

/**
 * Reconcile a working order with a freshly fetched list.
 *
 * Ids that have gone are dropped and ids that are new are APPENDED in their score order, so a piece
 * added by a colleague while this screen was open turns up at the end of the arrangement rather than
 * vanishing or silently re-sorting the whole list. Appending rather than inserting by score is
 * deliberate: a fixed order belongs to a person, and slotting a new piece into the middle of it on
 * the strength of its first rating would be the score re-sorting a list somebody fixed.
 */
fun dwReconcileOrder(order: List<String>, items: List<RankedSubjectDto>): List<String> {
    val present = items.map { it.subjectId }.toSet()
    val kept = order.filter { it in present }
    val seen = kept.toSet()
    return kept + dwScoreOrder(items).filter { it !in seen }
}

/** Whether two orders are the same arrangement of the same pieces. */
fun dwSameOrder(a: List<String>, b: List<String>): Boolean = a == b

/**
 * One step up or down — the ARROW path, which is the primary one.
 *
 * The arrows and the drag write through the same two functions on purpose. Two implementations of
 * "move this one place up" is how a list ends up behaving differently depending on which control a
 * designer reached for, and the keyboard and TalkBack path is the one that must be exactly right.
 */
fun dwMoveBy(order: List<String>, id: String, delta: Int): List<String> {
    val from = order.indexOf(id)
    if (from < 0) return order
    return dwMoveTo(order, from, from + delta)
}

/**
 * Move the item at [from] to sit at index [to], clamped. The drag path and [dwMoveBy] both use it.
 *
 * CLAMPED RATHER THAN REFUSED, so "move up" on the first row is a no-op instead of an error — the
 * arrows are disabled at the ends anyway, and a gesture that ends past the edge of the list is an
 * ordinary thing a thumb does.
 */
fun dwMoveTo(order: List<String>, from: Int, to: Int): List<String> {
    if (from < 0 || from >= order.size) return order
    val target = to.coerceIn(0, order.size - 1)
    if (target == from) return order
    val next = order.toMutableList()
    next.add(target, next.removeAt(from))
    return next
}

// --------------------------------------------------------------------------------------
// The override stamp
// --------------------------------------------------------------------------------------

/** Who settled this order, and on what day. Both halves are stored on every row of the collection. */
data class DwFixedOrderStamp(val by: String, val at: String)

/**
 * The stamp these rows carry, or null for "still in the default order".
 *
 * **A STAMP ON ANY ROW COUNTS, AND THE MOST RECENT ONE WINS.** The two fields are per-ROW because
 * that is where the registry put them — a collection entity has no row of its own to hang a
 * list-level fact on — but what they describe is the ARRANGEMENT, which is a property of the whole
 * collection. So a list where one row was written by an older build, or by a laptop that has not
 * synced the other rows yet, still reads as fixed: the fail direction that KEEPS a deliberate order
 * rather than the one that silently throws it away and re-sorts by score.
 *
 * A row carrying a name and no date, or a date and no name, is NOT a stamp: the sentence on screen
 * is "fixed by X on Y" and half of it is not a sentence. Treating it as unfixed puts the list back
 * in score order with a visible way to fix it again, which is recoverable; treating it as fixed
 * prints "fixed by — on 12 August" at a designer for ever.
 *
 * The dates are compared as STRINGS, which is correct for exactly one reason: the registry stores a
 * DATE as `yyyy-mm-dd`, whose lexical order IS its chronological order. Anything else in the field
 * sorts arbitrarily among the others, which loses nothing a parse would have recovered.
 */
fun dwFixedOrderStamp(rows: List<DraftRow>): DwFixedOrderStamp? {
    var best: DwFixedOrderStamp? = null
    for (row in rows) {
        val by = row.text(DW_RANK_FIXED_BY_FIELD)
        val at = row.text(DW_RANK_FIXED_AT_FIELD)
        if (by.isEmpty() || at.isEmpty()) continue
        if (best == null || at > best.at) best = DwFixedOrderStamp(by, at)
    }
    return best
}

/** Today as the DATE fields in this registry store it — `yyyy-mm-dd`, in the reader's own zone. */
fun dwTodayStamp(today: LocalDate = LocalDate.now()): String = today.toString()

/**
 * The rows of one collection, rearranged to match [order] and stamped with who did it.
 *
 * ── THE ORDINAL IS NOT WRITTEN HERE, AND THAT IS DELIBERATE ─────────────────────────────────────
 *
 * `buildStageBody` derives `ordinal` from the ARRAY ORDER at send time (`ordinal = index`) and reads
 * no stored ordinal at all, precisely so a row carrying a stale number after a reorder cannot be
 * sorted back to where it came from. Writing one here would be a second opinion about the same
 * number, and the sync's is the one that reaches the server.
 *
 * ── EVERY ROW IS STAMPED, NOT JUST THE ONE THAT MOVED ───────────────────────────────────────────
 *
 * The stamp describes the ARRANGEMENT, so a row left where it was is as much a part of the fixed
 * order as the row that was dragged. Stamping only the moved row would make "is this list fixed?"
 * depend on which row a reader happened to look at, and [dwFixedOrderStamp] reads whichever it
 * finds.
 *
 * A null [stamp] clears both fields on every row, which is the way back to the default the owner's
 * rule requires — blank is exactly what "the computed score still governs" is spelled as in the
 * registry, so returning to the default is a real write and not a client-side pretence. What that
 * write cannot do on a stage this device has never read is the whole subject of
 * [dwPlanArrangement]'s third guard.
 *
 * ROWS THE ORDER DOES NOT NAME KEEP THEIR RELATIVE POSITION AT THE END. That case is reachable in
 * ordinary use — a row created on this device and not yet pushed has no server id and so cannot be
 * in a server-sent order at all — and dropping it here would delete a designer's unsent sketch from
 * the draft on the next save.
 */
fun dwArrangeRows(
    rows: List<DraftRow>,
    order: List<String>,
    stamp: DwFixedOrderStamp?,
): List<DraftRow> {
    val byId = LinkedHashMap<String, DraftRow>()
    for (row in rows) row.dwEntryId()?.let { byId[it] = row }
    val named = ArrayList<DraftRow>(rows.size)
    val taken = HashSet<String>()
    for (id in order) {
        val row = byId[id] ?: continue
        if (taken.add(id)) named.add(row)
    }
    val rest = rows.filter { row ->
        val id = row.dwEntryId()
        id == null || id !in taken
    }
    return (named + rest).map { row ->
        row.copy(
            values = row.values +
                mapOf(
                    DW_RANK_FIXED_BY_FIELD to JsonPrimitive(stamp?.by ?: ""),
                    DW_RANK_FIXED_AT_FIELD to JsonPrimitive(stamp?.at ?: ""),
                )
        )
    }
}

// --------------------------------------------------------------------------------------
// Writing an arrangement back: a PLAN, so the guards are testable without a disk
// --------------------------------------------------------------------------------------

/**
 * What one requested arrangement turns into: a set of rows to write, or a refusal with its reason.
 *
 * A PLAN AND NOT A COROUTINE THAT WRITES, for the reason the backend's own `RatingWritePlan` gives
 * and this repository repeats in `DwWorkshopCreation` and `DwSubmissionReadiness`: a plan can be
 * asserted about with no disk, no draft store and no Android runtime, which is what makes the three
 * guards below coverable on a laptop rather than by somebody reproducing a courtyard.
 *
 * [Refused.reason] IS A SENTENCE AND NOT A CODE, because every one of these refusals is something a
 * designer has to be able to act on. A disabled control with no explanation is the shape of a screen
 * that looks broken.
 */
sealed interface DwArrangementPlan {
    /** Write these rows over the entity's rows in the stage draft, then offer the stage. */
    data class Write(val rows: List<DraftRow>, val stamp: DwFixedOrderStamp?) : DwArrangementPlan

    /** Nothing is written, and this is what to say. */
    data class Refused(val reason: String) : DwArrangementPlan
}

/**
 * The write one reorder makes, or the finding that it cannot honestly be made.
 *
 * The three guards are ports of the three the web's review panel carries, each of which was a
 * shipped defect or a sentence the code could not keep:
 *
 *  1. **AN ARRANGEMENT NOBODY'S NAME IS ON IS NOT A DECISION.** `rankFixedBy` is TEXT and the
 *     sentence it feeds is "fixed by X on Y", so a session with no name to record would write a
 *     stamp that reads "fixed by — on 12 August" for ever. Refused before anything is written.
 *  2. **NOTHING IS WRITTEN OVER AN EMPTY COLLECTION WHILE THE SERVER IS SHOWING A FULL ONE.**
 *     [dwArrangeRows] is total on ids it cannot find, which is right — an unsent row has no server
 *     id — but it means an order of eight pieces applied to zero held rows produces zero rows and
 *     quietly blanks this entity in the draft. That state is reachable: the ranking request
 *     succeeded while this device had never opened the stage, so the cards on screen came from the
 *     server and the draft has no rows at all. Refusing costs one sentence; the alternative empties
 *     the sketch list on the phone and needs a reload to notice.
 *  3. **CLEARING THE STAMP IS REFUSED ON A STAGE THIS DEVICE HAS NEVER READ**, because on such a
 *     stage it cannot be done and the screen would say it had been. Returning to the default writes
 *     two blanks; `coerce_value` reads a blank as None and `validate_entry` then leaves the key out
 *     of the cleaned row altogether; and every row of a stage this device has not read is sent with
 *     `merge = true` (`buildStageBody`: `merge = !authoritative`, and `isAuthoritative` IS
 *     [StageDraft.stageSeen]), whose branch in `save_stage` is "keep every field the client left
 *     out". So the repository keeps the stamp it already holds, the list stays "settled
 *     deliberately" for ever, and the naive code reports success. Only rows the repository already
 *     knows about are at risk — a row with no server id has no previous version for the merge to
 *     preserve — so a workshop still working entirely offline returns to the default as normal.
 *
 * @param held the entity's rows as the draft holds them, in stored order.
 * @param order the requested arrangement, by SERVER entry id.
 * @param stamp who is claiming this arrangement, or null to return to the score order.
 * @param stageSeen [StageDraft.stageSeen] — has this device ever read the repository's copy?
 */
fun dwPlanArrangement(
    held: List<DraftRow>,
    order: List<String>,
    stamp: DwFixedOrderStamp?,
    stageSeen: Boolean,
): DwArrangementPlan {
    if (stamp != null && (stamp.by.isBlank() || stamp.at.isBlank())) {
        return DwArrangementPlan.Refused(
            "This arrangement has not been saved: this session has no name to record against it, " +
                "and an order fixed by nobody is not a decision anyone can read back."
        )
    }
    if (held.isEmpty() && order.isNotEmpty()) {
        return DwArrangementPlan.Refused(
            "This arrangement has not been saved: this phone has not read the stage these pieces " +
                "live in, so there is nothing here to rearrange. Open that stage once with a " +
                "connection, then try again."
        )
    }
    val knownToServer = held.any { it.dwEntryId() != null }
    if (stamp == null && !stageSeen && knownToServer) {
        return DwArrangementPlan.Refused(
            "The list is still in the designers' order. Returning to score order cannot be sent " +
                "from here yet: this phone has never read the repository's copy of this stage, so " +
                "its saves are merges — the repository keeps every field this device leaves blank, " +
                "and clearing the stamp IS a blank. Open this stage once with a connection, then " +
                "return to score order."
        )
    }
    return DwArrangementPlan.Write(dwArrangeRows(held, order, stamp), stamp)
}

// --------------------------------------------------------------------------------------
// What the screen says
// --------------------------------------------------------------------------------------

/**
 * The average, printed the way a designer can check it against the numbers on their own screen.
 *
 * "Not rated yet" AND NEVER "0.0", because null is not zero: a sketch nobody has got to has not been
 * judged badly, it has not been judged, and a list that showed a zero would rank the unreviewed as
 * the worst.
 *
 * [Locale.ROOT] rather than the reader's own locale, matching the web's `toFixed(1)`, which is
 * locale-independent: a designer comparing the phone against the laptop mid-workshop must not see
 * "4,2" on one and "4.2" on the other for the same piece.
 */
fun dwScoreText(score: Double?, count: Int): String {
    if (score == null || count == 0) return "Not rated yet"
    val average = String.format(Locale.ROOT, "%.1f", score)
    return "$average from $count ${if (count == 1) "designer" else "designers"}"
}

/**
 * The two positions, said in words, exactly as the web's card says them.
 *
 * BOTH PLACES, ALWAYS, WHERE BOTH ARE KNOWABLE. The gap between them IS the feature: a reader has to
 * be able to see at a glance that a piece is third in the designers' order and first on the scores.
 * Printing only the one the list is sorted by would make the two orders indistinguishable, which is
 * the failure the whole override rule exists to stop.
 *
 * @param showPlaced whether `placedPosition` describes the WHOLE collection. On the workshop's own
 *   round it does. On a pool round read by a stranger the ranking is narrowed to the pieces they may
 *   see BEFORE the positions are computed — `ranked_payload` gives the reason: a stranger shown
 *   "placed 3 of 3" for one opened prototype has been told how many the workshop holds. A position
 *   within an unknown subset is not the makers' order, so it is not printed as one.
 * @param fixedOrder true when the list on screen is the designer's own arrangement rather than the
 *   score order.
 */
fun dwPositionText(item: RankedSubjectDto, showPlaced: Boolean, fixedOrder: Boolean): String {
    val placed = if (showPlaced) "The designers place it ${item.placedPosition} · " else ""
    val tail = if (fixedOrder) "" else " (this list is in score order)"
    return "${placed}scores put it ${item.defaultPosition}$tail"
}

/**
 * What to say when a ledger came back with no rows in it — the sentence that must not be one
 * sentence.
 *
 * THREE DIFFERENT FACTS, and collapsing any two of them is the defect. "Nobody has rated this piece
 * yet" is a statement about the piece; "you can see the score, not the scorers" is a statement about
 * this account's entitlement, and the server sends [SubjectLedgerDto.canReadLedger] precisely so a
 * client does not have to guess which it is looking at. The third — rows were expected and none came
 * — is a genuine oddity and says so rather than being smoothed into either.
 *
 * Null when there ARE rows: the caller renders them instead.
 */
fun dwLedgerEmptyNote(ledger: SubjectLedgerDto): String? {
    if (ledger.ratings.isNotEmpty()) return null
    return when {
        ledger.summary.ratingCount == 0 -> "Nobody has rated this piece yet."
        ledger.canReadLedger -> "No rating rows came back for this round."
        else -> "${ledger.summary.ratingCount} designer(s) have rated this piece. Who they are is " +
            "not yours to see — you can see the score, not the scorers."
    }
}

/**
 * The note above a ledger whose rows carry no reviewer, or null when they do.
 *
 * IT NAMES THE SERVER AS THE DECIDER, deliberately. Whether a pool rater's identity reaches the
 * designer whose record it is, is an owner call held in one constant
 * (`POOL_RATINGS_NAME_THEIR_RATER`), and a sentence implying this app withheld something would send
 * a designer looking for a setting on their phone that does not exist.
 */
fun dwLedgerNamesNote(ledger: SubjectLedgerDto): String? =
    if (ledger.namesShown || ledger.ratings.isEmpty()) {
        null
    } else {
        "These ratings are shown without their reviewers. That is the server's decision for this " +
            "round, not something withheld by this screen."
    }

/**
 * Who left one ledger row, in the words the web's card uses.
 *
 * READ OFF THE ROW AND NOT OFF [SubjectLedgerDto.namesShown], which is the note above the list. The
 * per-row fact is whether `reviewerId` ARRIVED: the reviewer always sees their own row in full, so a
 * ledger can legitimately hold one named row among unnamed ones, and asking the list-level flag
 * would print "not named" over the reader's own rating.
 *
 * THE ID AND NOT A NAME, because an id is what the wire carries. Neither rating route joins the
 * `User` table, and inventing a lookup here would be a second request per row on a phone — and one
 * that could resolve a name the ledger route had deliberately withheld.
 */
fun dwRatingAttribution(rating: DesignRatingDto): String = when {
    rating.mine -> "your rating"
    !rating.reviewerId.isNullOrBlank() -> "reviewer ${rating.reviewerId}"
    else -> "reviewer not named on this response"
}

/**
 * The clock line under one ledger row: when it was judged, and when the server heard, if they differ.
 *
 * BOTH CLOCKS WHEN THEY DIFFER BY A DAY. "When" in the owner's sentence is ambiguous between "when
 * the designer judged it" and "when the server heard about it", and on this fleet those can be a
 * fortnight apart — a rating captured in a courtyard reaches the server whenever the phone next
 * finds signal. Showing only one decides the ambiguity silently, in the direction that credits the
 * sync with the judgement.
 *
 * The same-day case prints one date, because two identical dates on one line read as a bug.
 */
fun dwRatingClockLine(rating: DesignRatingDto): String {
    val judged = rating.ratedAt
    val heard = rating.createdAt
    val day = dwRatingDay(judged ?: heard)
    if (judged == null || heard == null) return "Judged $day"
    val sameDay = judged.take(10) == heard.take(10)
    return if (sameDay) "Judged $day" else "Judged $day · reached the server ${dwRatingMoment(heard)}"
}

/**
 * One ISO stamp as a date a person reads, IN THE READER'S OWN ZONE, or "-" for anything unparseable.
 *
 * ── THE ZONE CONVERSION IS THE POINT, AND THE APP HAS SHIPPED THE BUG IT PREVENTS ───────────────
 *
 * The server stamps in UTC and sends `+00:00`. Reading the date straight off the offset names the
 * UTC day, which for this product's users is off by one for a third of every day: a designer in
 * Asia/Kolkata who rates a prototype at 01:30 on 2 March is stamped `2026-03-01T20:00:00+00:00`, and
 * a handset that did not convert said "1 Mar" for something they did on the 2nd — while the browser,
 * which has always converted, said "2 Mar" for the same stamp read the same minute. One product, two
 * answers, on a ledger whose whole subject is when something happened.
 *
 * ── AND WHY THIS IS NOT `shortDay`, WHICH IS THE SAME CONVERSION ONE FILE OVER ───────────────────
 *
 * `StageSchema.shortDay` deliberately drops the YEAR — a year on each of forty field-provenance
 * labels is noise, and where it matters the provenance view prints it in full. A rating is a dated
 * judgement in an audit trail that a ministry may read years later, and "12 Aug" with no year in
 * that context is not a date. The web agrees: its ledger uses `formatDate`, which is
 * day-month-YEAR. So this carries the year and that is the only difference between them.
 *
 * "-" rather than an exception for a shape this build does not expect, matching the web's own
 * fallback: the API is entitled to send one, and a crash inside a label is far worse than a label
 * without a date.
 */
fun dwRatingDay(iso: String?): String {
    val raw = iso?.trim().orEmpty()
    if (raw.isEmpty()) return "-"
    fun render(date: LocalDate): String {
        val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return "${date.dayOfMonth} $month ${date.year}"
    }
    return runCatching {
        render(OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate())
    }.getOrElse {
        // A bare `2026-03-01` names a day already, so it takes no zone — shifting it by an offset
        // would invent one.
        runCatching { render(LocalDate.parse(raw.take(10))) }.getOrDefault("-")
    }
}

/**
 * One ISO stamp as a date AND A TIME a person reads, in the reader's own zone, or "-".
 *
 * Only ever printed for the SERVER's clock, and only when it names a different day from the
 * designer's own — see [dwRatingClockLine]. The minute matters there: the whole point of the second
 * half of that line is that the judgement and its arrival are two events, and a bare date beside
 * another bare date invites a reader to think one of them is a typo. The web prints
 * `formatDateTime` in exactly this position.
 */
fun dwRatingMoment(iso: String?): String {
    val raw = iso?.trim().orEmpty()
    if (raw.isEmpty()) return "-"
    return runCatching {
        val local = OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault())
        val month = local.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val minute = local.minute.toString().padStart(2, '0')
        "${local.dayOfMonth} $month ${local.year}, ${local.hour}:$minute"
    }.getOrElse {
        // No time in it to print, so this degrades to the date rather than to "-": a stamp that
        // names a day is still an answer to "when did the server hear".
        dwRatingDay(raw)
    }
}

/**
 * What one submitted rating turns into on screen.
 *
 * Three sentences and not one, because "the server already held this" is a SUCCESS that must not
 * wear the same words as a fresh write: a designer told "recorded" twice for one judgement starts
 * wondering whether they filed two.
 */
fun dwRatingSavedNote(replayed: Boolean, amended: Boolean): String = when {
    replayed -> "The server already held this rating, unchanged."
    amended -> "Your rating has been amended."
    else -> "Your rating has been recorded."
}

/**
 * The sentence a failed read or write gets, split into the two facts a screen needs.
 *
 * ── WHY THE OFFLINE HALF IS DECIDED BY THE CALLER AND NOT IN HERE ────────────────────────────────
 *
 * "Did this failure happen to the connection" already has exactly one answer in this app, and it is
 * `WorkshopSync.isConnectionFailure` — the port of the web's `isUnreachable`, which excuses 401, 408
 * and 429 and treats every other answered status as the server having DECIDED something. A second
 * copy of that test here would be a third idea of what offline means, and the file it lives in
 * records what the last disagreement cost: one stage the repository would never accept told a
 * designer their signal was gone on a phone showing four bars. So the caller asks that function and
 * passes the answer in.
 *
 * [refusal] IS READ ONCE BY THE CALLER, for the reason `apiRefusal` carries in capitals: Retrofit
 * buffers the error body and reading it CONSUMES the buffer, so a second pass hands back an empty
 * string.
 */
data class DwRatingFailure(val message: String, val offline: Boolean)

/** The whole-round read failed. */
fun dwRoundFailure(offline: Boolean, refusal: String): DwRatingFailure = DwRatingFailure(
    message = if (offline) {
        "The repository could not be reached, so the scores and the reviews are not on this " +
            "screen. This is not an empty list — it is a list that could not be loaded."
    } else {
        refusal
    },
    offline = offline,
)

/** The fallback sentence for a round the repository answered and refused. */
const val DW_ROUND_REFUSED: String =
    "This round could not be read. If this workshop's pieces have not been declared finished, " +
        "there is nothing in the wider round yet."

/** The fallback sentence for a ledger the repository answered and refused. */
const val DW_LEDGER_REFUSED: String = "This review history could not be read."

/** What to say when the ledger could not be reached at all. */
const val DW_LEDGER_UNREACHABLE: String =
    "The repository could not be reached, so who rated this piece is not known here yet."

/** The refusal for a submission that never left the phone. */
const val DW_RATING_NOT_SENT: String =
    "This rating was not accepted, and nothing has been recorded. What you have written is still " +
        "in the boxes."

/** The refusal for a submission with no score chosen. */
const val DW_RATING_NEEDS_A_SCORE: String =
    "Choose a score from 1 to 5 — the ranking is computed from it."

/**
 * What became of the stage push an arrangement asked for, in words.
 *
 * ── FIVE OUTCOMES AND NOT TWO, BECAUSE "SAVED" AND "SENT" ARE DIFFERENT PROMISES ────────────────
 *
 * The arrangement is durable BEFORE any of this: it is in the draft on this phone the moment the
 * designer let go, which is the whole reason the reorder does not wait for a connection. What varies
 * is whether the repository has it yet, and every one of these five states has a different next move
 * — so a single "saved" would be true of all of them and useful for none.
 *
 * [StagePush.AlreadySent] IS A SUCCESS AND READS AS ONE. It means the payload this stage would send
 * is byte-for-byte what the server already holds, which after a reorder means the push that carried
 * it has already happened (the coalescing window, or the background pass, got there first).
 *
 * [StagePush.NoRemoteYet] IS NOT AN ERROR EITHER. A workshop created in a courtyard has no server id
 * yet, so there is nowhere for an arrangement to go until the record itself is created — and saying
 * "could not be sent" about that would send a designer looking for a signal problem they do not have.
 */
fun dwPushNote(push: StagePush): String = when (push) {
    is StagePush.Sent ->
        "Saved on this phone and sent to the repository."
    StagePush.AlreadySent ->
        "Saved. The repository already holds this arrangement."
    is StagePush.HeldBack ->
        "Saved on this phone. Sending it is waiting on ${push.files} attachment" +
            "${if (push.files == 1) "" else "s"} from this stage that are still only on this " +
            "device — the sync tray carries them, and the arrangement goes up with them."
    StagePush.NoRemoteYet ->
        "Saved on this phone. This workshop has not been created on the repository yet, so there " +
            "is nowhere to send it until it is."
    StagePush.NothingToSend ->
        "Saved on this phone. There is no local copy of this stage to send, so the arrangement " +
            "stays here until this phone has read that stage once."
    StagePush.NotSent ->
        "Saved on this phone, but sending it did not complete. It goes up with the next sync — the " +
            "sync tray follows it."
}

/**
 * What became of one submitted rating: the server took it, or this phone is holding it.
 *
 * SPELLED OUT RATHER THAN RETURNED AS A NULLABLE, exactly as [StagePush] is and for the same reason:
 * "the repository has it" and "this device has it and will keep trying" are not interchangeable on
 * screen, and telling a designer the first when the truth is the second is how a judgement comes to
 * be believed filed.
 */
sealed interface DwRatingOutcome {
    /** The server answered. [saved] carries the stored row and whether this was a replay. */
    data class Sent(val saved: DesignRatingSavedDto) : DwRatingOutcome

    /**
     * Nothing reached the server; the judgement is durable on this device and the outbox owns it.
     *
     * NO STORED ROW COMES WITH THIS, deliberately — see [DW_RATING_QUEUED]. There is no row yet,
     * and manufacturing one from what is in the boxes would put a score into the average printed on
     * every card of this round that the repository has never seen and might yet refuse.
     */
    data object Queued : DwRatingOutcome
}

/**
 * What to say over a rating this phone is holding.
 *
 * ── IT DOES NOT CLAIM THE RANKING MOVED, AND THAT IS THE HONEST HALF ────────────────────────────
 *
 * The averages on these cards come from the round the repository served. A queued rating is not in
 * that round and will not be until the outbox drains AND the round is read again, so the sentence
 * says both rather than leaving a designer to discover it by comparing two numbers. The same bargain
 * the arrangement above the list makes — "saved on this device, going up later" — and it names the
 * tray that follows it, because a promise with nothing behind it is what this repository keeps
 * having to un-ship.
 */
const val DW_RATING_QUEUED: String =
    "This rating is saved on this phone and has NOT reached the repository yet. It goes up with the " +
        "next sync — the outbox tray lists it until it lands, and can send it on demand. The scores " +
        "on these cards will not move until it does."
