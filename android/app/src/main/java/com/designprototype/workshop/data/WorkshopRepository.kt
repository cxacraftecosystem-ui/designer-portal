package com.designprototype.workshop.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
// Borrowed from the ui package, and the direction is backwards on purpose: the consolidated
// questionnaire's DTOs live beside their screen because this file and ApiModels.kt were being edited
// concurrently when that feature landed. Re-declaring them here would give the app two spellings of
// one wire format; if they ever move into ApiModels.kt this import is the only line to delete.
import com.designprototype.workshop.ui.ConsolidatedQuestionnaireDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okio.BufferedSink
import retrofit2.HttpException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Files at/under this size upload as one streamed S3 PUT; larger files switch to a chunked S3
 * multipart upload (resilient/resumable, no 5 GB ceiling) that S3 stitches back into one object.
 */
private const val MULTIPART_THRESHOLD = 64L * 1024 * 1024

/**
 * The `linkedRecordType` tag every design-workshop attachment is filed under.
 *
 * Spelled exactly as the web spells it (`lib/designWorkshopStore.ts`, the `uploadMediaBatch` call in
 * its sync pass), because the tag is a free string on `MediaFile` and nothing on the server
 * normalises the case. Two spellings would file one workshop's photographs in two buckets, and every
 * query that lists "this workshop's media" would silently return half of them.
 */
internal const val DESIGN_WORKSHOP_MEDIA_TAG = "designWorkshop"

/**
 * [PendingEntry.type] for a queued export-log row.
 *
 * The one outbox entry that is not a record the researcher typed. It is written down as a constant
 * because the string is matched in `createFromEntry` and produced in `recordDesignWorkshopExport`,
 * and a typo between the two would park every offline export in the queue for ever: the replay
 * would throw "Unknown offline entry type", which `isTransient` calls worth retrying.
 */
internal const val OFFLINE_EXPORT_RECORD = "designWorkshopExport"

/**
 * [PendingEntry.type] for a DESIGN REVIEW RATING captured with no signal.
 *
 * The second entry type in this queue that is not a record — see [OFFLINE_EXPORT_RECORD] above — and
 * it is here for the reason that one is: the queue's dispatch is a `when` over this string, so the
 * constant is what stops a typo between the producer ([WorkshopRepository.submitDesignRating]) and
 * the replay (`createFromEntry`) from parking every queued judgement for ever behind "Unknown
 * offline entry type", which [WorkshopRepository.isTransient] calls worth retrying.
 *
 * THE REPLAY IS SAFE TO REPEAT, which is not true of most entries here and is why this one needs no
 * `targetId` and no de-duplication key. `POST /design-ratings` is idempotent under replay by
 * construction: `@@unique([stageEntryId, reviewerId, round])` makes a second row unrepresentable and
 * a delivery whose device clock is not newer than the stored row's writes nothing and answers with
 * the row the server already holds.
 */
internal const val OFFLINE_DESIGN_RATING = "designRating"

/**
 * A DESIGNER'S OWN QUESTIONNAIRE, STARTED WITH NO CONNECTION.
 *
 * ── WHY THIS TYPE EXISTS, AND WHY IT IS NOT `"questionnaire"` ─────────────────────────────────────
 *
 * `"questionnaire"` above is a questionnaire INTERVIEW — a sitting with an artisan, posted to
 * `/questionnaire/interviews`. This is the designer-authored FORM, posted to `/questionnaires`. Two
 * tables, two routes, two families of screen; the plural/singular pair this product deliberately
 * keeps apart (`docs/`'s own note: "Do NOT unify them"). One outbox type covering both would replay
 * a form against the interview route and lose it to a 422 nobody sees.
 *
 * ── WHAT IT CARRIES, AND WHAT IT DELIBERATELY DOES NOT ────────────────────────────────────────────
 *
 * The questionnaire ROW only — its title, its description and the workshop it is attached to. NOT
 * its sections and NOT its questions, and that is a decision rather than a first instalment: those
 * are separate creates against `/questionnaires/{id}/sections` and `.../questions`, and the id they
 * need does not exist until the row above them lands. The offline pass has no way to thread a
 * server-minted id from one queued entry into the next — `PendingEntry.targetId` addresses a record
 * that ALREADY exists — so queuing a whole tree would produce sections pointing at nothing.
 *
 * So what a designer gets offline is what they actually asked for in a courtyard: the form EXISTS,
 * with its name and its workshop, and it is on the server the moment there is signal. Writing the
 * questions is the part that wants a screen and a connection, and the list says so.
 *
 * NO MEDIA, ever. A questionnaire form has no attachment, so the entry is queued with an empty item
 * list and reaches Synced the moment the POST returns.
 */
internal const val OFFLINE_CUSTOM_QUESTIONNAIRE = "customQuestionnaire"

/**
 * [PendingEntry.type] for an entry that CREATES NOTHING and only carries files to a record that is
 * already on the server. [PendingEntry.targetId] holds that record's id and [PendingEntry.label]
 * names it for the tray.
 *
 * ── THE LOSS THIS EXISTS FOR, WHICH NEEDED NO OFFLINE AT ALL ──────────────────────────────────
 *
 * `uploadAttachments` (MainActivity.kt) runs strictly AFTER the record POST has landed. Signal dying
 * in between produced a record saved on the server, photographs held nowhere but as content Uris in
 * a Compose `remember` block pointing at `cacheDir/field-captures/`, and this instruction:
 *
 *     "The record was saved — check your connection and re-open it from "Update existing" to
 *      re-attach the media."
 *
 * The designer could not carry that out. The Uris die with the screen, `cacheDir` is emptied by
 * Android without asking, and re-opening the record to re-attach was itself an edit — which the
 * outbox refused. So the sharpest media-loss path in the app was the one that happened on a GOOD
 * connection that faltered for ten seconds, and it was silent about it afterwards.
 *
 * An entry of this type is queued at the moment those uploads fail, with the bytes copied out of
 * `cacheDir` into `filesDir/outbox/media/` first, so the promise the sentence makes is one the app
 * can keep. `linkTargetFor` sends them to [PendingEntry.targetId]; `createFromEntry` performs no
 * request for this type at all, so a replay cannot mint a second record.
 */
internal const val OFFLINE_MEDIA_ONLY = "recordMediaOnly"

/**
 * How many workshops the tray's re-pick dialog asks for.
 *
 * LARGER THAN THE FORM PICKER'S TWENTY, on purpose. `DesignWorkshopPicker` asks for twenty because a
 * long scroll on a form is a longer route to an answer the designer already has in mind, and a
 * sentence under it names what reaches the rest. This dialog has no such sentence and no such
 * elsewhere to point at: it is the last route out of a parked entry, and a workshop that falls off
 * the end of this page is a record a designer cannot un-park at all. Fifty is what the design
 * document's cap rule asks for — a number chosen so it is not reached rather than one that trims —
 * and the dialog still says so on screen when it bites.
 */
private const val REPICK_PAGE = 50

/**
 * The ceiling on a `.dpwq` file as it sits on disk, checked before a byte of it is read into memory.
 *
 * The measured questionnaire is 8,501 bytes gzipped. 4 MB is 490 times that, so nothing real is near
 * it, and it stops [WorkshopRepository.receiveQuestionnaireHandoff] pulling a 4 GB file off an SD card
 * into the heap because somebody picked the wrong thing in the document picker. The separate ceiling
 * on the INFLATED size — the decompression bomb, which this one does nothing about — is
 * [QUESTIONNAIRE_BUNDLE_MAX_INFLATED].
 */
internal const val QUESTIONNAIRE_HANDOFF_MAX_FILE = 4 * 1024 * 1024

/** MIME type for the .xlsx report workbook (OOXML spreadsheet). */
private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * When a workshop actually took place, as a sortable ISO-8601 timestamp.
 *
 * `GET /workshops` orders rows by `createdAt` like every other record list, but for a researcher in
 * the field "the most recent workshop" means the most recent date of OCCURRENCE — so we prefer the
 * workshop's own `startDate`, fall back to the single-day `date`, and only use `createdAt` when the
 * row carries neither. Every value is ISO-8601, so lexicographic ordering is chronological.
 */
fun WorkshopDetailDto.occurrenceDate(): String = startDate ?: date ?: createdAt ?: ""

/** Reader for API error bodies only — lenient, because a failing server can return anything. */
private val errorBodyJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The message the API meant the user to read; failing that, the exception's own text, and only then
 * [fallback]. Never swallows a gateway/transport failure ("HTTP 504 Gateway Time-out", "Unable to
 * resolve host") behind a generic sentence — that text is the one clue that the save never landed.
 *
 * Retrofit collapses every non-2xx response into an `HttpException` whose `message` is just
 * "HTTP 409 Conflict" — nothing a researcher can act on when what they need to know is WHICH artisan
 * already holds the Aadhaar number they typed. FastAPI puts the usable text in `detail`, in one of
 * three shapes, all unwrapped here:
 *
 * - a plain string, from `raise HTTPException(detail="…")`;
 * - an object carrying a `message`, e.g. the artisan identity 409, whose message names the existing
 *   artisan and their place;
 * - a list of Pydantic validation errors (422), where each `msg` holds the field validator's own
 *   wording ("That Aadhaar number fails its checksum…") behind a "Value error, " prefix worth
 *   stripping. Those messages are written for the person filling the form, so they are surfaced
 *   verbatim rather than replaced with something generic.
 *
 * Retrofit buffers the error body, but reading it CONSUMES the buffer — call this once per failure.
 */
fun Throwable.apiErrorMessage(fallback: String): String = apiRefusal(fallback).message

/**
 * A refusal the server ANSWERED with, read from the error body ONCE and split into the two facts a
 * caller needs: what to put on screen, and whether anybody can do anything about it.
 *
 * THE TWO HAVE TO COME OUT OF ONE READ. Retrofit buffers the error body and `string()` consumes the
 * buffer, so a second pass over it hands back an empty string — which is why [apiErrorMessage] has
 * always carried "call this once per failure". Asking the same exception "what did it say" and "was
 * it a schema refusal" as two calls would silently answer the second one no, for every failure,
 * which is the sort of bug that only shows up as a stage that never syncs.
 */
data class ApiRefusal(
    /** The sentence the API meant a person to read, or the best available fallback. */
    val message: String,
    /**
     * Did the server refuse the SHAPE of the request rather than anything a person entered?
     *
     * `APIModel` is `extra="forbid"` (`backend/app/schemas/common.py`), so a client that sends a key
     * the server does not know gets a 422 whose body names it with pydantic's own discriminator:
     *
     *     {"detail":[{"type":"extra_forbidden","loc":["body","entries",0,"merge"], …}]}
     *
     * `type` is matched, never the prose: the message is written for people and may be reworded or
     * translated, while the discriminator is part of pydantic's contract. The port of
     * `isSchemaRefusal` in `frontend/lib/offline.ts`, matched key for key.
     *
     * IT IS NOT HYPOTHETICAL. On 2026-08-08 a client sent the then-new `merge` flag to an API that
     * predated it and every stage save came back "merge: Extra inputs are not permitted". A handset
     * updates when it next sees wifi and the API updates when somebody deploys it, so a client
     * running ahead of the server is an ordinary state here rather than a mistake — and the one
     * refusal whose fix is an update rather than an edit.
     */
    val schemaSkew: Boolean,
    /**
     * THE FIELDS THE SERVER ITSELF NAMED, when it named any. Empty on every answer that did not.
     *
     * Pydantic puts the offending field in `loc` — `{"loc":["body","designWorkshopId"], …}` — and
     * that is the difference between a tray row that says *"points at a design & prototype workshop
     * that is not on the server"* and one that says *"Record not found"* and leaves a designer
     * guessing which of the two workshop boxes on the form is the problem.
     *
     * OUT OF THE SAME SINGLE READ as the other two facts, for this class's whole reason: Retrofit
     * buffers the error body and `string()` consumes the buffer, so asking a second question of the
     * same exception silently answers it with nothing — here, an empty list on every refusal, which
     * would look exactly like a server that never names fields.
     *
     * `body` and every numeric index are stripped, so a nested `["body","steps",0,"productId"]`
     * answers `["steps","productId"]` and the caller can still find the key it knows. Defaulted, so
     * every existing construction of this class is unchanged.
     */
    val namedFields: List<String> = emptyList(),
)

/** See [ApiRefusal]. Call once per failure — it consumes the buffered error body. */
fun Throwable.apiRefusal(fallback: String): ApiRefusal {
    val plain = message?.takeIf { it.isNotBlank() } ?: fallback
    // Not an HTTP failure at all (no connection, timeout, serialization): the platform message is all
    // there is, and it is more informative than anything this function could invent.
    val http = this as? HttpException ?: return ApiRefusal(plain, schemaSkew = false)
    val raw = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return ApiRefusal(plain, schemaSkew = false)
    val detail = (runCatching { errorBodyJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject)
        ?.get("detail")
        ?: return ApiRefusal(plain, schemaSkew = false)
    // Only a 422 qualifies: a 500 carrying the same words is a server fault, not a dialect mismatch,
    // and no update to either side is going to change it.
    val skew = http.code() == 422 && (detail as? JsonArray)?.any { entry ->
        ((entry as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "extra_forbidden"
    } == true
    return ApiRefusal(detailMessage(detail) ?: plain, schemaSkew = skew, namedFields = detail.locFields())
}

/**
 * The field names inside a pydantic error list's `loc` entries, in the order the server gave them.
 *
 * `"body"` is dropped because it is the request itself and names nothing, and integers are dropped
 * because they are positions in a list rather than columns — `["body","steps",0,"productId"]` is
 * about `productId`, and a caller matching against a set of column names would find nothing in the
 * `0`. Anything that is not a pydantic error list answers empty, which is the honest reading of "the
 * server named no field".
 */
private fun JsonElement.locFields(): List<String> =
    (this as? JsonArray).orEmpty().flatMap { entry ->
        ((entry as? JsonObject)?.get("loc") as? JsonArray).orEmpty().mapNotNull { part ->
            (part as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it != "body" }
        }
    }.distinct()

/**
 * Was this refusal ANSWERED BY THE APPLICATION, and if so what did it say?
 *
 * Null means the body was not FastAPI's — no JSON, or JSON with no `detail`. That distinction is not
 * decoration: 502, 503 and 504 are the codes `ApiClient` documents CloudFront returning when this
 * origin is slow or briefly unwell, so a status code alone cannot tell "the server has no
 * transcription provider" from "the gateway could not reach the server just now", and the two lead a
 * designer to completely different next moves. The gateway writes an HTML error page; the route
 * writes `{"detail": "…"}`.
 *
 * CONSUMES THE BUFFERED ERROR BODY, exactly like [apiRefusal] — call one of them, once, per failure.
 */
private fun HttpException.serverDetail(): String? {
    val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return null
    val body = runCatching { errorBodyJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    val detail = body?.get("detail") ?: return null
    return detailMessage(detail)
}

/**
 * A 429 read ONCE, into the sentence and the four facts that decide whether it is worth remembering.
 *
 * Everything comes out of a single `errorBody().string()` for [ApiRefusal]'s stated reason: Retrofit
 * buffers the error body and reading it CONSUMES the buffer, so asking the same exception two questions
 * silently answers the second one with nothing. Here that would mean either a refusal with no sentence
 * on screen or a cap that was never written down — and the second costs a six-megabyte upload per field
 * for the rest of the day.
 *
 * Both key names are the server's own and are checked against it rather than remembered: `dictationDay`
 * from `dictation_cap.allowance_payload`, `retryAfterSeconds` from `app/scale/rate_limit.py`. Missing
 * keys are not an error — [DwDictationCapRefused] documents what each absence means and which reading
 * wins when a body carries neither.
 */
private fun HttpException.dictationCapRefusal(): DwDictationCapRefused {
    val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
    val body = raw
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { errorBodyJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject
    fun number(key: String): Int? = (body?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    return DwDictationCapRefused(
        detail = body?.get("detail")?.let { detailMessage(it) },
        day = (body?.get("dictationDay") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        limit = number("dictationsLimit"),
        retryAfterSeconds = number("retryAfterSeconds"),
    )
}

/** Pull the human-readable text out of whichever `detail` shape FastAPI returned. */
/**
 * FastAPI's `detail` out of an error body this caller already has as a String.
 *
 * The twin of [HttpException.serverDetail], for the two questionnaire downloads. Those are declared
 * `Response<ResponseBody>` so that a 200 can be streamed to disk without Retrofit buffering it — and
 * a non-2xx therefore arrives as a RESPONSE rather than as an exception, so the extension above,
 * which reaches through `HttpException.response()`, has nothing to be called on.
 *
 * It matters that the sentence survives: the 403 on `/xlsx` is the one place the server tells a
 * designer that the question set exists and is theirs to take, and "HTTP 403" would send them to
 * find an admin for a file they never needed.
 */
private fun errorBodyDetail(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val body = runCatching { errorBodyJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    val detail = body?.get("detail") ?: return null
    return detailMessage(detail)
}

private fun detailMessage(detail: JsonElement): String? = when (detail) {
    is JsonPrimitive -> detail.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonArray -> detail
        .mapNotNull { entry ->
            ((entry as? JsonObject)?.get("msg") as? JsonPrimitive)?.contentOrNull
                ?.removePrefix("Value error, ")?.trim()?.takeIf { it.isNotEmpty() }
        }
        .distinct()
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }
    else -> null
}

/**
 * Was this failure the server REFUSING THE ACCOUNT, as opposed to refusing the credentials?
 *
 * THE PRODUCTION FAILURE THIS PREVENTS. A designer whose roster row was suspended is turned away by
 * `POST /auth/login` with a 403 and a sentence explaining that their access was revoked and whom to
 * contact. The sign-in screen used to render `HttpException.message` — the string "HTTP 403
 * Forbidden" — or, worse, a hand-written "invalid email or password" fallback. Either way the person
 * reads "wrong password", tries their password again, tries the other one, and then resets a
 * password that was never wrong; the reset succeeds, the next sign-in is refused identically, and
 * they conclude the app is broken rather than that their access was withdrawn. Nobody involved ever
 * sees the sentence the server wrote for exactly this moment.
 *
 * 401 is deliberately NOT included. That one really is "these credentials are wrong", and treating
 * it as an account refusal would tell somebody who fat-fingered their password to go and email an
 * administrator.
 *
 * Reads only the STATUS CODE and never the body, so it is safe to call before [apiErrorMessage] —
 * which consumes the buffered error body and can therefore only be called once per failure.
 */
fun Throwable.isAccountRefusal(): Boolean = (this as? HttpException)?.code() == 403

/**
 * WHAT a refused sign-in was refused FOR, so the card can draw the right thing around the server's
 * sentence.
 *
 * ── WHY THE CATEGORY IS NEEDED AT ALL ────────────────────────────────────────────────────────────
 *
 * [isAccountRefusal] answers "was this the account rather than the credentials", and until the
 * platform allow-list existed that was the whole question — a 403 meant one thing, a suspended
 * designer, and the card headed itself "Your access to this app has been withdrawn". There are now
 * five refusals behind that one status code, and FOUR OF THEM MAKE THAT HEADING A LIE. Nothing has
 * been withdrawn from a person who is waiting to be approved for the first time; telling them so
 * sends them to argue with an administrator about an access they never had, which is the same class
 * of wrong answer as "invalid email or password" and the reason this feature exists.
 *
 * ── WHY IT READS A HEADER AND NOT THE MESSAGE ────────────────────────────────────────────────────
 *
 * The sentences are English written for the person reading them and they WILL be reworded. A client
 * that matched on prose would silently stop distinguishing "awaiting approval" from "your access was
 * suspended" the first time somebody fixed a comma, and the screen would go on looking correct. The
 * server therefore classifies its own refusal in `X-Access-Status` (see `ACCESS_STATUS_HEADER` in
 * backend/app/api/routes/auth.py), which carries nothing the sentence does not already say in words.
 *
 * ── UNKNOWN IS A REAL ANSWER AND MUST STAY ONE ───────────────────────────────────────────────────
 *
 * A phone in the field may be talking to a deployment older than itself, or through a proxy that
 * strips unknown headers. [AccessRefusal.UNCLASSIFIED] is what that looks like, and the card draws
 * neutral chrome around the server's own words rather than guessing at a category. Guessing is the
 * only way this can produce a WRONG heading, which is worse than producing none.
 *
 * READS ONLY THE STATUS AND THE HEADERS, NEVER THE BODY, so it is safe to call before
 * [signInErrorMessage] — which consumes the buffered error body and can therefore be called once.
 */
enum class AccessRefusal {
    /** Waiting for an administrator. NOT a password problem, and not something that was taken away. */
    PENDING,

    /** An administrator reviewed the request and said no. Trying again will not reopen it. */
    REJECTED,

    /** The allow-list barred this address after it had been admitted. */
    SUSPENDED,

    /** The DESIGNER EMPANELMENT ended — a different list, a different remedy. See `assert_roster_admits`. */
    DESIGNER_SUSPENDED,

    /** The approval queue is full, so the request could not even be RECORDED. Nobody will see it. */
    QUEUE_FULL,

    /** 401 — the credentials really are wrong. Deliberately never merged with the five above. */
    BAD_CREDENTIAL,

    /** Refused, but this build cannot say why: no header, or no response at all. Say only what the server said. */
    UNCLASSIFIED,

    /** Not a refusal — a network failure, a timeout, a 500. The card treats it as neither. */
    NOT_REFUSED,
}

/** The header the API classifies a refused sign-in with. Spelled once; mirrors the server constant. */
const val ACCESS_STATUS_HEADER = "X-Access-Status"

fun Throwable.accessRefusal(): AccessRefusal {
    val http = this as? HttpException ?: return AccessRefusal.NOT_REFUSED
    if (http.code() == 401) return AccessRefusal.BAD_CREDENTIAL
    if (http.code() != 403 && http.code() != 503) return AccessRefusal.NOT_REFUSED
    return when (http.response()?.headers()?.get(ACCESS_STATUS_HEADER)?.trim()?.uppercase()) {
        "PENDING" -> AccessRefusal.PENDING
        "REJECTED" -> AccessRefusal.REJECTED
        "SUSPENDED" -> AccessRefusal.SUSPENDED
        "DESIGNER_SUSPENDED" -> AccessRefusal.DESIGNER_SUSPENDED
        "NOT_RECORDED" -> AccessRefusal.QUEUE_FULL
        // A 503 with no header is an ordinary outage and not a statement about this person, so it
        // must not be dressed as one; a 403 with no header is a refusal we cannot categorise.
        else -> if (http.code() == 503) AccessRefusal.NOT_REFUSED else AccessRefusal.UNCLASSIFIED
    }
}

/**
 * The sentence to put on the sign-in screen for a failed sign-in.
 *
 * The server's own `detail` wins wherever there is one, because it is the only text that knows WHY
 * this particular attempt was refused — a suspended roster row, an account with no password set, a
 * Google token that does not verify. The fallbacks are split by status for the same reason: a
 * network failure must not be reported as bad credentials (the message would send the user to reset
 * a password when the real problem is that they are in a basement), and a 403 must not be either.
 *
 * Call this ONCE per failure: it reads the buffered error body, and reading consumes it.
 */
fun Throwable.signInErrorMessage(): String {
    val fallback = when ((this as? HttpException)?.code()) {
        401 -> "That email and password were not accepted. Check them and try again."
        403 -> "This account is not allowed to sign in. Contact your administrator."
        null -> "Could not reach the server. Check your connection and try again."
        else -> "Sign-in failed. Please try again."
    }
    return apiErrorMessage(fallback)
}

/** Files in flight at once. Matches the web's UPLOAD_CONCURRENCY; see docs/MEDIA_PIPELINE.md. */
private const val UPLOAD_CONCURRENCY = 3

/**
 * Which generation of REF-picker answer a workshop-owned cache file holds. See
 * [dwReferenceCacheOwner]; generation 1 is every build that never sent `scope`.
 */
private const val DW_REFERENCE_CACHE_GENERATION = 2

/**
 * The owner half of a reference cache key — the workshop id, plus the generation of the ANSWER that
 * is filed under it.
 *
 * WHY THE HANDSET CANNOT SIMPLY START SENDING `scope` AND BE DONE. [DwReferenceStore.cacheKey] files a
 * WORKSHOP-scoped list under the workshop's own id and an ALL-scoped one under the literal "ALL".
 * Every build before this one sent no `scope` at all, so every WORKSHOP-owned file already on a phone
 * in the field holds an ALL-scoped answer — the first fifty rows of the whole table, name-ascending —
 * under a key that promises this workshop's roster. Nothing in the file distinguishes it from a
 * correctly narrowed one: [DwReferenceList] records the model, the filter and the fetch time, and not
 * the scope. That stale copy is served FIRST, before the network is tried at all (step 1 of
 * [WorkshopRepository.designWorkshopReferences]'s cache-first order), and on a phone with no signal it
 * is served forever — which is the phone this fix exists for. The wire fix alone would therefore leave
 * the defect standing on exactly the devices that already have the data.
 *
 * [DW_REFERENCE_CACHE_GENERATION] is appended to the owner so a pre-fix file can never be reached by
 * exact key again. This is the "bump the file-name scheme" half of the remedy, done at the one place
 * that composes the key rather than inside the store: `cacheKey` is a pure function of what it is
 * given, it has exactly one caller, and a generation is a property of the ANSWER this repository
 * fetched, not of the filing system that holds it.
 *
 * A BUMP ALONE RETIRES NOTHING, which is the thing to understand before touching either side of it.
 * Making the exact key miss is only half a retirement: a miss is the exact condition that routes
 * [WorkshopRepository.designWorkshopReferences] into [DwReferenceStore.anyForModel], and while that
 * fallback merged on a model-only prefix it merged the retired file back in — same wrong list, one
 * function later. The stamp does its job only because `anyForModel` now fences its merge to files
 * that carry THIS owner segment, generation and all. Bumping the generation without that fence is a
 * no-op that reads like a fix, which is worse than leaving it alone.
 *
 * ONLY WORKSHOP-SCOPED ENTRIES ARE RETIRED, which is the whole reason for doing it this way instead of
 * wiping the directory. `cacheKey` ignores the workshop id entirely when the scope is ALL, so every
 * ALL-scoped register a device holds — participants, sketches, prototypes, tool documentation, the
 * ALL-scoped artisan list — keeps its file and keeps working offline. Deleting those to fix a
 * WORKSHOP-scope bug would strip a phone of lists it had every right to keep, which is a smaller
 * version of the harm this file refuses to cause elsewhere (see [DwReferenceStore.store]'s refusal to
 * let an empty fetch overwrite a populated cache).
 *
 * If a future change alters what a stored list MEANS again — a different narrowing, a different server
 * default — bump the generation rather than writing a migration. There is no way to tell a correctly
 * narrowed list from a wrongly narrowed one after the fact, so the only honest move is to declare the
 * old ones unreadable and let the next successful fetch write the truth.
 *
 * A blank id is returned unchanged. It is not an owner — it is a workshop that has never reached the
 * server, whose fetch is refused a few lines later anyway — and stamping a generation onto nothing
 * would only invent a second spelling of the store's "unnamed".
 */
internal fun dwReferenceCacheOwner(workshopId: String): String =
    if (workshopId.isBlank()) workshopId else "$workshopId-$DW_REFERENCE_CACHE_GENERATION"

class WorkshopRepository(
    private val api: WorkshopRepositoryApi,
    private val tokenStore: TokenStore
) {
    // Generous timeouts (large videos over slow field connections) + automatic connection retry.
    private val storageClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    private val offlineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /**
     * The artisan columns this app may hand back as NULL on a PATCH — the server's
     * `api/routes/artisans._CLEARABLE_COLUMNS` intersected with the boxes the artisan form owns and
     * seeds from the record it is editing. See [artisanPatchBody] for why sending a null for anything
     * else would be worse than the bug this fixes.
     */
    private val ARTISAN_CLEARABLE_COLUMNS = listOf(
        "localName",
        "gender",
        "phone",
        "email",
        "address",
        "notes",
        "dateOfBirth",
        // The reason this whole mechanism was written: the column arrived on 2026-08-23 with a clear
        // button beside it and two new comments in the API promising that "a date typed into the
        // wrong box has to be retractable from the form that typed it". It was not, from this form.
        "craftStartDate",
        "experienceYears",
        // Added 2026-08-30 alongside `MainActivity.kt`'s artisan form finally drawing the
        // `ExperienceFields` months picker — see `ArtisanCreateRequest.experienceMonths`'s own KDoc,
        // which names this exact list as where the key has to appear before an emptied months box
        // can clear the column at all. Without it here, a null in this scalar's position on the
        // request body is silently dropped by the PATCH's `exclude_unset` read on the server, and
        // the stored value survives an edit the form told the designer had removed it.
        "experienceMonths",
        "dos",
        "donts"
    )
    // Mirrors the Retrofit converter's config (ApiClient.kt:42) so a body re-encoded here to carry the
    // checksum is byte-identical to the one the plain call would have sent — same omitted nulls, same
    // omitted defaults. A `processingRequests: []` that should have been absent changes what the
    // server does with the file.
    private val completeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val syncMutex = Mutex()
    private val sweptStagedObjects = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        /*
          THE DICTATION CONTROL CANNOT BE HANDED A REPOSITORY, so it is left one here.

          `DwDictationButton` is drawn into a text field's trailing-icon slot from two call sites, and
          only one of them has a repository to pass: `RichTextEditor` is a general-purpose editor used
          in previews with no data layer at all. Threading a parameter would therefore have produced a
          handset where server dictation works in a short prose field and silently does not in a long
          one — two dictation behaviours on one form, which is worse than either answer alone.

          The alternative considered and rejected was a second HTTP stack built from
          `ApiClient.retrofit(TokenStore(context))` inside the control. That would opt dictation out of
          the gateway retry this origin needs and out of the auth header, and it would stand up an
          OkHttp client per field on a screen that draws hundreds of them.

          Publishing `this` from a constructor is only safe because nothing dereferences it until a
          designer taps a microphone, which cannot happen before `MainActivity.onCreate` has returned.
          See [DwDictationRun.repository].
        */
        DwDictationRun.adopt(this)
    }

    fun hasToken(): Boolean = !tokenStore.getToken().isNullOrBlank()

    /** Last known signed-in profile, used for instant, persistent login across resumes. */
    fun cachedUser(): UserDto? = tokenStore.getUser()

    // ── Design & Prototype Workshops ─────────────────────────────────────────────────────────────
    //
    // Every capture screen for the 22-stage record renders from [designWorkshopSchema] rather than
    // from hand-written forms. The pattern below is the same one at every entry point in this block:
    // reach for the network, and where the network is the difference between working and not
    // working, fall back to what is already on the device rather than propagate the failure. A
    // design workshop is filled in over two weeks in a courtyard, and an exception thrown because a
    // GET timed out is an exception thrown at a designer who cannot do anything about it.

    /**
     * The field registry, cached durably by its `version`.
     *
     * NEVER THROWS ON A NETWORK FAILURE. The fetch is attempted, and whatever it returns replaces the
     * cache; when it fails, [StageSchemaStore.load] answers from `filesDir` and, failing that, from
     * the copy built into the APK. The one thing that can still throw is a build that shipped without
     * the bundled asset, which is a packaging error rather than a field condition.
     *
     * [forceRefresh] exists for the pull-to-refresh gesture on the workshop list. The default path
     * skips the request entirely once a registry is already in memory, because opening each of 22
     * stage screens must not mean 22 round trips on a connection that is charged by the megabyte.
     */
    suspend fun designWorkshopSchema(
        context: Context,
        forceRefresh: Boolean = false
    ): SchemaResponse {
        val known = StageSchemaStore.peek()
        if (known != null && !forceRefresh) return known
        runCatching { api.designWorkshopSchema() }
            .onSuccess { fetched ->
                // An empty payload is not a registry. Storing one would overwrite a good cache with
                // nothing and leave 22 stages with no fields on them until the next successful fetch.
                if (fetched.stages.isNotEmpty()) StageSchemaStore.store(context, fetched)
            }
        return StageSchemaStore.load(context)
    }

    /**
     * The six report templates, with the registry's own list as the offline answer.
     *
     * The fallback is not a guess: `REPORT_TEMPLATE` in the registry carries the same ids and the same
     * display names the `/templates` endpoint serves, so a designer choosing a template offline picks
     * from exactly the set the server would have offered. Only the descriptions are missing.
     */
    suspend fun designWorkshopTemplates(context: Context): List<ReportTemplateDto> =
        runCatching { api.designWorkshopTemplates() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { StageSchemaStore.load(context) }
                .getOrNull()
                ?.enums
                ?.get("REPORT_TEMPLATE")
                ?.map { ReportTemplateDto(id = it.value, name = it.label) }
                .orEmpty()

    suspend fun designWorkshops(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null,
        statusFilter: String? = null,
        craftName: String? = null,
        state: String? = null,
        mineOnly: Boolean = false
    ): DesignWorkshopPageDto = api.designWorkshops(
        page = page,
        pageSize = pageSize,
        search = search?.trim()?.takeIf { it.isNotEmpty() },
        statusFilter = statusFilter?.takeIf { it.isNotBlank() },
        craftName = craftName?.trim()?.takeIf { it.isNotEmpty() },
        state = state?.takeIf { it.isNotBlank() },
        mineOnly = mineOnly
    )

    /**
     * The design workshop this account was most recently given access to. See the API declaration.
     *
     * NO CACHING AND NO FALLBACK. It is one small read that every record form issues once on open,
     * and a stale answer here would prefill a workshop the designer has since been removed from —
     * wrong in the permissive direction, which is the one direction a picker must never be wrong in
     * (`WorkshopSelect.tsx` states the rule for the ordinary workshop list and it holds here).
     * A failure is the caller's to swallow: a record form must open on a bad connection.
     */
    suspend fun designWorkshopDefaultForMe(): DesignWorkshopDefaultDto = api.designWorkshopDefaultForMe()

    /**
     * File a new design workshop, with the designer keys FOLDED HERE rather than by the caller.
     *
     * ── ONE NORMALISER, AT THE TRANSPORT, BECAUSE THERE ARE TWO WRITERS AND THEY ARE A FORTNIGHT
     * APART ────────────────────────────────────────────────────────────────────────────────────
     *
     * `CreateWorkshopDialog` posts one of these the moment an admin taps Start, and `WorkshopSync`
     * posts the other for a workshop that was started in a courtyard — possibly days later, from a
     * draft, on a build that may by then be older than the one that wrote it. Both hand over what
     * the picker held; exactly what reaches the wire is decided ONCE, here, so those two requests
     * cannot differ for the same choice.
     *
     * And the fold is not cosmetic. [DesignWorkshopCreateBody.designerUserIds] must be OMITTED
     * unless there is genuinely a second designer: `APIModel` is `extra="forbid"`, so an API that
     * predates the field answers 422 `extra_forbidden` to a body that merely CARRIES the key, a
     * 4xx is never queued, and the ordinary one-designer create would take a whole fortnight down
     * with it. [dwDesignerCreateFields] is that three-way answer and this is the only thing that
     * asks it. `createDesignWorkshop` in `frontend/lib/designWorkshops.ts` normalises at the same
     * point and for the same reason.
     */
    suspend fun createDesignWorkshop(body: DesignWorkshopCreateBody): DesignWorkshopDto {
        val designers = dwDesignerCreateFields(
            chosen = body.designerUserIds,
            lead = body.designerUserId,
        )
        return api.createDesignWorkshop(
            body.copy(
                designerUserId = designers.designerUserId,
                designerUserIds = designers.designerUserIds,
            )
        )
    }

    suspend fun designWorkshop(id: String): DesignWorkshopDetailDto = api.designWorkshop(id)

    suspend fun deleteDesignWorkshop(id: String) = api.deleteDesignWorkshop(id)

    suspend fun designWorkshopStages(id: String): StageListDto = api.designWorkshopStages(id)

    suspend fun designWorkshopStage(id: String, stageKey: String): StageBucketDto =
        api.designWorkshopStage(id, stageKey)

    /**
     * One workshop's custom definition, straight off the wire and NOT cached here.
     *
     * Named `…Raw` because the caller that screens are meant to use is
     * [designWorkshopCustomSections], which writes the answer to [DwCustomSectionStore] in the same
     * step. Two doors into one fetch would be two ideas of what "this device holds a definition"
     * means, and the whole three-state design turns on there being one.
     */
    suspend fun designWorkshopCustomSectionsRaw(id: String): DwCustomDefinitionDto =
        api.designWorkshopCustomSections(id)

    /**
     * Push one whole stage.
     *
     * `submit` stays false on every auto-save, because turning it on makes the server enforce the
     * Basic-tier required fields and 422 the request — which on a debounced save would mean a stage
     * the designer is halfway through typing refuses to sync, silently, for the rest of the day.
     */
    suspend fun saveDesignWorkshopStage(
        id: String,
        stageKey: String,
        body: StageSaveBody
    ): StageSaveResultDto = api.saveDesignWorkshopStage(id, stageKey, body)

    /**
     * Tell the office that this handset produced a report — and if it cannot be told now, QUEUE IT.
     *
     * The export log is what an office matches a delivered field copy against. Every export that
     * matters most is made in exactly the condition that used to lose this call: at the close of a
     * workshop, in a village, with no signal, minutes before the file is handed to a visiting
     * ministry officer. This was a bare pass-through wrapped in `runCatching` at the call site, so
     * offline the record was dropped on the floor and the officer's copy existed against an empty
     * log — the one comparison that would show the field copy was genuine.
     *
     * THE BYTES ARE STILL NEVER SENT. The queued entry is the ExportRecordBody and nothing else: a
     * format, a template id, a filename, a size and a checksum. A designer on a metered connection is
     * not charged thirty megabytes to prove a report was made, and the checksum is what matches the
     * file later.
     *
     * Triage is [isTransient]'s, the same as every other queued write. No signal or a 5xx queues; a
     * 4xx is the server's final answer about a bookkeeping call and is swallowed, because an export
     * that HAPPENED is not undone by a record of it that the server refuses — the file is already in
     * the designer's Downloads folder and the officer is already holding it.
     */
    suspend fun recordDesignWorkshopExport(context: Context, id: String, body: ExportRecordBody) {
        try {
            api.recordDesignWorkshopExport(id, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!isTransient(e)) return
            OfflineOutbox.enqueue(
                context,
                PendingEntry(
                    id = java.util.UUID.randomUUID().toString(),
                    type = OFFLINE_EXPORT_RECORD,
                    payloadJson = offlineJson.encodeToString(PendingExportRecord(workshopId = id, body = body)),
                    // Read by the outbox banner and by the "could not be uploaded" notice, so it has
                    // to name the thing a designer would recognise: the file they just handed over.
                    label = "Export log · ${body.fileName}",
                    createdAt = Instant.now().toString()
                )
            )
        }
    }

    /**
     * One REF field's options, cache-first and NEVER throwing on a network failure.
     *
     * The order of operations is the whole design, and it is the opposite of the obvious one:
     *
     *  1. Answer from [DwReferenceStore] immediately, whatever is there.
     *  2. Try the network. On success, replace the cache and answer again with the fresh list.
     *  3. On failure — no signal, a timeout, a 500, a workshop that exists only on this device and
     *     therefore has no id to ask about — say nothing and leave step 1's answer standing.
     *
     * Step 3 is what a designer in a courtyard experiences as "the artisan dropdown works". The
     * alternative, which is what an ordinary `suspend fun` that propagates its exception produces, is
     * a picker that throws two weeks into a study because a GET timed out, at a person who cannot do
     * anything about it and whose phone is holding a perfectly good copy of the list.
     *
     * [onList] is called ONCE for the cache and, if the fetch adds anything, once more for the fresh
     * list — rather than returning a single value — so the dropdown fills instantly from disk and
     * then quietly improves, instead of showing a spinner over a list the device already had.
     */
    suspend fun designWorkshopReferences(
        context: Context,
        workshopId: String?,
        model: String,
        scope: String,
        filterValue: String,
        onList: (DwReferenceList, truncated: Boolean) -> Unit,
    ) {
        if (model.isBlank()) return
        // NOT the bare workshop id — see [dwReferenceCacheOwner]. Every WORKSHOP-owned file already on
        // a handset was written from an un-narrowed answer, and is indistinguishable from a good one.
        val owner = dwReferenceCacheOwner(workshopId.orEmpty())
        val key = DwReferenceStore.cacheKey(model, scope, owner, filterValue)

        // Exact key first; failing that, whatever this device holds for the model AND THIS OWNER,
        // narrowed here. A phone that once cached the whole product register for this workshop can
        // serve "this artisan's products" without ever having asked the server that narrower question.
        //
        // THE SECOND LINE IS NOT A LOOPHOLE IN THE FIRST, AND IT USED TO BE. `anyForModel` merged
        // every file whose name began with the model — it was written to merge across FILTERS, but
        // the key is `model__owner__filter`, so it merged across OWNERS as well. That made the
        // generation stamp above inert: retiring a poisoned file makes the exact key MISS, and a
        // miss is exactly what routes the lookup into this fallback, which then merged the retired
        // file straight back in because it still began with "Artisan__". The un-narrowed list the
        // wire fix exists to prevent was served anyway, offline, for ever.
        //
        // The fence now lives one level down, in `DwReferenceStore.anyForModel`, because that is
        // where the file names are composed and where "same owner" can be decided from the same
        // function `cacheKey` uses. It is NOT done by refusing the fallback for WORKSHOP scope from
        // here: that would kill the cascade `productRef` depends on, which is WORKSHOP-scoped AND
        // filtered and works offline precisely because of this merge. Read the block comment there
        // before changing either half.
        //
        // `filteredBy` IS LEFT BLANK ON THE FALLBACK, AND IT IS NOT COSMETIC. The field means "the
        // parent these options were narrowed to BY THE SERVER", and this branch cannot claim it: the
        // merge above joins every file for the model and owner, filters included, and the only
        // narrowing applied afterwards is `narrowedTo` — which KEEPS an option carrying no
        // `filterValue`, and the server has never populated that column (`_reference_option` emits
        // id, label, sublabel and data and nothing else). So on this branch every option survives and
        // the list is exactly as wide as it was. It used to be stamped with `filterValue` anyway,
        // which read as a narrowing that had happened; `dwScanLocalStep` now asks this question
        // before it answers a scanned card from the cache without a request, and an over-claimed
        // stamp there is one artisan's product linked under another artisan's row.
        val cached = DwReferenceStore.load(context, key)
            ?: DwReferenceStore.anyForModel(context, model, scope, owner)
                ?.let { whole ->
                    whole.copy(filteredBy = "", items = whole.narrowedTo(filterValue))
                }
        cached?.let { onList(it, false) }

        // A workshop that has never been sent to the server has no id the references endpoint would
        // recognise. Spending a request on it buys a 404 and nothing else, so the cache above is the
        // final answer — which for an ALL-scoped model is a genuinely useful one.
        val target = workshopId?.takeUnless { it.isBlank() || isLocalOnlyWorkshop(it) } ?: return

        val fetched = runCatching {
            api.designWorkshopReferences(
                id = target,
                model = model,
                // THE SCOPE TRAVELS. It used to stop here: this function has always taken a `scope`,
                // and spent all of it on the cache key above, while the request went out without it.
                // The route defaults an absent `scope` to ALL rather than deriving one (its own
                // docstring says deriving it server-side was deliberately rejected, so that the form
                // and the server cannot hold two ideas of how wide the list is), so "not sent" was
                // read as "the client asked for everything". The four WORKSHOP-scoped REF fields on
                // the phone were answered with the first fifty rows of the whole table — strangers in
                // the artisan picker at stage 6, whose name, village and craft `hydrateFromReference`
                // then wrote onto the row and the report printed.
                //
                // `takeIf { it.isNotBlank() }` and NOT `?: "ALL"`. A blank `refScope` means the
                // registry did not say; asserting ALL here is precisely the client-invents-a-default
                // failure the schema's own KDoc forbids. Blank must reach the wire as an omitted
                // parameter, not as `scope=` — the empty string is a 422 from `reference_options`,
                // which the `runCatching` below turns into a picker that silently stops refreshing.
                scope = scope.takeIf { it.isNotBlank() },
                filterBy = filterValue.takeIf { it.isNotBlank() },
                search = null,
            )
        }.getOrNull() ?: return

        val stored = DwReferenceStore.store(
            context = context,
            key = key,
            // `fetched.options`, NOT `fetched.items`. The DTO used to declare `items`, a key the
            // server has never sent, so this line stored an empty list over and over while every
            // request returned 200 with fifty records in it. `DwReferenceStore.store` then refused
            // to overwrite a non-empty cache with the empty one — which is the right rule and is
            // why the failure looked like "this phone has never seen the register" rather than
            // like a bug.
            list = DwReferenceList(model = model, filteredBy = filterValue, items = fetched.options),
        )
        onList(stored, fetched.truncated)
    }

    /**
     * The one option a SCANNED code names, asked for by id.
     *
     * ── IT THROWS, AND IT CACHES NOTHING. BOTH ARE DELIBERATE ─────────────────────────────────
     *
     * [designWorkshopReferences] above never throws, because a picker that dies on a timed-out GET
     * is a picker that stops working in the courtyard it exists for. This one is the opposite case
     * and takes the opposite rule, the same way [designWorkshopIdentityOcr] does: a by-id lookup has
     * NO offline answer to degrade to. `id` is in none of the server's search columns, so no cached
     * list can be interrogated for a record it does not already hold, and a silent null here would
     * reach the designer as "no such record" for a record that is merely out of reach. The caller
     * turns the throw into a sentence that says there is no signal and that nothing was changed.
     *
     * NOTHING IS WRITTEN TO [DwReferenceStore], and that is the trap this signature exists to avoid.
     * The answer to a by-id request is at most ONE option, and it arrives under the same (model,
     * scope, filter) triple the whole register is cached under. Storing it would replace a fifty-name
     * artisan list with a one-name one on the device that is least able to refetch it — offline, in
     * the field, which is exactly where a scan is used. So this returns the payload and touches no
     * file. The list fetch is left to refresh the cache on its own terms.
     *
     * [recordId] IS SENT ALONGSIDE [scope] AND [filterValue], never instead of them — see the wire
     * comment on `WorkshopRepositoryApi.designWorkshopReferences`. The server needs the scope to
     * answer `outOfScope` at all, because that flag means "found only with the WORKSHOP clause
     * lifted", and a request that omitted the scope would have no clause to lift.
     *
     * IT THROWS `HttpException` FOR A SERVER THAT ANSWERED AND ANYTHING ELSE FOR ONE THAT DID NOT,
     * which is Retrofit's split and the one the caller spends: "there is no signal" and "the lookup
     * failed" send a designer out of the building or not, and getting that backwards puts an offline
     * message on a 500. `RecordCodeLookup.lookUpRecordCode` makes the same distinction for the same
     * reason.
     */
    suspend fun designWorkshopReferenceById(
        workshopId: String,
        model: String,
        scope: String,
        filterValue: String,
        recordId: String,
    ): DwReferenceResponseDto = api.designWorkshopReferences(
        id = workshopId,
        model = model,
        // Blank must reach the wire as an OMITTED parameter and not as `scope=`, which
        // `reference_options` answers with a 422. Same rule, same reason, as the list fetch above.
        scope = scope.takeIf { it.isNotBlank() },
        filterBy = filterValue.takeIf { it.isNotBlank() },
        search = null,
        recordId = recordId,
        // ONE ROW. An id clause matches at most one, and asking for one turns a server that has
        // never heard of `recordId` into a visible `truncated: true` rather than an ordinary list
        // the caller might mistake for an answer — see the wire comment on the API, and
        // `dwScanServerAnswer`, which is the reader that depends on it.
        limit = 1,
    )

    /**
     * Read the number off a photographed identity card, ON THE SERVER — the second rung of a ladder
     * whose first rung is the handset itself.
     *
     * THE OFFLINE ANSWER IS NOT IN THIS FILE, WHICH IS WHY THIS ONE STILL THROWS. `DwIdentityCardControl`
     * asks `MlKitIdentityCardRecognizer` first, out of a model bundled in the APK, and only reaches
     * this call when the phone found nothing and there IS a connection. So the sentence this paragraph
     * used to carry — "there is no cached anything that could stand in for it" — is no longer true of
     * the feature, though it remains true of this function: there is nothing here to degrade to, and a
     * failure has to reach the designer as a sentence ("there is no connection, type the number in")
     * rather than as a silently empty result, because a silently empty result reads as "the card is
     * unreadable" and sends them photographing it again in better light for a problem that is not
     * about light.
     *
     * The bytes are read from the DURABLE copy under filesDir rather than from a content Uri, so a
     * retry a minute later still has something to send.
     */
    suspend fun designWorkshopIdentityOcr(
        context: Context,
        uri: Uri,
        // The designer's DECLARED intention for their own copy of the picture. Defaulted to the safe
        // half so a call site that has no choice to offer — and there are two — sends the same answer
        // the server would have assumed for it. See [DW_RETENTION_DEFAULT].
        retention: String = DW_RETENTION_DEFAULT,
    ): DwIdentityOcrDto {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("That photograph could not be opened on this device.")
        }
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "identity-card.jpg",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        return api.designWorkshopIdentityOcr(
            file = part,
            // Normalised through this client's copy of the server's own parser, so a value mangled
            // anywhere above lands on DISCARD here rather than being sent as something the server
            // will then resolve to DISCARD anyway — the two must not be able to disagree about what
            // was asked for, because the echo in the reply is what a screen reports back.
            retention = parseRetention(retention).toRequestBody("text/plain".toMediaType()),
        )
    }

    /**
     * Keep one already-stored identity photograph, or delete it outright.
     *
     * THROWS, like its neighbours, and here the reason is sharper than "there is no offline
     * substitute". Both outcomes change durable state: one deletes a regulated document, the other
     * writes an accountability record naming the person who chose to keep it. A failure swallowed
     * into a null would leave a designer believing a photograph of somebody's Aadhaar card had been
     * destroyed when it is still in the bucket — which is the exact belief the whole feature exists
     * to make true rather than assumed.
     *
     * A 502 from this route means NOTHING WAS DELETED: the object could not be removed from storage,
     * so the row was left pointing at it deliberately rather than being deleted anyway. The server's
     * sentence says so and says what to do next, and this client shows it rather than translating a
     * status code.
     *
     * NEVER QUEUED and never retried automatically — see the note on the route in
     * [WorkshopRepositoryApi.decideIdentityPhotograph].
     */
    suspend fun decideIdentityPhotograph(mediaId: String, decision: String): DwRetentionResultDto =
        api.decideIdentityPhotograph(
            DwRetentionDecisionBody(mediaId = mediaId, decision = parseRetention(decision))
        )

    /**
     * Ask the server for this designer's dictation ceiling and write it into the local mirror.
     *
     * WHAT IT BUYS: the refusal happening BEFORE the microphone opens, on a phone that has learned
     * nothing yet. `DwDictationRun.learnAllowance` writes the mirror from the 200 of a dictation, so
     * until this existed the FIRST dictation of every day — on every handset — paid a six-megabyte
     * upload to discover a number the server would have given for two primary-key reads. In a
     * district town the connection is scarcer than the provider credit the cap is about.
     *
     * EVERY FAILURE IS SWALLOWED AND NOTHING IS WRITTEN ON ONE. A 404 is a deployment that predates
     * the route, a 403 is an account that is not a designer, and no signal is the ordinary case; all
     * three mean "we were not told", which is exactly what an untouched mirror already says. Writing
     * anything here on a failure would be inventing an allowance, and a fabricated `dictationDay`
     * would then be compared against a real one.
     */
    suspend fun refreshDictationAllowance(context: Context) {
        val userId = cachedUser()?.id?.takeIf { it.isNotBlank() } ?: return
        val dto = runCatching { api.designWorkshopDictationAllowance() }.getOrNull() ?: return
        dwDictationAllowanceOf(dto, userId)?.let { DwDictationAllowanceStore.write(context, it) }
    }

    /**
     * Send one dictated clip to be written down — rung 2 of the ladder in [dwDictationLadder].
     *
     * THROWS, for the same reason [designWorkshopIdentityOcr] does and for one more. The first: there
     * is no cached anything that could stand in for a transcription, so a swallowed failure would
     * reach the designer as an empty box, which reads as "the phone heard nothing" and sends them
     * speaking louder at a control that was never going to answer. The second: the caller has a
     * LADDER to walk. An exception is how it learns that this rung is spent and the next one should
     * be tried, and a null would make "the server refused" and "the server heard silence"
     * indistinguishable at exactly the point where they lead to different next moves.
     *
     * NOT QUEUED, EVER. See the route declaration in [WorkshopRepositoryApi.designWorkshopDictate].
     *
     * The bytes are STREAMED from the file rather than read into memory first. A six-megabyte
     * `ByteArray` on a Galaxy M32 that is also holding Compose, a camera preview and a stage
     * draft is a cost with no purpose here — `asRequestBody` reads the file as it writes the socket,
     * and the size is taken from the filesystem for the cap check without touching the contents.
     *
     * THE THREE FAILURES IT TRANSLATES are the ones whose CODE alone cannot say what happened: the
     * route's own 503 (told from a gateway's by its FastAPI `detail` — see [DwDictationNotConfigured]
     * and [serverDetail]), the consent gate's 409, and the 429 that is either the daily cap or the
     * courtesy rate limiter. Each becomes a type carrying the server's own sentence, because the control
     * shows that sentence verbatim; everything else is rethrown untouched, so the caller still reads the
     * server's words off a 413 or a 422.
     *
     * [workshopId] IS THE SERVER'S ID AND IS REQUIRED. `POST /design-workshops/{id}/dictate` is the only
     * dictation route that can read a workshop's `dictationConsent` column, so the id is what the gate is
     * enforced against — and a nullable or defaulted parameter here would put the ungated door back
     * within reach of any call site that forgot, which is the whole defect this closed. A workshop that
     * exists only on this device HAS no server id; the ladder refuses rung 2 for it before a microphone
     * is ever opened ([DwDictationConditions.workshopOnServer]), so no caller has to invent one.
     */
    suspend fun designWorkshopDictate(
        workshopId: String,
        clip: File,
        languageTag: String,
    ): DwDictateDto {
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            // The name and type the server reads its decoder from. `dictation.m4a` + `audio/mp4`
            // describes what `DwDictationRecorder` actually produces (MPEG-4 container, AAC audio);
            // the web sends `dictation.webm` or `dictation.m4a` depending on what the browser
            // recorded, for the same reason — a hardcoded container name lies about the bytes.
            "dictation.m4a",
            clip.asRequestBody("audio/mp4".toMediaType())
        )
        try {
            return api.designWorkshopDictate(
                workshopId,
                part,
                languageTag.toRequestBody("text/plain".toMediaType()),
            )
        } catch (e: HttpException) {
            /*
              THE CONSENT GATE, READ FIRST BECAUSE THE ROUTE CHECKS IT FIRST.

              A 409 from this route is the WORKSHOP being in no state to permit the send, and almost always
              that is `DesignWorkshop.dictationConsent` not being GRANTED. The route deliberately answers
              409 rather than 403 — a 403 is about the CALLER, and this designer is entitled to dictate —
              and it writes a `detail` that is field copy, one sentence for "nobody has asked the artisan"
              and a different one for "the artisan said no", because those have different next moves.

              IT IS NOT THE ONLY 409 THE ROUTE CAN RAISE, AND THIS COMMENT USED TO SAY IT WAS ("a 409 from
              this route means one thing"). Before the gate runs, `load_workshop_or_404(..., for_edit=True)`
              answers 409 "This workshop is deleted. Restore it before editing." for a soft-deleted
              workshop — reachable only by an admin, because that helper turns everybody else away with a
              404 rather than confirm the id exists. WHAT MAKES ONE TYPE HONEST FOR BOTH is that the
              server's own sentence is what the designer reads: a deleted workshop is told it is deleted and
              named the restore, which is the right next move and not a consent question. What the type may
              NOT do is invent a state for either of them, which is why a null `detail` stays null below
              instead of being papered over with a sentence about consent.

              SO THE SENTENCE IS CARRIED, NOT THE CODE, exactly as the 503 below is. This client may not
              compose either sentence itself: it does not know WHICH state the server found, and a 409
              body carries no discriminator — guessing would tell a designer to go and ask a question that
              is already on record. Read once, for [ApiRefusal]'s reason: the buffered error body is
              consumed by the first read.

              NULL DETAIL IS KEPT AS NULL rather than papered over here, and the control falls back to
              [DW_DICTATION_CONSENT_REFUSED], which says only what a bodiless 409 proves.
            */
            if (e.code() == 409) throw DwDictationConsentRefused(e.serverDetail())
            // A 503 with a FastAPI `detail` is the route saying the deployment has no transcription
            // provider — a fact about this deployment, worth remembering for the run. A 503 without
            // one came from the gateway in front of it, means only "not now", and must NOT be
            // remembered: retiring rung 2 for the rest of a fortnight's fieldwork over one
            // CloudFront blip is the failure this branch exists to prevent.
            if (e.code() == 503) e.serverDetail()?.let { throw DwDictationNotConfigured(it) }
            // A 429 is EITHER this designer's daily allowance being spent OR the courtesy backstop in
            // front of the whole API, and the two want opposite handling — one is remembered for the
            // rest of the India-time day, the other must not be remembered at all. They are told apart
            // by the keys in the body, in ONE read, because reading the buffered error body consumes
            // it: a second pass hands back an empty string and the sentence with it. See
            // [DwDictationCapRefused].
            if (e.code() == 429) throw e.dictationCapRefusal()
            throw e
        }
    }

    /**
     * Record one workshop's answer to "may its recordings leave the device for a third party".
     *
     * THROWS, unlike almost everything else in this block, and for the viewers routes' reason: this is a
     * WRITE that says a named person took responsibility for an artisan's voice leaving the device, so
     * a failure that was swallowed and reported as success would leave the phone gating on an answer the
     * server never heard — and the server refusing every upload afterwards with a sentence the designer
     * has no way to connect to the tap they made a fortnight earlier.
     *
     * [recordedAt] IS WHEN THE ARTISAN ANSWERED, on this device's clock, and it is sent even when the
     * call happens seconds later: a consent recorded in a courtyard reaches the server on the next sync,
     * which on this fleet can be a fortnight, and the server keeps its own `createdAt` for when it
     * heard. The server refuses a stamp more than fifteen minutes in its own future rather than
     * correcting it (`dictation_consent.MAX_DEVICE_CLOCK_SKEW`), so a phone with a hand-set clock is
     * told to fix its date instead of having a fabricated moment stored for somebody's consent.
     *
     * THE RESPONSE IS DECODED LENIENTLY AND NOT DEPENDED ON. What is load-bearing is the 2xx: the answer
     * is already on this device, and this call exists to get it to the server. The route was being
     * written in parallel with this method, so the SHAPE of what it returns is unconfirmed — every field
     * of [DwConsentDecisionDto] is defaulted, exactly as [DesignWorkshopDto]'s are and for the same
     * reason, so a body that turns out to carry something else still decodes rather than turning a
     * successful write into a failure. (That is the [DwIdentityOcrDto] defect, one file over: five keys
     * declared that the endpoint never sent, and a perfect read reported as a failure.)
     */
    suspend fun designWorkshopRecordDictationConsent(
        workshopId: String,
        decision: DwTier3Consent,
        recordedAt: String?,
        note: String? = null,
    ): DwConsentDecisionDto = api.recordDesignWorkshopDictationConsent(
        id = workshopId,
        body = DwConsentDecisionRequest(
            decision = dwTier3ConsentToken(decision),
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            recordedAt = recordedAt,
        ),
    )

    // ── The five AI verbs ────────────────────────────────────────────────────────────────────────
    //
    // ALL FIVE THROW, and none of them is ever queued. The reasons are [designWorkshopDictate]'s and
    // they transfer whole: there is no cached anything that could stand in for a model's answer, so a
    // swallowed failure would reach the designer as an empty panel — which reads as "the model had
    // nothing to say" and sends them off to rewrite a perfectly good note. And a verb SPENDS MONEY,
    // counted for every run that reached a provider including one that then failed, so a run banked in
    // [OfflineOutbox] and replayed three days later would be charged against a day the designer is not
    // having, over a workshop whose consent may have been withdrawn in between.
    //
    // THE THREE FAILURES TRANSLATED are the ones whose CODE alone cannot say what happened, and the
    // list is deliberately SHORTER than dictation's by one: there is no consent type here, because a
    // verb route answers 409 for four different states with no discriminator in the body. See
    // [DwAiVerbRefused], which carries the code and the server's own sentence and classifies nothing.
    //
    // THE ALLOWANCE IS LEARNED AS IT GOES BY. There is no pre-flight route for these — grep `backend/`
    // for `ai-verb-allowance` and there is none, where dictation has one — so the numbers on the 201
    // and the refusal on the 429 are the only things this phone can be told. See [DwAiVerbAllowance].

    /**
     * Correct the spelling, grammar and punctuation of a passage, and change nothing else.
     *
     * **THE CORRECTED TEXT IS A NEW ROW AND NOTHING IS WRITTEN BACK** — not into the stage field the
     * words came from, not over the layer they were read from. The server cannot express either write
     * (`LayerWritePlan` may only name a table in `WRITABLE_TABLES` and `DwStageEntry` is not in it),
     * and this side keeps it true by returning a layer and offering no way to apply one.
     *
     * IT IS A DIFFERENT VERB FROM THE REFINEMENT THIS SERVER ALREADY DOES, which is why it has its own
     * kind: `ai.refine_transcript_text` restructures a conversation into speaker turns and, on this
     * deployment's default, translates it into English. Proofreading promises the same words, in the
     * same language, in the same order, with the spelling fixed. Two promises, two headings.
     */
    suspend fun designWorkshopProofread(
        context: Context,
        workshopId: String,
        source: DwVerbSource,
        language: String? = null,
    ): DwAiVerbResultDto = runVerb(context, DwAiVerb.PROOFREAD) {
        api.designWorkshopProofread(dwVerbWorkshopId(workshopId), dwProofreadBody(source, language))
    }

    /**
     * Write a designer's terse note out into prose. **The riskiest thing this API does.**
     *
     * **THERE IS NO LAYER PARAMETER AND THERE MUST NEVER BE ONE**, and no caller may offer a control
     * that drops the result into a field. Both halves are the plan's: an expansion INVENTS sentences,
     * which over the designer's own shorthand turns their note into their prose with them standing
     * there to judge it, and over an artisan's transcript would put invented words in a named person's
     * mouth in a document a ministry officer reads. The expansion appears BESIDE the note, named as
     * machine-written, and reaches a document only through the annexure, only after a person accepts
     * it, and only under a printed caution. A designer who wants those words in the field types them —
     * at which point they are that designer's sentences under that designer's name, which is a true
     * statement that no paste button could produce. See [dwExpandBody].
     */
    suspend fun designWorkshopExpand(
        context: Context,
        workshopId: String,
        note: String,
        language: String? = null,
    ): DwAiVerbResultDto = runVerb(context, DwAiVerb.EXPAND) {
        api.designWorkshopExpand(dwVerbWorkshopId(workshopId), dwExpandBody(note, language))
    }

    /**
     * Translate a passage. **The original stays exactly where it is; this is a sibling.**
     *
     * The failure this shape is written against is already in this database rather than hypothetical:
     * `AppSetting.transcriptionMode` defaults to REFINED_TRANSLATED, under which the media queue writes
     * an English rewrite into `MediaFile.transcriptText` — the column an annexure prints as the
     * artisan's words. Nothing here updates, supersedes or flags the source layer; both rows stay live
     * and both stay printable, so a reader who wants the artisan's own words can have them.
     *
     * BOTH LANGUAGES ARE RECORDED ON THE ROW, and "in English" is not a provenance record for a
     * translation — a reader checking it against what the artisan said has to know what they said it
     * in. `multi` is a real SOURCE and never a target; [dwTranslationTargetRefusal] says so before the
     * press rather than spending a run to be told.
     */
    suspend fun designWorkshopTranslate(
        context: Context,
        workshopId: String,
        source: DwVerbSource,
        targetLanguage: String,
        sourceLanguage: String? = null,
    ): DwAiVerbResultDto = runVerb(context, DwAiVerb.TRANSLATE) {
        api.designWorkshopTranslate(
            dwVerbWorkshopId(workshopId),
            dwTranslateBody(source, targetLanguage, sourceLanguage),
        )
    }

    /**
     * Describe a photograph or a video in one sentence — for the annexure, and for a screen reader.
     *
     * THE ACCESSIBILITY HALF IS NOT A SECOND FEATURE: a media annexure of forty photographs with no
     * descriptions is unusable to a reader with a screen reader and nearly as unusable to anybody
     * reading the .docx a year later without the designer beside them.
     *
     * [remoteMediaId] IS THE SERVER'S `MediaFile` ID AND NEVER THIS DEVICE'S. A photograph that has not
     * been uploaded names nothing up there; [dwVerbMediaRefusal] is the pre-press rung that says so, in
     * a sentence, instead of spending a round trip on a 404.
     *
     * `multi` is DROPPED by the server rather than refused for a caption — it is something a recording
     * can BE, not something one sentence can be written in — so nothing here has to special-case it.
     */
    suspend fun designWorkshopCaptionMedia(
        context: Context,
        workshopId: String,
        remoteMediaId: String,
        language: String? = null,
    ): DwAiVerbResultDto = runVerb(context, DwAiVerb.CAPTION) {
        api.designWorkshopCaptionMedia(
            dwVerbWorkshopId(workshopId),
            DwMediaVerbBody(sourceMediaId = remoteMediaId, language = language?.trim()?.takeIf { it.isNotEmpty() }),
        )
    }

    /**
     * Produce timed captions for a recording or a video, stored as cues.
     *
     * **THIS SENDS THE RECORDING TO A TRANSCRIPTION ENGINE AGAIN**, even one this workshop has already
     * transcribed, and the route calls that a defect rather than a design: the timings are the whole
     * point and every timing this system has ever received was discarded one line after being parsed.
     * On a handset that is an upload out of the designer's own data bundle, so
     * [DW_SUBTITLES_SECOND_UPLOAD_NOTE] belongs in front of the press and not in a log.
     *
     * NO LANGUAGE ARGUMENT, matching `AiMediaVerbIn`: a cue list is in whatever language was spoken.
     */
    suspend fun designWorkshopSubtitleMedia(
        context: Context,
        workshopId: String,
        remoteMediaId: String,
    ): DwAiVerbResultDto = runVerb(context, DwAiVerb.SUBTITLES) {
        api.designWorkshopSubtitleMedia(
            dwVerbWorkshopId(workshopId),
            DwMediaVerbBody(sourceMediaId = remoteMediaId),
        )
    }

    /**
     * One run of one verb: the call, the allowance it reports, and the three failures worth naming.
     *
     * THE ALLOWANCE IS WRITTEN DOWN ON THE WAY PAST, from the 201 that carries it, because there is no
     * route to ask — see [DwAiVerbAllowance] on the pre-flight this deployment does not have. A
     * response with no `aiVerbDay` leaves the mirror alone rather than overwriting it with a record
     * that can never match a day; [dwAiVerbAllowanceOf] is the one place that decides.
     *
     * A CAP REFUSAL IS REMEMBERED AND A THROTTLE IS NOT, which is the whole reason the two are told
     * apart: the cap will not clear until midnight IST, so remembering it saves a round trip per press
     * for the rest of the day, while remembering a courtesy-limiter burst would withdraw all five verbs
     * over a handful of taps. The day written for a cap refusal is THIS PHONE's reckoning of today,
     * because the verb routes' 429 carries no `aiVerbDay` at all.
     */
    private suspend fun runVerb(
        context: Context,
        verb: DwAiVerb,
        call: suspend () -> DwAiVerbResultDto,
    ): DwAiVerbResultDto {
        val userId = cachedUser()?.id?.takeIf { it.isNotBlank() }
        try {
            val result = call()
            dwAiVerbAllowanceOf(result, userId)?.let { DwAiVerbAllowanceStore.write(context, it) }
            return result
        } catch (e: HttpException) {
            val failure = e.asVerbFailure(verb)
            if (userId != null && failure is DwAiVerbCapRefused && !failure.transientThrottle) {
                val today = dwDictationIstDay()
                DwAiVerbAllowanceStore.write(
                    context,
                    dwAiVerbCapSpentRecord(
                        previous = DwAiVerbAllowanceStore.read(context, userId),
                        userId = userId,
                        today = today,
                    ),
                )
            }
            throw failure
        }
    }

    /**
     * One failed verb, read ONCE, into the type that carries what the code alone cannot say.
     *
     * Everything comes out of a single `errorBody().string()` for [ApiRefusal]'s stated reason: Retrofit
     * buffers the error body and reading it CONSUMES the buffer, so asking the same exception two
     * questions silently answers the second with nothing — here that would mean either a refusal with
     * no sentence on screen or a cap that was never written down.
     *
     * The order of the branches is the order the codes have to be read in. A 429 is the cap or the
     * courtesy limiter, told apart by `retryAfterSeconds` and never by prose. A 503 is the route saying
     * this deployment cannot run THIS VERB — but only when the body is FastAPI's: a 503 with no
     * `detail` came from the gateway in front of the origin ([ApiClient] documents 502/503/504 as what
     * CloudFront answers when this origin is slow), means only "not now", and must not be recorded as a
     * fact about the deployment. Everything else keeps its code and its sentence and is classified no
     * further — see [DwAiVerbRefused] for the four different states a 409 can be.
     */
    private fun HttpException.asVerbFailure(verb: DwAiVerb): Exception {
        val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
        val body = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { errorBodyJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject
        val detail = body?.get("detail")?.let { detailMessage(it) }
        val retryAfterSeconds =
            (body?.get("retryAfterSeconds") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return when {
            code() == 429 -> DwAiVerbCapRefused(detail = detail, retryAfterSeconds = retryAfterSeconds)
            code() == 503 && detail != null -> DwAiVerbNotConfigured(verb, detail)
            else -> DwAiVerbRefused(code(), detail)
        }
    }

    /**
     * Save one SUBTITLES layer into the device's Downloads folder, and answer with where it landed.
     *
     * **THE FILE NAME IS THE SERVER'S AND IS NEVER INVENTED WHERE IT SENT ONE.** `download_subtitles`
     * writes `subtitles-{layer}.speakers.srt` for the labelled file and `subtitles-{layer}.srt` for the
     * anonymised one, precisely so a designer with both in one folder can tell them apart — and
     * confusing those two is how a ministry is emailed the version that attributes an artisan's words
     * to a machine's guess. The fallback is reached only if a proxy strips the header, and it keeps the
     * same distinction for that reason.
     *
     * `speakers=false` IS SENT AS NO PARAMETER AT ALL rather than as `?speakers=false`, so the URL this
     * client asks for is byte for byte the one every build before the flag existed asked for.
     *
     * Spooled to the cache and copied second, exactly as [downloadQuestionnaireArtefact] does: the
     * MediaStore entry is created IS_PENDING and cleared once the bytes are there, so a transfer that
     * dies half-way cannot leave a half-written subtitle file visible in Downloads looking like one
     * somebody can play.
     */
    suspend fun downloadDesignWorkshopSubtitles(
        context: Context,
        workshopId: String,
        layerId: String,
        format: DwSubtitleFormat,
        speakers: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val response = api.designWorkshopSubtitleFile(
            id = dwVerbWorkshopId(workshopId),
            layerId = layerId,
            fmt = format.extension,
            speakers = if (speakers) true else null,
        )
        if (!response.isSuccessful) {
            // The server's own sentence and not a status code: a 422 here names the actual problem —
            // this layer is not a SUBTITLES layer, or it carries no speaker labels so a labelled file
            // would be the same file — and "download failed (HTTP 422)" throws away the only part of
            // the answer that helps.
            throw DwAiVerbRefused(response.code(), errorBodyDetail(response.errorBody()?.string()))
        }
        val body = response.body()
            ?: throw IllegalStateException("The subtitle download came back with no file in it.")
        val name = filenameFromContentDisposition(response.headers()["Content-Disposition"])
            ?: buildString {
                append("subtitles-")
                append(safeDownloadName(layerId) ?: "layer")
                if (speakers) append(".speakers")
                append('.')
                append(format.extension)
            }
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val location = persistFileToDownloads(context, tmp, name, format.mimeType)
        tmp.delete()
        location
    }

    // ── What becomes of a layer ──────────────────────────────────────────────────────────────────
    //
    // THESE FOUR THROW, for the viewers routes' reason rather than the verbs': an acceptance is a named
    // person stating that they read this text and stand behind it, and the report prints their name
    // beside it. A failure swallowed and reported as success would leave this phone showing an
    // acceptance the server never recorded — and the report, which reads the server's rows, printing
    // nothing. There is nothing to cache and nothing to queue.

    /**
     * Every layer this workshop's material has produced, newest first.
     *
     * [includeText] IS OFF BY DEFAULT AND SHOULD STAY OFF FOR A LIST. A workshop can hold twenty-five
     * interviews and an hour of speech is tens of kilobytes; a list with the text in would be megabytes
     * on one bar of signal, and unread, because a list is scanned by `preview` and `textChars`. Ask for
     * the text when showing ONE layer to the person about to put their name to it.
     */
    suspend fun designWorkshopAiLayers(
        workshopId: String,
        kind: String? = null,
        includeText: Boolean = false,
        includeDeleted: Boolean = false,
    ): DwAiLayerListDto = api.designWorkshopAiLayers(
        id = dwVerbWorkshopId(workshopId),
        kind = kind?.trim()?.takeIf { it.isNotEmpty() },
        includeText = if (includeText) true else null,
        includeDeleted = if (includeDeleted) true else null,
    )

    /**
     * A person puts their name to one layer, and it becomes printable. **Rule 3's only door.**
     *
     * Until this is called the row is a suggestion sitting in a table that no report reads. The answer
     * carries the layer's new state AND the whole decision log, and both are returned rather than the
     * boolean, because the audit being visible is what stops a screen rendering acceptance as a
     * checkbox.
     */
    suspend fun acceptDesignWorkshopAiLayer(
        workshopId: String,
        layerId: String,
        note: String? = null,
    ): DwAiLayerDecisionResultDto = api.acceptDesignWorkshopAiLayer(
        id = dwVerbWorkshopId(workshopId),
        layerId = layerId,
        body = DwAiLayerDecisionBody(note = note?.trim()?.takeIf { it.isNotEmpty() }),
    )

    /**
     * A person takes their name off one layer. **The layer itself is untouched and stays readable.**
     *
     * THE ACCEPTANCE IS CLEARED AND THE HISTORY IS NOT: a report generated while this layer was
     * accepted named it as accepted, and that document does not change because somebody changed their
     * mind on the 11th. Withdrawing is not declining — the layer goes back to being a suggestion and
     * can be accepted again, by the same person or another.
     */
    suspend fun unacceptDesignWorkshopAiLayer(
        workshopId: String,
        layerId: String,
        note: String? = null,
    ): DwAiLayerDecisionResultDto = api.unacceptDesignWorkshopAiLayer(
        id = dwVerbWorkshopId(workshopId),
        layerId = layerId,
        // A WITHDRAWAL'S NOTE IS THE ONE WORTH ASKING FOR, which is `AiLayerDecisionIn`'s own
        // asymmetry: accepting needs no explanation, and the reason somebody took their name off is
        // what stops the same layer being re-accepted by a colleague who was not told.
        body = DwAiLayerDecisionBody(note = note?.trim()?.takeIf { it.isNotEmpty() }),
    )

    /**
     * Decline a layer: the model proposed it and a person said no. **Soft, and 204.**
     *
     * The row stays, because it is the only record that the proposal was made and refused. It does not
     * touch what the layer was made from — a deletion plan names exactly one row and sets exactly
     * `deletedAt` and `deletedById` — and a 409 comes back where other layers derive from this one.
     */
    suspend fun declineDesignWorkshopAiLayer(workshopId: String, layerId: String) {
        api.declineDesignWorkshopAiLayer(dwVerbWorkshopId(workshopId), layerId)
    }

    // ── Who may open one design workshop ─────────────────────────────────────────────────────────
    //
    // THESE THREE THROW, unlike almost everything above them, and the exception is the point. The
    // block above degrades to the device because a stage is a fortnight of fieldwork captured in a
    // courtyard and an exception thrown at a designer who cannot do anything about it is worse than
    // stale data. Access is not that kind of fact: a grant is a row another person's sign-in reads on
    // the other side of the country, so a cached answer would tell an admin their co-designer is in
    // while the co-designer is still refused, and a queued write would be a promise this app cannot
    // keep. The screen says "this needs a connection" in words; it does not pretend to have one.
    //
    // ADMIN-ONLY on the server — `require_admin` on all three routes, so {ADMIN, MASTER_ADMIN} and
    // not the workshop's creator. The screen mirrors that with `FieldPermissions.isAdmin` and
    // re-derives it at the moment of the write; see data/DesignWorkshopViewers.kt for the full rule.

    /**
     * The accounts that may be given a viewer row at all, straight off the wire.
     *
     * NEVER re-derived from the user directory. The eligible set is DESIGNER/ADMIN/MASTER_ADMIN — a
     * SET, so a PROFESSOR is out despite outranking a designer — narrowed further by the designer
     * roster, a clause this client cannot see. Computing it here would drift within a release, and
     * the drift shows up as an admin granting access that the next sign-in refuses.
     *
     * NOT PAGED, AND SEARCHED INSTEAD — and the note that used to sit here, saying the eligible set
     * was "a few dozen accounts", was measured wrong. The endpoint caps at `ELIGIBLE_VIEWER_LIMIT =
     * 2000` and this repository holds 2543 eligible accounts, because ADMINs are not roster-gated at
     * all: 1344 of them plus the 1282 designers the roster admits. Ordered by name and cut at 2000,
     * 398 eligible accounts were unreachable from this phone, and there was no `page` to ask for them
     * and no `total` to notice them by.
     *
     * So [search] is how this client reaches past the ceiling, and it is applied by the SERVER — a
     * filter over the list this method returns would only ever search the part of the alphabet that
     * fitted. The answer now also carries `truncated`, which is the first time the wire could say it
     * had cut anything; it is returned rather than dropped because a picker that hides people has to
     * be able to say so. Null [search] is the unsearched (capped) list, exactly as before.
     */
    suspend fun eligibleDesignWorkshopViewers(search: String? = null): DwEligibleViewers =
        api.eligibleDesignWorkshopViewers(search).let {
            DwEligibleViewers(users = it.users, truncated = it.truncated, search = search)
        }

    /**
     * Everyone holding a viewer row on this workshop, oldest grant first.
     *
     * The CREATOR is not in this answer and an empty list must never be read as "nobody can see
     * this": their access comes from `createdById`, a different clause entirely.
     */
    suspend fun designWorkshopViewers(workshopId: String): List<DwViewerDto> =
        api.designWorkshopViewers(workshopId).viewers

    /**
     * REPLACE the viewer set with exactly [userIds], and answer with it as the server now holds it.
     *
     * Named for the whole set because that is what the endpoint means — there is deliberately no
     * `addDesignWorkshopViewer` for somebody to reach for, because such a helper would have to send
     * a list, and a list built from one name revokes everybody else. The answer is returned rather
     * than the request echoed so the caller adopts the SERVER's membership: another admin may have
     * added somebody between this screen loading and Save being pressed.
     */
    suspend fun setDesignWorkshopViewers(
        workshopId: String,
        userIds: List<String>
    ): List<DwViewerDto> =
        api.setDesignWorkshopViewers(workshopId, DwViewersBody(userIds = userIds)).viewers

    // ── THE FIFTH SCOPE: inspections ────────────────────────────────────────────────────────────
    //
    // ALL FIVE THROW, AND NOTHING BELOW IS CACHED, QUEUED OR FALLEN BACK TO THE DEVICE. That is a
    // decision rather than an omission, and it is the opposite of what the 22-stage block above does
    // — so it is worth the paragraph.
    //
    // The stage block degrades to the device because a workshop is a DATED OBSERVATION captured over
    // a fortnight in a courtyard, and yesterday's copy of it is still true. An inspection is not that
    // kind of fact, in three separate ways:
    //
    //  1. THE SCOPE IS A ROW SOMEBODY ELSE OWNS AND CAN TAKE AWAY. An admin who ends an inspection
    //     this morning has ended it. A cached read would keep a fortnight of somebody else's
    //     fieldwork legible on a handset whose access was withdrawn — and no later sync repairs it,
    //     because the bytes are already on the phone.
    //  2. AN INSPECTION IS A JUDGEMENT ABOUT WHAT THE RECORD SAYS NOW. The provenance names are
    //     resolved server-side at read time, so a stale copy would have an inspector reviewing a
    //     state of the workshop that no longer exists, with nothing on screen saying the two had
    //     diverged.
    //  3. THERE IS NOTHING TO QUEUE, AND A QUEUE HERE WOULD LOSE WORK. Every route is a GET; the
    //     server has no write route on this prefix at all, and `saveOrQueue` does not queue a 4xx —
    //     so a queued inspector write would be accepted by this app, refused for ever by the server,
    //     and reported to the inspector as saved. No write path may be added here.
    //
    // The screens say "this needs a connection" in words BEFORE anything is attempted, rather than
    // after it fails, exactly as the three viewer-administration calls above do.

    /**
     * The accounts that may be assigned an inspection at all, straight off the wire.
     *
     * NEVER re-derived from the user directory. The eligible set is `INSPECTION_ROLES` — a set of
     * ONE — narrowed further by the PLATFORM allow-list, which is a clause this client cannot see;
     * computing it here would drift within a release, and the drift shows up as an admin assigning an
     * inspection that the next sign-in refuses, with nothing on either screen saying why.
     *
     * The DESIGNER roster is deliberately NOT consulted by the server here, unlike
     * [eligibleDesignWorkshopViewers]: an inspector is not empanelled to run anything, so requiring a
     * roster row would refuse every inspector there will ever be.
     *
     * [search] is applied by the SERVER inside the same query as the eligibility rule. `truncated`
     * is carried back rather than dropped because a picker that hides people has to be able to say
     * so — the answer is capped at `ELIGIBLE_INSPECTOR_LIMIT` and a client that shows a cut list
     * without saying so is this repository's most repeated bug class.
     */
    suspend fun eligibleDesignWorkshopInspectors(search: String? = null): DwEligibleInspectors =
        api.eligibleDesignWorkshopInspectors(search).let {
            DwEligibleInspectors(users = it.users, truncated = it.truncated, search = search)
        }

    /**
     * Everyone assigned to inspect this workshop, oldest assignment first.
     *
     * AN EMPTY LIST MEANS NOBODY IS INSPECTING IT — the literal truth, and the sharp difference from
     * [designWorkshopViewers], where an empty answer still leaves the creator holding the workshop
     * through `createdById`. Nobody holds an inspection by any route other than a row in this table,
     * so a screen over this may say "not under inspection" and be right.
     */
    suspend fun designWorkshopInspectors(workshopId: String): List<DwInspectorDto> =
        api.designWorkshopInspectors(workshopId).inspectors

    /**
     * REPLACE the inspection set with exactly [userIds], and answer with it as the server now holds it.
     *
     * Named for the whole set because that is what the endpoint means — there is deliberately no
     * `addDesignWorkshopInspector` for somebody to reach for, because such a helper would have to
     * send a list, and a list built from one name ends everybody else's inspection.
     *
     * ADMIN ONLY on the server, AND THAT INCLUDES THE WORKSHOP'S OWN CREATOR. The inspected must not
     * choose the inspector; if a designer could put somebody on their own workshop as its inspector —
     * or take somebody off it — the inspection is worth nothing. Nothing here re-derives that; the
     * screen does, at the moment of the write, and the server does again.
     */
    suspend fun setDesignWorkshopInspectors(
        workshopId: String,
        userIds: List<String>
    ): List<DwInspectorDto> =
        api.setDesignWorkshopInspectors(workshopId, DwInspectorsBody(userIds = userIds)).inspectors

    /**
     * The design & prototype workshops THIS account has been assigned to inspect, newest first.
     *
     * ONE PAGE, ASKED FOR BY NUMBER, and deliberately not the page WALK [visibleDesignWorkshops]
     * performs. That walk exists because a viewer GRANT widens a designer's own list and lands the
     * granted workshop — always older than the ones they started themselves — past the end of page
     * one in a `createdAt desc` ordering, where a single-page client could never see it. This list
     * has no such second source: every row in it is an assignment, there is no "mine" arm for them to
     * be buried under, and the whole list is the scope. So the honest shape is a pager the inspector
     * drives, with the server's `total` on screen, rather than five silent requests on a metered
     * connection.
     *
     * THROWS. See the block comment above: an inspection is not cached.
     */
    suspend fun inspectableDesignWorkshops(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null
    ): DesignWorkshopPageDto = api.inspectableDesignWorkshops(
        page = page,
        pageSize = pageSize,
        search = search?.trim()?.takeIf { it.isNotEmpty() }
    )

    /**
     * ONE WORKSHOP UNDER INSPECTION — every stage, its completeness and its per-field authorship.
     *
     * [workshopId] IS THE SERVER'S ID AND NOT THE DRAFT STORE'S, unlike every other design-workshop
     * screen on this handset. There is no draft to resolve a `remoteId` from: an inspector never
     * opened this workshop for editing, has no local copy of it and must not acquire one. The ids
     * come from [inspectableDesignWorkshops], which is the only list this account can reach.
     */
    suspend fun workshopUnderInspection(workshopId: String): DwInspectionDetailDto =
        api.workshopUnderInspection(workshopId)

    /**
     * The admin authorship & divergence report for one workshop — every stage entry, every stamp, and
     * what the shared record behind each hydrated field says TODAY.
     *
     * THROWS, AND IS NEVER CACHED OR FALLEN BACK, which puts it with the three access calls above
     * rather than with the stage reads. The stage block degrades to the device because a workshop is
     * a DATED OBSERVATION and yesterday's copy of it is still true. Half of THIS answer is the
     * opposite kind of fact: "what does the artisan record say now" has no offline form, and a cached
     * one would show an admin a comparison against a record as it stood the last time this phone had
     * signal — which is a divergence report that invents divergences and hides real ones. Failing
     * honestly is the only correct behaviour, and [DwProvenanceScreen] renders the failure in place.
     *
     * ADMIN AND MASTER ADMIN ONLY on the server; see [WorkshopRepositoryApi.designWorkshopProvenance].
     * Nothing here re-derives that — the screen decides whether to ASK, using `FieldPermissions
     * .isAdmin`, and the server decides whether to answer.
     *
     * [workshopId] IS THE SERVER'S ID AND NOT THE DRAFT STORE'S. A workshop started in a courtyard
     * carries a `local-…` id no server has ever seen; the caller resolves `remoteId` first, exactly as
     * `WorkshopViewersScreen` does, because this endpoint has nothing to say about a workshop that has
     * never left the phone.
     */
    suspend fun designWorkshopProvenance(workshopId: String): DwProvenanceReportDto =
        api.designWorkshopProvenance(workshopId)

    /**
     * The report-history service, built ONCE for the life of this repository.
     *
     * `by lazy` and not a fresh service per call, which is what `DwJoinCard` and `DwWorkshopJoin` do
     * from composables that have no repository to reach: [ApiClient.retrofit] builds a new
     * OkHttpClient each time it is called, and a screen that re-reads its history on every retry
     * would leave a connection pool and a dispatcher behind for each one. Lazy rather than eager so a
     * designer who never opens the screen never pays for it at all. See [DwReportHistoryApi] for why
     * the service is declared separately instead of as a method on [WorkshopRepositoryApi].
     */
    private val reportHistoryApi: DwReportHistoryApi by lazy {
        ApiClient.retrofit(tokenStore).create(DwReportHistoryApi::class.java)
    }

    /**
     * Every report ever generated for one workshop, and the stage timestamps a diff is built from.
     *
     * THROWS, AND IS NEVER CACHED OR FALLEN BACK, which puts it with [designWorkshopProvenance] and
     * the access calls above rather than with the stage reads. The stage block degrades to the device
     * because a workshop is a DATED OBSERVATION and yesterday's copy of it is still true. This is the
     * opposite kind of fact: **the export table records files made on OTHER devices by OTHER people**
     * — a colleague's regeneration, a file the web produced this morning — so a cached answer would
     * show a designer a history with somebody else's revisions missing from it, on the one screen
     * whose entire job is to say what the record of revisions contains. There is no offline form of
     * "what has everybody generated", and inventing one would be a screen that looks complete and is
     * not. [DW_REPORT_HISTORY_OFFLINE] is what the screen says instead, and it says why.
     *
     * NOTHING IS WRITTEN. Recording an export is [recordDesignWorkshopExport], which is queued
     * through the outbox precisely because it happens with no signal; this is the read side and has
     * no write beside it. An export row whose size or checksum could be rewritten afterwards would
     * not be evidence of anything.
     *
     * [workshopId] IS THE SERVER'S ID AND NOT THE DRAFT STORE'S, the same requirement
     * [designWorkshopProvenance] carries: a workshop started in a courtyard has a local id no server
     * has ever seen. The caller resolves `remoteId` first — and a workshop that has none has no
     * export log at all, because [recordDesignWorkshopExport] needs a server id to file a row under.
     */
    suspend fun designWorkshopReportHistory(workshopId: String): DwReportHistoryDto =
        reportHistoryApi.designWorkshopReportHistory(workshopId)

    // ── DESIGN REVIEW: the rating ledger ─────────────────────────────────────────────────────────
    //
    // THE READS THROW AND ARE NEVER CACHED, which puts them with the three access calls above rather
    // than with the stage reads. The stage block degrades to the device because a workshop is a DATED
    // OBSERVATION and yesterday's copy of it is still true; a round's SCORES are the opposite kind of
    // fact. They are other people's judgements arriving continuously from other devices, so a cached
    // answer would show a designer a ranking that has since moved — and, worse, would let them fix an
    // arrangement against averages that are no longer the ones the ranking was computed from. There
    // is no offline form of "what do my colleagues think", and inventing one would be a screen that
    // looks live and is not.
    //
    // THE WRITE IS THE EXCEPTION AND IS QUEUED, because a judgement is captured in a courtyard. See
    // [submitDesignRating].

    /**
     * One review round's pieces, in PLACED order, each carrying both positions and this caller's own
     * rating.
     *
     * [workshopId] IS THE SERVER'S ID AND NOT THE DRAFT STORE'S, the same requirement
     * [designWorkshopProvenance] carries: a workshop started in a courtyard holds a `local-…` id no
     * server has ever seen, and this endpoint has nothing to say about a workshop that has never left
     * the phone. The screen resolves it before asking.
     *
     * The round token is passed as a String rather than the enum so that this layer stays the wire
     * and nothing else; `DwRatingRound.wire` is what the caller hands over.
     */
    suspend fun designRatingRound(
        round: String,
        workshopId: String,
        entityKey: String,
    ): RoundRankingDto = api.designRatingRound(
        round = round,
        workshopId = workshopId,
        entityKey = entityKey,
    )

    /**
     * Who rated one piece, when and how — whatever the server is willing to tell THIS account.
     *
     * NOTHING IS FILTERED HERE AND NOTHING MAY BE. `rating_payload` omits a `reviewerId` the caller
     * may not have and `visible_rows` drops rows they may not see, both before the response is built,
     * so what arrives is already exactly what may be shown. A second opinion in this client would be
     * a weaker copy of a rule the server enforces — the kind that goes stale the first time the
     * server's own changes — and hiding a column in a client was never a control in the first place.
     */
    suspend fun designRatingLedger(subjectId: String, round: String): SubjectLedgerDto =
        api.designRatingLedger(subjectId = subjectId, round = round)

    /**
     * Submit a rating, or keep it on this phone and let the outbox deliver it.
     *
     * ══════════════════════════════════════════════════════════════════════════════════════════════
     * WHY THIS ONE IS QUEUED WHEN THE TWO READS ABOVE ARE NOT
     * ══════════════════════════════════════════════════════════════════════════════════════════════
     *
     * A rating is a judgement made in a courtyard, in front of the piece, by somebody who will not be
     * standing there again. Refusing it for want of a signal loses a score, an assessment and a
     * suggested change that nothing else on the device holds — the web's own review card records this
     * as having been "the ONE value on the whole sketches and prototypes surface with no persistence
     * path of any kind", and offline is far more nearly the RULE on a handset than it is in a browser.
     *
     * Everything the queue needs, the server already had: the route is idempotent under replay, a
     * second row is unrepresentable (`@@unique([stageEntryId, reviewerId, round])`), and two
     * deliveries of one capture are ordered by the DEVICE clock rather than by arrival.
     *
     * ══════════════════════════════════════════════════════════════════════════════════════════════
     * `ratedAt` IS STAMPED HERE AND ONLY ON THE QUEUED PATH, WHICH IS THE POINT OF THE FIELD
     * ══════════════════════════════════════════════════════════════════════════════════════════════
     *
     * The direct path omits it deliberately: submitted straight against the server, the row's own
     * `createdAt` IS the moment the designer moved the control, and stamping the field at send time
     * would write the sync clock into the one column whose job is to not be the sync clock. A QUEUED
     * rating is the case the column exists for — made in the courtyard, delivered from the office
     * three days later — and `rating_plan` orders two deliveries of one capture by it, which is the
     * whole of what stops a queued original from undoing an amendment after a tunnel.
     *
     * So the stamp is taken at the moment of QUEUEING, which is the moment of capture: this function
     * is called synchronously from the designer pressing the button, and the failing request in front
     * of it takes seconds rather than days.
     *
     * ══════════════════════════════════════════════════════════════════════════════════════════════
     * ONLY A REQUEST THE SERVER NEVER ANSWERED IS QUEUED
     * ══════════════════════════════════════════════════════════════════════════════════════════════
     *
     * An [HttpException] means the server SAW this rating and said no — a 403 on the designer's own
     * record, a 422 on a bad round, a 404 on a subject they may not see, a 503 on a deployment whose
     * migration has not run — and replaying a refusal for ever behind a sentence promising it will
     * land is worse than the refusal. So the status is re-thrown for the screen to print, and only a
     * shape that never reached the server is queued. That is a NARROWER test than [isTransient],
     * which excuses every 5xx because all a record queue has to decide is whether to keep the entry;
     * here a 5xx is an answer, and the boxes keep the designer's text either way.
     *
     * A DOUBLE PRESS WITH NO SIGNAL QUEUES TWICE, AND THAT IS ACCEPTED RATHER THAN UNNOTICED. The
     * queue holds no de-duplication key, so two entries go up; the second carries the later
     * `ratedAt`, the route stores whichever is newer and answers `replayed` for the other, and no
     * second row can exist. Two tray rows for one judgement is the whole cost, and it is paid in
     * exchange for never having to decide on a device whether two presses were one intention.
     *
     * @param label what a designer will recognise this by in the outbox tray a week later. The
     *   PIECE's own name — an endpoint is not something anybody recognises.
     */
    suspend fun submitDesignRating(
        context: Context,
        body: DesignRatingBody,
        label: String,
    ): DwRatingOutcome {
        try {
            return DwRatingOutcome.Sent(api.submitDesignRating(body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Answered, or permanent: the screen prints what the server said. See the KDoc.
            if (e is HttpException || !isTransient(e)) throw e
            OfflineOutbox.enqueue(
                context,
                PendingEntry(
                    id = java.util.UUID.randomUUID().toString(),
                    type = OFFLINE_DESIGN_RATING,
                    payloadJson = offlineJson.encodeToString(
                        body.copy(ratedAt = Instant.now().toString())
                    ),
                    label = label,
                    createdAt = Instant.now().toString(),
                )
            )
            return DwRatingOutcome.Queued
        }
    }

    // ── The DESIGNER tier ────────────────────────────────────────────────────────────────────────
    //
    // NOTHING IN THIS BLOCK FALLS BACK TO THE DEVICE, and that is the difference between it and the
    // design-workshop block above. A stage is a fortnight of fieldwork done in a courtyard, so its
    // reads degrade to the local draft rather than fail. A roster is an ADMIN'S STATEMENT ABOUT WHO
    // MAY SIGN IN, and a profile is what a report prints under a person's name; showing a cached copy
    // of either would mean an admin suspending somebody against a stale list and being told it
    // worked, or a designer editing a profile the server has since had changed underneath them. Both
    // of those are worse than an honest error, so a failure here propagates.

    /**
     * ONE PAGE of the roster, narrowed and ordered BY THE SERVER.
     *
     * ── THIS WAS A WALK, AND THE WALK IS GONE ────────────────────────────────────────────────────
     *
     * It used to call [walkPagedListing] to gather up to 500 rows and hand the whole lot to a screen
     * that then sorted and filtered them in Kotlin. The argument for that was written here and it was
     * wrong in three separate ways, each of which is a rule in DROPDOWN_DESIGN §4.6:
     *
     *  - **It answered "no match" about designers who exist.** 100 × 5 is a 500-row ceiling against a
     *    table `design_workshop_viewers.py:106` counts at about 1,300, so the device-side search box
     *    was filtering a PREFIX and reporting its result as an answer about the roster (rule iv).
     *  - **It lost the wrong end.** The walk read `createdAt desc` from page one, so a short read kept
     *    the NEWEST empanelments and dropped the OLDEST — and the oldest is the row this screen is
     *    opened for: the designer empanelled two seasons ago who cannot sign in today.
     *  - **It cost five requests to answer one question**, on the connection the courtyard argument
     *    was worried about in the first place.
     *
     * Every parameter now goes to the server and one page comes back. Nothing in this app narrows a
     * roster on the device any more, and `RosterFilterWireTest` asserts it.
     *
     * ── WHAT THE DEFAULTS SEND, WHICH IS THE POINT OF RULE (ii) ──────────────────────────────────
     *
     * Every filter is nullable and every null is omitted by Retrofit, so calling this with nothing but
     * a page number issues `GET /designers/roster?page=1&pageSize=50` — byte for byte what the walk's
     * first request was, and the server's spelling of "every standing, every tier, every institution".
     * Suspended rows are in that answer, which is the whole reason the screen is usable.
     *
     * `activeOnly` IS NOT SENT AND MUST NOT BE. `standing` is the same question in §4.1's grammar and
     * sending both is a 422 rather than a silent winner.
     *
     * Admin and above — the server refuses everyone else, and the screen does not even issue the
     * request for an account that may not have it.
     */
    suspend fun designerRoster(
        page: Int = 1,
        pageSize: Int = 50,
        search: String? = null,
        standing: String? = null,
        roles: String? = null,
        institutions: String? = null,
        dateField: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        sort: String? = null,
        dir: String? = null,
    ): RosterPageDto<DesignerRosterDto> = api.designerRoster(
        page = page,
        pageSize = pageSize,
        search = search?.trim()?.ifBlank { null },
        standing = standing,
        roles = roles,
        institutions = institutions,
        dateField = dateField,
        dateFrom = dateFrom,
        dateTo = dateTo,
        sort = sort,
        dir = dir,
    )

    /**
     * The distinct institutions on the roster, for the filter picker over them.
     *
     * SERVER-SERVED AND NOT ASSEMBLED FROM THE PAGE ON SCREEN. `DesignerRoster.institution` is free
     * text, so an exact-match filter is only usable behind a picker of the values that exist — and a
     * picker built from the fifteen rows this handset happens to hold could only ever offer the
     * institutions those fifteen rows carried. An admin filtering for one that is two pages down
     * would find no row for it and read that as "nobody is from there".
     *
     * ITS FAILURE IS THE CALLER'S TO WORD AND IS NEVER SWALLOWED HERE. This endpoint ships in the same
     * wave as the screen, so the two halves may be deployed in either order and a 404 is an ordinary
     * outcome rather than a fault. A 404 is an ANSWERED refusal, so §3.5's could-not-be-listed
     * sentence is the honest one; returning an empty list from here instead would tell the picker that
     * the roster holds no institutions, which is the claim this whole design exists to stop a control
     * from making.
     */
    suspend fun designerRosterInstitutions(): RosterInstitutionsDto =
        api.designerRosterInstitutions()

    /**
     * The accounts an admin may hand a workshop to — used here purely as the email -> account id
     * join a roster row needs to reach `/designers/{userId}/profile`.
     *
     * `includeSuspended = true` because the row an admin is most often working on is the suspended
     * one; see [DesignerDirectoryEntryDto]. Capped at 500 accounts server-side, which the caller must
     * SAY when it is hit rather than silently drop the profile action from rows whose account exists.
     */
    suspend fun designerDirectory(): List<DesignerDirectoryEntryDto> =
        api.designerDirectory(includeSuspended = true)

    /**
     * Empanel somebody by email.
     *
     * The email is lower-cased here as well as on the server. Not belt-and-braces: the roster is
     * keyed by a UNIQUE email, so "A.Sharma@…" and "a.sharma@…" are one row on the server and two
     * rows in an admin's head — and the 409 that comes back from the second attempt says a duplicate
     * exists without saying that the difference is the capital letters they cannot see.
     */
    suspend fun addDesignerToRoster(
        email: String,
        fullName: String?,
        institution: String?,
        notes: String?
    ): DesignerRosterDto = api.addDesignerToRoster(
        DesignerRosterCreateBody(
            email = email.trim().lowercase(),
            fullName = fullName?.trim()?.ifBlank { null },
            institution = institution?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null }
        )
    )

    /** Correct a roster row. Pass [suspendOrRestore] to move the sign-in gate in either direction. */
    suspend fun updateDesignerRosterEntry(
        id: String,
        email: String? = null,
        fullName: String? = null,
        institution: String? = null,
        notes: String? = null,
        suspendOrRestore: Boolean? = null
    ): DesignerRosterDto = api.updateDesignerRosterEntry(
        id,
        designerRosterUpdateJson(
            email = email?.trim()?.lowercase(),
            fullName = fullName,
            institution = institution,
            notes = notes,
            suspendOrRestore = suspendOrRestore
        )
    )

    /**
     * Revoke a designer's access WITHOUT deleting the record that they were ever empanelled.
     *
     * There is deliberately no `deleteDesignerFromRoster` anywhere in this app. A deleted row leaves
     * no evidence that the institution ever recognised the person — which matters, because reports
     * already delivered to a ministry carry their name and their empanelment number, and a roster
     * that cannot account for them makes those documents unverifiable. Suspension keeps the row,
     * stamps `revokedAt`, and is reversible through [restoreDesigner].
     */
    suspend fun suspendDesigner(id: String): DesignerRosterDto = api.suspendDesigner(id)

    /** The way back from [suspendDesigner]. A DELETE cannot express a restore, so this is a PATCH. */
    suspend fun restoreDesigner(id: String): DesignerRosterDto =
        api.updateDesignerRosterEntry(id, designerRosterUpdateJson(suspendOrRestore = true))

    // ── The PLATFORM allow-list: who may sign in at all, and who is waiting to ────────────────────
    //
    // NOTHING IN THIS BLOCK FALLS BACK TO THE DEVICE EITHER, for the reason stated above the designer
    // roster and more so: this list is what the sign-in gate reads. An admin deciding somebody's
    // access against a cached copy, and being told it worked, is worse here than anywhere else in the
    // app — the person they thought they let in is still standing outside.

    /**
     * One page of the allow-list. NOT A WALK, unlike [designerRoster], and the difference is the
     * point.
     *
     * The designer roster is a few hundred empanelments and its screen filters on the device. This
     * table holds every address the institution has ever admitted OR REFUSED — including every
     * stranger who ever tried a password against it — so it grows without bound and in a direction
     * nobody controls. Walking it to filter three pending rows on the handset would download the
     * whole history of the front door over mobile data. The server filters and pages instead, and
     * the screen renders `PageResponse.total` and `pages` rather than counting what it holds.
     *
     * @param status one [AccessStatus], or several COMMA-JOINED, or null for every state. One value is
     *   byte-identical to what this client sent before requirement 30 — which is how the pending queue
     *   below keeps reading `?status=PENDING` and is untouched by the filter work.
     */
    suspend fun accessRoster(
        page: Int = 1,
        pageSize: Int = 25,
        status: String? = null,
        search: String? = null,
        roles: String? = null,
        dateField: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        sort: String? = null,
        dir: String? = null,
    ): RosterPageDto<AccessRosterDto> = api.accessRoster(
        page = page,
        pageSize = pageSize,
        status = status,
        search = search?.trim()?.ifBlank { null },
        roles = roles,
        dateField = dateField,
        dateFrom = dateFrom,
        dateTo = dateTo,
        sort = sort,
        dir = dir,
    )

    /**
     * How many people are waiting for an administrator to let them in — THE NOTIFICATION.
     *
     * Called from the app-wide loop that already runs while somebody is signed in (see
     * `MainActivity`), NOT from a timer of its own. One integer every 45 seconds on a connection
     * that is already being used to drain the outbox is free; a second poller would be a second
     * wake-up on a phone in a field, and two clocks that drift apart show two different numbers on
     * two surfaces of one app.
     */
    suspend fun pendingAccessCount(): PendingAccessCountDto = api.pendingAccessCount()

    /**
     * Admit an address by hand. No account is required and none is created.
     *
     * Lower-cased here as well as on the server, for the designer roster's reason: the table is keyed
     * by a UNIQUE email, so "A.Sharma@…" and "a.sharma@…" are one row on the server and two in an
     * admin's head, and the 409 the second attempt earns says a duplicate exists without saying that
     * the difference is a capital letter nobody can see.
     */
    suspend fun addToAccessRoster(
        email: String,
        fullName: String? = null,
        role: String? = null,
        notes: String? = null
    ): AccessRosterDto = api.addToAccessRoster(
        AccessRosterCreateBody(
            email = email.trim().lowercase(),
            fullName = fullName?.trim()?.ifBlank { null },
            role = role?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null }
        )
    )

    /**
     * Approve a waiting request, at [role] or at the platform's default joining tier.
     *
     * ALSO THE WAY BACK FROM A REFUSAL OR A SUSPENSION. There is no separate restore endpoint,
     * because reopening a decision IS a decision — and routing it through the same call means the
     * stamps that go with it (who decided, when) are written by one piece of code that cannot forget
     * them. `joinedAt` is not moved by it: somebody who joined in 2024 and was let back in today has
     * still been here since 2024.
     */
    suspend fun approveAccessRequest(id: String, role: String? = null, notes: String? = null): AccessRosterDto =
        api.decideAccessRequest(
            id,
            AccessDecisionBody(
                decision = AccessDecision.APPROVE,
                role = role?.trim()?.ifBlank { null },
                notes = notes?.trim()?.ifBlank { null }
            )
        )

    /**
     * Refuse a waiting request. FINAL UNTIL AN ADMIN SAYS OTHERWISE.
     *
     * The person's next sign-in does not re-queue them — the server bumps their attempt count, leaves
     * the status alone, and tells them their request was not approved. Any other choice makes the
     * queue unworkable: the admin clears it, the same people retry overnight, and it is full again
     * with entries they already decided. Reopening one is [approveAccessRequest], which is an
     * administrator's decision rather than a stranger's persistence.
     */
    suspend fun rejectAccessRequest(id: String, notes: String? = null): AccessRosterDto =
        api.decideAccessRequest(
            id,
            AccessDecisionBody(decision = AccessDecision.REJECT, notes = notes?.trim()?.ifBlank { null })
        )

    /** Correct the admin-typed columns of a row. Cannot move the gate — see [accessRosterUpdateJson]. */
    suspend fun updateAccessEntry(
        id: String,
        fullName: String? = null,
        role: String? = null,
        notes: String? = null
    ): AccessRosterDto = api.updateAccessEntry(id, accessRosterUpdateJson(fullName, role, notes))

    /**
     * End an address's access WITHOUT deleting the record that it was ever admitted.
     *
     * There is deliberately no `deleteAccessEntry` anywhere in this app, and the reason is sharper
     * than the designer roster's: the sign-in gate treats a MISSING row as PENDING, so deleting a
     * suspended person would put them back in the approval queue as though they were a stranger —
     * and destroy the joining date and the attempt history on the way.
     */
    suspend fun suspendAccessEntry(id: String): AccessRosterDto = api.suspendAccessEntry(id)

    /**
     * A designer's profile. [userId] null reads the signed-in account's own.
     *
     * Returns null on a 404 rather than throwing, because "this account has no profile row yet" is
     * the ORDINARY state of a designer who has signed in and never opened the screen — the row is
     * created by the first save. Reporting that as an error would greet every new designer with a
     * red line on the screen they were sent to fill in.
     */
    suspend fun designerProfile(userId: String? = null): DesignerProfileDto? = try {
        if (userId == null) api.myDesignerProfile() else api.designerProfile(userId)
    } catch (e: HttpException) {
        if (e.code() == 404) null else throw e
    }

    /**
     * Write a profile. [userId] null writes the signed-in account's own; an admin correcting
     * somebody else's passes theirs.
     *
     * The body carries every column with explicit nulls for the cleared ones — see
     * [designerProfileUpdateJson], and do not "simplify" it back into a typed body.
     */
    suspend fun saveDesignerProfile(
        userId: String?,
        body: DesignerProfileUpdateBody
    ): DesignerProfileDto {
        val encoded = designerProfileUpdateJson(body)
        return if (userId == null) {
            api.updateMyDesignerProfile(encoded)
        } else {
            api.updateDesignerProfile(userId, encoded)
        }
    }

    // ── Custom questionnaires ────────────────────────────────────────────────────────────────────
    //
    // NOTHING IN THIS BLOCK FALLS BACK TO THE DEVICE either, and for a sharper reason than the roster
    // above. An answer is evidence, and the wording it was recorded under is part of that evidence —
    // which is a rule that lives entirely on the server (`guard_question_edit`, `save_answers`). A
    // cached copy of a questionnaire cannot know that a question was retired an hour ago, so an
    // offline queue here would let a designer fill in a section against wording that has since been
    // superseded and be told it saved; the reconciliation would then have to either refuse the batch
    // (losing the sitting) or attach it to the new wording (fabricating evidence). Both are worse
    // than being told, in the room, that there is no signal. If this feature ever does go offline it
    // needs the questionnaire VERSION as its precondition, not a retry loop.
    //
    // [DwQuestionnaireStore] IS NOT AN EXCEPTION TO THIS, and the distinction is worth stating here
    // because the two look alike from a distance. That store keeps a copy of the ANSWERS ALREADY
    // READ, for one purpose: printing them into the report this handset writes at the close of a
    // workshop. It is read-only evidence. Nothing in it is ever sent back, no method below consults
    // it, and it holds none of the ids a save would need — so it cannot become the offline queue the
    // paragraph above refuses. The rule that stands is about writing an answer without the server;
    // the gap that was closed is a report that could not print an answer the server had already
    // handed over.

    suspend fun customQuestionnaires(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null,
        designWorkshopId: String? = null,
        activeOnly: Boolean = true,
        mineOnly: Boolean = false,
    ): PageResponse<CustomQuestionnaireSummaryDto> = api.customQuestionnaires(
        page = page,
        pageSize = pageSize,
        search = search?.trim()?.takeIf { it.isNotEmpty() },
        designWorkshopId = designWorkshopId?.takeIf { it.isNotBlank() },
        activeOnly = activeOnly,
        mineOnly = mineOnly,
    )

    suspend fun customQuestionnaireOptions(
        designWorkshopId: String? = null
    ): List<CustomQuestionnaireOptionDto> =
        api.customQuestionnaireOptions(designWorkshopId?.takeIf { it.isNotBlank() })

    /**
     * One questionnaire.
     *
     * [includeRetired] is not a preference — it is which SCREEN is asking. See the KDoc on
     * [CustomQuestionnaireDto]; the answer screen must never be handed a retired question, and the
     * read/edit screen must never be denied one.
     */
    suspend fun customQuestionnaire(
        id: String,
        includeRetired: Boolean = false
    ): CustomQuestionnaireDto = api.customQuestionnaire(id, includeRetired)

    /**
     * One questionnaire, WITH A FALLBACK TO THE COPY THIS DEVICE ALREADY HOLDS.
     *
     * ── WHAT THIS CHANGES AND WHAT IT DELIBERATELY DOES NOT ───────────────────────────────────
     *
     * The paragraph above this block explains why nothing here queues a WRITE, and that stands
     * untouched. What it was also doing, unintentionally, was making the custom questionnaire the
     * one capture surface in this app that could not be OPENED without a connection — 24 sections
     * and hundreds of questions, and a designer with no bars saw an error message where the
     * instrument should be. That is not a consequence of the answer rule; it is a consequence of
     * never having written the read down.
     *
     * NETWORK FIRST, DISK SECOND. The opposite order to [DwReferenceStore], and the difference is
     * deliberate: a reference list is a picker whose staleness costs a missing option, whereas a
     * questionnaire's staleness can mean a retired question offered for a new answer. So a live copy
     * is always preferred and the cache is a fallback, never a shortcut. A live read also refreshes
     * the copy, which is what makes the fallback worth having at all.
     *
     * THE FALLBACK IS ONLY FOR A FAILURE THAT MEANS "NO ANSWER". A 403 or a 404 must reach the
     * caller: a questionnaire the designer may no longer read, or that has been deleted, must not be
     * served out of this device's memory of when they could. That would be showing somebody a
     * document whose access was revoked, from the app's own pocket.
     *
     * @return the form plus whether it came off the disk, so the screen can say so. It must say so;
     *   see [cachedQuestionnaireNotice].
     */
    /**
     * @param acceptEitherCachedView when the network cannot be reached, accept the copy stored under
     *   the OTHER `includeRetired` key rather than only this one. FOR CALLERS THAT DO NOT RENDER THE
     *   FORM. The two keys exist because one screen must never be handed a retired question and the
     *   other must never be denied one, so a screen that turns this on gets a silent, invisible
     *   defect. [buildQuestionnaireHandoffFile] is the only caller that may, and the reason it may is
     *   structural: see [DwQuestionnaireFormCache.getEither].
     */
    suspend fun customQuestionnaireCached(
        context: Context,
        id: String,
        includeRetired: Boolean = false,
        acceptEitherCachedView: Boolean = false,
    ): QuestionnaireFormRead {
        val live = runCatching { api.customQuestionnaire(id, includeRetired) }
        live.getOrNull()?.let { form ->
            DwQuestionnaireFormCache.put(context, form, includeRetired)
            return QuestionnaireFormRead(form = form, fromCache = false)
        }
        val error = live.exceptionOrNull() ?: IllegalStateException("This questionnaire could not be opened.")
        // The server ANSWERED, and its answer was no. Not a connectivity problem, so the cache is not
        // consulted: see the paragraph above.
        if (error is HttpException && error.code() !in setOf(408, 429) && error.code() < 500) throw error
        val cached = if (acceptEitherCachedView) {
            DwQuestionnaireFormCache.getEither(context, id, preferred = includeRetired)
        } else {
            DwQuestionnaireFormCache.get(context, id, includeRetired)
        } ?: throw error
        return QuestionnaireFormRead(
            form = cached.form,
            fromCache = true,
            cachedAt = cached.fetchedAt.takeIf { it.isNotBlank() },
        )
    }

    // ── The peer handoff: a questionnaire as a file this device builds and reads ────────────────
    //
    // WHAT THIS IS AND IS NOT, because the honest name matters: a FILE FORMAT plus Android's own
    // share sheet. Not a bespoke peer channel — no socket, no pairing, no discovery, no Bluetooth
    // code, no dependency, no new permission. See the header of `data/QuestionnaireBundle.kt` for the
    // whole argument, including why the QR carries a fingerprint and not the questionnaire.
    //
    // THE ONE THING THAT MADE IT NECESSARY: every other file-based share path in this app is
    // server-dependent. `question-set.xlsx` is produced by an endpoint and read back by an endpoint;
    // the dataset zips stream a server manifest and fetch media by URL. So there was no artefact this
    // handset could BUILD offline that another handset could READ offline.

    /** A built bundle, ready to be handed over: where it landed, how to share it, what is in it. */
    data class QuestionnaireHandoffFile(
        /** What [persistFileToDownloads] returned — a display location, shown to the designer. */
        val savedTo: String,
        /**
         * The share-sheet Uri, or null when the file could not be published for sharing.
         *
         * Nullable and NOT assumed: `shareUriForSavedFile` re-derives a MediaStore row and a query can
         * come back empty. A screen must gate its Share control on this rather than crash
         * `FileProvider` at the moment the designer taps it.
         */
        val shareUri: Uri?,
        val bundle: QuestionnaireBundle,
        /** The 23-character code to show beside it, for the recipient to scan and check. */
        val handoffCode: String,
    )

    /**
     * Build a questionnaire's question set as a `.dpwq` file on this device.
     *
     * WORKS WITH NO SIGNAL, which is the entire point, and it works because it reads through
     * [customQuestionnaireCached]. A designer in a courtyard who opened this questionnaire at any
     * point earlier in the fortnight — on EITHER of the two screens that open it — can hand it to the
     * colleague beside them now. `includeRetired = false`: a retired wording must not be reborn as a
     * live question on somebody else's phone.
     *
     * `acceptEitherCachedView` IS WHAT MAKES THE SENTENCE ABOVE TRUE, and without it that sentence was
     * a lie in the field. The cache is one file per (id, includeRetired) pair and the only writer of
     * the `-active` file is the ANSWER screen, whereas this card lives on the DETAIL screen, which
     * warms `-all`. So the ordinary case — open the questionnaire, walk out of signal, tap "Make the
     * file" — found no cache at all and rethrew, and the designer was told the questionnaire could not
     * be made into a file having done exactly what they were told was enough. Accepting either copy is
     * safe HERE and nowhere else, because [questionnaireBundleOf] filters to
     * `isActive && supersededById == null` itself, so a retired wording in an `-all` copy cannot reach
     * the bundle no matter which file it came out of.
     *
     * Published through [persistFileToDownloads] rather than a new file-writing path of its own, for
     * the reason stated on that function — the `IS_PENDING` handshake, the pre-Q permission check and
     * the `filesDir` fallback were all learned from field failures, and a second copy is a second copy
     * to get wrong.
     */
    suspend fun buildQuestionnaireHandoffFile(
        context: Context,
        questionnaireId: String,
    ): QuestionnaireHandoffFile = withContext(Dispatchers.IO) {
        val read = customQuestionnaireCached(
            context = context,
            id = questionnaireId,
            includeRetired = false,
            acceptEitherCachedView = true,
        )
        val bundle = questionnaireBundleOf(read.form)
        if (bundle.sections.none { it.questions.isNotEmpty() }) {
            throw IllegalStateException(
                "This questionnaire has no questions in it yet, so there is nothing to hand over."
            )
        }
        val bytes = encodeQuestionnaireBundle(bundle)
        val handoffCode = questionnaireHandoffCode(bundle)
        // The digest goes in the NAME as well as in the code. The version alone cannot separate two
        // editions — the server does not bump it when questions are added — so without this, two
        // exports of the same questionnaire either side of an edit are two indistinguishable files in
        // the designer's Downloads folder, and nearby share is a file picker.
        val name = questionnaireBundleFilename(
            title = bundle.title,
            version = bundle.sourceVersion,
            digest = handoffCode.split(":").getOrNull(2).orEmpty(),
        )
        // cacheDir is right for this one: the bytes live here only until the copy into Downloads (or
        // the filesDir fallback) has been made, which happens on the next line.
        val staging = File(context.cacheDir, name)
        FileOutputStream(staging).use { it.write(bytes) }
        val savedTo = try {
            persistFileToDownloads(context, staging, name, QUESTIONNAIRE_BUNDLE_MIME)
        } finally {
            runCatching { staging.delete() }
        }
        QuestionnaireHandoffFile(
            savedTo = savedTo,
            shareUri = shareUriForSavedFile(context, savedTo),
            bundle = bundle,
            handoffCode = handoffCode,
        )
    }

    /**
     * Read a `.dpwq` that arrived from another phone and keep it on this one.
     *
     * ── EVERY BYTE HERE IS UNTRUSTED ──────────────────────────────────────────────────────────
     *
     * It came over Bluetooth from a device this app knows nothing about, and whoever sent it can edit
     * the JSON. Three defences, in this order:
     *
     *  1. THE RAW FILE IS CAPPED BEFORE IT IS READ. A questionnaire measures 8,501 bytes gzipped; a
     *     ceiling of 4 MB on the compressed side is 490 times that and stops this function reading a
     *     4 GB file off an SD card into memory.
     *  2. THE INFLATION IS CAPPED WHILE IT RUNS, inside [readQuestionnaireBundle] — the decompression
     *     bomb, which the raw cap above does nothing about.
     *  3. THE FORMAT HAS NOTHING TO ATTACK. No id, no owner, no author, no status, no answers. That is
     *     the real defence and it is structural rather than a check; see `QuestionnaireBundle.kt`.
     *
     * @return the stored row, or a refusal carrying the sentence to put on screen.
     */
    suspend fun receiveQuestionnaireHandoff(
        context: Context,
        uri: Uri,
    ): Result<ReceivedQuestionnaire> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    if (out.size() + n > QUESTIONNAIRE_HANDOFF_MAX_FILE) {
                        throw IllegalStateException(
                            "That file is far too big to be a questionnaire. It has not been opened."
                        )
                    }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }.getOrElse { error ->
            return@withContext Result.failure(
                IllegalStateException(
                    error.message ?: "That file could not be opened from where it is stored."
                )
            )
        } ?: return@withContext Result.failure(
            IllegalStateException(
                "That file could not be opened. If it came from a chat app, save it to the phone first " +
                    "and pick it from there."
            )
        )
        when (val read = readQuestionnaireBundle(bytes)) {
            is QuestionnaireBundleRead.Refused -> Result.failure(IllegalStateException(read.message))
            is QuestionnaireBundleRead.Ok -> Result.success(
                QuestionnaireBundleInbox.put(
                    context = context,
                    filename = displayNameForUri(context, uri) ?: "questionnaire.$QUESTIONNAIRE_BUNDLE_EXTENSION",
                    bytes = bytes,
                    bundle = read.bundle,
                )
            )
        }
    }

    /** The name a provider gives a Uri, for display only. Never used as a path. */
    private fun displayNameForUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /** Received questionnaires waiting on this device. */
    suspend fun receivedQuestionnaires(context: Context): List<ReceivedQuestionnaire> =
        QuestionnaireBundleInbox.all(context)

    /** Throw one away, with its bytes. A person's explicit act. */
    suspend fun discardReceivedQuestionnaire(context: Context, id: String): Boolean =
        QuestionnaireBundleInbox.remove(context, id)

    /**
     * Turn a received file into a questionnaire ON THIS ACCOUNT, resuming wherever a previous attempt
     * stopped.
     *
     * ── WHERE THE UNTRUSTED-INPUT RULES ARE ACTUALLY ENFORCED, IN ONE PLACE ───────────────────
     *
     * Every write below is a CREATE — `createCustomQuestionnaire`, `addCustomSection`,
     * `addCustomQuestion`. There is no update, no id from the file is ever sent, and the three create
     * bodies ([CustomQuestionnaireCreateBody], [CustomSectionCreateBody], [CustomQuestionCreateBody])
     * have no field for an owner, an author or a status — so:
     *
     *  * OWNERSHIP is set by the server from the bearer token (`createdById` / `ownerId`), which is why
     *    the adopting designer owns the result and why nothing in the file can change that. The screen
     *    says so before the designer accepts; see [QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE].
     *  * IDS cannot be dictated. The file has none to dictate, and every row here is minted by the
     *    server, so a received bundle cannot address — let alone overwrite — an existing questionnaire,
     *    section or question.
     *  * REVIEW STATUS is untouched. None of the three bodies carries one, so there is no cross-device
     *    approval to launder.
     *  * ANSWERS AND RESPONDENTS do not exist in the format at all, so there is nothing here that
     *    could write somebody else's answer about a named artisan onto this account.
     *
     * ── WHY IT IS 310 REQUESTS AND WHY THAT IS WRITTEN DOWN AFTER EACH ONE ────────────────────
     *
     * There is no bulk JSON create route — the only bulk import the API has takes an .xlsx, which this
     * handset cannot build. So the 24-section instrument costs one POST for the questionnaire, one per
     * section and one per question. On a field connection the chance of all 310 landing is not one, so
     * progress is persisted after each step and this function resumes from it. Without that, a drop at
     * question two hundred left a half-built questionnaire on the server and no way to finish it: the
     * only move was to start again, producing a second half-built one.
     *
     * @return the row as it now stands. [ReceivedQuestionnaire.adoptedAt] non-null means it finished.
     */
    suspend fun adoptReceivedQuestionnaire(
        context: Context,
        row: ReceivedQuestionnaire,
        /** Called after each section, so a screen can show progress through a 285-question form. */
        onProgress: (sectionsDone: Int, sectionCount: Int) -> Unit = { _, _ -> },
    ): Result<ReceivedQuestionnaire> {
        val bytes = QuestionnaireBundleInbox.bytes(context, row)
            ?: return Result.failure(
                IllegalStateException(
                    "The copy of that file this app kept has gone. Ask for it again."
                )
            )
        val bundle = when (val read = readQuestionnaireBundle(bytes)) {
            is QuestionnaireBundleRead.Refused -> return Result.failure(IllegalStateException(read.message))
            is QuestionnaireBundleRead.Ok -> read.bundle
        }
        // Only sections that have questions. An empty section adopted would be a heading with nothing
        // under it, and `sectionCount` on the row was computed the same way, so the two agree.
        val sections = bundle.sections.filter { it.questions.isNotEmpty() }
        var state = row.copy(failure = null)
        QuestionnaireBundleInbox.update(context, state)

        suspend fun persist(next: ReceivedQuestionnaire) {
            state = next
            QuestionnaireBundleInbox.update(context, next)
        }

        try {
            if (state.remoteId == null) {
                val created = createCustomQuestionnaire(
                    title = bundle.title.ifBlank { "Questionnaire" },
                    description = bundle.description,
                    // NOT attached to a design workshop. The bundle carries no workshop id — it could
                    // only be the SENDER's, which means nothing on this account — and attaching it to
                    // one is a decision the designer makes afterwards on the questionnaire's own
                    // screen, where the dropdown lists workshops they actually work on.
                    designWorkshopId = null,
                )
                persist(state.copy(remoteId = created.id))
            }
            val remoteId = state.remoteId ?: error("unreachable: the questionnaire was just created")

            var sectionIndex = state.sectionsDone
            while (sectionIndex < sections.size) {
                val section = sections[sectionIndex]
                var sectionRemoteId = state.currentSectionRemoteId
                if (sectionRemoteId == null) {
                    // ── LOOK BEFORE CREATING, WHEN A CREATE WAS ALREADY ATTEMPTED ──────────────
                    //
                    // `sectionCreateStarted` means a POST went out and its answer was never written
                    // down, so the section may already be on the server. Creating a second one leaves
                    // a permanent empty duplicate in somebody's instrument. Matched EXACTLY — code or
                    // title, never the sort-order fallback — because the fallback would bind to
                    // whatever empty section happens to be last, and here we would then fill it.
                    val alreadyThere = if (state.sectionCreateStarted) {
                        runCatching { customQuestionnaire(remoteId, includeRetired = true) }
                            .getOrNull()
                            ?.let { matchAdoptedSection(it, section, exactOnly = true) }
                    } else {
                        null
                    }
                    if (alreadyThere != null) {
                        sectionRemoteId = alreadyThere
                    } else {
                        // WRITTEN DOWN BEFORE THE REQUEST, not after. This is the whole fix: the file
                        // has to say "a create is in flight" while it is in flight, or a kill between
                        // the POST and the persist below is indistinguishable from a POST that never
                        // happened. Same discipline as `PendingEntry.createdId`.
                        persist(state.copy(sectionCreateStarted = true))
                        val afterSection = addCustomSection(
                            id = remoteId,
                            title = section.title.ifBlank { section.code.ifBlank { "Section" } },
                            // The bundle's own code, never a derived one: the code is what the report
                            // prints beside an answer and what a designer says out loud. Deriving one
                            // here would renumber somebody's instrument.
                            code = section.code.takeIf { it.isNotBlank() },
                        )
                        sectionRemoteId = matchAdoptedSection(afterSection, section)
                            ?: return Result.failure(
                                IllegalStateException(
                                    "Section “${section.title}” was created but could not be found " +
                                        "again to put its questions in. Nothing has been lost — try " +
                                        "again."
                                )
                            )
                    }
                    persist(
                        state.copy(
                            currentSectionRemoteId = sectionRemoteId,
                            questionsDone = 0,
                            sectionCreateStarted = false,
                        )
                    )
                }

                var questionIndex = state.questionsDone
                while (questionIndex < section.questions.size) {
                    val question = section.questions[questionIndex]
                    addCustomQuestion(
                        id = remoteId,
                        sectionId = sectionRemoteId,
                        prompt = question.prompt,
                        helpText = question.helpText,
                        isRequired = question.isRequired,
                    )
                    questionIndex++
                    // AFTER EACH ONE. A pass that dies at question two hundred must resume at two
                    // hundred and one, not at one — see PendingEntry's `uploadedMedia` for the same
                    // discipline applied to files.
                    persist(state.copy(questionsDone = questionIndex))
                }

                sectionIndex++
                persist(state.copy(sectionsDone = sectionIndex, currentSectionRemoteId = null, questionsDone = 0))
                onProgress(sectionIndex, sections.size)
            }
            persist(state.copy(adoptedAt = Instant.now().toString()))
            return Result.success(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The reason is recorded on the row so the list can show it, and progress stays exactly
            // where it reached. A refusal is NOT a reason to delete the file or the partial
            // questionnaire — the same policy the outbox keeps.
            val reason = e.apiErrorMessage("The questionnaire could not be added.")
            persist(state.copy(failure = reason))
            return Result.failure(IllegalStateException(reason))
        }
    }

    /**
     * Find the section that was just created, in the questionnaire the create returned.
     *
     * MATCHED ON THE CODE FIRST, then on the title, and only among sections that have no questions
     * yet. Matching on "the last one" would work today and break the moment the server orders
     * `sections` by anything other than creation; matching on the title alone breaks on a
     * questionnaire with two sections called "Tools", which is an ordinary thing for a questionnaire
     * to have. The no-questions-yet filter is what separates a section just created from an older one
     * that happens to share a title.
     *
     * @param exactOnly drop the "last empty section wins" fallback. Set by the RESUME path, which is
     *   asking a different question: not "which of these did my POST just make" — it knows one was
     *   made — but "is the section I may have created already here". A wrong answer there does not
     *   merely misattribute, it FILLS somebody else's empty section with two hundred questions, so the
     *   resume would rather create a section it can name than adopt one it is guessing at.
     */
    private fun matchAdoptedSection(
        form: CustomQuestionnaireDto,
        wanted: QuestionnaireBundleSection,
        exactOnly: Boolean = false,
    ): String? {
        val fresh = form.sections.filter { it.questions.isEmpty() }
        if (wanted.code.isNotBlank()) {
            fresh.firstOrNull { it.code.equals(wanted.code, ignoreCase = true) }?.let { return it.id }
        }
        fresh.firstOrNull { it.title.trim().equals(wanted.title.trim(), ignoreCase = true) }
            ?.let { return it.id }
        if (exactOnly) return null
        return fresh.maxByOrNull { it.sortOrder }?.id
    }

    /**
     * Start a questionnaire, sending it now or banking it for when there is signal.
     *
     * ── THE DECISION IS MADE HERE AND NOT AT THE SCREEN ───────────────────────────────────────────
     *
     * Every record form on this handset asks `isOnline` and then either posts or queues, and each of
     * them got that wrong once. Deciding it in the transport means the questionnaire list has one
     * call to make and cannot forget the second branch — which is the same argument
     * `createDesignWorkshop` makes for folding its designer keys here rather than at its two callers.
     *
     * ── `isOnline` IS A HINT AND THE POST IS THE TRUTH ────────────────────────────────────────────
     *
     * A courtyard with one bar answers "online" and then times out, so a failure that
     * [isConnectionFailure] recognises falls through to the queue rather than to a red sentence. The
     * refusals do NOT: a 403 or a 422 is the server having answered, and banking it would replay a
     * rejection for ever while telling the designer it was saved. Same split, same reason, as
     * `saveOrQueue` on the web.
     *
     * @return the created row, or null when it was banked — which the caller must say out loud,
     *         because "it is on this device" and "the repository has it" are different facts.
     */
    suspend fun createCustomQuestionnaireOrQueue(
        context: Context,
        title: String,
        description: String?,
        designWorkshopId: String?,
    ): CustomQuestionnaireDto? {
        val body = CustomQuestionnaireCreateBody(
            title = title.trim(),
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            designWorkshopId = designWorkshopId?.takeIf { it.isNotBlank() },
        )
        if (isOnline(context)) {
            val sent = runCatching { api.createCustomQuestionnaire(body) }
            sent.getOrNull()?.let { return it }
            val error = sent.exceptionOrNull()
            if (error != null && error !is CancellationException && !isConnectionFailure(error)) throw error
        }
        queueOfflineEntry(
            context = context,
            type = OFFLINE_CUSTOM_QUESTIONNAIRE,
            payloadJson = offlineJson.encodeToString(body),
            label = "Questionnaire · ${body.title}",
            items = emptyList(),
        )
        return null
    }

    suspend fun createCustomQuestionnaire(
        title: String,
        description: String? = null,
        designWorkshopId: String? = null,
    ): CustomQuestionnaireDto = api.createCustomQuestionnaire(
        CustomQuestionnaireCreateBody(
            title = title.trim(),
            description = description?.trim()?.ifBlank { null },
            designWorkshopId = designWorkshopId?.takeIf { it.isNotBlank() },
        )
    )

    /**
     * Rename, re-describe, attach to a workshop, or deactivate.
     *
     * [changeWorkshop] must be set by the caller that is DECIDING the attachment, because a blank
     * [designWorkshopId] means detach and an omitted key means leave alone — two different requests
     * that a single nullable argument cannot tell apart. See [customQuestionnaireUpdateJson].
     */
    suspend fun updateCustomQuestionnaire(
        id: String,
        title: String? = null,
        description: String? = null,
        changeDescription: Boolean = false,
        designWorkshopId: String? = null,
        changeWorkshop: Boolean = false,
        isActive: Boolean? = null,
    ): CustomQuestionnaireDto = api.updateCustomQuestionnaire(
        id,
        customQuestionnaireUpdateJson(
            title = title,
            description = description,
            changeDescription = changeDescription,
            designWorkshopId = designWorkshopId,
            changeWorkshop = changeWorkshop,
            isActive = isActive,
        )
    )

    suspend fun addCustomSection(
        id: String,
        title: String,
        code: String? = null,
    ): CustomQuestionnaireDto = api.createCustomSection(
        id,
        CustomSectionCreateBody(
            title = title.trim(),
            code = code?.trim()?.ifBlank { null },
        )
    )

    /**
     * A section's title may change even when its questions have been answered — a heading is not what
     * an answer answers. Only the QUESTION's wording is frozen by an answer.
     */
    suspend fun updateCustomSection(
        id: String,
        sectionId: String,
        title: String? = null,
        sortOrder: Int? = null,
        isActive: Boolean? = null,
    ): CustomQuestionnaireDto = api.updateCustomSection(
        id,
        sectionId,
        CustomSectionPatchBody(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            sortOrder = sortOrder,
            isActive = isActive,
        )
    )

    suspend fun addCustomQuestion(
        id: String,
        sectionId: String,
        prompt: String,
        helpText: String? = null,
        isRequired: Boolean = false,
    ): CustomQuestionnaireDto = api.createCustomQuestion(
        id,
        sectionId,
        CustomQuestionCreateBody(
            prompt = prompt.trim(),
            helpText = helpText?.trim()?.ifBlank { null },
            isRequired = isRequired,
        )
    )

    /**
     * Edit one question. The RESULT says what the server actually did — `updated` or `superseded` —
     * and the caller is expected to show `detail` verbatim when it is the latter.
     *
     * [prompt] is passed only when the wording genuinely changed; see [customQuestionUpdateJson].
     */
    suspend fun updateCustomQuestion(
        id: String,
        questionId: String,
        prompt: String? = null,
        helpText: String? = null,
        changeHelpText: Boolean = false,
        isRequired: Boolean? = null,
    ): CustomQuestionEditResultDto = api.updateCustomQuestion(
        id,
        questionId,
        customQuestionUpdateJson(
            prompt = prompt,
            helpText = helpText,
            changeHelpText = changeHelpText,
            isRequired = isRequired,
        )
    )

    /**
     * Remove a question — really deleted when nobody answered it, RETIRED when somebody did.
     *
     * There is no separate "retire" call, because the choice is not the client's to make: it depends
     * on whether an answer exists at the moment of the request, which only the server knows. A UI
     * that decided locally from a stale `hasAnswers` would promise a delete and produce a retire.
     */
    suspend fun removeCustomQuestion(
        id: String,
        questionId: String
    ): CustomQuestionEditResultDto = api.removeCustomQuestion(id, questionId)

    suspend fun startCustomEntry(
        id: String,
        title: String? = null,
        respondentName: String? = null,
        notes: String? = null,
    ): CustomEntryDto = api.createCustomEntry(
        id,
        CustomEntryCreateBody(
            title = title?.trim()?.ifBlank { null },
            respondentName = respondentName?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null },
        )
    )

    suspend fun updateCustomEntry(
        id: String,
        entryId: String,
        title: String? = null,
        respondentName: String? = null,
        notes: String? = null,
    ): CustomEntryDto = api.updateCustomEntry(
        id,
        entryId,
        CustomEntryPatchBody(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            respondentName = respondentName?.trim(),
            notes = notes?.trim(),
        )
    )

    /**
     * Record answers against one sitting. Idempotent — re-sending an unchanged section writes nothing.
     *
     * The batch is built by the caller and is deliberately NOT "every question on the form": a blank
     * answer row is written for every question sent, and the edit-after-answers rule counts a blank
     * answer as no answer, so a save that posted the whole form would litter the sitting with rows
     * that mean nothing and re-stamp `answeredById` across work the saver never did.
     */
    suspend fun saveCustomAnswers(
        id: String,
        entryId: String,
        answers: List<CustomAnswerInputBody>,
    ): CustomAnswerSaveResultDto =
        api.saveCustomAnswers(id, entryId, CustomAnswerBatchBody(answers = answers))

    // ── The .xlsx interchange: three downloads and two uploads ───────────────────────────────────
    //
    // See the block comment in [WorkshopRepositoryApi] for why these exist now and did not before.
    // Everything here goes to the DOWNLOADS folder through [persistFileToDownloads], the same path
    // the dataset zip and the relational report already take, so a workbook a designer has just
    // saved is where every file manager, mail client and WhatsApp attach dialog on the handset
    // already looks — and shareable through the existing FileProvider on the pre-Q fallback.

    /**
     * Save one of the three questionnaire workbooks into the device's Downloads folder.
     *
     * THE SERVER'S OWN FILENAME IS USED WHEN IT SENDS ONE, and for this feature that is a
     * correctness requirement rather than politeness. `question_set_filename` suffixes the stem with
     * `-questions`, and its docstring gives the reason: both downloads land in the same folder under
     * the same questionnaire title, and the difference between them is the difference between
     * sending a colleague your question list and sending them every respondent you have ever
     * interviewed — "the name is the last thing standing between a designer and that mistake". A
     * client that composed its own name from the title would strip exactly that.
     *
     * [fallbackStem] is used only when the header is missing or unusable, and it carries the same
     * distinction rather than a generic "questionnaire": a fallback that lost the suffix would
     * reintroduce the confusion on precisely the deployments whose proxies rewrite headers.
     *
     * Returns where the file landed, for a message that tells the designer where to look.
     */
    suspend fun downloadQuestionnaireArtefact(
        context: Context,
        artefact: DwQuestionnaireArtefact,
        questionnaireId: String? = null,
        fallbackStem: String = "questionnaire",
    ): String = withContext(Dispatchers.IO) {
        val response = when (artefact) {
            DwQuestionnaireArtefact.PRO_FORMA -> api.questionnaireProForma()
            DwQuestionnaireArtefact.QUESTION_SET -> api.questionnaireQuestionSet(
                requireNotNull(questionnaireId) { "A question set is a download OF a questionnaire." }
            )
            DwQuestionnaireArtefact.FULL_WORKBOOK -> api.questionnaireWorkbook(
                requireNotNull(questionnaireId) { "A workbook is a download OF a questionnaire." }
            )
        }
        if (!response.isSuccessful) {
            // The server's own sentence, not a status code. The 403 on `/xlsx` names the next move
            // ("download the question set instead"), and the 409 on a re-upload names the file the
            // designer actually picked. Collapsing either into "download failed (HTTP 403)" would
            // throw away the only part of the answer that helps.
            throw IllegalStateException(
                errorBodyDetail(response.errorBody()?.string())
                    ?: "That workbook could not be downloaded (HTTP ${response.code()})."
            )
        }
        val body = response.body() ?: throw IllegalStateException("The download response was empty.")
        val name = filenameFromContentDisposition(response.headers()["Content-Disposition"])
            ?: defaultArtefactFilename(artefact, fallbackStem)
        // Spooled to the cache first and copied second, exactly as `downloadReport` does: the
        // MediaStore entry is created with IS_PENDING and cleared once the bytes are there, so a
        // transfer that dies half-way must not leave a half-written .xlsx visible in Downloads
        // looking like a file somebody can open.
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val location = persistFileToDownloads(context, tmp, name, XLSX_MIME)
        tmp.delete()
        location
    }

    /**
     * Create a NEW questionnaire from a workbook the designer picked out of the device.
     *
     * The bytes are read from the content Uri and sent whole. No re-encoding, no parsing on the
     * phone: the .xlsx grammar lives in one place, on the server, and a handset that tried to
     * validate a workbook before uploading it would be a second parser to disagree with the first.
     *
     * A FAILURE HERE IS SHOWN, NEVER QUEUED. `OfflineOutbox` exists for record creates, and this is
     * not one: the response carries the change report — which questions were read, which rows could
     * not be, and what happened to the answers — and a report nobody sees is the whole point of the
     * upload thrown away. A designer with no signal is told to try again in signal.
     */
    suspend fun uploadQuestionnaireWorkbook(
        context: Context,
        uri: Uri,
        title: String? = null,
        designWorkshopId: String? = null,
    ): QFormUploadResultDto {
        val part = workbookPart(context, uri)
        return api.uploadQuestionnaire(
            file = part,
            // Sent only when the designer typed one. An empty part is not "no title" to the server —
            // it is a present-and-blank Form field, and `title or parsed.title` would then prefer the
            // blank over the title written on the Details sheet of the file they just uploaded.
            title = title?.trim()?.takeIf { it.isNotEmpty() }?.toPlainPart(),
            designWorkshopId = designWorkshopId?.trim()?.takeIf { it.isNotEmpty() }?.toPlainPart(),
        )
    }

    /**
     * Re-upload an edited workbook over an existing questionnaire. Owner-only on the server.
     *
     * The response's `report.details` names every question that was superseded or retired and says
     * why in a sentence meant to be shown verbatim — see [QFormDetailDto].
     */
    suspend fun reuploadQuestionnaireWorkbook(
        context: Context,
        questionnaireId: String,
        uri: Uri,
        title: String? = null,
    ): QFormUploadResultDto {
        val part = workbookPart(context, uri)
        return api.reuploadQuestionnaire(
            id = questionnaireId,
            file = part,
            title = title?.trim()?.takeIf { it.isNotEmpty() }?.toPlainPart(),
        )
    }

    /**
     * Use this questionnaire again, as a template, at another design workshop.
     *
     * THE OWNER ASKED FOR THIS IN THESE WORDS: questionnaires "would usually be scoped to the
     * workshops, but the designers would have the permission to use the same questionnaire later on
     * for a different workshop as well in case they want to reuse the same template."
     *
     * IT COPIES; IT DOES NOT SHARE. Questions and sections come across, sittings and answers do not,
     * and the original keeps every answer ever recorded against it. An EMPTY call — every argument
     * left alone — makes an unattached copy this account owns, which is what a designer wants when
     * they are lifting an instrument now and will decide which workshop it serves later.
     *
     * NOT OWNER-GATED ON THE SERVER AND IT MUST NOT BE GATED HERE. The questions of any
     * questionnaire already leave this system for any designer through
     * `questionnaires/{id}/question-set.xlsx`, so refusing here would refuse in JSON what that door
     * hands over — and be routed around by downloading that file and re-uploading it, which
     * produces the same row with NO provenance recorded at all. What IS gated is the TARGET
     * workshop: `_require_attachable_workshop` wants workshop creator, admin or a viewer grant, so a
     * screen should offer only workshops this account can already write to (404 for one it cannot
     * see, 409 for a soft-deleted one). A DEACTIVATED SOURCE IS STILL REUSABLE, deliberately —
     * `isActive: false` is this API's stand-in for a delete, and a retired instrument is exactly the
     * thing a designer wants to lift for a new round, so do not filter it out of whatever list
     * offers this.
     *
     * [changeDescription] is the tri-state's switch and not a redundant flag: leave it false to
     * carry the source's description across, set it true to decide it — [description] blank then
     * meaning "start it empty". See [questionnaireReuseJson] for why that needs a JsonObject rather
     * than a data class, and [QFormReuseResultDto] for what comes back (the upload result's shape,
     * key for key, so the existing upload-report panel renders it with nothing new to keep in step).
     */
    suspend fun reuseQuestionnaire(
        id: String,
        designWorkshopId: String? = null,
        title: String? = null,
        description: String? = null,
        changeDescription: Boolean = false,
    ): QFormReuseResultDto = api.reuseQuestionnaire(
        id,
        questionnaireReuseJson(
            designWorkshopId = designWorkshopId,
            title = title,
            description = description,
            changeDescription = changeDescription,
        )
    )

    /**
     * One picked document, as the multipart file part both upload routes take.
     *
     * THE FILENAME IS CARRIED THROUGH, because the server stores it as `sourceFilename` and shows it
     * on the questionnaire ("From artisan-survey.xlsx"). A designer with three uploads in a week
     * uses that line to tell which spreadsheet a questionnaire came out of, and a hardcoded
     * "upload.xlsx" would make all three look the same.
     *
     * The bytes are read into memory rather than streamed. That is a deliberate exception to what
     * [uploadResolved] does for media, and it is bounded by what this actually is: a questionnaire
     * workbook is tens of kilobytes of XML — the pro-forma itself is a few — whereas the media path
     * streams because it carries multi-hundred-megabyte video. Spooling a copy of a 40 KB file to
     * disk to avoid holding it in the heap would be ceremony.
     */
    private suspend fun workbookPart(context: Context, uri: Uri): okhttp3.MultipartBody.Part {
        val mimeType = context.contentResolver.getType(uri) ?: XLSX_MIME
        val name = displayName(context, uri)?.let { safeDownloadName(it) } ?: "questionnaire.xlsx"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException(
                    "That file could not be opened on this device. Pick it again from Downloads, or " +
                        "copy it onto the phone first — some cloud folders hand out a link rather " +
                        "than the file."
                )
        }
        if (bytes.isEmpty()) {
            throw IllegalStateException("That file is empty, so there is nothing to read out of it.")
        }
        return okhttp3.MultipartBody.Part.createFormData(
            "file",
            name,
            bytes.toRequestBody(mimeType.toMediaType())
        )
    }

    /** A scalar on a multipart body — what `Form(...)` on the server reads. */
    private fun String.toPlainPart(): okhttp3.RequestBody =
        toRequestBody("text/plain".toMediaType())

    /**
     * The filename used only when the server sent no usable `Content-Disposition`.
     *
     * KEEPS THE `-questions` DISTINCTION. A fallback that named both downloads after the title alone
     * would reintroduce, on exactly the deployments whose proxies strip headers, the confusion the
     * suffix exists to prevent.
     */
    private fun defaultArtefactFilename(
        artefact: DwQuestionnaireArtefact,
        stem: String,
    ): String {
        val safe = stem.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(60)
            .ifBlank { "questionnaire" }
        return when (artefact) {
            DwQuestionnaireArtefact.PRO_FORMA -> "questionnaire-pro-forma.xlsx"
            DwQuestionnaireArtefact.QUESTION_SET -> "$safe-questions.xlsx"
            DwQuestionnaireArtefact.FULL_WORKBOOK -> "$safe.xlsx"
        }
    }

    suspend fun login(email: String, password: String): UserDto {
        val response = api.login(LoginRequest(email = email.trim(), password = password))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    suspend fun loginWithGoogle(idToken: String): UserDto {
        val response = api.googleLogin(GoogleLoginRequest(googleIdToken = idToken))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    /**
     * End the session AND forget what this device remembered on that person's behalf.
     *
     * ── WHY THIS TAKES A CONTEXT NOW, AND WHAT IT USED TO LEAVE BEHIND ────────────────────────
     *
     * It cleared the token and nothing else. [customQuestionnaireCached]'s KDoc claims a questionnaire
     * this account may no longer read is never "served out of this device's memory of when they
     * could", and that is earned against a REVOKED GRANT — a 403 is the server answering, so the cache
     * is not consulted — but it was not earned against a CHANGE OF PERSON. Sign out, hand the handset
     * to the second designer in the cluster, sign in as them, and they were served the first
     * designer's cached form: [CustomQuestionnaireDto.entries] and therefore `respondentName` and the
     * answers given. `DwQuestionnaireFormCache.forget` existed for exactly this and was never called
     * from anywhere except its own file.
     *
     * THE TOKEN GOES FIRST and the deletion is best-effort, because those two are not equally
     * important: a designer handing the phone over must end up signed out even if a file will not
     * delete. `DwQuestionnaireStore` is deliberately NOT cleared here — it holds the report annexure's
     * evidence, it is unscoped by existing design, and emptying it on sign-out would silently destroy
     * a courtyard export somebody is relying on. That one is a decision for its owner, stated in
     * `DwQuestionnaireFormCache`'s header, not a side effect of this change.
     */
    suspend fun logout(context: Context) {
        tokenStore.clear()
        runCatching { DwQuestionnaireFormCache.forgetAll(context) }
    }

    suspend fun currentUser(): UserDto = api.me()

    /** Refresh the profile from the server and update the local cache. */
    suspend fun refreshUser(): UserDto {
        val user = api.me()
        tokenStore.setUser(user)
        return user
    }

    suspend fun stats(): DashboardStats = api.dashboardStats()

    /**
     * The state / union-territory list an address form renders its dropdown from.
     *
     * Cached for the life of the process. The payload is a server-side constant, so re-asking on
     * every form would buy nothing; a FAILURE is deliberately not cached, so the next form that opens
     * after the phone finds signal asks again rather than being stuck with an empty dropdown for the
     * rest of the session.
     */
    suspend fun addressReference(): AddressReferenceDto =
        cachedAddressReference ?: api.addressReference().also { cachedAddressReference = it }

    @Volatile
    private var cachedAddressReference: AddressReferenceDto? = null

    suspend fun users(): List<UserDto> = api.users(pageSize = 100).items

    suspend fun updateUserQuestionnaireAccess(id: String, canManageQuestionnaire: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageQuestionnaire = canManageQuestionnaire))

    suspend fun updateUserCraftAccess(id: String, canManageCrafts: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageCrafts = canManageCrafts))

    suspend fun updateUserWorkshopAccess(id: String, canManageWorkshops: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageWorkshops = canManageWorkshops))

    suspend fun updateUserReviewAccess(id: String, canReview: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canReview = canReview))

    suspend fun updateUserProvenanceAccess(id: String, canViewProvenance: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canViewProvenance = canViewProvenance))

    suspend fun updateUserDatasetAccess(id: String, canDownloadDataset: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canDownloadDataset = canDownloadDataset))

    /** Change a user's role (e.g. elevate RESEARCHER -> ADMIN). Master-admin gated server-side for ADMIN+. */
    suspend fun updateUserRole(id: String, role: String): UserDto =
        api.updateUser(id, UserUpdateRequest(role = role))

    // --- Cross-researcher data access (Sharing) ---
    suspend fun userDirectory(): List<UserDto> = api.userDirectory()
    suspend fun dataAccessTiers(): List<DataAccessTierInfo> = api.dataAccessTiers()
    suspend fun dataAccessGrants(): MyGrantsDto = api.dataAccessGrants()
    suspend fun requestDataAccess(ownerId: String, tier: String, note: String?): DataAccessGrantDto =
        api.requestDataAccess(DataAccessRequestBody(ownerId = ownerId, tier = tier, allData = true, requestNote = note?.ifBlank { null }))
    suspend fun grantDataAccess(granteeId: String, tier: String, allData: Boolean, scopeItems: List<DataAccessScopeItemDto>): DataAccessGrantDto =
        api.grantDataAccess(DataAccessGrantBody(granteeId = granteeId, tier = tier, allData = allData, scopeItems = scopeItems))
    suspend fun decideDataAccess(id: String, status: String, tier: String?): DataAccessGrantDto =
        api.decideDataAccess(id, DataAccessDecisionBody(status = status, tier = tier))
    suspend fun revokeDataAccess(id: String): DataAccessGrantDto = api.revokeDataAccess(id)
    suspend fun deleteDataAccess(id: String) = api.deleteDataAccess(id)
    suspend fun entryComments(recordType: String, recordId: String): List<EntryCommentDto> =
        api.entryComments(recordType, recordId)
    suspend fun addEntryComment(recordType: String, recordId: String, body: String): EntryCommentDto =
        api.addEntryComment(EntryCommentBody(recordType = recordType, recordId = recordId, body = body))

    /**
     * Withdraw a comment. The server allows the AUTHOR or an admin, and refuses anyone else with 403.
     *
     * The screen offers the control on the same test rather than letting everybody try and be
     * refused — see `RecordCollabSection` — but the server's rule is the one that decides, exactly as
     * it does on the web.
     */
    suspend fun deleteEntryComment(commentId: String) = api.deleteEntryComment(commentId)
    suspend fun recordRevisions(recordType: String, recordId: String): List<RecordRevisionDto> =
        api.recordRevisions(recordType, recordId)

    // --- Workshop assignment (admin roster for ONE workshop) ---
    suspend fun workshopAssignments(workshopId: String): List<WorkshopAssignmentDto> =
        api.workshopAssignments(workshopId)

    /**
     * Replace the whole roster. Everyone in [userIds] becomes GRANTED; everyone dropped is REVOKED
     * (not deleted, so "X had access until Y removed them" survives). An EMPTY set therefore revokes
     * everybody, which — with no granted admin row left — reopens the workshop to all.
     */
    suspend fun setWorkshopAssignments(workshopId: String, userIds: List<String>, accessLevel: String? = null): List<WorkshopAssignmentDto> =
        api.setWorkshopAssignments(workshopId, WorkshopAssignmentBody(userIds, accessLevel))

    /** Grant one user access at a level without disturbing the rest of the roster (upsert). */
    suspend fun grantWorkshopAccess(workshopId: String, userId: String, accessLevel: String, note: String? = null): WorkshopAssignmentDto =
        api.grantWorkshopAssignment(workshopId, WorkshopGrantBody(userId = userId, accessLevel = accessLevel, note = note?.blankToNull()))

    /** Raise/lower one roster row's level, and/or set it GRANTED | DENIED | REVOKED. */
    suspend fun updateWorkshopAccess(workshopId: String, userId: String, accessLevel: String? = null, status: String? = null, note: String? = null): WorkshopAssignmentDto =
        api.updateWorkshopAssignment(
            workshopId,
            userId,
            WorkshopAssignmentUpdateBody(accessLevel = accessLevel, status = status, note = note?.blankToNull())
        )

    suspend fun revokeWorkshopAccess(workshopId: String, userId: String): WorkshopAssignmentDto =
        api.revokeWorkshopAssignment(workshopId, userId)

    // --- Workshop access requests (user side + admin queue) ---
    suspend fun workshopAccessLevels(): List<WorkshopAccessLevelDto> = api.workshopAccessLevels()

    /** Ask for access to several workshops at once. Idempotent per workshop; see the outcomes list. */
    suspend fun requestWorkshopAccess(workshopIds: List<String>, accessLevel: String?, note: String?): WorkshopAccessRequestResultDto =
        api.requestWorkshopAccess(
            WorkshopAccessRequestBody(
                workshopIds = workshopIds,
                accessLevel = accessLevel?.blankToNull(),
                note = note?.blankToNull()
            )
        )

    /** Every workshop-access row belonging to me: held, waiting, and refused — not just the pending ones. */
    suspend fun myWorkshopAccess(): List<WorkshopAssignmentDto> = api.myWorkshopAccess()

    /** Admin: the PENDING approval queue across ALL workshops (oldest first). */
    suspend fun workshopAccessQueue(statusFilter: String = "PENDING"): List<WorkshopAssignmentDto> =
        api.workshopAccessRequests(statusFilter)

    /** Admin: answer a PENDING request. [status] is GRANTED or DENIED; anything else is a 422. */
    suspend fun decideWorkshopAccess(requestId: String, status: String, accessLevel: String? = null, note: String? = null): WorkshopAssignmentDto =
        api.decideWorkshopAccess(
            requestId,
            WorkshopAccessDecisionBody(status = status, accessLevel = accessLevel, note = note?.blankToNull())
        )

    // --- Assigned tasks ---

    /**
     * My to-do list. [view] "created"/"all" are admin-only planning views and 403 for everyone else.
     *
     * [assigneeId] and [batchId] are admin-only narrowings — on the default "assigned" view the API
     * hard-pins the list to the caller, so they cannot be used to read somebody else's tasks.
     */
    /**
     * Exact per-status totals for the filter chips, mirroring the web's `loadCounts` on /tasks.
     *
     * One tiny call per status (`pageSize=1`, derivation off) rather than counting the list on
     * screen: the list is one page and a count taken from it would be a count of what is displayed,
     * which is not the question a chip labelled "Done 14" is answering. `withDerived = false` skips
     * the data-backed rollup the API would otherwise compute for rows nobody is going to read.
     *
     * Failures are the CALLER's to swallow — an unlabelled chip is a working filter, and the list
     * itself is what the designer came for.
     */
    suspend fun taskCounts(view: String = "assigned", statuses: List<String>): Map<String, Int> =
        statuses.associateWith { status ->
            api.tasks(view = view, status = status, pageSize = 1, withDerived = false).total
        }

    suspend fun tasks(
        view: String = "assigned",
        status: String? = null,
        workshopId: String? = null,
        assigneeId: String? = null,
        batchId: String? = null,
        pageSize: Int = 100
    ): List<TaskDto> =
        api.tasks(
            view = view,
            status = status?.blankToNull(),
            workshopId = workshopId?.blankToNull(),
            pageSize = pageSize,
            assigneeId = assigneeId?.blankToNull(),
            batchId = batchId?.blankToNull()
        ).items

    /** One task, enriched exactly like a list item. Visible to the assignee, the creator and admins. */
    suspend fun task(taskId: String): TaskDto = api.task(taskId)

    /** Assignee-side update: move the status and/or report how much is done. */
    suspend fun updateTaskProgress(taskId: String, status: String? = null, progressCount: Int? = null): TaskDto =
        api.updateTask(taskId, TaskUpdateBody(status = status, progressCount = progressCount))

    // --- Task administration (admin) ---

    /**
     * Every picker the assignment builder needs, in one call. Pass [workshopId] to narrow the artisan
     * list to that workshop; the assignee list is already filtered to who this admin may assign to.
     *
     * [search] IS THE ONLY WAY PAST THE THREE CAPS, and two of them are live on this deployment's
     * measured population (3632 accounts, 731 artisans — docs/OPEN_FINDINGS.md, 2026-08-13). The
     * server folds the term into the WHERE of all three queries, so it reaches past the ceiling
     * instead of searching the first 500 names and stopping exactly where the cap already stopped.
     * ONE term for all three pickers, not three: FastAPI silently DISCARDS a query parameter the
     * route does not declare, so a handset sending `assigneeSearch` would draw a search box that
     * narrows nothing — the defect this exists to close, in different clothes.
     *
     * `take(120)` IS NOT DECORATION. The route declares `Query(None, max_length=120)`, so a longer
     * term is a 422 on a request the admin reads as a search that simply failed — and a name pasted
     * out of a spreadsheet cell is the ordinary way to exceed it. Trimmed first so a term that is
     * all whitespace becomes "no search" rather than a filter matching nothing, then capped, then
     * [blankToNull] so the key is omitted rather than sent empty.
     *
     * THE RULE THAT TRAVELS WITH IT, for whoever wires the dialog: never read the absence of an
     * ALREADY-SELECTED id from a narrowed list as "that record is gone" and clear the selection.
     * `ProductForm` on the web does exactly that against a capped page and unlinks the artisan.
     */
    suspend fun taskOptions(workshopId: String? = null, search: String? = null): TaskOptionsDto =
        api.taskOptions(workshopId?.blankToNull(), search?.trim()?.take(120)?.blankToNull())

    /**
     * Hand ONE scope to several people at once — the assignment action. All-or-nothing: a bad
     * assignee or a typo'd artisan id fails the whole call rather than leaving half a batch behind.
     *
     * The scope must contain work ([recordTypes] and/or [sectionIds] non-empty) or the API 422s.
     * Empty [artisanIds]/[sectionIds] mean "not narrowed". Omit [title] to let the server derive a
     * readable one from the scope. [dueAt] is ISO-8601.
     */
    suspend fun createTaskBatch(
        assigneeIds: List<String>,
        workshopId: String? = null,
        recordTypes: List<String> = emptyList(),
        artisanIds: List<String> = emptyList(),
        sectionIds: List<String> = emptyList(),
        targetCount: Int? = null,
        title: String? = null,
        description: String? = null,
        dueAt: String? = null
    ): TaskBatchResultDto =
        api.createTaskBatch(
            TaskBatchCreateBody(
                assigneeIds = assigneeIds,
                workshopId = workshopId?.blankToNull(),
                recordTypes = recordTypes,
                artisanIds = artisanIds,
                sectionIds = sectionIds,
                targetCount = targetCount,
                title = title?.blankToNull(),
                description = description?.blankToNull(),
                dueAt = dueAt?.blankToNull()
            )
        )

    /**
     * Assignments grouped back into the action that created them, newest first. The filters choose
     * which batches are SHOWN; every count reported is for the whole batch regardless.
     */
    suspend fun taskBatches(
        workshopId: String? = null,
        view: String = "all",
        batchId: String? = null,
        assigneeId: String? = null,
        status: String? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): PageResponse<TaskBatchDto> =
        api.taskBatches(
            view = view,
            workshopId = workshopId?.blankToNull(),
            batchId = batchId?.blankToNull(),
            assigneeId = assigneeId?.blankToNull(),
            status = status?.blankToNull(),
            page = page,
            pageSize = pageSize
        )

    /**
     * The accountability rollup: what each person was given, what they claim, and what the repository
     * can actually find them having produced. Leave [workshopId] off for the organisation-wide view.
     */
    suspend fun taskProgress(
        workshopId: String? = null,
        assigneeId: String? = null,
        includeFinished: Boolean = true
    ): TaskProgressReportDto =
        api.taskProgress(
            workshopId = workshopId?.blankToNull(),
            assigneeId = assigneeId?.blankToNull(),
            includeFinished = includeFinished
        )

    /** Withdraw a whole assignment. Only the admin who sent it, or the master admin, may unsend it. */
    suspend fun deleteTaskBatch(batchId: String) = api.deleteTaskBatch(batchId)

    /** Withdraw ONE row — the way to remove a pre-batch/single-assignee assignment (batchId null). */
    suspend fun deleteTask(taskId: String) = api.deleteTask(taskId)

    // --- Managed provider keys (MASTER ADMIN ONLY; everyone else gets a 403) ---

    /**
     * Every manageable key with where its value comes from and how its last test went. No provider is
     * contacted, and no row here ever carries a value — only a four-character hint.
     */
    suspend fun managedSecrets(): List<ManagedSecretDto> = api.managedSecrets()

    /** The plaintext of ONE key, for the eye button. The read is audit-logged server-side. */
    suspend fun revealSecret(key: String): ManagedSecretRevealDto = api.revealSecret(key)

    /**
     * Set or rotate a key. Takes effect on the next provider call — no restart, no redeploy. Blank is
     * a 422 by design: use [clearSecret] to fall back to the deployed environment value.
     */
    // ── A designer's OWN provider keys ────────────────────────────────────────────────────
    // Thin pass-throughs: there is no caching and no local store for these on purpose. A key is a
    // credential, and the only copy that should exist on the handset is the one being typed.

    suspend fun aiProviders(): AiCatalogueDto = api.aiProviders()

    suspend fun myAiKeys(): List<UserAiKeyDto> = api.myAiKeys()

    suspend fun setMyAiKey(provider: String, key: String?, model: String?): UserAiKeyDto =
        api.setMyAiKey(provider, UserAiKeySetBody(key = key?.takeIf { it.isNotBlank() }, model = model))

    suspend fun deleteMyAiKey(provider: String): UserAiKeyDto = api.deleteMyAiKey(provider)

    suspend fun testMyAiKey(provider: String): UserAiKeyDto = api.testMyAiKey(provider)

    suspend fun setSecret(key: String, value: String): ManagedSecretDto =
        api.setSecret(key, ManagedSecretSetBody(value = value.trim()))

    /** Drop the stored override so the environment value applies again. Returns the key's new state. */
    suspend fun clearSecret(key: String): ManagedSecretDto = api.clearSecret(key)

    /** Call the provider once with the key in force; the verdict is persisted onto the row. */
    suspend fun testSecret(key: String): ManagedSecretDto = api.testSecret(key)

    // --- Appearance + accessibility preferences ---

    /**
     * This account's saved preferences, or NULL when it has never saved any.
     *
     * Null means "no opinion yet", not "the defaults": keep whatever the device already applied and
     * seed the server with it via [savePreferences], rather than snapping the user back to system.
     */
    suspend fun myPreferences(): PreferencesDto? = api.myPreferences().takeIf { it.exists }

    /**
     * Create or update this account's preferences. Sent whole on every save. [theme] is
     * `system` | `light` | `dark`; anything else is a 422.
     */
    suspend fun savePreferences(
        theme: String = "system",
        reducedMotion: Boolean = false,
        largerText: Boolean = false,
        highContrast: Boolean = false
    ): PreferencesDto =
        api.updateMyPreferences(
            PreferencesUpdateBody(
                theme = theme,
                reducedMotion = reducedMotion,
                largerText = largerText,
                highContrast = highContrast
            )
        )

    /** Save a whole [PreferencesDto] back (the round-trip form of [savePreferences]). */
    suspend fun savePreferences(preferences: PreferencesDto): PreferencesDto =
        savePreferences(
            theme = preferences.theme,
            reducedMotion = preferences.reducedMotion,
            largerText = preferences.largerText,
            highContrast = preferences.highContrast
        )

    // --- Global search ---

    /**
     * Search artisans, workshops, products, tools and media at once. Every argument is optional; the
     * five buckets share one [page]/[pageSize] but each has its own length and its own total, so page
     * against `totals`/`pageCount`, never against how full one bucket happens to be.
     *
     * Every filter ANDs: a query plus a place plus a date range narrows to the rows satisfying all
     * three, never their union.
     *
     * [types] names the buckets to search in the API's own PLURAL vocabulary — `artisans`,
     * `workshops`, `products`, `tools`, `media` — not the singular record type a search hit reports.
     * Null or empty searches all five. An unrecognised name is a 422 rather than a silent omission,
     * so nothing here invents one: the caller passes the canonical list and this only tidies it.
     *
     * [dateFrom]/[dateTo] are ISO-8601 instants. The API takes DATES, never preset names — "Last 30
     * days" is a phrase in a UI and only the client knows the clock it is counted against — so the
     * caller resolves its presets before it gets here. [pageSize] is capped at 50 server-side.
     */
    suspend fun search(
        q: String? = null,
        craftId: String? = null,
        place: String? = null,
        artisanId: String? = null,
        mediaType: String? = null,
        types: List<String>? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        workshopIds: List<String>? = null,
        page: Int = 1,
        pageSize: Int = 10
    ): SearchResultsDto =
        api.search(
            q = q?.blankToNull(),
            craftId = craftId?.blankToNull(),
            place = place?.blankToNull(),
            artisanId = artisanId?.blankToNull(),
            mediaType = mediaType?.blankToNull(),
            types = types
                ?.mapNotNull { it.trim().lowercase().blankToNull() }
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(","),
            dateFrom = dateFrom?.blankToNull(),
            dateTo = dateTo?.blankToNull(),
            workshopIds = workshopIds.toQueryCsv(),
            page = page,
            pageSize = pageSize.coerceIn(1, 50)
        )

    // --- Map: where the records are ---

    /**
     * Every pin for the current filters, in BOTH layers — where the craft comes from (ORIGIN) and
     * where it was recorded (CAPTURE). The filter vocabulary is [search]'s, argument for argument, so
     * one set of UI filters drives both and the two can never disagree about what a phrase contains.
     *
     * [types] names the buckets in the API's PLURAL vocabulary (`artisans`, `workshops`, `products`,
     * `tools`, `media`); null or empty counts all five.
     *
     * [workshopIds] is the shared workshop SCOPE. Null or empty means EVERY workshop — it is not a
     * narrowing at all — and the reserved id `none` means "records linked to no workshop", so
     * `listOf("none")` is a real and different question from `null`.
     *
     * [level] is `NATION` | `STATE` | `DISTRICT`: the administrative unit both layers are grouped at.
     * Null lets the server apply its own default rather than hard-coding a second copy of it here;
     * read the level actually used back off `MapPointsDto.level`, and build the toggle from
     * `MapPointsDto.levels`.
     *
     * [focusType] + [focusId] ask for one record in context. Pass BOTH or neither — one alone is
     * ignored — and note the map still draws the whole filtered corpus; the focus only names which
     * pins hold that record, in `MapFocusDto.pointKeys`.
     */
    suspend fun mapPoints(
        q: String? = null,
        craftId: String? = null,
        place: String? = null,
        artisanId: String? = null,
        mediaType: String? = null,
        types: List<String>? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        workshopIds: List<String>? = null,
        level: String? = null,
        focusType: String? = null,
        focusId: String? = null
    ): MapPointsDto =
        api.mapPoints(
            q = q?.blankToNull(),
            craftId = craftId?.blankToNull(),
            place = place?.blankToNull(),
            artisanId = artisanId?.blankToNull(),
            mediaType = mediaType?.blankToNull(),
            types = types?.map { it.lowercase() }.toQueryCsv(),
            dateFrom = dateFrom?.blankToNull(),
            dateTo = dateTo?.blankToNull(),
            workshopIds = workshopIds.toQueryCsv(),
            level = level?.blankToNull(),
            focusType = focusType?.blankToNull(),
            focusId = focusId?.blankToNull()
        )

    /**
     * The records behind ONE pin, fetched when a reader opens it rather than carried by [mapPoints] —
     * the aggregate is a couple of dozen pins, but the records behind every pin would be the whole
     * corpus in a payload that exists to draw thirteen dots.
     *
     * [key] is `MapPointDto.key`, passed through UNTOUCHED: it holds ':' and '|' and the encoding is
     * Retrofit's job (see `WorkshopRepositoryApi.mapPointRecords`). Do not trim, split or re-case it.
     *
     * PASS THE SAME FILTERS THE MAP WAS DRAWN WITH, [level] and [workshopIds] included. The key names
     * an administrative unit; which records sit in it is exactly what the filters decide, so a panel
     * fetched with different filters would list records the pin was not counting.
     */
    suspend fun mapPointRecords(
        key: String,
        q: String? = null,
        craftId: String? = null,
        place: String? = null,
        artisanId: String? = null,
        mediaType: String? = null,
        types: List<String>? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        workshopIds: List<String>? = null,
        level: String? = null
    ): MapPointRecordsDto =
        api.mapPointRecords(
            key = key,
            q = q?.blankToNull(),
            craftId = craftId?.blankToNull(),
            place = place?.blankToNull(),
            artisanId = artisanId?.blankToNull(),
            mediaType = mediaType?.blankToNull(),
            types = types?.map { it.lowercase() }.toQueryCsv(),
            dateFrom = dateFrom?.blankToNull(),
            dateTo = dateTo?.blankToNull(),
            workshopIds = workshopIds.toQueryCsv(),
            level = level?.blankToNull()
        )

    // --- Data browser ---

    /**
     * ONE level of the virtual data tree. Lazy: only this level's queries run, so navigate by calling
     * this again with an entry's `path`. `path = ""` is the taxonomy chooser, not a folder listing.
     *
     * Needs the dataset-download permission (403 otherwise) and everything listed is already filtered
     * to what the caller may see.
     */
    suspend fun dataTree(path: String = ""): DataTreeDto = api.dataTree(path)

    /**
     * The tree folder that holds [recordId], or null when nothing files it yet — an artisan who has
     * never been attached to a workshop genuinely has no folder, so the caller must say so rather
     * than open the nearest one, which would belong to somebody else.
     *
     * [recordType] is one of `workshop`, `craft`, `artisan`, `product`, `tool`, `process`,
     * `interview`, `media` — the same vocabulary the search buckets hand back.
     */
    suspend fun locateRecord(recordType: String, recordId: String): String? {
        val body = api.dataLocate(recordType, recordId) as? JsonObject ?: return null
        return (body["path"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * The flattened subtree below [path]. [include] is a CSV of
     * `text,images,videos,audios,transcripts,documents,other`; null means everything.
     */
    suspend fun dataManifest(path: String = "", include: String? = null): DataManifestDto =
        api.dataManifest(path, include?.blankToNull())

    /**
     * Records awaiting review (status PENDING), newest first, across record types.
     *
     * RETURNS THE WHOLE ENVELOPE, not `.items`. It collapsed to the list until 2026-08-27, which
     * threw away `truncated`, `cap` and `total` — so a queue the server had cut at 200 of each of
     * six record types reached the screen indistinguishable from a queue that was simply that
     * length, and the rows dropped are the oldest. See [PendingReviewListDto].
     */
    suspend fun pendingReviews(): PendingReviewListDto = api.pendingReviews()

    /** Approve a pending record (admins, or users granted the review permission). */
    suspend fun approveRecord(recordType: String, recordId: String) {
        api.approveRecord(recordType, recordId, ReviewActionRequest())
    }

    /** Reject a pending record (admins, or users granted the review permission). */
    suspend fun rejectRecord(recordType: String, recordId: String) {
        api.rejectRecord(recordType, recordId, ReviewActionRequest())
    }

    /** Send a record back to its creator. [notes] is mandatory — the API 422s on a blank one. */
    suspend fun reviseRecord(recordType: String, recordId: String, notes: String) {
        api.reviseRecord(recordType, recordId, ReviewActionRequest(notes = notes))
    }

    /**
     * Reviewer edit: fix the record's values in place rather than bouncing it back. Only the keys in
     * [fields] are written and the status is left alone, so this is never a back-door approval.
     */
    suspend fun editReviewedRecord(recordType: String, recordId: String, fields: Map<String, String>, note: String?) {
        api.editReviewedRecord(
            recordType,
            recordId,
            ReviewEditRequest(fields = fields, note = note?.blankToNull())
        )
    }

    // --- Over-the-air app update ---

    /** versionCode baked into the currently-installed app, for comparing against a published release. */
    fun installedVersionCode(context: Context): Int {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pkg.versionCode
    }

    /**
     * Master admin: publish the currently-installed APK as the over-the-air update for everyone. The
     * app reads its own installed APK, uploads it to object storage, and records the version so other
     * devices can discover and self-install it on next launch.
     */
    suspend fun publishAppUpdate(context: Context): AppReleaseDto {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = installedVersionCode(context)
        val versionName = pkg.versionName ?: versionCode.toString()
        val apk = File(context.applicationInfo.sourceDir)
        val size = apk.length()
        val mime = "application/vnd.android.package-archive"
        val presign = api.presignMedia(
            MediaPresignRequest(
                // Rebranded, and safe to rebrand: this string is the DISPLAY filename on the
                // uploaded media row, not the object key — `presignMedia` returns the key the
                // server chose, and everything afterwards addresses the object by that. A release
                // published before this change keeps its old name and is still resolvable, because
                // nothing ever looks a release up by filename. It is matched here to
                // `_download_filename` in backend/app/api/routes/app_release.py, which names the
                // file a sideloader actually receives; the two disagreeing is not a fault but it
                // does make an admin comparing the media list to a phone's Downloads folder think
                // they are looking at two different builds.
                filename = "design-workshop-v$versionCode.apk",
                mimeType = mime,
                mediaType = "DOCUMENT",
                sizeBytes = size
            )
        )
        withContext(Dispatchers.IO) {
            putToStorage(presign.uploadUrl, presign.headers, size, mime, { FileInputStream(apk) }, null)
        }
        return api.publishAppRelease(
            AppReleasePublishRequest(
                versionCode = versionCode,
                versionName = versionName,
                objectKey = presign.objectKey,
                url = presign.publicUrl
            )
        )
    }

    /** The currently-published release (highest versionCode), or versionCode 0 when none exists. */
    suspend fun latestAppRelease(): AppReleaseDto = api.latestAppRelease()

    /** The current user's own app feedback (empty/blank id when they haven't given any yet). */
    suspend fun myFeedback(): FeedbackDto = api.myFeedback()

    /** Create or update the current user's detailed feedback (they can revisit and change it anytime). */
    suspend fun upsertMyFeedback(request: FeedbackUpsertRequest): FeedbackDto =
        api.upsertMyFeedback(request)

    /** Master-admin only: all users' feedback, newest first, each with its author. */
    suspend fun allFeedback(): List<FeedbackDto> = api.allFeedback()

    /** Master-admin only: the global app settings (transcription mode + off-peak processing window). */
    suspend fun appSettings(): AppSettingDto = api.appSettings()

    /** Master-admin only: update the global app settings. */
    suspend fun updateAppSettings(request: AppSettingUpdateRequest): AppSettingDto =
        api.updateAppSettings(request)

    /**
     * The media processing queue, newest first — what became of the transcription job each audio
     * upload enqueued. Open to everyone: the server returns every job to an admin and only the
     * caller's own to anyone else, so there is deliberately no rank check here.
     *
     * Pass [statusFilter] = "FAILED" for the state this exists to surface. A failed job is ordered
     * by creation date like any other, so on a long queue it can sit pages deep — filtering is the
     * only way a reader reliably finds one.
     */
    suspend fun mediaJobs(statusFilter: String? = null, pageSize: Int = 20): List<MediaProcessingJobDto> =
        api.mediaJobs(statusFilter = statusFilter, pageSize = pageSize).items

    /** How many jobs are in one state — for saying "3 failed" without paging through the queue. */
    suspend fun mediaJobCount(statusFilter: String): Int =
        api.mediaJobs(statusFilter = statusFilter, pageSize = 1).total

    /**
     * Admin/master-admin: put a job back to QUEUED (lock and error cleared, runAfter now) so the
     * next drain re-runs it. Backend is `require_admin` — NOT the uploader — so gate any control on
     * the admin roles, never on ownership.
     */
    suspend fun retryMediaJob(jobId: String): MediaProcessingJobDto = api.retryMediaJob(jobId)

    /**
     * Admin/master-admin: drain the queue now rather than waiting for the worker's next tick.
     * `processed == 0` does not mean the queue is empty — transcription runs only inside the
     * off-peak window or while the server is idle, and pauses during a provider cooldown.
     */
    suspend fun processMediaJobs(limit: Int? = null): MediaQueueRunDto = api.processMediaJobs(limit)

    /** Admin-only: media files whose parent record was deleted (recoverable, not lost). */
    suspend fun orphanedMedia(): List<MediaFileDto> = api.orphanMedia()

    /** Admin-only: re-attach an orphaned/mis-linked media file to an existing record. */
    suspend fun relinkMedia(mediaId: String, linkedRecordType: String, linkedRecordId: String): MediaFileDto =
        api.relinkMedia(mediaId, MediaRelinkRequest(linkedRecordType = linkedRecordType, linkedRecordId = linkedRecordId))

    /**
     * AI-refine a media file's transcript into a clean interviewer/interviewee conversation (Markdown),
     * optionally translated to English. Billable (gpt-4o-mini) — the caller confirms cost first.
     */
    suspend fun refineTranscript(mediaId: String, translate: Boolean): TranscriptRefineResponse =
        api.refineTranscript(mediaId, TranscriptRefineRequest(translate = translate))

    /** Save an approved (AI-refined) transcript in place of the stored one. Uploader or admin only. */
    suspend fun applyTranscript(mediaId: String, text: String): MediaFileDto =
        api.setTranscript(mediaId, TranscriptUpdateRequest(text = text))

    /**
     * Admin/master-admin: transcribe an audio media file right now, applying the transcription mode
     * configured on the settings page (raw / refined / refined+translated), bypassing the off-peak
     * window. Returns the updated media row (its transcriptStatus/Text reflect the outcome).
     */
    suspend fun transcribeNow(mediaId: String): MediaFileDto = api.transcribeNow(mediaId)

    /** Download an update APK to the cache and return the file, for handing to the system installer. */
    suspend fun downloadApk(context: Context, url: String, versionCode: Int): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } } // drop older downloads
        // Purely local: this file lives in cacheDir/updates, which the line above empties on every
        // download, and it is handed straight to the system installer by path. Nothing persists it
        // and nothing looks it up by name, so the rebrand costs at most one redundant re-download
        // on the first update after this ships — and in fact not even that, since the directory is
        // cleared first regardless of what the files were called.
        val out = File(dir, "design-workshop-v$versionCode.apk")
        val request = Request.Builder().url(url).get().build()
        storageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Update download failed: HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Update download returned no body")
            body.byteStream().use { input -> FileOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) } }
        }
        out
    }

    /**
     * [workshopIds] is the shared workshop scope: null or empty is EVERY workshop, and the reserved
     * id `none` asks for artisans linked to no workshop. Broader than the singular `workshopId` the
     * form pickers use — it also counts an artisan who merely sat in an interview taken at the
     * workshop — so this list and the completion matrix agree about who was there.
     */
    /**
     * ============================================================================================
     * [createdBy] — WHOSE RECORDS. IT MUST BE ASKED OF THE SERVER, NEVER SIFTED OUT OF THE ANSWER.
     * ============================================================================================
     *
     * Every list function here takes ONE page of a hundred rows. That was indistinguishable from
     * "all of them" while reading the repository was owner-scoped, so a caller wanting its own
     * records could fetch a page and filter it client-side and be right by accident.
     *
     * READING IS OPEN NOW — `backend/app/services/records.py::viewable_where` returns an empty
     * `where` with the comment "everything, for every signed-in account". Page one is therefore a
     * hundred rows of the WHOLE repository, ordered newest-first, and a caller's own records are on
     * it only if they happen to be among the hundred most recent things anyone recorded.
     *
     * MEASURED against http://localhost:8000 as designer@example.org (2026-08): 431 artisans and 854
     * media rows exist; page one of each holds 100 rows from 34 and 18 distinct creators
     * respectively, and NONE of them are this designer's — while `?createdBy=<me>` returns their
     * true total of 1 artisan and `?uploadedBy=<me>` their 1 media file. A client-side filter over
     * page one answers "you have recorded nothing" to someone who has, which reads as data loss on a
     * handset that is the only copy until it syncs.
     *
     * So the ownership test belongs in the query. Callers that want everyone's rows keep passing
     * null and are unaffected.
     */
    suspend fun artisans(workshopIds: List<String>? = null, createdBy: String? = null): List<ArtisanDto> =
        api.artisans(pageSize = 100, workshopIds = workshopIds.toQueryCsv(), createdBy = createdBy?.blankToNull()).items

    suspend fun crafts(createdBy: String? = null): List<CraftDto> =
        api.crafts(pageSize = 100, createdBy = createdBy?.blankToNull()).items

    suspend fun products(createdBy: String? = null): List<ProductDetailDto> =
        api.products(pageSize = 100, createdBy = createdBy?.blankToNull()).items

    /**
     * Products the server links to a given artisan. Covers datasets with >100 total products, and —
     * when the artisan's name is supplied — also returns legacy products that carry only the typed
     * artisan name with no FK link (the server OR-matches by name for FK-null rows). This is what
     * makes the process form's product dropdown reliable instead of intermittently empty.
     */
    suspend fun productsForArtisan(artisanId: String, artisanName: String? = null): List<ProductDetailDto> =
        api.products(pageSize = 100, artisanId = artisanId, artisanName = artisanName?.trim()?.ifBlank { null }).items

    suspend fun tools(createdBy: String? = null): List<ToolDetailDto> =
        api.tools(pageSize = 100, createdBy = createdBy?.blankToNull()).items

    /** Artisans a tool is assigned to (many-to-many). */
    suspend fun toolArtisans(toolId: String): List<ArtisanDto> = api.toolArtisans(toolId)

    /** Assign a tool to the given artisans (idempotent). Returns the full updated assignment list. */
    suspend fun assignToolArtisans(toolId: String, artisanIds: List<String>): List<ArtisanDto> =
        api.assignToolArtisans(toolId, ToolArtisanAssignRequest(artisanIds))

    suspend fun unassignToolArtisan(toolId: String, artisanId: String) = api.unassignToolArtisan(toolId, artisanId)

    suspend fun workshops(createdBy: String? = null, accessibleOnly: Boolean = false): List<WorkshopDetailDto> =
        api.workshops(
            pageSize = 100,
            createdBy = createdBy?.blankToNull(),
            // Sent only when asked for, so every existing read keeps making the request it made
            // before this parameter existed. See the KDoc on the API declaration.
            accessibleOnly = true.takeIf { accessibleOnly }
        ).items

    /**
     * Every workshop this user can SEE, ordered by date of occurrence, most recent first.
     *
     * READING IS OPEN IN THIS REPOSITORY AND THIS LIST IS THE WHOLE TABLE. `GET /workshops` applies
     * no visibility narrowing to a signed-in account (the server's `viewable_where` returns `{}` on
     * purpose), so this is the right list for a READ surface — the workshop scope filters, the admin
     * roster panel, the access-request screen, the browse and re-link pickers — and it is the WRONG
     * list for a control that offers a workshop to save a record into.
     *
     * Visible is NOT the same as submittable. The API separately 403s a submission into a workshop
     * that has assignments the user is not part of, and flags a submission made outside the
     * workshop's [startDate, endDate] window as needing admin approval — neither of which this list
     * filters out. `GET /workshops/{id}/submission-check` is the pre-flight for both, and
     * [workshopsIMaySubmitTo] is the list a picker has to use instead.
     */
    suspend fun workshopsByOccurrence(): List<WorkshopDetailDto> =
        workshops().sortedByDescending { it.occurrenceDate() }

    /**
     * The workshops this user may actually FILE A RECORD AGAINST, most recent occurrence first — the
     * single source of truth for every record form's workshop dropdown. The list order is what the
     * picker shows and its first entry is the one pre-selected when creating a new record.
     *
     * WHY THIS IS A SECOND FUNCTION AND NOT A FLAG ON THE ONE ABOVE: the two answer different
     * questions and the wrong answer is invisible in both directions. Handed to a picker, the wide
     * list offers rosters the designer is not on and the 403 lands after the record is typed. Handed
     * to a filter or a roster panel, the narrow list silently hides rows the account is entitled to
     * READ, which reads as an empty repository rather than as a scope. Two names, so a call site has
     * to say which of the two it means.
     *
     * The narrowing is the SERVER'S — `accessibleOnly=true`, resolved from `WorkshopAssignment` rows
     * this client never sees, at the same CONTRIBUTE level the save demands. There is deliberately no
     * cached, bundled or last-known fallback behind it: a stale access list is wrong in the
     * PERMISSIVE direction (a revoked grant still reads as a grant), and a picker is the one control
     * that must not offer what it cannot honour. A failure leaves the dropdown EMPTY — see
     * `rememberWorkshopPicker`, which is the same choice the web control makes.
     *
     * The pre-flight is still called on the selection, and is now what it should always have been:
     * not the access gate, but the answer to the two questions a scoped list cannot give — has this
     * workshop ENDED, and did access change since the list was fetched.
     */
    suspend fun workshopsIMaySubmitTo(): List<WorkshopDetailDto> =
        workshops(accessibleOnly = true).sortedByDescending { it.occurrenceDate() }

    /**
     * The same scoped list, WITH THE NUMBER THE SERVER HOLDS — the one fact [workshopsIMaySubmitTo]
     * throws away, and the one `workshopCapLine` cannot print a sentence without.
     *
     * -- WHY A SECOND FUNCTION RATHER THAN A WIDENED ONE ------------------------------------------
     *
     * Because the existing one is called from a screen this parcel may not edit, and a signature
     * change there would be a compile error in `MainActivity.kt` landing under another agent's hands
     * mid-edit. This is additive: every existing caller keeps the list it already reads, and the
     * record form adopts this one in the same commit that starts printing the sentence.
     *
     * -- WHAT THE NUMBER IS FOR, AND THE DEFECT ITS ABSENCE IS -----------------------------------
     *
     * [workshops] asks for `pageSize = 100` and takes `.items`. `GET /workshops` serves a table this
     * deployment counts at 196 rows (`components/data/cappedList.ts` counts the live tables:
     * MediaFile 2530, Artisan 749, Workshop 196, Craft 178), so the picker over it is ONE
     * SERVER-TRUNCATED PAGE and always has been — silently. A designer assigned to more workshops
     * than fit that page is offered a prefix and told nothing, which is R4's failure exactly:
     * *"196 workshops -> 100 fetched -> 80 drawn -> silence."*
     *
     * `total` is what makes the sentence honest rather than alarming. `workshopCapLine` prints
     * nothing at all while `shown == total`, so the ordinary designer on four workshops never reads
     * a word about a ceiling they cannot reach — and the sentence appears, with BOTH numbers, for
     * exactly the account it is about. Keeping `items` and discarding `total` is the shape
     * `DesignWorkshopPickerState.total` records eleven call sites in this app as having shipped.
     *
     * -- AND WHY IT DOES NOT SORT -----------------------------------------------------------------
     *
     * [workshopsIMaySubmitTo] sorts by occurrence on the device before handing the list on, and that
     * sort is now a SECOND opinion: `WorkshopOptions.fieldWorkshopOptions` owns the one order both
     * clients draw (open workshops first, then occurrence, then label, then id) and re-sorts whatever
     * it is given. Two sorts over one list is how a picker comes to disagree with the web about which
     * workshop is at the top, so this one hands the page over exactly as it was served and lets the
     * single owner decide. The page is still a prefix chosen by the SERVER's order, which is what
     * `total` is there to say out loud.
     */
    suspend fun workshopsIMaySubmitToPage(): PageResponse<WorkshopDetailDto> =
        api.workshops(pageSize = 100, accessibleOnly = true)

    /**
     * The pre-flight above: what submitting a record into [workshopId] would mean for this user.
     *
     * Returns null instead of throwing when the answer cannot be had — the endpoint is missing, the
     * phone is offline, or the server hiccupped. A record form MUST read null as "no answer" and let
     * the save proceed: a researcher standing in a field must never lose an entry to a failed
     * courtesy request. The endpoint itself never 403s, so a real refusal always arrives as
     * `canSubmit = false` inside a successful response.
     */
    suspend fun workshopSubmissionCheck(workshopId: String): WorkshopSubmissionCheckDto? =
        runCatching { api.workshopSubmissionCheck(workshopId) }.getOrNull()

    /**
     * Which records name NO workshop, and which workshop each one's own evidence points at. Admin-only.
     *
     * A pure READ and the preview [mapUnmappedRecords] acts on. It THROWS on failure rather than returning
     * null, unlike [workshopSubmissionCheck] above: that one is a courtesy check on the way to saving a
     * record a researcher is standing in a field to enter, so a failure must not cost them the entry. This
     * is a whole screen whose only content is the report — a silent null would render "nothing is unmapped",
     * which is the same wrong answer this feature exists to stop the app giving.
     */
    suspend fun unmappedRecords(): WorkshopMappingPlanDto = api.unmappedRecords()

    /**
     * File every unassigned record whose evidence names exactly one workshop, and return what was written.
     *
     * Sends no body: the server re-derives the plan, so this cannot ask for a particular row to be moved to
     * a particular workshop. Idempotent — every write carries "and the column is still empty" — so pressing
     * it twice changes nothing and a row somebody assigned by hand in between keeps that answer.
     */
    suspend fun mapUnmappedRecords(): WorkshopMappingPlanDto = api.mapUnmappedRecords()

    /**
     * File ONE record the ladder could not settle, under the workshop an admin named.
     *
     * THROWS, like [unmappedRecords] and for the same reason: this is a write somebody pressed a
     * button for, and a silent null would leave the screen showing the row still unfiled with no
     * account of why. The 409 the server answers when the row was filed since the report was read is
     * the one refusal a caller must render rather than swallow — it names the workshop it went to.
     */
    suspend fun fileOneUnmappedRecord(bucket: String, recordId: String, workshopId: String): WorkshopMappingPlanDto =
        api.fileOneUnmappedRecord(bucket, recordId, FileUnmappedRecordBody(workshopId = workshopId))

    /**
     * Delete ONE unfiled record permanently. Admin and master admin only, enforced on the server.
     *
     * THE ANSWER CARRIES `mediaKept` AND A CALLER MUST SAY IT. Every `MediaFile` relation is
     * `onDelete: SetNull`, so this detaches a record's attachments rather than removing them; a
     * screen that reported only "deleted" would be leaving out the half an admin has to act on.
     */
    suspend fun discardUnmappedRecord(bucket: String, recordId: String): DiscardUnmappedRecordDto =
        api.discardUnmappedRecord(bucket, recordId)

    suspend fun createArtisan(body: ArtisanCreateRequest): ArtisanDto = api.createArtisan(body)

    suspend fun artisan(id: String): ArtisanDetailDto = api.artisan(id)

    /**
     * Is this Aadhaar number already on an artisan? The form's pre-flight duplicate check, run while
     * the researcher is still typing so a duplicate surfaces before the whole form is filled in rather
     * than as a 409 on save. [number] may be typed with spacing; the API normalises it.
     */
    suspend fun lookupArtisanByAadhaar(number: String): AadhaarLookupDto =
        api.lookupArtisanByAadhaar(number.trim())

    suspend fun updateArtisan(
        id: String,
        body: ArtisanCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
    ): ArtisanDetailDto = api.updateArtisan(id, artisanPatchBody(body, clearedLinks))

    /**
     * A PATCH BODY THAT CAN SAY "NOTHING" — the general form of [artisanPatchBody], for the two
     * workshop links that every record type carries.
     *
     * ── THE LIE THIS ENDS, WHICH WAS WRITTEN DOWN A YEAR BEFORE IT WAS FIXED ──────────────────
     *
     * `DesignWorkshopPicker` draws a "None" row and has never been able to mean it, and its own KDoc
     * says so: *"a designer clearing the box, pressing Save, being told it saved, and finding the
     * workshop still there — which is the 'exit zero is not evidence' class of defect wearing a
     * form."* The mechanism is [ApiClient.json]'s `explicitNulls = false` meeting the API's
     * `model_dump(exclude_unset=True)`: a null property is dropped, an absent key means "leave the
     * stored value alone", and the un-filing returns 200 having changed nothing.
     *
     * The server has been ready the whole time. `designWorkshopId` and `workshopId` are both in
     * `services/records.CLEARABLE_KEYS`, added with the column and with the failure spelled out:
     * *"without this entry `{"designWorkshopId": null}` would be stripped as an unset optional, the
     * save would return 200, the form would show it unfiled, and the old link would survive in the
     * database."* Only the handset could not spell it. BOTH columns are fixed at once, because
     * `WorkshopPickerState.value()` and `DesignWorkshopPickerState.value()` are the same three lines
     * with the same consequence, and a form with one box that clears and one that pretends to is
     * worse than a form with two that pretend.
     *
     * ── WHY SENDING THESE NULLS IS SAFE, WHICH IS THE ONLY DANGEROUS PART ─────────────────────
     *
     * An explicit null DESTROYS a link, so [artisanPatchBody]'s two conditions have to hold here too
     * and both do:
     *
     *   1. THE BOX IS SEEDED FROM THE RECORD. Both pickers take the stored id as `initialId` and
     *      neither clears it when its list fails to load — `DesignWorkshopPickerState(initialId)`,
     *      `WorkshopPickerState(repository, initialId)` — so an empty box on an edit means the person
     *      editing this record emptied it, never "this screen never knew". A record filed under a
     *      workshop that is off the end of the picker's page keeps its id in state and sends it back.
     *   2. THE COLUMN IS ON THE SERVER'S OWN CLEARABLE LIST, so a key sent for a model that does not
     *      have it would be dropped rather than acted on — and it is not sent at all, because
     *      [declaredKeys] asks the request class what it declares first. `APIModel` is
     *      `extra="forbid"`: posting `workshopId` to a route whose body has no such field is a 422
     *      carrying `extra_forbidden`, which this queue would then read as a disagreement between
     *      BUILDS and re-attempt once per app run for ever.
     *
     * ── AND WHY THE REPLAY PASSES A DIFFERENT SET ─────────────────────────────────────────────
     *
     * [clearable] is the caller's, not a constant, because the two callers know different things.
     * A form on screen was built by THIS build, so an empty box is evidence: the online path passes
     * both columns. A queued correction may have been written a fortnight ago by a build that had no
     * design-workshop picker at all, and its silence is not evidence of anything —
     * `PendingEntry.clearedLinkKeys` is empty for every such entry, so the replay omits the key and
     * the stored link stands. That is the compatibility rule for this whole change, in one argument.
     */
    private fun <T> patchBodyWithClearances(
        serializer: KSerializer<T>,
        body: T,
        clearable: Set<String>,
    ): JsonObject {
        val encoded = ApiClient.json.encodeToJsonElement(serializer, body).jsonObject
        val declared = declaredKeys(serializer)
        val out = encoded.toMutableMap()
        for (column in clearable) if (column in declared && column !in out) out[column] = JsonNull
        return JsonObject(out)
    }

    /**
     * The property names a request class actually declares, asked of the serializer rather than
     * remembered in a list here.
     *
     * ASKED, AND NOT LISTED, because the list would be the thing that rots. `APIModel` is
     * `extra="forbid"` on the server, so posting `workshopId` to a route whose body has no such field
     * is a 422 carrying `extra_forbidden` — which this queue reads as a disagreement between BUILDS
     * and re-attempts once per app run, for ever, on a prepaid connection. A hand-kept list of which
     * of the six request classes carries which link column is one refactor away from producing
     * exactly that, silently, on one record type.
     *
     * THE OPT-IN IS SCOPED TO THIS FUNCTION and is the whole of the experimental surface used here.
     * `SerialDescriptor.elementsCount` and `getElementName` are `@ExperimentalSerializationApi` in
     * this version; what they answer — the declared property names of a `@Serializable` class — is
     * the most stable thing about it, and the alternative is the rotting list above.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun declaredKeys(serializer: KSerializer<*>): Set<String> =
        (0 until serializer.descriptor.elementsCount).mapTo(mutableSetOf()) {
            serializer.descriptor.getElementName(it)
        }

    /**
     * The artisan PATCH body: the form's request, plus an EXPLICIT `null` for every column the server
     * allows a client to clear and this form has left empty.
     *
     * ── WHY THIS EXISTS AT ALL ──────────────────────────────────────────────────────────────────
     *
     * `ApiClient.json` is built with `explicitNulls = false` — correct for a create, where a null is
     * a box nobody filled in — and the artisan update is a PATCH read with
     * `model_dump(exclude_unset=True)`, where an ABSENT KEY MEANS "LEAVE THIS COLUMN ALONE". The two
     * together made every clearable column unclearable from the handset: emptying "Practising since"
     * (or the birthday, the phone, the experience number, the address, the do's and don'ts) put a
     * null in the request object, the encoder dropped it, the server left the stored value standing,
     * and the form went back to the record showing the value it had just been told to remove.
     * `FieldDateField(clearable = true)` was drawing a clear button that saved nothing.
     *
     * The web client has always had this right, by an accident of HTML rather than by insight: a
     * hidden input plus `|| null` means its payload carries the key with a null in it. This is that
     * behaviour, ported deliberately.
     *
     * ── WHY IT IS SAFE TO SEND THESE NULLS, WHICH IS THE ONLY DANGEROUS PART ────────────────────
     *
     * An explicit null DESTROYS data, so sending one for a column the form does not own would be a
     * worse bug than the one being fixed — the retraction working in reverse. Two properties make it
     * safe here and BOTH have to keep holding:
     *
     *   1. Every column below is seeded from the record being edited when the form opens
     *      (`editing?.phone`, `editing?.craftStartDate?.take(10)`, and so on for all eleven), so an
     *      empty box means "the person editing this record emptied it", never "this screen never
     *      knew".
     *   2. Every column below is in the server's own `_CLEARABLE_COLUMNS`
     *      (`api/routes/artisans`), which is the list of nullable scalars that route will accept a
     *      null for. A column NOT on that list is dropped by `clean_data` regardless of what is sent,
     *      so adding one here would be silently ineffective rather than destructive — but the list is
     *      still kept in step by hand, and a name added on the server does not arrive here for free.
     *
     * DELIBERATELY NOT THE WHOLE SET. `name` and `place` are not nullable, and `aadhaarNumber` /
     * `pehchanCardNumber` are handled by the form itself: the Aadhaar is sent as a trimmed string
     * (the API normalises "" to null, which is how a mistyped number is retracted) and the Pehchan
     * pair is reconciled server-side from `pehchanCardAvailable`. Sending nulls for those would
     * fight rules that already work.
     *
     * The encoding goes through `ApiClient.json` — the very encoder Retrofit's converter uses — so
     * everything except the added nulls is byte-identical to the body the typed call would have sent.
     */
    private fun artisanPatchBody(
        body: ArtisanCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
    ): JsonObject = patchBodyWithClearances(
        ArtisanCreateRequest.serializer(),
        body,
        // The eleven scalar columns are unconditional for the reason above: every one of them is
        // seeded from the record when the form opens. The two LINK columns are the caller's, because
        // a queued correction written by an older build never carried them — see
        // [patchBodyWithClearances].
        ARTISAN_CLEARABLE_COLUMNS.toSet() + clearedLinks,
    )

    suspend fun artisanQuestionnaire(id: String): ArtisanQuestionnaireDto = api.artisanQuestionnaire(id)

    suspend fun media(): List<MediaFileDto> = api.media(pageSize = 20).items

    /**
     * A broader media list for the View Data "Miscellaneous Media" browser (most recent first), and
     * — with [uploadedBy] — the Media half of My Activity.
     *
     * [uploadedBy] is media's owner column and it is NOT `createdBy`: MediaFile owns its rows through
     * `uploadedById` while every other record uses `createdById`, and the query key follows the column
     * on both sides of the wire. Spelling this one `createdBy` would be ignored by the API and hand
     * back the whole repository, which is the exact failure asking the server is meant to prevent.
     *
     * Asked for by name rather than sifted out of the answer, for the reason set out on [artisans]:
     * reading media is open, so page one is the newest hundred rows of the whole archive and a
     * client-side filter silently reports that a designer has uploaded nothing.
     *
     * THE FIVE FILTERS ARE THE SAME ARGUMENT, ONE STEP FURTHER. `GET /media` folds [search] into the
     * WHERE across `originalFilename`, `caption` and `mimeType`, and [mediaType] / [statusFilter] /
     * [dateFrom] / [dateTo] into it beside them, so a term reaches PAST the hundred-row page rather
     * than sifting it. Every one of them is defaulted, so a caller that wants the newest hundred
     * writes exactly what it wrote before this line existed.
     *
     * EVERY ARGUMENT GOES THROUGH [blankToNull], AND ONE OF THEM 422s WITHOUT IT. Retrofit omits a
     * null `@Query` and SENDS an empty one, so a screen handing this an untouched search box would
     * put `?dateFrom=` on the wire — and `dateFrom` is declared `datetime | None` on the route, so
     * FastAPI fails to parse "" and answers 422 for a request the designer reads as a filter that
     * simply found nothing. The enum pair is one `if` away from the same fate: `""` is falsy, so
     * `list_media` skips it today and `enum_filter_or_422` is never reached, but the difference
     * between "not filtered" and "filtered by nothing" should not rest on a truthiness test in
     * another language. Send the key or do not send it.
     */
    suspend fun mediaList(
        uploadedBy: String? = null,
        search: String? = null,
        mediaType: String? = null,
        statusFilter: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
    ): List<MediaFileDto> = api.media(
        pageSize = 100,
        uploadedBy = uploadedBy?.blankToNull(),
        search = search?.blankToNull(),
        mediaType = mediaType?.blankToNull(),
        statusFilter = statusFilter?.blankToNull(),
        dateFrom = dateFrom?.blankToNull(),
        dateTo = dateTo?.blankToNull(),
    ).items

    /** One media file by id, for the View Data media detail. */
    suspend fun mediaItem(id: String): MediaFileDto = api.getMedia(id)

    /** Delete one saved media file (its DB row + S3 object). Backend allows the uploader or an admin. */
    suspend fun deleteMedia(id: String) = api.deleteMedia(id)

    // Admin-only deletes (backend enforces is_admin; 403 otherwise).
    suspend fun deleteArtisan(id: String) = api.deleteArtisan(id)
    suspend fun deleteCraft(id: String) = api.deleteCraft(id)
    suspend fun deleteProduct(id: String) = api.deleteProduct(id)
    suspend fun deleteTool(id: String) = api.deleteTool(id)
    suspend fun deleteWorkshop(id: String) = api.deleteWorkshop(id)
    suspend fun deleteProcess(id: String) = api.deleteProcess(id)
    suspend fun deleteInterview(id: String) = api.deleteInterview(id)

    /**
     * Result of a full-dataset download: where it was saved and how many files succeeded.
     *
     * [truncated] is the server's own flag carried through unchanged. It is NOT derivable from
     * [saved]/[total]: those count the manifest's files, and a capped manifest is internally
     * consistent — every file it lists is fetched, so the counts agree while the archive is short.
     * The only place the shortfall is known is the response, so it has to be carried to the UI.
     */
    data class DatasetDownloadResult(
        val displayLocation: String,
        val saved: Int,
        val total: Int,
        val failed: Int,
        val truncated: Boolean = false,
        /**
         * Media rows `/export/dataset` could not address at all. Always 0 for a folder download —
         * `/data/manifest` has no such concept — and 0 against a server that predates the field.
         */
        val skippedMedia: Int = 0
    )

    /**
     * Read a download manifest without ever holding it whole, and hand each entry to [onEntry].
     *
     * WHY THE MANIFEST IS SPOOLED TO DISK FIRST, WHICH IS THE PART THAT LOOKS REDUNDANT.
     *
     * `docs/SCALABILITY.md` specifies zipping the manifest line-by-line straight off the socket.
     * Implemented literally that is wrong here, and it would fail in the field rather than on a
     * desk. The manifest response is served by the API host while every media object is fetched
     * from S3 on [storageClient]; consuming the manifest lazily means its socket stays open for the
     * whole download and sits IDLE for as long as each media transfer takes. `ApiClient` sets a
     * 60-second read timeout (deliberately, for mobile data), so the first media file slower than a
     * minute would kill the manifest connection mid-download and take the whole archive with it — on
     * exactly the slow rural connection this app exists for. Spooling drains the manifest at full
     * speed, closes its connection, and leaves a file we can re-read at whatever pace S3 answers.
     *
     * `copyTo` streams in 8 KB chunks, so the spool costs one buffer, not one manifest — the peak
     * heap for the whole operation becomes the longest single LINE (one entry; at worst one
     * transcript body), against the ~48 MB single contiguous allocation the typed call asks for. See
     * `data/ManifestStream.kt` for the OutOfMemoryError this replaces.
     *
     * THE FALLBACK IS NOT DEAD CODE. `?stream=1` is a query parameter, and a server that predates it
     * ignores it and answers the ordinary JSON object. That response must still produce an archive —
     * a client that only works against a new server is a client that breaks every installed handset
     * on the day of a rollback. [buffered] is that path, and it is the OLD behaviour exactly: one
     * whole-manifest allocation, which is fine on the small repositories that never hit this defect
     * and is at least an attempt on the large ones.
     */
    private suspend fun <T> readManifest(
        streamed: suspend () -> retrofit2.Response<okhttp3.ResponseBody>,
        buffered: suspend () -> ManifestFallback<T>,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        spool: File,
        onEntry: suspend (entry: T, announcedTotal: Int) -> Unit
    ): ManifestOutcome {
        val response = streamed()
        val body = response.body()
        val ndjson = response.isSuccessful && body != null &&
            ManifestStream.isNdjson(response.headers()["Content-Type"])
        if (!ndjson) {
            // Not the streamed format. Close what did arrive — an unclosed ResponseBody leaks the
            // connection out of OkHttp's pool — and ask again for the shape this server does speak.
            runCatching { body?.close() }
            val fallback = buffered()
            for (entry in fallback.files) onEntry(entry, fallback.total)
            return ManifestOutcome(
                total = fallback.total,
                truncated = fallback.truncated,
                unreadable = 0,
                skippedMedia = fallback.skippedMedia
            )
        }
        // Read BEFORE the body, which is the whole point of putting these in headers: the caller
        // needs the total to report progress from the first entry, not after the last one.
        val total = ManifestStream.count(response.headers()[ManifestStream.TOTAL_HEADER])
        val truncated = ManifestStream.flag(response.headers()[ManifestStream.TRUNCATED_HEADER])
        val skipped = maxOf(0, ManifestStream.count(response.headers()[ManifestStream.SKIPPED_HEADER]))
        try {
            body!!.byteStream().use { input ->
                FileOutputStream(spool).use { out -> input.copyTo(out) }
            }
            var unreadable = 0
            spool.reader(Charsets.UTF_8).use { reader ->
                val lines = ManifestLines(reader, ApiClient.json, deserializer)
                // A plain `for`, not `forEach`: the body suspends (a folder download asks the API to
                // transcode each convertToMp4 audio row), and only the loop keeps us in the caller's
                // coroutine where it is allowed to.
                for (entry in lines.entries()) onEntry(entry, total)
                unreadable = lines.unreadable
            }
            return ManifestOutcome(
                total = total,
                truncated = truncated,
                unreadable = unreadable,
                skippedMedia = skipped
            )
        } finally {
            // cacheDir is not guaranteed to be swept, and a 48 MB spool left behind after every
            // download is the kind of thing that quietly fills a field handset.
            spool.delete()
        }
    }

    /**
     * What the OLD, whole-object manifest response carried, normalised so [readManifest] does not
     * have to know which of the two DTOs it is looking at.
     *
     * A named type rather than a `Triple`/`Quadruple` because the fourth member is `skippedMedia`,
     * which only `/export/dataset` has — positional tuples are how "truncated" and "skipped" get
     * swapped by somebody adding a field, and the two mean different things to the researcher
     * reading the message at the end.
     */
    private data class ManifestFallback<T>(
        val files: List<T>,
        val total: Int,
        val truncated: Boolean,
        val skippedMedia: Int = 0
    )

    /**
     * How many files the download was SUPPOSED to contain — the server's count, not ours.
     *
     * The server's is the honest denominator and the two can legitimately differ. A line that would
     * not decode was never handed to the zip loop, so it is in the server's count and not in [seen];
     * and if a stream ends early the count is higher than anything we saw. Reporting `seen` as the
     * total in either case would let the archive present itself as complete while being short —
     * which is the failure `truncated` exists to stop, reintroduced by another door. Falls back to
     * what arrived only when there is no header to believe (-1: a server that predates it).
     */
    private fun manifestTotal(outcome: ManifestOutcome, seen: Int): Int =
        if (outcome.total >= 0) maxOf(outcome.total, seen) else seen + outcome.unreadable

    /**
     * Pull the full dataset manifest, then download every media object straight from S3 and zip the
     * whole directory tree to the device's Downloads folder. [onProgress] reports (done, total) as each
     * entry is written so the UI can show real progress. Individual file failures are skipped, not fatal.
     *
     * The manifest is read entry-by-entry ([readManifest]) rather than deserialised whole, so a large
     * repository no longer has to fit in one allocation. `total` therefore comes from the server's
     * `X-Dataset-Total` header instead of `files.size`; while it is unknown (-1, an old server that
     * sent no header) progress reports the running count as the total, which reads as "N of N so
     * far" rather than as a wrong fraction.
     */
    suspend fun downloadDataset(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit
    ): DatasetDownloadResult = withContext(Dispatchers.IO) {
        val stamp = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val zipName = "DesignWorkshop_dataset_$stamp.zip"
        val tmp = File(context.cacheDir, zipName)
        var failed = 0
        var written = 0
        val outcome = ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
            readManifest(
                streamed = { api.datasetManifestStream() },
                buffered = {
                    val manifest = api.datasetManifest()
                    ManifestFallback(
                        files = manifest.files,
                        total = manifest.files.size,
                        truncated = manifest.truncated,
                        skippedMedia = manifest.skippedMedia
                    )
                },
                deserializer = DatasetFileDto.serializer(),
                spool = File(context.cacheDir, "$zipName.manifest")
            ) { f, announced ->
                runCatching {
                    zip.putNextEntry(ZipEntry(f.path))
                    when {
                        f.content != null -> zip.write(f.content.toByteArray(Charsets.UTF_8))
                        f.url != null -> writeObject(f.url, zip)
                    }
                    zip.closeEntry()
                }.onFailure {
                    failed++
                    runCatching { zip.closeEntry() }
                }
                written++
                onProgress(written, if (announced >= 0) announced else written)
            }
        }
        val location = persistFileToDownloads(context, tmp, zipName, "application/zip")
        tmp.delete()
        val total = manifestTotal(outcome, written)
        DatasetDownloadResult(
            displayLocation = location,
            saved = written - failed,
            total = total,
            failed = total - (written - failed),
            truncated = outcome.truncated,
            skippedMedia = outcome.skippedMedia
        )
    }

    /**
     * Download the styled .xlsx relational report straight into the public Downloads folder (same
     * MediaStore path the dataset zip uses) and return where it was saved.
     *
     * [path] scopes the report to one subtree of the data browser; the default, "", is the whole
     * dataset — which is what every caller before the data browser existed meant.
     */
    suspend fun downloadReport(context: Context, path: String = ""): String = withContext(Dispatchers.IO) {
        val response = api.dataReport(format = "xlsx", path = path)
        if (!response.isSuccessful) throw IllegalStateException("Report request failed (HTTP ${response.code()})")
        val body = response.body() ?: throw IllegalStateException("The report response was empty")
        val stamp = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val name = "DesignWorkshop_report_$stamp.xlsx"
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val location = persistFileToDownloads(context, tmp, name, XLSX_MIME)
        tmp.delete()
        location
    }

    /**
     * Zip ONE folder of the data browser into the device's Downloads folder.
     *
     * The same shape as [downloadDataset], but scoped to [path] and filterable with [include] (a CSV
     * of `text,images,videos,audios,transcripts,documents,other`; null means everything). Generated
     * text entries are written from their inline `content` — no request at all. Audio marked
     * `convertToMp4` is fetched from the API as an .mp4 and falls back to the original object when
     * the server cannot convert it, exactly as the web does. A file that fails is counted and
     * skipped, never fatal.
     *
     * [folderName] names the .zip; the requested folder's own name is the natural choice.
     *
     * The manifest is read entry-by-entry ([readManifest]) for the same reason [downloadDataset]
     * does it, and this endpoint is if anything the worse of the two: an unfiltered folder manifest
     * inlines the FULL transcript of every audio row in the subtree, so its byte size is bounded by
     * nothing at all.
     */
    suspend fun downloadDataFolder(
        context: Context,
        path: String,
        include: String? = null,
        folderName: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): DatasetDownloadResult = withContext(Dispatchers.IO) {
        val stamp = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val stem = (folderName ?: path.substringAfterLast('/')).blankToNull()
            ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")?.take(60)
            ?: "dataset"
        val zipName = "DesignWorkshop_${stem}_$stamp.zip"
        val tmp = File(context.cacheDir, zipName)
        var failed = 0
        var written = 0
        val outcome = ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
            readManifest(
                streamed = { api.dataManifestStream(path, include?.blankToNull()) },
                buffered = {
                    val manifest = api.dataManifest(path, include?.blankToNull())
                    ManifestFallback(
                        files = manifest.files,
                        total = manifest.files.size,
                        truncated = manifest.truncated
                    )
                },
                deserializer = DataManifestFileDto.serializer(),
                spool = File(context.cacheDir, "$zipName.manifest")
            ) { f, announced ->
                runCatching {
                    zip.putNextEntry(ZipEntry(f.path))
                    when {
                        f.content != null -> zip.write(f.content.toByteArray(Charsets.UTF_8))
                        f.convertToMp4 && f.mediaId != null ->
                            if (!writeConvertedMedia(f.mediaId, zip)) writeObject(f.url, zip)
                        else -> writeObject(f.url, zip)
                    }
                    zip.closeEntry()
                }.onFailure {
                    failed++
                    runCatching { zip.closeEntry() }
                }
                written++
                onProgress(written, if (announced >= 0) announced else written)
            }
        }
        val location = persistFileToDownloads(context, tmp, zipName, "application/zip")
        tmp.delete()
        val total = manifestTotal(outcome, written)
        DatasetDownloadResult(
            displayLocation = location,
            saved = written - failed,
            total = total,
            failed = total - (written - failed),
            // The walk stops at MAX_MANIFEST_FILES / the depth ceiling and the server reports that it
            // did. This result used to drop the flag, so "Archive saved — 20,000 files" was printed
            // over a subtree that has more — the one message here that can send somebody away
            // believing they have everything.
            truncated = outcome.truncated
        )
    }

    /** Stream the API's .mp4 conversion of one audio row into [sink]. False = let the caller fall back. */
    private suspend fun writeConvertedMedia(mediaId: String, sink: java.io.OutputStream): Boolean =
        runCatching {
            val response = api.downloadDataMedia(mediaId, "mp4")
            val body = response.body()
            if (!response.isSuccessful || body == null) return@runCatching false
            body.byteStream().use { it.copyTo(sink) }
            true
        }.getOrDefault(false)

    /** Stream a stored object straight from its (presigned) URL into [sink]. */
    private fun writeObject(url: String?, sink: java.io.OutputStream) {
        if (url.isNullOrBlank()) return
        storageClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.byteStream()?.copyTo(sink)
        }
    }

    /**
     * Save ONE media file from the data browser into Downloads and return where it landed. Audio
     * arrives as an .mp4 (AAC) the server transcodes on the fly, which is what makes a field
     * recording playable on any device; pass [format] = "original" to bypass that.
     */
    suspend fun downloadDataMedia(
        context: Context,
        mediaId: String,
        filename: String,
        format: String? = null
    ): String = withContext(Dispatchers.IO) {
        val response = api.downloadDataMedia(mediaId, format?.blankToNull())
        if (!response.isSuccessful) throw IllegalStateException("Download failed (HTTP ${response.code()})")
        val body = response.body() ?: throw IllegalStateException("The download response was empty")
        val name = filename.blankToNull()?.replace(Regex("[^A-Za-z0-9._-]+"), "_") ?: mediaId
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val mime = response.headers()["Content-Type"]?.substringBefore(';')?.trim().blankToNull()
            ?: "application/octet-stream"
        val location = persistFileToDownloads(context, tmp, name, mime)
        tmp.delete()
        location
    }

    /**
     * Copy a built file into the public Downloads collection (MediaStore on Q+, file path below).
     *
     * INTERNAL rather than private because the offline report exporter
     * ([com.designprototype.workshop.report.ReportExport]) publishes its .docx and .pdf through this
     * exact function. It is deliberately not reimplemented there: everything below — the IS_PENDING
     * handshake, the pre-Q permission check, the filesDir fallback and the path
     * [shareUriForSavedFile] knows how to recognise — was learned from field failures, and a second
     * copy of it would be a second copy to get wrong.
     */
    internal fun persistFileToDownloads(context: Context, source: File, name: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the download entry")
            resolver.openOutputStream(uri).use { out -> source.inputStream().use { it.copyTo(out!!) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            // ── THE NAME MEDIAPROVIDER ACTUALLY USED, WHICH IS NOT ALWAYS THE ONE WE ASKED FOR ────
            //
            // A colliding DISPLAY_NAME is silently uniquified to `name (1).ext`. This function used to
            // return the REQUESTED name, and two things downstream then lied in the same breath:
            // `shareUriForSavedFile` → `mediaStoreDownloadUri` matches `DISPLAY_NAME = ?` exactly, so
            // it resolved the OLDER row and every Share control in the app handed over the PREVIOUS
            // export; and the location shown to the designer named a file that is not on disk.
            //
            // It is worst on the questionnaire handoff, because that one has a fingerprint. The
            // filename cannot distinguish two editions on its own — the server bumps a
            // questionnaire's `version` only on supersede and retire, never when a question is added
            // (`backend/app/api/routes/questionnaire_forms.py`) — so "export, add three questions,
            // export again" produced the identical name. The new bytes landed as `… (1).dpwq`, "Send
            // it" shared the old file, and the recipient's scan of a code computed from the NEW
            // bundle printed "this is NOT the file that code was made for. Ask them to send it
            // again" — the false alarm `questionnaireHandoffCode`'s KDoc calls worse than not
            // checking, raised over a transfer fault that never happened.
            //
            // Read back rather than guessed: the uniquifying rule is MediaProvider's, it has changed
            // between releases, and a second implementation of it here would be a second thing to be
            // wrong. Falls back to the requested name if the query comes back empty, which is no
            // worse than what this returned unconditionally before.
            val storedName = runCatching {
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (!cursor.moveToFirst()) null
                        else cursor.getString(0)?.takeIf { it.isNotBlank() }
                    }
            }.getOrNull() ?: name
            return "Downloads/$storedName"
        }
        // ── Pre-Q (API 26/27/28) ─────────────────────────────────────────────────────────────────
        // There is no MediaStore.Downloads collection below Q, so the public Downloads folder is a
        // raw external-storage path — and writing to it is a dangerous permission.
        //
        // THIS USED TO THROW. The branch below dropped straight into
        // getExternalStoragePublicDirectory and copied, while the manifest declared no
        // WRITE_EXTERNAL_STORAGE at all. minSdk is 26, so on every Android 8 and 9 handset in the
        // field fleet "Save report", "Download dataset" and "Save this file" raised SecurityException
        // — after the export had already been built and streamed, so the designer waited out the
        // whole progress bar to be told nothing useful. It never surfaced in testing because
        // development phones are Q+, where the MediaStore branch above returns first.
        //
        // WHAT WAS CHOSEN, AND WHY BOTH HALVES. The permission is now declared with
        // maxSdkVersion="28" (see AndroidManifest.xml) and requested at launch with the rest
        // (requiredAndroidPermissions in MainActivity.kt) — that is the fix for the common case, and
        // it keeps the file where the designer expects it, in Downloads, findable by any file manager
        // or WhatsApp attach dialog. But a declared permission is not a granted one: it can be
        // refused, or revoked later from Settings, and this function runs on Dispatchers.IO with no
        // Activity, so it cannot raise a dialog and wait. So the grant is CHECKED rather than
        // assumed, and a refusal falls back to app-private storage under filesDir instead of
        // throwing. filesDir specifically — not getExternalFilesDir — because res/xml/file_paths.xml
        // already publishes `files-path .`, so the fallback copy is immediately shareable through
        // the existing FileProvider authority. A report the designer can share is worth more than a
        // report in the "right" folder, and infinitely more than an exception.
        val hasLegacyWrite = context.checkSelfPermission(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasLegacyWrite) {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists()) downloads.mkdirs()
            val dest = File(downloads, name)
            source.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }
        val fallback = File(context.filesDir, "downloads").apply { mkdirs() }
        val dest = File(fallback, name)
        source.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    /**
     * A share-sheet Uri for a file [persistFileToDownloads] put in the app-private fallback.
     *
     * Only meaningful for the pre-Q refusal path above: a file in the public Downloads folder is
     * already reachable by every app, whereas one under filesDir is reachable by none until it is
     * granted through the FileProvider. Returns null for anything outside filesDir, so a caller
     * cannot accidentally hand out a grant for a path the provider does not cover — which throws
     * IllegalArgumentException from FileProvider itself, at the moment the designer taps Share.
     */
    fun shareUriForSavedFile(context: Context, absolutePath: String): Uri? {
        val file = File(absolutePath)
        if (file.absolutePath.startsWith(context.filesDir.absolutePath)) {
            return runCatching {
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        }
        return mediaStoreDownloadUri(context, absolutePath)
    }

    /**
     * The MediaStore Uri for a file [persistFileToDownloads] wrote through the Q+ branch.
     *
     * ── THE DEFECT THIS CLOSES, WHICH MADE EVERY SHARE BUTTON IN THE APP INVISIBLE ────────────
     *
     * [persistFileToDownloads] returns the STRING `"Downloads/<name>"` on Q and above, and
     * [shareUriForSavedFile] used to answer null for anything outside `filesDir`. Every Share control
     * in this app is gated on that Uri being non-null — `ui/RecordCodeCard.kt`,
     * `ui/designworkshop/WorkshopCodesScreen.kt`, `ui/designworkshop/ReportScreen.kt` — so on every
     * Android 10+ handset the button DID NOT RENDER AT ALL, and the designer was shown "open it from
     * the Downloads folder" instead. The in-app Share button existed only on Android 8 and 9 devices
     * that had REFUSED `WRITE_EXTERNAL_STORAGE`: the narrowest possible slice of the fleet, and the
     * exact inverse of where it is needed.
     *
     * The comments defending that behaviour ("every app can already open it from there") are right
     * about OPENING and wrong about SHARING. A designer who wants to hand a file to the colleague
     * standing beside them was being sent out to a file manager to find it — and "nearby share, or
     * bluetooth" is reached through the share sheet, so this one null was the difference between the
     * transport the owner asked for and a button that is not there.
     *
     * The insert Uri is thrown away inside the MediaStore branch above, and it is re-derived here
     * rather than plumbed out of it, deliberately: that function's return type is a display string
     * used by five screens and by `report/ReportExport.kt`, and widening it would be a change to all
     * of them for a value only this function wants.
     *
     * `_display_name` and nothing else is matched on. `RELATIVE_PATH` is not queried, because
     * `MediaStore.Downloads.EXTERNAL_CONTENT_URI` is already scoped to Downloads and — on Q — the app
     * can only see rows it wrote itself, which is exactly the set we want. The newest matching row
     * wins, so a second export of the same name shares the copy that was just written.
     */
    private fun mediaStoreDownloadUri(context: Context, location: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = location.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(name),
                "${MediaStore.Downloads._ID} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }.getOrNull()
    }

    /** All media attached to a specific record, used by the View Data screen (with transcripts). */
    suspend fun mediaForRecord(linkedRecordType: String, linkedRecordId: String): List<MediaFileDto> =
        api.media(pageSize = 100, linkedRecordType = linkedRecordType, linkedRecordId = linkedRecordId).items

    suspend fun processes(createdBy: String? = null): List<ProcessDetailDto> =
        api.processes(pageSize = 100, createdBy = createdBy?.blankToNull()).items

    suspend fun process(id: String): ProcessDetailDto = api.process(id)

    suspend fun createProcess(body: ProcessCreateRequest): ProcessDetailDto = api.createProcess(body)

    suspend fun updateProcess(
        id: String,
        body: ProcessCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
    ): ProcessDetailDto =
        api.updateProcess(id, patchBodyWithClearances(ProcessCreateRequest.serializer(), body, clearedLinks))

    suspend fun createCraft(body: CraftCreateRequest): CreatedRecordDto = api.createCraft(body)

    suspend fun craft(id: String): CraftDto = api.craft(id)

    suspend fun updateCraft(
        id: String,
        body: CraftCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
    ): CraftDto =
        api.updateCraft(id, patchBodyWithClearances(CraftCreateRequest.serializer(), body, clearedLinks))

    suspend fun createWorkshop(body: WorkshopCreateRequest): CreatedRecordDto = api.createWorkshop(body)

    suspend fun workshop(id: String): WorkshopDetailDto = api.workshop(id)

    suspend fun updateWorkshop(
        id: String,
        body: WorkshopCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
    ): WorkshopDetailDto =
        api.updateWorkshop(id, patchBodyWithClearances(WorkshopCreateRequest.serializer(), body, clearedLinks))

    suspend fun createProduct(body: ProductCreateRequest): CreatedRecordDto = api.createProduct(body)

    suspend fun product(id: String): ProductDetailDto = api.product(id)

    suspend fun updateProduct(
        id: String,
        body: ProductCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
    ): ProductDetailDto =
        api.updateProduct(id, patchBodyWithClearances(ProductCreateRequest.serializer(), body, clearedLinks))

    suspend fun createTool(body: ToolCreateRequest): CreatedRecordDto = api.createTool(body)

    suspend fun tool(id: String): ToolDetailDto = api.tool(id)

    suspend fun updateTool(
        id: String,
        body: ToolCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
    ): ToolDetailDto =
        api.updateTool(id, patchBodyWithClearances(ToolCreateRequest.serializer(), body, clearedLinks))

    suspend fun questionnaireQuestions(): List<QuestionnaireQuestionDto> = api.questionnaireQuestions()

    suspend fun questionnaireSections(): List<QuestionnaireSectionDto> = api.questionnaireSections()

    suspend fun createQuestionnaireSection(body: QuestionnaireSectionCreateRequest): QuestionnaireSectionDto =
        api.createQuestionnaireSection(body)

    suspend fun updateQuestionnaireSection(id: String, body: QuestionnaireSectionUpdateRequest): QuestionnaireSectionDto =
        api.updateQuestionnaireSection(id, body)

    suspend fun deleteQuestionnaireSection(id: String) {
        api.deleteQuestionnaireSection(id)
    }

    suspend fun reorderQuestionnaireSections(sectionIds: List<String>): List<QuestionnaireSectionDto> =
        api.reorderQuestionnaireSections(QuestionnaireSectionReorderRequest(sectionIds))

    suspend fun createQuestionnaireQuestion(body: QuestionnaireQuestionCreateRequest): QuestionnaireQuestionDto =
        api.createQuestionnaireQuestion(body)

    suspend fun updateQuestionnaireQuestion(id: String, body: QuestionnaireQuestionUpdateRequest): QuestionnaireQuestionDto =
        api.updateQuestionnaireQuestion(id, body)

    suspend fun deleteQuestionnaireQuestion(id: String) {
        api.deleteQuestionnaireQuestion(id)
    }

    suspend fun reorderQuestionnaireQuestions(sectionId: String, questionIds: List<String>): List<QuestionnaireSectionDto> =
        api.reorderQuestionnaireQuestions(QuestionnaireQuestionReorderRequest(sectionId, questionIds))

    suspend fun createQuestionnaireInterview(body: QuestionnaireInterviewCreateRequest): CreatedRecordDto =
        api.createQuestionnaireInterview(body)

    suspend fun interviews(createdBy: String? = null): List<QuestionnaireInterviewDetailDto> =
        api.interviews(pageSize = 100, createdBy = createdBy?.blankToNull()).items


    suspend fun interview(id: String): QuestionnaireInterviewDetailDto = api.interview(id)

    suspend fun updateQuestionnaireInterview(id: String, body: QuestionnaireInterviewUpdateRequest): QuestionnaireInterviewDetailDto =
        api.updateInterview(id, body)

    /**
     * Completion matrix (artisans x sections). Pass [artisanId] to scope it to one artisan, and
     * [workshopIds] to scope it to workshops — null or empty is every workshop, `none` is the records
     * linked to none. LAST and defaulted so no existing call site has to change.
     */
    suspend fun completionMatrix(
        artisanId: String? = null,
        workshopIds: List<String>? = null
    ): CompletionMatrixDto =
        api.completionMatrix(artisanId?.blankToNull(), workshopIds.toQueryCsv())

    /** Admin-only: set ([status] = COMPLETED/NEEDS_REVIEW/NEEDS_REDO) or clear ([status] = null) one cell. */
    suspend fun setCompletionCell(artisanId: String, sectionId: String, status: String?) =
        api.setCompletionCell(CompletionCellRequest(artisanId, sectionId, status))

    /**
     * One artisan's questionnaire gathered from EVERY interview they sat in.
     *
     * [workshopIds] reads the document as it stands FOR THOSE WORKSHOPS: the whole document comes back
     * either way, the scope only decides which sittings feed it. Null or empty is every workshop.
     *
     * The DTO is declared in `ui/ConsolidatedQuestionnaireScreen.kt` beside the screen that renders
     * it — see the import note at the top of [WorkshopRepositoryApi] for why it is not in ApiModels.kt.
     */
    suspend fun consolidatedQuestionnaire(
        artisanId: String,
        workshopIds: List<String>? = null
    ): ConsolidatedQuestionnaireDto =
        api.consolidatedQuestionnaire(artisanId, workshopIds.toQueryCsv())

    /**
     * Upload a captured/selected file as a single streamed object. The bytes are streamed straight
     * from the content Uri to object storage (S3 PUT handles up to 5 GB), so even large videos upload
     * whole — no client-side chunking and no re-muxing, which is both faster and keeps each capture a
     * single file. Long audio is chunked only on the server for transcription, where the per-chunk
     * transcripts are stitched back together, so the stored audio object stays whole too.
     */
    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        linkedRecordType: String?,
        linkedRecordId: String?,
        caption: String?,
        location: LocationRequest?,
        titleHint: String? = null,
        batchIndex: Int = 1,
        processingRequests: List<String>? = null,
        stageStep: Int? = null,
        customSegment: String? = null,
        overrideBaseName: String? = null,
        /**
         * THE DESIGN & PROTOTYPE WORKSHOP a MISCELLANEOUS upload is filed under, straight from that
         * form's dropdown. Every other caller leaves it null and sends nothing.
         *
         * NOT DERIVED FROM [linkedRecordType], deliberately — a stage photograph already carries the
         * tag "designWorkshop" and must keep carrying only the tag. `records.media_relation_data` on
         * the server holds the argument: the orphan-recovery machinery is split precisely on whether
         * a link has a typed foreign key, and deriving this would put every stage photograph in scope
         * of both halves at once.
         */
        designWorkshopId: String? = null,
        /** Stored verbatim on the media row. Today only `{"purpose": …}` - see [MEASUREMENT_GRID_PURPOSE]. */
        extraMetadata: JsonObject? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): MediaFileDto {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val mediaType = inferMediaType(mimeType)
        return uploadResolved(
            context = context,
            uri = uri,
            mimeType = mimeType,
            mediaType = mediaType,
            originalName = originalName,
            linkedRecordType = linkedRecordType,
            linkedRecordId = linkedRecordId,
            caption = caption,
            location = location,
            titleHint = titleHint,
            batchIndex = batchIndex,
            processingRequests = processingRequests,
            stageStep = stageStep,
            customSegment = customSegment,
            overrideBaseName = overrideBaseName,
            extraMetadata = extraMetadata,
            designWorkshopId = designWorkshopId,
            onProgress = onProgress
        )
    }

    /** Single-object upload (no splitting). Streams straight from the Uri so the heap never holds the file. */
    private suspend fun uploadResolved(
        context: Context,
        uri: Uri,
        mimeType: String,
        mediaType: String,
        originalName: String,
        linkedRecordType: String?,
        linkedRecordId: String?,
        caption: String?,
        location: LocationRequest?,
        titleHint: String?,
        batchIndex: Int,
        processingRequests: List<String>?,
        stageStep: Int?,
        customSegment: String?,
        overrideBaseName: String? = null,
        extraMetadata: JsonObject? = null,
        /** See [uploadMedia]'s parameter of the same name. */
        designWorkshopId: String? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): MediaFileDto {
        val resolvedProcessing = processingRequests
            ?: if (mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList()
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = titleHint,
            mediaType = mediaType,
            index = batchIndex,
            stageStep = stageStep,
            customSegment = customSegment,
            caption = caption,
            overrideBaseName = overrideBaseName,
            originalName = originalName
        )
        // Stream the file straight from the content Uri to object storage — never load it fully into
        // memory — so even multi-hundred-MB videos upload without OOM. The size comes from metadata;
        // if that is unavailable we spool to a temp cache file on disk to obtain an exact length.
        val source = withContext(Dispatchers.IO) { resolveUploadSource(context, uri) }
        try {
            val target = uploadBytesToS3(
                context = context,
                filename = filename,
                mimeType = mimeType,
                mediaType = mediaType,
                source = source,
                linkedRecordType = linkedRecordType,
                linkedRecordId = linkedRecordId,
                onProgress = onProgress
            )
            val media = completeUpload(
                MediaCompleteRequest(
                    originalFilename = filename,
                    mediaType = mediaType,
                    mimeType = mimeType,
                    sizeBytes = source.size,
                    objectKey = target.objectKey,
                    bucket = target.bucket,
                    url = target.publicUrl,
                    caption = caption.blankToNull(),
                    linkedRecordType = linkedRecordType.blankToNull(),
                    linkedRecordId = linkedRecordId.blankToNull(),
                    designWorkshopId = designWorkshopId.blankToNull(),
                    recordedAt = Instant.now().toString(),
                    location = location,
                    processingRequests = resolvedProcessing,
                    extraMetadata = extraMetadata
                ),
                target.checksum
            )
            StagedJournal.drop(target.objectKey)
            return media
        } finally {
            source.cleanup()
        }
    }

    /**
     * `/media/complete`, carrying the SHA-256 of the bytes that actually went up so a silently
     * corrupted transfer is detectable later. [MediaCompleteRequest] has no `checksum` field, so the
     * key is added to the encoded body — derived from the canonical request rather than through a
     * parallel data class, so a field added to it is still sent here.
     */
    private suspend fun completeUpload(body: MediaCompleteRequest, checksum: String?): MediaFileDto {
        if (checksum == null) return api.completeMedia(body)
        val encoded = completeJson.encodeToJsonElement(MediaCompleteRequest.serializer(), body).jsonObject
        return api.completeMediaChecksummed(JsonObject(encoded + ("checksum" to JsonPrimitive(checksum))))
    }

    /**
     * Eager pre-upload: push the bytes to object storage immediately on capture using a provisional
     * key, so the slow network transfer overlaps the time the user spends filling the form. The
     * human-readable, nomenclature-correct filename is applied later in [completeStaged].
     */
    suspend fun preuploadObject(
        context: Context,
        uri: Uri,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): StagedMedia {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val mediaType = inferMediaType(mimeType)
        val source = withContext(Dispatchers.IO) { resolveUploadSource(context, uri) }
        try {
            val provisional = "staged-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}" +
                (extension?.let { ".$it" } ?: "")
            val target = uploadBytesToS3(
                context = context,
                filename = provisional,
                mimeType = mimeType,
                mediaType = mediaType,
                source = source,
                linkedRecordType = null,
                linkedRecordId = null,
                onProgress = onProgress
            )
            // The hash is computed by the transfer and consumed by a save that may be many minutes
            // away, so it rides in the journal — the one record of this object that outlives both.
            StagedJournal.record(context, target.objectKey, target.checksum)
            return StagedMedia(
                objectKey = target.objectKey,
                bucket = target.bucket,
                publicUrl = target.publicUrl,
                mimeType = mimeType,
                mediaType = mediaType,
                sizeBytes = source.size,
                extension = extension
            )
        } finally {
            source.cleanup()
        }
    }

    /** Where an uploaded object ended up: its key, bucket, public URL, and the hash of what went up. */
    private data class UploadTarget(
        val objectKey: String,
        val bucket: String,
        val publicUrl: String?,
        val checksum: String?
    )

    /**
     * Push a resolved source to object storage and return its location. Files at/under
     * [MULTIPART_THRESHOLD] go up as one streamed PUT (fast, simple). Larger files use an S3 multipart
     * upload: the bytes are chunked for the transfer (resilient, resumable per part, and past the 5 GB
     * single-PUT ceiling), then S3 stitches the parts into a single object on complete — so the stored
     * file is still whole. Best of both worlds.
     */
    private suspend fun uploadBytesToS3(
        context: Context,
        filename: String,
        mimeType: String,
        mediaType: String,
        source: UploadSource,
        linkedRecordType: String?,
        linkedRecordId: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): UploadTarget {
        if (source.size <= MULTIPART_THRESHOLD) {
            val presign = api.presignMedia(
                MediaPresignRequest(
                    filename = filename,
                    mimeType = mimeType,
                    mediaType = mediaType,
                    sizeBytes = source.size,
                    linkedRecordType = linkedRecordType.blankToNull(),
                    linkedRecordId = linkedRecordId.blankToNull()
                )
            )
            // Journalled before the first byte moves: from here until /media/complete claims the key,
            // this line on disk is the only thing that would know the bucket holds an unreferenced
            // object if the process were killed right now.
            StagedJournal.record(context, presign.objectKey)
            val digest = ContentDigest()
            withContext(Dispatchers.IO) {
                putToStorage(presign.uploadUrl, presign.headers, source.size, mimeType, source.open, onProgress, digest)
            }
            return UploadTarget(presign.objectKey, presign.bucket, presign.publicUrl, digest.hex())
        }
        return uploadMultipart(context, filename, mimeType, mediaType, source, linkedRecordType, linkedRecordId, onProgress)
    }

    /** S3 multipart upload for a large file: chunk → upload parts → S3 stitches into one object. */
    private suspend fun uploadMultipart(
        context: Context,
        filename: String,
        mimeType: String,
        mediaType: String,
        source: UploadSource,
        linkedRecordType: String?,
        linkedRecordId: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): UploadTarget {
        val create = api.createMultipart(
            MultipartCreateRequest(
                filename = filename,
                mimeType = mimeType,
                mediaType = mediaType,
                sizeBytes = source.size,
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull()
            )
        )
        // The uploadId goes to disk WITH the key, because for a multipart the key alone is useless:
        // until the parts are stitched there is no object at it, only uploaded parts, and the one
        // call that reclaims those needs the uploadId to name them. Recording the key alone left a
        // large video killed mid-transfer — the most likely transfer to be killed — costing storage
        // that no sweep could ever find its way back to.
        StagedJournal.record(context, create.objectKey, uploadId = create.uploadId)
        try {
            val partUrls = api.presignMultipartParts(
                MultipartPresignPartsRequest(
                    objectKey = create.objectKey,
                    uploadId = create.uploadId,
                    partNumbers = (1..create.partCount).toList()
                )
            ).urls
            val completed = ArrayList<CompletedPart>(create.partCount)
            val digest = ContentDigest()
            withContext(Dispatchers.IO) {
                source.open().use { input ->
                    var sentTotal = 0L
                    for (partNumber in 1..create.partCount) {
                        val thisSize = minOf(create.partSize, source.size - (partNumber - 1).toLong() * create.partSize)
                        val bytes = readExactly(input, thisSize.toInt())
                        // Hashed here rather than in the request body: parts are read in order (so the
                        // hash is of the whole file, as S3 will stitch it) and a part retry re-sends
                        // bytes already counted.
                        digest.update(bytes, bytes.size)
                        val url = partUrls[partNumber.toString()]
                            ?: throw IllegalStateException("Missing presigned URL for part $partNumber")
                        val base = sentTotal
                        val etag = putPart(
                            url = url,
                            bytes = bytes,
                            onProgress = { sent -> onProgress?.invoke(base + sent, source.size) },
                            repesign = {
                                api.presignMultipartParts(
                                    MultipartPresignPartsRequest(
                                        objectKey = create.objectKey,
                                        uploadId = create.uploadId,
                                        partNumbers = listOf(partNumber)
                                    )
                                ).urls[partNumber.toString()]
                            }
                        )
                        completed.add(CompletedPart(partNumber = partNumber, etag = etag))
                        sentTotal += bytes.size.toLong()
                    }
                }
            }
            val done = api.completeMultipart(
                MultipartCompleteRequest(
                    objectKey = create.objectKey,
                    uploadId = create.uploadId,
                    parts = completed
                )
            )
            return UploadTarget(done.objectKey, done.bucket, done.publicUrl, digest.hex())
        } catch (t: Throwable) {
            // Clean up the half-done multipart upload so its parts don't linger in storage.
            //
            // NonCancellable because the commonest reason to be here is now cancellation — a sibling
            // upload in the same batch failed and took this one down with it — and a suspend call
            // made from a cancelled coroutine gives up at its first suspension point, which would
            // mean the cancelled transfer's parts were never reclaimed. The journal is still the
            // backstop if even this cannot reach the server.
            withContext(NonCancellable) {
                runCatching {
                    api.abortMultipart(MultipartAbortRequest(create.objectKey, create.uploadId))
                    // The abort discarded the parts, so there is nothing left for a sweep to reclaim.
                    StagedJournal.drop(create.objectKey)
                }
            }
            throw t
        }
    }

    /** Read exactly [size] bytes from [input] (handles short reads); returns fewer only at EOF. */
    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == size) buffer else buffer.copyOf(offset)
    }

    /**
     * Upload one multipart part (with retry) and return its S3 ETag for the complete call.
     *
     * A part URL is signed for an hour (`s3.py:191`) and every part of a 400 MB video is signed at
     * once, up front — on a field connection the last parts can easily still be waiting when their
     * signature runs out, and S3 rejects an expired one with 403. [repesign] gets that single part a
     * fresh URL so the transfer continues, instead of the whole upload dying on the one thing that is
     * certain to fix itself. The refresh is allowed once and does not spend a retry attempt: it is not
     * a failure, and the bytes still have to go somewhere.
     */
    private suspend fun putPart(
        url: String,
        bytes: ByteArray,
        onProgress: ((sent: Long) -> Unit)?,
        repesign: suspend () -> String?
    ): String {
        val maxAttempts = 3
        var target = url
        var refreshed = false
        var failures = 0
        var lastError: Exception? = null
        while (true) {
            // Same reason as [putToStorage]: a cancelled call arrives as an IOException, and this
            // loop would otherwise re-send the part instead of standing down.
            currentCoroutineContext().ensureActive()
            var expired = false
            try {
                // Content-Type is intentionally unset: the part presign does not sign it, so sending one
                // would not match. A fresh ByteArrayInputStream per attempt lets a retry re-send cleanly.
                val body = StreamingRequestBody(
                    bytes.size.toLong(),
                    null,
                    { java.io.ByteArrayInputStream(bytes) },
                    onProgress?.let { cb -> { sent, _ -> cb(sent) } }
                )
                executeCancellable(storageClient.newCall(Request.Builder().url(target).put(body).build())).use { response ->
                    if (response.isSuccessful) {
                        return response.header("ETag")
                            ?: throw IllegalStateException("S3 returned no ETag for the uploaded part")
                    }
                    if (response.code == 403 && !refreshed) expired = true
                    else if (response.code < 500) throw IllegalStateException("Part upload failed: HTTP ${response.code}")
                    lastError = IllegalStateException("Part upload failed: HTTP ${response.code}")
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (expired) {
                refreshed = true
                target = repesign() ?: throw (lastError ?: IllegalStateException("Part upload failed: HTTP 403"))
                continue
            }
            failures++
            if (failures >= maxAttempts) break
            delay(800L * failures)
        }
        throw lastError ?: IllegalStateException("Part upload failed")
    }

    /** Attach an already-uploaded staged object to a saved record, applying the final filename. */
    suspend fun completeStaged(
        staged: StagedMedia,
        linkedRecordType: String?,
        linkedRecordId: String?,
        recordName: String?,
        caption: String?,
        location: LocationRequest?,
        batchIndex: Int = 1,
        stageStep: Int? = null,
        customSegment: String? = null,
        processingRequests: List<String>? = null,
        overrideBaseName: String? = null,
        /**
         * Stored verbatim on the media row - see [MEASUREMENT_GRID_PURPOSE].
         *
         * IT HAS TO BE HERE AS WELL AS ON [uploadMedia], and this is the arm that actually carries
         * the grid marker in practice: an attachment is eagerly pre-uploaded the moment it is picked,
         * so by the time the record saves the bytes are already staged and this is the call that
         * registers the row. A marker only the fresh-upload path wrote would fire only when the eager
         * upload had failed.
         */
        extraMetadata: JsonObject? = null
    ): MediaFileDto {
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = recordName,
            mediaType = staged.mediaType,
            index = batchIndex,
            stageStep = stageStep,
            customSegment = customSegment,
            caption = caption,
            overrideBaseName = overrideBaseName,
            originalName = "media" + (staged.extension?.let { ".$it" } ?: "")
        )
        val resolvedProcessing = processingRequests
            ?: if (staged.mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList()
        val media = completeUpload(
            MediaCompleteRequest(
                originalFilename = filename,
                mediaType = staged.mediaType,
                mimeType = staged.mimeType,
                sizeBytes = staged.sizeBytes,
                objectKey = staged.objectKey,
                bucket = staged.bucket,
                url = staged.publicUrl,
                caption = caption.blankToNull(),
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull(),
                recordedAt = Instant.now().toString(),
                location = location,
                processingRequests = resolvedProcessing,
                extraMetadata = extraMetadata
            ),
            StagedJournal.checksumFor(staged.objectKey)
        )
        StagedJournal.drop(staged.objectKey)
        return media
    }

    /** Delete a staged object that was cancelled before save. */
    suspend fun deleteStaged(objectKey: String) {
        // Journalled until the server confirms: a delete that never landed still leaves bytes behind,
        // and the next launch's sweep is what finishes the job.
        api.deleteMediaObject(objectKey)
        StagedJournal.drop(objectKey)
    }

    /**
     * Delete every object a previous run of the app left staged and never attached to a record — the
     * bytes of a capture that was mid-upload when the process died. Run once on app start (see
     * [syncOutbox]), never per upload: an object staged by THIS process belongs to a form that may
     * still be open.
     *
     * Nothing is destructive by accident. `/media/object` and `/media/multipart/abort` are both
     * scoped to the caller's own `media/<user_id>/` prefix, and the delete 409s on anything a record
     * already points at, so the worst a bad entry can do is come back refused — which counts as
     * settled, since the object clearly found an owner. Only an unsettled key (no signal, gateway
     * failure) stays for the next launch.
     */
    suspend fun sweepStagedObjects(context: Context): Int {
        if (!hasToken() || !ConnectivityObserver.isOnline(context)) return 0
        return StagedJournal.sweep(context) { objectKey, uploadId ->
            // Abort BEFORE deleting, and only for a key that was journalled as a multipart. An
            // interrupted multipart has no object to delete — deleting the key removes nothing and
            // the uploaded parts stay billed for ever — so this is the only call that reclaims them.
            val abortSettled = uploadId == null || try {
                api.abortMultipart(MultipartAbortRequest(objectKey, uploadId))
                true
            } catch (e: HttpException) {
                // The server answered, so there is nothing more to do about this uploadId here: a 500
                // is what S3's NoSuchUpload becomes once the upload DID complete (leaving the finished
                // object, which the delete below handles), 403/404 mean it was never ours to abort.
                true
            } catch (e: IOException) {
                false
            }
            val deleteSettled = try {
                api.deleteMediaObject(objectKey)
                true
            } catch (e: HttpException) {
                // 409 attached, 403 another account's key (this device changed hands), 404 already gone.
                e.code() == 409 || e.code() == 403 || e.code() == 404
            } catch (e: IOException) {
                false
            }
            // Journalled until BOTH halves are settled: forgetting the key after a successful delete
            // whose abort never landed would strand the parts exactly as before.
            abortSettled && deleteSettled
        }
    }

    // --- Offline outbox: make entries with no connection, sync them when it returns ---

    /** True when the device currently has validated internet. */
    fun isOnline(context: Context): Boolean = ConnectivityObserver.isOnline(context)

    /** How many records are waiting in the local outbox to be uploaded — refusals excluded. */
    suspend fun pendingUploads(context: Context): Int = OfflineOutbox.count(context)

    /** Both halves of the queue, for the banner that must not describe one as the other. */
    suspend fun outboxCounts(context: Context): OutboxCounts = OfflineOutbox.counts(context)

    /**
     * Try a refused entry again, at a person's request, and drain the queue while we are here.
     *
     * The missing inverse of `markFailure` reaching the UI. Everything else in this app that can be
     * permanently refused already has this — `retryDesignWorkshopSync` for a workshop,
     * `DraftMedia.uploadFailure` for one file — and the records queue did not, so a refusal was a
     * dead end with no surface and no tap. See [OfflineOutbox.clearFailure].
     *
     * ── WHY THIS ANSWERS ABOUT ONE ENTRY AND NOT WITH A COUNT ─────────────────────────────────
     *
     * It used to return [syncOutbox]'s number, which counts every entry the WHOLE pass moved. With
     * entry A refused and entry B merely waiting behind it, retrying A drained B, the count came back
     * 1, and the tray said "“A” was sent." while A was still listed underneath with its refusal
     * printed under it. That is the one thing this queue's whole design forbids — nothing may claim to
     * have sent something before it did — and it is worse than a missing message, because the next act
     * it invites is throwing the entry away.
     *
     * The entry's own fate is read from the queue rather than inferred from the count: an id that is
     * gone was removed by [syncOutbox] on a successful replay, and nothing else in the app removes one
     * except a person's explicit discard, which cannot happen inside this call.
     */
    suspend fun retryOutboxEntry(context: Context, entryId: String): OutboxRetryResult {
        // READ BEFORE THE PASS, because `syncOutbox` returns 0 both for "no signal, nothing attempted"
        // and for "attempted and refused again", and those need different sentences. The failure is
        // cleared either way: an entry a person has asked to retry should be waiting rather than
        // parked, so the next automatic pass picks it up when the signal returns.
        val online = isOnline(context)
        OfflineOutbox.clearFailure(context, entryId)
        val moved = syncOutbox(context)
        val stillQueued = OfflineOutbox.all(context).any { it.id == entryId }
        return OutboxRetryResult(
            requestedSent = !stillQueued,
            refusedSent = if (stillQueued) 0 else 1,
            refusedTried = 1,
            othersSent = (moved - if (stillQueued) 0 else 1).coerceAtLeast(0),
            attempted = online,
        )
    }

    /**
     * Try every refused entry again — after signing in again, or after an app update.
     *
     * @return how many of the entries that WERE REFUSED went. Not [syncOutbox]'s number, for the
     *   reason given on [retryOutboxEntry]: that number includes entries which were only ever waiting
     *   on a signal, so "tried all of them again; 4 went" was said over a tray in which four refusals
     *   were all still listed.
     */
    suspend fun retryAllOutboxFailures(context: Context): OutboxRetryResult {
        val online = isOnline(context)
        val wereRefused = OfflineOutbox.failed(context).map { it.id }.toSet()
        OfflineOutbox.clearAllFailures(context)
        val moved = syncOutbox(context)
        val remaining = OfflineOutbox.all(context).map { it.id }.toSet()
        val refusedThatWent = wereRefused.count { it !in remaining }
        return OutboxRetryResult(
            requestedSent = refusedThatWent > 0,
            refusedSent = refusedThatWent,
            refusedTried = wereRefused.size,
            othersSent = (moved - refusedThatWent).coerceAtLeast(0),
            attempted = online,
        )
    }

    /**
     * Throw away one refused entry and its copied bytes, because a person said so.
     *
     * Reachable from the tray and from nowhere else. Nothing automatic in this app may call it: the
     * whole policy of [OfflineOutbox] is that a refusal is a reason to stop retrying and tell
     * somebody, never a reason to destroy the only copy of a day's fieldwork.
     */
    suspend fun discardOutboxEntry(context: Context, entryId: String): Boolean =
        OfflineOutbox.discard(context, entryId)

    /**
     * RE-PICK ONE FIELD ON A QUEUED RECORD THAT POINTS AT SOMETHING THE SERVER DOES NOT HAVE, and
     * send it.
     *
     * ── THE THIRD DOOR, ON THE ONE ROW THAT HAD ONLY TWO BAD ONES ─────────────────────────────
     *
     * A dangling foreign key is a permanent refusal ([blocksRetry] parks it, correctly — a bare retry
     * fetches the identical 404), so before this the tray offered *Try again*, which cannot work, and
     * *Throw away*, which destroys the only copy of the record and its photographs. Neither is the
     * remedy, and the remedy is small: the record is whole on this phone and exactly one key in it is
     * wrong. This changes that key.
     *
     * IT DRAINS THE QUEUE AFTERWARDS for the reason [retryOutboxEntry] does — a person who has just
     * fixed a record wants it gone, and they are by definition holding a phone with a connection,
     * because a 404 is an answer. The result is reported the same way and about THIS entry only:
     * `syncOutbox`'s number counts every entry the pass moved, and reading it as the answer about one
     * row is how "“A” was sent." came to be printed above A, still listed.
     *
     * NOTHING IS DELETED HERE AND NOTHING CAN BE. [OfflineOutbox.repick] rewrites one key inside the
     * payload and clears the refusal; the staged bytes are not touched, and `discard` is still the
     * only door out of this queue that is not a successful send. If the re-picked id is ALSO not on
     * the server, the next pass records the identical outcome and the row comes back with its
     * sentence — which is the honest end of a bad guess, rather than a deletion.
     *
     * @param field the wire name of the column — one of `PendingEntry.danglingKeys`.
     * @param value the new id, or null/blank for "file it under nothing", which is a real choice and
     *   is recorded as one ([UNFILED_BY_CHOICE]) so the replay sends the explicit null the server
     *   needs rather than an omitted key it would read as "leave it alone".
     */
    suspend fun repickOutboxEntry(
        context: Context,
        entryId: String,
        field: String,
        value: String?,
    ): OutboxRetryResult {
        val online = isOnline(context)
        val rewritten = OfflineOutbox.repick(context, entryId, field, value)
        // A payload that would not parse, or an entry that is no longer queued. Nothing was changed,
        // so nothing is claimed and the pass is not run: reporting "still did not go" over a request
        // that was never made is the defect `OutboxRetryResult.attempted` exists to prevent.
        if (!rewritten) return OutboxRetryResult(false, 0, 1, 0, attempted = false)
        val moved = syncOutbox(context)
        val stillQueued = OfflineOutbox.all(context).any { it.id == entryId }
        return OutboxRetryResult(
            requestedSent = !stillQueued,
            refusedSent = if (stillQueued) 0 else 1,
            refusedTried = 1,
            othersSent = (moved - if (stillQueued) 0 else 1).coerceAtLeast(0),
            attempted = online,
        )
    }

    /**
     * The options for a re-picked workshop link, as the tray's picker needs them: id and label.
     *
     * ── IT ASKS THE SERVER AND NEVER A CACHE, LIKE EVERY OTHER ACCESS LIST ────────────────────
     *
     * R6, and it binds here as hard as it binds on the form: *"a stale copy of an access list is
     * wrong in the PERMISSIVE direction — a revoked grant still reads as a grant — and this is the
     * one control whose whole job is offering."* Offering a workshop from a cache on THIS screen
     * would be worse than on a form, because the designer is here precisely because a workshop id
     * turned out not to be honourable, and answering that with a remembered list is answering a
     * question about the server with a question about the phone.
     *
     * A FAILED READ IS AN EMPTY LIST AND THE CALLER SAYS SO. It never throws into the tray: the entry
     * is safe either way, and the one thing that must not happen on this screen is a dialog that
     * cannot open over a record whose only remaining route out is that dialog.
     */
    suspend fun repickOptions(field: String): RepickChoices = when (field) {
        "designWorkshopId" -> runCatching {
            RepickChoices(
                designWorkshops(page = 1, pageSize = REPICK_PAGE).items.map { workshop ->
                    RepickOption(
                        id = workshop.id,
                        label = workshop.title.ifBlank { "Untitled workshop" },
                        // The three facts that tell two workshops apart on a phone, exactly as
                        // `DesignWorkshopField` assembles them — a designer choosing here is
                        // choosing between the same rows they were choosing between on the form,
                        // and two lists of the same workshops labelled two ways is how the wrong
                        // one gets picked.
                        hint = listOfNotNull(
                            workshop.craftName?.takeIf { it.isNotBlank() },
                            workshop.clusterName?.takeIf { it.isNotBlank() }
                                ?: workshop.state?.takeIf { it.isNotBlank() },
                            workshop.startDate?.take(10)?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").takeIf { it.isNotBlank() },
                    )
                },
                listed = true,
            )
        }.getOrDefault(RepickChoices(emptyList(), listed = false))

        "workshopId" -> runCatching {
            RepickChoices(
                workshopsIMaySubmitTo().map { workshop ->
                    RepickOption(
                        id = workshop.id,
                        label = workshop.title.ifBlank { "Untitled workshop" },
                        hint = workshop.place.takeIf { it.isNotBlank() },
                    )
                },
                listed = true,
            )
        }.getOrDefault(RepickChoices(emptyList(), listed = false))

        // Every other reference is dangling-able and not re-pickable from here — see
        // `OutboxFailureRow.repickKeys`, which is why this is unreachable rather than merely empty.
        // `listed = true` because there is nothing to read and no read to have failed; the tray does
        // not open this dialog for such a field at all.
        else -> RepickChoices(emptyList(), listed = true)
    }

    /**
     * Save a new record to the local outbox (no network). Copies the attached media into app storage so
     * nothing is lost, then enqueues the serialized create request. [payloadJson] is the record's
     * create request serialized with [offlineJson]. Synced later by [syncOutbox].
     *
     * [purposes] IS THE PER-FILE MARKER MAP THE FORM HOLDS — `MediaCaptureState.purposes`, keyed by
     * the same [Uri]s in [mediaUris]. It is passed as a plain map rather than as the capture state
     * itself because that class is private to the UI layer; the map is the whole of what this layer
     * needs. Empty for every form with no grid section, so nothing changes for them.
     *
     * WITHOUT IT THE GRID MARKER DIED AT THE OUTBOX. `uploadAttachments` reads the purposes map on
     * the online path; this is the offline one, and it used to build its specs from the uris alone —
     * so a product photographed on graph paper in a cluster with no signal uploaded that sheet
     * unmarked on reconnect and the report printed it as the product. See [PendingMedia.purpose].
     */
    suspend fun queueOffline(
        context: Context,
        type: String,
        payloadJson: String,
        label: String,
        mediaUris: List<Uri>,
        recordName: String?,
        caption: String?,
        purposes: Map<Uri, String> = emptyMap(),
        /** Non-null queues a CORRECTION to that record rather than a new one. See [PendingEntry.targetId]. */
        targetId: String? = null,
        /**
         * WHY A WORKSHOP BOX ON THIS FORM WAS EMPTY, when one was — see [PendingEntry.unfiled].
         *
         * The form is the only place that knows, and it is the only place that can ever know: by the
         * time the entry drains, days later, the picker that was empty in a courtyard with no signal
         * is full again, and nothing on the device can reconstruct which of the two absences it was.
         * `UNFILED_BY_CHOICE` reaches the wire as an explicit null and un-files the record;
         * `UNFILED_NO_OPTIONS` sends nothing and is reported when the entry lands.
         *
         * DEFAULTED TO EMPTY, which is what every caller that has not been taught to pass it sends,
         * and which decodes and replays exactly as an entry from any earlier build does.
         */
        unfiled: Map<String, String> = emptyMap(),
    ): OfflineQueueResult = queueOfflineEntry(
        context, type, payloadJson, label,
        mediaUris.mapIndexed { index, uri ->
            OfflineMediaSpec(
                uri = uri,
                caption = caption,
                recordName = recordName,
                batchIndex = index + 1,
                purpose = purposes[uri],
            )
        },
        targetId = targetId,
        unfiled = unfiled,
    )

    /**
     * Queue files against a record that is ALREADY on the server, because the connection died after
     * the record landed and before its photographs did.
     *
     * Called by `uploadAttachments` at the moment it would otherwise have thrown the instruction the
     * designer could not follow. The bytes are copied out of `cacheDir` here, which is the last
     * moment they exist: see [OFFLINE_MEDIA_ONLY].
     */
    suspend fun queueMediaForSavedRecord(
        context: Context,
        recordType: String,
        recordId: String,
        label: String,
        items: List<OfflineMediaSpec>,
    ): OfflineQueueResult = queueOfflineEntry(
        context = context,
        type = OFFLINE_MEDIA_ONLY,
        // Empty, and read by nothing: `createFromEntry` performs no request for this type. An entry
        // that carried a body could be replayed into a second record by a future edit to that `when`.
        payloadJson = "",
        label = label,
        items = items.map { it.copy(linkedType = it.linkedType ?: recordType) },
        targetId = recordId,
    )

    /**
     * Queue an offline entry with a fully specified media list (e.g. attachments + stage captures).
     *
     * ── WHY THIS RETURNS SOMETHING NOW, AND WHY IT NEVER THROWS ON A BAD FILE ─────────────────
     *
     * `OfflineOutbox.stageMedia` throws on a Uri it cannot open, and this used to stage every file
     * and THEN enqueue. One bad file out of eight therefore cost three separate things at once:
     *
     *  1. THE TYPED RECORD. The throw escaped to the form, which evaluates
     *     `runCatching { trySaveOffline(...) }.getOrElse { false }` — read as "we are online" — and
     *     went down the ONLINE path, which failed with a network error on a phone with no signal.
     *     The record the designer had just spent twenty minutes typing was gone.
     *  2. THE SEVEN GOOD FILES. Already copied into `filesDir/outbox/media/` with nothing pointing
     *     at them, permanently orphaned, and until [OfflineOutbox.reclaimOrphanMedia] there was no
     *     sweeper for that directory anywhere in the app.
     *  3. ANY IDEA OF WHAT HAPPENED. "Couldn't save offline" was the whole message.
     *
     * So a file that cannot be read no longer stops the save. The record and every readable file are
     * queued, the unreadable ones are NAMED in the returned [OfflineQueueResult], and the caller
     * tells the designer — which is the honest half of a whole this app cannot deliver: those bytes
     * were never obtainable, and pretending the record is complete would be worse than saying so
     * while the designer is still standing where the photograph could be taken again.
     *
     * A failure of the ENQUEUE itself — a full disk, an unwritable queue file — is still thrown, and
     * every file staged for this entry is deleted on the way out, so a failed save leaks nothing.
     */
    suspend fun queueOfflineEntry(
        context: Context,
        type: String,
        payloadJson: String,
        label: String,
        items: List<OfflineMediaSpec>,
        targetId: String? = null,
        /** See [queueOffline]. Empty for every caller that does not mount a workshop picker. */
        unfiled: Map<String, String> = emptyMap(),
    ): OfflineQueueResult = withContext(Dispatchers.IO) {
        val media = mutableListOf<PendingMedia>()
        val unreadable = mutableListOf<String>()
        for ((position, spec) in items.withIndex()) {
            val staged = runCatching {
                OfflineOutbox.stageMedia(
                    context = context,
                    uri = spec.uri,
                    caption = spec.caption,
                    recordName = spec.recordName,
                    customSegment = spec.customSegment,
                    overrideBaseName = spec.overrideBaseName,
                    batchIndex = spec.batchIndex,
                    processing = spec.processing,
                    stageStep = spec.stageStep,
                    linkedType = spec.linkedType,
                    stepIndex = spec.stepIndex,
                    purpose = spec.purpose
                )
            }
            staged.getOrNull()?.let { media.add(it) }
                // `withIndex` and not `items.indexOf(spec)`: two captures of the same file in one
                // sitting are equal specs, so `indexOf` answers with the FIRST one's position and the
                // designer is sent to look at the wrong photograph — or at two entries both calling
                // themselves "file 3". The position is the only thing here that identifies a capture
                // whose provider gave up no name at all.
                ?: unreadable.add(spec.uri.lastPathSegment ?: "file ${position + 1}")
        }
        val entryId = java.util.UUID.randomUUID().toString()
        try {
            OfflineOutbox.enqueue(
                context,
                PendingEntry(
                    id = entryId,
                    type = type,
                    payloadJson = payloadJson,
                    label = label,
                    media = media,
                    createdAt = Instant.now().toString(),
                    targetId = targetId,
                    unfiled = unfiled,
                )
            )
        } catch (e: Throwable) {
            // ROLLED BACK, so a save that could not be recorded leaves no bytes behind it. The entry
            // is what makes those files findable; without one they are unreachable by every path in
            // the app including the reclaim sweep's 24-hour grace.
            media.forEach { runCatching { File(it.localPath).delete() } }
            throw e
        }
        OfflineQueueResult(entryId = entryId, queuedFiles = media.size, unreadableFiles = unreadable)
    }

    /**
     * Replay every queued offline entry: create the record, then upload its copied media, then drop
     * the local copy. Returns the number of entries fully synced. Safe to call often.
     *
     * TWO THINGS THIS NO LONGER DOES, both of which cost field data.
     *
     * IT NEVER RE-CREATES A RECORD THE SERVER ALREADY HAS. "Create, then upload the media" is two
     * steps and only the first is cheap to repeat — repeating it makes a SECOND record. The server id
     * is written back to the entry the moment the create lands, and every uploaded file is ticked off
     * as it goes, so a pass interrupted during the media resumes at the media. Before this, an entry
     * whose upload failed re-created its record on the next pass, and the pass after that, once per
     * sync for as long as the signal stayed bad — and a bad signal is the entire reason the entry is
     * in the outbox.
     *
     * AND IT NEVER STOPS THE WHOLE QUEUE AT A RECORD THE SERVER WILL NOT TAKE. A 4xx is the server's
     * final answer: the payload is wrong, or this user may not do that, and the next pass sends the
     * identical bytes to the identical rejection. It is recorded on the entry, said out loud, and
     * stepped over. A 5xx, a timeout or a dead connection is the opposite — everything behind it will
     * fail the same way — so the pass stops there and the queue keeps its order. This is the triage
     * `frontend/lib/offline.ts` already makes; without it, one unacceptable record silently blocked
     * every record queued behind it, for ever.
     */
    suspend fun syncOutbox(context: Context): Int {
        // App-start housekeeping, not per-upload work. This is the app's existing "signed in, or the
        // network just came back" hook and the only one that carries a Context, so the first pass of
        // the process also reclaims objects an earlier run left staged. Detached, so a slow sweep
        // never delays the queued records — those are the data the researcher is waiting on.
        if (sweptStagedObjects.compareAndSet(false, true)) {
            AppScope.io.launch { runCatching { sweepStagedObjects(context) } }
            // AND THE LOCAL COPIES NOBODY OWNS, on the same once-per-process hook and for the same
            // reason: this is the app's only "we are alive and something might need reclaiming"
            // moment that carries a Context. Earlier builds staged every file and then enqueued, so a
            // save that failed part-way left bytes in `filesDir/outbox/media/` with nothing pointing
            // at them and no sweeper anywhere in the app. Detached and swallowing everything — a
            // reclaim must never delay the queued records, which are the data somebody is waiting on.
            AppScope.io.launch { runCatching { OfflineOutbox.reclaimOrphanMedia(context) } }
        }
        // THE DICTATION CEILING, LEARNED BEFORE IT IS SPENT — on the same hook and for the same
        // reason. This runs on app open and whenever the network comes back, which is exactly when a
        // handset's mirror is either empty (first open of the day, or a cold start after a
        // swipe-away) or belongs to yesterday. Detached and swallowing every failure, because a
        // number that is only an optimisation must never delay or fail the queued records beside it.
        AppScope.io.launch { runCatching { refreshDictationAllowance(context) } }
        val synced = syncMutex.withLock {
            val queue = OfflineOutbox.all(context)
            // Read first, then reported, and reported before the connection is even checked: a queue
            // file that would not parse is the one problem the researcher cannot see for themselves,
            // because its only symptom is a count that quietly drops.
            OfflineOutbox.takeAlert()?.let { notifyUser(context, it) }
            if (!ConnectivityObserver.isOnline(context)) return@withLock 0
            var synced = 0
            for (queued in queue) {
                // Already triaged as permanent: this one is waiting on a person, not on the network —
                // UNLESS it is waiting on an update instead, in which case this run is the one that
                // gets to find out whether the update has landed. The identical gate the design
                // workshop pass uses (`WorkshopSync.pushStages`) and the web outbox uses
                // (`frontend/lib/offline.ts`), because it is the identical defect: a record refused
                // for a key the API had not learned yet stayed refused after the API learned it.
                if (blocksRetry(queued.failure != null, queued.skewRun)) continue
                when (val outcome = replayEntry(context, queued)) {
                    ReplayOutcome.Synced -> {
                        OfflineOutbox.remove(context, queued)
                        synced++
                        // THE THIRD OUTCOME, AND THE ONLY ONE THAT ENDS IN SUCCESS — which is
                        // precisely why it has to be said out loud. This record went up filed under
                        // nothing because the picker that would have filed it was EMPTY when the
                        // designer filled the form in: no signal, and the access lists are never
                        // cached because a stale one is wrong in the permissive direction
                        // (`WorkshopRepository.kt` `designWorkshops`, `MainActivity`'s
                        // `rememberWorkshopPicker`). Every visible sign now says the record arrived
                        // intact — it did, unfiled — and without this sentence the absence is
                        // discovered weeks later as a record missing from a workshop's lists, by
                        // which time nobody can tell it from a record deliberately filed under
                        // nothing. That is the collapse R7 forbids, arriving by the back door.
                        val emptyPickers = queued.emptyPickerKeys
                        if (emptyPickers.isNotEmpty()) {
                            notifyUser(
                                context,
                                outboxSentUnfiledMessage(
                                    label = queued.label,
                                    nouns = emptyPickers.mapNotNull { REFERENCE_FIELD_NOUNS[it] },
                                ),
                            )
                        }
                    }
                    is ReplayOutcome.Rejected -> {
                        OfflineOutbox.markFailure(
                            context,
                            queued.id,
                            outcome.reason,
                            skewRun = if (outcome.schemaSkew) APP_RUN else null,
                            // Passed on EVERY refusal, not only on a clash. An entry that clashed on
                            // one pass and was refused for a bad field on the next must stop being
                            // described as a clash, or the tray goes on telling the designer to open
                            // a record that has nothing to do with what is now standing in the way
                            // — the same reason `skewRun` is written unconditionally above.
                            conflict = outcome.conflict,
                            // And unconditionally for the third time, for the third instance of the
                            // same reason: an entry that dangled on one pass and was refused for a
                            // bad field on the next must stop offering a Re-pick, or the tray hands
                            // the designer a picker for a field that is no longer what is standing
                            // in the way.
                            danglingField = outcome.danglingField,
                        )
                        // SAID WHEN IT CHANGES, NOT ON EVERY PASS THAT REACHES IT. Until a schema
                        // refusal could be re-attempted, an entry was refused exactly once and this
                        // fired exactly once; a skew that is still open would otherwise raise the
                        // identical sentence every time the app is opened, which is how a researcher
                        // learns to dismiss this notification without reading it — and the one that
                        // matters is then dismissed too. The refusal is still listed the whole time
                        // by `outboxFailures`, so nothing is hidden by staying quiet.
                        if (queued.failure != outcome.reason) {
                            notifyUser(context, "\"${queued.label}\" could not be uploaded. ${outcome.reason}")
                        }
                    }
                    // Transient: stop here so the queue keeps its order and nothing is marked failed
                    // for a reason that is really "the signal went away again".
                    ReplayOutcome.Retry -> return@withLock synced
                }
            }
            synced
        }

        // THE DESIGN WORKSHOPS DRAIN FROM HERE TOO, and hanging them off this call rather than
        // giving them their own trigger is the whole reason they need no scheduler. This function is
        // already invoked at sign-in, from the fallback timer and from the connectivity callback
        // (MainActivity.kt), and every one of those is a moment a workshop should be tried as well.
        // A second timer and a second network callback would be two more things to keep in step with
        // this one, and the day they fall out of step is the day a designer's fortnight sits on the
        // phone because the outbox drained and the workshops did not.
        //
        // AFTER the outbox and outside its lock, in that order and for two reasons: an outbox entry
        // is a whole record the researcher believes is already sent, so it goes first; and holding
        // `syncMutex` across a fortnight of photograph uploads would block every later drain behind
        // it. [WorkshopSyncEngine.syncAll] has its own single-pass lock, so overlapping calls
        // coalesce rather than duplicating work, and it never throws.
        runCatching { WorkshopSyncEngine.syncAll(context, this) }
        return synced
    }

    /**
     * Push one design workshop now, at the designer's request, clearing whatever refusals it holds.
     *
     * Exposed on the repository so a screen never has to reach past it into the engine, matching how
     * every other network action in this app is reached.
     */
    suspend fun retryDesignWorkshopSync(context: Context, workshopId: String): SyncPassResult =
        WorkshopSyncEngine.retryWorkshop(context, this, workshopId)

    /** Send everything outstanding across every workshop on this device. */
    suspend fun syncDesignWorkshops(context: Context): SyncPassResult =
        WorkshopSyncEngine.syncAll(context, this)

    /** What became of one replayed entry. */
    private sealed interface ReplayOutcome {
        /** Record and every attachment are on the server; the local copy can go. */
        data object Synced : ReplayOutcome

        /** Nothing more will happen until the network comes back. Stop the pass; keep the order. */
        data object Retry : ReplayOutcome

        /** The server's final answer. Keep the entry AND its files; tell the researcher. */
        data class Rejected(
            val reason: String,
            /**
             * True when the server could not read the SHAPE of what was sent — this build of the app
             * speaking a dialect this build of the API does not know. Nothing on the record is wrong
             * and no person can settle it; only an update to one of the two can, so the entry is
             * re-attempted by the next app run rather than held for ever. See [blocksRetry].
             */
            val schemaSkew: Boolean = false,
            /**
             * True when the register already holds a record occupying this one's identity — an
             * answered 409, and an outcome of its own rather than one more anonymous refusal. See
             * [PendingEntry.conflict] for why this queue needed a sixth kind of "not synced", and
             * `isConflictRefusal` in `data/WorkshopSync.kt` for the classification.
             *
             * MUTUALLY EXCLUSIVE WITH [schemaSkew] by construction — one is a 409 and the other a
             * 422 carrying `extra_forbidden` — and, unlike it, never re-attempted on its own: a
             * skew clears when either build is updated, a clash clears when a PERSON resolves it. So
             * [blocksRetry] parks it and the tray offers the escape instead.
             */
            val conflict: Boolean = false,
            /**
             * THE COLUMN — or the candidates, comma-separated — this record points at and the server
             * does not have. Null on every other refusal. See [PendingEntry.danglingField].
             *
             * The fifth outcome, and the mirror of [conflict] rather than a variant of it: a clash is
             * something already occupying what this record asked for, this is something this record
             * depends on being gone. [blocksRetry] parks both, because a bare retry fetches the
             * identical answer either way — but only this one has a remedy that is a single tap on
             * the record already sitting on the phone, which is what the tray's third button is.
             *
             * MUTUALLY EXCLUSIVE WITH [schemaSkew] and [conflict] by construction: 422-with-
             * `extra_forbidden`, 409, and 404/422-naming-a-reference are three different answers.
             */
            val danglingField: String? = null,
        ) : ReplayOutcome
    }

    /**
     * Replay one entry, writing every step back to disk as it lands.
     *
     * Where those writes happen is the whole point. The created id goes down BEFORE the first byte of
     * media moves, and each finished file is ticked off as soon as it is up — so whatever kills this
     * pass, the next one starts from what has actually happened rather than from the top.
     */
    private suspend fun replayEntry(context: Context, queued: PendingEntry): ReplayOutcome {
        var entry = queued
        if (entry.createdId == null) {
            val created = try {
                writeFromEntry(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isTransient(e)) return ReplayOutcome.Retry
                // A 409 IS ITS OWN ANSWER, AND IT IS ASKED FOR BEFORE THE BODY IS READ.
                // `isConflictRefusal` reads only the status code — the same reason `isTransient`
                // above it is safe to ask first — because `apiRefusal` below consumes Retrofit's
                // buffered error body and may be called only once per failure.
                //
                // NOTHING HERE DELETES, AND THAT IS THE WHOLE POINT. `frontend/lib/offline.ts` opens
                // with the incident: a 409 read as "the create already landed and we simply lost the
                // response" dropped the queued record and its photographs as sent. No endpoint in
                // this API means that. The lost-response case belongs to `PendingEntry.createdId`,
                // which knows rather than guesses; see `PendingEntry.conflict` for the argument.
                val clash = isConflictRefusal(e)
                // `apiRefusal`, not `apiErrorMessage`: both facts have to come out of ONE read of the
                // error body, because reading it consumes Retrofit's buffer. `isTransient` above is
                // safe to ask first — it reads only the status code.
                val refusal = e.apiRefusal("The server rejected this record.")
                if (clash) {
                    return ReplayOutcome.Rejected(
                        outboxConflictSentence(
                            said = refusal.message,
                            // Nothing has been uploaded: this leg only runs while `createdId` is
                            // null, and a file cannot be attached before the record exists. So every
                            // staged capture is still on the phone, and the sentence says how many.
                            files = entry.media.size,
                            // The same test `outboxKindLabel` uses, so the tray's "— a correction"
                            // and the sentence under it cannot disagree about what this entry is.
                            isCorrection = entry.targetId != null && entry.type != OFFLINE_MEDIA_ONLY,
                        ),
                        // NOT a skew, explicitly rather than by omission: a 409 is not a 422 carrying
                        // `extra_forbidden`, and no update to either build clears it. Stamping
                        // APP_RUN would re-POST the same losing create once per app open, for ever,
                        // on a prepaid connection — every answer the identical 409.
                        schemaSkew = false,
                        conflict = true,
                    )
                }
                // A REFERENCE THIS RECORD POINTS AT IS NOT ON THE SERVER — the fifth outcome, and
                // the opposite failure to the clash above it. Asked AFTER the skew test and gated on
                // it, because a 422 carrying `extra_forbidden` is a disagreement between builds that
                // no re-pick can settle and that the next app run must re-attempt by itself.
                //
                // AND GATED ON THERE BEING A FIELD TO NAME. `danglingReferenceCandidates` answers
                // empty when this entry sent no reference at all, and an empty answer stays an
                // ordinary refusal: a Re-pick button over a record with no id in it would be a
                // remedy with nothing to change, which is the shape of dead end this whole outcome
                // exists to remove rather than to add a second one of.
                val isCorrection = entry.targetId != null && entry.type != OFFLINE_MEDIA_ONLY
                val dangling = if (isMissingReferenceRefusal(e) && !refusal.schemaSkew) {
                    danglingReferenceCandidates(
                        payload = entry.payloadJson,
                        named = refusal.namedFields,
                        isCorrection = isCorrection,
                        // A 422 IS ABOUT A VALUE UNTIL THE SERVER SAYS OTHERWISE. Every field
                        // validator on these routes answers 422 — a name that is too long, an
                        // Aadhaar that fails its checksum — so reading ids out of the payload on
                        // one of those would print "points at an artisan that is not on the server"
                        // over a refusal about a name. A 404 carries no such ambiguity on any route
                        // this outbox replays. See `danglingReferenceCandidates`.
                        namedOnly = (e as? HttpException)?.code() == 422,
                    )
                } else {
                    emptyList()
                }
                if (dangling.isNotEmpty()) {
                    return ReplayOutcome.Rejected(
                        outboxDanglingSentence(
                            said = refusal.message,
                            nouns = dangling.map { key ->
                                referenceFieldNoun(key, recordNoun = outboxRecordNoun(entry.type))
                            },
                            // Nothing has been uploaded: this leg only runs while `createdId` is
                            // null, so every staged capture is still on the phone. Same argument,
                            // same line, as the clash arm above.
                            files = entry.media.size,
                            isCorrection = isCorrection,
                        ),
                        // NOT a skew and NOT a clash, explicitly rather than by omission, for the
                        // reason the clash arm gives about `schemaSkew`: an outcome that is written
                        // conditionally is an outcome that survives from the pass before it.
                        schemaSkew = false,
                        conflict = false,
                        danglingField = dangling.joinToString(","),
                    )
                }
                return ReplayOutcome.Rejected(
                    if (refusal.schemaSkew) {
                        skewSentence("What this copy of the app sent for this record", refusal.message)
                    } else {
                        refusal.message
                    },
                    schemaSkew = refusal.schemaSkew,
                )
            }
            entry = entry.copy(createdId = created.id, createdStepIds = created.stepIds)
            OfflineOutbox.update(context, entry)
        }

        val alreadyUp = entry.uploadedMedia.toMutableSet()
        val refused = mutableListOf<String>()
        val remaining = entry.media.withIndex().filterNot { (index, _) -> index in alreadyUp }
        // Each worker posts its own result here the instant its file lands, rather than the batch
        // reporting as a unit — a batch that is torn down because one of its three files failed has
        // usually finished one of the other two, and a finished file that is not ticked off gets
        // uploaded again next pass, leaving the record holding the same photograph twice.
        val landed = ConcurrentLinkedQueue<FileOutcome>()

        /** Fold everything the workers have finished into the entry and write it to disk. */
        suspend fun persistProgress() {
            var changed = false
            while (true) {
                when (val outcome = landed.poll() ?: break) {
                    is FileOutcome.Uploaded -> alreadyUp.add(outcome.index)
                    is FileOutcome.Refused -> refused.add(outcome.reason)
                }
                changed = true
            }
            if (!changed) return
            entry = entry.copy(uploadedMedia = alreadyUp.sorted())
            OfflineOutbox.update(context, entry)
        }

        var stopped = false
        try {
            for (batch in remaining.chunked(UPLOAD_CONCURRENCY)) {
                try {
                    uploadBatch(context, entry, batch, landed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Only a transient failure escapes [uploadBatch]. The record and every file that
                    // did land are on disk, so the next pass resumes at what is left.
                    stopped = true
                    break
                }
                persistProgress()
            }
        } finally {
            // NonCancellable, because a file that IS on the server has to be ticked off even while
            // this pass is being torn down. The alternative is the next pass sending it again.
            withContext(NonCancellable) { persistProgress() }
        }
        if (stopped) return ReplayOutcome.Retry

        if (refused.isNotEmpty()) {
            // The record IS saved, so the entry must never be replayed — but its files are still only
            // here, so it must not be deleted either. Kept, with the reason, exactly as the web does.
            //
            // `schemaSkew` stays false, and deliberately so rather than by omission: a media upload is
            // multipart form-data rather than an `APIModel` body, so `extra_forbidden` cannot arise
            // here — and re-attempting it every app run would re-POST a record that is ALREADY on the
            // server. This one really does wait for a person.
            return ReplayOutcome.Rejected(
                "It was saved, but ${refused.size} file(s) were refused: ${refused.distinct().joinToString(" ")} " +
                    "Re-attach them on the record."
            )
        }
        return ReplayOutcome.Synced
    }

    /** One file's fate within a replayed batch. */
    private sealed interface FileOutcome {
        data class Uploaded(val index: Int) : FileOutcome
        data class Refused(val index: Int, val reason: String) : FileOutcome
    }

    /**
     * Upload up to [UPLOAD_CONCURRENCY] of a record's attachments at once.
     *
     * The web has done this since the eager-upload work and Android did not: an interview with twelve
     * clips uploaded them strictly in series, so the transfer cost the sum of twelve round trips even
     * though a field connection is usually latency-bound rather than bandwidth-bound. Three at a time
     * is the cap the web uses, and for the same reason: a 2G-ish uplink shared by ten parallel PUTs
     * makes every one of them time out instead of making any of them finish.
     *
     * WHY THE TWO KINDS OF FAILURE LEAVE BY DIFFERENT DOORS. A transient one is THROWN, so
     * `coroutineScope` cancels the siblings on the spot: they share the connection that has just been
     * shown to be gone, and now that the transfers are genuinely cancellable that cancellation stops
     * real sockets instead of being a note in a log. A refusal is POSTED to [landed] instead, because
     * a file the server will not take says nothing about the other two — cancelling them would strand
     * attachments that were seconds from succeeding.
     *
     * Every result goes to [landed] as it happens rather than being returned when the batch is over,
     * so that a torn-down batch still tells the caller which of its files did land.
     */
    private suspend fun uploadBatch(
        context: Context,
        entry: PendingEntry,
        batch: List<IndexedValue<PendingMedia>>,
        landed: ConcurrentLinkedQueue<FileOutcome>
    ): Unit = coroutineScope {
        batch.forEach { (index, pm) ->
            launch(Dispatchers.IO) {
                val target = linkTargetFor(entry, pm)
                if (target == null) {
                    landed.add(
                        FileOutcome.Refused(
                            index,
                            "\"${pm.originalFilename}\" had nowhere to attach — the saved record has fewer " +
                                "process steps than were captured."
                        )
                    )
                    return@launch
                }
                // THE BYTES, BEFORE ANYTHING IS CLAIMED ABOUT THEM. `uploadLocalFile` used to open
                // with `if (!file.exists()) return` — it returned NORMALLY, so this loop posted
                // `Uploaded(index)`, the index was ticked off, the entry reached Synced and
                // `OfflineOutbox.remove` deleted it. The photograph was gone and nothing anywhere
                // said so. The design-workshop path handles the identical case correctly 2,600 lines
                // away (`WorkshopSync.kt`, `noteMediaFailure`) and uses `isFile` rather than
                // `exists()` for a stated reason: a blank or damaged `relativePath` resolves to the
                // DIRECTORY, which exists, so `exists()` answers true for a file that is not one.
                val staged = File(pm.localPath)
                if (!staged.isFile) {
                    landed.add(
                        FileOutcome.Refused(
                            index,
                            "\"${pm.originalFilename}\" is no longer on this device — the copy this app " +
                                "made of it has gone. Nothing can send it now. If the original is still " +
                                "in the gallery, attach it to the record again."
                        )
                    )
                    return@launch
                }
                try {
                    uploadLocalFile(context, pm, target.first, target.second, capturedAt = entry.createdAt)
                    landed.add(FileOutcome.Uploaded(index))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isTransient(e)) throw e
                    landed.add(
                        FileOutcome.Refused(index, "\"${pm.originalFilename}\": ${e.apiErrorMessage("refused by the server.")}")
                    )
                }
            }
        }
    }

    /**
     * Where one queued file attaches once its record exists: the link type and the server id.
     *
     * Null when a process came back with fewer steps than were captured — the caller reports that
     * rather than attaching the capture to the wrong step or dropping it without a word.
     */
    private fun linkTargetFor(entry: PendingEntry, pm: PendingMedia): Pair<String, String>? {
        val recordId = entry.createdId ?: return null
        val stepIndex = pm.stepIndex
        if (entry.type == "process" && stepIndex != null) {
            val stepId = entry.createdStepIds.getOrNull(stepIndex) ?: return null
            return "processstep" to stepId
        }
        // A MEDIA-ONLY ENTRY HAS NO RECORD TYPE OF ITS OWN, so `entry.type` is not a fallback here —
        // it is the string "recordMediaOnly", which no route knows. `queueMediaForSavedRecord` fills
        // `linkedType` on every spec it stages; refusing rather than guessing means a future caller
        // that forgets is told so by one file failing, not by every file being filed under a type the
        // API will reject one at a time.
        if (entry.type == OFFLINE_MEDIA_ONLY) {
            return (pm.linkedType ?: return null) to recordId
        }
        // Media attaches to the created record, or to an overridden link type (e.g. a clip).
        return (pm.linkedType ?: entry.type) to recordId
    }

    /**
     * Will trying this again help?
     *
     * The web outbox's `isTransient` (`frontend/lib/offline.ts`), in Kotlin, plus the two failures a
     * phone has that a browser does not. Being wrong in either direction is expensive: call a
     * permanent failure transient and one bad record blocks the queue for ever; call a transient one
     * permanent and a day's work is parked for a human because a tunnel took the signal away.
     *
     * INTERNAL rather than private because the design-workshop pass reads it too, for every failure
     * shape that is not an HTTP status — no route to host, a socket dropped mid-transfer, a payload
     * that will not parse — and a second implementation of THAT would be a second idea of what
     * "offline" means. The one that was wrong would either strand a fortnight of fieldwork for ever
     * or replay a rejection until somebody reinstalled the app.
     *
     * IT IS NOT THE WHOLE TEST FOR THAT PASS, and the difference is deliberate rather than drift.
     * This function answers "is it worth trying again", which is all a queue needs. The sync pass
     * also STOPS on a yes and puts "the connection dropped" on screen, so it has to ask the narrower
     * question "did the server answer": an answered 5xx is a refusal to record against one stage,
     * not a reason to report lost signal on a phone with four bars and skip every workshop behind
     * it. See `isConnectionFailure` in `data/WorkshopSync.kt`, and `isUnreachable` in
     * `frontend/lib/offline.ts` for the same pair on the web.
     */
    internal fun isTransient(error: Throwable): Boolean = when (error) {
        is HttpException -> when (val code = error.code()) {
            // The credential expired, not the record. Every entry would fail this way and re-signing
            // in fixes all of them at once, so this is the one 4xx that must not condemn an entry.
            401 -> true
            408, 429 -> true
            else -> code >= 500
        }
        // No answer at all: no signal, DNS, a socket dropped mid-transfer, a gateway timeout.
        is IOException -> true
        // The queued payload itself will not parse. The next pass reads the same bytes off the same
        // disk and fails identically, so this is as permanent as a 422.
        is SerializationException -> false
        // Anything else (a presign that came back malformed, an unexpected state) is treated as worth
        // another try: the cost of retrying is a delay, and the cost of not retrying is a lost record.
        else -> true
    }

    /**
     * Say something to the researcher from a sync that has no screen.
     *
     * A repository raising UI is not where this belongs, and it is here because the alternative is
     * silence: `syncOutbox` runs from a timer and a network callback, and the only thing the shell
     * shows is a count of queued entries under the words "uploading when you're online" — which is a
     * lie for an entry the server has refused for good, and which stays a lie for ever. An entry that
     * will never send has to say so at the moment it stops trying. The durable half is
     * [PendingEntry.failure], readable through [outboxFailures] by whatever screen shows it next.
     */
    private suspend fun notifyUser(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Queued entries the server refused, each carrying the reason it will never be sent. */
    suspend fun outboxFailures(context: Context): List<PendingEntry> = OfflineOutbox.failed(context)

    /**
     * An artisan queued by a build that predates the identity fields carries no Pehchan answer at all,
     * and the API refuses a create that claims a card without giving its number. Replaying it as "no
     * card recorded" gets the field capture safely onto the server, where the researcher can correct
     * the answer on the record itself — rather than the entry being parked as permanently rejected
     * for a question the build that captured it never asked.
     */
    private fun withIdentityAnswer(body: ArtisanCreateRequest): ArtisanCreateRequest =
        if (body.pehchanCardAvailable != null) body
        else body.copy(pehchanCardAvailable = body.pehchanCardNumber != null)

    /** What a replayed create produced: the record's server id, plus a process's step ids in order. */
    private data class CreatedRecord(val id: String, val stepIds: List<String> = emptyList())

    /**
     * Replay one entry's WRITE: a create, a correction to a record the server already holds, or —
     * for [OFFLINE_MEDIA_ONLY] — no request at all.
     *
     * ── WHY THE UPDATE BRANCH IS A SEPARATE `when` AND NOT A FLAG INSIDE THE ONE BELOW ────────
     *
     * Because the two are not the same request with a different verb. `updateQuestionnaireInterview`
     * takes a different BODY type from its create, `updateProcess` answers with the whole detail
     * while `createProcess` also mints the step ids, and three of the routes answer with a different
     * DTO. A single `when` trying to hold both would have to spell every type twice anyway, and the
     * one thing that must never happen — an entry meant as a correction being replayed as a create,
     * putting a second copy of an artisan into the register — is easiest to prevent by making the
     * dispatch on [PendingEntry.targetId] the very first decision.
     *
     * ── ⚠ WHAT AN OFFLINE CORRECTION HAS NO PRECONDITION ON: ANYBODY ELSE'S EDIT ──────────────
     *
     * The update branch below sends a WHOLE create-shaped body with no version and no `If-Match`. So a
     * correction composed in a courtyard and drained six hours later overwrites, field for field,
     * every change anyone made to that record in between — and neither person is told.
     *
     * THIS IS A TRADE AND NOT AN OVERSIGHT, and the trade is different for these six types than it is
     * for an interview. `QuestionnaireForm` refuses to queue an edit at all, and its reasoning holds
     * for it: `responses` is a DIFF against what the server held when the form opened, so replaying it
     * late does not overwrite a second interviewer's answers, it DROPS them while appearing to succeed.
     * An artisan or a product is not a diff. It is the whole record as the designer in front of it
     * last saw it, the register is kept by a small team, and refusing the queue would mean losing a
     * day's fieldwork to a bad signal — which is the failure this queue exists to prevent.
     *
     * WHAT MUST NOT HAPPEN IS THE TRADE BEING SILENT, and it was. `offlineSavedMessage` now tells the
     * designer, at the moment they queue it, that their version replaces the whole record and that a
     * colleague's later edit will be lost — which is the only thing available until the queued write
     * can carry the record's version as its precondition, the same precondition the custom
     * questionnaire's write is waiting on. Do not add "last write wins" to any more types without
     * saying so in a sentence a person reads.
     *
     * ── WHAT AN OFFLINE CORRECTION DELIBERATELY DOES NOT CARRY ────────────────────────────────
     *
     * A review status. The bodies these routes take are the same create-request shapes the forms
     * build, and `ApiModels.kt` records that the API "refuses `status`" on an edit and leaves the
     * record's status alone unless `approve` is set — "an edit is not an approval". A queued
     * correction is therefore incapable of moving a record through review, which is the correct and
     * only safe answer for a write that was composed on a device hours or days before it lands.
     */
    private suspend fun writeFromEntry(entry: PendingEntry): CreatedRecord {
        val target = entry.targetId
        // No request, no record, no possibility of one: the files are for a record that already
        // exists. Answered before anything else so no future edit to the dispatch below can reach it.
        if (entry.type == OFFLINE_MEDIA_ONLY) {
            return CreatedRecord(
                target ?: throw IllegalStateException("A media-only outbox entry has no record to attach to")
            )
        }
        if (target == null) return createFromEntry(entry)
        /*
          THE LINK COLUMNS THIS ENTRY'S AUTHOR DELIBERATELY EMPTIED, and ONLY those.

          The online forms pass both workshop columns unconditionally, because a form on screen was
          built by this build and an empty box on it is evidence that the person editing the record
          emptied it (see `patchBodyWithClearances`). A QUEUED correction carries no such guarantee:
          the queue on a handset that has been out of coverage for a fortnight was written by the
          build installed a fortnight ago, and `designWorkshopId` only reached these forms on
          2026-08-28. An old entry is silent about the column because it had never heard of it — not
          because anybody asked for it to be cleared — and reading that silence as a clearance would
          strip a workshop link on a record nobody touched, under a 200, on the drain of a correction
          about something else entirely.

          `clearedLinkKeys` is empty for every entry from every earlier build, so those replay exactly
          as they do today. It is non-empty only where this build wrote down a choice a person made.
        */
        val cleared = entry.clearedLinkKeys
        return when (entry.type) {
            // THE REPOSITORY'S OWN METHODS AND NOT `api.` DIRECTLY, for one of them specifically:
            // `updateArtisan` wraps the body in `artisanPatchBody`, which adds the EXPLICIT nulls
            // that make a cleared column actually clear. Posting the raw request to `api` would
            // reintroduce, on the offline path only, the defect that block of KDoc documents —
            // emptying a birthday or a phone number on the bus home and finding it still there.
            //
            // `withIdentityAnswer` is deliberately NOT applied here. It exists so a create queued by
            // a build that predated the identity questions is not refused for a question it never
            // asked; on a PATCH the same substitution would send a DERIVED `pehchanCardAvailable`
            // over whatever is stored, which on an old entry would answer a question about a card
            // nobody asked about. An edit form seeds the field from the record, so it is already set.
            "artisan" -> CreatedRecord(
                updateArtisan(
                    target,
                    offlineJson.decodeFromString<ArtisanCreateRequest>(entry.payloadJson),
                    cleared,
                ).id
            )
            "product" -> CreatedRecord(
                updateProduct(target, offlineJson.decodeFromString<ProductCreateRequest>(entry.payloadJson), cleared).id
            )
            "tool" -> CreatedRecord(
                updateTool(target, offlineJson.decodeFromString<ToolCreateRequest>(entry.payloadJson), cleared).id
            )
            "workshop" -> CreatedRecord(
                updateWorkshop(target, offlineJson.decodeFromString<WorkshopCreateRequest>(entry.payloadJson), cleared).id
            )
            "craft" -> CreatedRecord(
                updateCraft(target, offlineJson.decodeFromString<CraftCreateRequest>(entry.payloadJson), cleared).id
            )
            // The step ids come back from the UPDATE too, and they have to be read: a queued
            // correction can carry step media, and `linkTargetFor` resolves `stepIndex` against this
            // list. Reading only the id would attach a step photograph to nothing.
            "process" -> updateProcess(target, offlineJson.decodeFromString<ProcessCreateRequest>(entry.payloadJson), cleared)
                .let { detail -> CreatedRecord(detail.id, detail.steps.map { it.id }) }
            // DELIBERATELY ABSENT: "questionnaire". A questionnaire interview's edit route takes
            // `QuestionnaireInterviewUpdateRequest`, whose `responses` and `artisanIds` fields mean
            // "leave alone" when null and "replace" when present — a diff computed against what the
            // server held at the moment the form was opened. Replaying that diff hours later against
            // a record another interviewer may have added answers to would silently drop theirs. The
            // form therefore still refuses to queue an interview edit, and says so; see the note in
            // MainActivity's questionnaire save handler.
            else -> throw IllegalStateException("Unknown offline correction type: ${entry.type}")
        }
    }

    private suspend fun createFromEntry(entry: PendingEntry): CreatedRecord = when (entry.type) {
        "artisan" -> CreatedRecord(
            api.createArtisan(
                withIdentityAnswer(offlineJson.decodeFromString<ArtisanCreateRequest>(entry.payloadJson))
            ).id
        )
        "product" -> CreatedRecord(api.createProduct(offlineJson.decodeFromString<ProductCreateRequest>(entry.payloadJson)).id)
        "tool" -> CreatedRecord(api.createTool(offlineJson.decodeFromString<ToolCreateRequest>(entry.payloadJson)).id)
        "workshop" -> CreatedRecord(api.createWorkshop(offlineJson.decodeFromString<WorkshopCreateRequest>(entry.payloadJson)).id)
        "craft" -> CreatedRecord(api.createCraft(offlineJson.decodeFromString<CraftCreateRequest>(entry.payloadJson)).id)
        "questionnaire" -> CreatedRecord(
            api.createQuestionnaireInterview(
                offlineJson.decodeFromString<QuestionnaireInterviewCreateRequest>(entry.payloadJson)
            ).id
        )
        // The designer's own FORM, not a sitting. See [OFFLINE_CUSTOM_QUESTIONNAIRE] for why this is
        // a second type rather than a branch inside the one above, and for why it carries the row
        // alone.
        OFFLINE_CUSTOM_QUESTIONNAIRE -> CreatedRecord(
            api.createCustomQuestionnaire(
                offlineJson.decodeFromString<CustomQuestionnaireCreateBody>(entry.payloadJson)
            ).id
        )
        // Steps come back in submit order, so `stepIndex` on a queued file selects the matching one.
        "process" -> api.createProcess(offlineJson.decodeFromString<ProcessCreateRequest>(entry.payloadJson))
            .let { detail -> CreatedRecord(detail.id, detail.steps.map { it.id }) }
        // NOT a record — a bookkeeping row about a report this phone already produced. It carries no
        // media, so the replay reaches Synced the moment this returns and the entry leaves the queue.
        // The id is the workshop's rather than a created row's: this route answers with no body, and
        // `createdId` only has to be non-null so a pass interrupted after the POST does not send it
        // twice and put two rows in the office's export log for one delivered file.
        OFFLINE_EXPORT_RECORD -> {
            val queuedExport = offlineJson.decodeFromString<PendingExportRecord>(entry.payloadJson)
            api.recordDesignWorkshopExport(queuedExport.workshopId, queuedExport.body)
            CreatedRecord(queuedExport.workshopId)
        }
        // A JUDGEMENT, NOT A RECORD, and it carries no media — so the replay reaches Synced the
        // moment this returns and the entry leaves the queue.
        //
        // THE ID IS THE RATING ROW'S, and unlike the export above there is a real one to report: the
        // route answers with the stored row whichever of create, amend and replay it resolved to. It
        // matters only for the same narrow reason `createdId` exists at all — a pass interrupted
        // after the POST must not send the body again — and here even that is belt and braces,
        // because the route is idempotent under replay by construction. Nothing downstream reads it:
        // this entry has no media to attach and no second step.
        //
        // THE BODY IS REPLAYED EXACTLY AS IT WAS QUEUED, `ratedAt` INCLUDED. That field is the
        // courtyard moment and re-stamping it here would be the one mistake this whole mechanism
        // exists to prevent: it would date every queued judgement to the drive home, and it would
        // make a stale delivery look newer than the amendment that superseded it — putting back the
        // tunnel regression `rating_plan` documents at length.
        OFFLINE_DESIGN_RATING -> CreatedRecord(
            api.submitDesignRating(
                offlineJson.decodeFromString<DesignRatingBody>(entry.payloadJson)
            ).rating.id
        )
        else -> throw IllegalStateException("Unknown offline entry type: ${entry.type}")
    }

    /**
     * Upload one design-workshop attachment from the durable copy the draft store owns, and RETURN
     * what the server stored it as.
     *
     * The return value is the entire point and is what makes this different from [uploadLocalFile]
     * beside it, which uploads and discards the answer. A design workshop's stage references its
     * photographs by `MediaFile.id`, so the id in the response is the only thing that can be written
     * into the stage payload — and it is also the only evidence that the bytes reached the server at
     * all. [WorkshopSyncEngine] records it against the local [DraftMedia] and never deletes anything
     * on the strength of a request having been sent.
     *
     * The FILE is passed, not a content Uri, and that is deliberate: these bytes already live in
     * `filesDir/workshops/<id>/media/`, put there at capture time precisely so they cannot be
     * revoked with a Uri grant or evicted with the cache. Re-resolving them through the content
     * resolver would reintroduce both risks at the last possible moment.
     */
    suspend fun uploadDesignWorkshopMedia(
        context: Context,
        workshopRemoteId: String,
        file: File,
        originalFilename: String,
        mimeType: String,
        mediaType: String,
        caption: String?,
        recordedAt: String?,
        latitude: Double?,
        longitude: Double?,
        workshopTitle: String?,
        batchIndex: Int = 1
    ): MediaFileDto {
        val filename = mediaFilename(
            recordType = DESIGN_WORKSHOP_MEDIA_TAG,
            recordName = workshopTitle,
            mediaType = mediaType,
            index = batchIndex,
            caption = caption,
            originalName = originalFilename
        )
        val source = UploadSource(size = file.length(), open = { FileInputStream(file) }, cleanup = {})
        val target = uploadBytesToS3(
            context = context,
            filename = filename,
            mimeType = mimeType,
            mediaType = mediaType,
            source = source,
            linkedRecordType = DESIGN_WORKSHOP_MEDIA_TAG,
            linkedRecordId = workshopRemoteId,
            onProgress = null
        )
        val media = completeUpload(
            MediaCompleteRequest(
                originalFilename = filename,
                mediaType = mediaType,
                mimeType = mimeType,
                sizeBytes = file.length(),
                objectKey = target.objectKey,
                bucket = target.bucket,
                url = target.publicUrl,
                caption = caption.blankToNull(),
                linkedRecordType = DESIGN_WORKSHOP_MEDIA_TAG,
                linkedRecordId = workshopRemoteId,
                // The moment of CAPTURE as this device recorded it, not the moment of upload. A
                // photograph taken on day two of a fortnight and uploaded on the bus home on day
                // fourteen must be dated day two, or the report's chronology becomes the chronology
                // of when the signal came back.
                recordedAt = recordedAt ?: Instant.now().toString(),
                location = if (latitude != null && longitude != null) {
                    LocationRequest(latitude = latitude, longitude = longitude)
                } else {
                    null
                },
                // An artisan explaining a technique into the phone is an interview recording like
                // any other. The stage save queues stage audio for transcription server-side too
                // (`enqueue_stage_transcriptions`), and both paths are idempotent — a clip that
                // already has a job is skipped — so asking here costs nothing and covers the case
                // where the stage is held back behind another file.
                processingRequests = if (mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList()
            ),
            target.checksum
        )
        // The object now belongs to a record, so it is no longer an orphan the next launch should
        // reclaim. Dropped only after `/media/complete` returned — a sweep that ran between the PUT
        // and the complete would otherwise delete the bytes out from under the row.
        StagedJournal.drop(target.objectKey)
        return media
    }

    /**
     * Upload one queued attachment from the copy the outbox owns.
     *
     * @param capturedAt WHEN THE SITTING HAPPENED, not when the signal came back. Pass
     *   [PendingEntry.createdAt]. This used to be `Instant.now()`, computed inside this function, so
     *   every photograph on every record queued offline — the primary field path — was dated the
     *   moment it finally uploaded. Forty lines above, [uploadDesignWorkshopMedia] gets this right
     *   and states why: "A photograph taken on day two of a fortnight and uploaded on the bus home on
     *   day fourteen must be dated day two, or the report's chronology becomes the chronology of when
     *   the signal came back." The same rule is restated in `WorkshopSync.kt`. There was never a
     *   reason for the two paths to disagree — the entry's own timestamp was already in scope at the
     *   only call site.
     *
     *   Nullable so an entry written by a build with no such stamp still uploads rather than being
     *   refused; the fallback is the old behaviour, which is wrong but is not a lost file.
     */
    private suspend fun uploadLocalFile(
        context: Context,
        pm: PendingMedia,
        linkedRecordType: String,
        linkedRecordId: String,
        capturedAt: String? = null,
    ) {
        val file = File(pm.localPath)
        // A HARD FAILURE, and no longer a silent success. The caller checks `isFile` first and
        // reports a named refusal; reaching here means the file vanished between that check and this
        // line, and throwing is what keeps the index from being ticked off as uploaded.
        if (!file.isFile) {
            throw IllegalStateException("The stored copy of \"${pm.originalFilename}\" is missing.")
        }
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = pm.recordName,
            mediaType = pm.mediaType,
            index = pm.batchIndex,
            stageStep = pm.stageStep,
            customSegment = pm.customSegment,
            caption = pm.caption,
            overrideBaseName = pm.overrideBaseName,
            originalName = pm.originalFilename
        )
        val source = UploadSource(size = file.length(), open = { FileInputStream(file) }, cleanup = {})
        val target = uploadBytesToS3(
            context = context,
            filename = filename,
            mimeType = pm.mimeType,
            mediaType = pm.mediaType,
            source = source,
            linkedRecordType = linkedRecordType,
            linkedRecordId = linkedRecordId,
            onProgress = null
        )
        completeUpload(
            pendingMediaCompleteRequest(
                pm = pm,
                filename = filename,
                linkedRecordType = linkedRecordType,
                linkedRecordId = linkedRecordId,
                objectKey = target.objectKey,
                bucket = target.bucket,
                publicUrl = target.publicUrl,
                sizeBytes = file.length(),
                recordedAt = capturedAt ?: Instant.now().toString(),
            ),
            target.checksum
        )
        StagedJournal.drop(target.objectKey)
    }

    /**
     * Analyse a grid-sheet photo for one dimension (length/breadth/height) and return the estimated
     * inches, or null if the model couldn't read it. A grid photo is small, so reading it into memory
     * is fine. Used by the "Document using grid" capture to OFFER a reading for the field.
     *
     * ── WHY THIS RETURNS A PAIR AND NOT A `Double?` — 2026-08-27 ──────────────────────────────
     *
     * It used to return the bare estimate, and the sentence above used to end "to auto-fill the
     * measurement field". Both changed for the same reason: a number this endpoint produced is a
     * MODEL'S ESTIMATE, and the row it lands in is stamped with the name of whoever pressed Save. So
     * the caller needs the [AnalyzeMeasurementResponse.methodMarker] that came back with it, or the
     * record asserts a named human measured something Gemini guessed. Returning the value alone made
     * that marker unreachable at the only call site that could send it.
     */
    suspend fun analyzeMeasurement(context: Context, uri: Uri, dimension: String): DwMeasurementReading {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open the captured image")
        }
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "grid-${dimension}.jpg",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        val response = api.analyzeMeasurement(part, dimension)
        return DwMeasurementReading(
            lengthInches = null,
            breadthInches = null,
            valueInches = response.analysis?.valueInches,
            marker = response.methodMarker,
        )
    }

    /**
     * Analyse a single grid-sheet photo for BOTH length and breadth at once (the object's footprint
     * on the grid). Calls the measurement endpoint with no dimension, which returns the legacy
     * length+breadth pair. Either value may be null if unread.
     *
     * ONE MARKER FOR BOTH NUMBERS, and that is correct rather than a shortcut: they came out of a
     * single inference over a single photograph, so the provider, the model id and the model's own
     * confidence are the same fact about both. See [analyzeMeasurement] for why the marker travels.
     */
    suspend fun analyzeMeasurementLengthBreadth(context: Context, uri: Uri): DwMeasurementReading {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open the captured image")
        }
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "grid-length-breadth.jpg",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        val response = api.analyzeMeasurement(part, null)
        val analysis = response.analysis
        return DwMeasurementReading(
            lengthInches = analysis?.lengthInches,
            breadthInches = analysis?.breadthInches,
            valueInches = null,
            marker = response.methodMarker,
        )
    }

    /**
     * Run an OkHttp call so that cancelling the coroutine actually stops the transfer.
     *
     * `execute()` blocks a thread nothing can interrupt. Cancel the coroutine around it and the
     * socket keeps pushing bytes until OkHttp's own call timeout — twelve minutes, on the field
     * connection that is already the scarce resource. `enqueue` gives the cancellation somewhere to
     * land, and `call.cancel()` closes the socket at once.
     */
    private suspend fun executeCancellable(call: Call): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // Cancelled while the answer was in flight: nobody downstream will reach the
                    // `use` that closes this, so close it here rather than leak the connection.
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    /**
     * PUT bytes to object storage with bounded retries and byte-level progress. Transient failures
     * (network drop, or a 5xx from S3 under concurrent load) are retried with linear backoff so a
     * single hiccup never loses an upload; a 4xx (bad signature etc.) fails fast. This is what makes
     * many files — and many researchers uploading at once — resilient.
     *
     * SUSPENDING AND CANCELLABLE, deliberately. This was a blocking function that slept between
     * attempts with `Thread.sleep`, so a transfer could not be stopped at all: neither the socket nor
     * the backoff could hear a cancellation. That is what made a parallel upload batch unable to give
     * up — when one file failed, its two siblings were cancelled on paper and went on transferring
     * for real, over the connection that had just been shown to be broken.
     */
    private suspend fun putToStorage(
        uploadUrl: String,
        headers: Map<String, String>,
        contentLength: Long,
        mimeType: String,
        openStream: () -> InputStream,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
        digest: ContentDigest? = null
    ) {
        val maxAttempts = 3
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            // A cancelled call surfaces as a plain IOException, which the catch below would retry.
            // Checked here so a cancellation ends the loop as a cancellation rather than as a
            // transport failure the caller would then queue for another try.
            currentCoroutineContext().ensureActive()
            try {
                // A fresh stream per attempt so a retry re-reads from the start.
                val body = StreamingRequestBody(contentLength, mimeType.toMediaType(), openStream, onProgress, digest)
                val builder = Request.Builder().url(uploadUrl).put(body)
                headers.forEach { (name, value) -> builder.header(name, value) }
                executeCancellable(storageClient.newCall(builder.build())).use { response ->
                    if (response.isSuccessful) return
                    // Client errors (4xx) won't fix themselves — fail immediately.
                    if (response.code < 500) {
                        throw IllegalStateException("Object storage upload failed: HTTP ${response.code}")
                    }
                    lastError = IllegalStateException("Object storage upload failed: HTTP ${response.code}")
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < maxAttempts) delay(800L * attempt)
        }
        throw lastError ?: IllegalStateException("Object storage upload failed")
    }

    /** A re-openable upload source: exact byte size, a fresh stream per attempt, and cleanup. */
    private class UploadSource(val size: Long, val open: () -> InputStream, val cleanup: () -> Unit)

    /** Content-provider SIZE column, or 0 if unknown. */
    private fun queryContentSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /**
     * Build an [UploadSource] that streams from disk, not memory. When the provider exposes a SIZE we
     * stream straight from the content Uri (re-opened per retry). When it doesn't, we spool the bytes
     * to a temp cache file (streamed copy, never a giant in-memory array) to learn the exact length,
     * then stream from that file. Either way the heap never holds the whole video.
     */
    private fun resolveUploadSource(context: Context, uri: Uri): UploadSource {
        val size = queryContentSize(context, uri)
        if (size > 0L) {
            return UploadSource(
                size = size,
                open = {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Unable to open selected media")
                },
                cleanup = {}
            )
        }
        val temp = File.createTempFile("upload-", ".bin", context.cacheDir)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { out -> input.copyTo(out, 64 * 1024) }
            } ?: throw IllegalStateException("Unable to open selected media")
        }.onFailure { temp.delete(); throw it }
        return UploadSource(
            size = temp.length(),
            open = { FileInputStream(temp) },
            cleanup = { runCatching { temp.delete() } }
        )
    }

    /** OkHttp body that streams an InputStream in 64 KB chunks, reporting cumulative bytes written. */
    private class StreamingRequestBody(
        private val length: Long,
        private val contentType: MediaType?,
        private val openStream: () -> InputStream,
        private val onProgress: ((sent: Long, total: Long) -> Unit)?,
        private val digest: ContentDigest? = null
    ) : RequestBody() {
        override fun contentType(): MediaType? = contentType
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            // Every write of this body re-sends the whole file from the start (a retry, or OkHttp
            // re-issuing the request), so the hash has to start over with it.
            digest?.reset()
            openStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    digest?.update(buffer, read)
                    sent += read
                    onProgress?.invoke(sent, length)
                }
            }
        }
    }

    /**
     * SHA-256 of a file's content, fed from the bytes on their way to the socket so hashing a 400 MB
     * video costs no second read of it. Sent on `/media/complete` as `sha256:<hex>` (the same shape
     * the web sends) so a transfer that silently corrupted the file is detectable afterwards, and so
     * identical bytes are recognisable. Nothing verifies it at upload time.
     */
    private class ContentDigest {
        private val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun reset() = digest.reset()
        fun update(bytes: ByteArray, length: Int) = digest.update(bytes, 0, length)
        /** Terminal — reading the hash resets the digest, so call this once, after the last byte. */
        fun hex(): String = "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
        }
        return uri.lastPathSegment
    }

    private fun inferMediaType(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "IMAGE"
        mimeType.startsWith("video/") -> "VIDEO"
        mimeType.startsWith("audio/") -> "AUDIO"
        mimeType == "application/pdf" -> "PDF"
        else -> "DOCUMENT"
    }

}

private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The `/media/complete` body for one file replayed out of the offline outbox.
 *
 * SPLIT OUT OF [WorkshopRepository.uploadLocalFile] SO IT CAN BE ASSERTED WITHOUT A NETWORK. Every
 * other property of this request has a live analogue in `uploadMedia` that a person would notice
 * going wrong; [PendingMedia.purpose] does not. Nothing errors when the marker stops being sent — no
 * build breaks, no request fails, no log line appears — the .docx handed to a Development
 * Commissioner's office simply starts printing a sheet of graph paper captioned as the tool again.
 * A function returning a request is something a unit test can hold; the eight lines inside a private
 * suspend function that also does a multipart PUT is not, which is why the queued path shipped
 * without the marker in the first place. See `MeasurementGridMarkerTest`.
 *
 * THE TRANSCRIPTION DEFAULT IS PART OF THE BODY AND SO LIVES HERE TOO: an artisan explaining a
 * technique into the phone is an interview recording like any other, and the queued path must ask for
 * the same processing the live one asks for or a fortnight offline costs a fortnight of transcripts.
 * An explicit [PendingMedia.processing] wins, because a caller that named the list meant it.
 */
internal fun pendingMediaCompleteRequest(
    pm: PendingMedia,
    filename: String,
    linkedRecordType: String,
    linkedRecordId: String,
    objectKey: String,
    bucket: String?,
    publicUrl: String?,
    sizeBytes: Long,
    recordedAt: String,
): MediaCompleteRequest = MediaCompleteRequest(
    originalFilename = filename,
    mediaType = pm.mediaType,
    mimeType = pm.mimeType,
    sizeBytes = sizeBytes,
    objectKey = objectKey,
    bucket = bucket,
    url = publicUrl,
    caption = pm.caption.blankToNull(),
    linkedRecordType = linkedRecordType,
    linkedRecordId = linkedRecordId,
    recordedAt = recordedAt,
    processingRequests = pm.processing
        ?: if (pm.mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList(),
    // THE MARKER THE FORM WROTE, CARRIED ACROSS THE OUTBOX — see [PendingMedia.purpose].
    //
    // Nothing else on this path can tell the server what the file is. The filename is built by
    // `mediaFilename(...)` and never starts `grid-`/`measure-grid-`, and the caption is "Field media
    // for X", so NEITHER of the server's transitional clauses matches a queued grid shot: without
    // this the graph paper is still the oldest image on the record and still wins
    // `createdAt ASC, id ASC`. Null for every other queued file, and `ApiClient.json` leaves
    // `encodeDefaults` false, so those bodies are byte-identical to what they always were.
    extraMetadata = mediaPurposeMetadata(pm.purpose),
)

/**
 * Comma-join a list-shaped query parameter, the way every route in the shared filter vocabulary reads
 * one — or null when there is nothing to send.
 *
 * AN EMPTY LIST MUST BECOME NULL, not "". Absent means "every workshop / every bucket"; a blank
 * string is one blank id, which matches nothing, so the two differ by the entire result set. Blanks
 * and duplicates are dropped here rather than at the interface, which stays a plain description of
 * the wire (house rule), and order is preserved so the query string a screen sends is stable across
 * recompositions instead of depending on tick order.
 *
 * Case is left ALONE: workshop ids are case-sensitive cuids and the reserved sentinel `none` is
 * already lowercase. Callers that need a lowercased vocabulary (the `types` buckets) lowercase
 * before calling.
 */
private fun List<String>?.toQueryCsv(): String? =
    this?.mapNotNull { it.blankToNull() }?.distinct()?.takeIf { it.isNotEmpty() }?.joinToString(",")

// ---------------------------------------------------------------------------
// The name a captured file is uploaded under.
//
//     {RecordType}-{RecordName}-{Descriptor}-{ddMMyyyyHHmm}.{ext}
//
// It used to be `K_1_RASHPALSINGHJAMMUKASHMIRBAMBOOSECTIONKL_000137_010720261728.m4a`: every word
// run together, every part a code, and the whole thing legible only to the screen that wrote it. A
// researcher works from a zip extracted onto a laptop, where the folder that carried the meaning is
// gone, so the name has to answer on its own what kind of record this hangs off, which one, what
// the file is, and when it was taken.
//
// This is the capture-time half of backend/app/services/media_naming.py, which re-derives the same
// name from the row whenever the repository is browsed, exported or downloaded. The two have to
// agree: where they disagree a researcher sees one file under two names with nothing to tell them
// it is one file. Every rule below — the character deny list, the two length limits, the descriptor
// vocabulary, which part gets truncated — is that module's, in Kotlin.
// ---------------------------------------------------------------------------

/**
 * The two path separators plus the punctuation Windows reserves: the only characters a filesystem or
 * a zip genuinely cannot carry.
 *
 * The rule is a DENY list rather than an allow list, and that inversion is the point of this whole
 * change. The old `[^A-Za-z0-9]` allow list emptied every Devanagari name it touched, so a row of
 * artisans collapsed onto one indistinguishable filename — and in a repository whose subject is
 * Indian craft, the names are the data.
 */
private val NAME_UNSAFE_CHARS: Set<Int> = "<>:\"/\\|?*".map { it.code }.toSet()

/**
 * Cc control characters; Cf invisible format characters, which include the bidi overrides that
 * render a filename back to front; Cs lone surrogates, which no encoder will take.
 */
private val NAME_UNSAFE_CATEGORIES = setOf(
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.SURROGATE.toInt()
)

/**
 * …except these two, which are Cf but load-bearing: in Devanagari and the other Indic scripts they
 * select conjunct and half forms, so dropping them misspells the very names this scheme exists to
 * keep.
 */
private const val ZERO_WIDTH_NON_JOINER = '\u200C'.code
private const val ZERO_WIDTH_JOINER = '\u200D'.code

/**
 * Combining marks are not letters or digits to the JDK, but they are half of the syllable they sit
 * on, so a word splitter that treats "not alphanumeric" as a boundary shatters every Indic syllable.
 */
private val NAME_MARK_CATEGORIES = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt()
)

/** Numerals the JDK does not count as digits (Nl/No) but Python's `isalnum` does — kept so the two
 *  halves of the scheme break words at the same places. */
private val NAME_NUMBER_CATEGORIES = setOf(
    Character.LETTER_NUMBER.toInt(),
    Character.OTHER_NUMBER.toInt()
)

/** Windows refuses these device names in any case, with or without an extension. */
private val NAME_RESERVED: Set<String> = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    (1..9).forEach { add("COM$it"); add("LPT$it") }
}

/**
 * The whole leaf, in characters AND in bytes. A filesystem caps a name at ~255 BYTES while a slice
 * counts characters, and one Devanagari character costs three of them, so a name that passes the
 * character check can still be rejected on write. 200 bytes matches the backend's budget and leaves
 * room under the ceiling for the `-2` a duplicate name picks up on the way out of an export.
 */
private const val MAX_NAME_CHARS = 150
private const val MAX_NAME_BYTES = 200

/** Enough of a record name to identify it; the rest of the budget belongs to the descriptor. */
private const val MAX_RECORD_NAME_CHARS = 60

/** A step name is free text a researcher typed and runs to a sentence more often than not. */
private const val MAX_STEP_NAME_CHARS = 32

/** What each kind of file is called in a name. Plain words — never the old IMG/VID/AUD codes. */
private val NAME_KIND_WORD = mapOf(
    "IMAGE" to "Photo",
    "VIDEO" to "Video",
    "AUDIO" to "Audio-Note",
    "PDF" to "Document",
    "DOCUMENT" to "Document"
)

/**
 * `linkedRecordType` -> the word that opens the name.
 *
 * A questionnaire clip is deliberately absent. The record such a clip is really about is the artisan
 * being interviewed, and at capture time this layer holds only the interview's TITLE — free text
 * from a field labelled "Interview title", which is routinely "Rashpal Singh Jammu Kashmir Bamboo
 * Section KL" rather than anyone's name. Opening that with "Artisan-" would state something false,
 * so the head word is left out and the descriptor's own "Interview-…" says what kind of record it
 * is. The backend, which can see the interview's artisans, fills the head in when it re-derives.
 */
private val NAME_RECORD_TYPE_WORD = mapOf(
    "artisan" to "Artisan",
    "product" to "Product",
    "tool" to "Tool",
    "process" to "Process",
    "processstep" to "Process",
    "workshop" to "Workshop",
    "craft" to "Craft",
    // The 22-stage record's own attachments. Without this entry the lookup misses and the uploaded
    // file is named from its caption alone — so a fortnight of stage photographs arrive in the
    // repository as "Photo-1-…", "Photo-2-…", indistinguishable from every other record's.
    "designworkshop" to "Design Workshop"
)

/** "Question audio: K1 What types of waste …" — section letter then question number. */
private val CAPTION_QUESTION = Regex("""^question\s+audio:\s*([A-Za-z]{1,3})\s*(\d+)\b""", RegexOption.IGNORE_CASE)

/** "Section audio: D RAW MATERIALS …" — a recording covering a whole section, answering no one question. */
private val CAPTION_SECTION = Regex("""^section\s+audio:\s*([A-Za-z]{1,3})\b""", RegexOption.IGNORE_CASE)

/** "Process step Dyeing" — the only place the step's NAME reaches this layer. */
private val CAPTION_STEP = Regex("""^process\s+step\s+(.+)$""", RegexOption.IGNORE_CASE)

private val CAPTION_PRE_PROCESS = Regex("""^pre-process\s+media\b""", RegexOption.IGNORE_CASE)

/** Every capture screen ends its caption with the record's name; this is the fallback for a call
 *  site that passed no title of its own. */
private val CAPTION_RECORD_NAME = Regex(
    """^(?:field media|pre-process media|process stage step \d+|measurement grid image)\s+for\s+(.+)$""",
    RegexOption.IGNORE_CASE
)

/** The process-step segment the form mints: `STEP_1A` (sequential) or `STEP_2_G1` (group). */
private val SEGMENT_STEP = Regex("""^STEP[_-](\d+)""", RegexOption.IGNORE_CASE)

/** `DabuHandBlockPrinting` -> `Dabu Hand Block Printing`: the one place this scheme has to invent
 *  word boundaries, and only for a name no caller supplied. */
private val CAMEL_BOUNDARY = Regex("""(?<=[a-z0-9])(?=[A-Z])""")

private val REPEATED_HYPHEN = Regex("-{2,}")
private val NON_LETTERS = Regex("[^a-z]")

/**
 * The pieces of one questionnaire clip's base name, as the interview screen minted it:
 * `{SECTION}_{QUESTION}_{NAME}_{DURATIONHHMMSS}_{STAMPDDMMYYYYHHMM}` with an optional trailing clip
 * number. Only the stamp and the clip number cannot be recovered from anywhere else, which is why
 * that base name is still parsed rather than ignored.
 */
private data class QuestionnaireClip(
    val section: String?,
    val answer: Int?,
    val name: String,
    val stamp: String,
    val clip: Int?
)

/**
 * `{RecordType}-{RecordName}-{Descriptor}-{ddMMyyyyHHmm}.{ext}` for one file about to be uploaded.
 *
 *     Artisan-Giriraj-Prasad-Chhipa-Photo-2-010720261824.jpg
 *     Product-Bagru-Block-Print-Video-1-200620261153.mp4
 *     Tool-Ringal-Splitting-Knife-Grid-Measurement-Height-200620261200.jpg
 *     Process-Dabu-Printing-Step-2-Dyeing-Video-1-210620261430.mp4
 *
 * [overrideBaseName] is the interview screen's own clip name; it is read for the section, the
 * question and the moment of capture, then re-spelled in the same words as everything else instead
 * of being passed through as `K_1_…`.
 */
private fun mediaFilename(
    recordType: String?,
    recordName: String?,
    mediaType: String,
    index: Int,
    stageStep: Int? = null,
    customSegment: String? = null,
    caption: String? = null,
    overrideBaseName: String? = null,
    originalName: String
): String {
    val extension = nameSafeChars(originalName.substringAfterLast('.', ""))
        .takeIf { it.isNotBlank() }
        ?.let { ".$it" }
        .orEmpty()

    val base = overrideBaseName.blankToNull()
    val clip = base?.let(::parseQuestionnaireClip)
    // A caller-supplied base that is not the interview shape is that caller's business, not this
    // function's to re-interpret; it is only made safe and hyphenated.
    if (base != null && clip == null) return literalBaseName(base, extension)

    val descriptor = if (clip != null) {
        interviewClipDescriptor(clip, caption, mediaType, index)
    } else {
        mediaDescriptor(recordType, mediaType, index, stageStep, customSegment, caption, originalName)
    }

    val supplied = recordName.blankToNull()
        ?: captionRecordName(caption)
        ?: clip?.name
        ?: splitCamel(originalName.substringBeforeLast('.'))
    val stem = assembleName(
        recordType = hyphenate(NAME_RECORD_TYPE_WORD[recordType?.trim()?.lowercase()]),
        recordName = hyphenate(trimRedundantTail(supplied, descriptor)),
        descriptor = descriptor,
        stamp = clip?.stamp ?: captureStamp(),
        extension = extension
    )
    return stem.ifBlank { nameSafeChars(originalName).trim(' ', '.').ifBlank { "file" } }
}

/**
 * ddMMyyyyHHmm, in the device's own zone. Twelve digits, never fourteen.
 *
 * The moment the phone captured the file, which is what the researcher saw on screen — not the
 * moment the row reached the server, which lands a beat later and shifts a name by a minute. The
 * backend reads this stamp straight back off the uploaded name and cuts the seconds off the older
 * uploads that carry them, so every file in the repository is stamped to the same precision. Where
 * that puts two files of one minute on one name, the export numbers the later one `-2`; a second
 * this app never recorded is never invented to keep them apart.
 */
private fun captureStamp(): String =
    java.text.SimpleDateFormat("ddMMyyyyHHmm", java.util.Locale.US).format(java.util.Date())

/** Drop only the characters a filesystem or a zip genuinely cannot carry, in any script. */
private fun nameSafeChars(value: String?): String {
    val text = value ?: return ""
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val code = text.codePointAt(i)
        i += Character.charCount(code)
        val category = Character.getType(code)
        val keep = code !in NAME_UNSAFE_CHARS &&
            (category !in NAME_UNSAFE_CATEGORIES || code == ZERO_WIDTH_JOINER || code == ZERO_WIDTH_NON_JOINER)
        if (keep) out.appendCodePoint(code)
    }
    return out.toString()
}

/**
 * Words joined by hyphens, with every script intact.
 *
 * Anything that is not a letter, a digit or a combining mark is a boundary, so "Cane, Bamboo and
 * Block Printing" reads "Cane-Bamboo-and-Block-Printing" and "गिरीराज प्रसाद छीपा" keeps its
 * characters and simply gains hyphens between its words.
 */
private fun hyphenate(value: String?): String {
    val text = nameSafeChars(value)
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val code = text.codePointAt(i)
        i += Character.charCount(code)
        val category = Character.getType(code)
        val isWordChar = Character.isLetterOrDigit(code) ||
            category in NAME_NUMBER_CATEGORIES ||
            category in NAME_MARK_CATEGORIES ||
            code == ZERO_WIDTH_JOINER ||
            code == ZERO_WIDTH_NON_JOINER
        if (isWordChar) out.appendCodePoint(code) else out.append('-')
    }
    return out.toString().replace(REPEATED_HYPHEN, "-").trim('-')
}

/** Trim to both limits at once, never mid-character and never mid-surrogate-pair. */
private fun clipName(value: String, maxChars: Int, maxBytes: Int): String {
    var out = if (value.codePointCount(0, value.length) > maxChars) {
        value.substring(0, value.offsetByCodePoints(0, maxChars))
    } else {
        value
    }
    while (out.isNotEmpty() && out.toByteArray(Charsets.UTF_8).size > maxBytes) {
        out = out.substring(0, out.offsetByCodePoints(out.length, -1))
    }
    return out
}

/**
 * [clipName], but cutting back to a whole word when it has to cut at all.
 *
 * The text is already hyphenated, so a blind slice leaves "Cane-Bamboo-an" — a fragment that reads
 * as a word the record does not contain. Dropping the partial word says less and says nothing
 * false. A single very long word has no boundary to retreat to, and there the blind cut stands.
 */
private fun clipWords(value: String, maxChars: Int, maxBytes: Int): String {
    val clipped = clipName(value, maxChars, maxBytes)
    if (clipped == value) return value
    val head = clipped.substringBeforeLast('-', "")
    return (if (head.isNotEmpty()) head else clipped).trim('-')
}

private fun splitCamel(value: String?): String = CAMEL_BOUNDARY.replace(value.orEmpty(), " ")

/** Only lowercase letters, so `GRID_HEIGHT`, `grid-height-9.jpg` and "Height grid" all compare alike. */
private fun nameLetters(value: String?): String = value.orEmpty().lowercase().replace(NON_LETTERS, "")

/**
 * Join the pieces, spending the byte budget on the record name and nothing else.
 *
 * The descriptor and the timestamp are what tell one artisan's forty clips apart, so they are never
 * trimmed. The record name absorbs the whole shortfall, and if the tail alone has eaten the budget
 * the head goes entirely — leaving a name that says less about which record this belongs to but
 * still says exactly which file it is.
 */
private fun assembleName(
    recordType: String,
    recordName: String,
    descriptor: String,
    stamp: String,
    extension: String
): String {
    val tail = listOf(descriptor, stamp).filter { it.isNotEmpty() }.joinToString("-")
    val budget = MAX_NAME_BYTES - "-$tail$extension".toByteArray(Charsets.UTF_8).size
    val head = if (budget <= 0) {
        ""
    } else {
        // Re-clip the pair: a long type word plus a short name can still overrun.
        clipWords(
            listOf(recordType, clipWords(recordName, MAX_RECORD_NAME_CHARS, budget))
                .filter { it.isNotEmpty() }
                .joinToString("-"),
            MAX_NAME_CHARS,
            budget
        )
    }

    var stem = listOf(head, tail).filter { it.isNotEmpty() }.joinToString("-")
        .replace(REPEATED_HYPHEN, "-")
        .trim('-', ' ', '.')
    if (stem.isEmpty()) return ""
    if (stem.substringBefore('.').uppercase() in NAME_RESERVED) stem = "${stem}_"
    val room = MAX_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size
    return clipName(stem, MAX_NAME_CHARS, room).trim('-', ' ', '.') + extension
}

/** What this file IS, in words: the half of the name that disambiguates one photo from the next. */
private fun mediaDescriptor(
    recordType: String?,
    mediaType: String,
    index: Int,
    stageStep: Int?,
    customSegment: String?,
    caption: String?,
    originalName: String
): String {
    val kind = "${nameKindWord(mediaType)}-$index"
    val tag = recordType?.trim()?.lowercase().orEmpty()

    if (tag == "questionnaire" || tag == "questionnaireinterview") {
        val (section, answer) = interviewSectionAnswer(caption) ?: return "Interview-$kind"
        return "Interview-Section-$section" + (answer?.let { "-Answer-$it" }).orEmpty()
    }

    gridDescriptor(customSegment, caption, originalName)?.let { return it }

    // `stageStep` is the tool form's numbered process stage; `customSegment` is the process form's
    // `STEP_1A` / `STEP_2_G1`, whose trailing letter distinguishes files within a step and is what
    // the file index already says.
    val step = stageStep ?: SEGMENT_STEP.find(customSegment.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    if (step != null || tag == "processstep") {
        val stepName = clipWords(
            hyphenate(CAPTION_STEP.find(caption.orEmpty().trim())?.groupValues?.get(1)),
            MAX_STEP_NAME_CHARS,
            MAX_STEP_NAME_CHARS * 3
        )
        return listOf(step?.let { "Step-$it" } ?: "Step", stepName, kind)
            .filter { it.isNotEmpty() }
            .joinToString("-")
    }

    val isPreProcess = customSegment?.trim().equals("PRE", ignoreCase = true) ||
        CAPTION_PRE_PROCESS.containsMatchIn(caption.orEmpty().trim())
    if (isPreProcess) return "Pre-Process-$kind"

    return kind
}

/**
 * `Grid-Measurement-Height` and friends, when this image is a measurement grid.
 *
 * The axis is only stated when something actually says which one it is: the segment the older grid
 * flow tagged, or the name the capture screen gave the file on disk, or the caption. A grid photo
 * that names no axis gets the bare `Grid-Measurement` rather than a guess that would be wrong half
 * the time.
 */
private fun gridDescriptor(customSegment: String?, caption: String?, originalName: String): String? {
    val segment = nameLetters(customSegment)
    val file = originalName.substringBeforeLast('.').lowercase()
    val head = nameLetters(caption)
    if (segment.contains("gridlengthbreadth") ||
        file.startsWith("grid-lengthbreadth") ||
        head.startsWith("lengthbreadthgrid")
    ) {
        return "Grid-Measurement-Length-Breadth"
    }
    if (segment.contains("gridheight") || file.startsWith("grid-height") || head.startsWith("heightgrid")) {
        return "Grid-Measurement-Height"
    }
    if (segment.contains("measurementgrid") ||
        file.startsWith("measure-grid") ||
        head.startsWith("measurementgrid")
    ) {
        return "Grid-Measurement"
    }
    return null
}

/** `Interview-Section-K-Answer-1`, or as much of it as the caption can prove. */
private fun interviewSectionAnswer(caption: String?): Pair<String, Int?>? {
    val text = caption.orEmpty().trim()
    CAPTION_QUESTION.find(text)?.let { return it.groupValues[1].uppercase() to it.groupValues[2].toIntOrNull() }
    CAPTION_SECTION.find(text)?.let { return it.groupValues[1].uppercase() to null }
    return null
}

/**
 * The descriptor for one interview clip.
 *
 * A recording that covers a whole section is not an answer to any one question, so it stops at
 * `Interview-Section-D` instead of claiming an answer number it does not have. `-Clip-2` is
 * appended only when the same target really was recorded more than once in the same save; without
 * it two takes of one answer, a few seconds apart, would land on the same name.
 */
private fun interviewClipDescriptor(
    clip: QuestionnaireClip,
    caption: String?,
    mediaType: String,
    index: Int
): String {
    val (section, answer) = interviewSectionAnswer(caption) ?: (clip.section to clip.answer)
    if (section == null) return "Interview-${nameKindWord(mediaType)}-$index"
    return buildString {
        append("Interview-Section-").append(section)
        if (answer != null) append("-Answer-").append(answer)
        if (clip.clip != null) append("-Clip-").append(clip.clip)
    }
}

private fun nameKindWord(mediaType: String): String = NAME_KIND_WORD[mediaType.uppercase()] ?: "File"

private fun parseQuestionnaireClip(base: String): QuestionnaireClip? {
    val tokens = base.trim().split('_')
    if (tokens.size < 5) return null
    if (tokens[3].length != 6 || !tokens[3].all(Char::isDigit)) return null
    val stamp = tokens[4].takeIf { it.length == 12 && it.all(Char::isDigit) } ?: return null
    // Both slots fall back to the literal "SEC" when the screen had no code to put there, and
    // "Section-SEC" would be exactly the sort of code this scheme exists to stop emitting.
    val section = tokens[0].takeIf { it.isNotBlank() && !it.equals("SEC", ignoreCase = true) }
    return QuestionnaireClip(
        section = section,
        answer = tokens[1].toIntOrNull(),
        name = tokens[2],
        stamp = stamp,
        clip = tokens.getOrNull(5)?.toIntOrNull()?.takeIf { it > 1 }
    )
}

/** The record's name out of a capture screen's caption, for a call site that passed no title. */
private fun captionRecordName(caption: String?): String? =
    CAPTION_RECORD_NAME.find(caption.orEmpty().trim())?.groupValues?.get(1)?.blankToNull()

/**
 * Drop a tail the descriptor is about to repeat.
 *
 * The measurement screen hands down "Ringal splitting knife measurement grid" as the record name,
 * which followed by `Grid-Measurement-Height` says "grid" twice and "measurement" twice. The record
 * is the knife; the descriptor is what the photo is of.
 */
private fun trimRedundantTail(recordName: String, descriptor: String): String {
    if (!descriptor.startsWith("Grid-Measurement")) return recordName
    val trimmed = recordName.trim()
    if (!nameLetters(trimmed).endsWith("measurementgrid")) return trimmed
    val cut = trimmed.lowercase().lastIndexOf("measurement")
    // A record genuinely called nothing but "measurement grid" keeps its name; there is nothing else
    // left to call it.
    return if (cut > 0) trimmed.substring(0, cut).trim() else trimmed
}

/** A caller-supplied base name this scheme cannot read: made safe, hyphenated, left alone otherwise. */
private fun literalBaseName(base: String, extension: String): String {
    val room = MAX_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size
    return clipWords(hyphenate(base), MAX_NAME_CHARS, room).ifBlank { "Recording" } + extension
}
