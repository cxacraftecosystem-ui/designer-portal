package com.designprototype.workshop.data

/**
 * **THE RECOMMENDATION AS ADVICE RATHER THAN AS A VERDICT: what this handset is comfortable with,
 * what it can run with less room to spare, what will not physically fit — and the one line a
 * designer may not cross.**
 *
 * ── WHY THIS FILE EXISTS ──────────────────────────────────────────────────────────────────────
 *
 * In the user's own words: *"the model suggestion would not be binding, the model suggestion would
 * be based upon device's capabilities, and the user would be able to install the one of their
 * choice."*
 *
 * [dwRecommendTiers] answers with an offer or a refusal, and until now THAT WAS THE OUTCOME: a
 * [DwTierOffer.None] meant no control was drawn and there was nothing further to say. That shape is
 * right for "there is no engine in this build" — nobody can choose their way past a missing
 * artifact — and wrong for "this phone would be tight with the larger model", which is a fact about
 * a trade-off that belongs to the person holding the phone. A designer who works in a quiet room
 * with the charger in and nothing else open knows something about their own working day that a
 * reading of `availMem` at one instant does not.
 *
 * So the offer stays exactly as it was, and it becomes THE SUGGESTION. Beside it stands the whole
 * catalogue, judged: [DwModelFit.COMFORTABLE], [DwModelFit.TIGHT], [DwModelFit.WILL_NOT_FIT],
 * [DwModelFit.UNMEASURED]. `DwModelChoiceTest` pins that the two cannot disagree — a suggestion is
 * always a model this file called comfortable, computed from the same numbers in the same call.
 *
 * ── WHERE THE LINE IS DRAWN, AND WHY IT IS DRAWN THERE ────────────────────────────────────────
 *
 * **A DESIGNER MAY INSTALL A MODEL THIS APP WOULD NOT HAVE SUGGESTED. THEY MAY NOT INSTALL ONE
 * THAT CANNOT PHYSICALLY FIT.** Refusing the second is not paternalism, it is the difference
 * between a recommendation and a crash, and the line is drawn at exactly three facts — each of
 * which is a *physical impossibility on this handset*, not a policy about margins:
 *
 *  | the line | why nothing a designer does changes it |
 *  |---|---|
 *  | `peakRssBytes >= totalRamBytes` | the resident set alone is the whole of what the kernel says exists. Closing every other app leaves the process still larger than the machine |
 *  | `onDiskBytes > freeStorageBytes` | the file cannot be written. Not "tight" — the download fails partway and takes the day's photographs' room with it while it tries |
 *  | the ABI has no build | the code will not execute on this processor. There is nothing to be brave about |
 *
 * And one more that is not arithmetic at all: **a model that has already failed to load on THIS
 * handset** ([DwLoadFailureNote]). That is measured evidence about this exact phone, and evidence
 * outranks a table — the rule was already written in docs/DEVICE-TIER-MEASUREMENT.md before there
 * was anything to apply it to.
 *
 * **EVERYTHING ELSE IS ADVICE.** The 512 MiB free-memory margin and the 1 GiB storage margin are
 * both **chosen** numbers ([DW_MODEL_FREE_RAM_MARGIN_BYTES] says so in its own comment), and a
 * chosen number is not a physical law: crossing one produces [DwModelFit.TIGHT], which is offered
 * with the expected cost said out loud and then installed if the designer still wants it.
 * Android's own `isLowRamDevice` flag is in the same group — it is the platform's considered
 * verdict about a build, so a model can never be COMFORTABLE on a handset carrying it, but it is
 * not a claim that the bytes will not fit, so it does not refuse either.
 *
 * ── AND THE FOURTH VALUE, WHICH IS NEITHER A YES NOR A NO ─────────────────────────────────────
 *
 * [DwModelFit.UNMEASURED] is what a handset that would not answer gets — `StatFs` threw, `availMem`
 * could not be read, `Build.SUPPORTED_ABIS` came back empty. It **refuses the install** like a no,
 * and it **says something quite different** from one: this app will not spend a gigabyte of
 * somebody's prepaid bundle on a guess about whether it fits, and it will not tell them their phone
 * is too small when the truth is that their phone would not say. Same rule as
 * `DwPackState.UNKNOWN`, same rule as `DwAsrOffer.STORAGE_UNMEASURED`, applied to the one decision
 * in this app that spends the most data.
 *
 * ── WHY THE DECISION IS PURE ──────────────────────────────────────────────────────────────────
 *
 * Plain Kotlin over plain numbers: no Context, no `android.os`, no Compose. The predicate standing
 * between a designer and a multi-gigabyte download has to be checkable on a desktop JVM, exactly as
 * `dwPackOffer` and `dwAsrOffer` are. The composable that draws it is
 * `ui/designworkshop/DwModelChoiceUi.kt` and it decides nothing.
 *
 * ── BOTH CATALOGUES NOW HAVE ROWS, AND THIS PARAGRAPH USED TO SAY THEY DID NOT ────────────────
 *
 * It read: *"[DW_TIER1_CATALOGUE] and [DW_TIER2_CATALOGUE] are empty, so every list this file produces
 * is empty on every handset in the fleet."* Tier 1 has held one measured row since 2026-08-12 and
 * Tier 2 has held two since 2026-08-13, so this file's arithmetic now runs on real numbers on every
 * handset and the lists have rows. What it was pointing at is still true and is worth keeping: the
 * arithmetic was written and exercised against openly invented plans by `DwModelChoiceTest` BEFORE any
 * artifact existed, which is why the day the measurements arrived the only new thing in the app was a
 * row of numbers.
 *
 * **THE TWO CATALOGUES ARE NOT DRAWN BY THE SAME COMPOSABLE ANY MORE**, and that is not a duplication
 * to be tidied away: [dwModelChoiceSentence] appends `dwModelAccuracyClause` and `dwModelSpeedClause`,
 * both of which say the word "transcribe", and a language model does not transcribe. Tier 2 draws
 * through `ui/designworkshop/DwTier2ModelUi.kt` over sentences in `data/DwTier2Models.kt`; every
 * verdict on both paths still comes from [dwModelFit] here, which is the part that must never fork.
 */

// ---------------------------------------------------------------------------------------------
// The four answers
// ---------------------------------------------------------------------------------------------

/** How this handset would get on with one model. **Four values, and only one of them is a no.** */
enum class DwModelFit {
    /**
     * It fits with both chosen margins intact. **The only fit the probe ever SUGGESTS.**
     *
     * Exactly the state [dwPlanFits] returns null for — the same arithmetic, not a second copy of
     * it, and a test pins the equivalence so the two can never drift into disagreeing about one
     * phone.
     */
    COMFORTABLE,

    /**
     * It physically fits and one or both margins are gone. **Offered, with the cost said first.**
     *
     * The margins are chosen numbers standing in for what the app is still holding while a model
     * runs — the draft, the decoded photographs, Compose's retained bitmaps — so crossing one is a
     * risk rather than an impossibility, and it is a risk whose shape the designer can judge better
     * than this app can: they know whether they are about to open the camera.
     */
    TIGHT,

    /**
     * **THE LINE. It cannot run on this handset, and consent does not enter into it.**
     *
     * Larger than the whole of the phone's memory, larger than its free storage, no build for its
     * processor, or already measured failing to load on this exact handset. No control is drawn,
     * and the sentence says which of the four it is.
     */
    WILL_NOT_FIT,

    /**
     * The handset would not answer a question the fit depends on. **Not a no, and not a yes.**
     *
     * Refuses the install for the same reason `DwAsrOffer.STORAGE_UNMEASURED` does — a download
     * started on a question nobody answered is the one that dies at 98% having spent the bundle to
     * get there — while saying the true thing, which is that the reading is missing rather than the
     * room.
     */
    UNMEASURED,
    ;

    /**
     * Whether a model in this state may be installed at all. **The line, in one property.**
     *
     * TIGHT is a yes: that is the whole point of this file. UNMEASURED is a no that is not about
     * the phone being small. Read [dwModelDownloadMayBeOffered] before drawing any control — this
     * property knows nothing about the connection.
     */
    val mayInstall: Boolean get() = this == COMFORTABLE || this == TIGHT
}

/**
 * WHY a model is not comfortable, in the vocabulary the sentences are built from.
 *
 * Ordered as the checks run: the impossibilities first, then the unmeasured signals, then the
 * margins. A [DwModelChoice] that is WILL_NOT_FIT or UNMEASURED carries exactly one of these — the
 * first found, in durability order, because the useful sentence is the one that says whether
 * anything the designer could do would change the answer. A TIGHT one carries **every** margin it
 * crossed, because "this is tight on memory AND on storage" is a materially different decision from
 * either alone.
 */
enum class DwFitNote {
    // ---- The line: physically impossible on this handset -------------------------------------

    /** Peak RSS is at least the whole of the memory this phone reports. Nothing closes that gap. */
    LARGER_THAN_THIS_PHONE_S_MEMORY,

    /** The file is larger than the free storage. The download cannot complete, let alone the load. */
    LARGER_THAN_THE_FREE_STORAGE,

    /** No runtime build for any ABI this handset reports. The code will not execute here. */
    NO_BUILD_FOR_THIS_PROCESSOR,

    /**
     * **IT WAS TRIED ON THIS HANDSET AND IT DID NOT LOAD.** Measured, and it outranks the table.
     *
     * Matched on model id AND context cap, as [DwLoadFailureNote] documents: the same weights at a
     * smaller cap is a different run that may well succeed, and refusing it would retire a model on
     * the strength of an experiment nobody performed.
     */
    LOAD_FAILED_HERE_BEFORE,

    // ---- Unmeasured: the handset would not say -----------------------------------------------

    /** `Build.SUPPORTED_ABIS` came back empty — which is not "this phone has no processor". */
    PROCESSOR_UNMEASURED,

    /** `MemoryInfo.totalMem` could not be read, so "larger than this phone" cannot be tested. */
    TOTAL_MEMORY_UNMEASURED,

    /** `StatFs` would not answer. Nothing this size is fetched on a guess about where it goes. */
    FREE_STORAGE_UNMEASURED,

    /** `MemoryInfo.availMem` could not be read, so how much room is left over is a question. */
    FREE_MEMORY_UNMEASURED,

    // ---- Tight: it fits, and something the app chose to keep in reserve is gone ---------------

    /**
     * `isLowRamDevice()` is set. **Android's own verdict about this build**, and the one tight note
     * that is not arithmetic — the OEM configuration behind it knows things a memory total does not.
     */
    ANDROID_CALLS_THIS_A_LOW_MEMORY_DEVICE,

    /** Peak RSS plus [DW_MODEL_FREE_RAM_MARGIN_BYTES] is more memory than this phone has in total. */
    LITTLE_MEMORY_LEFT_OVER,

    /** It fits in what is free RIGHT NOW only by eating into the margin. Closing apps helps. */
    LITTLE_FREE_MEMORY_RIGHT_NOW,

    /** It fits on the volume, without the room [DW_MODEL_FREE_STORAGE_MARGIN_BYTES] keeps for a day's work. */
    LITTLE_STORAGE_LEFT_OVER,
}

/**
 * One model, judged against one handset at one moment, **with the reasoning attached**.
 *
 * A data class rather than a pair of the plan and a verdict, because every surface that renders one
 * of these needs the numbers behind it: a designer deciding whether to accept a tight fit is owed
 * the headroom, not only the word "tight".
 */
data class DwModelChoice(
    val plan: DwModelPlan,
    val fit: DwModelFit,
    /** Empty exactly when [fit] is [DwModelFit.COMFORTABLE]. See [DwFitNote] for the ordering. */
    val notes: List<DwFitNote>,
    /**
     * Free memory that would remain beyond the peak RSS, or null when free memory is unmeasured.
     *
     * **NEGATIVE IS KEPT AS NEGATIVE AND NEVER CLAMPED**, for [dwHeadroomBytes]'s reason: "you are
     * 300 MB short" is a more useful thing to be able to say than "you have 0 MB spare".
     */
    val freeRamHeadroomBytes: Long?,
    /** Free storage that would remain after the download, or null when storage is unmeasured. */
    val freeStorageHeadroomBytes: Long?,
    /**
     * Whether this is the one this phone's own reading points to. **Advice, not a permission.**
     *
     * Exactly one choice in a list may carry it, and only ever a comfortable one — the invariant is
     * checked below rather than trusted, because a "suggested" flag on a model this file called
     * tight would be the app recommending something it warned about in the next sentence.
     */
    val suggested: Boolean = false,
) {
    init {
        require(fit != DwModelFit.COMFORTABLE || notes.isEmpty()) {
            "A model this phone is comfortable with has nothing to note about the fit. If there is " +
                "something to say — a margin gone, a flag set — the fit is TIGHT and the note " +
                "belongs to it; a comfortable model carrying a warning is how a card ends up " +
                "printing a caveat under a green tick and teaching a designer to ignore both."
        }
        require(fit == DwModelFit.COMFORTABLE || notes.isNotEmpty()) {
            "A model that is not comfortable has to say WHY, in a value a sentence can be built " +
                "from. Every refusal in this app names what would change it; a bare “no” is the " +
                "greyed-out control with no explanation that plan §2.1 forbids."
        }
        require(!suggested || fit == DwModelFit.COMFORTABLE) {
            "This phone's own reading may only suggest a model it is comfortable with. Suggesting " +
                "a tight one would have the app recommend something it warns about two lines later " +
                "— the choice to run it anyway is the designer's to make, and it is not made for them."
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The arithmetic — one pass, in order of durability
// ---------------------------------------------------------------------------------------------

/**
 * Judge one model against one handset. **The whole of the line, in one function.**
 *
 * THE ORDER IS THE ORDER OF DURABILITY, most permanent first, exactly as [dwPlanFits] runs — so
 * that where a model is refused for several reasons at once, the one reported is the one that says
 * whether anything the designer could do would change the answer. A phone that is short of storage
 * AND has no build for its processor hears about the processor, because deleting photographs would
 * not have helped.
 *
 * **THE EVIDENCE CHECK COMES FIRST, AHEAD OF EVERY PIECE OF ARITHMETIC.** A load that already
 * failed on this handset is a measurement of this handset, and the table it contradicts is the
 * thing being checked. Running the arithmetic first and the evidence second would let a model the
 * phone has actually rejected be printed as "comfortable" for the length of one branch.
 *
 * ── THE ONE PLACE THIS IS DELIBERATELY STRICTER THAN [dwPlanFits], AND WHY IT CANNOT BE SEEN ──
 *
 * `dwPlanFits` does **not** refuse on a missing total: it consults the total only as a durable
 * ceiling and lets the free-memory gate decide, on the argument that both figures come out of one
 * `MemoryInfo` read, so a reading that lost the total lost `availMem` with it and refuses there
 * instead. That argument is sound and nothing here weakens it. **This function refuses anyway**,
 * because the LINE it draws — "larger than the whole of this phone's memory" — is not testable
 * without a total, and a line that quietly stops being checked when a signal goes missing is not a
 * line. The two therefore agree on every reading a handset can produce, and where they cannot
 * agree, this one is the stricter: it withholds a control rather than offering a download it could
 * not size. `DwModelChoiceTest` pins both halves of that, including the equivalence itself.
 */
fun dwModelFit(
    plan: DwModelPlan,
    measurement: DwDeviceMeasurement,
    failures: List<DwLoadFailureNote> = emptyList(),
): DwModelChoice {
    val freeRamHeadroom = dwHeadroomBytes(plan, measurement)
    val storageHeadroom = measurement.freeStorageBytes?.let { it - plan.onDiskBytes }

    fun answer(fit: DwModelFit, vararg notes: DwFitNote) = DwModelChoice(
        plan = plan,
        fit = fit,
        notes = notes.toList(),
        freeRamHeadroomBytes = freeRamHeadroom,
        freeStorageHeadroomBytes = storageHeadroom,
    )

    // 1. WHAT THIS PHONE HAS ALREADY DONE WITH THIS MODEL. Matched on id AND cap — see [DwFitNote].
    if (failures.any { it.modelId == plan.modelId && it.contextCapTokens == plan.contextCapTokens }) {
        return answer(DwModelFit.WILL_NOT_FIT, DwFitNote.LOAD_FAILED_HERE_BEFORE)
    }

    // 2. The processor. Empty is UNKNOWN and not "matches nothing" — the distinction
    //    [DwDeviceMeasurement.abis] exists to keep.
    if (measurement.abis.isEmpty()) {
        return answer(DwModelFit.UNMEASURED, DwFitNote.PROCESSOR_UNMEASURED)
    }
    if (measurement.abis.none { it.equals(plan.abi, ignoreCase = true) }) {
        return answer(DwModelFit.WILL_NOT_FIT, DwFitNote.NO_BUILD_FOR_THIS_PROCESSOR)
    }

    // 3. Total memory: the line, and the one signal whose absence is not a verdict either way.
    val total = measurement.totalRamBytes
    if (total == null || total <= 0L) {
        return answer(DwModelFit.UNMEASURED, DwFitNote.TOTAL_MEMORY_UNMEASURED)
    }
    /*
     * `>=` AND NOT `>`, AND THE EQUAL CASE IS THE INTERESTING ONE. A model whose resident set is
     * EXACTLY the memory the kernel reports leaves nothing for the kernel, the launcher, Compose or
     * this app's own draft — the process cannot exist, never mind run. Erring on the side of
     * refusing an exactly-equal model costs a capability nobody could have used.
     */
    if (plan.peakRssBytes >= total) {
        return answer(DwModelFit.WILL_NOT_FIT, DwFitNote.LARGER_THAN_THIS_PHONE_S_MEMORY)
    }

    // 4. Storage: the second line. Durable — a designer can delete photographs, but not until they
    //    have some to delete, and a download that cannot complete is not a trade-off to consent to.
    val storage = measurement.freeStorageBytes
    if (storage == null) {
        return answer(DwModelFit.UNMEASURED, DwFitNote.FREE_STORAGE_UNMEASURED)
    }
    if (plan.onDiskBytes > storage) {
        return answer(DwModelFit.WILL_NOT_FIT, DwFitNote.LARGER_THAN_THE_FREE_STORAGE)
    }

    // 5. Free memory at this instant. Unmeasured refuses, for [dwPlanFits]'s reason: a model started
    //    on a guess is one the low-memory killer ends halfway through, taking the job with it.
    val free = measurement.availableRamBytes
    if (free == null) {
        return answer(DwModelFit.UNMEASURED, DwFitNote.FREE_MEMORY_UNMEASURED)
    }

    /*
     * 6. EVERYTHING BELOW HERE PHYSICALLY FITS. What remains is the margins, which are chosen
     *    numbers, and a chosen number may be crossed by somebody who knows their own working day.
     *    EVERY crossed margin is collected rather than only the first: "tight on memory" and "tight
     *    on memory and storage" are different decisions, and a designer about to spend a gigabyte is
     *    owed both halves before the tap rather than the more durable half alone.
     */
    val tight = buildList {
        if (measurement.lowRamDevice == true) add(DwFitNote.ANDROID_CALLS_THIS_A_LOW_MEMORY_DEVICE)
        if (plan.peakRssBytes + DW_MODEL_FREE_RAM_MARGIN_BYTES > total) add(DwFitNote.LITTLE_MEMORY_LEFT_OVER)
        if (plan.onDiskBytes + DW_MODEL_FREE_STORAGE_MARGIN_BYTES > storage) add(DwFitNote.LITTLE_STORAGE_LEFT_OVER)
        if (plan.peakRssBytes + DW_MODEL_FREE_RAM_MARGIN_BYTES > free) add(DwFitNote.LITTLE_FREE_MEMORY_RIGHT_NOW)
    }
    return if (tight.isEmpty()) {
        answer(DwModelFit.COMFORTABLE)
    } else {
        answer(DwModelFit.TIGHT, *tight.toTypedArray())
    }
}

/**
 * Every model in [catalogue], judged, in the order a designer should read them.
 *
 * ── THE ORDER IS DECIDED HERE AND NOT BY THE SCREEN, AND IT IS DETERMINISTIC ──────────────────
 *
 * Comfortable first, then tight, then unmeasured, then the ones that will not fit — because the
 * list is read top-down by somebody deciding, and the ones they can act on belong at the top. The
 * ones that will not fit are still LISTED rather than hidden: a designer who has heard that a
 * larger model exists needs to find out from this app that it will not fit their handset, and a
 * list that silently omitted it would send them looking for the download somewhere else.
 *
 * Within a group: largest peak RSS first (the most capable model of the ones that fit), tie-broken
 * on the artifact id. **Deterministic across two probes of an unchanged handset** — a list that
 * reordered itself under a designer's finger would move the row they were reaching for as they
 * reached for it, which is the same rule `dwPackStates` keeps its `LinkedHashMap` for.
 *
 * [suggestion] is the offer [dwRecommendTiers] produced from the same reading. Passing it here is
 * what makes "suggested" a marking of one of these rows rather than a second opinion computed
 * somewhere else.
 */
fun dwModelChoices(
    catalogue: List<DwModelPlan>,
    measurement: DwDeviceMeasurement,
    failures: List<DwLoadFailureNote> = emptyList(),
    suggestion: DwTierOffer? = null,
    tier: DwAiTier? = null,
): List<DwModelChoice> {
    // The failures that are about THIS tier, exactly as `dwTier1Offer` and `dwTier2Offer` filter
    // them. A Tier 2 summariser failing says nothing about a Tier 1 speech model that happens to
    // share an id prefix, and letting one retire the other would be a table taking evidence from a
    // run it was not about.
    val mine = if (tier == null) failures else failures.filter { it.tier == tier }
    val suggested = (suggestion as? DwTierOffer.Available)?.plan
    return catalogue
        .map { plan ->
            val judged = dwModelFit(plan, measurement, mine)
            /*
             * THE FLAG IS SET FROM THE OFFER RATHER THAN RE-DERIVED, and the `fit` guard is what
             * makes a disagreement between the two impossible to render instead of merely unlikely.
             * If a future edit made `dwBestPlan` and `dwModelFit` disagree, this drops the marking
             * — the card then shows a list with nothing suggested, which reads as "choose for
             * yourself" — rather than printing "suggested" beside a model the row underneath calls
             * tight. `DwModelChoiceTest` asserts the two agree today; this is what happens if that
             * ever stops being true in a release nobody re-ran the test on.
             */
            if (
                suggested != null &&
                suggested.modelId == plan.modelId &&
                suggested.contextCapTokens == plan.contextCapTokens &&
                judged.fit == DwModelFit.COMFORTABLE
            ) {
                judged.copy(suggested = true)
            } else {
                judged
            }
        }
        .sortedWith(
            compareBy<DwModelChoice> { dwFitOrder(it.fit) }
                .thenByDescending { it.plan.peakRssBytes }
                .thenBy { it.plan.modelId }
        )
}

/** Reading order for the four fits. Private to the sort; never rendered, never compared elsewhere. */
private fun dwFitOrder(fit: DwModelFit): Int = when (fit) {
    DwModelFit.COMFORTABLE -> 0
    DwModelFit.TIGHT -> 1
    DwModelFit.UNMEASURED -> 2
    DwModelFit.WILL_NOT_FIT -> 3
}

// ---------------------------------------------------------------------------------------------
// The gates — the only two predicates a control may be drawn from
// ---------------------------------------------------------------------------------------------

/**
 * Whether a control that spends a designer's data may be drawn for [choice]. **The only gate.**
 *
 * Sibling of [dwTierDownloadMayBeOffered] and [dwPackOffer], and it keeps their two rules:
 * **nothing auto-downloads, ever**, and a control that cannot work is worse than an absent one — so
 * a phone with no connection gets no button and a sentence instead of a button that does nothing.
 *
 * It returns false for every handset in existence today, because both catalogues are empty and a
 * list with no rows has no row to draw a control on. `DwModelChoiceTest` asserts exactly that
 * across every device shape and every connection, so a future catalogue entry cannot quietly turn a
 * multi-gigabyte download loose on the fleet without a test going red first.
 */
fun dwModelDownloadMayBeOffered(choice: DwModelChoice, connection: DwConnection): Boolean =
    choice.fit.mayInstall && connection != DwConnection.NONE

/**
 * Whether installing [choice] needs the designer to say yes to a stated cost first.
 *
 * True exactly for [DwModelFit.TIGHT]. **Consent to a risk is not the same as being protected from
 * it**: the app's job here is to say plainly what it expects to happen and then get out of the way,
 * which is one confirmation and not a wall of warnings. A comfortable model needs no second tap —
 * inventing one would train designers to tap through the confirmation that matters.
 */
fun dwModelNeedsConsent(choice: DwModelChoice): Boolean = choice.fit == DwModelFit.TIGHT

// ---------------------------------------------------------------------------------------------
// The words. One fit must read the same wherever it is printed
// ---------------------------------------------------------------------------------------------

/** The short verdict, for the end of a row. The sentence carries the rest. */
fun dwModelFitLabel(fit: DwModelFit): String = when (fit) {
    DwModelFit.COMFORTABLE -> "Comfortable here"
    DwModelFit.TIGHT -> "Tight here"
    DwModelFit.WILL_NOT_FIT -> "Will not fit"
    // NOT "Unknown fit", which reads as a shrug about the model. What is unknown is a reading of
    // the phone, and the label says whose silence it is.
    DwModelFit.UNMEASURED -> "This phone would not say"
}

/**
 * The full sentence for one choice: what it is, what it costs, and how this phone would get on with
 * it. **The place a designer decides from.**
 *
 * It states the numbers rather than only the verdict, for [dwDeviceReadoutSentence]'s reason: a
 * figure a designer can read down a phone line survives being disagreed with, and a verdict does
 * not. Sizes go through [dwBytesLabel], which divides by 1000 so the letters mean what they say
 * beside a prepaid bundle sold in decimal gigabytes.
 */
fun dwModelChoiceSentence(
    choice: DwModelChoice,
    measurement: DwDeviceMeasurement,
    /**
     * Tag → the name a designer reads, for the accuracy clause. Defaulted to empty so that the
     * arithmetic tests can ask for the sentence without carrying the nineteen; a real surface passes
     * `DW_DICTATION_LANGUAGES`, and an empty map degrades to printing raw tags rather than to silence.
     */
    labels: Map<String, String> = emptyMap(),
): String = buildString {
    val plan = choice.plan
    /*
     * THE ROW OPENS WITH THE TWO FIGURES A DESIGNER CHOOSES ON — the model's name and what the
     * download costs — and nothing else. The quantisation tag, the run bound, the peak memory
     * figure and the handset the figures came off were dropped 2026-08-16 for the reason set out on
     * [dwTierOfferSentence]: they compare CANDIDATE MODELS, which is a decision this app has already
     * made on the designer's behalf, and they are still on [DwModelPlan] for the reader making it.
     */
    append(plan.modelId)
    append(" — ")
    append(dwBytesLabel(plan.onDiskBytes))
    append(" to download. ")
    append(dwModelFitSentence(choice, measurement))
    /*
     * THE ACCURACY GOES ON THE ROW, NOT IN A FOOTNOTE, AND IT IS THE POINT OF THE FEATURE.
     *
     * A designer choosing a speech model is choosing whether their language will be written down
     * correctly, and every other number here — megabytes, memory, fit — is a question about whether
     * it will run at all. A row that printed the size and not the word error rate would be answering
     * the easy question loudly and the important one not at all. See [dwModelAccuracyClause], which
     * also prints the languages this model was measured for and NOT offered for, because "measured
     * and rejected" is what stops somebody going to look for it elsewhere.
     */
    append(dwModelAccuracyClause(plan, labels))
    append(dwModelSpeedClause(plan))
    /*
     * THE TIGHT ARM ALREADY SAID THIS, MORE SPECIFICALLY, AND BOTH WERE PRINTING.
     *
     * A tight row read "Android may close this app while the model is running — most likely while
     * you are using the camera — and the job would have to be started again. Your recording,
     * photographs and draft are saved either way." and then, forty words later, "It may stop if you
     * leave the app. Your recording and draft are saved either way." Same warning, same
     * reassurance, twice in one paragraph — which reads as two separate risks and is why the row
     * measured 176 words. The fit sentence wins because it names the camera and the consequence;
     * the generic clause is what the OTHER fits need, and they still get it.
     */
    if (choice.fit != DwModelFit.TIGHT) append(dwModelBackgroundingClause(plan))
}

/**
 * How this phone would get on with one model, in one or two sentences, naming the numbers.
 *
 * ── THE TIGHT ARM IS THE ONE THIS WHOLE LANE IS ABOUT ─────────────────────────────────────────
 *
 * It must say **what is expected to happen**, in the words a person would use, and then stop — not
 * hedge, not moralise, and not refuse. The user's own framing: *"this is larger than this phone has
 * room to spare; it may be closed by Android while you are using the camera"* — and then let them
 * do it. The camera is named on purpose rather than "another app": it is the one thing a designer
 * is certain to open during a workshop, it is the thing that costs the most when it fails, and a
 * warning about an abstraction is a warning nobody can picture.
 *
 * ── WHY THE READING IS A PARAMETER AND NOT A FIELD OF [DwModelChoice] ─────────────────────────
 *
 * The same shape `dwAsrOfferSentence` already has, for the same reason: a surface must not be able
 * to describe a different world from the one the decision was made in, and the way to enforce that
 * is to make it pass the reading it decided from. `DwTierRecommendation` carries exactly one, next
 * to the choices it produced, so the only reading in scope on the card is the right one.
 */
fun dwModelFitSentence(choice: DwModelChoice, measurement: DwDeviceMeasurement): String = when (choice.fit) {
    DwModelFit.COMFORTABLE -> buildString {
        append("This phone is comfortable with it")
        choice.freeRamHeadroomBytes?.let {
            append(" — it would have ")
            append(dwBytesLabel(it))
            append(" of memory to spare")
        }
        append(". Nothing is fetched unless you ask for it.")
    }

    DwModelFit.TIGHT -> buildString {
        // "but only just" rather than "with less room to spare than this app would choose": the note
        // that follows gives the two figures, so the opening only has to carry the verdict.
        append("This phone can run it, but only just. ")
        append(choice.notes.joinToString(" ") { dwFitNoteClause(it, choice, measurement) })
        // "THIS APP may be closed", never "it may be closed": the ambiguous pronoun reads as the
        // MODEL being closed, which sounds like a tidy shutdown rather than the app the designer is
        // working in disappearing mid-sentence. What Android ends is the process.
        append(
            " Android may close this app while the model is running — most likely while you are " +
                "using the camera — and the job would have to be started again. Your work is " +
                "saved either way. You can install it anyway."
        )
    }

    DwModelFit.WILL_NOT_FIT -> buildString {
        append("This model cannot run on this phone. ")
        append(choice.notes.joinToString(" ") { dwFitNoteClause(it, choice, measurement) })
        append(" It is not offered.")
    }

    DwModelFit.UNMEASURED -> buildString {
        append(choice.notes.joinToString(" ") { dwFitNoteClause(it, choice, measurement) })
        append(
            " So whether it would fit is unknown, and nothing this size will be fetched on a " +
                "guess. Tap “Check again”."
        )
    }
}

/** One reason, as a clause that can stand between two others. Every one names a real number. */
private fun dwFitNoteClause(
    note: DwFitNote,
    choice: DwModelChoice,
    measurement: DwDeviceMeasurement,
): String = when (note) {
    DwFitNote.LARGER_THAN_THIS_PHONE_S_MEMORY ->
        "It needs ${dwBytesLabel(choice.plan.peakRssBytes)} of memory, more than this phone has in " +
            "total — closing other apps would not make room."

    DwFitNote.LARGER_THAN_THE_FREE_STORAGE ->
        "It is ${dwBytesLabel(choice.plan.onDiskBytes)} to download and there is less free storage " +
            "than that, so the download could not finish."

    DwFitNote.NO_BUILD_FOR_THIS_PROCESSOR ->
        "There is no build of it for this phone's processor (it was built for ${choice.plan.abi})."

    /*
     * THE CITATION CAME OFF HERE ON 2026-08-16, AND IT WAS THE LAST ONE LIVE ON A SCREEN.
     *
     * `DwSpeechCardProseTest` asserts across the whole DwAiTier × DwTierRefusal grid that no refusal
     * names a file in this repository, and it swept all six arms clean on 2026-08-13 — but the model
     * ROWS were never in its net, and this clause sat on one of them still reading "it is exactly
     * what docs/DEVICE-TIER-MEASUREMENT.md exists to collect". A designer cannot open a path in this
     * repository; its only effect is to tell them the app is addressing its own authors. The test
     * now walks the fit notes too, so the next one cannot hide in the same blind spot.
     */
    DwFitNote.LOAD_FAILED_HERE_BEFORE ->
        "It was tried on this phone and would not load. It will not be tried again here, and the " +
            "failure is worth reporting."

    DwFitNote.PROCESSOR_UNMEASURED ->
        "This phone would not say what kind of processor it has."

    DwFitNote.TOTAL_MEMORY_UNMEASURED ->
        "This phone would not say how much memory it has."

    DwFitNote.FREE_STORAGE_UNMEASURED ->
        "This phone would not say how much storage is free."

    DwFitNote.FREE_MEMORY_UNMEASURED ->
        "This phone would not say how much memory is free."

    DwFitNote.ANDROID_CALLS_THIS_A_LOW_MEMORY_DEVICE ->
        "Android flags this handset as a low-memory device."

    DwFitNote.LITTLE_MEMORY_LEFT_OVER ->
        "It needs ${dwBytesLabel(choice.plan.peakRssBytes)} of memory and this phone has " +
            "${dwBytesLabel(measurement.totalRamBytes)} in total, so even with everything else " +
            "closed there would be too little left for your draft and photographs."

    DwFitNote.LITTLE_FREE_MEMORY_RIGHT_NOW ->
        "It needs ${dwBytesLabel(choice.plan.peakRssBytes)} of memory and this phone has " +
            "${dwBytesLabel(measurement.availableRamBytes)} free right now — close other apps and " +
            "tap “Check again”."

    DwFitNote.LITTLE_STORAGE_LEFT_OVER ->
        "It would leave only ${dwBytesLabel(choice.freeStorageHeadroomBytes)} of storage free, " +
            "less than this app keeps back for a workshop day of photographs and recordings."
}

/**
 * **WHAT A DESIGNER IS AGREEING TO WHEN THEY OVERRIDE THE SUGGESTION. Said before the tap, once.**
 *
 * Returns null for anything that is not [DwModelFit.TIGHT], because there is nothing to consent to:
 * a comfortable model needs no ceremony, and one that will not fit is not on offer at all.
 *
 * ── WHY IT IS NOT A LIST OF WARNINGS ──────────────────────────────────────────────────────────
 *
 * One outcome, named concretely, and what happens to the designer's work when it happens. A
 * confirmation that recites four risks is one nobody reads, and this app has exactly one thing to
 * say here that the designer cannot work out for themselves: **the job stops, the work does not.**
 * That is the fact that decides whether the risk is worth taking, and it is the fact that is easy
 * to get wrong from the outside — a designer who thinks a killed model takes the draft with it will
 * never accept a tight fit, and one who thinks nothing at all can go wrong is being misled.
 */
fun dwModelOverrideSentence(choice: DwModelChoice): String? {
    if (choice.fit != DwModelFit.TIGHT) return null
    return "You are choosing ${choice.plan.modelId}, which this phone's own reading says is larger " +
        "than it has room to spare. What that is expected to cost: Android may close this app while " +
        "the model is running — most likely while you are using the camera, which is the other " +
        "thing on this phone that wants a lot of memory at once. If that happens, the job stops and " +
        "you start it again; the recording, the photographs and the draft are saved on the phone " +
        "and are not affected. Install it if that trade is one you want to make."
}

/** The words on the button that accepts a tight fit. Names the model, so it cannot be a blind yes. */
fun dwModelOverrideConfirmLabel(choice: DwModelChoice): String = "Install ${choice.plan.modelId} anyway"

/**
 * The one line above the list, saying what kind of list it is. Null when there is nothing to list.
 *
 * **IT SAYS THE SUGGESTION IS NOT BINDING, IN THE FIRST LINE**, because that is the fact that
 * changes what a designer does with the rest of the card — and because a list where one row is
 * marked "suggested" reads as a list where the other rows are mistakes unless somebody says
 * otherwise.
 */
fun dwModelChoiceIntroSentence(choices: List<DwModelChoice>): String? {
    if (choices.isEmpty()) return null
    val comfortable = choices.count { it.fit == DwModelFit.COMFORTABLE }
    val tight = choices.count { it.fit == DwModelFit.TIGHT }
    val refused = choices.count { it.fit == DwModelFit.WILL_NOT_FIT }
    val unmeasured = choices.count { it.fit == DwModelFit.UNMEASURED }
    val offerable = comfortable + tight
    return buildString {
        append("What this phone can run, from the models that have actually been measured. ")
        append(
            when {
                comfortable > 0 && tight > 0 ->
                    "$comfortable of them this phone is comfortable with and $tight it can run with " +
                        "less room to spare"
                comfortable > 0 -> "$comfortable of them this phone is comfortable with"
                // CAPITALISED, BECAUSE A FULL STOP PRECEDES IT. This arm and the two below it open a
                // new sentence, and the lower-case forms they replaced rendered as "…have actually
                // been measured. none is comfortable here…" — which reads as a truncated string and
                // teaches a designer that the card is broken before they have read a word of it.
                tight > 0 -> "None is comfortable here; $tight can be run with less room to spare"

                /*
                 * NOTHING CAN BE OFFERED, AND THE TWO REASONS FOR THAT ARE NOT ONE SENTENCE.
                 *
                 * **A VERDICT MAY ONLY BE PRINTED OVER A COMPLETE READING.** The arm this replaced
                 * said "none of them can be run on this phone" whenever nothing was comfortable or
                 * tight — INCLUDING on a handset whose `availMem`, `StatFs` or `SUPPORTED_ABIS`
                 * simply did not answer, where every row is [DwModelFit.UNMEASURED]. That is the
                 * exact thing this file's own header forbids: *"it will not tell them their phone is
                 * too small when the truth is that their phone would not say."* A designer told
                 * their phone cannot run any of these goes and buys a different phone; a designer
                 * told the reading failed taps “Check again”.
                 *
                 * So "cannot be run" is claimed only for the models the arithmetic actually judged,
                 * and where some rows were judged and others were not, the count is named rather
                 * than generalised to all of them.
                 */
                refused > 0 && unmeasured == 0 -> "None of them can be run on this phone"
                refused > 0 -> "$refused of them cannot be run on this phone"
                else -> "This phone did not say enough about itself to judge any of them"
            }
        )
        if (refused > 0 && offerable > 0) append(", and $refused will not fit at all")
        if (unmeasured > 0 && (offerable > 0 || refused > 0)) {
            append(". For $unmeasured, this phone did not say enough to judge")
        }
        append(".")
        /*
         * THE MARKING IS ONLY EXPLAINED WHERE THERE IS A MARKING TO EXPLAIN.
         *
         * This clause used to be appended unconditionally, so a card on which nothing was
         * comfortable — which is every handset the moment the suggestion arithmetic refuses — printed
         * "The one marked “suggested” is what this phone's own reading points to" over a list with no
         * such row. That is a sentence pointing at an empty set, and it is the same defect
         * [dwCoverageSummarySentence] deliberately avoids one file over: copy that refers to
         * something not on screen reads as broken and teaches a designer to stop reading the card.
         *
         * The second arm is not silence, because the absence is itself the news: a list of things
         * this phone can run with nothing recommended needs to say WHY nothing is recommended, or a
         * designer reads the missing marking as a rendering fault.
         */
        if (choices.any { it.suggested }) {
            append(
                " The one marked “suggested” is what this phone's own reading points to — it is " +
                    "advice, not a rule, and you may install any of the others that fits."
            )
        } else if (offerable > 0) {
            append(
                " Nothing is marked “suggested”, because this phone's own reading is not comfortable " +
                    "with any of them. Each row says what it would cost here; the choice is yours."
            )
        }
    }
}

/**
 * **THE LANGUAGES ONE MODEL SERVES, IN THE NAMES A DESIGNER READS — OR THE WORD "unmeasured".**
 *
 * [labels] maps a BCP-47 tag to the name shown in the dictation chooser
 * (`DW_DICTATION_LANGUAGES`); it is passed in rather than imported because that list lives in a
 * Compose file and this one is pure. **ITS KEYS ARE THE AUTHORITY ON WHAT "this app's languages"
 * MEANS**, and the sentence below is computed from them rather than from the plan's own list.
 *
 * ── WHY THE COUNT IS AN INTERSECTION AND NOT `plan.languages.size` ────────────────────────────
 *
 * The sentence says "*N of this app's languages*", which is a claim about the nineteen, and an
 * earlier version of it counted and printed the artifact's OWN tags instead. Those two numbers are
 * the same only while every tag on a catalogue row happens to be one of the nineteen spelled the
 * same way, and the moment they are not, the row overstates on the one screen where overstating
 * costs a designer a download they cannot use:
 *
 *  * a model measured as serving `en-US` would have been printed as hearing "1 of this app's
 *    languages: en-US" on a phone whose only English row is `en-IN` — and [dwTagCovers] is
 *    deliberately asymmetric there, so it serves NONE of them. That asymmetry exists precisely
 *    because English (India) transcribed by a US model is the silent-wrong-output failure;
 *  * a model listing a tag this app does not offer at all (`fr-FR`, or a typo like `od-IN`) would
 *    have been counted as coverage and printed as a raw tag, which reads to a designer scanning the
 *    row as a language they could dictate in;
 *  * a model measured against a bare `or` — legitimate, and what [dwModelServesLanguage] widens to
 *    cover `or-IN` — would have printed the string "or" rather than the word "Odia", because a bare
 *    tag is not a key of this map.
 *
 * Asking [dwModelServesLanguage] once per offered language answers all three with the same rule the
 * coverage rows and the ladder use, so a model row and a language row can never come to two accounts
 * of one artifact. The three shapes below are still [DwModelPlan.languages]'s three — unmeasured,
 * measured-and-none, and a list — with the middle one now covering "measured, and none of what it
 * serves is ours", which is the same fact to a designer and a different one to whoever reads the
 * catalogue.
 */
fun dwModelLanguagesSentence(plan: DwModelPlan, labels: Map<String, String>): String {
    val languages = plan.languages
        ?: return "Which of this app's dictation languages ${plan.modelId} can actually hear is " +
            "unmeasured — nobody has checked it against them. It is not counted as covering any of " +
            "them, because an unchecked model is not evidence."
    val served = labels.keys.filter { tag -> dwModelServesLanguage(plan, tag) }
    if (served.isEmpty()) {
        // The two ways of serving none are kept apart, because they are not the same news to
        // whoever has to decide what to publish next: one artifact was measured against these
        // languages and hears none of them, the other was measured to hear languages this app does
        // not offer. To the designer holding the phone the consequence is identical and is said in
        // the second sentence, which is the one they act on.
        val why = if (languages.isEmpty()) {
            "${plan.modelId} serves none of this app's dictation languages."
        } else {
            // THE TAGS ARE NAMED RATHER THAN COUNTED, because this arm is the one that fires when a
            // catalogue row is wrong — a region that does not match (en-US against en-IN), or a tag
            // this app has never offered — and the person who can fix it needs to see which string
            // it was. There is no label to print them under, by definition.
            "${plan.modelId} serves none of this app's dictation languages: what it was measured to " +
                "hear (${languages.joinToString(", ")}) is not on the list this app offers."
        }
        return "$why Installing it would not change what this phone can hear offline."
    }
    val named = served.map { tag -> labels[tag] ?: tag }
    return "${plan.modelId} hears ${named.size} of this app's languages: ${named.joinToString(", ")}."
}
