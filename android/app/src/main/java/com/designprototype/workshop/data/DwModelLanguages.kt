package com.designprototype.workshop.data

fun dwModelServesLanguage(plan: DwModelPlan, tag: String): Boolean = plan.servesLanguage(tag)

data class DwLanguageCoverage(
    val tag: String,

    val fromPlatform: DwPackState,

    val fromThisApp: DwPackState,

    val offline: DwPackState,

    val servedByInstalled: List<String>,

    val servedByInstallable: List<String>,

    val servedByModelsThatWillNotFit: List<String>,

    val someCoverageUnmeasured: Boolean,
)

private fun dwCoverageRank(state: DwPackState): Int = when (state) {
    DwPackState.INSTALLED -> 0
    DwPackState.DOWNLOADING -> 1
    DwPackState.DOWNLOADABLE -> 2
    DwPackState.NETWORK_ONLY -> 3
    DwPackState.UNKNOWN -> 4
    DwPackState.UNSUPPORTED -> 5
    DwPackState.NO_OFFLINE_PACK -> 6
}

fun dwOfflineCoverage(fromPlatform: DwPackState, fromThisApp: DwPackState): DwPackState =
    if (dwCoverageRank(fromThisApp) < dwCoverageRank(fromPlatform)) fromThisApp else fromPlatform

fun dwAppModelCoverage(
    tag: String,
    choices: List<DwModelChoice>,
    installedModelIds: Set<String> = emptySet(),
    installingModelIds: Set<String> = emptySet(),
    installKnown: Boolean = true,
): DwPackState {
    val serving = choices.filter { dwModelServesLanguage(it.plan, tag) }
    if (serving.isEmpty()) return DwPackState.NO_OFFLINE_PACK
    if (!installKnown) return DwPackState.UNKNOWN
    if (serving.any { it.plan.modelId in installedModelIds && !dwRefusedByThisHandset(it) }) {
        return DwPackState.INSTALLED
    }
    if (serving.any { it.plan.modelId in installingModelIds }) return DwPackState.DOWNLOADING
    if (serving.any { it.fit.mayInstall }) return DwPackState.DOWNLOADABLE
    return DwPackState.NO_OFFLINE_PACK
}

private fun dwRefusedByThisHandset(choice: DwModelChoice): Boolean =
    DwFitNote.LOAD_FAILED_HERE_BEFORE in choice.notes

fun dwLanguageCoverages(
    tags: List<String>,
    support: DwRecognitionSupport?,
    choices: List<DwModelChoice>,
    installedModelIds: Set<String> = emptySet(),
    installingModelIds: Set<String> = emptySet(),
    installKnown: Boolean = true,
): Map<String, DwLanguageCoverage> {
    val out = LinkedHashMap<String, DwLanguageCoverage>(tags.size)
    tags.forEach { tag ->
        val serving = choices.filter { dwModelServesLanguage(it.plan, tag) }
        val platform = dwPackState(tag, support)
        val ours = dwAppModelCoverage(tag, choices, installedModelIds, installingModelIds, installKnown)
        out[tag] = DwLanguageCoverage(
            tag = tag,
            fromPlatform = platform,
            fromThisApp = ours,
            offline = dwOfflineCoverage(platform, ours),
            servedByInstalled = serving
                .filter { it.plan.modelId in installedModelIds && !dwRefusedByThisHandset(it) }
                .map { it.plan.modelId },
            servedByInstallable = serving
                .filter { it.fit.mayInstall && it.plan.modelId !in installedModelIds }
                .map { it.plan.modelId },
            servedByModelsThatWillNotFit = serving.filterNot { it.fit.mayInstall }.map { it.plan.modelId },
            someCoverageUnmeasured = choices.any { it.plan.languages == null },
        )
    }
    return out
}

fun dwSpeechSummaryLine(
    packStates: Map<String, DwPackState>,
    modelState: DwAsrModelState,
    modelServedTags: Set<String> = emptySet(),
    canAsk: Boolean = true,
): String {
    val modelClause = when (modelState) {
        DwAsrModelState.INSTALLED -> " · speech model installed"
        DwAsrModelState.INSTALLING -> " · speech model installing"
        DwAsrModelState.PAUSED -> " · speech model download paused"
        else -> ""
    }
    if (!canAsk) return "Can't list installed packs on this Android version$modelClause"
    val total = packStates.size
    if (total == 0) return "No dictation languages configured"
    val offline = packStates.count { (tag, state) ->
        val ours = if (tag in modelServedTags) DwPackState.INSTALLED else DwPackState.NO_OFFLINE_PACK
        dwOfflineCoverage(state, ours) == DwPackState.INSTALLED
    }
    val head = when (offline) {
        0 -> "No languages work offline"
        1 -> "1 of $total languages works offline"
        else -> "$offline of $total languages work offline"
    }
    return head + modelClause
}
