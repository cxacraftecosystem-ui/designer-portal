package com.designprototype.workshop.ui

import com.designprototype.workshop.data.apiErrorMessage
import retrofit2.HttpException

/**
 * WHAT THE WORKSHOP-ACCESS QUEUE SAYS WHEN IT IS SHOWING NOTHING — and the rule that "nobody asked"
 * and "you were not shown it" may never be the same sentence.
 *
 * ── THE DEFECT THIS CLOSES ───────────────────────────────────────────────────────────────────────
 *
 * `WorkshopAccessQueueCard` (MainActivity) loads `GET /workshops/access-requests` with
 * `runCatching { … }.onSuccess { rows = it }.onFailure { … }`, and USED TO decide what to draw
 * from `rows.isEmpty()` alone. Three completely different situations therefore produced ONE screen
 * reading "Nothing waiting — the queue is clear":
 *
 *  1. the server answered, and nobody has asked for access;
 *  2. the server answered 403, because this account may not read the cross-workshop queue at all;
 *  3. nothing answered — no signal, a captive portal, a 502 from the gateway — so the queue was
 *     never actually asked.
 *
 * Only the first of those is "clear". In the other two, requests may be piling up while an approver
 * is being told, in as many words, that there is nothing to do. The message `onError` raises is not
 * a defence: `showMessage` parks ONE line at the foot of the whole scrolling page, below every card
 * on it, so the sentence sitting where the queue should be is the one that gets read. That is this
 * repo's rule stated exactly: an error must be actionable and DISTINGUISHABLE, and "Something went
 * wrong" — or worse, "all clear" — is a defect.
 *
 * ── WHERE THIS IS MOUNTED ────────────────────────────────────────────────────────────────────────
 *
 * `WorkshopAccessQueueCard` now keeps the classified [WorkshopAccessQueueFailure] in state, clears
 * it on a successful load, and draws [workshopAccessQueueNotice] above the list — an ordinary line
 * for an answered-and-empty queue, a filled amber panel for the two that were never answered. That
 * card is the ONE production caller. `grep -rn "workshopAccessQueueNotice" android/app/src/main`
 * returned six lines when this was written (2026-08-27): MainActivity's import, its single CALL,
 * the comment above that call, and this file's own declaration plus two prose mentions — one call
 * site and no second one. No other surface may re-derive these sentences.
 *
 * ── THE PRECEDENT THIS FOLLOWS, DELIBERATELY ─────────────────────────────────────────────────────
 *
 * `secretsRefusedTheAccount` / `ApiKeysRestrictedCard` in [ApiKeysScreen] already split "the server
 * refused this account" from "the server could not be reached" for `/secrets`, and
 * `ApiKeysAccessTest` pins the split because it is invisible by inspection. This is the same split
 * for the same reason on a different route, so it is spelled the same way: 403 is a STATE, every
 * other failure is a FAILURE, and neither one is an empty list.
 *
 * ── WHY 403 IS REACHABLE AT ALL, GIVEN THE TILE IS ALREADY GATED ─────────────────────────────────
 *
 * The admin hub only offers this card when `isAdmin` is true, and that flag is read from the CACHED
 * user (`MainActivity.isAdminUser()`), fetched when the session began. A handset in this fleet can
 * go a fortnight between updates and a good while between sign-ins, so "my role was changed on the
 * server after I signed in" is an ordinary state here, not a contrived one. The client gate is never
 * the real gate: `GET /workshops/access-requests` and `POST /workshops/access-requests/{id}/decide`
 * are both `require_admin` (backend/app/api/routes/workshops.py:586 and :605 — checked 2026-08-27;
 * re-check with `grep -n "access-requests" backend/app/api/routes/workshops.py`), which is
 * `{ADMIN, MASTER_ADMIN}` and nobody else — not a PROFESSOR, and not the workshop's creator. So the
 * server can still refuse an account this screen let in, and when it does the screen has to say so
 * rather than call it calm.
 *
 * ── WHY PLAIN FUNCTIONS IN A FILE OF THEIR OWN ───────────────────────────────────────────────────
 *
 * No Compose and no Android framework, for the same reason as [accessRefusalChrome]: the whole
 * feature IS the words, and a `when` block inside a composable cannot be reached from a JVM test.
 * `WorkshopAccessQueueCopyTest` walks every combination and asserts that no two of them read alike.
 */

/** Which half of the queue the approver is looking at — the two chips on the card. */
enum class WorkshopAccessQueueView {
    /** `statusFilter=PENDING`: requests still waiting for an answer. */
    PENDING,

    /** `statusFilter=ALL`: the full history, kept for auditing — DENIED/REVOKED rows are never deleted. */
    HISTORY,
}

/**
 * A failed attempt to read the queue, classified ONCE.
 *
 * [message] is read from the error body, and reading it CONSUMES Retrofit's buffered body — see
 * `apiRefusal`'s "call this once per failure". That is precisely why this is a value built by one
 * call to [workshopAccessQueueFailure] and then passed around, rather than a throwable the copy
 * layer interrogates twice.
 */
data class WorkshopAccessQueueFailure(
    /**
     * The server ANSWERED, and the answer was "not you".
     *
     * Decides which sentence is shown and nothing else. False covers every unreachable-server shape a
     * village handset actually produces — `UnknownHostException`, `SocketTimeoutException`, a bare
     * `IOException` from a closed socket, and the 502/503/504 the gateway writes when this origin is
     * slow — none of which is a refusal, and all of which used to read as "the queue is clear".
     */
    val refused: Boolean,
    /** What the server, or the platform, actually said. Shown verbatim; never replaced with prose. */
    val message: String,
)

/**
 * Classify a queue-load failure. Call once per failure.
 *
 * `code()` is read BEFORE [apiErrorMessage] on purpose: reading the status does not touch the
 * buffered error body, while reading the message empties it. Doing it the other way round would work
 * today and break silently the first time somebody reordered the two lines.
 */
fun Throwable.workshopAccessQueueFailure(): WorkshopAccessQueueFailure {
    val http = this as? HttpException
    return WorkshopAccessQueueFailure(
        // Named arguments are evaluated top to bottom, so the status is read while the body is still
        // there. Do not reorder these two lines.
        refused = http?.code() == 403,
        message = apiErrorMessage("The server did not say why."),
    )
}

/**
 * The banner, or empty state, the card should draw — or null when the list speaks for itself.
 *
 * [answered] is the whole point of the type: true means the queue WAS read and this is what it
 * holds; false means it was not read, and nothing on screen may be taken as the state of the queue.
 * A caller may colour on it, but the heading already says which it is in words, because a reader who
 * cannot distinguish amber from grey still has to know whether there is nothing to do or nothing
 * they can see.
 */
data class WorkshopAccessQueueNotice(
    /** The loudest line. It must be TRUE for this situation and for no other. */
    val heading: String,
    /** What it means, and the next thing to do. Never "try again" on its own. */
    val body: String,
    /** Did the server answer this question? False for both a refusal and an unreachable server. */
    val answered: Boolean,
)

/**
 * What to say above — or instead of — the queue.
 *
 * @param view which chip is selected. An empty PENDING list is good news; an empty HISTORY list
 *   means the feature has never been used. They are not the same statement.
 * @param failure the classified failure from the last load, or null if it succeeded.
 * @param rowsOnScreen how many rows are currently drawn. A failure with rows still on screen is NOT
 *   an empty state — it is a staleness warning, because `refresh()` leaves the previous rows in
 *   place when the reload fails, so an approver can otherwise be looking at a request somebody else
 *   has already decided and press Approve on it.
 */
fun workshopAccessQueueNotice(
    view: WorkshopAccessQueueView,
    failure: WorkshopAccessQueueFailure?,
    rowsOnScreen: Int,
): WorkshopAccessQueueNotice? {
    if (failure != null) {
        val stale = if (rowsOnScreen > 0) {
            " What is listed below was read earlier and may already have been answered by somebody else."
        } else {
            ""
        }
        return if (failure.refused) {
            WorkshopAccessQueueNotice(
                heading = "You are not being shown this queue",
                // Says what was refused, who may see it, and the ONE action that changes it. It must
                // never imply the queue is empty: a refusal carries no information at all about how
                // many people are waiting, and pretending otherwise is the defect this file exists
                // for. It also never names another account or a rank ladder — the server's own
                // sentence is the whole disclosure, exactly as in [accessRefusalChrome].
                body = "The server refused this account. Requests across every workshop are readable " +
                    "by an admin or the master admin only, so this is not an empty queue — it is a " +
                    "queue you were not shown, and researchers may be waiting in it. If you could " +
                    "open this before, your role was changed after you signed in: sign out and back " +
                    "in to see what you now hold, and ask a master admin to restore it. The server " +
                    "said: ${failure.message}$stale",
                answered = false,
            )
        } else {
            WorkshopAccessQueueNotice(
                heading = "The queue could not be read",
                // No signal is the normal condition for this fleet, so this is a state rather than a
                // scolding — but it refuses outright to be mistaken for "nothing waiting".
                body = "This handset could not reach the server, so nothing here says whether anybody " +
                    "is waiting. Requests are held on the server and none of them is lost; try again " +
                    "where there is signal. The reason given: ${failure.message}$stale",
                answered = false,
            )
        }
    }
    if (rowsOnScreen > 0) return null
    // The server answered, and the answer was "none". These two mirror the web's own empty states in
    // frontend/components/settings/WorkshopAccessQueuePanel.tsx word for word, because an approver
    // who reads one on the phone and the other on the website has to be told the same thing.
    return when (view) {
        WorkshopAccessQueueView.PENDING -> WorkshopAccessQueueNotice(
            heading = "Nothing waiting",
            body = "Every workshop-access request has been answered.",
            answered = true,
        )

        WorkshopAccessQueueView.HISTORY -> WorkshopAccessQueueNotice(
            heading = "No requests yet",
            body = "Nobody has asked for access to a workshop.",
            answered = true,
        )
    }
}
