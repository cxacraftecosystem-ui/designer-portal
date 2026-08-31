package com.designprototype.workshop.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.designprototype.workshop.data.UsageClient
import com.designprototype.workshop.data.UsageNoticeDto
import com.designprototype.workshop.data.UsageNoticeStore
import kotlinx.coroutines.launch

/**
 * THE TERMS AND CONDITIONS, IN THE APP — where the long text at the sign-in door went.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS SCREEN EXISTS
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [UsageConsentDoor] used to carry the whole recording notice: a label, the server's
 * `requiredSentence` under it, and a disclosure that opened eight sections over a half-typed
 * password. It was the first thing a designer met and the last thing any of them read. The owner's
 * instruction on 2026-08-30 was to reduce the door to one line — "I agree to the terms and
 * conditions", the phrase underlined — which leaves exactly one question: where does the text go?
 *
 * Here. Nothing is deleted. Clause 10 renders [UsageNoticeBody], the same composable the settings
 * card uses, off the same versioned `GET /usage/consent/notice` payload, so not one sentence about
 * what is collected is written twice.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THE NINE CLAUSES ARE COPIED FROM THE WEB WORD FOR WORD, AND MAY NOT BE REWORDED HERE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `frontend/app/terms/page.tsx` carries the identical nine. This is a legal agreement and the tick
 * that accepts it is the same tick on both clients, so two wordings would not be an inconsistency —
 * they would be **two different agreements**, one of which a person accepted without ever seeing it.
 * The rule the rest of this app follows for shared vocabulary is that the SERVER owns it; these
 * clauses are not served, so the rule degrades to the next best thing: one text, copied, with this
 * paragraph above it. **If you change a clause, change `frontend/app/terms/page.tsx` in the same
 * commit.** [TERMS_CLAUSES] is a plain list of data precisely so the JVM suite can assert on it
 * without composing a screen.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * AND WHY IT IS A SCREEN IN THIS APP RATHER THAN A LINK TO THE WEB
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The handsets are used in courtyards with no signal. A browser intent to the web `/terms` from a
 * phone that cannot load it produces a blank tab and a person who has been asked to agree to
 * something they were then prevented from reading — which is worse than offering no link at all.
 * The nine clauses below are constants in this binary and need no network. Clause 10 does, and it
 * is the ONE section that can be missing; when it is, the other nine still render and that section
 * says so in a line, because blanking the terms on a failed sub-fetch would hide the agreement a
 * person came to read on the strength of a request that has nothing to do with most of it.
 */

// ---------------------------------------------------------------------------------------------
// The words
// ---------------------------------------------------------------------------------------------

/** One numbered clause. The number is carried as data, not baked into the title, so the list can be
 *  renumbered by inserting into [TERMS_CLAUSES] rather than by editing ten strings. */
data class TermsClause(val number: Int, val title: String, val body: String)

/** The screen's own title, and the words on the door's underlined link. Held apart from
 *  [USAGE_TERMS_LINK] — the link is mid-sentence and lower case, this is a heading. */
const val TERMS_TITLE: String = "Terms and conditions"

/** What agreeing means and where the tick is. Two sentences, because a person who arrived here from
 *  the door needs to know the box is what accepts them, and a person who arrived from the menu needs
 *  to know they already have. */
const val TERMS_INTRO: String =
    "These terms govern your use of the Design Prototype Workshop platform. Ticking the box on the " +
        "sign-in screen accepts them."

/**
 * THE NINE. Copied from `frontend/app/terms/page.tsx`; see this file's header before touching one.
 */
val TERMS_CLAUSES: List<TermsClause> = listOf(
    TermsClause(
        1,
        "Who may use the platform",
        "Access is by invitation. An administrator approves an account before it can sign in, and " +
            "may set, change or withdraw its tier at any time."
    ),
    TermsClause(
        2,
        "Your account",
        "An account belongs to one person. Keep your credentials to yourself, and tell an " +
            "administrator at once if you believe someone else has them. Every record, edit and " +
            "review is stored against the account that made it."
    ),
    TermsClause(
        3,
        "What you record",
        "Record only what you observed, and only with the knowledge of the artisans, groups and " +
            "institutions concerned. Photographs, recordings and identity details of other people " +
            "are entered on their behalf, so their agreement is yours to obtain before you enter them."
    ),
    TermsClause(
        4,
        "Identity numbers",
        "Aadhaar and Pehchan card numbers are stored masked and are never shown in lists, exports " +
            "or reports. Do not enter a regulated identity number anywhere other than the field " +
            "provided for it."
    ),
    TermsClause(
        5,
        "The material you enter",
        "Records, media and reports created here belong to the programme that commissioned the " +
            "workshop. You keep the right to be identified as their author, and your name travels " +
            "with them."
    ),
    TermsClause(
        6,
        "Offline use",
        "The Android app holds work on the device when there is no signal and sends it when there " +
            "is. Work held on a device is your responsibility until it has sent — do not uninstall " +
            "the app or clear its data while the outbox has entries in it."
    ),
    TermsClause(
        7,
        "Availability",
        "The platform is provided as it stands. Maintenance, releases and connectivity can " +
            "interrupt it, and no uptime is guaranteed."
    ),
    TermsClause(
        8,
        "Suspension",
        "An administrator may suspend or remove an account that is misused, shared, or used to " +
            "enter material the account holder had no right to enter."
    ),
    TermsClause(
        9,
        "Changes",
        "These terms and the recording notice below can change. The version you agreed to is " +
            "recorded against your account, so it is always possible to establish which words were " +
            "on screen when you agreed."
    ),
)

/** Clause 10 is the served one. Its number is derived so an inserted clause cannot leave two 10s. */
val TERMS_RECORDING_CLAUSE_NUMBER: Int get() = TERMS_CLAUSES.size + 1

/** Clause 10's heading. The web's, verbatim. */
const val TERMS_RECORDING_CLAUSE_TITLE: String = "How your use of the platform is recorded"

/**
 * WHAT CLAUSE 10 SAYS WHEN THE NOTICE IS NOT IN HAND, which is not an error page.
 *
 * It names the other place the same text lives, because the honest next move for somebody who wants
 * to read it and has no signal is "later, in Settings" and not "try harder now". It deliberately
 * does NOT say the terms could not be loaded: nine of the ten clauses are on the screen underneath
 * this sentence, and a reader told "the terms are unavailable" while looking at them stops trusting
 * either statement.
 */
const val TERMS_NOTICE_UNAVAILABLE_LINE: String =
    "The recording notice could not be read, so it is not shown here. Everything above still " +
        "applies. It is also in Settings once you have signed in."

// ---------------------------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------------------------

/**
 * The terms, scrolled, with the recording notice as the last clause.
 *
 * @param notice the notice to render as clause 10, or null when none is in hand. The DOOR passes the
 *   copy it will file the answer against, deliberately: a terms screen that fetched its own could
 *   show one version while the tick was recorded against another, which is the one way this feature
 *   could produce a signature against text nobody displayed.
 * @param onRetryNotice offered as a control rather than polled, for the reason the door's own retry
 *   was offered: a phone that was in a tunnel when the app opened is never asked again by a one-shot
 *   fetch, and a background loop on a screen nobody may be reading is a battery cost for nothing.
 *   Null hides the control — pass null where there is nothing useful to retry against.
 */
@Composable
fun TermsScreen(
    notice: UsageNoticeDto?,
    onRetryNotice: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            TERMS_INTRO,
            color = MaterialTheme.field.muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )

        TERMS_CLAUSES.forEach { clause ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The number rides IN the heading string rather than in a column of its own, so a
                // screen reader announces "1. Who may use the platform" as one heading instead of
                // reading a lone digit and then a title it cannot attach to anything.
                Text(
                    "${clause.number}. ${clause.title}",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    clause.body,
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.field.hairline)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$TERMS_RECORDING_CLAUSE_NUMBER. $TERMS_RECORDING_CLAUSE_TITLE",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            if (notice != null && notice.isUsable) {
                // THE SERVER'S TEXT, VERBATIM AND VERSIONED, through the same composable the
                // settings card renders. Not one sentence of it is written in Kotlin — see
                // `UsageCopy.kt`'s header for the boundary, and `services/usage.consent_notice`
                // for why writing it twice would be two different consents rather than a typo.
                UsageNoticeBody(notice)
            } else {
                Text(
                    TERMS_NOTICE_UNAVAILABLE_LINE,
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                onRetryNotice?.let { retry ->
                    TextButton(onClick = retry) { Text("Try again", fontSize = 13.sp) }
                }
            }
        }
    }
}

/**
 * The terms as a full-screen dialog over whatever the reader was on.
 *
 * A DIALOG AND NOT A ROUTE, and the reason is the sign-in screen: the door is drawn before there is
 * a token, a `NavController` or a `Screen`, so a route would be reachable from the menu and from
 * nowhere else — which is precisely the half of this feature that has to work. The system back
 * gesture dismisses it for free, and a person who opened the terms while half-way through typing a
 * password comes back to the password.
 */
@Composable
fun TermsDialog(
    notice: UsageNoticeDto?,
    onRetryNotice: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            // Named, because a bare "Close" on a screen reached from a sign-in form
                            // does not tell somebody what they are returning to.
                            contentDescription = "Close the terms and conditions",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        TERMS_TITLE,
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    )
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
                TermsScreen(notice = notice, onRetryNotice = onRetryNotice)
            }
        }
    }
}

/**
 * The terms reached from the menu, which has no door state in hand.
 *
 * It seeds from this device's stored copy on the first frame and then asks the server, in that
 * order and for the same reason `rememberUsageDoorState` does it in that order: a phone with no
 * signal shows the notice it already has instead of a spinner that will never resolve. It writes
 * back what it gets, so the next person to open the door on this handset has a notice to be asked
 * against even if they open it in a tunnel.
 *
 * It holds no `agreed` and files nothing. Reaching the terms from the menu is READING; the tick
 * lives at the door and on [UsageConsentGateScreen] and nowhere else, because a second control that
 * could record a consent would be a second place for the basis column to be wrong.
 */
@Composable
fun TermsMenuDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { UsageNoticeStore(context) }
    var notice by remember(context) { mutableStateOf(store.read()) }

    suspend fun load(appContext: Context) {
        val fresh = runCatching { UsageClient.of(appContext).consentNotice() }.getOrNull()
        if (fresh != null && fresh.isUsable) {
            notice = fresh
            store.write(fresh)
        }
    }

    LaunchedEffect(context) { load(context) }

    TermsDialog(
        notice = notice,
        onRetryNotice = { scope.launch { load(context) } },
        onDismiss = onDismiss
    )
}
