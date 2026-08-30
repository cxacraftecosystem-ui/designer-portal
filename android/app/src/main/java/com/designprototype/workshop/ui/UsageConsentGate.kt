package com.designprototype.workshop.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.USAGE_BASIS_REQUIRED_AT_SIGN_IN
import com.designprototype.workshop.data.USAGE_CONSENT_GRANTED
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.UsageClient
import com.designprototype.workshop.data.UsageConsentBody
import com.designprototype.workshop.data.UsageConsentStateDto
import com.designprototype.workshop.data.UsageNoticeDto
import com.designprototype.workshop.data.UsageNoticeStore
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.usageNowStamp
import kotlinx.coroutines.launch

/**
 * THE USAGE-RECORDING CONSENT, AT THE DOOR AND JUST INSIDE IT.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THE GATE IS IN TWO PLACES AND NOT ONE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The server deliberately does NOT refuse a sign-in for an unanswered consent, and its own route
 * says why: *"a 403 here would be unescapable — recording an answer needs a bearer token."* So the
 * blocking half belongs to the clients, and on this client it is two surfaces, for two different
 * reasons:
 *
 *  1. **[UsageConsentDoor]** — the checkbox on the sign-in screen. This is the requirement as it was
 *     asked for: a person agrees BEFORE they proceed. It gates both credentials, and the second one
 *     is the one that matters — see the note on `enabled` in [UsageConsentDoor].
 *
 *  2. **[UsageConsentGateScreen]** — a full screen that stands between sign-in and the dashboard
 *     while `usageConsentGate.required` is still true. It is what makes the gate ACTUALLY hold. The
 *     tick at the door is a promise this client makes; this screen is the enforcement, and it is
 *     reached in three genuine situations, none of them a corner case:
 *
 *       * the door could not show the notice at all ([UsageDoorPolicy.AskLater]);
 *       * the tick was taken and the `POST /usage/consent` that records it failed — a phone that
 *         had signal for the login and lost it a second later, which on this fleet is a Tuesday;
 *       * `usage.NOTICE_VERSION` moved since the account last agreed, so a session that was fine
 *         yesterday is asked again today, at the door it happens to walk through.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE ANSWER RECORDED AT THE DOOR IS NOT ALWAYS THE ANSWER SENT
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [usageAnswerAtTheDoor] sends a grant ONLY when the server says the account must be asked. A tick
 * taken at a door — before anybody knows whose account it is — must never overwrite an answer the
 * person gave freely in Settings. Concretely: somebody withdraws their consent on Monday, signs in
 * on Tuesday, ticks the box because the door requires it, and a client that posted unconditionally
 * would have silently converted their withdrawal into a fresh grant recorded as REQUIRED_AT_SIGN_IN.
 * That would make the withdrawal theatre, and the withdrawal being real is the entire reason the
 * turnstile is defensible in the first place. `consent_gate` reports `required: false` for a REFUSED
 * account precisely so a client does not put the question back in front of somebody who has already
 * answered it, and this is the client honouring that.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * NO COPY IS WRITTEN IN THIS FILE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Every sentence about what is collected comes off [UsageNoticeDto]. The strings this file does own
 * are in `UsageCopy.kt` and are about the CLIENT's state — what it is doing, what it could not
 * reach, what a person may do next. See that file's header for the boundary.
 */

// ---------------------------------------------------------------------------------------------
// The door's state
// ---------------------------------------------------------------------------------------------

/**
 * What the sign-in screen knows about the consent, and what the person has done about it.
 *
 * HOISTED OUT OF THE COMPOSABLE because the sign-in handler needs two of its fields AFTER the screen
 * has gone: the version that was actually on screen, and the moment the box was ticked. A checkbox
 * whose state died with its composable would leave the POST guessing at both, and "the moment they
 * agreed" would silently become "the moment the server heard", which is the one thing the two-clock
 * design exists to prevent.
 */
@Stable
class UsageDoorState internal constructor(private val store: UsageNoticeStore) {

    /** The notice on screen: the server's, or the copy this device kept. Null means neither. */
    var notice by mutableStateOf<UsageNoticeDto?>(null)
        private set

    /** A fetch is in flight and this device had nothing stored to show meanwhile. */
    var fetching by mutableStateOf(false)
        private set

    /** Ticked, and when. Both, because the POST needs the moment and not just the fact. */
    var agreed by mutableStateOf(false)
        private set

    var agreedAt by mutableStateOf<String?>(null)
        private set

    val policy: UsageDoorPolicy
        get() = usageDoorPolicy(noticeReady = notice?.isUsable == true, stillFetching = fetching)

    /** Sign-in may proceed. TRUE under [UsageDoorPolicy.AskLater] on purpose — the enforcement moves
     *  to [UsageConsentGateScreen] rather than disappearing. See [UsageDoorPolicy]. */
    val mayProceed: Boolean
        get() = when (policy) {
            UsageDoorPolicy.Blocking -> agreed
            UsageDoorPolicy.AskLater -> true
            UsageDoorPolicy.Waiting -> false
        }

    /** Named `agree` and not `setAgreed`: the latter is the JVM name Kotlin already
     *  generates for [agreed]'s private setter, and declaring both is a signature clash. */
    fun agree(next: Boolean) {
        agreed = next
        // Stamped on the TICK and cleared on the untick. Reading the clock at POST time instead
        // would record the moment the network came back, which on a phone that agreed in a courtyard
        // and signed in on the bus is a different hour and sometimes a different day.
        agreedAt = if (next) usageNowStamp() else null
    }

    /**
     * FORGET THE TICK. Called on sign-out, and it is not housekeeping.
     *
     * This state outlives [UsageConsentDoor] on purpose, so without this the box stays ticked across
     * a sign-out — and the handsets in this fleet are shared. The failure is the one
     * `WorkshopRepository.logout` was rewritten for, in a worse register: the second designer in the
     * cluster signs in, meets a consent box somebody else ticked, and this client posts a GRANTED
     * against THEIR account carrying the FIRST person's tick moment. A consent recorded for a person
     * who never saw the question is not a smaller kind of wrong.
     *
     * The notice itself is kept — it is published text about the policy, holds nobody's answer, and
     * is what lets the next person be asked at all on a phone with no signal.
     */
    fun reset() {
        agreed = false
        agreedAt = null
    }

    internal fun seedFromDevice() {
        if (notice == null) notice = store.read()
    }

    /** Ask the server for the notice again. Public so the door can offer a retry to somebody
     *  who was offline when this screen opened and has since found a signal. */
    suspend fun refresh(context: Context) {
        fetching = notice == null
        val fresh = runCatching { UsageClient.of(context).consentNotice() }.getOrNull()
        if (fresh != null && fresh.isUsable) {
            // A NEW VERSION UNTICKS THE BOX. Somebody who ticked against the cached text and then
            // received a different one has not agreed to what is now on screen, and carrying the tick
            // across would record them as having agreed to text they never saw.
            if (fresh.version != notice?.version) agree(false)
            notice = fresh
            store.write(fresh)
        }
        fetching = false
    }
}

/**
 * The door's state, seeded synchronously from this device's stored copy and then refreshed.
 *
 * SEEDED FIRST, FETCHED SECOND, and the order is the point: a phone with no signal shows the notice
 * it already has on the first frame instead of a spinner that will never resolve, which is what lets
 * the gate block honestly offline rather than either locking the person out or waving them through.
 */
@Composable
fun rememberUsageDoorState(): UsageDoorState {
    val context = LocalContext.current
    val state = remember(context) { UsageDoorState(UsageNoticeStore(context)).also { it.seedFromDevice() } }
    LaunchedEffect(state) { state.refresh(context) }
    return state
}

// ---------------------------------------------------------------------------------------------
// Recording the answer once a token exists
// ---------------------------------------------------------------------------------------------

/** True when this account may not use the product until it answers. Reads the server's boolean and
 *  computes nothing; a null gate is a deployment older than the flow and gates nothing. */
fun usageConsentBlocks(user: UserDto?): Boolean = user?.usageConsentGate?.required == true

/**
 * Record the answer given at the door, if one was given and if it is still wanted.
 *
 * Returns the account with its gate brought up to date — or [user] untouched, which is the honest
 * outcome for every path that did not write: the gate stays required and
 * [UsageConsentGateScreen] asks properly, one screen later, where the person can retry.
 *
 * NEVER THROWS. It is called on the sign-in path, where an exception would turn "your consent did
 * not reach the server" into "sign-in failed" — sending somebody to reset a password over a
 * checkbox.
 */
suspend fun usageAnswerAtTheDoor(
    context: Context,
    repository: WorkshopRepository,
    user: UserDto,
    door: UsageDoorState,
): UserDto {
    val gate = user.usageConsentGate ?: return user
    // NOT ASKED FOR, SO NOT SENT. See this file's header: a tick taken before anybody knew whose
    // account it was must not overwrite a refusal made freely in Settings.
    if (!gate.required) return user
    if (!door.agreed) return user
    val version = door.notice?.version?.takeIf { it.isNotBlank() } ?: return user

    val recorded = runCatching {
        UsageClient.of(context).recordConsent(
            UsageConsentBody(
                decision = USAGE_CONSENT_GRANTED,
                basis = USAGE_BASIS_REQUIRED_AT_SIGN_IN,
                // WHAT THIS SCREEN ACTUALLY SHOWED, which on a phone holding a cached notice is not
                // necessarily what the server publishes today. The server stores it verbatim rather
                // than refusing it, so the record says which text the person read.
                noticeVersion = version,
                recordedAt = door.agreedAt,
            )
        )
    }.getOrNull() ?: return user

    // Re-read the account so the DEVICE's cached profile carries the closed gate too. Without this a
    // relaunch with no signal would restore yesterday's cached user, see `required = true`, and put
    // the gate screen in front of somebody who has already agreed — on a phone that cannot reach the
    // server to prove it.
    return runCatching { repository.refreshUser() }
        .getOrElse { user.copy(usageConsentGate = recorded.gate, usageConsent = recorded.consent.state) }
}

// ---------------------------------------------------------------------------------------------
// The notice, drawn
// ---------------------------------------------------------------------------------------------

/**
 * The whole notice, in the order the server publishes it. **Nothing here is summarised or reordered.**
 *
 * The order is not cosmetic: what is collected, then what is not, then that agreeing is required. A
 * person who reads two paragraphs of reassurance and only then discovers the choice was not a choice
 * has been handled rather than asked, so the requirement is stated plainly and early rather than
 * implied by a disabled button further down.
 *
 * EVERY LINE IS AN ORDINARY [Text], which is what makes it TalkBack-readable: a screen reader walks
 * them in order, and nothing here is drawn into an image, a canvas or a `contentDescription`.
 */
@Composable
fun UsageNoticeBody(notice: UsageNoticeDto, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NoticeSection("What is recorded", notice.collects)
        NoticeSection("What is never recorded", notice.doesNotCollect)

        if (notice.requiredSentence.isNotBlank()) {
            // THE LOUDEST THING IN THE NOTICE, and drawn as such. It is the sentence that says the
            // answer is a condition of access — the fact a person is most likely to be given by
            // implication and most entitled to be told outright.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, MaterialTheme.shapes.medium)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "This is required",
                    display = true,
                    color = MaterialTheme.field.onWarningContainer,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(notice.requiredSentence, color = MaterialTheme.field.onWarningContainer, fontSize = 13.sp)
            }
        }

        if (notice.durationCaveat.isNotBlank()) {
            NoticeParagraph("What \"how long it took\" is not", notice.durationCaveat)
        }

        if (notice.readableBy.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Who can read it",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                // Keyed by route on the wire. Printed as "route — who", because the route name is
                // what a reader can go and check, and dropping it would leave four promises about
                // "aggregates" with nothing to attach them to.
                notice.readableBy.forEach { (route, who) ->
                    Text("$route — $who", color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Taking it back",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            if (notice.withdrawal.where.isNotBlank()) {
                Text(notice.withdrawal.where, color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (notice.withdrawal.costsNothing.isNotBlank()) {
                Text(
                    notice.withdrawal.costsNothing,
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            notice.withdrawal.does.forEach { Bullet(it) }
            notice.withdrawal.doesNot.forEach { Bullet(it) }
        }

        if (notice.retention.isNotBlank()) {
            NoticeParagraph("How long it is kept", notice.retention)
        }

        Text(
            "Notice version ${notice.version}" +
                (notice.document.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun NoticeSection(heading: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            heading,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        lines.forEach { Bullet(it) }
    }
}

@Composable
private fun NoticeParagraph(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            heading,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Text(body, color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/**
 * One bullet. The marker is part of the STRING and not a drawn dot, so a screen reader reads the
 * line as a line instead of skipping an unlabelled shape and starting mid-sentence.
 */
@Composable
private fun Bullet(text: String) {
    Text("• $text", color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 18.sp)
}

/**
 * The notice behind a disclosure. Used where the notice is not the only thing on the screen.
 *
 * **THE HEADER IS A REAL BUTTON WITH A REAL STATE**, not a `Row` that happens to be clickable:
 * `Role.Button` is what makes TalkBack say "double tap to activate", and `stateDescription` is what
 * makes it say "Expanded" or "Collapsed" — without which a blind reader is told a control exists and
 * never told whether pressing it opened anything. `DesignReviewScreen` sets the same pair on its own
 * ledger disclosure and says so for the same reason.
 */
@Composable
fun UsageNoticeDisclosure(
    notice: UsageNoticeDto,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                USAGE_NOTICE_EXPANDER,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                // Null: the row above already carries the label and the state. A second announcement
                // here would have TalkBack read the control twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) UsageNoticeBody(notice)
    }
}

// ---------------------------------------------------------------------------------------------
// The door
// ---------------------------------------------------------------------------------------------

/**
 * The consent block on the sign-in screen: the required tick, the notice behind it, and — when
 * sign-in is blocked — the reason, announced.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * ACCESSIBILITY IS THE DESIGN HERE, NOT A PASS OVER IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 *  * **A real checkbox.** The whole row is `toggleable` with `Role.Checkbox` and the [Checkbox]
 *    itself takes `onCheckedChange = null`. That gives one tap target with one announcement — "not
 *    ticked, checkbox, double tap to toggle" — instead of a label and a box announced separately,
 *    where the box is a 20dp target beside a three-line sentence.
 *  * **The blocked reason is announced.** A disabled `Button` is read as "disabled" and nothing
 *    more; [usageSignInBlockedReason] is drawn in a polite live region so the reason is SPOKEN when
 *    it changes, and it is on screen for everybody else too.
 *  * **It survives the largest font scale.** Nothing here has a fixed height, the tick row is a
 *    `Row` with an `Alignment.Top` checkbox beside a weighted `Column`, and the whole sign-in screen
 *    is already in a `verticalScroll`. At 200% with "Larger text" also on, the notice grows
 *    downwards and the person scrolls; nothing clips and nothing overlaps.
 */
@Composable
fun UsageConsentDoor(
    door: UsageDoorState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    /*
     * READ HERE AND NOT PASSED IN, which is the opposite of what the two usage screens do and is
     * right for the opposite reason. A value handed down from the sign-in host is computed when the
     * HOST recomposes, and the host does not recompose while somebody types an email or taps this
     * card's retry — so a phone that was in a tunnel when the app opened would go on being told it
     * has no connection for the whole session. Read at composition, this is fresh every time the
     * door redraws, which includes every retry.
     *
     * IT IS NOT A SECOND IDEA OF "OFFLINE". `WorkshopRepository.isOnline` is a one-line forward to
     * this same function; this composable simply has no repository to reach through.
     */
    val online = ConnectivityObserver.isOnline(context)
    var expanded by remember { mutableStateOf(false) }
    val notice = door.notice
    val blocked = usageSignInBlockedReason(door.policy, door.agreed, online)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (notice != null) {
            if (notice.title.isNotBlank()) {
                Text(
                    notice.title,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = door.agreed,
                        role = Role.Checkbox,
                        onValueChange = { door.agree(it) }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Decoration: the row owns the gesture. A clickable box here would be announced as a
                // second control on top of the row that already announces itself.
                Checkbox(checked = door.agreed, onCheckedChange = null)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        USAGE_AGREE_LABEL,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (notice.requiredSentence.isNotBlank()) {
                        // The SERVER's sentence, under the label, unopened. The requirement must not
                        // be something a person only meets by expanding a disclosure.
                        Text(
                            notice.requiredSentence,
                            color = MaterialTheme.field.muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
            UsageNoticeDisclosure(
                notice = notice,
                expanded = expanded,
                onExpandedChange = { expanded = it }
            )
        } else if (door.policy == UsageDoorPolicy.AskLater) {
            Text(
                USAGE_ASK_LATER_LINE,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            // OFFERED RATHER THAN POLLED. A phone that was in a tunnel when this screen opened will
            // never be asked again by the one-shot fetch in `rememberUsageDoorState`, and a
            // background retry loop on a sign-in screen is a battery drain for a question nobody may
            // be about to answer. So the person who has just walked outside gets a control, and the
            // person who has not is not charged for it.
            TextButton(onClick = { scope.launch { door.refresh(context) } }) {
                Text("Read the notice now", fontSize = 12.sp)
            }
        }

        if (blocked != null) {
            Text(
                blocked,
                // POLITE and not ASSERTIVE: the reader is mid-form, typing an email, and an
                // assertive region interrupts them at every keystroke that changes the state.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The screen that actually holds the gate
// ---------------------------------------------------------------------------------------------

/**
 * BETWEEN SIGN-IN AND THE PRODUCT, while this account still owes an answer.
 *
 * ── WHY IT HAS A WAY OUT ──────────────────────────────────────────────────────────────────────
 *
 * "Sign out instead" is not a hedge. Without it, a person who genuinely cannot agree — or a phone
 * that cannot reach the server to record an agreement — is held on one screen with no controls that
 * do anything, which is a lockout wearing the costume of a question. Signing out returns them to a
 * door they can use with a different account, which is the same escape the sign-in screen's own
 * refusal panel deliberately leaves open ("the person may hold a second, unaffected account").
 *
 * ── AND WHY IT ASKS THE SERVER RATHER THAN TRUSTING THE ACCOUNT IT WAS HANDED ──────────────────
 *
 * `GET /usage/consent` returns the gate, the notice, the stored answer AND the decision log in one
 * call. Rendering the notice from that payload rather than from the door's copy means the text on
 * the screen where the answer is actually recorded came from the same request that said an answer
 * was needed — so the version sent back is provably the version shown. It also gives this screen a
 * SECOND source for the notice: a deployment where `GET /usage/consent/notice` is broken but the
 * rest of the API is fine still asks the question here, correctly, which is what makes
 * [UsageDoorPolicy.AskLater] a safe fallback rather than a hole.
 */
@Composable
fun UsageConsentGateScreen(
    repository: WorkshopRepository,
    user: UserDto,
    onSatisfied: (UserDto) -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read here rather than passed down, for the reason [UsageConsentDoor] states: this screen's own
    // "Try again" does not recompose the sign-in host, so a value handed in would keep telling
    // somebody who has just walked outside that their phone has no connection. Same function the
    // repository forwards to, so it is not a second idea of offline.
    val online = ConnectivityObserver.isOnline(context)
    var state by remember { mutableStateOf<UsageConsentStateDto?>(null) }
    var read by remember { mutableStateOf<UsageReadState>(UsageReadState.Loading) }
    var agreed by remember { mutableStateOf(false) }
    var agreedAt by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        read = UsageReadState.Loading
        val answer = runCatching { UsageClient.of(context).myConsent() }.getOrNull()
        if (answer == null) {
            read = UsageReadState.Failed
            return
        }
        state = answer
        // Keep the notice, so that if this account signs out and the next person opens the app with
        // no signal, the door can still ask. It is public text and holds nobody's answer.
        answer.notice?.let { UsageNoticeStore(context).write(it) }
        read = UsageReadState.Answered(count = 1)
        // The gate may have closed while this screen was opening — somebody answering on the web, or
        // a door POST that landed after the screen was pushed. Leave rather than ask a second time.
        if (!answer.gate.required) onSatisfied(user.copy(usageConsentGate = answer.gate))
    }

    LaunchedEffect(Unit) { load() }

    val notice = state?.notice

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            notice?.title?.takeIf { it.isNotBlank() } ?: "Recording how you use this platform",
            display = true,
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        // The SERVER's sentence for the state this account is actually in. Three states reach three
        // different sentences — nobody has asked, the text has changed since you agreed, you have
        // declined — because the next moves differ, and a single "please agree" would be false for
        // two of the three.
        state?.gate?.reason?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 19.sp)
        }

        when {
            read is UsageReadState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(loadingListLine("recording notice"), color = MaterialTheme.field.muted, fontSize = 13.sp)
            }

            read is UsageReadState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (online) usageCouldNotReadLine("recording notice") else usageOfflineLine("recording notice"),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Button(onClick = { scope.launch { load() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
            }

            notice != null -> ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // EXPANDED, not behind a disclosure. This screen has nothing else on it, so
                    // hiding the text a person is agreeing to behind one more tap would be asking for
                    // a signature on a folded page.
                    UsageNoticeBody(notice)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = agreed,
                                role = Role.Checkbox,
                                onValueChange = {
                                    agreed = it
                                    agreedAt = if (it) usageNowStamp() else null
                                }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(checked = agreed, onCheckedChange = null)
                        Text(
                            USAGE_AGREE_LABEL,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (!agreed) {
                        Text(
                            "You cannot continue until you tick the box.",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.field.muted,
                            fontSize = 12.sp
                        )
                    }
                    sendError?.let {
                        Text(
                            it,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }

                    Button(
                        enabled = agreed && !sending,
                        onClick = {
                            scope.launch {
                                sending = true
                                sendError = null
                                runCatching {
                                    UsageClient.of(context).recordConsent(
                                        UsageConsentBody(
                                            decision = USAGE_CONSENT_GRANTED,
                                            basis = USAGE_BASIS_REQUIRED_AT_SIGN_IN,
                                            noticeVersion = notice.version,
                                            recordedAt = agreedAt,
                                        )
                                    )
                                }.onSuccess { recorded ->
                                    val refreshed = runCatching { repository.refreshUser() }
                                        .getOrElse {
                                            user.copy(
                                                usageConsentGate = recorded.gate,
                                                usageConsent = recorded.consent.state
                                            )
                                        }
                                    onSatisfied(refreshed)
                                }.onFailure { failure ->
                                    // THE SERVER'S OWN SENTENCE WHERE THERE IS ONE, and the reason is
                                    // a specific lockout: `POST /usage/consent` refuses a
                                    // `recordedAt` more than fifteen minutes in the future with a 422
                                    // naming the clock. A handset whose date is wrong would otherwise
                                    // sit on this screen being told "try again" for ever, retrying a
                                    // request that cannot succeed until somebody fixes something this
                                    // message never mentioned. `apiErrorMessage` reads the buffered
                                    // body ONCE — never ask the same failure twice.
                                    sendError = failure.apiErrorMessage(
                                        if (online) {
                                            "Your answer did not reach the server, so it has not " +
                                                "been recorded. Try again."
                                        } else {
                                            "This phone has no connection, so your answer was not " +
                                                "sent and nothing has been recorded. Try again " +
                                                "where there is a signal."
                                        }
                                    )
                                }
                                sending = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (sending) "Recording your answer…" else "Agree and continue")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out instead")
        }
        TextButton(
            onClick = { scope.launch { load() } },
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape
        ) {
            Text("Reload this question", fontSize = 12.sp)
        }
    }
}
