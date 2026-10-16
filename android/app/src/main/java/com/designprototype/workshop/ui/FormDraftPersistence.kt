package com.designprototype.workshop.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.AppScope
import com.designprototype.workshop.data.FormDraftStore
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.data.URI_FIELD_PREFIX
import com.designprototype.workshop.data.formDraftIsWorthKeeping
import com.designprototype.workshop.data.formDraftLeftovers
import com.designprototype.workshop.data.formDraftUriList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The handle a form holds on its on-disk draft. See [FormDraftStore] for why this exists.
 *
 * [discard] is called by the form the moment its work is safe somewhere else — saved online, or
 * queued in the outbox — and BEFORE it navigates away, so the debounce cannot write the form back
 * after the save. [restoredAt] is non-null when the form opened with a draft from an earlier run.
 */
class FormDraftHandle internal constructor(
    private val context: Context,
    private val owner: String?,
    private val key: String?,
) {
    internal val finished = AtomicBoolean(false)

    /**
     * Every write and every clear of this form's file runs under this, in order. Without it a save
     * that was mid-way through copying a video when Save was pressed could land AFTER the clear, and
     * the saved form would come back as a "restored" draft next time.
     */
    internal val io = kotlinx.coroutines.sync.Mutex()

    /**
     * The values the form settled on after [discard] (it usually resets itself). Typing anything
     * different from them re-arms the draft, so a screen that stays open after a save — the media
     * upload screen, feedback — keeps protecting the next thing typed into it.
     */
    @Volatile
    internal var valuesAtDiscard: Map<String, String>? = null
    internal var baseline: Map<String, String> = emptyMap()
    internal var restoreFn: (Map<String, String>) -> Unit = {}

    /** Restored values whose boxes the form has not drawn yet; see [formDraftLeftovers]. */
    @Volatile
    internal var leftovers: Map<String, String> = emptyMap()

    var restoredAt by mutableStateOf<String?>(null)
        internal set

    /** The work is safe elsewhere: forget the draft, and stop writing it until something new is typed. */
    fun discard() {
        valuesAtDiscard = null
        finished.set(true)
        restoredAt = null
        val k = key ?: return
        AppScope.io.launch { io.withLock { runCatching { FormDraftStore.clear(context, owner, k) } } }
    }

    /** Put the form back the way it opened and forget the restored draft. */
    fun startOver() {
        restoredAt = null
        leftovers = emptyMap()
        restoreFn(baseline)
        val k = key ?: return
        AppScope.io.launch { io.withLock { runCatching { FormDraftStore.clear(context, owner, k) } } }
    }
}

/** Gallery picks already copied this process, so a draft write does not copy a video twice. */
private val durableCopies = ConcurrentHashMap<String, String>()
private val sweptThisProcess = AtomicBoolean(false)

/**
 * Keep this form's unsaved values on disk and give them back after the app was killed.
 *
 * [key] names the form (null disables persistence entirely, e.g. while an edit is still loading).
 * [snapshot] returns every value the form would lose, as strings; a key starting with
 * [URI_FIELD_PREFIX] holds newline-separated content Uris. [restore] puts a restored map back into
 * the form's state, and is also what "Start over" calls with the values the form opened with.
 *
 * Writes are debounced (600 ms) and also flushed when the form leaves composition, so a process kill
 * costs at most the last fraction of a second of typing. A form whose values equal what it opened
 * with keeps no draft at all.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberFormDraft(
    key: String?,
    snapshot: () -> Map<String, String>,
    restore: (Map<String, String>) -> Unit,
): FormDraftHandle {
    val context = LocalContext.current.applicationContext
    val owner = remember { runCatching { TokenStore(context).getUser()?.id }.getOrNull() }
    val handle = remember(key) { FormDraftHandle(context, owner, key) }
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentRestore by rememberUpdatedState(restore)
    // What the form opened with, captured before any restore. Equal to it = nothing to keep.
    val baseline = remember(key) { snapshot() }
    handle.baseline = baseline
    handle.restoreFn = { currentRestore(it) }

    if (key == null) return handle

    LaunchedEffect(key) {
        if (sweptThisProcess.compareAndSet(false, true)) {
            AppScope.io.launch { runCatching { FormDraftStore.sweepUnreferencedMedia(context) } }
        }
        val draft = withContext(Dispatchers.IO) { runCatching { FormDraftStore.load(context, owner, key) }.getOrNull() }
        if (draft != null && formDraftIsWorthKeeping(draft.fields, baseline)) {
            // Media that can no longer be opened (a copy reclaimed, a grant that died with the old
            // process) is dropped here rather than offered as a tile that fails at Save.
            val readable = withContext(Dispatchers.IO) {
                draft.fields.mapValues { (field, value) ->
                    if (!field.startsWith(URI_FIELD_PREFIX)) value
                    else formDraftUriList(value)
                        .filter { FormDraftStore.isReadable(context, Uri.parse(it)) }
                        .joinToString("\n")
                }
            }
            currentRestore(baseline + readable)
            handle.restoredAt = draft.savedAt.ifBlank { null }
            handle.leftovers = formDraftLeftovers(readable, currentSnapshot().keys)
        }
        snapshotFlow { currentSnapshot() }
            .debounce(600)
            .collect { values ->
                // Boxes that have appeared since the restore take what was waiting for them.
                val waiting = handle.leftovers
                if (waiting.isNotEmpty()) {
                    val arrived = waiting.filterKeys { it in values.keys }
                    if (arrived.isNotEmpty()) {
                        handle.leftovers = waiting - arrived.keys
                        currentRestore(values + arrived)
                        return@collect // the restore changes the snapshot, which comes round again
                    }
                }
                persist(context, owner, key, handle, values + handle.leftovers, baseline)
            }
    }
    DisposableEffect(key) {
        onDispose {
            // FLUSHED ON THE WAY OUT, so Back (after the unsaved-changes prompt chose to keep editing
            // elsewhere) or a navigation inside the debounce window does not lose the last keystrokes.
            val last = runCatching { currentSnapshot() }.getOrNull() ?: return@onDispose
            val waiting = handle.leftovers
            AppScope.io.launch { persist(context, owner, key, handle, last + waiting, baseline) }
        }
    }
    return handle
}

private suspend fun persist(
    context: Context,
    owner: String?,
    key: String,
    handle: FormDraftHandle,
    values: Map<String, String>,
    baseline: Map<String, String>,
) = withContext(Dispatchers.IO) { handle.io.withLock {
    if (handle.finished.get()) {
        val settled = handle.valuesAtDiscard
        if (settled == null) {
            handle.valuesAtDiscard = values
            return@withLock
        }
        if (values == settled) return@withLock
        // Something new was typed after the save: protect it like anything else.
        handle.finished.set(false)
        handle.valuesAtDiscard = null
    }
    runCatching {
        if (!formDraftIsWorthKeeping(values, baseline)) {
            FormDraftStore.clear(context, owner, key)
            return@runCatching
        }
        val durable = values.mapValues { (field, value) ->
            if (!field.startsWith(URI_FIELD_PREFIX)) value
            else formDraftUriList(value).joinToString("\n") { original ->
                durableCopies[original] ?: run {
                    val copy = FormDraftStore.durableCopy(context, Uri.parse(original))?.toString() ?: original
                    durableCopies[original] = copy
                    copy
                }
            }
        }
        // Re-checked after the copy: a Save that landed while a video was copying wins.
        if (handle.finished.get()) return@runCatching
        FormDraftStore.save(context, owner, key, durable)
    }
    Unit
} }

/**
 * The line a form shows when it reopened with a draft, and the way back to a clean form.
 */
@Composable
fun FormDraftRestoredNote(handle: FormDraftHandle) {
    val at = handle.restoredAt ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Restored what you entered before the app closed (${readableDraftStamp(at)}). It has not been " +
                "saved yet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { handle.startOver() }) { Text("Start over") }
    }
}

/** "16 Oct, 14:05" in the phone's zone, or the raw stamp if it does not parse. */
internal fun readableDraftStamp(iso: String): String = runCatching {
    val zoned = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
    java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm").format(zoned)
}.getOrDefault(iso)

/** A list of strings as one draft field. The separator is a control character nobody types. */
fun draftList(items: List<String>): String = items.joinToString("\u001F")

/** The inverse of [draftList]. An empty field is an empty list. */
fun parseDraftList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split('\u001F')

/** A date field as stored in a draft ("" = no date). */
fun draftDate(value: String): java.time.LocalDate? =
    value.takeIf { it.isNotBlank() }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
