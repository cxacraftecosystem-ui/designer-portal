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

class FormDraftHandle internal constructor(
    private val context: Context,
    private val owner: String?,
    private val key: String?,
) {
    internal val gate = DraftDiscardGate()

    internal val io = kotlinx.coroutines.sync.Mutex()

    internal var baseline: Map<String, String> = emptyMap()
    internal var restoreFn: (Map<String, String>) -> Unit = {}

    @Volatile
    internal var leftovers: Map<String, String> = emptyMap()

    var restoredAt by mutableStateOf<String?>(null)
        internal set

    fun discard() {
        gate.close()
        restoredAt = null
        val k = key ?: return
        AppScope.io.launch { io.withLock { runCatching { FormDraftStore.clear(context, owner, k) } } }
    }

    fun startOver() {
        restoredAt = null
        leftovers = emptyMap()
        restoreFn(baseline)
        val k = key ?: return
        AppScope.io.launch { io.withLock { runCatching { FormDraftStore.clear(context, owner, k) } } }
    }
}

internal class DraftDiscardGate {
    private val closed = AtomicBoolean(false)

    @Volatile
    private var settled: Map<String, String>? = null

    val isClosed: Boolean get() = closed.get()

    fun close() {
        settled = null
        closed.set(true)
    }

    fun admits(values: Map<String, String>): Boolean {
        if (!closed.get()) return true
        val at = settled
        if (at == null) {
            settled = values
            return false
        }
        if (values == at) return false
        settled = null
        closed.set(false)
        return true
    }
}

private val durableCopies = ConcurrentHashMap<String, String>()
private val sweptThisProcess = AtomicBoolean(false)

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
                val waiting = handle.leftovers
                if (waiting.isNotEmpty()) {
                    val arrived = waiting.filterKeys { it in values.keys }
                    if (arrived.isNotEmpty()) {
                        handle.leftovers = waiting - arrived.keys
                        currentRestore(values + arrived)
                        return@collect
                    }
                }
                persist(context, owner, key, handle, values + handle.leftovers, baseline)
            }
    }
    DisposableEffect(key) {
        onDispose {
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
    if (!handle.gate.admits(values)) return@withLock
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
        if (handle.gate.isClosed) return@runCatching
        FormDraftStore.save(context, owner, key, durable)
    }
    Unit
} }

@Composable
fun FormDraftRestoredNote(handle: FormDraftHandle) {
    val at = handle.restoredAt ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Restored your unsaved entry (${readableDraftStamp(at)}).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { handle.startOver() }) { Text("Start over") }
    }
}

internal fun readableDraftStamp(iso: String): String = runCatching {
    val zoned = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
    java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm").format(zoned)
}.getOrDefault(iso)

fun draftList(items: List<String>): String = items.joinToString("\u001F")

fun parseDraftList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split('\u001F')

fun draftDate(value: String): java.time.LocalDate? =
    value.takeIf { it.isNotBlank() }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
