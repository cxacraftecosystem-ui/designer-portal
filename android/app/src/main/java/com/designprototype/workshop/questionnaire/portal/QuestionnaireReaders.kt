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

/**
 * C10 on the designer portal: a session's read view shows the record completeness the server's readers
 * compute for it (`GET /questionnaire/interviews/{id}`, whose `completeness` counts the session's live
 * takes and typed answers). The same readers feed task progress, stage counts and the annexure, so
 * what this meter shows is what those see. Offline, or before the server answers, it draws nothing.
 */
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

/** The meter alone, read-only: the read view is not where a session's fields are filled. */
@Composable
fun SessionCompleteness(block: CompletenessDto) {
    CompletenessMeter(block = block, canUpdate = false, onUpdate = {}, modifier = Modifier.testTag("q-session-readers"))
}
