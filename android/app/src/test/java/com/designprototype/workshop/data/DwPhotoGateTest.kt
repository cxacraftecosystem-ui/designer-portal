package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE GATE, BY VALUE** — which photographs are refused, which are admitted with a word said about
 * them, and every sentence a designer reads either way.
 *
 * WHY THIS IS A UNIT TEST AND NOT A SCREENSHOT. Every judgement below is a decision about work that
 * cannot be redone: a refusal turns away a photograph of an object that will be sold before anyone
 * is back in the cluster, and an admission puts a soft one into a document a ministry receives.
 * There is no Robolectric in this module, so a rule written inside a composable is a rule nothing
 * can exercise — which is exactly why [DwPhotoGate] holds no Android at all.
 *
 * THE SENTENCES ARE ASSERTED AS SENTENCES, not merely as "some message". They are the whole of what
 * a designer has to act on, they are what the web is being asked to copy verbatim, and a wording
 * that drifts is two clients describing one refusal differently.
 */
class DwPhotoGateTest {

    /**
     * A photograph with nothing wrong with it: sharp, contrasty, well past the report's own plate
     * geometry, and unlike anything already attached.
     */
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

    // ── What is refused ─────────────────────────────────────────────────────────────────────────

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

    /**
     * THE FLOOR IS PRINTED AS AN INTEGER, NOT AS THE Double IT IS DECLARED AS.
     *
     * [DwImageQuality.BLUR_VARIANCE_FLOOR] is `60.0` in Kotlin and `60` in the TypeScript it is a
     * port of. Interpolated raw, this client would say "a floor of 60.0" about the same calibration
     * the browser calls 60 — two clients quoting one number differently, in the sentence a designer
     * is expected to argue with.
     */
    @Test
    fun `the blur sentence never prints the floor as a decimal`() {
        val message = DwPhotoGate.judge(goodPhotograph(blurScore = 10.0, contrast = 40.0))
            .faults.single().message
        assertTrue(message.contains("a floor of 60,"))
        assertFalse(message.contains("60.0"))
    }

    /**
     * THE CONTRAST GUARD IS THE ONLY THING STANDING BETWEEN THIS GATE AND A CORRECT PHOTOGRAPH OF A
     * FLAT MOTIF, AND IT IS NOW CARRYING A REFUSAL RATHER THAN A WARNING.
     *
     * `ImageQuality.kt` records the measurement by name: a perfectly sharp field scaled to a flat
     * subject — undyed cotton, a smooth metal tool, a plain-dyed cloth, which is much of what these
     * clusters make — scores a blur variance of 58.98 at a contrast of 9.03. That is BELOW the blur
     * floor. Without the guard the gate would refuse it, and a designer photographing a plain-dyed
     * length would be told twenty-five times that their camera had moved.
     *
     * This case is asserted here, on the refusal's side of the line, as well as wherever the
     * predicate itself is tested: raising [DwImageQuality.MIN_CONTRAST_STDDEV] is now a decision
     * about how often a correct photograph is turned away, and it should fail here when it is made.
     */
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

    // ── Duplicates: the exact one closes the door, the near one does not ────────────────────────

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

    /**
     * THE NEAR-DUPLICATE IS ADMITTED, AND THIS IS THE ASSERTION THAT KEEPS THE FEATURE USABLE.
     *
     * `ImageQuality.kt`'s calibration says two exposures of one object seconds apart land in the low
     * single digits, INSIDE the threshold of 6 — and two exposures of one object seconds apart is
     * how twenty-five motifs on one length of cloth get photographed. Refusing here would turn away
     * correct, wanted, irreplaceable work, and it would do it most often to the designer working
     * fastest.
     */
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
        // The reading and the threshold, because this sentence ends up in an archive row rather
        // than only on a screen: "looks like the same shot" is an opinion, "1 bit different out of
        // 64, against a threshold of 6" is evidence somebody can check.
        assertEquals(
            "this looks like the same shot as \"motif-04.jpg\" — 1 bit different out of 64, against " +
                "a threshold of 6. Two views of one object are worth keeping; two copies of one " +
                "view are not.",
            verdict.faults.single().message,
        )
    }

    /**
     * THE ARM THAT MAKES THE WRITE PATH REAL, ASSERTED AS AN END-TO-END SHAPE RATHER THAN AS A
     * MESSAGE.
     *
     * `frontend/components/media/photoGate.ts` has no near-duplicate arm at all, so every fault its
     * gate can produce also REFUSES — which means its verdict never carries a fault on a photograph
     * that was actually uploaded, `CapturedFinding` is never produced, and
     * `mediaQualityFlagRows` — documented as existing precisely to record "the one fault worth
     * recording" — is handed nothing, forever. This test is what stops the same hole opening here:
     * a fault that survives the gate has to reach a row.
     */
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

    // ── What is said ────────────────────────────────────────────────────────────────────────────

    /**
     * THE SCOPE SENTENCE NAMES WHAT IS NOT CHECKED, AND QUOTES NO NUMBER.
     *
     * The floors are client constants that move with a re-calibration; a fixed sentence quoting one
     * goes stale silently, and a stated threshold that is not the enforced threshold is worse than
     * no sentence at all. Every refusal prints its own reading and its own floor instead.
     */
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

    /**
     * A NEAR-DUPLICATE PRODUCES NO REFUSAL LINE, because it produced no refusal. A receipt naming a
     * photograph that was in fact attached is the worst thing this notice could say.
     */
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

    // ── The floor, and the bar it is counted against ────────────────────────────────────────────

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

    /**
     * A PHOTOGRAPH BEING CHECKED IS NOT IN THE GALLERY, and the numerator must never quietly include
     * it. "25 of 25" over a gallery a save would post twenty-three of is a full bar as a receipt for
     * work that has not happened.
     */
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

    /**
     * THE STANDING SENTENCE PROMISES NO SUBMIT BLOCK AND NAMES NO READINESS SCREEN, because neither
     * is true: `PATCH /design-workshops/{id}` writes SUBMITTED behind an enum check with no
     * completeness test anywhere. What IS true is what it says — the stage still saves, the score
     * says incomplete, and the report prints it.
     */
    @Test
    fun `the floor sentence claims only what is actually enforced`() {
        val sentence = DwPhotoGate.galleryFloorSentence(25, "Traditional motif photographs")
        assertTrue(sentence.startsWith("All 25 are required."))
        assertTrue(sentence.contains("The stage still saves with fewer"))
        assertTrue(sentence.contains("until Traditional motif photographs holds 25"))
        assertTrue(sentence.contains("the stage is scored incomplete"))
        assertFalse(sentence.contains("submit"))
        assertFalse(sentence.contains("readiness"))
        // The handset's own vocabulary for what has and has not left the phone — the same words the
        // stage screen's status line uses, so the two cannot disagree about what "saved" means here.
        assertTrue(sentence.contains("Attached is saved on this device"))
        assertTrue(sentence.contains("when this stage syncs"))
    }

    // ── The write path ─────────────────────────────────────────────────────────────────────────

    /**
     * FOUR OF THE SEVEN REGISTRY FLAGS MAY NEVER BE FILLED IN BY A MACHINE. Nothing in this product
     * measures exposure or subject, and MISSING_VIEW is about a ROW rather than about one file, so
     * it has no media id to be recorded against.
     */
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

    /**
     * THE NEWEST READING WINS AND THE ROW KEEPS ITS PLACE. The same photograph really is measured
     * more than once — a stage reopened, a collection row re-expanded — and the reading that
     * describes the file in hand is the last one taken; two rows about one file read as two separate
     * problems. Re-ordering the table under a reader because a number was refreshed would be a
     * second, quieter defect.
     */
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
