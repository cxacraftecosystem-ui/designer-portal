package com.designprototype.workshop.ui

import com.designprototype.workshop.data.AccessRefusal

/**
 * WHAT THE SIGN-IN CARD SAYS AROUND A REFUSAL — never instead of it.
 *
 * ── THE RULING THIS IMPLEMENTS ───────────────────────────────────────────────────────────────────
 *
 * "Wrong password and pending approval should be differentiated." Somebody waiting on an
 * administrator, told "invalid email or password", will reset a password that was never wrong —
 * twice — and then telephone somebody who cannot help them, because this product has no
 * registration page and no password-reset email, so the vague answer leaves them with no next
 * action that exists. The account-enumeration cost of saying so was weighed and accepted; what is
 * NOT accepted is saying anything MORE than "this address is awaiting approval". Nothing here names
 * a person, a tier, whether a password was ever set, or anything about any other account, and
 * nothing added later may either. The server's own sentence is the whole disclosure.
 *
 * ── WHY THIS IS A PLAIN FUNCTION IN A FILE OF ITS OWN ────────────────────────────────────────────
 *
 * No Compose, no Android framework, no Retrofit: the copy is the part of this feature most likely to
 * be quietly wrong, and the only way to pin "a person awaiting approval is not told their access was
 * withdrawn" is a test that can read the words. `AccessRefusalCopyTest` walks every [AccessRefusal]
 * and asserts exactly that. A `when` block buried inside a composable is not reachable from a JVM
 * test, and this rule is too important to verify by looking at it.
 *
 * ── THE WEB SAYS THE SAME THING, WORD FOR WORD ───────────────────────────────────────────────────
 *
 * `frontend/lib/accessRoster.ts::accessRefusalChrome` carries the identical headings and advice.
 * That is not tidiness: a person who cannot get into the phone opens the website next, and reading a
 * different explanation there is how somebody concludes one of the two is broken. Change one, change
 * both.
 */
data class AccessRefusalChrome(
    /** The loudest line on the card. It must be TRUE for this refusal and no other. */
    val heading: String,
    /** What to do next. There is always exactly one thing, and it is never "try again". */
    val advice: String,
    /**
     * Waiting on somebody, as opposed to having been refused by them.
     *
     * Drives the colour and nothing else — the heading already says which it is in words, because a
     * reader who cannot distinguish amber from red still has to know whether an administrator has
     * not got to them yet or has said no.
     */
    val waiting: Boolean,
)

/**
 * The chrome for a refusal, or null for "say nothing extra".
 *
 * NULL IS THE RIGHT ANSWER TWICE OVER, and both matter:
 *
 * * [AccessRefusal.BAD_CREDENTIAL] — a mistyped password is an ordinary field error and must stay
 *   one. Dressing it in a panel would make every typo look like an account problem, which is this
 *   feature's own mistake made backwards.
 * * [AccessRefusal.UNCLASSIFIED] — the server refused but this build cannot say which refusal it
 *   was: an older deployment, or a proxy that stripped the classifying header. Neutral chrome around
 *   the server's own words is the only safe direction to be wrong in. Guessing a heading is how a
 *   person waiting for approval gets told their access was withdrawn.
 */
fun accessRefusalChrome(refusal: AccessRefusal): AccessRefusalChrome? = when (refusal) {
    AccessRefusal.PENDING -> AccessRefusalChrome(
        heading = "You are on the list, waiting for an administrator",
        advice =
            "Your password is not the problem and resetting it will not help. An administrator has " +
                "been shown your request and has to approve it before you can sign in. Trying again " +
                "does not move you up the queue — it is recorded as another attempt on the same request.",
        waiting = true,
    )

    AccessRefusal.REJECTED -> AccessRefusalChrome(
        heading = "Your request was reviewed and not approved",
        advice =
            "Signing in again will not reopen it — an administrator has to. Contact them directly if " +
                "you believe this is a mistake.",
        waiting = false,
    )

    AccessRefusal.SUSPENDED -> AccessRefusalChrome(
        heading = "Your access to this application has been suspended",
        advice =
            "This is not a password problem. An administrator ended this address's access and only an " +
                "administrator can restore it.",
        waiting = false,
    )

    // THE OLD BEHAVIOUR OF THIS CARD, NARROWED TO THE ONE CASE IT WAS EVER TRUE FOR. Until the
    // allow-list existed, every 403 drew "your access has been withdrawn" and pointed at the
    // designer roster; that is exactly right here and wrong for all four of its neighbours.
    AccessRefusal.DESIGNER_SUSPENDED -> AccessRefusalChrome(
        heading = "Your designer empanelment has ended",
        advice =
            "Your account itself is not barred — the institution's designer roster no longer carries " +
                "this address. Ask an administrator to restore you on the designer roster.",
        waiting = false,
    )

    AccessRefusal.QUEUE_FULL -> AccessRefusalChrome(
        heading = "Requests to join are temporarily closed",
        advice =
            "Nothing about you was refused and nothing was recorded, so waiting will not help: an " +
                "administrator has to clear the approval queue before new requests can be accepted. " +
                "Contact one directly.",
        waiting = false,
    )

    AccessRefusal.BAD_CREDENTIAL,
    AccessRefusal.UNCLASSIFIED,
    AccessRefusal.NOT_REFUSED,
    -> null
}
