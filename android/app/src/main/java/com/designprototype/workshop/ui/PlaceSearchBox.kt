package com.designprototype.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.PLACE_SEARCH_MIN_QUERY_LENGTH
import com.designprototype.workshop.data.PlaceHit
import com.designprototype.workshop.data.PlaceSearchException
import com.designprototype.workshop.data.describePlaceSearchFailure
import com.designprototype.workshop.data.placeSearchAvailable
import com.designprototype.workshop.data.searchPlaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Type a village, move the map. The one affordance that makes a map picker usable in rural India.
 *
 * A SEARCH RESULT MOVES THE CAMERA AND WRITES NOTHING. This is the load-bearing rule, it is not a
 * detail of this control, and it is why [onChoose] hands back a [PlaceHit] — a point and an extent —
 * rather than an address: there is nothing here a record could be filled in from. The coordinate
 * that reaches a research record is still the one the designer places by hand afterwards, and the
 * state and district still come from the reverse path, through the closed lists in
 * `backend/app/services/address.py`. `LocationFields` says it in one line and it holds here too: the
 * geocoder may offer a value; a person accepts it. The promise is printed under the box, before the
 * designer uses it, because a search box on a location form has every reason to be expected to fill
 * the location in.
 *
 * WHY IT FLOATS OVER THE MAP instead of sitting above it in the dialog's column. Arithmetic, on the
 * smallest handset this app runs on. The picker's card already spends about 590dp on a title, an
 * instruction, a 380dp map, the selected-coordinate line and two buttons; a 640dp-tall screen has
 * nothing left. A field plus a results list added to that column is another ~200dp, which pushes
 * "Use this point" off the bottom of the screen — a search box that costs the designer the button
 * they came for. Floating it costs the map 40dp of height and the results nothing at all, which is
 * the same trade the web makes with an absolutely-positioned panel.
 *
 * WHY THERE IS NO EVENT FIREWALL HERE, unlike `components/forms/PlaceSearchBox.tsx`. That component
 * stops `input` and `keydown` from leaving it because on the web this is a real `<input>` inside the
 * record's `<form onInput={() => setDirty(true)}>`, so merely typing a place name to LOOK at the map
 * would arm the unsaved-changes prompt on a form nobody had edited. Compose has no bubbling input
 * events and no enclosing form: this state is local to this composable and cannot reach the stage's
 * dirty flag. The failure is real; the mechanism that causes it does not exist on this client.
 *
 * OFFLINE. Forward geocoding is the one part of the location card that genuinely cannot work with no
 * signal — there is no bundled gazetteer and this file does not pretend otherwise. It says so in the
 * designer's own terms when a request fails, and it says the useful half out loud: the map and the
 * two coordinate boxes need no network at all.
 */

/**
 * The pause after the last keystroke before anything is sent.
 *
 * A request per keystroke is eight requests to spell "Barpali", each one billed to this deployment's
 * MapTiler quota and each one carried by the connection this whole feature exists for. 350 ms is
 * longer than the gap between two letters and shorter than the pause someone makes when they have
 * finished a word.
 */
private const val PLACE_SEARCH_DEBOUNCE_MS = 350L

/** Where the search is up to. `Short` is not a failure — it is the box waiting to be worth asking. */
internal enum class PlaceSearchState { Idle, Short, Searching, Done, Failed }

/**
 * The one line the panel shows when it has no rows, and the sentence TalkBack reads out.
 *
 * FOUR OUTCOMES, KEPT APART, because the designer's next move differs in every one: wait, type more,
 * try a different name, or stop trying and place the pin by hand. "No results" for all four is the
 * failure this branch exists to avoid — a designer in the field with one bar being told the village
 * does not exist.
 *
 * INTERNAL RATHER THAN PRIVATE SO IT CAN BE PINNED. This is the only place in the client where an
 * EMPTY SUCCESSFUL ANSWER is worded, and it is the outcome nothing else can check: the three failure
 * sentences live in [describePlaceSearchFailure] and are diffed against the web, but "no place in
 * India is called that" is produced here and nowhere else. Collapsing it into the offline sentence
 * is a one-word edit that no other test in the repository would notice — see
 * `PlaceSearchMessageTest`, which exists to make that edit fail.
 */
internal fun placeSearchStatusLine(
    state: PlaceSearchState,
    query: String,
    hits: List<PlaceHit>,
    problem: String?
): String = when (state) {
    PlaceSearchState.Short -> "Type at least $PLACE_SEARCH_MIN_QUERY_LENGTH letters to search."
    PlaceSearchState.Searching -> "Searching…"
    PlaceSearchState.Failed -> problem ?: "The place search did not answer."
    PlaceSearchState.Done ->
        if (hits.isNotEmpty()) {
            "${hits.size} place${if (hits.size == 1) "" else "s"} found."
        } else {
            "No place in India is called “${query.trim()}”. Check the spelling, try the nearest town, " +
                "or place the pin by hand."
        }
    PlaceSearchState.Idle -> ""
}

/**
 * @param apiKey this build's MapTiler key. Blank is a legitimate deployment and is handled, not
 *   crashed — see [placeSearchAvailable].
 * @param getProximity where the map is looking, as (longitude, latitude), read at REQUEST time. It
 *   is a lambda rather than a value so that panning the map — which fires on every gesture — cannot
 *   recompose this control, and so the bias is exactly where the designer was looking when they
 *   asked rather than where they were when the box was drawn.
 * @param onChoose move the map. MUST NOT write a record value — see the header.
 */
@Composable
fun PlaceSearchOverlay(
    apiKey: String,
    getProximity: () -> Pair<Double, Double>?,
    onChoose: (PlaceHit) -> Unit,
    modifier: Modifier = Modifier
) {
    // No key, no forward geocoding — but, unlike the web, still a working map: these tiles come from
    // OpenStreetMap and need no key. The copy says which half is missing rather than implying the
    // picker is broken, and names the two routes that always work.
    if (!placeSearchAvailable(apiKey)) {
        Surface(
            color = Canvas,
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 2.dp,
            modifier = modifier.fillMaxWidth()
        ) {
            Text(
                "Place search is off in this build (no map key). Pan the map, or type the coordinates in — " +
                    "both work with no network.",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        return
    }

    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(emptyList<PlaceHit>()) }
    var state by remember { mutableStateOf(PlaceSearchState.Idle) }
    var problem by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }

    /*
     * Debounced, abortable, and re-armed by the query alone.
     *
     * ONE `LaunchedEffect` IS BOTH HALVES. A new keystroke cancels this coroutine, which cancels
     * either the [delay] (that is the debounce) or the suspended HTTP call (that is the abort, and it
     * reaches the socket — see `searchPlaces`). Without the cancel, a slow answer for "Barp" can land
     * after the answer for "Barpali" and leave a stale list under a query that has moved on, which is
     * the same shape of bug as showing a geocoder suggestion that arrived after its point changed.
     */
    LaunchedEffect(query) {
        val wanted = query.trim()
        if (wanted.length < PLACE_SEARCH_MIN_QUERY_LENGTH) {
            hits = emptyList()
            problem = null
            state = if (wanted.isEmpty()) PlaceSearchState.Idle else PlaceSearchState.Short
            return@LaunchedEffect
        }
        state = PlaceSearchState.Searching
        delay(PLACE_SEARCH_DEBOUNCE_MS)
        /*
         * ASKED BEFORE THE REQUEST, not after it times out — the same call `DwIdentityCardControl`
         * makes for the same reason. With no signal, `searchPlaces` sits on a ten-second connect
         * timeout, and ten seconds of a spinner that then says "no connection" is indistinguishable
         * from a hung app to somebody standing in a courtyard. Saying it in 350 ms is the same
         * sentence, delivered while it is still useful.
         *
         * Best-effort, and it can be wrong in the optimistic direction — a captive portal or a bar of
         * signal that carries nothing — which is why the catch below has to say the same thing in the
         * past tense rather than treating this as the only place offline is handled.
         */
        if (!ConnectivityObserver.isOnline(context)) {
            hits = emptyList()
            problem = describePlaceSearchFailure(PlaceSearchException(null, "No connection"))
            state = PlaceSearchState.Failed
            open = true
            return@LaunchedEffect
        }
        try {
            val found = searchPlaces(wanted, apiKey, getProximity())
            hits = found
            problem = null
            state = PlaceSearchState.Done
            open = true
        } catch (cancelled: CancellationException) {
            // A newer keystroke, never an answer. Rethrown so the coroutine actually dies rather than
            // being reported to the designer as a failed search they did not make.
            throw cancelled
        } catch (error: Throwable) {
            hits = emptyList()
            problem = describePlaceSearchFailure(error)
            state = PlaceSearchState.Failed
            open = true
        }
    }

    val status = placeSearchStatusLine(state, query, hits, problem)

    Surface(
        color = Canvas,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; open = true },
                label = { Text("Search for a place to move the map") },
                placeholder = { Text("Village, town or district", color = Muted) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = Muted,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    when {
                        state == PlaceSearchState.Searching ->
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        query.isNotEmpty() ->
                            IconButton(onClick = { query = ""; open = false }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear the place search", tint = Muted)
                            }
                        else -> {}
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // THE PROMISE, IN THE PLACE IT IS MADE, and before it is used.
            Text(
                "Searching only moves the map. Nothing is filled in until you place the pin yourself.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )

            if (open && state != PlaceSearchState.Idle) {
                HorizontalDivider(color = Hairline)
                Column(
                    // Capped and scrollable rather than as tall as the list: eight results at full
                    // height would cover the map the results exist to move.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 168.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (hits.isEmpty()) {
                        Text(
                            status,
                            color = if (state == PlaceSearchState.Failed) MaterialTheme.colorScheme.error else Muted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = if (state == PlaceSearchState.Failed) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                // Announced rather than silently swapped, because the four outcomes
                                // above are the whole point and a TalkBack user cannot see the panel
                                // change. Polite: it must never interrupt what is being typed.
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    } else {
                        hits.forEach { hit ->
                            PlaceSearchRow(
                                hit = hit,
                                onClick = {
                                    onChoose(hit)
                                    open = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One offered place. The context line is what tells two Barpalis apart, so it is never dropped. */
@Composable
private fun PlaceSearchRow(hit: PlaceHit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(SurfaceCard, RoundedCornerShape(8.dp))
            // The app's 48dp floor. A list of villages tapped by a fingertip in a courtyard is the
            // last place to be economical about target height.
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                hit.name.ifBlank { "Unnamed place" },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hit.context.isNotBlank()) {
                Text(hit.context, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
