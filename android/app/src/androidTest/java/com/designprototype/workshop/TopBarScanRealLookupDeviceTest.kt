package com.designprototype.workshop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.ArtisanCardCode
import com.designprototype.workshop.data.ArtisanCardWireDto
import com.designprototype.workshop.data.ArtisanDto
import com.designprototype.workshop.data.DwReferenceList
import com.designprototype.workshop.data.DwReferenceStore
import com.designprototype.workshop.data.OfflineOutbox
import com.designprototype.workshop.data.PendingEntry
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopRepositoryApi
import com.designprototype.workshop.data.dispatchScan
import com.designprototype.workshop.ui.AppSearchHeader
import com.designprototype.workshop.ui.DesignWorkshopTheme
import com.designprototype.workshop.ui.TopBarScanSheet
import com.designprototype.workshop.ui.designerScanServices
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

/**
 * The top-bar scanner on a device with the REAL header, the REAL designer services and the REAL
 * offline lookups (REQUIREMENTS_2026-10-20 §6.1): the artisan register this phone cached, and the
 * cards already held in its outbox or in a design-workshop draft's stage. The API is pointed at a closed loopback port, so every network
 * call fails as "no signal" and nothing is signed in or sent.
 */
@RunWith(AndroidJUnit4::class)
class TopBarScanRealLookupDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val card = (ArtisanCardCode.parse(CARD) as ArtisanCardCode.Result.Ok).card

    private fun offlineRepository(): WorkshopRepository {
        val api = Retrofit.Builder()
            .baseUrl("http://127.0.0.1:9/")
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WorkshopRepositoryApi::class.java)
        return WorkshopRepository(api, TokenStore(context))
    }

    private fun cacheRegister(vararg artisans: ArtisanDto) = runBlocking {
        DwReferenceStore.store(
            context,
            DwReferenceStore.cacheKey(REGISTER_ARTISAN, "ALL", "", ""),
            DwReferenceList(model = REGISTER_ARTISAN, items = artisans.map(::artisanToOption)),
        )
    }

    private fun scanTyped(text: String) {
        val services = designerScanServices(context, offlineRepository())
        rule.setContent {
            DesignWorkshopTheme(darkTheme = false) {
                var open by remember { mutableStateOf(false) }
                AppSearchHeader(appName = "Design Workshop", logo = {}, onOpenSearch = {}, onScan = { open = true })
                if (open) {
                    TopBarScanSheet(
                        dispatch = { raw -> dispatchScan(raw, services) },
                        onAction = { null },
                        onDismiss = { open = false },
                        scanner = { _, _, _ -> },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Scan a code").performClick()
        rule.onNodeWithTag("scan-typed").performTextInput(text)
        rule.onNodeWithTag("scan-lookup").performClick()
    }

    @Test
    fun anArtisanCardOpensOfflineFromTheCachedRegister() {
        cacheRegister(
            ArtisanDto(
                id = "cmartisandevice01",
                name = "Meena Devi",
                place = "Bagru",
                status = "APPROVED",
                card = ArtisanCardWireDto(card.code, CARD, "token"),
            )
        )
        scanTyped(CARD)
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Artisan: Meena Devi")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("scan-action").assertTextEquals("Open")
    }

    @Test
    fun aCardWaitingToSyncIsNamedOfflineRatherThanUnknown() {
        cacheRegister(ArtisanDto(id = "cmartisandevice02", name = "Sita Devi", place = "Sanganer", status = "APPROVED"))
        val entry = PendingEntry(
            id = "device-test-${UUID.randomUUID()}",
            type = "product",
            payloadJson = """{"artisanId":"${ArtisanCardCode.pendingValue(card)}","productName":"Indigo saree"}""",
            label = "Indigo saree",
            createdAt = Instant.now().toString(),
        )
        runBlocking { OfflineOutbox.enqueue(context, entry) }
        try {
            scanTyped(CARD)
            val said = ArtisanCardCode.pendingLabel(ArtisanCardCode.pendingValue(card))
            rule.waitUntil(10_000) { rule.onAllNodes(hasText(said)).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            runBlocking { OfflineOutbox.discard(context, entry.id) }
        }
    }

    @Test
    fun aCardScannedIntoAWorkshopStageOfflineIsNamedAsWaitingToSync() {
        cacheRegister(ArtisanDto(id = "cmartisandevice03", name = "Lali Bai", place = "Kaladera", status = "APPROVED"))
        val workshopId = "device-test-${UUID.randomUUID()}"
        val participants = JsonArray(listOf(JsonObject(mapOf("artisanRef" to JsonPrimitive(ArtisanCardCode.pendingValue(card))))))
        val draft = WorkshopDraft(
            workshopId = workshopId,
            title = "Device test workshop",
            stages = mapOf("participants" to StageDraft(stageId = "participants", values = mapOf("participants" to participants))),
        )
        runBlocking { WorkshopDraftStore.save(context, draft) }
        try {
            scanTyped(CARD)
            val said = ArtisanCardCode.pendingLabel(ArtisanCardCode.pendingValue(card))
            rule.waitUntil(10_000) { rule.onAllNodes(hasText(said)).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            runBlocking { WorkshopDraftStore.delete(context, workshopId) }?.deleteRecursively()
        }
    }

    private companion object {
        /** A well-formed Designer Repository card (the shared scan fixture's). */
        const val CARD = "D-7K3QM-9XA2F-HQ"
    }
}
