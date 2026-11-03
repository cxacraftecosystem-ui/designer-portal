package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

class GreyPlane(val data: ByteArray, val width: Int, val height: Int) {
    fun at(index: Int): Int = data[index].toInt() and 0xFF
}

object DwImageQuality {

    const val WORK_EDGE_PX = 640

    const val MIN_CONTRAST_STDDEV = 12.0

    const val BLUR_VARIANCE_FLOOR = 60.0

    const val MIN_LONG_EDGE_PX = 1280

    const val NEAR_DUPLICATE_MAX_DISTANCE = 6

    private const val HASH_EDGE = 8

    fun clampToByte(value: Double): Int {
        if (value.isNaN()) return 0
        if (value <= 0.0) return 0
        if (value >= 255.0) return 255
        val f = floor(value)
        return when {
            f + 0.5 < value -> (f + 1).toInt()
            value < f + 0.5 -> f.toInt()
            f.toInt() % 2 != 0 -> (f + 1).toInt()
            else -> f.toInt()
        }
    }

    internal fun jsRound(value: Double): Long = floor(value + 0.5).toLong()

    fun workingSizeFor(width: Int, height: Int): Pair<Int, Int> {
        val longEdge = max(width, height)
        if (longEdge <= 0) return 0 to 0
        val scale = if (longEdge <= WORK_EDGE_PX) 1.0 else WORK_EDGE_PX.toDouble() / longEdge
        return max(1L, jsRound(width * scale)).toInt() to max(1L, jsRound(height * scale)).toInt()
    }

    fun greyPlaneFromArgb(pixels: IntArray, width: Int, height: Int): GreyPlane {
        val data = ByteArray(width * height)
        for (index in data.indices) {
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            data[index] = clampToByte((red * 299 + green * 587 + blue * 114) / 1000.0).toByte()
        }
        return GreyPlane(data, width, height)
    }

    fun resampleGrey(plane: GreyPlane, width: Int, height: Int): GreyPlane {
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            val sourceTop = (y * plane.height) / height
            val sourceBottom = max(sourceTop + 1, ((y + 1) * plane.height) / height)
            for (x in 0 until width) {
                val sourceLeft = (x * plane.width) / width
                val sourceRight = max(sourceLeft + 1, ((x + 1) * plane.width) / width)
                var total = 0.0
                var count = 0
                for (sy in sourceTop until sourceBottom) {
                    for (sx in sourceLeft until sourceRight) {
                        total += plane.at(sy * plane.width + sx)
                        count += 1
                    }
                }
                data[y * width + x] = clampToByte(if (count > 0) total / count else 0.0).toByte()
            }
        }
        return GreyPlane(data, width, height)
    }

    fun contrastStdDev(plane: GreyPlane): Double {
        val length = plane.data.size
        if (length == 0) return 0.0
        var sum = 0.0
        for (index in 0 until length) sum += plane.at(index)
        val mean = sum / length
        var squares = 0.0
        for (index in 0 until length) {
            val delta = plane.at(index) - mean
            squares += delta * delta
        }
        return sqrt(squares / length)
    }

    fun laplacianVariance(plane: GreyPlane): Double {
        val width = plane.width
        val height = plane.height
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var squares = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val response = (
                    plane.at(index - width) + plane.at(index + width) +
                        plane.at(index - 1) + plane.at(index + 1) - 4 * plane.at(index)
                    ).toDouble()
                sum += response
                squares += response * response
                count += 1
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return squares / count - mean * mean
    }

    fun differenceHash(plane: GreyPlane): String {
        val small = resampleGrey(plane, HASH_EDGE + 1, HASH_EDGE)
        val hex = StringBuilder(HASH_EDGE * 2)
        for (y in 0 until HASH_EDGE) {
            var byte = 0
            for (x in 0 until HASH_EDGE) {
                val left = small.at(y * (HASH_EDGE + 1) + x)
                val right = small.at(y * (HASH_EDGE + 1) + x + 1)
                byte = (byte shl 1) or (if (left > right) 1 else 0)
            }
            hex.append(byte.toString(16).padStart(2, '0'))
        }
        return hex.toString()
    }

    fun hammingDistance(left: String, right: String): Int {
        if (left.length != right.length) return HASH_EDGE * HASH_EDGE
        var distance = 0
        for (index in left.indices) {
            val a = Character.digit(left[index], 16)
            val b = Character.digit(right[index], 16)
            if (a < 0 || b < 0) continue
            var diff = a xor b
            while (diff != 0) {
                distance += diff and 1
                diff = diff shr 1
            }
        }
        return distance
    }

    fun isBlurred(measurement: ImageMeasurement): Boolean = isBlurred(measurement.blurScore, measurement.contrast)

    fun isBlurred(blurScore: Double, contrast: Double): Boolean {
        if (contrast < MIN_CONTRAST_STDDEV) return false
        return blurScore < BLUR_VARIANCE_FLOOR
    }

    fun isUnderResolution(width: Int, height: Int): Boolean = max(width, height) < MIN_LONG_EDGE_PX

    private fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many

    fun findQualityIssues(
        measurement: ImageMeasurement,
        checksum: String? = null,
        attached: List<AttachedImage> = emptyList(),
    ): List<QualityFinding> {
        val findings = mutableListOf<QualityFinding>()

        if (isBlurred(measurement)) {
            findings += QualityFinding(
                flag = QualityFlag.BLUR,
                severity = QualitySeverity.MEDIUM,
                message = "Sharpness score ${jsRound(measurement.blurScore)} — a sharp photograph " +
                    "normally scores well above ${BLUR_VARIANCE_FLOOR.toInt()} here. This one may be " +
                    "out of focus. If it is meant to be soft, or it is the only shot you have, keep it.",
            )
        }

        if (isUnderResolution(measurement.width, measurement.height)) {
            findings += QualityFinding(
                flag = QualityFlag.LOW_RESOLUTION,
                severity = QualitySeverity.MEDIUM,
                message = "${measurement.width}x${measurement.height} — a catalogue plate needs about " +
                    "${MIN_LONG_EDGE_PX}px on the long edge to print sharply at A4. This will still be " +
                    "saved; it will simply print soft.",
            )
        }

        val identical = if (checksum.isNullOrBlank()) {
            emptyList()
        } else {
            attached.filter { !it.checksum.isNullOrBlank() && it.checksum == checksum }
        }
        if (identical.isNotEmpty()) {
            findings += QualityFinding(
                flag = QualityFlag.DUPLICATE,
                severity = QualitySeverity.LOW,
                message = "The same file is already attached here as " +
                    identical.joinToString(", ") { "\"${it.label}\"" } +
                    " — identical content, so this looks like the same import twice.",
            )
        } else {
            val near = attached
                .map { item ->
                    item to if (!item.perceptualHash.isNullOrBlank() && measurement.perceptualHash.isNotBlank()) {
                        hammingDistance(measurement.perceptualHash, item.perceptualHash)
                    } else {
                        Int.MAX_VALUE
                    }
                }
                .filter { (_, distance) -> distance <= NEAR_DUPLICATE_MAX_DISTANCE }
                .sortedBy { (_, distance) -> distance }
            if (near.isNotEmpty()) {
                findings += QualityFinding(
                    flag = QualityFlag.DUPLICATE,
                    severity = QualitySeverity.LOW,
                    message = "This looks like the same shot as " +
                        near.joinToString(", ") { (item, _) -> "\"${item.label}\"" } +
                        ". Two views of one object are worth keeping; two copies of one view are not.",
                )
            }
        }

        return findings
    }

    val NAMED_VIEW_SLOTS: Map<String, Map<String, String>> = mapOf(
        "existingProduct" to linkedMapOf(
            "viewFront" to "Front view",
            "viewBack" to "Back view",
            "viewDetail" to "Detail view",
        )
    )

    fun findMissingViews(entityKey: String, values: Map<String, JsonElement?>): List<QualityFinding> {
        val slots = NAMED_VIEW_SLOTS[entityKey] ?: return emptyList()
        val keys = slots.keys.toList()
        val present = keys.filter { key -> viewSlotFilled(values[key]) }
        if (present.isEmpty() || present.size == keys.size) return emptyList()
        val missing = keys.filterNot { it in present }
        return listOf(
            QualityFinding(
                flag = QualityFlag.MISSING_VIEW,
                severity = QualitySeverity.LOW,
                message = "${present.size} of ${keys.size} views captured — no " +
                    missing.joinToString(", no ") { slots.getValue(it).lowercase() } + ". " +
                    plural(missing.size, "That view is", "Those views are") +
                    " easiest to add now, while the product is still in front of you.",
            )
        )
    }

    private fun viewSlotFilled(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonArray -> value.isNotEmpty()
        is JsonPrimitive -> value.content.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
    }
}

enum class QualityFlag { BLUR, LOW_RESOLUTION, DUPLICATE, MISSING_VIEW }

enum class QualitySeverity { LOW, MEDIUM, HIGH }

data class QualityFinding(
    val flag: QualityFlag,
    val severity: QualitySeverity,
    val message: String,
)

data class ImageMeasurement(
    val width: Int,
    val height: Int,
    val blurScore: Double,
    val contrast: Double,
    val perceptualHash: String,
    val elapsedMs: Long,
)

data class AttachedImage(
    val label: String,
    val checksum: String? = null,
    val perceptualHash: String? = null,
)
