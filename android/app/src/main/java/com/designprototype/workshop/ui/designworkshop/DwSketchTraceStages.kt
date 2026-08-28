package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Immutable

/**
 * **THE TWELVE STAGES A TRACE GOES THROUGH, AND THE SIX KNOB NAMES THE ENGINE PROTECTS.**
 *
 * Two tables, both of which are CONTRACTS WITH A FILE NOBODY HERE MAY EDIT, which is why they are in
 * a file of their own with a test that reads the vendored TypeScript and fails when either drifts.
 * Everything else about the trace surface is a design decision; these two are transcriptions, and a
 * transcription that nothing checks is a transcription that is already wrong.
 *
 * ── THE LABELS BELOW ARE FOR WEIGHTING AND FOR THE TEST. THEY ARE NOT WHAT IS DRAWN ───────────
 *
 * The progress row renders [DwTraceProgress.label] — the string the ENGINE sent with the event — and
 * never [DwTraceStage.label] from this table. `worker/trace.worker.ts:114-117` records why: re-typing
 * engine wording in a client is how "the two clients would eventually describe one operation
 * differently". This table exists so the bar can be weighted (below) and so a vendored update that
 * inserts a thirteenth stage fails a unit test instead of silently mis-labelling a progress bar.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The stages
 * ──────────────────────────────────────────────────────────────────────────── */

/** One pipeline stage. [id] is stable — "the UI keys its progress rows on them" (`pipeline.ts:210`). */
@Immutable
data class DwTraceStage(val id: String, val label: String)

/**
 * `engine/pipeline.ts`'s `STAGES`, in execution order, transcribed exactly.
 *
 * Two of them run their bodies only under a condition and **every one of them still fires** —
 * `matte` posts progress and records a timing whether or not `matte.mode` is anything but NONE
 * (`pipeline.ts:394`), and `crop` does the same unless `auto.mode` is APPLY (`:457`). So there is no
 * way to tell a skipped stage from a fast one at this boundary, and **the surface must not invent a
 * "skipped" state**. It shows the label the engine sent.
 */
val DW_TRACE_STAGES: List<DwTraceStage> = listOf(
    DwTraceStage("prepare", "Preparing image"),
    DwTraceStage("matte", "Separating background"),
    DwTraceStage("crop", "Cropping to the subject"),
    DwTraceStage("gray", "Converting to grey"),
    DwTraceStage("denoise", "Reducing noise"),
    DwTraceStage("contrast", "Enhancing contrast"),
    DwTraceStage("edge", "Detecting edges"),
    DwTraceStage("cleanup", "Cleaning up"),
    DwTraceStage("skeleton", "Thinning strokes"),
    DwTraceStage("distance", "Measuring stroke width"),
    DwTraceStage("vectorize", "Tracing vectors"),
    DwTraceStage("document", "Assembling document"),
)

/** Twelve, from the table rather than from anybody's memory. */
val DW_TRACE_STAGE_COUNT: Int = DW_TRACE_STAGES.size

/** Position in execution order, or -1 for an id this build has never heard of. */
fun dwTraceStageIndex(stageId: String): Int = DW_TRACE_STAGES.indexOfFirst { it.id == stageId }

/**
 * The two stages that dominate the wall clock, named so a progress UI can be built knowing it.
 *
 * The feasibility spike's stage timings put **13,037 of 16,655 ms** in `edge` alone at the product's
 * input cap with the shipped flow engine, and `vectorize` is the other long one. `gray` and
 * `distance` are near-instant, and `distance` does not run at all unless `output.modulateWidth` is on.
 * A bar driven off `index / 12` therefore RUSHES to a half and then appears to hang for most of the
 * trace, which reads as a crash. Hence [DwTraceProgressWeights].
 */
val DW_TRACE_SLOW_STAGE_IDS: List<String> = listOf("edge", "vectorize")

/* ────────────────────────────────────────────────────────────────────────────
 * How far along a trace really is
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Turns a stage boundary into a bar position, using what THIS DEVICE measured last time.
 *
 * ── WHY NOT JUST USE THE FRACTION THE ENGINE SENDS ────────────────────────────────────────────
 *
 * Because it is a stage COUNT and not a time estimate. `pipeline.ts:244` posts `index / 12` at the
 * START of each stage, so the events are 0.000, 0.083, 0.167 … 0.917 whatever the stages cost, and
 * the last one ever sent is 0.917 — **the engine's fraction never reaches 1.0.** On a laptop the
 * distortion is a shrug; on a phone where `edge` is four fifths of a twenty-second wait, a bar that
 * sits at 0.5 for sixteen seconds is a bar a designer stops believing.
 *
 * ── AND WHY THE WEIGHTS ARE MEASURED RATHER THAN GUESSED ──────────────────────────────────────
 *
 * `TraceResult.stages` carries `{id, label, millis}` per stage (`pipeline.ts:58-62`) and survives the
 * worker boundary as `SerializedStage` (`worker/protocol.ts:49-54`), so after the first full trace on
 * a device the true shape of the curve is already in hand — for THIS phone, THIS photograph and THESE
 * parameters, which is better than any table could be. It costs no new engine surface and no new
 * measurement. Before that first trace, [Unweighted] is the engine's own `index / 12`, honestly
 * labelled as the thing it is.
 *
 * ── WHAT THIS DELIBERATELY DOES NOT DO ────────────────────────────────────────────────────────
 *
 * It does not interpolate WITHIN a stage. The engine reports boundaries and nothing else, so a bar
 * that crept forward during `edge` would be an animation of a number nobody measured — and the one
 * stage where a designer most wants to know is the one where the creeping would be pure invention.
 * The stage LABEL is the primary signal and the bar is secondary, which is the same order of
 * importance the timings above imply.
 */
@Immutable
class DwTraceProgressWeights private constructor(
    private val startFractions: Map<String, Float>,
    /** True when the weights came from a real run on this device rather than from the stage count. */
    val measured: Boolean,
) {

    /**
     * The bar position at the start of [stageId].
     *
     * Falls back to [fallback] — the fraction the engine itself sent — for an id this build does not
     * know, which is what a vendored update that adds a stage looks like from here. The unit test
     * turns that into a build failure; this keeps the running app honest in the meantime.
     */
    fun fractionAt(stageId: String, fallback: Float): Float =
        startFractions[stageId] ?: fallback

    companion object {
        /** The engine's own `index / 12`, for a device that has not yet completed a full trace. */
        val Unweighted: DwTraceProgressWeights = DwTraceProgressWeights(
            startFractions = DW_TRACE_STAGES.withIndex().associate { (index, stage) ->
                stage.id to index.toFloat() / DW_TRACE_STAGE_COUNT
            },
            measured = false,
        )

        /**
         * Weights from a completed run's own timings.
         *
         * Returns [Unweighted] rather than a division by zero when the timings are empty or sum to
         * nothing — which is a real case and not a defensive one: a preview reports no timings at all,
         * and a trace of a blank sheet can finish fast enough for every stage to round to zero.
         */
        fun from(timings: List<DwTraceStageTiming>): DwTraceProgressWeights {
            val total = timings.sumOf { it.millis }
            if (timings.isEmpty() || total <= 0L) return Unweighted
            var elapsed = 0L
            val out = LinkedHashMap<String, Float>(timings.size)
            for (timing in timings) {
                out[timing.id] = elapsed.toFloat() / total.toFloat()
                elapsed += timing.millis
            }
            return DwTraceProgressWeights(startFractions = out, measured = true)
        }
    }
}

/**
 * A sentence naming the stage a trace is in, for a screen reader and for a live region.
 *
 * "Stage 7 of 12" and not a percentage, because the percentage is the thing this file has just spent
 * a page explaining is not a time estimate. The label is the engine's. A stage the given list does
 * not know is described by its label alone rather than by a wrong number.
 *
 * ── [stages] IS A PARAMETER BECAUSE THE COUNT IS THE RUNTIME'S TO SAY, NOT THIS TABLE'S ───────
 *
 * It used to read [DW_TRACE_STAGES] directly, and that was correct while the only runtime was the
 * JavaScript one whose engine reports exactly these twelve. The Kotlin engine the handset now runs
 * reports **nineteen**, because it separates steps the TypeScript fuses — `prepare` is `orient` +
 * `perspective` + `downscale`, `cleanup` is `binarise` + `morphology` + `blobs` + `bridge`, and three
 * ids are spelled differently. Seven ids appear in both lists and five of those seven sit at a
 * different position, so reading the id out of this table would have announced "Stage 2 of 12" while
 * the engine was on stage 7 of 19 — a wrong number spoken confidently, which is worse than the label
 * alone. `DW_TRACE_KOTLIN_STAGES` is what the panel passes and it is built from `Stages.ALL` at run
 * time, so a vendored update that adds a stage moves this sentence with it and needs no edit here.
 *
 * The default stays [DW_TRACE_STAGES] so the table above is still exercised by its own test, and so a
 * caller with no runtime in hand gets the documented twelve rather than nothing.
 */
fun dwTraceProgressSentence(
    progress: DwTraceProgress,
    stages: List<DwTraceStage> = DW_TRACE_STAGES,
): String {
    val index = stages.indexOfFirst { it.id == progress.stageId }
    return if (index < 0) {
        progress.label
    } else {
        "${progress.label}. Stage ${index + 1} of ${stages.size}."
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The knobs the engine protects
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `engine/params.ts`'s `Knobs`, transcribed exactly, keyed by the constant name it uses.
 *
 * ── WHY A SURFACE THAT DOES NOT USE AUTO-DETECTION STILL CARRIES THIS ─────────────────────────
 *
 * `params.ts:725-731` calls these strings **"a contract with the UI: the Android editor's `KNOBS`
 * table uses exactly these labels"**. The upstream had an Android editor, and these six strings are
 * how it told the engine which values a person had set by hand, so that auto-detection could put them
 * back after a preset overwrote them (`restoreHandTuned`, `params.ts:752`).
 *
 * **No shipping client has ever exercised that contract.** `grep -rn "handTuned\|AutoMode\|auto:"
 * frontend/components/sketches/upload/SketchTraceField.tsx` returns nothing (verified 2026-08-27), so
 * the portal never writes any of the six `auto.*` leaves and the protection has never been asked for.
 * `sanitizeAutoParams` trims and de-duplicates the list (`params.ts:702-710`) but does **not** validate
 * it against `KNOB_NAMES`, so a typo would be silently unprotected: auto-detection would overwrite the
 * hand-set value and nothing anywhere would say so.
 *
 * So this table exists now, pinned by a test now, for the day somebody offers `AutoMode.APPLY` on this
 * client. Writing it later, from memory, next to the feature that needs it, is exactly how the typo
 * gets in.
 */
val DW_TRACE_KNOBS: Map<String, String> = linkedMapOf(
    "EDGE_SENSITIVITY" to "edge sensitivity",
    "STROKE_WIDTH" to "stroke width",
    "SIMPLIFY" to "simplify",
    "CORNER" to "corner threshold",
    "MIN_PATH_LENGTH" to "minimum path length",
    "MIN_BLOB_AREA" to "minimum blob area",
)

/** Every protected knob, in the order the engine's own `KNOB_NAMES` lists them. */
val DW_TRACE_KNOB_NAMES: List<String> = DW_TRACE_KNOBS.values.toList()

/**
 * The control each protected knob names, so a future `AutoMode.APPLY` can build `handTuned` from the
 * rows a designer actually moved rather than from a hand-written list.
 *
 * The mapping is the reason the wire strings are not the labels: the engine says "corner threshold"
 * and the panel says "Keep corners", and both are correct in their own vocabulary. A client that sent
 * its own label would be sending a string `restoreHandTuned` has never heard of.
 */
val DW_TRACE_KNOB_KEY_FOR_NAME: Map<String, String> = mapOf(
    "edge sensitivity" to "edge.sensitivity",
    "stroke width" to "output.strokeWidth",
    "simplify" to "output.simplify",
    "corner threshold" to "output.corner",
    "minimum path length" to "output.minPathLength",
    "minimum blob area" to "cleanup.minBlobArea",
)
