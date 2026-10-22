package com.designprototype.workshop.questionnaire.portal

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.designprototype.workshop.data.CompletenessDto
import com.designprototype.workshop.data.CompletenessMissingDto
import com.designprototype.workshop.questionnaire.QFeature
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C10: the read view shows the readers' completeness for a session, read-only. */
@RunWith(AndroidJUnit4::class)
class SessionReadersUiTest {
    @get:Rule val compose = createComposeRule()

    @QFeature("C10")
    @Test fun aSessionsReadViewShowsWhatTheReadersStillMiss() {
        val block = CompletenessDto(
            rulesVersion = 3, filled = 5, total = 6, percent = 83,
            missing = listOf(CompletenessMissingDto("recording", "Recording", "recording", "recommended"))
        )
        compose.setContent { MaterialTheme { SessionCompleteness(block) } }
        compose.onNodeWithTag("q-session-readers").assertIsDisplayed()
        compose.onNodeWithText("1 field left: recording").assertIsDisplayed()
        compose.onNodeWithText("Recording").assertIsDisplayed()
    }
}
