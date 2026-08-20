package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DW_AI_VERBS_SPENT
import com.designprototype.workshop.data.DW_AI_VERB_COUNTDOWN_FROM
import com.designprototype.workshop.data.DW_VERBS_NEED_A_CONNECTION
import com.designprototype.workshop.data.DwAiVerbCapRefused
import com.designprototype.workshop.data.DwAiVerbCapView
import com.designprototype.workshop.data.DwAiVerbNotConfigured
import com.designprototype.workshop.data.DwAiVerbRefused
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.dwAiIsUnrecorded
import com.designprototype.workshop.report.RichDoc
import java.io.IOException

/**
 * EVERYTHING THE THREE VERB SURFACES DECIDE AND SAY — with no composition, no Context and no network.
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * NO RULE IS DECIDED HERE. `data/DwAiVerbs.kt` decides every one of them.
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The pre-press ladder is `dwVerbGate`, the passage bound is `dwVerbPassageRefusal`, the ceiling is
 * `dwAiVerbCapView`, the target-language rule is `dwTranslationTargetRefusal`, and the five pre-press
 * sentences are that file's constants. Each is USED here rather than re-expressed, because the
 * cross-surface rule for this feature is that one rule may not acquire a second voice.
 *
 * What lives here is the part that is genuinely a SCREEN's: which passage the editor's caret is
 * pointing at, the vocabulary a reader meets beside a layer, and the two sentences the surfaces
 * compose out of facts the data lane hands them. It is a separate file from the composables for the
 * reason `DwStageFindings` and `DwPhotoMeasure` are separate from their panels — so it can be
 * asserted on a desktop JVM by `DwAiVerbWordingTest`, which is where the properties that matter
 * about it are actually checked.
 */

// -------------------------------------------------------------------------------------------------
// Which passage the caret is pointing at
// -------------------------------------------------------------------------------------------------

/**
 * THE PASSAGE AN AI VERB WOULD RUN OVER: the designer's selection where they made one, and the whole
 * paragraph the caret is in where they did not.
 *
 * ── WHY BOTH, RATHER THAN ONE OR THE OTHER ──────────────────────────────────────────────────────
 *
 * A collapsed caret is the ORDINARY state of this editor — a designer types, the keyboard closes, and
 * nothing is highlighted — so a selection-only rule would leave the verbs unavailable almost all of
 * the time and would ask for a gesture Android makes awkward on a small screen. But a designer who
 * DID drag out a phrase means that phrase, and widening it to the paragraph would send more of an
 * artisan's words to a provider than they chose to send. So: the selection when there is one.
 *
 * ── AND WHY IT CANNOT REACH PAST ONE BLOCK ──────────────────────────────────────────────────────
 *
 * Because a selection in this editor cannot: `RichTextBlockRow.onSelectionChanged` builds both ends
 * of the range from the same block index, since each block is a separate `BasicTextField` and
 * Android's selection handles do not cross composables. Reading one block is therefore the whole
 * truth about what is selected rather than a simplification of it — and it is why [DwAiVerbsPanel]
 * is paragraph-scoped instead of porting the browser's block-walking `selectedPassage`.
 *
 * A TABLE CONTRIBUTES ITS CELLS, tab-separated by row, because that is what `RichBlock.text` means
 * for that kind — the same reading the browser's `blockText` gives it. AN IMAGE BLOCK CONTRIBUTES ITS
 * CAPTION, because an IMAGE block's spans ARE its caption; a caret in a caption is a caret in prose,
 * and proofreading a caption is a perfectly ordinary thing to want.
 *
 * A RANGE NAMING A BLOCK THAT IS NOT THERE ANSWERS "" rather than throwing. Nothing should produce
 * one — every command clamps through `clampPoint` — but this is read on a click handler, and
 * `clampPoint`'s own KDoc says what an off-the-end point costs there: *"it does not look to a
 * designer like a bad caret, it looks like the app closing while they type."*
 */
internal fun dwVerbPassageOf(doc: RichDoc, range: DocRange): String {
    val ordered = normaliseRange(doc, range)
    val block = doc.blocks.getOrNull(ordered.anchor.block) ?: return ""
    val text = block.text
    val from = ordered.anchor.offset.coerceIn(0, text.length)
    val to = ordered.focus.offset.coerceIn(0, text.length)
    // A COLLAPSED CARET MEANS THE WHOLE PARAGRAPH. `normaliseRange` has already put the two ends in
    // document order, so `from >= to` is the only shape a collapsed range can have by the time it
    // reaches here — including a selection the designer dragged UPWARD, whose anchor followed its
    // focus until that call.
    if (from >= to) return text
    return text.substring(from, to)
}

/** How much of the passage the card shows, so a designer can see what is about to be sent. */
internal const val DW_VERB_PREVIEW_CHARS: Int = 160

/**
 * The opening of a passage, on one line.
 *
 * Newlines are collapsed for `ai_layers._preview`'s reason applied to a table: a cell-joined block
 * would otherwise draw as several lines in a card sized for one, pushing the buttons off the bottom
 * of a phone screen. Truncation is MARKED with an ellipsis rather than silent — a preview that simply
 * stopped would be indistinguishable from a short paragraph, which is the defect class this
 * repository keeps re-fixing.
 *
 * ── THE COST IS BOUNDED BY [DW_VERB_PREVIEW_CHARS] AND NOT BY THE PASSAGE ───────────────────────
 *
 * This was `passage.split(Regex("\\s+")).filter { … }.joinToString(" ")` and then `take(160)`, which
 * built the WHOLE collapsed paragraph — thousands of intermediate strings for a paragraph near
 * `DW_VERB_MAX_TEXT_CHARS` — before throwing all but 160 characters of it away. Its caller in
 * `RichTextEditor` is the one composable that file says recomposes on every keystroke, so the cost
 * landed per character typed. The single pass below stops as soon as it has one character more than
 * it can show, which is what makes the 161st character the last one this function ever looks at.
 *
 * SAME ANSWER, arrived at more cheaply: a run of whitespace becomes one space, leading and trailing
 * whitespace is dropped, and the ellipsis appears on exactly the inputs it appeared on before.
 *
 * ONE DELIBERATE DIFFERENCE, WHICH IS THE PREDICATE. `Char.isWhitespace()` is
 * `Character.isWhitespace(ch) || Character.isSpaceChar(ch)`, so it covers what the regex's ASCII `\s`
 * did AND the Unicode separators a pasted document carries — U+2028/U+2029, and the non-breaking
 * space a word processor puts between a number and its unit. Folding those is what this function is
 * for: a LINE SEPARATOR left in place drew the second line of a paste into a card sized for one. **It
 * changes nothing that is sent or measured** — `passageChars` and `readPassage` never come through
 * here, so the passage a layer records as its evidence is byte-for-byte what it always was.
 */
internal fun dwVerbPassagePreview(passage: String): String {
    val flat = StringBuilder(DW_VERB_PREVIEW_CHARS + 1)
    var gap = false
    for (ch in passage) {
        if (ch.isWhitespace()) {
            // Recorded rather than written, so a trailing run adds nothing and a leading one — where
            // `flat` is still empty — is dropped altogether.
            if (flat.isNotEmpty()) gap = true
            continue
        }
        if (gap) {
            flat.append(' ')
            gap = false
        }
        flat.append(ch)
        // ONE CHARACTER PAST THE LIMIT AND THEN STOP. The extra character is what tells a passage of
        // exactly [DW_VERB_PREVIEW_CHARS] from a longer one, which is the difference between a
        // preview that ends and one that is marked as truncated.
        if (flat.length > DW_VERB_PREVIEW_CHARS) break
    }
    if (flat.length <= DW_VERB_PREVIEW_CHARS) return flat.toString()
    return flat.substring(0, DW_VERB_PREVIEW_CHARS).trimEnd() + "…"
}

/**
 * **THE ONE SENTENCE THESE SURFACES AUTHOR, AND THE ONE PLACE THE HANDSET'S WORDING DIVERGES FROM
 * THE BROWSER'S.**
 *
 * `DW_VERBS_NOTHING_SELECTED` says *"Select the words you want worked on first"*, which is right in a
 * browser and is an instruction this editor cannot honour: a selection here can never leave one block
 * (see [dwVerbPassageOf]), and the ordinary state of a caret in a paragraph is collapsed. A designer
 * told to select first would be told to perform a gesture that is not needed and, for anything longer
 * than a paragraph, not possible.
 *
 * So this sentence names the caret and the paragraph, and it makes the SAME CLAIM as the browser's
 * about what is sent and what the layer records. Everything else on these surfaces uses the shared
 * constants unchanged; this is a divergence in the words, forced by a divergence in the editor, and
 * not a second opinion about the rule.
 */
internal const val DW_NO_PARAGRAPH_TO_WORK_ON: String =
    "Put the caret in the paragraph you want worked on, or select part of one. These run over a " +
        "passage rather than over the whole field, so what is sent is what the layer records as its " +
        "source — a later reader sees exactly this passage quoted as the evidence."

// -------------------------------------------------------------------------------------------------
// Which verb a file admits
// -------------------------------------------------------------------------------------------------

/** `ai_verbs.Verb.CAPTION` / `SUBTITLES` as the media row names them. */
internal const val MEDIA_VERB_CAPTION: String = "CAPTION"
internal const val MEDIA_VERB_SUBTITLES: String = "SUBTITLES"

/**
 * The media verbs one stored file may be offered, mirroring `_VERB_MEDIA_TYPES` exactly.
 *
 *     IMAGE  ->  caption
 *     VIDEO  ->  caption AND subtitles
 *     AUDIO  ->  subtitles
 *     PDF, DOCUMENT -> neither
 *
 * **OFFERED BY TYPE SO THIS CLIENT NEVER PRODUCES THAT 409.** The server checks the same pairing
 * before any bytes move, precisely because the failure otherwise is expensive and unreadable: a
 * caption run over an audio file uploads a recording to a vision model, which answers with a parse
 * error after the credit is spent, and the designer reads "FAILED (HTTP 400)" about a file they
 * picked correctly.
 *
 * Compared with [String.endsWith] for the reason the server states about the same comparison: the
 * stored form of that column has varied, and a prefixed enum spelling must not silently match
 * nothing. This handset's own vocabulary is `DraftMedia.mediaType` — "IMAGE / VIDEO / AUDIO / PDF /
 * DOCUMENT" — so the bare tokens match today and a prefixed one would still match tomorrow.
 *
 * **A PDF AND A DOCUMENT GET NO CONTROL AND NO SENTENCE**, which is the one place this feature stays
 * silent on purpose. A designer looking at a scanned sanction order has no question that a refusal
 * would answer: there is no verb for it and nothing has been taken away. That is the treatment the
 * stage list gives the divergence row, for the reason recorded there — a control explained where the
 * capability does not apply advertises something nobody can get.
 */
internal fun dwMediaVerbsFor(mediaType: String?): Set<String> {
    val stored = (mediaType ?: "").trim().uppercase()
    if (stored.isEmpty()) return emptySet()
    val verbs = mutableSetOf<String>()
    if (stored.endsWith("IMAGE") || stored.endsWith("VIDEO")) verbs += MEDIA_VERB_CAPTION
    if (stored.endsWith("AUDIO") || stored.endsWith("VIDEO")) verbs += MEDIA_VERB_SUBTITLES
    return verbs
}

/**
 * Could a file attached to a field of THIS TYPE be offered a media verb at all?
 *
 * **THE FIELD'S DECLARATION, FOR THE CASE WHERE THE FILE'S OWN `mediaType` IS NOT KNOWN.**
 * [DwFieldType] is what the registry declares; `DraftMedia.mediaType` is measured from bytes this device holds. When
 * a descriptor cannot be resolved — a photograph attached in the browser, or bytes that have gone
 * missing — there are no bytes to measure and [dwMediaVerbsFor] has nothing to answer from, so the
 * tile has to decide from the field whether to explain the missing control or to say nothing.
 *
 * IMAGE, IMAGE_LIST, AUDIO and VIDEO fields hold exactly the three media types a verb exists for.
 * **FILE IS DELIBERATELY FALSE**, and it is the interesting one: a FILE field holds the scanned
 * sanction order, and `dwMediaVerbsFor` answers PDF and DOCUMENT with nothing at all *"which is the
 * one place this feature stays silent on purpose"*. Explaining an absent control there would advertise
 * a capability that does not exist for that field even when the bytes are present — so an
 * unresolvable FILE tile keeps that silence rather than acquiring a sentence the resolvable one does
 * not have. The cost, stated: a designer who photographed a certificate INTO a FILE field on another
 * device gets no sentence about the missing caption control. Naming the field type is a declaration;
 * guessing the type of bytes this phone does not hold is not.
 *
 * `else` RATHER THAN TWENTY-THREE SPELLED-OUT ARMS, and the direction is why that is safe here: a
 * media type added to [DwFieldType] later answers FALSE, so it draws no sentence until somebody adds
 * it — silence, which is this feature's own floor, and never a sentence about a capability that does
 * not exist. The opposite default would advertise one.
 */
internal fun dwMediaFieldMayCarryVerbs(type: DwFieldType): Boolean = when (type) {
    DwFieldType.IMAGE, DwFieldType.IMAGE_LIST, DwFieldType.AUDIO, DwFieldType.VIDEO -> true
    else -> false
}

/**
 * WHY THERE IS NO CAPTION OR SUBTITLE CONTROL UNDER A TILE THIS PHONE CANNOT OPEN.
 *
 * ── THE SILENCE THIS REPLACES, AND THE CASE THAT MADE IT WORTH REPLACING ────────────────────────
 *
 * `DwMediaCaptureCard` draws the row as `item?.let { DwMediaAiVerbsRow(…) }`, so an unresolvable
 * descriptor got a tile with no control and no sentence. **The comment there named only one cause —
 * bytes gone missing — and the second one is the case where the server CERTAINLY holds the file: a
 * photograph attached from the browser.** `mediaIndex` in `StageScreen` is
 * `draft?.media.orEmpty().associateBy { it.id }`, LOCAL descriptors only, and `DraftMedia` is a local
 * file record (`relativePath`, `sha256` of the copy on this disk) — so a web upload resolves to null
 * here even though it has a `remoteMediaId` on the server and is the one file a verb could certainly
 * run over. `RichTextEditor`'s comment on the same resolver already records the shape: *"a picture
 * placed on the web carries a server id and answers null, which is a different thing to draw and not
 * an error."*
 *
 * So the tile now names BOTH causes — it cannot tell them apart from here — and says what to do. A
 * sentence rather than silence is the minimum; the fuller repair is for the bridge to surface a
 * SERVER-ONLY descriptor so `remoteMediaId` is known for a file this device never imported, which is
 * a data-lane change (the pull would have to write descriptors with no local bytes, and every reader of
 * `DraftMedia.relativePath` — the report writer and the uploader among them — would have to be shown
 * to tolerate one) and is handed off rather than guessed at here.
 */
internal const val DW_MEDIA_VERBS_NEED_THE_FILE_HERE: String =
    "There is no “Ask AI about this file” here because this phone has no record of this attachment. " +
        "This app knows only the files that were imported on this device, so one attached in the " +
        "browser or on another handset cannot be named to the server from here — and bytes that have " +
        "gone missing leave the same gap. Describing it and subtitling it are both offered on the " +
        "web, on the workshop's own copy of this file."

// -------------------------------------------------------------------------------------------------
// The two sentences these surfaces compose
// -------------------------------------------------------------------------------------------------

/**
 * TODAY'S COUNTDOWN, COMPOSED IN ONE PLACE. Null means draw nothing at all.
 *
 * ── WHY IT IS A FUNCTION ────────────────────────────────────────────────────────────────────────
 *
 * This is the third of the three defects the browser's own review found: *"the cap countdown was
 * computed inline in three places before being extracted."* There are three surfaces here too — the
 * prose panel, the media row and the review sheet — and this is the one copy between them.
 *
 * **NULL FOR AN UNCAPPED DEPLOYMENT, WHICH IS THE WHOLE POINT.** `allowance_payload` sends null for
 * both the limit and the remainder where there is no ceiling, deliberately, *"because 0 remaining and
 * 'no ceiling' must not look alike"* — and the obvious `?: 0` an implementer reaches for turns an
 * uncapped deployment into one that appears to be out of runs.
 *
 * The day is printed beside the number because it is the SERVER's India-time day, and it is passed in
 * rather than computed here: on the review sheet it comes off the 201's own `aiVerbDay`, and on the
 * two pre-press surfaces it is the day the stored mirror was accepted under (`dwAiVerbCapView`
 * answers "unknown" for any other day, so the two can never be shown together). A handset whose clock
 * is a day out would otherwise print a count against a date the designer does not recognise, with
 * nothing on screen admitting whose date it is.
 */
internal fun dwAiVerbCountdownLine(remaining: Int?, day: String?): String? {
    if (remaining == null || remaining > DW_AI_VERB_COUNTDOWN_FROM) return null
    val runs = if (remaining == 1) "1 run" else "$remaining runs"
    val on = day?.trim().orEmpty()
    val where = if (on.isEmpty()) "left today" else "left for $on"
    return "$runs of the writing and captioning models $where. Dictation has its own separate " +
        "allowance and is unaffected."
}

/**
 * WHAT TO SAY ABOUT THE CEILING WHEN THERE IS NO NUMBER TO COUNT DOWN — null to say nothing.
 *
 * ── THREE FACTS, AND TWO OF THEM LOOK IDENTICAL IN THE NUMBERS ──────────────────────────────────
 *
 * [DwAiVerbCapView.told] is the fact that separates them, and its KDoc has the table. This function
 * exists so the separation is made ONCE: [DwAiVerbsPanel] branched on `limit == null && remaining ==
 * null` and told an uncapped deployment its allowance was unknown, which is precisely what
 * `allowance_payload` keeps two nulls apart to prevent — *"because 0 remaining and 'no ceiling' must
 * not look alike"*.
 *
 * ── AND WHY BOTH PRE-PRESS SURFACES NOW DRAW IT ─────────────────────────────────────────────────
 *
 * The prose panel drew a sentence in this state and [DwMediaAiVerbsRow] drew nothing, which was a
 * divergence between two surfaces of one feature and nothing argued for it. The panel's own reason —
 * *"Silence would leave a designer discovering it as a refusal after typing a language in"* — is
 * stronger on the media row, not weaker: the press it precedes can be a whole recording going up over
 * a designer's own mobile data, which is the most expensive press this feature has.
 *
 * NOTHING HERE IS DRAWN WHILE A COUNTDOWN IS: [dwAiVerbCountdownLine] answers non-null only when a
 * `remaining` is known and low, and both branches here need it to be absent. A surface may therefore
 * draw both without ever printing two sentences about one ceiling.
 */
internal fun dwAiVerbAllowanceNote(cap: DwAiVerbCapView): String? = when {
    !cap.told ->
        // THE MISSING PRE-FLIGHT, STATED RATHER THAN GUESSED AT. `ai_verb_cap.allowance_payload` rides
        // on the 201 and on the 429 and nowhere else; there is no route that answers "what is my
        // allowance" (checked against `backend/app/api/routes/` rather than assumed). So until a run
        // has gone past on this phone today there is no number, and nothing here can say whether the
        // ceiling is near, far or absent.
        "How many runs are left today is not known until one goes through — this server has no way " +
            "to be asked without running something. If the allowance is already used up, the " +
            "refusal will say so and nothing will have been spent finding out."

    cap.limit == null && cap.remaining == null ->
        // TOLD, AND TOLD THERE IS NO CEILING. A statement about the DEPLOYMENT rather than about this
        // designer's afternoon, which is why it does not decay: it is read off a row this phone
        // accepted for today, and `dwAiVerbCapView` answers "not told" for any other day.
        "This server sets no daily ceiling on these runs, so there is no count to watch. Dictation " +
            "has its own separate allowance either way."

    // A number is known. The countdown says it where it is worth saying — see [dwAiVerbCountdownLine],
    // which stays silent well above the ceiling rather than nagging from run one.
    else -> null
}

/**
 * THE SENTENCE TO SHOW WHEN A PRESS COMES BACK BADLY — **the server's own words wherever it sent any.**
 *
 * Consent (`dictation_consent.gate_refusal`), the ceiling (`ai_verb_cap.cap_refusal`), the layer law
 * (`ai_layers.check_placement`), the wrong-file-type 409 and the 503 that names a missing key are all
 * field copy already, each naming a next move that can actually work. Re-wording any of them here
 * would give one rule two voices, which is the drift `DwDictationConsentRefused` refuses in as many
 * words: *"[detail] IS THE COPY … Composing our own here would mean choosing between those two states
 * from a body that does not name one."*
 *
 * The three arms that are not verbatim are the three where the server said nothing:
 *
 *  * an [IOException] — Retrofit's shape for a request that never reached anybody — is the offline
 *    sentence, which is this client's to write because no server saw the request;
 *  * a cap refusal whose body carried no sentence falls back to `DW_AI_VERBS_SPENT`, which
 *    deliberately does not invent the zero-cap case (`cap_refusal` has its own sentence for a
 *    deployment that has switched these verbs off, and it is the server's to write);
 *  * anything else is named as an unexpected failure rather than dressed up as a refusal, because a
 *    client that turned a decoding bug into "the model declined" would send a designer off to
 *    rewrite a perfectly good note.
 *
 * **A REFUSAL WHOSE BODY CARRIED NO SENTENCE NAMES THE STATUS AND SAYS WHAT WAS NOT WRITTEN.** It is
 * the one place a code reaches a designer, and the alternative is worse: `DwAiVerbRefused`'s own KDoc
 * records that a 409 rewritten by something in between carries no detail, and a bare "the server said
 * no" would leave somebody unable to tell a lost proxy body from a real refusal of their work.
 */
internal fun dwAiVerbProblem(error: Throwable): String = when (error) {
    is DwAiVerbNotConfigured -> error.detail
    is DwAiVerbCapRefused -> error.detail ?: DW_AI_VERBS_SPENT
    is DwAiVerbRefused -> error.detail
        ?: "The server refused this run and sent no reason with it (HTTP ${error.status}). Nothing " +
        "was written to this workshop and nothing was changed in your draft. Try once more, and tell " +
        "whoever administers the server if it keeps happening."
    is IOException -> DW_VERBS_NEED_A_CONNECTION
    else -> error.message?.takeIf { it.isNotBlank() }
        ?: "Something went wrong on the way to the server. Nothing was written to this workshop."
}

// -------------------------------------------------------------------------------------------------
// The vocabulary a reader meets — the same words the report annexure prints
// -------------------------------------------------------------------------------------------------

/**
 * What each rung IS, in the words a designer would use, and the words the annexure prints above the
 * passage — so the person signing recognises what they signed for when they meet it again in the
 * .docx a year later.
 *
 * "Machine transcript" rather than the bare "Transcript" for RAW_TRANSCRIPT: a workshop also holds
 * transcripts a person typed or corrected, and a heading that did not distinguish them would let
 * model output be read as somebody's own words.
 */
private val DW_LAYER_KIND_LABELS: Map<String, String> = mapOf(
    "RAW_TRANSCRIPT" to "Machine transcript",
    "CLEANED_TRANSCRIPT" to "AI-cleaned transcript",
    "SUMMARY" to "AI summary",
    "OCR_TEXT" to "Text read off a photograph",
    "STRUCTURED_TEXT" to "Fields read off a photograph",
    "TAGS" to "Suggested tags",
    "METADATA" to "Extracted details",
    "PROOFREAD" to "AI-corrected spelling and punctuation",
    "EXPANDED" to "Prose written by AI from a designer's note",
    "TRANSLATION" to "AI translation",
    "CAPTION" to "AI description of a photograph or video",
    "SUBTITLES" to "AI subtitles, with their timings",
)

/**
 * A kind's heading.
 *
 * A kind this build has never heard of degrades to an honest note carrying the SERVER'S OWN WORD and
 * never to a blank — a deployment can be a release behind, which the server allows for by name in
 * `_verb_layer_kind`. The row still shows its tier, its model and its acceptance, none of which needs
 * the kind to be understood.
 */
internal fun dwLayerKindLabel(kind: String?): String {
    DW_LAYER_KIND_LABELS[kind.orEmpty()]?.let { return it }
    return if (!kind.isNullOrBlank()) {
        "A layer kind this screen does not know ($kind)"
    } else {
        "A layer with no kind recorded"
    }
}

/**
 * A kind as a NOUN PHRASE that can sit inside a sentence — "…against this AI translation."
 *
 * Separate from [dwLayerKindLabel] because that one degrades to a whole sentence, and a sentence
 * inside a sentence reads as a bug.
 */
internal fun dwLayerKindNoun(kind: String?): String =
    DW_LAYER_KIND_LABELS[kind.orEmpty()]?.lowercase() ?: "layer"

/**
 * The sentence under a kind's heading: what the machine WAS and WAS NOT allowed to change.
 *
 * That is the question somebody about to quote the passage actually has, and it is why each of the
 * five verbs has its own kind rather than reusing a neighbour's — each is a different PROMISE to
 * whoever reads the document. Null for the kinds these five verbs cannot produce; the review sheet is
 * only ever shown one of the five.
 */
internal fun dwLayerKindNote(kind: String?): String? = when (kind) {
    "PROOFREAD" ->
        "Spelling, grammar and punctuation only. The model was refused permission to translate, to " +
            "restructure or to shorten, and it was given the craft vocabulary as a do-not-touch " +
            "list so that “dabu” is not “corrected” to “double”. The original is untouched and " +
            "stays where it is."

    "EXPANDED" ->
        "A machine wrote these sentences from a short note. It is the only kind here that INVENTS, " +
            "and nothing may be derived from an expansion."

    "TRANSLATION" ->
        "A translation that stands BESIDE the original rather than replacing it, so a reader who " +
            "wants the artisan's own words can still have them. The row records which language it " +
            "came from as well as which it went into, because a translated passage nobody can " +
            "trace back is a passage nobody can check."

    "CAPTION" ->
        "One sentence a model wrote about the photograph or video — for the media annexure, and " +
            "for a screen reader. Check it against the picture, which is the evidence it stands on."

    "SUBTITLES" ->
        "Timed captions: a cue list with a start and an end for every line. The timings are the " +
            "whole verb — a subtitle without them is a transcript."

    else -> null
}

/**
 * THE TIER, SAID AS WHAT IT MEANS, with no numeral.
 *
 * `AiTier.number` exists on the server "for prose only, never for a comparison", and a chip reading
 * "Tier 3" invites exactly the comparison the enum was chosen to prevent: Tier 1 is the only tier
 * that works in a courtyard with no signal and Tier 3 is the only one carrying the craft keyterm
 * list, so neither direction is "better".
 */
internal fun dwTierLabel(tier: String?): String = when (tier) {
    "TIER_1" -> "On the handset"
    "TIER_2" -> "On the handset, small model"
    "TIER_3" -> "In the cloud"
    null, "" -> "Tier not recorded"
    else -> "An unfamiliar tier ($tier)"
}

internal fun dwTierSentence(tier: String?): String = when (tier) {
    "TIER_1" ->
        "Produced by a model running on the device itself. This is the only tier that works in a " +
            "courtyard with no signal, and the material never left the handset."

    "TIER_2" ->
        "Produced by a small language model running on the handset. Nothing left the device, and " +
            "what a given handset can run depends on the handset."

    "TIER_3" ->
        "Produced by a provider in the cloud. This is the only tier that carries the craft " +
            "vocabulary — the list that stops “dabu” being written as “double” — and the material " +
            "left the device to reach it."

    else ->
        "This server recorded a tier this screen does not know, so where the model ran cannot be " +
            "stated in words here. The stored value is shown as it was sent."
}

/**
 * A provenance column in words.
 *
 * `UNRECORDED` is a REAL stored value and not a null — the server writes it deliberately, because a
 * null on a provenance column would read as "there is no such thing" where the truth is "nobody
 * recorded one". Printing the bare token would put a shouting constant in front of a designer, which
 * reads like a code rather than like an answer. `dwAiIsUnrecorded` is the one comparison, so this
 * screen and the tier-2 lane cannot come to spell it differently.
 */
internal fun dwProvenanceWord(raw: String?): String {
    val value = raw?.trim().orEmpty()
    return if (value.isEmpty() || dwAiIsUnrecorded(value)) "not recorded" else value
}

internal fun dwModelWords(modelId: String?, modelVersion: String?): String {
    val id = dwProvenanceWord(modelId)
    val version = modelVersion?.trim().orEmpty()
    return if (id == "not recorded" || version.isEmpty() || dwAiIsUnrecorded(version)) {
        id
    } else {
        "$id · $version"
    }
}

/**
 * A language column in words.
 *
 * `multi` IS A REAL ANSWER AND NOT A PLACEHOLDER: Deepgram Nova-3 is deliberately called with
 * `language=multi` precisely because these interviews code-switch mid-sentence. Printing the bare
 * token would read like a missing value, which is the opposite of what it says.
 */
internal fun dwLanguageWords(raw: String?): String {
    val value = raw?.trim().orEmpty()
    return when {
        value.isEmpty() || dwAiIsUnrecorded(value) -> "not recorded"
        value.equals("multi", ignoreCase = true) -> "multi — mixed, code-switched speech"
        else -> value
    }
}
