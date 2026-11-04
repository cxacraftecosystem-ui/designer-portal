package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

enum class DwRatingRound(
    val wire: String,
    val title: String,
    val blurb: String,
    val emptyNote: String,
) {
    PEER(
        wire = "PEER",
        title = "Peer review — this workshop",
        blurb = "Designers on this workshop rate and order each other's work.",
        emptyNote = "Nothing to review yet.",
    ),
    POOL(
        wire = "POOL",
        title = "The wider pool of designers",
        blurb = "Finished pieces, open to all designers, in score order.",
        emptyNote = "Nothing finished yet.",
    );

    companion object {
        fun of(raw: String?): DwRatingRound? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

enum class DwRateableEntity(
    val wire: String,
    val label: String,
) {
    PROTOTYPE("prototype", "Prototypes"),
    SKETCH("sketch", "Sketches");

    fun hint(round: DwRatingRound): String = when (round) {
        DwRatingRound.POOL -> when (this) {
            PROTOTYPE -> "The prototypes in this workshop's pool round."
            SKETCH -> "The sketches in this workshop's pool round — including ones never prototyped."
        }
        DwRatingRound.PEER -> when (this) {
            PROTOTYPE -> "The prototypes in this workshop's peer round."
            SKETCH -> "The sketches in this workshop's peer round — including ones never prototyped."
        }
    }

    companion object {
        fun of(raw: String?): DwRateableEntity? =
            entries.firstOrNull { it.wire == raw?.trim() }
    }
}

const val DW_MIN_SCORE: Int = 1
const val DW_MAX_SCORE: Int = 5

val DW_SCORES: List<Int> = (DW_MIN_SCORE..DW_MAX_SCORE).toList()

const val DW_POOL_OPENS_WHEN_FIELD: String = "peerRoundClosedAt"

const val DW_RANK_FIXED_BY_FIELD: String = "rankFixedBy"
const val DW_RANK_FIXED_AT_FIELD: String = "rankFixedAt"

private fun DraftRow.text(key: String): String {
    val value = values[key]
    if (value !is JsonPrimitive || value is JsonNull || !value.isString) return ""
    return value.content.trim()
}

fun DraftRow.dwEntryId(): String? =
    (values["_entryId"] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull && it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }

fun dwPoolIsOpen(row: DraftRow): Boolean = row.text(DW_POOL_OPENS_WHEN_FIELD).isNotEmpty()

fun dwPoolOpenCount(rows: List<DraftRow>): Int = rows.count { dwPoolIsOpen(it) }

fun dwRowSubtitle(row: DraftRow?): String {
    if (row == null) return ""
    return listOf("sketchNo", "prototypeCode", "designerName", "makerName")
        .map { row.text(it) }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
}

fun dwStageKeyForEntity(schema: SchemaResponse, entityKey: String): String? =
    schema.stages.firstOrNull { stage ->
        stage.entities.any { it.key == entityKey && it.cardinality == "COLLECTION" }
    }?.key

fun dwMayArrange(items: List<RankedSubjectDto>): Boolean =
    items.isNotEmpty() && items.all { it.ordinal != null }

fun dwScoreOrder(items: List<RankedSubjectDto>): List<String> =
    items.sortedWith(compareBy({ it.defaultPosition }, { it.subjectId })).map { it.subjectId }

fun dwPlacedOrder(items: List<RankedSubjectDto>): List<String> =
    items.sortedWith(compareBy({ it.placedPosition }, { it.subjectId })).map { it.subjectId }

fun dwHeldOrder(rows: List<DraftRow>): List<String> = rows.mapNotNull { it.dwEntryId() }

fun dwOpeningOrder(
    items: List<RankedSubjectDto>,
    fixed: DwFixedOrderStamp?,
    held: List<String> = emptyList(),
): List<String> {
    if (fixed == null) return dwScoreOrder(items)
    return if (held.isNotEmpty()) dwReconcileOrder(held, items) else dwPlacedOrder(items)
}

fun dwReconcileOrder(order: List<String>, items: List<RankedSubjectDto>): List<String> {
    val present = items.map { it.subjectId }.toSet()
    val kept = order.filter { it in present }
    val seen = kept.toSet()
    return kept + dwScoreOrder(items).filter { it !in seen }
}

fun dwSameOrder(a: List<String>, b: List<String>): Boolean = a == b

fun dwMoveBy(order: List<String>, id: String, delta: Int): List<String> {
    val from = order.indexOf(id)
    if (from < 0) return order
    return dwMoveTo(order, from, from + delta)
}

fun dwMoveTo(order: List<String>, from: Int, to: Int): List<String> {
    if (from < 0 || from >= order.size) return order
    val target = to.coerceIn(0, order.size - 1)
    if (target == from) return order
    val next = order.toMutableList()
    next.add(target, next.removeAt(from))
    return next
}

data class DwFixedOrderStamp(val by: String, val at: String)

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

fun dwTodayStamp(today: LocalDate = LocalDate.now()): String = today.toString()

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

sealed interface DwArrangementPlan {
    data class Write(val rows: List<DraftRow>, val stamp: DwFixedOrderStamp?) : DwArrangementPlan

    data class Refused(val reason: String) : DwArrangementPlan
}

fun dwPlanArrangement(
    held: List<DraftRow>,
    order: List<String>,
    stamp: DwFixedOrderStamp?,
    stageSeen: Boolean,
): DwArrangementPlan {
    if (stamp != null && (stamp.by.isBlank() || stamp.at.isBlank())) {
        return DwArrangementPlan.Refused(
            "Not saved — your name could not be recorded. Sign in again and retry."
        )
    }
    if (held.isEmpty() && order.isNotEmpty()) {
        return DwArrangementPlan.Refused(
            "Not saved — these pieces are not on this phone. Open this stage once with a connection, then retry."
        )
    }
    val knownToServer = held.any { it.dwEntryId() != null }
    if (stamp == null && !stageSeen && knownToServer) {
        return DwArrangementPlan.Refused(
            "Still in the designers' order — open this stage once with a connection to reset."
        )
    }
    return DwArrangementPlan.Write(dwArrangeRows(held, order, stamp), stamp)
}

fun dwScoreText(score: Double?, count: Int): String {
    if (score == null || count == 0) return "Not rated yet"
    val average = String.format(Locale.ROOT, "%.1f", score)
    return "$average from $count ${if (count == 1) "designer" else "designers"}"
}

fun dwPositionText(item: RankedSubjectDto, showPlaced: Boolean, fixedOrder: Boolean): String {
    val placed = if (showPlaced) "The designers place it ${item.placedPosition} · " else ""
    val tail = if (fixedOrder) "" else " (this list is in score order)"
    return "${placed}scores put it ${item.defaultPosition}$tail"
}

fun dwLedgerEmptyNote(ledger: SubjectLedgerDto): String? {
    if (ledger.ratings.isNotEmpty()) return null
    return when {
        ledger.summary.ratingCount == 0 -> "Nobody has rated this piece yet."
        ledger.canReadLedger -> "No ratings could be shown for this piece."
        else -> "${plural(ledger.summary.ratingCount, "designer")} ${if (ledger.summary.ratingCount == 1) "has" else "have"} rated this piece. You can see the score, not who gave it."
    }
}

fun dwLedgerNamesNote(ledger: SubjectLedgerDto): String? =
    if (ledger.namesShown || ledger.ratings.isEmpty()) {
        null
    } else {
        "Reviewers' names are not shown in this round."
    }

fun dwRatingAttribution(rating: DesignRatingDto): String = when {
    rating.mine -> "your rating"
    !rating.reviewerId.isNullOrBlank() -> "reviewer ${rating.reviewerId}"
    else -> "reviewer not named"
}

fun dwRatingClockLine(rating: DesignRatingDto): String {
    val judged = rating.ratedAt
    val heard = rating.createdAt
    val day = dwRatingDay(judged ?: heard)
    if (judged == null || heard == null) return "Judged $day"
    val sameDay = judged.take(10) == heard.take(10)
    return if (sameDay) "Judged $day" else "Judged $day · uploaded ${dwRatingMoment(heard)}"
}

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
        runCatching { render(LocalDate.parse(raw.take(10))) }.getOrDefault("-")
    }
}

fun dwRatingMoment(iso: String?): String {
    val raw = iso?.trim().orEmpty()
    if (raw.isEmpty()) return "-"
    return runCatching {
        val local = OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault())
        val month = local.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val minute = local.minute.toString().padStart(2, '0')
        "${local.dayOfMonth} $month ${local.year}, ${local.hour}:$minute"
    }.getOrElse {
        dwRatingDay(raw)
    }
}

fun dwRatingSavedNote(replayed: Boolean, amended: Boolean): String = when {
    replayed -> "This rating was already saved, unchanged."
    amended -> "Your rating has been amended."
    else -> "Your rating has been recorded."
}

data class DwRatingFailure(val message: String, val offline: Boolean)

fun dwRoundFailure(offline: Boolean, refusal: String): DwRatingFailure = DwRatingFailure(
    message = if (offline) {
        "Offline — scores could not be loaded."
    } else {
        refusal
    },
    offline = offline,
)

const val DW_ROUND_REFUSED: String =
    "Couldn't load this round."

const val DW_LEDGER_REFUSED: String = "This review history could not be read."

const val DW_LEDGER_UNREACHABLE: String =
    "Offline — raters couldn't load."

const val DW_RATING_NOT_SENT: String =
    "Rating not accepted. Your text is kept."

const val DW_RATING_NEEDS_A_SCORE: String =
    "Choose a score from 1 to 5."

fun dwPushNote(push: StagePush): String = when (push) {
    is StagePush.Sent ->
        "Saved on this phone and uploaded."
    StagePush.AlreadySent ->
        "Saved. This arrangement was already uploaded."
    is StagePush.HeldBack ->
        "Saved. Uploads with ${push.files} attachment${if (push.files == 1) "" else "s"} still pending."
    StagePush.NoRemoteYet ->
        "Saved. Uploads after the workshop itself has been uploaded."
    StagePush.NothingToSend ->
        "Saved. Uploads once this stage has been opened online."
    StagePush.NotSent ->
        "Saved. Upload didn't finish; it retries next sync."
}

sealed interface DwRatingOutcome {
    data class Sent(val saved: DesignRatingSavedDto) : DwRatingOutcome

    data object Queued : DwRatingOutcome
}

const val DW_RATING_QUEUED: String =
    "Saved. Uploads with the next sync."
