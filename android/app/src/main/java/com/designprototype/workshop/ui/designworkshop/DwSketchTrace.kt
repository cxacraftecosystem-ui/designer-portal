package com.designprototype.workshop.ui.designworkshop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun rememberDwTraceRuntime(): DwTraceRuntime {
    val app = LocalContext.current.applicationContext
    return remember(app) { dwTraceKotlinRuntime(app) }
}


internal fun dwTracePlateResult(
    decoded: DwTraceDecoded,
    rgba: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
    frameNote: String,
    request: DwTraceRequest,
): DwTraceResult {
    var tracePlate: Bitmap? = null
    var photographPlate: Bitmap? = null
    var plateRefusal = ""

    if (decoded.width != sourceWidth || decoded.height != sourceHeight) {
        plateRefusal = dwTraceSentence(
            DwTraceFailureKind.FRAME_MISMATCH,
            "${decoded.width}x${decoded.height} from a ${sourceWidth}x$sourceHeight photograph",
        )
    } else {
        val (plateWidth, plateHeight) = dwTraceWorkingSize(
            decoded.width,
            decoded.height,
            request.plateLongEdgePx,
        )
        tracePlate = DwSketchTracePlates.renderTrace(
            geometry = decoded.geometry,
            documentWidth = decoded.width,
            documentHeight = decoded.height,
            plateWidth = plateWidth,
            plateHeight = plateHeight,
        )
        photographPlate = tracePlate?.let {
            DwSketchTracePlates.photographPlate(
                rgba = rgba,
                width = sourceWidth,
                height = sourceHeight,
                plateWidth = plateWidth,
                plateHeight = plateHeight,
            )
        }
        if (tracePlate == null || photographPlate == null) {
            tracePlate?.recycle()
            photographPlate?.recycle()
            tracePlate = null
            photographPlate = null
            plateRefusal = DW_TRACE_PLATE_MEMORY_REFUSAL
        }
    }

    return DwTraceResult(
        svg = decoded.svg,
        geometry = decoded.geometry,
        tracePlate = tracePlate,
        photographPlate = photographPlate,
        plateRefusal = plateRefusal,
        width = decoded.width,
        height = decoded.height,
        workingWidth = decoded.workingWidth,
        workingHeight = decoded.workingHeight,
        shapeCount = decoded.shapeCount,
        nodeCount = decoded.nodeCount,
        stages = decoded.stages,
        totalMillis = decoded.totalMillis,
        notes = decoded.notes,
        appliedParams = decoded.appliedParams,
        autoSubjectId = decoded.autoSubjectId,
        suggestedStyleId = decoded.suggestedStyleId,
        frameNote = frameNote,
    )
}

internal suspend fun dwTraceDecodeForTrace(path: String): Bitmap = withContext(Dispatchers.IO) {
    if (path.isBlank() || !runCatching { File(path).isFile }.getOrDefault(false)) {
        throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_UNREADABLE, "no file at that path")
    }
    if (dwTraceRotationTagged(path)) {
        throw DwTraceHostFailure(
            DwTraceFailureKind.IMAGE_UNREADABLE,
            "this photo is rotated — straighten the sheet first, then trace the plate",
        )
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val storedWidth = bounds.outWidth
    val storedHeight = bounds.outHeight
    if (storedWidth < 1 || storedHeight < 1) {
        throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_EMPTY, "${storedWidth}x$storedHeight")
    }

    val (targetWidth, targetHeight) = dwTraceWorkingSize(storedWidth, storedHeight)
    var sample = 1
    while (maxOf(storedWidth, storedHeight) / (sample * 2) >= maxOf(targetWidth, targetHeight)) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
        ?: throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_UNREADABLE, "the decoder refused it")

    if (decoded.width == targetWidth && decoded.height == targetHeight) return@withContext decoded
    val scaled = runCatching {
        Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
    }.getOrNull()
    if (scaled == null || scaled === decoded) {
        return@withContext decoded
    }
    decoded.recycle()
    scaled
}

private fun dwTraceRotationTagged(path: String): Boolean {
    val exif = runCatching { ExifInterface(path) }.getOrNull() ?: return false
    val orientation = runCatching {
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return orientation != ExifInterface.ORIENTATION_NORMAL &&
        orientation != ExifInterface.ORIENTATION_UNDEFINED
}
