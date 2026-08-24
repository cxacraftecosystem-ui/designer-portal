package com.designprototype.workshop.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WHAT ACTUALLY SENDS A SCAN THAT WAS TAKEN WITH NO SIGNAL.
 *
 * ── THE BUG THIS FILE EXISTS TO CLOSE ─────────────────────────────────────────────────────────
 *
 * `DwInductionQueue.flush` had **no caller anywhere in `src/main`**. A card scanned in a courtyard
 * with no signal was written to `dw-pending-inductions.json` and stayed there for ever, while
 * `dwJoinQueuedMessage` told the designer "the request goes out on its own as soon as there is
 * signal … You do not need to scan again". They did. The only thing that could recover the scan was
 * the same person happening to re-scan the same card while online, and the sentence on screen
 * actively told them not to.
 *
 * ── WHY A ContentProvider, WHICH LOOKS LIKE A STRANGE PLACE FOR THIS ──────────────────────────
 *
 * Because a provider declared in the manifest is instantiated by the platform BEFORE the first
 * activity exists, on every process start, with no code in `MainActivity` and no `Application`
 * subclass. That is not a trick invented here: it is exactly how `WorkManager`, Firebase and
 * `androidx.startup` bootstrap themselves, and `androidx.startup` is a library whose entire content
 * is one such provider.
 *
 * It matters that no activity is involved. The requirement is that a scan is sent when connectivity
 * returns — not when somebody happens to open the right screen — and a hook that lived in a composable
 * would drain the queue only for a designer who navigated back to the scanner. A workshop's Cards &
 * tags screen also calls [flushNow] when its lookup panel appears, and that is belt-and-braces for the
 * force-stopped case rather than the mechanism.
 *
 * ── AND THE HONEST LIMIT, STATED RATHER THAN LEFT FOR SOMEBODY TO FIND ────────────────────────
 *
 * **This runs only while the process is alive.** If Android kills the app before signal returns,
 * nothing is sent until the app is next opened — at which point this provider runs again and the
 * queue drains. `dwJoinQueuedMessage` says both halves out loud for that reason.
 *
 * The mechanism that would remove that limit is `WorkManager` with a `NetworkType.CONNECTED`
 * constraint, which survives process death and reboots. It is deliberately NOT used here: it means
 * adding `androidx.work` to a build whose APK size is argued line by line in `build.gradle.kts`, and
 * a dependency added to make one sentence stronger is a decision for whoever owns that budget. **If
 * this queue is ever asked to carry something that cannot wait for the next launch, that is the
 * change to make** — and the two entry points below are what it would replace.
 *
 * ── IT MUST NEVER SLOW DOWN A COLD START ──────────────────────────────────────────────────────
 *
 * [onCreate] registers one callback and returns. Nothing reads a file, touches the network or blocks:
 * the flush itself runs on [Dispatchers.IO] behind an [AtomicBoolean] latch, and its first act is to
 * read a queue that is empty on virtually every launch. Every part of it fails softly — a device that
 * will not give out a `ConnectivityManager` is a device where the old behaviour (send on the next
 * scan) is what happens, which is where this started.
 */
object DwInductionFlusher {

    /**
     * A scope for the life of the process, deliberately not tied to any screen.
     *
     * `SupervisorJob` so one row's failure cannot cancel the pass, and `Dispatchers.IO` because every
     * step is a file read or a socket. There is no `cancel()` anywhere: the work this does is bounded
     * by the queue, which is one row in the ordinary case and three at worst, and a scope cancelled on
     * some screen's disposal is exactly the bug this file was written to fix.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ONE PASS AT A TIME, ACROSS THE WHOLE PROCESS.
     *
     * Without this, a network callback firing while a screen's own flush was in flight would send the
     * same row twice. The server is idempotent about that — the redemption's `@@unique([tokenId,
     * userId])` returns the first outcome, and the ask's own unique index makes a replay a no-op — so
     * the cost is two requests rather than a wrong answer. It is still worth not doing: the second
     * request would be racing the first one's `clear`, and a row cleared between a send and its
     * answer is a row whose outcome nothing reports.
     */
    private val running = AtomicBoolean(false)

    /** Registered once per process. Guarded because two provider instances is not impossible. */
    private val watching = AtomicBoolean(false)

    /**
     * Start watching for connectivity, and drain whatever is already waiting.
     *
     * THE IMMEDIATE FLUSH IS THE HALF THAT MATTERS MOST, and it is why this is called from a provider
     * rather than from a connectivity callback alone: the ordinary case is a designer who scanned
     * offline yesterday, walked into signal, and opened the app today. No transition ever fires,
     * because the network was already up when the process started.
     */
    fun watch(context: Context) {
        val app = context.applicationContext
        flushNow(app)
        if (!watching.compareAndSet(false, true)) return
        runCatching {
            val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    /**
                     * SIGNAL RETURNED. `onAvailable` and not `onCapabilitiesChanged`, because the
                     * latter fires repeatedly for signal-strength and metering changes on a network
                     * that was already up — which would mean a flush attempt every few seconds on a
                     * moving vehicle.
                     *
                     * `ConnectivityObserver.isOnline` is still consulted inside the send, and it asks
                     * for `NET_CAPABILITY_VALIDATED` rather than merely "connected", so a captive
                     * portal that has answered `onAvailable` does not turn into a failed request.
                     */
                    override fun onAvailable(network: Network) {
                        flushNow(app)
                    }
                }
            )
        }
    }

    /**
     * Send everything waiting, unless a pass is already running.
     *
     * ANSWERS NOTHING AND REPORTS NOTHING, deliberately. There is no screen to report to at process
     * start, and a notification saying "your request has now been sent" would be the app volunteering
     * a claim about somebody's access at a moment nobody asked — including the conditional "if that
     * workshop exists" the ask route's sentence is carefully phrased around. What the outcome is used
     * for is the queue: a terminal answer drops the row, and anything else leaves it for next time.
     */
    fun flushNow(context: Context) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                DwInductionQueue.flush(app)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // SWALLOWED ON PURPOSE, AND THE QUEUE IS WHY IT IS SAFE. Every row whose send did not
                // reach a terminal answer is still on disk with its attempt count moved, so the next
                // pass tries it again. There is nothing here that a crash would tell anybody: the
                // person whose scan it is has already been shown `dwJoinQueuedMessage`.
            } finally {
                running.set(false)
            }
        }
    }
}

/**
 * The manifest hook that starts [DwInductionFlusher] at process start. **It stores nothing.**
 *
 * A ContentProvider with no data, which is the `androidx.startup` pattern: the platform instantiates
 * a declared provider before the first activity, so [onCreate] is the earliest ordinary place a
 * library can run code without an `Application` subclass — and this app deliberately has none.
 *
 * `android:exported="false"` in the manifest, and every data method below answers "nothing". A
 * provider that answered a query would be a second, undocumented API surface on this app; these
 * overrides exist because `ContentProvider` is abstract and for no other reason.
 */
class DwInductionFlushProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // `runCatching`, because a provider that throws in `onCreate` takes the whole process with it
        // — and the thing being started is a best-effort background send. The old behaviour (nothing
        // is flushed until the next scan) is an acceptable fallback; failing to launch is not.
        runCatching { context?.let { DwInductionFlusher.watch(it) } }
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
