package com.designprototype.workshop.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Forward geocoding — typing "Barpali" and being taken there. The Kotlin port of
 * `frontend/lib/placeSearch.ts`, pinned to it by value in `PlaceSearchParityTest`.
 *
 * WHAT IT IS FOR. [com.designprototype.workshop.ui.MapPickerDialog] opens on the centre of India at
 * zoom 4 whenever the record has no coordinate yet, which is every new record. A designer
 * documenting a cluster in rural Odisha then has to pan and pinch roughly two thousand kilometres by
 * fingertip to reach a village they could have named in eight keystrokes, dragging billable map
 * tiles the whole way down a connection this whole feature area is written around.
 *
 * WHAT IT IS EMPHATICALLY NOT FOR, and the rule the rest of this feature is built around: NOTHING
 * HERE EVER SETS A VALUE. A result of this search moves the CAMERA. The coordinate that reaches a
 * research record is the one the designer then places by hand, and the state and district that reach
 * it come from the REVERSE path — `LocationFields.resolvePoint`, which puts the geocoder's spelling
 * through the same closed lists `backend/app/services/address.py` validates a write against. That is
 * the whole reason [PlaceHit] carries no address fields at all: it CANNOT be wired into a record by
 * accident, because it has nothing a record wants. A search that dropped a pin would put a
 * geocoder's guess into a research record with nobody in the loop, which is character-for-character
 * the failure `LocationFields` exists to have ended ("The geocoder may offer a value; a person
 * accepts it") and the same discipline `DwIdentityOcr` enforces on a recognised card number.
 *
 * THE PARAMETERS ARE MAPTILER'S, checked against docs.maptiler.com/cloud/api/geocoding/ AND against
 * the live service with this build's own key — `country`, `proximity`, `limit` (1–10),
 * `autocomplete`, `language`. Mapbox's geocoding API takes a set that looks very nearly the same and
 * is a different service; nothing here was copied from it.
 *
 * WHY THIS FILE OWNS ITS OWN HTTP CLIENT rather than going through [ApiClient], which every other
 * network call in the app shares. Two reasons, and the first is not negotiable: [ApiClient]'s chain
 * attaches `Authorization: Bearer <session token>` to every request it carries, and api.maptiler.com
 * is a third party. Sending this deployment's portal session to a tile vendor to ask where Barpali
 * is would be a credential leak caused by convenience. Second, [ApiClient] is bound to the portal's
 * base URL and carries a 504 retry written for THIS origin's CloudFront front door; retrying a
 * geocode four times with backoff on a metered rural link is the opposite of what a designer typing
 * into a search box needs. The timeouts here are correspondingly short: an answer or a sentence,
 * never a minute of spinner.
 */

// ---------------------------------------------------------------------------------------------
// What a result is
// ---------------------------------------------------------------------------------------------

/**
 * `[west, south, east, north]` in degrees, when the feature has an extent.
 *
 * A district or a city is an area, and flying to its centre at village zoom shows a designer one
 * arbitrary street inside it. Framing the extent shows them the thing they asked for.
 */
data class PlaceBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    /**
     * True when MapTiler handed back a bbox that is a single point (w == e and s == n).
     *
     * NOT hypothetical and not a malformed response: the live API returns exactly this for every
     * `place.*` feature — verified for Barpali, Bhuj, Srinagar and Bargarh — because a village's
     * "extent" is its centroid. Framing it as a box would ask the map to fit a zero-size rectangle.
     */
    val isPoint: Boolean get() = west == east && south == north
}

/** One place the geocoder offers. Deliberately camera-shaped: a point and an extent, no address. */
data class PlaceHit(
    val id: String,
    /** "Barpali" — the name on its own, which is what the designer typed and wants to recognise. */
    val name: String,
    /** "Paikmal, Odisha, India" — everything after the name, and the ONLY thing telling two Barpalis apart. */
    val context: String,
    val lon: Double,
    val lat: Double,
    val bounds: PlaceBounds? = null
)

/**
 * Below this, a query is noise: one or two letters match thousands of places, so the request costs a
 * quota unit to return a list nobody can choose from. The caller says so rather than searching.
 */
const val PLACE_SEARCH_MIN_QUERY_LENGTH = 3

/**
 * India only.
 *
 * Every cluster this repository documents is Indian, the address register it validates against is
 * the 36 Indian states and 795 Indian districts, and without this a search for "Bagru" ranks a
 * same-named place elsewhere in the world above the block-printing town in Rajasthan. There is no
 * setting for it because there is no second answer.
 */
private const val PLACE_SEARCH_COUNTRY = "in"

/** MapTiler caps `limit` at 10. Eight fills the panel without asking anyone to scroll a menu. */
private const val PLACE_SEARCH_LIMIT = 8

/**
 * Whether places can be looked up at all in this build.
 *
 * THE ANDROID ANSWER IS NOT THE WEB'S, and the difference matters to the copy. On the web the same
 * per-tenant key draws the tiles, so no key means no map either and the caller hides the whole
 * control. Here the picker's tiles come from OpenStreetMap and need no key at all (see `mapHtml` in
 * FieldComponents.kt), so a keyless build still has a working map and a working pair of coordinate
 * boxes — it has lost only the ability to jump to a name. The degraded state therefore says exactly
 * that rather than implying the map is broken.
 */
fun placeSearchAvailable(key: String): Boolean = key.isNotBlank()

/**
 * The geocoder said no, and this is what it said.
 *
 * [status] is the HTTP status when a server answered, and null when nothing did. Keeping the two
 * apart here is what lets [describePlaceSearchFailure] tell a designer with one bar of signal
 * something different from a designer whose tenant has run out of quota.
 */
class PlaceSearchException(val status: Int?, message: String) : IOException(message)

// ---------------------------------------------------------------------------------------------
// The request
// ---------------------------------------------------------------------------------------------

/**
 * `encodeURIComponent`, exactly.
 *
 * NOT `java.net.URLEncoder`, which is the form encoder: it writes a space as `+`, and a `+` inside a
 * PATH segment is a literal plus sign, so "Kollam Kerala" would silently become a search for
 * "Kollam+Kerala". The unreserved set below is the one `encodeURIComponent` leaves alone —
 * alphanumerics plus `-_.!~*'()` — and everything else goes out as upper-case percent-encoded UTF-8,
 * which is what carries an Odia or Devanagari village name intact.
 */
internal fun encodeUriComponent(value: String): String = percentEncode(value, "-_.!~*'()", spaceAsPlus = false)

/**
 * `application/x-www-form-urlencoded`, which is what `URLSearchParams` writes for the query string.
 *
 * Differs from [encodeUriComponent] in the set it leaves alone (`*-._` only) and in nothing else
 * this feature can reach. It exists so `proximity=85,21` goes out as `proximity=85%2C21`, byte for
 * byte what the web sends — the comma is the only character in any of these five parameters that
 * needs encoding at all, but writing the rule down is cheaper than proving that stays true.
 */
private fun formUrlEncode(value: String): String = percentEncode(value, "*-._", spaceAsPlus = true)

/**
 * Percent-encode the UTF-8 bytes of [value], keeping ASCII alphanumerics and [alsoKeep].
 *
 * THE BYTE IS MASKED TO 0..255 BEFORE ANYTHING LOOKS AT IT. `Byte.toInt()` sign-extends, so the
 * second byte of an Odia character (0xAC) arrives as -84 and `toChar()` on that lands at U+FFAC
 * rather than U+00AC — which would then be asked questions about letters and digits that are
 * meaningless for a continuation byte. Only ASCII may ever be emitted raw; everything from 0x80 up
 * is a fragment of a multi-byte character and must go out as `%XX`.
 */
private fun percentEncode(value: String, alsoKeep: String, spaceAsPlus: Boolean): String {
    val out = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val ch = code.toChar()
        when {
            code < 128 && (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9') -> out.append(ch)
            code < 128 && alsoKeep.indexOf(ch) >= 0 -> out.append(ch)
            spaceAsPlus && ch == ' ' -> out.append('+')
            // Locale.ROOT, not the default: a Formatter in a locale with its own digit shapes is
            // entitled to render "%02X" with them, and a percent-escape written in Odia digits is
            // not an escape any server will decode.
            else -> out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", code))
        }
    }
    return out.toString()
}

/**
 * A double written the way JavaScript writes it, so the proximity parameter is byte-identical to the
 * web's and the parity table can diff whole URLs rather than "the same numbers, roughly".
 *
 * THE TWO LANGUAGES AGREE ON THE DIGITS AND DISAGREE ON THE NOTATION, and it is the notation that
 * made the first version of this function wrong. Both produce the shortest decimal that round-trips;
 * they then choose between fixed and exponential by different rules:
 *
 *   * `Double.toString` goes exponential below 1e-3 and at or above 1e7.
 *   * ECMA-262 `Number::toString` goes exponential only outside `-6 < n <= 21`, where `n` is the
 *     position of the decimal point — i.e. below 1e-6 and at or above 1e21.
 *
 * So a value in either gap is spelt differently by the two, and merely stripping Java's trailing
 * `.0` and lower-casing its `E` — which is what this function used to do — got fifteen of the
 * forty-three values in [com.designprototype.workshop.data.JsNumberParityTest] wrong: `0.0001` went
 * out as `1e-4`, `1e20` as `1e20` rather than its twenty-one digits, and `-0.0` as `-0` where
 * JavaScript writes plain `0`. Even the case the old comment acknowledged was wrong in its spelling,
 * because JavaScript writes a positive exponent with its sign: `1e+21`, not `1e21`.
 *
 * HOW MUCH OF THAT COULD REACH A DESIGNER: almost none of it, and the honest reason to fix it anyway
 * is that this file's whole job is byte-parity with `placeSearch.ts`. The search is `country=in`, so
 * every coordinate that comes back is Indian and nowhere near either gap; the only value that goes
 * out through here is the map-centre bias, which would have to be panned to within about a hundred
 * metres of the equator or the prime meridian. A comment asserting a boundary that was not the real
 * one is the part that had to go: a port is trusted exactly as far as its stated limits are true.
 *
 * The rule below is ECMA-262's, applied to Java's digits, so there is one threshold in this file
 * rather than two implicit ones.
 */
internal fun jsNumber(value: Double): String {
    // Catches -0.0 as well as 0.0, because `-0.0 == 0.0`. Java spells the negative one "-0.0" and
    // JavaScript spells it "0"; Leaflet can hand back a -0.0 longitude on the prime meridian.
    if (value == 0.0) return "0"

    val raw = java.lang.Double.toString(value)
    val negative = raw.startsWith("-")
    val body = if (negative) raw.substring(1) else raw

    // Decompose Java's output — "d.dddEx" or plain "ddd.ddd" — into an integer digit string and the
    // power of ten it is scaled by, so the notation can then be chosen by JavaScript's rule alone.
    val exponentAt = body.indexOf('E')
    val mantissa = if (exponentAt < 0) body else body.substring(0, exponentAt)
    val exponent = if (exponentAt < 0) 0 else body.substring(exponentAt + 1).toInt()
    val dotAt = mantissa.indexOf('.')
    val whole = if (dotAt < 0) mantissa else mantissa.substring(0, dotAt)
    val fraction = if (dotAt < 0) "" else mantissa.substring(dotAt + 1)

    var digits = (whole + fraction).trimStart('0')
    var scale = exponent - fraction.length
    // Java always prints at least one fractional digit, so an integral value arrives as "85.0" and
    // would otherwise be carried as the digits "850". JavaScript's digit string never ends in a zero
    // it does not need, and `n` below is computed from the length, so this has to happen first.
    while (digits.length > 1 && digits.endsWith("0")) {
        digits = digits.dropLast(1)
        scale += 1
    }
    // Belt to the braces: the only value whose digits are all zeros is zero itself, which returned
    // above, so this cannot fire today — but the branches below index `digits[0]` and would throw
    // rather than misprint if it ever did. "NaN" and "Infinity" do NOT come through here; they carry
    // no '.' and no 'E', so they arrive as their own letters and leave through the first branch of
    // the `when` spelt exactly as Java and JavaScript both spell them.
    if (digits.isEmpty()) return raw

    val count = digits.length
    // `n` is where the decimal point sits: value = 0.<digits> x 10^n. This is ECMA-262's own n.
    val n = count + scale
    val sign = if (negative) "-" else ""

    return sign + when {
        n in 1..21 && count <= n -> digits + "0".repeat(n - count)
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        else -> {
            val head = if (count == 1) digits else digits.substring(0, 1) + "." + digits.substring(1)
            val power = n - 1
            head + "e" + (if (power >= 0) "+$power" else "-${-power}")
        }
    }
}

/**
 * The URL the geocoder is asked, built as a pure function so the parity test can compare it to the
 * string the web's `URLSearchParams` produces character for character.
 *
 * [proximity] is (longitude, latitude) — MapTiler's order, which is the opposite of the (lat, lng)
 * order every Android location API uses and therefore the single easiest thing in this file to get
 * backwards. Getting it backwards does not fail: it biases the search to a point in the Arabian Sea
 * and quietly reorders the results.
 */
internal fun placeSearchUrl(query: String, key: String, proximity: Pair<Double, Double>?): String {
    val parameters = StringBuilder()
        .append("key=").append(formUrlEncode(key))
        .append("&country=").append(formUrlEncode(PLACE_SEARCH_COUNTRY))
        .append("&language=").append(formUrlEncode("en"))
        .append("&limit=").append(formUrlEncode(PLACE_SEARCH_LIMIT.toString()))
        // The designer is typing a prefix, not a finished name; this is what makes the list useful
        // before they have spelt the whole village.
        .append("&autocomplete=").append(formUrlEncode("true"))
    // Bias to where the map is already looking. Without it a cluster in Odisha is outranked by a
    // same-named town on the other side of the country — verified against the live API, where
    // "Barpali" returns two Odisha places and two Chhattisgarh ones for the same six letters.
    if (proximity != null) {
        parameters.append("&proximity=")
            .append(formUrlEncode("${jsNumber(proximity.first)},${jsNumber(proximity.second)}"))
    }
    return "https://api.maptiler.com/geocoding/${encodeUriComponent(query)}.json?$parameters"
}

// ---------------------------------------------------------------------------------------------
// The response
// ---------------------------------------------------------------------------------------------

private val placeSearchJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The failure a non-2xx status becomes.
 *
 * Pulled out as a function of the status alone so the parity test can pin the wording and the bucket
 * for every status the service actually returns — 403 for a key MapTiler will not accept, 429 for a
 * tenant over quota, 5xx for its own trouble — without standing up an HTTP server to produce them.
 */
internal fun placeSearchStatusFailure(status: Int): PlaceSearchException =
    PlaceSearchException(status, "The place search answered $status.")

/**
 * Split "Barpali, Paikmal, Odisha, India" into the name and the part that disambiguates it.
 *
 * `place_name` repeats `text` as its first element, so printing both would read "Barpali — Barpali,
 * Paikmal, Odisha, India" on every row. The remainder is the half that matters: the live API returns
 * four Barpalis for one query, in two states, and the only thing separating them is this string.
 */
private fun describeFeature(text: String?, placeName: String?): Pair<String, String> {
    val name = (text ?: "").trim()
    val full = (placeName ?: "").trim()
    if (name.isEmpty()) return full to ""
    if (full.isEmpty() || full == name) return name to ""
    return name to if (full.startsWith("$name,")) full.substring(name.length + 1).trim() else full
}

/**
 * The number at [index], or null when it is absent, null, or a JSON STRING.
 *
 * A quoted "83.5" is refused rather than coerced, which is what the web does — `Number.isFinite`
 * does not coerce — and it is also the safer of the two: a coordinate arriving as text means
 * something upstream has changed shape, and guessing at it is how a wrong pin gets drawn confidently.
 */
private fun JsonArray.finiteNumberAt(index: Int): Double? {
    val primitive = getOrNull(index) as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun JsonObject.string(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

/**
 * Turn a MapTiler FeatureCollection into hits, dropping anything that cannot move a camera.
 *
 * A FEATURE WITH NO USABLE CENTRE IS DROPPED, NOT DEFAULTED. `center` is what the whole result is
 * for; a hit that fell back to 0,0 would be a row a designer taps and lands in the Gulf of Guinea.
 *
 * ONE DELIBERATE HARDENING OVER THE WEB, stated rather than hidden: the web keeps any four-element
 * `bbox` without checking that its entries are numbers, because `fitBounds` is the next thing to see
 * them. Here all four must parse as finite degrees or the extent is discarded and the hit is treated
 * as a bare point — the same outcome the web reaches for a feature with no bbox at all, and the
 * input that would separate them is one MapTiler has never been observed to produce.
 */
internal fun parsePlaceSearchResponse(body: String): List<PlaceHit> {
    val root = runCatching { placeSearchJson.parseToJsonElement(body).jsonObject }.getOrElse {
        // A 200 carrying something that is not JSON is the captive portal this repo has already been
        // bitten by: the wi-fi answered, the geocoder did not. 502 puts it in the try-again bucket,
        // which is the truthful next move.
        throw PlaceSearchException(502, "The place search answered with something that could not be read.")
    }
    val features = root["features"] as? JsonArray ?: return emptyList()
    return features
        .mapNotNull { it as? JsonObject }
        .mapNotNull { feature ->
            val centre = feature["center"] as? JsonArray ?: return@mapNotNull null
            val lon = centre.finiteNumberAt(0) ?: return@mapNotNull null
            val lat = centre.finiteNumberAt(1) ?: return@mapNotNull null
            Triple(feature, lon, lat)
        }
        .mapIndexed { index, (feature, lon, lat) ->
            val (name, context) = describeFeature(feature.string("text"), feature.string("place_name"))
            val box = (feature["bbox"] as? JsonArray)?.takeIf { it.size == 4 }
            val bounds = box?.let {
                val west = it.finiteNumberAt(0)
                val south = it.finiteNumberAt(1)
                val east = it.finiteNumberAt(2)
                val north = it.finiteNumberAt(3)
                if (west == null || south == null || east == null || north == null) null
                else PlaceBounds(west = west, south = south, east = east, north = north)
            }
            PlaceHit(
                // The index is the one AFTER the centreless features were dropped, matching the web,
                // so a fallback id is stable against the list the designer is actually shown.
                id = feature.string("id") ?: "hit-$index",
                name = name,
                context = context,
                lon = lon,
                lat = lat,
                bounds = bounds
            )
        }
}

/**
 * Why the search did not answer, in the words the designer needs.
 *
 * THREE BUCKETS, AND THEY ARE THIS REPOSITORY'S EXISTING TWO QUESTIONS, not a third vocabulary. The
 * web splits them with `isUnreachable` ("did anything reach a server at all") and `isTransient` ("is
 * it worth trying again"), and the reason they are kept apart is written at length in
 * `frontend/lib/offline.ts`: telling somebody their signal is at fault when the server answered
 * sends them out of the building to look for a better one and leaves a real bug wearing an offline
 * message. A designer in the field with one bar has to be told "no connection", and a designer whose
 * tenant has run out of quota has to be told something an administrator can act on.
 *
 * 408 sits on the offline side: it is what a proxy answers when the request never completed, so
 * there is nothing the server can be said to have decided. Anything that is not a
 * [PlaceSearchException] at all — a socket that died, DNS with no answer, a read timeout — is
 * offline for the same reason, which is exactly what `isUnreachable` returns for a non-`ApiError`.
 *
 * None of these is "no such place" — that is an empty LIST from a successful request, and it is the
 * caller's to word, because it is not a failure.
 */
fun describePlaceSearchFailure(error: Throwable): String {
    val status = (error as? PlaceSearchException)?.status
    return when {
        status == null || status == 408 ->
            "No connection, so places cannot be looked up right now. The map still works, and the coordinate boxes need no network at all."
        status >= 500 || status == 429 ->
            "The place search is not answering just now — this is the service, not your connection. Try again in a moment."
        else ->
            "The place search refused this request: this build's map key may be missing, expired or out of quota. Nothing is wrong with what you typed, and an administrator can check the key."
    }
}

// ---------------------------------------------------------------------------------------------
// The call
// ---------------------------------------------------------------------------------------------

/** See the file header for why this is not [ApiClient]'s client. */
private val placeSearchClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

/**
 * The proximity actually worth sending, or null.
 *
 * A PROXIMITY THAT IS NOT A REAL POINT ON EARTH IS DROPPED RATHER THAN SENT. MapTiler validates
 * `proximity` and refuses the WHOLE request over it, so an odd camera would turn "the map is
 * somewhere strange" into "place search is broken" — and the bias is the one parameter in this
 * request that nothing depends on. Dropping it degrades the ranking; sending it loses the feature.
 *
 * WHERE A NON-FINITE CENTRE COMES FROM, because this is not a hypothetical: the bias is fed by
 * `MapBridge.onCentre`, i.e. a `double` marshalled out of JavaScript through `addJavascriptInterface`
 * on a WebView whose Leaflet map may not have finished laying out. A map with a zero-size container
 * reports a centre computed from a degenerate viewport, and `NaN` is what arrives.
 *
 * IT IS (LONGITUDE, LATITUDE) — MapTiler's order, which is the reverse of every Android location
 * API. That is why the two ranges below are not the same number, and it is the reason this is a
 * named function with a test rather than four terms inlined in a call: the ranges are the only
 * written record of which element is which.
 *
 * Extracted from [searchPlaces] so it can be pinned without a socket. `isFinite` is belt to the
 * braces of the range checks — a NaN fails every comparison — and is kept because a reader should
 * not have to know that rule to believe this line.
 */
internal fun placeSearchBias(proximity: Pair<Double, Double>?): Pair<Double, Double>? =
    proximity?.takeIf {
        it.first.isFinite() && it.second.isFinite() && it.first in -180.0..180.0 && it.second in -90.0..90.0
    }

/**
 * Ask MapTiler which places answer to this name.
 *
 * THROWS rather than returning an empty list on failure, because "the search is down" and "there is
 * no such village" are different sentences to a designer standing in the village — see
 * [describePlaceSearchFailure]. An empty LIST is the honest answer to the second one only.
 *
 * CANCELLATION REACHES THE SOCKET. The caller re-arms this on every keystroke, so the coroutine that
 * is no longer wanted must not keep a request alive on a metered link and must not be able to land
 * its answer under a query that has moved on. Cancelling the coroutine cancels the OkHttp call.
 */
suspend fun searchPlaces(
    query: String,
    key: String,
    proximity: Pair<Double, Double>?
): List<PlaceHit> {
    if (!placeSearchAvailable(key)) {
        // Not reachable from the UI, which hides the box without a key — but throwing here means a
        // future caller that forgets the check fails loudly instead of searching against `key=`.
        throw PlaceSearchException(503, "This build has no map key, so places cannot be looked up.")
    }
    val request = Request.Builder().url(placeSearchUrl(query, key, placeSearchBias(proximity))).get().build()

    val body = suspendCancellableCoroutine { continuation ->
        val call = placeSearchClient.newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Nothing answered. Status stays null, which is what puts this in the offline bucket.
                if (continuation.isActive) continuation.resumeWithException(PlaceSearchException(null, e.message ?: "No answer"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        // The status is read BEFORE the body, exactly as the web does: a 403 whose
                        // body is MapTiler's plain-text "Invalid key" must be reported as a refusal,
                        // not as unreadable JSON.
                        if (continuation.isActive) continuation.resumeWithException(placeSearchStatusFailure(it.code))
                        return
                    }
                    val text = runCatching { it.body?.string().orEmpty() }.getOrElse { error ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                PlaceSearchException(null, error.message ?: "The answer stopped part-way through.")
                            )
                        }
                        return
                    }
                    if (continuation.isActive) continuation.resume(text)
                }
            }
        })
    }
    return parsePlaceSearchResponse(body)
}

// ---------------------------------------------------------------------------------------------
// Where the camera goes
// ---------------------------------------------------------------------------------------------

/**
 * How close a chosen place is framed when it has an extent of its own.
 *
 * Fitting a small village's bounding box lands somewhere past zoom 18 — a few rooftops, with no way
 * to tell which village they belong to. Capping it keeps the surrounding roads and the neighbouring
 * settlements on screen, which is what a designer needs in order to recognise the place and then tap
 * the right part of it.
 */
const val PLACE_MAX_ZOOM = 14

/** Zoom for a place the geocoder gives as a bare point: close enough to see a village's streets. */
const val PLACE_POINT_ZOOM = 13

/**
 * The camera a hit asks for. Two shapes, because an area and a point want different framings.
 *
 * It lives beside the parser rather than in the UI because it is arithmetic over a [PlaceHit] with
 * no Android in it, and the parity test pins it against the same case table as everything above.
 */
sealed interface PlaceCamera {
    /** Frame an extent. Degrees, in the order Leaflet's `fitBounds` wants them. */
    data class Frame(val south: Double, val west: Double, val north: Double, val east: Double) : PlaceCamera

    data class Point(val lat: Double, val lon: Double, val zoom: Int) : PlaceCamera
}

/**
 * IT DECIDES A CAMERA AND NOTHING ELSE. There is no variant of this that returns a coordinate to
 * store, and there must never be one — see the file header.
 */
fun cameraForPlace(hit: PlaceHit): PlaceCamera {
    val bounds = hit.bounds
    // A degenerate bbox is MapTiler's answer for a village (w == e, s == n) and asking a map to fit a
    // zero-size rectangle is asking it for its maximum zoom, which is a roof.
    if (bounds != null && !bounds.isPoint) {
        return PlaceCamera.Frame(
            south = bounds.south,
            west = bounds.west,
            north = bounds.north,
            east = bounds.east
        )
    }
    return PlaceCamera.Point(lat = hit.lat, lon = hit.lon, zoom = PLACE_POINT_ZOOM)
}

/**
 * The one line of JavaScript that moves the Leaflet map inside the picker's WebView.
 *
 * NOTE THE AXIS ORDER, which is the trap in this function. MapTiler states a bbox as
 * `[west, south, east, north]` — longitude first. Leaflet states bounds as
 * `[[south, west], [north, east]]` — latitude first. They are the same four numbers in a different
 * order, so getting it wrong does not throw: it flies the map to a rectangle over the Indian Ocean.
 *
 * IT TOUCHES THE CAMERA ONLY. It never calls `marker.setLatLng` and never calls the bridge's
 * `onPick`, so no path through this function can put a coordinate into a record. The guard on
 * `window.__map` is not defensive noise either — the WebView may still be fetching Leaflet over a
 * rural link when the first result is tapped, and the honest behaviour then is to do nothing rather
 * than to throw inside a page nobody can see.
 */
fun leafletCameraScript(camera: PlaceCamera): String = when (camera) {
    is PlaceCamera.Frame ->
        "if(window.__map){window.__map.fitBounds(" +
            "[[${jsNumber(camera.south)},${jsNumber(camera.west)}]," +
            "[${jsNumber(camera.north)},${jsNumber(camera.east)}]]," +
            "{maxZoom:$PLACE_MAX_ZOOM,padding:[24,24]});}"
    is PlaceCamera.Point ->
        "if(window.__map){window.__map.setView(" +
            "[${jsNumber(camera.lat)},${jsNumber(camera.lon)}],${camera.zoom});}"
}
