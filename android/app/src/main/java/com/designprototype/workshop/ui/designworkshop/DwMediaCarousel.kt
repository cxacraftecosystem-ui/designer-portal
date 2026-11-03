package com.designprototype.workshop.ui.designworkshop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import java.io.File

private val FRAME_HEIGHT = 220.dp

private val TRAILING_PICTURE_WORD = Regex("(?:^|\\s)photographs?\$", RegexOption.IGNORE_CASE)

private val WHITESPACE_RUN = Regex("\\s+")

internal data class DwCarouselSubject(val one: String, val many: String)

internal fun dwDescribeSubject(noun: String): DwCarouselSubject {
    val stem = noun
        .replace(WHITESPACE_RUN, " ")
        .trim()
        .replace(TRAILING_PICTURE_WORD, "")
        .trim()
    if (stem.isEmpty()) return DwCarouselSubject(one = "photograph", many = "photographs")
    return DwCarouselSubject(one = "$stem photograph", many = "$stem photographs")
}

@Composable
internal fun DwMediaCarousel(
    items: List<DwMediaItem>,
    noun: String,
    onOpen: (DwMediaItem) -> Unit,
) {
    if (items.isEmpty()) return

    val reduceMotion = LocalAppPreferences.current.reducedMotion

    val subject = remember(noun) { dwDescribeSubject(noun) }

    var position by remember(items.size) { mutableStateOf(0 to 1) }
    val (rawIndex, direction) = position

    val index = rawIndex.coerceIn(0, items.lastIndex)
    val current = items[index]

    fun go(to: Int) {
        val wrapped = ((to % items.size) + items.size) % items.size
        val heading = when {
            index == items.lastIndex && wrapped == 0 -> 1
            index == 0 && wrapped == items.lastIndex -> -1
            else -> if (wrapped > index) 1 else -1
        }
        position = wrapped to heading
    }

    val stripState = rememberLazyListState()
    LaunchedEffect(index, reduceMotion) {
        if (reduceMotion) stripState.scrollToItem(index) else stripState.animateScrollToItem(index)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(FRAME_HEIGHT)
                .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
                .clickable { onOpen(current) }
                .semantics { contentDescription = subject.many }
        ) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(90)) togetherWith fadeOut(tween(90))
                    } else {
                        val offset = 40
                        (slideInHorizontally(tween(200)) { if (direction >= 0) offset else -offset } +
                            fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(160)) { if (direction >= 0) -offset else offset } +
                                fadeOut(tween(160)))
                    }
                },
                label = "carousel-frame"
            ) { item ->
                val file = remember(item.absolutePath) { File(item.absolutePath) }
                if (file.exists()) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                } else {
                    val missing = "This file is no longer on this device."
                    Text(
                        missing,
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { contentDescription = missing }
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { go(index - 1) }, enabled = items.size > 1) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "Previous ${subject.one}",
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                "${index + 1} of ${items.size}",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
            IconButton(onClick = { go(index + 1) }, enabled = items.size > 1) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Next ${subject.one}",
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                current.displayName,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
        }

        if (items.size > 1) {
            LazyRow(
                state = stripState,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { at, item ->
                    val active = at == index
                    val file = remember(item.absolutePath) { File(item.absolutePath) }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.field.surface100)
                            .border(
                                width = if (active) 2.dp else 1.dp,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.field.hairline,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { position = at to (if (at > index) 1 else -1) }
                    ) {
                        if (file.exists()) {
                            AsyncImage(
                                model = file,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("${at + 1}", color = MaterialTheme.field.muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
