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
 * THE DOOR IS ONE LINE NOW, AND THIS IS THE RECORD OF WHAT WAS TAKEN OFF IT.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT USED TO BE HERE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A `UsageDoorPolicy` enum with three arms, a `usageSignInBlockedReason` with four, and an
 * ask-later paragraph. All of it existed to answer ONE question — *what does the sign-in screen do
 * when it cannot show the recording notice?* — and the answer it gave was "let the person through,
 * because a checkbox whose text never arrives is a fleet-wide lockout on the one screen that has no
 * other controls". That reasoning was correct while **the checkbox WAS the notice**.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THE QUESTION NO LONGER ARISES
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Since 2026-08-30 the box agrees to the TERMS AND CONDITIONS, and [TermsScreen] is nine constants
 * compiled into this binary. There is no fetch that can fail to produce them, so the tick can always
 * be put and can always be given — which removes the lockout the old escape hatch was built for, and
 * removes with it the reason to let anybody past the door unasked. The gate is now the tick and
 * nothing else, exactly as `frontend/app/login/page.tsx` states it: `blocked = !agreed`.
 *
 * **Anyone reintroducing an escape hatch must first say what it is escaping FROM.** The old one is
 * not a safety net that was removed; it is a net for a fall that can no longer happen, and leaving
 * it in would have been a front door that a dead endpoint walks straight through.
 *
 * The notice is still fetched, because the answer is filed against its version — see
 * [usageAnswerAtTheDoor]. When it is missing nothing is filed, [USAGE_NOTICE_NOT_FILED_LINE] says
 * so in one line, the server's gate goes on reading `required`, and [UsageConsentGateScreen] asks
 * again one screen later. Nobody is waved through; only the FILING is deferred.
 */

/** The half-sentence beside the tick. It ends where the link begins, so the two together read as
 *  one line — see `UsageConsentGate.UsageAgreeRow` for why they are two controls and not one. */
internal const val USAGE_AGREE_LABEL: String = "I agree to the"

/** The underlined, tappable half. Lower case and mid-sentence on purpose: it is the tail of
 *  [USAGE_AGREE_LABEL] and not a heading. [TERMS_TITLE] is the heading. */
internal const val USAGE_TERMS_LINK: String = "terms and conditions"

/**
 * WHY SIGN-IN IS NOT AVAILABLE, or null when it is.
 *
 * **THIS SENTENCE IS THE ACCESSIBILITY REQUIREMENT AND NOT A GARNISH**, and that has not changed
 * with the copy. A disabled `Button` is announced by TalkBack as "disabled" and nothing else;
 * somebody who cannot see the checkbox above it is told a control does not work and given no reason
 * and no remedy. So the screen draws this in a polite live region beside the buttons.
 *
 * IT NAMES THE CONTROL, where the web's own live region says only "Required to sign in." The web can
 * afford the shorter sentence because it is bound to the checkbox by `aria-describedby` and is read
 * out as part of the box itself; a Compose live region is a separate node with no such binding, so
 * dropping the noun would leave a reader hearing "Required to sign in" with nothing to act on. §16's
 * rule for a platform that genuinely differs — pick the equivalent shape, and say so — applied to
 * four words.
 */
internal const val USAGE_TICK_TO_SIGN_IN: String = "Tick the box to sign in."

/** The same sentence for [UsageConsentGateScreen], where the next step is not a sign-in. */
internal const val USAGE_TICK_TO_CONTINUE: String = "Tick the box to continue."

/**
 * The tick was given and there is no notice to file it against.
 *
 * It says the thing a person actually needs to know — that they are not being let past the
 * question, only past this screen — because the alternative is somebody agreeing here, meeting the
 * same question one screen later, and concluding the app asked them twice.
 */
internal const val USAGE_NOTICE_NOT_FILED_LINE: String =
    "The recording notice could not be read, so this answer is not filed yet. You will be asked " +
        "again after signing in."

/**
 * The one line under the tick, or null when there is nothing to say.
 *
 * @param agreed the box is ticked.
 * @param noticeReady a usable notice is in hand, from the server or from this device's stored copy.
 * @param stillFetching the first fetch is in flight and nothing was stored. Silent, deliberately:
 *   it lasts a second or two on a fresh install and a line that appears and vanishes on its own is
 *   read as a fault. It blocks nothing either — see this section's header.
 */
internal fun usageDoorHint(
    agreed: Boolean,
    noticeReady: Boolean,
    stillFetching: Boolean,
): String? = when {
    !agreed -> USAGE_TICK_TO_SIGN_IN
    noticeReady || stillFetching -> null
    else -> USAGE_NOTICE_NOT_FILED_LINE
}

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
