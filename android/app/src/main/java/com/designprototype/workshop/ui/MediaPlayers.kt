package com.designprototype.workshop.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.delay

@Composable
fun rememberMediaImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
}

@Composable
fun MediaViewerDialog(uri: Uri, mediaType: String, onSave: (() -> Unit)? = null, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .padding(16.dp)
        ) {
            when (mediaType.uppercase()) {
                "IMAGE" -> AsyncImage(
                    model = uri,
                    contentDescription = "Image preview",
                    modifier = Modifier.fillMaxSize()
                )
                "VIDEO" -> VideoPlayer(uri = uri, modifier = Modifier.align(Alignment.Center).fillMaxWidth())
                "AUDIO" -> Box(modifier = Modifier.align(Alignment.Center).fillMaxWidth()) { AudioPlayer(uri = uri) }
                else -> Text(
                    "This file type opens in an external app.",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Row(modifier = Modifier.align(Alignment.TopEnd)) {
                if (onSave != null) {
                    MediaActionButton(
                        label = "Download",
                        icon = Icons.Filled.Download,
                        onClick = onSave,
                        accessibleLabel = "Download to this device",
                        modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black, RoundedCornerShape(12.dp)),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        }
    )
}

@Composable
fun AudioPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }

    val player = remember {
        MediaPlayer().apply {
            runCatching {
                setDataSource(context, uri)
                setOnPreparedListener {
                    durationMs = it.duration.coerceAtLeast(0)
                    ready = true
                }
                setOnCompletionListener {
                    playing = false
                    positionMs = 0
                    seekTo(0)
                }
                prepareAsync()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (playing) {
            runCatching { player.pause() }
            playing = false
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(0)
            delay(200)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            enabled = ready,
            onClick = {
                if (playing) {
                    player.pause(); playing = false
                } else {
                    player.start(); playing = true
                }
            }
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Slider(
                value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onValueChange = { fraction ->
                    val target = (fraction * durationMs).toInt()
                    positionMs = target
                    runCatching { player.seekTo(target) }
                },
                enabled = ready && durationMs > 0,
                colors = FieldProgressDefaults.sliderColors()
            )
            Text("${formatMs(positionMs)} / ${formatMs(durationMs)}", color = Body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatMs(ms: Int): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
fun RecordingIndicator(getAmplitude: () -> Int, modifier: Modifier = Modifier, paused: Boolean = false) {
    val reduceMotion = LocalAppPreferences.current.reducedMotion
    val transition = rememberInfiniteTransition(label = "rec")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink"
    )
    val pausedBlink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pausedBlink"
    )
    val amplitudes = remember { mutableStateListOf<Float>() }
    var elapsedMs by remember { mutableStateOf(0L) }

    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        val start = System.currentTimeMillis() - elapsedMs
        while (true) {
            val amp = runCatching { getAmplitude() }.getOrDefault(0)
            val norm = (amp.coerceIn(0, 32767).toFloat() / 32767f)
            amplitudes.add(norm)
            if (amplitudes.size > 48) amplitudes.removeAt(0)
            elapsedMs = System.currentTimeMillis() - start
            delay(100)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val dotColor = if (paused) MaterialTheme.field.muted else MaterialTheme.colorScheme.error
        val dotAlpha = if (reduceMotion) 1f else if (paused) pausedBlink else blink
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(dotColor.copy(alpha = dotAlpha), CircleShape)
        )
        if (paused) {
            Text("Paused", color = Body.copy(alpha = if (reduceMotion) 1f else pausedBlink), style = MaterialTheme.typography.bodySmall)
            Text(formatMs(elapsedMs.toInt()), color = Body, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Recording  ${formatMs(elapsedMs.toInt())}", color = Body, style = MaterialTheme.typography.bodySmall)
        }
        val waveColor = MaterialTheme.colorScheme.primary
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
        ) {
            val barCount = amplitudes.size
            if (barCount == 0) return@Canvas
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
            amplitudes.forEachIndexed { i, level ->
                val h = (level.coerceIn(0.05f, 1f)) * size.height
                val x = i * (barWidth + gap)
                drawRoundRect(
                    color = waveColor,
                    topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2f),
                    size = androidx.compose.ui.geometry.Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}

@Composable
fun MediaThumb(
    uri: Uri,
    mediaType: String,
    title: String,
    subtitle: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onLoadError: (() -> Unit)? = null,
    onTile: Color? = null
) {
    val loader = rememberMediaImageLoader()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(onTile?.copy(alpha = 0.08f) ?: MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(64.dp).background(SurfaceCard, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            when (mediaType.uppercase()) {
                "IMAGE" -> AsyncImage(
                    model = uri,
                    contentDescription = title,
                    onError = { onLoadError?.invoke() },
                    modifier = Modifier.fillMaxSize()
                )
                "VIDEO" -> {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(uri).build(),
                        imageLoader = loader,
                        contentDescription = title,
                        onError = { onLoadError?.invoke() },
                        modifier = Modifier.fillMaxSize()
                    )
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                }
                else -> Text(mediaType.take(3).uppercase(), color = Body, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = onTile ?: MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitleInk = if (onTile != null) MaterialTheme.field.onBrandTileMuted else MaterialTheme.field.muted
            subtitle?.let { Text(it, color = subtitleInk, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (onTile != null) {
            OutlinedButton(
                onClick = onOpen,
                colors = onTileButtonColors(onTile),
                border = onTileBorder(onTile),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) { Text("Open", style = MaterialTheme.typography.labelLarge) }
        } else {
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}
