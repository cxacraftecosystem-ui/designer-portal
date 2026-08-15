package com.designprototype.workshop.data

import java.util.Locale

/**
 * WHAT THIS PARTICULAR HANDSET COULD RUN AN AI MODEL FOR, DECIDED FROM NUMBERS THE PHONE ITSELF
 * REPORTED — AND, TODAY, THE HONEST ANSWER THAT NOTHING HAS BEEN WEIGHED YET.
 *
 * ── WHY THIS FILE EXISTS ──────────────────────────────────────────────────────────────────────
 *
 * docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §2.1, "The device decides the tier and the model.
 * DECIDED." There is no one global answer to "can this app summarise on the phone": a 4 GB handset
 * and a 12 GB flagship are different products for this purpose, and pretending otherwise means
 * either shipping nothing or shipping a crash. So the app PROBES and RECOMMENDS — the same move the
 * language-pack screen already makes when it asks the recogniser which packs exist instead of
 * assuming (`DwLanguagePacks.kt`).
 *
 * ── THE SAFEGUARD THAT MAKES DEVICE-DEPENDENT TIERING PERMISSIBLE AT ALL ──────────────────────
 *
 * A fleet where one phone summarises locally and another sends the audio to a server produces TWO
 * CLASSES OF WORKSHOP RECORD, and without provenance they are indistinguishable on the page. Plan
 * §3 therefore requires every AI layer to record the tier and the model that produced it, and
 * §2.1 says the two ship together or not at all. THAT HALF IS ALREADY BUILT AND IS NOT SPECULATIVE:
 * `backend/app/services/ai_layers.py` refuses to write a layer without a provider and a model id,
 * and `DwAiLayer.tier` / `DwAiLayer.modelId` in `backend/prisma/schema.prisma` are NOT NULL. [DwAiTier]
 * below is the handset's copy of that vocabulary, spelled with the same three names on purpose.
 *
 * ── WHY THE DECISION IS PURE ──────────────────────────────────────────────────────────────────
 *
 * Everything in this file is plain Kotlin over plain numbers: no Context, no `android.os`, no
 * Compose. That is what lets the desktop JVM test it (`DwDeviceTierTest`), and this repository's
 * catalogue of shipped defects says the untestable half is the half that is wrong. The platform
 * calls that FILL a [DwDeviceMeasurement] live in `DwDeviceProbe.kt`; what the numbers MEAN lives
 * here. Same split as `DwLanguagePacks.kt` / `ui/designworkshop/DwLanguagePackUi.kt`, same reason.
 *
 * ── THE HONEST-UNKNOWN RULE, WHICH IS MOST OF WHAT THIS FILE DOES TODAY ───────────────────────
 *
 * **THERE IS NO TIER 2 MODEL FOR ANY DEVICE, AND SAYING SO IS THE FEATURE.**
 * docs/DEVICE-TIER-MEASUREMENT.md records the state of the evidence: whether Google publishes a
 * mobile export of a Gemma 4 model for Android at all — and if so its artifact id, quantisation and
 * on-disk size — is UNVERIFIED, and no peak RSS has been measured on the fleet's M32 at any context
 * cap. So [DW_TIER2_CATALOGUE] is empty and **no device is offered a Tier 2 model**.
 *
 * TWO SENTENCES SAY THAT, NOT ONE, and only the first names the document. Everything above the
 * measurement doc's first row gets [DwTierRefusal.NO_MEASURED_MODEL], which names that document and
 * the two questions that would fill it. A handset IN that first row — [DwDeviceClass.LOW_RAM] — gets
 * [DwTierRefusal.DEVICE_TOO_SMALL] instead, because both refusals are true of it and only one of
 * them avoids promising that the next update might change the answer. See [dwTier2Offer], where the
 * ordering is argued in full.
 *
 * That is NOT a stub. It is the same answer [DwPackState.UNKNOWN] gives for a question the platform
 * will not answer, and it is the whole point of the discipline: a recommender that named a model
 * nobody has weighed would be the defect. [DwModelPlan] cannot even be CONSTRUCTED without the
 * handset a peak RSS was measured on, so there is no quiet path to a plausible-looking entry.
 *
 * ── THE ONE SIGNAL THIS FILE DELIBERATELY REFUSES TO READ ─────────────────────────────────────
 *
 * **`ActivityManager.getMemoryClass()` IS NOT READ ANYWHERE, AND ITS ABSENCE IS A DECISION.**
 * It is the DALVIK HEAP cap for Java objects. A LiteRT or ONNX model allocates NATIVELY — outside
 * that cap and outside its accounting — so `getMemoryClass()` reports something like 192 MB on a
 * handset that could comfortably hold a 2 GB model, and reports nothing useful at all about the one
 * that could not. Gating on it would be measuring the wrong thing confidently, which is the failure
 * mode this repository keeps finding and writing up. The next reader WILL reach for it, because it
 * is the obvious-looking call with "memory" in the name; this paragraph is here for them.
 *
 * AND IT IS NO LONGER A PREDICTION. Measured 2026-08-12 on the fleet's SM-M325F: **256 MB from
 * `getMemoryClass()` against 5,927,968,768 bytes of `totalMem` — 22.1×.** A 1.5 GB model would be
 * 5.6× the heap cap and 0.25× the phone's memory: two answers pointing opposite ways, and gating on
 * the wrong one would refuse this handset a model it has four times the room for.
 * docs/DEVICE-TIER-MEASUREMENT.md carries the readout.
 *
 * ── CONTEXT LENGTH IS A MEMORY DIAL, NOT A MODEL PROPERTY ─────────────────────────────────────
 *
 * The published 128K and 256K windows are desktop affordances. KV-cache grows with context length
 * and can exceed the weights themselves, so **a recommendation that names a model without naming
 * its context cap has not said what will be run.** [DwModelPlan] therefore carries the cap in its
 * constructor beside the model id, and refuses to be built without one: it is not possible anywhere
 * in this app to hold a recommendation that names a model and not the cap it was measured at.
 *
 * ── AND TIER 1 IS NOT BUILT EITHER, WHICH IS A DIFFERENT SENTENCE — AND NO LONGER ONE SENTENCE ─
 *
 * Offline speech recognition (sherpa-onnx + AI4Bharat IndicConformer) is step 4 of the plan's
 * sequence and NO ASR RUNTIME IS IN THIS APK. This file may describe what a handset COULD run; it
 * must never imply the app can run it today. [DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD] is that
 * distinction made into a value, so the difference survives being rendered.
 *
 * **WHAT CHANGED ON 2026-08-12: THE ENGINE IS NOW SOMETHING A DESIGNER CAN INSTALL, SO "NO ENGINE IN
 * THIS BUILD" STOPPED BEING THE WHOLE ANSWER.** It is still the answer every handset gets today —
 * see below — but it is now REACHED rather than assumed, and the states around it have multiplied.
 * `DwAsrRuntime.kt` owns the engine's install state, and [dwTier1Offer] asks it instead of returning
 * a constant. **EIGHT distinct refusals are now reachable where there was one** — count them off the
 * `when` in [dwTier1Offer] rather than off this table, which groups some of them by situation:
 *
 *  | the handset's situation | the refusal it gets |
 *  |---|---|
 *  | nothing published to install (**today, everywhere**) | [DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD] |
 *  | an artifact exists and this phone could take it, is fetching it, or has no connection to fetch it with | [DwTierRefusal.RUNTIME_NOT_INSTALLED] |
 *  | an artifact exists and this phone cannot take it | [DwTierRefusal.ABI_NOT_BUILT_FOR] / [DwTierRefusal.ABI_UNMEASURED] / [DwTierRefusal.NOT_ENOUGH_FREE_STORAGE] / [DwTierRefusal.FREE_STORAGE_UNMEASURED] |
 *  | the engine is installed, and there is no model — or it cannot be installed because there is none | [DwTierRefusal.NO_MEASURED_MODEL] |
 *  | the app could not look at its own files | [DwTierRefusal.RUNTIME_UNMEASURED] |
 *
 * THIS HEADER SAID "FIVE ANSWERS" AND LISTED SIX REFUSALS WHILE THE CODE PRODUCED EIGHT, which is the
 * defect the last line of this paragraph warns about, committed in the paragraph itself: `ABI_UNMEASURED`
 * and `FREE_STORAGE_UNMEASURED` were reachable from the `when` and named nowhere here. Corrected
 * 2026-08-12 on review of the device-tier measurement lane.
 *
 * Six of the eight reuse refusals this file already had, which is deliberate: "no build for your
 * processor" and "not enough room" are the same news whether the thing that will not fit is an engine
 * or a model, and inventing second spellings of them would put two sentences behind one fact.
 * docs/DEVICE-TIER-MEASUREMENT.md carries the same eight, one situation per row rather than grouped,
 * and the two must move together — a document describing code that no longer exists is a defect this
 * repository has already filed twice.
 */

// ---------------------------------------------------------------------------------------------
// Which machine ran it — the vocabulary the provenance half already writes down
// ---------------------------------------------------------------------------------------------

/**
 * Which machine would produce a piece of AI output. The handset's copy of the backend's `AiTier`.
 *
 * NOT A RANKING, and the enum is names rather than an integer for exactly that reason. Tier 1 is the
 * only tier that works in a courtyard with no signal, and Tier 3 is the only one carrying the craft
 * keyterm list that stops a general model writing "double" where the artisan said "dabu"
 * (`backend/app/services/ai.py`). "Higher is better" is false in both directions.
 *
 * THE NAMES ARE THE WIRE VALUES. `AiTier` in `backend/app/services/ai_layers.py` and the `DwAiTier`
 * Postgres enum both spell them `TIER_1` / `TIER_2` / `TIER_3`, and a layer this handset registers
 * has to arrive carrying one of those strings. `DwDeviceTierTest` pins the spelling so a rename here
 * fails on the desktop JVM rather than as a 422 from a district town.
 *
 * THE NAME COLLISION IS NOT HYPOTHETICAL AND IT BIT ON THE FIRST COMPILE. `StageSchema.kt` already
 * has a `DwTier`, and it means something else entirely — BASIC / STANDARD / ADVANCED, how important
 * a FIELD is to a complete record, which is the thing that collapses two hundred fields behind a
 * "More detail" disclosure in a village hall. The two are unrelated, they will end up in the same
 * report code, and the compiler caught the clash only because they share a package. So this one is
 * `DwAiTier`, spelled exactly like the `DwAiTier` Postgres enum it mirrors, and `StageSchema`'s
 * keeps the name it has had all along. Do not "tidy" either of them into the other's shape.
 */
enum class DwAiTier {
    /** On this handset, offline: ASR. Not built — see [DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD]. */
    TIER_1,

    /** On this handset, offline: a small language model. No artifact has been weighed. */
    TIER_2,

    /** The server provider chain, which has worked since long before any of this. Needs signal. */
    TIER_3,
    ;

    /** 1, 2 or 3 — for prose only ("Tier 3, in the cloud"), never for a comparison. */
    val number: Int get() = name.substringAfterLast('_').toInt()

    /** Where the work would happen, for a sentence a designer reads. */
    val where: String
        get() = when (this) {
            TIER_1, TIER_2 -> "on this phone"
            TIER_3 -> "on the server"
        }
}

// ---------------------------------------------------------------------------------------------
// What one probe actually measured — including, explicitly, what it could not
// ---------------------------------------------------------------------------------------------

/**
 * How hot the phone says it is. `PowerManager.getCurrentThermalStatus()`, plus an honest absence.
 *
 * [UNMEASURED] is the state on every handset below API 29 and on any handset whose read threw, and
 * it is NOT folded into [NONE]. "The phone did not say" and "the phone said it is cool" are
 * different facts, and a fleet still carrying Android 8 and 9 would otherwise have its silence read
 * as a clean bill of health on every one of them.
 */
enum class DwThermalState {
    /** API < 29, or the read failed. Never treated as cool, never treated as hot. */
    UNMEASURED,
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
    ;

    /**
     * Whether a long, heavy job should be STARTED right now.
     *
     * The bar is MODERATE, not SEVERE: by the time the platform reports severe throttling the user
     * experience is already degraded, and a summarisation run is precisely the sustained load that
     * takes a mid-range phone from moderate to severe. Starting one there would be choosing to make
     * the camera the designer is about to use slower.
     *
     * THE THRESHOLD IS A CHOICE, NOT A MEASUREMENT. Nobody has yet run a model on a handset in this
     * fleet and watched what the thermal status did; when somebody does, the number that comes back
     * belongs in docs/DEVICE-TIER-MEASUREMENT.md and this line should be revisited against it.
     *
     * [UNMEASURED] does NOT block. An Android 9 handset cannot answer, and barring it for ever from
     * a capability on the grounds that it could not describe its own temperature would be turning a
     * missing answer into a verdict — the thing the honest-unknown rule forbids.
     */
    val tooHotToStart: Boolean
        get() = this == MODERATE || this == SEVERE || this == CRITICAL ||
            this == EMERGENCY || this == SHUTDOWN
}

/**
 * One reading of this handset, taken at one moment. **A NULL IS NEVER A ZERO.**
 *
 * Every nullable field below means "this app asked and did not get an answer", and the difference
 * matters more here than almost anywhere else in the app: a zero in [totalRamBytes] reads as "this
 * device has no memory", which would refuse every tier on a flagship and do it with total
 * confidence. `/proc/meminfo` can be unreadable, `StatFs` can throw on a path the app cannot stat,
 * and thermal status did not exist before API 29. Each of those is a null, is rendered as the word
 * "unmeasured", and blocks the decision it feeds rather than silently passing it.
 *
 * The same shape, and the same reasoning, as [DwRecognitionSupport] being nullable in
 * [dwPackState] — a device that was not asked is UNKNOWN, every time, with no fallback reasoning.
 */
data class DwDeviceMeasurement(
    /**
     * `ActivityManager.MemoryInfo.totalMem`. Null means the read failed.
     *
     * ALWAYS SMALLER THAN THE NUMBER ON THE BOX. This is memory accessible to the kernel; the
     * firmware's own reservations are taken before Android ever sees them, so a handset sold as
     * 4 GB reports something in the threes. The band edges below are placed with that in mind.
     *
     * MEASURED ON ONE HANDSET, 2026-08-12: the fleet's SM-M325F reports 5,927,968,768 bytes, and that
     * reading matched `/proc/meminfo`'s `MemTotal` byte for byte. **THE SHORTFALL ITSELF IS STILL
     * UNMEASURED**, because it is the gap to a box figure and the box is not something the probe — or
     * anything else in this lane — ever read. Against 6 GiB it would be 490.6 MiB (7.99%); against
     * 8 GiB, 2,538.6 MiB (30.99%); against 4 GiB it is negative, which is the one useful half, since
     * it rules the 4 GB variant out by arithmetic rather than by looking anything up. The edges depend
     * only on the direction. docs/DEVICE-TIER-MEASUREMENT.md has all three rows.
     */
    val totalRamBytes: Long? = null,

    /**
     * `ActivityManager.MemoryInfo.availMem` — what is free AT THIS INSTANT, not at install time.
     *
     * This is the number that changes between the settings screen and the job, which is why
     * [dwProbeIsStale] exists and why nothing here may be cached for the life of the process.
     */
    val availableRamBytes: Long? = null,

    /**
     * `ActivityManager.isLowRamDevice()` — ANDROID'S OWN VERDICT, and an immediate no.
     *
     * Null means the read failed, and null is not `false`: a handset that would have said "yes, I am
     * a low-memory device" must not be treated as a comfortable one because a service lookup
     * returned nothing.
     */
    val lowRamDevice: Boolean? = null,

    /**
     * `StatFs(context.filesDir.absolutePath).availableBytes`. Null means `StatFs` threw.
     *
     * AVAILABLE, not free: the free figure includes blocks reserved for root that this app can never
     * have, and sizing a multi-gigabyte download against memory it cannot use is how a download
     * fails at 98%.
     */
    val freeStorageBytes: Long? = null,

    /**
     * `Build.SUPPORTED_ABIS`, in the platform's own preference order — which runtime build would
     * be fetched. EMPTY means the list could not be read, which is why [DwTierRefusal.ABI_UNMEASURED]
     * exists rather than an empty list quietly matching nothing.
     */
    val abis: List<String> = emptyList(),

    /** `PowerManager.getCurrentThermalStatus()` where there is one. See [DwThermalState]. */
    val thermal: DwThermalState = DwThermalState.UNMEASURED,

    /**
     * `BatteryManager.isCharging`. Null means the read failed.
     *
     * ADVISORY ONLY — it never blocks a job. See [dwTier2PowerAdvice] for why blocking on it would
     * be the wrong call in a courtyard with no socket.
     */
    val charging: Boolean? = null,

    /**
     * `SystemClock.elapsedRealtime()` when this reading was taken, for [dwProbeIsStale].
     *
     * The MONOTONIC clock, deliberately, not wall time: `System.currentTimeMillis` moves when NTP
     * corrects it or a designer changes the date, and an age computed across such a jump can come
     * out negative or hours wrong. Tests pass 0 here because they supply "now" themselves.
     */
    val takenAtElapsedMs: Long = 0L,
)

// ---------------------------------------------------------------------------------------------
// The device class — the left-hand column of the table in docs/DEVICE-TIER-MEASUREMENT.md
// ---------------------------------------------------------------------------------------------

private const val MIB: Long = 1024L * 1024L

/*
 * THE RULE EVERY BAND EDGE BELOW OBEYS, AND THE DIRECTION IT MUST BE WRONG IN.
 *
 * The figure being compared is REPORTED memory (see [DwDeviceMeasurement.totalRamBytes]), which is
 * always below the number on the handset's box: the firmware's reservations are taken before Android
 * is told anything. How large that shortfall is on any particular phone IS UNMEASURED and nothing
 * here depends on knowing it. What the rule gives for free is a CEILING — a handset sold as N GB
 * cannot report more than N GiB — and every edge below is placed at or above the ceiling of the row
 * underneath it. The consequence is that a firmware reservation can only ever push a handset DOWN a
 * row, never up.
 *
 * DOWN IS THE DIRECTION TO BE WRONG IN. Erring low recommends less than a handset could do, which
 * costs a designer a capability; erring high recommends a job the low-memory killer ends halfway
 * through, which costs them the work.
 */

/**
 * Reported `totalMem` below which this is the measurement doc's first row — "low-RAM flag set, or
 * < 3 GB", whose Tier 2 cell reads "none, and said so plainly".
 *
 * A ROUND 3 GiB, AND THE ROUNDNESS IS THE WHOLE ARGUMENT. A handset sold as 3 GB reports something
 * below 3 GiB whatever its firmware happens to reserve, so this edge catches every one of them
 * without anybody having to know the shortfall; and a handset sold as 4 GB would have to be missing
 * more than a quarter of its memory to fall below it.
 *
 * AN EARLIER DRAFT PUT THIS AT 2,750 MiB, WHICH WAS THE WRONG DIRECTION AND IS THE REASON THIS
 * PARAGRAPH EXISTS. 2,750 MiB sits INSIDE the range its own comment said a 3 GB handset reports
 * ("somewhere in the twos"), so a 3 GB phone reporting 2.8 GiB was classified as a 4 GB-class one —
 * erring high, on the one row whose whole content is the word "none". Nothing rendered differently
 * while both catalogues are empty; the first measured model would have offered a download to a
 * handset the plan's own table says gets none.
 */
const val DW_LOW_RAM_CEILING_BYTES: Long = 3L * 1024L * MIB

/**
 * Top of the 4 GB row. Above it, a handset sold as 6 GB.
 *
 * THE ROW USED TO BE CALLED "4 GB (the M32 fleet)" HERE AND IN THE DOC'S TABLE, AND THAT PARENTHESIS
 * WAS FALSE — measured 2026-08-12, the fleet's own SM-M325F reports 5,653.352 MiB and lands in the
 * row ABOVE this edge, not below it. The fleet handset has never been in the row named after it. The
 * name is dropped rather than moved: which row a handset lands in is answered by asking that handset,
 * never by inferring from a model name, which is exactly what went wrong here.
 *
 * ONE HALF OF THIS EDGE IS GIVEN AND THE OTHER IS CHOSEN. The floor is given: a handset sold as 4 GB
 * cannot report more than 4,096 MiB, so anything at or above that keeps a 4 GB handset in its own
 * row. Where above the floor it sits is a CHOICE, because the other half of the question — the least
 * a 6 GB handset reports — was UNMEASURED when it was made. 5,500 MiB is that choice, and it errs
 * low: a 6 GB phone with an unusually large reservation lands in the 4 GB row.
 *
 * ONE HANDSET HAS CLEARED IT, BY 153 MiB, ON 2026-08-12 — AND THE CHOSEN HALF IS STILL UNMEASURED.
 * The fleet's own SM-M325F reports 5,653.352 MiB, clearing this edge by 160,800,768 bytes, 2.8% of it.
 * **That is not the same as measuring the least a 6 GB handset reports**, which is what this edge was
 * chosen against: the reading fills that gap only if the phone is a 6 GB one, and WHAT IT WAS SOLD AS
 * IS NOT SOMETHING IT REPORTED. Its `totalMem` rules out the 4 GB variant arithmetically and rules out
 * nothing above. If it is a 6 GB phone its firmware takes 490.6 MiB (7.99% of 6 GiB), and one taking
 * 644 MiB would have been demoted into the 4 GB row — the safe direction, but the room is thinner than
 * the round number reads. See docs/DEVICE-TIER-MEASUREMENT.md before moving this.
 * ONE HANDSET IS NOT A DISTRIBUTION, AND AN UNREAD VARIANT IS NOT A MEASURED ONE.
 */
const val DW_FOUR_GB_CEILING_BYTES: Long = 5_500L * MIB

/**
 * Top of the "6–8 GB" row. The 8–12 GB gap in the doc's table folds in here, not into 12 GB+.
 *
 * Same shape as the edge above: the floor is given (an 8 GB handset cannot report more than
 * 8,192 MiB) and everything above it is a choice, because the least a 12 GB handset reports is
 * UNMEASURED. Folding the gap downwards is the erring-low direction.
 */
const val DW_EIGHT_GB_CEILING_BYTES: Long = 11_000L * MIB

/**
 * Which row of the measurement doc's table this handset is, if it said enough to place it.
 *
 * The rows are that document's rows and are not to be re-cut here without cutting them there too:
 * the whole arrangement of this lane is that the table's SHAPE is fixed and its CELLS are filled by
 * measuring real handsets.
 */
enum class DwDeviceClass {
    /**
     * The phone did not say how much memory it has, and Android did not say whether it considers it
     * a low-memory device either. NOT a synonym for small — a flagship whose `ActivityManager`
     * lookup failed lands here, and calling that "too small" would be inventing a verdict.
     */
    UNMEASURED,

    /** `isLowRamDevice()` is set, or reported memory is under [DW_LOW_RAM_CEILING_BYTES]. */
    LOW_RAM,

    /**
     * A handset sold as 4 GB.
     *
     * THIS KDOC USED TO END "…like the Galaxy M32 these workshops are run on", AND THE HANDSET SAID
     * OTHERWISE. Measured 2026-08-12 (docs/DEVICE-TIER-MEASUREMENT.md): the fleet's own SM-M325F
     * reports 5,927,968,768 bytes and lands in [MID_6_TO_8GB], not here. **What that phone was sold
     * as is not something it said, and this comment does not guess**; the reading alone is enough,
     * because 5.521 GiB cannot be reported by a 4 GB handset. **How many of the fleet are 4 GB is
     * unmeasured** — one phone is not a survey — so this row is named after a size and nothing else.
     */
    SMALL_4GB,

    /**
     * 6–8 GB, and everything up to the 12 GB edge.
     *
     * **The one handset in this fleet that has actually been probed lands HERE**, 2026-08-12.
     */
    MID_6_TO_8GB,

    /** 12 GB and up. */
    LARGE_12GB_PLUS,
}

/**
 * Place this handset in the table, from what it was willing to report.
 *
 * ANDROID'S OWN FLAG WINS AND IS CHECKED FIRST, before any arithmetic on byte counts. `isLowRamDevice`
 * is the platform's considered verdict about a build — it is what Android Go sets, and the OEM
 * configuration behind it knows things about the device that a memory total does not. A handset that
 * says "I am a low-memory device" while reporting 3.9 GB is telling us the truth about itself.
 */
fun dwDeviceClass(measurement: DwDeviceMeasurement): DwDeviceClass {
    if (measurement.lowRamDevice == true) return DwDeviceClass.LOW_RAM
    val total = measurement.totalRamBytes
    // Null, and not zero — a phone that would not say how much memory it has has not said it is
    // small. See the class doc on why UNMEASURED is its own row rather than the bottom one.
    if (total == null || total <= 0L) return DwDeviceClass.UNMEASURED
    return when {
        total < DW_LOW_RAM_CEILING_BYTES -> DwDeviceClass.LOW_RAM
        total < DW_FOUR_GB_CEILING_BYTES -> DwDeviceClass.SMALL_4GB
        total < DW_EIGHT_GB_CEILING_BYTES -> DwDeviceClass.MID_6_TO_8GB
        else -> DwDeviceClass.LARGE_12GB_PLUS
    }
}

// ---------------------------------------------------------------------------------------------
// A model, and the context cap it was measured at — which cannot be separated
// ---------------------------------------------------------------------------------------------

/**
 * **HOW MUCH SLOWER THAN REAL TIME ONE MODEL DECODES, AS A BAND ACROSS MEASURED UTTERANCES.**
 *
 * A BAND AND NOT A FIGURE, AND THAT IS A CORRECTION RATHER THAN A PREFERENCE.
 * docs/DEVICE-TIER-MEASUREMENT.md's *CORRECTION, 2026-08-12 LATE EVENING* records the lane that
 * reported **1.119 – 1.272** off six utterances and then failed to reproduce it: an adversarial re-run
 * on the same handset, the same WAVs and the same `numThreads` returned **1.078 – 2.967** across
 * twelve. The transcripts were byte-identical both times — the decode is deterministic and the clock
 * is not. So a single number here would be a measurement that has already been shown not to hold, and
 * the document says in as many words what it costs: *"The next lane to write 'a five-minute recording
 * takes about six minutes to transcribe' will size that sentence off this row. At 2.967 the same
 * recording takes close to fifteen minutes."*
 *
 * **[slowest] IS THE ONE ANY SENTENCE SHOWN BEFORE A TAP MUST BE BUILT FROM.** A designer told six
 * minutes and made to wait fifteen in a courtyard is the failure this class exists to prevent;
 * a designer told fifteen and finished in six has lost nothing.
 */
data class DwModelRtfBand(
    /** Fastest measured decode ÷ audio duration. Above 1.0 means slower than the audio plays. */
    val fastest: Double,
    /** Slowest measured. **The figure every promise is sized from.** */
    val slowest: Double,
    /** How many decodes the band spans. Twelve is not a distribution either; it is what was run. */
    val utterances: Int,
    /** The handset, named, for [DwModelPlan.measuredOn]'s reason. */
    val measuredOn: String,
) {
    init {
        require(fastest > 0.0 && slowest > 0.0) {
            "A real-time-factor band is a ratio of two durations and cannot be zero or negative. If " +
                "nobody has timed this model, pass null for the whole band — that is the word " +
                "“unmeasured”, and it is a better answer than a number nobody took."
        }
        require(slowest >= fastest) {
            "A real-time-factor band runs fastest to slowest. Swapping them would have every " +
                "sentence in this app size a designer's wait off the best case, which is the exact " +
                "failure docs/DEVICE-TIER-MEASUREMENT.md's own correction was written about."
        }
        require(utterances > 0) {
            "A band needs the number of decodes it spans. One reading is not a band, and a band with " +
                "no count behind it cannot be argued with."
        }
        require(measuredOn.isNotBlank()) {
            "A timing needs the handset it was timed on. Silicon, thermal state and governor decide " +
                "this number, so it is meaningless without the phone attached to it."
        }
    }
}

/**
 * **HOW WELL ONE MODEL WAS MEASURED TO TRANSCRIBE ONE LANGUAGE. CARRIED EVEN WHERE THE LANGUAGE IS
 * NOT CLAIMED AS SERVED — ESPECIALLY THERE.**
 *
 * ── WHY A REJECTED LANGUAGE KEEPS ITS ROW ─────────────────────────────────────────────────────
 *
 * [DwModelPlan.languages] is the list this app ACTS on: a tag in it makes `dwOfflineCoverage` report
 * offline capability, puts [DwDictationRung.APP_SPEECH_MODEL] in a ladder, and turns a language row
 * green. Odia is deliberately NOT in it for the model pinned in [DW_TIER1_CATALOGUE], because 53.3%
 * WER is not a working language and listing it would be the precise failure this feature exists to
 * prevent.
 *
 * **BUT SILENCE IS THE WRONG WAY TO SAY NO.** A designer in Odisha reading "no model this app could
 * install has been measured to hear Odia" would go looking for one — and one has been found, run on
 * this fleet's own handset, and scored. *"Measured and rejected"* is a far more useful answer than
 * *"nothing here"*: it tells them the search has been done, what the bar was, and how far short the
 * artifact fell. So the evidence lives here, beside the claim, and [DwModelPlan.servesLanguage]
 * decides the claim from [DwModelPlan.languages] alone — never from this list.
 *
 * **NOTHING IN THIS APP MAY PROMOTE A ROW HERE INTO COVERAGE.** That is the one rule. An accuracy row
 * is a measurement of a model; a language list is a decision about what to offer. Collapsing them
 * would put the 53.3% Odia row back on the ladder by the side door.
 */
data class DwModelAccuracy(
    /** BCP-47, compared through `dwTagCovers` exactly as [DwModelPlan.languages] is. */
    val tag: String,
    /** Character error rate, per cent. Read the WER instead — see [werPercent]. */
    val cerPercent: Double,
    /**
     * Word error rate, per cent. **THE FIGURE THAT DECIDES WHETHER A LANGUAGE IS SERVED.**
     *
     * docs/DEVICE-TIER-MEASUREMENT.md: *"A 15% character error rate on Odia looks tolerable and the
     * word error rate says what it actually means: more than half the words are wrong."* Anything
     * that renders one of these renders the WER, and a surface that showed only the CER would be
     * choosing the flattering half of a measurement.
     */
    val werPercent: Double,
    /**
     * What it was scored against, in a sentence — and whether that is a ceiling or a field result.
     *
     * Required, because *"53.3% WER"* alone invites the reader to assume a courtyard. It was studio
     * read speech, which is the easier test, so every number here is a CEILING.
     */
    val corpus: String,
    /** How many utterances. Three is a demonstration, not an evaluation, and the row must say so. */
    val utterances: Int,
    /** Reference words behind the WER. A rate over sixty words is not a rate over six thousand. */
    val referenceWords: Int,
) {
    init {
        require(tag.isNotBlank()) {
            "An accuracy row needs the BCP-47 tag it scored, so it can be matched against the " +
                "languages this app offers rather than read as a claim about the model in general."
        }
        require(cerPercent >= 0.0 && werPercent >= 0.0) {
            "Error rates are not negative. If nobody scored this language, leave the row out — an " +
                "absent row is the word “unmeasured”, and a zero would read as a perfect transcript."
        }
        require(corpus.isNotBlank()) {
            "An accuracy row needs to say what it was scored against. Studio read speech and a " +
                "courtyard are different tests, and a WER with no corpus behind it will be read as " +
                "the harder one."
        }
        require(utterances > 0 && referenceWords > 0) {
            "An accuracy row needs the size of the sample behind it. This repository has already " +
                "shipped a claim with no sample behind it once; a rate over an unstated number of " +
                "words cannot be argued with."
        }
    }
}

/**
 * ONE MODEL AS AN ARTIFACT THAT HAS ACTUALLY BEEN WEIGHED, TOGETHER WITH THE ENVELOPE THE WEIGHING
 * WAS DONE OVER. There is no other way to name a model anywhere in this app.
 *
 * EVERY FIELD IS REQUIRED, AND THAT IS THE ENFORCEMENT MECHANISM OF THIS WHOLE FILE. There is no
 * constructor that takes a model id alone, no default cap, and no default peak RSS, so the sentence
 * "we recommend Gemma on this phone" cannot be expressed without also having somewhere to put the
 * cap it was measured at, the handset it was measured on and the resident set it reached. Plan §2.1:
 * *"A recommendation that names only the model has not actually said what will be run."*
 *
 * [peakRssBytes] rather than the download size is what the fit arithmetic uses, because the
 * low-memory killer reads the resident set and nothing else. Weights may be memory-mapped, in which
 * case the resident set is smaller than the file and depends on the access pattern; both numbers are
 * carried because they answer different questions — [onDiskBytes] is what the designer's data bundle
 * pays for, [peakRssBytes] is what ends the process.
 *
 * [measuredOn] is not decoration. docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md exists because a test
 * fixture in this repository once claimed capabilities for a named handset that the handset did not
 * have, and the suite agreed with a device that does not exist for weeks. A plan carries the name of
 * the phone the number came off so a wrong number can be traced to a real run.
 */
data class DwModelPlan(
    /** The exact artifact, not the family name: "gemma4-e2b-int4-litert", not "Gemma". */
    val modelId: String,
    /** int4, int8, fp16 — the thing that actually decides the footprint. */
    val quantisation: String,
    /** Which `Build.SUPPORTED_ABIS` entry the runtime build exists for, e.g. `arm64-v8a`. */
    val abi: String,
    /**
     * **WHICH OF THIS APP'S DICTATION LANGUAGES THIS ARTIFACT ACTUALLY SERVES — OR NULL, WHICH IS
     * THE WORD "unmeasured" AND IS NEVER AN EMPTY LIST.**
     *
     * BCP-47 tags drawn from `DW_DICTATION_LANGUAGES` (the nineteen), compared through
     * [dwTagCovers] — never through [dwNormalizeLanguageTag] alone, and the distinction is not
     * pedantry. Normalisation makes `or_IN`, `or-IN` and `OR-in` one tag, which is separator and
     * case only; the bare `or` is a DIFFERENT string after normalisation and reaches `or-IN` through
     * `dwTagCovers`'s one deliberate widening ("a device that reports a bare language is reporting a
     * pack that serves every region of it"). An earlier version of this line said normalisation made
     * all three "mean the same row", which is false, and it is the sentence somebody would use to
     * justify comparing catalogue tags with `==` after normalising — a comparison that would quietly
     * drop a legitimate bare-language row while looking correct.
     *
     * ── WHY A MODEL HAS TO CARRY THIS AT ALL ──────────────────────────────────────────────────
     *
     * **A DESIGNER IN ODISHA CHOOSING A MODEL THAT CANNOT HEAR ODIA IS THE FAILURE THE WHOLE
     * FEATURE EXISTS TO PREVENT.** A model is not a capability, it is a capability *in some
     * languages*: Whisper's ninety-nine exclude Odia, the `k2-fsa/sherpa-onnx` `asr-models` release
     * carries no Indic model at all, and the one artifact that HAS been run on the fleet's handset
     * and does hear Odia — `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`,
     * pinned in `DwAsrModel.kt` — did it at 53.3% WER (docs/DEVICE-TIER-MEASUREMENT.md). A
     * catalogue row that named a size and a peak RSS but not the languages would let this app
     * recommend a 900 MB download to the one designer it can do nothing for.
     *
     * **AN EARLIER VERSION OF THIS PARAGRAPH SAID "no Odia-capable ASR model was found", AND THE
     * SAME FILE SAID OTHERWISE TWO HUNDRED LINES DOWN.** [DW_TIER1_CATALOGUE]'s own comment records
     * the model that was pinned, run and scored on Odia; asserting an absence beside it would send
     * the next reader off to repeat a search that has already been done, when what is open is a
     * model good enough to offer. The catalogue is empty because of a measurement, not a gap.
     *
     * ── THE THREE VALUES AND WHY NULL IS NOT emptyList() ──────────────────────────────────────
     *
     *  * `null` — **nobody has checked which of the nineteen this artifact can hear.** Rendered as
     *    the word "unmeasured". It contributes NOTHING to any language's coverage: an unchecked
     *    model is not evidence that a language is served, and this app does not turn a question it
     *    declined to ask into an answer. The model may still be offered and installed; what may not
     *    happen is a language row going green on the strength of it.
     *  * `emptyList()` — **checked, and it serves none of the nineteen.** A real answer (an English
     *    -only export, say) and a different fact from the one above, exactly as
     *    [DwDeviceMeasurement.totalRamBytes]'s null is not a zero and `DwPackState.UNKNOWN` is not
     *    `NO_OFFLINE_PACK`.
     *  * a non-empty list — the tags it was measured to serve, and only those.
     *
     * ── WHAT IT MUST NOT BE FILLED FROM ───────────────────────────────────────────────────────
     *
     * **NOT FROM AN UPSTREAM README'S LANGUAGE COUNT.** "Ninety-nine languages" is a claim about a
     * family, and this field is a claim about the exact artifact named in [modelId] at the
     * quantisation in [quantisation]. Where the upstream list is all there is, the honest entry is
     * the intersection somebody actually read off the artifact's own tokeniser or model card, and
     * where nobody has read it, the honest entry is `null`. Plan §2.2's accuracy bar is a separate
     * question again: this field says the model *emits* the language, never that it is any good at
     * it in a courtyard.
     */
    val languages: List<String>?,
    /**
     * The token context cap the numbers below were measured at — **or null, because not every model
     * has one, and the first real row in this app is one of the models that does not.**
     *
     * ── WHY THIS BECAME NULLABLE, AND WHY THE INVARIANT DID NOT WEAKEN ────────────────────────
     *
     * It was a required `Int`, on the argument in this file's header: KV-cache grows with context
     * length and can exceed the weights, so *"a recommendation that names a model without naming its
     * context cap has not said what will be run."* **That argument is about a decoder-only language
     * model, and it is still exactly right about one.** [DW_TIER2_CATALOGUE]'s rows, when they exist,
     * will all carry a number here.
     *
     * **IT IS NOT RIGHT ABOUT A CTC SPEECH MODEL, AND THE FIRST MEASURED ARTIFACT IN THIS
     * REPOSITORY IS ONE.** docs/DEVICE-TIER-MEASUREMENT.md, filling the *time to first token* cell:
     * *"there are no incremental tokens to be first, and saying otherwise would be inventing a
     * measurement. This is a non-streaming CTC model: audio goes in whole and one result comes out."*
     * There is no KV-cache, no context window and no token cap to configure; what the peak RSS scales
     * with is the LENGTH OF THE AUDIO handed in. Writing `contextCapTokens = 2048` beside it to
     * satisfy a `require` would have been a plausible value invented to fill a field — the single
     * thing this whole file exists to make impossible.
     *
     * **SO THE INVARIANT MOVED RATHER THAN RELAXING.** [runBound] is required of EVERY plan and says,
     * in words, what bounds one run and what the peak RSS below was measured over. A plan still
     * cannot be constructed that names a model and not the envelope its memory figure came from;
     * what it can now do is say that the envelope is measured in seconds of audio rather than in
     * tokens, which for this artifact is the truth.
     */
    val contextCapTokens: Int?,
    /**
     * **WHAT BOUNDS ONE RUN OF THIS MODEL, AND WHAT [peakRssBytes] WAS MEASURED OVER. REQUIRED OF
     * EVERY PLAN, WITH NO DEFAULT.**
     *
     * This is [contextCapTokens]'s argument, generalised to models that are not decoders — see that
     * field for why it had to be. For a language model it restates the cap in words ("a 2,048-token
     * context cap"); for the CTC speech model in [DW_TIER1_CATALOGUE] it names the audio, because
     * that is the dial that actually moves the resident set.
     *
     * A SENTENCE RATHER THAN A NUMBER, deliberately: the units differ per model family, and a
     * numeric field with a units field beside it is two things to keep in step where one thing will
     * do. Nothing computes with it; it is printed beside the model id wherever a size is printed, so
     * that no surface in this app can name a model without naming what was run.
     */
    val runBound: String,
    /** What the download costs the designer's data bundle. */
    val onDiskBytes: Long,
    /** Peak resident set within [runBound], on [measuredOn]. The number the LMK reads. */
    val peakRssBytes: Long,
    /** The handset this was measured on, named, e.g. "Galaxy M32 (Android 13)". */
    val measuredOn: String,
    /**
     * Whether the app survived being backgrounded with this model loaded, ON THAT HANDSET.
     *
     * Not a nicety and not a performance note: a designer takes a photograph mid-summary, and if
     * that kills the process the summary and possibly the draft go with it. A plan that is `false`
     * here may still be offered, but the offer has to say so — see [dwTierOfferSentence].
     *
     * **NULLABLE, AND THE NULL IS THE FIRST REAL ROW'S ACTUAL ANSWER.** `DwAsrEngineProbeTest` sent
     * the app to Home with the model loaded and decoded the same audio again to the same text, in
     * both runs — and docs/DEVICE-TIER-MEASUREMENT.md refuses to score that as a yes, because
     * `oom_score_adj` stayed at 0 across the transition: a process under instrumentation is held up
     * by the runner and is not a backgrounded app as the low-memory killer sees one. What was
     * measured is that the ACTIVITY leaving the foreground does not kill the model. The question this
     * field is really asking — *does the LMK take it* — is **unmeasured, in that word**. `true` would
     * have claimed a measurement nobody took; `false` would have claimed a failure that did not
     * happen. So there are three values, and the sentences below print the third one as the word.
     */
    val survivesBackgrounding: Boolean?,
    /**
     * How much slower than real time it decodes, **or null, which is the word "unmeasured".**
     *
     * Null is not "fast enough". Nothing in this app may promise a designer a wait without one of
     * these, and every promise built from one is built from [DwModelRtfBand.slowest] — see that class
     * for the correction that made a band out of what was reported as a figure.
     */
    val realTimeFactor: DwModelRtfBand? = null,
    /**
     * **HOW WELL IT WAS SCORED, PER LANGUAGE — INCLUDING LANGUAGES [languages] DELIBERATELY OMITS.**
     *
     * Empty means nobody has scored this artifact against anything, which is a different fact from a
     * bad score and is rendered as the word "unmeasured". A row here NEVER grants coverage; see
     * [DwModelAccuracy] for the one rule this list has.
     */
    val accuracy: List<DwModelAccuracy> = emptyList(),
    /**
     * **WHAT IS CLAIMED ABOUT THIS ARTIFACT'S LANGUAGES BEYOND WHAT WAS MEASURED, AND BY WHOM.**
     *
     * Null when there is nothing to say. Non-null when the upstream publisher claims a language count
     * this app has not checked — which for the pinned speech model is *1,600+*, a number that must
     * never be repeated as a property of the artifact. The field exists so a card can print the honest
     * shape in one place: **two languages measured, and a thousand-odd unmeasured, in that word.**
     */
    val unmeasuredLanguagesNote: String? = null,
) {
    /**
     * Whether this plan CLAIMS [tag] — read off [languages] and nothing else.
     *
     * **[accuracy] IS NOT CONSULTED, AND THAT IS THE RULE RATHER THAN AN OMISSION.** A scored
     * language that did not clear the bar has a row in [accuracy] and is absent from [languages], and
     * a `servesLanguage` that fell back to the evidence list would put a 53.3% WER model back on the
     * dictation ladder through the side door. The evidence is for a designer to read; the claim is
     * what the app acts on.
     */
    fun servesLanguage(tag: String): Boolean = languages?.any { dwTagCovers(it, tag) } == true

    /** The score for [tag], or null when nobody measured this artifact against it. */
    fun accuracyFor(tag: String): DwModelAccuracy? = accuracy.firstOrNull { dwTagCovers(it.tag, tag) }

    init {
        // Sentences, naming the next move, because these fire at a developer adding the first real
        // row to the catalogue and a code would send them to a search engine instead of the doc.
        require(modelId.isNotBlank()) {
            "A model plan needs the exact artifact id, not a family name. Take it from the export " +
                "you actually downloaded and record it in docs/DEVICE-TIER-MEASUREMENT.md too."
        }
        require(quantisation.isNotBlank()) {
            "A model plan needs its quantisation. int4 and fp16 of the same weights are different " +
                "artifacts with different footprints; naming the family without it says nothing."
        }
        require(abi.isNotBlank()) {
            "A model plan needs the ABI its runtime build exists for, e.g. arm64-v8a. Read it off " +
                "the runtime you are shipping and put it here."
        }
        /*
         * A CAP OF ZERO IS STILL REFUSED, AND NULL IS NOT ZERO. Null says "this model family has no
         * token context cap"; zero says "it has one and it is nothing", which is not a model. The
         * distinction is the same one [DwDeviceMeasurement.totalRamBytes] draws, and it is what stops
         * a caller from defaulting the field away to get past the constructor.
         */
        require(contextCapTokens == null || contextCapTokens > 0) {
            "A model plan's context cap is either a real cap or null. KV-cache grows with context " +
                "and can exceed the weights, so a decoder named without a cap has not said what " +
                "will be run — measure the peak RSS at a fixed cap and record both. Pass null only " +
                "for a model family that has no such dial at all (a CTC speech model takes audio " +
                "whole and emits one result), and say what bounds a run in `runBound` instead. Zero " +
                "is neither and is refused."
        }
        require(runBound.isNotBlank()) {
            "A model plan has to say what bounds ONE RUN of it and what its peak RSS was measured " +
                "over — “a 2,048-token context cap”, or “one utterance of up to N seconds of 16 kHz " +
                "audio, decoded whole”. This is the field that makes “we recommend this model” " +
                "impossible to say without also saying what was actually run; it has no default for " +
                "that reason."
        }
        require(accuracy.map { dwNormalizeLanguageTag(it.tag) }.distinct().size == accuracy.size) {
            "A model plan scores each language once. Two rows for one tag would let a card print " +
                "whichever was written first, and the one it printed would be a coin toss between " +
                "two real measurements."
        }
        require(onDiskBytes > 0L) {
            "A model plan needs its real on-disk size — this screen states the cost before the tap, " +
                "unlike the language packs, precisely because our own models have a known size."
        }
        require(peakRssBytes > 0L) {
            "A model plan needs a measured peak RSS at its context cap. Run it on the handset and " +
                "read the number; docs/DEVICE-TIER-MEASUREMENT.md says why the on-disk size will " +
                "not do instead."
        }
        require(measuredOn.isNotBlank()) {
            "A model plan needs the name of the handset its peak RSS was measured on. A number with " +
                "no device behind it cannot be checked, and this repository has already shipped one."
        }
        /*
         * THE LANGUAGE LIST IS CHECKED FOR SHAPE HERE AND FOR MEMBERSHIP IN A TEST, and the split is
         * forced rather than chosen: the nineteen live in `ui/designworkshop/DwDictation.kt` as
         * `DW_DICTATION_LANGUAGES`, which is a Compose file, and this file may not import it without
         * dropping the purity that lets the desktop JVM run every line below. So `DwModelChoiceTest`
         * pins that every tag in every catalogue row is one of the nineteen, which is the assertion
         * that would otherwise have to live in this constructor.
         */
        require(languages == null || languages.all { it.isNotBlank() }) {
            "A model plan's language list may not contain a blank tag. Write the BCP-47 tags the " +
                "artifact actually serves (or-IN, hi-IN…), or pass null, which is this app's word " +
                "for “nobody has checked which languages this model can hear”. A blank entry is " +
                "neither, and it would silently match no language row while looking like an answer."
        }
        require(
            languages == null ||
                languages.map { dwNormalizeLanguageTag(it) }.distinct().size == languages.size
        ) {
            "A model plan lists each language it serves once. Two spellings of one tag (or-IN and " +
                "or_IN, hi-IN and HI-in) are the same language to every comparison in this app, and " +
                "a duplicated row would make a coverage count read higher than the languages behind it."
        }
    }
}

/**
 * THE TIER 2 MODELS THIS APP WOULD OFFER. **NO LONGER EMPTY — TWO ROWS, IN
 * [DwTier2Models.kt][DW_TIER2_PLANS], WHICH THIS DELEGATES TO.**
 *
 * ── WHAT THIS COMMENT USED TO SAY, AND WHICH HALF OF IT WAS FALSE ──────────────────────────────
 *
 * It listed docs/DEVICE-TIER-MEASUREMENT.md's two open questions and said neither was answerable from
 * a developer machine:
 *
 *  1. *"Does Google publish a mobile export of a Gemma 4 model for Android at all… The naming used in
 *     the source conversation ("Gemma 4 E2B/E4B") could not be verified and is deliberately not
 *     repeated as fact anywhere in this file."* **ANSWERED, AND IT IS ANSWERED FROM A DEVELOPER
 *     MACHINE.** `litert-community/gemma-4-E2B-it-litert-lm` and `-E4B-` are ungated, Apache-2.0, and
 *     both files were downloaded here, weighed and hashed: 2,588,147,712 and 3,659,530,240 bytes. That
 *     document's own lesson applied to itself — *an absence is a claim, and a claim needs a command
 *     beside it.*
 *  2. *"Loaded on a Galaxy M32 at a 2K context cap, what is the peak RSS, and does the app survive
 *     being backgrounded?"* **STILL OPEN, AND IT IS THE ONLY THING KEEPING THESE ROWS FROM CARRYING A
 *     LOCAL MEASUREMENT.** The figures in them are Google's, taken on an S26 Ultra at exactly that 2K
 *     cap, and each row says so in the field that gets printed. Nothing has been run on this fleet.
 *
 * ADDING A ROW HERE IS STILL NOT THE LAST STEP. [DW_TIER2_RUNTIME_PRESENT] is still `false` — there is
 * no LiteRT-LM in this APK, because the published runtime's Kotlin metadata is newer than this
 * project's compiler and adding it does not compile. So the rows are listed, judged and never offered
 * for download; `dwTier2InstallMayBeOffered` is the gate that says so, and it reads this same constant.
 * "We have numbers" and "we can run it" stay two separate questions, which is what this split was for.
 */
val DW_TIER2_CATALOGUE: List<DwModelPlan> = DW_TIER2_PLANS

/**
 * The Tier 1 (ASR) models. **NO LONGER EMPTY — ONE ROW, AND IT IS THE FIRST MEASURED MODEL THIS
 * REPOSITORY HAS EVER OFFERED.**
 *
 * ── WHAT CHANGED, AND WHAT THE PREVIOUS LANE GOT RIGHT ────────────────────────────────────────
 *
 * This list was empty, and its comment said why: the model that had been pinned and run transcribed
 * Odia at **53.3% WER**, and its peak RSS of ~1.26 GB is one this file's own [dwPlanFits] refuses on
 * the very handset it was measured on. **Both facts are still true and neither has been softened.**
 * What was wrong was the conclusion drawn from them — that the row should not exist — and it was
 * wrong in two separate ways:
 *
 *  * **"53.3% Odia" IS A FACT ABOUT ODIA, NOT ABOUT THE ARTIFACT.** The same run scored Hindi at
 *    **24.2%**. Refusing the whole model on its worst language is refusing a designer a capability in
 *    the language it does serve, and it is the same conflation `DwModelPlan.languages` exists to
 *    prevent: *a model is not a capability, it is a capability in some languages.* So the row lists
 *    `hi-IN` and does not list `or-IN`, and the Odia measurement is carried in `accuracy` where a
 *    designer can read it. **Measured and rejected is a far more useful answer than silence.**
 *  * **"dwPlanFits REFUSES IT" IS NOT THE GATE THAT DECIDES WHETHER A ROW EXISTS.** `dwPlanFits`
 *    decides what this phone is SUGGESTED, and [DwModelChoice] — which landed in a different lane and
 *    was never joined to this one — decides what a designer may CHOOSE. 1.26 GB against the M32's
 *    ~1.5 GB free is [DwModelFit.TIGHT], not [DwModelFit.WILL_NOT_FIT]: it physically fits, one chosen
 *    margin is gone, and the designer is told exactly what that is expected to cost and then allowed
 *    to decide. Keeping the row out of the catalogue took that decision away from them on the grounds
 *    that a 512 MiB margin — **a chosen number, as its own comment says** — had been crossed.
 *
 * ── WHAT A ROW HERE SWITCHES ON, WHICH IS WHY IT IS NOT A ONE-LINE EDIT ───────────────────────
 *
 * `DwAsrRuntime` reads this same list — not a boolean of its own — to answer whether the engine has
 * anything to say ([DwAsrOffer.NO_MODEL_TO_FEED_IT]); `dwTier1Offer` reads it for the tier sentence;
 * `dwModelChoices` turns it into the list a designer picks from; `dwLanguageCoverages` turns each
 * `languages` tag into a green row and a ladder rung. One list, read by all of them, so they cannot
 * come to disagree about whether a model exists — and one row therefore moves every one of those
 * surfaces at once, which is the whole reason this is the second half of a two-part change and not a
 * tidy-up.
 *
 * ── **WHY THERE IS STILL NO INDICCONFORMER ROW HERE, WITH THE NUMBERS** ───────────────────────
 *
 * AI4Bharat IndicConformer is the model Plan §2.2 named, it **is** obtainable — the official
 * `ai4bharat/indic-conformer-600m-multilingual` publishes ONNX under MIT for all 22 scheduled languages
 * — it **does** load on the sherpa-onnx vendored in this APK, and it is far better than the row above
 * where it matters most: **Odia CER 5.1% / WER 16.7%** against Omnilingual's 53.3%, and **Hindi CER 6.9%
 * / WER 20.9%** against 24.2%, greedy CTC on FLEURS, scored on identical references through one
 * normaliser. Odia error falls by roughly 3.8×. Any earlier note in this repository saying that model
 * could not be had was wrong and has been corrected in place.
 *
 * **AND A ROW HERE IS STILL IMPOSSIBLE, BECAUSE [DwModelPlan] REQUIRES A PEAK RSS MEASURED ON A NAMED
 * HANDSET AND THIS ARTIFACT CANNOT BE LOADED ON ONE.** Two independent measurements, not one opinion:
 *
 *  * **fp32 does not fit.** The shared encoder is **2,428,824,576 bytes** of external weight data.
 *    `/proc/meminfo` on the fleet's SM-M325F reported `MemAvailable` **1,340,412 kB** on 2026-08-12 and
 *    **1,058,148 kB** the next morning. Storage is not the constraint — `/data` has 37 GB free.
 *  * **int8 does not transcribe.** Measured 2026-08-13 on a quiet box, twice, with
 *    `onnxruntime.quantization.quantize_dynamic`: the default op set produces **654,790,526 bytes** that
 *    decode the **empty string** on all three Odia FLEURS utterances the fp32 graph scores WER 16.7 on,
 *    and `op_types_to_quantize = ["MatMul"]` produces **883,021,360 bytes** — *larger* — that decode a
 *    single character, `ପ`. Decode also slows, RTF 0.26–0.33 against 0.20–0.24.
 *
 * So the honest state is **"a good Odia model exists and this fleet cannot run it"**, which is neither
 * of the two things this file has said before. Writing a plan row with a desktop RSS in `measuredOn`
 * would be the one thing every `require` in [DwModelPlan] exists to prevent: a number from a machine
 * that is not the phone, feeding arithmetic that decides what the phone is offered.
 *
 * ── **AND AN INDICCONFORMER *HAS* NOW RUN ON THIS HANDSET, WHICH IS WHY THE ROW IS CLOSE** ─────
 *
 * `DwAsrIndicProbeTest` loaded a **120M** IndicConformer CTC export on the fleet's own SM-M325F on
 * 2026-08-13 at 05:27, through `OfflineNemoEncDecCtcModelConfig` on the sherpa-onnx **inside this APK**
 * — the same branch [DwAsrSpeechModel] now takes for [DwAsrModelFamily.NEMO_ENC_DEC_CTC]. Both files
 * hashed on the phone and both VERIFIED. Measured:
 *
 *  * graph **493,060,445 bytes** fp32, hashed on the handset in 2.33 s;
 *  * **load 6,017 ms**; `VmHWM` 236,015,616 before → 861,810,688 after → **884,117,504 peak**;
 *  * 5,015 ms of speech decoded in **2,193 ms, RTF 0.437**, transcript byte-identical to the desktop's.
 *
 * ~~**THE RATIO IS THE PART THAT MATTERS TO THIS FILE.** 884,117,504 ÷ 493,060,445 is **1.79×**, where the
 * Omnilingual int8 row above measures **3.4×** — dynamic-int8 weights are dequantised into fp32 working
 * buffers, fp32 weights are used where they lie. So an int8 120M at the ~138 MB upstream publishes
 * should land near **250–350 MB resident**, which clears `MemAvailable` and the 512 MiB
 * [DW_MODEL_FREE_RAM_MARGIN_BYTES] together — the first artifact in this feature's history that would be
 * [DwModelFit.COMFORTABLE] on this handset rather than TIGHT.~~
 *
 * **THAT PARAGRAPH WAS A PREDICTION AND IT IS RETRACTED, 2026-08-13 06:15, BY MEASURING THE THING IT
 * PREDICTED.** An int8 IndicConformer of exactly that size class — `OpenVoiceOS/ai4bharat-indicconformer-
 * hi-onnx`, **137,677,431 bytes** — was run through the same probe on the same handset:
 *
 *  * `VmHWM` **231,321,600 before → 453,337,088 after load → 538,144,768 peak**, i.e. **513 MiB**;
 *  * load **3,378 ms**; 12,240 ms of Hindi decoded in **6,922 ms, RTF 0.566**.
 *
 * **So the fit verdict is not COMFORTABLE, it is CONDITIONAL, and this file is where that distinction
 * lives.** [dwModelFit] adds [DW_MODEL_FREE_RAM_MARGIN_BYTES] to the peak, so 538,144,768 needs
 * **1,075,015,680 bytes of `availMem`** to answer [DwModelFit.COMFORTABLE]. The handset had 1.38 GB at
 * 06:15 and would have. **At the 774,848 kB this same probe left behind twenty minutes earlier it is
 * [DwModelFit.TIGHT]** — `LITTLE_FREE_MEMORY_RIGHT_NOW`. A model whose verdict flips with whatever the
 * launcher is doing is precisely what [DwModelChoice]'s TIGHT state exists to describe, and calling it
 * COMFORTABLE in advance would have been this file's own [dwModelFit] contract asserted from arithmetic.
 *
 * **WHY THE PREDICTION MISSED, because the mistake is reusable and cheap to repeat here.** `VmHWM` is a
 * **whole-process** high-water mark and the process floor is **231,321,600 bytes before any graph is
 * opened** (231–236 MB in every reading above). Dividing that by a file size gives a ratio that silently
 * contains the floor, so rescaling it to a smaller file shrinks the floor too: 1.79 × 137,677,431 =
 * **246,442,601**, which leaves **15 MB above an empty process** in which to hold 138 MB of weights —
 * floor plus weights alone is **368,999,031**, already past the middle of the predicted band. The honest
 * decomposition is additive — floor + weights + a roughly file-independent arena — and the measured int8
 * ratio is **3.91×** of file (**2.23×** with the floor subtracted), *worse* than the Omnilingual int8's
 * 3.44×, which is the direction the dequantisation sentence predicted.
 *
 * **AND THE SAME MEASUREMENT RETRACTS "int8 DOES NOT TRANSCRIBE" AS A GENERAL CLAIM.** That artifact
 * scores **CER 5.6 / WER 19.8** on the three Hindi FLEURS utterances the 600M fp32 scores 6.9 / 20.9 on.
 * What fails is `quantize_dynamic` applied to the merged 600M graph — this repository's own script on one
 * graph — not int8 IndicConformer. `DwAsrModel.kt` carries the byte-exact reading.
 *
 * **WHAT STILL BLOCKS THE ROW IS THE LANGUAGE, NOT THE SIZE.** The 120M measured above is
 * `jeswinjestin/sherpa-onnx-nemo-ctc-indicconformer-malayalam`: a third-party export whose head is
 * **Malayalam-locked** — handed Odia and Hindi audio it answers in Malayalam script at 100% WER (see
 * `DwAsrModel.kt`, which records both the claim that it served 22 languages and the measurement that
 * retracted it). So it proves the SHAPE and cannot be the row. **What creates the row:** the official
 * per-language 120M `.nemo` (`ai4bharat/indicconformer_stt_or_hybrid_ctc_rnnt_large`, 523,192,320 bytes,
 * downloadable) put through NeMo's own exporter, then this same probe re-run for `or` and `hi`.
 * `docs/ASR-RUNTIME-MEASUREMENT.md` §6 carries every byte and both dead ends, so nobody re-runs them.
 */
val DW_TIER1_CATALOGUE: List<DwModelPlan> = listOf(
    DwModelPlan(
        modelId = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        quantisation = "int8",
        /*
         * arm64-v8a ONLY, AND THE ENGINE IS BUILT FOR TWO. `DW_ASR_ENGINE_ABIS` carries both ARM
         * builds and the ONNX graph itself is architecture-independent — the same `model.int8.onnx`
         * would run under either. What has never happened is a `armeabi-v7a` handset in the room:
         * docs/ASR-RUNTIME-MEASUREMENT.md §6 marks that build **unmeasured**, and this row's peak RSS,
         * real-time factor and transcripts all came off one arm64 phone. A 32-bit process cannot
         * address the 1.24 GB below in the first place.
         *
         * The consequence is that an `armeabi-v7a`-only handset gets `ABI_NOT_BUILT_FOR`, which
         * overstates the absence — the build exists, nobody has run it. That is the erring-low
         * direction this file's band edges take everywhere: refusing a capability nobody has proved
         * costs a designer a download, and offering one nobody has proved costs them the workshop.
         */
        abi = "arm64-v8a",
        /*
         * ── THE HARDEST LINE IN THIS FILE, AND THE POINT OF THE WHOLE FEATURE ─────────────────
         *
         * **ODIA IS NOT IN THIS LIST, AND ODIA IS THE LANGUAGE THESE WORKSHOPS ARE RUN IN.**
         *
         * The model hears it. `DwAsrEngineProbeTest` transcribed real Odia on the fleet's own
         * SM-M325F and the output is recognisably Odia, twice, byte-identical. It scored **53.3%
         * WER** — docs/DEVICE-TIER-MEASUREMENT.md, *Scored, and the score is not good enough to offer
         * yet* — on **FLEURS studio read speech, which is an easier test than a courtyard**, so that
         * figure is a ceiling and the real one is worse.
         *
         * A tag in this list is not a description, it is a **decision with consequences**:
         * `dwOfflineCoverage` reports offline capability for it, the Odia row on the settings card
         * turns green and says *"Dictation in Odia works with no signal"*, and `dwDictationLadder`
         * puts [DwDictationRung.APP_SPEECH_MODEL] ahead of the server — taking the craft-aware rung
         * away from the language that needs it most. **More than half the words wrong is not a
         * language this artifact serves**, and a designer in Odisha installing 365 MB on the strength
         * of that row is the exact failure `DwModelLanguages.kt` opens by naming.
         *
         * **WHAT IT IS INSTEAD OF, WHICH IS THE COMPARISON THAT DECIDES IT.** For Odia the fallback
         * is the server, which carries the craft keyterm list and writes "dabu" rather than "double",
         * and typing where there is no signal. A local model at 53.3% would be replacing a good
         * transcript with a bad one wherever there IS signal, and replacing typing with something
         * worse than typing where there is not.
         *
         * **AND THE REJECTION IS NOT SILENT.** The Odia measurement is carried in `accuracy` below,
         * printed on the card, and named in the language row's own sentence. *"Measured, and it is
         * not good enough"* tells a designer the search has been done and what the bar was;
         * *"nothing hears Odia"* would send them looking for a model that has already been found.
         *
         * ── AND WHY HINDI IS IN IT ────────────────────────────────────────────────────────────
         *
         * **24.2% WER, on the same corpus, by the same script, on the same handset.** That is roughly
         * one word in four and it is not good; what makes it a yes rather than a no is the same
         * comparison that made Odia a no, run the other way. This rung sits BELOW Android's own pack
         * ([DwDictationRung.APP_SPEECH_MODEL]'s placement argument), and on a handset that has the
         * Hindi pack it therefore never fires at all. It fires on a handset WITHOUT the pack, in a
         * courtyard with no signal — where the alternative is not a better transcript, it is no
         * dictation. Plan §2.2's bar is *"better than nothing is the honest bar in a courtyard, but it
         * has to actually clear it"*, and three words in four, offered only where the choice is
         * against silence, clears it.
         *
         * **THIS IS A JUDGEMENT ON A SAMPLE OF THREE UTTERANCES AND SHOULD BE OVERTURNED BY A BIGGER
         * ONE.** n = 3 is a demonstration, not an evaluation; the document says so and so does
         * `accuracy` below. If a real evaluation puts Hindi the wrong side of the bar, this line is
         * the one to delete, and deleting it changes nothing else in the app.
         *
         * **THE OTHER 1,600-ODD ARE UNMEASURED**, which `unmeasuredLanguagesNote` says in that word.
         * They are not absent and they are not present: nobody has asked.
         */
        languages = listOf("hi-IN"),
        // No such dial: see the field. Audio goes in whole and one result comes out.
        contextCapTokens = null,
        runBound = "one utterance handed to the recogniser whole and decoded in one pass — this is " +
            "a non-streaming CTC model with no context window and no KV-cache, so what moves the " +
            "resident set is the LENGTH OF THE AUDIO, not a token count. The peak below was reached " +
            "decoding FLEURS utterances of roughly 9–14 seconds of 16 kHz mono at numThreads = 2. " +
            "What a two-minute recording costs in memory is UNMEASURED — nobody has decoded one.",
        /*
         * MEASURED OFF THE FILES IN `filesDir` ON THE PHONE, not added up from a catalogue: the probe
         * summed `File.length()` over the two installed files and printed 365,438,543. That it also
         * equals the two pinned sizes added together is a cross-check rather than the source.
         *
         * NOTE THIS IS NOT THE DOWNLOAD SIZE. The published container is 292,571,207 bytes
         * (`DW_ASR_MODEL_ARTIFACTS`); this is what stays on the phone afterwards, and it is the
         * figure the storage arithmetic uses because it is the one that has to fit for good.
         */
        onDiskBytes = 365_438_543L,
        /*
         * **THE HIGHEST OF FOUR MEASURED READINGS, AND THE CHOICE OF THE HIGHEST IS THE SAFE
         * DIRECTION.** `VmHWM` across four runs on the same handset: 1,242,996,736 / 1,241,206,784 /
         * 1,258,713,088 / 1,165,746,176 — a 7.4% spread, cross-checked to within 0.1% by
         * `dumpsys meminfo` sampled from the host. Peak RSS is the sturdiest of the three figures this
         * lane measured; the timings are not (see `realTimeFactor`).
         *
         * Taking the LARGEST is this file's own erring-low rule applied to a memory figure: understate
         * it and `dwPlanFits` offers a model the low-memory killer ends halfway through, which costs a
         * designer the work. Overstate it and the app refuses a model that might just have fitted.
         *
         * **AND THE CONSEQUENCE IS THAT THE FLEET'S OWN HANDSET REFUSES IT.** 1.26 GB + the 512 MiB
         * margin is 1.78 GB against the M32's ~1.5–1.76 GB of free memory, so `dwPlanFits` answers
         * NOT_ENOUGH_FREE_RAM_NOW and `dwModelFit` answers TIGHT — which is not WILL_NOT_FIT, and the
         * difference is the whole of `DwModelChoice.kt`: the designer may take it anyway, having been
         * told what it is expected to cost. That is the intended path on this handset, not an accident.
         */
        peakRssBytes = 1_258_713_088L,
        measuredOn = "Samsung Galaxy M32 (SM-M325F), Android 13, arm64-v8a, idle and on the charger",
        // The word, not a guess. See the field: what was measured is weaker than the question.
        survivesBackgrounding = null,
        realTimeFactor = DwModelRtfBand(
            fastest = 1.078,
            slowest = 2.967,
            utterances = 12,
            measuredOn = "Samsung Galaxy M32 (SM-M325F), Android 13, numThreads = 2",
        ),
        /*
         * ELEVEN LANGUAGES SCORED, AND ONE OF THEM IS IN `languages`. That asymmetry is the point of
         * this list existing — see [DwModelAccuracy].
         *
         * ── **NINE OF THESE ROWS WERE ADDED 2026-08-13 AND THEY REPLACE THE WORD "UNMEASURED"** ──
         *
         * `unmeasuredLanguagesNote` below used to say *"Two languages have been measured on a handset
         * — Hindi and Odia"*, which was true when it was written and is not any more. Nine more of the
         * nineteen `DW_DICTATION_LANGUAGES` were decoded through **this** artifact, on **this**
         * handset, by `DwAsrEngineProbeTest` — the shipped [DwAsrModelFamily.OMNILINGUAL_ASR_CTC]
         * branch, both files hashed to the pinned digests and VERIFIED in that run — against three
         * FLEURS validation utterances each, scored through one normaliser. Every transcript is in
         * `docs/DEVICE-TIER-MEASUREMENT.md`, verbatim, beside its reference.
         *
         * **THE ROW THAT MATTERS MOST IS `ur-IN`, AND IT IS THE TRAP THIS FILE EXISTS TO CATCH.** This
         * artifact's `tokens.txt` carries **155 tokens in Arabic script** — see `languageNote` in
         * `DwAsrModel.kt`, which cites that number as evidence the model is *able* to write the script
         * while saying in the same breath that being able to write one is necessary and not sufficient.
         * Handed Urdu speech it emits **not one Arabic character**: it answers in fluent **Devanagari**,
         * transliterating the utterance into Hindi, on all three utterances, at **WER 100%**. So the
         * necessary-not-sufficient rule is no longer an argument in a comment — it is a measurement of
         * this exact file, and `ur-IN` is a language the artifact cannot serve however many Arabic
         * tokens it holds.
         *
         * **AND THREE ROWS HERE SCORE WHAT HINDI SCORES, WHICH IS A QUESTION FOR THE OWNER AND NOT A
         * LINE THIS LANE MAY EDIT.** `gu-IN` 24.9, `kn-IN` 24.7 and `pa-IN` 24.9 against Hindi's 24.2
         * (24.4 on this run) — same corpus, same handset, same n, within a point of each other. The
         * argument that admitted Hindi to `languages` was that the rung sits BELOW Android's own pack
         * and therefore only fires where the alternative is no dictation at all; on the fleet's handset
         * Android has **no** pack for Gujarati, Kannada or Punjabi (docs/DICTATION-LANGUAGE-PACK-
         * MEASUREMENT.md: thirty languages listed, exactly `hi-IN` and `en-IN` ours), so for those
         * three the model is the only offline option there has ever been and the argument applies more
         * strongly than it does to Hindi. **Adding a tag to `languages` turns a settings row green and
         * reorders the dictation ladder**, which is a decision with a surface behind it; this lane
         * measured and did not decide. The numbers are here so the decision is one line away.
         */
        accuracy = listOf(
            DwModelAccuracy(
                tag = "or-IN",
                cerPercent = 15.2,
                werPercent = 53.3,
                corpus = "Google FLEURS or_in dev — studio read speech by a professional speaker, " +
                    "which is an EASIER test than a courtyard, so this is a ceiling and a real " +
                    "workshop would score worse",
                utterances = 3,
                referenceWords = 60,
            ),
            DwModelAccuracy(
                tag = "hi-IN",
                cerPercent = 7.3,
                werPercent = 24.2,
                corpus = "Google FLEURS hi_in dev — studio read speech, the same ceiling caveat as " +
                    "the row above",
                utterances = 3,
                referenceWords = 91,
            ),
            /*
             * ── THE NINE ADDED 2026-08-13, ALL FROM ONE RUN ON THE FLEET'S OWN SM-M325F ─────────
             *
             * One `DwAsrEngineProbeTest` invocation decoded 39 WAVs through one loaded recogniser, so
             * every row below shares a handset, a thread count, a normaliser and a corpus split, and
             * the rows are therefore comparable with each other in a way that separately-run figures
             * would not be. They are NOT byte-identical in method to the two rows above — those were
             * scored against the FLEURS `dev.tsv`, these against the same dataset's `transcription`
             * field — which is why the Odia and Hindi rows were left exactly as the earlier lane
             * measured them rather than being overwritten: re-running Odia here gave CER 13.8 / WER
             * 51.4 against the recorded 15.2 / 53.3, so the recorded figures reproduce and there was
             * nothing to correct.
             *
             * Every one of these nine is ABOVE Hindi's error and none is in `languages`.
             */
            DwModelAccuracy(
                tag = "gu-IN",
                cerPercent = 6.1,
                werPercent = 24.9,
                corpus = "Google FLEURS gu_in validation, first 3 utterances — studio read speech, " +
                    "the same ceiling caveat as every row here. Decoded on the handset 2026-08-13 " +
                    "through the shipped OMNILINGUAL_ASR_CTC branch",
                utterances = 3,
                referenceWords = 63,
            ),
            DwModelAccuracy(
                tag = "kn-IN",
                cerPercent = 6.2,
                werPercent = 24.7,
                corpus = "Google FLEURS kn_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 55,
            ),
            DwModelAccuracy(
                tag = "pa-IN",
                cerPercent = 10.7,
                werPercent = 24.9,
                corpus = "Google FLEURS pa_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 94,
            ),
            DwModelAccuracy(
                tag = "bn-IN",
                cerPercent = 10.6,
                werPercent = 43.2,
                corpus = "Google FLEURS bn_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 53,
            ),
            DwModelAccuracy(
                tag = "ne-IN",
                cerPercent = 15.3,
                werPercent = 45.9,
                corpus = "Google FLEURS ne_np validation, first 3 utterances — studio read speech, " +
                    "and the corpus is Nepal's rather than India's, which is the closest FLEURS has " +
                    "to this app's ne-IN. Same handset run as the row above",
                utterances = 3,
                referenceWords = 38,
            ),
            DwModelAccuracy(
                tag = "ta-IN",
                cerPercent = 8.2,
                werPercent = 52.0,
                corpus = "Google FLEURS ta_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 58,
            ),
            DwModelAccuracy(
                tag = "ml-IN",
                cerPercent = 9.5,
                werPercent = 54.1,
                corpus = "Google FLEURS ml_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 51,
            ),
            DwModelAccuracy(
                tag = "te-IN",
                cerPercent = 18.0,
                werPercent = 54.6,
                corpus = "Google FLEURS te_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 46,
            ),
            /*
             * **THE SCRIPT IS WRONG, NOT MERELY THE WORDS, AND 100% IS THEREFORE THE HONEST FIGURE.**
             * Three Urdu utterances, three answers in Devanagari, zero Arabic characters emitted — so
             * every word is a miss by construction and the WER is 100.0 rather than a high number that
             * might be read as "nearly". The CER of 85.5 is the mean of 80.0 / 84.9 / 91.6 and is what
             * a scorer reports when two scripts share nothing but their spaces.
             *
             * The transcripts are worth reading before anybody trusts a token count again: `آپ اہرام
             * کو تاریکی میں دیکھ سکتے ہیں` came back as `आप अहराम को तारीखी में देख सकते हैं` — the
             * model HEARD the sentence and wrote it in the other language's alphabet. That is a
             * transcript a designer would paste into a field and a report would print.
             */
            DwModelAccuracy(
                tag = "ur-IN",
                cerPercent = 85.5,
                werPercent = 100.0,
                corpus = "Google FLEURS ur_pk validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the rows above. The model answered in DEVANAGARI on all " +
                    "three, emitting no Arabic-script character at all, so this is a script failure " +
                    "and not a word-accuracy figure",
                utterances = 3,
                referenceWords = 87,
            ),
        ),
        unmeasuredLanguagesNote = "Meta CLAIM more than 1,600 languages for the family this artifact " +
            "was exported from. That is a claim about a family and this app does not repeat it as a " +
            "property of this file. ELEVEN languages have now been measured on a handset — Hindi, " +
            "Odia, Bengali, Gujarati, Kannada, Malayalam, Nepali, Punjabi, Tamil, Telugu and Urdu, " +
            "eleven of the nineteen this app offers — and only Hindi is offered, because only Hindi " +
            "clears the bar the catalogue argues for. The eight not yet measured are Assamese, " +
            "English (India), Marathi, Sanskrit, Konkani, Manipuri, Kashmiri and Sindhi: UNMEASURED, " +
            "in that word, and neither claimed nor denied. The vocabulary is a separate and weaker " +
            "fact, and 2026-08-13 turned it into the clearest example this repository has of why: " +
            "tokens.txt carries 155 Arabic-script tokens, and handed Urdu speech the model wrote " +
            "DEVANAGARI on all three utterances and no Arabic character at all. Being able to spell a " +
            "script is necessary for emitting it and is not sufficient, and Urdu is now the measured " +
            "proof rather than the worked example. One language can be denied without audio for the " +
            "same reason read the other way: tokens.txt contains ZERO Meetei Mayek characters, so " +
            "Manipuri written in its own script is not something this artifact can produce at all.",
    ),
)

/**
 * Whether this APK **CONTAINS** a speech-recognition runtime. **IT NOW DOES, AND THIS CONSTANT IS
 * THEREFORE KNOWN-FALSE. READ THE CORRECTION BEFORE THE REST.**
 *
 * ── CORRECTED 2026-08-12, EVENING, BY THE LANE THAT REVIEWED THE ENGINE ──────────────────────
 *
 * Everything below this block was written when no engine shipped. The reasoning in it still holds and
 * is preserved verbatim; **its opening claim does not.** The lane that vendored
 * `sherpa-onnx-static-link-onnxruntime-1.13.5.aar` put the engine inside the package:
 * **23,646,824 bytes at `lib/arm64-v8a/libsherpa-onnx-jni.so`**, read off the *installed* `base.apk`
 * on the fleet's SM-M325F, plus 16,152,132 at `lib/armeabi-v7a/`. `minSdk = 26` gives
 * `extractNativeLibs="false"`, so it is never unpacked to a directory — it maps straight out of
 * `base.apk!/lib/arm64-v8a`. That is why `DwAsrEngineProbeTest` prints the APK's own `lib` directory
 * as not holding it, and it is the trap in this correction: **"not on disk" must not be read as "not
 * in the build".**
 *
 * **WHAT THE STALE VALUE COSTS A DESIGNER, CONCRETELY.** With this constant `false` and
 * [DW_ASR_ARTIFACTS] empty, [dwAsrMayLoad] is false, so [dwTier1Offer] answers
 * [DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD] and the sentence rendered under it opens *"This app has no
 * speech engine of its own on this phone"* — on a phone carrying 23.6 MB of exactly that. **The error
 * points the safe way**: the app claims LESS than it holds and never more, which is why this is a
 * correction to act on rather than a defect that stops a release.
 *
 * **WHY IT WAS NOT SIMPLY FLIPPED IN PASSING.** `true` sends [dwTier1Offer] down the installed branch
 * to [DwTierRefusal.NO_MEASURED_MODEL], which is the truthful answer while [DW_TIER1_CATALOGUE] is
 * empty — but it also rewrites the Tier 1 sentence a designer reads and invalidates seven assertions
 * in `DwDeviceTierTest`, one of which asserts this very falsehood in its own message
 * (*"sherpa-onnx is step 4 of the plan and is not in this APK"*). That is a deliberate pass with the
 * tests moved in the same commit, not a one-token edit made on the way past.
 *
 * This constant means "baked into the APK", and it is `false` by a decision rather than by a gap in
 * the work: docs/ASR-RUNTIME-MEASUREMENT.md measured the engine at +53,308,196 bytes (3.03×) on the
 * ARM-pair APK, and this app's updater fetches the whole APK on every release behind a dialog with no
 * "Later" button. The engine is therefore an **opt-in install** the designer chooses (`DwAsrRuntime`),
 * offered once at first run and standing permanently in Settings, because only some of these designers
 * work where there is no signal and only sometimes.
 *
 * SO THE CONSTANT IS NOT DEAD, IT CHANGED JOB. [dwTier1Offer] reads it as the first of two ways the
 * engine could be present — in the APK, or installed on this handset — and the second is now the live
 * one. Flipping it to `true` would be a claim that the libraries ship in the package, which would also
 * mean the loader, a model and the wiring into `DwDictationLadder` exist; none of those do.
 *
 * ── FLIPPED 2026-08-12, LATE, AND THE LAST PARAGRAPH ABOVE IS WHY IT TOOK A WHOLE PASS ────────
 *
 * **IT IS NOW `true`, AND EVERY ONE OF THE THREE CONDITIONS IT NAMED IS MET IN THIS COMMIT.** The
 * paragraph above set the bar for flipping it — libraries in the package, a loader, a model, and the
 * wiring into `DwDictationLadder` — and it was right to; the previous lane left it `false` precisely
 * because only the first was true. What is true now, each checkable rather than asserted:
 *
 *  | the bar it set | where it is met |
 *  |---|---|
 *  | the libraries ship in the package | `lib/arm64-v8a/libsherpa-onnx-jni.so`, **23,646,824 bytes**, read off the installed `base.apk`. `implementation(":sherpa-onnx-static-link-onnxruntime-1.13.5@aar")` in `app/build.gradle.kts` |
 *  | a loader exists | `ui/designworkshop/DwAsrSpeechModel.kt` — `OfflineRecognizer` over the verified model in `filesDir`, gated on `dwAsrModelMayLoad` |
 *  | a model exists | [DW_TIER1_CATALOGUE], one row, every number off a handset |
 *  | the wiring into `DwDictationLadder` | `DwDictationRung.APP_SPEECH_MODEL` no longer steps past itself in `beginAt`; it decodes |
 *
 * **WHAT THE STALE `false` WAS COSTING, WHICH IS THE REASON THIS IS A DEFECT AND NOT A TIDY-UP.**
 * With it `false` and [DW_ASR_ARTIFACTS] empty, [dwAsrMayLoad] is false, so [dwTier1Offer] answered
 * [DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD] and the Appearance screen opened *"This app has no speech
 * engine of its own on this phone"* — **on a phone carrying 23.6 MB of exactly that.** Worse,
 * `DwDeviceTierTest` asserted the falsehood in its own failure message, so the green suite certified
 * it. The tests moved in this same commit, which is what the paragraph above asked for.
 *
 * **AND THE `.so` STILL CANNOT BE MOVED OUT OF THE APK, WHICH IS SETTLED AND NOT WORTH REVISITING.**
 * docs/DEVICE-TIER-MEASUREMENT.md, *THE FINDING THAT INVALIDATES A DESIGN*: every entry class in
 * `com.k2fsa.sherpa.onnx` static-inits `System.loadLibrary("sherpa-onnx-jni")`, which resolves through
 * `ClassLoader.findLibrary`, which searches only `nativeLibraryDirectories` — and `filesDir` is not in
 * that list and cannot be put in it. `System.load(absolutePath)` records the library under its path, so
 * the binding's own `loadLibrary` still throws before a line of our code runs. **So the engine is in
 * the APK by necessity, this constant is the honest description of that, and the opt-in download half
 * of `DwAsrRuntime.kt` is unreachable rather than wrong.** What IS downloaded is the model, which is
 * data rather than code and has no such constraint — see `DW_ASR_MODEL_ARTIFACTS`.
 */
const val DW_TIER1_RUNTIME_PRESENT: Boolean = true

/** Whether this APK contains an on-device language-model runtime (LiteRT / ONNX). **IT DOES NOT.** */
const val DW_TIER2_RUNTIME_PRESENT: Boolean = false

// ---------------------------------------------------------------------------------------------
// The margins the fit arithmetic keeps — chosen, and labelled as chosen
// ---------------------------------------------------------------------------------------------

/**
 * How much free memory must remain over and above a model's peak RSS before a job may start.
 *
 * 512 MiB, and **THIS IS A CHOSEN NUMBER, NOT A MEASURED ONE.** It stands for everything the app is
 * still holding while a model runs — the draft, the decoded photographs, Compose's own retained
 * bitmaps — plus whatever the system decides to want back at the wrong moment. The measurement that
 * would replace it with a real figure is the second open question in
 * docs/DEVICE-TIER-MEASUREMENT.md, and until it is taken this margin is the reason the arithmetic
 * errs towards refusing.
 */
const val DW_MODEL_FREE_RAM_MARGIN_BYTES: Long = 512L * MIB

/**
 * How much free storage must remain after a model is downloaded. 1 GiB, also a chosen number.
 *
 * A workshop day fills a phone with photographs and audio, and a model that fits with nothing to
 * spare converts the next capture into a failure. The download is refused rather than the recording.
 */
const val DW_MODEL_FREE_STORAGE_MARGIN_BYTES: Long = 1024L * MIB

// ---------------------------------------------------------------------------------------------
// The verdict
// ---------------------------------------------------------------------------------------------

/** Why a tier is not being offered on this handset. Each value has exactly one sentence. */
enum class DwTierRefusal {
    /**
     * Nobody has weighed a model for this tier yet. **THE ANSWER EVERY HANDSET GETS FOR TIER 2
     * TODAY**, and the one that names docs/DEVICE-TIER-MEASUREMENT.md.
     */
    NO_MEASURED_MODEL,

    /** There is no runtime in this build that could load a model even if one were listed. */
    NO_RUNTIME_IN_THIS_BUILD,

    /**
     * **TIER 1 ONLY: the engine is an optional install, this handset could take it, and it is not
     * there yet.** The one refusal in this enum whose next move is a control on the same screen.
     *
     * Distinct from [NO_RUNTIME_IN_THIS_BUILD], which says there is nothing to install at all. This
     * one says there is, and the "Offline speech engine" card above is where it happens. It also
     * covers a download that is IN FLIGHT — deliberately, because that card is already showing the
     * progress and a tier sentence saying "installed" a moment early would be the second account of
     * one fact that this repository has a scar about (`DwDictationRun`'s remembered refusal against a
     * pack that had since arrived, two accounts of one pack one tap apart).
     */
    RUNTIME_NOT_INSTALLED,

    /**
     * **TIER 1 ONLY: this app could not look at its own files, so whether its engine is installed is
     * unknown.** The honest-unknown rule, applied to a question about our own storage.
     *
     * Not folded into [RUNTIME_NOT_INSTALLED]: that would offer a designer who already has the engine
     * a second 24 MB download, and this app does not spend somebody's data on a guess. The same
     * distinction [DwAsrRuntimeState.UNKNOWN] draws, carried into the tier vocabulary so it survives
     * being rendered on the card below.
     */
    RUNTIME_UNMEASURED,

    /**
     * Android flags this as a low-memory device, or it reports less memory than the smallest
     * measured model needs however much of it happens to be free. A durable no, not a "not now".
     */
    DEVICE_TOO_SMALL,

    /** Enough memory in total, not enough free at this moment. Re-probing later may say otherwise. */
    NOT_ENOUGH_FREE_RAM_NOW,

    /** The model would not fit on the phone with room left for a workshop day's photographs. */
    NOT_ENOUGH_FREE_STORAGE,

    /** No runtime build exists for any ABI this handset reports. */
    ABI_NOT_BUILT_FOR,

    /** `MemoryInfo.availMem` could not be read, so "enough free" is a question, not a yes. */
    FREE_RAM_UNMEASURED,

    /** `StatFs` would not answer, so whether the download fits is unknown and will not be guessed. */
    FREE_STORAGE_UNMEASURED,

    /** `Build.SUPPORTED_ABIS` came back empty, so which build to fetch is unknown. */
    ABI_UNMEASURED,

    /**
     * The table said this handset was fine and the load failed on it anyway. That is data, and it
     * outranks the table: see [DwLoadFailureNote] and [dwFallbackAfterLoadFailure].
     */
    LOAD_FAILED_HERE_BEFORE,
}

/**
 * What may be offered for one tier on this handset right now — the decision behind every control.
 *
 * A sealed pair rather than a nullable [DwModelPlan] so that "no" is forced to carry its reason.
 * A `null` model would let a caller render an empty space, and the rule from plan §2.1 is that **a
 * device that cannot run a tier says so in words, once** — not a greyed-out control, not silence.
 */
sealed interface DwTierOffer {

    /**
     * A model this handset can hold, at the cap it was measured at, with its real size.
     *
     * **NOTHING CONSTRUCTS THIS TODAY**, because both catalogues are empty. It is written, and
     * `DwDeviceTierTest` exercises the arithmetic behind it against an openly invented plan, so that
     * the day a real measurement arrives the only new thing in the app is a row of numbers.
     */
    data class Available(
        val plan: DwModelPlan,
        /** Free memory that would remain beyond the peak RSS. For the sentence, not for a gate. */
        val headroomBytes: Long,
    ) : DwTierOffer

    /** No, with the reason, so the screen can say which no it is. */
    data class None(val refusal: DwTierRefusal) : DwTierOffer
}

/**
 * A load that failed on a handset the table said was fine. **RECORDED, BECAUSE IT IS EVIDENCE.**
 *
 * Plan §2.1: *"If a load fails on a device the table said was fine, that is data. Record it, fall
 * back a tier, and tell the designer what changed rather than failing the job silently."* A note
 * carries the model AND the cap for the same reason [DwModelPlan] does — the same weights at a 4K
 * cap and a 2K cap are two different runs, and only one of them may have failed.
 */
data class DwLoadFailureNote(
    val tier: DwAiTier,
    val modelId: String,
    /**
     * The cap the failed run was configured at, **or null for a model that has no such dial.**
     *
     * Nullable for [DwModelPlan.contextCapTokens]'s reason and matched against it directly, so a CTC
     * speech model — which has no context window at all — records a failure that pairs with its own
     * catalogue row rather than with an invented number. Two nulls match, which is correct: for a
     * model with one configuration there is one run to have failed.
     */
    val contextCapTokens: Int?,
    /** What the runtime actually said, kept verbatim for the measurement doc. */
    val detail: String,
    /**
     * **WHETHER THE DESIGNER PICKED THIS MODEL OVER THE ONE THIS PHONE'S OWN READING SUGGESTED.**
     *
     * Since the recommendation became advice ([DwModelChoice]), a load can fail for two quite
     * different reasons and the sentence a designer reads must not blame the wrong one:
     *
     *  * `false` — **the table was wrong about this handset.** The reading said comfortable and the
     *    load failed anyway. That is the case plan §2.1 calls data, and the value of it is that it
     *    contradicts a measurement somebody wrote down; it belongs in
     *    docs/DEVICE-TIER-MEASUREMENT.md as a correction.
     *  * `true` — **the app said this would be tight and the designer chose it anyway.** The load
     *    failing is the outcome the override sentence named before the tap, so telling them "the
     *    table was wrong" would be this app disowning advice it actually gave. It is still recorded,
     *    because a TIGHT model that fails on a handset is evidence about where the margin really
     *    sits — but it is not a contradiction, and the fallback sentence says so.
     *
     * Defaulted to `false` so that the many callers who are recording an ordinary failure say
     * nothing at all about a choice; the override path is the one that has to be explicit.
     */
    val chosenAgainstAdvice: Boolean = false,
)

/** Everything one settings card, or one job scheduler, needs to know about this handset. */
data class DwTierRecommendation(
    val deviceClass: DwDeviceClass,
    /** The reading this was decided from, carried so the screen can print the numbers it used. */
    val measurement: DwDeviceMeasurement,
    /**
     * **THE SUGGESTION FOR TIER 1 — WHAT THIS PHONE'S OWN READING POINTS TO, AND NOT A VERDICT.**
     *
     * Read it beside [tier1Choices], which is every measured model judged against this handset. The
     * offer is what the probe would pick; the choices are what the designer may pick from, and the
     * two are produced from one arithmetic (`DwModelChoiceTest` pins that a `Available` offer is
     * always exactly the choices that came out [DwModelFit.COMFORTABLE]).
     */
    val tier1: DwTierOffer,
    /** The suggestion for Tier 2. See [tier1] — the same relationship to [tier2Choices]. */
    val tier2: DwTierOffer,
    /**
     * Tier 3 is not a device question at all — it is the server chain in
     * `backend/app/services/ai.py`, which has worked since long before this file. It is carried
     * anyway so the card cannot be read as "this phone can do nothing".
     */
    val connection: DwConnection,
    /**
     * **EVERY MEASURED TIER 1 MODEL, JUDGED AGAINST THIS HANDSET — THE LIST A DESIGNER CHOOSES
     * FROM.** Empty today, because [DW_TIER1_CATALOGUE] is.
     *
     * It is not the same list as "what we recommend": it holds the ones this phone is comfortable
     * with, the ones it can run with less room to spare, the ones that will not fit at all and the
     * ones nothing could be said about — each carrying why. See [DwModelChoice], and
     * [dwModelDownloadMayBeOffered] for the one gate that decides whether a control is drawn.
     */
    val tier1Choices: List<DwModelChoice> = emptyList(),
    /** The same for Tier 2. Empty today, because [DW_TIER2_CATALOGUE] is. */
    val tier2Choices: List<DwModelChoice> = emptyList(),
) {
    /** True when the server chain can be reached right now. The only tier that is ever available. */
    val tier3Available: Boolean get() = connection != DwConnection.NONE
}

// ---------------------------------------------------------------------------------------------
// The arithmetic
// ---------------------------------------------------------------------------------------------

/**
 * Whether [plan] fits on the handset [measurement] describes; `null` when it does.
 *
 * ORDER OF CHECKS IS ORDER OF DURABILITY, most permanent first, because the first refusal found is
 * the one that gets rendered and the useful sentence is the one that tells the designer whether
 * waiting would help. "Your phone is too small" is for ever; "not enough free storage" is until they
 * delete something; "not enough free memory right now" is until they close an app.
 *
 * EVERY UNMEASURED SIGNAL THAT IS CHECKED HERE REFUSES — free storage, free memory and the ABI list
 * each have their own refusal. A missing `availMem` does not mean zero and does not mean plenty; it
 * means the question was not answered, and starting a multi-hundred-megabyte allocation on the
 * strength of a question nobody answered is how the low-memory killer takes a workshop draft.
 *
 * THE ONE SIGNAL WHOSE ABSENCE DOES NOT REFUSE IS TOTAL MEMORY, and that is not a hole. Total is
 * consulted only as a durable ceiling — "no amount of closing apps would help" — and the free-memory
 * check below is the gate. The two come out of ONE `MemoryInfo` read in `DwDeviceProbe.kt`, so a
 * null total arrives with a null `availMem` beside it and step 4 refuses on that; there is no
 * reachable reading in which the total went missing and the free figure did not.
 */
internal fun dwPlanFits(plan: DwModelPlan, measurement: DwDeviceMeasurement): DwTierRefusal? {
    // 1. Android's own verdict, and total memory. Durable: nothing the designer does changes it.
    if (measurement.lowRamDevice == true) return DwTierRefusal.DEVICE_TOO_SMALL
    val total = measurement.totalRamBytes
    if (total != null && total > 0L && plan.peakRssBytes + DW_MODEL_FREE_RAM_MARGIN_BYTES > total) {
        // Not "not right now" — the phone does not have this much memory in total, so no amount of
        // closing other apps would help and saying "try again later" would be a lie.
        return DwTierRefusal.DEVICE_TOO_SMALL
    }

    // 2. The ABI, which decides whether a runtime build for this handset exists at all.
    if (measurement.abis.isEmpty()) return DwTierRefusal.ABI_UNMEASURED
    if (measurement.abis.none { it.equals(plan.abi, ignoreCase = true) }) {
        return DwTierRefusal.ABI_NOT_BUILT_FOR
    }

    // 3. Storage, before memory, because it is the durable one of the two: a model that will not
    //    fit on the phone can never be fetched, whereas free memory changes minute to minute.
    val storage = measurement.freeStorageBytes ?: return DwTierRefusal.FREE_STORAGE_UNMEASURED
    if (storage < plan.onDiskBytes + DW_MODEL_FREE_STORAGE_MARGIN_BYTES) {
        return DwTierRefusal.NOT_ENOUGH_FREE_STORAGE
    }

    // 4. Free memory at this instant. The reason the whole recommendation is re-probed rather than
    //    cached — see [dwProbeIsStale].
    val free = measurement.availableRamBytes ?: return DwTierRefusal.FREE_RAM_UNMEASURED
    if (free < plan.peakRssBytes + DW_MODEL_FREE_RAM_MARGIN_BYTES) {
        return DwTierRefusal.NOT_ENOUGH_FREE_RAM_NOW
    }
    return null
}

/**
 * Free memory that would remain beyond a plan's peak RSS, or null when free memory is unmeasured.
 *
 * Never used as a gate — [dwPlanFits] is the gate — only as a number for a sentence. A negative
 * result is possible and is kept as one rather than clamped to zero, because "you are 300 MB short"
 * is a more useful thing to be able to say than "you have 0 MB spare".
 */
internal fun dwHeadroomBytes(plan: DwModelPlan, measurement: DwDeviceMeasurement): Long? =
    measurement.availableRamBytes?.let { it - plan.peakRssBytes }

/**
 * The best plan from [catalogue] this handset can hold, or the reason there is none.
 *
 * "Best" is the LARGEST measured peak RSS that still fits, tie-broken on the artifact id so the
 * choice is deterministic across runs — a recommendation that changed between two probes of an
 * unchanged handset would be untestable and would look like a bug to the designer watching it.
 *
 * When nothing fits, the refusal reported is the one belonging to the plan with the SMALLEST PEAK
 * RSS — smallest by the number the ranking is done on, not by download size — because that is the
 * one that names the real obstacle. If even the least demanding plan is short of storage, "not
 * enough free storage" is the news; the largest plan's refusal would be true of almost any device
 * and would tell a designer nothing about theirs.
 */
internal fun dwBestPlan(
    catalogue: List<DwModelPlan>,
    measurement: DwDeviceMeasurement,
    failures: List<DwLoadFailureNote>,
): DwTierOffer {
    // A model that has already failed to load on THIS handset is off the table, whatever the
    // arithmetic says: the table was wrong about this phone and the evidence outranks the table.
    // Matched on id AND cap, because the same weights at a smaller cap is a different run that may
    // well succeed — see [DwLoadFailureNote].
    val untried = catalogue.filterNot { plan ->
        failures.any { it.modelId == plan.modelId && it.contextCapTokens == plan.contextCapTokens }
    }
    if (untried.isEmpty()) {
        return DwTierOffer.None(
            if (catalogue.isEmpty()) DwTierRefusal.NO_MEASURED_MODEL
            else DwTierRefusal.LOAD_FAILED_HERE_BEFORE
        )
    }
    val ranked = untried.sortedWith(
        compareByDescending<DwModelPlan> { it.peakRssBytes }.thenBy { it.modelId }
    )
    ranked.forEach { plan ->
        if (dwPlanFits(plan, measurement) == null) {
            val headroom = dwHeadroomBytes(plan, measurement)
            // `dwPlanFits` returned null, so `availableRamBytes` was non-null and the headroom is a
            // real number; the elvis is here only because the compiler cannot know that.
            return DwTierOffer.Available(plan, headroom ?: 0L)
        }
    }
    val smallest = ranked.last()
    return DwTierOffer.None(dwPlanFits(smallest, measurement) ?: DwTierRefusal.DEVICE_TOO_SMALL)
}

/**
 * What this handset is offered for Tier 2 — **nothing, on every device today**, and since 2026-08-13
 * the reason is [DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD] rather than a missing measurement:
 * [DW_TIER2_CATALOGUE] holds two weighed rows and this APK still has nothing that can load one.
 *
 * ── THE LOW-RAM SHORTCUT MOVED, AND THE ARGUMENT FOR ITS OLD POSITION IS WHY ───────────────────
 *
 * It used to be the FIRST check, on this reasoning, which was right at the time: *"nothing has been
 * measured yet" invites a designer to come back after the next update, and for a Go-edition handset the
 * answer after that update will still be no.* **The premise of that sentence is now false.** The
 * smallest Tier 2 row needs 1,733 MiB, which fits inside a 3 GB handset with the app's own 512 MiB
 * margin intact, so "the answer will still be no" is not something this function knows about a
 * low-memory phone any more — and `dwModelChoices`, reading the same catalogue two lines away in
 * [dwRecommendTiers], would have shown that phone a row marked COMFORTABLE directly beneath a tier
 * sentence telling it that it does not have the memory. Two accounts of one fact on one card is the
 * defect this repository keeps paying for.
 *
 * So the durable, build-level facts are asked first — is there a model, is there a runtime — and the
 * question about THIS handset is left to [dwBestPlan] and [dwPlanFits], which answer it with the
 * phone's own numbers rather than from its class. A Go-edition handset that genuinely cannot hold the
 * smallest row still gets [DwTierRefusal.DEVICE_TOO_SMALL], from the arithmetic, on the day the runtime
 * lands and this line becomes reachable.
 */
internal fun dwTier2Offer(
    measurement: DwDeviceMeasurement,
    deviceClass: DwDeviceClass,
    catalogue: List<DwModelPlan>,
    failures: List<DwLoadFailureNote>,
): DwTierOffer {
    if (catalogue.isEmpty()) return DwTierOffer.None(DwTierRefusal.NO_MEASURED_MODEL)
    if (!DW_TIER2_RUNTIME_PRESENT) return DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD)
    // Android's own verdict about the build still outranks the arithmetic — `isLowRamDevice` knows
    // things a memory total does not — and `dwPlanFits` checks it first for exactly that reason. The
    // CLASS, which is a band this file computed from a byte count, does not get to pre-empt the row.
    if (deviceClass == DwDeviceClass.LOW_RAM && measurement.lowRamDevice == true) {
        return DwTierOffer.None(DwTierRefusal.DEVICE_TOO_SMALL)
    }
    return dwBestPlan(catalogue, measurement, failures.filter { it.tier == DwAiTier.TIER_2 })
}

/**
 * What this handset is offered for Tier 1 — **still [DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD] on every
 * device today, but ASKED rather than assumed.**
 *
 * ── WHY THE ANSWER IS THE SAME AND THE CODE IS NOT ────────────────────────────────────────────
 *
 * Until 2026-08-12 this function's whole body was `if (!DW_TIER1_RUNTIME_PRESENT) return …`, a
 * compile-time constant that made "there is no engine in this build" the answer on every handset in
 * the world. The engine is now an **opt-in install** (`DwAsrRuntime.kt`), so presence is a property of
 * the HANDSET and not of the build, and the honest answers multiply: not installed but installable;
 * not installed and not installable, for two different reasons; installed but with no model to feed
 * it; and not looked at yet. Each of those sends a designer somewhere different, so each gets its own
 * refusal — see the table in this file's header, which docs/DEVICE-TIER-MEASUREMENT.md mirrors.
 *
 * TODAY EVERY ONE OF THEM RESOLVES TO THE SAME PLACE, because [DW_ASR_ARTIFACTS] is empty: nothing has
 * been published to install, so the answer is "no engine, and none to be had", which is
 * [DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD] and is the same value the constant used to return. **The
 * shipped sentence is therefore nearly unchanged and the reasoning behind it is completely different**,
 * which is exactly the state the measurement document predicted this lane would be in.
 *
 * ── THE ORDER, AND WHY THE ENGINE QUESTION COMES BEFORE THE MODEL QUESTION ────────────────────
 *
 * The asymmetry with [dwTier2Offer] survives and is the honest difference between the two lanes. For
 * Tier 2 the blocker with a document behind it is a missing measurement, so the catalogue is checked
 * first. For Tier 1 the engine is checked first, because a designer reading "no model has been
 * measured" would reasonably conclude that a model turning up is all that stands in the way — and on a
 * handset with no engine installed, it is not. THIS FILE MAY DESCRIBE WHAT A HANDSET COULD RUN; IT
 * MUST NOT IMPLY THE APP CAN RUN IT.
 *
 * The one exception is [DwAsrOffer.NO_MODEL_TO_FEED_IT], which arrives here as
 * [DwTierRefusal.NO_MEASURED_MODEL] even though the engine is NOT installed. That is deliberate and it
 * is the same judgement `dwAsrOffer` makes one file over: when the engine cannot be installed because
 * there would be nothing for it to say, the missing thing a designer is waiting for is the model, and
 * naming the engine instead would send them to a card whose own sentence points at the model anyway.
 *
 * A LOW-MEMORY HANDSET IS STILL NOT REFUSED FOR ITS SIZE. The measurement doc's first row gives it
 * "smallest ASR model only", so its Tier 1 answer is about this app, not about the phone.
 */
internal fun dwTier1Offer(
    measurement: DwDeviceMeasurement,
    catalogue: List<DwModelPlan>,
    failures: List<DwLoadFailureNote>,
    runtime: DwAsrRuntimeStatus,
    connection: DwConnection,
    artifacts: List<DwAsrArtifact>,
    /**
     * Whether the engine is baked into the package. **A PARAMETER, DEFAULTING TO THE REAL CONSTANT.**
     *
     * ── WHY THIS STOPPED BEING READ STRAIGHT OFF THE CONSTANT ─────────────────────────────────
     *
     * [DW_TIER1_RUNTIME_PRESENT] is now `true`, so the whole install branch below — six of this
     * function's eight refusals — became unreachable in one edit, and every test that covered it
     * became a test of nothing. Deleting those tests would have been the wrong move twice over: the
     * branch is live code that a build without the vendored AAR takes, and the refusals in it are the
     * ones a designer sees on a handset whose processor has no engine build.
     *
     * So the compile-time fact becomes an argument with the compile-time fact as its default.
     * Production passes nothing and gets today's world; `DwDeviceTierTest` passes `false` and can go
     * on pinning the world a build that does not bundle the engine would be in. **This is the same
     * move `catalogue1` and `artifacts` already make on this function**, for the same reason and with
     * the same rule: a caller that passes it is writing a test.
     */
    runtimeInApk: Boolean = DW_TIER1_RUNTIME_PRESENT,
): DwTierOffer {
    /*
     * TWO WAYS THE ENGINE COULD BE PRESENT, AND SINCE 2026-08-12 THE FIRST IS THE LIVE ONE. It was
     * the other way round: the constant was false by decision and the opt-in install carried the
     * feature. Then docs/DEVICE-TIER-MEASUREMENT.md found that `System.loadLibrary` cannot reach
     * `filesDir` under any arrangement, so an installed engine cannot be loaded at all and the AAR
     * had to be vendored into the package. The `||` survives unchanged because the second arm is
     * still the honest description of a build that does not bundle it.
     */
    val engineInstalled = runtimeInApk || dwAsrMayLoad(runtime)
    if (engineInstalled) {
        if (catalogue.isEmpty()) return DwTierOffer.None(DwTierRefusal.NO_MEASURED_MODEL)
        return dwBestPlan(catalogue, measurement, failures.filter { it.tier == DwAiTier.TIER_1 })
    }
    // The engine is not usable on this handset. WHY is the whole of the news, and the install offer
    // has already worked it out in the order of durability — this only translates its vocabulary into
    // the tier one, so the card above and the card below cannot come to different conclusions from
    // the same reading.
    return DwTierOffer.None(
        /*
         * `runtimeInApk` IS PASSED DOWN, AND LEAVING IT OUT WAS A BUG FOR ONE COMPILE. `dwAsrOffer`
         * defaults it to the real constant, so an unqualified call from inside this branch would have
         * answered BUNDLED_IN_THIS_BUILD — on the one path that is only ever reached when the engine
         * is NOT bundled. Every refusal below would have collapsed to a single wrong one. Two
         * functions with the same default are not the same as two functions with one answer.
         */
        when (dwAsrOffer(runtime, measurement, connection, artifacts, catalogue, runtimeInApk)) {
            // Nothing published, no digest pinned: there is no engine and none to be had. TODAY'S
            // ANSWER, on every handset in the fleet.
            DwAsrOffer.NOTHING_PUBLISHED_TO_INSTALL -> DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD

            // An engine exists for this phone and could be fetched — or is being fetched right now.
            // The next move is a control on the same screen, which is what this refusal names.
            DwAsrOffer.INSTALL, DwAsrOffer.RETRY, DwAsrOffer.NO_CONNECTION,
            DwAsrOffer.IN_PROGRESS -> DwTierRefusal.RUNTIME_NOT_INSTALLED

            // The engine would arrive without a voice, so the model is what is being waited for.
            DwAsrOffer.NO_MODEL_TO_FEED_IT -> DwTierRefusal.NO_MEASURED_MODEL

            // These four reuse the refusals this file already had, because they are the same news
            // whether the thing that will not fit is an engine or a model, and a second spelling of
            // "no build for your processor" would put two sentences behind one fact.
            DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR -> DwTierRefusal.ABI_NOT_BUILT_FOR
            DwAsrOffer.PROCESSOR_UNMEASURED -> DwTierRefusal.ABI_UNMEASURED
            DwAsrOffer.STORAGE_UNMEASURED -> DwTierRefusal.FREE_STORAGE_UNMEASURED
            DwAsrOffer.NOT_ENOUGH_STORAGE -> DwTierRefusal.NOT_ENOUGH_FREE_STORAGE

            // Unreachable from here — `dwAsrMayLoad` above is the same question and would have taken
            // the installed branch — but mapped honestly rather than with a `throw`, because the two
            // functions could be made to disagree by a future edit and the cost of that disagreement
            // must be a slightly conservative sentence rather than a crash on a settings screen.
            DwAsrOffer.ALREADY_INSTALLED -> DwTierRefusal.NO_MEASURED_MODEL

            // Unreachable for the same reason and more strongly: this arm is only entered when
            // `engineInstalled` was false, and `runtimeInApk` being true is the first thing that
            // makes it true. Mapped rather than thrown, because a crash on a settings screen is a
            // worse outcome for a disagreement between two functions than a conservative sentence.
            DwAsrOffer.BUNDLED_IN_THIS_BUILD -> DwTierRefusal.NO_MEASURED_MODEL

            DwAsrOffer.UNKNOWN -> DwTierRefusal.RUNTIME_UNMEASURED
        }
    )
}

/**
 * The whole answer for one handset at one moment. The only entry point a screen should need.
 *
 * [catalogue2], [catalogue1] and [artifacts] are parameters with the real, empty catalogues as
 * defaults purely so the desktop tests can exercise the arithmetic against openly invented rows.
 * Production code passes none of them; a caller that passes a hand-built catalogue is writing a test.
 *
 * [runtime] IS DIFFERENT AND A CALLER SHOULD PASS IT. It is a reading of this handset's own storage,
 * which only the Android half can take, and its default is the honest
 * [DwAsrRuntimeState.UNKNOWN] — "nobody has looked" — rather than "not installed". A screen that
 * leaves it out gets [DwTierRefusal.RUNTIME_UNMEASURED] the moment an artifact is published, which is
 * the correct thing to say about a question it never asked; it does not silently offer a designer a
 * second copy of an engine they already have.
 */
fun dwRecommendTiers(
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    failures: List<DwLoadFailureNote> = emptyList(),
    runtime: DwAsrRuntimeStatus = DwAsrRuntimeStatus(),
    catalogue2: List<DwModelPlan> = DW_TIER2_CATALOGUE,
    catalogue1: List<DwModelPlan> = DW_TIER1_CATALOGUE,
    artifacts: List<DwAsrArtifact> = DW_ASR_ARTIFACTS,
    /** See [dwTier1Offer]. Production passes nothing; a caller that passes it is writing a test. */
    runtimeInApk: Boolean = DW_TIER1_RUNTIME_PRESENT,
): DwTierRecommendation {
    val deviceClass = dwDeviceClass(measurement)
    val tier1 =
        dwTier1Offer(measurement, catalogue1, failures, runtime, connection, artifacts, runtimeInApk)
    val tier2 = dwTier2Offer(measurement, deviceClass, catalogue2, failures)
    return DwTierRecommendation(
        deviceClass = deviceClass,
        measurement = measurement,
        tier1 = tier1,
        tier2 = tier2,
        connection = connection,
        /*
         * THE SUGGESTION AND THE CHOICES ARE BUILT FROM ONE READING, IN ONE CALL, ON PURPOSE.
         *
         * A screen that took the offer from here and then computed the choices itself would be two
         * readings of one handset moments apart — the two-accounts-of-one-fact failure this
         * repository has already shipped once (`DwDictationRun`'s remembered refusal against a pack
         * that had since arrived). `availMem` moves 14–30 MB in ten seconds on an IDLE handset
         * (docs/DEVICE-TIER-MEASUREMENT.md), which is easily enough to put one model on either side
         * of a margin between two calls, and the card would then suggest a model its own list called
         * tight.
         */
        tier1Choices = dwModelChoices(catalogue1, measurement, failures, tier1, DwAiTier.TIER_1),
        tier2Choices = dwModelChoices(catalogue2, measurement, failures, tier2, DwAiTier.TIER_2),
    )
}

// ---------------------------------------------------------------------------------------------
// Nothing downloads by itself, and nothing is trusted for ever
// ---------------------------------------------------------------------------------------------

/**
 * Whether a control that spends a designer's data may be drawn for [offer]. **The only gate.**
 *
 * NOTHING IN THIS FEATURE AUTO-DOWNLOADS, EVER — the same rule [dwPackOffer] follows, for the same
 * reason: a multi-gigabyte fetch on a prepaid bundle in a district town is a bill, not a feature.
 * This function only ever decides whether a BUTTON EXISTS; the fetch itself is a designer's own tap
 * on it, with the size printed above it first.
 *
 * It returns false for every handset in existence today, because every [DwTierOffer] this app can
 * currently produce is a [DwTierOffer.None]. `DwDeviceTierTest` asserts exactly that, across every
 * device class and every connection, so a future catalogue entry cannot quietly turn a download
 * loose on the fleet without a test going red first.
 */
fun dwTierDownloadMayBeOffered(offer: DwTierOffer, connection: DwConnection): Boolean =
    offer is DwTierOffer.Available && connection != DwConnection.NONE

/**
 * How long a probe's numbers may be believed. Two minutes.
 *
 * RE-PROBE, DO NOT CACHE FOR EVER (plan §2.1). Free memory and free storage change while the
 * designer is standing there: an app was closed, a video was recorded, forty photographs were taken.
 * A recommendation made at install time is stale by the first workshop, and one made when the
 * settings screen opened is stale by the time a job is queued from it.
 *
 * TWO MINUTES IS A CHOSEN NUMBER. It is short enough that "free memory" still means something and
 * long enough that reading a settings card does not re-probe under the reader's hands.
 */
const val DW_PROBE_FRESH_FOR_MS: Long = 2L * 60L * 1000L

/**
 * Whether a reading taken at [takenAtElapsedMs] is too old to act on at [nowElapsedMs].
 *
 * BOTH ARGUMENTS ARE `SystemClock.elapsedRealtime()`, the monotonic clock. A negative age is
 * therefore impossible from correct callers and is treated as STALE rather than as fresh: it means
 * the two numbers came from different clocks, and a mixed-clock age is not an age.
 */
fun dwProbeIsStale(takenAtElapsedMs: Long, nowElapsedMs: Long): Boolean {
    val age = nowElapsedMs - takenAtElapsedMs
    return age < 0L || age > DW_PROBE_FRESH_FOR_MS
}

// ---------------------------------------------------------------------------------------------
// When a Tier 2 job may actually start
// ---------------------------------------------------------------------------------------------

/** Whether a queued on-device job may start at this moment, and if not, what it is waiting for. */
enum class DwTier2Window {
    /** There is no model to run. The state every handset is in today. */
    NOTHING_TO_RUN,

    /**
     * A recording or the camera is open. **THE RULE WHOSE VIOLATION LOSES DATA.**
     *
     * Plan §2.1: Tier 2 never runs concurrently with capture. A summariser that pushes the process
     * over the low-memory line mid-recording is a data-loss bug wearing a feature's clothes — the
     * artisan does not repeat the sentence, and the draft may go with the recording.
     */
    WAIT_CAPTURE_IS_OPEN,

    /** The phone already reports a temperature problem. See [DwThermalState.tooHotToStart]. */
    WAIT_DEVICE_IS_HOT,

    /** Nothing in the way. */
    RUN_NOW,
}

/**
 * Whether a Tier 2 job may start now. **THE ONLY FUNCTION ALLOWED TO SAY YES.**
 *
 * It has no caller yet, and that is not an oversight: the job queue is step 7 of the plan's sequence
 * and there is nothing to queue. The rule is written and pinned by a test NOW so that whoever builds
 * that queue finds the capture bar already standing rather than having to remember it.
 *
 * [capturing] IS NOT REACHED WHERE THERE IS NOTHING TO RUN. [NOTHING_TO_RUN][DwTier2Window.NOTHING_TO_RUN]
 * is returned first and the capture check never happens, which is the intended order rather than an
 * oversight: a screen that said "waiting for the recording to finish" about a model that does not
 * exist would be describing a queue nobody is in. The bar itself is still pinned by
 * `DwDeviceTierTest`, against an openly invented and otherwise-perfect offer — the only way to reach
 * it at all while both catalogues are empty.
 *
 * CHARGING IS NOT A BAR, deliberately. The plan prefers a job to run on the wall socket, and
 * [dwTier2PowerAdvice] says so in words — but a designer in a courtyard has no socket, and a
 * capability that only ever works at the guest house is not the offline capability this is for.
 */
fun dwTier2RunWindow(
    offer: DwTierOffer,
    capturing: Boolean,
    thermal: DwThermalState,
): DwTier2Window = when {
    offer !is DwTierOffer.Available -> DwTier2Window.NOTHING_TO_RUN
    capturing -> DwTier2Window.WAIT_CAPTURE_IS_OPEN
    thermal.tooHotToStart -> DwTier2Window.WAIT_DEVICE_IS_HOT
    else -> DwTier2Window.RUN_NOW
}

/**
 * The battery sentence, or null when there is nothing worth saying.
 *
 * NULL WHEN THE STATE IS UNMEASURED, not a hedged sentence. "This phone may or may not be charging"
 * is a line that costs a designer a moment and tells them nothing; an unread `BatteryManager` should
 * produce silence, not a claim. Null when charging, too — a phone on the socket needs no advice.
 */
fun dwTier2PowerAdvice(charging: Boolean?): String? =
    if (charging == false) {
        "This phone is on battery. A long job on the phone's own processor is better queued for " +
            "when it is on the charger — it will finish sooner and take less of the day's battery " +
            "with it."
    } else {
        null
    }

// ---------------------------------------------------------------------------------------------
// When the table turns out to be wrong about a handset
// ---------------------------------------------------------------------------------------------

/** Where the work goes after a load failed, and what to tell the designer changed. */
data class DwTierFallback(
    /**
     * The tier the job moves to, or null when there is nowhere for it to go right now — which is a
     * real outcome in a courtyard and must be said rather than silently dropped.
     */
    val goesTo: DwAiTier?,
    val sentence: String,
)

/**
 * A load failed on a handset the table said was fine. Fall back a tier, and SAY WHAT CHANGED.
 *
 * Plan §2.1's rule in full: record it, fall back a tier, and tell the designer what changed rather
 * than failing the job silently. The recording half is the caller's ([DwLoadFailureNote] is what it
 * keeps, and it belongs in docs/DEVICE-TIER-MEASUREMENT.md when it happens); this function decides
 * the other two.
 *
 * "FALL BACK A TIER" IS NOT ARITHMETIC ON THE TIER NUMBER, which is why [DwAiTier.number] is documented
 * as prose-only. Tier 1 is not a smaller Tier 2 — it is a different kind of model doing a different
 * job — and today neither exists, so a Tier 2 failure lands on Tier 3 when there is signal and
 * nowhere at all when there is not.
 */
fun dwFallbackAfterLoadFailure(
    note: DwLoadFailureNote,
    connection: DwConnection,
): DwTierFallback {
    /*
     * NAMED WITH ITS CAP WHERE IT HAS ONE, AND WITHOUT WHERE IT HAS NOT. Printing "at a null-token
     * context cap" is the sort of thing a designer reads once before they stop believing the screen,
     * and a CTC speech model genuinely has no cap to name — see [DwModelPlan.contextCapTokens].
     */
    val what = note.contextCapTokens
        ?.let { "${note.modelId} at a $it-token context cap" }
        ?: note.modelId
    /*
     * WHY THE OPENING CLAUSE HAS TWO FORMS, AND WHY THE SECOND IS NOT AN APOLOGY.
     *
     * "although its memory and storage said it would fit" is TRUE ONLY OF A MODEL THIS PHONE'S OWN
     * READING WAS COMFORTABLE WITH. Since a designer may now install a model the reading called
     * TIGHT ([DwModelChoice]), printing that clause after an override would have this app claim it
     * had promised something it explicitly warned against — and the designer who read
     * [dwModelOverrideSentence] before tapping would know it, which is how a screen stops being
     * believed about anything. The override arm names what actually happened instead, and it names
     * it without scolding: consent to a risk is not a mistake, and the outcome was disclosed.
     */
    val opening = if (note.chosenAgainstAdvice) {
        "This phone could not load $what. That is the outcome this app said to expect when you " +
            "chose it: it needed more room than this phone had to spare."
    } else {
        "This phone could not load $what, although its memory and storage said it would fit."
    }
    return when {
        // Tier 3 is the only tier that exists in this build, so every fallback that has anywhere to
        // go, goes there — and it needs signal.
        connection != DwConnection.NONE -> DwTierFallback(
            goesTo = DwAiTier.TIER_3,
            sentence = "$opening The job has gone to the server instead, so the result will be as " +
                "usual — it just needed the connection. The failure has been noted against this " +
                "handset and will not be retried on it.",
        )
        else -> DwTierFallback(
            goesTo = null,
            sentence = "$opening There is no connection to send the job to the server instead. " +
                "Nothing has been lost — the recording and the draft are saved on the phone, and " +
                "the job will run when there is signal.",
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The words. Android owns wording in this repository, and one fact must read the same everywhere
// ---------------------------------------------------------------------------------------------

/**
 * A byte count as a designer reads it, or the word "unmeasured".
 *
 * THE DIVISOR IS 1000, NOT 1024, SO THE LETTERS MEAN WHAT THEY SAY. An earlier draft of this
 * function divided by 1024 and still printed "GB" — a GiB wearing a GB's label — and justified it as
 * "reading low, which is the safe direction". IT IS THE SAFE DIRECTION FOR EXACTLY ONE OF THE THREE
 * FIGURES THIS FUNCTION PRINTS AND THE UNSAFE ONE FOR THE MOST CONSEQUENTIAL. The number that
 * matters most here is a model's on-disk size standing next to somebody's prepaid data bundle, and a
 * bundle is sold in decimal gigabytes: a 3,000,000,000-byte artifact printed as "2.8 GB" understates
 * what the download costs, which is the one direction a size beside a data allowance must not be
 * wrong in. Dividing by 1000 overstates it instead, and overstating a bill is survivable.
 *
 * NOBODY HAS COMPARED THIS AGAINST A HANDSET'S OWN SETTINGS SCREEN, and this comment does not claim
 * to know what one shows. The claim is only that G, M and k here mean what they say.
 *
 * NOTHING GATES ON THIS STRING. Every comparison in [dwPlanFits] is done in bytes; this function
 * exists so a sentence can be read aloud down a phone line, and changing its base cannot change
 * which handset is offered what.
 *
 * NULL IS THE WORD "unmeasured", NEVER "0 MB". A zero is a claim about the handset; the word is the
 * truth about the reading. This is the same rule `dwPackStateLabel` follows for
 * [DwPackState.UNKNOWN], and it is the rule this whole lane exists to keep.
 */
fun dwBytesLabel(bytes: Long?): String {
    if (bytes == null) return "unmeasured"
    val kb = 1000.0
    val mb = kb * 1000.0
    val gb = mb * 1000.0
    val magnitude = kotlin.math.abs(bytes.toDouble())
    return when {
        magnitude >= gb -> String.format(Locale.ROOT, "%.1f GB", bytes / gb)
        magnitude >= mb -> String.format(Locale.ROOT, "%.0f MB", bytes / mb)
        else -> String.format(Locale.ROOT, "%.0f kB", bytes / kb)
    }
}

/** The short name of a device class, for the end of a row. */
fun dwDeviceClassLabel(deviceClass: DwDeviceClass): String = when (deviceClass) {
    DwDeviceClass.UNMEASURED -> "Unmeasured"
    DwDeviceClass.LOW_RAM -> "Low-memory phone"
    DwDeviceClass.SMALL_4GB -> "4 GB class"
    DwDeviceClass.MID_6_TO_8GB -> "6–8 GB class"
    DwDeviceClass.LARGE_12GB_PLUS -> "12 GB and up"
}

/**
 * The numbers this handset reported, in a sentence, with the word "unmeasured" wherever it did not.
 *
 * This is the readout a designer can quote down a phone line when something goes wrong, which is why
 * it prints what was measured rather than only the conclusion drawn from it.
 */
fun dwDeviceReadoutSentence(measurement: DwDeviceMeasurement): String = buildString {
    append("This phone reports ")
    append(dwBytesLabel(measurement.totalRamBytes))
    append(" of memory in total, ")
    append(dwBytesLabel(measurement.availableRamBytes))
    append(" of it free at this moment, and ")
    append(dwBytesLabel(measurement.freeStorageBytes))
    append(" of free storage. ")
    append(
        when (measurement.lowRamDevice) {
            true -> "Android flags it as a low-memory device."
            false -> "Android does not flag it as a low-memory device."
            // Not "it is not flagged". A lookup that failed is not a handset that answered no.
            null -> "Whether Android flags it as a low-memory device could not be read."
        }
    )
    if (measurement.abis.isNotEmpty()) {
        append(" Its processor is ")
        append(measurement.abis.first())
        append(".")
    }
}

// ---------------------------------------------------------------------------------------------
// The three clauses every surface that names a model has to be able to say
// ---------------------------------------------------------------------------------------------

/**
 * **HOW MUCH SLOWER THAN REAL TIME, SAID WHEREVER THE MODEL IS NAMED — OR THE WORD "unmeasured".**
 *
 * ONE COPY, READ BY THE TIER SENTENCE, THE MODEL ROW AND THE DICTATION CONFIRMATION, because a
 * designer who reads "about as fast as you speak" on one card and waits three minutes on another has
 * been told two things by one app. Empty string when there is nothing to add, so it can be appended
 * unconditionally.
 *
 * IT IS BUILT FROM [DwModelRtfBand.slowest] AND NAMES THE FASTEST ONLY AS A FLOOR. That ordering is
 * the correction docs/DEVICE-TIER-MEASUREMENT.md wrote out at length: a lane that sizes a promise off
 * 1.078 tells a designer six minutes and makes them wait fifteen.
 */
fun dwModelSpeedClause(plan: DwModelPlan): String {
    val band = plan.realTimeFactor
        ?: return " How long it takes to transcribe a recording on this phone is UNMEASURED — " +
            "nobody has timed it, so this app will not guess at the wait."
    return " It is SLOWER THAN REAL TIME: across ${band.utterances} timed recordings on a " +
        "${band.measuredOn} it took between ${"%.1f".format(band.fastest)}× and " +
        "${"%.1f".format(band.slowest)}× the length of the audio to transcribe it. Plan for the " +
        "larger figure — a two-minute recording can take ${dwRoughMinutes(120_000L, band.slowest)} " +
        "— and it can only be worse on a warm phone with other apps open, which is unmeasured."
}

/**
 * The backgrounding clause, **with a third arm for the answer nobody measured.**
 *
 * `false` is the one that costs a designer the job and it keeps the sentence it had. `null` says the
 * word, because docs/DEVICE-TIER-MEASUREMENT.md is explicit that a process under instrumentation is
 * not a backgrounded app as the low-memory killer sees one — so the only honest thing to report is
 * what was actually observed and what it does not establish.
 */
fun dwModelBackgroundingClause(plan: DwModelPlan): String = when (plan.survivesBackgrounding) {
    true -> ""
    false ->
        " This model has been measured as NOT surviving the app being sent to the background: " +
            "taking a photograph while it runs would end the job. It runs only while nothing else " +
            "is open."
    null ->
        " Whether Android would close this app while the model is running, to give the memory to " +
            "something else, is UNMEASURED. What was tried: the model stayed loaded and transcribed " +
            "the same audio again after the app was sent to the background — but under a test " +
            "harness that holds the app up, which is not the same phone the low-memory killer sees. " +
            "Keep the screen on while it works, and if it does stop, the recording and the draft are " +
            "saved either way."
}

/**
 * **WHAT IT WAS SCORED AT, PER LANGUAGE, INCLUDING THE LANGUAGES IT IS NOT OFFERED FOR.**
 *
 * ── THE SECOND HALF IS THE WHOLE REASON THIS FUNCTION EXISTS ──────────────────────────────────
 *
 * A designer in Odisha reading a Hindi-only model row would reasonably conclude that Odia has never
 * been looked at. It has: it was pinned, run on this fleet's own handset, scored, and **rejected at
 * 53.3% WER**. That is a materially different thing to be told, and it is the difference between a
 * designer who stops looking and one who goes and finds a third-party model nobody has checked.
 *
 * So every scored language appears, and each says which side of the line it fell:
 * *"offered"* for one in [DwModelPlan.languages], *"measured and NOT offered"* for one that is not.
 * [labels] maps a tag to the name a designer reads; a tag with no label prints as itself, which is
 * ugly and truthful rather than absent.
 */
fun dwModelAccuracyClause(plan: DwModelPlan, labels: Map<String, String>): String {
    if (plan.accuracy.isEmpty()) {
        return " How accurately it transcribes ANY language is UNMEASURED — nobody has scored it, " +
            "so nothing here says it works."
    }
    val rows = plan.accuracy.joinToString(" ") { score ->
        val name = labels[score.tag] ?: labels.keys.firstOrNull { dwTagCovers(score.tag, it) }
            ?.let { labels[it] } ?: score.tag
        val verdict = if (plan.servesLanguage(score.tag)) {
            "offered"
        } else {
            "measured and NOT offered — this is below the bar, so this app does not claim it"
        }
        "$name: ${"%.1f".format(score.werPercent)}% of words wrong " +
            "(${"%.1f".format(score.cerPercent)}% of characters), over ${score.utterances} " +
            "recordings and ${score.referenceWords} words of ${score.corpus} — $verdict."
    }
    return " How well it was actually measured to hear each language, which is the number that " +
        "decides whether it is worth installing: $rows" +
        (plan.unmeasuredLanguagesNote?.let { " $it" } ?: "")
}

/**
 * A rough wait, in words a designer can plan around. **Rounded UP, always.**
 *
 * Minutes rather than seconds once it is over ninety seconds, because "3 minutes" is a thing somebody
 * can decide about and "187 seconds" is not. It rounds up for [dwBytesLabel]'s reason applied to time:
 * overstating a wait costs a designer nothing and understating one leaves them standing in a courtyard
 * wondering whether the app has frozen.
 */
internal fun dwRoughMinutes(audioMillis: Long, factor: Double): String {
    val millis = (audioMillis * factor).toLong().coerceAtLeast(0L)
    if (millis < 90_000L) {
        val seconds = ((millis + 999L) / 1000L).coerceAtLeast(1L)
        return "about $seconds seconds"
    }
    val minutes = (millis + 59_999L) / 60_000L
    return "about $minutes minutes"
}

/**
 * **WHAT THE DESIGNER IS ABOUT TO WAIT, SAID BEFORE THEY COMMIT TO IT. Null when it cannot be said.**
 *
 * The one sentence [DwDictationRung.APP_SPEECH_MODEL] must show before it starts decoding. It is not a
 * progress report and must never be mistaken for one: a CTC model produces no partial transcript, so
 * there is nothing to report progress against, and a bar that filled at a guessed rate would be a
 * fabricated measurement on the one screen where a designer is deciding whether to wait.
 *
 * Null when the model was never timed, and null is not "instant" — the caller says the word instead.
 */
fun dwModelWaitSentence(plan: DwModelPlan, audioMillis: Long): String? {
    val band = plan.realTimeFactor ?: return null
    if (audioMillis <= 0L) return null
    /*
     * 76 WORDS BECAME 24, AND THE TWO MEASURED NUMBERS ARE BOTH STILL IN IT.
     *
     * What went was the explanation of WHY there is no progress bar — a paragraph about CTC models
     * emitting the whole transcript at the end. That is the app describing its own internals; the
     * designer's experience of it is "there is nothing to watch", which is four words and is kept.
     * What could NOT go is the band itself: this is the one screen in the app where a person decides
     * whether to keep waiting, and the range is the only thing that lets them.
     */
    return "Nothing leaves this phone. Expect ${dwRoughMinutes(audioMillis, band.fastest)} to " +
        "${dwRoughMinutes(audioMillis, band.slowest)} — slower than the audio plays, with nothing " +
        "to watch until it finishes."
}

/**
 * The full sentence for one tier's verdict on this handset. **THE PLACE THE REFUSALS ARE SAID.**
 *
 * Plan §2.1: a device that cannot run a tier says so IN WORDS, ONCE — not a greyed-out control with
 * no explanation, and not silence. Each refusal names what would change it, because a "no" that
 * cannot be acted on teaches a designer to stop reading the screen.
 */
fun dwTierOfferSentence(tier: DwAiTier, offer: DwTierOffer): String = when (offer) {
    is DwTierOffer.Available -> buildString {
        append("Tier ${tier.number} would run ")
        append(offer.plan.modelId)
        append(" (")
        append(offer.plan.quantisation)
        append(") ")
        append(tier.where)
        append(". ")
        // WHAT WAS ACTUALLY RUN, IN THE MODEL'S OWN UNITS. This used to print "at an N-token context
        // cap" unconditionally, which is a sentence about a decoder and is false of the CTC speech
        // model that is now the first row in this app — see [DwModelPlan.runBound].
        append("One run is: ")
        append(offer.plan.runBound)
        append(" It is ")
        append(dwBytesLabel(offer.plan.onDiskBytes))
        append(" on the phone and needs ")
        append(dwBytesLabel(offer.plan.peakRssBytes))
        append(" of memory while it runs, measured on a ")
        append(offer.plan.measuredOn)
        append(" — this phone would have ")
        append(dwBytesLabel(offer.headroomBytes))
        append(" to spare. Nothing is fetched unless you ask for it.")
        append(dwModelSpeedClause(offer.plan))
        append(dwModelBackgroundingClause(offer.plan))
    }

    is DwTierOffer.None -> dwTierRefusalSentence(tier, offer.refusal)
}

/** One refusal, said in full. Every arm names the next move, or names why there is not one. */
fun dwTierRefusalSentence(tier: DwAiTier, refusal: DwTierRefusal): String = when (refusal) {
    /*
     * ── THE CITATION CAME OFF ALL SIX OF THESE ARMS, 2026-08-13. ────────────────────────────────
     *
     * Both of these sentences, and the LOAD_FAILED arm below, ended by naming a file in this
     * repository — `docs/ASR-RUNTIME-MEASUREMENT.md` and `docs/DEVICE-TIER-MEASUREMENT.md`. That was
     * live on the fleet's own handset: read off the view hierarchy of Settings → Speech & AI at 02:57
     * on 2026-08-13, inside a 96-word paragraph, on a card with no controls on it.
     *
     * A REPOSITORY PATH IS THE ONE THING ON A SCREEN THAT IS PROVABLY USELESS TO THE PERSON HOLDING
     * THE PHONE. They cannot open it, cannot act on it, and its only effect is to tell them the app is
     * addressing its own authors rather than them. `DwSpeechCardProseTest` now asserts across the
     * whole `DwAiTier` × `DwTierRefusal` grid that no arm does this again — it caught all six.
     *
     * WHAT DELIBERATELY SURVIVES, because the standing rule is that a refusal stays VISIBLE and short:
     * each arm still says there is nothing to install and, where there is one, names the next move.
     * What went with the citation is the argument FOR the refusal — which model was going to be
     * exported, what a document records, which measurement is outstanding, and how well something
     * transcribes "an Odia courtyard". The last of those is also the special pleading the owner has
     * asked three times to be rid of: no language is singled out for explanation on a settings card.
     *
     * The two arms still differ, and that difference is still the point — Tier 1's missing artifact is
     * a speech model and Tier 2's is a language model — so they are still two sentences, not one.
     */
    DwTierRefusal.NO_MEASURED_MODEL -> when (tier) {
        DwAiTier.TIER_1 ->
            "No speech model has been measured for this app's own engine yet, so there is nothing " +
                "to install here."

        DwAiTier.TIER_2, DwAiTier.TIER_3 ->
            "No Tier ${tier.number} model has been measured yet, so there is nothing to install here."
    }

    /*
     * TIER 1 GETS ITS OWN SENTENCE, BECAUSE THE GENERAL ONE IS FALSE ON THE SCREEN IT IS PRINTED ON.
     * This card sits directly beneath "Offline dictation languages", which offers Android's own
     * on-device speech packs and says of an installed one that "dictation in it works with no
     * signal". A designer who then read "there is no engine in this build that could run a model on
     * this phone" would conclude that one of the two cards was lying to them, and the reasonable
     * response to that is to stop trusting a control that WORKS. Tier 1 is THIS APP'S OWN speech
     * engine — sherpa-onnx + IndicConformer, step 4 of the plan's sequence — which is a different
     * thing from the platform's recogniser, and the sentence has to say which of the two it means.
     *
     * The two and the seventeen are MEASURED, not estimated: docs/DICTATION-LANGUAGE-PACK-
     * MEASUREMENT.md is raw logcat off the fleet's own M32, where `checkRecognitionSupport` returned
     * thirty languages of which exactly two — `hi-IN` and `en-IN` — are ours. It is named as a
     * reading from one handset rather than as a property of Android, because that is what it is.
     *
     * "THE OFFLINE DICTATION ABOVE" IS A CONSTRAINT ON THE LAYOUT, NOT A TURN OF PHRASE. It is true
     * of the only surface that renders this today — the tier card sits immediately below the
     * language-pack card in `ui/AppearanceScreen.kt`. Move one of the two cards and this word has to
     * move with it, or the sentence points at nothing.
     */
    DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD -> when (tier) {
        /*
         * AMENDED 2026-08-12, AND THE AMENDMENT IS THE POINT OF THE WHOLE LANE. This sentence used to
         * open with "that is work that has not been built, not a control missing from this screen" —
         * which was true when there was no install path at all, and became FALSE the moment a card
         * offering the engine appeared directly above it. A designer reading "no control is missing
         * from this screen" two centimetres below a card headed "Offline speech engine" would conclude
         * the app does not know what it is showing them. So the sentence now names the card, says why
         * the card cannot do anything today, and keeps the clause that was always the important one:
         * this is OUR engine, not the offline dictation above, and that dictation goes on working.
         */
        DwAiTier.TIER_1 ->
            "This app has no speech engine of its own on this phone, and there is none published for " +
                "it to fetch — the card above is where it would be installed, and it is disabled for " +
                "that reason rather than switched off. Publishing one, and measuring a speech model " +
                "to go with it, is work that has not been built. It is a different thing from the " +
                "offline " +
                "dictation above, which is Android's own: on the handset this was measured on, " +
                "Android's packs cover two of our nineteen languages, and dictation in those works " +
                "with no signal once the pack is on the phone. The other seventeen — Odia among " +
                "them — need a connection, and go to the server, which is where the craft " +
                "vocabulary lives."

        DwAiTier.TIER_2, DwAiTier.TIER_3 ->
            "Tier ${tier.number} is not in this app yet. There is no engine in this build that " +
                "could run a model ${tier.where}, so this is not a control that is missing from " +
                "this screen — it is work that has not been built. Recording, transcription and " +
                "the rest carry on through the server whenever there is signal."
    }

    /*
     * THE ONE REFUSAL WHOSE NEXT MOVE IS A CONTROL ON THE SAME SCREEN, so it is the one that must not
     * read as a dead end. It also has to be true while a download is in flight — the card above shows
     * the progress and this sentence sits under it, so "not on this phone yet" is worded to cover both
     * "you have not asked" and "it is coming".
     *
     * The Tier 2 arm exists because [dwTierRefusalSentence] is total over the enum and every sentence
     * has to be a true one. It is not reachable: [dwTier2Offer] never returns this value, because
     * Tier 2's runtime would be part of the app rather than a download.
     */
    DwTierRefusal.RUNTIME_NOT_INSTALLED -> when (tier) {
        DwAiTier.TIER_1 ->
            "This app's own speech engine is not on this phone yet. It is not built into the app — it " +
                "is an optional download, offered on the card above, because it is tens of megabytes " +
                "and most designers never work anywhere without signal. Until it is installed, " +
                "dictation uses the phone's own packs where it has them and the server where it does " +
                "not, exactly as it does today. Nothing is fetched unless you ask for it."

        DwAiTier.TIER_2, DwAiTier.TIER_3 ->
            "Tier ${tier.number} has no engine that can be installed separately, so it is not " +
                "waiting on a download you could choose: its runtime would be part of the app, and " +
                "there is none in this build. This work is done on the server whenever there is " +
                "signal. If this sentence is on your screen, that is a fault in this app rather than " +
                "a fact about your phone — it is worth reporting."
    }

    /*
     * WE COULD NOT READ OUR OWN FILES. Rendered rather than swallowed, because the alternative — a
     * card that quietly says "not installed" — offers a 24 MB download to a designer who may already
     * have the engine, and this app does not spend somebody's data on a guess.
     */
    DwTierRefusal.RUNTIME_UNMEASURED -> when (tier) {
        DwAiTier.TIER_1 ->
            "This app could not look at its own files to see whether its speech engine is installed, " +
                "so it will not claim either way — saying “not installed” could offer you a download " +
                "you have already paid for once. Tap “Check again”. Dictation is unaffected: it goes " +
                "on using the phone's own packs and the server exactly as before."

        DwAiTier.TIER_2, DwAiTier.TIER_3 ->
            "Whether an engine is installed is not a question Tier ${tier.number} has — its runtime " +
                "would ship inside the app rather than being fetched, so there is nothing on this " +
                "phone to go looking for. This work is done on the server whenever there is signal. " +
                "Reaching this sentence would be a fault in this app; it is worth reporting."
    }

    DwTierRefusal.DEVICE_TOO_SMALL ->
        "This phone does not have the memory to run a model ${tier.where}, and no setting changes " +
            "that. That is not a fault: this app was built for handsets like this one, and " +
            "everything it does goes through the server whenever there is signal, exactly as it " +
            "always has."

    DwTierRefusal.NOT_ENOUGH_FREE_RAM_NOW ->
        "This phone has the memory for Tier ${tier.number} but not enough of it free right now. " +
            "Close the apps you are not using and tap “Check again”; the reading below is taken " +
            "fresh each time this screen is opened."

    DwTierRefusal.NOT_ENOUGH_FREE_STORAGE ->
        "There is not enough free storage on this phone for a Tier ${tier.number} model, with room " +
            "left over for a day of photographs and recordings. Free some space and check again — " +
            "the size is stated above the download button before anything is fetched."

    DwTierRefusal.ABI_NOT_BUILT_FOR ->
        "There is no build of the Tier ${tier.number} engine for this phone's processor. Nothing " +
            "on this screen can change that, and the server does this work whenever there is signal."

    DwTierRefusal.FREE_RAM_UNMEASURED ->
        "This phone would not say how much memory is free, so whether a Tier ${tier.number} model " +
            "would fit is unknown — and a model started on a guess is one the phone can end " +
            "halfway through, taking the job with it. Tap “Check again”; if it keeps saying this, " +
            "the work stays on the server."

    DwTierRefusal.FREE_STORAGE_UNMEASURED ->
        "This phone would not say how much storage is free, so whether a Tier ${tier.number} model " +
            "would fit on it is unknown, and nothing that size will be fetched on a guess. Tap " +
            "“Check again”."

    DwTierRefusal.ABI_UNMEASURED ->
        "This phone would not say what kind of processor it has, so which build of the Tier " +
            "${tier.number} engine it would need is unknown. Tap “Check again”; if it keeps saying " +
            "this, the work stays on the server."

    /*
     * The citation came off here too — see the note on NO_MEASURED_MODEL above. "That failure is worth
     * reporting" survives, because it IS the designer's next move and the only one they have; where to
     * report it is a thing this app should already know, not a filename to hand them.
     */
    DwTierRefusal.LOAD_FAILED_HERE_BEFORE ->
        "A Tier ${tier.number} model was tried on this phone and would not load, although its " +
            "memory and storage said it would fit. It will not be tried again on this handset, and " +
            "the failure is worth reporting."
}

/** What Tier 3 — the server chain that has always run — can do at this moment. */
fun dwTier3Sentence(connection: DwConnection): String = when (connection) {
    DwConnection.NONE ->
        "There is no connection now, so the server cannot be reached. Recordings, photographs and " +
            "answers are saved on the phone and go up when there is signal; nothing is lost by " +
            "working through a courtyard afternoon with no bars."
    DwConnection.METERED ->
        "This phone is on mobile data. Transcription and the rest run on the server as they always " +
            "have — that is where the craft vocabulary lives, which is why a server transcript " +
            "writes “dabu” where a general engine writes “double”."
    DwConnection.UNMETERED ->
        "This phone is on Wi-Fi. Transcription and the rest run on the server as they always have " +
            "— that is where the craft vocabulary lives, which is why a server transcript writes " +
            "“dabu” where a general engine writes “double”."
}

// ---------------------------------------------------------------------------------------------
// TWO CONSTANTS STOOD HERE AND BOTH ARE DELETED, 2026-08-13, for the same reason and by the same
// rule as `DW_PACK_OFFER_BLURB` and `DW_PACK_NO_CONNECTION_SENTENCE` in `DwLanguagePacks.kt`:
// NEITHER HAD A CALLER. `SpeechAndAiScreen`'s own docstring already listed both as "what came off
// this card"; the code was left behind, which is how a paragraph gets re-hung by the next reader who
// finds a named constant that sounds like it belongs on a card.
//
// `DW_TIER_CARD_BLURB` — 51 words explaining what a tier IS: *"Some of this app's AI work could one
// day run on the phone itself instead of on a server, which would make it work with no signal…"*.
// A designer does not choose a tier, they read a verdict; and the clause about a server is the
// standing network announcement principle 2 forbids.
//
// `DW_TIER_REPROBE_SENTENCE` — 39 words explaining that the reading is re-taken when the screen is
// opened. The "Check again" button says that, and `DW_TIER_STALE_SENTENCE` below says the only part
// a designer has to act on — that what they are looking at has aged.
//
// DO NOT REINTRODUCE EITHER AS A STANDING PARAGRAPH ON A CARD.
// ---------------------------------------------------------------------------------------------

/** Said when the reading on screen has gone stale under a designer who left the screen open. */
const val DW_TIER_STALE_SENTENCE: String =
    "This reading was taken a while ago and free memory will have moved since. Tap “Check again” " +
        "for what this phone has now."
