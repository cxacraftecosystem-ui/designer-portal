package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * HOW a record's dimension came to be known, as the request body says it — and the rule that stops
 * it saying so once it has stopped being true.
 *
 * ── WHAT THE MARKER IS ────────────────────────────────────────────────────────────────────────
 *
 * `ProductDocumentation` / `ToolDocumentation` carry `lengthInches` / `breadthInches` /
 * `heightInches`, and three different processes write them: somebody types a number off a tape,
 * somebody accepts a reading from `RecordMeasureField` (deterministic plane geometry, on this
 * handset), or somebody accepts an estimate from `GridMeasurementSection` (a vision model, over the
 * network). The server stamps every changed field with the `{by, byName, at}` of whoever pressed
 * Save, so until this key existed a machine's number was stored asserting that a NAMED HUMAN had
 * measured it. `measurementMethods` puts the method beside the signature, and the row goes back to
 * saying something true: a vision model estimated this, and R. Menon accepted it into the record at
 * that moment.
 *
 * The wire shape is `backend/app/services/measurement_provenance.py`'s, and that file is the
 * authority for every token below — [DW_MEASUREMENT_DIMENSIONS] mirrors its `DIMENSION_FIELDS`,
 * [DW_TECHNIQUE_SCALE] / [DW_TECHNIQUE_RECTIFIED] its `GEOMETRY_TECHNIQUES`. Re-check with:
 *
 *     grep -n "DIMENSION_FIELDS\|GEOMETRY_TECHNIQUES" backend/app/services/measurement_provenance.py
 *
 * ── SENDING NOTHING IS ALWAYS LEGAL, AND THAT IS WHAT MAKES THIS SAFE TO SHIP ─────────────────
 *
 * An absent marker is read as `UNRECORDED`, never as `TYPED` — the server's own docstring calls that
 * "the one decision here most likely to be simplified later" and refuses to make it. So a typed
 * number needs no marker, an older handset that has never heard of this key keeps saving exactly as
 * it does today, and [DwMeasurementMarkers.body] returning null is a correct answer rather than a
 * failure. Nothing here ever composes a `TYPED` marker: typing is the absence of a machine, and
 * saying so out loud would add a claim without adding a fact.
 *
 * ── WHY A REFUSAL IS A DATA-LOSS QUESTION ON THIS PLATFORM ────────────────────────────────────
 *
 * `marker_body_problems` is strict and every violation is a 422 on the WHOLE save. The outbox will
 * not queue a 4xx — the server saw it and said no — so a malformed marker does not merely fail to
 * record a method, it throws away a record filled in somewhere with no signal. That is why
 * [DwMeasurementMarkers.body] is conservative in one direction only: when in doubt it sends NO
 * marker, which costs a fact and loses nothing.
 */

/**
 * The only three columns a method may describe. `measurement_provenance.DIMENSION_FIELDS`, verbatim.
 *
 * The tool form's `height`, `width`, `thickness`, `weight` and `radius` are deliberately NOT here:
 * they are ordinary typed boxes with no measurement route pointed at them, and the server refuses a
 * marker naming one of them BY NAME rather than dropping it.
 */
val DW_MEASUREMENT_DIMENSIONS: Set<String> = setOf("lengthInches", "breadthInches", "heightInches")

/** Deterministic plane geometry, on this device. `DwPhotoMeasure.METHOD_SCALE`'s wire spelling. */
const val DW_TECHNIQUE_SCALE = "SCALE"

/** Deterministic plane geometry via a homography. `DwPhotoMeasure.METHOD_RECTIFIED`'s spelling. */
const val DW_TECHNIQUE_RECTIFIED = "RECTIFIED"

private const val DW_METHOD_PHOTO_GEOMETRY = "PHOTO_GEOMETRY"
private const val DW_METHOD_VISION_MODEL = "VISION_MODEL"

/**
 * The marker for a reading [DwPhotoMeasure] produced on this handset.
 *
 * [technique] arrives as `DwPhotoMeasure.Result.method`, which is already `"SCALE"` or `"RECTIFIED"`
 * — the same two words `GEOMETRY_TECHNIQUES` holds. That is not a coincidence to be tidied into a
 * mapping table: `DwPhotoMeasure` is a port of `frontend/lib/photoMeasure.ts` and the server took its
 * vocabulary from that file on purpose, so passing the value straight through is what keeps the three
 * surfaces from drifting. A technique this server does not know is DROPPED rather than sent, because
 * the server refuses an unknown one and the method is worth recording without it.
 *
 * (Note that `DwMeasureMode.RECTIFY` — the panel's own UI enum — is NOT this word. The mode names a
 * screen; `Result.method` names the geometry that ran, and only the second is on the wire.)
 */
fun dwGeometryMarker(technique: String?): JsonObject = buildJsonObject {
    put("method", JsonPrimitive(DW_METHOD_PHOTO_GEOMETRY))
    if (technique == DW_TECHNIQUE_SCALE || technique == DW_TECHNIQUE_RECTIFIED) {
        put("technique", JsonPrimitive(technique))
    }
}

/**
 * The marker for a reading `POST /media/analyze-measurement` produced, which is the server's OWN
 * marker echoed back unchanged.
 *
 * ECHOED, NOT REBUILT, and the server asks for exactly that: `MeasurementProvenance.marker` is
 * documented as "what a client echoes back, unchanged, when it saves the value it was given", and
 * `marker_body_problems` leaves a marker's key set OPEN specifically so a handset relaying a newer
 * server's extra key is not refused mid-deploy. Rebuilding it here from `provider` / `modelId` /
 * `selfReportedConfidence` would be a second implementation of a shape the server already handed us,
 * and the first thing it would lose is the confidence — a bug already caught once on the server side,
 * where reading `confidence` instead of `selfReportedConfidence` silently dropped the only number on
 * the stamp.
 *
 * [fromServer] is null when the response carried no `methodMarker`: an older deployment, or one of
 * the failure paths. The bare `{"method": "VISION_MODEL"}` fallback still records the fact that
 * matters most — a model produced this and a person accepted it — and every other key is optional.
 */
fun dwVisionMarker(fromServer: JsonObject?): JsonObject =
    fromServer ?: buildJsonObject { put("method", JsonPrimitive(DW_METHOD_VISION_MODEL)) }

/**
 * What `POST /media/analyze-measurement` answered: the numbers, and the marker that says a model
 * produced them.
 *
 * THE MARKER TRAVELS WITH THE NUMBER, in one object, because the two are only ever correct together.
 * `WorkshopRepository.analyzeMeasurement` used to return a bare `Double?` and the marker had nowhere
 * to ride, which is precisely how a model's estimate reached a record wearing a designer's name.
 *
 * [valueInches] is the single-dimension answer (the `height` capture); [lengthInches] / [breadthInches]
 * are the footprint pair. One call fills one side or the other, never both — the endpoint answers in
 * whichever shape it was asked in — and the caller already knows which it asked for.
 */
data class DwMeasurementReading(
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val valueInches: Double? = null,
    /** `MeasurementProvenance.marker()`, verbatim. Null from an older server; see [dwVisionMarker]. */
    val marker: JsonObject? = null,
)

/**
 * One accepted proposal: the marker to send, and the EXACT text that was written into the box.
 *
 * [acceptedText] is the whole anti-staleness mechanism and is stored for no other purpose. See
 * [DwMeasurementMarkers.body].
 */
private data class DwAcceptedMeasurement(val acceptedText: String, val marker: JsonObject)

/**
 * What a record form remembers about the proposals somebody accepted into its dimension boxes.
 *
 * ── THE RULE THIS TYPE EXISTS TO ENFORCE ──────────────────────────────────────────────────────
 *
 * A marker is a CLAIM ABOUT HOW A NUMBER WAS OBTAINED, so it has to stop being sent the moment it
 * stops being true. A designer accepts a geometry reading into "Length (inches)", looks at the object
 * again, and types over it: the value is now typed, and a `PHOTO_GEOMETRY` marker on it is a FALSE
 * CLAIM — strictly worse than no marker at all, because `UNRECORDED` is honest and this is not.
 * Emptying the box is the same failure with nothing left in it to describe.
 *
 * ── HOW IT IS ENFORCED, WHICH IS BY COMPARISON AND NOT BY INTERCEPTION ────────────────────────
 *
 * [accept] records the exact string the proposal put in the box. [body] is handed what the boxes
 * hold AT SAVE TIME and emits a marker only for a column whose current text is still that string.
 * So a marker survives exactly one condition — nobody touched the digits since accepting — and any
 * hand edit, any clearing, any retyping drops it with no listener, no `onValueChange` hook and no
 * flag to keep in step.
 *
 * That is deliberate. The alternative is to clear the marker from each box's own text callback, and
 * it fails the first time somebody adds a fourth way to write a dimension (a paste handler, a prefill
 * from a carried record, an undo) and does not know there was a marker to clear. A comparison cannot
 * be forgotten by code that has not been written yet: a new writer changes the text, the text stops
 * matching, and the marker drops itself.
 *
 * TRIMMED ON BOTH SIDES, so re-entering a box and leaving a trailing space does not drop a marker
 * whose number is unchanged. The digits are compared exactly: "12.0" and "12.00" are a hand edit and
 * drop it, which is right — `DwPhotoMeasurePanel` rounds to the precision its error bar reaches, so
 * the number of digits is itself part of the reading.
 *
 * THE ONE FALSE POSITIVE, STATED RATHER THAN HIDDEN: a designer who accepts 12.4, deletes it, and
 * types "12.4" back by hand keeps the `PHOTO_GEOMETRY` marker. The two states are indistinguishable
 * from the text alone, and the claim is still true of the number — the geometry did produce it. It is
 * not worth a keystroke-level audit trail to separate them.
 *
 * NOT SNAPSHOT STATE. Nothing recomposes on this; it is written from the accept buttons and read once
 * inside `submit()`, so a plain map inside a `remember { }` is the whole requirement.
 */
class DwMeasurementMarkers {
    private val accepted = mutableMapOf<String, DwAcceptedMeasurement>()

    /**
     * Record that [column] now holds [text] because somebody accepted a proposal carrying [marker].
     *
     * Called from the accept button and nowhere else — the same discipline the proposal routes
     * themselves hold, and for the same reason: a marker written anywhere but an acceptance is a
     * claim nobody made.
     */
    fun accept(column: String, text: String, marker: JsonObject) {
        if (column !in DW_MEASUREMENT_DIMENSIONS) return
        if (text.isBlank()) return
        accepted[column] = DwAcceptedMeasurement(text, marker)
    }

    /**
     * The `measurementMethods` body for a save, or null when there is nothing true to say.
     *
     * [current] is what the form's boxes hold right now, keyed by column. Null rather than an empty
     * object on purpose: `ApiClient`'s converter is `explicitNulls = false`, so a null drops the key
     * from the request entirely and the save goes out byte-for-byte as it does today.
     *
     * A COLUMN WITH NO VALUE IS NEVER MARKED, and that is not only honesty — it is a refusal the
     * server makes by name. `marker_body_problems` rejects a marker for a dimension the same request
     * sends no value for ("Send the measurement in the same request, or leave the method out"), and
     * on this platform a 422 is a lost record rather than a message. The blank check below is what
     * keeps that from ever being reachable, and it is the same check that handles a cleared box.
     */
    fun body(current: Map<String, String>): JsonObject? {
        val live = accepted.filter { (column, entry) ->
            val text = current[column]?.trim().orEmpty()
            text.isNotEmpty() && text == entry.acceptedText.trim()
        }
        if (live.isEmpty()) return null
        return buildJsonObject { live.forEach { (column, entry) -> put(column, entry.marker) } }
    }
}
