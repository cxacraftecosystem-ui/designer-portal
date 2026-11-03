package com.designprototype.workshop.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.core.app.ActivityCompat

enum class DwCameraUse(
    internal val subject: String,
    internal val button: String,
    internal val alternatives: String,
) {
    QR_CODE(
        subject = "a code",
        button = "Scan a code",
        alternatives = "pick a picture of the code already on this phone, or type the code printed under the QR.",
    ),

    IDENTITY_CARD(
        subject = "the card",
        button = "Photograph the card",
        alternatives = "pick a photo of the card already on this phone, or type the number in.",
    ),
}

const val DW_CAMERA_SETTINGS_BUTTON = "Camera settings"

fun dwCameraRefusal(use: DwCameraUse, blocked: Boolean): String = if (blocked) {
    "Camera blocked, so Android will not ask again and “${use.button}” will do nothing. Turn it on in this app's permission settings — the “$DW_CAMERA_SETTINGS_BUTTON” button opens them — or: " + use.alternatives
} else {
    "Camera denied. Tap “${use.button}” to be asked once more, or ${use.alternatives}"
}

internal fun dwCameraBlocked(context: Context): Boolean {
    val activity = context.dwCameraHostActivity() ?: return true
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

private fun Context.dwCameraHostActivity(): Activity? {
    var cursor: Context? = this
    while (cursor is ContextWrapper) {
        if (cursor is Activity) return cursor
        cursor = cursor.baseContext
    }
    return null
}

internal fun Context.dwOpenAppPermissionSettings() {
    runCatching {
        startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
