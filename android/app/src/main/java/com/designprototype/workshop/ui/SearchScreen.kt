package com.designprototype.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.ArtisanDto
// The cache-first register loaders and their provenance type. They live beside the record forms in
// `MainActivity.kt` for the reason `DwReferenceStore` gives about codecs: the store owns a document
// with a date on it, and which columns a picker reads is a fact about the screens.
import com.designprototype.workshop.loadArtisanRegister
import com.designprototype.workshop.loadCraftRegister
import com.designprototype.workshop.data.CraftDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.SearchResultsDto
import com.designprototype.workshop.data.apiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Global cross-record search — the Android mirror of the web `/search` page.
 *
 * `GET /search` runs ONE shared skip/take across five independent buckets (artisans, workshops,
 * products, tools, media), so a page can be full in one bucket and empty in another. That single
 * fact drives most of the decisions below: one pager for everything, per-bucket totals rather than
 * per-bucket pagers, and a footer that says out loud that the buckets page together.
 *
 * Open to any authenticated user; the API already filters every row down to what the caller may see.
 */

// ---------------------------------------------------------------------------------------------
// Public contract
// ---------------------------------------------------------------------------------------------

/**
 * The `recordType` values [SearchScreen] hands to `onOpenRecord`, one per bucket the API returns.
 * Lower-case to match the backend's own record-type vocabulary (`artisan`, `product`, `tool`, …);
 * `workshop` is not in that list server-side but is a real bucket here, so it is named the same way.
 */
object SearchRecordTypes {
    const val ARTISAN = "artisan"
    const val WORKSHOP = "workshop"
    const val PRODUCT = "product"
    const val TOOL = "tool"
    const val MEDIA = "media"

    /**
     * The twenty-two-stage record this product is NAMED AFTER, and the bucket this screen was
     * missing outright until 2026-08-31.
     *
     * [WORKSHOP] above is the LEGACY `Workshop` table — a craft-documentation visit, a different
     * model with no join to this one — so before this constant existed the handset searched FIVE
     * record types where the web searched six, and neither a design workshop, nor its stages, nor
     * its fields, nor the designer's own questions was reachable from the screen labelled Search.
     * The scope filter for it (`workshopIds`) had already landed here; the bucket had not, which is
     * the half nobody notices, because a bucket that is not asked for comes back empty and an empty
     * result reads as "the repository holds none of those".
     *
     * SPELLED `designWorkshop` — the singular vocabulary the rest of the app uses, and the same
     * string as `DwWorkshopRecordType.DESIGN_WORKSHOP.wire`, so a scanned `G` code and a tapped
     * search row name one record type rather than two.
     */
    const val DESIGN_WORKSHOP = "designWorkshop"

    /**
     * Every bucket, in the order `GET /search` counts, reads and returns them.
     *
     * Type selections are always re-derived by filtering THIS list, never stored in the order the
     * user happened to tick them. Two researchers who picked the same three buckets in different
     * orders are asking one question, and it must reach the API as one query string.
     *
     * [DESIGN_WORKSHOP] IS LAST, matching the server's own order (`search.SEARCH_TYPES`), so the
     * chips, the checkboxes and the result sections all read in the order the API counts them.
     */
    val ALL: List<String> = listOf(ARTISAN, WORKSHOP, PRODUCT, TOOL, MEDIA, DESIGN_WORKSHOP)

    /** The bucket's own heading, as the web's `TYPE_LABEL` words it. */
    fun label(recordType: String): String = when (recordType) {
        ARTISAN -> "Artisans"
        WORKSHOP -> "Workshops"
        PRODUCT -> "Products"
        TOOL -> "Tools"
        MEDIA -> "Media"
        DESIGN_WORKSHOP -> "Design workshops"
        else -> recordType.replaceFirstChar { it.uppercase() }
    }

    /**
     * The name `GET /search?types=` knows this bucket by.
     *
     * The API's vocabulary there differs from the app's, which is singular everywhere else — singular
     * is what `onOpenRecord`, `EntryMode` routing and `/data/locate` all take — so the two are
     * translated in one place instead of a second vocabulary being kept in step by hand.
     *
     * NOT `recordType + "s"`. The API's list is `artisans, workshops, products, tools, media`
     * (`backend/app/api/routes/search.py`): plural for four of them and `media` for the fifth,
     * because "medias" is not a word. Appending an s sent `medias`, and that route answers an
     * unrecognised name with a 422 rather than dropping it — so ticking the Media chip did not
     * narrow the search, it made every search that included Media fail and show nothing at all.
     * Written out one bucket at a time so that the next bucket added has to be written out too.
     */
    fun bucket(recordType: String): String = when (recordType) {
        ARTISAN -> "artisans"
        WORKSHOP -> "workshops"
        PRODUCT -> "products"
        TOOL -> "tools"
        MEDIA -> "media"
        // `designWorkshops`, and the CASE MATTERS ON THE WIRE even though this app lower-cases the
        // whole list before sending it (`WorkshopRepository.search`). `resolve_types` folds case for
        // the comparison and answers with the vocabulary's own spelling — a rule it grew precisely
        // because this is the first bucket name that is not all lower-case — so `designworkshops`
        // resolves and `types` comes back reading `designWorkshops`. Written in the API's spelling
        // here so a reader comparing this line with the response is comparing like with like.
        DESIGN_WORKSHOP -> "designWorkshops"
        // Unreachable while every caller filters [ALL] first, and left as-is rather than guessed at:
        // an unknown name reaching the API as itself is a 422 naming the real problem, where a name
        // this function invented would be a 422 naming something the app made up.
        else -> recordType
    }
}

/**
 * The record-time presets, resolved to concrete dates by [SearchFilters.resolveDateRange] before any
 * request is made. The API takes dates and never preset names, deliberately: "Last 30 days" is a
 * phrase in a UI counted against the researcher's own clock, and only this client knows that clock.
 */
enum class SearchRange(val label: String) {
    ANY("Any time"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    LAST_90_DAYS("Last 90 days"),
    THIS_MONTH("This month"),
    THIS_YEAR("This year"),
    CUSTOM("Custom range")
}

/**
 * Rows per bucket per page. `GET /search` caps pageSize at 50 and applies one shared skip/take to
 * all five buckets, so this is the page size of every bucket at once. 20 matches the web page.
 */
const val SEARCH_PAGE_SIZE = 20

/** How long the inputs must settle before a query is sent. One request per typed word, not per key. */
const val SEARCH_DEBOUNCE_MILLIS = 350L

/**
 * How many "matched in" entries a result row names before the rest are counted.
 *
 * A workshop can legitimately answer one word in a dozen of its twenty-two stages, and a dozen stage
 * titles on a result row is a paragraph where a subtitle should be. THE REMAINDER IS COUNTED AND
 * NEVER DROPPED: a list that quietly stops is indistinguishable from a list that ended there, and
 * here it would understate how much of a fortnight's fieldwork the researcher's word appears in.
 * Three matches the web's `MATCHED_IN_SHOWN`.
 */
internal const val MATCHED_IN_SHOWN = 3

/**
 * Everything `GET /search` filters on, for BOTH surfaces that search: this screen and the panel at
 * the top of the Data Browser.
 *
 * They were drifting apart — the search screen had count pills and a place box, the browser had a
 * bare text field — which meant the same question got two answers depending on which screen the
 * researcher was standing on. The vocabulary (what a type is, what "Last 30 days" resolves to, how
 * the filters become a request) lives here once so the two cannot disagree again.
 *
 * Held as ONE value so the debounce can compare "what is set now" against "what was last searched"
 * in a single equality check, and so paging reads a frozen snapshot instead of drifting with a
 * half-typed box.
 */
@Immutable
data class SearchFilters(
    val query: String = "",
    val place: String = "",
    /**
     * The buckets to search. EMPTY MEANS EVERY BUCKET — the set never lists all five explicitly, so
     * "nothing ticked" and "everything ticked" cannot both exist and mean the same thing. Held in
     * [SearchRecordTypes.ALL] order, whatever order the ticks went in.
     */
    val types: Set<String> = emptySet(),
    val range: SearchRange = SearchRange.ANY,
    /** Only read when [range] is [SearchRange.CUSTOM]; either bound may stand alone. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val craftId: String = "",
    val artisanId: String = "",
    val mediaType: String = ""
) {
    /**
     * The parts a person TYPES, as one comparable value. Everything else here is clicked, and the
     * two deserve different timing: typing has to settle before it is worth a request, a tapped chip
     * is already the finished thought. A pair rather than a joined string, so that no separator can
     * collapse ("a", "b c") and ("a b", "c") into one value and swallow one of the two changes.
     */
    val typed: Pair<String, String>
        get() = query to place

    /**
     * How many filters the sheet is hiding. Types are deliberately NOT counted: the chips show them
     * whether the sheet is open or shut, and a badge counting something already on screen reads as a
     * second, disagreeing filter.
     */
    val sheetFilterCount: Int
        get() = listOf(
            place.isNotBlank(),
            range != SearchRange.ANY,
            craftId.isNotBlank(),
            artisanId.isNotBlank(),
            mediaType.isNotBlank()
        ).count { it }

    /** True when anything at all is narrowing the search — a bare filter is a real question. */
    val hasFilters: Boolean
        get() = types.isNotEmpty() || sheetFilterCount > 0

    /** No filter at all — searching this would list the whole repository, so it needs an explicit ask. */
    val isEmpty: Boolean
        get() = query.isBlank() && !hasFilters

    /** Whether a bucket survives the type filter — the client half of the `types` contract. */
    fun includes(recordType: String): Boolean = types.isEmpty() || recordType in types

    /**
     * The selected buckets as [WorkshopRepository.search] wants them: the API's plural names, in
     * canonical order, or null for "everything".
     */
    fun bucketTypes(): List<String>? = SearchRecordTypes.ALL
        .filter { it in types }
        .map(SearchRecordTypes::bucket)
        .takeIf { it.isNotEmpty() }

    /**
     * [range] as the concrete `dateFrom`/`dateTo` instants the API takes.
     *
     * Resolved against [today] at REQUEST time rather than when the preset was picked, so a screen
     * left open overnight does not keep searching yesterday. Both bounds are built in the device's
     * own zone and serialised as instants: the end of a chosen day is 23:59:59, because the API
     * compares with `lte` and a bare start-of-day bound would drop every record made on that day.
     */
    fun resolveDateRange(today: LocalDate = LocalDate.now()): Pair<String?, String?> {
        val endOfToday = endOfDay(today)
        return when (range) {
            SearchRange.ANY -> null to null
            SearchRange.TODAY -> startOfDay(today) to endOfToday
            // Inclusive of today, so "last 7 days" really is seven days and not eight.
            SearchRange.LAST_7_DAYS -> startOfDay(today.minusDays(6)) to endOfToday
            SearchRange.LAST_30_DAYS -> startOfDay(today.minusDays(29)) to endOfToday
            SearchRange.LAST_90_DAYS -> startOfDay(today.minusDays(89)) to endOfToday
            SearchRange.THIS_MONTH -> startOfDay(today.withDayOfMonth(1)) to endOfToday
            SearchRange.THIS_YEAR -> startOfDay(today.withDayOfYear(1)) to endOfToday
            SearchRange.CUSTOM -> from?.let(::startOfDay) to to?.let(::endOfDay)
        }
    }
}

private fun startOfDay(date: LocalDate): String =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

private fun endOfDay(date: LocalDate): String =
    date.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toString()

/** Media types the API accepts for `mediaType`, in the backend enum's own order. */
private val SEARCH_MEDIA_TYPES = listOf("IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER")

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

/**
 * Search across every record type at once.
 *
 * @param onOpenRecord called with a [SearchRecordTypes] value and the record's id when a result is
 *   tapped; the host routes that into the existing detail/edit screen for that type.
 * @param onBack invoked by the back control — only rendered when [showBackAction] is true, because
 *   the app's own chrome already draws a Back pill above every non-dashboard screen. Host this
 *   screen outside that chrome and pass `showBackAction = true`.
 */
@Composable
fun SearchScreen(
    repository: WorkshopRepository,
    onOpenRecord: (recordType: String, recordId: String) -> Unit,
    onBack: () -> Unit,
    showBackAction: Boolean = false,
    /**
     * Open showing only this bucket — a [SearchRecordTypes] value, or null for all five.
     *
     * This is what a tapped dashboard total means. "74 tools" is a question, and the honest answer
     * is the list of those tools, not a page of five headings where four are empty. Arriving with
     * one also implies the listing itself: the tap already said what it wanted, so the screen must
     * not sit on an empty form waiting to be told again.
     */
    initialRecordType: String? = null,
    modifier: Modifier = Modifier
) {
    /*
     * KEYED on initialRecordType, and that is not a detail: Compose keeps a slot's `remember` across
     * a navigation that lands on the same composable, so an unkeyed one would hold the FIRST focus
     * for ever. Tapping "Tools" after having tapped "Artisans" showed a page headed "Every tools
     * record" with the Artisans bucket still selected — the caller had moved on and the state had
     * not. Everything seeded from the parameter is keyed on it for the same reason, `results`
     * included: the previous bucket's rows must not sit under the new bucket's heading.
     */
    val seed = remember(initialRecordType) { SearchFilters(types = setOfNotNull(initialRecordType)) }

    /*
     * WHICH BUCKETS THIS READER IS OFFERED — five for most accounts, six for a professor and above.
     *
     * Design-workshop stage data is Professor / Admin / Master Admin (the owner's ruling of
     * 2026-08-30). The server enforces it: a caller without the capability gets the bucket dropped
     * and NAMED in `typesRefused`, so nothing here is a security boundary. What it prevents is a chip
     * that never works — a researcher ticking "Design workshops", getting a refusal sentence, and
     * concluding the search box is broken. The web hides the same chip for the same reason.
     *
     * FROM THE CACHED USER, deliberately, and not from a new screen parameter. The host that mounts
     * this screen owns its parameter list, and this lane may not edit it; the token store already
     * holds the account the API issued, which is what every other permission read on this screen's
     * neighbours uses. A null (a store cleared under a screen that is still composed) offers the
     * FIVE, which is the safe direction: a missing chip is a control to go and find, where an
     * offered one that always refuses is a bug report.
     */
    val offeredTypes = remember {
        val user = repository.cachedUser()
        if (user != null && FieldPermissions.canViewDesignWorkshopData(user)) {
            SearchRecordTypes.ALL
        } else {
            SearchRecordTypes.ALL - SearchRecordTypes.DESIGN_WORKSHOP
        }
    }
    // Live inputs.
    var filters by remember(seed) { mutableStateOf(seed) }
    // The filters the CURRENT results belong to. The pager and the rendered buckets walk THESE,
    // never `filters`: they describe the rows on screen, and a half-typed box does not.
    var applied by remember(seed) { mutableStateOf(seed) }
    var page by remember(seed) { mutableStateOf(1) }
    // Bumped by the Search button so pressing it re-runs an identical query (same filters, same page).
    var runCount by remember { mutableStateOf(0) }
    // Set once the researcher explicitly asks for an unfiltered listing — or immediately, when the
    // screen was opened from a dashboard total, which is that same request made by tapping a number.
    var browseAll by remember(seed) { mutableStateOf(initialRecordType != null) }

    var results by remember(seed) { mutableStateOf<SearchResultsDto?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    // The registers below are cached under `filesDir`, which needs a Context. See [loadCraftRegister].
    val context = androidx.compose.ui.platform.LocalContext.current
    /** Where each register came from, and how old it is - see [RegisterLoad]. */
    var craftRegister by remember { mutableStateOf(RegisterLoad()) }
    var artisanRegister by remember { mutableStateOf(RegisterLoad()) }

    /*
     * THE WORKSHOP SCOPE — the filter this screen was missing, and the web has had since the map
     * started linking here.
     *
     * `workshopIds` occurred ZERO times in this file while `WorkshopRepository.search` has taken it
     * all along and every other cross-workshop screen on this handset offers it: the completion
     * matrix, the consolidated questionnaire, the map. So a researcher could scope the map to last
     * week's workshop, tap through to a record, come here to look for a second one, and be handed the
     * whole repository with nothing on screen to say the scope had been dropped. Two surfaces
     * disagreeing about what "this workshop" contains is two surfaces disagreeing about what the
     * workshop's data IS — which is exactly what `ui/WorkshopScope.kt` exists to prevent, and it was
     * being prevented everywhere but here.
     *
     * `defaultToMostRecent = false`, matching the web's `/search` and for its stated reason: this is
     * the general way IN to the corpus and its default has always been "everything". The matrix and
     * the map default the other way because they are read DURING a workshop. Quietly narrowing this
     * screen would change what every existing route into it means.
     */
    val workshopScope = rememberWorkshopScope(
        repository = repository,
        defaultToMostRecent = false,
        onError = { message -> error = message }
    )

    /*
      CACHE-FIRST, AND THE PICKERS NO LONGER VANISH - two changes, and the second depends on the first.
    
      This was two bare `repository.crafts()` / `repository.artisans()` calls whose failure was
      swallowed, under a comment saying the picker "simply stays hidden". Hiding it was the honest
      thing to do while the alternative was a menu that could not say why it was empty; it is still
      the worst possible reading for the designer, because a filter that is ABSENT is
      indistinguishable from a filter this screen never had, and neither of those is what happened.
    
      Routing both through `loadCraftRegister` / `loadArtisanRegister` gives them the same offline
      copy the record forms use (DROPDOWN_DESIGN 3.3: a register is not an access list, so it may be
      cached), so in a courtyard the filters are usually simply THERE. When they are not, they are
      drawn stood down with 3.5's sentence rather than removed - see the `extraFilters` slot.
    */
    LaunchedEffect(Unit) {
        craftRegister = loadCraftRegister(context, repository) { crafts = it }
        artisanRegister = loadArtisanRegister(context, repository) { artisans = it }
    }

    // Editing any input restarts this effect, so a change is only promoted to `applied` once the
    // inputs have been still for SEARCH_DEBOUNCE_MILLIS — but only TYPING has to be still. A chip, a
    // range or a type tick is one deliberate tap and refreshes at once; waiting on it would read as
    // the filter not working. Both paths still go through this one effect, so the cancel-and-restart
    // that guards against stale responses stays the only way a request is ever made.
    LaunchedEffect(filters) {
        if (filters == applied) return@LaunchedEffect
        if (filters.typed != applied.typed) delay(SEARCH_DEBOUNCE_MILLIS)
        applied = filters
        page = 1
    }

    // The one place a request is made. Re-keying cancels the in-flight call, so a stale response can
    // never overwrite a newer one.
    LaunchedEffect(applied, page, browseAll, runCount, workshopScope.requestKey, workshopScope.settled) {
        // HELD UNTIL THE SCOPE HAS SETTLED, which is `rememberWorkshopScope`'s own instruction. The
        // default selection is only known once the workshops have loaded, so a request issued before
        // that would be answered against a scope the researcher never chose — and this effect re-runs
        // the moment `settled` flips, so nothing is lost by waiting. Nothing is reset here either:
        // clearing `results` would blank a list the reader is looking at on every recomposition
        // before the load returns.
        if (!workshopScope.settled) return@LaunchedEffect
        // A WORKSHOP PICK IS A SEARCH IN ITS OWN RIGHT. "Everything from this workshop" is a complete
        // question with an empty text box, exactly as "everything of this type" is — so without this
        // the picker would move and the list would not, which reads as the control not working. The
        // web's `/search` page carries the same rule in the same words.
        if (applied.isEmpty && !browseAll && workshopScope.isAllRecords) {
            results = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            // Every active filter goes into ONE request, so they AND rather than being applied in
            // passes. The presets become concrete dates here, at request time, against the device's
            // own clock — see SearchFilters.resolveDateRange.
            val (dateFrom, dateTo) = applied.resolveDateRange()
            results = repository.search(
                q = applied.query.trim().ifBlank { null },
                craftId = applied.craftId.ifBlank { null },
                place = applied.place.trim().ifBlank { null },
                artisanId = applied.artisanId.ifBlank { null },
                mediaType = applied.mediaType.ifBlank { null },
                types = applied.bucketTypes(),
                dateFrom = dateFrom,
                dateTo = dateTo,
                // Read from the scope state rather than from `applied`, because it is a PICKER and
                // not a typed box: it applies at once, like a chip and unlike the query. The effect
                // is keyed on `requestKey`, so a change here is what re-runs this request.
                workshopIds = workshopScope.workshopIds.takeIf { it.isNotEmpty() },
                page = page,
                pageSize = SEARCH_PAGE_SIZE
            )
            error = null
        } catch (cancelled: CancellationException) {
            // Every settled keystroke re-keys this effect, and Compose FORGETS the old one — which
            // cancels the in-flight call with "The coroutine scope left the composition". runCatching
            // catches that like any other Throwable, which is how a plain superseded request ended up
            // reported as a failed search. Rethrowing also skips the `loading = false` below, and must:
            // the pass that replaced this one already owns the flag.
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.apiErrorMessage("Search failed")
        }
        loading = false
    }

    fun runNow() {
        if (filters.isEmpty) browseAll = true
        applied = filters
        page = 1
        runCount += 1
    }

    // THE PAGE GOES BACK TO 1 WHEN THE SCOPE CHANGES, as it does for every other filter on this
    // screen. Narrowing from four pages of results to one while sitting on page 4 produces five empty
    // buckets and a "No more results" message — for a scope that plainly has records in it. Keyed on
    // `requestKey` rather than on the raw set so re-picking the same two workshops in the other order
    // is not a change.
    LaunchedEffect(workshopScope.requestKey) { page = 1 }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showBackAction) {
            // The same arrow every other screen uses, not a pill: one action, one shape.
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        SearchCard(title = "Search", icon = Icons.Filled.Search) {
            Text(
                "Search across artisans, workshops, products, tools and media with shared API filters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = filters.query,
                onValueChange = { filters = filters.copy(query = it) },
                label = { Text("Search repository") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (filters.query.isNotBlank()) {
                        IconButton(onClick = { filters = filters.copy(query = "") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // The place box lives in the sheet with the rest of the filters rather than beside the
            // query box: one value with two controls on screen at once is exactly the confusion the
            // chips and the multi-select are carefully avoiding, and a phone has no width to spare.
            SearchFilterBar(
                value = filters,
                onChange = { filters = it },
                offeredTypes = offeredTypes,
                // Craft, artisan and media type are this screen's own, and the slot says so out
                // loud: the shared filters above them are one implementation, and an addition
                // declared here cannot quietly become a second copy that drifts.
                extraFilters = {
                    /*
                      DRAWN WHETHER OR NOT THEY HAVE ROWS, AND STOOD DOWN WITH A REASON WHEN THEY DO
                      NOT — which is the change, and it is R3 rather than a layout preference.

                      `if (crafts.isNotEmpty())` removed the control entirely, so a designer with no
                      signal opened the filter sheet and found a Craft filter that simply was not
                      there. Absent reads as "this screen has no such filter", which is a claim about
                      the app; the truth is a claim about the read, and §3.5 has the sentence for
                      whichever one it was. Disabled-with-a-reason is the same treatment
                      `DesignReviewScreen` gives its own picker and the same one the record forms now
                      give theirs.
                    */
                    SearchDropdownField(
                        label = "Craft",
                        options = crafts.map { craft ->
                            craft.id to listOfNotNull(
                                craft.name.ifBlank { "Untitled craft" },
                                craft.place?.takeIf { it.isNotBlank() }
                            ).joinToString(" · ")
                        },
                        selectedValue = filters.craftId,
                        placeholder = "Any craft",
                        emptyMessage = registerListNotice("crafts", crafts.size, craftRegister),
                        enabled = crafts.isNotEmpty(),
                        onSelect = { filters = filters.copy(craftId = it) }
                    )
                    SearchDropdownField(
                        label = "Artisan",
                        options = artisans.map { artisan ->
                            artisan.id to listOf(artisan.name, artisan.place)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                        },
                        selectedValue = filters.artisanId,
                        placeholder = "Any artisan",
                        emptyMessage = registerListNotice("artisans", artisans.size, artisanRegister),
                        enabled = artisans.isNotEmpty(),
                        onSelect = { filters = filters.copy(artisanId = it) }
                    )
                    // Class (a): `SEARCH_MEDIA_TYPES` is compiled into this APK and cannot be empty,
                    // so §3.1 gives it no sentence and it passes none.
                    SearchDropdownField(
                        label = "Media type",
                        options = SEARCH_MEDIA_TYPES.map { it to it },
                        selectedValue = filters.mediaType,
                        placeholder = "Any media type",
                        onSelect = { filters = filters.copy(mediaType = it) }
                    )
                    Text(
                        "Craft, artisan and media type narrow only the buckets that carry them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            // Below the filters and above Search, which is where the web puts it. It applies
            // IMMEDIATELY, like the chips and unlike the typed boxes: a picker that needed a second
            // button press to take effect reads as broken.
            WorkshopScopeSelect(scope = workshopScope, label = "Workshops")

            Button(onClick = { runNow() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "Searching…" else "Search")
            }

            // A CODE IS A SEARCH WHOSE QUERY IS EXACT, so it sits with the search box rather than
            // behind a destination of its own: when a designer has the tag in their hand there is
            // nothing to type and nothing to narrow. It reads every record type this app prints a
            // code for and hands the hit back through the SAME `onOpenRecord` a tapped result uses,
            // so a code and a search hit for one record cannot lead to two different places.
            RecordCodeLookupPanel(repository = repository, onOpen = onOpenRecord)

            error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        val data = results
        when {
            data == null && loading -> SearchCard(title = "Searching…") {
                Text(
                    // COUNTED FROM `offeredTypes`, never written out as a word. This sentence read
                    // "all five record types" until the sixth bucket landed, at which point it was
                    // wrong for every professor and right for everybody else — the shape of error a
                    // literal count in copy always takes, and the same one the web's guide header
                    // paid for. It is also correctly FIVE for a reader who is not offered the sixth.
                    if (applied.types.isEmpty()) {
                        "Looking across all ${offeredTypes.size} record types."
                    } else {
                        "Looking in ${applied.types.size} of the ${offeredTypes.size} record types."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            data == null -> SearchCard(title = "Nothing searched yet") {
                Text(
                    "Type what you are looking for — a name, a place, a filename — and results appear as " +
                        "you pause. A chip or a date on its own is a question too. Press Search on an " +
                        "empty form to list the most recent records instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                // Filtered against `applied`, not the live `filters`: these buckets describe rows
                // that are already on screen, and they must not disappear the instant a chip is
                // touched but before the response for it has landed.
                //
                // Dropped on the CLIENT as well as in the request, because `types` is the one filter
                // key an older deployment may not know: a server that ignores it answers with all
                // five buckets, and a screen that trusted that would show artisans to a researcher
                // who asked for media.
                val allBuckets = remember(data) { data.toSearchBuckets() }
                val buckets = remember(allBuckets, applied, offeredTypes) {
                    // NARROWED BY `offeredTypes` AS WELL AS BY THE TICKS, so a bucket this reader is
                    // not offered cannot appear on screen at all — a stale `applied` (a screen left
                    // open across a role change) must not put a section here that the chips above it
                    // can neither express nor clear. The web drops the same bucket from its render
                    // for the same reason.
                    allBuckets.filter { it.recordType in offeredTypes && applied.includes(it.recordType) }
                }
                val shown = buckets.sumOf { it.rows.size }
                val matched = buckets.sumOf { it.total }
                // The API reports `pageCount` (the last page of its LONGEST bucket), so Next is
                // exact — but that longest bucket may be one this search is not showing, so the page
                // count is re-derived from the SELECTED buckets' own totals. The last fallback —
                // "some bucket came back full" — keeps this working against an API that predates
                // those keys; it can walk one page too far when a bucket's total is an exact
                // multiple of the page size, which is precisely why the server-side counts win.
                val hasMore = if (data.totalsReported()) {
                    val selectedMax = buckets.maxOfOrNull { it.total } ?: 0
                    val pageCount = maxOf(1, (selectedMax + SEARCH_PAGE_SIZE - 1) / SEARCH_PAGE_SIZE)
                    page < pageCount
                } else {
                    buckets.any { it.rows.size == SEARCH_PAGE_SIZE }
                }

                SearchCard(title = "Results") {
                    Text(
                        if (data.totalsReported()) {
                            val scope = if (applied.types.isEmpty()) {
                                "across every record type"
                            } else {
                                "in the selected record types"
                            }
                            "$matched match${if (matched == 1) "" else "es"} $scope."
                        } else {
                            "$shown result${if (shown == 1) "" else "s"} on this page."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // WHAT THE SERVER REFUSED. A bucket that comes back empty is indistinguishable
                    // from a repository with nothing in it, so a bucket this account may not read
                    // has to be NAMED — the difference between "no design workshops matched" and
                    // "design workshops were not looked at". The LIST is the server's; the sentence
                    // is this client's, because it names the next move and an API has no business
                    // writing a screen's copy. It is reachable in practice from a link or a stale
                    // filter set, both of which can ask for a bucket the chips no longer offer.
                    if (data.typesRefused.isNotEmpty()) {
                        Text(
                            "Design workshops are not searched at your access level. Ask an admin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (shown == 0) {
                        HorizontalDivider(color = MaterialTheme.field.hairline)
                        Text(
                            if (page > 1) "No more results" else "No matching records",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (page > 1) {
                            Text(
                                "Every result type has run out on this page. Go back to see the earlier matches.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                buckets.filter { it.rows.isNotEmpty() }.forEach { bucket ->
                    SearchBucketSection(bucket = bucket, onOpenRecord = onOpenRecord)
                }

                SearchPager(
                    page = page,
                    shown = shown,
                    hasMore = hasMore,
                    loading = loading,
                    onPage = { page = it }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Result model
// ---------------------------------------------------------------------------------------------

/**
 * One tappable result. Deliberately carries no id in anything it RENDERS — [id] exists only to hand
 * back to `onOpenRecord`; the design system never shows an internal id to a researcher.
 */
internal data class SearchRow(
    val recordType: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val date: String?,
    /**
     * WHERE INSIDE THE RECORD THE QUERY MATCHED, when the match is on nothing the row prints.
     *
     * Only the design-workshop bucket fills it today: that bucket matches stage ANSWERS as well as
     * the workshop's own columns, so a hit whose word is in neither the title nor the subtitle is
     * otherwise unaccountable. The strings are the SERVER'S resolved stage names — this app holds no
     * copy of the twenty-two titles, and one built here would disagree the day a stage was retitled.
     */
    val matchedIn: List<String> = emptyList()
)

/** A result type with its page of rows and how many matches it has in total. */
private data class SearchBucket(
    /** A [SearchRecordTypes] value. Carried on the bucket, not read off its first row, because an
     *  EMPTY bucket still has to be nameable — that is exactly the one a type filter has to match. */
    val recordType: String,
    val title: String,
    val total: Int,
    val rows: List<SearchRow>,
    /**
     * Whether tapping a row of this bucket goes anywhere. TRUE for the five record buckets and
     * FALSE for design workshops, which is a limitation stated rather than a tap that misfires.
     *
     * A tapped row reports through `onOpenRecord`, and the host resolves that string in
     * `MainActivity.searchRecordEntryMode`, whose `else` is `EntryMode.ARTISAN`. There is no
     * `EntryMode` for a design workshop — the handset reaches one through `Screen.DesignWorkshops`,
     * a different route entirely — so a tappable row here would open somebody's fortnight of
     * fieldwork AS AN ARTISAN. That is the same trap `RecordCodeLookup` declined to walk into with a
     * scanned `G` code, written up at length beside its `DESIGN_WORKSHOP` branch, and the answer is
     * the same: do not report a type the host cannot route.
     *
     * FINDING THE WORKSHOP IS THE FEATURE; opening it from here is not. The row names it, says which
     * stage matched, and [noRouteNote] says where to open it. Wiring the tap needs a route the host
     * owns and is a change to `MainActivity`, which this lane does not touch.
     */
    val openable: Boolean = true,
    /** Printed under the heading when [openable] is false: where a row of this bucket is opened. */
    val noRouteNote: String? = null,
    /** The server's own sentence about what a text query in this bucket matched, when it sent one. */
    val scopeNote: String? = null
)

/**
 * True when the response carries the per-bucket counts. `totals`/`total` default to zero in the DTO,
 * so "all zero while rows came back" is how an older API that never sent them looks — and the only
 * case where the page has to fall back to guessing at a next page.
 */
private fun SearchResultsDto.totalsReported(): Boolean =
    total > 0 || totals.artisans > 0 || totals.workshops > 0 || totals.products > 0 ||
        totals.tools > 0 || totals.media > 0 || totals.designWorkshops > 0

private fun SearchResultsDto.toSearchBuckets(): List<SearchBucket> {
    val reported = totalsReported()
    fun total(counted: Int, rows: Int) = if (reported) counted else rows

    val artisanRows = artisans.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.ARTISAN,
            id = item.id,
            title = item.name.ifBlank { "Unnamed artisan" },
            // The craft relation is not expanded by /search, so it is shown only when it is there —
            // printing "No craft" for every row would state something the response never claimed.
            subtitle = listOfNotNull(
                item.place.takeIf { it.isNotBlank() },
                item.craft?.name?.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            status = item.status,
            date = item.createdAt
        )
    }
    val workshopRows = workshops.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.WORKSHOP,
            id = item.id,
            title = item.title.ifBlank { "Untitled workshop" },
            subtitle = item.place,
            status = item.status,
            date = item.startDate ?: item.date
        )
    }
    val productRows = products.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.PRODUCT,
            id = item.id,
            title = item.productName.ifBlank { "Untitled product" },
            subtitle = listOf(item.craftName, item.artisanName, item.place)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            status = item.status,
            date = item.createdAt
        )
    }
    val toolRows = tools.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.TOOL,
            id = item.id,
            title = item.toolkitName.ifBlank { "Untitled toolkit" },
            subtitle = listOf(item.craftName, item.artisanName, item.place)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            status = item.status,
            date = item.createdAt
        )
    }
    val mediaRows = media.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.MEDIA,
            id = item.id,
            title = item.caption?.trim()?.takeIf { it.isNotEmpty() } ?: item.originalFilename,
            subtitle = listOfNotNull(item.mediaType.takeIf { it.isNotBlank() }, item.mimeType?.takeIf { it.isNotBlank() })
                .joinToString(" · "),
            // A media file carries no review status of its own in this payload; the badge is skipped.
            status = "",
            date = item.createdAt
        )
    }

    val designWorkshopRows = designWorkshops.map { item ->
        SearchRow(
            recordType = SearchRecordTypes.DESIGN_WORKSHOP,
            id = item.id,
            title = item.title.ifBlank { "Untitled design workshop" },
            // The PROMOTED columns, which are the axes a researcher filters on — and which are null
            // until stage 1 has been saved, so a freshly opened workshop legitimately shows a title
            // and nothing else rather than a row of the word "null". Same four, in the same order,
            // as the web's design-workshop result row.
            subtitle = listOfNotNull(
                item.workshopCode?.takeIf { it.isNotBlank() },
                item.craftName?.takeIf { it.isNotBlank() },
                (item.clusterName ?: item.district)?.takeIf { it.isNotBlank() },
                item.state?.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            status = item.status,
            date = item.startDate,
            matchedIn = designWorkshopStageMatches[item.id].orEmpty()
        )
    }

    return listOf(
        SearchBucket(SearchRecordTypes.ARTISAN, "Artisans", total(totals.artisans, artisanRows.size), artisanRows),
        SearchBucket(SearchRecordTypes.WORKSHOP, "Workshops", total(totals.workshops, workshopRows.size), workshopRows),
        SearchBucket(SearchRecordTypes.PRODUCT, "Products", total(totals.products, productRows.size), productRows),
        SearchBucket(SearchRecordTypes.TOOL, "Tools", total(totals.tools, toolRows.size), toolRows),
        SearchBucket(SearchRecordTypes.MEDIA, "Media", total(totals.media, mediaRows.size), mediaRows),
        SearchBucket(
            SearchRecordTypes.DESIGN_WORKSHOP,
            "Design workshops",
            total(totals.designWorkshops, designWorkshopRows.size),
            designWorkshopRows,
            // See [SearchBucket.openable]: there is no `EntryMode` for a design workshop, and the
            // host's fallback for an unrecognised record type is ARTISAN.
            openable = false,
            noRouteNote = "Open these from Design workshops.",
            scopeNote = designWorkshopSearchScope
        )
    )
}

// ---------------------------------------------------------------------------------------------
// Quick search
//
// The same query for screens that search in order to GO somewhere rather than to list results.
// Shared from here so the app has one debounce, one flattening rule and one result row, instead of
// a second search that drifts away from this one.
// ---------------------------------------------------------------------------------------------

/** How many hits a quick search shows. Short enough to sit above a screen's own content. */
internal const val QUICK_SEARCH_LIMIT = 8

/** Below this a query matches so much of the repository that the list is noise, not a shortlist. */
internal const val QUICK_SEARCH_MIN_CHARS = 2

/**
 * A debounced search over the same [SearchFilters] the full screen uses, owned by
 * [rememberQuickSearch] and written only from its effect.
 *
 * Single-flight by construction: the caller re-keys the effect on the whole filter value, so a
 * superseded request is cancelled before its response can land on top of a newer one.
 */
@Stable
internal class QuickSearchState(private val repository: WorkshopRepository) {
    var hits by mutableStateOf<List<SearchRow>>(emptyList())
        private set

    /** Matches across the SELECTED buckets, so "of N" can never promise more than the filter allows. */
    var total by mutableStateOf(0)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** A query has actually run — the difference between "no matches" and "nothing asked yet". */
    var searched by mutableStateOf(false)
        private set

    /**
     * The typed text the last scheduled run carried, so a clicked filter can skip the typist's
     * pause. Seeded with the empty pair rather than null: the first chip tapped on an untouched
     * panel is a deliberate click too, and should not sit through a debounce meant for keystrokes.
     */
    private var scheduledTyped: Pair<String, String> = SearchFilters().typed

    /** True when [typed] is not what the last run was scheduled for — i.e. typing is still settling. */
    fun awaitsTyping(typed: Pair<String, String>): Boolean {
        val changed = scheduledTyped != typed
        scheduledTyped = typed
        return changed
    }

    /** Back to "nothing asked": what a blank, too-short and unfiltered form shows. */
    fun reset() {
        hits = emptyList()
        total = 0
        loading = false
        error = null
        searched = false
        // Nothing is scheduled any more either, so the next word typed gets its full pause back and
        // the next chip tapped on the emptied form still answers at once.
        scheduledTyped = SearchFilters().typed
    }

    suspend fun run(filters: SearchFilters, limit: Int) {
        loading = true
        try {
            val (dateFrom, dateTo) = filters.resolveDateRange()
            val results = repository.search(
                q = filters.query.trim().ifBlank { null },
                craftId = filters.craftId.ifBlank { null },
                place = filters.place.trim().ifBlank { null },
                artisanId = filters.artisanId.ifBlank { null },
                mediaType = filters.mediaType.ifBlank { null },
                types = filters.bucketTypes(),
                dateFrom = dateFrom,
                dateTo = dateTo,
                page = 1,
                pageSize = limit
            )
            hits = results.toQuickHits(filters, limit)
            total = results.selectedTotal(filters)
            error = null
            searched = true
        } catch (cancelled: CancellationException) {
            // Superseded by a newer keystroke, or the screen was left. Neither is a failed search,
            // and `loading` now belongs to the pass that replaced this one — so rethrow rather than
            // fall through. See the same guard on the full screen's own request.
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.apiErrorMessage("Search failed")
        }
        loading = false
    }
}

/**
 * A [QuickSearchState] bound to [filters] and to this composition.
 *
 * Re-keying on the whole filter value is what cancels both the pending debounce and any request
 * already in flight, so only the newest state of the form can produce hits.
 */
@Composable
internal fun rememberQuickSearch(
    repository: WorkshopRepository,
    filters: SearchFilters,
    limit: Int = QUICK_SEARCH_LIMIT
): QuickSearchState {
    val state = remember(repository) { QuickSearchState(repository) }
    // Trimmed before it becomes the effect key, so a trailing space is not a new question.
    val request = remember(filters) { filters.copy(query = filters.query.trim(), place = filters.place.trim()) }
    LaunchedEffect(state, request, limit) {
        // Two characters of text OR any filter at all. A chip on its own is a real question here —
        // "the media from this workshop week" — and answering it with a blank panel reads as broken.
        if (request.query.length < QUICK_SEARCH_MIN_CHARS && !request.hasFilters) {
            state.reset()
            return@LaunchedEffect
        }
        if (state.awaitsTyping(request.typed)) delay(SEARCH_DEBOUNCE_MILLIS)
        state.run(request, limit)
    }
    return state
}

/**
 * The selected buckets flattened into one shortlist, round-robin rather than concatenated:
 * `GET /search` fills every bucket to the same page size, so appending them in order would spend the
 * whole list on artisans and hide the workshop the researcher was actually typing.
 *
 * The type filter is applied here as well as in the request, for the same reason the full screen
 * applies it twice: a deployment that does not know `types` yet answers with all five buckets.
 */
private fun SearchResultsDto.toQuickHits(filters: SearchFilters, limit: Int): List<SearchRow> {
    val buckets = toSearchBuckets()
        .filter { filters.includes(it.recordType) }
        .map { it.rows }
        .filter { it.isNotEmpty() }
    val hits = mutableListOf<SearchRow>()
    var index = 0
    while (hits.size < limit && buckets.any { index < it.size }) {
        for (rows in buckets) {
            if (hits.size == limit) break
            rows.getOrNull(index)?.let { hits += it }
        }
        index++
    }
    return hits
}

/** Matches in the buckets this search is showing. Falls back to row counts on an API without totals. */
private fun SearchResultsDto.selectedTotal(filters: SearchFilters): Int =
    toSearchBuckets().filter { filters.includes(it.recordType) }.sumOf { it.total }

// ---------------------------------------------------------------------------------------------
// Result rendering
// ---------------------------------------------------------------------------------------------

@Composable
private fun SearchBucketSection(bucket: SearchBucket, onOpenRecord: (String, String) -> Unit) {
    SearchCard(title = bucket.title, trailing = {
        SearchCountPill(
            text = if (bucket.total > bucket.rows.size) "${bucket.rows.size} of ${bucket.total}" else "${bucket.total}",
            emphasised = true
        )
    }) {
        // The SERVER'S sentence about what a text query in this bucket matched, printed where the
        // matches are — the web prints the same string under the same heading. Nothing is invented
        // here: a client that wrote its own would be a second description of one rule.
        bucket.scopeNote?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // WHERE A ROW OF THIS BUCKET IS OPENED, when it is not opened from here. Said once at the
        // top rather than on every row, and said at all because a list of records that do not
        // respond to a tap reads as a broken screen. See [SearchBucket.openable].
        bucket.noRouteNote?.takeIf { !bucket.openable && it.isNotBlank() }?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        bucket.rows.forEach { row ->
            SearchResultRow(
                row = row,
                onOpen = { onOpenRecord(row.recordType, row.id) },
                openable = bucket.openable
            )
        }
    }
}

/**
 * One result.
 *
 * [openable] false draws the row without the "Open ›" affordance and without a click target, which
 * is the honest rendering for a bucket whose records this app cannot route to — see
 * [SearchBucket.openable]. It is NOT `enabled = false`: a greyed row would say "this record is
 * unavailable", when the record is perfectly available and it is this screen that has nowhere to
 * send it. The section prints where to open it instead.
 */
@Composable
internal fun SearchResultRow(row: SearchRow, onOpen: () -> Unit, openable: Boolean = true) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.medium)
            .then(if (openable) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (openable) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "Open ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (row.subtitle.isNotBlank()) {
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // WHY THIS ROW CAME BACK, when nothing above it says so. A design-workshop hit can be on an
        // answer inside one of twenty-two stages, and a researcher who cannot see which one has to
        // open all of them. Only the first few are named and the REMAINDER IS COUNTED — a list that
        // quietly stops would understate how much of the fortnight the word appears in.
        if (row.matchedIn.isNotEmpty()) {
            val shown = row.matchedIn.take(MATCHED_IN_SHOWN).joinToString(" · ")
            val extra = row.matchedIn.size - MATCHED_IN_SHOWN
            Text(
                "Matched in $shown" + if (extra > 0) " · and $extra more" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (row.status.isNotBlank()) SearchStatusBadge(row.status)
            Text(
                formatSearchDateTime(row.date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Prev/Next footer. It states only what the contract really knows: which page this is, how many rows
 * it holds, and that all five result types page together — the buckets do not have pagers of their own.
 */
@Composable
private fun SearchPager(page: Int, shown: Int, hasMore: Boolean, loading: Boolean, onPage: (Int) -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Page $page · $shown result${if (shown == 1) "" else "s"} on this page · every result type pages together",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onPage(page - 1) },
                    enabled = !loading && page > 1,
                    modifier = Modifier.weight(1f)
                ) { Text("Previous") }
                OutlinedButton(
                    onClick = { onPage(page + 1) },
                    enabled = !loading && hasMore,
                    modifier = Modifier.weight(1f)
                ) { Text("Next") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The shared filter bar
//
// ONE implementation, used by the search screen and by the Data Browser's search panel. The nav bar
// and the drawer were each written twice in this app and each pair drifted; a filter set is worse,
// because the divergence is silent — the same question simply answers differently depending on
// which screen you asked it from.
// ---------------------------------------------------------------------------------------------

/** How a chip reads: the filter, a member of a multi-type filter, or off. */
private enum class ChipTone { ON, PART, OFF }

/**
 * The six category chips, the sheet button, and the sheet behind it.
 *
 * THE CHIPS AND THE MULTI-SELECT ARE ONE PIECE OF STATE, not two. [SearchFilters.types] is the only
 * store of which types are being searched; the chip row and the checkbox list are two editors of
 * that same set, which is why they cannot fall out of step:
 *
 *   - a chip is the shortcut for "only this" — tapping one REPLACES the set with that single type,
 *     and Everything empties it;
 *   - a checkbox adds or removes one member and leaves the rest alone.
 *
 * The chips keep saying what the set is even when the set is something chips alone cannot express:
 * with two or more types selected no chip is the solid "this is the filter" fill, the members are
 * drawn in the lighter included style instead, and a line of text says how many are in play.
 *
 * A bottom sheet rather than the inline disclosure the web uses: three filters and a five-way tick
 * list unfolding in place would push the results off a phone screen every time they were consulted.
 *
 * @param extraFilters screen-specific fields, appended below the shared ones. A declared slot rather
 *   than a second bar — an addition that has to be passed in cannot quietly become a copy.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SearchFilterBar(
    value: SearchFilters,
    onChange: (SearchFilters) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The buckets this reader is OFFERED, in [SearchRecordTypes.ALL] order. Defaults to all of them.
     *
     * It exists for the sixth bucket alone: design-workshop stage data is Professor and above, and
     * the server drops the bucket for anybody else and names it in `typesRefused`. A chip whose
     * every use is refused is a control that teaches a researcher the app is broken, so the tick box
     * is not offered where the answer cannot be read. The DEFAULT is every bucket, so the Data
     * Browser's copy of this bar is untouched — a panel that searches for a file path has no reason
     * to grow a permission question, and its five buckets are what it has always shown.
     */
    offeredTypes: List<String> = SearchRecordTypes.ALL,
    extraFilters: (@Composable ColumnScope.() -> Unit)? = null
) {
    // Not seeded from any parameter, so an unkeyed remember is right here: whether the sheet is open
    // belongs to this composition and to nothing else.
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hidden = value.sheetFilterCount

    fun close() {
        // Hide first so the sheet slides away instead of vanishing; the flag drops once it has.
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) open = false }
    }

    fun toggleType(recordType: String) {
        val next = if (recordType in value.types) value.types - recordType else value.types + recordType
        // Stored in bucket order so the state reads the same as the row of chips above it, whatever
        // order the ticks went in. `bucketTypes()` re-derives it anyway; this keeps the state honest.
        // Ordered by [SearchRecordTypes.ALL] and NOT by `offeredTypes`, deliberately: this is the
        // canonical order the chips, the checkboxes and the API all read in, and a per-reader order
        // would make two accounts send the same question as two different query strings. Anything
        // not offered cannot be in `next` — there is no control for it.
        onChange(value.copy(types = SearchRecordTypes.ALL.filter { it in next }.toSet()))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchChip(
                text = "Everything",
                tone = if (value.types.isEmpty()) ChipTone.ON else ChipTone.OFF,
                onClick = { onChange(value.copy(types = emptySet())) }
            )
            offeredTypes.forEach { recordType ->
                SearchChip(
                    text = SearchRecordTypes.label(recordType),
                    tone = when {
                        value.types.size == 1 && recordType in value.types -> ChipTone.ON
                        recordType in value.types -> ChipTone.PART
                        else -> ChipTone.OFF
                    },
                    onClick = { onChange(value.copy(types = setOf(recordType))) }
                )
            }
            SearchChip(
                text = if (hidden > 0) "Filters · $hidden" else "Filters",
                tone = if (open || hidden > 0) ChipTone.PART else ChipTone.OFF,
                icon = Icons.Filled.FilterList,
                onClick = { open = true }
            )
        }

        if (value.types.size > 1) {
            Text(
                "Searching ${value.types.size} record types. A chip narrows to just that one; " +
                    "tick more under Filters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Outside the scroll, so the keyboard SHRINKS the scrollable area rather than
                    // padding the content inside it — the place box is the last thing that should
                    // end up underneath the IME, and the sheet has its own window, which the
                    // activity's inset handling does not reach.
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = value.place,
                    onValueChange = { onChange(value.copy(place = it)) },
                    label = { Text("Place") },
                    placeholder = { Text("Any place") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                SearchDropdownField(
                    label = "Record time",
                    options = SearchRange.entries.map { it.name to it.label },
                    selectedValue = value.range.name,
                    placeholder = SearchRange.ANY.label,
                    // "Any time" is a real choice in this list, so there is no blank row above it.
                    allowNone = false,
                    onSelect = { picked -> onChange(value.copy(range = SearchRange.valueOf(picked))) }
                )

                if (value.range == SearchRange.CUSTOM) {
                    // Stacked, not side by side: each end is now a typed dd/mm/yyyy box with a
                    // calendar button in its trailing slot, and half a phone width cannot hold both
                    // at a large system font. The web's own date grid splits only above 640px, so a
                    // single column is what a handset gets there too.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        // Each end bounds the other in the picker itself, so an inverted range —
                        // which matches nothing and looks like a broken filter — cannot be entered.
                        FieldDateField(
                            label = "From",
                            value = value.from,
                            onValueChange = { onChange(value.copy(from = it)) },
                            maximum = value.to,
                            placeholder = "Any date",
                            clearable = true
                        )
                        FieldDateField(
                            label = "To",
                            value = value.to,
                            onValueChange = { onChange(value.copy(to = it)) },
                            minimum = value.from,
                            placeholder = "Any date",
                            clearable = true
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Record types",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "The same setting as the chips above. Tick any number; nothing ticked " +
                            "searches everything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                offeredTypes.forEach { recordType ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { toggleType(recordType) }
                    ) {
                        Checkbox(checked = recordType in value.types, onCheckedChange = { toggleType(recordType) })
                        Text(
                            SearchRecordTypes.label(recordType),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.field.body
                        )
                    }
                }

                extraFilters?.invoke(this)

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (value.hasFilters) {
                        // Clears the FILTERS and keeps the query: they are separate questions, and
                        // wiping a typed name to widen a date range would be its own small betrayal.
                        TextButton(onClick = { onChange(SearchFilters(query = value.query)) }) {
                            Text("Clear all filters")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { close() }) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun SearchChip(text: String, tone: ChipTone, onClick: () -> Unit, icon: ImageVector? = null) {
    val background = when (tone) {
        ChipTone.ON -> MaterialTheme.colorScheme.primary
        ChipTone.PART -> MaterialTheme.colorScheme.primaryContainer
        ChipTone.OFF -> MaterialTheme.field.surface50
    }
    val foreground = when (tone) {
        ChipTone.ON -> MaterialTheme.colorScheme.onPrimary
        ChipTone.PART -> MaterialTheme.colorScheme.onPrimaryContainer
        ChipTone.OFF -> MaterialTheme.field.body
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            // Clipped before it is clickable, or the ripple squares off the pill.
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(background, CircleShape)
            .then(
                if (tone == ChipTone.OFF) Modifier.border(1.dp, MaterialTheme.field.hairline, CircleShape) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Local widgets
//
// Deliberate local copies of shapes that live inside MainActivity.kt (RecordCard, DropdownField,
// DatePickerField): that file is 10k lines and owned by one agent, so this screen restates the few
// pieces it needs rather than forcing an import out of it.
// ---------------------------------------------------------------------------------------------

@Composable
internal fun SearchCard(
    title: String,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

/**
 * A bucket's "3 of 12". It only REPORTS: choosing which buckets to search is the chips' job, and a
 * count that also filtered would be a second control for a setting already on screen.
 */
@Composable
private fun SearchCountPill(text: String, emphasised: Boolean) {
    val background = if (emphasised) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.field.surface100
    val foreground = if (emphasised) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.field.placeholder
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        maxLines = 1,
        modifier = Modifier
            .background(background, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** Review status, worded exactly as the web StatusBadge words it. */
@Composable
private fun SearchStatusBadge(status: String) {
    val key = status.uppercase(Locale.ROOT)
    val background: Color
    val foreground: Color
    when (key) {
        "APPROVED" -> {
            background = MaterialTheme.field.successContainer
            foreground = MaterialTheme.field.onSuccessContainer
        }
        "PENDING" -> {
            background = MaterialTheme.field.warningContainer
            foreground = MaterialTheme.field.onWarningContainer
        }
        "REJECTED" -> {
            background = MaterialTheme.colorScheme.errorContainer
            foreground = MaterialTheme.colorScheme.onErrorContainer
        }
        "NEEDS_REVISION" -> {
            background = MaterialTheme.colorScheme.primaryContainer
            foreground = MaterialTheme.colorScheme.onPrimaryContainer
        }
        else -> {
            background = MaterialTheme.field.surface100
            foreground = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Text(
        searchStatusLabel(key),
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        maxLines = 1,
        modifier = Modifier
            .background(background, CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

private fun searchStatusLabel(status: String): String = when (status) {
    "DRAFT" -> "Draft"
    "PENDING" -> "Pending"
    "APPROVED" -> "Approved"
    "REJECTED" -> "Rejected"
    "NEEDS_REVISION" -> "Needs revision"
    // SOME_STATUS -> "Some status", the same fallback the web badge uses.
    else -> status.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
}

/**
 * This screen's filter dropdowns — now a thin adapter over [SearchableSelectField].
 *
 * ── IT WAS A HAND-ROLLED MENU, AND §3.4 IS ABOUT WHAT THAT COST ───────────────────────
 *
 * Sixty lines duplicating `SelectTrigger` + `DropdownMenu`, down to `"▾"` and `"✓"` written out as
 * string glyphs — which are not spoken as anything useful, do not follow the type scale, and do not
 * change with the theme. That was the visible half. The half §3.4 is written about is that it could
 * not say WHICH of the four empty states it was in: with no options it drew a menu containing one
 * "Any craft" row and nothing else, which reads as "this repository documents no crafts".
 *
 * Everything the shared field brings is a thing this control silently lacked: the sheet above
 * [SEARCH_THRESHOLD] (749 artisans is not a menu), the diacritic-folding filter box, the
 * "N options / M of N match" live region, the pinned selection past the render cap, the IME commit
 * path, and a trigger whose accessible name is the label AND the value.
 *
 * ── `allowNone` KEEPS ITS MEANING AND ITS NAME ───────────────────────────────
 *
 * It maps to `includeNone`, which is the primitive's word for the same row. R1 — *empty means
 * everything, by absence* — is what that row expresses on a FILTER: the blank value is "do not
 * narrow by this", not an all-ticked state, and `SearchRange` opts out because "Any time" is already
 * a real member of its own vocabulary.
 */
@Composable
private fun SearchDropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    placeholder: String,
    onSelect: (String) -> Unit,
    /** False when "no filter" is already one of [options], so the list does not offer it twice. */
    allowNone: Boolean = true,
    /**
     * The caller's §3.5 sentence for an empty list, or null where the list cannot be empty.
     *
     * The two record-backed filters below pass one; the two constant vocabularies pass nothing,
     * which is §3.1 — a list compiled into the APK has no fact to report. NEVER "there are none".
     */
    emptyMessage: String? = null,
    enabled: Boolean = true
) {
    SearchableSelectField(
        label = label,
        options = remember(options) { options.map { SelectOption(value = it.first, label = it.second) } },
        selectedValue = selectedValue,
        placeholder = placeholder,
        includeNone = allowNone,
        enabled = enabled,
        emptyMessage = emptyMessage,
        onSelect = onSelect
    )
}

// ---------------------------------------------------------------------------------------------
// Dates
//
// The From/To filters used to be a local copy of MainActivity's DatePickerField, opening the
// PLATFORM android.app.DatePickerDialog. That dialog is styled from res/values/styles.xml, which
// the system picks by ITS OWN night setting and not by the app's appearance preference, so the
// calendar arrived in the opposite theme to the screen behind it whenever the two disagreed. Both
// ends are now ui/DateTimeFields.FieldDateField — one Compose control, one colour scheme, and the
// dd/mm/yyyy the web types in the same boxes.
// ---------------------------------------------------------------------------------------------

private val searchDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.getDefault())

/** The web's `formatDateTime` — "-" for a missing or unparseable value, never a raw ISO string. */
private fun formatSearchDateTime(value: String?): String {
    if (value.isNullOrBlank()) return "-"
    val instant = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
        ?: return "-"
    return runCatching { searchDateTimeFormatter.format(instant.atZone(ZoneId.systemDefault())) }.getOrElse { "-" }
}
