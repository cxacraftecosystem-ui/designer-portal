package com.designprototype.workshop.ui

import android.Manifest
import android.content.Context
import android.util.Rational
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.designprototype.workshop.data.DwQrFraction
import com.designprototype.workshop.data.DwQrFrameRead
import com.designprototype.workshop.data.DwQrFrameReader
import com.designprototype.workshop.data.MlKitQrFrameReader
import com.designprototype.workshop.data.ReferenceQrFrameReader
import com.designprototype.workshop.data.dwQrReticleFraction
import com.designprototype.workshop.ui.designworkshop.hasPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

suspend fun <T> dwCameraStep(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

fun dwQrCameraUnavailable(rearLensMissing: Boolean): String = if (rearLensMissing) {
    "This device has no rear camera, so live scanning is unavailable. Instead: " + DwCameraUse.QR_CODE.alternatives
} else {
    "Couldn't open the camera (another app may be using it). Instead: " + DwCameraUse.QR_CODE.alternatives
}

const val DW_QR_LIVE_AIMING = "Hold the code inside the box. It reads on its own."

const val DW_QR_LIVE_FRONT_LENS =
    "This device has no rear camera, so the front one is in use — hold the code facing the screen."

val DW_QR_LIVE_STILL_TRYING = dwQrLiveStillTrying("Use a picture")

fun dwQrLiveStillTrying(pictureLabel: String?, captureLabel: String? = "Scan a code"): String = when {
    captureLabel != null && pictureLabel != null ->
        "Not reading? Close and tap “$captureLabel” — a photo is read at full resolution — or “$pictureLabel”."
    captureLabel != null -> "Not reading? Close and tap “$captureLabel” — a photo is read at full resolution."
    pictureLabel != null -> "Not reading? Close and tap “$pictureLabel” — a picture is read at full resolution."
    else -> "Not reading? Move closer, hold the phone steady and add light."
}

const val DW_QR_LIVE_STALLED =
    "The camera stopped sending pictures. Close this and open it again."

const val DW_QR_LIVE_OUTSIDE_BOX =
    "Line the code up inside the box."

const val DW_QR_LIVE_REFERENCE_READER =
    "Using a basic reader — hold the code flat, steady and well lit. Or close and tap “Scan a code”."

private const val DW_QR_LIVE_OUTSIDE_BOX_MS = 1_500L

private const val DW_QR_LIVE_PATIENCE_MS = 20_000L

private const val DW_QR_LIVE_STALL_MS = 6_000L

private const val DW_QR_LIVE_POLL_MS = 200L

private const val DW_QR_REBIND_SLOP_PX = 24

private fun dwQrAnalysisSize(): android.util.Size = android.util.Size(1280, 720)

@Composable
fun DwQrLiveScanControl(
    enabled: Boolean,
    onText: (String) -> Unit,
    onRefusal: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Scan with the camera",
    pictureLabel: String? = "Use a picture",
    captureLabel: String? = "Scan a code",
    hint: String? = "Line the code up in the box; it reads automatically. Works offline.",
    alongside: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var cameraBlocked by remember { mutableStateOf(false) }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraBlocked = false
            scanning = true
        } else {
            val blocked = dwCameraBlocked(context)
            cameraBlocked = blocked
            onRefusal(dwCameraRefusal(DwCameraUse.QR_CODE, blocked))
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionRow {
            OutlinedButton(
                onClick = {
                    if (hasPermission(context, Manifest.permission.CAMERA)) {
                        cameraBlocked = false
                        scanning = true
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
            alongside?.invoke()
            if (cameraBlocked) {
                OutlinedButton(
                    onClick = { context.dwOpenAppPermissionSettings() },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(DW_CAMERA_SETTINGS_BUTTON, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (hint != null) {
            Text(
                hint,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }
    }

    if (scanning) {
        DwQrLiveScannerDialog(
            onText = { text ->
                scanning = false
                onText(text)
            },
            onRefusal = { message ->
                scanning = false
                onRefusal(message)
            },
            onDismiss = { scanning = false },
            stillTrying = dwQrLiveStillTrying(pictureLabel, captureLabel),
        )
    }
}

@Composable
fun DwQrLiveScannerDialog(
    onText: (String) -> Unit,
    onRefusal: (String) -> Unit,
    onDismiss: () -> Unit,
    stillTrying: String = DW_QR_LIVE_STILL_TRYING,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val reduceMotion = LocalAppPreferences.current.reducedMotion

    val reticleSink = remember { AtomicReference<DwQrFraction?>(null) }
    val hitSink = remember { AtomicReference<String?>(null) }
    val cameraSink = remember { AtomicReference<Camera?>(null) }
    val providerSink = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val analysisSink = remember { AtomicReference<ImageAnalysis?>(null) }
    val lastFrameAt = remember { AtomicLong(0L) }
    val framesSeen = remember { AtomicLong(0L) }
    val outsideBoxAt = remember { AtomicLong(0L) }

    val mlKitReader = remember { MlKitQrFrameReader() }
    val referenceReader = remember { ReferenceQrFrameReader() }
    var referenceReaderInUse by remember { mutableStateOf(false) }
    var sawOutsideBox by remember { mutableStateOf(false) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var frontLensFallback by remember { mutableStateOf(false) }
    var hit by remember { mutableStateOf<String?>(null) }
    var cameraGeneration by remember { mutableIntStateOf(0) }
    var patienceSpent by remember { mutableStateOf(false) }
    var stalled by remember { mutableStateOf(false) }

    val reticle = remember(boxSize) { dwQrReticleFraction(boxSize.width, boxSize.height) }
    LaunchedEffect(reticle) { reticleSink.set(reticle) }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(Unit) {
        val awaited = dwCameraStep { ProcessCameraProvider.awaitInstance(context) }
        if (awaited == null) {
            onRefusal(dwQrCameraUnavailable(rearLensMissing = false))
            return@LaunchedEffect
        }
        providerSink.set(awaited)
        cameraProvider = awaited
    }

    LaunchedEffect(cameraProvider, boxSize, configuration.orientation) {
        val provider = cameraProvider ?: return@LaunchedEffect
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        if (hitSink.get() != null) return@LaunchedEffect

        val hasBack = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        val hasFront = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        if (!hasBack && !hasFront) {
            onRefusal(dwQrCameraUnavailable(rearLensMissing = true))
            return@LaunchedEffect
        }
        val selector = if (hasBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        frontLensFallback = !hasBack

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            dwQrAnalysisSize(),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .build()
            .also { analysisSink.set(it) }
            .apply {
                setAnalyzer(
                    analysisExecutor,
                    DwQrFrameAnalyzer(
                        primary = mlKitReader,
                        reference = referenceReader,
                        reticle = { reticleSink.get() },
                        onFrame = { at ->
                            lastFrameAt.set(at)
                            framesSeen.incrementAndGet()
                        },
                        onOutsideTheBox = { at -> outsideBoxAt.set(at) },
                        onDecoded = { text -> hitSink.compareAndSet(null, text) },
                    ),
                )
            }

        val viewPort = ViewPort.Builder(
            Rational(boxSize.width, boxSize.height),
            dwDisplayRotation(context),
        )
            .setScaleType(ViewPort.FILL_CENTER)
            .build()

        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(analysis)
            .build()

        val camera = runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, group)
        }.getOrNull()
        if (camera == null) {
            onRefusal(dwQrCameraUnavailable(rearLensMissing = false))
            return@LaunchedEffect
        }
        torchAvailable = runCatching { camera.cameraInfo.hasFlashUnit() }.getOrDefault(false)
        cameraSink.set(camera)
        cameraGeneration++
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { analysisSink.get()?.clearAnalyzer() }
            runCatching { providerSink.get()?.unbindAll() }
            val closeReaders = Runnable {
                runCatching { mlKitReader.close() }
                runCatching { referenceReader.close() }
            }
            runCatching { analysisExecutor.execute(closeReaders) }.onFailure { closeReaders.run() }
            runCatching { analysisExecutor.shutdown() }
        }
    }

    DisposableEffect(cameraGeneration) {
        val info = cameraSink.get()?.cameraInfo
        val observer = Observer<Int> { state -> torchOn = state == TorchState.ON }
        info?.torchState?.observeForever(observer)
        onDispose { info?.torchState?.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            val text = hitSink.get()
            if (text != null) {
                hit = text
                break
            }
            val now = System.currentTimeMillis()
            stalled = framesSeen.get() > 0L && now - lastFrameAt.get() > DW_QR_LIVE_STALL_MS
            patienceSpent = now - startedAt > DW_QR_LIVE_PATIENCE_MS
            referenceReaderInUse = framesSeen.get() > 0L && mlKitReader.unavailable
            val refusedAt = outsideBoxAt.get()
            sawOutsideBox = refusedAt > 0L && now - refusedAt < DW_QR_LIVE_OUTSIDE_BOX_MS
            delay(DW_QR_LIVE_POLL_MS)
        }
    }

    LaunchedEffect(hit) {
        val text = hit ?: return@LaunchedEffect
        runCatching { analysisSink.get()?.clearAnalyzer() }
        runCatching { providerSink.get()?.unbindAll() }
        runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        if (!reduceMotion) delay(220)
        onText(text)
    }

    val sweep = rememberInfiniteTransition(label = "qr-sweep")
    val sweepAt by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "qr-sweep-position",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { measured ->
                    val current = boxSize
                    val moved = kotlin.math.abs(measured.width - current.width) > DW_QR_REBIND_SLOP_PX ||
                        kotlin.math.abs(measured.height - current.height) > DW_QR_REBIND_SLOP_PX
                    if (current == IntSize.Zero || moved) boxSize = measured
                },
        ) {
            surfaceRequest?.let { request ->
                CameraXViewfinder(request, Modifier.fillMaxSize())
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val box = reticle ?: return@Canvas
                val left = box.left * size.width
                val top = box.top * size.height
                val right = box.right * size.width
                val bottom = box.bottom * size.height
                val scrim = Color.Black.copy(alpha = 0.55f)

                drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, top))
                drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
                drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, bottom - top))
                drawRect(scrim, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))

                val edge = if (hit != null) Color(0xFF34D399) else Color.White
                val strokePx = 2.dp.toPx()
                drawRoundRect(
                    color = edge.copy(alpha = 0.9f),
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = Stroke(width = strokePx),
                )
                val armPx = (right - left) * 0.16f
                val heavy = Stroke(width = strokePx * 2.5f)
                listOf(
                    Offset(left, top) to (1f to 1f),
                    Offset(right, top) to (-1f to 1f),
                    Offset(left, bottom) to (1f to -1f),
                    Offset(right, bottom) to (-1f to -1f),
                ).forEach { (corner, direction) ->
                    val (dx, dy) = direction
                    drawLine(
                        color = edge,
                        start = corner,
                        end = Offset(corner.x + armPx * dx, corner.y),
                        strokeWidth = heavy.width,
                    )
                    drawLine(
                        color = edge,
                        start = corner,
                        end = Offset(corner.x, corner.y + armPx * dy),
                        strokeWidth = heavy.width,
                    )
                }

                if (!reduceMotion && hit == null) {
                    val y = top + (bottom - top) * sweepAt
                    drawLine(
                        color = Color(0xFF7DD3FC).copy(alpha = 0.85f),
                        start = Offset(left + strokePx, y),
                        end = Offset(right - strokePx, y),
                        strokeWidth = strokePx * 1.5f,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Close", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                if (torchAvailable) {
                    TextButton(
                        onClick = { runCatching { cameraSink.get()?.cameraControl?.enableTorch(!torchOn) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (torchOn) "Light on" else "Light", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (hit != null) "Read." else DW_QR_LIVE_AIMING,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (frontLensFallback) {
                    DwQrLiveNote(DW_QR_LIVE_FRONT_LENS)
                }
                if (referenceReaderInUse && hit == null) {
                    DwQrLiveNote(DW_QR_LIVE_REFERENCE_READER)
                }
                if (stalled) {
                    DwQrLiveNote(DW_QR_LIVE_STALLED)
                } else if (sawOutsideBox && hit == null) {
                    DwQrLiveNote(DW_QR_LIVE_OUTSIDE_BOX)
                } else if (patienceSpent && hit == null) {
                    DwQrLiveNote(stillTrying)
                }
            }
        }
    }
}

@Composable
private fun DwQrLiveNote(message: String) {
    Text(
        message,
        color = Color.White,
        style = MaterialTheme.typography.bodySmall,
        lineHeight = 17.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

private class DwQrFrameAnalyzer(
    private val primary: DwQrFrameReader,
    private val reference: DwQrFrameReader,
    private val reticle: () -> DwQrFraction?,
    private val onFrame: (Long) -> Unit,
    private val onOutsideTheBox: (Long) -> Unit,
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val done = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (done.get()) return
            val box = reticle()
            val first = if (primary.unavailable) reference.read(image, box) else primary.read(image, box)
            val read = if (first is DwQrFrameRead.None && primary.unavailable) {
                reference.read(image, box)
            } else {
                first
            }
            when (read) {
                is DwQrFrameRead.Read ->
                    if (done.compareAndSet(false, true)) onDecoded(read.sighting.text)
                is DwQrFrameRead.OutsideTheBox -> onOutsideTheBox(System.currentTimeMillis())
                DwQrFrameRead.None -> Unit
            }
        } catch (_: Throwable) {
        } finally {
            onFrame(System.currentTimeMillis())
            runCatching { image.close() }
        }
    }
}

@Suppress("DEPRECATION")
private fun dwDisplayRotation(context: Context): Int = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        context.display?.rotation
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?.defaultDisplay
            ?.rotation
    }
}.getOrNull() ?: Surface.ROTATION_0
