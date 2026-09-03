package com.designprototype.workshop.data

import java.io.File

/**
 * **WHETHER THE APK THIS PHONE JUST DOWNLOADED IS THE WHOLE APK.**
 *
 * ── THE GAP THIS CLOSES ───────────────────────────────────────────────────────────────────────
 *
 * `WorkshopRepository.downloadApk` accepted ANY 2xx and handed whatever bytes arrived to the system
 * installer. A response is successful the moment its headers are, and the body is streamed
 * afterwards: a link that dies mid-copy, a proxy that truncates, a captive portal that answers a
 * short HTML page with a 200 — every one of those produces a file on disk, a `File` handed back, and
 * `launchApkInstaller` starting on it. This is the update path for a fleet of field handsets on
 * district-town connections, and it is *forced*: `pendingUpdate` draws a dialog with no "Later" in
 * it, so a designer who cannot get past it cannot use the app at all.
 *
 * The OS then refuses the truncated file — `PackageInstaller` parses and signature-checks it, which
 * is the real integrity boundary and STAYS the boundary; nothing here replaces it. What the OS
 * cannot do is say anything useful. Its refusal arrives as a system dialog about a parse failure,
 * outside this app's control, over a required-update prompt the designer cannot dismiss — and the
 * app's own state has already moved on (`updateBusy = false`, no `updateError`), so the screen
 * behind it looks as though nothing went wrong. The remedy for a short download is to download it
 * again, and that was the one instruction nobody was given.
 *
 * ── WHY A DECLARED SIZE AND NOT A HASH ────────────────────────────────────────────────────────
 *
 * A hash would be strictly better and is not available: nothing in the publish path computes one.
 * `AppRelease` records what the publisher uploaded, and the byte count is the one figure all three
 * publishers already hold — the workflow stats the file (`APK_BYTES`), the browser panel has
 * `File.size`, and `publishAppUpdate` reads `apk.length()` for its own presign. Adding a column that
 * every publisher can fill today is worth more than a stronger check two of them would leave null.
 *
 * A length check catches exactly the failure above — a body that stopped early, or a wrong body
 * entirely — and claims nothing about authenticity. A file of the right length carrying the wrong
 * bytes is a signature problem, and the signature check is the OS's.
 *
 * ── NULL IS NOT A FAILURE, AND THAT IS THE WHOLE COMPATIBILITY STORY ──────────────────────────
 *
 * `AppRelease.sizeBytes` is nullable and every release published before it existed carries null. A
 * phone updating from one of those must behave exactly as it did — the alternative is a fleet that
 * cannot update off the build it is on, which is a worse outcome than the truncation this guards
 * against, and it is unrecoverable without a cable. The same rule, for the same reason, as every
 * defaulted field on [PendingEntry]: an absent fact is not a claim.
 *
 * ── WHY IT IS PURE ────────────────────────────────────────────────────────────────────────────
 *
 * A [File] and a nullable [Long], no Context and no okhttp, for `DwDownload.kt`'s reason: the only
 * way to see this on a handset is to interrupt a 66 MB download at exactly the wrong moment, and by
 * then the designer is looking at a dialog they cannot get out of. A desktop JVM can write a short
 * file in microseconds. Pinned by `AppUpdateIntegrityTest`.
 */

// ---------------------------------------------------------------------------------------------
// What the designer is told, and the one rule that decides whether they are told anything
// ---------------------------------------------------------------------------------------------

/**
 * WHAT THE DESIGNER READS WHEN AN UPDATE DOWNLOAD DID NOT ARRIVE WHOLE.
 *
 * ONE COPY, and this constant is the reason: it is `MainActivity`'s existing fallback for any throw
 * out of the update path, and the throw below deliberately reuses it rather than inventing a second
 * sentence. A truncated body IS a connection failure — the link dropped part-way through 66 MB —
 * so the instruction that was already right for a dead socket is right for this too, and the
 * designer gets one sentence for one situation instead of two spellings of it.
 *
 * IT IS RETRYABLE ON PURPOSE. The update dialog's confirm button re-enables the moment the coroutine
 * finishes (`updateBusy = false`) and the next tap starts a fresh download into a directory this
 * function's caller empties first, so the remedy the sentence names is a remedy the screen offers.
 */
const val DW_UPDATE_DOWNLOAD_RETRY_MESSAGE =
    "Unable to download the update — check your connection and try again."

/**
 * True when [expectedBytes] is known and the file on disk is not that long.
 *
 * Separate from [dwRequireWholeApk] so the rule can be asserted without a file at all, and so the
 * two halves of the decision — *is this wrong* and *what do we do about it* — cannot drift.
 *
 * A size of zero or below is treated as UNKNOWN rather than as a claim that the release is empty:
 * the backend column is `BigInt?` with no floor of its own on old rows, and reading a stray 0 as
 * "this APK should be zero bytes long" would refuse every download of that release for ever.
 */
internal fun dwApkSizeMismatch(expectedBytes: Long?, actualBytes: Long): Boolean =
    expectedBytes != null && expectedBytes > 0L && expectedBytes != actualBytes

/**
 * Verify a downloaded APK against the size its release declared; on a mismatch DELETE it and throw.
 *
 * DELETED BEFORE THE THROW, and the order matters. `downloadApk` empties `cacheDir/updates` at the
 * START of every attempt, so a truncated file left behind would be swept by the next tap anyway —
 * but only if there IS a next tap. Left on disk it is a 40 MB partial APK sitting in the cache of a
 * phone whose storage the photographs need, and it is addressable by path: `launchApkInstaller` is
 * handed a `File`, and any future caller that reached for the last download rather than starting a
 * new one would hand the installer a file this function has already judged incomplete. Nothing that
 * failed its check may survive as something that looks like a result.
 *
 * @return [file], unchanged, whenever there is nothing to object to — so the caller can return the
 *   result of this rather than remembering to return the file itself after calling it.
 */
internal fun dwRequireWholeApk(file: File, expectedBytes: Long?): File {
    if (!dwApkSizeMismatch(expectedBytes, file.length())) return file
    runCatching { file.delete() }
    throw IllegalStateException(DW_UPDATE_DOWNLOAD_RETRY_MESSAGE)
}
