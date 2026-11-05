package com.designprototype.workshop.explorer.portal

import android.content.Context
import androidx.compose.runtime.Composable
import com.designprototype.workshop.BuildConfig
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.explorer.data.ExplorerApi
import com.designprototype.workshop.explorer.ui.ExplorerOffline
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object ExplorerPortal {
    const val NAME = "designer-portal"
    const val ROOT_NAME = "Repository"
    const val WEB_BASE = "https://designer-repository.vercel.app"
    const val PRODUCT = "Design Workshop"
    const val DOWNLOAD_FOLDER = "DesignWorkshop"
    const val REPORT_WAIT_SECONDS = 150L

    val statusOptions: List<Pair<String, String>> = emptyList()
    val defaultStatus: String? = null

    fun statusParam(choice: String?): String? = null

    fun baseUrl(): String = BuildConfig.DEFAULT_API_BASE_URL

    private fun retrofit(context: Context): Retrofit = ApiClient.retrofit(TokenStore(context.applicationContext))

    private fun patient(context: Context): Retrofit {
        val base = retrofit(context)
        val client = (base.callFactory() as OkHttpClient).newBuilder().readTimeout(REPORT_WAIT_SECONDS, TimeUnit.SECONDS).build()
        return base.newBuilder().client(client).build()
    }

    fun api(context: Context): ExplorerApi = patient(context).create(ExplorerApi::class.java)

    fun client(context: Context): OkHttpClient = retrofit(context).callFactory() as OkHttpClient

    fun token(context: Context): String? = TokenStore(context.applicationContext).getToken()

    fun userId(context: Context): String? = runCatching { TokenStore(context.applicationContext).getUser()?.id }.getOrNull()

    @Composable
    fun rememberOffline(mediaId: String?): ExplorerOffline? = null
}
