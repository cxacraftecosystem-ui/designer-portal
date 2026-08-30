package com.designprototype.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.UsageClient
import com.designprototype.workshop.data.UsageCollectionDto
import com.designprototype.workshop.data.UsageRouteRow
import com.designprototype.workshop.data.UsageRoutesPageDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.usageCount
import com.designprototype.workshop.data.usageDurationText
import com.designprototype.workshop.data.usageMetricText
import com.designprototype.workshop.data.usageWindowStart
import com.designprototype.workshop.data.usageNowStamp
import com.designprototype.workshop.data.usageWithheld
import kotlinx.coroutines.launch

/**
 * **USAGE** — which screens are reached, how often, how fast, how often broken, aggregated across
 * every account. The handset's twin of the web's `/settings/usage`.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * NOT "ANALYTICS", AND THE COLLISION IS THE REASON
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `/admin/analytics` observes CRAFT OUTCOMES and no person at all. This observes PEOPLE — which
 * screens they reached, in what order the platform recorded them arriving. Calling both "analytics"
 * would leave every future reader to work out which one a name meant, in a product where one of the
 * two is a privacy surface. So this is titled, labelled and routed as "Usage" on both clients, and
 * `AdminHubEntry.USAGE` carries the web's tile wording verbatim.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE THREE RULES, COPIED FROM THE WEB PAGE BECAUSE THEY ARE THE SAME THREE RULES
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * 1. **Nothing here computes.** Every figure comes off the wire. [usageWithheld] is the only branch a
 *    reader needs, and a withheld row's numbers are `null` — which in Kotlin becomes `0` through
 *    `?: 0` and through every arithmetic helper anybody would add. A page that fell back would
 *    publish a number the server explicitly refused to state, about a screen too few accounts used
 *    to be safely reported.
 * 2. **The collection posture renders FIRST, above the figures.** A number with no stated method is
 *    a number nobody can check, and the posture is the one thing on this screen that says whether
 *    the rows below carry names at all.
 * 3. **The gate is mirrored, not invented.** `require_usage_reader` is Admin and above; this screen
 *    is only ever reached from `AdminHubEntry.USAGE`, and the hub itself is already behind
 *    `isAdmin && adminChrome`. So there is no predicate in this file — adding one would be a third
 *    copy of the rule and the one nobody updates.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT IS A HANDSET SHAPE AND NOT A COPY OF THE TABLE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The web draws an eight-column `min-w-[760px]` table in a horizontal scroller. That cannot survive
 * a phone: eight columns at 360dp means either a two-axis scroll nobody can read a row across, or
 * type at a size the accessibility settings this same app ships exist to prevent. **The FIGURES port
 * and the LAYOUT does not** — each screen is one card with its template on top and its six figures
 * laid out beneath, which reads down a thumb instead of across a desk.
 *
 * Three other differences, all stated rather than silently made:
 *
 *  * **A window CHIP and not a date pair.** Two date pickers on a phone is four taps to answer "how
 *    was last week"; the chips are the three windows an admin actually asks for. The API's `maxDays`
 *    is honoured and stated. Anybody needing an arbitrary range has the web page, which is where
 *    that question is comfortable.
 *  * **A smaller page.** [PAGE_SIZE] rather than the API's 50, because 50 cards is 350 text nodes
 *    composed at once in a Column that is already inside the app's scroller — and the cheapest
 *    handset in this fleet is the one an admin opens this on in a field office. The page count and
 *    the total are printed, so a smaller page is visible rather than a truncation.
 *  * **NO CHARTS.** The web page has none today, and a chart drawn on one client only would be two
 *    clients disagreeing about the same dataset — with the phone the more emphatic of the two. When
 *    the web grows them, the trap they introduce is already known and written down: a withheld route
 *    must be a GAP and never a plotted zero.
 */
@Composable
fun UsageScreen(
    repository: WorkshopRepository,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var windowDays by remember { mutableLongStateOf(WINDOW_CHOICES.first()) }
    var page by remember { mutableIntStateOf(1) }
    var method by remember { mutableStateOf<UsageCollectionDto?>(null) }
    var methodRead by remember { mutableStateOf<UsageReadState>(UsageReadState.Loading) }
    var routes by remember { mutableStateOf<UsageRoutesPageDto?>(null) }
    var routesRead by remember { mutableStateOf<UsageReadState>(UsageReadState.Loading) }

    // Re-read at every load, not sampled once on entry — see the identical note on
    // [UsageRecordingScreen]. An admin who opened this in a lift and retried in the corridor
    // must not still be told the phone has no connection.
    var online by remember { mutableStateOf(repository.isOnline(context)) }

    suspend fun loadMethod() {
        methodRead = UsageReadState.Loading
        online = repository.isOnline(context)
        val answer = runCatching { UsageClient.of(context).usageCollection() }.getOrNull()
        method = answer
        methodRead = if (answer == null) UsageReadState.Failed else UsageReadState.Answered(1)
    }

    suspend fun loadRoutes() {
        routesRead = UsageReadState.Loading
        online = repository.isOnline(context)
        val answer = runCatching {
            UsageClient.of(context).usageRoutes(
                from = usageWindowStart(windowDays),
                to = usageNowStamp(),
                page = page,
                pageSize = PAGE_SIZE,
            )
        }.getOrNull()
        routes = answer
        routesRead = if (answer == null) UsageReadState.Failed else UsageReadState.Answered(answer.items.size)
    }

    // The method does not vary with the window, so it is loaded once. The rows are reloaded whenever
    // either of the two things that decide them moves.
    LaunchedEffect(Unit) { loadMethod() }
    LaunchedEffect(windowDays, page) { loadRoutes() }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                "Usage",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // ── 1. THE COLLECTION POSTURE, ABOVE EVERY FIGURE ─────────────────────────────────────
        CollectionPostureCard(method = method, state = methodRead, online = online) {
            scope.launch { loadMethod() }
        }

        // ── 2. THE WINDOW ─────────────────────────────────────────────────────────────────────
        PreferenceCard {
            Text(
                "Window",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WINDOW_CHOICES.forEach { days ->
                    FilterChip(
                        selected = windowDays == days,
                        onClick = {
                            windowDays = days
                            // Back to page 1: the row that was on page 3 of a week is not the row on
                            // page 3 of a quarter, and staying put would silently answer a different
                            // question than the one the chip asked.
                            page = 1
                        },
                        label = { Text("$days days") }
                    )
                }
            }
            routes?.window?.let { window ->
                Text(
                    "${window.days} days, up to ${window.maxDays} allowed in one request. " +
                        "Dates with no time are read as UTC midnight, and the range is " +
                        "${window.interval}.",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        // ── 3. THE FIGURES ────────────────────────────────────────────────────────────────────
        usageReadNotice(
            state = routesRead,
            noun = "usage figures",
            online = online,
            // ANSWERED AND EMPTY is a real state here and it is not "nothing happened": the page may
            // simply be past the end, or every route on it may have been withheld. Both are said,
            // because "no screens in this window" alone would be read as a quiet week.
            emptyLine = "No screens on this page of this window. That is not a claim that nothing " +
                "was used — a screen too few identified accounts reached is withheld rather than " +
                "reported, and the count of withheld screens is above."
        )?.let { notice ->
            Text(
                notice,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.field.muted,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
        if (routesRead is UsageReadState.Loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(loadingListLine("usage figures"), color = MaterialTheme.field.muted, fontSize = 12.sp)
            }
        }
        if (routesRead is UsageReadState.Failed) {
            Button(onClick = { scope.launch { loadRoutes() } }) { Text("Try again") }
        }

        routes?.let { data ->
            PreferenceCard {
                Text(
                    "This page",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                // NAMED "This page" AND NOT "Total", following the server's own naming of the field
                // it comes from. A figure called "total" beside a paged list is read as the platform
                // figure by everybody, every time — and no arm of this API produces one.
                StatLine("Requests on this page", usageCount(data.totalsForThisPage.requests))
                StatLine("Succeeded", usageCount(data.totalsForThisPage.ok))
                StatLine(
                    "Client / server errors",
                    "${usageCount(data.totalsForThisPage.clientErrors)} / " +
                        "${usageCount(data.totalsForThisPage.serverErrors)}"
                )
                StatLine("Screens withheld", usageCount(data.totalsForThisPage.routesWithheld))
                if (data.totalsForThisPage.routesWithheld > 0) {
                    Text(
                        "Fewer than ${data.limits.minimumIdentifiedUsers} identified accounts used " +
                            "them in this window, so the server did not state their figures. They " +
                            "are excluded from the sums above rather than counted as zero.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
                Text(
                    if (data.routeSource == "mounted") {
                        "Every measured screen this deployment currently serves."
                    } else {
                        "The screens you asked about."
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
            }

            data.items.forEach { row -> RouteCard(row) }

            if (data.pages > 1) {
                PreferenceCard {
                    Text(
                        "Page ${data.page} of ${data.pages} — ${usageCount(data.total)} screens in total",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = data.page > 1 && routesRead !is UsageReadState.Loading,
                            onClick = { page = (page - 1).coerceAtLeast(1) }
                        ) { Text("Previous") }
                        OutlinedButton(
                            enabled = data.page < data.pages && routesRead !is UsageReadState.Loading,
                            onClick = { page = (page + 1).coerceAtMost(data.pages) }
                        ) { Text("Next") }
                    }
                }
            }

            if (data.notMeasured.isNotEmpty()) {
                Text(
                    // Excluded from the rows rather than reported as zero, and SAID: a row that is
                    // structurally always zero reads as "nobody uses this screen", and the two are
                    // opposite facts.
                    "Not measured, on any window: ${data.notMeasured.joinToString(", ")}.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            data.notes.forEach {
                Text("• $it", color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

/**
 * The consent and collection posture, drawn above every figure.
 *
 * `unaskedPolicy` IS COMPARED DIRECTLY and never sniffed out of a sentence. The web page carries the
 * scar: it used to test `collects.some(line => line.startsWith("The account id"))`, on the
 * assumption that only the ATTRIBUTED sentence began that way — and BOTH of the server's account-id
 * sentences share that prefix, one per policy, with only the wording after it saying which. The
 * result was that the one screen whose stated purpose is telling an admin the truth about this exact
 * fact told them the opposite of it. The enum token is on the wire; nothing here needs to guess.
 */
@Composable
private fun CollectionPostureCard(
    method: UsageCollectionDto?,
    state: UsageReadState,
    online: Boolean,
    onRetry: () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.warningContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.field.onWarningContainer,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (method == null) {
                    Text(
                        usageReadNotice(
                            state = state,
                            noun = "collection method",
                            online = online,
                            emptyLine = "This server did not say how these figures were collected."
                        ).orEmpty(),
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    if (state is UsageReadState.Failed) {
                        // WITHOUT THE METHOD THE FIGURES ARE STILL DRAWN, and this card says why that
                        // is a problem rather than hiding them: an admin reading counts with no
                        // stated posture cannot tell whether the rows below carry names.
                        Text(
                            "The figures below are still shown, but nothing on this screen can tell " +
                                "you whether they are attributed to accounts until this loads.",
                            color = MaterialTheme.field.onWarningContainer,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        Button(onClick = onRetry) { Text("Try again") }
                    }
                } else {
                    val attributed = method.consent.unaskedPolicy == "ATTRIBUTED"
                    Text(
                        if (method.consent.flowExists) {
                            "Consent is asked, and the rows below reflect what each account answered."
                        } else {
                            "No consent flow exists yet. This deployment's policy for the unasked is " +
                                method.consent.unaskedPolicy +
                                if (attributed) {
                                    " — requests ARE attributed to an account id, without having asked."
                                } else {
                                    "."
                                }
                        },
                        color = MaterialTheme.field.onWarningContainer,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Text(
                        method.consent.explanation,
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    method.consent.withdrawalCosts?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            color = MaterialTheme.field.onWarningContainer,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                    Text(
                        "Method: ${method.document}. Decision: ${method.consent.document}.",
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

/**
 * One screen's figures, as a card.
 *
 * **THE WITHHELD BRANCH IS THE WHOLE POINT OF THIS FUNCTION.** A withheld row has `null` in every
 * metric, and drawing it through the same six [StatLine]s as an ordinary row would render six
 * confident dashes with no explanation — or, one careless `?: 0` later, six zeroes claiming a screen
 * nobody used. So a withheld row gets its own shape: the reason the server gave, in place of the
 * figures it refused to give.
 */
@Composable
private fun RouteCard(row: UsageRouteRow) {
    PreferenceCard {
        Text(
            row.routeTemplate,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        if (usageWithheld(row)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.hairline, MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                // The em dash, drawn once and labelled, rather than six of them down the card. A dash
                // and a small number must not look alike, or a reader skims past a refusal as though
                // it were a quiet fortnight.
                Text("—", color = MaterialTheme.field.muted, fontSize = 16.sp)
                Text(
                    row.withheldBecause.orEmpty().ifBlank {
                        "The server did not state figures for this screen."
                    },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        } else {
            StatLine("Requests", usageMetricText(row.requests))
            StatLine("Identified accounts", usageMetricText(row.identifiedUsers))
            StatLine("OK", usageMetricText(row.ok))
            StatLine("Client / server errors", "${usageMetricText(row.clientErrors)} / ${usageMetricText(row.serverErrors)}")
            StatLine("Average duration", usageDurationText(row.avgDurationMs))
            StatLine("Longest", usageDurationText(row.maxDurationMs))
        }
    }
}

/**
 * One label and one figure, on one line, wrapping to two at a large font scale.
 *
 * `Alignment.Top` and a weighted label, so that at 200% text scale with "Larger text" also on, a
 * three-word label wraps under itself and the figure stays beside its own first line rather than
 * being pushed off the card.
 */
@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.field.muted, fontSize = 12.sp)
        Text(
            value,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

/**
 * The three windows a chip offers. Every one is inside the API's 366-day cap, which the response
 * states for itself and the screen prints.
 */
private val WINDOW_CHOICES: List<Long> = listOf(7L, 30L, 90L)

/** See the header: fewer cards than the API's page of 50, and the page count says so. */
private const val PAGE_SIZE: Int = 20
