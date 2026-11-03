package com.designprototype.workshop.data

enum class DwModelFit {
    COMFORTABLE,

    TIGHT,

    WILL_NOT_FIT,

    UNMEASURED,
    ;

    val mayInstall: Boolean get() = this == COMFORTABLE || this == TIGHT
}

enum class DwFitNote {

    LARGER_THAN_THIS_PHONE_S_MEMORY,

    LARGER_THAN_THE_FREE_STORAGE,

    NO_BUILD_FOR_THIS_PROCESSOR,

    LOAD_FAILED_HERE_BEFORE,

    PROCESSOR_UNMEASURED,

    TOTAL_MEMORY_UNMEASURED,

    FREE_STORAGE_UNMEASURED,

    FREE_MEMORY_UNMEASURED,

    ANDROID_CALLS_THIS_A_LOW_MEMORY_DEVICE,

    LITTLE_MEMORY_LEFT_OVER,

    LITTLE_FREE_MEMORY_RIGHT_NOW,

    LITTLE_STORAGE_LEFT_OVER,
}

data class DwModelChoice(
    val plan: DwModelPlan,
    val fit: DwModelFit,
    val notes: List<DwFitNote>,
    val freeRamHeadroomBytes: Long?,
    val freeStorageHeadroomBytes: Long?,
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

    if (failures.any { it.modelId == plan.modelId && it.contextCapTokens == plan.contextCapTokens }) {
        return answer(DwModelFit.WILL_NOT_FIT, DwFitNote.LOAD_FAILED_HERE_BEFORE)
    }

    if (measurement.abis.isEmpty()) {
        return answer(DwModelFit.UNMEASURED, DwFitNote.PROCESSOR_UNMEASURED)
    }
    if (measurement.abis.none { it.equals(plan.abi, ignoreCase = true) }) {
        return answer(DwModelFit.WILL_NOT_FIT, DwFitNote.NO_BUILD_FOR_THIS_PROCESSOR)
    }

    val total = measurement.totalRamBytes
    if (total == null || total <= 0L) {
        return answer(DwModelFit.UNMEASURED, DwFitNote.TOTAL_MEMORY_UNMEASURED)
    }
    if (plan.peakRssBytes >= total) {
        return answer(DwModelFit.WILL_NOT_FIT, DwFitNote.LARGER_THAN_THIS_PHONE_S_MEMORY)
    }

    val storage = measurement.freeStorageBytes
    if (storage == null) {
        return answer(DwModelFit.UNMEASURED, DwFitNote.FREE_STORAGE_UNMEASURED)
    }
    if (plan.onDiskBytes > storage) {
        return answer(DwModelFit.WILL_NOT_FIT, DwFitNote.LARGER_THAN_THE_FREE_STORAGE)
    }

    val free = measurement.availableRamBytes
    if (free == null) {
        return answer(DwModelFit.UNMEASURED, DwFitNote.FREE_MEMORY_UNMEASURED)
    }

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

fun dwModelChoices(
    catalogue: List<DwModelPlan>,
    measurement: DwDeviceMeasurement,
    failures: List<DwLoadFailureNote> = emptyList(),
    suggestion: DwTierOffer? = null,
    tier: DwAiTier? = null,
): List<DwModelChoice> {
    val mine = if (tier == null) failures else failures.filter { it.tier == tier }
    val suggested = (suggestion as? DwTierOffer.Available)?.plan
    return catalogue
        .map { plan ->
            val judged = dwModelFit(plan, measurement, mine)
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

private fun dwFitOrder(fit: DwModelFit): Int = when (fit) {
    DwModelFit.COMFORTABLE -> 0
    DwModelFit.TIGHT -> 1
    DwModelFit.UNMEASURED -> 2
    DwModelFit.WILL_NOT_FIT -> 3
}

fun dwModelDownloadMayBeOffered(choice: DwModelChoice, connection: DwConnection): Boolean =
    choice.fit.mayInstall && connection != DwConnection.NONE

fun dwModelNeedsConsent(choice: DwModelChoice): Boolean = choice.fit == DwModelFit.TIGHT

fun dwModelFitLabel(fit: DwModelFit): String = when (fit) {
    DwModelFit.COMFORTABLE -> "Comfortable here"
    DwModelFit.TIGHT -> "Tight here"
    DwModelFit.WILL_NOT_FIT -> "Will not fit"
    DwModelFit.UNMEASURED -> "This phone would not say"
}

fun dwModelChoiceSentence(
    choice: DwModelChoice,
    measurement: DwDeviceMeasurement,
    labels: Map<String, String> = emptyMap(),
): String = buildString {
    val plan = choice.plan
    append(plan.modelId)
    append(" — ")
    append(dwBytesLabel(plan.onDiskBytes))
    append(" to download. ")
    append(dwModelFitSentence(choice, measurement))
    append(dwModelAccuracyClause(plan, labels))
    append(dwModelSpeedClause(plan))
    if (choice.fit != DwModelFit.TIGHT) append(dwModelBackgroundingClause(plan))
}

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
        append("This phone can run it, but only just. ")
        append(choice.notes.joinToString(" ") { dwFitNoteClause(it, choice, measurement) })
        append(
            " Android may close this app while it runs (e.g. with the camera). Your work is saved."
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
            " Tap “Check again”."
        )
    }
}

private fun dwFitNoteClause(
    note: DwFitNote,
    choice: DwModelChoice,
    measurement: DwDeviceMeasurement,
): String = when (note) {
    DwFitNote.LARGER_THAN_THIS_PHONE_S_MEMORY ->
        "Needs ${dwBytesLabel(choice.plan.peakRssBytes)} of memory — more than this phone has; closing apps would not make room."

    DwFitNote.LARGER_THAN_THE_FREE_STORAGE ->
        "${dwBytesLabel(choice.plan.onDiskBytes)} download — not enough free storage."

    DwFitNote.NO_BUILD_FOR_THIS_PROCESSOR ->
        "Not built for this processor (needs ${choice.plan.abi})."

    DwFitNote.LOAD_FAILED_HERE_BEFORE ->
        "Failed to load here before."

    DwFitNote.PROCESSOR_UNMEASURED ->
        "This phone would not say its processor."

    DwFitNote.TOTAL_MEMORY_UNMEASURED ->
        "This phone would not say how much memory it has."

    DwFitNote.FREE_STORAGE_UNMEASURED ->
        "This phone would not say its free storage."

    DwFitNote.FREE_MEMORY_UNMEASURED ->
        "This phone would not say how much memory is free."

    DwFitNote.ANDROID_CALLS_THIS_A_LOW_MEMORY_DEVICE ->
        "Android flags this handset as low-memory."

    DwFitNote.LITTLE_MEMORY_LEFT_OVER ->
        "Needs ${dwBytesLabel(choice.plan.peakRssBytes)} of ${dwBytesLabel(measurement.totalRamBytes)} memory — too little left over."

    DwFitNote.LITTLE_FREE_MEMORY_RIGHT_NOW ->
        "Needs ${dwBytesLabel(choice.plan.peakRssBytes)}; ${dwBytesLabel(measurement.availableRamBytes)} free now. Close apps and tap “Check again”."

    DwFitNote.LITTLE_STORAGE_LEFT_OVER ->
        "Would leave only ${dwBytesLabel(choice.freeStorageHeadroomBytes)} free."
}

fun dwModelOverrideSentence(choice: DwModelChoice): String? {
    if (choice.fit != DwModelFit.TIGHT) return null
    return "${choice.plan.modelId} is tight on this phone. Android may close this app while it runs (e.g. with the camera); your recording, photos and draft stay saved on the phone. Install anyway?"
}

fun dwModelOverrideConfirmLabel(choice: DwModelChoice): String = "Install ${choice.plan.modelId} anyway"

fun dwModelChoiceIntroSentence(choices: List<DwModelChoice>): String? {
    if (choices.isEmpty()) return null
    val comfortable = choices.count { it.fit == DwModelFit.COMFORTABLE }
    val tight = choices.count { it.fit == DwModelFit.TIGHT }
    val refused = choices.count { it.fit == DwModelFit.WILL_NOT_FIT }
    val unmeasured = choices.count { it.fit == DwModelFit.UNMEASURED }
    val offerable = comfortable + tight
    return buildString {
        append("What this phone can run. ")
        append(
            when {
                comfortable > 0 && tight > 0 ->
                    "$comfortable of them this phone is comfortable with and $tight it can run with " +
                        "less room to spare"
                comfortable > 0 -> "$comfortable of them this phone is comfortable with"
                tight > 0 -> "None is comfortable here; $tight can be run with less room to spare"

                refused > 0 && unmeasured == 0 -> "None of them can be run on this phone"
                refused > 0 -> "$refused of them cannot be run on this phone"
                else -> "This phone did not say enough about itself to judge"
            }
        )
        if (refused > 0 && offerable > 0) append(", and $refused will not fit at all")
        if (unmeasured > 0 && (offerable > 0 || refused > 0)) {
            append("; $unmeasured unknown")
        }
        append(".")
        if (choices.any { it.suggested }) {
            append(
                " “Suggested” is advice, not a rule; any that fits will work."
            )
        } else if (offerable > 0) {
            append(
                " Nothing is marked “suggested” — each row shows the cost."
            )
        }
    }
}

fun dwModelLanguagesSentence(plan: DwModelPlan, labels: Map<String, String>): String {
    val languages = plan.languages
        ?: return "${plan.modelId} is not used for any of this app's languages: its languages are not listed."
    val served = labels.keys.filter { tag -> dwModelServesLanguage(plan, tag) }
    if (served.isEmpty()) {
        val why = if (languages.isEmpty()) {
            "${plan.modelId} serves none of this app's dictation languages."
        } else {
            "${plan.modelId} serves none of this app's dictation languages (${languages.joinToString(", ")})."
        }
        return "$why"
    }
    val named = served.map { tag -> labels[tag] ?: tag }
    return "${plan.modelId} hears ${named.size} of this app's languages: ${named.joinToString(", ")}."
}
