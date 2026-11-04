package com.designprototype.workshop.data

import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed interface DwQrFrameRead {

    data object None : DwQrFrameRead

    data class Read(val sighting: DwQrSighting) : DwQrFrameRead

    data class OutsideTheBox(val sighting: DwQrSighting) : DwQrFrameRead
}

interface DwQrFrameReader {

    fun read(image: ImageProxy, reticle: DwQrFraction?): DwQrFrameRead

    val unavailable: Boolean get() = false

    fun close() {}
}

const val DW_QR_MLKIT_FAILURE_RUN: Int = 10

class MlKitQrFrameReader : DwQrFrameReader {

    private val scanner: BarcodeScanner? = runCatching {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }.getOrNull()

    @Volatile
    private var down: Boolean = scanner == null

    private val failures = AtomicInteger(0)

    override val unavailable: Boolean get() = down

    @ExperimentalGetImage
    override fun read(image: ImageProxy, reticle: DwQrFraction?): DwQrFrameRead {
        val client = scanner ?: return DwQrFrameRead.None
        val media = runCatching { image.image }.getOrNull()
        if (media == null) {
            down = true
            return DwQrFrameRead.None
        }

        val rotation = image.imageInfo.rotationDegrees
        val input = runCatching { InputImage.fromMediaImage(media, rotation) }.getOrNull()
        if (input == null) {
            down = true
            return DwQrFrameRead.None
        }

        val found = AtomicReference<List<Barcode>?>(null)
        val settled = CountDownLatch(1)
        val direct = Executor { it.run() }
        val started = runCatching {
            client.process(input)
                .addOnSuccessListener(direct) { barcodes -> found.set(barcodes) }
                .addOnCompleteListener(direct) { settled.countDown() }
        }.isSuccess
        if (!started) {
            down = true
            return DwQrFrameRead.None
        }
        try {
            settled.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return DwQrFrameRead.None
        }

        val result = found.get()
        if (result == null) {
            if (failures.incrementAndGet() >= DW_QR_MLKIT_FAILURE_RUN) down = true
            return DwQrFrameRead.None
        }
        failures.set(0)

        val barcodes = result
        if (barcodes.isEmpty()) return DwQrFrameRead.None

        val frameWidth = dwQrUprightWidth(image.width, image.height, rotation)
        val frameHeight = dwQrUprightHeight(image.width, image.height, rotation)
        val box = reticle?.let {
            dwQrReticleInUprightFrame(
                reticle = it,
                displayed = image.displayedRect(),
                rotationDegrees = rotation,
                bufferWidth = image.width,
                bufferHeight = image.height,
            )
        }

        var refused: DwQrSighting? = null
        for (barcode in barcodes) {
            val text = barcode.rawValue?.takeIf { it.isNotBlank() } ?: continue
            val bounds = barcode.boundingBox?.asCrop()
            val sighting = DwQrSighting(text, bounds)
            if (dwQrSightingInReticle(bounds, frameWidth, frameHeight, box)) return DwQrFrameRead.Read(sighting)
            if (refused == null) refused = sighting
        }
        return refused?.let { DwQrFrameRead.OutsideTheBox(it) } ?: DwQrFrameRead.None
    }

    override fun close() {
        runCatching { scanner?.close() }
    }
}

class ReferenceQrFrameReader : DwQrFrameReader {

    private val decoder = DwQrLiveDecoder()

    override fun read(image: ImageProxy, reticle: DwQrFraction?): DwQrFrameRead {
        val plane = image.planes.firstOrNull() ?: return DwQrFrameRead.None
        val displayed = image.displayedRect()
        val target = reticle?.let { fraction ->
            dwQrCropInBuffer(
                reticle = fraction,
                displayed = displayed,
                rotationDegrees = image.imageInfo.rotationDegrees,
                bufferWidth = image.width,
                bufferHeight = image.height,
            )
        } ?: displayed
        if (target.width <= 0 || target.height <= 0) return DwQrFrameRead.None

        val luminance = decoder.luminanceBuffer(target.width * target.height)
        val compacted = dwQrCompactLuminance(
            source = plane.buffer,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            crop = target,
            into = luminance,
        )
        if (!compacted) return DwQrFrameRead.None

        val text = decoder.decode(luminance, target.width, target.height) ?: return DwQrFrameRead.None
        return DwQrFrameRead.Read(DwQrSighting(text, target))
    }
}

private fun ImageProxy.displayedRect(): DwQrCrop = cropRect.let { rect ->
    DwQrCrop(left = rect.left, top = rect.top, width = rect.width(), height = rect.height())
}

private fun Rect.asCrop(): DwQrCrop = DwQrCrop(left = left, top = top, width = width(), height = height())
