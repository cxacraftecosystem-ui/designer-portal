package com.designprototype.workshop.transcript.portal

import android.content.Context
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.BackgroundSync
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.transcript.data.RetrofitTranscriptBackend
import com.designprototype.workshop.transcript.data.TranscriptApi
import com.designprototype.workshop.transcript.data.TranscriptBackend
import com.designprototype.workshop.transcript.data.TranscriptRepository
import com.designprototype.workshop.transcript.data.TranscriptStore
import kotlinx.serialization.json.Json

/**
 * THE DESIGNER PORTAL'S HALF OF THE SHARED TRANSCRIPT TREES. NOT SHARED.
 *
 * `transcript/data`, `transcript/ui` and their tests are the same Kotlin in both portals
 * (`scripts/transcript_android.py`). What differs — the HTTP stack, who is signed in, how a
 * background pass is asked for — is reached through this object, which documentation-portal
 * declares with the same members over its own code.
 */
object TranscriptPortal {
    const val NAME = "designer-portal"

    val json: Json get() = ApiClient.json

    fun backend(context: Context): TranscriptBackend =
        RetrofitTranscriptBackend(ApiClient.retrofit(TokenStore(context.applicationContext)).create(TranscriptApi::class.java))

    fun userId(context: Context): String? = TokenStore(context.applicationContext).getUser()?.id

    /** The signed-in account's store, or null when nobody is signed in. */
    fun store(context: Context): TranscriptStore? =
        userId(context)?.let { TranscriptStore(context.applicationContext.filesDir, it, json) }

    fun repository(context: Context): TranscriptRepository? =
        store(context)?.let { TranscriptRepository(backend(context), it, json) }

    /** Send queued edits as soon as there is a network, app open or not (`data/BackgroundSync.kt`). */
    fun requestSync(context: Context) = BackgroundSync.requestSoon(context.applicationContext)

    fun fileProviderAuthority(context: Context): String = context.packageName + ".fileprovider"
}
