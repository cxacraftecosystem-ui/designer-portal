package com.designprototype.workshop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Security
import com.designprototype.workshop.data.ArtisanDto
import com.designprototype.workshop.data.CraftDto
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException

/*
 * WHAT THIS APP'S SEARCH PANEL SEARCHES — the designer portal's half of `AppSearch.kt`.
 *
 * The shared file holds the rules and the UI; this one names this app's sources:
 *
 *  - RECORDS: `GET /search` (artisans, workshops, products, tools, media and design workshops — the
 *    server drops a bucket this account may not read, so nothing here re-implements that gate), plus
 *    the `search` filter of `GET /crafts`, `GET /processes`, `GET /questionnaire/interviews` and,
 *    for the roles that may author them, `GET /questionnaires`. Each source that fails is skipped;
 *    only when EVERY source fails for want of a network does the panel say it is offline.
 *  - OFFLINE: the artisans and crafts this screen already holds, which are what a field visit
 *    without signal most often needs to find.
 *  - SCREENS and SETTINGS: below.
 */

/** The name the page header and the search field carry — the old island wordmark's words. */
const val APP_SEARCH_NAME = "Design Workshop"

/** The settings rows a search can open, mapped to screens by the host. */
enum class AppSearchSettingTarget { APPEARANCE, SPEECH_AND_AI, AI_KEYS, USAGE_RECORDING, TWO_STEP }

/** Settings by name and by the words people use for them. */
fun appSearchSettingItems(open: (AppSearchSettingTarget) -> Unit): List<AppSearchItem> = listOf(
    setting("appearance", "Appearance & accessibility", Icons.Filled.Palette, AppSearchSettingTarget.APPEARANCE,
        listOf("theme", "dark mode", "light mode", "colour", "color", "accessibility"), open),
    setting("theme", "Theme", Icons.Filled.Palette, AppSearchSettingTarget.APPEARANCE,
        listOf("dark", "light", "system", "night"), open),
    setting("larger-text", "Larger text", Icons.Filled.Palette, AppSearchSettingTarget.APPEARANCE,
        listOf("font size", "text size", "zoom", "bigger"), open),
    setting("reduced-motion", "Reduced motion", Icons.Filled.Palette, AppSearchSettingTarget.APPEARANCE,
        listOf("animation", "motion", "transitions"), open),
    setting("speech", "Speech & AI", Icons.Filled.Mic, AppSearchSettingTarget.SPEECH_AND_AI,
        listOf("dictation", "voice", "speech", "offline speech", "language pack", "transcription"), open),
    setting("ai-keys", "My AI keys", Icons.Filled.Key, AppSearchSettingTarget.AI_KEYS,
        listOf("api key", "openai", "gemini", "anthropic", "ai"), open),
    setting("usage", "Usage recording", Icons.Filled.Policy, AppSearchSettingTarget.USAGE_RECORDING,
        listOf("consent", "analytics", "privacy", "tracking"), open),
    setting("two-step", "Two-step verification", Icons.Filled.Security, AppSearchSettingTarget.TWO_STEP,
        listOf("2fa", "two factor", "authenticator", "otp", "security", "verification code"), open),
)

private fun setting(
    key: String,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    target: AppSearchSettingTarget,
    keywords: List<String>,
    open: (AppSearchSettingTarget) -> Unit
) = AppSearchItem(AppSearchGroup.SETTINGS, key, title, icon, subtitle = "Settings", keywords = keywords) { open(target) }

/** The screens this account's menu offers — exactly [entries], which the host takes from `visibleNavItems`. */
fun appSearchScreenItems(entries: List<NavEntry>, open: (NavDestination) -> Unit): List<AppSearchItem> =
    entries.map { entry ->
        AppSearchItem(
            AppSearchGroup.SCREENS,
            entry.destination.name,
            entry.label,
            entry.icon,
            subtitle = entry.group?.label,
            keywords = listOfNotNull(entry.group?.label)
        ) { open(entry.destination) }
    }

/** The records this screen already holds, which is what the panel searches without a network. */
fun appSearchCachedItems(
    artisans: List<ArtisanDto>,
    crafts: List<CraftDto>,
    openRecord: (recordType: String, recordId: String) -> Unit
): List<AppSearchItem> =
    artisans.map { a ->
        AppSearchItem(AppSearchGroup.ARTISANS, a.id, a.name, Icons.Filled.Person, subtitle = a.place.ifBlank { null }) {
            openRecord(SearchRecordTypes.ARTISAN, a.id)
        }
    } + crafts.map { c ->
        AppSearchItem(
            AppSearchGroup.CRAFTS, c.id, c.name, Icons.Filled.Brush,
            subtitle = c.localName ?: c.place,
            keywords = listOfNotNull(c.localName)
        ) { openRecord(DwWorkshopRecordType.CRAFT.wire, c.id) }
    }

/**
 * Every server source for [query], at once. Throws [IOException] only when every source failed and
 * at least one of them for want of a network, which is the panel's cue to say it is offline.
 */
suspend fun appSearchRemoteItems(
    repository: WorkshopRepository,
    query: String,
    includeCustomQuestionnaires: Boolean,
    openRecord: (recordType: String, recordId: String) -> Unit,
    openCustomQuestionnaire: (questionnaireId: String) -> Unit
): List<AppSearchItem> = coroutineScope {
    val search = async { runCatching { repository.search(q = query, pageSize = 10) } }
    val crafts = async { runCatching { repository.searchCrafts(query) } }
    val processes = async { runCatching { repository.searchProcesses(query) } }
    val interviews = async { runCatching { repository.searchInterviews(query) } }
    val questionnaires = if (includeCustomQuestionnaires) {
        async { runCatching { repository.customQuestionnaires(pageSize = 10, search = query).items } }
    } else {
        null
    }
    val all = listOfNotNull(search.await(), crafts.await(), processes.await(), interviews.await(), questionnaires?.await())
    all.forEach { r -> (r.exceptionOrNull() as? CancellationException)?.let { throw it } }
    if (all.all { it.isFailure } && all.any { it.exceptionOrNull() is IOException }) {
        throw IOException("No search source could be reached")
    }

    val items = mutableListOf<AppSearchItem>()
    search.await().getOrNull()?.let { r ->
        r.artisans.forEach { a ->
            items += AppSearchItem(AppSearchGroup.ARTISANS, a.id, a.name, Icons.Filled.Person, subtitle = a.place.ifBlank { null }) {
                openRecord(SearchRecordTypes.ARTISAN, a.id)
            }
        }
        r.workshops.forEach { w ->
            items += AppSearchItem(AppSearchGroup.WORKSHOPS, w.id, w.title, Icons.Filled.Groups, subtitle = w.place.ifBlank { null }) {
                openRecord(SearchRecordTypes.WORKSHOP, w.id)
            }
        }
        r.designWorkshops.forEach { d ->
            items += AppSearchItem(
                AppSearchGroup.DESIGN_WORKSHOPS, d.id, d.title.ifBlank { d.workshopCode ?: "Design workshop" },
                Icons.Filled.DesignServices,
                subtitle = listOfNotNull(d.craftName, d.clusterName).joinToString(" · ").ifBlank { null }
            ) { openRecord(SearchRecordTypes.DESIGN_WORKSHOP, d.id) }
        }
        r.products.forEach { p ->
            items += AppSearchItem(
                AppSearchGroup.PRODUCTS, p.id, p.productName, Icons.Filled.Inventory2,
                subtitle = listOf(p.artisanName, p.place).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
            ) { openRecord(SearchRecordTypes.PRODUCT, p.id) }
        }
        r.tools.forEach { t ->
            items += AppSearchItem(
                AppSearchGroup.TOOLS, t.id, t.toolkitName, Icons.Filled.Build,
                subtitle = listOf(t.artisanName, t.place).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
            ) { openRecord(SearchRecordTypes.TOOL, t.id) }
        }
        r.media.forEach { m ->
            items += AppSearchItem(
                AppSearchGroup.MEDIA, m.id, m.originalFilename, Icons.Filled.PermMedia,
                subtitle = m.caption?.ifBlank { null } ?: m.mediaType
            ) { openRecord(SearchRecordTypes.MEDIA, m.id) }
        }
    }
    crafts.await().getOrNull()?.forEach { c ->
        items += AppSearchItem(AppSearchGroup.CRAFTS, c.id, c.name, Icons.Filled.Brush, subtitle = c.localName ?: c.place) {
            openRecord(DwWorkshopRecordType.CRAFT.wire, c.id)
        }
    }
    processes.await().getOrNull()?.forEach { p ->
        items += AppSearchItem(AppSearchGroup.PROCESSES, p.id, p.name, Icons.Filled.AccountTree, subtitle = p.product?.productName) {
            openRecord(DwWorkshopRecordType.PROCESS.wire, p.id)
        }
    }
    interviews.await().getOrNull()?.forEach { i ->
        items += AppSearchItem(AppSearchGroup.INTERVIEWS, i.id, i.title.ifBlank { "Untitled interview" }, Icons.Filled.Quiz, subtitle = i.place) {
            openRecord(DwWorkshopRecordType.QUESTIONNAIRE.wire, i.id)
        }
    }
    questionnaires?.await()?.getOrNull()?.forEach { q ->
        items += AppSearchItem(
            AppSearchGroup.QUESTIONNAIRES, q.id, q.title, Icons.AutoMirrored.Filled.ListAlt,
            subtitle = q.designWorkshopTitle ?: q.kindLabel
        ) { openCustomQuestionnaire(q.id) }
    }
    items
}

/** A search failure that is not "no network", in the words this app uses for API errors. */
fun appSearchErrorLine(error: Throwable): String =
    error.apiErrorMessage("Offline — showing what's on this phone.")
