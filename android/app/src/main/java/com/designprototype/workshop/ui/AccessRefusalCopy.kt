package com.designprototype.workshop.ui

import com.designprototype.workshop.data.AccessRefusal
import com.designprototype.workshop.data.SignInHint

data class AccessRefusalChrome(
    val heading: String,
    val advice: String,
    val waiting: Boolean,
)

fun accessRefusalChrome(refusal: AccessRefusal): AccessRefusalChrome? = when (refusal) {
    AccessRefusal.PENDING -> AccessRefusalChrome(
        heading = "Waiting for an admin to approve you",
        advice =
            "Your password is fine. An admin needs to approve your request; retrying won't speed it up.",
        waiting = true,
    )

    AccessRefusal.REJECTED -> AccessRefusalChrome(
        heading = "Your request was reviewed and not approved",
        advice =
            "Only an admin can reopen this. Contact them if it's a mistake.",
        waiting = false,
    )

    AccessRefusal.SUSPENDED -> AccessRefusalChrome(
        heading = "Your access has been suspended",
        advice =
            "Not a password issue. Only an admin can restore access.",
        waiting = false,
    )

    AccessRefusal.DESIGNER_SUSPENDED -> AccessRefusalChrome(
        heading = "Your designer empanelment has ended",
        advice =
            "Your email is no longer on the designer roster. Ask an admin to restore it.",
        waiting = false,
    )

    AccessRefusal.QUEUE_FULL -> AccessRefusalChrome(
        heading = "Requests to join are temporarily closed",
        advice =
            "The approval queue is full, so your request wasn't recorded. Contact an admin.",
        waiting = false,
    )

    AccessRefusal.BAD_CREDENTIAL,
    AccessRefusal.UNCLASSIFIED,
    AccessRefusal.NOT_REFUSED,
    -> null
}

fun signInHintHeading(hint: SignInHint): String? = when (hint) {
    SignInHint.AMBIGUOUS_IDENTIFIER -> "That number matches more than one account"
    SignInHint.PASSWORD_NOT_SET -> "This account has no password yet"
    SignInHint.NONE -> null
}
