package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * WHERE THE HANDSET KEEPS ONE WORKSHOP'S ANSWER TO "MAY ITS RECORDINGS LEAVE THE DEVICE", AND THE
 * WORDS THE QUESTION IS ASKED IN.
 *
 * The decision this feeds is [dwDictationLadder]'s and is pure; the enum it feeds is [DwTier3Consent].
 * This file is the storage shape, the fail-closed reading of a stored token, and the copy — one copy,
 * so the workshop screen, the language panel and the exhausted sentence cannot end up making three
 * different claims about one artisan's answer.
 *
 * ── WHY THIS LIVES IN THE DRAFT AND NOT IN A SERVER CACHE ──────────────────────────────────────
 *
 * Three per-workshop stores already exist on this device and they are not interchangeable:
 *
 *  * [WorkshopDraftStore] is the workshop as a document THE DEVICE OWNS — `filesDir`, an atomic temp →
 *    `fd.sync()` → `renameTo` write, and an unreadable draft is QUARANTINED, never deleted;
 *  * `DwReferenceStore` and `DwQuestionnaireStore` both DELETE what they cannot parse, and both say why
 *    in the same words: "this is a COPY of something the server still holds … A draft would never be
 *    treated this way."
 *
 * A consent belongs in the draft, on all three counts that separate them. It is AUTHORED on the device,
 * with the artisan standing there and no signal — so a store that can only mirror the server cannot
 * hold it at all. It is EVIDENCE ABOUT A NAMED PERSON, so "delete it and fetch it again" is exactly the
 * wrong recovery for a byte that will not parse. And it is the only store that works for a workshop
 * with NO SERVER ROW YET, which is every workshop created in a courtyard — the ones most likely to be
 * dictated into.
 *
 * It costs no [WORKSHOP_DRAFT_SCHEMA_VERSION] bump and no migration rung, and that is the ladder's own
 * rule rather than a shortcut: every field of [DraftConsent] is defaulted and the field itself is
 * defaulted on [WorkshopDraft], which is the purely additive case `ignoreUnknownKeys` plus an
 * all-defaulted model already handles losslessly in both directions. A rung is owed to a field that
 * MOVES, is RENAMED or changes MEANING, and spending a version number on an addition "trains the next
 * reader to skip the ladder for the change that genuinely needs one".
 *
 * ── AND WHY THE LADDER DOES NOT READ IT FROM DISK ──────────────────────────────────────────────
 *
 * `conditionsNow()` runs on the tap, on composition's main thread. Loading a draft there would be a
 * whole-22-stage JSON parse per field, taken under the ONE store-wide `Mutex` that every stage
 * auto-save also takes — hundreds of microphones serialising behind the very auto-saves that protect
 * the designer's text. So the stage screen, which has already read the draft once for its own reasons,
 * publishes the answer into [DwDictationRun] and the ladder reads it from there in place. The same shape
 * `DwReferenceStore` and `DwQuestionnaireStore` already keep: a map mirror written after the file.
 */

/**
 * One workshop's consent as the draft stores it.
 *
 * [decision] IS A STRING AND NOT [DwTier3Consent], deliberately. A draft is read by whatever build is on
 * the handset, and a serialised enum whose token this build does not know makes kotlinx throw — which
 * for this store means quarantining a fortnight of fieldwork because a newer build recorded a fourth
 * consent state. A string plus [dwTier3ConsentOf]'s fail-closed reading degrades to NOT_RECORDED
 * instead, which gates rather than permits, and loses nothing but one rung until the app is updated.
 */
@Serializable
data class DraftConsent(
    /** `"NOT_RECORDED"`, `"GRANTED"` or `"REFUSED"`; anything else reads as NOT_RECORDED. */
    val decision: String = DW_CONSENT_NOT_RECORDED,
    /**
     * WHEN THE ARTISAN ACTUALLY ANSWERED, on the clock of the device that was there.
     *
     * Kept apart from when the server heard it, because on this fleet the two differ by up to a
     * fortnight: a consent recorded in a courtyard reaches the server on the next sync. The server
     * stores this as the workshop's `dictationConsentAt` and the log row's `recordedAt`, and keeps its
     * own `createdAt` for the moment it heard. Collapsing them would date a signature to the day it was
     * filed.
     */
    val recordedAt: String? = null,
    /** Who took the answer down — the account, so the row can say a NAMED person did it. */
    val recordedById: String? = null,
    /** Their display name at the time, so the row reads without a lookup in a courtyard. */
    val recordedByName: String? = null,
    /**
     * True once the SERVER has acknowledged this answer.
     *
     * False is the ordinary state for a consent recorded offline, and it is not an error: the gate on
     * this phone is already honest, and the server checks its own column again on the upload. What it
     * drives is the sentence — a designer whose consent has not reached the server yet must be told
     * that dictation will still be refused up there until it does, rather than left to discover it one
     * upload at a time.
     */
    val synced: Boolean = false,
)

const val DW_CONSENT_NOT_RECORDED: String = "NOT_RECORDED"
const val DW_CONSENT_GRANTED: String = "GRANTED"
const val DW_CONSENT_REFUSED: String = "REFUSED"

// ---------------------------------------------------------------------------------------------
// The wire
// ---------------------------------------------------------------------------------------------

/**
 * What a designer's recorded answer is sent to the server as.
 *
 * ITS OWN ROUTE AND NEVER A PATCH OF THE WORKSHOP, which is the server's decision and worth knowing
 * here so nobody "simplifies" this into the header update beside it: `PATCH /{workshop_id}`'s writable
 * set is a hand-written tuple of key names copied in a loop, and it records neither who made a change
 * nor when. A value whose entire content is who said so and when cannot ride a generic field-copy loop.
 *
 * `NOT_RECORDED` is not sendable and the server refuses it: it is the absence of an answer, not an
 * answer somebody can record. Taking a consent back is recording REFUSED, which is a decision with a
 * next move behind it. This app therefore only ever sends GRANTED or REFUSED — see the two buttons.
 */
@Serializable
data class DwConsentDecisionRequest(
    val decision: String,
    val note: String? = null,
    /** When the ARTISAN answered, on this device's clock. See [DraftConsent.recordedAt]. */
    val recordedAt: String? = null,
)

/**
 * What comes back: the workshop's new answer, AND the whole decision log.
 *
 * TWO KEYS AND NOT ONE, and the route's own docstring says why the log is in the response at all:
 * "returning the log as well as the answer is what stops a client rendering this as a checkbox". The
 * shape is checked against the route (`record_dictation_consent` returns exactly `{"workshop":
 * workshop_summary(...) + dictationConsentByName, "decisions": [...]}`) rather than remembered, which is
 * the discipline [DwDictateDto] states and the defect [DwIdentityOcrDto] was written after — five keys
 * declared that the endpoint never sent, decoded silently to defaults, and a perfect read reported as a
 * failure.
 *
 * EVERY FIELD IS STILL DEFAULTED, because what this app depends on is the 2xx: the answer is already on
 * the device and the call exists to get it to the server, so a body that turns out to be shaped
 * differently must decode rather than turn a successful write into a failure.
 *
 * NOTHING HERE IS READ YET, and that is worth saying plainly rather than leaving as an implied promise.
 * The device already holds the answer it just sent, so the acknowledgement is what matters. The log is
 * declared because it is THERE — a surface that shows "granted on the 3rd, withdrawn on the 9th" is the
 * obvious next thing to build, and it should not have to change this DTO to do it.
 */
@Serializable
data class DwConsentDecisionDto(
    val workshop: DesignWorkshopDto? = null,
    val decisions: List<DwConsentDecisionRowDto> = emptyList(),
)

/**
 * One row of a workshop's consent history, as `dictation_consent.decision_payload` writes it.
 *
 * BOTH MOMENTS ARE CARRIED, and the pair is not a duplication: [recordedAt] is what the DEVICE said —
 * null when the answer was taken straight against the server — and [createdAt] is when the server heard
 * it. A fortnight of no signal makes them differ by a fortnight, and a reader who can see only one of
 * them cannot tell a consent given today from one given before the workshop was ever synced.
 */
@Serializable
data class DwConsentDecisionRowDto(
    val id: String = "",
    val designWorkshopId: String? = null,
    val decision: String = DW_CONSENT_NOT_RECORDED,
    val note: String? = null,
    val actorId: String? = null,
    val recordedAt: String? = null,
    val createdAt: String? = null,
)

/**
 * A stored token as the ladder's enum, FAILING CLOSED on anything unreadable.
 *
 * A null, a blank, a token from a newer build, a hand-edited draft: all NOT_RECORDED, which gates. It
 * does not throw, because a workshop whose consent cannot be read must still be openable, editable and
 * printable — the only thing that must not happen is a send.
 *
 * EXACT MATCH, WHICH IS THE SERVER'S `consent_of` TOKEN FOR TOKEN, and the two must not drift: a phone
 * that permitted what the server refuses would spend a six-megabyte upload per field to be told so. It
 * used to trim and upper-case, and that was leniency pointing the wrong way. Every writer of this value
 * is canonical — the server's Postgres enum, and this app's own [dwTier3ConsentToken] — so the only
 * strings the normalisation could ever have changed the answer for are ones nothing in this system
 * writes, and for those it turned a token the SERVER gates ("granted", " GRANTED ") into a grant on this
 * phone. In a function whose entire contract is to fail closed, that is the one direction the tolerance
 * may not run.
 */
fun dwTier3ConsentOf(token: String?): DwTier3Consent = when (token) {
    DW_CONSENT_GRANTED -> DwTier3Consent.GRANTED
    DW_CONSENT_REFUSED -> DwTier3Consent.REFUSED
    else -> DwTier3Consent.NOT_RECORDED
}

/** The stored token for a decision, for the draft and for the wire. */
fun dwTier3ConsentToken(consent: DwTier3Consent): String = when (consent) {
    DwTier3Consent.GRANTED -> DW_CONSENT_GRANTED
    DwTier3Consent.REFUSED -> DW_CONSENT_REFUSED
    DwTier3Consent.NOT_RECORDED -> DW_CONSENT_NOT_RECORDED
}

// ---------------------------------------------------------------------------------------------
// Two copies of one answer, and which of them is the artisan's latest
// ---------------------------------------------------------------------------------------------

/** What is to be done with the answer on this device and the answer on the server. */
enum class DwConsentMerge {
    /** They agree, or there is nothing to send. Leave the draft exactly as it is. */
    KEEP_DEVICE,

    /** This device holds the later answer and the server has not heard it. Send it. */
    PUSH_DEVICE,

    /** The server already holds this same answer. Stop offering to push it on every open. */
    MARK_SYNCED,

    /** The server holds an answer this device has not seen, and it is the one to honour. Learn it. */
    TAKE_SERVER,
}

/**
 * WHICH OF TWO COPIES OF ONE WORKSHOP'S ANSWER IS THE ARTISAN'S CURRENT ONE.
 *
 * ── THE DEFECT THIS FUNCTION EXISTS TO END, WHICH IS A WITHDRAWN CONSENT COMING BACK ────────────
 *
 * The first version of this reconciliation lived in the workshop screen and its rule was one line:
 * *"an answer this device holds that the server has NOT heard is newer by construction — it was
 * recorded here and has not been pushed — so it wins."* The premise is false, and the way it fails is
 * the worst way anything in this lane can fail:
 *
 *  1. On the 3rd, in a courtyard with no signal, a designer records GRANTED. It sits in the draft
 *     marked unsent.
 *  2. On the 5th the artisan changes their mind. Somebody records REFUSED — in the browser, or on a
 *     colleague's handset — and the server's column and its decision log both carry the withdrawal.
 *  3. On the 9th the first designer opens this workshop with signal. "Unsent wins" pushes the 3rd's
 *     GRANTED over the 5th's REFUSED, the log gains a row saying the consent was granted again, and
 *     the gate reopens. NOBODY IS TOLD. The artisan's withdrawal is undone by a phone that had been in
 *     a pocket, and the next dictation from that workshop goes to the provider.
 *
 * A consent that can be un-withdrawn by a stale device is not a consent. So the two copies are ordered
 * by the moment each of them says THE ARTISAN ANSWERED — the device's [DraftConsent.recordedAt] and
 * the server's `dictationConsentAt`, which is the same quantity on both sides — and the later one wins.
 *
 * ── AND WHEN THE TWO MOMENTS CANNOT BE COMPARED, THE ANSWER THAT GATES WINS ─────────────────────
 *
 * A draft written by an older build carries no `recordedAt`, and a stamp that will not parse is the
 * same situation. There is then no ordering to be had, and inventing one would be inventing the fact
 * this whole function turns on — so the tie is broken the way everything else about consent is broken:
 * closed. A REFUSED on either side wins over a GRANTED that cannot be shown to be newer. The cost of
 * that choice, stated rather than hidden: a genuine re-grant recorded by a build that did not stamp it
 * has to be recorded again from the workshop screen, which is one tap by the person who is standing
 * there anyway. The cost of the other choice is a voice that was withdrawn leaving the device.
 *
 * Two clocks are being compared and neither is authoritative, which is worth knowing rather than
 * papering over: the server refuses a device stamp more than fifteen minutes in its own future
 * (`dictation_consent.MAX_DEVICE_CLOCK_SKEW`), so what a server-stored moment can be wrong by is
 * bounded, and a phone whose clock is hours out cannot get its answer stored at all — it is told to fix
 * its date. Ordering by the artisan's moment is the only ordering either side records; the alternative
 * is no ordering, which is the defect above.
 *
 * [serverKnown] is false when the server was not reached, which is NOT the same as a server that
 * answered "nobody has asked": one of them leaves the device's answer untouched and the other is an
 * answer to be reconciled against.
 */
fun dwConsentMerge(
    onDevice: DraftConsent,
    serverDecision: String?,
    serverRecordedAt: String?,
    serverKnown: Boolean,
): DwConsentMerge {
    val here = dwTier3ConsentOf(onDevice.decision)
    // An answer of NOT_RECORDED is the absence of one, so it is never "unsent": there is nothing for
    // the server to hear and the route refuses to record it.
    val unsent = !onDevice.synced && here != DwTier3Consent.NOT_RECORDED
    if (!serverKnown) return if (unsent) DwConsentMerge.PUSH_DEVICE else DwConsentMerge.KEEP_DEVICE

    val there = dwTier3ConsentOf(serverDecision)
    // Agreement is an acknowledgement rather than a conflict — from the web, from a colleague's phone,
    // or from an earlier push whose response never arrived. Marking it synced is what stops this phone
    // re-pushing an answer that is already up there on every single open.
    if (there == here) return if (unsent) DwConsentMerge.MARK_SYNCED else DwConsentMerge.KEEP_DEVICE
    if (!unsent) return DwConsentMerge.TAKE_SERVER

    val mine = dwConsentMoment(onDevice.recordedAt)
    val theirs = dwConsentMoment(serverRecordedAt)
    return when {
        mine != null && theirs != null ->
            if (theirs > mine) DwConsentMerge.TAKE_SERVER else DwConsentMerge.PUSH_DEVICE
        // No ordering to be had. Fail closed: see the docstring.
        there == DwTier3Consent.REFUSED -> DwConsentMerge.TAKE_SERVER
        else -> DwConsentMerge.PUSH_DEVICE
    }
}

// ---------------------------------------------------------------------------------------------
// The words. One copy, read on the workshop screen where the answer is recorded.
// ---------------------------------------------------------------------------------------------

/**
 * THE QUESTION ITSELF, in the words it has to be put to the artisan in.
 *
 * It is on screen in full rather than summarised into a switch label, and that is the whole difference
 * between a consent and a checkbox. A designer holding this phone is the person who asks; if the screen
 * says only "Allow cloud dictation" then what the artisan is asked is whatever that designer improvises,
 * and no two artisans are asked the same thing. So the question is written once, here, in the plainest
 * available terms: what leaves the phone, who receives it, and what for.
 *
 * It names what is true of rung 2 and does not overclaim: the clip goes to this project's server and
 * from there to a transcription provider, and the words come back. It does not promise deletion
 * timelines this app cannot vouch for — what a provider retains is not this file's to assert.
 *
 * AND IT NO LONGER SAYS "THE SERVER KEEPS NO COPY OF THE AUDIO", WHICH WAS FALSE OF HALF OF WHAT THE
 * QUESTION ASKS ABOUT. The question is the user's own wording and covers "recordings AND dictation".
 * That is true of the sentence and false of the claim that followed it: `/dictate` stores nothing, but
 * a recording added to a workshop through the MEDIA flow is stored with the workshop and is written
 * down by the very same provider chain (`media_queue.transcribe_media_now` calls the same
 * `transcribe_audio_bytes`). Printing "the server keeps no copy of the audio" above a Yes button was
 * therefore telling an artisan something untrue about the interview recording sitting in the same
 * record — the one claim a consent screen cannot afford to get wrong. Both halves are now named apart,
 * and what this answer does not yet GOVERN is [DW_CONSENT_MEDIA_NOT_GATED]'s job to say.
 */
const val DW_CONSENT_QUESTION: String =
    "May recordings and dictation from this workshop leave the phone to be written down by a " +
        "transcription service outside it? The recording goes to this project's server and on to the " +
        "service that turns it into words. A passage dictated into a field is not kept afterwards — " +
        "the words come back and the server stores no audio; a recording attached to this workshop as " +
        "audio is kept with the workshop, because it is there to be listened to again. Ask the " +
        "artisan whose voice it is, and record their answer here."

/** The heading on the row, so it can be found by somebody who was told "it is on the workshop screen". */
const val DW_CONSENT_ROW_TITLE: String = "Sending recordings out to be written down"

/**
 * What the row says about the answer as it stands, and what it means for dictation on this phone.
 *
 * ONE FUNCTION FOR ALL THREE STATES, so the screen cannot describe a state the ladder gates
 * differently. Each sentence names the CONSEQUENCE and not merely the state: "not recorded" on its own
 * tells a designer nothing about why their microphone behaves differently on the stage they just left.
 */
fun dwConsentStateSentence(consent: DwTier3Consent, recorded: DraftConsent?): String = when (consent) {
    DwTier3Consent.GRANTED -> {
        val who = dwConsentAttribution(recorded)
        "Recordings from this workshop may be sent out to be written down$who. Dictation here uses the " +
            "same craft-aware transcription as the workshop's own recordings — the one that writes " +
            "“dabu” rather than “double”." + dwConsentSyncNote(recorded)
    }

    DwTier3Consent.REFUSED -> {
        val who = dwConsentAttribution(recorded)
        "Recordings from this workshop may NOT be sent out to be written down$who. No dictation from " +
            "it is sent to the transcription service. Dictation still works — it goes to the " +
            "phone's own speech recogniser instead, which has none of the craft vocabulary." +
            DW_CONSENT_PHONE_RECOGNISER_CAVEAT + DW_CONSENT_MEDIA_NOT_GATED +
            dwConsentSyncNote(recorded)
    }

    // NOT the same sentence as REFUSED, and the difference is the entire reason this is three states:
    // one is an answer and the other is the absence of one. Both gate; only this one is answered by
    // somebody asking.
    DwTier3Consent.NOT_RECORDED ->
        "Nobody has answered this for this workshop yet, so no dictation from it is sent to the " +
            "transcription service. Dictation still works — it goes to the phone's own speech " +
            "recogniser instead, which has none of the craft vocabulary and writes “double” where " +
            "the artisan said “dabu”." + DW_CONSENT_PHONE_RECOGNISER_CAVEAT +
            DW_CONSENT_MEDIA_NOT_GATED
}

/**
 * THE PART A REFUSAL DOES NOT COVER, SAID ON THE SCREEN WHERE THE REFUSAL IS RECORDED.
 *
 * A consent that gated only what this app posts, while the phone's own dictation quietly handed the same
 * voice to Google, would be a consent in name. The gate is genuinely narrower than the question sounds:
 * plan §6 answer 3 makes the recorded answer govern rung 2 — this project's server and the transcription
 * provider behind it — and says nothing about the platform recogniser the phone falls back to, which on
 * most handsets in this fleet is a NETWORK service belonging to Google.
 *
 * So it is stated here rather than left for somebody to discover, and stated on the one screen where a
 * person is making the decision. WHETHER A REFUSAL SHOULD ALSO SUPPRESS THE PLATFORM RECOGNISER IS AN
 * OPEN DECISION, recorded in `DwDictationLadder`'s docstring and belonging to the user rather than to
 * this file: it would take dictation away entirely for the seventeen languages with no offline pack, in
 * exchange for a promise this app cannot otherwise make.
 *
 * The honest floor in the meantime: an INSTALLED offline pack is the only path that truly keeps the voice
 * on the device, and that is what the sentence points at.
 */
private const val DW_CONSENT_PHONE_RECOGNISER_CAVEAT: String =
    " One thing this answer does not cover: the phone's own dictation is Android's, and on most " +
        "handsets that means Google's speech service over the network. Only a downloaded offline " +
        "language pack keeps a voice on the phone entirely."

/**
 * THE SECOND THING A REFUSAL DOES NOT COVER, AND THE ONE THAT SENDS AUDIO OF THE SAME ARTISAN.
 *
 * ── THE FALSE SENTENCE THIS REPLACES ────────────────────────────────────────────────────────────
 *
 * Both gating sentences above used to read "this app sends nothing from it to the transcription
 * service". That is not true and could not be made true by anything in this lane. The consent column
 * is read in exactly one place on the server — the gate on `POST /{workshop_id}/dictate` — while a
 * recording ATTACHED to a workshop as audio goes through the media queue, which calls the same
 * `transcribe_audio_bytes` provider chain (`media_queue.transcribe_media_now`) and consults no consent
 * at all. So a designer who read that sentence, recorded a refusal, and then uploaded the interview
 * would have sent the very voice they had just been told nothing would be sent — and would have been
 * told so by this screen, on the strength of an answer they took in good faith.
 *
 * Widening the gate to the media flow is deliberately out of this lane's scope (it would change an
 * existing capability, and plan §6 answer 3 names rung 2). What is NOT optional is saying so on the
 * screen where the decision is made, with the one instruction that actually holds the line in the
 * meantime — because that instruction is a person's to follow, and nothing in this app enforces it.
 */
private const val DW_CONSENT_MEDIA_NOT_GATED: String =
    " And one thing this answer does not yet govern: a recording attached to this workshop as audio " +
        "is still uploaded and written down by the same outside service, because this answer was not " +
        "given the power to stop that. Until it is, do not attach a recording of anybody who has said " +
        "no."

/** " — recorded by Meera Joshi on 12 Aug 2026", or as much of it as is actually known. */
private fun dwConsentAttribution(recorded: DraftConsent?): String {
    val name = recorded?.recordedByName?.trim()?.takeIf { it.isNotEmpty() }
    val day = dwConsentDay(recorded?.recordedAt)
    return when {
        name != null && day != null -> " — recorded by $name on $day"
        name != null -> " — recorded by $name"
        // NOT "recorded by you": whoever is looking at this screen may not be who answered, and a phone
        // is handed between designers. An unattributed answer says so by saying nothing.
        day != null -> " — recorded on $day"
        else -> ""
    }
}

/**
 * The one thing a designer cannot see for themselves: this answer has not reached the server.
 *
 * Said because the gate is enforced twice. The ladder on this phone honours a GRANTED answer the moment
 * it is recorded, which is right — the artisan agreed, and the designer should not have to walk to
 * signal before dictating. But the SERVER checks its own column on the upload, so until the answer gets
 * there every rung-2 attempt is refused up there with the server's own sentence. A designer who is not
 * told that reads those refusals as the app ignoring the answer they just recorded.
 */
private fun dwConsentSyncNote(recorded: DraftConsent?): String =
    if (recorded == null || recorded.synced) {
        ""
    } else {
        " This answer has not reached the server yet, so dictation sent out may still be refused there " +
            "until this workshop next syncs with a connection."
    }

/**
 * A stored ISO stamp as a comparable instant, or null when it is not one.
 *
 * A DATE ALONE IS DELIBERATELY NOT ACCEPTED, unlike [dwConsentDay]'s third fallback. Printing "12 Aug
 * 2026" from a bare date is honest; ordering two consents by it is not — midnight is not when anybody
 * answered, and two answers on the same day would compare equal and hand the decision to the tie-break
 * rather than to the facts. Null means "no ordering available", which is a state [dwConsentMerge] has a
 * rule for.
 */
private fun dwConsentMoment(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

/** A stored ISO stamp as "12 Aug 2026", or null when there is nothing readable to print. */
private fun dwConsentDay(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val date = runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
        ?: return null
    return runCatching { date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")) }.getOrNull()
}

/**
 * What the account looking at this row may do about it — a SENTENCE for the account that may not.
 *
 * `can_run_design_workshops` = {DESIGNER, ADMIN, MASTER_ADMIN}, which is the set the server's own
 * consent route is gated on, named rather than approximated from the rank ladder for
 * [FieldPermissions.canRunDesignWorkshops]'s stated reason: a PROFESSOR outranks a designer and may not
 * run a workshop, so a threshold would put this control in front of a 403.
 *
 * NEVER A GREYED BUTTON, which is the pattern the viewers row on the same screen already sets: "a
 * greyed control refuses a tap without saying why, which is how somebody concludes the app is broken."
 */
const val DW_CONSENT_NOT_YOURS_TO_RECORD: String =
    "Only the designer running this workshop, or an administrator, can record this answer. Ask whoever " +
        "is running it to put the question to the artisan and record what they say — dictation here " +
        "uses the phone's own recogniser until they do."

/** The two buttons, worded as the artisan's answer rather than as a setting. */
const val DW_CONSENT_YES_LABEL: String = "They agreed — may be sent out"
const val DW_CONSENT_NO_LABEL: String = "They did not agree — keep it on the phone"

/**
 * What a designer reads the moment an answer has been taken down — and WHERE it actually landed.
 *
 * THREE OUTCOMES AND NOT TWO, because "saved" is a claim and one of the three cases would make it a
 * false one. The answer can be on this phone, on the server, or — if the draft write failed and the push
 * failed too — nowhere. A control that said "saved on this phone" in that last case would leave a
 * designer believing an artisan's answer was on record when nothing had kept it, and the next person to
 * find out would be whoever read the report.
 *
 * The unsynced case is not a failure and does not read as one: it is the ordinary state in a courtyard,
 * and what it names is the one thing a designer could not otherwise know — the server checks its own
 * copy, so dictation sent out may still be refused up there until this answer reaches it.
 */
fun dwConsentRecordedNote(
    consent: DwTier3Consent,
    synced: Boolean,
    storedOnDevice: Boolean = true,
    serverRefusal: String? = null,
): String {
    val what = when (consent) {
        DwTier3Consent.GRANTED -> "Recorded: recordings from this workshop may be sent out to be written down."
        DwTier3Consent.REFUSED -> "Recorded: recordings from this workshop may not be sent out."
        DwTier3Consent.NOT_RECORDED -> "Nothing was recorded."
    }
    return when {
        synced && storedOnDevice -> "$what The server has it."
        /*
          THE SERVER ANSWERED AND SAID NO, WHICH IS NOT THE SAME AS NOT REACHING IT.

          Without this arm the next one below was printed instead — "it goes to the server the next time
          this workshop is opened here with a connection" — which for an answer the server has REFUSED is
          a promise that will never come true, made again on every open. The one refusal a field phone
          can actually earn is a wrong date: the server declines a stamp more than fifteen minutes in its
          own future rather than correcting it, because a corrected moment is a fabricated fact about
          when somebody consented. Its sentence names the fix (the phone's clock), and it is written for
          the person holding the phone, so it is passed through verbatim rather than summarised.

          The gate on THIS phone still honours the answer, and that is said first so nobody reads this as
          the recording having been lost.
        */
        storedOnDevice && serverRefusal != null ->
            "$what It is saved on this phone and dictation here honours it. The server would not " +
                "record it, though, so dictation sent out from this workshop will go on being refused " +
                "up there until this is put right: $serverRefusal"
        serverRefusal != null ->
            "This phone could not save that answer and the server refused it, so nothing was " +
                "recorded: $serverRefusal"
        // The server has it and this phone does not, so the answer is safe but this handset may ask
        // again after a restart. Said rather than hidden: it is the one state where a designer might
        // reasonably be surprised to see the question a second time.
        synced -> "$what The server has it, but this phone could not keep its own copy — it may ask you " +
            "again after a restart."
        storedOnDevice -> "$what It is saved on this phone and goes to the server the next time this " +
            "workshop is opened here with a connection."
        else -> "This phone could not save that answer and the server has not been told either, so " +
            "nothing was recorded. Try again, and if it keeps failing tell whoever runs the server " +
            "before dictating anything from this workshop."
    }
}
