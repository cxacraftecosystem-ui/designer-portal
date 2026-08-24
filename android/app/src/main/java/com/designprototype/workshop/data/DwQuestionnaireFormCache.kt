package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

/**
 * THE CUSTOM QUESTIONNAIRE, AS A DOCUMENT THIS DEVICE OWNS — so that a designer with no signal can
 * OPEN the 24-section instrument instead of being shown an error where it should be.
 *
 * ── WHAT WAS BROKEN, AND WHAT WAS NOT ─────────────────────────────────────────────────────────
 *
 * The custom questionnaire was the one capture surface on this handset that could not even be
 * OPENED without a connection. `QuestionnaireAnswerScreen` hit the network to load the form and a
 * failure produced a `loadError`; `QuestionnaireDetailScreen` did the same. Hundreds of questions
 * across 24 sections, and a designer with no bars saw a red line.
 *
 * THE REFUSAL TO SAVE OFFLINE IS NOT WHAT WAS BROKEN AND IS NOT TOUCHED HERE. That refusal is
 * argued, and the argument is sound: see the KDoc on `QuestionnaireAnswerScreen` and the custom
 * questionnaires block in [WorkshopRepository]. Whether a question may still be answered is a fact
 * only the server holds — it can be retired between opening the screen and saving — so a queued
 * batch would have to be either refused later, losing the sitting the designer believed was
 * recorded, or re-attached to whatever wording replaced it, which fabricates evidence.
 *
 * But that argument is about WRITING, and it was used to justify the read side too. A designer who
 * cannot open the form cannot review what a colleague recorded this morning, cannot read the
 * question they are about to ask out loud, and cannot see the section they had reached. None of
 * that writes anything. So the read is now served from a copy of the last successful download, and
 * the write stays exactly as refused as it was.
 *
 * ── THE VERSION IS THE POINT, NOT AN EXTRA ────────────────────────────────────────────────────
 *
 * [WorkshopRepository]'s own note says what a future offline write would need: "If this feature ever
 * does go offline it needs the questionnaire VERSION as its precondition, not a retry loop."
 * [CachedQuestionnaireForm.version] is that precondition, recorded now, at the only moment it is
 * knowable — the moment the form was downloaded. This file does not spend it. It records it, shows
 * it, and leaves the decision to whoever writes that wave; a cache without it would have to be
 * thrown away and re-fetched before the write could ever be built, which is the same as not having
 * cached anything.
 *
 * ── WHY IT IS KEYED BY `includeRetired` AS WELL AS BY ID ──────────────────────────────────────
 *
 * Because `includeRetired` is not a preference, it is WHICH SCREEN IS ASKING (see the KDoc on
 * [CustomQuestionnaireDto]). The answer screen must never be handed a retired question — the server
 * refuses the save with a 422 naming it — and the read/edit screen must never be denied one, because
 * a retired question still has answers hanging off it. One cache file for both would hand one of the
 * two screens exactly the list it must not have, and the failure would be silent: a retired wording
 * offered for a new answer looks like an ordinary question.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────────────────────
 *
 * NO EXPIRY, on [DwReferenceStore]'s reasoning, which applies here word for word: there is no clock
 * on a phone that can tell "three days old because nothing changed" from "three days old because
 * there has been no signal for three days". [CachedQuestionnaireForm.fetchedAt] is recorded and SHOWN
 * so the designer can judge it; nothing in this file ever deletes on the strength of it.
 *
 * NO WRITE PATH BACK. Nothing in this file is ever sent anywhere. It is the same kind of object
 * [DwQuestionnaireStore] is — read-only evidence — and it is a separate file from that one for a
 * reason worth stating: that store deliberately holds "no question ids, no section ids and no answer
 * ids, which is precisely what a save would need". This one holds all of them, because a screen
 * cannot render a form without them. So this one is the more dangerous object of the two, and the
 * only thing standing between it and the fabricated-evidence failure above is that no save consults
 * it. Do not add one here.
 */

/** The wrapper written to disk: the form, when it was fetched, and the shape of the file. */
@Serializable
data class CachedQuestionnaireForm(
    /**
     * The shape of THIS FILE, not the questionnaire's own [CustomQuestionnaireDto.version].
     *
     * Bumped when the stored shape changes incompatibly. A file written at a HIGHER version than
     * this build knows is ignored rather than decoded — the same discipline
     * [WorkshopDraftStore] applies with its quarantine, and for the same reason: a build that
     * half-understands a newer file renders a form with fields silently missing, and a form missing
     * a section is indistinguishable from a questionnaire that never had one.
     */
    val schemaVersion: Int = QUESTIONNAIRE_FORM_CACHE_VERSION,
    /** ISO-8601, when this copy crossed the network. SHOWN to the designer, never acted on. */
    val fetchedAt: String = "",
    /** Whether retired questions are present, i.e. which screen this copy was fetched for. */
    val includeRetired: Boolean = false,
    /** The questionnaire's own version at the moment of the fetch. The write's precondition. */
    val version: Int = 0,
    val form: CustomQuestionnaireDto,
)

/** See [CachedQuestionnaireForm.schemaVersion]. */
const val QUESTIONNAIRE_FORM_CACHE_VERSION = 1

/** What a read produced, and whether the network was involved. */
data class QuestionnaireFormRead(
    val form: CustomQuestionnaireDto,
    /** True when this came off the disk because the network could not be reached. */
    val fromCache: Boolean,
    /** ISO-8601 fetch time, only when [fromCache]; null for a live read. */
    val cachedAt: String? = null,
)

object DwQuestionnaireFormCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private fun dir(context: Context): File =
        File(context.filesDir, "questionnaire-forms").apply { mkdirs() }

    /**
     * One file per (questionnaire, includeRetired) pair.
     *
     * The id is sanitised even though every id this repository issues is a cuid: this string reaches
     * a filesystem path, and an id arriving from a payload one day with a `/` or a `..` in it would
     * be a write somewhere nobody looked. The same reasoning as `safeDownloadName`.
     */
    private fun file(context: Context, id: String, includeRetired: Boolean): File {
        val key = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        return File(dir(context), "$key-${if (includeRetired) "all" else "active"}.json")
    }

    /**
     * Keep this copy. Best-effort: a full disk must never turn reading a questionnaire into an error
     * about a cache the designer did not ask for.
     *
     * Written temp-then-rename, exactly as [OfflineOutbox.write] does and for the identical reason —
     * a `writeText` truncates first, so a process killed in that window leaves a half-written file,
     * and a half-written form is a form with sections missing.
     */
    suspend fun put(
        context: Context,
        form: CustomQuestionnaireDto,
        includeRetired: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val target = file(context, form.id, includeRetired)
                val temp = File(target.parentFile, "${target.name}.writing")
                val bytes = json.encodeToString(
                    CachedQuestionnaireForm(
                        fetchedAt = Instant.now().toString(),
                        includeRetired = includeRetired,
                        version = form.version,
                        form = form,
                    )
                ).toByteArray()
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    runCatching { temp.delete() }
                    throw IOException("Unable to replace ${target.name}")
                }
                true
            }.getOrDefault(false)
        }
    }

    /** The stored copy, or null when there is not one this build can read. */
    suspend fun get(
        context: Context,
        id: String,
        includeRetired: Boolean,
    ): CachedQuestionnaireForm? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = file(context, id, includeRetired)
            if (!target.isFile) return@withLock null
            val cached = runCatching {
                json.decodeFromString<CachedQuestionnaireForm>(target.readText())
            }.getOrNull() ?: return@withLock null
            // A file from the future is not read. See CachedQuestionnaireForm.schemaVersion.
            if (cached.schemaVersion > QUESTIONNAIRE_FORM_CACHE_VERSION) return@withLock null
            cached
        }
    }

    /**
     * The stored copy under EITHER key, preferring [preferred].
     *
     * ── WHY THIS EXISTS, AND WHY ONLY ONE CALLER MAY USE IT ────────────────────────────────────
     *
     * The two-file split above is not negotiable for a screen that RENDERS the form: one of the two
     * screens must never be handed a retired question and the other must never be denied one, and the
     * failure is silent either way. But it made the offline handoff unbuildable on the path it was
     * designed for. `buildQuestionnaireHandoffFile` read `includeRetired = false`, so it could only
     * ever find the `-active` file — and the only writer of that file is the ANSWER screen, while the
     * handoff card lives on the DETAIL screen, which warms `-all`. A designer who opened the
     * questionnaire, went out of signal and tapped "Make the file" was told the questionnaire "could
     * not be made into a file", having done exactly what the KDoc told them was enough.
     *
     * IT IS SAFE FOR THAT ONE CALLER AND FOR NO OTHER, and the reason is structural rather than
     * careful: `questionnaireBundleOf` filters to `isActive && supersededById == null` itself, so a
     * retired wording present in an `-all` copy cannot reach the bundle. A caller that renders the
     * form has no such filter and must keep asking for its own key.
     */
    suspend fun getEither(
        context: Context,
        id: String,
        preferred: Boolean,
    ): CachedQuestionnaireForm? = get(context, id, preferred) ?: get(context, id, !preferred)

    /** Forget one questionnaire's copies — used when it is deleted, never on a clock. */
    suspend fun forget(context: Context, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { file(context, id, includeRetired = true).delete() }
            runCatching { file(context, id, includeRetired = false).delete() }
            Unit
        }
    }

    /**
     * Forget EVERY questionnaire's copies. Called on sign-out and from nowhere else.
     *
     * ── WHAT WAS WRONG WITHOUT IT ─────────────────────────────────────────────────────────────
     *
     * [WorkshopRepository.customQuestionnaireCached] claims that a questionnaire this account may no
     * longer read "is never served out of this device's memory of when they could", and it earns that
     * for a revoked grant — a 403 is the server ANSWERING, so the cache is not consulted. It did not
     * earn it across a change of person. `logout()` cleared the token and nothing else, so a second
     * designer signing in on a shared handset was served the first one's cached form — and
     * [CustomQuestionnaireDto.entries] carry `respondentName` and the answers given.
     *
     * BEST-EFFORT AND NEVER FATAL. Sign-out must complete: a designer handing the phone to somebody
     * else cannot be held signed in because a file would not delete.
     */
    suspend fun forgetAll(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { dir(context).listFiles()?.forEach { file -> file.delete() } }
            Unit
        }
    }
}

/**
 * The sentence shown above a form that came off the disk.
 *
 * PURE, so it is pinned by a test rather than by taking a handset somewhere with no signal. It says
 * three things and all three are load-bearing: that this is a copy, WHEN it was taken, and that
 * answers cannot be saved from it — that last one before the designer starts asking questions out
 * loud, not after they have filled in a section.
 *
 * @param version the questionnaire's version as of the copy. Shown because it is the one number that
 *   tells a designer whether the form has moved on since — and because if a colleague says "I added
 *   four questions yesterday", the version is how they find out this copy predates them.
 */
fun cachedQuestionnaireNotice(cachedAt: String?, version: Int): String {
    // `readableStamp`, because the one question this sentence exists to answer — "is this copy older
    // than the four questions my colleague added this morning?" — is a question about the time of day,
    // and the stored stamp is a UTC instant. Printed raw it was both unreadable and, in the field,
    // five and a half hours wrong.
    val whenPart = readableStamp(cachedAt)?.let { " on $it" }.orEmpty()
    return "You are reading the copy this phone downloaded$whenPart (version $version). You can read " +
        "it and check what has already been recorded. ANSWERS CANNOT BE SAVED without a connection: " +
        "whether a question may still be answered is something only the server knows, so this app " +
        "will not record an answer it might have to attach to different wording later."
}
