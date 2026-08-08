package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

/**
 * The reference lists a REF field picks from, and the durable copy of them that makes a courtyard
 * with no signal survivable.
 *
 * ── WHY THIS FILE EXISTS AT ALL ───────────────────────────────────────────────────────────────
 *
 * A REF field is a foreign key. Before this, the renderer drew it as a text box holding the raw id,
 * with the model named in the help line, because "a picker would need a per-model lookup that cannot
 * be served offline". That reasoning was right about the constraint and wrong about the conclusion.
 * The constraint is real — a designer picks an artisan two weeks into a study, in a village, with the
 * phone in aeroplane mode by then out of habit — but the answer is not to abandon the picker. It is
 * to make the list a DOCUMENT the device owns, exactly the way [WorkshopDraftStore] makes the
 * workshop a document the device owns.
 *
 * So: every successful `GET /design-workshops/{id}/references` is written to `filesDir` under the
 * key it was asked for, and every read is served from that file FIRST. The network is a refresh, not
 * a prerequisite. A designer in a courtyard picks from whatever the phone last saw; a designer who
 * has never once had signal on this handset gets an empty list and is told so in words, rather than
 * a dropdown that opens onto nothing and lets them conclude the artisan is not in the system.
 *
 * ── THE CACHE KEY, AND WHY IT IS NOT SIMPLY THE WORKSHOP ID ───────────────────────────────────
 *
 * The registry gives every ref field a scope (see [FieldDto.refScope]). WORKSHOP-scoped lists — the
 * sketches of THIS workshop, its prototypes, its participants — are meaningless in another workshop
 * and are keyed by workshop id. ALL-scoped lists — the artisan register, the craft register, the
 * documented products of a cluster — are the same list wherever they are asked for, so they are
 * keyed by the model alone and SHARED across every workshop on the device.
 *
 * That sharing is not a space optimisation, it is the feature. Stage 3 builds the roster from the
 * ALL-scoped artisan register. A designer who starts a brand-new workshop in a village — a workshop
 * with no server id at all, created offline, that could not possibly fetch anything — still gets the
 * artisan register that some earlier workshop on this same phone downloaded, and can therefore pick
 * a real artisan record rather than retyping a name that will never join to anything.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────────────────────
 *
 * No expiry. A stale artisan list is worth immeasurably more than no artisan list, and there is no
 * clock on a phone that can tell "two weeks old because nothing changed" from "two weeks old because
 * there has been no signal for two weeks". [DwReferenceList.fetchedAt] is recorded and SHOWN so the
 * designer can judge it; nothing in this file ever deletes on the strength of it.
 */

/** One option in a reference dropdown, as the server serves it and as the device stores it. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DwReferenceOption(
    /** The id that is stored in the REF field. This is the join key; everything else is display. */
    val id: String = "",
    /** What a human reads in the list. */
    val label: String = "",
    /**
     * A second line — a village, a code, a date — that disambiguates two artisans called Ram.
     *
     * THE SERVER CALLS THIS `sublabel` and always has (`_reference_payload`, and the web reads
     * `option.sublabel`). Declared under the local name `hint`, which is what the picker's UI calls
     * it, so the wire name is asserted here rather than silently mismatched — a defaulted field whose
     * key never appears in the payload decodes to "" for ever and reports nothing.
     *
     * [JsonNames] keeps the OLD name readable so a device that already holds a cached list written by
     * a previous build does not lose every second line the moment it updates. A stale artisan list is
     * worth immeasurably more than no artisan list; the same reasoning applies to half of one.
     */
    @SerialName("sublabel") @JsonNames("hint") val hint: String = "",
    /**
     * The value of the parent field this option belongs under, for cascading pickers.
     *
     * `productRef` declares `refFilterBy = "artisanRef"`, so each product option carries the id of
     * the artisan who made it here. THE CLIENT NEEDS ITS OWN COPY OF THIS and cannot rely on the
     * server having filtered: offline, the cached list is the whole model and the narrowing has to
     * happen on the device. See [DwReferenceList.filteredBy].
     */
    val filterValue: String = "",
    /**
     * The record's own values, keyed by the FIELD KEYS of the entity being filled in.
     *
     * This is what "choosing an option hydrates the row" means. The registry marks the hydrated
     * fields with `fromref(...)` — `name`, `village`, `phone`, `specialisation` and the rest — and
     * they are hydrated by matching key, so a field added to both the reference model and the entity
     * starts hydrating with no client change at all.
     */
    val data: JsonObject = JsonObject(emptyMap()),
)

/**
 * One cached list: the options plus enough provenance to say honestly how old they are.
 *
 * Serialisable as a whole because the file on disk IS this class — writing the raw response instead
 * would leave the device unable to say when it arrived, and "when did this phone last see the
 * artisan register" is the one question a designer looking at a short list actually needs answered.
 */
@Serializable
data class DwReferenceList(
    val model: String = "",
    /** The value of the parent field these options were narrowed to, or blank for the whole model. */
    val filteredBy: String = "",
    val items: List<DwReferenceOption> = emptyList(),
    /** ISO-8601, device clock. Displayed, never used to decide whether the cache may be used. */
    val fetchedAt: String = "",
) {
    /**
     * The options this list holds for one parent value.
     *
     * Applied on the DEVICE even when the server was asked to filter, and the redundancy is the
     * point: a cached whole-model list and a cached narrowed list are stored under different keys
     * but either may be what the picker has on hand, and a product list that was cached whole must
     * still narrow to one artisan when the phone has no signal to re-ask. An option carrying no
     * [DwReferenceOption.filterValue] at all is KEPT rather than dropped — a server that does not
     * populate the field would otherwise empty every cascading dropdown in the app, which is a far
     * worse failure than showing a few options too many.
     */
    fun narrowedTo(parentValue: String): List<DwReferenceOption> {
        if (parentValue.isBlank()) return items
        return items.filter { it.filterValue.isBlank() || it.filterValue == parentValue }
    }
}

/**
 * The wire shape of `GET /design-workshops/{id}/references`.
 *
 * ── THE FIELD BELOW IS NAMED `options` AND MUST STAY NAMED `options` ─────────────────────────────
 *
 * It was called `items`, and because every property here is defaulted and the Retrofit converter is
 * built with `ignoreUnknownKeys = true`, that one wrong word did not fail a request, log a line or
 * drop a warning anywhere. It decoded a perfectly good 50-record payload into an empty list, on every
 * one of the registry's 22 REF fields, on every workshop, online and offline alike. What a designer
 * saw was the picker's honest-looking empty state — "No records on this device yet" — and the only
 * thing left to do about it was to type the artisan's name into a free-text field, which is precisely
 * the outcome `FieldDto.refFilterBy` says the REF type exists to prevent: the workshop's baseline can
 * no longer be joined to the record it was measured from, and nobody notices until someone compares a
 * cluster's second workshop against its first and finds thirty free-text rows.
 *
 * `_reference_payload` in `design_workshops.py` is the authority for these names, and the web reads
 * the same six (`payload.options`, `payload.scopedToWorkshop`, `payload.truncated`, …).
 */
@Serializable
data class DwReferenceResponseDto(
    val model: String = "",
    /** ALL | WORKSHOP, as the registry declared it for the field being filled in. */
    val scope: String = "",
    val options: List<DwReferenceOption> = emptyList(),
    /**
     * True when the server stopped short of the whole list.
     *
     * SAID OUT LOUD IN THE UI when it is set, because a truncated list looks exactly like a complete
     * one to the person scrolling it, and the conclusion they draw — "this artisan is not in the
     * system, I will type the name in" — is the conclusion that destroys the join key.
     */
    val truncated: Boolean = false,
    /**
     * False on a WORKSHOP-scoped field when this workshop is not linked to the cluster the list would
     * have been narrowed to, so what came back is the whole model rather than this workshop's part of
     * it. Carried so the picker can say which of the two it is showing instead of implying the
     * narrow one — the web says exactly this at `StageReferenceField.tsx`.
     */
    val scopedToWorkshop: Boolean = false,
    /**
     * True when the server actually applied the cascade filter. `filtered = true` with an EMPTY list
     * is a real and ordinary answer — the parent record was typed in by hand and has no children —
     * and it reads identically to "the list failed to load" unless the flag is carried.
     */
    val filtered: Boolean = false,
)

/**
 * One number the server claims to have read, AFTER its own filtering.
 *
 * Every 12-digit candidate that reaches here has already survived the UIDAI Verhoeff checksum in
 * `backend/app/services/identity_ocr.py` — roughly nine misreads in ten die there. The client checks
 * it again anyway ([ArtisanIdentity.aadhaarError]) because a number that satisfies the server's rule
 * and fails the handset's would mean the two ports have drifted, and the moment to find that out is
 * before a designer is offered the number, not after it is stored.
 */
@Serializable
data class DwIdentityCandidateDto(
    /** The digits themselves, unconfirmed. NEVER written to a field without a human tap. */
    val value: String = "",
    /** AADHAAR | PEHCHAN. Shown so a misclassification is visible rather than silent. */
    val kind: String = "",
    /** 0..0.95 — the server clamps below 1.0 on purpose. Shown as words, not a percentage. */
    val confidence: Double = 0.0,
    /** AADHAAR only: "XXXX XXXX 9012", the ONLY form any surface but the confirm panel may print. */
    val masked: String = "",
)

/**
 * The wire shape of `POST /design-workshops/ocr/identity`.
 *
 * THESE FIELD NAMES ARE THE SERVER'S, CHECKED AGAINST IT RATHER THAN REMEMBERED. This DTO previously
 * declared `number`, `documentType`, `name`, `confidence` and `message` — none of which the endpoint
 * has ever sent. `IdentityOcrResult.payload()` returns `aadhaarCandidates` / `pehchanCandidates` /
 * `rejectedAadhaarCount` / `provider` / `requiresConfirmation`, and because this client decodes with
 * `ignoreUnknownKeys = true` the mismatch did not throw: every field defaulted, `number` came back
 * "", and a PERFECT read was reported to the designer as "no number could be read from that
 * photograph". The card looked unreadable; the reader was simply listening on the wrong keys.
 * `DwIdentityOcrWireTest` pins this against the exact JSON the server emits so it cannot drift back.
 */
@Serializable
data class DwIdentityOcrDto(
    /** Verhoeff-valid 12-digit candidates, best first. Empty is an ordinary answer, not an error. */
    val aadhaarCandidates: List<DwIdentityCandidateDto> = emptyList(),
    /** Normalised PM Vishwakarma artisan IDs. No checksum exists for these — see ArtisanIdentity. */
    val pehchanCandidates: List<DwIdentityCandidateDto> = emptyList(),
    /**
     * How many 12-digit runs the model produced that FAILED the checksum.
     *
     * A count and never the values — a rejected candidate is still somebody's misread identity
     * number. Worth showing, because "3 readings were rejected" tells a designer the card was found
     * and misread (move to better light) while "nothing was read" tells them it was not found at
     * all (fill the frame). Those are different next actions.
     */
    val rejectedAadhaarCount: Int = 0,
    /** Which vision provider answered. Diagnostic only; never shown beside a number. */
    val provider: String = "",
    /**
     * The server saying, in the payload, that this is a suggestion and not a commit.
     *
     * Defaults TRUE so that a server which omits it — an older deployment, a proxy that rewrote the
     * body — is read as "a human must confirm this" rather than as permission to write. The client
     * never auto-commits regardless; this is the belt to that braces.
     */
    val requiresConfirmation: Boolean = true,
)

object DwReferenceStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * One lock for the whole store.
     *
     * Two reference fields in the same row warm their caches in the same frame — `artisanRef` and
     * `productRef` are drawn one after the other — and both write a file. Coarse is right for the
     * same reason it is right in [WorkshopDraftStore]: these are single-digit-kilobyte documents and
     * the critical section is a write and a rename.
     */
    private val mutex = Mutex()

    /**
     * A process-lifetime mirror, so a stage screen that redraws forty times does not read forty files.
     *
     * It is a mirror and never the authority: every write goes to disk first and the map second, so a
     * process killed between them loses nothing.
     */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, DwReferenceList>()

    private fun rootDir(context: Context): File =
        File(context.filesDir, "dw-references").apply { mkdirs() }

    /**
     * Which document a (model, scope, workshop, filter) request belongs to.
     *
     * WORKSHOP-scoped lists carry the workshop id; everything else carries `ALL` and is therefore
     * shared by every workshop on the handset — see the note at the top of this file for why that
     * sharing is what makes a brand-new offline workshop usable.
     */
    fun cacheKey(model: String, scope: String, workshopId: String, filterValue: String): String {
        val owner = if (scope.equals("WORKSHOP", ignoreCase = true)) workshopId else "ALL"
        val filter = filterValue.ifBlank { "_" }
        return listOf(model, owner, filter).joinToString("__") { safeName(it) }
    }

    private fun safeName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').take(80).ifBlank { "unnamed" }

    private fun fileFor(context: Context, key: String): File = File(rootDir(context), "$key.json")

    /**
     * The cached list for one key, or null when this device has never successfully fetched it.
     *
     * Null and empty are different answers and the picker draws them differently. Null is "we have
     * never seen this list"; an empty [DwReferenceList] is "the server told us there is nothing here",
     * which for a workshop whose roster has not been built yet is the correct and final answer.
     */
    suspend fun load(context: Context, key: String): DwReferenceList? {
        memory[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, key)
                if (!file.exists()) return@withLock null
                val text = runCatching { file.readText() }.getOrNull()
                if (text.isNullOrBlank()) return@withLock null
                // A cache file this build cannot read is DELETED rather than quarantined, and that is
                // the one place this store differs from the draft store. A draft is irreplaceable
                // fieldwork; a reference list is a copy of something the server still has, so the
                // cheap recovery is to drop it and re-fetch, and keeping it would mean the picker
                // fails to parse the same bytes on every single composition forever.
                runCatching { json.decodeFromString(DwReferenceList.serializer(), text) }
                    .onSuccess { memory[key] = it }
                    .onFailure { runCatching { file.delete() } }
                    .getOrNull()
            }
        }
    }

    /**
     * Replace one cached list.
     *
     * AN EMPTY FETCH DOES NOT OVERWRITE A NON-EMPTY CACHE. A server that answers `[]` because a
     * permission check quietly failed, or because a workshop id was wrong, would otherwise wipe the
     * artisan register off a phone that is about to lose signal for three days — and the designer
     * would find a dropdown with nothing in it and no way to understand why. The refusal is logged
     * into the returned value: the caller gets back what is actually cached, not what it just tried
     * to store.
     */
    suspend fun store(context: Context, key: String, list: DwReferenceList): DwReferenceList {
        val existing = load(context, key)
        if (list.items.isEmpty() && existing != null && existing.items.isNotEmpty()) return existing
        val stamped = list.copy(fetchedAt = Instant.now().toString())
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, key)
                val temp = File(file.parentFile, "${file.name}.tmp")
                runCatching {
                    // Write-then-rename, the same discipline draft.json uses. A process killed
                    // mid-write must not leave a half-written file where a whole one was: the picker
                    // would then delete it on the next read (see [load]) and the designer would lose
                    // a list they had, offline, for a write that was only a refresh.
                    temp.writeText(json.encodeToString(DwReferenceList.serializer(), stamped))
                    if (file.exists()) file.delete()
                    temp.renameTo(file)
                }.onFailure { runCatching { temp.delete() } }
            }
        }
        memory[key] = stamped
        return stamped
    }

    /**
     * The label to show for an id that is already stored, looked up across every cached list.
     *
     * A row saved last week holds `artisanRef = "a3f1…"` and nothing else. Without this the reopened
     * form shows a designer a hex string where a name should be, and the natural response — clearing
     * it and picking again — silently rewrites a join key that was correct.
     */
    fun labelFor(id: String): String? {
        if (id.isBlank()) return null
        memory.values.forEach { list ->
            list.items.firstOrNull { it.id == id }?.let { return it.label.ifBlank { null } }
        }
        return null
    }

    /**
     * Everything cached for one model, whatever it was filtered by, merged and de-duplicated.
     *
     * Used when the picker has no cache under its exact key but the device does hold the whole model
     * from some earlier, unfiltered fetch. Narrowing that on the device (see
     * [DwReferenceList.narrowedTo]) is how a cascading product dropdown works on a phone that has
     * never once been asked for "the products of artisan a3f1" specifically.
     */
    suspend fun anyForModel(context: Context, model: String, scope: String, workshopId: String): DwReferenceList? {
        val whole = load(context, cacheKey(model, scope, workshopId, ""))
        if (whole != null) return whole
        val prefix = safeName(model) + "__"
        val merged = withContext(Dispatchers.IO) {
            runCatching { rootDir(context).listFiles() }.getOrNull().orEmpty()
                .filter { it.name.startsWith(prefix) }
                .mapNotNull { file ->
                    runCatching {
                        json.decodeFromString(DwReferenceList.serializer(), file.readText())
                    }.getOrNull()
                }
        }
        if (merged.isEmpty()) return null
        return DwReferenceList(
            model = model,
            filteredBy = "",
            items = merged.flatMap { it.items }.distinctBy { it.id },
            // The OLDEST of the parts, not the newest. The list is only as trustworthy as its stalest
            // component, and a merged list that reports the freshest timestamp is a merged list that
            // lies about how much of it is two weeks old.
            fetchedAt = merged.mapNotNull { it.fetchedAt.takeIf(String::isNotBlank) }.minOrNull().orEmpty(),
        )
    }
}

/**
 * A stored REF value as text, tolerating both shapes the field has ever held.
 *
 * A REF used to be typed by hand into a plain text box, so drafts on real handsets hold bare strings.
 * They must keep working: a build that only understood the picker's output would show a blank field
 * where a designer had typed an id, and the first save would erase it.
 */
fun dwRefId(value: JsonElement?): String = when (value) {
    is JsonPrimitive -> value.content
    else -> DwValues.text(value)
}
