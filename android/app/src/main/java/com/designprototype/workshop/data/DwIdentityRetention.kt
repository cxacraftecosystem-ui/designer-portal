package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

/**
 * Keep the photograph of the identity card, or delete it — the designer's answer, never the app's.
 *
 * ── WHAT THIS PORTS, AND THE ONE THING ANDROID DID NOT HAVE ───────────────────────────────────
 *
 * The web found a real hole and closed it: its design-workshop stage form renders
 * `IdentityCardReader` UNDER a media field, and reads numbers off photographs the designer has
 * ALREADY attached there — which means the ordinary media flow has already presigned a PUT, put the
 * bytes in S3 and created a `MediaFile` row. On that surface an unmasked identity document was
 * durably in the repository before anybody was offered a thought about it. Nobody chose that; the
 * form did.
 *
 * **THIS APP HAS NEVER HAD THAT SURFACE, AND THE ABSENCE WAS CHECKED RATHER THAN ASSUMED.**
 * `DwIdentityCardControl` is the only identity reader on Android and it has three call sites — two
 * on the artisan form, one on a stage TEXT field — and every one of them takes a FRESH photograph
 * into a scratch directory that is swept on mount and on dispose, or reads a picked content Uri
 * without copying it. Nothing on this handset reads a number off an attachment. So the defect the
 * web lane fixed did not exist here, and porting the fix verbatim would have meant inventing the
 * hole in order to plug it.
 *
 * ── WHAT WAS THEREFORE PORTED, WHICH IS THE OTHER HALF ────────────────────────────────────────
 *
 * The web's artisan-form reader was in the same position as this app's — safe already, with **no way
 * to choose to keep** and a promise ("the photograph is not kept") that lived only in a comment, so
 * no client could read it. The lane fixed both of those, and both of them apply here:
 *
 *  1. **THE DECISION IS DECLARED ON THE WIRE.** `POST /design-workshops/ocr/identity` now takes a
 *     `retention` form field. It changes nothing about what the route does — that route has no
 *     storage path — and that is the point of sending it: the one request in which an unmasked
 *     identity document crosses the network carries the designer's answer to "are you keeping this".
 *  2. **THE PROMISE IS READ, NOT CLAIMED.** The reply now carries `photograph.stored`, a literal
 *     `false` on the server rather than a computed value. A panel that says "the photograph was not
 *     kept" now says it because the server said so — see [photographWasNotStored], which is where
 *     the difference between "the server said false" and "the server said nothing" is enforced.
 *  3. **THE CHOICE IS OFFERED, AND IT IS REAL WHERE THERE IS SOMEWHERE TO PUT IT.** See
 *     `DwIdentityCardControl`.
 *
 * ── THE DEFAULT IS DISCARD, EVERYWHERE, AND THIS IS WHY IT IS A NAMED CONSTANT ────────────────
 *
 * The brief was explicit that the phone and the browser must not disagree, and the direction they
 * must agree in is the safe one. `identity_ocr.parse_retention` maps EVERYTHING it does not
 * recognise — missing, blank, `"keep"`, `true`, a dict — onto DISCARD and never raises, on the
 * argument that a photograph which was not kept can be retaken in ten seconds with the card still in
 * the artisan's hand, whereas one kept by accident is a regulated document in a research bucket that
 * nobody knows to go and delete. [parseRetention] below is the same function, so a value this client
 * mangles resolves the same way on both sides of the wire rather than one of them guessing.
 *
 * ── WHY A PHOTOGRAPH IS A HOLE AND THE NUMBER IS NOT ──────────────────────────────────────────
 *
 * [ArtisanIdentity.mask] and the server's `mask_aadhaar` / `mask_identity_number` mask the DIGITS on
 * every surface this repository shows or exports, and that machinery is sound. It cannot touch a
 * JPEG. A photograph of the card IS the number, unmasked, in the one form the masking rule cannot
 * reach. Keeping it is therefore not a storage preference; it is a decision to hold regulated
 * personal data, and the server writes the name of whoever made it onto the file.
 */

/** Delete the photograph: the object first, then the row. The safe half, and every default. */
const val DW_RETENTION_DISCARD = "DISCARD"

/**
 * Keep it, on the record, stamped with who decided that and when.
 *
 * NEVER A DEFAULT AND NEVER AN INFERENCE. It exists only where a person pressed it.
 */
const val DW_RETENTION_STORE = "STORE"

/**
 * The safe half, named rather than spelled at each call site.
 *
 * Every default in this feature — the switch the panel mounts with, the argument the repository
 * falls back to, the value the server assumes for a client that says nothing — is this constant or
 * the server's copy of it. A reader checking "is the default the safe one" has one place to look.
 */
const val DW_RETENTION_DEFAULT = DW_RETENTION_DISCARD

/** The whole vocabulary. Mirrors `identity_ocr.RETENTION_DECISIONS`, which pins the same pair. */
val DW_RETENTION_DECISIONS = listOf(DW_RETENTION_DISCARD, DW_RETENTION_STORE)

/**
 * The decision a value means, resolved to one of [DW_RETENTION_DECISIONS].
 *
 * **EVERYTHING UNRECOGNISED BECOMES DISCARD, AND THIS NEVER THROWS.** That is the safety property of
 * the feature and it is a deliberate copy of `identity_ocr.parse_retention`, including the reasoning:
 * the two ways to handle a value the code cannot act on are "keep the identity document and let
 * somebody sort it out" or "keep nothing", and only one of those is recoverable.
 *
 * Case and surrounding whitespace are forgiven because they are not ambiguity — `"store"`, `"STORE"`
 * and `" Store "` are one intention, and refusing them would only teach the next caller to send
 * something else.
 */
fun parseRetention(raw: String?): String {
    val cleaned = raw?.trim()?.uppercase()
    return if (cleaned != null && cleaned in DW_RETENTION_DECISIONS) cleaned else DW_RETENTION_DISCARD
}

/**
 * What the OCR route did with the PICTURE, as opposed to what it read off it.
 *
 * [stored] is `false` on every reply this route has ever sent, and it is a literal on the server
 * rather than a computed value — so a build that ever sees `true` is talking to a server that grew a
 * storage path. It is NULLABLE here on purpose: a deployment older than this field sends no
 * `photograph` block at all, and that absence must not be read as `false`. See
 * [photographWasNotStored].
 */
@Serializable
data class DwIdentityPhotographDto(
    val stored: Boolean? = null,
    val retention: String? = null,
    /** Where a photograph that WAS stored through the media flow gets its decision recorded. */
    val decisionRoute: String? = null,
)

/**
 * Has the server told us, IN THIS REPLY, that it did not keep the photograph?
 *
 * TRUE ONLY FOR AN EXPLICIT `stored == false`. A deployment older than the field sends nothing, and
 * the temptation is to treat that as false because it is in fact what those servers do. That would
 * be a screen telling a designer "your photograph was not kept" on the strength of a key that was
 * missing — a promise about regulated data made from an absence. Where the server has not said, the
 * panel says nothing rather than reassuring anybody.
 *
 * The web enforces the identical distinction in `photographWasNotStored`; the two must not drift,
 * because a designer who compares the phone and the browser on the same deployment is entitled to
 * read the same promise.
 */
fun photographWasNotStored(result: DwIdentityOcrDto): Boolean = result.photograph?.stored == false

/**
 * The accountability block written onto a photograph that is kept.
 *
 * [decidedByName] duplicates something the `User` row already holds, which is normally a reason not
 * to store it — but this is a record about a regulated document and it has to survive the account
 * being renamed, disabled or removed. A stamp reading `decidedById: "usr_7"` that resolves to
 * nothing is not a record of who decided; it is a record that somebody did.
 */
@Serializable
data class DwRetentionStampDto(
    val decision: String = DW_RETENTION_DISCARD,
    val decidedById: String? = null,
    val decidedByName: String? = null,
    val decidedAt: String? = null,
)

/** The body of `POST /design-workshops/ocr/identity/retention`. */
@Serializable
data class DwRetentionDecisionBody(
    val mediaId: String,
    val decision: String,
)

/**
 * What the retention route says it did.
 *
 * [deleted] is a HARD delete — the S3 object and then the row. There is no soft one on this route,
 * and that is deliberately the inverse of everything else in `design_workshops.py`, whose header
 * opens by stating that nothing in it hard-deletes. A soft-deleted photograph of somebody's Aadhaar
 * card is a retained photograph of somebody's Aadhaar card with a flag on it, and the designer who
 * pressed "delete this" would be entitled to believe otherwise.
 */
@Serializable
data class DwRetentionResultDto(
    val mediaId: String = "",
    val decision: String = DW_RETENTION_DISCARD,
    val deleted: Boolean = false,
    val objectDeleted: Boolean = false,
    val retention: DwRetentionStampDto? = null,
)

/**
 * The sentence a panel shows once a decision has landed.
 *
 * Here rather than in each screen so that the artisan form and any future stage surface cannot come
 * to say two different things about the same outcome, and so the wording is pinned by a JVM test
 * rather than by a screenshot. It states what happened in the past tense and does not hedge:
 * "deleted" means the file and the record of it are both gone, which is what the route did.
 *
 * Deliberately mirrors the web's `retentionOutcomeSentence`, down to naming the person on the STORE
 * branch — the whole point of the stamp is that a retained identity document can be traced to
 * whoever decided it should be, and a sentence that omitted the name would be describing a weaker
 * record than the one that was actually written.
 */
fun retentionOutcomeSentence(result: DwRetentionResultDto): String {
    if (result.deleted) {
        return "That photograph has been deleted — the file and the record of it are both gone. " +
            "The number you confirmed is unaffected."
    }
    val who = result.retention?.decidedByName?.trim()?.takeIf { it.isNotEmpty() }
    return if (who != null) {
        "That photograph is kept on this record, in $who's name. It is a photograph of an identity " +
            "document, so it is stored unmasked and anyone who can open this record can see it."
    } else {
        "That photograph is kept on this record. It is a photograph of an identity document, so it " +
            "is stored unmasked and anyone who can open this record can see it."
    }
}

/**
 * What the control says beside the choice, BEFORE the photograph is taken.
 *
 * Deliberately not reassuring on the STORE side. A designer who ticks this and walks away has put an
 * unmasked identity document on the record, so the copy says that rather than "you can change your
 * mind later" — the moment before the shutter is the only place the cost of the decision is visible,
 * and it is the only place where not taking the photograph is still an option.
 */
fun retentionChoiceBlurb(keep: Boolean): String = if (keep) {
    "You have asked for this photograph to be KEPT on the record. It is a photograph of an identity " +
        "document, so it is stored unmasked — unlike the number itself, which this repository masks " +
        "everywhere it is shown. Your name is recorded against the decision."
} else {
    "The photograph is not kept — not on this phone and not on the server. Only the number you " +
        "confirm is saved."
}
