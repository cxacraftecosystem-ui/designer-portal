package com.designprototype.workshop.ui

import com.designprototype.workshop.data.USAGE_BASIS_OFFERED_IN_SETTINGS
import com.designprototype.workshop.data.USAGE_BASIS_REQUIRED_AT_SIGN_IN
import com.designprototype.workshop.data.USAGE_CONSENT_GRANTED
import com.designprototype.workshop.data.USAGE_CONSENT_NOT_RECORDED
import com.designprototype.workshop.data.USAGE_CONSENT_REFUSED
import com.designprototype.workshop.data.UsageConsentDecisionDto
import com.designprototype.workshop.data.UsageConsentRecordDto
import com.designprototype.workshop.data.usageMoment

/**
 * EVERY SENTENCE THIS HANDSET OWNS ABOUT USAGE RECORDING — and, far more importantly, the boundary
 * marking which sentences it does NOT own.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE LINE THIS FILE DRAWS
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * **The server owns the CONSENT.** What is collected, what is not, that agreeing is required, what a
 * duration is not, who may read what, what a withdrawal does, and the retention answer all arrive in
 * `GET /usage/consent/notice`, computed from the collection policy actually in force. Not one of
 * those sentences is written here, and none may ever be. `services/usage.consent_notice` says why in
 * as many words: *"Two sign-in screens and two settings cards render from this payload. The
 * alternative — the copy written once in TSX and again in Kotlin — is how one decision comes to be
 * described two ways, and here that would not be an inconsistency but two different consents."*
 *
 * **This client owns its own STATE.** What it is doing, what it could not reach, and what a person
 * may do next. Those are facts about a phone in a courtyard and no server can know them.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT IS A PLAIN FILE WITH NO COMPOSE IN IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The same argument [AccessRefusalChrome] makes, and for a closely related reason. The states below
 * are mostly UNREACHABLE ON A DESK: a read that failed while online and a device that has never had
 * a connection look identical with four bars of Wi-Fi, and the sentence a designer reads while
 * blocked at a sign-in screen is by definition read by somebody who cannot get in. A `when` block
 * inside a composable can only be checked by looking at it, and "looking at it" is exactly what
 * missed the defect the six sentences in [WorkshopOptions] were written to end.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * ON REUSING THE SIX SHARED SENTENCES, AND THE TWO THAT COULD NOT BE REUSED
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `WorkshopOptions.kt` fixes six sentences for an empty list and rules that "Nothing outside this
 * module may write a seventh." This file is inside that module and honours the rule as it is meant:
 * the SHAPE — six causes, six different next moves, told apart by a type rather than by an empty
 * `List` — is copied exactly, and [loadingListLine] is reused verbatim because it is true here word
 * for word.
 *
 * Two of the six are NOT reused, and the reason is that both were written for a PICKER on a FORM and
 * say so in clauses that are false on a read-only screen:
 *
 *  * [offlineListLine] ends *"Connect once and the list is kept on the device from then on."* No
 *    usage figure is ever kept on this device — see [UsageRecordingScreen]'s own note on why a
 *    person's trail must not be cached — so that clause promises something this feature deliberately
 *    does not do.
 *  * [couldNotListLine] ends *"Nothing you have entered is at risk — this record can be saved
 *    without it."* There is nothing to enter and nothing to save on any of these screens, and a
 *    reassurance about typing that a reader has not done reads as a screen quoting somebody else's
 *    UI at them.
 *
 * So [usageOfflineLine] and [usageCouldNotReadLine] restate those two facts for a screen that reads
 * rather than collects, and they keep the clause that is the whole point of the originals: **"That
 * is not a claim that there are none."** Everything else on the screen is saying the list is empty;
 * one clause has to be doing the work of saying what the emptiness means.
 */

// ---------------------------------------------------------------------------------------------
// What happened when a usage screen asked for its figures
// ---------------------------------------------------------------------------------------------

/**
 * The three answers a read can be looking at, told apart by a type.
 *
 * Mirrors [WorkshopListState] arm for arm, for the reason that file states: "the read has not
 * answered yet", "the read failed" and "the read answered and the answer is none" are three
 * different facts with three different next moves, and a bare `List<T>` spells all three
 * `emptyList()`. That single collapse is what lets a failed fetch on a phone with no signal render
 * as a confident claim that nothing was recorded — which on THESE screens would be a confident claim
 * that the platform holds no record of somebody, made by a screen whose entire job is to tell them
 * what it holds.
 */
sealed interface UsageReadState {

    /** Asked for, not yet answered. */
    data object Loading : UsageReadState

    /**
     * The read did not answer. WHY is not carried in here on purpose — the caller passes `online`
     * to [usageReadNotice], and the classification of "offline" is `WorkshopRepository.isTransient`
     * rather than a second idea of what offline means on this handset.
     */
    data object Failed : UsageReadState

    /** The read answered. [count] rows came back, which may legitimately be zero. */
    data class Answered(val count: Int) : UsageReadState
}

/**
 * OFFLINE — this device could not reach the server at all.
 *
 * Keeps [offlineListLine]'s load-bearing middle clause and drops its trailing promise; see this
 * file's header for why that promise is false here.
 */
internal fun usageOfflineLine(noun: String): String =
    "This device has no connection, so the $noun could not be read. That is not a claim that there " +
        "are none — nothing about them is kept on this phone, so this screen has nothing to show " +
        "until it can ask the server. Try again where there is a signal."

/**
 * ONLINE AND THE READ FAILED — the server answered with something other than the figures.
 *
 * Deliberately a DIFFERENT sentence from [usageOfflineLine], on the same rule that separates
 * [scopedEmptyLine] from [unscopedEmptyLine]: one names a phone with no signal and the other names a
 * server that refused, and the person's next move is different. Telling somebody to go and find a
 * signal when they already have four bars is the walk up the hill that `outboxDeviceBanner` exists
 * to stop sending people on.
 */
internal fun usageCouldNotReadLine(noun: String): String =
    "The $noun could not be read, so this is not showing what was recorded. This phone has a " +
        "connection, so trying again may work; if it keeps failing, the figures are still on the " +
        "server and nothing has been lost."

/**
 * The sentence for the state a usage read is actually in, or null when it has nothing to say.
 *
 * @param noun the plural, lower-case, mid-clause: "usage figures", "recorded requests", "decisions".
 * @param online what the outbox's own classification thinks, NOT a fresh network probe. A second
 *   idea of what offline means is how one screen calls a dead tunnel a server fault while the queue
 *   behind it calls the same throwable worth retrying.
 * @param emptyLine what to say when the read ANSWERED and the answer was none. The caller supplies
 *   it because only the caller knows whether an empty answer means "you have not used the app in
 *   this window" or "nobody was asked, so nothing carries a name" — the same reason
 *   `SearchableSelectField.emptyMessage` is the caller's string.
 */
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

// ---------------------------------------------------------------------------------------------
// The sign-in door
// ---------------------------------------------------------------------------------------------

/**
 * What this client is prepared to do at the sign-in screen, given whether it has a notice to show.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE DECISION THIS TYPE RECORDS, WHICH IS THE MOST CONSEQUENTIAL ONE IN THE FEATURE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The requirement is a blocking, required agreement before a person may use the product. The naive
 * reading of that is "disable the sign-in buttons until the box is ticked, always". Applied without
 * a second thought it produces a **fleet-wide lockout with no way past it**: the notice is computed
 * on the server from the running collection policy, so one bad deploy can break `GET
 * /usage/consent/notice` while `POST /auth/login` beside it keeps working — and every handset in
 * every village then meets a permanently disabled button, on the one screen that has no other
 * controls, held there by a checkbox whose text never arrives.
 *
 * So the rule is: **the tick is required whenever the question can be put, and the question can
 * almost always be put.**
 *
 *  * [Blocking] — a notice is in hand (fetched, or the copy this device kept from last time). The
 *    box must be ticked before either sign-in button does anything.
 *  * [AskLater] — no notice, from any source. The buttons work, the screen says plainly that the
 *    question could not be loaded and will be asked as soon as it can be, and the BLOCKING half then
 *    happens after sign-in, where a token exists and `GET /usage/consent` carries the notice inline
 *    as a second, independent source of the same text. Nobody reaches the product without answering;
 *    the enforcement simply moves one screen later, to the only place it can still be escaped from.
 *  * [Waiting] — the fetch is in flight and this device has no stored copy. Momentary; the buttons
 *    wait rather than committing to either of the above.
 *
 * `UsageNoticeStore` is what makes [AskLater] rare rather than routine, and the server explicitly
 * blessed it: an unrecognised `noticeVersion` is accepted and stored verbatim *"because refusing
 * would lock out a handset holding a cached notice, and the honest record of 'they agreed to THAT
 * text' is the version they saw."*
 */
enum class UsageDoorPolicy {
    Waiting,
    Blocking,
    AskLater,
}

/** Which of the three the sign-in screen is in. [noticeReady] is "a usable notice is in hand". */
internal fun usageDoorPolicy(noticeReady: Boolean, stillFetching: Boolean): UsageDoorPolicy = when {
    noticeReady -> UsageDoorPolicy.Blocking
    stillFetching -> UsageDoorPolicy.Waiting
    else -> UsageDoorPolicy.AskLater
}

/**
 * WHY SIGN-IN IS NOT AVAILABLE RIGHT NOW, or null when it is.
 *
 * **THIS SENTENCE IS THE ACCESSIBILITY REQUIREMENT AND NOT A GARNISH.** A disabled `Button` is
 * announced by TalkBack as "disabled" and nothing else; somebody who cannot see the checkbox above
 * it is told a control does not work and given no reason and no remedy. So the screen draws this in
 * a polite live region beside the buttons, which is how a change to it is spoken the moment it
 * changes rather than only when the reader happens to swipe onto it.
 *
 * It is also the reason the tick and the buttons are not the only things on that screen: a control
 * that refuses a tap without saying why is how somebody concludes the app is broken — the ruling
 * `DwDictationConsent` states for its own greyed control, applied at the door.
 */
internal fun usageSignInBlockedReason(
    policy: UsageDoorPolicy,
    agreed: Boolean,
    online: Boolean,
): String? = when {
    policy == UsageDoorPolicy.Waiting && online ->
        "Reading the recording notice you have to agree to before signing in…"
    policy == UsageDoorPolicy.Waiting ->
        "This phone has no connection. Signing in needs one, and so does reading the recording " +
            "notice you have to agree to first."
    policy == UsageDoorPolicy.Blocking && !agreed ->
        "You cannot sign in until you tick the box above. Open \"What is recorded\" to read what " +
            "you are agreeing to first."
    else -> null
}

/**
 * What the screen says when it could not put the question at the door.
 *
 * It says the thing a person actually needs to know — that they are not being let past the question,
 * only past this screen — because the alternative is somebody signing in, meeting the same question
 * one screen later, and concluding the app asked them twice.
 */
internal const val USAGE_ASK_LATER_LINE: String =
    "The recording notice you have to agree to could not be loaded on this screen, so you are being " +
        "asked after signing in instead. You will not be able to use the app until you have answered."

/** The label beside the checkbox at the door. Short, because the SERVER's `requiredSentence` is
 *  printed directly under it and is the sentence that carries the weight. */
internal const val USAGE_AGREE_LABEL: String = "I agree to this being recorded"

/** The expander that opens the notice. Worded as what is behind it rather than as "Details", so a
 *  person can decide whether to open it without opening it. */
internal const val USAGE_NOTICE_EXPANDER: String = "What is recorded, and what is not"

// ---------------------------------------------------------------------------------------------
// The recorded answer, in words
// ---------------------------------------------------------------------------------------------

/**
 * The heading over this account's own answer. **Three states, three headings, never a boolean.**
 *
 * NOT_RECORDED is not a refusal and must never be worded as one: it is every account that has not
 * been asked, and telling somebody their answer was "no" when nobody put the question is the exact
 * failure the three-state enum exists to prevent. Nor is it worded as a grant.
 */
internal fun usageConsentHeading(state: String): String = when (state) {
    USAGE_CONSENT_GRANTED -> "You have agreed to this being recorded"
    USAGE_CONSENT_REFUSED -> "You have declined, and nothing is being recorded"
    // Every unknown token lands here WITH NOT_RECORDED, deliberately: an answer this build cannot
    // read is not evidence that one was given. `resolve_consent` fails the same way on the server —
    // never to GRANTED — and a client that guessed the other way would tell somebody they had agreed
    // to something on the strength of a token it did not recognise.
    else -> "Nobody has asked you about this yet"
}

/**
 * The line under the heading: when, and in what circumstances.
 *
 * **THE CIRCUMSTANCE IS PRINTED, NOT JUST THE ANSWER.** "You agreed" over a turnstile is a sentence
 * that misleads by omission; "you agreed at sign-in, where agreeing was a condition of using the
 * platform" is what actually happened, and this screen is the one place the person can see it. The
 * basis column exists on the server for exactly this reason, and a client that stored it and did not
 * show it would be keeping an honest record and publishing a misleading one.
 */
internal fun usageConsentDetail(record: UsageConsentRecordDto): String? {
    val moment = usageMoment(record.at) ?: return null
    val circumstance = when (record.basis) {
        USAGE_BASIS_REQUIRED_AT_SIGN_IN ->
            " — at sign-in, where agreeing was a condition of using the platform"
        USAGE_BASIS_OFFERED_IN_SETTINGS -> " — freely, from this settings screen"
        else -> ""
    }
    val version = record.version?.takeIf { it.isNotBlank() }?.let { ", against notice $it" } ?: ""
    return "Recorded $moment$circumstance$version."
}

/**
 * One row of the decision log, as a sentence.
 *
 * **BOTH CLOCKS APPEAR WHEN THEY DIFFER, AND ONLY THEN.** [UsageConsentDecisionDto.recordedAt] is
 * when the box was ticked on a device; `createdAt` is when the server heard. On this fleet the two
 * can be a fortnight apart, and a log that showed one of them would date a signature to the day it
 * was filed. Printing both unconditionally would put a redundant clause on every row answered
 * straight against the server, where `recordedAt` is null by design and a copy of `createdAt` would
 * read as "a device reported this" — which would be false.
 */
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
            " on $ticked (this server heard it on $heard)"
        ticked != null -> " on $ticked"
        heard != null -> " on $heard"
        else -> ""
    }
    val version = row.noticeVersion?.takeIf { it.isNotBlank() }?.let { ", notice $it" } ?: ""
    return "$answer$where$when_$version."
}

/**
 * What an empty decision log means, which is NOT "nothing happened".
 *
 * The log is written by the server on every answer, so an account with a recorded answer always has
 * at least one row. An empty one therefore means the question has never been put — which is the same
 * fact NOT_RECORDED carries, said in the place a reader is looking for a history.
 */
internal const val USAGE_NO_DECISIONS_LINE: String =
    "There is nothing here yet because you have not been asked. The first time you answer, the " +
        "answer and its date are kept here — including if you later change it."

/** The withdrawal control. Says what it does AND what it does not cost, because the second half is
 *  what makes it a real choice rather than a button nobody dares press. */
internal const val USAGE_WITHDRAW_LABEL: String = "Withdraw and delete what is stored"

/**
 * The confirmation before a withdrawal.
 *
 * It states the one thing a person is most likely to be afraid of and the one thing they are most
 * likely to misunderstand: it does not sign them out or take anything away, and it does not erase
 * the dated record that they had agreed. The second half matters because somebody expecting a
 * withdrawal to remove every trace would otherwise read their own decision log afterwards and
 * conclude the withdrawal did not work.
 */
internal const val USAGE_WITHDRAW_CONFIRM: String =
    "This stops the recording immediately and deletes the requests already stored for your account. " +
        "It does not sign you out and it takes nothing away — everything in the app keeps working " +
        "exactly as it does now. The dated record that you had agreed stays in your own log below, " +
        "because a withdrawal must not rewrite the answer the earlier recording was made under."

/** Re-granting after a withdrawal. Offered because a refusal that cannot be changed is a state, not
 *  a choice — and the server records it as OFFERED_IN_SETTINGS, which is the free half of the
 *  vocabulary the turnstile at the door depends on. */
internal const val USAGE_REGRANT_LABEL: String = "Agree again"
