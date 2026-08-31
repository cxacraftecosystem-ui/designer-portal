package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto

/**
 * THE WORDS AND THE RULES BEHIND THE TWO PASSWORD SCREENS, with no Compose in sight.
 *
 * ── WHY THIS IS A FILE OF ITS OWN ────────────────────────────────────────────────────────────────
 *
 * The same argument `AccessRefusalCopy.kt` makes, and it applies harder here. Six of the sentences
 * below are one-per-refusal, each naming a DIFFERENT next action — "ask for another", "you already
 * used it, go and sign in", "ask the administrator what happened" — and the failure this feature can
 * actually ship is two of them being the same sentence, or one of them quietly becoming "invalid
 * link", which leaves a person with no next move at all. A `when` block inside a composable is not
 * reachable from a JVM test; this file is, and `PasswordSetupCopyTest` walks it.
 *
 * The token extraction is here for the same reason: it is the one piece of parsing on the redeem
 * path, it decides whether a designer who pastes a whole link gets a form or a refusal, and looking
 * at it is not evidence that it works.
 *
 * ── WHAT IS DELIBERATELY *NOT* HERE ──────────────────────────────────────────────────────────────
 *
 * The refusal sentence for a POST. `POST /auth/set-password` answers with the SERVER's own sentence
 * for every one of these reasons (`_SET_PASSWORD_REFUSALS` in backend/app/api/routes/auth.py), and
 * the redeem screen shows that verbatim rather than looking one up here. [passwordLinkRefusal] is
 * only for `GET /auth/set-password`, which answers with a reason WORD and no sentence — deliberately,
 * because the words a person reads are the client layer's job on that route and the server says so.
 */

/**
 * The shortest password this product will store.
 *
 * ONE FLOOR, FIVE SPELLINGS OF IT, AND THEY MUST AGREE. `credential_links.MIN_PASSWORD_LENGTH`,
 * `SetPasswordRequest.password`, `ChangePasswordRequest.newPassword`, `LoginRequest.password` and
 * `UserCreate.password` all carry 8 on the server, so that a password which can be SET can always be
 * used to sign in. The web mirrors it in `frontend/lib/signIn.ts`; this is the handset's copy.
 *
 * MIRRORED RATHER THAN FETCHED, the treatment `DW_DICTATION_MAX_BYTES` gets: no endpoint reports it,
 * it is a deployment constant, and the server refuses either way — so the only thing a round trip
 * would buy is a screen that cannot draw its own hint sentence with no signal.
 */
const val MIN_PASSWORD_LENGTH = 8

/**
 * The one line printed under a pair of password boxes.
 *
 * A FUNCTION AND NOT A CONSTANT, because the two screens have a genuinely different second clause —
 * a link works once, the first-login gate involves no link at all — and a shared string carrying the
 * link sentence would have the gate telling somebody about a link they are not holding. The FIRST
 * clause is what must not diverge, and it is the only part this owns. `passwordRuleLine` in
 * `frontend/lib/signIn.ts` is the same function, word for word.
 */
fun passwordRuleLine(suffix: String? = null): String =
    "At least $MIN_PASSWORD_LENGTH characters." + (suffix?.let { " $it" } ?: "")

/**
 * Must this account choose its own password before it is let into the product?
 *
 * ── THE SERVER REPORTS AND THE CLIENT REFUSES, WHICH IS THE CONSENT GATE'S OWN SHAPE ─────────────
 *
 * `POST /auth/login` mints a token for an account carrying `mustChangePassword`, deliberately: the
 * only route that can change a password needs a bearer token, so a 403 at the door would be a demand
 * the account could never satisfy. `usageConsentBlocks` sits three files away and says the identical
 * thing about the identical arrangement, and the two gates are `when` arms side by side in
 * `RepositoryApp` for that reason.
 *
 * ── A NULL IS "NO GATE", NEVER "GATE OPEN" AND NEVER "GATE SHUT" ─────────────────────────────────
 *
 * [UserDto.mustChangePassword] is nullable because a deployment older than the column sends nothing,
 * and a handset in the field may well be talking to one. `== true` is the only safe reading: it must
 * not invent a demand nobody made, and it must not claim the person has already chosen. This is the
 * same rule `usageConsentBlocks` states as `?.required == true`.
 */
fun mustChangePasswordBlocks(user: UserDto?): Boolean = user?.mustChangePassword == true

/**
 * The token inside whatever a designer pasted.
 *
 * ── WHY THE SCREEN TAKES A PASTE AT ALL, RATHER THAN ONLY A DEEP LINK ────────────────────────────
 *
 * An administrator issues a link and hands it over by hand — a message, a note, read out over a
 * telephone. On a handset that arrives as a URL in a chat app, and tapping it opens a BROWSER, which
 * works and is not what somebody standing in a courtyard with this app open is trying to do. So the
 * screen accepts the whole link pasted, and also the bare token for the case where it reached them
 * without the address around it.
 *
 * ── WHAT IT WILL AND WILL NOT DO ─────────────────────────────────────────────────────────────────
 *
 * It reads `?token=` (or `&token=`) out of the text and otherwise returns the text as typed. It does
 * NOT validate the shape: the token is `base64url(payload).base64url(HMAC)` and the server checks the
 * signature, the shape, the expiry, the row AND the credential fingerprint. A client-side shape test
 * would only be able to produce a SEVENTH refusal — one the server does not have a word for — for a
 * string the server might well have accepted.
 *
 * PERCENT-DECODING IS DONE HERE because the value is URL-encoded in the link and Retrofit will encode
 * whatever it is given again; sending the encoded form would put `%3D` in the token and the signature
 * would not verify. It is deliberately NOT a general decoder — only `%XX` pairs, and a malformed one
 * is left standing rather than throwing, because a person who pasted something odd is owed the
 * server's refusal and not a crash.
 */
fun passwordLinkToken(pasted: String): String {
    val text = pasted.trim()
    if (text.isEmpty()) return ""
    val marker = Regex("[?&]token=")
    val match = marker.find(text) ?: return text
    val rest = text.substring(match.range.last + 1)
    // The link may carry further parameters after the token, and a fragment after those.
    val value = rest.takeWhile { it != '&' && it != '#' }
    return percentDecode(value)
}

private fun percentDecode(value: String): String {
    if (!value.contains('%')) return value
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val hex = value.substring(index + 1, index + 3)
            val decoded = hex.toIntOrNull(16)
            if (decoded != null) {
                out.append(decoded.toChar())
                index += 3
                continue
            }
        }
        out.append(char)
        index += 1
    }
    return out.toString()
}

/**
 * One sentence per refusal from `GET /auth/set-password`, because each has a different next action.
 *
 * ── KEYED ON THE SERVER'S REASON WORD, NEVER ON ITS PROSE ────────────────────────────────────────
 *
 * The keys are the words `app/services/credential_links.py` defines as constants — `MISSING`,
 * `MALFORMED`, `EXPIRED`, `REVOKED`, `SPENT`, `UNKNOWN_ACCOUNT`. Matching on a sentence is what breaks
 * the first time somebody fixes a comma, and the route deliberately returns the WORD so that the
 * words a person reads are this layer's decision.
 *
 * ── AND WHY THERE ARE SIX AND NOT ONE ────────────────────────────────────────────────────────────
 *
 * "This link is not valid" is true of all six and useful for none of them. Expired means "ask for
 * another"; revoked means "ask the administrator what happened"; already used means "you have set it
 * — go and sign in", which is the opposite of asking anybody for anything. A single sentence leaves a
 * person with no next action that exists, which is the same failure the sign-in refusals were split up
 * to end. `LINK_REFUSALS` in `frontend/app/set-password/page.tsx` carries the same six, word for word,
 * because a designer refused on the phone opens the website next.
 *
 * The UNKNOWN branch is the seventh answer and is a real one: a reason word this build has never heard
 * of, from a server newer than the handset. It says only what a bare refusal proves.
 */
fun passwordLinkRefusal(reason: String?): String = when (reason?.trim()?.lowercase()) {
    "missing" -> "This link is incomplete. Paste the whole link the administrator sent you."
    "malformed" -> "This is not a link this app issued. Ask the administrator for another."
    "expired" -> "This link has expired. Ask the administrator for a new one."
    "revoked" -> "This link was withdrawn. Ask the administrator for a new one."
    "spent" -> "This link has already been used. Sign in with the password you set."
    "unknown-account" -> "This link no longer points at an account."
    else -> "This password link is not valid."
}

/**
 * How long a link lasts, said in words beside the one an administrator has just minted.
 *
 * TWO PURPOSES AND TWO LIFETIMES, and the server picks which — an INVITE is generous (72 hours,
 * passed on by hand, read on a Monday and acted on after a conference) and a RESET is short (2 hours,
 * because it answers "I am locked out NOW" and a link outliving that conversation is a spare key left
 * under the mat). The handset does not compute either number: it prints the server's own `expiresAt`,
 * and this only names WHICH KIND it is, because "expires at 14:12" answers a different question from
 * "this is an invitation".
 */
fun passwordLinkPurposeLine(purpose: String?): String = when (purpose?.trim()?.uppercase()) {
    "INVITE" -> "Invitation — for an account that has never had a password."
    "RESET" -> "Reset — this account already has a password."
    else -> "One-time password link."
}
