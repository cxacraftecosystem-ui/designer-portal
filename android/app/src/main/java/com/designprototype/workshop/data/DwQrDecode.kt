package com.designprototype.workshop.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class DwQrSource {
    CAMERA,

    PICTURE,
}

sealed interface DwQrReadResult {
    data class Found(val text: String) : DwQrReadResult

    data class NothingFound(val message: String) : DwQrReadResult

    data class Unreadable(val message: String) : DwQrReadResult
}

interface DwQrImageDecoder {
    suspend fun decode(context: Context, source: Uri): String?
}

class DwQrPictureUnreadable(message: String = DW_QR_UNREADABLE_PICTURE) : IllegalStateException(message)

val DW_QR_SAMPLE_LADDER: List<Int> = listOf(2, 1, 4)

fun dwQrNothingFound(source: DwQrSource): String = when (source) {
    DwQrSource.CAMERA ->
        "No QR code found. Fill the frame, hold steady, or type the code below it."
    DwQrSource.PICTURE ->
        "Couldn't read a QR code. Try a tighter crop, or type the code."
}

const val DW_QR_UNREADABLE_PICTURE =
    "That picture could not be opened (formats like HEIC aren't supported). Scan with the camera or type the code."

object ZxingQrImageDecoder : DwQrImageDecoder {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    override suspend fun decode(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        var everOpened = false
        DW_QR_SAMPLE_LADDER.forEach { sample ->
            val bitmap = readBitmap(context, source, sample)
            if (bitmap != null) {
                everOpened = true
                try {
                    val text = decodeBitmap(bitmap)
                    if (text != null) return@withContext text
                } finally {
                    bitmap.recycle()
                }
            }
        }
        if (!everOpened) throw DwQrPictureUnreadable()
        null
    }

    private fun readBitmap(context: Context, source: Uri, sample: Int): Bitmap? = runCatching {
        context.contentResolver.openInputStream(source)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = false
                }
            )
        }
    }.getOrNull()

    private fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        return runCatching { MultiFormatReader().apply { setHints(hints) }.decode(binary).text }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}

suspend fun dwReadQrPicture(
    context: Context,
    source: Uri,
    from: DwQrSource,
    decoder: DwQrImageDecoder = ZxingQrImageDecoder,
): DwQrReadResult = runCatching { decoder.decode(context, source) }
    .fold(
        onSuccess = { text ->
            if (text.isNullOrBlank()) {
                DwQrReadResult.NothingFound(dwQrNothingFound(from))
            } else {
                DwQrReadResult.Found(text)
            }
        },
        onFailure = { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            DwQrReadResult.Unreadable(error.message?.takeIf { it.isNotBlank() } ?: DW_QR_UNREADABLE_PICTURE)
        }
    )

data class DwQrCrop(val left: Int, val top: Int, val width: Int, val height: Int) {
    internal val right: Int get() = left + width
    internal val bottom: Int get() = top + height
    internal val isEmpty: Boolean get() = width <= 0 || height <= 0
}

data class DwQrFraction(val left: Float, val top: Float, val right: Float, val bottom: Float)

const val DW_QR_RETICLE_MARGIN: Float = 0.12f

private const val DW_QR_MIN_CROP: Int = 96

fun dwQrCropInBuffer(
    reticle: DwQrFraction,
    displayed: DwQrCrop,
    rotationDegrees: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    margin: Float = DW_QR_RETICLE_MARGIN,
): DwQrCrop? {
    if (bufferWidth <= 0 || bufferHeight <= 0) return null
    if (displayed.isEmpty) return null
    if (displayed.left < 0 || displayed.top < 0) return null
    if (displayed.right > bufferWidth || displayed.bottom > bufferHeight) return null
    if (rotationDegrees != 0 && rotationDegrees != 90 && rotationDegrees != 180 && rotationDegrees != 270) return null

    val safeMargin = if (margin.isFinite() && margin >= 0f) margin else 0f
    val inflated = reticle.inflated(safeMargin) ?: return null
    val inBuffer = inflated.unrotated(rotationDegrees)

    val left = displayed.left + floor(inBuffer.left * displayed.width).toInt()
    val top = displayed.top + floor(inBuffer.top * displayed.height).toInt()
    val right = displayed.left + ceil(inBuffer.right * displayed.width).toInt()
    val bottom = displayed.top + ceil(inBuffer.bottom * displayed.height).toInt()

    val clampedLeft = left.coerceIn(displayed.left, displayed.right)
    val clampedTop = top.coerceIn(displayed.top, displayed.bottom)
    val clampedRight = right.coerceIn(clampedLeft, displayed.right)
    val clampedBottom = bottom.coerceIn(clampedTop, displayed.bottom)

    val width = clampedRight - clampedLeft
    val height = clampedBottom - clampedTop
    if (width < DW_QR_MIN_CROP || height < DW_QR_MIN_CROP) return null
    if (width >= displayed.width && height >= displayed.height) return null
    return DwQrCrop(clampedLeft, clampedTop, width, height)
}

private fun DwQrFraction.inflated(margin: Float): DwQrFraction? {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
    if (right <= left || bottom <= top) return null
    val growX = (right - left) * margin
    val growY = (bottom - top) * margin
    return DwQrFraction(
        left = (left - growX).coerceIn(0f, 1f),
        top = (top - growY).coerceIn(0f, 1f),
        right = (right + growX).coerceIn(0f, 1f),
        bottom = (bottom + growY).coerceIn(0f, 1f),
    )
}

private fun DwQrFraction.unrotated(rotationDegrees: Int): DwQrFraction = when (rotationDegrees) {
    90 -> DwQrFraction(left = top, top = 1f - right, right = bottom, bottom = 1f - left).ordered()
    180 -> DwQrFraction(left = 1f - right, top = 1f - bottom, right = 1f - left, bottom = 1f - top).ordered()
    270 -> DwQrFraction(left = 1f - bottom, top = left, right = 1f - top, bottom = right).ordered()
    else -> this.ordered()
}

private fun DwQrFraction.ordered(): DwQrFraction = DwQrFraction(
    left = min(left, right),
    top = min(top, bottom),
    right = max(left, right),
    bottom = max(top, bottom),
)

fun dwQrCompactLuminance(
    source: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    crop: DwQrCrop,
    into: ByteArray,
): Boolean {
    if (crop.isEmpty || crop.left < 0 || crop.top < 0) return false
    if (rowStride <= 0 || pixelStride <= 0) return false
    if (into.size < crop.width * crop.height) return false
    val lastIndex = (crop.bottom - 1).toLong() * rowStride + (crop.right - 1).toLong() * pixelStride
    if (lastIndex >= source.capacity().toLong()) return false

    var out = 0
    for (row in crop.top until crop.bottom) {
        var index = row * rowStride + crop.left * pixelStride
        var column = 0
        while (column < crop.width) {
            into[out++] = source.get(index)
            index += pixelStride
            column++
        }
    }
    return true
}

class DwQrLiveDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            )
        )
    }

    private var scratch = ByteArray(0)

    fun luminanceBuffer(size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        if (scratch.size < size) scratch = ByteArray(size)
        return scratch
    }

    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (luminance.size < width * height) return null
        val source = PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        runCatching { reader.reset() }
        return text
    }
}

const val DW_QR_RETICLE_SIDE_FRACTION = 0.72f

fun dwQrReticleFraction(
    boxWidth: Int,
    boxHeight: Int,
    sideFraction: Float = DW_QR_RETICLE_SIDE_FRACTION,
): DwQrFraction? {
    if (boxWidth <= 0 || boxHeight <= 0) return null
    if (!sideFraction.isFinite() || sideFraction <= 0f || sideFraction > 1f) return null
    val side = minOf(boxWidth, boxHeight) * sideFraction
    val halfWidthFraction = (side / 2f) / boxWidth
    val halfHeightFraction = (side / 2f) / boxHeight
    return DwQrFraction(
        left = (0.5f - halfWidthFraction).coerceIn(0f, 1f),
        top = (0.5f - halfHeightFraction).coerceIn(0f, 1f),
        right = (0.5f + halfWidthFraction).coerceIn(0f, 1f),
        bottom = (0.5f + halfHeightFraction).coerceIn(0f, 1f),
    )
}

data class DwQrSighting(val text: String, val bounds: DwQrCrop?)

fun dwQrUprightWidth(bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int): Int =
    if (rotationDegrees == 90 || rotationDegrees == 270) bufferHeight else bufferWidth

fun dwQrUprightHeight(bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int): Int =
    if (rotationDegrees == 90 || rotationDegrees == 270) bufferWidth else bufferHeight

fun dwQrReticleInUprightFrame(
    reticle: DwQrFraction,
    displayed: DwQrCrop,
    rotationDegrees: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    margin: Float = DW_QR_RETICLE_MARGIN,
): DwQrFraction? {
    if (bufferWidth <= 0 || bufferHeight <= 0) return null
    if (displayed.isEmpty) return null
    if (displayed.left < 0 || displayed.top < 0) return null
    if (displayed.right > bufferWidth || displayed.bottom > bufferHeight) return null
    if (rotationDegrees != 0 && rotationDegrees != 90 && rotationDegrees != 180 && rotationDegrees != 270) return null

    val safeMargin = if (margin.isFinite() && margin >= 0f) margin else 0f
    val inflated = reticle.inflated(safeMargin) ?: return null

    val frameWidth = dwQrUprightWidth(bufferWidth, bufferHeight, rotationDegrees).toFloat()
    val frameHeight = dwQrUprightHeight(bufferWidth, bufferHeight, rotationDegrees).toFloat()

    val edges = when (rotationDegrees) {
        90 -> DwQrEdges(
            left = bufferHeight - displayed.bottom.toFloat(),
            right = bufferHeight - displayed.top.toFloat(),
            top = displayed.left.toFloat(),
            bottom = displayed.right.toFloat(),
        )
        180 -> DwQrEdges(
            left = bufferWidth - displayed.right.toFloat(),
            right = bufferWidth - displayed.left.toFloat(),
            top = bufferHeight - displayed.bottom.toFloat(),
            bottom = bufferHeight - displayed.top.toFloat(),
        )
        270 -> DwQrEdges(
            left = displayed.top.toFloat(),
            right = displayed.bottom.toFloat(),
            top = bufferWidth - displayed.right.toFloat(),
            bottom = bufferWidth - displayed.left.toFloat(),
        )
        else -> DwQrEdges(
            left = displayed.left.toFloat(),
            right = displayed.right.toFloat(),
            top = displayed.top.toFloat(),
            bottom = displayed.bottom.toFloat(),
        )
    }

    val spanX = edges.right - edges.left
    val spanY = edges.bottom - edges.top
    if (spanX <= 0f || spanY <= 0f) return null

    return DwQrFraction(
        left = ((edges.left + inflated.left * spanX) / frameWidth).coerceIn(0f, 1f),
        top = ((edges.top + inflated.top * spanY) / frameHeight).coerceIn(0f, 1f),
        right = ((edges.left + inflated.right * spanX) / frameWidth).coerceIn(0f, 1f),
        bottom = ((edges.top + inflated.bottom * spanY) / frameHeight).coerceIn(0f, 1f),
    )
}

private data class DwQrEdges(val left: Float, val right: Float, val top: Float, val bottom: Float)

fun dwQrSightingInReticle(
    bounds: DwQrCrop?,
    frameWidth: Int,
    frameHeight: Int,
    reticle: DwQrFraction?,
): Boolean {
    if (reticle == null) return true
    if (bounds == null || bounds.isEmpty) return true
    if (frameWidth <= 0 || frameHeight <= 0) return true
    val centreX = (bounds.left + bounds.width / 2f) / frameWidth
    val centreY = (bounds.top + bounds.height / 2f) / frameHeight
    return centreX >= reticle.left && centreX <= reticle.right &&
        centreY >= reticle.top && centreY <= reticle.bottom
}
