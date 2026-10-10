package com.designprototype.workshop.data

import com.designprototype.workshop.report.formatReportDate
import java.util.concurrent.ConcurrentHashMap

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE SANCTIONING AUTHORITY'S SIGN-OFF, AS THE HANDSET DISPLAYS IT. Added 2026-10-09.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Approving a report, sending it back or withdrawing an approval, and handing it on to the office
 * are the Ministry Admin's and the master admin's, on the web (docs/DECISION-ministry-surfaces-web-only.md).
 * This handset gets no screen for any of the three. What it gets is the CONSEQUENCE, because a
 * designer meets it in the field:
 *
 *  * an APPROVED report, and one handed on to the office, can no longer be changed — the server
 *    answers 403 to every content write with one of the two sentences below, and the sync records
 *    that refusal against the item and deletes nothing. Saying it on the workshop's own screen
 *    BEFORE a designer types into it is the whole of [dwFrozenSentence]'s job;
 *  * the generated report's Certification section names who approved it and, once it has gone,
 *    where it was handed on to — [dwReportSignOffLines], the twin of the server's
 *    `report_builder._render_signatures` lines.
 *
 * Pure functions over the wire values, so every rule here is asserted on the desktop JVM.
 */

/** Said when an APPROVED report refuses a change. BYTE-IDENTICAL to the server's sentence. */
const val DW_FROZEN_APPROVED_SENTENCE =
    "This report has been approved, so it can no longer be changed. If something in it needs " +
        "correcting, ask the Ministry Admin to withdraw the approval; it then comes back to its " +
        "designers to correct and hand in again."

/** Said when a report handed on to the office refuses a change. BYTE-IDENTICAL to the server's. */
const val DW_FROZEN_HANDED_ON_SENTENCE =
    "This report has been approved and handed on to the office, so it can no longer be changed. If " +
        "something in it needs correcting, ask the Ministry Admin to return it to its designers."

/**
 * Is this report closed to every content write? APPROVED, or SUBMITTED with a hand-on on record.
 *
 * ── `handedOnAt` AND NOT THE STATUS ALONE ────────────────────────────────────────────────────────
 *
 * `SUBMITTED` changed meaning on 2026-09-13 and nothing was backfilled, so a SUBMITTED row from
 * before that day is a designer's own submission and is still theirs to reopen. Only a row the
 * sanctioning authority actually handed on carries `handedOnAt`, and only that row is frozen. A
 * status this build does not recognise is NOT frozen here: the server is the authority and refuses
 * what it refuses, and a phone that guessed would lock a designer out of a workshop that is open.
 */
fun dwIsFrozen(status: String, handedOnAt: String?): Boolean {
    val token = status.trim().uppercase()
    return token == "APPROVED" || (token == "SUBMITTED" && !handedOnAt.isNullOrBlank())
}

/** The sentence a frozen report is introduced with, or null for one that may still be changed. */
fun dwFrozenSentence(status: String, handedOnAt: String?): String? = when {
    !dwIsFrozen(status, handedOnAt) -> null
    status.trim().uppercase() == "APPROVED" -> DW_FROZEN_APPROVED_SENTENCE
    else -> DW_FROZEN_HANDED_ON_SENTENCE
}

/**
 * What each workshop's own screen last read off the server about its sign-off, for the screens
 * reached from it: the frozen sentence for the stage screens, and the sign-off for the report.
 *
 * ── WHY A MEMO AND NOT A ROUTE ARGUMENT OR A SECOND READ ─────────────────────────────────────────
 *
 * The stage screen reads one STAGE from the server, and a stage carries no workshop status; the
 * draft on this device carries none either. The report screen reads the stage LIST, which carries
 * no header either, and reading the single workshop again there would download every stage a
 * second time over a field connection to learn two names and two dates. The index screen already
 * reads that header, and a designer reaches both screens through it — so it leaves the answer here,
 * the shape [DwDictationRun.rememberConsentAnswer] uses for the consent and for the same reason.
 *
 * IN MEMORY ONLY. A cold start forgets it, and then the stage screen shows what it always showed and
 * a report printed on this device carries no sign-off lines until the index is opened again with a
 * connection — the honest answer for a device that has not read one. The server's 403 is what
 * enforces the freeze either way.
 */
object DwReportFreeze {
    private val sentences = ConcurrentHashMap<String, String>()
    private val signOffs = ConcurrentHashMap<String, DwReportSignOff>()

    /**
     * Record what the server's single read said about this workshop. A report that is no longer
     * signed off clears both answers — an approval withdrawn must not go on printing.
     */
    fun rememberRead(workshopId: String, detail: DesignWorkshopDetailDto) {
        if (workshopId.isBlank()) return
        val sentence = dwFrozenSentence(detail.status, detail.handedOnAt)
        if (sentence == null) sentences.remove(workshopId) else sentences[workshopId] = sentence
        val signOff = dwReportSignOffOf(detail)
        if (signOff == null) signOffs.remove(workshopId) else signOffs[workshopId] = signOff
    }

    /** The sentence last read for this workshop, or null when it was open or never read this run. */
    fun sentenceFor(workshopId: String): String? = sentences[workshopId]

    /** The sign-off last read for this workshop, or null when it had none or was never read this run. */
    fun signOffFor(workshopId: String): DwReportSignOff? = signOffs[workshopId]
}

/**
 * Who approved this report and where it went, as the single read carries it. Null fields are real
 * states, not loading ones: nobody has approved it, or an account is no longer named.
 */
data class DwReportSignOff(
    val approvedByName: String? = null,
    /** A role TOKEN (`MINISTRY_ADMIN`), spoken through [dwApproverRoleLabel]. */
    val approvedByRole: String? = null,
    val approvedAt: String? = null,
    val handedOnTo: String? = null,
    val handedOnAt: String? = null,
)

/**
 * The sign-off as the designer's single read serves it, or null when the report has none to print.
 *
 * NULL UNLESS THE REPORT IS APPROVED OR HANDED ON, read off the STATUS and not merely off the
 * columns: an approval withdrawn clears them on the server, but a payload is also printed from
 * whatever this device last decoded, and a report that is back in its designers' hands must not
 * carry "Approved by" on a fresh copy.
 */
fun dwReportSignOffOf(detail: DesignWorkshopDetailDto?): DwReportSignOff? {
    if (detail == null || !dwIsFrozen(detail.status, detail.handedOnAt)) return null
    if (detail.approvedAt.isNullOrBlank()) return null
    return DwReportSignOff(
        approvedByName = detail.approvedByName,
        approvedByRole = detail.approvedByRole,
        approvedAt = detail.approvedAt,
        handedOnTo = detail.handedOnTo,
        handedOnAt = detail.handedOnAt,
    )
}

/**
 * The approver's role as a document names it — the server's `design_workshop_approvals.role_label`.
 *
 * The two roles that may approve have their labels; any other token (an approver whose role has
 * since changed) is printed as its words, title-cased, exactly as the server's `str.title()` prints
 * it, so the two copies of one report cannot name the role two ways. Null only for no role at all.
 */
fun dwApproverRoleLabel(role: String?): String? {
    val token = role?.trim().orEmpty()
    if (token.isEmpty()) return null
    return when (token) {
        "MINISTRY_ADMIN" -> "Ministry Admin"
        "MASTER_ADMIN" -> "Master Admin"
        else -> token.replace('_', ' ').split(' ').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }
}

/**
 * The lines the Certification section prints after its signatures — the server's text, in order.
 *
 *   "Approved by {name} ({role label}) on {date}."
 *   "Handed on to {office} on {date}."        (only once handed on)
 *
 * The date is the report's own `formatReportDate` (`report_builder._format_date`), so a cover date
 * and a sign-off date in one file cannot be written two ways. A blank name drops the name and keeps
 * the fact — "Approved on …" — rather than guessing at whose approval it was; a blank office reads
 * "the office", which is what handing on is.
 */
fun dwReportSignOffLines(signOff: DwReportSignOff?): List<String> {
    if (signOff == null) return emptyList()
    val approvedAt = signOff.approvedAt?.takeIf { it.isNotBlank() } ?: return emptyList()
    val lines = ArrayList<String>(2)
    val name = signOff.approvedByName?.trim().orEmpty()
    val role = dwApproverRoleLabel(signOff.approvedByRole)
    val approvedOn = formatReportDate(approvedAt)
    lines += when {
        name.isEmpty() -> "Approved on $approvedOn."
        role == null -> "Approved by $name on $approvedOn."
        else -> "Approved by $name ($role) on $approvedOn."
    }
    signOff.handedOnAt?.takeIf { it.isNotBlank() }?.let { handedOnAt ->
        val office = signOff.handedOnTo?.trim()?.takeIf { it.isNotEmpty() } ?: "the office"
        lines += "Handed on to $office on ${formatReportDate(handedOnAt)}."
    }
    return lines
}
