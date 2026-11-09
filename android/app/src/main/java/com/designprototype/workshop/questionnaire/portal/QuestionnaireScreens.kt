package com.designprototype.workshop.questionnaire.portal

import com.designprototype.workshop.ui.LocationFieldsSection
import com.designprototype.workshop.ui.LocationSubject
import com.designprototype.workshop.questionnaire.ui.SessionLocationCard
import com.designprototype.workshop.questionnaire.data.QSessionLocation
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.LocationRequest
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

@Composable
fun questionnaireSessionsOn(): Boolean {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val config by v2.config.collectAsState()
    return config?.v2Enabled == true
}

fun ArtisanDto.asQuestionnaireArtisan(): QArtisanOption = QArtisanOption(id = id, name = name, place = place, craft = craft?.name)

@Composable
fun DesignerQuestionnaireHome(
    artisans: List<ArtisanDto>,
    user: UserDto?,
    initialTab: QuestionnaireTab,
    prefillArtisanIds: List<String> = emptyList(),
    formsTab: @Composable (onStartSession: ((questionnaireId: String) -> Unit)?) -> Unit,
    onNewForm: () -> Unit,
    onError: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onLeaveSession: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val config by v2.config.collectAsState()
    LaunchedEffect(Unit) { runCatching { v2.refreshConfig() } }
    val contentAdmin = FieldPermissions.isContentAdmin(user)
    if (config?.v2Enabled != true) {
        androidx.compose.foundation.layout.Column(modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            formsTab(null)
        }
        return
    }
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
            modifier = modifier,
            onLeaveSession = onLeaveSession
        )
    }
}

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

fun QSessionLocation.request(): LocationRequest = LocationRequest(
    latitude = latitude, longitude = longitude, altitude = altitude, accuracy = accuracy, address = address, placeName = placeName,
    state = state, district = district, village = village, pincode = pincode,
    subjectLatitude = subjectLatitude, subjectLongitude = subjectLongitude, capturedAt = capturedAt
)

fun LocationRequest.session(): QSessionLocation = QSessionLocation(
    latitude = latitude, longitude = longitude, accuracy = accuracy, altitude = altitude, placeName = placeName, address = address,
    state = state, district = district, village = village, pincode = pincode,
    subjectLatitude = subjectLatitude, subjectLongitude = subjectLongitude, capturedAt = capturedAt
)

fun sessionLocationCard(repository: WorkshopRepository): SessionLocationCard = { value, editing, onChange ->
    LocationFieldsSection(repository = repository, value = value?.request(), onChange = { onChange(it?.session()) }, required = false, isEdit = editing, subject = SESSION_LOCATION_SUBJECT)
}

val SESSION_LOCATION_SUBJECT: LocationSubject = LocationSubject(
    heading = "Session location",
    subtitle = "Where this sitting took place. A pin fills state, district and pincode; GPS fills only blanks.",
    label = "the session",
    possessive = "the session's",
    pinLabel = "Session pin"
)
