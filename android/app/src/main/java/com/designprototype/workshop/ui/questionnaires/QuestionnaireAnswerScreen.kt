package com.designprototype.workshop.ui.questionnaires

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import com.designprototype.workshop.data.CustomAnswerInputBody
import com.designprototype.workshop.data.CustomQuestionDto
import com.designprototype.workshop.data.CustomQuestionnaireDto
import com.designprototype.workshop.data.CustomSectionDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.cachedQuestionnaireNotice
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * ONE SITTING, ONE SECTION AT A TIME. This is the screen a designer uses standing in a room.
 *
 * Everything about its shape follows from that. The whole form's answers are held in ONE map for the
 * life of the screen and only a section at a time is drawn — so moving between sections is instant
 * and cannot lose typing, while the composition stays the size of one section rather than of a
 * forty-question instrument. Saving is per section, because that is the unit of a conversation: you
 * finish talking about tools, you save, you move on, and if the phone dies on the way to the next
 * village the tools section is already recorded.
 *
 * THE FORM IS LOADED WITHOUT RETIRED QUESTIONS, and that is not an optimisation. A retired question
 * keeps the answers it already has and must never collect new ones — the server refuses the save with
 * a 422 naming the question — so offering one here would be offering a box whose contents are thrown
 * away at the end of the section. See the KDoc on `CustomQuestionnaireDto`.
 *
 * WHY THERE IS NO OFFLINE DRAFT. The design-workshop stages next door save to the device first, and
 * this deliberately does not: whether a question may still be answered is a fact only the server
 * holds (it can be retired between opening this screen and saving), so a queued batch would have to
 * be either refused later — losing the sitting the designer believed was recorded — or re-attached to
 * whatever wording replaced it, which fabricates evidence. Being told there is no signal, in the
 * room, while the answers are still on screen to be re-sent, is the honest failure.
 *
 * ── BUT THE FORM NOW OPENS WITH NO SIGNAL, AND THAT WAS A SEPARATE BUG ───────────────────────────
 *
 * The paragraph above is about WRITING, and it was silently doing duty for the read as well. This
 * screen used to hit the network to load the form, full stop — so a designer with no bars was shown
 * `loadError` where a 24-section instrument should be, and could not read the question they were
 * about to ask out loud, nor review what a colleague recorded that morning. None of that writes
 * anything. It is served from [DwQuestionnaireFormCache] now, the screen SAYS it is a copy and when
 * it was taken, and saving is refused up front rather than by letting the designer fill in a section
 * and then failing — see [cachedQuestionnaireNotice] and `offlineRefusal` below.
 */
@Composable
fun QuestionnaireAnswerScreen(
    repository: WorkshopRepository,
    questionnaireId: String,
    entryId: String,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The application context, so a cache read/write cannot outlive an Activity it holds.
    val appContext = LocalContext.current.applicationContext

    var form by remember(questionnaireId) { mutableStateOf<CustomQuestionnaireDto?>(null) }
    var loading by remember(questionnaireId) { mutableStateOf(true) }
    var loadError by remember(questionnaireId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var sectionIndex by remember(questionnaireId, entryId) { mutableIntStateOf(0) }
    var showEntryEditor by remember { mutableStateOf(false) }

    /*
     * The typing, for the WHOLE questionnaire, not for the section on screen.
     *
     * That is the difference between a section switch that is free and one that either loses work or
     * has to save first. A designer flicking back to section 2 to correct an answer they gave three
     * minutes ago must find it exactly as they left it, saved or not.
     */
    val answers = remember(questionnaireId, entryId) { mutableStateMapOf<String, String>() }
    val notes = remember(questionnaireId, entryId) { mutableStateMapOf<String, String>() }

    /** What the server currently holds, so "changed" is a fact rather than a guess. */
    var baseline by remember(questionnaireId, entryId) {
        mutableStateOf<Map<String, Pair<String, String>>>(emptyMap())
    }

    /**
     * Questions whose answer this account may not overwrite.
     *
     * `save_answers` refuses a batch outright when it contains ONE answer somebody else recorded —
     * the whole section fails, not the one question. Marking them here, before the designer types
     * into them, is the difference between "that answer is Priya's" and forty answers refused at the
     * end of a section with a message about a question the designer cannot pick out of the wall.
     */
    var locked by remember(questionnaireId, entryId) { mutableStateOf<Set<String>>(emptySet()) }

    val me = remember { signedInUserId(repository) }
    val admin = remember { isAdminAccount(repository) }

    /**
     * Set when the form on screen came off this device's disk rather than off the network.
     *
     * The sentence is [cachedQuestionnaireNotice]'s, and it is drawn ABOVE the questions rather than
     * beside the save button — a designer has to know they cannot record before they start asking,
     * not after forty answers are typed.
     */
    var cachedNotice by remember(questionnaireId) { mutableStateOf<String?>(null) }

    LaunchedEffect(questionnaireId, entryId) {
        loading = true
        loadError = null
        cachedNotice = null
        runCatching {
            // includeRetired = false. See the note in the KDoc above; this is the one caller that
            // must never be handed a retired question.
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
                loadError = "That sitting is no longer on this questionnaire. Go back and open it again."
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
                locked = if (admin) {
                    emptySet()
                } else {
                    entry.answers
                        .filter { !it.answerText.isNullOrBlank() }
                        .filter { !it.answeredById.isNullOrBlank() && it.answeredById != me }
                        .map { it.questionId }
                        .toSet()
                }
            }
        }.onFailure { error ->
            if (error !is CancellationException) {
                loadError = error.apiErrorMessage("This questionnaire could not be opened.")
            }
        }
        loading = false
    }

    val loaded = form
    val entry = loaded?.entries?.firstOrNull { it.id == entryId }
    val sections = remember(loaded) { loaded?.sections?.filter { it.isActive }.orEmpty() }

    /** Is this question's box different from what the server holds? */
    fun dirty(question: CustomQuestionDto): Boolean {
        val base = baseline[question.id] ?: ("" to "")
        return answers[question.id].orEmpty().trim() != base.first.trim() ||
            notes[question.id].orEmpty().trim() != base.second.trim()
    }

    /**
     * The batch for [questions].
     *
     * NOT "every question on the form", and not even every question in the section. A row is written
     * for each answer sent, blank ones included, and the edit-after-answers rule counts a blank answer
     * as no answer — so posting the whole section would fill the sitting with rows that assert
     * nothing, and re-stamp `answeredById` across answers this saver never gave. Only genuine changes
     * go, and a change to blank goes only when there is an existing row to clear.
     */
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
        // REFUSED BEFORE THE BATCH IS EVEN BUILT, and refused in words rather than by a network
        // error. The form on screen is a copy this device kept; whether these questions may still be
        // answered is a fact only the server holds. Letting the request go would produce an OkHttp
        // sentence that reads as a fault in the app, and would leave the designer wondering whether
        // some of it got through.
        if (cachedNotice != null) {
            onError(
                "These answers cannot be saved: the questionnaire on screen is the copy this phone " +
                    "kept, and this app will not record an answer against wording it cannot confirm " +
                    "is still in use. Your typing is still here — save when you have a signal."
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
                    // Rebuilt from the RESPONSE and not from what was sent. The response carries every
                    // answer now on the sitting, so a save that landed while somebody else was writing
                    // to the same sitting leaves this screen holding what is actually stored rather
                    // than what this phone believes it stored.
                    val next = mutableMapOf<String, Pair<String, String>>()
                    result.answers.forEach { answer ->
                        next[answer.questionId] = answer.answerText.orEmpty() to answer.notes.orEmpty()
                    }
                    baseline = next
                    if (!admin) {
                        locked = result.answers
                            .filter { !it.answerText.isNullOrBlank() }
                            .filter { !it.answeredById.isNullOrBlank() && it.answeredById != me }
                            .map { it.questionId }
                            .toSet()
                    }
                    val saved = result.saved
                    onMessage(
                        "Saved ${saved.created + saved.updated} answer(s)." +
                            if (saved.unchanged > 0) " ${saved.unchanged} were already recorded." else ""
                    )
                    if (thenAdvance && sectionIndex < sections.lastIndex) sectionIndex++
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        // Surfaced verbatim. The server's 422s here are written for the person holding
                        // the phone — "these questions have been retired and can no longer be
                        // answered: …", "only the person who recorded this answer, or an admin, can
                        // change it" — and nothing this screen could invent would say as much.
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
        // ABOVE THE QUESTIONS, not beside the save button. A designer who is going to be refused a
        // save has to know it before they start asking, not after a section is typed.
        cachedNotice?.let { notice ->
            Text(
                notice,
                color = MaterialTheme.field.warning,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Opening the questionnaire…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }

            loadError != null -> Text(
                loadError.orEmpty(),
                color = MaterialTheme.field.warning,
                fontSize = 13.sp
            )

            loaded == null || entry == null -> EmptyNote("This sitting could not be opened.")

            sections.isEmpty() || sections.all { it.questions.none { q -> q.isActive } } -> {
                Text(
                    loaded.title.ifBlank { "Untitled questionnaire" },
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp
                )
                EmptyNote(
                    "This questionnaire has no questions that can be answered. Add them from the " +
                        "questionnaire screen, or upload the filled-in .xlsx pro-forma on a computer."
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
                            fontSize = 17.sp,
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
                                onAnswer = { answers[question.id] = it },
                                onNote = { notes[question.id] = it },
                            )
                            HorizontalDivider(color = MaterialTheme.field.hairline)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { save(questions, thenAdvance = false) },
                        enabled = !saving,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (saving) "Saving…" else "Save this section") }
                    if (index < sections.lastIndex) {
                        OutlinedButton(
                            onClick = { save(questions, thenAdvance = true) },
                            enabled = !saving,
                            modifier = Modifier.weight(1f)
                        ) { Text("Save & next") }
                    }
                }

                // The whole-form save exists for the end of a sitting, where a designer has walked
                // back through three sections correcting answers and would otherwise have to remember
                // which ones they touched. It sends only what changed, exactly like the per-section
                // button — the two are the same operation over a different set of questions.
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
                        // Patched into the loaded form rather than re-fetched: a full reload would
                        // re-seed the answer boxes from the server and throw away every unsaved
                        // answer on screen, which is the one thing renaming a sitting must not do.
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
                fontSize = 20.sp
            )
            Text(
                listOfNotNull(
                    entryTitle.takeIf { it.isNotBlank() },
                    respondentName?.takeIf { it.isNotBlank() && it != entryTitle }
                ).joinToString(" · ").ifBlank { "Answers" },
                color = MaterialTheme.field.body,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else answered.toFloat() / total },
                modifier = Modifier.fillMaxWidth()
            )
            Text("$answered of $total answered", color = MaterialTheme.field.muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (source == "UPLOAD") {
                    QChip(
                        "from the spreadsheet",
                        MaterialTheme.field.surface200,
                        MaterialTheme.field.muted
                    )
                }
                // Stated in a colour and a number rather than left to the designer to remember. The
                // one failure this screen can still produce is walking out of the room with a section
                // typed and not sent, and a count is the only thing on screen that can say so.
                if (unsaved > 0) {
                    QChip(
                        "$unsaved not saved yet",
                        MaterialTheme.field.warningContainer,
                        MaterialTheme.field.onWarningContainer
                    )
                }
            }
            TextButton(onClick = onEditSitting, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Who is answering?", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Previous / next, plus a jump list once there are enough sections for stepping to be tedious.
 *
 * The jump list is a picker and not a row of chips because a questionnaire built in a spreadsheet
 * routinely has eight or twelve sections with long titles, and twelve chips on a phone wrap into a
 * block that pushes the first question off the screen.
 */
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
                fontSize = 12.sp,
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
                        hint = "${section.questions.count { it.isActive }} question(s)"
                    )
                },
                selectedValue = index.toString(),
                includeNone = false,
                onSelect = { picked -> picked.toIntOrNull()?.let(onGo) }
            )
        }
    }
}

/**
 * One question and its answer.
 *
 * The NOTE box is always drawn rather than hidden behind a toggle. It is the field an interviewer
 * uses for "she hesitated here" and "answered for her husband as well", which is context that stops
 * being recorded at all the moment it costs a tap to reach.
 */
@Composable
private fun AnswerField(
    question: CustomQuestionDto,
    answer: String,
    note: String,
    locked: Boolean,
    dirty: Boolean,
    onAnswer: (String) -> Unit,
    onNote: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            question.prompt + if (question.isRequired) " *" else "",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        question.helpText?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp)
        }
        OutlinedTextField(
            value = answer,
            onValueChange = onAnswer,
            enabled = !locked,
            label = { Text("Answer") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = note,
            onValueChange = onNote,
            enabled = !locked,
            label = { Text("Note (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        when {
            locked -> Text(
                "Someone else recorded this answer. Only they, or an admin, can change it — and " +
                    "sending it anyway would make the server refuse this whole section.",
                color = MaterialTheme.field.warning,
                fontSize = 11.sp
            )
            dirty -> Text("Not saved yet", color = MaterialTheme.field.warning, fontSize = 11.sp)
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
                    "A sitting is one filled-in copy of this questionnaire. Naming the person " +
                        "answering is what tells two sittings apart in the download.",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
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
                OutlinedTextField(
                    value = entryNotes,
                    onValueChange = { entryNotes = it },
                    label = { Text("Notes") },
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
