package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * The answers recorded against this workshop's own questionnaires, as a document the DEVICE owns.
 *
 * ── WHAT THIS ENDS ────────────────────────────────────────────────────────────────────────────────
 *
 * Every template carries the questionnaire annexure, the office's copy of the report prints it
 * (`report_questionnaires.py`), and the handset printed nothing and said so:
 *
 *     "If a questionnaire is attached to this workshop, the answers recorded against it are not in
 *      this file. This device keeps no copy of them …"
 *
 * That sentence named the real root cause. No file under `data/` held a questionnaire answer at all,
 * so the one copy of the report that is handed to a visiting officer at the close of a workshop — the
 * one written on the phone, in a village, with no signal — was missing the whole of the fieldwork a
 * designer had typed into their own instrument. This file is the copy that was missing.
 *
 * ── WHY THIS IS NOT THE OFFLINE QUEUE `WorkshopRepository` REFUSES TO BUILD ────────────────────────
 *
 * `WorkshopRepository`'s "Custom questionnaires" block states, and keeps, a rule: nothing there falls
 * back to the device, because a cached form cannot know that a question was retired an hour ago, so
 * an offline WRITE would either be refused later (losing the sitting) or re-attached to the wording
 * that replaced it (fabricating evidence). That argument is about ANSWERING, and it is untouched
 * here: this store is READ-ONLY EVIDENCE. Nothing in it is ever sent back to the server, nothing in
 * it is ever offered to `QuestionnaireAnswerScreen` as a form to fill in, and no write path consults
 * it. It exists so a document can PRINT what this phone has already been shown.
 *
 * The shape it keeps is deliberately the REPORT's shape ([DwQuestionnaireItem] mirrors
 * `report_questionnaires.QuestionnaireItem` field for field) and not the form's, so it cannot be
 * mistaken for a form to answer: it has no question ids, no section ids and no answer ids, which is
 * precisely what a save would need.
 *
 * ── THE PROVENANCE THIS KEEPS, AND WHY EACH PIECE IS LOAD-BEARING ─────────────────────────────────
 *
 * Three states have to be told apart, and telling them apart IS the feature — an empty annexure and a
 * missing one are different documents:
 *
 *   * NO CACHE AT ALL ([load] returns null). This device has never read the questionnaire list for
 *     this workshop, so it cannot tell an unattached workshop from an attached one. The report prints
 *     nothing and the designer gets the conditional "if a questionnaire is attached …" warning at the
 *     export screen, exactly as before.
 *   * CACHED, [complete], EMPTY. The server was asked and said no questionnaire is attached. The
 *     report prints NOTHING AT ALL — no heading, no note, no apology — because there is nothing to
 *     apologise for, and no warning is shown either.
 *   * CACHED WITH ITEMS. The annexure is drawn. Any questionnaire whose sittings this device has not
 *     managed to read carries [DwQuestionnaireItem.answersHeld] = false and is NAMED in the document
 *     rather than silently omitted: "attached, and this copy does not have it" is a fact about the
 *     file that its reader is entitled to.
 *
 * [fetchedAt] is recorded and PRINTED for the same reason [DwReferenceList.fetchedAt] is shown: a
 * report built from a fortnight-old copy of the answers is still worth having, and the only thing
 * that makes it safe to hand over is that it says how old it is. Nothing here ever deletes on the
 * strength of it.
 *
 * ── HOW IT DIFFERS FROM [DwReferenceStore], WHICH IT IS OTHERWISE MODELLED ON ──────────────────────
 *
 * One rule is deliberately inverted. [DwReferenceStore.store] refuses to let an EMPTY fetch overwrite
 * a non-empty cache, because an empty reference list is indistinguishable from a permission check
 * that quietly failed and wiping the artisan register off a phone about to lose signal is
 * unrecoverable. Here an empty answer is MEANINGFUL: detaching the questionnaire
 * (`designWorkshopId = null`) is the documented way a designer takes it out of the report, so a store
 * that refused to record "none attached" would keep printing a questionnaire the designer removed —
 * in the document that is hardest to retract. The protection is placed differently instead: only a
 * SUCCESSFUL read writes at all ([designWorkshopQuestionnaires] throws rather than returning empty on
 * a failure), so a refusal or a dead connection leaves whatever is cached exactly where it is.
 */

// --------------------------------------------------------------------------------------
// The model — `report_questionnaires.py`'s three dataclasses, field for field
// --------------------------------------------------------------------------------------

/**
 * One question and what one sitting answered to it — `QuestionnaireAnswer`.
 *
 * [prompt] is the wording the answer was GIVEN UNDER and never the current wording of the question
 * that superseded it. That distinction is the whole point of `QuestionnaireFormQuestion.supersededById`
 * and losing it here would put the rule's own failure case — "… and a ministry report now states
 * there are twelve weavers" — into the copy of the report a handset writes.
 */
@Serializable
data class DwQuestionnaireAnswer(
    val prompt: String = "",
    val answerText: String = "",
    val notes: String = "",
    val sectionCode: String = "",
    val sectionTitle: String = "",
    val isRequired: Boolean = false,
    /** The question was reworded after this answer was given, so it is no longer asked. */
    val isRetired: Boolean = false,
) {
    val hasAnswer: Boolean get() = answerText.isNotBlank()

    /**
     * Whether this row belongs in the document at all — `QuestionnaireAnswer.prints`.
     *
     * An answered question always prints. An unanswered one prints only when it was REQUIRED, where
     * the blank is itself the finding. An unanswered optional question prints nothing, so a
     * forty-question form answered on eight questions is eight rows rather than forty.
     */
    val prints: Boolean get() = hasAnswer || isRequired

    /** `" — ".join(p for p in (section_code, section_title) if p)`. */
    val sectionLabel: String
        get() = listOf(sectionCode, sectionTitle).filter { it.isNotEmpty() }.joinToString(" — ")
}

/** ONE FILLED-IN COPY of a questionnaire — one respondent, one sitting. `QuestionnaireSitting`. */
@Serializable
data class DwQuestionnaireSitting(
    val entryId: String = "",
    val title: String = "",
    val respondentName: String = "",
    /** APP or UPLOAD. Shown, never branched on: see [CustomEntryDto.source]. */
    val source: String = "",
    val notes: String = "",
    val recordedAt: String = "",
    val recordedBy: String = "",
    val answers: List<DwQuestionnaireAnswer> = emptyList(),
) {
    /**
     * How the sitting is titled in the annexure — `QuestionnaireSitting.label`.
     *
     * The respondent first, because that is who the answers are FROM; the sitting's own title only
     * when no respondent was named. A heading reading "Entry 3" tells a reader nothing about whose
     * answers they are about to read.
     */
    val label: String
        get() = respondentName.trim().ifEmpty { title.trim() }
            .ifEmpty { "Sitting ${entryId.take(8)}" }

    val printedAnswers: List<DwQuestionnaireAnswer> get() = answers.filter { it.prints }
    val answeredCount: Int get() = answers.count { it.hasAnswer }
    val hasAnswers: Boolean get() = answeredCount > 0
}

/** One custom questionnaire attached to this workshop, with every sitting recorded against it. */
@Serializable
data class DwQuestionnaireItem(
    val questionnaireId: String = "",
    val title: String = "",
    val description: String = "",
    val version: Int = 1,
    val sourceFilename: String = "",
    /**
     * The questions the form ASKS TODAY — retired ones excluded, exactly as the server counts them.
     *
     * NOT `CustomQuestionnaireDto.questionCount`, which is the wrong number here and looks right.
     * That field is `sum(len(section["questions"]))` over the payload `load_form` built, and the read
     * this store is fed from passes `includeRetired = true` — so it counts retired questions too,
     * while `report_items` counts `sum(1 for _s, q in pairs if q.isActive)`. A reader compares this
     * figure against the instrument in their hand, and the instrument no longer contains them. See
     * [dwQuestionnaireItemOf], which recomputes it rather than copying it.
     */
    val questionCount: Int = 0,
    /**
     * Whether this device has actually READ this questionnaire's sittings, as opposed to merely
     * knowing from the list that it is attached.
     *
     * FALSE IS NOT THE SAME AS "no sittings" and the annexure must never print it as such. A
     * questionnaire that is attached and whose answers this phone could not fetch is named in the
     * document as exactly that; a questionnaire whose answers this phone HAS read and which nobody
     * has filled in is silently absent from the annexure and raised as a warning at the export
     * screen, which is what the server does with it.
     */
    val answersHeld: Boolean = false,
    val sittings: List<DwQuestionnaireSitting> = emptyList(),
) {
    /**
     * The sittings this annexure prints: the ones that actually carry an answer.
     *
     * A sitting created and never filled in is an empty page in an appendix of evidence. It is
     * reported as a warning beside the export instead — see [dwQuestionnaireWarnings].
     */
    val printedSittings: List<DwQuestionnaireSitting> get() = sittings.filter { it.hasAnswers }
    val hasAnswers: Boolean get() = printedSittings.isNotEmpty()
    val answeredTotal: Int get() = printedSittings.sumOf { it.answeredCount }
}

/**
 * What this device holds about one workshop's questionnaires, and how sure it is of it.
 *
 * @property complete this list is EVERY questionnaire the server says is attached to this workshop.
 *   Only a workshop-scoped read can claim it ([designWorkshopQuestionnaires]); a copy assembled from
 *   single-questionnaire reads on the detail screen ([mergeQuestionnaire]) cannot, and the annexure
 *   says so rather than implying it has the whole set.
 * @property fetchedAt ISO-8601, device clock. Printed in the annexure, never used to decide whether
 *   the cache may be used — there is no clock on a phone that can tell "a fortnight old because
 *   nothing changed" from "a fortnight old because there has been no signal for a fortnight".
 */
@Serializable
data class DwQuestionnaireCache(
    val workshopId: String = "",
    val items: List<DwQuestionnaireItem> = emptyList(),
    val complete: Boolean = false,
    val fetchedAt: String = "",
) {
    /** Attached questionnaires with at least one answered sitting — what the annexure prints. */
    val printedItems: List<DwQuestionnaireItem> get() = items.filter { it.hasAnswers }

    /** Attached, and this device does not hold their answers. Named in the document, never dropped. */
    val unreadItems: List<DwQuestionnaireItem> get() = items.filter { !it.answersHeld }

    /** Attached, read, and nobody has answered them. The server's own warning case. */
    val emptyItems: List<DwQuestionnaireItem>
        get() = items.filter { it.answersHeld && !it.hasAnswers }
}

/**
 * What this device can honestly say about the questionnaires attached to one workshop.
 *
 * The report's warnings turn on this and on nothing else. It is a THREE-state answer because two
 * would force a false alarm on one of the two commonest exports: "we hold nothing" and "there is
 * nothing to hold" look identical from inside a phone with no signal, and warning on both puts an
 * apology for a missing questionnaire on the majority of reports, which is how a designer learns to
 * stop reading warnings.
 */
enum class DwQuestionnaireCopy {
    /** Never read for this workshop. Attached and unattached are indistinguishable from here. */
    UNKNOWN,

    /** The server was asked, and said none. Print nothing, warn about nothing. */
    NONE_ATTACHED,

    /** At least one is attached and this device has a copy of the list. The annexure is drawn. */
    ATTACHED,
}

/** Which of the three states [cache] represents. A null cache has never been fetched. */
fun dwQuestionnaireCopy(cache: DwQuestionnaireCache?): DwQuestionnaireCopy = when {
    cache == null -> DwQuestionnaireCopy.UNKNOWN
    cache.items.isNotEmpty() -> DwQuestionnaireCopy.ATTACHED
    cache.complete -> DwQuestionnaireCopy.NONE_ATTACHED
    // Empty AND not complete: assembled from single-form reads that found nothing, which is not an
    // answer about the workshop at all. Treated as never having asked.
    else -> DwQuestionnaireCopy.UNKNOWN
}

// --------------------------------------------------------------------------------------
// Turning the wire shape into the report shape
// --------------------------------------------------------------------------------------

/**
 * One `GET /questionnaires/{id}` payload as the annexure reads it — the port of
 * `questionnaire_forms.report_items`' inner loop.
 *
 * **[dto] MUST HAVE BEEN FETCHED WITH `includeRetired = true`.** `load_form` drops retired sections
 * and retired questions when it is false, and `report_items` — which the office's copy is built from
 * — applies no such filter. A cache fed from the ANSWER screen's read would therefore be missing
 * every answer given under a wording that has since been reworded, and the two copies of one report
 * would disagree about the fieldwork with nothing in either saying so. [mergeQuestionnaire] refuses
 * a payload it cannot trust for exactly this reason.
 *
 * NO SORTING HAPPENS HERE and none is needed: `load_form` returns the sections ordered
 * `(sortOrder, createdAt)` with each section's own questions bucketed under it in the same order,
 * and the entries ordered `createdAt asc`. Walking the payload as it arrived reproduces
 * `report_items`' ordering exactly — including the part that matters, which is that questions are
 * walked SECTION BY SECTION. The server's own comment records what a flat ordering did to the
 * document: one single-row table per question, A/B/A/B down the page, in the appendix of evidence
 * handed to a ministry officer.
 */
fun dwQuestionnaireItemOf(dto: CustomQuestionnaireDto): DwQuestionnaireItem {
    // (section, question) in print order, built once — a sitting is then a lookup against it, so two
    // sittings of the same form cannot come out in two different orders. Side by side in one
    // document, that is what makes them comparable.
    val pairs = dto.sections.flatMap { section -> section.questions.map { section to it } }
    return DwQuestionnaireItem(
        questionnaireId = dto.id,
        title = dto.title,
        description = dto.description.orEmpty(),
        version = dto.version,
        sourceFilename = dto.sourceFilename.orEmpty(),
        questionCount = pairs.count { (_, question) -> question.isActive },
        answersHeld = true,
        sittings = dto.entries.map { entry ->
            val recorded = entry.answers.associateBy { it.questionId }
            DwQuestionnaireSitting(
                entryId = entry.id,
                title = entry.title,
                respondentName = entry.respondentName.orEmpty(),
                source = entry.source.orEmpty(),
                notes = entry.notes.orEmpty(),
                recordedAt = entry.createdAt.orEmpty(),
                recordedBy = entry.createdByName.orEmpty(),
                answers = pairs.map { (section, question) ->
                    val answer = recorded[question.id]
                    DwQuestionnaireAnswer(
                        prompt = question.prompt,
                        answerText = answer?.answerText.orEmpty(),
                        notes = answer?.notes.orEmpty(),
                        sectionCode = section.code,
                        sectionTitle = section.title,
                        isRequired = question.isRequired,
                        isRetired = !question.isActive,
                    )
                },
            )
        },
    )
}

/** The list row for a questionnaire whose sittings this device has NOT been able to read. */
private fun dwUnreadItemOf(summary: CustomQuestionnaireSummaryDto) = DwQuestionnaireItem(
    questionnaireId = summary.id,
    title = summary.title,
    description = summary.description.orEmpty(),
    version = summary.version,
    sourceFilename = summary.sourceFilename.orEmpty(),
    // No questionCount: the summary does not carry one, and printing 0 would read as "a form with no
    // questions" rather than as "we have not opened it". The index block prints "—" for 0.
    answersHeld = false,
)

// --------------------------------------------------------------------------------------
// Reading it off the server
// --------------------------------------------------------------------------------------

/**
 * Every questionnaire attached to one design workshop, with its sittings, ready to cache and print.
 *
 * ONE LIST REQUEST PLUS ONE PER QUESTIONNAIRE, which is the shape the API offers: the list carries
 * summaries and only `GET /questionnaires/{id}` carries the sittings. Almost every workshop has zero
 * or one, so this is almost always one or two requests.
 *
 * THE LIST WALK IS THE PROVEN ONE ([walkPagedListing]), not a hand-written page loop, and not a
 * `pageSize=500` read: a designer with a season of their own forms behind a colleague's is exactly
 * the case `QuestionnaireListing.kt` was written to end, and a report that silently stopped at page
 * one would reintroduce it in the document rather than on a screen where it could be noticed.
 *
 * ORDERED `createdAt` ASCENDING BEFORE IT IS RETURNED, and that single line is a real divergence
 * closed rather than tidiness. `GET /questionnaires` orders `createdAt desc` (newest first, which is
 * what a list screen wants) and `report_items` orders `createdAt asc`. Left alone, a workshop with
 * two attached questionnaires would print them in one order at the office and the opposite order on
 * the phone, and an officer comparing the two copies has no way to read that as anything but an
 * altered document.
 *
 * A QUESTIONNAIRE WHOSE FORM CANNOT BE READ IS KEPT, marked [DwQuestionnaireItem.answersHeld] =
 * false, rather than dropped. Signal dies mid-walk in a courtyard; dropping the row would turn "we
 * could not fetch this one" into "this one is not attached", which is the single most damaging thing
 * this store could get wrong — it is the difference between a report that admits a gap and one that
 * denies it.
 *
 * A FAILURE OF THE LIST ITSELF THROWS, and must. An empty list is written to the cache as the
 * positive fact "nothing is attached to this workshop", so a dead connection that returned empty
 * would erase a questionnaire from every future offline export.
 */
suspend fun WorkshopRepository.designWorkshopQuestionnaires(
    workshopId: String,
): DwQuestionnaireCache {
    val listing: PagedListing<CustomQuestionnaireSummaryDto> = walkPagedListing(
        idOf = { it.id },
    ) { page, size ->
        customQuestionnaires(
            page = page,
            pageSize = size,
            designWorkshopId = workshopId,
            // Deactivation is what this API has instead of a delete, and `report_items` refuses to
            // print a deactivated questionnaire for the reason it gives: the report is a list, and
            // this would be the one surface where a record the designer retired came back.
            activeOnly = true,
        )
    }
    val summaries = listing.items.sortedBy { it.createdAt.orEmpty() }
    val items = summaries.map { summary ->
        runCatching { customQuestionnaire(summary.id, includeRetired = true) }
            .map { dwQuestionnaireItemOf(it) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                dwUnreadItemOf(summary)
            }
    }
    return DwQuestionnaireCache(
        workshopId = workshopId,
        items = items,
        // `truncated` is the walk admitting it never reached the end of the list, so the annexure
        // must not claim to know the whole set either.
        complete = !listing.truncated,
        fetchedAt = Instant.now().toString(),
    )
}

// --------------------------------------------------------------------------------------
// The store
// --------------------------------------------------------------------------------------

object DwQuestionnaireStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** One lock for the whole store, for the reason [DwReferenceStore]'s is coarse: write-and-rename. */
    private val mutex = Mutex()

    /** A process-lifetime mirror. Always written AFTER the file, so a kill between them loses nothing. */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, DwQuestionnaireCache>()

    private fun rootDir(context: Context): File =
        File(context.filesDir, "dw-questionnaires").apply { mkdirs() }

    private fun safeName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').take(80).ifBlank { "unnamed" }

    private fun fileFor(context: Context, workshopId: String): File =
        File(rootDir(context), "${safeName(workshopId)}.json")

    /**
     * What this device holds for one workshop, or null when it has never read the list.
     *
     * Null and an empty [DwQuestionnaireCache.items] are DIFFERENT ANSWERS and the annexure draws
     * them differently — see the header. Null is "we have never asked"; complete-and-empty is "the
     * server says nothing is attached", which is the correct and final answer for most workshops.
     */
    suspend fun load(context: Context, workshopId: String): DwQuestionnaireCache? {
        if (workshopId.isBlank()) return null
        memory[workshopId]?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, workshopId)
                if (!file.exists()) return@withLock null
                val text = runCatching { file.readText() }.getOrNull()
                if (text.isNullOrBlank()) return@withLock null
                // Deleted rather than quarantined when this build cannot read it, exactly as
                // [DwReferenceStore] does and for the same reason: this is a COPY of something the
                // server still holds, so the cheap recovery is to drop it and re-read. A draft would
                // never be treated this way — that is irreplaceable fieldwork; this is not.
                runCatching { json.decodeFromString(DwQuestionnaireCache.serializer(), text) }
                    .onSuccess { memory[workshopId] = it }
                    .onFailure { runCatching { file.delete() } }
                    .getOrNull()
            }
        }
    }

    /**
     * Replace what this device holds for one workshop.
     *
     * AN EMPTY, COMPLETE CACHE OVERWRITES A FULL ONE, which is the one place this store deliberately
     * departs from [DwReferenceStore.store]. See the header: detaching the questionnaire is the
     * documented way to take it out of the report, and refusing to record that would leave the phone
     * printing a questionnaire the designer removed. Only a successful read reaches here at all.
     */
    suspend fun store(context: Context, cache: DwQuestionnaireCache): DwQuestionnaireCache {
        val workshopId = cache.workshopId
        if (workshopId.isBlank()) return cache
        val stamped =
            if (cache.fetchedAt.isNotBlank()) cache else cache.copy(fetchedAt = Instant.now().toString())
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, workshopId)
                val temp = File(file.parentFile, "${file.name}.tmp")
                runCatching {
                    // Write-then-rename, the discipline draft.json uses. A process killed mid-write
                    // must not leave a half-written file where a whole one was — [load] deletes what
                    // it cannot parse, so the designer would lose a copy they had, offline, to a
                    // write that was only a refresh.
                    temp.writeText(json.encodeToString(DwQuestionnaireCache.serializer(), stamped))
                    if (file.exists()) file.delete()
                    temp.renameTo(file)
                }.onFailure { runCatching { temp.delete() } }
            }
        }
        memory[workshopId] = stamped
        return stamped
    }

    /**
     * Fold ONE questionnaire the designer just opened into whatever this device already holds.
     *
     * This is the cheap half of the feature and the one that costs no extra request: the designer is
     * standing on [QuestionnaireDetailScreen] with the sittings on screen, which means the answers
     * are already in hand and already paid for. Writing them down here is what makes an export three
     * days later, in a courtyard with no signal, carry the fieldwork.
     *
     * IT CANNOT MAKE THE LIST COMPLETE, and does not claim to. One questionnaire says nothing about
     * whether another is attached, so [DwQuestionnaireCache.complete] is only ever set by the
     * workshop-scoped read, and an incomplete cache prints a line saying so.
     *
     * A payload with no `designWorkshopId` is IGNORED rather than filed under some guess: an
     * unattached questionnaire is not part of any workshop's report, and detaching one is how a
     * designer takes it out.
     */
    suspend fun mergeQuestionnaire(
        context: Context,
        dto: CustomQuestionnaireDto,
    ): DwQuestionnaireCache? {
        val workshopId = dto.designWorkshopId?.takeIf { it.isNotBlank() } ?: return null
        // A deactivated questionnaire is not printed — `report_items` excludes it — so caching one
        // would only teach the annexure to disagree with the office's copy.
        if (!dto.isActive) return null
        val item = dwQuestionnaireItemOf(dto)
        val existing = load(context, workshopId)
        val kept = existing?.items.orEmpty().filter { it.questionnaireId != item.questionnaireId }
        // Completeness SURVIVES a refresh of a questionnaire the list already knew about, and is lost
        // the moment this adds one it did not. The second case is a questionnaire attached since the
        // list was last read, which is proof the list is out of date — and a cache that kept claiming
        // to be the whole set would print an annexure asserting there is nothing else.
        val stillComplete = existing != null &&
            existing.complete &&
            kept.size == existing.items.size - 1
        return store(
            context,
            DwQuestionnaireCache(
                workshopId = workshopId,
                // Appended in the order they were first seen; the workshop-scoped read reorders the
                // whole set to `createdAt asc` next time it runs. A questionnaire merged in from the
                // detail screen has never been listed, so there is no createdAt here to sort on.
                items = kept + item,
                complete = stillComplete,
                fetchedAt = Instant.now().toString(),
            ),
        )
    }
}

// --------------------------------------------------------------------------------------
// What the designer is told
// --------------------------------------------------------------------------------------

/**
 * What to say beside the export buttons about the questionnaires attached to this workshop.
 *
 * The first entry is `report_questionnaires.questionnaire_warnings` word for word — a questionnaire
 * attached and never answered is silently absent from the annexure, and silence is exactly wrong
 * there: the designer chose that form for this workshop on purpose and would otherwise have to
 * notice the shortfall themselves in a sixty-page document.
 *
 * The second has no server counterpart because the condition cannot arise on a server: a
 * questionnaire this device knows is attached and whose answers it has not got. It is phrased as
 * something the designer can ACT on — open it once with a connection — because they can, and because
 * they are usually the only person who will be anywhere near this workshop with signal again.
 */
fun dwQuestionnaireWarnings(cache: DwQuestionnaireCache?): List<String> {
    if (cache == null) return emptyList()
    val said = ArrayList<String>()
    val empty = cache.emptyItems
    if (empty.isNotEmpty()) {
        val names = empty.take(3).joinToString(", ") { it.title.ifBlank { it.questionnaireId } }
        val more = if (empty.size > 3) "…" else ""
        said += "${empty.size} questionnaire(s) attached to this workshop have no recorded answers " +
            "and were left out of the questionnaire annexure ($names$more)."
    }
    val unread = cache.unreadItems
    if (unread.isNotEmpty()) {
        val names = unread.take(3).joinToString(", ") { it.title.ifBlank { it.questionnaireId } }
        val more = if (unread.size > 3) "…" else ""
        said += "${unread.size} questionnaire(s) attached to this workshop are named in the annexure " +
            "without their answers, because this device has no copy of them ($names$more). Open the " +
            "questionnaire on this phone once while you have a connection and export again; the " +
            "office's copy of this report carries them either way."
    }
    return said
}
