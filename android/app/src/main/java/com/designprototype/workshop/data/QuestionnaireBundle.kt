package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * THE FILE ONE DESIGNER HANDS ANOTHER IN A COURTYARD WITH NO SIGNAL: a questionnaire's QUESTIONS,
 * gzipped JSON, built entirely on the device.
 *
 * ── WHAT THIS IS, STATED PLAINLY, BECAUSE IT IS NOT A PEER CHANNEL ────────────────────────────
 *
 * This is a FILE FORMAT plus Android's own share sheet. It is not a bespoke transport, there is no
 * socket, no pairing, no discovery, no Bluetooth code and no dependency. `ACTION_SEND` hands the
 * file to whatever the designer picks — Quick Share, Bluetooth object push, a cable, an SD card, a
 * shared folder — and every one of those works with no internet because the OS owns the radios. The
 * owner asked for "QR codes, or … the same wifi network, or nearby share, or bluetooth"; the share
 * sheet is the canonical door to nearby share AND to bluetooth, and it is the only mechanism that
 * reaches both without a line of transport code. What it CANNOT do is tell the designer which wire
 * they chose — pick WhatsApp in a village and it fails later, silently — so the screen says so.
 *
 * The thing that was actually missing was not a transport. Every file-based share path in this app
 * was SERVER-DEPENDENT: `question-set.xlsx` is produced by `GET /questionnaires/{id}/question-set.xlsx`
 * and read back by `POST /questionnaires/upload`, and the dataset zips stream a server manifest and
 * fetch media by URL. So there was no artefact this handset could BUILD offline that another handset
 * could READ offline, and a perfect transport for a file that does not exist is worth nothing. This
 * file is that artefact.
 *
 * ── WHY GZIPPED JSON, AND NOT .XLSX ───────────────────────────────────────────────────────────
 *
 * `kotlinx-serialization-json` is already on the classpath and `java.util.zip` is in the platform,
 * so the cost is zero bytes of dependency. Every local store in this app is already an atomically
 * written JSON document with a schema version and a migration path ([WorkshopDraftStore],
 * [OfflineOutbox], [DwQuestionnaireStore]), so this is the shape the codebase already knows how to
 * version and quarantine.
 *
 * NOT .xlsx: that is the HUMAN handoff format and it is a server product. Reimplementing
 * `questionnaire_xlsx.py` on a handset to make it offline would be porting a spreadsheet writer to
 * solve a serialisation problem.
 *
 * MEASURED, on `backend/app/data/questionnaire_questions.json` — 24 sections, 285 questions:
 * 48,026 bytes as it ships, 29,178 compact, **8,501 gzipped**. It compresses 3.4x because it is 285
 * short English prompts with a repeating key vocabulary, which is DEFLATE's best case. Under 9 KB is
 * one second over classic Bluetooth. The questionnaire's size was never the problem.
 *
 * ── WHAT THE QR CARRIES, AND WHY IT IS NOT THIS ───────────────────────────────────────────────
 *
 * IT CANNOT BE THIS, AND NOT BECAUSE OF THIS APP'S ENCODER. [DwQrEncode] tops out at 108 alphanumeric
 * characters at the ECC level cards print at (level Q, version 6) — the gzipped questionnaire needs
 * 13,608 base32 characters, which is 126 times over. Raising `MAX_VERSION` does not rescue it and
 * this is worth writing down so nobody re-opens it: a MAXIMUM QR symbol — version 40 at ECC level L,
 * the weakest correction anybody would print — holds 4,296 alphanumeric characters or 2,953 bytes.
 * 13,608 > 4,296 and 8,501 > 2,953. **No QR of any version at any error-correction level can carry
 * this questionnaire.**
 *
 * So the QR carries a FINGERPRINT of the file — see [questionnaireHandoffCode]. Twenty-three
 * characters inside a 108-character budget, in the grammar this app already prints and already
 * scans, answering exactly one question: *is the file on your phone the file that left mine, whole?*
 * That is worth having, because Bluetooth object push has no resume and a truncated file is a file
 * that decodes into a shorter questionnaire without complaining.
 *
 * ── WHAT IS STRUCTURALLY ABSENT FROM THE FORMAT, WHICH IS THE SECURITY ARGUMENT ───────────────
 *
 * A received bundle is UNTRUSTED INPUT. It arrived over Bluetooth from a phone this app knows nothing
 * about, and the person who sends it can edit the JSON. So the defence is not validation — it is that
 * there are NO FIELDS TO ATTACK:
 *
 *  * NO `id`, on the questionnaire, on a section or on a question. A received bundle cannot name an
 *    existing row, so it cannot overwrite one. Adoption CREATES, always.
 *  * NO `ownerId`, no `createdById`, no author name, no "recorded by". This is the attribution
 *    failure worth being explicit about: the server sets `createdById` from the bearer token, so the
 *    API cannot be tricked into minting a record attributed to somebody else — the danger is entirely
 *    client-side. A bundle carrying `recordedBy: "Priya"`, adopted from Ravi's phone, would produce a
 *    row OWNED by Ravi and a screen saying PRIYA, and nothing afterwards could tell which was true.
 *    Whoever adopts a bundle owns what it creates, and the import screen says that in words before
 *    the designer accepts.
 *  * NO `isActive`, no review status, no `version`, no `updatedAt`. `ApiModels.kt` records that the
 *    API "refuses `status`" on an edit and that "an edit is not an approval"; a status copied between
 *    two handsets would be decoration at best and cross-device approval laundering at worst.
 *  * NO ENTRIES AND NO ANSWERS. This is the owner's own non-negotiable and it is enforced by
 *    [questionnaireBundleOf] reading only `sections`, and by there being nowhere in these three data
 *    classes to put one. Somebody else's responses about a named artisan do not travel. It is also
 *    the rule the server already holds from the other direction: `create_from_parsed` refuses to
 *    write the answers of a workbook that came out of the platform, because "those answers already
 *    exist in this database under the names of the people who recorded them".
 *  * NO MEDIA. A `MediaFileDto.url` is withheld by the server "unless the caller may download that
 *    uploader's data"; a peer channel re-sharing bytes the sender happens to hold locally would route
 *    around an access-control decision. A question set has no media anyway, which is part of why it
 *    is the right first artefact.
 *
 * [sourceVersion] is the ONE piece of provenance carried, and it is deliberately informational: it
 * lets the receiving screen say "this is version 4 of Ravi's form". It is never written to a server
 * row.
 *
 * ⚠ AND IT CANNOT TELL TWO DESIGNERS WHETHER THEY HOLD THE SAME EDITION, which this comment used to
 * claim. The server bumps a questionnaire's version on supersede and on retire only — adding a
 * question or a section does not move it — so a form with three new questions in it carries the same
 * `sourceVersion` as the one before them. [questionnaireHandoffCode]'s digest is the thing that
 * answers that question, because it is taken over the questions themselves; it is what the QR check
 * compares and what [questionnaireBundleFilename] puts in the name. Do not reintroduce a screen that
 * decides sameness from the version.
 *
 * ── VERSIONING ────────────────────────────────────────────────────────────────────────────────
 *
 * [QUESTIONNAIRE_BUNDLE_SCHEMA_VERSION] is carried in the file and checked on the way in, on
 * `DwWorkshopCodes`' reasoning about printed cards: two designers in a courtyard are on two different
 * builds, and the older one must say "update the app" rather than silently drop the sections it did
 * not understand. A bundle from the future is REFUSED, not partially read.
 *
 * PURE — no Context, no Compose, no network, no clock, no randomness. Everything here runs on a
 * handset that has had no signal for three days and is pinned by `QuestionnaireBundleTest`.
 */

/** The format marker inside the file. A string, so a wrong file type is named rather than guessed. */
const val QUESTIONNAIRE_BUNDLE_FORMAT = "designprototype.questionnaire"

/** See the versioning note above. Only ever incremented, and a bundle from the future is refused. */
const val QUESTIONNAIRE_BUNDLE_SCHEMA_VERSION = 1

/** The extension the file is written with, and the one the picker suggests. */
const val QUESTIONNAIRE_BUNDLE_EXTENSION = "dpwq"

/**
 * The MIME type declared on the share intent and on the manifest's `<intent-filter>`.
 *
 * A CUSTOM SUBTYPE UNDER `application/`, and it must stay one. `application/gzip` would put this app
 * on the share sheet for every .tar.gz on the phone, and the match-anything wildcard would put it
 * there for everything.
 * The filter also accepts `application/octet-stream` and `application/gzip` for RECEIVING, because
 * providers routinely report those for the same bytes — see the note on the picker.
 */
const val QUESTIONNAIRE_BUNDLE_MIME = "application/vnd.designprototype.questionnaire+gzip"

// --------------------------------------------------------------------------------------
// The wire shapes. Read the absences, not just the presences.
// --------------------------------------------------------------------------------------

/**
 * One question, as it travels.
 *
 * NO ID. NO `hasAnswers`. NO `isActive`, `retiredAt` or `supersededById`. A retired question is not
 * sent at all ([questionnaireBundleOf] takes the active list), because a wording somebody
 * deliberately replaced must not be reborn as a live question on a colleague's phone.
 */
@Serializable
data class QuestionnaireBundleQuestion(
    val prompt: String = "",
    val helpText: String? = null,
    val isRequired: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * One section, as it travels.
 *
 * [code] IS CARRIED even though the server can derive one from the title, because the code is what
 * the report prints beside an answer and what a designer says out loud ("section D, question 4"). A
 * derived code on the receiving side would renumber somebody's instrument.
 */
@Serializable
data class QuestionnaireBundleSection(
    val code: String = "",
    val title: String = "",
    val sortOrder: Int = 0,
    val questions: List<QuestionnaireBundleQuestion> = emptyList(),
)

/**
 * The whole bundle.
 *
 * [format] and [schemaVersion] come FIRST in declaration order deliberately: `kotlinx.serialization`
 * emits fields in that order, so the first bytes after the gzip header identify the file, which is
 * what makes a refusal specific ("that is a workshop draft, not a questionnaire") rather than
 * generic.
 */
@Serializable
data class QuestionnaireBundle(
    val format: String = QUESTIONNAIRE_BUNDLE_FORMAT,
    val schemaVersion: Int = QUESTIONNAIRE_BUNDLE_SCHEMA_VERSION,
    val title: String = "",
    val description: String? = null,
    /** The sending questionnaire's version, for a human to compare editions. Never stored anywhere. */
    val sourceVersion: Int = 0,
    val sections: List<QuestionnaireBundleSection> = emptyList(),
) {
    val questionCount: Int get() = sections.sumOf { it.questions.size }
}

// --------------------------------------------------------------------------------------
// Building one
// --------------------------------------------------------------------------------------

/**
 * Project a questionnaire this device holds into a bundle.
 *
 * THE PROJECTION IS THE ENFORCEMENT. It reads `sections` and nothing else on the DTO — not
 * [CustomQuestionnaireDto.entries], not `ownerId`, not `id`, not `isActive` — so no future field
 * added to that class can leak into a file two designers pass between phones without somebody
 * deliberately adding it here.
 *
 * RETIRED AND INACTIVE ROWS ARE DROPPED. A retired question keeps the answers it already has and
 * must never collect new ones; sending it would make it a live question on the receiving phone, which
 * is the one outcome the retirement existed to prevent.
 *
 * A question whose prompt is blank is dropped too. `CustomQuestionCreateBody.prompt` is
 * `min_length=1` on the server, so a blank one would be a 422 in the middle of adopting 285
 * questions — a failure two hundred rows in, for a row that says nothing.
 */
fun questionnaireBundleOf(form: CustomQuestionnaireDto): QuestionnaireBundle = QuestionnaireBundle(
    title = form.title.trim(),
    description = form.description?.trim()?.takeIf { it.isNotEmpty() },
    sourceVersion = form.version,
    sections = form.sections
        .filter { it.isActive }
        .sortedBy { it.sortOrder }
        .map { section ->
            QuestionnaireBundleSection(
                code = section.code.trim(),
                title = section.title.trim(),
                sortOrder = section.sortOrder,
                questions = section.questions
                    .filter { it.isActive && it.supersededById == null }
                    .filter { it.prompt.isNotBlank() }
                    .sortedBy { it.sortOrder }
                    .map { question ->
                        QuestionnaireBundleQuestion(
                            prompt = question.prompt.trim(),
                            helpText = question.helpText?.trim()?.takeIf { it.isNotEmpty() },
                            isRequired = question.isRequired,
                            sortOrder = question.sortOrder,
                        )
                    },
            )
        },
)

/**
 * The canonical JSON for a bundle: compact, field order fixed by declaration order, defaults
 * included.
 *
 * `encodeDefaults = true` matters for the fingerprint. With defaults dropped, a question with
 * `isRequired = false` would encode differently from one where the sender's build had a different
 * default, and two identical questionnaires would fingerprint differently — which is exactly the
 * false alarm that teaches designers to ignore the check.
 */
private val bundleJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/** The bytes that go in the file: gzip of [canonicalQuestionnaireBundleJson]. */
fun encodeQuestionnaireBundle(bundle: QuestionnaireBundle): ByteArray {
    val json = canonicalQuestionnaireBundleJson(bundle).toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(json.size / 3)
    GZIPOutputStream(out).use { it.write(json) }
    return out.toByteArray()
}

/** The exact string the fingerprint is taken over, exposed so a test can assert it is stable. */
fun canonicalQuestionnaireBundleJson(bundle: QuestionnaireBundle): String = bundleJson.encodeToString(bundle)

// --------------------------------------------------------------------------------------
// Reading one — which is the untrusted direction
// --------------------------------------------------------------------------------------

/** Why a file could not be read as a questionnaire bundle. Every one is a fact about the BYTES. */
enum class QuestionnaireBundleRefusal {
    /** Nothing, or an empty file. */
    EMPTY,

    /** Not gzip, or gzip that will not inflate — the usual fingerprint of a truncated transfer. */
    DAMAGED,

    /** Readable JSON, but not this app's questionnaire format. */
    NOT_A_QUESTIONNAIRE,

    /** Ours, written by a newer build. */
    NEWER_VERSION,

    /** Ours and readable, but there is nothing in it to adopt. */
    NOTHING_IN_IT,

    /** Bigger than any questionnaire could be. See [QUESTIONNAIRE_BUNDLE_MAX_INFLATED]. */
    TOO_LARGE,
}

/**
 * The ceiling on the INFLATED size, checked while inflating and not after.
 *
 * A gzip stream is untrusted input from a device this app knows nothing about, and a few kilobytes of
 * it can inflate to gigabytes — the decompression bomb. `readBytes()` on a `GZIPInputStream` would
 * happily fill the heap and take the app down on the phone with the least of it. Two megabytes is
 * 235 times the measured 8,501-byte questionnaire, so nothing real is anywhere near it.
 */
const val QUESTIONNAIRE_BUNDLE_MAX_INFLATED = 2 * 1024 * 1024

/** What [readQuestionnaireBundle] made of some bytes. */
sealed interface QuestionnaireBundleRead {
    data class Ok(val bundle: QuestionnaireBundle) : QuestionnaireBundleRead
    data class Refused(val reason: QuestionnaireBundleRefusal, val message: String) : QuestionnaireBundleRead
}

/**
 * Read bytes that arrived from somewhere else.
 *
 * EVERY FAILURE GETS ITS OWN SENTENCE, and the reason is the transport: Bluetooth object push and
 * Quick Share have no resume, so a truncated file is the single most likely fault and it presents as
 * a gzip that will not inflate. "Damaged, ask them to send it again" and "that is not a questionnaire
 * file" lead a designer to two completely different next actions, and a generic "could not be read"
 * leads them to neither.
 */
fun readQuestionnaireBundle(bytes: ByteArray?): QuestionnaireBundleRead {
    if (bytes == null || bytes.isEmpty()) {
        return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.EMPTY,
            "That file is empty. Ask for it again — a transfer that was interrupted often leaves an " +
                "empty file behind."
        )
    }
    val text = runCatching {
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buffer = ByteArray(64 * 1024)
            val out = ByteArrayOutputStream(bytes.size * 3)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (out.size() + read > QUESTIONNAIRE_BUNDLE_MAX_INFLATED) {
                    throw IllegalStateException("inflated past the ceiling")
                }
                out.write(buffer, 0, read)
            }
            out.toString("UTF-8")
        }
    }.getOrElse { error ->
        return if (error.message == "inflated past the ceiling") {
            QuestionnaireBundleRead.Refused(
                QuestionnaireBundleRefusal.TOO_LARGE,
                "That file is far too big to be a questionnaire. It has not been opened."
            )
        } else {
            QuestionnaireBundleRead.Refused(
                QuestionnaireBundleRefusal.DAMAGED,
                "That file is damaged or did not arrive whole. Ask for it again — Bluetooth and " +
                    "nearby sharing cannot resume a transfer, so a half-sent file looks exactly like " +
                    "this."
            )
        }
    }
    val bundle = runCatching { bundleJson.decodeFromString<QuestionnaireBundle>(text) }.getOrNull()
        ?: return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NOT_A_QUESTIONNAIRE,
            "That is not a questionnaire from this app. A spreadsheet, a photograph or a workshop " +
                "export will not open here — ask for the .$QUESTIONNAIRE_BUNDLE_EXTENSION file."
        )
    if (bundle.format != QUESTIONNAIRE_BUNDLE_FORMAT) {
        return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NOT_A_QUESTIONNAIRE,
            "That file came from this app but it is not a questionnaire — ask for the questionnaire " +
                "file, the one ending .$QUESTIONNAIRE_BUNDLE_EXTENSION."
        )
    }
    if (bundle.schemaVersion > QUESTIONNAIRE_BUNDLE_SCHEMA_VERSION) {
        // REFUSED WHOLE, never read in part. A build that half-understands a newer file produces a
        // questionnaire with sections silently missing, and a missing section is indistinguishable
        // from a questionnaire that never had one. The same rule `SUPPORTED_VERSIONS` applies to a
        // printed card, and for the same reason: the handset in the village is the client least
        // likely to be up to date, so it is the one that meets the newer file.
        return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NEWER_VERSION,
            "That questionnaire was made by a newer version of this app, so this one cannot read it " +
                "safely. Update the app, or ask them to send the question set as a spreadsheet instead."
        )
    }
    if (bundle.sections.none { it.questions.isNotEmpty() }) {
        return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NOTHING_IN_IT,
            "That questionnaire file has no questions in it. Ask them to check they sent the right one."
        )
    }
    return QuestionnaireBundleRead.Ok(bundle)
}

// --------------------------------------------------------------------------------------
// The QR: a fingerprint, and never the questionnaire
// --------------------------------------------------------------------------------------

/**
 * The payload version a HANDOFF CHECK code is written at, and the letter it uses.
 *
 * ── WHY A THIRD VERSION AND A THIRD LETTER ────────────────────────────────────────────────────
 *
 * `DwWorkshopCodes` sets the precedent twice over: version 1 names a RECORD, version 2 is a join
 * card, and the join card got its own version specifically so an older build would answer "that card
 * was printed by a newer version of the app" instead of misreading it. This is the third grammar and
 * it takes the third version for the same reason.
 *
 * `H` for Handoff. ⚠ RESERVED, like `J`. The letters spent are A C W D S T Q M G P (records), J
 * (join card) and now H. Do not give any of them to a record type later: these characters are printed
 * on cards and screens that outlive the build that made them.
 *
 * DELIBERATELY NOT ADDED TO `SUPPORTED_VERSIONS`, exactly as `WORKSHOP_JOIN_CODE_VERSION` is not:
 * that set gates `decodeWorkshopCode`, which returns a record and a record type, and a handoff code
 * resolves to neither.
 */
const val QUESTIONNAIRE_HANDOFF_CODE_VERSION = 3

/** See above. `H` is reserved. */
const val QUESTIONNAIRE_HANDOFF_LETTER = "H"

/**
 * Crockford base32 — the alphabet the check characters and the join secret already use, so a code
 * carries one character set and not two, and so `CHECK_CONFUSABLES` folding is meaningful over it.
 */
private const val HANDOFF_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/**
 * 11 characters of a 32-symbol alphabet is 55 bits of digest.
 *
 * IT IS A TRUNCATED CHECKSUM AND NOTHING MORE, and the length is chosen against the only threat it
 * answers: a file that arrived truncated or corrupted. 55 bits makes an accidental collision
 * impossible in practice. It is NOT a signature — the algorithm ships in this APK — and it must never
 * be described as one, which is the discipline `DwWorkshopCodes` states about its own four check
 * characters and about the join card's 110-bit secret: "it is not a key unless it behaves like one".
 * Nothing here authenticates the sender, and nothing about a matching fingerprint says the
 * questionnaire is one the recipient should trust — only that the bytes are whole.
 */
private const val HANDOFF_DIGEST_LENGTH = 11

/**
 * The code printed and scanned beside a handed-over questionnaire: 23 characters.
 *
 *     DPW3:H:8QK4T2WMZ0R:X7PA
 *     ─┬── ┬ ─────┬───── ─┬──
 *      │   │      │       └─ check: four characters over everything to its left (FNV-1a, a typo
 *      │   │      │          detector, the same one every code in this app uses)
 *      │   │      └─ 55 bits of SHA-256 over the file's canonical JSON, Crockford base32
 *      │   └─ H for handoff
 *      └─ namespace + payload version 3
 *
 * 23 of the 108 characters [DwQrEncode] can carry at the level cards print at, so this needs no new
 * QR version, no new dependency, and both existing decode paths ([DwQrDecode] and the live scanner)
 * read it unchanged.
 *
 * TAKEN OVER THE CANONICAL JSON AND NOT OVER THE GZIP BYTES, which is the one subtle decision here.
 * Gzip output depends on the compressor's level and implementation, so two builds — or two Android
 * versions — can produce different bytes for identical content. Fingerprinting those would make the
 * check fail between two designers holding the same questionnaire, which is worse than not checking:
 * it would send them looking for a transfer fault that does not exist.
 */
fun questionnaireHandoffCode(bundle: QuestionnaireBundle): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonicalQuestionnaireBundleJson(bundle).toByteArray(Charsets.UTF_8))
    val body = buildString(HANDOFF_DIGEST_LENGTH) {
        // Five bits at a time out of the leading bytes, most significant first, so the mapping is
        // fixed and a test can pin it by value rather than re-deriving it.
        var bitBuffer = 0L
        var bits = 0
        var index = 0
        while (length < HANDOFF_DIGEST_LENGTH) {
            if (bits < 5) {
                bitBuffer = (bitBuffer shl 8) or (digest[index].toLong() and 0xFF)
                bits += 8
                index++
            }
            val shift = bits - 5
            append(HANDOFF_ALPHABET[((bitBuffer shr shift) and 0x1F).toInt()])
            bits = shift
        }
    }
    val prefix = "$WORKSHOP_CODE_NAMESPACE$QUESTIONNAIRE_HANDOFF_CODE_VERSION:" +
        "$QUESTIONNAIRE_HANDOFF_LETTER:$body"
    return "$prefix:${workshopCodeCheck(prefix)}"
}

/** Does this string CLAIM to be a handoff code? Read from the letter alone, as `looksLikeJoinCard` is. */
fun looksLikeQuestionnaireHandoffCode(input: String?): Boolean {
    val raw = (input ?: "").filterNot { it.isWhitespace() || it.code == 0xA0 || it.code == 0xFEFF }.uppercase()
    val parts = raw.split(":")
    return parts.size == 4 &&
        parts[0].startsWith(WORKSHOP_CODE_NAMESPACE) &&
        parts[1] == QUESTIONNAIRE_HANDOFF_LETTER
}

/** What a scanned or typed handoff code turned out to be. */
sealed interface QuestionnaireHandoffRead {
    /** A readable code. [digest] is the 11-character body, for comparing against a file in hand. */
    data class Ok(val digest: String, val code: String) : QuestionnaireHandoffRead
    data class Refused(val message: String) : QuestionnaireHandoffRead
}

/**
 * Read a handoff code. Refuses on the same three grounds every grammar in this app refuses on: not
 * ours, from a newer build, or one character wrong.
 *
 * The confusable fold is applied to the digest AND to the check, and safely: both are drawn from
 * Crockford base32, which has no `I`, `L`, `O` or `U`, so a `0` in either can only be a misread `O`.
 * (The record parser cannot fold its ID for the opposite reason — a cuid legitimately contains both.)
 */
fun readQuestionnaireHandoffCode(input: String?): QuestionnaireHandoffRead {
    val raw = (input ?: "").filterNot { it.isWhitespace() || it.code == 0xA0 || it.code == 0xFEFF }.uppercase()
    if (raw.isEmpty()) return QuestionnaireHandoffRead.Refused("Nothing was scanned or typed.")
    val parts = raw.split(":")
    if (parts.size != 4 || !parts[0].startsWith(WORKSHOP_CODE_NAMESPACE)) {
        return QuestionnaireHandoffRead.Refused(
            "That is not a questionnaire check code. They begin “DPW” — a shop barcode or a web " +
                "address will not check a file."
        )
    }
    val versionText = parts[0].substring(WORKSHOP_CODE_NAMESPACE.length)
    if (versionText.isEmpty() || !versionText.all { it in '0'..'9' }) {
        return QuestionnaireHandoffRead.Refused(
            "That is not a questionnaire check code. They begin “DPW” followed by a version number."
        )
    }
    if (parts[1] != QUESTIONNAIRE_HANDOFF_LETTER) {
        return QuestionnaireHandoffRead.Refused(
            "That code belongs to this app but it is not a questionnaire check code — it says " +
                "something else. Scan the code shown beside the questionnaire that was sent to you."
        )
    }
    // `toDouble`, as every other parser in this app does with a version read off a card or a screen:
    // "DPW03" is somebody being careful and four hundred nines is a corrupted scan, and neither should
    // be reported as damage for the wrong reason.
    if (versionText.toDouble() != QUESTIONNAIRE_HANDOFF_CODE_VERSION.toDouble()) {
        return QuestionnaireHandoffRead.Refused(
            "That check code was made by a version of this app this one does not read. Update the app."
        )
    }
    val digest = buildString(parts[2].length) {
        for (c in parts[2]) append(when (c) { 'I', 'L' -> '1'; 'O' -> '0'; else -> c })
    }
    if (digest.length != HANDOFF_DIGEST_LENGTH || digest.any { it !in HANDOFF_ALPHABET }) {
        return QuestionnaireHandoffRead.Refused(
            "This check code is incomplete or was typed wrongly. Read it off the screen again, " +
                "character by character."
        )
    }
    val typedCheck = buildString(parts[3].length) {
        for (c in parts[3]) append(when (c) { 'I', 'L' -> '1'; 'O' -> '0'; else -> c })
    }
    val prefix = "$WORKSHOP_CODE_NAMESPACE$QUESTIONNAIRE_HANDOFF_CODE_VERSION:" +
        "$QUESTIONNAIRE_HANDOFF_LETTER:$digest"
    if (typedCheck.length != 4 || typedCheck != workshopCodeCheck(prefix)) {
        return QuestionnaireHandoffRead.Refused(
            "This check code does not check out, so one of its characters is wrong. Read it off the " +
                "screen again, character by character."
        )
    }
    return QuestionnaireHandoffRead.Ok(digest = digest, code = "$prefix:$typedCheck")
}

/**
 * What to tell the designer after comparing a scanned code against the file they are holding.
 *
 * THE MISMATCH SENTENCE IS THE ONE THAT MATTERS and it is deliberately not accusatory. Two innocent
 * causes are far more likely than any interesting one: the transfer was truncated, or the sender
 * edited the questionnaire after the code was shown. Both are named, because "this file does not
 * match" with no explanation reads as an accusation and gets ignored.
 */
fun questionnaireHandoffVerdict(expected: String, fileDigest: String): String =
    if (expected == fileDigest) {
        "This is the same questionnaire, whole. Nothing was lost in the transfer."
    } else {
        "This is NOT the file that code was made for. Either it did not arrive whole — Bluetooth and " +
            "nearby sharing cannot resume a transfer — or the questionnaire was changed after the code " +
            "was shown. Ask them to send it again and show you a fresh code."
    }

// --------------------------------------------------------------------------------------
// Arriving from another app: the share sheet's delivery
// --------------------------------------------------------------------------------------

/**
 * Should a delivery from another app be treated as a questionnaire bundle?
 *
 * ── WHY THIS EXISTS AT ALL: THE APP COULD NOT RECEIVE ─────────────────────────────────────────
 *
 * Sending was already possible three times over; RECEIVING was not. `MainActivity` had exactly one
 * `<intent-filter>` — MAIN/LAUNCHER — so a colleague's Quick Share or Bluetooth push landed in
 * Downloads and this app never heard about it. The designer's only route in was to remember the file
 * existed, open the questionnaire list and re-find it through the document picker. That works, and it
 * works offline, but it is four steps and a memory test at the end of a transfer that the sender
 * believes has already arrived.
 *
 * ── WHY THE MIME TYPE IS NOT TRUSTED, AND WHY IT IS STILL CHECKED ─────────────────────────────
 *
 * Providers disagree about the same bytes. Our own share sets [QUESTIONNAIRE_BUNDLE_MIME], but
 * Bluetooth object push, Quick Share and file managers routinely re-report a file they do not
 * recognise as `application/octet-stream`, and gzip-sniffers report `application/gzip`. A filter that
 * accepted only our custom subtype would miss the transports the owner actually named.
 *
 * But the inverse is worse in a different way, and it is the reason the manifest and this function
 * do NOT simply accept those two types: adding `application/octet-stream` to an `ACTION_SEND` filter
 * puts this app on the share sheet for every unrecognised file on the phone, and
 * `application/gzip` puts it there for every `.tar.gz`. So the split, which the manifest mirrors:
 *
 *  * `ACTION_SEND` — our own subtype only. That is what our export sets, and a share sheet is a menu
 *    a person reads, so a wrong entry on it is a cost paid by everybody who ever shares anything.
 *  * `ACTION_VIEW` — the broad types too, but only for a path ending `.$QUESTIONNAIRE_BUNDLE_EXTENSION`.
 *    Opening a file is already a specific act aimed at one file, so the extension is evidence there
 *    rather than noise.
 *
 * ── ⚠ WHICH TRANSPORTS THIS ACTUALLY REACHES, WHICH IS NOT ALL OF THEM ────────────────────────
 *
 * The broad-type VIEW filter is gated on a `.dpwq` PATH, and that is what limits it. It fires for a
 * DocumentsUI-backed open (`/document/primary:Download/x.dpwq` matches once decoded), and our own
 * subtype fires for a file this app itself exported and shared. It does NOT fire for the two
 * transports the owner named:
 *
 *  * Quick Share / Nearby and Bluetooth OPP save the file first and hand over a Uri like
 *    `content://media/external/downloads/1234` or `content://com.android.bluetooth.opp/btopp/12`. The
 *    PATH carries no extension, so the pattern misses.
 *  * And the custom-subtype filter misses those too, because the receiving MediaStore guesses the type
 *    from an unknown `.dpwq` extension and does not guess ours.
 *
 * So for those two the app appears as no handler at all, and dropping the path gate is not the fix:
 * that would put this app on the "open with" list for every unrecognised file on the phone, which is
 * the cost the split above exists to refuse. Nothing an app declares can teach the receiving
 * MediaStore what a `.dpwq` is.
 *
 * WHICH IS WHY THE DOCUMENT PICKER IN THE RECEIVING CARD IS NOT A FALLBACK BUT THE MAIN DOOR for
 * nearby share and Bluetooth, and why the card's own text tells the designer to go and find the file
 * where it was saved. It validates identically — `readQuestionnaireBundle` reading the bytes — and it
 * works with no signal. Do not restore a comment claiming these filters are "the whole of nearby
 * share, or bluetooth, on the receiving side"; they are not, and a designer sent looking for a
 * notification that will not appear loses the transfer.
 *
 * ── AND THE MIME TYPE IS NEVER THE DECISION ───────────────────────────────────────────────────
 *
 * Everything above is about which entries appear in an OS menu. It is not validation. What a delivery
 * actually is gets decided by [readQuestionnaireBundle] reading the bytes — format marker, schema
 * version, inflated-size ceiling — because the type string is set by whichever app is sending and is
 * therefore untrusted input like the rest of it. A file that passes this function and is really a
 * photograph is refused with "that is not a questionnaire from this app", which is the same sentence
 * the picker gives. The document picker remains the universal door for a transport that mangles the
 * type beyond recognition, and it validates the same way.
 *
 * PURE, taking strings rather than an `Intent`, so every one of these combinations is pinned by
 * `QuestionnaireBundleTest` on the JVM instead of by carrying two handsets into a courtyard.
 *
 * @param action the intent's action.
 * @param mimeType the intent's type, as the sending app reported it. Case and parameters (`; charset=`)
 *   are tolerated because providers add them.
 * @param filename the last path segment of the delivered Uri, or null when there is none to read.
 */
fun isQuestionnaireBundleDelivery(action: String?, mimeType: String?, filename: String?): Boolean {
    val normalisedType = mimeType?.substringBefore(';')?.trim()?.lowercase()
    val looksNamedRight = filename
        ?.substringBefore('?')
        ?.trim()
        ?.lowercase()
        ?.endsWith(".$QUESTIONNAIRE_BUNDLE_EXTENSION") == true
    return when (action) {
        // OUR SUBTYPE AND NOTHING ELSE, because that is the whole of the manifest's SEND filter.
        // This used to also accept any type at all with a `.dpwq` name, which was strictly wider than
        // the filter that gates it — harmless in practice, since a SEND intent reaches this function
        // only after the manifest has already matched, but it made the claim above ("mirrors it
        // exactly so that what the OS offers and what the app accepts cannot drift apart") false, and
        // a mirror nobody can trust is worse than no mirror. Narrowing rather than widening the
        // manifest, because widening SEND is the cost paid by everybody who ever shares a file.
        "android.intent.action.SEND" -> normalisedType == QUESTIONNAIRE_BUNDLE_MIME
        "android.intent.action.VIEW" ->
            normalisedType == QUESTIONNAIRE_BUNDLE_MIME ||
                (looksNamedRight && normalisedType in BROAD_BUNDLE_TYPES)
        else -> false
    }
}

/**
 * The types a provider reports for bytes it does not recognise.
 *
 * NULL IS NOT IN THIS SET, and the reason is worth writing down because the obvious argument for
 * including it is wrong. A `content://` Uri from a file manager does frequently carry no type at all
 * — but an `<intent-filter>` that declares a `mimeType` never matches an intent that has none, so a
 * typeless VIEW delivery cannot reach this function through the manifest in the first place. A `null`
 * member was therefore unreachable code that made this function look more permissive than the door it
 * describes. A file manager offering a typeless Uri is handled by the in-card document picker, which
 * is a separate door and does not consult this function.
 */
private val BROAD_BUNDLE_TYPES: Set<String?> =
    setOf("application/octet-stream", "application/gzip", "application/x-gzip")

/**
 * The filename a bundle is written under.
 *
 * `-questions` IS IN THE STEM AND IS NOT DECORATION. The server's own `question_set_filename`
 * appends the same suffix and its docstring says why: two downloads land in the same folder with the
 * same questionnaire title on them, and "the name is the last thing standing between a designer and
 * that mistake" — one of them is a question list and the other is every respondent they have ever
 * interviewed. This file carries nobody, and its name has to say which of the two it is.
 *
 * ── WHY THE VERSION IS NOT ENOUGH, AND THE DIGEST IS IN THE NAME ──────────────────────────────
 *
 * `version` DOES NOT MOVE WHEN QUESTIONS ARE ADDED. The server bumps it on supersede and on retire
 * only — `create_question` and `create_section` do not — so "export, add three questions, export
 * again" produced two files with the identical name and different contents. In a file manager, which
 * is where a designer picks the file for nearby share or Bluetooth, those two are indistinguishable,
 * and handing over the older one puts the recipient's scan of a fresh code straight into the
 * "this is NOT the file that code was made for" branch over a transfer that was never at fault.
 *
 * So [digest] — the same 11-character body [questionnaireHandoffCode] prints in the QR, and nothing
 * new to learn — goes in the name. Two exports of the same questions share a name; two exports of
 * different questions cannot. It is also the one thing in the name a designer can hold against the
 * code on the sender's screen.
 *
 * @param digest [questionnaireHandoffCode]'s digest body, or blank to leave it out. Blank is
 *   tolerated rather than required so a caller that has no bundle in hand still gets a legal name.
 */
fun questionnaireBundleFilename(title: String, version: Int, digest: String = ""): String {
    val stem = title.trim()
        .replace(Regex("[^A-Za-z0-9\\u0900-\\u097F ._-]+"), "")
        .replace(Regex("\\s+"), "-")
        .trim('-', '.', '_')
        .take(60)
        .ifBlank { "questionnaire" }
    // Sanitised like the stem, and for the identical reason: this reaches a filesystem path, and the
    // caller is free to hand over whatever it read off a file that came from another device.
    val mark = digest.filter { it in HANDOFF_ALPHABET }.take(HANDOFF_DIGEST_LENGTH)
    val marked = if (mark.isEmpty()) "" else "-$mark"
    return "$stem-questions-v$version$marked.$QUESTIONNAIRE_BUNDLE_EXTENSION"
}

/** [questionnaireHandoffCode]'s 11-character digest body, without the namespace or the check. */
fun questionnaireHandoffDigest(bundle: QuestionnaireBundle): String =
    questionnaireHandoffCode(bundle).split(":").getOrNull(2).orEmpty()

/**
 * A stored ISO instant as something a person reads: "24 Aug 2026, 09:12 am".
 *
 * ── WHY THIS EXISTS ───────────────────────────────────────────────────────────────────────────
 *
 * Three sentences a designer with no signal reads were printing the raw stamp:
 * "Added to your questionnaires on 2026-08-24T09:12:33.221Z", the row's "arrived
 * 2026-08-24T09:12:33.221Z", and the cached-form notice's "downloaded on …". Every one of those is
 * ALSO in UTC, which in the field is five and a half hours off the wall clock — so the one case the
 * stamp is there to settle, "is this copy older than the four questions my colleague added this
 * morning", was being answered against the wrong time of day. The rest of the app has said
 * "dd MMM yyyy, hh:mm a" for a while (`SearchScreen`, `ApiKeysScreen`); this is that, reachable from
 * the pure layer.
 *
 * BOTH OFFSET AND INSTANT FORMS ARE PARSED, as `dwConsentDay` does, because these strings come from
 * three writers: `Instant.now().toString()` on this device, the server's own timestamps, and a file
 * that arrived from a handset nobody here controls. UNPARSEABLE INPUT COMES BACK UNCHANGED rather than
 * as a dash — a stamp we cannot read is still evidence, and blanking it would hide the only clue about
 * what wrote it.
 *
 * @param zone and @param locale are arguments so this stays pinnable by a JVM test on any machine.
 *   The defaults read the device's settings, which is what every caller wants; neither default reads a
 *   clock, so this function is still pure in the sense the header of this file means.
 */
fun readableStamp(
    iso: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    val text = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val moment = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(text) }.getOrNull()
        ?: return text
    return runCatching {
        DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", locale).format(moment.atZone(zone))
    }.getOrNull() ?: text
}

/**
 * The sentence shown beside the export control, and beside a received file before it is adopted.
 *
 * The one rule `QuestionnaireInterchangeUi` enforces about the .xlsx downloads applies here word for
 * word: WHICH FILE CARRIES RESPONDENTS IS NEVER BEHIND A DISCLOSURE. This one carries none, and it
 * says so in the same voice — in terms of PEOPLE, not of columns.
 */
const val QUESTIONNAIRE_BUNDLE_CONTENTS_NOTICE: String =
    "The questions only — no answers, no respondents' names, no recorded sittings, and nobody's " +
        "name as the author. It is built on this phone, so it can be sent with no internet at all: " +
        "nearby share, Bluetooth, a cable or a shared folder."

/**
 * What the person ADOPTING one is told, before they accept it.
 *
 * The third sentence is the attribution rule, said out loud rather than merely enforced. The server
 * sets `createdById` from the bearer token, so whatever this bundle claims, the questionnaire it
 * creates belongs to whoever is signed in here. A designer who believes they are filing a colleague's
 * form under the colleague's name would be wrong, and would only find out from an audit column.
 */
const val QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE: String =
    "This creates a NEW questionnaire on your account. It copies the questions only — no answers " +
        "and no respondents come with it, and nothing on the sender's copy is changed. Once created " +
        "it is YOURS: it is recorded as uploaded by you, not by whoever sent the file."
