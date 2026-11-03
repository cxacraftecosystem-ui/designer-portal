package com.designprototype.workshop.data

object DwPhotoGate {

    enum class GateFaultKind { BLUR, LOW_RESOLUTION, EXACT_DUPLICATE, NEAR_DUPLICATE }

    data class GateFault(
        val kind: GateFaultKind,
        val flag: QualityFlag,
        val severity: QualitySeverity,
        val message: String,
    )

    fun faultRefuses(fault: GateFault): Boolean = fault.kind != GateFaultKind.NEAR_DUPLICATE

    const val NEAR_DUPLICATE_IS_NEVER_REFUSED = true

    data class GateVerdict(
        val admitted: Boolean,
        val faults: List<GateFault>,
    )

    fun judge(
        measurement: ImageMeasurement,
        checksum: String? = null,
        attached: List<AttachedImage> = emptyList(),
    ): GateVerdict {
        val faults = mutableListOf<GateFault>()

        if (DwImageQuality.isBlurred(measurement)) {
            faults += GateFault(
                kind = GateFaultKind.BLUR,
                flag = QualityFlag.BLUR,
                severity = QualitySeverity.MEDIUM,
                message = "the sharpness reading was ${DwImageQuality.jsRound(measurement.blurScore)} " +
                    "against a floor of ${DwImageQuality.BLUR_VARIANCE_FLOOR.toInt()}, so it is out " +
                    "of focus or the camera moved. Hold still, tap the subject to focus, and take it " +
                    "again.",
            )
        }

        if (DwImageQuality.isUnderResolution(measurement.width, measurement.height)) {
            faults += GateFault(
                kind = GateFaultKind.LOW_RESOLUTION,
                flag = QualityFlag.LOW_RESOLUTION,
                severity = QualitySeverity.MEDIUM,
                message = "it is ${measurement.width}x${measurement.height}, and a report plate needs " +
                    "about ${DwImageQuality.MIN_LONG_EDGE_PX}px on the long edge. Raise the camera's " +
                    "resolution, or send the original rather than a copy something has already shrunk.",
            )
        }

        val identical = if (checksum.isNullOrBlank()) {
            emptyList()
        } else {
            attached.filter { !it.checksum.isNullOrBlank() && it.checksum == checksum }
        }
        if (identical.isNotEmpty()) {
            faults += GateFault(
                kind = GateFaultKind.EXACT_DUPLICATE,
                flag = QualityFlag.DUPLICATE,
                severity = QualitySeverity.LOW,
                message = "the identical file is already attached here as " +
                    identical.joinToString(", ") { "\"${it.label}\"" } +
                    ". Nothing is lost by leaving this copy out.",
            )
        } else {
            val near = attached
                .map { item ->
                    item to if (!item.perceptualHash.isNullOrBlank() && measurement.perceptualHash.isNotBlank()) {
                        DwImageQuality.hammingDistance(measurement.perceptualHash, item.perceptualHash)
                    } else {
                        Int.MAX_VALUE
                    }
                }
                .filter { (_, distance) -> distance <= DwImageQuality.NEAR_DUPLICATE_MAX_DISTANCE }
                .sortedBy { (_, distance) -> distance }
            if (near.isNotEmpty()) {
                val closest = near.first().second
                faults += GateFault(
                    kind = GateFaultKind.NEAR_DUPLICATE,
                    flag = QualityFlag.DUPLICATE,
                    severity = QualitySeverity.LOW,
                    message = "this looks like the same shot as " +
                        near.joinToString(", ") { (item, _) -> "\"${item.label}\"" } +
                        " — $closest bit${if (closest == 1) "" else "s"} different out of 64, against " +
                        "a threshold of ${DwImageQuality.NEAR_DUPLICATE_MAX_DISTANCE}. Two views of " +
                        "one object are worth keeping; two copies of one view are not.",
                )
            }
        }

        return GateVerdict(admitted = faults.none(::faultRefuses), faults = faults)
    }

    fun scopeSentence(): String =
        "Each photograph is checked on this device before it uploads — for focus, for resolution, " +
            "and for being a file that is already attached here. Exposure and subject are not " +
            "checked; judge those by eye."

    fun refusalHeading(count: Int): String {
        if (count <= 0) return ""
        val one = count == 1
        return "$count photograph${if (one) "" else "s"} ${if (one) "was" else "were"} not attached, " +
            "so nothing was sent. Take ${if (one) "it" else "them"} again and attach " +
            "${if (one) "it" else "them"}."
    }

    data class RefusedPhoto(val displayName: String, val faults: List<GateFault>)

    fun refusalLines(refused: List<RefusedPhoto>): List<String> = refused.mapNotNull { entry ->
        val reasons = entry.faults.filter(::faultRefuses).joinToString(" And ") { it.message }
        if (reasons.isEmpty()) null else "${entry.displayName} — $reasons"
    }

    data class GalleryCounts(
        val held: Int,
        val onDevice: Int = 0,
        val screening: Int = 0,
        val uploading: Int = 0,
    )

    data class GalleryProgress(
        val held: Int,
        val floor: Int,
        val percent: Int,
        val readout: String,
        val words: String,
        val complete: Boolean,
    )

    fun galleryProgress(counts: GalleryCounts, floor: Int): GalleryProgress {
        val held = maxOf(0, counts.held)
        val safeFloor = maxOf(1, floor)
        val percent = ((held * 100.0) / safeFloor).let { Math.round(it).toInt() }.coerceIn(0, 100)
        val readout = "$held of $floor"
        val remaining = maxOf(0, floor - held)

        val clauses = mutableListOf<String>()
        when {
            remaining == 0 -> clauses += "All $floor photographs are attached."
            held == 0 -> clauses += "None of the $floor photographs this gallery needs are attached yet."
            else -> clauses += "$readout photographs are attached. $remaining more " +
                "${if (remaining == 1) "is" else "are"} needed."
        }
        if (counts.screening > 0) {
            clauses += "${counts.screening} ${if (counts.screening == 1) "is" else "are"} being " +
                "checked before ${if (counts.screening == 1) "it uploads" else "they upload"}."
        }
        if (counts.uploading > 0) {
            clauses += "${counts.uploading} more ${if (counts.uploading == 1) "is" else "are"} " +
                "uploading and ${if (counts.uploading == 1) "is" else "are"} not counted yet."
        }
        if (counts.onDevice > 0) {
            clauses += if (counts.onDevice >= held) {
                "All $held are on this device only until the connection returns."
            } else {
                "${counts.onDevice} of the $held ${if (counts.onDevice == 1) "is" else "are"} on " +
                    "this device only until the connection returns."
            }
        }

        return GalleryProgress(
            held = held,
            floor = floor,
            percent = percent,
            readout = readout,
            words = clauses.joinToString(" "),
            complete = remaining == 0,
        )
    }

    fun galleryFloorSentence(floor: Int, label: String): String =
        "All $floor are required. The stage still saves with fewer — nothing you attach is ever at " +
            "risk — but until $label holds $floor the stage is scored incomplete, and the generated " +
            "report says so. Attached is saved on this device, and uploads when this stage " +
            "syncs."

    data class CapturedFinding(
        val mediaId: String,
        val fileName: String,
        val flag: QualityFlag,
        val severity: QualitySeverity,
        val note: String,
        val raisedAt: String,
    )

    val AUTO_FILLABLE_FLAGS: Set<QualityFlag> =
        setOf(QualityFlag.BLUR, QualityFlag.LOW_RESOLUTION, QualityFlag.DUPLICATE)

    data class MediaQualityFlagRow(
        val mediaId: String,
        val flag: QualityFlag,
        val severity: QualitySeverity,
        val autoDetected: Boolean = true,
        val note: String,
    )

    fun mediaQualityFlagRows(findings: List<CapturedFinding>): List<MediaQualityFlagRow> {
        val byKey = LinkedHashMap<String, MediaQualityFlagRow>()
        for (finding in findings) {
            if (finding.flag !in AUTO_FILLABLE_FLAGS) continue
            byKey["${finding.mediaId}:${finding.flag}"] = MediaQualityFlagRow(
                mediaId = finding.mediaId,
                flag = finding.flag,
                severity = finding.severity,
                autoDetected = true,
                note = "${finding.fileName}: ${finding.note}",
            )
        }
        return byKey.values.toList()
    }
}
