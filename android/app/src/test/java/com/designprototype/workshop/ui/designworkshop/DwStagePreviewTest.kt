package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StageListDto
import com.designprototype.workshop.data.WorkshopDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The stage preview's two release blockers, held where a unit test can reach them:
 *
 *  · THE ZOOM MATH — [dwFitScale], [dwZoomTarget], [dwScaledExtent] — and the guard that the sheet
 *    is not drawn with `requiredSize` again, whose centring left the page blank at every zoom but 100%;
 *  · THE DRAFT — [dwStagePreviewDraft] — which is always the report screen's merge of this device's
 *    draft with the server's copy, so a one-stage local draft never hides the other stages;
 *  · THE SERVER READ — [DwPreviewServerCopy] — which tells offline and failed apart from empty, and
 *    survives the debounce cancelling the build that is waiting for it.
 */
class DwStagePreviewTest {

    // ── Scale ─────────────────────────────────────────────────────────────────────────────────────

    private val a4WidthPx = 210f * DW_PX_PER_MM
    private val a4HeightPx = 297f * DW_PX_PER_MM

    @Test
    fun `fit width on a phone is the column over the page, and never enlarges`() {
        val fit = dwFitScale(availableWidth = 350f, pageWidth = a4WidthPx)
        assertEquals(350f / a4WidthPx, fit, 1e-4f)
        assertEquals(0.44f, fit, 0.01f)
        assertEquals(1f, dwFitScale(availableWidth = 1600f, pageWidth = a4WidthPx), 0f)
    }

    @Test
    fun `an unusable width falls back instead of collapsing the sheet`() {
        assertEquals(1f, dwFitScale(Float.POSITIVE_INFINITY, a4WidthPx), 0f)
        assertEquals(1f, dwFitScale(Float.NaN, a4WidthPx), 0f)
        assertEquals(DW_FIT_MIN, dwFitScale(0f, a4WidthPx), 0f)
        assertEquals(1f, dwFitScale(350f, 0f), 0f)
    }

    @Test
    fun `zoom targets stay between half the fit and the maximum`() {
        val fit = 0.44f
        assertEquals(fit, dwZoomTarget(null, fit), 0f)
        assertEquals(1f, dwZoomTarget(1f, fit), 0f)
        assertEquals(fit * 0.5f, dwZoomTarget(0.01f, fit), 0f)
        assertEquals(DW_ZOOM_MAX, dwZoomTarget(9f, fit), 0f)
        assertEquals(fit, dwZoomTarget(Float.NaN, fit), 0f)
        // Smaller from the fit, then Larger back, returns to the fit.
        val smaller = dwZoomTarget(fit - DW_ZOOM_STEP, fit)
        assertEquals(fit, dwZoomTarget(smaller + DW_ZOOM_STEP, fit), 1e-5f)
    }

    @Test
    fun `the scaled page fills its sheet exactly, from the top-left, at every zoom`() {
        val density = 2.625f
        val refW = (a4WidthPx * density).toInt()
        val refH = (a4HeightPx * density).toInt()
        val fit = dwFitScale(350f, a4WidthPx)
        for (scale in listOf(fit * 0.5f, fit, fit + DW_ZOOM_STEP, 1f, 1.5f, DW_ZOOM_MAX)) {
            val w = dwScaledExtent(refW, scale)
            val h = dwScaledExtent(refH, scale)
            // The reference page's far corner, drawn at `scale` from the origin, lands on the sheet's
            // own far corner — the running foot is therefore inside the sheet, not off its edge.
            assertTrue("width at $scale", abs(refW * scale - w) <= 1f)
            assertTrue("height at $scale", abs(refH * scale - h) <= 1f)
            // And it agrees with how the sheet itself is sized: `Modifier.size((ref × scale).dp)`.
            assertEquals(Math.round(a4WidthPx * scale * density).toFloat(), w.toFloat(), 1f)
        }
        // Held inside what the parent allows.
        assertEquals(500, dwScaledExtent(refW, 1f, maxPx = 500))
    }

    @Test
    fun `the sheet is placed at its origin, never centred by an oversized requiredSize`() {
        val source = repoFile(
            "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwReportSheets.kt"
        ).readText()
        assertFalse(source.contains(".requiredSize("))
        assertTrue(source.contains(".dwScaledPage(geometry.pageWidthPx.dp, geometry.pageHeightPx.dp, scale)"))
        assertTrue(source.contains("placeable.placeWithLayer(0, 0)"))
    }

    // ── The draft the preview is built from ───────────────────────────────────────────────────────

    private val schema = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 1, key = "WORKSHOP_SETUP", title = "Workshop setup",
                entities = listOf(
                    EntityDto(
                        key = "setup", cardinality = "SINGLETON", title = "Workshop setup",
                        fields = listOf(FieldDto(key = "craftName", label = "Craft", type = "TEXT", reportRole = "KEY_VALUE")),
                    )
                ),
            ),
            StageDto(
                number = 7, key = "MARKET_SURVEY_PLANNING", title = "Market survey planning",
                entities = listOf(
                    EntityDto(
                        key = "survey", cardinality = "SINGLETON", title = "Survey",
                        fields = listOf(FieldDto(key = "objectives", label = "Objectives", type = "TEXT", reportRole = "KEY_VALUE")),
                    )
                ),
            ),
        ),
    )

    private val stageKey = "MARKET_SURVEY_PLANNING"

    private fun typed(text: String): (StageDraft?) -> StageDraft = { existing ->
        (existing ?: StageDraft(stageId = stageKey)).copy(values = mapOf("objectives" to JsonPrimitive(text)))
    }

    private val untouched: (StageDraft?) -> StageDraft = { existing -> existing ?: StageDraft(stageId = stageKey) }

    private val serverCopy = StageListDto(
        stages = mapOf(
            "WORKSHOP_SETUP" to StageBucketDto(
                singleton = JsonObject(mapOf("craftName" to JsonPrimitive("Dabu print")))
            ),
        )
    )

    @Test
    fun `no draft on this device builds from the server's copy, without waiting for a keystroke`() {
        val draft = dwStagePreviewDraft(schema, "ws-1", stageKey, local = null, remoteId = "ws-1", remote = serverCopy, live = untouched)
        assertNotNull(draft)
        assertEquals(
            "Dabu print",
            (draft!!.stages["WORKSHOP_SETUP"]?.values?.get("craftName") as JsonPrimitive).content,
        )
        assertTrue(stageKey in draft.stages)
    }

    @Test
    fun `typing on a workshop with no draft anywhere still previews the stage`() {
        val draft = dwStagePreviewDraft(schema, "ws-1", stageKey, local = null, remoteId = null, remote = null, live = typed("Learn prices"))
        assertNotNull(draft)
        assertEquals("ws-1", draft!!.workshopId)
        assertEquals(
            "Learn prices",
            (draft.stages[stageKey]?.values?.get("objectives") as JsonPrimitive).content,
        )
    }

    @Test
    fun `genuinely nothing is the empty state, not a build`() {
        assertNull(dwStagePreviewDraft(schema, "ws-1", stageKey, local = null, remoteId = "ws-1", remote = null, live = untouched))
        assertNull(
            dwStagePreviewDraft(schema, "ws-1", stageKey, local = null, remoteId = "ws-1", remote = StageListDto(), live = untouched)
        )
    }

    @Test
    fun `a one-stage local draft keeps the server's other stages, with the live stage laid over it`() {
        // What the first debounced save writes on a workshop this device had never opened.
        val local = WorkshopDraft(
            workshopId = "ws-1",
            remoteId = "ws-1",
            stages = mapOf(stageKey to StageDraft(stageId = stageKey, values = mapOf("objectives" to JsonPrimitive("old")))),
        )
        val draft = dwStagePreviewDraft(schema, "ws-1", stageKey, local = local, remoteId = "ws-1", remote = serverCopy, live = typed("new"))
        assertEquals("new", (draft!!.stages[stageKey]?.values?.get("objectives") as JsonPrimitive).content)
        // The device holds no work for setup, so it is the server's — the report screen's merge.
        assertEquals(
            "Dabu print",
            (draft.stages["WORKSHOP_SETUP"]?.values?.get("craftName") as JsonPrimitive).content,
        )
    }

    @Test
    fun `a stage this device holds work for stays the device's`() {
        val local = WorkshopDraft(
            workshopId = "ws-1",
            remoteId = "ws-1",
            stages = mapOf(
                "WORKSHOP_SETUP" to StageDraft(stageId = "WORKSHOP_SETUP", values = mapOf("craftName" to JsonPrimitive("Ajrakh"))),
            ),
        )
        val draft = dwStagePreviewDraft(schema, "ws-1", stageKey, local = local, remoteId = "ws-1", remote = serverCopy, live = untouched)
        assertEquals("Ajrakh", (draft!!.stages["WORKSHOP_SETUP"]?.values?.get("craftName") as JsonPrimitive).content)
    }

    @Test
    fun `with no server copy the local draft is used as it is`() {
        val local = WorkshopDraft(
            workshopId = "ws-1",
            stages = mapOf(stageKey to StageDraft(stageId = stageKey, values = mapOf("objectives" to JsonPrimitive("old")))),
        )
        val draft = dwStagePreviewDraft(schema, "ws-1", stageKey, local = local, remoteId = "ws-1", remote = null, live = untouched)
        assertEquals("old", (draft!!.stages[stageKey]?.values?.get("objectives") as JsonPrimitive).content)
        assertFalse("WORKSHOP_SETUP" in draft.stages)
    }

    // ── The server read ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `offline with no earlier read is Offline, and nothing is fetched`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var calls = 0
        val read = DwPreviewServerCopy(scope).read(online = false) { calls++; serverCopy }
        assertEquals(DwServerRead.Offline, read)
        assertEquals(0, calls)
        scope.cancel()
    }

    @Test
    fun `a failed read is Failed, is not remembered, and the next read asks again`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val copy = DwPreviewServerCopy(scope)
        var calls = 0
        val first = copy.read(online = true) { calls++; throw IllegalStateException("500") }
        assertEquals(DwServerRead.Failed, first)
        val second = copy.read(online = true) { calls++; serverCopy }
        assertEquals(DwServerRead.Read(serverCopy), second)
        // Read once, then served from memory — even offline.
        assertEquals(DwServerRead.Read(serverCopy), copy.read(online = false) { calls++; serverCopy })
        assertEquals(2, calls)
        scope.cancel()
    }

    @Test
    fun `a build cancelled by the next keystroke does not cancel the read it was waiting for`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val copy = DwPreviewServerCopy(scope)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val fetch: suspend () -> StageListDto = { calls++; started.complete(Unit); gate.await(); serverCopy }
        val firstBuild = launch(Dispatchers.Default) { copy.read(online = true, fetch = fetch) }
        started.await()
        firstBuild.cancel()
        firstBuild.join()
        gate.complete(Unit)
        assertEquals(DwServerRead.Read(serverCopy), copy.read(online = true, fetch = fetch))
        assertEquals(1, calls)
        scope.cancel()
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found from ${File(".").absolutePath}")
    }
}
