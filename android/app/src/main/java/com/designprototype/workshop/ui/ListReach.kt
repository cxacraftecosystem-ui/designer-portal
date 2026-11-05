package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

object ListReachText {
    fun showing(shown: Int, total: Int) = "Showing $shown of $total"
    fun showMore(count: Int) = "Show $count more"
    fun loadAll(total: Int) = "Load all $total"
    const val loading = "Loading…"
    const val failed = "Couldn't load more. Try again."
}

const val LIST_REACH_STEP = 20

internal fun serverHasMore(pagesRead: Int, pageSize: Int, total: Int): Boolean =
    pageSize > 0 && pagesRead.toLong() * pageSize < total

internal data class ListReach(val shown: Int, val loaded: Int, val serverMore: Boolean) {
    val more: Boolean get() = shown < loaded || serverMore

    fun needsFetch(step: Int = LIST_REACH_STEP): Boolean = serverMore && shown + step > loaded

    fun revealed(loadedNow: Int, step: Int = LIST_REACH_STEP): Int = minOf(shown + step, loadedNow)

    fun nextCount(total: Int, step: Int = LIST_REACH_STEP): Int =
        minOf(step, (maxOf(total, loaded) - shown).coerceAtLeast(1))
}

@Composable
internal fun ListReachButton(
    shown: Int,
    total: Int,
    label: String,
    loading: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (total > shown) {
            Text(
                ListReachText.showing(shown, total),
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("$tag.count"),
            )
        }
        if (failed) {
            Text(ListReachText.failed, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = onClick,
            enabled = !loading,
            modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
        ) { Text(if (loading) ListReachText.loading else label) }
    }
}
