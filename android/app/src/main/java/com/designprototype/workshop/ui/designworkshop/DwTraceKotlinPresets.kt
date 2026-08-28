package com.designprototype.workshop.ui.designworkshop

import com.offlinetracer.pipeline.Knobs
import com.offlinetracer.pipeline.StylePreset
import com.offlinetracer.pipeline.Styles
import com.offlinetracer.pipeline.SubjectPreset
import com.offlinetracer.pipeline.Subjects
import com.offlinetracer.pipeline.TraceParams

/**
 * **THE TWO PRESET REGISTERS, READ OUT OF THE VENDORED KOTLIN ENGINE — AND THE PLACES WHERE IT AND
 * THE PORTAL DO NOT AGREE.**
 *
 * ── WHAT THIS FILE IS ─────────────────────────────────────────────────────────────────────────
 *
 * `DwTraceRuntime` (`DwSketchTraceEngine.kt:481`) has six members and two of them are preset work:
 * `applyStyle` and `applySubject`. This file is those two, plus the `presets()` tables they are
 * chosen from, backed by `android/core-*` — the four plain Kotlin/JVM modules vendored verbatim from
 * `F:\Offline-Tracer` and hashed file by file in `android/UPSTREAM-MANIFEST-KOTLIN.txt`.
 *
 * Each half is stated at two levels. The `TraceParams` forms carry the judgement and are what the
 * tests exercise; the `DwTraceValues` forms are two-line compositions over `DwTraceKotlinParams.kt`'s
 * codec — `dwTraceParamsOf` in, `dwTraceValuesOfParams` out — and are what a `DwTraceRuntime` calls.
 * That split keeps every decision below testable without a JSON round trip, and keeps the round trip
 * itself in the one file that owns it.
 *
 * ── THERE IS NO KOTLIN TABLE OF PRESET NAMES HERE, AND THERE MUST NOT BE ──────────────────────
 *
 * `DwSketchTracePresets.kt:65-66` states the rule for the surface — "There is no Kotlin table of style
 * names in this repository and there must not be one" — and it binds this file harder, because this
 * file is the one that could most easily have written one. Every id, name, description and group
 * below is read from `Styles.ALL` and `Subjects.ALL` at run time. The ONLY strings this file owns are
 * the divergence notes and the two refusals, and each of them is named, constant and pinned by a test.
 *
 * ── THE TWO REGISTERS ARE NOT THE PORTAL'S, AND THIS IS THE HONEST ACCOUNT OF HOW ─────────────
 *
 * The two registers below belong to the KOTLIN engine, and the portal's belong to the TypeScript one.
 * They are two vendorings of one upstream and they are not identical, which is what this section is
 * for. All figures were checked against the trees in this repository on 2026-08-27.
 *
 * There was briefly a sharper way to check them: a second runtime in this same APK ran the TypeScript
 * engine in an `androidx.javascriptengine` isolate, so the two tables could be compared inside one
 * process. That route is deleted. What replaces it as the mechanical check is
 * `DwTraceKotlinPresetsTest`, which reads `frontend/lib/trace/engine/styles.ts` itself off disk rather
 * than a copy of it — a weaker instrument for display text and an equally strong one for the ids,
 * which are the part that is binding.
 *
 * **STYLES AGREE ON THE THING THAT IS BINDING.** Both registers carry the same twenty ids in the same
 * order — `clean-line` … `minimal` — and the id is what is written into `TraceParams.styleId` and
 * therefore into anything persisted (`Styles.kt:12-14`, `styles.ts:15-17`). A project moves between
 * clients. `DwTraceKotlinPresetsTest` pins that against `frontend/lib/trace/engine/styles.ts` itself
 * rather than against a copy of it.
 *
 * What differs is display text, which both registers say may differ: **one name** (`comic` is
 * "Comic ink" here and "Comic" on the portal) and **fourteen of the twenty groups**, because the
 * engines group into different sets — four here (Drawing, Print & relief, Fabrication, Education)
 * against five there (Line art, Drawing, Technical, Print & relief, Making), with only "Print & relief"
 * common. A designer therefore reads "Fabrication · Technical drawing" on a handset running this
 * runtime and "Technical · Technical drawing" on a laptop. Nothing about the drawing changes.
 *
 * **SUBJECTS DISAGREE ON THE SET, AND THAT IS NOT SOMETHING TO PICK A WINNER FOR SILENTLY.** This
 * engine has TWELVE (`Subjects.kt:408-421`); the portal has TEN (`subjects.ts:89`). Nine ids are
 * shared and spelled identically. The other three here — `wood-carving`, `stone-carving`, `metalwork`
 * — have no portal row, and the portal's `carving` ("Wood & stone carving") has no row here.
 *
 * This file takes **the upstream twelve**, because they are what the engine actually contains and a
 * filtered copy of somebody else's register is a second register that drifts — the failure this
 * repository has already shipped twice. It then says so where a designer can see it:
 * [DW_TRACE_SUBJECT_DIVERGENCE_NOTES] puts a sentence on each of the three rows the portal has no
 * match for, and [dwTraceKotlinNoSuchSubjectSentence] answers a portal-only id with the remedy rather
 * than with "no such thing".
 *
 * That refusal is not hypothetical. `DwSketchTraceParams.kt:1094` maps the `DECORATIVE` product
 * category to `"carving"`, and `DwSketchTracePanel.kt:404` seeds the subject picker from it — so a
 * decorative record opened under this runtime arrives holding an id this register does not carry.
 * Changing that map is an owner's call about which register wins and is left alone here.
 *
 * ── AND THE ONE BEHAVIOURAL DIFFERENCE, WHICH MATTERS MORE THAN EITHER LIST ───────────────────
 *
 * **A SUBJECT IS IDEMPOTENT ON THE PORTAL AND COMPOUNDS HERE.** The TypeScript tables are *absolute*
 * overrides pushed through `withOverrides` (`subjects.ts:56-63`), so re-applying one is a no-op, and
 * `subjects.ts:41-43` says so — which `DwSketchTracePresets.kt:153-154` and `DwSketchTraceEngine.kt:524`
 * both rely on when they explain why the panel may re-adjust freely. The Kotlin tables are *relative*:
 * every one of the twelve `adjust` bodies is built from `scale()` and `raise()`, so a second
 * application multiplies again. `painting` on `clean-line` takes `cleanup.minBlobArea` 24 → 60 → 150.
 * `Subjects.kt:21-24` claims only the weaker property — that applying twice cannot leave the legal
 * range — and that claim is true.
 *
 * Nothing here "fixes" that. Correcting it would be a third behaviour, belonging to neither engine,
 * invented in the adapter — and vendored judgement is not this repository's to improve. Instead it is
 * pinned by a test so it cannot become a surprise, stated on every subject row by
 * [DW_TRACE_SUBJECT_COMPOUNDS_NOTE] with the remedy that actually works, and handed to an owner as a
 * decision rather than absorbed as an accident.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The sentences this file owns
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Appended to **every** subject row, because it is true of every one of the twelve.
 *
 * See the file header. The remedy is the real one: [dwTraceKotlinApplyStyle] returns the style's
 * whole tree and discards what was there, so re-picking the style is a genuine reset to the preset.
 */
internal const val DW_TRACE_SUBJECT_COMPOUNDS_NOTE: String =
    "Tapping this again adjusts the settings that are on screen now, so it compounds; pick the " +
        "style again to start from the preset."

/**
 * The subjects this engine carries that the portal's register has no row for, in [Subjects.ALL] order.
 *
 * Pinned against `frontend/lib/trace/engine/subjects.ts` by `DwTraceKotlinPresetsTest`, so the day
 * either register changes the test fails and somebody decides, rather than the two drifting quietly.
 */
internal val DW_TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE: List<String> =
    listOf("wood-carving", "stone-carving", "metalwork")

/** The reverse: the portal's subject ids this engine has no row for. Same pinning. */
internal val DW_TRACE_SUBJECTS_ONLY_ON_THE_PORTAL: List<String> = listOf("carving")

/**
 * The sentence each unmatched subject row carries, on screen, under the material's own hint.
 *
 * Keyed by id and covering exactly [DW_TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE] — the test asserts the two
 * agree, so a row cannot be added to one without the other. Two sentences rather than one because the
 * two facts are different: two rows here are one row there, and one row here is no row there.
 */
internal val DW_TRACE_SUBJECT_DIVERGENCE_NOTES: Map<String, String> = linkedMapOf(
    "wood-carving" to DW_TRACE_SPLIT_CARVING_NOTE,
    "stone-carving" to DW_TRACE_SPLIT_CARVING_NOTE,
    "metalwork" to
        "The portal has no metalwork material at all, so this choice exists only on the handset.",
)

/** Shared by the two halves of the portal's single `carving` row. Declared once so it reads once. */
private const val DW_TRACE_SPLIT_CARVING_NOTE: String =
    "The portal carries wood and stone as one “Wood & stone carving” material, so a laptop cannot " +
        "tell which of the two was chosen here."

/* ────────────────────────────────────────────────────────────────────────────
 * The tables
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `Styles.ALL` and `Subjects.ALL` as the picker reads them.
 *
 * Built once. `Styles.ALL` and `Subjects.ALL` are immutable `val`s initialised with the objects
 * themselves, so nothing about this can change between calls and re-mapping thirty-two entries on
 * every composition would buy nothing.
 */
internal fun dwTraceKotlinPresetTables(): DwTracePresetTables = DW_TRACE_KOTLIN_TABLES

private val DW_TRACE_KOTLIN_TABLES: DwTracePresetTables = DwTracePresetTables(
    styles = Styles.ALL.map(::dwTraceKotlinStyleRow),
    subjects = Subjects.ALL.map(::dwTraceKotlinSubjectRow),
)

/**
 * One style row, in [Styles.ALL] order — which is the display order the register calls binding
 * alongside the ids (`Styles.kt:911-913`, "append to the end, never renumber").
 *
 * The order is NOT grouped-contiguous in either engine: `Styles.ALL` runs Drawing, Drawing,
 * Fabrication, Fabrication, Drawing, so a group recurs after another has intervened. That is exactly
 * why `dwTraceStyleOptions` folds the group into the label instead of drawing sticky headers over the
 * list — see its own KDoc, which makes the argument from the search box.
 */
private fun dwTraceKotlinStyleRow(style: StylePreset): DwTracePreset = DwTracePreset(
    id = style.id,
    name = style.name,
    description = style.description,
    group = style.group,
)

/**
 * One subject row: the engine's own hint, then the sentences this client owes the designer.
 *
 * [DwTracePreset.group] is empty because subjects are a flat list — the same shape
 * `DwSketchTracePresets.kt:101-103` builds its picker against, and the same value `bridge.ts:442` sends
 * for the TypeScript register, so the two runtimes hand the surface the same shape.
 */
private fun dwTraceKotlinSubjectRow(subject: SubjectPreset): DwTracePreset = DwTracePreset(
    id = subject.id,
    name = subject.name,
    description = buildString {
        append(subject.hint)
        append(' ')
        append(DW_TRACE_SUBJECT_COMPOUNDS_NOTE)
        DW_TRACE_SUBJECT_DIVERGENCE_NOTES[subject.id]?.let {
            append(' ')
            append(it)
        }
    },
    group = "",
)

/* ────────────────────────────────────────────────────────────────────────────
 * Applying a style
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `Styles.byId(styleId).params`, WHOLE — a style is a complete tree, not a diff.
 *
 * ── [base] IS READ AND DISCARDED, ON PURPOSE ──────────────────────────────────────────────────
 *
 * `styles.ts:19-21` puts it in one sentence — "a user who switches styles expects the second one to
 * look like itself rather than like a blend of the two" — and `Styles.kt:6-10` argues the same from
 * the other end: the presets differ structurally, in engine and vector mode, not cosmetically.
 *
 * `DwTraceRuntime.applyStyle` leaves room for an implementation to keep what a style legitimately does
 * not name, and the sibling runtime already answered that question — `bridge.ts:475-484` takes the
 * base, writes `void paramsJson` and returns the preset alone. **Two runtimes behind one button must
 * not disagree**, so this one discards it too, and the parameter stays in the signature so the discard
 * is visible at the place a reader would look for it rather than being a missing argument nobody
 * notices.
 *
 * The knob protection that [dwTraceKotlinApplySubject] honours is deliberately absent here.
 * API-CONTRACT §4.2 gives a style no such guard, and the panel treats a style as its new baseline
 * (`DwSketchTracePanel.kt:676`). A style that quietly kept six values from the last one would be the
 * blend both registers say a designer does not expect.
 *
 * One difference from the TypeScript register is worth knowing about even though it changes nothing
 * here. There, `preset()` FORCES `params.styleId` to the preset's own id, so "a copy-pasted entry
 * cannot ship a style that reports itself as a different one" (`styles.ts:46-47`). Here each of the
 * twenty writes its own `styleId` by hand (`Styles.kt:51`), so the guarantee is a convention rather
 * than a construction — which is why `DwTraceKotlinPresetsTest` asserts `params.styleId == id` for
 * all twenty rather than assuming it.
 *
 * @throws IllegalArgumentException with [dwTraceKotlinNoSuchStyleSentence] for an id this register
 *   does not carry. A refusal and not `Styles.default()`: falling back would put `clean-line` on
 *   screen under the name of whatever was asked for, which is a silent substitution. The panel prints
 *   the message (`DwSketchTracePanel.kt:669`).
 */
@Suppress("UNUSED_PARAMETER")
internal fun dwTraceKotlinApplyStyle(base: TraceParams, styleId: String): TraceParams {
    val preset = Styles.byId(styleId)
        ?: throw IllegalArgumentException(dwTraceKotlinNoSuchStyleSentence(styleId))
    // `sanitized()` is a fixpoint on every preset — `StylesTest` asserts it (`Styles.kt:16-19`) — so
    // this changes nothing today. It is here because the sanitiser is the sole authority on legality
    // and a tree that has not been through it is a tree nobody has checked; `bridge.ts:483` calls it
    // on the same value for the same reason.
    return preset.params.sanitized()
}

/* ────────────────────────────────────────────────────────────────────────────
 * Applying a subject
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `Subjects.byId(subjectId).adjust(base)`, with the style's identity and the designer's own knobs
 * restored on top.
 *
 * ── THE THREE STEPS ARE `Auto.decide`'s OWN, MINUS THE ONE THAT NEEDS A PHOTOGRAPH ────────────
 *
 * `Subjects.kt:582-584` does `preserveIdentity(params, subject.adjust(params))`, then a matte decision
 * measured from the classifier, then `Knobs.restore(params, …, auto.handTuned).sanitized()`. This does
 * the first and the third. The middle one is skipped because it takes a `Classify.SourceProfile`,
 * which only a run of the pipeline over real pixels can produce — so the subject's own matte request
 * stands here, exactly as it does on the portal (`bridge.ts:487-491`), and the measurement overrules
 * it later inside the trace if it disagrees.
 *
 * ── WHY THE TWO GUARDS ARE ENFORCED RATHER THAN TRUSTED ───────────────────────────────────────
 *
 * API-CONTRACT §4.3 states both as promises of the register: `adjust` "never touches `edge.engine`,
 * `output.vectorMode`, `output.fillClosed` or `styleId` — those are what a style *is*", and the
 * automatic path "never overwrites a knob named in `handTuned`". Today the twelve tables keep the
 * first promise unaided: those four assignments appear nowhere in `Subjects.kt` outside
 * `preserveIdentity` itself (`:664-669`), verified 2026-08-27.
 *
 * Enforcing anyway is upstream's own reasoning, quoted from `Subjects.kt:657-661`: the subject tables
 * "are data, they are edited by hand, and this is the one path that applies one *without a user having
 * asked for it* — so it is the one path where a table with a stray `engine =` in it would change
 * somebody's export with nothing on screen to explain it." The panel re-applies a subject on top of
 * whatever is there, on a tap, after any amount of hand tuning, so this is that path on this client.
 *
 * `auto` is carried across with the four identity fields, matching `preserveIdentity` exactly. It is
 * what holds `handTuned` and `mode`, and the panel asks for `auto.mode = OFF` on previews
 * (`DwSketchTraceEngine.kt:493-495`) — a subject table that reset either would be undoing a decision
 * made elsewhere on the screen.
 *
 * **THE HAND-TUNED RESTORE IS A NO-OP TODAY AND IS STILL NOT DEAD CODE.** No client writes
 * `auto.handTuned`: `DwSketchTraceStages.kt:180-185` records that the portal never sets any `auto.*`
 * leaf, and this panel builds no such set either, so [Knobs.restore] returns its input unchanged.
 * `sanitizeAutoParams` does not validate the list against the six names, so the day somebody does
 * write it, an unprotected knob fails silently — which is the argument `DW_TRACE_KNOBS` was written
 * under and the reason the wiring exists before the feature that needs it.
 *
 * ── WHAT THIS FUNCTION DOES NOT PROMISE ───────────────────────────────────────────────────────
 *
 * **It is not idempotent.** See the file header: the Kotlin tables adjust relatively, so a second
 * application adjusts a second time. Callers that re-apply must expect a changed tree, the surface
 * says so on every row through [DW_TRACE_SUBJECT_COMPOUNDS_NOTE], and the panel's own
 * `dwTraceOverwriteNotice` names every setting that moved (`DwSketchTracePanel.kt:698`).
 *
 * @throws IllegalArgumentException with [dwTraceKotlinNoSuchSubjectSentence] for an unknown id.
 */
internal fun dwTraceKotlinApplySubject(base: TraceParams, subjectId: String): TraceParams {
    val preset = Subjects.byId(subjectId)
        ?: throw IllegalArgumentException(dwTraceKotlinNoSuchSubjectSentence(subjectId))
    val adjusted = dwTraceKotlinPreserveIdentity(base, preset.adjust(base))
    return Knobs.restore(base, adjusted, base.auto.handTuned).sanitized()
}

/**
 * Restores the four fields that make a style what it is, plus the `auto` block.
 *
 * A LOCAL MIRROR OF `Subjects.kt:663-671`, WHICH IS `private` THERE. The vendored file is copied
 * verbatim and hashed in `android/UPSTREAM-MANIFEST-KOTLIN.txt`, so widening its visibility is not
 * available; six lines that a test compares field by field against the contract is the cheaper of the
 * two honest options. `DwTraceKotlinPresetsTest` walks all twenty styles against all twelve subjects
 * and asserts the four fields survive, which is the check that would catch this copy going stale.
 */
private fun dwTraceKotlinPreserveIdentity(before: TraceParams, after: TraceParams): TraceParams = after.copy(
    edge = after.edge.copy(engine = before.edge.engine),
    output = after.output.copy(
        vectorMode = before.output.vectorMode,
        fillClosed = before.output.fillClosed,
    ),
    styleId = before.styleId,
    auto = before.auto,
)

/* ────────────────────────────────────────────────────────────────────────────
 * The two refusals
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * "There is no style called …" — `bridge.ts:477`'s sentence, word for word.
 *
 * ── THE WORDS ARE SHARED; THE ENVELOPE AROUND THEM IS NOT, AND THAT IS A REAL MISMATCH ────────
 *
 * The panel prints `"That style could not be applied. "` followed by whatever the runtime threw
 * (`DwSketchTracePanel.kt:669`), so on this path a designer reads exactly this sentence and nothing
 * else. On the JavaScript path the same words come back inside an error envelope, and `expectValue`
 * turns any envelope it cannot use into `DwTraceHostFailure(PROTOCOL_UNREADABLE, …)`
 * (`DwSketchTraceWire.kt:1038-1045`) — whose sentence is "The tracing engine answered with something
 * this app could not read… Try once more; if it happens again, report the app version" with the real
 * reason in brackets at the end. One cause, two readings, and the JavaScript one blames the engine
 * for a mistyped id and sends the designer to a laptop.
 *
 * Mirroring that here would be mirroring the defect, so this throws a plain `IllegalArgumentException`
 * carrying the sentence and nothing else. `DwTraceFailureKind` has no member that fits — every one of
 * them is about the sandbox, the protocol or memory — and pressing `PROTOCOL_UNREADABLE` into service
 * would be saying something untrue about what happened. The mismatch on the other path is reported
 * rather than copied.
 */
internal fun dwTraceKotlinNoSuchStyleSentence(styleId: String): String =
    "There is no style called \"${styleId.trim()}\"."

/**
 * "There is no subject called …" — the same sentence, **with the register difference spelled out when
 * that is what actually happened.**
 *
 * The bare form matches `bridge.ts:489`. The longer form fires for the ids in
 * [DW_TRACE_SUBJECTS_ONLY_ON_THE_PORTAL], where "there is no such subject" would be true and useless:
 * the id is real, it is the portal's, and the designer's device seeded it from the record's own
 * category. Naming the three rows that replaced it turns a dead end into one tap.
 */
internal fun dwTraceKotlinNoSuchSubjectSentence(subjectId: String): String {
    val bare = "There is no subject called \"${subjectId.trim()}\"."
    if (subjectId.trim() !in DW_TRACE_SUBJECTS_ONLY_ON_THE_PORTAL) return bare
    return bare + " The portal's list has ten materials and this engine's has twelve: “Wood & stone " +
        "carving” is split into “Wood carving” and “Stone carving”, and “Metalwork” is added. Choose " +
        "one of those."
}

/* ────────────────────────────────────────────────────────────────────────────
 * The same two halves, at the boundary a runtime actually calls
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * [dwTraceKotlinApplyStyle] as `DwTraceRuntime.applyStyle` needs it.
 *
 * ── WHY THIS IS A SEPARATE FUNCTION RATHER THAN THE ONLY ONE ──────────────────────────────────
 *
 * Everything above is decided on the engine's own `TraceParams`, so the decisions are testable with
 * no JSON in the way, and the day a leaf is added to the vendored tree they keep working untouched.
 * This pair is the whole of the translation, and it lives beside the decisions rather than inside
 * them: `dwTraceParamsOf` in, `dwTraceValuesOfParams` out, both from `DwTraceKotlinParams.kt`, which
 * is the one file allowed to know how the tree and the flat map correspond.
 *
 * NOT `suspend`, and not on a dispatcher. Applying a preset is a handful of field copies and one
 * `sanitized()` — microseconds — so the `withContext` that `DwTraceRuntime` requires belongs at the
 * implementation of the interface, where the same wrapper also covers `trace`. Putting it here would
 * be paying for a thread hop per slider tap to guard arithmetic that never blocks.
 *
 * @throws IllegalArgumentException for an unknown id, exactly as the `TraceParams` form does.
 */
internal fun dwTraceKotlinApplyStyle(base: DwTraceValues, styleId: String): DwTraceValues =
    dwTraceValuesOfParams(dwTraceKotlinApplyStyle(dwTraceParamsOf(base), styleId))

/**
 * [dwTraceKotlinApplySubject] as `DwTraceRuntime.applySubject` needs it. See the note above.
 *
 * The round trip is what makes the two guards reach the wire at all: `base` arrives as text, so
 * `auto.handTuned` — the one leaf `dwTraceFlatten` deliberately skips, because it is an array and no
 * control reads it (`DwSketchTraceWire.kt:1144-1146`) — is recovered from the tree by
 * [dwTraceParamsOf] rather than from the flat map, and is therefore honoured here even though nothing
 * on this screen can see it.
 */
internal fun dwTraceKotlinApplySubject(base: DwTraceValues, subjectId: String): DwTraceValues =
    dwTraceValuesOfParams(dwTraceKotlinApplySubject(dwTraceParamsOf(base), subjectId))
