package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A SAVE THE SERVER PARTLY REFUSED, TURNED INTO SOMETHING A DESIGNER CAN ACT ON.
 *
 * ── THE DEFECT THIS CLOSES ───────────────────────────────────────────────────────────────────────
 *
 * [StageSaveResultDto.errors] has been decoded off every stage save this app has ever made and read
 * by NOTHING in `main`. `save_stage` answers 200 with a per-field error map — a bound tightened on
 * the web, a MONEY field typed "65OO", anything `coerce_value` cannot read — writes every OTHER field
 * on the entry, and PUTS THE PREVIOUSLY STORED VALUE BACK under the refused key
 * (`design_workshops.py`, "A REJECTED FIELD MUST NOT DESTROY THE VALUE ALREADY STORED UNDER IT"). So
 * the phone went on showing the typed text, the repository went on holding the old value, the stage
 * reported itself synced, and nothing on any screen said the edit had not landed. The designer found
 * out from an officer, or never.
 *
 * ── WHAT A REFUSAL MEANS, IN FOUR SENTENCES THAT MUST ALL STAY TRUE ──────────────────────────────
 *
 *  1. WHICH question. Not "some answers were not saved" — a designer who has driven home from a
 *     cluster cannot act on that. Every refusal is addressed to the box that produced it: the entity,
 *     the ROW where there is one, and the field, resolved to the label the form draws.
 *  2. WHAT THE REPOSITORY NOW HOLDS. The save response does not carry it — measured: `save_stage`
 *     returns `stageKey, saved, created, updated, removed, errors, droppedKeys, droppedCustomKeys,
 *     completeness, transcriptionsQueued, schemaVersion, customSchemaVersion` and no stored values at
 *     all — so it is [DwHeld.UNRECORDED] until a `GET .../stages/{key}` says otherwise, IN THAT WORD,
 *     and [dwHoldingsFrom] is what fills it in from that read. An unmeasured thing says it is
 *     unmeasured; it does not guess that the server kept what the designer typed, which is the one
 *     guess that would make this whole surface a second way of lying.
 *  3. NOTHING TYPED IS DISCARDED. The refused text stays in the draft and on the screen, because the
 *     designer may be somewhere with no signal to retype it under and the server's copy is not
 *     reachable from there. This module never proposes a value; it only reports.
 *  4. THE SAVE DID NOT FAIL. It usually succeeded for everything else on the stage, and saying
 *     otherwise sends somebody back over twenty fields that are already safe.
 *
 * ── KEYED BY THE POSITION IN THE ARRAY THAT WAS SENT, WHICH IS THE TRAP ──────────────────────────
 *
 * `save_stage` files a singleton's errors under the bare entity key and a collection row's under
 * `` `${entityKey}[${index}]` `` where INDEX IS THE ENTRY'S INDEX IN `payload.entries` — not the row's
 * ordinal and not its position within its collection. A stage sending a singleton and three tools
 * files them under `tool[1]`, `tool[2]`, `tool[3]`. Decoding that as a row number puts every message
 * on the wrong row, and on the LAST row it puts it on no row at all. The payload is therefore passed
 * in and walked, exactly as `buildStageEntries` and the web's stage page do it, so both surfaces
 * decode one error map against one ordering.
 *
 * ── AND A KEY THIS BUILD HAS NO CONTROL FOR IS SAID OUT LOUD ─────────────────────────────────────
 *
 * `_custom` is the ordinary case of an entity that is not in the registry — it is a row of its own,
 * one per (workshop, stage) — and a retired or newly-added custom question is the ordinary case of a
 * field key this build cannot draw. Dropping either would mean the one refusal a designer most needs
 * to see (the question they added themselves, this morning, on the web) is the one that vanishes.
 * [DwStageRefusal.drawn] carries the distinction and the sentence changes rather than the message
 * being swallowed.
 *
 * ── AND ONE KEY IN THE MAP IS NOT A QUESTION AT ALL ──────────────────────────────────────────────
 *
 * `save_stage` gained a version guard on 2026-09-03, and with it a refusal about the WHOLE ROW:
 * somebody else saved that row while this payload was in flight, so the row was left alone and the
 * repository has one sentence about it. It rides the same `errors` map — under [DW_ROW_REFUSAL_KEY],
 * which every other reader of this map would take for a field key. Read that way the bullet named a
 * question no designer has ever seen and then blamed this build for having no box for it. See
 * [DwStageRefusal.isRowLevel] for the four places that branch on it.
 */
data class DwStageRefusal(
    /** The registry entity, or [CUSTOM_ENTITY_KEY] for the designer's own questions. */
    val entityKey: String,
    /** The row's position within its collection as the form draws it, or null for a singleton. */
    val rowIndex: Int?,
    /**
     * The row's `_clientKey` — the identity `save_stage` matches a row on — where the entry carried one.
     *
     * Held so [dwHoldingsFrom] can find the same row in a LATER read rather than trusting position.
     * The bucket's rows come back ordered by the server's stored ordinal, which is usually the order
     * that was sent and is not guaranteed to be: a row added on the web between the save and the read
     * shifts every position after it, and "what the repository holds" would then quote the wrong row's
     * value — a worse failure than saying UNRECORDED, because it is confidently wrong.
     */
    val rowKey: String? = null,
    val fieldKey: String,
    /**
     * The question as this build names it, or the bare key when it has no control for it.
     *
     * For a row-level refusal ([isRowLevel]) it is the ENTITY as the form titles it, because there
     * is no question — see `dwRowRefusalLabel`.
     */
    val label: String,
    /** The server's own sentence. Never rewritten — this repository's errors are sentences, not codes. */
    val message: String,
    /**
     * Whether this build can draw a control for this question at all.
     *
     * For a row-level refusal it answers the same thing one rung up — whether this build knows the
     * entity — and nothing reads it, because [sentence] stops before the clause it decides.
     */
    val drawn: Boolean,
    /** What the repository holds under this key now. [DwHeld.UNRECORDED] until a read says. */
    val held: DwHeld = DwHeld.unrecorded(),
) {
    /** Where the message belongs on screen. Singletons and `_custom` have no row. */
    val address: String
        get() = if (rowIndex == null) entityKey else "$entityKey[$rowIndex]"

    /**
     * THE REPOSITORY REFUSED THE WHOLE ROW, NOT ONE OF ITS QUESTIONS — see [DW_ROW_REFUSAL_KEY].
     *
     * Four things branch on it, and each of them is a sentence that is otherwise false:
     *
     *  * [sentence] stops on the repository's own words. The two clauses that normally follow are
     *    both about a FIELD — what is held under the key, and which box the typed text sits in — and
     *    neither has anything to refer to here.
     *  * [DwStageRefusalReport.byAddress] leaves it out, because no form draws a box keyed `_row`
     *    and a message handed to the form that the form cannot draw is a message that was dropped.
     *  * [dwHoldingsFrom] does not measure it: no row carries `_row`, so a stage read's silence
     *    about it is not a fact about an answer, and [DwHeldState.NOTHING] would be a fabrication.
     *  * [DwStageRefusalReport.needsRead] therefore does not ask for that read at all.
     *
     * IT IS STILL ONE REFUSAL AND IT IS STILL COUNTED. [DwStageRefusalReport.count] includes it, and
     * `recordStageSent` sums the same bucket at one — the row was not stored, and a stage that said
     * "saved" over a row somebody else won is the exact failure this whole file exists to end.
     * (2026-09-03)
     */
    val isRowLevel: Boolean
        get() = fieldKey == DW_ROW_REFUSAL_KEY

    /**
     * THE IDENTITY A MEASURED HOLDING MAY BE CARRIED ON — see [dwCarryHoldings].
     *
     * [rowKey] first and [address] only where there is none, and the difference is a wrong number in
     * front of a designer. Keyed on the address alone, a row's measured holding belonged to its
     * POSITION: delete row 2 of a costing table and row 3 slides up into `costLine[2]`, matches the
     * measurement taken against the row that is now gone, and inherits it — while
     * [DwStageRefusalReport.needsRead] goes false on the strength of that match, so no read is ever
     * made to correct it. The card then quotes one row's stored amount against another row's line,
     * confidently, for as long as the refusal stands.
     *
     * Keyed on the row's own `_clientKey` the same move MISSES, the refusal stays
     * [DwHeldState.UNRECORDED], `needsRead` goes true and one read answers it correctly. That is the
     * same conservative answer `a refusal that moved to a different row is measured again rather than
     * assumed` already demands for a changed address, and the same rule [dwHoldingsFrom] follows when
     * it looks the row up in a later read: the key the server matches a row on, never its position.
     *
     * A singleton, `_custom`, and any row whose entry carried no client key fall back to [address],
     * which is all the identity they have — and for the first two it is a complete identity.
     */
    val carryKey: String
        get() = rowKey ?: address

    /**
     * One line for a list: the question, what became of it, and where what you typed is now.
     *
     * ── WHY THE LAST CLAUSE IS BUILT HERE AND NOT INSIDE [DwHeld] ────────────────────────────────
     *
     * It used to be, and the two halves then contradicted each other inside one bullet. [DwHeld]'s
     * sentences each ended "…and is still in the box above", and this function appended "This copy of
     * the app has no box for that question" straight after it whenever [drawn] was false. A designer
     * reading the one refusal they are least able to work out for themselves — a question their own
     * build cannot draw — was told to look in a box and then told the box does not exist, in the same
     * breath. Reachable without any custom section at all: a draft written by a newer build carries a
     * key this build's registry does not declare (the whole point of `Map<String, JsonElement>`), the
     * server knows it, validates it and refuses it, and `registryField` here is null.
     *
     * So [DwHeld] now says only what the REPOSITORY holds, which is the one thing it can know, and
     * WHERE THE TYPED TEXT IS is decided here, where [drawn] is.
     */
    val sentence: String
        get() = buildString {
            append(if (rowIndex == null) label else "$label (row ${rowIndex + 1})")
            append(": ")
            append(message.trim())
            if (!message.trim().endsWith('.')) append('.')
            // A ROW-LEVEL REFUSAL ENDS ON THE REPOSITORY'S OWN SENTENCE, ADDRESSED TO THE ROW.
            //
            // It arrives complete — one line that names the state and the next move — and is
            // rendered verbatim rather than wrapped, because the two clauses below are both about a
            // FIELD. "What it holds under this question" has no question to be about, and "still in
            // the box above" would point at a box that does not exist. The card's heading already
            // promises nothing typed was thrown away, which is the reassurance those clauses carry.
            // See [isRowLevel]. (2026-09-03)
            if (isRowLevel) return@buildString
            append(' ')
            append(held.sentence)
            append(" What you typed is still on this phone")
            if (drawn) {
                append(" and is still in the box above.")
            } else {
                append(
                    ", but this copy of the app has no box for that question, so it is not shown in " +
                        "the form above — it was answered elsewhere, or the question has been " +
                        "changed since this phone last read this workshop."
                )
            }
        }
}

/**
 * THE RESERVED KEY A ROW-LEVEL REFUSAL ARRIVES UNDER — `save_stage`'s `STAGE_ROW_CONFLICT_KEY`.
 *
 * WHY IT RIDES THE ERROR MAP AT ALL, which is the decision this constant inherits. A version
 * conflict could have been a new key on the save response, and a new response key is only worth its
 * cost when a client renders it — which no build already in a cluster does. Filed inside `errors` it
 * reaches the handsets that shipped before it existed, on a surface both clients already draw and
 * both already count, so a fielded 0.0.7 shows it as an unnamed refusal rather than as nothing.
 *
 * WHY AN UNDERSCORE. `errors` is `{scope: {field: message}}` and every other entry in it names a
 * real field. The underscore is this protocol's own mark for "not workshop data" — `_clientKey`,
 * `_entryId`, `_ordinal`, `_custom` — and [CUSTOM_KEY_PATTERN] refuses a designer's own question any
 * first character but a lower-case letter, so a custom key can never collide with it.
 *
 * Kept in step with `backend/app/services/design_workshops.py:STAGE_ROW_CONFLICT_KEY` BY HAND: it is
 * a wire constant on both sides and there is no generator between them. (2026-09-03)
 */
const val DW_ROW_REFUSAL_KEY: String = "_row"

/**
 * WHAT THE REPOSITORY HOLDS UNDER A REFUSED KEY — three states, and the first is a word.
 *
 * Modelled the way [DwCustomCopy] and `DwPackState` are, and for the identical reason: "the server
 * holds nothing" and "this phone has not asked" are different facts with different remedies, and a
 * type that can only say "" collapses them into the one that reads as reassurance. The save response
 * carries no stored values, so [UNRECORDED] is the honest state immediately after a refusal and stays
 * that way for a designer with no signal to re-read under.
 */
data class DwHeld(val state: DwHeldState, val text: String) {

    /**
     * WHAT THE REPOSITORY HOLDS, AND NOTHING ABOUT WHERE THE TYPED TEXT IS.
     *
     * That second half used to live here, ending every one of these three with "…and is still in the
     * box above" — a claim about the FORM, which this type cannot see and which is false for a
     * question this build has no control for. It is [DwStageRefusal.sentence]'s to make, because
     * [DwStageRefusal.drawn] is what decides it.
     */
    val sentence: String
        get() = when (state) {
            DwHeldState.UNRECORDED -> "The repository kept whatever it already held for this " +
                "question and did not send it back, so what it holds is UNRECORDED here."
            DwHeldState.NOTHING -> "The repository holds no answer to this question."
            DwHeldState.HOLDS -> "The repository still holds: “$text”."
        }

    companion object {
        fun unrecorded(): DwHeld = DwHeld(DwHeldState.UNRECORDED, DW_UNRECORDED)
        fun nothing(): DwHeld = DwHeld(DwHeldState.NOTHING, "")
        fun holding(text: String): DwHeld =
            if (text.isBlank()) nothing() else DwHeld(DwHeldState.HOLDS, text)
    }
}

enum class DwHeldState { UNRECORDED, NOTHING, HOLDS }

/** The word this repository uses for a thing it has not measured. Never "" and never "unknown". */
const val DW_UNRECORDED: String = "UNRECORDED"

/**
 * Everything one save came back refusing, ready to be drawn.
 *
 * [unplaced] is the honesty valve. A scope key the payload's own ordering cannot account for — an
 * index past the end of the entries, an entity this stage does not declare — is REPORTED rather than
 * dropped, because the alternative is a refusal that exists on the server and nowhere else.
 */
data class DwStageRefusalReport(
    val refusals: List<DwStageRefusal> = emptyList(),
    val unplaced: List<String> = emptyList(),
    /**
     * `droppedCustomKeys` from the same response: this workshop's own sections no longer ask these
     * questions, so the answers this phone holds for them were NOT stored.
     *
     * IT LIVES HERE BECAUSE THE CARD MAKES A CLAIM ABOUT IT. [heading] said "Everything else in this
     * stage was saved" unconditionally, and the same response that carried the refusals also carried
     * this list — a set of answers that were not saved either, for a completely different reason, and
     * which nothing on the stage screen mentioned at all. `recordStageSent` wrote a sentence about
     * them onto the sync status and the stage the designer was told to open said the opposite.
     *
     * Its own clause rather than a refusal, because the remedy is not the same: a refused answer needs
     * CORRECTING, and this needs the phone's copy of the sections REFRESHED — one tap, with a
     * connection. Folding it into the refusal count would send a designer to retype an answer that was
     * never wrong.
     */
    val droppedCustomKeys: List<String> = emptyList(),
    /**
     * When the refused save happened, for a report restored off disk. Null while it is this
     * composition's own — the designer just pressed save and does not need to be told when.
     */
    val recordedAt: String? = null,
) {
    val isEmpty: Boolean
        get() = refusals.isEmpty() && unplaced.isEmpty() && droppedCustomKeys.isEmpty()

    val count: Int get() = refusals.size + unplaced.size

    /**
     * Whether anything here would be answered by reading the stage back. See [dwCarryHoldings].
     *
     * A ROW-LEVEL REFUSAL NEVER ASKS FOR THE READ. There is no key for the read to answer about, so
     * a contested row would spend one request per debounced save — the exact cost [dwCarryHoldings]
     * exists to avoid — to come back with nothing to fill in. See [DwStageRefusal.isRowLevel].
     * (2026-09-03)
     */
    val needsRead: Boolean
        get() = refusals.any { !it.isRowLevel && it.held.state == DwHeldState.UNRECORDED }

    /**
     * Per-address, per-field, for the form to mark the boxes. `address` is `entity` or `entity[row]`.
     *
     * ROW-LEVEL REFUSALS ARE DELIBERATELY NOT IN HERE. Every consumer of this map looks a message up
     * BY FIELD KEY to mark one control, and nothing on any form is keyed `_row` — so a message
     * handed over here would be silently dropped by the surface that received it, which is the same
     * defect this module was built to end, one level in. They are drawn from [refusals] instead: on
     * the card, and by the row card itself. See [DwStageRefusal.isRowLevel]. (2026-09-03)
     */
    val byAddress: Map<String, Map<String, String>>
        get() = refusals.filterNot { it.isRowLevel }
            .groupBy { it.address }
            .mapValues { (_, list) -> list.associate { it.fieldKey to it.message } }

    /**
     * The heading. It says what happened, what did NOT happen, and where the rest of the work is.
     *
     * "everything else in this stage was saved" is load-bearing and is the sentence the web settled
     * on ("Some answers were not accepted… everything else was saved"). A refusal is not a failed
     * save, and telling a designer their save failed sends them back over twenty fields that landed.
     *
     * ── THREE CLAUSES THAT ARE NOW CONDITIONAL, AND WERE ASSERTED ────────────────────────────────
     *
     *  * "and kept what it already held for them" was said whatever [DwHeld] state the refusals were
     *    in. It is the repository's documented behaviour — `save_stage` deliberately puts the stored
     *    value back — but "kept what it already held" reads as *your previous answer is safe there*,
     *    and for a question the repository holds NOTHING for that is a promise about an empty box. So
     *    it is claimed only where a read has actually found a value under at least one refused key;
     *    where the read came back silent it says so instead, and where nothing has been read it says
     *    nothing, because [DW_UNRECORDED] is the honest state and each bullet below already carries it.
     *  * "Everything else in this stage was saved" is false when [droppedCustomKeys] is not empty —
     *    the same response said those answers were not stored either.
     *  * The whole sentence assumed there WERE refusals. A response can carry `droppedCustomKeys` and
     *    no errors at all, and the card is now drawn for it.
     */
    val heading: String
        get() = buildString {
            val holds = refusals.count { it.held.state == DwHeldState.HOLDS }
            val nothing = refusals.count { it.held.state == DwHeldState.NOTHING }
            val them = if (count == 1) "it" else "them"
            if (count > 0) {
                append("The repository refused $count of your answer${if (count == 1) "" else "s"}")
                when {
                    holds > 0 -> append(" and kept what it already held for $them")
                    // Measured, and what it measured was an empty box. Said, because a designer who
                    // is told their previous answer is safe there will not go looking for it.
                    nothing == refusals.size && refusals.isNotEmpty() ->
                        append(", and holds no previous answer under ${if (count == 1) "it" else "any of them"}")
                }
                append(". ")
            }
            if (droppedCustomKeys.isEmpty()) {
                if (count > 0) append("Everything else in this stage was saved. ")
            } else {
                val keys = droppedCustomKeys.size
                append(
                    "Everything else in this stage was saved except " +
                        "$keys of this workshop's own question${if (keys == 1) "" else "s"} " +
                        "(${droppedCustomKeys.joinToString(", ").take(160)}), which the sections no " +
                        "longer ask — so the answer${if (keys == 1) "" else "s"} this phone holds " +
                        "for ${if (keys == 1) "it" else "them"} " +
                        "${if (keys == 1) "was" else "were"} not stored. The sections have been " +
                        "edited since this phone last read them — open this workshop once with a " +
                        "connection. "
                )
            }
            if (count > 0) {
                append("Nothing you typed has been thrown away — it is still on this phone, and in ")
                append("the boxes below.")
            } else {
                append("Nothing you typed has been thrown away — it is still on this phone.")
            }
            // DATED ONLY WHEN IT IS NOT THIS COMPOSITION'S OWN. A designer who has just pressed save
            // does not need to be told when; a designer who left the stage and came back on the app's
            // own instruction is looking at what the repository said THEN, and is owed the time.
            recordedAt?.let { append(" Recorded when this stage was last saved to the repository, at $it.") }
        }
}

/**
 * Decode one save response's error map against the payload that produced it.
 *
 * [entries] MUST be the entries that were actually sent, in the order they were sent — see the header.
 * [customFields] is this device's copy of the workshop's own questions for this stage, used only to
 * put a label on a refusal and to answer whether this build could draw it.
 */
fun dwDecodeStageRefusals(
    spec: StageDto?,
    entries: List<StageEntryBody>,
    errors: Map<String, JsonElement>,
    customFields: List<DwCustomFieldDto> = emptyList(),
): DwStageRefusalReport = dwDecodeStageRefusalsFromSent(
    spec = spec,
    sent = entries.map(::dwSentEntryOf),
    errors = errors,
    customFields = customFields,
)

/**
 * The decode itself, against the ADDRESSING of the payload rather than the payload.
 *
 * ONE DECODER AND NOT TWO. The save path has the entries in hand and calls the wrapper above; the
 * screen re-opening a stage has only what [DwStageRefusalRecord] stored and calls this. Both walk the
 * same list in the same order, so a refusal decoded on the wire and the same refusal decoded off disk
 * an hour later land on the same box — which they could not be trusted to do if the two paths each had
 * their own reading of `entity[i]`.
 *
 * NOT AN OVERLOAD, though it reads like one should serve. `List<StageEntryBody>` and
 * `List<DwSentEntry>` both erase to `java.util.List`, so a second `dwDecodeStageRefusals` taking the
 * other list is the SAME JVM signature and the compiler rejects the file outright — "Platform
 * declaration clash", on both the function and the `$default` bridge its default argument generates.
 * The distinct name is what the JVM requires; the pair is still one decoder and one wrapper.
 */
fun dwDecodeStageRefusalsFromSent(
    spec: StageDto?,
    sent: List<DwSentEntry>,
    errors: Map<String, JsonElement>,
    customFields: List<DwCustomFieldDto> = emptyList(),
): DwStageRefusalReport {
    if (errors.isEmpty()) return DwStageRefusalReport()

    val customByKey = customFields.associateBy { it.key }
    val refusals = ArrayList<DwStageRefusal>()
    val unplaced = ArrayList<String>()

    errors.forEach { (scope, payload) ->
        val fields = fieldMessages(payload)
        if (fields.isEmpty()) {
            unplaced += "$scope: ${DwValues.text(payload).ifBlank { "refused, with no reason given" }}"
            return@forEach
        }
        val match = SCOPED_ROW.matchEntire(scope)
        val entityKey: String
        val rowIndex: Int?
        var rowKey: String? = null
        if (match == null) {
            // A bare key: the stage's singleton, or the reserved `_custom` container.
            entityKey = scope
            rowIndex = null
        } else {
            entityKey = match.groupValues[1]
            val entryIndex = match.groupValues[2].toIntOrNull()
            val entry = entryIndex?.let { sent.getOrNull(it) }
            if (entry == null || entry.entityKey != entityKey) {
                // The server keyed this against a position in the array we did not send, or against a
                // different entity than the one that sits there. Either way the row cannot be named,
                // and naming the WRONG row is worse than admitting it — a message on a box that is
                // fine sends a designer to correct an answer nobody objected to.
                fields.forEach { (key, message) -> unplaced += "$scope.$key: $message" }
                return@forEach
            }
            // The row's position within its own collection, which is what the form draws and what
            // `ordinal` was set to when the payload was built. Counted from the entries rather than
            // taken from `ordinal` alone so a payload that omitted it still resolves.
            rowIndex = entry.ordinal
                ?: sent.take(entryIndex).count { it.entityKey == entityKey }
            rowKey = entry.rowKey
        }

        val entity = spec?.entity(entityKey)
        fields.forEach { (fieldKey, message) ->
            if (fieldKey == DW_ROW_REFUSAL_KEY) {
                /*
                  THE WHOLE ROW WAS REFUSED, AND THIS IS THE ONE KEY IN THE MAP THAT NAMES NO
                  QUESTION — so it is placed against the ROW and never labelled as a field.

                  Fall through to the field path and the bullet read "_row: Someone else saved this
                  row first… this copy of the app has no box for that question": a protocol key
                  presented to a designer as a question they have never seen, followed by this build
                  apologising for not drawing it. Both halves invented, on the one refusal whose
                  remedy is not theirs to carry out. See [DW_ROW_REFUSAL_KEY]. (2026-09-03)
                */
                refusals += DwStageRefusal(
                    entityKey = entityKey,
                    rowIndex = rowIndex,
                    rowKey = rowKey,
                    fieldKey = fieldKey,
                    label = dwRowRefusalLabel(entityKey, entity),
                    message = message,
                    // Whether this build can show the thing at all, which for a row is whether it
                    // knows the entity. Read by nothing today — [DwStageRefusal.sentence] stops
                    // before the clause `drawn` decides — and answered honestly rather than left at
                    // the `false` the field path would have produced for a key it cannot label.
                    drawn = entity != null || entityKey == CUSTOM_ENTITY_KEY,
                )
            } else {
                val registryField = entity?.fields?.firstOrNull { it.key == fieldKey }
                val customField = customByKey[fieldKey]?.takeIf { entityKey == CUSTOM_ENTITY_KEY }
                val label = registryField?.label?.takeIf { it.isNotBlank() }
                    ?: customField?.label?.takeIf { it.isNotBlank() }
                    ?: fieldKey
                val drawn = when {
                    registryField != null -> !registryField.deprecated
                    customField != null -> dwCustomFieldDrawable(customField.type) && !customField.retired
                    else -> false
                }
                refusals += DwStageRefusal(
                    entityKey = entityKey,
                    rowIndex = rowIndex,
                    rowKey = rowKey,
                    fieldKey = fieldKey,
                    label = label,
                    message = message,
                    drawn = drawn,
                )
            }
        }
    }

    return DwStageRefusalReport(refusals = refusals, unplaced = unplaced)
}

/**
 * What to call the thing a row-level refusal is about: the entity, as the form titles it.
 *
 * `entity.title` and not `entity.name`, because `title` is what `CollectionRowCard` prints at the
 * head of every row ("3. Cost line") and what `EntitySection` heads the singleton with — so the
 * bullet names the row using the same words as the thing the designer then scrolls to.
 *
 * `_custom` is not a registry entity and carries no title anywhere in the schema, so it is named the
 * way the form names it rather than by its reserved key. An entity this build's registry does not
 * declare falls back to the key, which is the same answer the field path gives for a key it has no
 * label for — a bare key is ugly and it is true. (2026-09-03)
 */
private fun dwRowRefusalLabel(entityKey: String, entity: EntityDto?): String = when {
    entityKey == CUSTOM_ENTITY_KEY -> "This workshop's own questions"
    else -> entity?.title?.takeIf { it.isNotBlank() } ?: entityKey
}

/**
 * Fill in what the repository holds, from a stage read taken AFTER the refusal.
 *
 * Pure, and separate from the decode on purpose: the decode happens on a save that may have been made
 * with no signal to re-read under, and a report whose "what is held" could only be produced by a
 * successful second request would have no honest state to sit in while that request was impossible.
 * Called with the bucket, every refusal gains a measured answer; not called, they all keep saying
 * [DW_UNRECORDED], which is true.
 *
 * A key ABSENT from the bucket resolves to [DwHeldState.NOTHING] and not to UNRECORDED: the read
 * succeeded, so its silence about a key is a measurement, not a gap.
 */
fun dwHoldingsFrom(report: DwStageRefusalReport, bucket: StageBucketDto): DwStageRefusalReport =
    report.copy(
        refusals = report.refusals.map { refusal ->
            // NOTHING TO MEASURE FOR A ROW-LEVEL REFUSAL. No row carries `_row`, so the bucket's
            // silence about it is not a measurement of an answer — resolving it here would write
            // [DwHeldState.NOTHING] and put "The repository holds no answer to this question." under
            // a conflict about a row somebody else has just filled in. See [DwStageRefusal.isRowLevel].
            // (2026-09-03)
            if (refusal.isRowLevel) return@map refusal
            val value: JsonElement? = when {
                refusal.entityKey == CUSTOM_ENTITY_KEY -> bucket.custom[refusal.fieldKey]
                refusal.rowIndex == null -> bucket.singleton[refusal.fieldKey]
                else -> {
                    val rows = bucket.collections[refusal.entityKey].orEmpty()
                    // The row's own key first, its position only as a fallback — see [DwStageRefusal.rowKey].
                    val row = refusal.rowKey
                        ?.let { key ->
                            rows.firstOrNull { (it["_clientKey"] as? JsonPrimitive)?.content == key }
                        }
                        ?: rows.getOrNull(refusal.rowIndex)
                    row?.get(refusal.fieldKey)
                }
            }
            refusal.copy(
                held = if (value == null) DwHeld.nothing() else DwHeld.holding(DwValues.text(value))
            )
        }
    )

/**
 * Carry already-measured holdings from an earlier report onto an identical new one.
 *
 * WHAT THIS IS FOR IS A REQUEST COUNT, and the count is not small. The stage screen decodes refusals
 * on EVERY debounced save — which fires 800 ms after a designer stops typing, over and over, while
 * they work through a form. A designer who has typed "65OO" into a MONEY field and moves on to the
 * next twenty boxes is refused on every one of those saves, and reading the stage back each time
 * would spend a request per keystroke-burst on a prepaid connection to be told the same thing.
 *
 * So a read is made only when the refusal set has CHANGED. "Changed" is judged on the ADDRESSES and
 * FIELD KEYS — which question was refused — and deliberately not on the message: a repository that
 * rewords its own error does not change what it holds, and re-reading for that would put the cost
 * back. Where a refusal is genuinely new, its [DwStageRefusal.held] stays [DwHeldState.UNRECORDED],
 * which is what tells the caller a read is worth making.
 *
 * Returns [next] unchanged when nothing can be carried, so the caller's test is a plain equality on
 * "does anything still say UNRECORDED".
 */
fun dwCarryHoldings(previous: DwStageRefusalReport?, next: DwStageRefusalReport): DwStageRefusalReport {
    if (previous == null || previous.refusals.isEmpty()) return next
    val measured = previous.refusals
        .filter { it.held.state != DwHeldState.UNRECORDED }
        .associateBy { it.carryKey to it.fieldKey }
    if (measured.isEmpty()) return next
    return next.copy(
        refusals = next.refusals.map { refusal ->
            measured[refusal.carryKey to refusal.fieldKey]
                ?.let { refusal.copy(held = it.held) }
                ?: refusal
        }
    )
}

// --------------------------------------------------------------------------------------
// The refusal that survives the screen
// --------------------------------------------------------------------------------------

/**
 * ONE SAVE'S REFUSAL, ON DISK — because the app's own advice erased it.
 *
 * ── THE DEFECT THIS CLOSES ───────────────────────────────────────────────────────────────────────
 *
 * The card and the marks on the boxes lived only in `StageScreen`'s `remember(stageKey)` state, so
 * leaving the stage and coming back erased every one of them. And the note `recordStageSent` writes
 * onto [StageSyncRecord.failure] — the one thing that outlived the screen — says *"open the stage to
 * see which answers, and what the repository holds"*. A designer who follows the instruction the app
 * itself gave them arrives at a stage with nothing on it: the sync status still counts
 * [StageSyncRecord.refusedFields], so the workshop keeps saying answers were refused, and the surface
 * that was built to say WHICH is blank. The evidence survives exactly as long as the composition does.
 *
 * ── WHY THESE TWO FIELDS AND NOTHING ELSE ────────────────────────────────────────────────────────
 *
 * [dwDecodeStageRefusals] is pure, and its inputs are the registry spec (rebuilt from the schema on
 * every open), this device's custom definition (read off disk on every open), the server's error map,
 * and THE ORDERING OF THE ENTRIES THAT WERE SENT. The first two are already re-derivable; the last two
 * are not, and are the only things stored here. So the card is re-decoded rather than re-drawn from a
 * frozen copy of itself, and a build whose registry has moved on since the save renders the refusal
 * against the registry it actually has.
 *
 * [sent] is deliberately not the entries themselves. A stage's entries carry every answer the designer
 * typed, and a second copy of them on disk would be a second thing to keep in step with the draft —
 * for a decode that reads exactly three things out of each one: which entity the position held, the
 * row's ordinal, and the row's own `_clientKey`.
 *
 * ── WHAT IT DOES NOT CARRY, AND WHY THAT IS THE HONEST CHOICE ─────────────────────────────────────
 *
 * [DwStageRefusal.held] — what the repository holds — is NOT stored. It is measured by a `GET
 * .../stages/{key}` that may have happened on a connection that no longer exists, and a value quoted
 * off disk as "the repository still holds" could be a day old. Restored, every refusal says
 * [DW_UNRECORDED] again, [DwStageRefusalReport.needsRead] is true, and the screen makes the one read
 * that answers it — or, with no signal, keeps saying UNRECORDED, which is true.
 *
 * ── AND IT IS CLEARED BY THE THING THAT FIXES IT ──────────────────────────────────────────────────
 *
 * Written by `recordStageSent` on every save that comes back with an error map and set to null on
 * every save that does not, exactly like [StageSyncRecord.refusedFields] beside it. So correcting the
 * answer clears the card, and the objection this field was originally rejected over — "a red mark on
 * a box whose answer the designer corrected an hour ago" — is answered by [recordedAt] being drawn on
 * the card: the marks are dated, and they are the last thing the repository actually said.
 *
 * Additive and defaulted, so a draft written by any earlier build decodes with null and behaves
 * exactly as it did. The same rung [StageDraft.custom] and [StageDraft.customSeen] were added on.
 */
@Serializable
data class DwStageRefusalRecord(
    /** The server's `errors` map, verbatim. Never rewritten — this repository's errors are sentences. */
    val errors: Map<String, JsonElement> = emptyMap(),
    /**
     * One per entry that was SENT, in the order it was sent.
     *
     * The scope keys in [errors] index INTO THIS LIST — `` `${entityKey}[${index}]` `` where index is
     * the entry's index in `payload.entries`, not the row's ordinal and not its position within its
     * collection. See the header of this file for what decoding that as a row number costs.
     */
    val sent: List<DwSentEntry> = emptyList(),
    /** When the refused save happened, so the card can date what it is showing. */
    val at: String? = null,
    /** `droppedCustomKeys` from the same response — see [DwStageRefusalReport.droppedCustomKeys]. */
    val droppedCustomKeys: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = errors.isEmpty() && droppedCustomKeys.isEmpty()
}

/** The three things a decode reads out of one sent entry. See [DwStageRefusalRecord.sent]. */
@Serializable
data class DwSentEntry(
    val entityKey: String,
    /** The row's position within its own collection as the payload declared it. Null for a singleton. */
    val ordinal: Int? = null,
    /** The row's `_clientKey` — the identity `save_stage` matches a row on. */
    val rowKey: String? = null,
)

/** What one entry of a sent payload contributes to a refusal's addressing, and nothing more. */
fun dwSentEntryOf(entry: StageEntryBody): DwSentEntry = DwSentEntry(
    entityKey = entry.entityKey,
    ordinal = entry.ordinal,
    rowKey = (entry.data["_clientKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
)

/**
 * Re-decode a stored refusal, so the card the designer was told to come back for is still there.
 *
 * The same [dwDecodeStageRefusals] the save path uses, against the same addressing — this is a
 * re-decode and not a second decoder, which is the whole reason [DwStageRefusalRecord] stores the
 * ordering rather than the drawn card.
 */
fun dwRestoreStageRefusals(
    spec: StageDto?,
    record: DwStageRefusalRecord?,
    customFields: List<DwCustomFieldDto> = emptyList(),
): DwStageRefusalReport? {
    if (record == null || record.isEmpty) return null
    val report = dwDecodeStageRefusalsFromSent(
        spec = spec,
        sent = record.sent,
        errors = record.errors,
        customFields = customFields,
    )
    return report.copy(recordedAt = record.at, droppedCustomKeys = record.droppedCustomKeys)
        .takeIf { !it.isEmpty }
}

/** `entity[3]` — the shape `save_stage` files a collection row's errors under. */
private val SCOPED_ROW = Regex("^(.+)\\[(\\d+)]$")

/**
 * `{field key: message}` out of whatever the server put under a scope key.
 *
 * Defensive about the VALUE and not about the shape of the map, because the two fail differently: a
 * scope whose payload is not an object at all is reported through `unplaced` (the caller's branch
 * above), while a message that arrives as a number or a nested object is rendered rather than
 * dropped. A refusal nobody can read is still a refusal somebody has to be told about.
 */
private fun fieldMessages(payload: JsonElement): Map<String, String> {
    val obj = payload as? JsonObject ?: return emptyMap()
    return obj.entries
        .associate { (key, value) ->
            key to ((value as? JsonPrimitive)?.content ?: DwValues.text(value))
                .ifBlank { "was not accepted, and the repository gave no reason" }
        }
}
