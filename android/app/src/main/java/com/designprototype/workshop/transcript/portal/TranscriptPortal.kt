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

object TranscriptPortal {
    const val NAME = "designer-portal"

    val json: Json get() = ApiClient.json

    fun backend(context: Context): TranscriptBackend =
        RetrofitTranscriptBackend(ApiClient.retrofit(TokenStore(context.applicationContext)).create(TranscriptApi::class.java))

    fun userId(context: Context): String? = TokenStore(context.applicationContext).getUser()?.id

    fun store(context: Context): TranscriptStore? =
        userId(context)?.let { TranscriptStore(context.applicationContext.filesDir, it, json) }

    fun repository(context: Context): TranscriptRepository? =
        store(context)?.let { TranscriptRepository(backend(context), it, json) }

    fun requestSync(context: Context) = BackgroundSync.requestSoon(context.applicationContext)

    fun fileProviderAuthority(context: Context): String = context.packageName + ".fileprovider"
}
