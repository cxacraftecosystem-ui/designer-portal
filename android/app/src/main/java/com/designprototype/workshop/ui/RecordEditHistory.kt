package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.RecordRevisionDto
import com.designprototype.workshop.data.RevisionChange
import com.designprototype.workshop.data.readableStamp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId
import java.util.Locale

/* ==================================================================================================
 * THE EDIT HISTORY OF ONE RECORD, ON THE HANDSET — and the one exception that must never be
 * rendered as though it were a value.
 *
 * `GET /api/data-access/revisions` returns an append-only ledger: one `RecordRevision` per edit,
 * carrying `{field: {old, new}}` and stamped with `editedById`. Owner-and-admin only; the endpoint
 * 403s everybody else and both clients hide the section rather than showing a refusal.
 *
 * ── WHY THIS FILE EXISTS AT ALL, GIVEN THE HANDSET ALREADY DREW SOMETHING ─────────────────────
 *
 * `MainActivity.RecordCollabSection` already listed the ledger. What it did NOT carry is the whole
 * substance of the parity gap `backend/app/api/routes/artisans.py` logged against this client, in
 * these words: the handset "is not yet at parity on the CAPTION naming this exception, nor on the
 * `redacted` flag". Read that block, and `access.REVISION_REDACTED_FIELDS`, before touching
 * anything here.
 *
 * THE EXCEPTION. Five columns — `aadhaarNumber`, `pehchanCardNumber`, `phone`, `email`, `address` —
 * are logged WITHOUT their value. `access._redacted_change` writes the DIRECTION of the change
 * instead: "(value recorded)" -> "(cleared)", and three siblings. The argument is written above the
 * set and the short version is that a retraction which copies the retracted value into an
 * append-only table is not a retraction.
 *
 * WHAT THAT COSTS A READER, AND WHY THE CAPTION IS NOT DECORATION. The panel's promise everywhere
 * else is "the original value is the first `before` of each field". For these five that promise is
 * FALSE, and an admin who believes it reads "(value recorded)" as the old Aadhaar number — or, far
 * worse, reads a row that says nothing and concludes nothing was done. The web panel had exactly
 * this defect until its caption was rewritten to name the exception; `frontend/components/
 * CollabPanel.tsx` carries the fixed wording and a comment saying why. [RECORD_EDIT_HISTORY_CAPTION]
 * below is that wording, word for word, and it is the reason this file is a parity fix rather than a
 * restyle.
 *
 * ── WHAT THIS FILE MATCHES THE WEB ON, EXACTLY ────────────────────────────────────────────────
 *
 *   * THE WORDING — the caption, the "Edit history" heading, the "No edits recorded." empty state,
 *     the "Unknown" fallback for a missing editor, and the em-dash for an absent value are the web
 *     strings character for character. They are `const`s here so a JVM test can pin them.
 *   * THE ORDERING — `data_access.list_revisions` sends `order={"createdAt": "asc"}`, oldest first,
 *     and BOTH clients render in the order received. Nothing here re-sorts. That is not laziness:
 *     the caption's claim about "the FIRST before" is a claim about the server's ordering, and a
 *     client that imposed its own would be a second source of truth for the sentence printed above
 *     the list.
 *   * THE EMPTY STATE — "No edits recorded." is drawn for an empty list. A NULL list means the
 *     endpoint refused (not owner, not admin) and the whole section is absent, which is why
 *     [RecordEditHistorySection] takes a nullable list and draws nothing for null.
 *
 * ── THE TWO THINGS IT DOES NOT MATCH, AND WHY ─────────────────────────────────────────────────
 *
 * FIRST, THE INK. The web sets every `old` in red with a strikethrough and every `new` in green. Applied to a
 * redacted pair that reads "(value recorded)" struck through, which says the LITERAL TEXT was
 * deleted, in the value colours that everywhere else in this panel mean "this is what the field
 * held". Here those two words are set muted and italic, with no strikethrough and no green — same
 * words, same arrow, same order, different ink.
 *
 * The reason is a real platform difference and not a taste: on the web the panel is short and the
 * caption is on screen with the rows. On a handset a record with thirty edits scrolls, and the
 * reader who meets "phone: (value recorded) -> (cleared)" is several screens below the sentence
 * that explains it. The ink is what carries the exception once the caption has scrolled away.
 *
 * SECOND, A BACKSTOP UNDER THE TWO IDENTITY KEYS — `RecordRevisionRedaction.redactedPlaceholder`,
 * where the argument is written out. In one sentence: this .apk goes on talking to whatever server
 * is deployed long after it was built, `records._IDENTITY_KEYS` promises that no value crosses
 * under `aadhaarNumber`/`pehchanCardNumber`, and where that promise fails this client prints the
 * server's own four words rather than twelve digits. It fires on nothing the current contract can
 * send, and it widens the redaction set by exactly nothing.
 * ================================================================================================== */

/**
 * The five columns whose ledger rows carry a direction and no value.
 *
 * A MIRROR OF `backend/app/services/access.REVISION_REDACTED_FIELDS`, and a mirror is exactly what
 * it is: this list is not authority, the server is. It is here because a renderer has to decide
 * whether a pair of strings in front of it is a value or a placeholder, and the field name is half
 * of that decision (see [isRedactedPlaceholder] for the other half).
 *
 * WIDENING OR NARROWING IT IS AN OWNER'S CALL AND NOT A CLIENT'S. The backend comment above the set
 * spells out which columns were deliberately LEFT OUT and what each exclusion buys — `notes`,
 * `dos`, `donts` and `localName` keep their old text because it is the only way to see what a
 * malicious edit quietly removed; `gender`, `dateOfBirth`, `craftStartDate` and `experienceYears`
 * are load-bearing derived data. A handset that redacted more than the server does would be making
 * that call for the owner AND hiding from a phone what a laptop still shows. So: do not add to this
 * set here. Add it to `access.REVISION_REDACTED_FIELDS`, and this follows.
 */
object RecordRevisionRedaction {

    val FIELDS: Set<String> = setOf(
        "aadhaarNumber",
        "pehchanCardNumber",
        "phone",
        "email",
        "address"
    )

    // The five wordings `access._redacted_change` writes. Constants rather than five literals for
    // the same reason they are constants on the server: a second reader has to recognise them.
    const val HAD_VALUE: String = "(value recorded)"
    const val CLEARED: String = "(cleared)"
    const val REPLACED: String = "(value replaced)"
    const val EMPTY: String = "(empty)"
    const val STILL_EMPTY: String = "(still empty)"

    /**
     * Every `(old, new)` pair the server can write for a redacted column — the Kotlin copy of
     * `access.REDACTED_PLACEHOLDER_PAIRS`.
     *
     * THE POINT OF THE SET IS THAT IT IS CLOSED. A reader holding it can decide "this entry is one
     * of the server's placeholders" by comparing against four constant pairs, which is the test the
     * backend prescribes for its own reader in `records._mask_identity_node`: *"THE PAIR IS CHECKED
     * AGAINST THE CLOSED SET RATHER THAN THE FLAG BELIEVED, because `extraMetadata` is
     * client-writable"*. This client does the same thing for the same reason — see
     * [isRedactedPlaceholder].
     *
     * Note that no pair has `old == new`. That is deliberate on the server: four distinct wordings
     * mean a consumer which decides whether to draw a row by diffing the two still sees a change on
     * every one of them, where a shared "(redacted)" on both sides would have hidden it.
     */
    val PLACEHOLDER_PAIRS: Set<Pair<String, String>> = setOf(
        EMPTY to STILL_EMPTY,
        HAD_VALUE to CLEARED,
        HAD_VALUE to REPLACED,
        EMPTY to HAD_VALUE
    )

    /** Whether [field] is one of the five the server logs without a value. Exact, not case-folded:
     *  the ledger is keyed by Prisma column name, which is fixed camelCase on both sides. */
    fun isRedactedField(field: String): Boolean = field in FIELDS

    /**
     * Whether this ledger entry is one of the server's own placeholders — i.e. whether the two
     * strings on screen describe a change rather than carry a value.
     *
     * TWO CONDITIONS, BOTH REQUIRED:
     *
     *   1. THE FIELD NAME is one of [FIELDS]. Without this gate a `notes` edit whose old text
     *      happens to read "(value recorded)" would be dressed as a redaction and a real, readable
     *      note would be presented as a non-value. The server applies this vocabulary only under
     *      these five names, so recognising it only under these five names is faithful.
     *   2. THE PAIR is in [PLACEHOLDER_PAIRS]. Not `old` alone, not `new` alone — the pair. A row
     *      whose `old` is "(value recorded)" and whose `new` is a real phone number is NOT one of
     *      the server's four transitions and must not be treated as one.
     *
     * AND ONE CONDITION DELIBERATELY NOT USED: the `redacted: true` flag the server sends beside
     * the pair. `access._redacted_change` says what that flag is for in as many words — *"the flag
     * is a CONVENIENCE for a renderer, never a security decision, because the same key can appear
     * in a client-written Json column"* — and `records._mask_identity_node` refuses to believe it
     * for exactly that reason. Modelling the flag on `RevisionChange` so it stops being dropped by
     * `ignoreUnknownKeys` is a fine thing to do and is a separate change; wiring it into THIS
     * decision is not, and if it is ever added it must widen nothing here.
     *
     * NOT-RECOGNISED IS NOT THE SAME AS NOT-REDACTED, and this function does not pretend otherwise.
     * A ledger row for `phone`/`email`/`address` written BEFORE the redaction set existed still
     * holds the real retracted value, and — unlike `aadhaarNumber`/`pehchanCardNumber`, which
     * `records._IDENTITY_KEYS` re-derives on the way out — nothing masks it on the read path. It
     * arrives here as an ordinary pair and is rendered as one, which is what the web does with the
     * same bytes. Suppressing it on the phone alone would hide from a field designer what an admin
     * on a laptop can still see, while removing nothing from the database; that is an owner's call
     * on the server, and `access.REVISION_REDACTED_FIELDS` records it as re-raised.
     */
    fun isRedactedPlaceholder(field: String, old: String?, new: String?): Boolean {
        if (!isRedactedField(field)) return false
        if (old == null || new == null) return false
        return (old to new) in PLACEHOLDER_PAIRS
    }

    /**
     * The two column names the READ path masks by key, `records._IDENTITY_KEYS`.
     *
     * They are a strict subset of [FIELDS] and the difference between the two sets is the whole
     * point of [redactedPlaceholder] below. For these two the server promises that NO value crosses
     * the wire under any circumstances: a recognised placeholder is passed through, an
     * audit-shaped entry from before the redaction existed is re-derived into one, and everything
     * else becomes a flat mask. For the other three — `phone`, `email`, `address` — there is no
     * masking on the read path at all, only on the write path, so a ledger row written before
     * `access.REVISION_REDACTED_FIELDS` existed still arrives holding the real retracted value and
     * both clients show it.
     */
    val SERVER_MASKED_FIELDS: Set<String> = setOf("aadhaarNumber", "pehchanCardNumber")

    /**
     * The Kotlin port of `access.redacted_placeholder` — which pair of words describes this
     * transition, derived from nothing but whether each side was empty.
     *
     * WHY A CLIENT HAS A COPY OF A SERVER FUNCTION, when the server already runs it. Because of the
     * gap between when this .apk is built and when it stops being used. An Android change costs a
     * tagged release and a fleet that may be out of signal for a fortnight; the handset therefore
     * talks to whatever server is deployed, not to the one it shipped against. Under
     * [SERVER_MASKED_FIELDS] the current contract says an unmasked value cannot reach this screen —
     * so when one does, the wire is not the contract, and the choice is between printing twelve
     * digits of somebody's Aadhaar in a history panel and printing the same four words the server
     * itself would have printed. It costs nothing when the promise holds: a pair the server already
     * redacted matches [PLACEHOLDER_PAIRS] and never reaches this function.
     *
     * THIS IS NOT THE CLIENT WIDENING THE REDACTION SET. It changes nothing about which columns are
     * redacted — that is `access.REVISION_REDACTED_FIELDS`'s decision and an owner's to revisit. It
     * only refuses to print, under two key names, a thing the server states it does not send.
     *
     * FOUR WORDINGS RATHER THAN ONE MASK ON BOTH SIDES, for the reason the server gives: masking a
     * replacement twice yields "XXXX XXXX XXXX" -> "XXXX XXXX XXXX", which is legible and reads as
     * though nothing changed — on the one screen somebody opened to find out what was done.
     */
    fun redactedPlaceholder(old: JsonElement?, new: JsonElement?): Pair<String, String> {
        val had = !isEmptyJson(old)
        val has = !isEmptyJson(new)
        return when {
            !had && !has -> EMPTY to STILL_EMPTY
            !has -> HAD_VALUE to CLEARED
            had -> HAD_VALUE to REPLACED
            else -> EMPTY to HAD_VALUE
        }
    }

    /**
     * `deps.is_empty_value` for a JSON node: null and JSON null are empty, a blank or
     * whitespace-only string is empty, an empty array or object is empty, and nothing else is.
     * A number or a boolean is never empty — `0` and `false` are values, and the Python original
     * says so by only special-casing `str` and the containers.
     */
    private fun isEmptyJson(value: JsonElement?): Boolean = when {
        value == null || value is JsonNull -> true
        value is JsonPrimitive -> value.content.isBlank()
        value is JsonArray -> value.isEmpty()
        value is JsonObject -> value.isEmpty()
        else -> false
    }
}

/** The heading over the ledger. The web's `<h3>Edit history</h3>`. */
const val RECORD_EDIT_HISTORY_TITLE: String = "Edit history"

/**
 * The caption, word for word from `frontend/components/CollabPanel.tsx`.
 *
 * DO NOT SHORTEN IT FOR THE SMALLER SCREEN. Every clause is load-bearing: the first names what the
 * left-hand column normally is, the second names the five fields where it is not and says outright
 * that the value is never recorded, and the third tells the reader who else can see this. The
 * sentence this replaced said only the first clause, and that is precisely the sentence which told
 * an admin the left-hand column was the old Aadhaar number when it never is.
 */
const val RECORD_EDIT_HISTORY_CAPTION: String =
    "Original values are the first \"before\" of each field — except identity and contact fields " +
        "(Aadhaar, Pehchan card, phone, email, address), where only the fact that they changed is " +
        "recorded, never the value. Visible to the owner and admins."

/** Drawn when the ledger is readable and empty. The web's `No edits recorded.` */
const val RECORD_EDIT_HISTORY_EMPTY: String = "No edits recorded."

/** The web's `r.editedBy?.name ?? "Unknown"`. */
const val RECORD_EDIT_HISTORY_UNKNOWN_EDITOR: String = "Unknown"

/** The web's `String(change.old ?? "—")` for a side the ledger has no value for. */
const val RECORD_EDIT_HISTORY_ABSENT: String = "—"

/**
 * One field's before/after, already resolved to what will be drawn.
 *
 * [redacted] is the answer [RecordRevisionRedaction.isRedactedPlaceholder] gave for this row, kept
 * on the model rather than recomputed in the composable so a JVM test can assert the classification
 * without composing anything. Nothing else about the row changes with it: [old] and [new] hold the
 * server's own words either way.
 */
data class RecordRevisionChangeRow(
    val field: String,
    val old: String,
    val new: String,
    val redacted: Boolean
)

/**
 * A JSON side of a change, as text.
 *
 * A string primitive renders unquoted (`.content`), which is what makes `"(value recorded)"`
 * comparable against [RecordRevisionRedaction.PLACEHOLDER_PAIRS] and what stops every value on the
 * screen being wrapped in quotation marks. `null` and a JSON null both become the em-dash in
 * [RECORD_EDIT_HISTORY_ABSENT], matching the web's `?? "—"`.
 *
 * A container (object or array) renders as its JSON text rather than the web's `"[object Object]"`.
 * That is a deliberate difference from a browser coercion nobody chose: `[object Object]` is not
 * information. It cannot leak an identity column — `records._mask_identity_node` replaces any
 * container it does not recognise under `aadhaarNumber`/`pehchanCardNumber` with a flat mask string
 * before it is ever encoded — and under the other three the ledger holds scalars.
 */
fun revisionValueText(value: JsonElement?): String {
    if (value == null || value is JsonNull) return RECORD_EDIT_HISTORY_ABSENT
    val primitive = value as? JsonPrimitive ?: return value.toString()
    return primitive.content
}

/**
 * The raw text of a side, WITHOUT the em-dash substitution, for classification only.
 *
 * The placeholder comparison has to run against what the server actually wrote. Feeding it the
 * display text would compare "—" against the closed set on a JSON null, which is harmless today but
 * is the kind of coupling that turns a display tweak into a redaction bug.
 */
private fun revisionRawText(value: JsonElement?): String? {
    if (value == null || value is JsonNull) return null
    return (value as? JsonPrimitive)?.content
}

/**
 * One change entry, classified and resolved to display text. Three arms, in this order:
 *
 *  1. A PAIR THE SERVER ALREADY REDACTED — recognised by name and by the closed set — is passed
 *     through in the server's own words and marked [RecordRevisionChangeRow.redacted]. This is the
 *     arm every current-contract row takes.
 *  2. ANYTHING ELSE UNDER `aadhaarNumber` / `pehchanCardNumber` is re-derived through
 *     [RecordRevisionRedaction.redactedPlaceholder] instead of being printed. Read the argument on
 *     that function: this arm exists because an .apk outlives the server it was built against, and
 *     it fires only on a shape the server says it does not send.
 *  3. EVERYTHING ELSE renders verbatim, which includes a `phone` / `email` / `address` row written
 *     before the redaction existed and still holding the retracted value. That is what the server
 *     serves and what the web shows; hiding it on the phone alone would remove nothing from the
 *     database while making a laptop and a handset disagree about the same record.
 */
fun recordRevisionChangeRow(field: String, change: RevisionChange): RecordRevisionChangeRow {
    val rawOld = revisionRawText(change.old)
    val rawNew = revisionRawText(change.new)
    if (RecordRevisionRedaction.isRedactedPlaceholder(field, rawOld, rawNew)) {
        return RecordRevisionChangeRow(field, rawOld.orEmpty(), rawNew.orEmpty(), redacted = true)
    }
    if (field in RecordRevisionRedaction.SERVER_MASKED_FIELDS) {
        val (old, new) = RecordRevisionRedaction.redactedPlaceholder(change.old, change.new)
        return RecordRevisionChangeRow(field, old, new, redacted = true)
    }
    return RecordRevisionChangeRow(
        field = field,
        old = revisionValueText(change.old),
        new = revisionValueText(change.new),
        redacted = false
    )
}

/**
 * Every change in one revision, in the order the server sent them.
 *
 * `changes` decodes into a `LinkedHashMap`, so this preserves the JSON object's key order — the
 * same order `Object.entries(r.changes)` gives the web. Not sorted alphabetically: a diff whose
 * rows move about between two readers of the same edit is a diff two people cannot discuss.
 */
fun recordRevisionRows(changes: Map<String, RevisionChange>): List<RecordRevisionChangeRow> =
    changes.map { (field, change) -> recordRevisionChangeRow(field, change) }

/**
 * "Asha Devi · 26 Aug 2026, 03:15 pm" — who made this edit and when.
 *
 * [zone] and [locale] are arguments so a JVM test can pin the string on any machine; the defaults
 * read the device, which is what every caller wants. `readableStamp` returns the raw stamp when it
 * cannot be parsed rather than blanking it — a timestamp we cannot read is still evidence of when
 * something happened, and hiding it would remove the only clue.
 */
fun recordRevisionEditorLine(
    revision: RecordRevisionDto,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val who = revision.editedBy?.name?.takeIf { it.isNotBlank() } ?: RECORD_EDIT_HISTORY_UNKNOWN_EDITOR
    val whenText = readableStamp(revision.createdAt, zone, locale) ?: RECORD_EDIT_HISTORY_ABSENT
    return "$who · $whenText"
}

/**
 * The edit-history section: heading, caption, then one block per revision.
 *
 * Pass NULL when `GET /data-access/revisions` refused — the endpoint 403s anyone who is neither the
 * record's owner nor an admin, and both clients swallow that and draw nothing rather than showing a
 * reader a refusal for a thing they were never offered. Pass an EMPTY list when it returned no rows;
 * that draws [RECORD_EDIT_HISTORY_EMPTY], which is a different and useful statement: the ledger is
 * readable and this record has never been edited.
 *
 * Deliberately not a card of its own. It is drawn inside the caller's existing record card, below a
 * divider, exactly where `RecordCollabSection` already put it.
 */
@Composable
fun RecordEditHistorySection(
    revisions: List<RecordRevisionDto>?,
    modifier: Modifier = Modifier
) {
    val revs = revisions ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            RECORD_EDIT_HISTORY_TITLE,
            display = true,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Text(RECORD_EDIT_HISTORY_CAPTION, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        if (revs.isEmpty()) {
            Text(RECORD_EDIT_HISTORY_EMPTY, color = Muted, fontSize = 12.sp)
        } else {
            revs.forEach { revision ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(recordRevisionEditorLine(revision), color = Muted, fontSize = 11.sp)
                    recordRevisionRows(revision.changes).forEach { row ->
                        Text(recordRevisionRowText(row), fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

/**
 * One "field: old → new" line.
 *
 * THE INK IS THE POINT. A value row is the web's pairing — the old struck through in the error
 * colour, the new in the success colour — because that is the vocabulary a reader already has for
 * "this was replaced by that". A REDACTED row gets neither: "(value recorded)" set in the error
 * colour with a line through it asserts that those two words were the old contents and were
 * deleted, which is false twice over. Muted italic says "this is the panel talking, not the record",
 * and it keeps saying it after the caption has scrolled off the top of a phone.
 */
@Composable
private fun recordRevisionRowText(row: RecordRevisionChangeRow): AnnotatedString {
    val fieldStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold
    )
    val oldStyle = if (row.redacted) {
        SpanStyle(color = Muted, fontStyle = FontStyle.Italic)
    } else {
        SpanStyle(color = MaterialTheme.colorScheme.error, textDecoration = TextDecoration.LineThrough)
    }
    val newStyle = if (row.redacted) {
        SpanStyle(color = Muted, fontStyle = FontStyle.Italic)
    } else {
        SpanStyle(color = MaterialTheme.field.success)
    }
    return buildAnnotatedString {
        withStyle(fieldStyle) { append(row.field) }
        withStyle(SpanStyle(color = Body)) { append(": ") }
        withStyle(oldStyle) { append(row.old) }
        withStyle(SpanStyle(color = Body)) { append(" → ") }
        withStyle(newStyle) { append(row.new) }
    }
}
