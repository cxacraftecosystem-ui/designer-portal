package com.designprototype.workshop.questionnaire.portal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.ArtisanDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.questionnaire.data.QArtisanOption
import com.designprototype.workshop.questionnaire.data.QuestionnaireCopy
import com.designprototype.workshop.questionnaire.data.QuestionnaireV2
import com.designprototype.workshop.questionnaire.ui.ArtisanQuestionnaireRecord
import com.designprototype.workshop.questionnaire.ui.QuestionnaireHome
import com.designprototype.workshop.questionnaire.ui.QuestionnaireTab
import com.designprototype.workshop.questionnaire.ui.QuestionnaireV2Nav
import com.designprototype.workshop.questionnaire.ui.openAppDownloadPage
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.LocalMayDelete
import com.designprototype.workshop.questionnaire.ui.LocalSessionReaders
import com.designprototype.workshop.ui.field

/**
 * The height the questionnaire home is given inside the app's scrolling page: the window less the
 * header and the island, so its own lists scroll and its button stays in view.
 */
@Composable
fun questionnaireViewportHeight(): androidx.compose.ui.unit.Dp =
    (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 190.dp).coerceAtLeast(420.dp)

/** Whether the server has sessions switched on (QUESTIONNAIRE_V2): a form then starts a session. */
@Composable
fun questionnaireSessionsOn(): Boolean {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val config by v2.config.collectAsState()
    return config?.v2Enabled == true
}

/** The designer portal's artisans, as the shared screens read them. */
fun ArtisanDto.asQuestionnaireArtisan(): QArtisanOption = QArtisanOption(id = id, name = name, place = place, craft = craft?.name)

/**
 * The questionnaire home as this app hosts it (UNIFY §4.2 Android): Sessions | Forms, with the forms
 * library ([formsTab]) on the second tab. It owns its whole viewport (its lists scroll themselves and
 * its button floats), so the app draws it outside the page that scrolls, with its own back control.
 *
 * Who may do what follows the server's rules: a content admin deletes and restores sessions and
 * changes an answer someone else wrote (U9); a reviewer approves or returns a session (B27); Professor
 * and above makes a take the main one.
 */
@Composable
fun DesignerQuestionnaireHome(
    artisans: List<ArtisanDto>,
    user: UserDto?,
    initialTab: QuestionnaireTab,
    prefillArtisanIds: List<String> = emptyList(),
    /** The forms library; given "start a session on this form" when sessions are switched on, else null. */
    formsTab: @Composable (onStartSession: ((questionnaireId: String) -> Unit)?) -> Unit,
    onNewForm: () -> Unit,
    onError: (String) -> Unit,
    /** Null when the app's own header draws the way back (the home sits in the scrolling page). */
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val config by v2.config.collectAsState()
    LaunchedEffect(Unit) { runCatching { v2.refreshConfig() } }
    val contentAdmin = FieldPermissions.isContentAdmin(user)
    // Sessions are the server's switch (QUESTIONNAIRE_V2, UNIFY U12): until it says on, the forms and
    // their sittings are the whole questionnaire, exactly as before.
    if (config?.v2Enabled != true) {
        androidx.compose.foundation.layout.Column(modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            formsTab(null)
        }
        return
    }
    // "Continue with…" or "Start session" from an artisan's record arrives through the nav holder.
    val pending by QuestionnaireV2Nav.pending.collectAsState()
    val prefill = remember(pending, prefillArtisanIds) { prefillArtisanIds.ifEmpty { listOfNotNull(pending) } }
    LaunchedEffect(pending) { if (pending != null) QuestionnaireV2Nav.pending.value = null }
    CompositionLocalProvider(
        LocalMayDelete provides contentAdmin,
        LocalSessionReaders provides { id, refresh -> DesignerSessionReaders(id, refresh) }
    ) {
        QuestionnaireHome(
            artisans = remember(artisans) { artisans.map { it.asQuestionnaireArtisan() } },
            meId = user?.id,
            meName = user?.name,
            prefillArtisanIds = prefill,
            canMakePrimary = FieldPermissions.rank(user?.role) >= FieldPermissions.RANK_PROFESSOR,
            isAdmin = contentAdmin,
            canReview = user?.let(FieldPermissions::canReview) == true,
            formsTab = formsTab,
            onNewForm = onNewForm,
            onUpdateApp = { openAppDownloadPage(context) },
            onError = onError,
            initialTab = initialTab,
            onBack = onBack,
            modifier = modifier
        )
    }
}

/**
 * Artisan → Questionnaire (B26, C6): pick an artisan, then their record across every form and every
 * session, newest first, with the timeline, the answers by question and the CSV. It replaces the
 * consolidated view of the retired global form. "Continue with…" and "Start session" open the
 * questionnaire home on the new session.
 */
@Composable
fun DesignerArtisanQuestionnaireRecords(
    artisans: List<ArtisanDto>,
    onStartSession: (artisanId: String) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    var search by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<ArtisanDto?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val chosen = picked
        if (chosen == null) {
            Text(QuestionnaireCopy.s("start.artisan"), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                label = { Text(QuestionnaireCopy.s("sessions.search")) }, modifier = Modifier.fillMaxWidth()
            )
            artisans.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) || it.place.contains(search, ignoreCase = true) }
                .take(200).forEach { a ->
                    Column(Modifier.fillMaxWidth().clickable { picked = a }.padding(vertical = 8.dp)) {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge)
                        Text(a.place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                    }
                    HorizontalDivider(color = MaterialTheme.field.hairline)
                }
        } else {
            TextButton(onClick = { picked = null }) { Text("‹ " + QuestionnaireCopy.s("start.artisan")) }
            Text(chosen.name, style = MaterialTheme.typography.titleLarge)
            ArtisanQuestionnaireRecord(
                v2 = v2, artisanId = chosen.id, artisanName = chosen.name,
                onContinueWith = { id -> QuestionnaireV2Nav.pending.value = id; onStartSession(id) },
                onStartSession = { id -> QuestionnaireV2Nav.pending.value = id; onStartSession(id) },
                onOpenSession = { id -> QuestionnaireV2Nav.openServerSession.value = id; onOpenSession(id) },
                onMessage = onMessage
            )
        }
    }
}
