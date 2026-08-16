package com.designprototype.workshop.ui

import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDictationConditions
import com.designprototype.workshop.data.DwDictationPlan
import com.designprototype.workshop.data.DwDictationRung
import com.designprototype.workshop.data.DwPackOffer
import com.designprototype.workshop.data.dwPackOffer
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.EMPTY_RICH_DOC
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.report.fromJson
import com.designprototype.workshop.report.toPlain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  THE RECORD FORMS' HALF OF DICTATION AND RICH TEXT — THE PART A DESKTOP JVM CAN CHECK.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `RecordProseField.kt` next door draws the controls; everything in THIS file is a pure function,
 * and the split is not tidiness. Two of the three decisions below are ones that fail SILENTLY when
 * they are wrong — a clip going somewhere it must not, and a document coming back out of a column
 * as visible JSON braces — and neither symptom is a crash, a red screen or a failing build. They
 * are only catchable by assertion, and an assertion needs a function with no `SpeechRecognizer`,
 * no `Context` and no composition in it. That is what this file is.
 *
 * ── WHAT A RECORD FORM IS, AND WHY IT IS NOT A STAGE SCREEN ───────────────────────────────────
 *
 * The artisan form, the craft form, the product and tool forms, the feedback screen, the review
 * panel, the media caption, the designer's profile paragraph. None of them has a design workshop
 * behind it. `DwDictationRun.published` already knows this and already fails closed for it — its
 * own docstring says "A microphone drawn anywhere else — a record form, a preview, a screen that
 * has no workshop behind it at all — therefore has no rung 2" — and that is correct and stays
 * correct. **Nothing in this lane may post a clip to a server.**
 *
 * The id-less `POST /design-workshops/dictate` was retired to 410 GONE deliberately, and
 * `POST /media/transcribe` moved to admin-only in the same sweep, precisely so that no caller could
 * dictate without a consent-bearing workshop. A record form has no consent-bearing workshop, so a
 * record form has no server rung. That is not a limitation to be worked around later; it is the
 * design.
 */

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  1. THE LADDER, WITH RUNG 2 REMOVED BY CONSTRUCTION
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * The rungs a microphone on a record form is allowed to walk. **Three, and never the fourth.**
 *
 * ── WHY THIS IS A FILTER OVER [DwDictationPlan] AND NOT A SECOND LADDER ───────────────────────
 *
 * `dwDictationLadder` holds the ORDER — installed pack, then this app's own model, then the
 * platform's network engine, with a documented swap for handsets that cannot be asked about packs —
 * and that order was argued for at length, measured on a real handset, and is pinned by
 * `DwDictationLadderTest`. Writing a second ordering here would give the repository two ladders to
 * keep in step, and the way they would drift is the way nobody notices: a record form quietly
 * spending a paid engine on a language this phone can already do for free, or skipping the free one
 * that works with no signal. So the order comes from the one function that owns it.
 *
 * What this adds is the one thing a record form must guarantee and a shared ladder cannot: that
 * [DwDictationRung.SERVER_DICTATE] is **not in the list**, whatever the conditions said.
 *
 * ── BELT AND BRACES, AND BOTH ARE DELIBERATE ──────────────────────────────────────────────────
 *
 * The caller ([recordDictationConditions]) already hands the ladder a set of facts under which the
 * server rung is impossible three times over — no workshop on the server, consent NOT_RECORDED, and
 * the route marked unavailable. That alone is enough today. This filter exists for the day somebody
 * changes `dwDictationLadder`'s conjunction for a reason that has nothing to do with record forms:
 * the plan would silently grow a rung, and the control would silently start uploading an artisan's
 * voice from a screen where nobody was ever asked. A filter written down here fails that change
 * LOUDLY — as a test, on a laptop, in this file — instead of in a courtyard.
 *
 * Do not "simplify" this by trusting the conditions. The conditions are an argument about what the
 * ladder should decide; this is a fact about what the record forms will do.
 */
fun recordDictationRungs(plan: DwDictationPlan): List<DwDictationRung> =
    plan.rungs.filter { it != DwDictationRung.SERVER_DICTATE }

/**
 * The facts a record form may tell the ladder about itself.
 *
 * Everything about the HANDSET is passed through from the caller, because it is measured there and
 * changes between two taps. Everything about a WORKSHOP is pinned here, because a record form has
 * none — and pinning it in a pure function is what makes "a record form cannot reach rung 2"
 * assertable rather than merely intended.
 *
 * [serverRouteUnavailable] is true even on a deployment whose `/dictate` route is perfectly healthy,
 * and that is not a lie about the server: it is the honest answer to the question the field actually
 * asks, which is "is there a route THIS control may post to". There is not, and there must not be.
 */
fun recordDictationConditions(
    languageLabel: String,
    packState: com.designprototype.workshop.data.DwPackState,
    onDeviceEngine: Boolean,
    networkRecogniser: Boolean,
    online: Boolean,
    deviceRefusedLanguage: Boolean,
    appModelServesLanguage: Boolean,
    appModelRefusedLanguage: Boolean,
): DwDictationConditions = DwDictationConditions(
    languageLabel = languageLabel,
    packState = packState,
    onDeviceEngine = onDeviceEngine,
    networkRecogniser = networkRecogniser,
    online = online,
    // NO ROUTE FROM HERE. See the docstring above, and `recordDictationRungs` for the second guard.
    serverRouteUnavailable = true,
    deviceRefusedLanguage = deviceRefusedLanguage,
    appModelServesLanguage = appModelServesLanguage,
    appModelRefusedLanguage = appModelRefusedLanguage,
    // No workshop, so no recorded answer to "may these recordings leave the device" — which is the
    // fail-closed reading and also the true one.
    tier3Consent = com.designprototype.workshop.data.DwTier3Consent.NOT_RECORDED,
    // The daily allowance is scoped, in the repository owner's own words, to `/dictate` reaching a
    // paid provider. Nothing here reaches one, so nothing here spends one. Never true.
    dailyCapSpent = false,
    dailyCapLimit = null,
    workshopOnServer = false,
)

/**
 * What a record form says when its ladder has nothing left. **Its own words, naming no workshop.**
 *
 * ── THE DEFECT THIS EXISTS TO FIX, WHICH WAS REAL AND WAS SHIPPING ────────────────────────────
 *
 * `dwDictationNothingLeftSentence` is the stage screen's sentence and it is a good one there. Five
 * of its arms are about a design workshop: an unanswered consent question, a refused one, a spent
 * per-designer allowance, a workshop with no server record, and a deployment with no transcription
 * provider. Every one of them sends the reader somewhere — "record the artisan's answer on the
 * workshop screen", "tell whoever runs the server" — and on a record form every one of those
 * destinations is wrong. A researcher filling in a product's remarks, told to go and record consent
 * on a workshop that has nothing to do with their record, reads the app as broken and stops tapping
 * the microphone. That is a permanent loss of a feature to a sentence.
 *
 * Guarding the workshop arms off is not enough either, and this is the subtle half: with
 * `serverRouteUnavailable` true (which is exactly what [recordDictationConditions] sets) the stage
 * sentence falls through to its two remaining arms, and BOTH of them assert that the server has no
 * transcription service configured and dispatch the reader to an administrator. On a record form
 * that is a false accusation about a server that is fine and a job for a person who cannot help.
 *
 * ── THE RULE EVERY ARM BELOW OBEYS ────────────────────────────────────────────────────────────
 *
 * The same rule the stage file holds itself to: **name a next move capable of a different
 * outcome.** No arm says "try again" where trying again reaches the same refusal, and no arm sends
 * anybody to a settings list that would offer them nothing when they got there — which is why the
 * pack arm asks [dwPackOffer], the same predicate the settings card and the offer dialog use,
 * rather than a second copy of the rule.
 *
 * And none of them mentions a design workshop, a consent decision, an allowance or a server. A
 * record form has none of those, and a sentence about one is a sentence about somebody else's
 * screen. `RecordProseDictationCopyTest` asserts that, over every combination, because this is copy
 * and copy is exactly the thing that rots quietly.
 */
fun recordDictationNothingLeftSentence(conditions: DwDictationConditions): String {
    val label = conditions.languageLabel
    return when {
        /*
         * This app's own model is on the phone, was measured as serving this language, and refused
         * it anyway — with no connection to fall back on. FIRST, above the connection arms, for the
         * reason the stage file gives about its equivalent: a model installed on this phone needs no
         * connection, so blaming the courtyard would be this app blaming the room for its own fault.
         * It names no retry, because the engine has already been asked in this run.
         */
        !conditions.online && conditions.appModelRefusedLanguage ->
            "This app's own speech model is on this phone and would not take $label just now, and " +
                "there is no connection. Type the answer in. This is worth reporting: the model " +
                "was measured as serving $label, so it refusing it here is a fault in this app " +
                "rather than anything you did."

        /*
         * No signal, the phone has an offline engine, and a pack for this language can ACTUALLY be
         * fetched once there is signal. Only then does anybody get sent to the settings list.
         *
         * Asked through [dwPackOffer] with [DwConnection.NONE] rather than by re-deriving the rule,
         * because that function answers NO_CONNECTION for exactly the one state a download may be
         * offered for and UNAVAILABLE for the states where no button could change anything. Sharing
         * the predicate is what stops this sentence promising a download the settings card would
         * refuse to draw.
         */
        !conditions.online && conditions.onDeviceEngine &&
            dwPackOffer(conditions.packState, DwConnection.NONE) == DwPackOffer.NO_CONNECTION ->
            "The $label pack is not on this phone, and without it dictation needs a connection — " +
                "there is none here. Type the answer in, and add the pack from Settings › Offline " +
                "dictation languages when you next have signal."

        // No signal, an offline engine, and NO pack for this language to add — either the platform's
        // catalogue has none or the engine has already refused it. Nothing to tap, so the sentence
        // names the connection and stops rather than sending anybody to an empty list.
        !conditions.online && conditions.onDeviceEngine ->
            "This phone's own recogniser has no offline $label to work from, so dictation in $label " +
                "needs a connection and there is none here. Type the answer in, and dictate the " +
                "rest where there is signal."

        // No signal, and this phone cannot be asked what it has (API < 33) or has no engine of its
        // own. Claiming the pack is missing would be inventing the one fact we do not have.
        !conditions.online ->
            "Dictation in $label on this phone needs a connection and there is none. Your " +
                "keyboard's own microphone may have an offline language pack; otherwise type the " +
                "answer in and dictate the rest later."

        /*
         * ── ONLINE, AND STILL NOTHING. THE ARMS THAT REPLACE FIVE WORKSHOP-FLAVOURED ONES. ──────
         *
         * Below the connection trio for the stage file's reason: with no signal the connection is
         * the true blocker and naming anything else prints a false cause over a real one.
         */

        // A phone with no speech service at all. Common on the budget handsets this app runs on, and
        // it is the one case where there is genuinely nothing to do on the phone — so the sentence
        // says so plainly instead of implying a retry would help.
        !conditions.networkRecogniser && !conditions.onDeviceEngine ->
            "This phone has no speech recogniser installed, so there is no dictation here. Type " +
                "the answer in."

        // Online, an engine exists, and it has told us in this run that it cannot take this
        // language. Naming the language is the point: the fix is picking a different one, and that
        // IS reachable from the control the reader is looking at.
        conditions.deviceRefusedLanguage ->
            "This phone's speech recogniser would not take $label. Pick a different dictation " +
                "language from the control beside the microphone, or type the answer in."

        // Everything else. Deliberately vague about the cause, because by here we do not know one —
        // and inventing a cause is how the sentences above lose their credibility.
        else ->
            "Dictation in $label is not available on this phone just now. Type the answer in, or " +
                "try a different dictation language."
    }
}

/**
 * Spoken text joined to what is already in the box.
 *
 * A space is inserted only where one is missing, so dictating twice into the same field does not
 * produce "…in Bagru.The second" and does not produce a double space either. Both are trivial and
 * both end up in a ministry report verbatim, because nobody proof-reads four hundred fields.
 *
 * A DELIBERATE TWIN of `FieldRenderer.appendSpoken`, which is `private` to the stage renderer. Two
 * copies of six lines is the cheaper mistake here: reaching into the stage file to widen its
 * visibility would put this lane's edits in a file three other agents are writing, and the rule
 * ("one space, only where one is missing") is short enough to be stated identically and pinned by a
 * test in both places.
 */
fun appendSpokenToRecord(existing: String, spoken: String): String = when {
    spoken.isBlank() -> existing
    existing.isBlank() -> spoken
    existing.last().isWhitespace() -> existing + spoken
    else -> "$existing $spoken"
}

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  2. RICH TEXT IN A `String?` COLUMN
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/*
 * ── THE STORAGE DECISION, WHICH IS THE ONE THING IN THIS LANE THAT CAN CORRUPT DATA ───────────
 *
 * Every column a record form writes to is `String?` and **stays** `String?`. There is no migration,
 * no `prisma generate`, no sidecar column and no schema change of any kind. That much is decided
 * and is not this file's to revisit.
 *
 * What IS this file's decision is what those twenty or so `String?` columns actually hold once a
 * rich editor is pointed at them, and there were only two candidates:
 *
 *   (a) THE DOCUMENT, as JSON, flattened by every reader.
 *   (b) THE FLATTENED TEXT, written through `toPlain` at the moment the editor emits.
 *
 * **This is (b), and the reason is that (a) is a silent-corruption trap in this repository
 * specifically.** `fromJson` here, `from_json` in `backend/app/services/rich_text.py:308` and
 * `fromStored` in `frontend/lib/richText.ts:591` all read a `str` as PLAIN PROSE — none of them
 * attempts a `json.loads` first, and that is deliberate on all three, because it is what lets a
 * column promoted from LONG_TEXT to RICH_TEXT keep the prose already in it. The consequence is
 * exact: a JSON document left in a `String?` column and read by ANY reader that has not been
 * specifically taught about it renders `{"blocks":[{"kind":"PARAGRAPH"…` as the value. Not a crash,
 * not a 500 — the braces, verbatim, in a CSV cell, a data-browser panel, a reviewer's edit box, or
 * a printed report. Nobody proof-reads four hundred exported cells.
 *
 * And the readers are not all reachable from here. `record_fields.py::cell()` feeds the info panel,
 * the `/data/report` workbook, `details.txt` in the dataset zip and both `/export` CSVs; eleven raw
 * Prisma `contains` clauses read these columns for free-text search with no chokepoint at all; the
 * review diff renders `RecordRevision.changes` on both platforms. Option (a) is correct only once
 * every one of those has landed, in three languages, in lanes that are not this one. Option (b) is
 * correct the moment it ships and stays correct if none of them ever land.
 *
 * ── WHAT (b) COSTS, SAID PLAINLY RATHER THAN HIDDEN ───────────────────────────────────────────
 *
 * STRUCTURE SURVIVES. `toPlain` writes bullets as "• ", ordered items as "1. " with the counter
 * resetting per list, nesting as two spaces per level, table rows joined by " | ", and paragraphs
 * as lines. [recordDocFromStored] reads all of that back — see below — so a designer who writes a
 * numbered list on the phone finds a numbered list when they reopen the record.
 *
 * INLINE MARKS DO NOT. Bold, italic, underline and strikethrough are an aid while composing and are
 * gone on save. That is a real half of "rich text" and the field says so out loud rather than
 * letting somebody discover it: `RecordProseField` prints one line under any rich box explaining
 * exactly what is kept. A control that silently drops formatting is worse than one that never
 * offered it.
 *
 * ── HOW TO CHANGE THIS LATER, IN ONE PLACE ────────────────────────────────────────────────────
 *
 * [recordStoredFromDoc] is the only function in the app that decides what lands in the column.
 * When `cell()`, the search clauses and the two diff renderers can all flatten a document, change
 * that one function to emit `toJson(doc).toString()` and nothing else here moves — [recordDocFromStored]
 * ALREADY reads that shape, deliberately, so the two directions can be switched independently and a
 * build that reads JSON written by the web is a build that works today.
 */

/**
 * Whether [stored] looks like a rich document rather than prose somebody typed.
 *
 * A CHEAP SHAPE TEST BEFORE AN EXPENSIVE PARSE, and the cheapness is not the reason for it — the
 * conservatism is. `Json.parseToJsonElement` will happily accept `123`, `true` and `"hello"` as
 * valid JSON, so parsing first and asking questions later would reinterpret a remark that happens
 * to read "true" as a boolean. The only thing this app writes and the web writes into these columns
 * is a `{"blocks": …}` object, so nothing that does not start with `{` and carry a `blocks` key is
 * ever offered to the parser.
 *
 * A designer who literally types `{"blocks": []}` into a remarks box gets it read as an empty
 * document. That is a real false positive, it is vanishingly unlikely on a craft record, and the
 * alternative — braces printed into a ministry report because the web wrote a document this build
 * refused to recognise — is the failure worth avoiding.
 */
internal fun looksLikeRichDocument(stored: String): Boolean {
    val trimmed = stored.trimStart()
    return trimmed.startsWith("{") && trimmed.contains("\"blocks\"")
}

/**
 * A leading list marker written by [toPlain], parsed back into the block it came from.
 *
 * Null when the line is ordinary prose. `level` counts the two-space indents `toPlain` emits for a
 * nested item, so a sub-bullet reopens as a sub-bullet rather than as a top-level one whose text
 * begins with two spaces.
 */
private data class ParsedMarker(val kind: BlockKind, val level: Int, val text: String)

private val ORDERED_MARKER = Regex("^(\\d{1,3})\\. (.*)$")

private fun parseMarker(line: String): ParsedMarker? {
    // Two spaces per level, exactly as `toPlain` writes them, and consumed in PAIRS. A lone leftover
    // space is not half an indent — it is somebody's typing — so it stays in the text rather than
    // rounding a level up, which would silently re-nest a list the designer had flattened.
    var level = 0
    var rest = line
    while (rest.startsWith("  ")) {
        level += 1
        rest = rest.substring(2)
    }
    if (rest.startsWith("• ")) return ParsedMarker(BlockKind.BULLET_ITEM, level, rest.substring(2))
    val ordered = ORDERED_MARKER.matchEntire(rest) ?: return null
    return ParsedMarker(BlockKind.ORDERED_ITEM, level, ordered.groupValues[2])
}

/**
 * Whatever is in the column, as a document the editor can open.
 *
 * ── IT ACCEPTS BOTH SHAPES ON PURPOSE, AND THAT IS THE INTEROPERABILITY GUARANTEE ─────────────
 *
 * A `{"blocks": …}` document — which is what the WEB will put in these columns if its own lane
 * chooses option (a) above, and what this app would write if [recordStoredFromDoc] is ever switched
 * — is parsed as a document. Anything else is prose. So this build renders correctly against a
 * column written by either platform in either shape, which is the property that lets the two lanes
 * land in either order without a release that shows braces to somebody.
 *
 * ── AND WHY THE PROSE PATH RE-READS LIST MARKERS RATHER THAN USING `fromPlain` ────────────────
 *
 * `fromPlain` makes every line a PARAGRAPH. That is right for a column that has only ever held
 * typed text, and wrong the moment this editor has written to it: a bullet saved as "• the warp is
 * sized" would reopen as a paragraph whose text literally begins with a bullet glyph, and the
 * designer's next tap on the bullet button would produce "• • the warp is sized". Two saves and the
 * list is a row of glyphs. Re-reading the markers `toPlain` wrote is what makes the round trip
 * stable, and `RecordProseTest` pins it by saving TWICE — a single round trip looks fine.
 *
 * Blank lines are dropped, matching `fromPlain` and matching how `toPlain` joins — a document does
 * not carry empty paragraphs through a save on either platform.
 */
fun recordDocFromStored(stored: String?): RichDoc {
    val text = stored?.takeIf { it.isNotBlank() } ?: return EMPTY_RICH_DOC
    if (looksLikeRichDocument(text)) {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        // A `{"blocks": …}` that will not parse is a truncated or corrupt value, and the honest
        // reading of it is the one below: show the designer the characters that are actually in
        // their record rather than an empty box that invites them to overwrite it with nothing.
        if (parsed is JsonObject) return fromJson(parsed)
    }
    val blocks = text.split("\n")
        .filter { it.isNotBlank() }
        .map { line ->
            val marker = parseMarker(line)
            if (marker == null) {
                RichBlock(spans = listOf(RichSpan(line.trim())))
            } else {
                RichBlock(
                    kind = marker.kind,
                    level = marker.level,
                    spans = listOf(RichSpan(marker.text.trim())),
                )
            }
        }
    return if (blocks.isEmpty()) EMPTY_RICH_DOC else RichDoc(blocks = blocks)
}

/**
 * What lands in the `String?` column. **The single decision point named in the block comment above.**
 *
 * Returns null for an empty document rather than `""`, because a blank box on a record form means
 * "this field is not filled in" and every one of these columns is nullable — writing an empty string
 * would make a never-answered field indistinguishable from one somebody cleared, and the review
 * screen's diff would show a change where nothing was said.
 */
fun recordStoredFromDoc(doc: RichDoc): String? = toPlain(doc).takeIf { it.isNotBlank() }

/**
 * The one line printed under a rich box, so nobody discovers the trade-off by losing work.
 *
 * SHORT ON PURPOSE. The stage screens learned this the hard way and their own files record it: a
 * thirty-five-word lecture under a control is a lecture nobody reads twice, and the second time it
 * appears they stop reading the sentences that matter. This says what is kept, says what is not,
 * and stops.
 */
const val RECORD_RICH_TEXT_NOTE: String =
    "Lists, numbering and paragraphs are saved. Bold and italic help you write and are not stored " +
        "on this field."
