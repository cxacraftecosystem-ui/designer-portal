package com.designprototype.workshop.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * THE CLIP RUNG 2 SENDS, AND THE ONE ROUTE IT SENDS IT DOWN.
 *
 * The decision about WHICH rung to use is [dwDictationLadder] and is pure. This file is the half
 * that has to touch the platform: a microphone, a file, and the multipart POST. It deliberately
 * carries no Compose — the control in `ui/designworkshop/DwDictation.kt` owns the drawing and the
 * lifecycle, this owns the recorder — so the shape of a dictated clip can be reasoned about without
 * reading a composable.
 */

/**
 * The wire shape of `POST /design-workshops/{id}/dictate` — and of its id-less sibling, unchanged.
 *
 * THESE FIELD NAMES ARE THE SERVER'S, CHECKED AGAINST IT RATHER THAN REMEMBERED — the shared body of
 * the two dictation routes in backend/app/api/routes/design_workshops.py
 * (`_transcribe_one_dictation`) returns exactly `status`, `text`, `provider`, `languageHint` and
 * `message`, plus the four keys of `dictation_cap.allowance_payload` spread into the same object, and
 * nothing else. The same discipline, and the same reason, as [DwIdentityOcrDto]: that DTO once declared
 * five keys the endpoint had never sent, and because this client decodes with `ignoreUnknownKeys = true`
 * the mismatch did not throw — every field simply defaulted, and a PERFECT read was reported to the
 * designer as a failure.
 */
@Serializable
data class DwDictateDto(
    /**
     * COMPLETED, EMPTY, FAILED or RATE_LIMITED, as `ai.transcribe_audio_bytes` resolved the chain.
     *
     * UNAVAILABLE never arrives here: the route turns that one into a 503 before it reaches the wire,
     * which is why [DW_DICTATION_NOT_CONFIGURED] is shown from an exception rather than from a status.
     */
    val status: String = "",
    /**
     * The plain text, ready to go straight into the field.
     *
     * Never the speaker-labelled Markdown the interview transcripts carry — the route strips that on
     * purpose, because "**Speaker 1:**" is not something a designer wants to delete by hand from a
     * process description.
     */
    val text: String = "",
    /** Which provider in the chain answered. Diagnostic; not shown beside the words. */
    val provider: String? = null,
    /**
     * THE TAG WE SENT, ECHOED BACK. It does not steer anything, and this comment exists so nobody
     * later assumes it does.
     *
     * The route declares `languageHint` as a form field, returns it in the payload, and passes it to
     * no provider — `transcribe_audio_bytes` is called with the bytes, the filename and the MIME type
     * only. Deepgram is called with `language=multi` (ai.py:360) because a workshop is code-switched
     * Hindi and English mid-sentence, so the chain is not being told a language by anybody today.
     * Sending the tag anyway costs nothing and is what makes the field mean something the day the
     * server does read it.
     */
    val languageHint: String? = null,
    /** The provider chain's own account of a failure, worth showing verbatim when it is there. */
    val message: String? = null,

    /*
      ── THIS DESIGNER'S DAILY ALLOWANCE, CARRIED ON EVERY SUCCESSFUL DICTATION ─────────────────

      Four keys added by the cap in plan §6 answer 1, and the reason they are on the 200 rather than
      only in the refusal is the same reason the ladder mirrors the byte cap instead of discovering it:
      a phone that can only learn a ceiling by being refused has to spend a six-megabyte upload to
      learn it, and then another tomorrow. With these, the handset knows from the LAST dictation
      whether the next one is worth attempting, and can fall back to its own recogniser with zero
      bytes uploaded ([DwDictationAllowance]).

      THE NAMES ARE THE SERVER'S, checked against `dictation_cap.allowance_payload` rather than
      remembered — the discipline this DTO's own docstring sets out, and the defect it was written
      after: five keys declared here that the endpoint had never sent, decoded silently to defaults
      because this client uses `ignoreUnknownKeys`, and a perfect read reported as a failure.

      ALL FOUR ARE DEFAULTED AND THREE ARE NULLABLE, which is what makes an older deployment — one
      that predates the cap and sends none of them — decode as "this phone has not been told a
      ceiling" rather than as an allowance of zero. `dictationsLimit` and `dictationsRemaining` are
      ALSO null on an uncapped deployment, deliberately, because "no ceiling" and "none left" are
      different facts and only one of them may be printed as a countdown.
    */
    /** The configured ceiling, or null when this deployment is uncapped. 0 means dictation is off. */
    val dictationsLimit: Int? = null,
    /** How many the designer has spent on [dictationDay]. Diagnostic; no sentence names it. */
    val dictationsUsed: Int? = null,
    /** How many are left; null when there is no ceiling to count down from. */
    val dictationsRemaining: Int? = null,
    /**
     * The SERVER'S India-time day these numbers are about, `"YYYY-MM-DD"`.
     *
     * THE KEY THE WHOLE MIRROR TURNS ON. The handset stores the day the server named and compares it
     * against its own reckoning of today ([dwDictationIstDay], the same fixed +05:30), so a cached
     * "spent" whose day has passed is stale rather than authoritative. Without it there is no
     * allowance worth storing at all — see [dwDictationAllowanceOf].
     */
    val dictationDay: String? = null,
)

/**
 * THE ROUTE ITSELF SAID IT HAS NO TRANSCRIPTION PROVIDER — as opposed to a 503 from in between.
 *
 * WHY A TYPE AND NOT A STATUS CODE. The dictation route raises 503 when `transcribe_audio_bytes`
 * answers UNAVAILABLE, and this client used to read any 503 as that. But `ApiClient` names 502, 503 and
 * 504 as the codes CloudFront returns "under transient origin trouble", and the dictation POST is
 * deliberately not on its safely-retriable list, so a gateway 503 reaches this app unretried and identical BY CODE
 * to the route's own answer. Conflating them cost more than one wrong sentence: the 503 is remembered
 * for the whole run ([DwDictationRun.serverSaidNotConfigured]), so ONE BLIP retired the craft-aware
 * rung until the app was restarted and told every designer to go and ask an administrator to
 * configure something that was already configured.
 *
 * They are told apart by the BODY, in [WorkshopRepository.designWorkshopDictate]: FastAPI writes a
 * `detail`, and a gateway writes its own error page.
 *
 * [detail] IS THE EVIDENCE, NOT THE COPY. It is carried so a crash log says which of the two this
 * was, and it is deliberately NOT what the designer reads: the chain writes "Transcription
 * unavailable: configure ELEVENLABS_API_KEY, DEEPGRAM_API_KEY, or OPENAI_API_KEY", which names three
 * environment variables at somebody standing in a courtyard. [DW_DICTATION_NOT_CONFIGURED] says the
 * same fact and names the move that is actually theirs.
 */
class DwDictationNotConfigured(val detail: String) : Exception(detail)

/**
 * THE CONSENT GATE REFUSED THIS CLIP — a 409 from `POST /design-workshops/{id}/dictate`.
 *
 * ── AND ONE OTHER 409 THE SAME ROUTE CAN RAISE, NAMED HERE BECAUSE THE NAME OF THIS TYPE HIDES IT ─
 *
 * The gate is what a 409 almost always is, but it is not the only one: BEFORE the gate runs,
 * `load_workshop_or_404(..., for_edit=True)` answers 409 "This workshop is deleted. Restore it before
 * editing." That one is reachable only by an admin, because the same helper turns everybody else away
 * with a 404 rather than confirm the id exists. It arrives here wearing this type's name, and it is
 * harmless BECAUSE OF [detail]: the designer reads the server's own sentence, which names the restore
 * rather than a consent question. The one place it is not harmless is a BODILESS such 409, where
 * [DW_DICTATION_CONSENT_REFUSED] would describe a deleted workshop as one whose answer is not a yes —
 * two improbabilities at once (a proxy that strips the body AND an admin dictating into a deleted
 * workshop), left as a stated residual rather than paid for by weakening the sentence the common case
 * reads. Telling the two apart would need a discriminator the 409 body does not carry.
 *
 * ── WHY THIS TYPE EXISTS AT ALL, WHEN THE LADDER ALREADY GATES ON CONSENT ───────────────────────
 *
 * BECAUSE THE PHONE'S ANSWER AND THE SERVER'S CAN DISAGREE, AND THE SERVER'S IS THE ONE THAT DECIDES.
 * The ladder reads a mirror published from a draft ([DwDictationRun.publishedWorkshop]); the route reads
 * its own column. Two ordinary field states put them at odds: a consent recorded in a courtyard that has
 * not been pushed up yet — `StageIndexScreen.dwPushUnsent` records that residual in as many words — and
 * an answer withdrawn in a browser since this handset last synced. In both, this phone offers rung 2 and
 * the server refuses it, which is the gate doing exactly the job it was moved onto this route to do.
 *
 * ── AND WHY THE SENTENCE IS THE SERVER'S ────────────────────────────────────────────────────────
 *
 * [detail] IS THE COPY, unlike [DwDictationNotConfigured.detail], and the difference is which audience
 * the server wrote for. The 503's message names environment variables at somebody standing in a
 * courtyard; the 409's is field copy by construction — `dictation_consent.gate_refusal` writes two
 * separate sentences, one for NOT_RECORDED and one for REFUSED, each naming a next move that can
 * actually work, and neither says "try again" because what changes a consent is a person deciding.
 * Composing our own here would mean choosing between those two states from a body that does not name
 * one, and telling a designer to go and ask a question that is already on record is how they learn to
 * stop reading these messages.
 *
 * NULLABLE, for [DwDictationCapRefused]'s reason: a 409 whose body was rewritten by something in
 * between carries no sentence, and the control then says only what a bodiless 409 proves — see
 * [DW_DICTATION_CONSENT_REFUSED].
 *
 * ── WHAT IS DELIBERATELY NOT DONE WITH IT, SAID PLAINLY BECAUSE IT HAS A COST ────────────────────
 *
 * IT IS NOT REMEMBERED. The 503 and the cap refusal are, because each is a fact about the deployment or
 * the rest of the day that any other field would otherwise buy again with its own six-megabyte upload.
 * This one is not, and the reason is that the only honest thing to remember would be "the server's
 * answer differs from this phone's" — which is a state neither [DwTier3Consent] nor the ladder has a
 * sentence for, and a phone that wrote down NOT_RECORDED here would be inventing a state the 409 never
 * named. The cost is stated rather than hidden: on a handset whose mirror says GRANTED while the server
 * says otherwise, every field dictated into pays one upload to be refused, and each refusal prints the
 * server's own sentence naming the screen that makes the two agree. The walk stops on the refusal, so
 * nothing is re-sent inside one tap.
 */
class DwDictationConsentRefused(val detail: String?) : Exception(detail)

/**
 * A 429 FROM THE DICTATION ROUTE — TWO DIFFERENT THINGS, TOLD APART BY THE BODY.
 *
 * THE CAP IS ENFORCED ON BOTH DICTATION ROUTES and is unaffected by rung 2 having moved onto the
 * workshop-scoped one: it is per DESIGNER and needs no workshop, which the server says in as many words
 * ("a ceiling that the older URL could walk around would be a ceiling in name only").
 *
 * WHY A TYPE AND NOT A STATUS CODE, for the second time in this file and for the same reason
 * [DwDictationNotConfigured] gives about the 503. Two things in this deployment answer 429 and they
 * want opposite handling:
 *
 *  * THE DAILY CAP. This designer's allowance for the server's India-time day is spent. It will not
 *    clear until midnight IST, so it is worth REMEMBERING — otherwise every one of the several
 *    hundred prose fields on the stage spends its own six-megabyte upload to be told the same thing.
 *  * `app/scale/rate_limit.py`, the courtesy backstop in front of the whole API — flag-gated off by
 *    default, and its own docstring calls it "NOT a security control". Its refusal is about THIS
 *    INSTANT: it carries `retryAfterSeconds` and answers "wait a moment and try again". Remembering
 *    that one would retire the craft-aware rung for the rest of the day over a burst of taps, which
 *    is the CloudFront-blip failure the 503 branch above exists to prevent, wearing a different code.
 *
 * TOLD APART BY SHAPE AND NOT BY ENGLISH. `dictationDay` is the cap's own key from
 * `dictation_cap.allowance_payload`; `retryAfterSeconds` is the limiter's. Matching prose would break
 * the first time somebody reworded a sentence, and this repository has already been bitten by matching
 * a provider's message rather than its status ([dwDictationServerAnswerSentence]).
 *
 * AND WHEN A BODY CARRIES NEITHER KEY, IT IS TREATED AS THE CAP. That is a decision with a cost, so it
 * is argued rather than assumed: the cap route is the only thing in this application that 429s a
 * dictation on purpose, and the alternative reading — "transient, remember nothing" — is the one whose
 * failure is unbounded, because it spends an upload per field for the rest of the day. Read as the cap,
 * a mistake costs the craft-aware rung until midnight IST while dictation goes on working through the
 * phone's own recogniser, and it says so in words. The bounded, visible, self-clearing error is the one
 * to prefer.
 *
 * AND THAT IS NOW THE PATH EVERY CAP REFUSAL TAKES, CONFIRMED AGAINST THE ROUTE RATHER THAN ASSUMED.
 * `_transcribe_one_dictation` raises its 429 with `detail=` a plain STRING and no allowance keys, and its
 * comment says why: "a client that shows `detail` verbatim would print a dictionary's repr at somebody in
 * a village", with the machine-readable copy on the 200 and on `GET /dictation-allowance` instead. So
 * [day] and [limit] are null for every cap refusal this deployment can produce, and it is the
 * neither-key reading above that identifies it — while `app/scale/rate_limit.py` does put
 * `retryAfterSeconds` at the top level of its own 429 body, which is the key that tells the two apart.
 * The two number fields are kept because they cost nothing and are what a later route would use to say
 * WHICH day it refused; nothing depends on them arriving.
 */
class DwDictationCapRefused(
    /** The server's own sentence for the person holding the phone, or null if the body carried none. */
    val detail: String?,
    /** The India-time day the refusal is about, when the body named one. See [DwDictateDto.dictationDay]. */
    val day: String?,
    /** The ceiling, when the body named one. Null is "not told", never "no ceiling". */
    val limit: Int?,
    /** The limiter's own key. Present means this was the courtesy backstop, not the cap. */
    val retryAfterSeconds: Int?,
) : Exception(detail) {
    /**
     * True only for a refusal that says WHEN to try again — the courtesy backstop, never the cap.
     *
     * The cap does not answer "in n seconds", because what it is waiting for is a calendar day. A body
     * that carries the allowance is the cap even if something also put a retry hint in it, so the day
     * is checked first.
     */
    val transientThrottle: Boolean get() = day == null && retryAfterSeconds != null
}

/**
 * WHAT THIS APP RUN HAS LEARNED ABOUT DICTATING, shared by every microphone on every field.
 *
 * A stage screen composes one dictation button per prose field — hundreds across a workshop — and
 * each of them is a separate composition with its own remembered state. Two facts must not be
 * re-learned once per field, because re-learning each of them costs the designer something real:
 *
 *  - THE SERVER SAID IT HAS NO PROVIDER (503). Learned once, that is a property of the deployment
 *    for the rest of the run. Forgotten per field, it is a six-megabyte upload per field, each one
 *    spending mobile data to be told the same thing.
 *  - THE ON-DEVICE ENGINE REFUSED A LANGUAGE (ERROR_LANGUAGE_UNAVAILABLE, 13). That is a
 *    measurement, and it outranks any list: it means the pack is not on this phone whatever
 *    `checkRecognitionSupport` claims. Forgotten per field, every single field pays a doomed round
 *    trip through the offline engine before reaching the rung that works.
 *
 * Both are deliberately PROCESS-SCOPED and not persisted. A pack downloaded overnight, or a server
 * that had its keys configured this morning, must not be permanently written off by a phone that
 * remembers last week — the same reasoning as `lastDictationTag` in the control.
 */
object DwDictationRun {

    /**
     * The application's one [WorkshopRepository], adopted at construction.
     *
     * A LOCATOR, AND SAID PLAINLY RATHER THAN HIDDEN. The microphone is drawn into a text field's
     * trailing-icon slot from two places — `FieldRenderer.ScalarInput` and `RichTextEditor`'s toolbar
     * — and only one of them has a repository to give it: the rich-text editor is a general-purpose
     * control used in previews and has no data layer at all. Threading a parameter through would
     * therefore have produced a phone where server dictation works in a short prose field and
     * silently does not in a long one, which is worse than either answer alone.
     *
     * The reference is safe to publish from the constructor because nothing dereferences it until a
     * designer taps a microphone, which cannot happen before `MainActivity.onCreate` has returned;
     * and it is `@Volatile` because an activity recreation (a rotation, a process restart) builds a
     * second repository on a fresh instance while the old composition is still being torn down.
     */
    @Volatile
    private var repository: WorkshopRepository? = null

    internal fun adopt(instance: WorkshopRepository) {
        repository = instance
    }

    /** Null on a build that has somehow composed a field before the repository exists. */
    fun repository(): WorkshopRepository? = repository

    @Volatile
    private var notConfigured: Boolean = false

    /** True once the dictation route has answered 503 in this run. Feeds `serverRouteUnavailable`. */
    val serverRouteUnavailable: Boolean get() = notConfigured

    fun serverSaidNotConfigured() {
        notConfigured = true
    }

    /**
     * The languages this phone's offline engine has refused, normalised the way the pack list
     * normalises them — `hi_IN`, `hi-IN` and `hi-in` have all been seen from OEM services, and a
     * case-sensitive set would remember a refusal under a spelling nothing else ever asks about.
     */
    private val refusedLanguages = ConcurrentHashMap.newKeySet<String>()

    fun engineRefused(tag: String) {
        refusedLanguages.add(dwNormalizeLanguageTag(tag))
    }

    fun engineHasRefused(tag: String): Boolean =
        refusedLanguages.contains(dwNormalizeLanguageTag(tag))

    /**
     * FORGET A REFUSAL, because the pack it was about has arrived.
     *
     * ── THE DEFECT THIS EXISTS TO END ─────────────────────────────────────────────────────────
     *
     * There was an `add` and a query and no way out, so a refusal outlived the download that fixed
     * it. The whole sequence, on an API 33+ handset with no Hindi pack:
     *
     *  1. The designer taps the microphone on a prose field. `packs.support` is null on an ordinary
     *     tap — `conditionsNow` says so in as many words, because binding a `SpeechRecognizer` per
     *     field would be an IPC handshake per field — so the pack state is UNKNOWN, and UNKNOWN is
     *     a yes for rung 1 under the honest-unknown rule.
     *  2. The offline engine answers ERROR_LANGUAGE_UNAVAILABLE (13). That is a MEASUREMENT and
     *     outranks any list, so it is written down here and the walk falls to the server, which
     *     works.
     *  3. The designer opens Settings › Offline dictation languages and pays for the Hindi download
     *     on a prepaid bundle. The row turns "Works offline" and the note reads, truthfully:
     *     "Hindi is on this phone now. Dictation in Hindi works with no signal."
     *  4. Back in a courtyard with no signal, `deviceRefusedLanguage` is STILL true. Rung 1 is
     *     dropped, both online rungs are gone with the connection, the plan is empty, and the
     *     designer reads that this phone has no offline Hindi to work from.
     *
     * So the settings screen and the microphone gave two accounts of one pack, one tap apart, and
     * the one that was wrong was the one standing between a designer and the pack they had just
     * bought. Only a force-stop cleared it, and nothing on either screen said so.
     *
     * ── WHY IT IS SAFE TO FORGET, WHICH IS THE PART WORTH GETTING RIGHT ───────────────────────
     *
     * A refusal is evidence from the engine that owns the packs, and it beats a list. It is beaten
     * in turn by NEWER evidence from that same engine — `checkRecognitionSupport` reporting the
     * language among its INSTALLED packs, which is a different question answered later. Nothing
     * else may call this: not a guess, not a connection change, not the passage of time. If the
     * pack is still absent the next tap earns its code 13 again and writes it back, at the cost of
     * one attempt, which is what the refusal cost to learn in the first place.
     */
    fun forgetRefusal(tag: String) {
        refusedLanguages.remove(dwNormalizeLanguageTag(tag))
    }

    // ── The workshop on screen: which one it is, and whether its recordings may leave the device ──

    /**
     * THE WORKSHOP WHOSE STAGE IS ON SCREEN, AND ITS ANSWER — published by the screen that read it.
     *
     * ── WHY AN AMBIENT AND NOT A PARAMETER, WHICH IS ALREADY SETTLED ────────────────────────────
     *
     * `DwDictationButton(enabled, onPartial, onCommit, onError)` is drawn into a text field's
     * trailing-icon slot from two call sites — `FieldRenderer.ScalarInput` and `RichTextEditor`'s
     * toolbar — and the rich-text editor is a general-purpose control used in previews with no data
     * layer at all. [repository] above records what threading a parameter through those two produced
     * and why it was rejected: "a phone where server dictation works in a short prose field and
     * silently does not in a long one". A consent parameter would reproduce that failure exactly, and
     * in the one direction that matters — the long prose fields are the ones dictation exists for.
     *
     * ── WHY IT NOW CARRIES AN ID, WHERE THIS COMMENT USED TO ARGUE IT COULD NOT ──────────────────
     *
     * It used to say: "there is no id it could safely carry … the question this ambient answers is
     * narrower and needs no id at all". That was true while rung 2 posted to `POST
     * /design-workshops/dictate`, which takes no id — and it is why that route was an unconsented door.
     * Rung 2 now posts to `POST /design-workshops/{id}/dictate`, the only route that can read a
     * workshop's `dictationConsent` column, so the id is no longer optional decoration: it is the thing
     * the gate is enforced against, and a clip cannot be sent without one.
     *
     * [serverId] IS THE SERVER'S ID AND NOTHING ELSE — `StageScreen`'s `syncId`. The old objection was
     * that this id is NULL for every workshop created in a courtyard, and that has not stopped being
     * true; what changed is the answer to it. Null is not treated as "somewhere to send it anyway", it
     * is treated as NO RUNG 2, because a workshop the server has never heard of could only answer 404 —
     * after the upload. A local draft id would be worse than null: it is a different id space, so it
     * would either 404 in the same way or, on a server where some other record happens to hold that id,
     * be checked against the wrong workshop's consent.
     *
     * ── ONE OBJECT AND NOT TWO FIELDS, WHICH IS A REAL DEFECT AND NOT TIDINESS ───────────────────
     *
     * Two `@Volatile`s would be read one after the other, and a publication landing between the two
     * reads would pair one workshop's GRANTED with another workshop's id — a clip sent under a consent
     * that was never given for it, which is the exact harm this whole lane exists to prevent. An
     * immutable pair swapped in one write cannot be read half-way.
     *
     * ── FAIL CLOSED, INCLUDING WHEN NOTHING IS ON SCREEN ────────────────────────────────────────
     *
     * NOT_RECORDED with no id until a stage screen says otherwise, and back to that once it goes. A
     * microphone drawn anywhere else — a record form, a preview, a screen that has no workshop behind
     * it at all — therefore has no rung 2, by both halves at once, and the sentences say why in words
     * that name the workshop screen. That is the honest answer: a dictation with no workshop has no
     * artisan's consent behind it either.
     */
    data class Published(
        /**
         * The workshop's SERVER id, or null when there is no server record to send a clip under.
         *
         * NEVER BLANK, by construction: [publishWorkshopConsent] normalises an empty string to null,
         * and the reason it has to is written out there — `""` is not null, so it would read as a
         * workshop that IS on the server and put an empty path segment on the wire.
         */
        val serverId: String?,
        /** Its recorded answer to "may these recordings leave the device". */
        val consent: DwTier3Consent,
    )

    @Volatile
    private var published = Published(serverId = null, consent = DwTier3Consent.NOT_RECORDED)

    /**
     * Which publication is current. A TOKEN AND NOT A WORKSHOP ID, and the difference is a real defect.
     *
     * A tabbed pager keeps a stage's NEIGHBOURS alive (`WorkshopDraftStore`'s lock comment says so in
     * as many words: "a tabbed pager keeps neighbours alive, and an auto-save fires on every
     * debounce"), so two stage screens of the same workshop can be composed at once and the one that
     * scrolls away disposes while the visible one is still there. A `forget` that cleared
     * unconditionally would then withdraw rung 2 from the screen the designer is looking at, silently,
     * and only until they navigated away and back. Matching the token means only the CURRENT
     * publication can be cleared.
     *
     * STILL A TOKEN NOW THAT AN ID IS CARRIED, and that is worth saying because the id looks like it
     * could serve: it cannot. Two stage screens of the SAME workshop are the case this guard is for, so
     * matching on the workshop would treat the disposing neighbour's clear as the visible screen's own
     * and withdraw the rung from under a designer mid-sentence.
     */
    private val consentPublication = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * The pair the ladder and the upload both read. ONE READ, so they cannot disagree.
     *
     * The control asks once per tap (`conditionsNow`) and captures the id when rung 2's recorder opens;
     * asking twice for the two halves is the interleaving [published] argues against.
     */
    fun publishedWorkshop(): Published = published

    /**
     * CONSENT ANSWERS THIS RUN HAS SEEN, for the one case the draft cannot cover.
     *
     * ── THE CONTRADICTION THIS EXISTS TO PREVENT ────────────────────────────────────────────────
     *
     * A workshop created on the web has no draft on this handset until a stage is opened and saved. Its
     * consent may already be GRANTED — an admin recorded it in the browser — and the workshop's own
     * screen reads that answer straight off the server and says so. But the STAGE screen publishes from
     * the draft, and `updateBookkeeping` deliberately refuses to create a draft that is not already
     * there ("bookkeeping about a workshop that has been deleted must not resurrect it as an empty
     * document"), so there is nowhere on disk for the answer to be written yet. Without this the
     * workshop screen would say "recordings may be sent out" while the microphone one tap away went on
     * withholding the craft-aware rung — two accounts of one answer, one tap apart, which is the exact
     * class of defect `DwDictationRun.forgetRefusal` was written to end for language packs.
     *
     * ── AND WHY IT CAN ONLY EVER WIDEN, NEVER NARROW ────────────────────────────────────────────
     *
     * It is consulted ONLY where the draft carries no answer at all. A REFUSED recorded on this device
     * therefore cannot be overridden by a stale GRANTED read from the server an hour earlier — the
     * device's own answer always wins, exactly as it does in the reconciliation on the workshop screen.
     * Process-scoped and not persisted, like the two facts above it and for their reason: the durable
     * home for a consent is the draft, and this is a bridge across one screen transition rather than a
     * second source of truth.
     *
     * Keyed by the WORKSHOP ID THE SCREENS ARE NAVIGATED WITH — the same value both of them receive as a
     * route argument — and deliberately not by `remoteId`, which is null for every workshop created in a
     * courtyard and would leave exactly the population most likely to be dictated into unable to use it.
     */
    private val consentAnswers = ConcurrentHashMap<String, DwTier3Consent>()

    /**
     * The workshop screen has resolved an answer — read from the server, or just recorded by a designer
     * whose draft write did not land. Remembered for the stage screens for the rest of the run.
     */
    fun rememberConsentAnswer(workshopId: String, consent: DwTier3Consent) {
        if (workshopId.isBlank()) return
        consentAnswers[workshopId] = consent
    }

    /** The answer this run has seen for this workshop, or null if it has seen none. */
    fun consentAnswerSeen(workshopId: String): DwTier3Consent? = consentAnswers[workshopId]

    /**
     * A stage screen has read a draft and knows which workshop this is and what it answered. Returns
     * the token to hand back to [forgetWorkshopConsent] on disposal.
     *
     * [serverWorkshopId] IS NULLABLE AND IS NOT DEFAULTED. Null is a real and ordinary answer — a
     * workshop that has not been sent up — and it must be stated at the call site rather than omitted,
     * because the pair is what the gate turns on and a publication that quietly dropped the id would
     * withdraw rung 2 with a sentence about syncing from a workshop that is perfectly well synced.
     */
    fun publishWorkshopConsent(consent: DwTier3Consent, serverWorkshopId: String?): Long {
        val token = consentPublication.incrementAndGet()
        /*
          AND A BLANK ID IS NOT AN ID — THE ONE SHAPE THAT COULD STILL REACH A PROVIDER UNGATED.

          `""` is not null, so publishing it would say this workshop IS on the server:
          [DwDictationConditions.workshopOnServer] reads `serverId != null`, so the ladder would offer
          rung 2, the recorder would open, and `designWorkshopDictate("")` would put an EMPTY PATH
          SEGMENT into `design-workshops/{id}/dictate`. Retrofit substitutes the empty string without
          complaint and okhttp PRESERVES the empty segment rather than collapsing it, so six megabytes
          of a named artisan's voice would be posted to `design-workshops//dictate` — which in front of
          any proxy that merges duplicate slashes IS `POST /design-workshops/dictate`, the id-less door
          that consults no `dictationConsent` column at all. That is the exact door this lane was
          written to shut, walked through by an id nobody checked was an id. Where the slashes are not
          merged it is a 404 instead, bought with the upload this whole file exists to avoid spending.

          THE BLANK IS NOT HYPOTHETICAL, WHICH IS WHY IT IS REFUSED HERE AND NOT MERELY DOCUMENTED.
          `DesignWorkshopDto.id` is DEFAULTED to `""` and decoded with `ignoreUnknownKeys` and
          `coerceInputValues`, so a 2xx that names no workshop — a captive portal answering the create
          POST with its own sign-in page, which this repository already treats as a field case — decodes
          to the empty string rather than throwing, and `WorkshopListScreen`'s create writes that into
          the draft's `remoteId`. `WorkshopSync.remoteIdOf` and `WorkshopSync`'s own create both already
          refuse the value in as many words ("the server accepted this workshop but did not say what it
          saved"); this is the same refusal at the one gate whose failure costs an artisan's recording
          instead of a retry.

          NORMALISED HERE AND NOT AT THE CALL SITE, because this is the only writer of [published] and
          the invariant is worth holding by construction: both readers — the ladder's fact and the id
          the recorder captures at Start — then inherit "a non-blank server id, or none at all", and a
          future publisher cannot reopen the rung by forgetting to check.
        */
        published = Published(
            serverId = serverWorkshopId?.takeIf { it.isNotBlank() },
            consent = consent,
        )
        return token
    }

    /**
     * The screen that published [token] has gone. Clears only if nothing has published since.
     *
     * Both halves run on composition's main thread — a `LaunchedEffect` publishes and a `DisposableEffect`
     * forgets — so the read-then-clear needs no lock; the counter is atomic because an activity
     * recreation can build the next screen's effects while the old one is still being torn down.
     *
     * BOTH HALVES GO TOGETHER. Clearing the consent and leaving the id — or the reverse — would leave a
     * pair describing no workshop that exists, and the whole point of the pair is that it always
     * describes one workshop or none.
     */
    fun forgetWorkshopConsent(token: Long) {
        if (consentPublication.get() == token) {
            published = Published(serverId = null, consent = DwTier3Consent.NOT_RECORDED)
        }
    }

    // ── The daily allowance, mirrored so a refusal costs no upload ──────────────────────────────

    /**
     * WHAT THIS PHONE LAST HEARD ABOUT THIS DESIGNER'S ALLOWANCE FOR TODAY.
     *
     * A FAÇADE OVER A PERSISTED, DAY-STAMPED, PER-USER RECORD — deliberately not a `@Volatile Boolean`
     * beside the two above. Those two are process-scoped because neither has a clock and both are
     * about the deployment or the handset: "a pack downloaded overnight, or a server that had its keys
     * configured this morning, must not be permanently written off by a phone that remembers last
     * week." An allowance is the other kind of fact. It EXPIRES ON A SCHEDULE, so an app alive across
     * midnight (opened at 23:00, used at 00:05) would go on refusing uploads the server would now
     * accept; it SURVIVES A SWIPE-AWAY, or a refusal bought with a six-megabyte upload is bought
     * again on the next cold start; and it is PER DESIGNER, which no process-global flag can be on a
     * phone that gets handed between them. The whole argument, and the fail-open direction, is at
     * [DwDictationAllowance].
     *
     * [context] is passed in rather than held: a singleton that kept one would outlive the activity
     * that gave it to it. Every caller is a composable with `LocalContext` in hand.
     */
    fun capView(context: Context): DwDictationCapView {
        // READ ONCE AND NOT TWICE, which was the first version. `cachedUser()` is a SharedPreferences
        // read AND a kotlinx decode of the stored profile, so asking twice doubled the only real cost in
        // this function — on the tap, on composition's main thread — and left the stored row being
        // matched against a possibly different account from the one it was fetched under, which on a
        // handset that gets handed between designers is the one comparison that has to be self-consistent.
        val userId = signedInUserId()
        return dwDictationCapView(
            stored = DwDictationAllowanceStore.read(context, userId),
            userId = userId,
            today = dwDictationIstDay(),
        )
    }

    /**
     * Write down the allowance a successful dictation reported.
     *
     * Called on the 200 and not only on a refusal, which is the whole reason the keys are on the 200:
     * the phone learns the ceiling from a dictation that WORKED, so the first refusal of the next day
     * can be made without an upload and can name the number.
     *
     * A response that carried no day changes nothing — see [dwDictationAllowanceOf]. That is what makes
     * this safe against a deployment older than the cap: it sends none of the keys, and the mirror
     * stays whatever it was rather than being overwritten with a record no day can ever match.
     */
    fun learnAllowance(context: Context, answer: DwDictateDto) {
        val allowance = dwDictationAllowanceOf(answer, signedInUserId()) ?: return
        DwDictationAllowanceStore.write(context, allowance)
    }

    /**
     * Write down a refusal, so the next field does not buy the same answer again.
     *
     * THE SERVER'S DAY WHEN IT NAMED ONE, THIS PHONE'S RECKONING OTHERWISE. The server is the authority
     * on which day an allowance belongs to, and a 429 that carries the allowance says so; a 429 that
     * carries nothing but a sentence leaves the phone with one honest thing to record — that as far as
     * it can tell, right now is spent — and the only day it can compare "right now" against later is
     * its own ([dwDictationIstDay], the same fixed +05:30 the server uses).
     *
     * A PHONE WHOSE DATE IS WRONG THEREFORE CANNOT USE THE MIRROR at all, in the case where the server
     * named a day it disagrees with: the stored day never matches, the record reads as stale, and every
     * field pays its upload to be refused — which is the behaviour this app had before the mirror
     * existed, and no worse. It is not corrected here, because a phone cannot tell a wrong clock from a
     * crossed boundary and the correction would be a fabricated day.
     */
    fun learnCapRefusal(context: Context, refusal: DwDictationCapRefused) {
        val userId = signedInUserId() ?: return
        val today = dwDictationIstDay()
        val day = refusal.day?.trim()?.takeIf { it.isNotEmpty() }
        val record = if (day != null) {
            DwDictationAllowance(userId = userId, day = day, limit = refusal.limit, remaining = 0)
        } else {
            dwDictationCapSpentRecord(
                previous = DwDictationAllowanceStore.read(context, userId),
                userId = userId,
                today = today,
            )
        }
        DwDictationAllowanceStore.write(context, record)
    }

    /**
     * Whose allowance is being mirrored, read synchronously from the token store.
     *
     * `cachedUser()` is a SharedPreferences read and answers in place, which is what lets the whole
     * mirror be consulted from `conditionsNow()` on the tap. Null on a build that has composed a field
     * before anybody signed in, and null is handled everywhere as "nothing known".
     */
    private fun signedInUserId(): String? = repository()?.cachedUser()?.id

    private val swept = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Clear clips orphaned by an earlier run, ONCE, on the first microphone this run composes.
     *
     * Once per run and not once per control: a stage screen composes one dictation button per prose
     * field and scrolls them in and out constantly, so a sweep on every mount would be a directory
     * listing per field per scroll for a directory that is empty almost always.
     */
    fun sweepScratchOnce(context: Context) {
        if (swept.compareAndSet(false, true)) dwSweepDictationScratch(context)
    }
}

/**
 * Where a clip lives for the seconds between Stop and the upload.
 *
 * `cacheDir`, and the neighbouring identity-card control deliberately does NOT use it (see
 * `identityScratchDir`: Android reclaims the cache under storage pressure without warning, and a
 * camera intent writing there can have its output deleted between the shutter and the read). The
 * answer differs here because the window differs: this file is opened, written, closed, read and
 * deleted inside one composition by one process, with no other application in the loop, and the
 * plan (§1) names cacheDir for it. A dictated clip is also audio of a named artisan, so the shorter
 * its life on a shared handset the better — and `cacheDir` is the directory the platform itself will
 * empty if this app ever fails to.
 */
private fun dictationScratchDir(context: Context): File =
    File(context.cacheDir, "dictation-scratch").apply { mkdirs() }

/**
 * How old an abandoned clip must be before a sweep may delete it. Ten minutes.
 *
 * THE AGE GUARD IS THE SECOND BRACE, AND SAYING WHICH ONE IT IS MATTERS. The first is that the sweep
 * runs ONCE PER APP RUN, on the first microphone composed ([DwDictationRun.sweepScratchOnce]) — at
 * which moment no clip of this run can exist yet, so an unconditional sweep would already be safe.
 * The identity-card control's directory is swept wholesale for the same reason and does not need this
 * guard at all.
 *
 * It is here because a stage screen holds one microphone per prose field and scrolls them in and out
 * constantly, so a later reader adding a second call site — a sweep on disposal is the obvious one —
 * would otherwise let one field's disposal delete the clip another field is at that moment uploading.
 * The recording is streamed from disk by okhttp, so the file has to still be there when the socket is
 * written. Ten minutes is longer than an ordinary clip's life and far shorter than the gap to the
 * next run; a four-minute recording whose upload then crawls for six minutes on a district-town bar
 * of signal would fall outside it, which is another reason the sweep is not wired to disposal.
 */
private const val DW_DICTATION_SCRATCH_STALE_MILLIS = 10L * 60L * 1000L

/**
 * Delete clips left behind by an EARLIER run of the app.
 *
 * The per-recording delete covers every ordinary path; this covers the one it cannot — the process
 * being killed between Stop and the response, which would otherwise leave a recording of a named
 * artisan's voice on a shared handset with nothing scheduled to remove it.
 */
fun dwSweepDictationScratch(context: Context) {
    val cutoff = System.currentTimeMillis() - DW_DICTATION_SCRATCH_STALE_MILLIS
    runCatching {
        dictationScratchDir(context).listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}

/**
 * The microphone for rung 2, from tap to finished file.
 *
 * ── WHY MPEG-4/AAC AND NOT WEBM/OPUS ──────────────────────────────────────────────────────────
 *
 * The web records whatever `MediaRecorder` in the browser offers — WebM/Opus on Chromium, MP4 on
 * Safari — and the server decodes both. Android's `MediaRecorder` cannot match that: OGG output with
 * the Opus encoder needs API 29, and minSdk here is 26, so it cannot be the only path and a
 * two-container implementation would double the surface for no gain. `OutputFormat.MPEG_4` with
 * `AudioEncoder.AAC` has been in the platform since API 10, which is every handset this app will
 * ever meet.
 *
 * The container is not validated server-side: the route hands the filename and MIME type straight to
 * `ai.transcribe_audio_bytes`, which passes them to whichever provider is first in the chain (a
 * multipart part for OpenAI and ElevenLabs, a `Content-Type` header for Deepgram). WHETHER A GIVEN
 * PROVIDER DECODES AAC-IN-MP4 IS NOT MEASURED FROM THIS HANDSET — the safety net is the chain
 * itself, which already tries the next provider when one fails, and whose own comment says "codecs
 * one engine can't decode are sometimes fine on another" (`ai.py:_transcribe_sync`).
 *
 * ── WHY THE CAPS ARE ON THE RECORDER AND NOT ONLY ON THE FILE ─────────────────────────────────
 *
 * See [DW_DICTATION_MAX_MILLIS]. A designer who taps the microphone and is then pulled into a
 * conversation must not find out at the end that everything they said is over the cap and refused.
 * The recorder stops itself at the cap and what was captured is sent.
 *
 * Every method must be called from the thread that constructed the recorder — which is composition's
 * main thread — because that is where `MediaRecorder`'s info callback is posted.
 */
class DwDictationRecorder {

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    /** True between a successful [start] and the [stop] or [discard] that ends it. */
    val recording: Boolean get() = recorder != null

    /**
     * Open the microphone. Returns null on success, or the SENTENCE to show the designer.
     *
     * [onLimitReached] is called on the main thread when the recorder hits its own duration or size
     * limit, so the control can finish the dictation exactly as though Stop had been pressed. It is
     * not an error path: what was captured is good, and sending it is better than losing it.
     */
    fun start(context: Context, onLimitReached: () -> Unit): String? {
        discard()
        val file = File(dictationScratchDir(context), "dictation-${UUID.randomUUID()}.m4a")
        // The API 31 constructor takes a Context so the platform can attribute the microphone to this
        // app in the privacy dashboard; the no-arg one is deprecated there and gone eventually.
        val created = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        }.getOrNull() ?: return "This phone would not open its microphone for recording."

        val configured = runCatching {
            /*
              VOICE_RECOGNITION AND NOT MIC, with a fallback because some OEM builds refuse it.

              `AudioSource.VOICE_RECOGNITION` is the source the platform documents as "tuned for voice
              recognition", which is what this clip is for; MIC is the general-capture source and on
              an OEM build may carry processing meant for video. WHETHER A GIVEN HANDSET ACTUALLY
              TREATS THE TWO DIFFERENTLY, AND WHETHER IT CHANGES THE TRANSCRIPT, IS UNMEASURED — this
              is the documented intent, not a result. What is not a guess is the fallback: where the
              source is refused outright the recording still has to happen, so the second attempt
              takes MIC rather than telling the designer to type it in.
            */
            created.configure(MediaRecorder.AudioSource.VOICE_RECOGNITION, file)
        }.recoverCatching {
            created.reset()
            created.configure(MediaRecorder.AudioSource.MIC, file)
        }

        if (configured.isFailure) {
            runCatching { created.release() }
            runCatching { file.delete() }
            // The two ordinary causes are a microphone another app is holding and a permission that
            // was revoked between the check and this call. Both are named, because the next move is
            // different for each and the designer can see which applies.
            return "The microphone could not be opened for recording. Close anything else that may " +
                "be using it, check that this app still has microphone permission, and try again."
        }

        created.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
            ) {
                onLimitReached()
            }
        }

        return runCatching {
            created.start()
            recorder = created
            target = file
            null
        }.getOrElse {
            runCatching { created.release() }
            runCatching { file.delete() }
            "This phone would not start recording. Close anything else that may be using the " +
                "microphone and try again."
        }
    }

    /**
     * The MediaRecorder call order is not stylistic — the platform throws IllegalStateException for
     * any other one. Source, then format, then encoder; the audio settings after the format and
     * before `prepare`.
     */
    private fun MediaRecorder.configure(source: Int, file: File) {
        setAudioSource(source)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(DW_DICTATION_CHANNELS)
        setAudioSamplingRate(DW_DICTATION_SAMPLE_RATE_HZ)
        setAudioEncodingBitRate(DW_DICTATION_BITS_PER_SECOND)
        setOutputFile(file)
        setMaxDuration(DW_DICTATION_MAX_MILLIS)
        // WRAPPED, because it is documented to be ignored or to throw on devices that do not support
        // it, and a phone that cannot enforce a byte ceiling must still be able to dictate. The
        // duration cap above and the check on the finished file are what actually hold the line.
        runCatching { setMaxFileSize(DW_DICTATION_MAX_BYTES) }
        prepare()
    }

    /**
     * Finish the recording and hand back the file, or null when there is nothing worth sending.
     *
     * ── WHY A THROW IS READ, AND NOT MERELY COUNTED ───────────────────────────────────────────
     *
     * `MediaRecorder.stop()` is documented to throw "if no valid audio/video data has been received
     * when stop() is called. This happens if stop() is called immediately after start()… the output
     * file is not properly constructed when this happens." Tap-and-immediately-stop happens
     * constantly, so that throw is not an error to report; it is "nothing was recorded", the file is
     * an unplayable stub, and the caller says so with [DW_DICTATION_NOTHING_RECORDED].
     *
     * THE OTHER THROW MEANS THE OPPOSITE, AND TREATING THE TWO ALIKE DELETED A GOOD RECORDING.
     * `setMaxDuration` is documented to stop the recorder itself at the cap, and to do it
     * ASYNCHRONOUSLY — "there is no guarantee that the recorder will have stopped by the time the
     * listener is notified". So the limit callback, whose whole purpose is to send what was captured
     * (see [DW_DICTATION_MAX_MILLIS]), lands here in one of two states: still recording, where
     * `stop()` succeeds normally, or already stopped, where stopping again is an invalid operation on
     * a recorder that has ALREADY FINALISED the file. The first version of this function counted both
     * throws as "nothing was recorded" and deleted the file — so a designer who dictated for the full
     * four minutes could lose the entire passage and be told the microphone heard nothing, which is
     * precisely the outcome the duration cap exists to prevent.
     *
     * The two are told apart by type: the framework raises `IllegalStateException` for a call made in
     * the wrong state and a plain `RuntimeException("stop failed.")` for the data failure above
     * (`android_media_MediaRecorder.cpp`, `process_media_recorder_call`). WHICH OF THE TWO A GIVEN
     * OEM BUILD ACTUALLY RAISES AT THE CAP IS UNMEASURED — nothing here has run on a handset — so the
     * file's own length is still required before anything is sent, and an empty one is still nothing.
     */
    fun stop(): File? {
        val active = recorder ?: return null
        val file = target
        recorder = null
        target = null
        val failure = runCatching { active.stop() }.exceptionOrNull()
        runCatching { active.release() }
        val finalised = failure == null || failure is IllegalStateException
        if (!finalised || file == null || !file.exists() || file.length() <= 0L) {
            runCatching { file?.delete() }
            return null
        }
        return file
    }

    /**
     * Throw the recording away and release the hardware. Safe to call at any time, including twice.
     *
     * THE FILE IS DELETED ON EVERY EXIT PATH — cancellation, failure, the composable leaving the
     * tree, the upload finishing. A held microphone and an orphaned recording of somebody's voice are
     * the two things this control must never leave behind on a shared handset.
     */
    fun discard() {
        recorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        recorder = null
        runCatching { target?.delete() }
        target = null
    }
}
