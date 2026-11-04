package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.plural
import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import com.designprototype.workshop.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.FormDraftRestoredNote
import com.designprototype.workshop.ui.rememberFormDraft
import com.designprototype.workshop.data.CustomAnswerInputBody
import com.designprototype.workshop.data.CustomQuestionDto
import com.designprototype.workshop.data.CustomQuestionnaireDto
import com.designprototype.workshop.data.CustomSectionDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.cachedQuestionnaireNotice
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.requiredMarked
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.RecordProseField
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun QuestionnaireAnswerScreen(
    repository: WorkshopRepository,
    questionnaireId: String,
    entryId: String,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext

    var form by remember(questionnaireId) { mutableStateOf<CustomQuestionnaireDto?>(null) }
    var loading by remember(questionnaireId) { mutableStateOf(true) }
    var loadError by remember(questionnaireId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var sectionIndex by remember(questionnaireId, entryId) { mutableIntStateOf(0) }
    var showEntryEditor by remember { mutableStateOf(false) }

    val answers = remember(questionnaireId, entryId) { mutableStateMapOf<String, String>() }
    val notes = remember(questionnaireId, entryId) { mutableStateMapOf<String, String>() }

    var baseline by remember(questionnaireId, entryId) {
        mutableStateOf<Map<String, Pair<String, String>>>(emptyMap())
    }

    var locked by remember(questionnaireId, entryId) { mutableStateOf<Set<String>>(emptySet()) }

    var problems by remember(questionnaireId, entryId) { mutableStateOf<Map<String, String>>(emptyMap()) }

    val me = remember { signedInUserId(repository) }
    val admin = remember { isAdminAccount(repository) }

    var cachedNotice by remember(questionnaireId) { mutableStateOf<String?>(null) }

    LaunchedEffect(questionnaireId, entryId) {
        loading = true
        loadError = null
        cachedNotice = null
        runCatching {
            repository.customQuestionnaireCached(
                context = appContext,
                id = questionnaireId,
                includeRetired = false,
            )
        }.map { read ->
            if (read.fromCache) {
                cachedNotice = cachedQuestionnaireNotice(read.cachedAt, read.form.version)
            }
            read.form
        }.onSuccess { loaded ->
            form = loaded
            val entry = loaded.entries.firstOrNull { it.id == entryId }
            if (entry == null) {
                loadError = "This sitting no longer exists. Go back and reopen."
            } else {
                answers.clear()
                notes.clear()
                val seeded = mutableMapOf<String, Pair<String, String>>()
                entry.answers.forEach { answer ->
                    val text = answer.answerText.orEmpty()
                    val note = answer.notes.orEmpty()
                    answers[answer.questionId] = text
                    notes[answer.questionId] = note
                    seeded[answer.questionId] = text to note
                }
                baseline = seeded
                locked = if (admin) emptySet() else othersAnswers(entry.answers, me)
                problems = emptyMap()
            }
        }.onFailure { error ->
            if (error !is CancellationException) {
                loadError = error.apiErrorMessage("This questionnaire could not be opened.")
            }
        }
        loading = false
    }

    LaunchedEffect(questionnaireId, entryId, cachedNotice != null) {
        while (cachedNotice != null) {
            delay(CACHED_COPY_RECHECK_MS)
            if (!repository.isOnline(appContext)) continue
            val read = runCatching {
                repository.customQuestionnaireCached(context = appContext, id = questionnaireId, includeRetired = false)
            }.getOrNull() ?: continue
            if (read.fromCache) continue
            val live = read.form.entries.firstOrNull { it.id == entryId } ?: continue
            form = read.form
            baseline = live.answers.associate { it.questionId to (it.answerText.orEmpty() to it.notes.orEmpty()) }
            live.answers.forEach { answer ->
                if (answer.questionId !in answers) answers[answer.questionId] = answer.answerText.orEmpty()
                if (answer.questionId !in notes) notes[answer.questionId] = answer.notes.orEmpty()
            }
            locked = if (admin) emptySet() else othersAnswers(live.answers, me)
            cachedNotice = null
        }
    }

    val loaded = form
    val entry = loaded?.entries?.firstOrNull { it.id == entryId }
    val sections = remember(loaded) { loaded?.sections?.filter { it.isActive }.orEmpty() }

    val draft = rememberFormDraft(
        key = if (!loading && entry != null) "qanswers:$questionnaireId:$entryId" else null,
        snapshot = {
            answers.entries.associate { "a:${it.key}" to it.value } +
                notes.entries.associate { "n:${it.key}" to it.value }
        },
        restore = { v ->
            v.forEach { (k, value) ->
                when {
                    k.startsWith("a:") -> answers[k.removePrefix("a:")] = value
                    k.startsWith("n:") -> notes[k.removePrefix("n:")] = value
                }
            }
        },
    )

    fun dirty(question: CustomQuestionDto): Boolean {
        val base = baseline[question.id] ?: ("" to "")
        return answers[question.id].orEmpty().trim() != base.first.trim() ||
            notes[question.id].orEmpty().trim() != base.second.trim()
    }

    fun batchFor(questions: List<CustomQuestionDto>): List<CustomAnswerInputBody> =
        questions.mapNotNull { question ->
            if (question.id in locked) return@mapNotNull null
            val text = answers[question.id].orEmpty().trim()
            val note = notes[question.id].orEmpty().trim()
            val base = baseline[question.id]
            if (base != null && text == base.first.trim() && note == base.second.trim()) return@mapNotNull null
            if (base == null && text.isEmpty() && note.isEmpty()) return@mapNotNull null
            CustomAnswerInputBody(
                questionId = question.id,
                answerText = text.takeIf { it.isNotEmpty() },
                notes = note.takeIf { it.isNotEmpty() },
            )
        }

    fun save(questions: List<CustomQuestionDto>, thenAdvance: Boolean) {
        if (saving) return
        if (cachedNotice != null) {
            onError(
                "Can't save offline — this is a saved copy. Your answers are still here; save when online."
            )
            return
        }
        val batch = batchFor(questions)
        if (batch.isEmpty()) {
            onMessage("Nothing new to save here.")
            if (thenAdvance && sectionIndex < sections.lastIndex) sectionIndex++
            return
        }
        saving = true
        scope.launch {
            runCatching { repository.saveCustomAnswers(questionnaireId, entryId, batch) }
                .onSuccess { result ->
                    val outcome = answerSaveOutcome(
                        result = result,
                        sent = batch.map { it.questionId },
                        baseline = baseline,
                        locked = locked,
                        previousProblems = problems,
                        me = me,
                        admin = admin,
                    )
                    baseline = outcome.baseline
                    locked = outcome.locked
                    problems = outcome.problems
                    if (outcome.problems.isEmpty()) onMessage(outcome.message) else onError(outcome.message)
                    if (thenAdvance && sectionIndex < sections.lastIndex) sectionIndex++
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        onError(error.apiErrorMessage("Those answers could not be saved."))
                    }
                }
            saving = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FormDraftRestoredNote(draft)
        cachedNotice?.let { notice ->
            Text(
                notice,
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp,
            )
        }
        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Opening the questionnaire…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }

            loadError != null -> Text(
                loadError.orEmpty(),
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodyMedium
            )

            loaded == null || entry == null -> EmptyNote("This sitting could not be opened.")

            sections.isEmpty() || sections.all { it.questions.none { q -> q.isActive } } -> {
                Text(
                    loaded.title.ifBlank { "Untitled questionnaire" },
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge
                )
                EmptyNote(
                    "No questions yet. Add them from the questionnaire screen."
                )
            }

            else -> {
                val total = activeQuestionCount(loaded)
                val answeredNow = sections.sumOf { section ->
                    section.questions.count { it.isActive && answers[it.id].orEmpty().isNotBlank() }
                }
                val dirtyNow = sections.sumOf { section ->
                    section.questions.count { it.isActive && dirty(it) }
                }
                val index = sectionIndex.coerceIn(0, sections.lastIndex)
                val section = sections[index]
                val questions = section.questions.filter { it.isActive }

                AnswerHeader(
                    questionnaireTitle = loaded.title,
                    entryTitle = entry.title,
                    respondentName = entry.respondentName,
                    source = entry.source,
                    answered = answeredNow,
                    total = total,
                    unsaved = dirtyNow,
                    onEditSitting = { showEntryEditor = true },
                )

                SectionNavigator(
                    sections = sections,
                    index = index,
                    onGo = { sectionIndex = it },
                )

                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            "${section.code} — ${section.title}".trim(' ', '—'),
                            display = true,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (questions.isEmpty()) {
                            EmptyNote("This section has no questions in it.")
                        }
                        questions.forEach { question ->
                            AnswerField(
                                question = question,
                                answer = answers[question.id].orEmpty(),
                                note = notes[question.id].orEmpty(),
                                locked = question.id in locked,
                                dirty = dirty(question),
                                problem = problems[question.id],
                                onAnswer = { answers[question.id] = it },
                                onNote = { notes[question.id] = it },
                            )
                            HorizontalDivider(color = MaterialTheme.field.hairline)
                        }
                    }
                }

                ActionRow {
                    Button(
                        onClick = { save(questions, thenAdvance = false) },
                        enabled = !saving,
                    ) { Text(if (saving) "Saving…" else "Save this section") }
                    if (index < sections.lastIndex) {
                        OutlinedButton(
                            onClick = { save(questions, thenAdvance = true) },
                            enabled = !saving,
                        ) { Text("Save & next") }
                    }
                }

                if (sections.size > 1) {
                    TextButton(
                        onClick = {
                            save(sections.flatMap { s -> s.questions.filter { it.isActive } }, thenAdvance = false)
                        },
                        enabled = !saving && dirtyNow > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (dirtyNow > 0) "Save every unsaved answer ($dirtyNow)" else "Everything is saved",
                            color = if (dirtyNow > 0) MaterialTheme.colorScheme.primary else MaterialTheme.field.muted
                        )
                    }
                }
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    if (showEntryEditor && entry != null) {
        EntryDetailsDialog(
            initialTitle = entry.title,
            initialRespondent = entry.respondentName.orEmpty(),
            initialNotes = entry.notes.orEmpty(),
            busy = saving,
            onDismiss = { showEntryEditor = false },
            onSave = { title, respondent, entryNotes ->
                scope.launch {
                    runCatching {
                        repository.updateCustomEntry(
                            id = questionnaireId,
                            entryId = entryId,
                            title = title,
                            respondentName = respondent,
                            notes = entryNotes,
                        )
                    }.onSuccess { updated ->
                        form = loaded?.copy(
                            entries = loaded.entries.map { row ->
                                if (row.id == entryId) {
                                    row.copy(
                                        title = updated.title,
                                        respondentName = updated.respondentName,
                                        notes = updated.notes,
                                    )
                                } else {
                                    row
                                }
                            }
                        )
                        showEntryEditor = false
                        onMessage("Sitting updated.")
                    }.onFailure { error ->
                        if (error !is CancellationException) {
                            onError(error.apiErrorMessage("The sitting could not be updated."))
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun AnswerHeader(
    questionnaireTitle: String,
    entryTitle: String,
    respondentName: String?,
    source: String?,
    answered: Int,
    total: Int,
    unsaved: Int,
    onEditSitting: () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                questionnaireTitle.ifBlank { "Untitled questionnaire" },
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                listOfNotNull(
                    entryTitle.takeIf { it.isNotBlank() },
                    respondentName?.takeIf { it.isNotBlank() && it != entryTitle }
                ).joinToString(" · ").ifBlank { "Answers" },
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            FieldLinearProgressIndicator(
                progress = { if (total == 0) 0f else answered.toFloat() / total },
                modifier = Modifier.fillMaxWidth()
            )
            Text("$answered of $total answered", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (source == "UPLOAD") {
                    QChip(
                        "from the spreadsheet",
                        MaterialTheme.field.surface200,
                        MaterialTheme.field.muted
                    )
                }
                if (unsaved > 0) {
                    QChip(
                        "$unsaved not saved yet",
                        MaterialTheme.field.warningContainer,
                        MaterialTheme.field.onWarningContainer
                    )
                }
            }
            ActionRow {
                TextButton(onClick = onEditSitting, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Who is answering?", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionNavigator(
    sections: List<CustomSectionDto>,
    index: Int,
    onGo: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = { onGo(index - 1) }, enabled = index > 0) { Text("Previous") }
            Spacer(Modifier.width(4.dp))
            Text(
                "Section ${index + 1} of ${sections.size}",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { onGo(index + 1) }, enabled = index < sections.lastIndex) {
                Text("Next")
            }
        }
        if (sections.size > 2) {
            SearchableSelectField(
                label = "Jump to a section",
                options = sections.mapIndexed { position, section ->
                    SelectOption(
                        value = position.toString(),
                        label = "${position + 1}. ${section.title.ifBlank { section.code }}",
                        hint = plural(section.questions.count { it.isActive }, "question")
                    )
                },
                selectedValue = index.toString(),
                includeNone = false,
                onSelect = { picked -> picked.toIntOrNull()?.let(onGo) }
            )
        }
    }
}

@Composable
private fun AnswerField(
    question: CustomQuestionDto,
    answer: String,
    note: String,
    locked: Boolean,
    dirty: Boolean,
    problem: String? = null,
    onAnswer: (String) -> Unit,
    onNote: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            requiredMarked(question.prompt + if (question.isRequired) " *" else ""),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        question.helpText?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        RecordProseField(
            label = "Answer",
            value = answer,
            onValueChange = onAnswer,
            enabled = !locked,
            dictate = !locked,
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        RecordProseField(
            label = "Note (optional)",
            value = note,
            onValueChange = onNote,
            enabled = !locked,
            dictate = !locked,
            modifier = Modifier.fillMaxWidth()
        )
        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        when {
            locked -> Text(
                "Recorded by someone else. Only they or an admin can change it.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall
            )
            dirty -> Text("Not saved yet", color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EntryDetailsDialog(
    initialTitle: String,
    initialRespondent: String,
    initialNotes: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, respondent: String, notes: String) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var respondent by remember { mutableStateOf(initialRespondent) }
    var entryNotes by remember { mutableStateOf(initialNotes) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("This sitting") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Name who's answering to tell sittings apart.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = respondent,
                    onValueChange = { respondent = it },
                    label = { Text("Who is answering") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Label for this sitting") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                RecordProseField(
                    label = "Notes",
                    value = entryNotes,
                    onValueChange = { entryNotes = it },
                    enabled = !busy,
                    dictate = !busy,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onSave(title, respondent, entryNotes) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}
