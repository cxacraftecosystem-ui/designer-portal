package com.designprototype.workshop.data

/**
 * **THE CAPTURE GATE** — which photographs are allowed into a workshop at all, and the words for the
 * ones that are not.
 *
 * The Kotlin twin of `frontend/components/media/photoGate.ts`, and the handset half of the owner's
 * instruction of 2026-08-27: a shaky or poor-quality photograph must not reach the server.
 *
 * ── WHY THIS IS A REFUSAL WHERE [DwImageQuality] IS ADVICE, AND EXACTLY WHERE THE LINE IS ─────
 *
 * ImageQuality.kt's header says, in as many words, that "a finding is advice, never a refusal" and
 * that "the surfacing must keep it that way". This module is the surfacing and it does NOT keep it
 * that way, so the disagreement is written down here rather than left for a reader to discover in a
 * diff. That file's argument was made under a different decision and it survives intact for
 * everything outside the narrow list below; what changed is one instruction from the owner, and an
 * instruction is theirs to give.
 *
 * What is NOT theirs to give is the CLAIM. A gate may only refuse on something this product actually
 * measures, and only where the designer can comply. So:
 *
 *  * REFUSED — the photograph is never imported, so no descriptor is written, no id is issued,
 *    nothing enters the draft and nothing is ever uploaded:
 *      - BLUR, variance of the Laplacian below [DwImageQuality.BLUR_VARIANCE_FLOOR]. "Shaky", the
 *        owner's own first word, and the fault this gate exists for.
 *      - LOW_RESOLUTION, long edge below [DwImageQuality.MIN_LONG_EDGE_PX]. The other honest reading
 *        of "poor quality", and unarguable: the photograph provably cannot fill a report plate at
 *        the resolution this app rasterises its own figures at.
 *      - DUPLICATE, but ONLY the exact one — the same SHA-256 as a file already attached here.
 *
 *  * WARNED AND NEVER REFUSED — admitted, with [DwPhotoQualityAdvisories] saying its piece
 *    afterwards exactly as it did before this gate existed:
 *      - DUPLICATE by perceptual hash. See [NEAR_DUPLICATE_IS_NEVER_REFUSED].
 *
 *  * NOT MEASURED, AND THEREFORE NOT CLAIMED ANYWHERE ON SCREEN — OVEREXPOSED, UNDEREXPOSED and
 *    WRONG_SUBJECT are tokens in stage 21's `MEDIA_QUALITY_FLAG` enum that NO code in this product
 *    computes. There is no luma histogram on this handset and this module must not imply there is
 *    one. [scopeSentence] names the gap outright rather than merely omitting it.
 *
 * ── IT FAILS OPEN BY CONSTRUCTION, NOT BY A BRANCH ────────────────────────────────────────────
 *
 * [judge] takes a MEASUREMENT. A file this device will not decode produces none — a HEIC the
 * platform refuses, a truncated JPEG, an OutOfMemoryError on a phone already at the edge — and the
 * caller therefore never reaches this function and the photograph is admitted. That is the required
 * direction: an image the detector cannot read is not a bad photograph, and refusing it would make
 * both motif galleries unfillable on a handset whose decoder differs. There is no arm below that
 * turns an absence of evidence into a refusal and there must never be one.
 *
 * The same rule one level down: [DwImageQuality.isBlurred] answers false whenever contrast is under
 * [DwImageQuality.MIN_CONTRAST_STDDEV], because variance of the Laplacian stops discriminating on a
 * flat subject. That guard is the only thing standing between this gate and refusing a perfectly
 * sharp photograph of undyed cotton, a smooth metal tool or a plain-dyed cloth — subjects that make
 * up much of what these clusters produce, and which ImageQuality.kt records measuring 58.98, BELOW
 * the blur floor, at a contrast of 9.03. It was carrying a warning; it is now carrying a refusal.
 * Anyone raising [DwImageQuality.MIN_CONTRAST_STDDEV], or moving
 * [DwImageQuality.BLUR_VARIANCE_FLOOR] toward the middle of its calibration gap, is deciding how
 * often a correct photograph of a flat motif is turned away.
 *
 * ── WHERE IT APPLIES: EVERY PHOTOGRAPH A PERSON CHOOSES, NOT ONLY THE TWO MOTIF GALLERIES ─────
 *
 * The owner's reason was that a poor photograph "would just go into reports", which is true of every
 * image field in the registry and not only of the two the same instruction gave a count of
 * twenty-five. So the gate runs wherever a designer photographs or picks an image, and the FLOOR —
 * the count and the bar — is drawn only where the registry declares a `minItems`. Two features out
 * of one sentence, with deliberately different scopes.
 *
 * THE SCOPING IS BY MEASURABILITY AND NOT BY FIELD TYPE, which is what keeps it honest without a
 * list to maintain: audio, video and a PDF produce no [ImageMeasurement] at all, so they are
 * admitted by the same construction that admits an undecodable photograph. A file this app DERIVED
 * rather than a person chose — a rectified sketch, a traced line-art export — attaches through its
 * own panel and never passes here; gating one would refuse an export for being too small to print,
 * over a control whose entire job is to produce it.
 *
 * ── PURE: NO ANDROID, NO COMPOSE, NO NETWORK ──────────────────────────────────────────────────
 *
 * Same split, and for the same reason, as [DwImageQuality] / [DwImageDecode] and [DwPhotoIntake]:
 * there is no Robolectric in this module, so a judgement written inside a composable is by
 * construction code no unit test can reach. `DwPhotoGateTest` drives every sentence below by value.
 */
object DwPhotoGate {

    // ── The faults, and which of them close the door ─────────────────────────────────────────────

    /**
     * Finer than [QualityFlag], for exactly one reason: DUPLICATE covers two situations that must be
     * treated differently (see [NEAR_DUPLICATE_IS_NEVER_REFUSED]) and the archive has only the one
     * token for both.
     */
    enum class GateFaultKind { BLUR, LOW_RESOLUTION, EXACT_DUPLICATE, NEAR_DUPLICATE }

    /** One reason a photograph was turned away, or flagged. */
    data class GateFault(
        val kind: GateFaultKind,
        /**
         * Stage 21's `MEDIA_QUALITY_FLAG` token, verbatim, so the sentence a designer reads in the
         * courtyard and the row the archive stores name one problem with one word.
         */
        val flag: QualityFlag,
        val severity: QualitySeverity,
        /**
         * The sentence put in front of the designer, and it ALWAYS carries the reading and the floor
         * it was measured against.
         *
         * "This photograph is blurred" is indistinguishable from the app being wrong, and a gate a
         * designer believes is wrong is a gate they route around — by pressing the shutter until one
         * gets through, which fills the gallery with exactly what the gate exists to keep out. "The
         * sharpness reading was 42 against a floor of 60" can be argued with, checked and acted on.
         */
        val message: String,
    )

    /** Whether this fault stops the import, or only annotates it. Nothing else may decide this. */
    fun faultRefuses(fault: GateFault): Boolean = fault.kind != GateFaultKind.NEAR_DUPLICATE

    /**
     * WHY A PERCEPTUAL-HASH MATCH IS A WARNING AND AN EXACT ONE IS A REFUSAL.
     *
     * A refusal is defensible only where the designer can comply and where complying costs nothing.
     *
     * EXACT — an identical SHA-256 with a file already attached to this same field. Refusing costs
     * the designer literally nothing: the bytes are already here, under a name the message prints,
     * and there is no information anywhere in the second copy. On this client it also saves the
     * six megabytes [WorkshopDraftStore.importMedia] would otherwise spend copying them.
     *
     * NEAR — within [DwImageQuality.NEAR_DUPLICATE_MAX_DISTANCE] bits of another shot.
     * ImageQuality.kt's own calibration says "two exposures of one object seconds apart land in the
     * low single digits", which is INSIDE the threshold — and two exposures of one object seconds
     * apart is precisely how a designer photographs twenty-five motifs on one length of cloth.
     * Refusing there would turn away correct, wanted, irreplaceable photographs, and it would do it
     * most often to the designer working fastest.
     *
     * This constant exists to hold the argument; [faultRefuses] is what the code calls.
     */
    const val NEAR_DUPLICATE_IS_NEVER_REFUSED = true

    data class GateVerdict(
        /** False only when at least one fault [faultRefuses]. */
        val admitted: Boolean,
        /** Everything found, refusing and not. Empty for a photograph with nothing wrong with it. */
        val faults: List<GateFault>,
    )

    /**
     * Judge one photograph from its measurement alone.
     *
     * CALLED ONLY WITH A MEASUREMENT — see the header on failing open. A caller holding null from
     * [DwImageDecode.screen] must admit the file without reaching here.
     *
     * The blur and resolution decisions delegate to [DwImageQuality]'s own predicates rather than
     * re-testing the constants, so this module cannot come to disagree with the module the floors
     * are calibrated in, and cannot drift from `frontend/lib/imageQuality.ts`, of which it is the
     * calibrated twin. The numbers are read for PRINTING only.
     *
     * @param checksum this file's own SHA-256 where it could be computed. Absent is "unknown",
     *   NEVER "unique" — a file with no hash produces no duplicate claim in either direction.
     * @param attached the photographs this field already holds, plus any admitted earlier in the
     *   same pass. In attachment order, so a second copy is refused and the first is never accused.
     */
    fun judge(
        measurement: ImageMeasurement,
        checksum: String? = null,
        attached: List<AttachedImage> = emptyList(),
    ): GateVerdict {
        val faults = mutableListOf<GateFault>()

        if (DwImageQuality.isBlurred(measurement)) {
            faults += GateFault(
                kind = GateFaultKind.BLUR,
                flag = QualityFlag.BLUR,
                severity = QualitySeverity.MEDIUM,
                // `.toInt()` on the floor rather than interpolating the Double: it is declared 60.0
                // here and 60 in the TypeScript, and "a floor of 60.0" against the web's "a floor of
                // 60" is two clients quoting one calibration differently. `jsRound` on the reading
                // for the same reason — the web prints `Math.round`, whose tie rule is not Kotlin's.
                message = "the sharpness reading was ${DwImageQuality.jsRound(measurement.blurScore)} " +
                    "against a floor of ${DwImageQuality.BLUR_VARIANCE_FLOOR.toInt()}, so it is out " +
                    "of focus or the camera moved. Hold still, tap the subject to focus, and take it " +
                    "again.",
            )
        }

        if (DwImageQuality.isUnderResolution(measurement.width, measurement.height)) {
            faults += GateFault(
                kind = GateFaultKind.LOW_RESOLUTION,
                flag = QualityFlag.LOW_RESOLUTION,
                severity = QualitySeverity.MEDIUM,
                message = "it is ${measurement.width}x${measurement.height}, and a report plate needs " +
                    "about ${DwImageQuality.MIN_LONG_EDGE_PX}px on the long edge. Raise the camera's " +
                    "resolution, or send the original rather than a copy something has already shrunk.",
            )
        }

        // Exact first and exclusively: where the bytes are identical there is nothing a perceptual
        // hash can add, and reporting a duplicate twice about one file reads as two separate
        // problems. Same ordering, for the same reason, as [DwImageQuality.findQualityIssues].
        val identical = if (checksum.isNullOrBlank()) {
            emptyList()
        } else {
            attached.filter { !it.checksum.isNullOrBlank() && it.checksum == checksum }
        }
        if (identical.isNotEmpty()) {
            faults += GateFault(
                kind = GateFaultKind.EXACT_DUPLICATE,
                flag = QualityFlag.DUPLICATE,
                severity = QualitySeverity.LOW,
                message = "the identical file is already attached here as " +
                    identical.joinToString(", ") { "\"${it.label}\"" } +
                    ". Nothing is lost by leaving this copy out.",
            )
        } else {
            /*
             * THE NEAR-DUPLICATE ARM, WHICH IS THE ONLY REASON THE WRITE PATH BELOW EXISTS — and
             * `frontend/components/media/photoGate.ts` DOES NOT HAVE IT. That is a defect there
             * rather than a difference to preserve, and it is worth stating plainly because the
             * whole argument for keeping this fault kind is written in three places on both clients.
             *
             * `gatePhotograph` there has three arms — blur, resolution, exact duplicate — and every
             * one of them REFUSES. So its verdict's `faults` list is empty for every photograph it
             * admits, `CapturedFinding` is therefore never produced, `qualityFlagLog` is written
             * with nothing, and `mediaQualityFlagRows` — whose own comment says the practical effect
             * of the gate is that "this table stops recording faults and starts recording the one
             * fault worth recording" — can never be handed that fault. The path is documented,
             * tested in isolation and dead end to end.
             *
             * IT COSTS NOTHING TO GET RIGHT HERE, because the comparison is already in hand: the
             * perceptual hash was computed by the same measurement the blur score came from, and the
             * photographs to compare against are the ones the exact-duplicate check just walked.
             *
             * AND IT IS ADMITTED, WHICH IS THE POINT. See [NEAR_DUPLICATE_IS_NEVER_REFUSED]. What
             * this produces is a fault on a photograph that WAS imported, which is precisely the
             * shape stage 21's archive table wants: a flag against a file that exists.
             *
             * EXCLUSIVE WITH THE EXACT CHECK, in the `else` and not beside it, exactly as
             * [DwImageQuality.findQualityIssues] orders the same pair: where the bytes are identical
             * there is nothing a perceptual hash can add, and reporting a duplicate twice about one
             * file reads as two separate problems.
             */
            val near = attached
                .map { item ->
                    item to if (!item.perceptualHash.isNullOrBlank() && measurement.perceptualHash.isNotBlank()) {
                        DwImageQuality.hammingDistance(measurement.perceptualHash, item.perceptualHash)
                    } else {
                        // A photograph nothing has measured offers no hash, and a null hash is
                        // "unknown" rather than "unlike everything" — so it is excluded by a distance
                        // no threshold can reach rather than by a claim it is different.
                        Int.MAX_VALUE
                    }
                }
                .filter { (_, distance) -> distance <= DwImageQuality.NEAR_DUPLICATE_MAX_DISTANCE }
                .sortedBy { (_, distance) -> distance }
            if (near.isNotEmpty()) {
                val closest = near.first().second
                faults += GateFault(
                    kind = GateFaultKind.NEAR_DUPLICATE,
                    flag = QualityFlag.DUPLICATE,
                    severity = QualitySeverity.LOW,
                    // The reading and the threshold, like every other sentence here, because this
                    // one ends up in an archive row an officer reads: "looks like the same shot" is
                    // an opinion, "1 bit different out of 64, against a threshold of 6" is evidence.
                    message = "this looks like the same shot as " +
                        near.joinToString(", ") { (item, _) -> "\"${item.label}\"" } +
                        " — $closest bit${if (closest == 1) "" else "s"} different out of 64, against " +
                        "a threshold of ${DwImageQuality.NEAR_DUPLICATE_MAX_DISTANCE}. Two views of " +
                        "one object are worth keeping; two copies of one view are not.",
                )
            }
        }

        return GateVerdict(admitted = faults.none(::faultRefuses), faults = faults)
    }

    // ── What it checks, and what it does not, on screen ──────────────────────────────────────────

    /**
     * WHAT THIS GATE CHECKS AND WHAT IT DOES NOT, IN ONE SENTENCE, WHERE A DESIGNER FORMS THE BELIEF.
     *
     * Both motif galleries' registry help already ends with the second half of this and stage 21's
     * note carries it too. It is repeated at the point of capture because a gate that silently
     * admits a badly exposed photograph, on a screen that has just refused two others for being
     * soft, teaches "the app checks my photographs" and nothing narrower. The three unmeasured
     * tokens are named rather than merely omitted.
     *
     * NO NUMBERS IN IT. The floors are client constants that move with a re-calibration, and a fixed
     * sentence quoting one goes stale silently; every refusal prints its own reading and its own
     * floor, which is the only place a number belongs. `DwPhotoGateTest` asserts this sentence
     * contains no digits.
     */
    fun scopeSentence(): String =
        "Each photograph is checked on this device before it uploads — for focus, for resolution, " +
            "and for being a file that is already attached here. Exposure and subject are not " +
            "checked; judge those by eye."

    /**
     * The heading over a refusal: how many were turned away, and what became of them.
     *
     * ── ANDROID'S WORDING, AND IT IS A CORRECTION THE WEB SHOULD COPY ────────────────────────
     *
     * `photoGate.ts` opens this with "3 photographs were not uploaded", which is true on both
     * clients and useless on this one: on the handset NOTHING has uploaded yet — the draft is the
     * document for a fortnight — so "not uploaded" describes the twenty photographs that WERE
     * accepted just as accurately as the three that were not. What separates them on this screen is
     * that no row appeared. Hence "not attached", with "nothing was sent" kept because it is the
     * half the owner's instruction is about, and both clauses are true in the browser too.
     */
    fun refusalHeading(count: Int): String {
        if (count <= 0) return ""
        val one = count == 1
        return "$count photograph${if (one) "" else "s"} ${if (one) "was" else "were"} not attached, " +
            "so nothing was sent. Take ${if (one) "it" else "them"} again and attach " +
            "${if (one) "it" else "them"}."
    }

    /** One refused photograph, as the notice names it. */
    data class RefusedPhoto(val displayName: String, val faults: List<GateFault>)

    /**
     * ONE LINE PER REFUSED FILE — its name, then its own reasons.
     *
     * NAMES EVERY FILE AND ITS OWN REASON rather than counting, because a designer who photographed
     * twenty-five motifs and had four refused needs to know WHICH four and WHY each, and a count
     * tells them neither. This is the same duty `dwCapNotice` discharges one door later for what a
     * ceiling turned away — and, unlike that one, this notice CAN name files, because the gate holds
     * a display name for every candidate before anything is imported.
     *
     * A LIST OF LINES AND NOT ONE PARAGRAPH, which is where this diverges from `photoGate.ts`'s
     * `gateRefusalSentence`, deliberately and by platform. The web joins every clause into a single
     * sentence; on a 360dp screen at 11sp that is a wall of text in an error colour, and the file
     * names — the one thing a designer has to pick out of it — are buried mid-paragraph. Same
     * sentences, one per line. The web can take the same shape; nothing about the strings changes.
     *
     * DERIVED FROM THE LIST WHEN IT IS DRAWN, never frozen when it happened, so a re-import cannot
     * leave a stale receipt on screen.
     */
    fun refusalLines(refused: List<RefusedPhoto>): List<String> = refused.mapNotNull { entry ->
        val reasons = entry.faults.filter(::faultRefuses).joinToString(" And ") { it.message }
        if (reasons.isEmpty()) null else "${entry.displayName} — $reasons"
    }

    // ── The floor, and the progress it is counted against ────────────────────────────────────────

    /** What a gallery is holding, in the states that are genuinely different from each other. */
    data class GalleryCounts(
        /** References in the field's value: what a save would post. */
        val held: Int,
        /**
         * Of [held], the ones whose bytes have not reached the server yet — a descriptor with no
         * `remoteMediaId`.
         *
         * On this client that is nearly always all of them, which is the point: the draft IS the
         * document for a fortnight, and a designer who reads "25 of 25" in a courtyard and never
         * learns that none of them have left the handset is a designer one wiped phone away from
         * losing the lot.
         */
        val onDevice: Int = 0,
        /** Measured but not yet judged — see the gate above. Not imported, and may never be. */
        val screening: Int = 0,
        /**
         * In flight to object storage right now.
         *
         * ALWAYS ZERO ON THIS CLIENT AND KEPT ANYWAY. The handset has no eager per-file upload —
         * `WorkshopSync` sends a stage's media in one pass, long after the capture screen is gone —
         * so nothing here ever sets it. It stays so that [galleryProgress] produces the SAME
         * sentence as `photoGate.ts` for the same counts, which is the whole reason the two are
         * ports rather than two implementations; a designer moving between the phone and the laptop
         * mid-workshop must not meet two accounts of one gallery.
         */
        val uploading: Int = 0,
    )

    data class GalleryProgress(
        val held: Int,
        val floor: Int,
        /** 0-100, clamped. The bar's fraction, and nothing else's. */
        val percent: Int,
        /** The bare readout, e.g. "18 of 25". Drawn in digits AND spoken in the content description. */
        val readout: String,
        /** The full sentence: the readout, what is left, and every qualifier true right now. */
        val words: String,
        val complete: Boolean,
    )

    /**
     * The bar's numbers and the bar's sentence, from one place so they cannot disagree.
     *
     * ── THE NUMERATOR IS [GalleryCounts.held], AND NOTHING IN FLIGHT IS QUIETLY ADDED TO IT ───
     *
     * A photograph being screened is not in the gallery: it may yet be refused, and the field's
     * value has no reference to it. Counting it would draw "25 of 25" over a gallery a save would
     * post twenty-three of — a full bar as a receipt for work that has not happened, which is the
     * failure this repository names most often. So the in-flight files are stated as their own
     * clause instead, which is both honest and more useful: "23 of 25 attached, 2 being checked"
     * tells a designer to wait, where "25 of 25" tells them to walk away.
     *
     * ── AND "ATTACHED" IS STILL NOT "SYNCED" ─────────────────────────────────────────────────
     *
     * That belongs on the standing floor paragraph rather than in here, because it is true from
     * first paint and does not change as the count moves; repeating it in a level that updates on
     * every attach would be noise around the one number that is changing. See [galleryFloorSentence].
     */
    fun galleryProgress(counts: GalleryCounts, floor: Int): GalleryProgress {
        val held = maxOf(0, counts.held)
        // A floor of zero cannot be a denominator, and a stage that divided by one anyway would read
        // 100% forever rather than crashing — which is the safer of the two wrong answers, and the
        // reason the caller is required to have a DECLARED floor before it draws anything at all.
        val safeFloor = maxOf(1, floor)
        val percent = ((held * 100.0) / safeFloor).let { Math.round(it).toInt() }.coerceIn(0, 100)
        val readout = "$held of $floor"
        val remaining = maxOf(0, floor - held)

        val clauses = mutableListOf<String>()
        when {
            remaining == 0 -> clauses += "All $floor photographs are attached."
            held == 0 -> clauses += "None of the $floor photographs this gallery needs are attached yet."
            else -> clauses += "$readout photographs are attached. $remaining more " +
                "${if (remaining == 1) "is" else "are"} needed."
        }
        if (counts.screening > 0) {
            clauses += "${counts.screening} ${if (counts.screening == 1) "is" else "are"} being " +
                "checked before ${if (counts.screening == 1) "it uploads" else "they upload"}."
        }
        if (counts.uploading > 0) {
            clauses += "${counts.uploading} more ${if (counts.uploading == 1) "is" else "are"} " +
                "uploading and ${if (counts.uploading == 1) "is" else "are"} not counted yet."
        }
        if (counts.onDevice > 0) {
            // THE ALL-CASE IS ITS OWN CLAUSE, which is a correction the web should copy. On this
            // client `onDevice` equals `held` for most of a fortnight, and "25 of the 25 are on this
            // device only" is a sentence nobody writes; it reads as a near-miss of the count above
            // it rather than as the plain fact that none of them have left. The browser reaches the
            // same state whenever a whole gallery was filled offline.
            clauses += if (counts.onDevice >= held) {
                "All $held are on this device only until the connection returns."
            } else {
                "${counts.onDevice} of the $held ${if (counts.onDevice == 1) "is" else "are"} on " +
                    "this device only until the connection returns."
            }
        }

        return GalleryProgress(
            held = held,
            floor = floor,
            percent = percent,
            readout = readout,
            words = clauses.joinToString(" "),
            complete = remaining == 0,
        )
    }

    /**
     * THE STANDING SENTENCE — the one that must be on screen BEFORE the twentieth photograph, not
     * after.
     *
     * It says three things in this order: how many are wanted, that falling short costs the designer
     * nothing today, and what it does cost.
     *
     * ── EVERY CLAIM IN IT WAS CHECKED AGAINST WHAT IS ACTUALLY ENFORCED ──────────────────────
     *
     * "The stage still saves with fewer" is the load-bearing one and it is TRUE: the floor lives in
     * `stage_completeness` and in nothing else — not in `coerce_value`, not in `validate_entry` — so
     * no save path can refuse a partial gallery. That is deliberate, and this client is the reason:
     * `saveOrQueue` DROPS a 4xx rather than queueing it, so a server that refused a short gallery
     * would destroy a village day's work rather than delay it.
     *
     * WHAT IT DELIBERATELY DOES NOT SAY, because it is not true today: that the workshop cannot be
     * SUBMITTED. `PATCH /design-workshops/{id}` writes `status: "SUBMITTED"` behind an enum check
     * with no completeness test anywhere, so promising a hard block would be this client inventing
     * an enforcement that does not exist.
     *
     * ── TWO WORDINGS CORRECTED FROM `photoGate.ts`, BOTH OF WHICH THE WEB SHOULD COPY BACK ────
     *
     * The web's version makes the field's LABEL the subject — "Traditional motif photographs still
     * saves with fewer" — which is ungrammatical for every plural label in the registry, which is
     * all of them here. The subject is the stage.
     *
     * And its closing clause reads "Attached is not saved: the count reaches the workshop when you
     * save the stage", which is a browser's model of this app and not this one's. On the handset the
     * draft is written continuously and the stage screen's own readout says "saved on this device,
     * not yet synced"; there is no Save to press. So the handset says what its own status line says,
     * in the same words, and the two cannot disagree.
     */
    fun galleryFloorSentence(floor: Int, label: String): String =
        "All $floor are required. The stage still saves with fewer — nothing you attach is ever at " +
            "risk — but until $label holds $floor the stage is scored incomplete, and the generated " +
            "report says so. Attached is saved on this device: the count reaches the server when " +
            "this stage syncs."

    // ── The write path: a finding raised at capture becomes a stage-21 row ───────────────────────

    /**
     * A finding this device raised about a photograph it NEVERTHELESS imported, tied to the media id
     * that photograph got.
     *
     * The media id is the whole point and the reason this cannot be recorded any earlier: stage 21's
     * `mediaQualityFlag.mediaId` is a required BASIC field, and a row naming a file that does not
     * exist is worse than no row at all. So a finding becomes one of these at exactly one moment —
     * when [DwMediaBridge.attach] answers with the ids it wrote.
     */
    data class CapturedFinding(
        val mediaId: String,
        val fileName: String,
        val flag: QualityFlag,
        val severity: QualitySeverity,
        val note: String,
        /** ISO 8601. Only so a reader can tell this workshop's fieldwork from an old finding. */
        val raisedAt: String,
    )

    /**
     * WHICH FLAGS MAY EVER BE FILLED IN BY A MACHINE — a shorter list than the enum, deliberately.
     *
     * Stage 21's `MEDIA_QUALITY_FLAG` has seven members. This product computes three of them. The
     * other four — OVEREXPOSED, UNDEREXPOSED, WRONG_SUBJECT, and MISSING_VIEW where it is about a
     * ROW rather than about one file — are judgements no measurement here makes, or are not about a
     * single file at all, and they stay hand-entered. Auto-filling a flag nothing measured would put
     * this app's guess into a column an officer reads as an observation.
     *
     * `autoDetected` on the row is what separates the two afterwards, and it is only ever true for a
     * member of this set.
     */
    val AUTO_FILLABLE_FLAGS: Set<QualityFlag> =
        setOf(QualityFlag.BLUR, QualityFlag.LOW_RESOLUTION, QualityFlag.DUPLICATE)

    /** One row of stage 21's `mediaQualityFlag` collection, in the registry's own field keys. */
    data class MediaQualityFlagRow(
        val mediaId: String,
        val flag: QualityFlag,
        val severity: QualitySeverity,
        val autoDetected: Boolean = true,
        val note: String,
    )

    /**
     * Turn what this device found at capture into stage-21 rows, ready to be offered.
     *
     * ── WHY THIS IS ALMOST ALWAYS ABOUT DUPLICATES, WHICH IS THE GATE WORKING ────────────────
     *
     * The gate and this write path are two halves of one decision and reading them together is the
     * only way either makes sense. A BLUR or LOW_RESOLUTION photograph is REFUSED, so it is never
     * imported, never gets an id, and can never be one of these rows — correctly, since there would
     * be no file in the archive for the flag to be about. What survives the gate and still deserves
     * a row is the near-duplicate, which is admitted on purpose. So the practical effect of shipping
     * the gate is that this table stops recording faults and starts recording the one fault worth
     * recording.
     *
     * The other two arms are kept rather than narrowed to DUPLICATE, because [faultRefuses] is the
     * one place that decision lives: the day an owner answers the override question by letting a
     * designer push a soft photograph through, its finding travels here with no other change.
     *
     * ── IT PROPOSES; A PERSON COMMITS ────────────────────────────────────────────────────────
     *
     * Same rule as [DwPhotoIntake] and the identity-card reader: nothing here writes to a stage. It
     * returns rows and a screen offers them. A row appearing in an archive table that nobody chose
     * is a row nobody will trust — and stage 21's own registry note still reads "Rows here are
     * entered by hand either way; tick 'Detected automatically' for the ones the app raised", which
     * is a promise about a screen and stays true for exactly as long as the commit is a person's.
     */
    fun mediaQualityFlagRows(findings: List<CapturedFinding>): List<MediaQualityFlagRow> {
        // ONE ROW PER FILE PER FLAG, AND THE NEWEST READING WINS — the same rule, in the same words,
        // that [DwQualityFlagLog.record] applies to the log this reads from, because two functions
        // in one feature disagreeing about which of two measurements of one photograph is the true
        // one is a difference nobody would ever see and everybody would eventually trip over. The
        // same photograph really is measured more than once (a stage reopened, a row re-expanded)
        // and the reading that describes the file in hand is the last one taken. LinkedHashMap keeps
        // a re-put key in its ORIGINAL position, so refreshing a reading does not reorder the table
        // under a reader.
        val byKey = LinkedHashMap<String, MediaQualityFlagRow>()
        for (finding in findings) {
            if (finding.flag !in AUTO_FILLABLE_FLAGS) continue
            byKey["${finding.mediaId}:${finding.flag}"] = MediaQualityFlagRow(
                mediaId = finding.mediaId,
                flag = finding.flag,
                severity = finding.severity,
                autoDetected = true,
                note = "${finding.fileName}: ${finding.note}",
            )
        }
        return byKey.values.toList()
    }
}
