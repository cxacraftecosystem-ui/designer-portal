package com.designprototype.workshop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.designprototype.workshop.data.ScanCardAnswer
import com.designprototype.workshop.data.ScanCardKind
import com.designprototype.workshop.data.ScanCodecs
import com.designprototype.workshop.data.ScanPortal
import com.designprototype.workshop.data.ScanRecordAnswer
import com.designprototype.workshop.data.ScanServices
import com.designprototype.workshop.data.dispatchScan
import com.designprototype.workshop.ui.AppSearchHeader
import com.designprototype.workshop.ui.DesignWorkshopTheme
import com.designprototype.workshop.ui.DesignerScanCodecs
import com.designprototype.workshop.ui.TopBarScanSheet
import com.designprototype.workshop.ui.designerScanCardKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a device (REQUIREMENTS_2026-10-20 §6.3): the header's scan button opens the top-bar sheet, and a
 * typed DPW1 code is answered with Open. The record lookup is faked, so nothing is signed in or sent.
 */
@RunWith(AndroidJUnit4::class)
class TopBarScanDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val services = object : ScanServices {
        override val portal = ScanPortal.DESIGNER
        override val codecs: ScanCodecs = DesignerScanCodecs
        override fun cardKind(raw: String): ScanCardKind = designerScanCardKind(raw)
        override suspend fun resolveCard(raw: String): ScanCardAnswer = ScanCardAnswer.Offline
        override suspend fun lookUpRecord(recordType: String, id: String): ScanRecordAnswer = ScanRecordAnswer.Found("Meena Devi")
    }

    @Test
    fun theHeaderScanButtonOpensTheSheetAndATypedCodeOffersOpen() {
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
        rule.onNodeWithTag("top-bar-scan-sheet").assertIsDisplayed()
        rule.onNodeWithTag("scan-typed").performTextInput("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
        rule.onNodeWithTag("scan-lookup").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("scan-action") }
        rule.onNodeWithTag("scan-action").assertTextEquals("Open")
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
