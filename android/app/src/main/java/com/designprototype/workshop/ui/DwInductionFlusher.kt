package com.designprototype.workshop.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.designprototype.workshop.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object DwInductionFlusher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val running = AtomicBoolean(false)

    private val watching = AtomicBoolean(false)

    @VisibleForTesting
    internal val heldForPassword = AtomicBoolean(false)

    @VisibleForTesting
    internal var flushPass: suspend (Context) -> Unit = { DwInductionQueue.flush(it) }

    @VisibleForTesting
    @Volatile
    internal var lastPass: Job? = null
        private set

    fun watch(context: Context) {
        val app = context.applicationContext
        flushNow(app)
        if (!watching.compareAndSet(false, true)) return
        runCatching {
            val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        flushNow(app)
                    }
                }
            )
        }
    }

    fun flushNow(context: Context) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        lastPass = scope.launch {
            try {
                if (mustChangePasswordBlocks(TokenStore(app).getUser())) {
                    heldForPassword.set(true)
                    return@launch
                }
                flushPass(app)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
            } finally {
                running.set(false)
            }
        }
    }

    fun holdForPasswordGate() {
        heldForPassword.set(true)
    }

    fun resumeAfterPasswordGate(context: Context) {
        if (heldForPassword.compareAndSet(true, false)) flushNow(context)
    }
}

class DwInductionFlushProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        runCatching { context?.let { DwInductionFlusher.watch(it) } }
        runCatching { context?.let { com.designprototype.workshop.data.BackgroundSync.ensureScheduled(it) } }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
