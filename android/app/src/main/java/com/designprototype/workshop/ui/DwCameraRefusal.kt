package com.designprototype.workshop.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.core.app.ActivityCompat

/**
 * WHAT TO SAY, AND WHERE TO SEND SOMEBODY, WHEN THE CAMERA IS REFUSED — for every surface that
 * photographs something in order to read it.
 *
 * ── WHY THIS FILE EXISTS ──────────────────────────────────────────────────────────────────────
 *
 * TWO SURFACES IN THIS APP POINT A LENS AT A PIECE OF PAPER AND READ IT: [DwQrScanControl], which
 * photographs a QR code on a card or tag, and `DwIdentityCardControl`, which photographs an identity
 * card. Both are the offline half of a feature whose whole premise is a courtyard with no signal, and
 * both have exactly the same two failures to explain.
 *
 * They did NOT explain them the same way, and that asymmetry is what this file closes. The scanner
 * had already learned to tell "denied once" from "Android has stopped asking" and to offer the system
 * permission page for the second one. The identity-card control had one sentence for both:
 *
 *     "The camera permission was refused, so the card cannot be photographed. You can still choose a
 *      photograph already on the phone, or type the number in."
 *
 * True of both situations and useful for only one. A designer whose camera is BLOCKED — denied twice,
 * or "Don't allow" on a build that treats it as permanent — can press "Photograph the card" for ever
 * and see nothing happen at all: the launcher returns denied without showing a dialog. That sentence
 * never tells them so and names nowhere that could undo it, so the one thing they cannot discover is
 * the one thing that would fix it.
 *
 * ── WHY THE WORDING IS BUILT HERE RATHER THAN COPIED INTO EACH CONTROL ────────────────────────
 *
 * `DwQrScanControl`'s header already states the rule this repository applies to scanner copies:
 * "copies of a scanner drift, and the half that drifts is always the refusal wording". A second
 * hand-written pair in the identity file would be exactly that — and the half that would drift is
 * the load-bearing clause, the one saying Android will not ask again and that the button below opens
 * the page where it can be turned back on.
 *
 * So the SHAPE of both sentences is written once, and each surface supplies only the three things
 * that genuinely differ: what it was going to photograph, what its own button says, and which
 * camera-free routes it still offers. A surface that offers no camera-free route has no business
 * using this file — see [DwCameraUse].
 *
 * ── PURE, SO THE SENTENCES ARE PINNED BY A TEST ───────────────────────────────────────────────
 *
 * [dwCameraRefusal] touches no Android class at all, which is deliberate: `DwCameraRefusalTest`
 * asserts on this machine that neither message is ever a dead end, that the blocked one names the
 * settings route and the denied one does not, and that the two are never the same string. That is a
 * claim about wording, and wording claims are the ones a repository with no handset can actually
 * make. Everything below [dwCameraRefusal] is platform plumbing and is not tested here.
 */

/**
 * A surface that photographs something in order to read it, and the words that are its own.
 *
 * EVERY ENTRY MUST NAME AT LEAST ONE ROUTE THAT NEEDS NO CAMERA in [alternatives]. That is not a
 * style rule — it is the reason a refused camera is survivable at all in this app. The typed code and
 * the typed number are the guaranteed paths (`docs/DECISION-qr-scanning-on-android.md` names the
 * first one as such), and the system photo picker reads a screenshot or a photograph somebody was
 * sent without any camera permission whatsoever. A surface whose only route is the lens would be a
 * feature that disappears when a designer taps Deny once, in a village, two districts from anybody
 * who could tell them why.
 *
 * @property subject what could not be photographed, as it reads mid-sentence after "so".
 * @property button the label on this surface's own camera button, quoted back so the sentence names
 *   a control that is actually on the screen the designer is looking at.
 * @property alternatives the routes that remain, ending in a full stop. See the rule above.
 */
enum class DwCameraUse(
    internal val subject: String,
    internal val button: String,
    internal val alternatives: String,
) {
    /** [DwQrScanControl] — a QR code on a card, a tag or a screenshot. */
    QR_CODE(
        subject = "a code",
        button = "Scan a code",
        alternatives = "choose a picture of the code that is already on this phone, or type the " +
            "code printed under the QR.",
    ),

    /** `DwIdentityCardControl` — an Aadhaar or Pehchan card held under the lens. */
    IDENTITY_CARD(
        subject = "the card",
        button = "Photograph the card",
        alternatives = "choose a photograph of the card that is already on this phone, or type the " +
            "number in.",
    ),
}

/**
 * The label on the way-out button, spelled once so the sentence and the button cannot disagree.
 *
 * Both camera surfaces render a button carrying this text when, and only when, Android has stopped
 * asking; [dwCameraRefusal]'s blocked branch quotes it. Two spellings of one label is a designer
 * hunting the screen for a control that is right in front of them under another name.
 */
const val DW_CAMERA_SETTINGS_BUTTON = "Camera settings"

/**
 * The sentence to show after the camera permission was refused.
 *
 * @param blocked what [dwCameraBlocked] answered inside the permission callback — true when Android
 *   has stopped asking, so pressing the button again would do nothing at all.
 *
 * THE TWO BRANCHES DIFFER IN THEIR NEXT ACTION, which is the only reason there are two. Denied once:
 * press the same button again and the prompt comes back, so the sentence says so and sends nobody on
 * a detour through a system screen. Blocked: the button is inert, so the sentence says THAT — a
 * designer who is not told will press it, conclude the app is broken, and be right about the symptom.
 *
 * Both end by naming the camera-free routes, because both remain open on every surface that uses
 * this file. A refused permission narrows this app; it never closes it.
 */
fun dwCameraRefusal(use: DwCameraUse, blocked: Boolean): String = if (blocked) {
    // NAMED, NOT PLACED. This clause used to read "the button below opens them", which was true of
    // the layout it was written in and of nothing else: `RecordCodeLookupPanel` renders the refusal
    // UNDER the button row, and `DwIdentityCardControl` hands its refusal to the screen's own error
    // surface, which on a stage form is nowhere near the control. A sentence that points at a
    // position is a sentence that goes wrong the first time a caller lays its own screen out; the
    // label is the same on every surface, so the label is what it names.
    "The camera is blocked for this app, so Android will not ask again and pressing " +
        "“${use.button}” will do nothing. Turn it back on in this app's permission " +
        "settings — the “$DW_CAMERA_SETTINGS_BUTTON” button opens them — or carry on " +
        "without the camera: " + use.alternatives
} else {
    "The camera permission was refused, so ${use.subject} cannot be photographed. Press " +
        "“${use.button}” again to be asked once more — or, without the camera at " +
        "all, ${use.alternatives}"
}

/**
 * Whether Android has stopped asking for the camera — READ ONLY FROM INSIDE A PERMISSION CALLBACK.
 *
 * `shouldShowRequestPermissionRationale` is false in TWO opposite situations: before the first prompt
 * has ever been shown, and after a permanent denial. Read anywhere else it cannot tell them apart —
 * `LocationCapture.diagnose` carries a `promptShown` latch for exactly that reason. Inside the
 * callback of a permission request that came back DENIED, the prompt has by definition just been
 * made, so a false there means blocked and nothing else, and no latch is needed.
 *
 * A context with no Activity behind it cannot be asked at all. That reads as BLOCKED rather than
 * denied-once, and the direction of that guess is the safe one: the blocked sentence offers the
 * settings page as well as the two camera-free routes, so a designer who could in fact have been
 * asked again loses nothing but a tap, while the reverse would hide the only door that opens.
 */
internal fun dwCameraBlocked(context: Context): Boolean {
    val activity = context.dwCameraHostActivity() ?: return true
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

/**
 * The Activity behind a Compose context.
 *
 * `LocationCapture.kt` and `Theme.kt` each still declare their own file-`private` copy of this loop,
 * and they are left alone deliberately: promoting either would leave two same-signature top-level
 * functions visible in one package at the call sites inside the file that still declares the private
 * one. This copy replaces the one `DwQrScanControl` used to keep to itself, so the count of copies
 * in this package has not gone up — it is the same loop, now reached by both camera surfaces.
 */
private fun Context.dwCameraHostActivity(): Activity? {
    var cursor: Context? = this
    while (cursor is ContextWrapper) {
        if (cursor is Activity) return cursor
        cursor = cursor.baseContext
    }
    return null
}

/**
 * Android's own permission page for this app — the only place a blocked permission can be undone.
 *
 * Wrapped in `runCatching` because the one failure available here is an OEM build with no activity
 * for that intent, and a crash on the way to fixing a permission is worse than a button that does
 * nothing. The sentence beside it has already named two routes that do not need this page at all.
 */
internal fun Context.dwOpenAppPermissionSettings() {
    runCatching {
        startActivity(
            // Fully qualified: `Settings` is also a Material icon, and the system class and a picture
            // of a cog must not be made to look like one name at a call site.
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
