package com.designprototype.workshop.questionnaire.portal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.CompletenessDto
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.ui.CompletenessMeter

@Composable
fun DesignerSessionReaders(sessionId: String, refresh: Int) {
    val context = LocalContext.current
    val block by produceState<CompletenessDto?>(null, sessionId, refresh) {
        value = runCatching {
            ApiClient.create(TokenStore(context.applicationContext)).interview(sessionId).completeness
        }.getOrNull()
    }
    block?.let { SessionCompleteness(it) }
}

@Composable
fun SessionCompleteness(block: CompletenessDto) {
    CompletenessMeter(block = block, canUpdate = false, onUpdate = {}, modifier = Modifier.testTag("q-session-readers"))
}
