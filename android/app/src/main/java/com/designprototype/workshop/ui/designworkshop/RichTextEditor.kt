package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import com.designprototype.workshop.report.Align
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.Mark
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.fromJson
import com.designprototype.workshop.report.toJson
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The rich text editor: bold, italic, underline, strike, headings, quotes, lists, alignment and
 * nesting, with a toolbar that belongs to whichever field the designer is actually in.
 *
 * ── WHY A TEXT FIELD PER BLOCK, RATHER THAN ONE FIELD FOR THE DOCUMENT ────────────────────────
 *
 * Compose ships no rich text editor and this app may not add one, so the shape of the solution is
 * forced. The obvious approach — a single [BasicTextField] holding the whole narrative, with marks
 * layered on as an [AnnotatedString] — collapses the moment structure matters. Pressing Enter in
 * such a field inserts a newline INTO a span, and the model's own parser exists to strip exactly
 * that (see `insertText`, which flattens a newline to a space, and the reason: a span holding one
 * makes a single block render as two paragraphs in the .docx and as one line with a stray gap in
 * the PDF — two renderers disagreeing about the same document). Nesting a list item would then have
 * to be inferred from leading whitespace, and the caret arithmetic for "which list item am I in"
 * would be re-derived on every keystroke from text that does not actually encode it.
 *
 * A field per BLOCK moves all of that into state transitions this file controls. Enter is a split.
 * Backspace at offset zero is an outdent, a demotion or a merge, in that order. Indentation is a
 * property of the block rather than of the characters. Each is a function in RichTextOps.kt with
 * the semantics ported from the web implementation and pinned by its self-check, and this file's
 * job is to route a keystroke to the right one and then put the caret where the result says.
 *
 * ── THE MODEL IS THE TRUTH, EXCEPT FOR THE ONE MOMENT WHEN IT MUST NOT BE ─────────────────────
 *
 * Every block's [AnnotatedString] is built FROM the model on recomposition, so the marks a designer
 * sees are the marks that will be written to the file. The single deliberate exception is an active
 * IME composition. Every Indic keyboard composes — this app is used in Odia and Hindi — and
 * replacing a [TextFieldValue] while a composition is open ends that composition, which turns a
 * conjunct being assembled out of three keystrokes into three separate letters on screen. So while
 * [TextFieldValue.composition] is non-null this file does not rebuild that field's value from the
 * model, and an external value arriving mid-word is parked until the composition closes.
 *
 * ── WHAT IS STORED ────────────────────────────────────────────────────────────────────────────
 *
 * The same JSON the web editor produces, because the two clients write into one column and a field
 * edited on a phone then reopened in a browser must not read as changed merely because it was
 * re-serialised. `null` when the document is empty by trimmed text — never an empty document, never
 * a document of empty paragraphs. Key order, omitted defaults and mark sorting all live in
 * `report/RichText.kt`'s `toJson`, which was verified against the Python by a 3044-check oracle, so
 * this file never assembles JSON itself.
 */

// --------------------------------------------------------------------------------------
// The model, rendered
// --------------------------------------------------------------------------------------

/**
 * The character-level styling for a set of marks.
 *
 * CODE is rendered as a monospace face rather than a tinted background box. The report renderers
 * print it in the body face (there is no monospace font embedded in the .docx or the PDF), so a
 * background box on the phone would promise the designer a visual treatment the generated document
 * does not deliver — and the whole purpose of showing marks live is that what is seen is what is
 * filed.
 */
private fun spanStyleFor(marks: Set<Mark>): SpanStyle {
    // Underline and strike are ONE property in Compose, not two. Setting textDecoration twice keeps
    // only the last, so a run that is both underlined and struck through would silently lose one of
    // them — which is a mark the designer applied, absent from the screen but present in the file.
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

/** A block's text with its marks applied, which is what the field displays. */
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

/** Heading sizes, deliberately the same ladder the report uses, so the phone previews the file. */
private fun headingSize(level: Int): Int = when (level) {
    1 -> 22
    2 -> 19
    3 -> 17
    else -> 15
}

/** The paragraph-level styling for a block: its face, its size and its alignment. */
private fun blockTextStyle(block: RichBlock, base: Color): TextStyle {
    val alignment = when (block.align) {
        Align.CENTER -> TextAlign.Center
        Align.RIGHT -> TextAlign.End
        Align.JUSTIFY -> TextAlign.Justify
        else -> TextAlign.Start
    }
    return when (block.kind) {
        BlockKind.HEADING -> TextStyle(
            fontSize = headingSize(block.level).sp,
            fontWeight = FontWeight.SemiBold,
            color = base,
            textAlign = alignment,
        )
        BlockKind.QUOTE -> TextStyle(
            fontSize = 15.sp,
            fontStyle = FontStyle.Italic,
            color = base.copy(alpha = 0.86f),
            textAlign = alignment,
        )
        else -> TextStyle(fontSize = 15.sp, color = base, textAlign = alignment)
    }
}

// --------------------------------------------------------------------------------------
// Turning a field's new text back into a document edit
// --------------------------------------------------------------------------------------

/** The one contiguous stretch of a block that changed: `[start, end)` replaced by [inserted]. */
private data class TextEdit(val start: Int, val end: Int, val inserted: String)

/**
 * The minimal edit that turns [old] into [new], as a common-prefix / common-suffix diff.
 *
 * ── WHY DIFF AT ALL, RATHER THAN REPLACING THE BLOCK'S TEXT ───────────────────────────────────
 *
 * Because replacing the text would throw away every mark in the block. The field hands back a plain
 * string; the model holds spans. Writing that string into the block as one unmarked span is the
 * easy mistake, and its symptom is that a designer who bolds a phrase and then corrects a typo six
 * words later watches the bold vanish. Diffing to the smallest changed stretch and routing it
 * through `deleteRange`/`insertText` keeps every span either side of the edit intact, because those
 * functions slice spans rather than rebuild them.
 *
 * The diff is deliberately naive — one prefix, one suffix, one replaced middle. A keystroke, a
 * backspace, an autocorrect substitution and an IME commit are all exactly that shape. A change
 * that genuinely is not (a paste that reuses characters from both ends) still produces a CORRECT
 * result, just a larger replaced middle than strictly necessary, which costs the marks inside that
 * middle and nothing else.
 *
 * Returns null when the strings are equal, which is the common case on a selection-only change.
 */
private fun diffEdit(old: String, new: String): TextEdit? {
    if (old == new) return null
    // Scan in UTF-16 units, then walk the boundary back off any surrogate pair we landed inside.
    // Cutting between the two halves of an astral character would hand `insertText` a lone
    // surrogate, and a lone surrogate is precisely what `cleanText` exists to strip — the character
    // would silently disappear instead of being typed.
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

// --------------------------------------------------------------------------------------
// The editor
// --------------------------------------------------------------------------------------

/**
 * The document the caret lives in, which must always have at least one block.
 *
 * An empty document has nowhere to put a caret, so a field rendered for one would draw no text
 * field at all and the designer would find a labelled blank they cannot click into. The block this
 * adds is never stored: `isEmptyDoc` judges on trimmed text, so a document of one empty paragraph
 * serialises to `null` exactly as the web's does.
 */
private fun forEditing(doc: RichDoc): RichDoc =
    if (doc.blocks.isEmpty()) emptyDoc() else doc

/**
 * Seed a document for a field that is a LIST by nature — the registry's `report_role=BULLETS`
 * fields, whose help reads "One deliverable per line", "One objective per line", "One problem per
 * line", and which the report prints as a list.
 *
 * The port of `richText.ts::seedListDocument`, and it must stay the port: the two clients write
 * into the same column and a designer who starts a list on the phone must find a list in the
 * browser. Only two inputs are converted, for the reasons given there —
 *
 *   - EMPTY, or a value that was a bare JSON STRING (the pre-promotion shape, where the newlines
 *     the help text asked for ARE the list), and
 *   - nothing else. A stored block document is returned untouched, because by then the blocks are
 *     the designer's own choices and reshaping them would be the editor arguing with them.
 *
 * Seeding the FIRST block is all that is needed: `splitBlock` already carries a list kind across an
 * Enter and already leaves the list on an empty item, so the second line numbers itself.
 */
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

/**
 * The canonical form under which two stored values are the same document.
 *
 * `null`, an absent value, an empty object and a document of empty paragraphs are four spellings of
 * "nothing has been written here", and a value prop arriving in a different spelling from the one
 * this editor emitted must not be treated as an external change — doing so re-seeds the field, and
 * re-seeding moves the caret to the start of the document. The symptom in the field is a designer
 * losing their place every time the stage's debounced save completes, which is the single most
 * common way a home-grown editor becomes unusable for long-form writing.
 */
private fun signatureOf(doc: RichDoc): String =
    if (isEmptyDoc(doc)) "EMPTY" else toJson(doc).toString()

@Composable
fun RichTextEditor(
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    label: String,
    help: String? = null,
    modifier: Modifier = Modifier,
    /**
     * Start this field as a list of the given kind — set for `report_role=BULLETS` fields so the
     * designer is inside a numbered item the moment the field opens. See [seedAsList].
     */
    listKind: BlockKind? = null,
) {
    // Read unconditionally, at the top level, because it is a composable call and the button below
    // sits behind an `if`. Reading it inside that branch would make the call site conditional,
    // which Compose forbids and which would crash the moment the toolbar appeared.
    val dictationAvailable = rememberDictationAvailable()
    var doc by remember {
        mutableStateOf(forEditing(if (listKind != null) seedAsList(value, listKind) else fromJson(value)))
    }
    var selection by remember { mutableStateOf(collapsedAt(DocPoint(0, 0))) }
    // The marks the NEXT typed character will carry. Null means "inherit from the caret's
    // neighbour", which is what ordinary typing does; a non-null set is the designer having pressed
    // Bold with nothing selected, and it survives until the caret moves or a character consumes it.
    var pendingMarks by remember { mutableStateOf<List<String>?>(null) }
    var focusedBlock by remember { mutableStateOf<Int?>(null) }
    // Where the caret must be put after a structural edit moved it to a DIFFERENT block. Compose
    // gives focus to a composable, not to a document position, so a split has to ask the new
    // block's field for focus once that field exists.
    var pendingFocus by remember { mutableStateOf<DocPoint?>(null) }
    var lastEmitted by remember { mutableStateOf(signatureOf(doc)) }

    // Undo, as a snapshot stack of the document AND the selection that produced it. Restoring the
    // text without the caret would leave a designer looking at a document they can no longer edit
    // from where they were.
    var past by remember { mutableStateOf(listOf<Pair<RichDoc, DocRange>>()) }
    var future by remember { mutableStateOf(listOf<Pair<RichDoc, DocRange>>()) }
    // Consecutive keystrokes coalesce into ONE undo step. Per-character undo is technically correct
    // and unusable: undoing a mistyped sentence would take forty presses. The web coalesces on a
    // 600ms timer; a burst of typing commits is the same idea without needing a clock.
    var lastWasTyping by remember { mutableStateOf(false) }

    val fieldValues = remember { mutableStateMapOf<Int, TextFieldValue>() }
    val focusRequesters = remember { mutableStateMapOf<Int, FocusRequester>() }

    fun emit(next: RichDoc) {
        val signature = signatureOf(next)
        if (signature == lastEmitted) return
        lastEmitted = signature
        onChange(if (isEmptyDoc(next)) null else toJson(next))
    }

    /**
     * Apply an edit: adopt its document, put the caret where it says, and publish the result.
     *
     * The selection is taken from the result VERBATIM. It is already clamped by the ops layer, and
     * re-normalising it here would put it in document order — which silently reverses a selection
     * the designer dragged upward, so their next Shift+Left would extend the wrong end.
     */
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

    // ADOPTING AN EXTERNAL VALUE. Only when it is genuinely a different document from the one this
    // editor last published — see `signatureOf`. A value that matches is this editor's own output
    // completing its round trip through the form, and re-seeding on it would fight the typist.
    LaunchedEffect(value) {
        val incoming = forEditing(fromJson(value))
        if (signatureOf(incoming) != lastEmitted) {
            doc = incoming
            lastEmitted = signatureOf(incoming)
            selection = normaliseRange(incoming, selection)
            fieldValues.clear()
        }
    }

    // Moving the caret to another block after a split, a merge or a demotion.
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
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
        }

        // THE CONTEXTUAL TOOLBAR. It exists only while a block of THIS field holds focus, which is
        // the user's actual requirement: a stage with twelve narrative fields must not draw twelve
        // permanent toolbars, and the one bar on screen must act on the field being written in.
        if (focusedBlock != null && enabled) {
            RichTextToolbar(
                state = toolbar,
                canUndo = past.isNotEmpty(),
                canRedo = future.isNotEmpty(),
                onMark = { mark ->
                    val range = selection
                    if (range.isCollapsed()) {
                        // A collapsed caret changes no text, so there is nothing to publish. It arms
                        // the mark for the next character instead — pressing Bold and then typing is
                        // how anybody writes, and requiring a selection first would make the button
                        // useless at the end of a sentence.
                        val block = doc.blocks.getOrNull(range.focus.block)
                        val base = pendingMarks ?: block?.let { marksAt(it, range.focus.offset) } ?: emptyList()
                        pendingMarks = if (mark in base) base - mark else (base + mark).sorted()
                    } else {
                        commit(toggleMark(doc, range, mark), moveFocus = false)
                    }
                },
                // THE BAR REPORTS A PRESS; THIS DECIDES WHAT IT MEANS. Pressing the kind a block
                // already is returns it to a paragraph, which is what makes every one of these
                // buttons a toggle — without it there is no way to leave a quote or a list from the
                // bar at all, and the designer has to discover that Backspace at offset zero does it.
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
            )
            // DICTATION, INTO THE MODEL RATHER THAN ONTO THE END OF A STRING.
            //
            // Every other prose field gets its microphone from `ScalarInput`'s trailing icon, whose
            // commit appends to a plain string — which is why `dictatable()` names only TEXT and
            // LONG_TEXT and must go on doing so. A string committed into a RICH_TEXT field is read
            // by the server as unformatted prose, so routing dictation that way would flatten every
            // heading, list and mark the designer had already applied.
            //
            // So it goes through `insertText` at the caret, exactly as a keystroke does: same mark
            // inheritance, same undo entry, and it lands where the designer is writing rather than
            // at the end of the document. The web editor carries the identical control beside its
            // word count, for the identical reason.
            if (dictationAvailable) {
                DwDictationButton(
                    enabled = enabled,
                    onPartial = { },
                    onCommit = { spoken ->
                        val phrase = spoken.trim()
                        if (phrase.isNotEmpty()) {
                            val range = selection
                            val block = doc.blocks.getOrNull(range.focus.block)
                            // A space unless the caret already follows one or starts the block: the
                            // recogniser stops and starts across a long answer, and without this a
                            // paragraph dictated in five goes reads "…the warpis sized…".
                            val before = block?.text?.take(range.focus.offset).orEmpty()
                            val joiner = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
                            val marks = pendingMarks
                                ?: block?.let { marksAt(it, range.focus.offset) }
                                ?: emptyList()
                            commit(insertText(doc, range, joiner + phrase, marks), moveFocus = false)
                        }
                    },
                    onError = { },
                )
            }
            Spacer(Modifier.height(6.dp))
        }

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
                    focusRequester = focusRequesters.getOrPut(index) { FocusRequester() },
                    fieldValues = fieldValues,
                    caret = if (selection.focus.block == index) selection.focus.offset else null,
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
                        // A pending mark belongs to the position that armed it. Carrying it to a new
                        // caret would bold a word the designer merely clicked into.
                        if (moved) pendingMarks = null
                    },
                    onTextEdit = { edit ->
                        val range = DocRange(DocPoint(index, edit.start), DocPoint(index, edit.end))
                        val marks = pendingMarks
                            ?: doc.blocks.getOrNull(index)?.let { marksAt(it, edit.start) }
                            ?: emptyList()
                        pendingMarks = null
                        val typed = insertText(doc, range, edit.inserted, marks)
                        // Markdown-style shortcuts fire on the space that completes them, and only
                        // outside a composition: an IME's intermediate space is not a word boundary.
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

        if (!help.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(help, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One block: its list marker or quote rule, and the field the designer types into. */
@Composable
private fun RichTextBlockRow(
    block: RichBlock,
    index: Int,
    ordinal: Int?,
    enabled: Boolean,
    focusRequester: FocusRequester,
    fieldValues: SnapshotStateMap<Int, TextFieldValue>,
    caret: Int?,
    onFocused: (Boolean) -> Unit,
    onSelectionChanged: (TextRange) -> Unit,
    onTextEdit: (TextEdit) -> Unit,
    onEnter: () -> Unit,
    onBackspaceAtStart: () -> Unit,
    onIndent: (Int) -> Unit,
) {
    val annotated = block.toAnnotatedString()
    val colour = MaterialTheme.colorScheme.onSurface

    // Seed the field once, then keep it in step with the model — but never mid-composition. See the
    // file header: ending an Indic composition by replacing the value turns a conjunct into its
    // separate letters, so a style refresh that arrives while one is open waits for it to close.
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.level.coerceIn(0, 3) * 16).dp),
        verticalAlignment = Alignment.Top,
    ) {
        when {
            block.kind == BlockKind.BULLET_ITEM -> {
                Text("•", fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
            }
            block.kind == BlockKind.ORDERED_ITEM -> {
                // The ordinal comes from the model's own numbering, not from the row's position, so
                // the number on screen is the number the .docx will print. A list interrupted by a
                // paragraph restarts in both places or in neither.
                Text(
                    "${ordinal ?: 1}.",
                    fontSize = 15.sp,
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
            textStyle = blockTextStyle(block, colour),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { onFocused(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        // Enter is a BLOCK SPLIT, never a newline character. A newline inside a span
                        // is the one thing the model's parser has to repair, and it would make this
                        // block print as two paragraphs in the .docx and as one gapped line in the
                        // PDF — the two renderers disagreeing about the same document.
                        Key.Enter, Key.NumPadEnter -> {
                            onEnter()
                            true
                        }
                        // Backspace is ordinary inside a block; at offset zero it is structural, and
                        // the ladder it runs (outdent, then demote, then merge) lives in the ops.
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
                                // Outside a list Tab belongs to focus traversal. Swallowing it would
                                // trap a hardware-keyboard user inside a twelve-field stage.
                                false
                            }
                        }
                        else -> false
                    }
                },
        )
    }
}
