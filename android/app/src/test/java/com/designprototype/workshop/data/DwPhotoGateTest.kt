package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwPhotoGateTest {

    private fun goodPhotograph(
        blurScore: Double = 740.0,
        contrast: Double = 48.0,
        width: Int = 4000,
        height: Int = 3000,
        perceptualHash: String = "a5a54a4aa5a54a4a",
    ) = ImageMeasurement(
        width = width,
        height = height,
        blurScore = blurScore,
        contrast = contrast,
        perceptualHash = perceptualHash,
        elapsedMs = 120,
    )

    @Test
    fun `a sharp photograph nothing matches is admitted with nothing said about it`() {
        val verdict = DwPhotoGate.judge(goodPhotograph())
        assertTrue(verdict.admitted)
        assertTrue(verdict.faults.isEmpty())
    }

    @Test
    fun `a blurred photograph is refused, and the sentence carries the reading and the floor`() {
        val verdict = DwPhotoGate.judge(goodPhotograph(blurScore = 42.4, contrast = 31.0))
        assertFalse(verdict.admitted)
        assertEquals(1, verdict.faults.size)
        val fault = verdict.faults.single()
        assertEquals(DwPhotoGate.GateFaultKind.BLUR, fault.kind)
        assertEquals(QualityFlag.BLUR, fault.flag)
        assertEquals(QualitySeverity.MEDIUM, fault.severity)
        assertEquals(
            "the sharpness reading was 42 against a floor of 60, so it is out of focus or the camera " +
                "moved. Hold still, tap the subject to focus, and take it again.",
            fault.message,
        )
    }

    @Test
    fun `the blur sentence never prints the floor as a decimal`() {
        val message = DwPhotoGate.judge(goodPhotograph(blurScore = 10.0, contrast = 40.0))
            .faults.single().message
        assertTrue(message.contains("a floor of 60,"))
        assertFalse(message.contains("60.0"))
    }

    @Test
    fun `a sharp photograph of a flat subject is admitted even though it scores under the blur floor`() {
        val flat = goodPhotograph(blurScore = 58.98, contrast = 9.03)
        assertTrue(flat.blurScore < DwImageQuality.BLUR_VARIANCE_FLOOR)
        assertTrue(flat.contrast < DwImageQuality.MIN_CONTRAST_STDDEV)
        val verdict = DwPhotoGate.judge(flat)
        assertTrue(verdict.admitted)
        assertTrue(verdict.faults.isEmpty())
    }

    @Test
    fun `an under-resolution photograph is refused, with its real dimensions and the plate's`() {
        val verdict = DwPhotoGate.judge(goodPhotograph(width = 900, height = 675))
        assertFalse(verdict.admitted)
        assertEquals(
            "it is 900x675, and a report plate needs about 1280px on the long edge. Raise the " +
                "camera's resolution, or send the original rather than a copy something has already " +
                "shrunk.",
            verdict.faults.single().message,
        )
    }

    @Test
    fun `one photograph can fail twice and both reasons are given`() {
        val verdict = DwPhotoGate.judge(
            goodPhotograph(blurScore = 5.0, contrast = 30.0, width = 800, height = 600)
        )
        assertFalse(verdict.admitted)
        assertEquals(
            listOf(DwPhotoGate.GateFaultKind.BLUR, DwPhotoGate.GateFaultKind.LOW_RESOLUTION),
            verdict.faults.map { it.kind },
        )
    }

    @Test
    fun `a byte-identical file is refused and the message names what it duplicates`() {
        val verdict = DwPhotoGate.judge(
            measurement = goodPhotograph(),
            checksum = "abc123",
            attached = listOf(AttachedImage(label = "motif-04.jpg", checksum = "abc123")),
        )
        assertFalse(verdict.admitted)
        val fault = verdict.faults.single()
        assertEquals(DwPhotoGate.GateFaultKind.EXACT_DUPLICATE, fault.kind)
        assertEquals(QualityFlag.DUPLICATE, fault.flag)
        assertEquals(
            "the identical file is already attached here as \"motif-04.jpg\". Nothing is lost by " +
                "leaving this copy out.",
            fault.message,
        )
    }

    @Test
    fun `a perceptual-hash match is admitted, with the duplicate still reported`() {
        val verdict = DwPhotoGate.judge(
            measurement = goodPhotograph(perceptualHash = "a5a54a4aa5a54a4b"),
            checksum = "different",
            attached = listOf(
                AttachedImage(
                    label = "motif-04.jpg",
                    checksum = "abc123",
                    perceptualHash = "a5a54a4aa5a54a4a",
                )
            ),
        )
        assertTrue(verdict.admitted)
        assertEquals(DwPhotoGate.GateFaultKind.NEAR_DUPLICATE, verdict.faults.single().kind)
        assertEquals(QualityFlag.DUPLICATE, verdict.faults.single().flag)
        assertEquals(
            "this looks like the same shot as \"motif-04.jpg\" — 1 bit different out of 64, against " +
                "a threshold of 6. Two views of one object are worth keeping; two copies of one " +
                "view are not.",
            verdict.faults.single().message,
        )
    }

    @Test
    fun `a fault that does not refuse still becomes an archive row`() {
        val verdict = DwPhotoGate.judge(
            measurement = goodPhotograph(perceptualHash = "a5a54a4aa5a54a4b"),
            checksum = "different",
            attached = listOf(
                AttachedImage("motif-04.jpg", checksum = "abc123", perceptualHash = "a5a54a4aa5a54a4a")
            ),
        )
        assertTrue("it was imported", verdict.admitted)
        val rows = DwPhotoGate.mediaQualityFlagRows(
            verdict.faults.map { fault ->
                DwPhotoGate.CapturedFinding(
                    mediaId = "media-77",
                    fileName = "motif-05.jpg",
                    flag = fault.flag,
                    severity = fault.severity,
                    note = fault.message,
                    raisedAt = "2026-08-28T09:00:00Z",
                )
            }
        )
        assertEquals(1, rows.size)
        assertEquals("media-77", rows.single().mediaId)
        assertEquals(QualityFlag.DUPLICATE, rows.single().flag)
        assertTrue(rows.single().note.startsWith("motif-05.jpg: this looks like the same shot as"))
    }

    @Test
    fun `an unknown checksum makes no duplicate claim in either direction`() {
        val verdict = DwPhotoGate.judge(
            measurement = goodPhotograph(perceptualHash = "0000000000000000"),
            checksum = null,
            attached = listOf(AttachedImage(label = "motif-04.jpg", checksum = "abc123")),
        )
        assertTrue(verdict.admitted)
        assertTrue(verdict.faults.isEmpty())
    }

    @Test
    fun `exactly one kind of fault leaves the door open`() {
        val refusing = DwPhotoGate.GateFaultKind.entries.filter { kind ->
            DwPhotoGate.faultRefuses(
                DwPhotoGate.GateFault(kind, QualityFlag.BLUR, QualitySeverity.LOW, "")
            )
        }
        assertEquals(
            listOf(
                DwPhotoGate.GateFaultKind.BLUR,
                DwPhotoGate.GateFaultKind.LOW_RESOLUTION,
                DwPhotoGate.GateFaultKind.EXACT_DUPLICATE,
            ),
            refusing,
        )
    }

    @Test
    fun `the scope sentence names exposure and subject as unchecked and carries no digits`() {
        val sentence = DwPhotoGate.scopeSentence()
        assertTrue(sentence.contains("Exposure and subject are not checked; judge those by eye."))
        assertTrue(sentence.none { it.isDigit() })
    }

    @Test
    fun `the refusal heading counts, and says nothing was sent`() {
        assertEquals(
            "1 photograph was not attached, so nothing was sent. Take it again and attach it.",
            DwPhotoGate.refusalHeading(1),
        )
        assertEquals(
            "3 photographs were not attached, so nothing was sent. Take them again and attach them.",
            DwPhotoGate.refusalHeading(3),
        )
        assertEquals("", DwPhotoGate.refusalHeading(0))
    }

    @Test
    fun `every refused file gets its own line, named, with its own reason`() {
        val soft = DwPhotoGate.judge(goodPhotograph(blurScore = 12.0, contrast = 30.0))
        val small = DwPhotoGate.judge(goodPhotograph(width = 640, height = 480))
        val lines = DwPhotoGate.refusalLines(
            listOf(
                DwPhotoGate.RefusedPhoto("IMG_0001.jpg", soft.faults),
                DwPhotoGate.RefusedPhoto("The photograph you just took", small.faults),
            )
        )
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("IMG_0001.jpg — the sharpness reading was 12 "))
        assertTrue(lines[1].startsWith("The photograph you just took — it is 640x480, "))
    }

    @Test
    fun `an admitted fault never appears in the refusal lines`() {
        val nearDuplicate = DwPhotoGate.GateFault(
            kind = DwPhotoGate.GateFaultKind.NEAR_DUPLICATE,
            flag = QualityFlag.DUPLICATE,
            severity = QualitySeverity.LOW,
            message = "this looks like the same shot.",
        )
        assertTrue(
            DwPhotoGate.refusalLines(
                listOf(DwPhotoGate.RefusedPhoto("motif-09.jpg", listOf(nearDuplicate)))
            ).isEmpty()
        )
    }

    @Test
    fun `an empty gallery reads none of them, and a full one reads all of them`() {
        val empty = DwPhotoGate.galleryProgress(DwPhotoGate.GalleryCounts(held = 0), floor = 25)
        assertEquals("0 of 25", empty.readout)
        assertEquals(0, empty.percent)
        assertFalse(empty.complete)
        assertEquals("None of the 25 photographs this gallery needs are attached yet.", empty.words)

        val full = DwPhotoGate.galleryProgress(DwPhotoGate.GalleryCounts(held = 25), floor = 25)
        assertEquals("25 of 25", full.readout)
        assertEquals(100, full.percent)
        assertTrue(full.complete)
        assertEquals("All 25 photographs are attached.", full.words)
    }

    @Test
    fun `a partial gallery says how many more are needed`() {
        val progress = DwPhotoGate.galleryProgress(DwPhotoGate.GalleryCounts(held = 24), floor = 25)
        assertEquals("24 of 25", progress.readout)
        assertEquals(96, progress.percent)
        assertFalse(progress.complete)
        assertEquals("24 of 25 photographs are attached. 1 more is needed.", progress.words)
    }

    @Test
    fun `photographs still being screened are named in their own clause and never counted`() {
        val progress = DwPhotoGate.galleryProgress(
            DwPhotoGate.GalleryCounts(held = 23, screening = 2),
            floor = 25,
        )
        assertEquals("23 of 25", progress.readout)
        assertTrue(progress.words.startsWith("23 of 25 photographs are attached. 2 more are needed."))
        assertTrue(progress.words.contains("2 are being checked before they upload."))
    }

    @Test
    fun `a gallery entirely on this device says so as a whole rather than as a fraction of itself`() {
        val all = DwPhotoGate.galleryProgress(
            DwPhotoGate.GalleryCounts(held = 25, onDevice = 25),
            floor = 25,
        )
        assertTrue(all.words.contains("All 25 are on this device only until the connection returns."))
        assertFalse(all.words.contains("25 of the 25"))

        val some = DwPhotoGate.galleryProgress(
            DwPhotoGate.GalleryCounts(held = 18, onDevice = 11),
            floor = 25,
        )
        assertTrue(some.words.contains("11 of the 18 are on this device only until the connection returns."))
    }

    @Test
    fun `the percentage is clamped rather than allowed past the floor`() {
        val over = DwPhotoGate.galleryProgress(DwPhotoGate.GalleryCounts(held = 40), floor = 25)
        assertEquals(100, over.percent)
        assertTrue(over.complete)
        val negative = DwPhotoGate.galleryProgress(DwPhotoGate.GalleryCounts(held = -3), floor = 25)
        assertEquals(0, negative.percent)
    }

    @Test
    fun `the floor sentence claims only what is actually enforced`() {
        val sentence = DwPhotoGate.galleryFloorSentence(25, "Traditional motif photographs")
        assertTrue(sentence.startsWith("All 25 are required."))
        assertTrue(sentence.contains("The stage still saves with fewer"))
        assertTrue(sentence.contains("until Traditional motif photographs holds 25"))
        assertTrue(sentence.contains("the stage is scored incomplete"))
        assertFalse(sentence.contains("submit"))
        assertFalse(sentence.contains("readiness"))
        assertTrue(sentence.contains("Attached is saved on this device"))
        assertTrue(sentence.contains("when this stage syncs"))
    }

    @Test
    fun `only the three measured flags are auto-fillable`() {
        assertEquals(
            setOf(QualityFlag.BLUR, QualityFlag.LOW_RESOLUTION, QualityFlag.DUPLICATE),
            DwPhotoGate.AUTO_FILLABLE_FLAGS,
        )
        assertFalse(QualityFlag.MISSING_VIEW in DwPhotoGate.AUTO_FILLABLE_FLAGS)
    }

    private fun finding(
        mediaId: String,
        flag: QualityFlag = QualityFlag.DUPLICATE,
        note: String = "same shot",
        fileName: String = "motif-09.jpg",
        raisedAt: String = "2026-08-28T09:00:00Z",
    ) = DwPhotoGate.CapturedFinding(
        mediaId = mediaId,
        fileName = fileName,
        flag = flag,
        severity = QualitySeverity.LOW,
        note = note,
        raisedAt = raisedAt,
    )

    @Test
    fun `a finding becomes a row that names the file in its note and is marked auto-detected`() {
        val rows = DwPhotoGate.mediaQualityFlagRows(listOf(finding("media-1")))
        assertEquals(1, rows.size)
        assertEquals("media-1", rows.single().mediaId)
        assertEquals(QualityFlag.DUPLICATE, rows.single().flag)
        assertTrue(rows.single().autoDetected)
        assertEquals("motif-09.jpg: same shot", rows.single().note)
    }

    @Test
    fun `a flag nothing measures never becomes a row`() {
        val rows = DwPhotoGate.mediaQualityFlagRows(
            listOf(finding("media-1", flag = QualityFlag.MISSING_VIEW))
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `one row per file per flag, newest reading kept, original position held`() {
        val rows = DwPhotoGate.mediaQualityFlagRows(
            listOf(
                finding("media-1", note = "first reading"),
                finding("media-2", note = "other file"),
                finding("media-1", note = "second reading"),
            )
        )
        assertEquals(listOf("media-1", "media-2"), rows.map { it.mediaId })
        assertEquals("motif-09.jpg: second reading", rows.first().note)
    }

    @Test
    fun `two different faults on one file are two rows`() {
        val rows = DwPhotoGate.mediaQualityFlagRows(
            listOf(
                finding("media-1", flag = QualityFlag.BLUR),
                finding("media-1", flag = QualityFlag.DUPLICATE),
            )
        )
        assertEquals(2, rows.size)
    }
}
