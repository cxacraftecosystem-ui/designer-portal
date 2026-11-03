package com.designprototype.workshop.ui

import com.designprototype.workshop.data.USAGE_BASIS_OFFERED_IN_SETTINGS
import com.designprototype.workshop.data.USAGE_BASIS_REQUIRED_AT_SIGN_IN
import com.designprototype.workshop.data.USAGE_CONSENT_GRANTED
import com.designprototype.workshop.data.USAGE_CONSENT_NOT_RECORDED
import com.designprototype.workshop.data.USAGE_CONSENT_REFUSED
import com.designprototype.workshop.data.UsageConsentDecisionDto
import com.designprototype.workshop.data.UsageConsentRecordDto
import com.designprototype.workshop.data.usageMoment

sealed interface UsageReadState {

    data object Loading : UsageReadState

    data object Failed : UsageReadState

    data class Answered(val count: Int) : UsageReadState
}

internal fun usageOfflineLine(noun: String): String =
    "Offline — the $noun aren't kept on this phone, so there's nothing to show until it connects."

internal fun usageCouldNotReadLine(noun: String): String =
    "Couldn't load the $noun. Try again; nothing is lost."

internal fun usageReadNotice(
    state: UsageReadState,
    noun: String,
    online: Boolean,
    emptyLine: String,
): String? = when (state) {
    UsageReadState.Loading -> loadingListLine(noun)
    UsageReadState.Failed -> if (online) usageCouldNotReadLine(noun) else usageOfflineLine(noun)
    is UsageReadState.Answered -> if (state.count == 0) emptyLine else null
}

internal const val USAGE_AGREE_LABEL: String = "I agree to the"

internal const val USAGE_TERMS_LINK: String = "terms and conditions"

internal const val USAGE_TICK_TO_SIGN_IN: String = "Tick the box to sign in."

internal const val USAGE_TICK_TO_CONTINUE: String = "Tick the box to continue."

internal const val USAGE_NOTICE_NOT_FILED_LINE: String =
    "Notice unavailable, so this answer is not filed yet. You'll be asked again after signing in."

internal fun usageDoorHint(
    agreed: Boolean,
    noticeReady: Boolean,
    stillFetching: Boolean,
): String? = when {
    !agreed -> USAGE_TICK_TO_SIGN_IN
    noticeReady || stillFetching -> null
    else -> USAGE_NOTICE_NOT_FILED_LINE
}

internal const val USAGE_NOTICE_EXPANDER: String = "What is recorded, and what is not"

internal fun usageConsentHeading(state: String): String = when (state) {
    USAGE_CONSENT_GRANTED -> "You have agreed to this being recorded"
    USAGE_CONSENT_REFUSED -> "You declined; nothing is recorded"
    else -> "Nobody has asked you about this yet"
}

internal fun usageConsentDetail(record: UsageConsentRecordDto): String? {
    val moment = usageMoment(record.at) ?: return null
    val circumstance = when (record.basis) {
        USAGE_BASIS_REQUIRED_AT_SIGN_IN ->
            " — at sign-in, as a condition of using the platform"
        USAGE_BASIS_OFFERED_IN_SETTINGS -> " — freely, from this settings screen"
        else -> ""
    }
    val version = record.version?.takeIf { it.isNotBlank() }?.let { ", against notice $it" } ?: ""
    return "Recorded $moment$circumstance$version."
}

internal fun usageDecisionLine(row: UsageConsentDecisionDto): String {
    val answer = when (row.decision) {
        USAGE_CONSENT_GRANTED -> "Agreed"
        USAGE_CONSENT_REFUSED -> "Declined"
        USAGE_CONSENT_NOT_RECORDED -> "Not recorded"
        else -> row.decision.ifBlank { "Recorded" }
    }
    val where = when (row.basis) {
        USAGE_BASIS_REQUIRED_AT_SIGN_IN -> " at sign-in"
        USAGE_BASIS_OFFERED_IN_SETTINGS -> " in settings"
        else -> ""
    }
    val ticked = usageMoment(row.recordedAt)
    val heard = usageMoment(row.createdAt)
    val when_ = when {
        ticked != null && heard != null && ticked != heard ->
            " on $ticked (received on $heard)"
        ticked != null -> " on $ticked"
        heard != null -> " on $heard"
        else -> ""
    }
    val version = row.noticeVersion?.takeIf { it.isNotBlank() }?.let { ", notice $it" } ?: ""
    return "$answer$where$when_$version."
}

internal const val USAGE_NO_DECISIONS_LINE: String =
    "Nothing yet — you have not been asked. Your answers and their dates appear here."

internal const val USAGE_WITHDRAW_LABEL: String = "Withdraw and delete what is stored"

internal const val USAGE_WITHDRAW_CONFIRM: String =
    "Stops recording now and deletes what's stored. It does not sign you out and nothing else changes. Your earlier agreement stays in your own log."

internal const val USAGE_REGRANT_LABEL: String = "Agree again"
