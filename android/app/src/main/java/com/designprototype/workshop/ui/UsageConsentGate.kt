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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
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
 *       * the door could not read the notice, so the tick was taken but nothing could be FILED
 *         against a version — see [UsageDoorState.mayProceed] for what that used to do instead;
 *       * the tick was taken and the `POST /usage/consent` that records it failed — a phone that
 *         had signal for the login and lost it a second later, which on this fleet is a Tuesday;
 *       * `usage.NOTICE_VERSION` moved since the account last agreed, so a session that was fine
 *         yesterday is asked again today, at the door it happens to walk through.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THE AGREEMENT IS TO, SINCE 2026-08-30
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The tick agrees to the TERMS AND CONDITIONS, not to the recording notice on its own. [TermsScreen]
 * carries nine clauses compiled into this binary plus the served notice as clause 10, so the whole
 * agreement can be read on a phone with no signal, and the door is one line with an underlined link.
 * The web made the identical change on the same day (`frontend/app/terms/page.tsx`), and §16 of the
 * frontend reference is why the two had to move together: the sign-in card and this screen are built
 * against one description.
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

    /** A usable notice is in hand — the server's, or the copy this device kept. It decides only what
     *  the answer can be FILED against; it has no vote on whether sign-in may proceed. */
    val noticeReady: Boolean
        get() = notice?.isUsable == true

    /**
     * SIGN-IN MAY PROCEED. The tick, and nothing else.
     *
     * ── WHAT THIS USED TO BE, AND WHY IT IS NOT THAT ANY MORE ─────────────────────────────────
     *
     * It used to be a three-way over a `UsageDoorPolicy`: the tick was required only where a notice
     * had arrived, sign-in WAITED while the first fetch was in flight, and where no notice could be
     * had from any source the door let the person through unasked and moved the enforcement to
     * [UsageConsentGateScreen]. Every arm of that existed to avoid one failure — a checkbox whose
     * text never arrives is a permanently disabled button on the one screen with no other controls,
     * i.e. a fleet-wide lockout out of one bad deploy of `GET /usage/consent/notice`.
     *
     * That failure cannot happen any more, and the reason is [TermsScreen]: since 2026-08-30 the box
     * agrees to the terms and conditions, which are constants compiled into this binary. The
     * question can always be put and can always be answered with no network at all, so the escape
     * hatch is a net for a fall that no longer exists — and leaving it in would be a front door that
     * a dead endpoint walks straight through. `frontend/app/login/page.tsx` made the identical
     * change on the same day and states it in one clause: `blocked = !agreed`.
     *
     * **The requirement is not weakened and the notice is not abandoned.** It is still fetched,
     * because [usageAnswerAtTheDoor] files the answer against its version; when it is missing
     * nothing is filed, [USAGE_NOTICE_NOT_FILED_LINE] says so under the box, the server's gate goes
     * on reading `required`, and [UsageConsentGateScreen] asks again one screen later. What moved is
     * the FILING, never the asking.
     */
    val mayProceed: Boolean
        get() = agreed

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

    /**
     * Ask the server for the notice again.
     *
     * Public so a RETRY can be offered to somebody who was offline when the app opened and has since
     * found a signal. That control used to sit on the door; it now sits under clause 10 of
     * [TermsScreen], which is the only place on either client where a missing notice is visible as
     * an absence — the door no longer shows the notice at all, so a retry button there would have
     * been a control for a problem the reader could not see.
     */
    suspend fun refresh(context: Context) {
        fetching = notice == null
        val fresh = runCatching { UsageClient.of(context).consentNotice() }.getOrNull()
        if (fresh != null && fresh.isUsable) {
            // A NEW VERSION UNTICKS THE BOX. Somebody who ticked against the cached text and then
            // received a different one has not agreed to what is now on screen, and carrying the tick
            // across would record them as having agreed to text they never saw.
            //
            // STILL TRUE NOW THAT THE DOOR SHOWS NO NOTICE, and worth saying because it looks like it
            // should not be: the notice is clause 10 of the terms this box agrees to, and
            // [TermsScreen] renders the very copy held here. So a version that moved under the tick
            // moved the agreement, whether or not the reader had the terms open at the time.
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
// The one line the agreement is made on
// ---------------------------------------------------------------------------------------------

/**
 * THE TICK AND THE LINK, shared by the door and by [UsageConsentGateScreen] so the two screens
 * cannot ask for the agreement in two different sentences.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT IS TWO CONTROLS AND NOT ONE ROW
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The whole row used to be one `toggleable` with `Role.Checkbox`, which is the right shape for a
 * checkbox beside a label and the WRONG shape the moment part of that label has to be tappable: a
 * `toggleable` ancestor consumes the tap, so a link drawn inside it would tick the box instead of
 * opening the terms — silently, and only ever for the reader who wanted to read before agreeing.
 *
 * So the tick target ends where the link begins. The reader gets two announcements, "not ticked,
 * checkbox" and "terms and conditions, button", which is what a person navigating with TalkBack
 * needs anyway: one control to agree, one to read what they are agreeing to, told apart by role
 * rather than by remembering which half of a sentence was underlined.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE OTHER ACCESSIBILITY RULINGS, UNCHANGED
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 *  * **A real checkbox.** The [Checkbox] takes `onCheckedChange = null` and the row around it owns
 *    the gesture, so the tick is one target with one announcement instead of a 20dp box beside a
 *    label announced separately.
 *  * **The link is underlined AND coloured.** Colour alone is a signal a colour-blind reader never
 *    receives, and on a phone there is no hover to reveal it either.
 *  * **It survives the largest font scale.** Nothing here has a fixed height and the link takes the
 *    remaining width with `weight`, so at 200% the phrase wraps downwards instead of clipping. The
 *    sign-in screen is already in a `verticalScroll`.
 */
@Composable
private fun UsageAgreeRow(
    agreed: Boolean,
    onAgree: (Boolean) -> Unit,
    onOpenTerms: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.toggleable(
                value = agreed,
                role = Role.Checkbox,
                onValueChange = onAgree
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Decoration: the row owns the gesture. A clickable box here would be announced as a
            // second control on top of the row that already announces itself.
            Checkbox(checked = agreed, onCheckedChange = null)
            Text(
                USAGE_AGREE_LABEL,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = fontSize
            )
        }
        Text(
            USAGE_TERMS_LINK,
            modifier = Modifier
                .weight(1f)
                .clickable(
                    role = Role.Button,
                    // Spoken instead of the bare phrase, because "terms and conditions, button"
                    // does not say what the button DOES, and on this screen the reader is deciding
                    // whether pressing it will lose the password they have half typed.
                    onClickLabel = "Read the terms and conditions"
                ) { onOpenTerms() }
                // The tap target, not decoration: 14sp of text is about 20dp tall, and 48dp is the
                // minimum a thumb can aim at. The Checkbox beside it already enforces its own.
                .padding(vertical = 14.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The door
// ---------------------------------------------------------------------------------------------

/**
 * The consent block on the sign-in screen: one line, and — when sign-in is blocked — the reason,
 * announced.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SCREEN USED TO CARRY, AND WHERE IT WENT
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The notice's title, a three-clause label, the server's `requiredSentence` printed under it, and a
 * disclosure that opened eight sections of served text — all of it over a half-typed password in a
 * card 360dp wide. The owner's ruling on 2026-08-30 was that nobody reads it there, and the web's
 * `ConsentGateField` was cut to one line the same day. This is the handset's half of that change.
 *
 * Nothing was deleted. Every one of those sentences is now clause 10 of [TermsScreen], rendered by
 * the same [UsageNoticeBody] the settings card uses, off the same payload, still versioned, still
 * the version the answer is filed against. What changed is that a person who wants to read it goes
 * to a screen built for reading.
 *
 * **THE LINK OPENS AN IN-APP SCREEN AND MUST NEVER OPEN A BROWSER.** These handsets are used where
 * there is no signal; a browser intent to the web terms page would hand somebody a blank tab and
 * ask them to agree to it. See [TermsScreen]'s header.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE BLOCKED REASON IS STILL ANNOUNCED
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A disabled `Button` is read by TalkBack as "disabled" and nothing more, so [usageDoorHint] is
 * drawn in a polite live region beside the buttons: the reason is SPOKEN when it changes, and it is
 * on screen for everybody else too. That was the argument for the old blocked-reason line and it is
 * unchanged; only the sentences are shorter.
 */
@Composable
fun UsageConsentDoor(
    door: UsageDoorState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    /*
     * STATE HERE AND NOT HOISTED, unlike the tick beside it. Whether the terms are open is a fact
     * about this screen and dies with it, which is exactly right: a person who read the terms and
     * came back has not thereby agreed to anything, and nothing after sign-in needs to know they
     * looked. `door.agreed` is hoisted because the POST genuinely needs it after this screen is gone.
     */
    var showTerms by remember { mutableStateOf(false) }
    val hint = usageDoorHint(
        agreed = door.agreed,
        noticeReady = door.noticeReady,
        stillFetching = door.fetching
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        UsageAgreeRow(
            agreed = door.agreed,
            onAgree = { door.agree(it) },
            onOpenTerms = { showTerms = true }
        )
        if (hint != null) {
            Text(
                hint,
                // POLITE and not ASSERTIVE: the reader is mid-form, typing an email, and an
                // assertive region interrupts them at every keystroke that changes the state.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }

    if (showTerms) {
        TermsDialog(
            // THE COPY THE ANSWER WILL BE FILED AGAINST, handed over rather than fetched again. A
            // terms screen that asked for its own could show one version while
            // [usageAnswerAtTheDoor] recorded another — a signature against text nobody displayed,
            // which is the single failure the version column exists to make impossible.
            notice = door.notice,
            // OFFERED RATHER THAN POLLED. A phone that was in a tunnel when the app opened will
            // never be asked again by the one-shot fetch in [rememberUsageDoorState], and a
            // background retry loop on a sign-in screen is a battery drain for a question nobody may
            // be about to answer. So the person who has just walked outside gets a control, and the
            // person who has not is not charged for it.
            onRetryNotice = { scope.launch { door.refresh(context) } },
            onDismiss = { showTerms = false }
        )
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
 * call. Taking the notice from that payload rather than from the door's copy means the version this
 * screen files the answer against came from the same request that said an answer was needed. It also
 * gives this screen a SECOND source for the notice: a deployment where `GET /usage/consent/notice`
 * is broken but the rest of the API is fine still asks the question here, correctly, and files it.
 *
 * ── THE NOTICE IS NO LONGER PRINTED ON THIS SCREEN, AND THAT IS A CHANGE OF ADDRESS ────────────
 *
 * It used to render [UsageNoticeBody] in full, expanded, with the argument: *"This screen has
 * nothing else on it, so hiding the text a person is agreeing to behind one more tap would be asking
 * for a signature on a folded page."* That argument was right about the notice being the agreement.
 * Since 2026-08-30 the agreement is the TERMS, the notice is clause 10 of them, and both clients ask
 * for it in one line with the phrase linked. So the text is one tap away in [TermsScreen] — the same
 * one tap it is at the door — and this screen asks the same question in the same words rather than
 * being the one place in the product that asks it differently.
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
    // Local, and it dies with the screen: reading the terms is not answering, and nothing after this
    // screen needs to know somebody looked. Same ruling as [UsageConsentDoor]'s own `showTerms`.
    var showTerms by remember { mutableStateOf(false) }

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
                    // THE SAME ROW AS THE DOOR, from the same composable. Two surfaces that ask for
                    // one agreement must ask for it in one sentence — a person who ticked "I agree
                    // to the terms and conditions" at the door and then met a differently worded box
                    // here would reasonably conclude they were being asked for something else.
                    UsageAgreeRow(
                        agreed = agreed,
                        onAgree = {
                            agreed = it
                            // Stamped on the TICK, not at the POST, for the reason `UsageDoorState`
                            // states: the two are separated by a network this fleet often does not
                            // have, and the server already stamps its own `createdAt`.
                            agreedAt = if (it) usageNowStamp() else null
                        },
                        onOpenTerms = { showTerms = true },
                        fontSize = 15.sp
                    )

                    if (!agreed) {
                        Text(
                            USAGE_TICK_TO_CONTINUE,
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

    if (showTerms) {
        TermsDialog(
            // This screen's own copy, from `GET /usage/consent` — the same payload the answer below
            // is filed against, so the version read and the version recorded cannot diverge.
            notice = notice,
            // `load()` and not `door.refresh`: on this screen the notice arrives with the gate, and
            // re-reading the one without the other would leave clause 10 fresh and the reason line
            // above it stale.
            onRetryNotice = { scope.launch { load() } },
            onDismiss = { showTerms = false }
        )
    }
}
