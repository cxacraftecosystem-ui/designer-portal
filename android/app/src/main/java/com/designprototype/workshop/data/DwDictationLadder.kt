package com.designprototype.workshop.data

enum class DwDictationRung {
    ON_DEVICE_PACK,

    APP_SPEECH_MODEL,

    SERVER_DICTATE,

    NETWORK_RECOGNISER,
}

enum class DwTier3Consent {
    NOT_RECORDED,

    GRANTED,

    REFUSED,
}

data class DwDictationConditions(
    val languageLabel: String,
    val packState: DwPackState,
    val onDeviceEngine: Boolean,
    val networkRecogniser: Boolean,
    val online: Boolean,
    val serverRouteUnavailable: Boolean,
    val deviceRefusedLanguage: Boolean = false,
    val appModelServesLanguage: Boolean = false,
    val appModelRefusedLanguage: Boolean = false,
    val tier3Consent: DwTier3Consent,
    val dailyCapSpent: Boolean,
    val dailyCapLimit: Int?,
    val workshopOnServer: Boolean,
)

data class DwDictationPlan(
    val rungs: List<DwDictationRung>,
    val exhausted: String?,
    val suppressed: String? = null,
) {
    val first: DwDictationRung? get() = rungs.firstOrNull()

    fun after(rung: DwDictationRung): DwDictationRung? {
        val index = rungs.indexOf(rung)
        return if (index < 0) null else rungs.getOrNull(index + 1)
    }
}

private fun packAllowsOnDevice(state: DwPackState): Boolean =
    state == DwPackState.INSTALLED || state == DwPackState.UNKNOWN

fun dwDictationLadder(conditions: DwDictationConditions): DwDictationPlan {
    val rungs = mutableListOf<DwDictationRung>()

    val deviceRung = conditions.onDeviceEngine &&
        !conditions.deviceRefusedLanguage &&
        packAllowsOnDevice(conditions.packState)

    val appModelRung = conditions.appModelServesLanguage && !conditions.appModelRefusedLanguage

    val networkRung = conditions.networkRecogniser &&
        conditions.online &&
        conditions.packState != DwPackState.UNSUPPORTED

    val serverRung = conditions.online &&
        !conditions.serverRouteUnavailable &&
        conditions.tier3Consent != DwTier3Consent.REFUSED &&
        !conditions.dailyCapSpent &&
        conditions.workshopOnServer

    val phoneNotYetAsked = conditions.packState == DwPackState.UNKNOWN &&
        !conditions.onDeviceEngine &&
        !conditions.deviceRefusedLanguage

    if (deviceRung) rungs += DwDictationRung.ON_DEVICE_PACK
    if (appModelRung) rungs += DwDictationRung.APP_SPEECH_MODEL
    if (phoneNotYetAsked && networkRung) rungs += DwDictationRung.NETWORK_RECOGNISER
    if (serverRung) rungs += DwDictationRung.SERVER_DICTATE
    if (!phoneNotYetAsked && networkRung) rungs += DwDictationRung.NETWORK_RECOGNISER

    return DwDictationPlan(
        rungs = rungs,
        exhausted = if (rungs.isEmpty()) dwDictationNothingLeftSentence(conditions) else null,
        suppressed = if (rungs.isEmpty()) null else dwSuppressedServerRungSentence(conditions),
    )
}

private fun dwSuppressedServerRungSentence(conditions: DwDictationConditions): String? {
    if (!conditions.online || conditions.serverRouteUnavailable) return null
    return when {
        conditions.tier3Consent == DwTier3Consent.REFUSED ->
            "Transcription was declined (the answer on record), so dictation uses the phone's recogniser (no craft words)."

        !conditions.workshopOnServer ->
            "This workshop is only on this phone, so dictation uses the phone's recogniser (no craft words). Upload the workshop from its card in Design workshops."

        conditions.dailyCapSpent && conditions.dailyCapLimit == 0 ->
            "Transcription is off — the daily allowance is set to none. Using the phone's recogniser."

        conditions.dailyCapSpent && conditions.dailyCapLimit != null ->
            "You have used all ${conditions.dailyCapLimit} of today's transcriptions. Using the phone's recogniser until midnight India time."

        conditions.dailyCapSpent ->
            "Today's transcriptions are used up. Using the phone's recogniser until midnight India time."

        else -> null
    }
}

fun dwDictationNothingLeftSentence(conditions: DwDictationConditions): String {
    val label = conditions.languageLabel
    return when {
        !conditions.online && conditions.appModelRefusedLanguage ->
            "The speech model on this phone can't take $label right now, and you're offline. Type the answer in."

        !conditions.online && conditions.onDeviceEngine &&
            dwPackOffer(conditions.packState, DwConnection.NONE) == DwPackOffer.NO_CONNECTION ->
            "$label pack missing and you're offline (needs a connection). Type the answer in; add the pack in Settings › Offline dictation languages."

        !conditions.online && conditions.onDeviceEngine ->
            "$label needs a connection. Type the answer in."

        !conditions.online ->
            "$label dictation needs a connection. Type the answer in."

        !conditions.serverRouteUnavailable &&
            conditions.tier3Consent == DwTier3Consent.REFUSED ->
            "Can't dictate $label — transcription was declined (the answer on record). Type the answer in."

        !conditions.serverRouteUnavailable && !conditions.workshopOnServer ->
            "Can't dictate $label: this workshop is only on this phone. Type the answer in, then upload the workshop from its card in Design workshops."

        !conditions.serverRouteUnavailable &&
            conditions.dailyCapSpent && conditions.dailyCapLimit == 0 ->
            "Can't dictate $label — the daily allowance is set to none. Type the answer in."

        !conditions.serverRouteUnavailable &&
            conditions.dailyCapSpent && conditions.dailyCapLimit != null ->
            "Can't dictate $label — you have used all ${conditions.dailyCapLimit} of today's transcriptions. Type the answer in; it starts again after midnight India time."

        !conditions.serverRouteUnavailable && conditions.dailyCapSpent ->
            "Can't dictate $label — today's transcriptions are used. Type the answer in; it starts again after midnight India time."

        conditions.appModelRefusedLanguage ->
            "Can't dictate $label — transcription is not set up. Type the answer in."

        conditions.packState == DwPackState.UNSUPPORTED ->
            "$label isn't supported here, and transcription is not set up. Pick another language or type the answer in."

        else ->
            "Can't dictate $label — transcription is not set up. Type the answer in."
    }
}

const val DW_DICTATION_MAX_BYTES: Long = 6L * 1024 * 1024

const val DW_DICTATION_MAX_MILLIS: Int = 4 * 60 * 1000

const val DW_DICTATION_BITS_PER_SECOND: Int = 32_000
const val DW_DICTATION_SAMPLE_RATE_HZ: Int = 16_000
const val DW_DICTATION_CHANNELS: Int = 1

fun dwDictationOversize(bytes: Long): String? {
    if (bytes <= DW_DICTATION_MAX_BYTES) return null
    val megabytes = DW_DICTATION_MAX_BYTES / (1024 * 1024)
    return "Clip over ${megabytes} MB. Dictate shorter passages, or attach it as workshop audio."
}

const val DW_DICTATION_NOTHING_RECORDED: String =
    "Nothing recorded. Check the microphone isn't in use and try again."

const val DW_DICTATION_NO_WORDS: String =
    "No words heard. Speak closer and try again, or type."

const val DW_DICTATION_NOT_CONFIGURED: String =
    "Transcription is not set up. Type the answer in."

const val DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN: String =
    "Transcription is not set up. Nothing was saved. Tap the mic and say it again — the phone's own recogniser will take it."

const val DW_DICTATION_CAP_SPENT_SAY_AGAIN: String =
    "Today's transcriptions are used up. Nothing was saved. Tap the mic and say it again — the phone's own recogniser will take it."

const val DW_DICTATION_CONSENT_REFUSED: String =
    "Not sent — there's no yes on record for transcribing this workshop's recordings. Nothing was saved. Type the answer in, and check the workshop's own screen."

const val DW_DICTATION_BUSY: String =
    "Service busy and nothing is queued. Try again shortly, or type."

const val DW_DICTATION_UPLOAD_FAILED: String =
    "Connection dropped; nothing saved. Try again or type."

const val DW_DICTATION_TRANSCRIPTION_FAILED: String =
    "Couldn't transcribe. Try again or type."

const val DW_DICTATION_SERVER_UNREACHABLE: String =
    "Couldn't connect; nothing saved. Try again or type."

fun dwDictationServerAnswerSentence(status: String, message: String?): String = when {
    status.equals("RATE_LIMITED", ignoreCase = true) -> DW_DICTATION_BUSY
    message?.contains("will retry automatically", ignoreCase = true) == true -> DW_DICTATION_BUSY
    status.equals("FAILED", ignoreCase = true) -> DW_DICTATION_TRANSCRIPTION_FAILED
    else -> DW_DICTATION_NO_WORDS
}
