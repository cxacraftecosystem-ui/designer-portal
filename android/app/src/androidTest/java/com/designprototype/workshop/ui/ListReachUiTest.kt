package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.designprototype.workshop.data.PageResponse
import com.designprototype.workshop.data.readEveryPage
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListReachUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val pageSize = 100

    private fun page(n: Int, total: Int): PageResponse<String> {
        val from = (n - 1) * pageSize
        return PageResponse(
            items = (from until minOf(from + pageSize, total)).map { "Record ${it + 1}" },
            total = total,
            page = n,
            pageSize = pageSize,
            pages = (total + pageSize - 1) / pageSize,
        )
    }

    @Composable
    private fun PagedPicker(total: Int) {
        var rows by remember { mutableStateOf(page(1, total).items) }
        var pages by remember { mutableIntStateOf(1) }
        var shown by remember { mutableIntStateOf(12) }
        val reach = ListReach(shown = shown.coerceAtMost(rows.size), loaded = rows.size, serverMore = serverHasMore(pages, pageSize, total))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            rows.take(reach.shown).forEach { Text(it, modifier = Modifier.testTag("row.$it")) }
            if (reach.more) {
                ListReachButton(
                    shown = reach.shown,
                    total = total,
                    label = ListReachText.showMore(reach.nextCount(total)),
                    loading = false,
                    tag = "picker.more",
                    onClick = {
                        if (reach.needsFetch()) {
                            rows = (rows + page(pages + 1, total).items).distinct()
                            pages += 1
                        }
                        shown = reach.revealed(rows.size)
                    },
                )
            }
        }
    }

    @Test
    fun showMoreReachesTheLastOf231Records() {
        compose.setContent { MaterialTheme { PagedPicker(total = 231) } }
        compose.onNodeWithText("Showing 12 of 231").assertIsDisplayed()
        var presses = 0
        while (true) {
            val more = runCatching { compose.onNodeWithTag("picker.more").performScrollTo() }.isSuccess
            if (!more) break
            compose.onNodeWithTag("picker.more").performClick()
            compose.waitForIdle()
            presses++
            assertTrue(presses < 30)
        }
        compose.onNodeWithTag("row.Record 231").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("picker.more").assertDoesNotExist()
    }

    @Composable
    private fun PartialList(total: Int) {
        var attempt by remember { mutableIntStateOf(0) }
        var rows by remember { mutableStateOf<List<String>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(attempt) {
            loading = true
            rows = readEveryPage { n -> if (attempt == 0 && n > 1) throw java.io.IOException("offline") else page(n, total) }.items
            loading = false
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            rows.forEach { Text(it, modifier = Modifier.testTag("row.$it")) }
            if (!loading && rows.size < total) {
                ListReachButton(
                    shown = rows.size,
                    total = total,
                    label = ListReachText.loadAll(total),
                    loading = loading,
                    tag = "list.loadall",
                    onClick = { scope.launch { attempt++ } },
                )
            }
        }
    }

    @Test
    fun loadAllReachesTheLastRecordAfterAPartialRead() {
        compose.setContent { MaterialTheme { PartialList(total = 431) } }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Showing 100 of 431").assertExists() }.isSuccess }
        compose.onNodeWithTag("list.loadall").performScrollTo().performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("row.Record 431").assertExists() }.isSuccess }
        compose.onNodeWithTag("row.Record 431").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("list.loadall").assertDoesNotExist()
    }
}
