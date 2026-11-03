package com.designprototype.workshop.ui.designworkshop

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.report.Align
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.IMAGE_MAX_WIDTH_PCT
import com.designprototype.workshop.report.IMAGE_MIN_WIDTH_PCT
import com.designprototype.workshop.report.IMAGE_WIDTH_STEP_PCT
import com.designprototype.workshop.report.Mark
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.fromJson
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.requiredMarked
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private fun spanStyleFor(marks: Set<Mark>): SpanStyle {
    val decorations = buildList {
        if (Mark.UNDERLINE in marks) add(TextDecoration.Underline)
        if (Mark.STRIKETHROUGH in marks) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        fontWeight = if (Mark.BOLD in marks) FontWeight.Bold else null,
        fontStyle = if (Mark.ITALIC in marks) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        fontFamily = if (Mark.CODE in marks) FontFamily.Monospace else null,
    )
}

private fun RichBlock.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        if (span.marks.isEmpty()) {
            append(span.text)
        } else {
            val start = length
            append(span.text)
            addStyle(spanStyleFor(span.marks), start, length)
        }
    }
}

private fun headingStyle(level: Int, type: Typography): TextStyle = when (level) {
    1 -> type.headlineSmall
    2 -> type.titleLarge
    3 -> type.titleMedium
    else -> type.bodyLarge
}

private fun blockTextStyle(block: RichBlock, base: Color, type: Typography): TextStyle {
    val alignment = when (block.align) {
        Align.CENTER -> TextAlign.Center
        Align.RIGHT -> TextAlign.End
        Align.JUSTIFY -> TextAlign.Justify
        else -> TextAlign.Start
    }
    return when (block.kind) {
        BlockKind.HEADING -> headingStyle(block.level, type).copy(
            fontWeight = FontWeight.SemiBold,
            color = base,
            textAlign = alignment,
        )
        BlockKind.QUOTE -> type.bodyLarge.copy(
            fontStyle = FontStyle.Italic,
            color = base.copy(alpha = 0.86f),
            textAlign = alignment,
        )
        else -> type.bodyLarge.copy(color = base, textAlign = alignment)
    }
}

private data class TextEdit(val start: Int, val end: Int, val inserted: String)

private fun diffEdit(old: String, new: String): TextEdit? {
    if (old == new) return null
    var prefix = 0
    val maxPrefix = minOf(old.length, new.length)
    while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
    if (prefix > 0 && prefix < maxPrefix && old[prefix - 1].isHighSurrogate()) prefix--

    var suffix = 0
    val maxSuffix = minOf(old.length - prefix, new.length - prefix)
    while (
        suffix < maxSuffix &&
        old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
    ) suffix++
    if (suffix > 0 && suffix < maxSuffix && old[old.length - suffix].isLowSurrogate()) suffix--

    return TextEdit(
        start = prefix,
        end = old.length - suffix,
        inserted = new.substring(prefix, new.length - suffix),
    )
}

private fun forEditing(doc: RichDoc): RichDoc =
    if (doc.blocks.isEmpty()) emptyDoc() else doc

private fun seedAsList(raw: JsonElement?, kind: BlockKind): RichDoc {
    val wasStoredDocument = raw != null && raw !is JsonPrimitive
    val doc = fromJson(raw)
    if (wasStoredDocument) return doc
    if (doc.blocks.isEmpty()) return RichDoc(listOf(RichBlock(kind = kind, level = 0)))
    return RichDoc(
        doc.blocks.map { block ->
            if (block.kind == BlockKind.PARAGRAPH) block.copy(kind = kind, level = 0) else block
        }
    )
}

private fun signatureOf(doc: RichDoc): String = docSignature(doc)

@Composable
fun RichTextEditor(
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    label: String,
    help: String? = null,
    modifier: Modifier = Modifier,
    helpRow: (@Composable () -> Unit)? = null,
    listKind: BlockKind? = null,
    media: DwMediaBridge? = null,
    mediaField: FieldDto? = null,
    onMessage: (String) -> Unit = {},
    onError: (String) -> Unit = {},
) {
    val dictationAvailable = rememberDictationAvailable()
    var doc by remember {
        mutableStateOf(forEditing(if (listKind != null) seedAsList(value, listKind) else fromJson(value)))
    }
    var selection by remember { mutableStateOf(collapsedAt(DocPoint(0, 0))) }
    var pendingMarks by remember { mutableStateOf<List<String>?>(null) }
    var keptMediaId by remember { mutableStateOf<String?>(null) }
    var focusedBlock by remember { mutableStateOf<Int?>(null) }
    var pendingFocus by remember { mutableStateOf<DocPoint?>(null) }
    var lastEmitted by remember { mutableStateOf(signatureOf(doc)) }

    var past by remember { mutableStateOf(listOf<Pair<RichDoc, DocRange>>()) }
    var future by remember { mutableStateOf(listOf<Pair<RichDoc, DocRange>>()) }
    var lastWasTyping by remember { mutableStateOf(false) }

    val fieldValues = remember { mutableStateMapOf<Int, TextFieldValue>() }
    val focusRequesters = remember { mutableStateMapOf<Int, FocusRequester>() }

    fun emit(next: RichDoc) {
        val signature = signatureOf(next)
        if (signature == lastEmitted) return
        lastEmitted = signature
        onChange(emittedValue(next))
    }

    fun commit(result: EditResult, moveFocus: Boolean, typing: Boolean = false) {
        val next = forEditing(result.doc)
        if (next == doc) return
        if (!(typing && lastWasTyping)) {
            past = (past + (doc to selection)).takeLast(200)
            future = emptyList()
        }
        lastWasTyping = typing
        doc = next
        selection = result.selection
        if (moveFocus) pendingFocus = selection.focus
        emit(next)
    }

    fun restore(snapshot: Pair<RichDoc, DocRange>) {
        doc = snapshot.first
        selection = snapshot.second
        pendingFocus = snapshot.second.focus
        lastWasTyping = false
        fieldValues.clear()
        emit(snapshot.first)
    }

    var imageAnchor by remember { mutableStateOf<DocRange?>(null) }

    val pickPhotograph = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val bridge = media
        val field = mediaField
        val anchor = imageAnchor ?: selection
        imageAnchor = null
        if (uri == null || bridge == null || field == null) return@rememberLauncherForActivityResult
        bridge.attach(listOf(uri), field) { newIds ->
            val id = newIds.firstOrNull() ?: return@attach
            val item = bridge.resolve(id)
            if (item == null || !item.mediaType.equals("IMAGE", ignoreCase = true)) {
                bridge.detach(id)
                onError(
                    "Only photos can go in a narrative. Use the stage's media fields for other files."
                )
                return@attach
            }
            commit(insertImage(doc, anchor, id), moveFocus = true)
            onMessage(
                "Photo added. The line below it is its caption."
            )
        }
    }

    LaunchedEffect(value) {
        val incoming = forEditing(fromJson(value))
        if (signatureOf(incoming) != lastEmitted) {
            doc = incoming
            lastEmitted = signatureOf(incoming)
            selection = normaliseRange(incoming, selection)
            fieldValues.clear()
        }
    }

    LaunchedEffect(pendingFocus, doc.blocks.size) {
        val target = pendingFocus ?: return@LaunchedEffect
        val requester = focusRequesters[target.block] ?: return@LaunchedEffect
        pendingFocus = null
        runCatching { requester.requestFocus() }
        fieldValues[target.block] = TextFieldValue(
            annotatedString = doc.blocks.getOrNull(target.block)?.toAnnotatedString() ?: AnnotatedString(""),
            selection = TextRange(target.offset),
        )
    }

    val toolbar = toolbarState(doc, selection, pendingMarks)

    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotBlank()) {
            Text(requiredMarked(label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
        }

        if (focusedBlock != null && enabled) {
            RichTextToolbar(
                state = toolbar,
                canUndo = past.isNotEmpty(),
                canRedo = future.isNotEmpty(),
                onMark = { mark ->
                    val range = selection
                    if (range.isCollapsed()) {
                        val block = doc.blocks.getOrNull(range.focus.block)
                        val base = pendingMarks ?: block?.let { marksAt(it, range.focus.offset) } ?: emptyList()
                        pendingMarks = if (mark in base) base - mark else (base + mark).sorted()
                    } else {
                        commit(toggleMark(doc, range, mark), moveFocus = false)
                    }
                },
                onBlockKind = { kind, level ->
                    val alreadyThis = toolbar.kind == kind &&
                        (kind != BlockKind.HEADING.name || toolbar.level == level)
                    val target = if (alreadyThis) BlockKind.PARAGRAPH.name else kind
                    val targetLevel = if (alreadyThis) 0 else level
                    commit(setBlockKind(doc, selection, target, targetLevel), false)
                },
                onIndent = { delta -> commit(shiftIndent(doc, selection, delta), false) },
                onAlign = { align -> commit(setAlign(doc, selection, align), false) },
                onClear = {
                    pendingMarks = null
                    commit(clearFormatting(doc, selection), false)
                },
                onUndo = {
                    past.lastOrNull()?.let { snapshot ->
                        future = future + (doc to selection)
                        past = past.dropLast(1)
                        restore(snapshot)
                    }
                },
                onRedo = {
                    future.lastOrNull()?.let { snapshot ->
                        past = past + (doc to selection)
                        future = future.dropLast(1)
                        restore(snapshot)
                    }
                },
                onInsertImage = if (media != null && mediaField != null) {
                    {
                        imageAnchor = selection
                        pickPhotograph.launch("image/*")
                    }
                } else {
                    null
                },
            )
            if (dictationAvailable) {
                DwDictationButton(
                    enabled = enabled,
                    onPartial = { },
                    onCommit = { spoken ->
                        val phrase = spoken.trim()
                        if (phrase.isNotEmpty()) {
                            val range = selection
                            val block = doc.blocks.getOrNull(range.focus.block)
                            val before = block?.text?.take(range.focus.offset).orEmpty()
                            val joiner = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
                            val marks = pendingMarks
                                ?: block?.let { marksAt(it, range.focus.offset) }
                                ?: emptyList()
                            commit(insertText(doc, range, joiner + phrase, marks), moveFocus = false)
                        }
                    },
                    onError = onError,
                    onKept = { id -> keptMediaId = id },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        DwDictationKept(keptMediaId)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = if (focusedBlock != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val numbers = orderedNumbers(doc)
            doc.blocks.forEachIndexed { index, block ->
                RichTextBlockRow(
                    block = block,
                    index = index,
                    ordinal = numbers.getOrNull(index),
                    enabled = enabled,
                    focusRequester = remember(index) {
                        focusRequesters.getOrPut(index) { FocusRequester() }
                    },
                    fieldValues = fieldValues,
                    caret = if (selection.focus.block == index) selection.focus.offset else null,
                    photograph = if (block.kind == BlockKind.IMAGE) media?.resolve(block.media) else null,
                    onRemoveImage = { commit(removeImage(doc, index), moveFocus = true) },
                    onWidthStep = { delta -> commit(setImageWidth(doc, index, delta), moveFocus = false) },
                    onFocused = { focused ->
                        if (focused) {
                            focusedBlock = index
                            selection = collapsedAt(
                                DocPoint(index, fieldValues[index]?.selection?.start ?: 0),
                            )
                        } else if (focusedBlock == index) {
                            focusedBlock = null
                            pendingMarks = null
                        }
                    },
                    onSelectionChanged = { range ->
                        val moved = selection.focus.block != index ||
                            selection.focus.offset != range.start ||
                            selection.anchor.offset != range.end
                        selection = DocRange(DocPoint(index, range.start), DocPoint(index, range.end))
                        if (moved) pendingMarks = null
                    },
                    onTextEdit = { edit ->
                        val range = DocRange(DocPoint(index, edit.start), DocPoint(index, edit.end))
                        val marks = pendingMarks
                            ?: doc.blocks.getOrNull(index)?.let { marksAt(it, edit.start) }
                            ?: emptyList()
                        pendingMarks = null
                        val typed = insertText(doc, range, edit.inserted, marks)
                        val ruled = if (edit.inserted == " ") applyInputRule(typed.doc, typed.selection) else null
                        commit(ruled ?: typed, moveFocus = ruled != null, typing = ruled == null)
                    },
                    onEnter = { commit(splitBlock(doc, selection), moveFocus = true) },
                    onBackspaceAtStart = { commit(deleteBackward(doc, selection), moveFocus = true) },
                    onIndent = { delta ->
                        if (canShiftIndent(doc, selection, delta)) {
                            commit(shiftIndent(doc, selection, delta), false)
                        }
                    },
                )
            }
        }

        if (enabled && media != null) {
            Spacer(Modifier.height(8.dp))
            val passageNow = dwVerbPassageOf(doc, selection)
            val passageSummary = remember(passageNow) { dwVerbPassagePreview(passageNow) }
            DwAiVerbsPanel(
                enabled = enabled,
                passageChars = passageNow.length,
                passagePreview = passageSummary,
                readPassage = { dwVerbPassageOf(doc, selection) },
            )
        }

        if (helpRow != null) {
            Spacer(Modifier.height(4.dp))
            helpRow()
        } else if (!help.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RichTextBlockRow(
    block: RichBlock,
    index: Int,
    ordinal: Int?,
    enabled: Boolean,
    focusRequester: FocusRequester,
    fieldValues: SnapshotStateMap<Int, TextFieldValue>,
    caret: Int?,
    photograph: DwMediaItem?,
    onRemoveImage: () -> Unit,
    onWidthStep: (Float) -> Unit,
    onFocused: (Boolean) -> Unit,
    onSelectionChanged: (TextRange) -> Unit,
    onTextEdit: (TextEdit) -> Unit,
    onEnter: () -> Unit,
    onBackspaceAtStart: () -> Unit,
    onIndent: (Int) -> Unit,
) {
    val annotated = block.toAnnotatedString()
    val colour = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(index) {
        if (fieldValues[index] == null) {
            fieldValues[index] = TextFieldValue(annotated, TextRange(caret ?: 0))
        }
    }
    LaunchedEffect(annotated) {
        val current = fieldValues[index] ?: return@LaunchedEffect
        if (current.composition != null) return@LaunchedEffect
        if (current.annotatedString != annotated) {
            fieldValues[index] = current.copy(
                annotatedString = annotated,
                selection = TextRange((caret ?: current.selection.start).coerceIn(0, annotated.length)),
            )
        }
    }
    val fieldValue = fieldValues[index] ?: TextFieldValue(annotated, TextRange(caret ?: 0))

    if (block.kind == BlockKind.IMAGE) {
        InlinePhotograph(
            block = block,
            item = photograph,
            enabled = enabled,
            onRemove = onRemoveImage,
            onWidthStep = onWidthStep,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.level.coerceIn(0, 3) * 16).dp),
        verticalAlignment = Alignment.Top,
    ) {
        when {
            block.kind == BlockKind.BULLET_ITEM -> {
                Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
            }
            block.kind == BlockKind.ORDERED_ITEM -> {
                Text(
                    "${ordinal ?: 1}.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                )
            }
            block.kind == BlockKind.QUOTE -> {
                Box(
                    Modifier
                        .padding(end = 10.dp, top = 3.dp)
                        .width(3.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
            }
        }

        BasicTextField(
            value = fieldValue,
            onValueChange = { updated ->
                val previous = fieldValues[index]
                fieldValues[index] = updated
                val edit = previous?.let { diffEdit(it.text, updated.text) }
                if (edit != null) onTextEdit(edit) else onSelectionChanged(updated.selection)
            },
            enabled = enabled,
            textStyle = blockTextStyle(block, colour, MaterialTheme.typography),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { onFocused(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onEnter()
                            true
                        }
                        Key.Backspace -> {
                            val current = fieldValues[index]?.selection
                            if (current != null && current.collapsed && current.start == 0) {
                                onBackspaceAtStart()
                                true
                            } else {
                                false
                            }
                        }
                        Key.Tab -> {
                            if (block.kind.isListItem) {
                                onIndent(if (event.isShiftPressed) -1 else 1)
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                },
        )
    }
}

@Composable
private fun InlinePhotograph(
    block: RichBlock,
    item: DwMediaItem?,
    enabled: Boolean,
    onRemove: () -> Unit,
    onWidthStep: (Float) -> Unit,
) {
    val onDisk = remember(item?.absolutePath) {
        item?.absolutePath?.let { File(it).isFile } ?: false
    }
    val drawable = onDisk && item != null && item.mediaType.equals("IMAGE", ignoreCase = true)
    val caption = block.text.trim()

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (drawable) {
            AsyncImage(
                model = Uri.fromFile(File(item!!.absolutePath)),
                contentDescription = caption.ifEmpty { "Photograph placed in this narrative" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(
                        block.widthPct.coerceIn(IMAGE_MIN_WIDTH_PCT, IMAGE_MAX_WIDTH_PCT) / 100f
                    )
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).padding(top = 1.dp),
                )
                Column {
                    Text(
                        "Photograph — the line below is its caption",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Stored online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (enabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "${block.widthPct.toInt()}% of the page width",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { onWidthStep(-IMAGE_WIDTH_STEP_PCT) },
                    enabled = block.widthPct > IMAGE_MIN_WIDTH_PCT,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = "Narrower",
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(
                    onClick = { onWidthStep(IMAGE_WIDTH_STEP_PCT) },
                    enabled = block.widthPct < IMAGE_MAX_WIDTH_PCT,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Wider",
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove photograph",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
