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

        CollectionPostureCard(method = method, state = methodRead, online = online) {
            scope.launch { loadMethod() }
        }

        PreferenceCard {
            Text(
                "Window",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WINDOW_CHOICES.forEach { days ->
                    FilterChip(
                        selected = windowDays == days,
                        onClick = {
                            windowDays = days
                            page = 1
                        },
                        label = { Text("$days days") }
                    )
                }
            }
            routes?.window?.let { window ->
                Text(
                    "${window.days} days, up to ${window.maxDays} at a time.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp
                )
            }
        }

        usageReadNotice(
            state = routesRead,
            noun = "usage figures",
            online = online,
            emptyLine = "No screens on this page. Rarely used screens are hidden."
        )?.let { notice ->
            Text(
                notice,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp
            )
        }
        if (routesRead is UsageReadState.Loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(loadingListLine("usage figures"), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (routesRead is UsageReadState.Failed) {
            ActionRow {
                Button(onClick = { scope.launch { loadRoutes() } }) { Text("Try again") }
            }
        }

        routes?.let { data ->
            PreferenceCard {
                Text(
                    "This page",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                StatLine("Requests on this page", usageCount(data.totalsForThisPage.requests))
                StatLine("Succeeded", usageCount(data.totalsForThisPage.ok))
                StatLine(
                    "Failed (request / system)",
                    "${usageCount(data.totalsForThisPage.clientErrors)} / " +
                        "${usageCount(data.totalsForThisPage.serverErrors)}"
                )
                StatLine("Screens withheld", usageCount(data.totalsForThisPage.routesWithheld))
                if (data.totalsForThisPage.routesWithheld > 0) {
                    Text(
                        "Used by fewer than ${data.limits.minimumIdentifiedUsers} accounts — hidden and excluded from totals.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp
                    )
                }
                Text(
                    if (data.routeSource == "mounted") {
                        "Every measured screen."
                    } else {
                        "The screens you asked about."
                    },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            data.items.forEach { row -> RouteCard(row) }

            if (data.pages > 1) {
                PreferenceCard {
                    Text(
                        "Page ${data.page} of ${data.pages} — ${usageCount(data.total)} screens in total",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    ActionRow {
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
                    "${usageCount(data.notMeasured.size)} screens are never measured.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
            }
            data.notes.forEach {
                Text("• $it", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
            }
        }
    }
}

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
                            emptyLine = "Collection method unavailable."
                        ).orEmpty(),
                        color = MaterialTheme.field.onWarningContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 18.sp
                    )
                    if (state is UsageReadState.Failed) {
                        Text(
                            "Figures shown; attribution unknown until this loads.",
                            color = MaterialTheme.field.onWarningContainer,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 17.sp
                        )
                        ActionRow {
                            Button(onClick = onRetry) { Text("Try again") }
                        }
                    }
                } else {
                    val attributed = method.consent.unaskedPolicy == "ATTRIBUTED"
                    Text(
                        if (method.consent.flowExists) {
                            "Consent is asked; rows reflect each account's answer."
                        } else {
                            if (attributed) {
                                "Not asked first; recorded per account."
                            } else {
                                "Not asked first; recorded anonymously."
                            }
                        },
                        color = MaterialTheme.field.onWarningContainer,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 18.sp
                    )
                    Text(
                        method.consent.explanation,
                        color = MaterialTheme.field.onWarningContainer,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp
                    )
                    method.consent.withdrawalCosts?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            color = MaterialTheme.field.onWarningContainer,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteCard(row: UsageRouteRow) {
    PreferenceCard {
        Text(
            row.routeTemplate,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
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
                Text("—", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyLarge)
                Text(
                    row.withheldBecause.orEmpty().ifBlank {
                        "Figures for this screen are not shown."
                    },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp
                )
            }
        } else {
            StatLine("Requests", usageMetricText(row.requests))
            StatLine("Identified accounts", usageMetricText(row.identifiedUsers))
            StatLine("OK", usageMetricText(row.ok))
            StatLine("Failed (request / system)", "${usageMetricText(row.clientErrors)} / ${usageMetricText(row.serverErrors)}")
            StatLine("Average duration", usageDurationText(row.avgDurationMs))
            StatLine("Longest", usageDurationText(row.maxDurationMs))
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

private val WINDOW_CHOICES: List<Long> = listOf(7L, 30L, 90L)

private const val PAGE_SIZE: Int = 20
