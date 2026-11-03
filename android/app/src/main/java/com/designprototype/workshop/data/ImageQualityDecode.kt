package com.designprototype.workshop.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max

class DwDisplayImage(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

object DwImageDecode {

    const val DISPLAY_EDGE_PX = 2400

    fun decodeForDisplay(path: String, maxEdgePx: Int = DISPLAY_EDGE_PX): DwDisplayImage? =
        runCatching { decodeForDisplayOrNull(path, maxEdgePx) }.getOrNull()

    private fun decodeForDisplayOrNull(path: String, maxEdgePx: Int): DwDisplayImage? {
        if (maxEdgePx < 1) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < 1 || height < 1) return null

        val longEdge = max(width, height)
        var sample = 1
        while (longEdge / sample > maxEdgePx) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null

        val (rotation, mirrored) = exifTransform(path)
        val oriented = orient(decoded, rotation, mirrored)
        val swapped = rotation == 90 || rotation == 270
        return DwDisplayImage(
            bitmap = oriented,
            sourceWidth = if (swapped) height else width,
            sourceHeight = if (swapped) width else height,
        )
    }

    private fun exifTransform(path: String): Pair<Int, Boolean> {
        val exif = runCatching { ExifInterface(path) }.getOrNull() ?: return 0 to false
        val orientation = runCatching {
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
        val mirrored = orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
            orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        return rotation to mirrored
    }

    private fun orient(source: Bitmap, rotation: Int, mirrored: Boolean): Bitmap {
        if (rotation == 0 && !mirrored) return source
        val matrix = Matrix()
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (mirrored) matrix.postScale(-1f, 1f)
        val rotated = runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull() ?: return source
        if (rotated !== source) source.recycle()
        return rotated
    }

    fun measure(path: String): ImageMeasurement? = runCatching { measureOrNull(path) }.getOrNull()

    private fun measureOrNull(path: String): ImageMeasurement? {
        val startedAt = System.nanoTime()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < 1 || height < 1) return null

        val longEdge = max(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= DwImageQuality.WORK_EDGE_PX) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null

        val decoded = try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            DwImageQuality.greyPlaneFromArgb(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }

        val (workWidth, workHeight) = DwImageQuality.workingSizeFor(width, height)
        val work = if (decoded.width == workWidth && decoded.height == workHeight) {
            decoded
        } else {
            DwImageQuality.resampleGrey(decoded, workWidth, workHeight)
        }

        return ImageMeasurement(
            width = width,
            height = height,
            blurScore = DwImageQuality.laplacianVariance(work),
            contrast = DwImageQuality.contrastStdDev(work),
            perceptualHash = DwImageQuality.differenceHash(work),
            elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    class DwScreenedImage(val measurement: ImageMeasurement, val sha256: String?)

    fun screen(resolver: ContentResolver, uri: Uri): DwScreenedImage? =
        runCatching { screenOrNull(resolver, uri) }.getOrNull()

    private fun screenOrNull(resolver: ContentResolver, uri: Uri): DwScreenedImage? {
        val startedAt = System.nanoTime()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openOrNull(resolver, uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < 1 || height < 1) return null

        val sha256 = runCatching { digestOf(resolver, uri) }.getOrNull()

        val longEdge = max(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= DwImageQuality.WORK_EDGE_PX) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = openOrNull(resolver, uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        val decoded = try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            DwImageQuality.greyPlaneFromArgb(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }

        val (workWidth, workHeight) = DwImageQuality.workingSizeFor(width, height)
        val work = if (decoded.width == workWidth && decoded.height == workHeight) {
            decoded
        } else {
            DwImageQuality.resampleGrey(decoded, workWidth, workHeight)
        }

        return DwScreenedImage(
            measurement = ImageMeasurement(
                width = width,
                height = height,
                blurScore = DwImageQuality.laplacianVariance(work),
                contrast = DwImageQuality.contrastStdDev(work),
                perceptualHash = DwImageQuality.differenceHash(work),
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
            ),
            sha256 = sha256,
        )
    }

    private fun openOrNull(resolver: ContentResolver, uri: Uri): InputStream? =
        runCatching { resolver.openInputStream(uri) }.getOrNull()

    private fun digestOf(resolver: ContentResolver, uri: Uri): String? {
        val stream = openOrNull(resolver, uri) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        stream.use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
