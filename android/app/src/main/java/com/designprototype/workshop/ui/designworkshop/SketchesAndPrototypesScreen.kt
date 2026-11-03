package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.isConnectionFailure
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.designWorkshopHint
import com.designprototype.workshop.ui.designWorkshopLabel
import com.designprototype.workshop.ui.designWorkshopPrefillNote
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException

@Composable
fun SketchesAndPrototypesScreen(
    repository: WorkshopRepository,
    onOpenStage: (workshopId: String, stageKey: String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onError: (String) -> Unit,
) {
    val user = remember { repository.cachedUser() }
    if (user == null || !FieldPermissions.canRunDesignWorkshops(user)) {
        SketchesAccessRefusal(roleLabel = FieldPermissions.label(user?.role))
        return
    }

    var workshops by remember { mutableStateOf<List<DesignWorkshopDto>?>(null) }
    val picker = remember { DwWorkshopPagedList(PAGE_SIZE) }
    val pickerScope = rememberCoroutineScope()
    val fetchWorkshops: suspend (Int, Int, String?) -> com.designprototype.workshop.data.DesignWorkshopPageDto =
        { page, size, search -> repository.designWorkshops(page = page, pageSize = size, search = search) }
    var listFailure by remember { mutableStateOf<String?>(null) }
    var listCachedNote by remember { mutableStateOf<String?>(null) }
    val chooserContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var attempt by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf("") }
    var prefillNote by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(DwSketchChooserTab.UPLOAD) }

    LaunchedEffect(attempt) {
        workshops = null
        listFailure = null
        listCachedNote = null
        picker.reset()
        runCatching {
            com.designprototype.workshop.data.ReadCache.read(
                chooserContext,
                "sketch-chooser",
                com.designprototype.workshop.data.DesignWorkshopPageDto.serializer(),
            ) { repository.designWorkshops(page = 1, pageSize = PAGE_SIZE) }
                .also { read ->
                    if (read.fromCache) listCachedNote = com.designprototype.workshop.data.readCacheNotice(read.cachedAt)
                }
                .value
        }
            .onSuccess { page ->
                workshops = page.items
                picker.seed(page.items, page.total)
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                workshops = emptyList()
                listFailure = if (repository.isConnectionFailure(error)) {
                    DW_SKETCH_CHOOSER_OFFLINE
                } else {
                    error.apiErrorMessage(DW_SKETCH_CHOOSER_REFUSED)
                }
            }

        val rows = workshops.orEmpty()
        if (rows.isNotEmpty()) {
            val answer = runCatching { repository.designWorkshopDefaultForMe() }
                .onFailure { error -> if (error is CancellationException) throw error }
                .getOrNull()
            val fromServer = answer?.workshopId?.trim().orEmpty()
            val wasBlank = chosen.isBlank()
            val next = dwChooserDefaultWorkshop(rows, fromServer, chosen)
            chosen = next
            prefillNote = when {
                !wasBlank -> prefillNote
                next.isNotBlank() && next == fromServer ->
                    designWorkshopPrefillNote(answer?.reason, answer?.accessAt)
                next.isNotBlank() -> DW_SKETCH_CHOOSER_FALLBACK_PREFILL
                else -> null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Sketches & prototypes",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            DW_SKETCH_CHOOSER_SUBTITLE,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp
        )

        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            val failure = listFailure
            val list = workshops
            when {
                failure != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        failure,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 18.sp
                    )
                    Text(
                        DW_SKETCH_CHOOSER_NOTHING_LOST,
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp
                    )
                }

                list == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text("Loading your workshops…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                }

                list.isEmpty() -> Text(
                    DW_SKETCH_CHOOSER_NO_WORKSHOPS,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )

                else -> Unit
            }
        }

        if (listFailure != null) {
            OutlinedButton(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
        }
        listCachedNote?.let { com.designprototype.workshop.ui.CachedCopyNote(it) }

        val rows = workshops.orEmpty()
        val shown = picker.rowsKeeping(chosen, rows)
        SearchableSelectField(
            label = "Which design workshop",
            options = shown.map { workshop ->
                SelectOption(
                    value = workshop.id,
                    label = designWorkshopLabel(workshop),
                    hint = listOfNotNull(
                        workshop.workshopCode?.takeIf { it.isNotBlank() },
                        designWorkshopHint(workshop),
                    ).joinToString(" · ").takeIf { it.isNotBlank() },
                )
            },
            selectedValue = chosen,
            placeholder = when {
                workshops == null -> "Looking for your workshops…"
                listFailure != null -> "This list could not be loaded"
                rows.isEmpty() -> "No workshops are listed for this account"
                else -> "Choose one of your design workshops"
            },
            includeNone = false,
            enabled = rows.isNotEmpty(),
            searchable = true,
            onSearch = { term -> picker.search(pickerScope, term, fetchWorkshops) },
            loadMore = picker.loadMoreAction(pickerScope, fetchWorkshops),
            onSelect = { id ->
                chosen = id
                prefillNote = null
            },
        )
        prefillNote?.let { note ->
            Text(note, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp)
        }

        DwSketchChooserTabStrip(
            selected = tab,
            onSelect = { tab = it },
            enabled = chosen.isNotBlank(),
        )

        if (chosen.isBlank()) {
            Text(
                DW_SKETCH_CHOOSER_PICK_FIRST,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp
            )
        } else {
            key(chosen) {
                DwSketchChooserTabPanel(tab) {
                    when (tab) {
                        DwSketchChooserTab.UPLOAD -> DwSketchChooserUploadTab(
                            repository = repository,
                            workshopId = chosen,
                            onOpenStage = onOpenStage,
                        )

                        DwSketchChooserTab.REVIEW -> DwSketchChooserReviewTab(
                            repository = repository,
                            workshopId = chosen,
                            onOpenStage = onOpenStage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SketchesAccessRefusal(roleLabel: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Sketches & prototypes",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Designer access required",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            DW_SKETCH_CHOOSER_DESIGNERS_ONLY,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
        )
        Text(
            "You're signed in as $roleLabel. Ask an admin for designer access.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal const val DW_SKETCH_CHOOSER_DESIGNERS_ONLY: String =
    "For designers and admins."

internal const val DW_SKETCH_CHOOSER_SUBTITLE: String =
    "Pick a workshop, then add pieces under Upload — trace, straighten or measure photos there — or see rankings under Review."

internal const val DW_SKETCH_CHOOSER_NO_WORKSHOPS: String =
    "You're not on a workshop yet. Ask an administrator to add you."

internal const val DW_SKETCH_CHOOSER_OFFLINE: String =
    "No connection — couldn't load your workshops. Try again."

internal const val DW_SKETCH_CHOOSER_REFUSED: String =
    "Couldn't list your workshops. Try again."

internal const val DW_SKETCH_CHOOSER_NOTHING_LOST: String =
    "Nothing is lost: a workshop has to be chosen before anything changes. Design workshops still lists what's on this phone."

internal const val DW_SKETCH_CHOOSER_FALLBACK_PREFILL: String =
    "Filled with your latest workshop, because your last one couldn't be found. Change it if needed."

internal const val DW_SKETCH_CHOOSER_PICK_FIRST: String =
    "Choose a workshop above."

internal const val DW_PROTOTYPE_3D_IN_THE_REPORT: String =
    "Stage 13 takes a 3D model and a 360° capture. The report shows the capture's photos and lists “1 3D model attached”, so photograph the piece as well."

internal const val DW_TURNTABLE_CAPTURE_ADVICE: String =
    "Walk around the piece: a photo every 30° (12) or 15° (24), same light and background."

internal const val DW_PROTOTYPE_MODEL_FORMATS: String =
    "GLB, glTF, STL, OBJ, PLY, 3MF, FBX or USDZ. GLB works best (one file)."

private const val PAGE_SIZE = 20

internal const val SKETCH_STAGE = "SKETCH_DEVELOPMENT"
internal const val PROTOTYPE_STAGE = "PROTOTYPE_DEVELOPMENT"
