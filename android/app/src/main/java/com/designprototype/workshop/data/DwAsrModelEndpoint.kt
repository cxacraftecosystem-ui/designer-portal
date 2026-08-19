package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

/**
 * **THIS DEPLOYMENT'S OWN COPY OF THE SPEECH MODEL — THE THIRD ROUTE IN, AND THE ONE THAT NEEDS NO
 * CABLE AND NO ARCHIVE READER.**
 *
 * `backend/app/api/routes/asr_models.py` has served the two pinned files, per file and unpacked,
 * since 2026-08-13. Nothing in this app has ever asked it for one: `docs/ASR-MODEL-HOSTING.md`
 * recorded the blocker in as many words — "`DwAsrModelArtifact` `require`s a single `https://`
 * **container** URL, while this endpoint serves **per file**… So the client half needs a per-file
 * artifact shape before any app surface can fetch from here." This file is that shape, and serving
 * per file is what deletes the `.tar.bz2` problem outright rather than working around it: there is
 * no archive to open, so [DwAsrContainerFormat] and the bzip2 refusal simply stop applying on this
 * route.
 *
 * ── WHY IT IS PURE, AND WHAT PURE MEANS HERE ──────────────────────────────────────────────────
 *
 * No `Context`, no okhttp, no `java.io`, no Compose — the same split `DwAsrModelInstall.kt` and
 * `DwAsrModelInstallUi.kt` already keep, for the reason that file states: the untestable half is the
 * half that is wrong. Everything here runs on a desktop JVM, so the two decisions that cost a
 * designer 365 MB if they are wrong — what path is built, and whether the fetch is worth starting —
 * are decided by functions a test can ask.
 *
 * ── **THE MANIFEST IS NOT THE TRUST ANCHOR, AND MUST NEVER BECOME ONE** ───────────────────────
 *
 * `docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md` §1 argues that the digest a handset trusts is the constant
 * compiled into the APK, and its own amendment names the exact condition that voids the argument: "a
 * client that verifies against the manifest instead of against its own constant". A manifest is a
 * statement by the SENDER about the bytes it is about to send; checking bytes against it verifies
 * the sender against itself and proves nothing at all.
 *
 * So [dwAsrManifestVerdict] is written with a REFUSE-ONLY contract, stated on the function and
 * restated here because it is the thing a plausible-looking optimisation deletes:
 * [DwAsrManifestVerdict.AGREES] means "worth spending the bytes" and NEVER "the file is good".
 * Nothing downstream may skip the on-disk SHA-256 against [DwAsrModelFile.sha256] because of it.
 * `DwAsrModelEndpointTest` holds a case named so it cannot be deleted casually.
 *
 * ── AND THE WHOLE ROUTE IS DARK UNTIL SOMEBODY PROVISIONS THE ORIGIN ──────────────────────────
 *
 * `Settings.asr_model_dir` is `Field(default=None, alias="ASR_MODEL_DIR")` and its own comment says
 * "UNSET by
 * default, which means /api/asr-models reports every artifact as unpublished and the byte routes
 * answer 503". Both `.env.example` files leave it commented out, and nothing in this repository
 * shows the production origin setting it or the 365 MB being placed there. **So on the fleet today
 * every handset that asks will be told nothing is published**, will derive
 * [DwAsrEndpointState.NOT_PUBLISHED], and will fall through to exactly the card it draws now. That
 * is deliberate, and it is why the container route in `DwAsrModelInstallUi.kt` is NOT deleted: the
 * client ships first and says so honestly, the operator step (docs/ASR-MODEL-HOSTING.md §3) comes
 * second, and anyone can check which of the two has happened with one authenticated
 * `GET /api/asr-models` against the deployment's base URL showing `available: true`. Docker is down
 * in the environment this was written in, so that could not be exercised here.
 */

// ---------------------------------------------------------------------------------------------
// The paths, built once and checked
// ---------------------------------------------------------------------------------------------

/**
 * The route prefix, as `backend/app/api/router.py` mounts it under the app's own `/api`.
 *
 * A CONSTANT AND NOT A LITERAL AT THREE CALL SITES, because it is a compiled fact on a device
 * nobody can redeploy. `_file_url` on the server builds its manifest URLs with `request.url_for(...)`
 * precisely so a moved prefix cannot drift from where the route actually is; an APK has no such
 * protection, which is why `backend/tests/test_asr_model_download.py` should read this file off disk
 * and assert the template against the path FastAPI resolves for the route named `asr_model_file`.
 * That test is named in this lane's hand-offs; it is not in a file this lane owns.
 */
private const val DW_ASR_ENDPOINT_PREFIX = "asr-models"

/**
 * Whether a string may be used as one segment of a path this app builds. **Stricter than the two
 * checks already in this package, deliberately.**
 *
 * `asr_artifacts._is_bare_name` on the server rejects blank, `/`, `\`, `..` AND a bare `.`;
 * [DwAsrModelFile] and [DwAsrModelArtifact] both `require` the first four and NOT the last, so a
 * bare `"."` passes on Android today. That is a small, separate defect in two constructors — noted
 * here rather than fixed here, because widening those `require`s changes what a catalogue row is
 * allowed to be, and the two checks being different is exactly the thing a reader needs told rather
 * than a claim that they are "the same check".
 *
 * This helper matches the SERVER, because the strings it guards address a directory inside this
 * app's own internal storage, and `.` as a file name resolves to the directory itself.
 */
private fun dwAsrIsBarePathSegment(value: String): Boolean =
    value.isNotBlank() &&
        '/' !in value &&
        '\\' !in value &&
        ".." !in value &&
        value != "."

private fun requireBare(value: String, what: String): String {
    require(dwAsrIsBarePathSegment(value)) {
        "“$value” is not a bare $what. These strings are joined onto a URL this app fetches from " +
            "and onto a file inside this app's own internal storage, so a separator, a `..` or a " +
            "bare `.` here would address something other than the one directory this feature owns."
    }
    return value
}

/**
 * `asr-models/{modelId}` — the SINGLE-artifact manifest route, relative to the API base.
 *
 * The single artifact and not the catalogue, because one row is all this build pins
 * ([DW_ASR_MODELS]) and it is a smaller body on a connection that is about to be asked for 365 MB.
 */
fun dwAsrModelManifestPath(modelId: String): String =
    "$DW_ASR_ENDPOINT_PREFIX/${requireBare(modelId, "model id")}"

/**
 * `asr-models/{modelId}/files/{fileName}` — the BYTES route, relative to the API base.
 *
 * **THIS FUNCTION INVENTS NOTHING.** Both arguments are already pinned in this APK as
 * [DwAsrModel.modelId] and [DwAsrModelFile.fileName], and both are byte-for-byte the server's
 * `artifact_id` and `file_name` — `backend/tests/test_asr_model_download.py`'s
 * `test_the_apk_pins_the_same_files_this_deployment_serves` reads this package off disk and asserts
 * exactly that. The server looks the file name up by exact match in its own catalogue and never
 * joins it onto a path, so the check above is this side's half of one rule rather than a duplicate.
 */
fun dwAsrModelFilePath(modelId: String, fileName: String): String =
    "${dwAsrModelManifestPath(modelId)}/files/${requireBare(fileName, "file name")}"

/**
 * Join a relative endpoint path onto the API base, normalising to exactly one `/`.
 *
 * `BuildConfig.DEFAULT_API_BASE_URL` already ends in `/api/`, but a base typed into
 * `local.properties` by a developer may not, and `"…/api" + "asr-models/…"` is a 404 an hour into a
 * field trip rather than a compile error.
 *
 * **DROPPING [DwAsrModelArtifact]'s `require(url.startsWith("https://"))` HERE IS NOT A SECURITY
 * RELAXATION**, and it looks exactly like one, so: that constructor guards a URL a release builder
 * TYPES into a catalogue, where a plaintext host is a decision somebody could make by accident. This
 * URL is not typed anywhere — it is `BuildConfig.DEFAULT_API_BASE_URL`, a build constant, and what
 * actually enforces the transport is `AndroidManifest`'s `usesCleartextTraffic=false` plus
 * `network_security_config.xml`, which permits plain HTTP for 10.0.2.2, 127.0.0.1 and localhost and
 * nothing else. That is what lets `adb reverse` serve a handset probe over cleartext while a release
 * build cannot reach a plaintext host at all. A `startsWith("https://")` here would break the probe
 * and buy nothing the platform is not already enforcing.
 */
fun dwAsrModelEndpointUrl(apiBaseUrl: String, path: String): String {
    val base = apiBaseUrl.trimEnd('/')
    val tail = path.trimStart('/')
    return "$base/$tail"
}

/** The absolute URL of one pinned file on this deployment. See [dwAsrModelFilePath]. */
fun dwAsrModelFileUrl(apiBaseUrl: String, modelId: String, fileName: String): String =
    dwAsrModelEndpointUrl(apiBaseUrl, dwAsrModelFilePath(modelId, fileName))

// ---------------------------------------------------------------------------------------------
// What the manifest route answers with
// ---------------------------------------------------------------------------------------------

/**
 * One file, as `_file_payload` builds it. **Written against the literal keys, because there is no
 * pydantic response model on the other side** — these are hand-built dicts, and note the camelCase,
 * which does not follow the snake_case of most of this API.
 *
 * [bytes] and [sha256] ARE NULLABLE AND THAT IS THE INTERESTING CASE, not a defensive default. The
 * server nulls them unless its own on-disk verdict is ready, and `_file_payload`'s docstring says
 * why: "They are never filled in from the catalogue: a manifest that answered with the size and
 * digest it WISHED the file had would be describing something that is not there." A client that
 * defaulted them to 0 and "" would turn that honesty back into a claim.
 */
@Serializable
data class DwAsrManifestFile(
    val fileName: String = "",
    val url: String = "",
    val mediaType: String = "",
    val bytes: Long? = null,
    val sha256: String? = null,
    val available: Boolean = false,
    val unavailableReason: String? = null,
    val detail: String? = null,
)

/**
 * One published artifact, as `_artifact_payload` builds it.
 *
 * Decoded with `ApiClient.json`, whose `ignoreUnknownKeys` is what stops a field added to this
 * payload on the server from breaking every installed handset — the same guarantee that file's own
 * docstring records for the download manifest.
 */
@Serializable
data class DwAsrManifestArtifact(
    val artifactId: String = "",
    val version: String = "",
    val quantisation: String = "",
    val available: Boolean = false,
    val unavailableReason: String? = null,
    val detail: String? = null,
    val totalBytes: Long? = null,
    val files: List<DwAsrManifestFile> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// What this deployment turned out to be
// ---------------------------------------------------------------------------------------------

/**
 * What the deployment answered when this app last asked whether it serves the model.
 *
 * [UNKNOWN] IS FIRST AND IS NEVER RENDERED AS "not published", the same rule
 * [DwAsrModelState.UNKNOWN], [DwAsrRuntimeState.UNKNOWN] and [DwPackState.UNKNOWN] all follow: the
 * app has not asked yet, and not-yet-looked is not not-there. It falls through to the container
 * branch of [dwAsrModelOffer] rather than being treated as a refusal.
 *
 * **THE REFUSALS ARE DIFFERENT NEXT MOVES AND ARE DELIBERATELY NOT ONE VALUE.** A card that
 * collapses them tells a designer standing in an office with bad Wi-Fi that their deployment has no
 * model, and they stop asking. Try again; sign in again; phone whoever manages accounts; phone the
 * administrator; update the app.
 */
enum class DwAsrEndpointState {
    /** Not asked yet, or the app had no connection to ask over. Claims nothing either way. */
    UNKNOWN,

    /** Asked, and could not get an answer at all — a timeout, a gateway, a dropped socket. NOT "no model". */
    UNREACHABLE,

    /**
     * **HTTP 401. The session lapsed; signing in again fixes it.**
     *
     * Its own value rather than folded into [UNREACHABLE] or [FORBIDDEN], and this repository already
     * keeps the distinction twice and says why: `WorkshopRepository`'s sync note — "401 — the
     * credentials really are wrong. Deliberately never merged with the five above" — and
     * `WorkshopSync`'s "401: the credential expired, not the item… re-signing in". Merged into "the
     * server said no", a designer retries a sign-in that cannot help, or fails to try the one that can.
     *
     * It matters most DURING a transfer: this is 365 MB with a 60-minute call timeout, and a session
     * can lapse inside that window. A 401 there keeps the part-files — the bytes that arrived are
     * good, and a fresh sign-in carries on from them rather than re-spending them.
     */
    SESSION_LAPSED,

    /**
     * **HTTP 403. This account will never be allowed, and only whoever manages accounts can change it.**
     *
     * The gate is `can_run_design_workshops`, and `backend/app/core/deps.py` is emphatic that it is
     * the one predicate in that file which is not a rank threshold: "This one is a SET — Designer,
     * Admin, Master Admin — which means a PROFESSOR cannot run one even though they outrank a
     * designer." The card quotes the server's own 403 sentence rather than writing a second one,
     * because two independently-worded explanations of a counter-intuitive rule will drift, and the
     * server's is worded around the trap (it deliberately does not say "or above").
     */
    FORBIDDEN,

    /**
     * Asked, allowed, and this deployment has not been given the bytes — `available: false`.
     *
     * **TODAY'S EXPECTED ANSWER ON THE FLEET**, because `ASR_MODEL_DIR` is unset by default. The
     * cable route still works and is what the card names.
     */
    NOT_PUBLISHED,

    /**
     * **HTTP 404 — this deployment's BUILD does not know the artifact id this APK pins.**
     *
     * Not [NOT_PUBLISHED], and the difference has a different owner. The route's own docstring calls
     * a 404 here "a client bug, not a 503" and answers "This deployment publishes no speech model
     * called “{artifact_id}”." In the field that is an app/server version skew, and telling that
     * designer "a cable still works, ask your administrator" sends them to somebody who will find
     * the bytes present and correct. `test_the_apk_pins_the_same_files_this_deployment_serves`
     * guards the id within one commit; a handset carrying an older APK is the case no test can catch.
     */
    VERSION_SKEW,

    /** Asked, allowed, and the manifest agrees with what this APK pins. The fetch is worth starting. */
    PUBLISHES,
}

/**
 * What a manifest reading is allowed to CONCLUDE. **Every value except [AGREES] is a refusal.**
 *
 * See the file header: this type may only ever stop a fetch, never authorise the bytes that come
 * back from one.
 */
enum class DwAsrManifestVerdict {
    /**
     * Worth spending the bytes — **and nothing more than that.**
     *
     * It does NOT mean the file is good, has not been substituted, or may be loaded. The only thing
     * in this app that establishes any of those is a SHA-256 taken off this phone's own disk in this
     * run and compared with [DwAsrModelFile.sha256]. See `dwAsrReadInstalledModel`.
     */
    AGREES,

    /** No manifest at all — the read failed, or this deployment answered about a different artifact. */
    ARTIFACT_ABSENT,

    /** The deployment has the row and not the bytes: `available: false`, or a null size or digest. */
    NOT_PUBLISHED,

    /** A file this APK pins is absent from the manifest. What is published is not this model. */
    FILE_MISSING,

    /** A pinned file's published size differs from the constant in this APK. */
    DISAGREES_ON_SIZE,

    /** A pinned file's published digest differs from the constant in this APK. **The loud one.** */
    DISAGREES_ON_DIGEST,
}

/**
 * Whether the manifest agrees with what this APK pins, **so a mismatch is found at one JSON read
 * rather than at 365 MB.**
 *
 * That is the manifest's entire stated purpose — `backend/app/services/asr_artifacts.py`'s module
 * docstring says "It lets a client fail before spending the bytes" — and it is the whole of what
 * this function is for.
 *
 * **REFUSE-ONLY. READ THE FILE HEADER BEFORE CHANGING THIS.** [DwAsrManifestVerdict.AGREES] is
 * permission to open a socket, never permission to trust a byte. If you are here intending to have
 * the download verify against `manifest.sha256` because it is right there and it would save reading
 * a constant: that verifies the file against its own sender, and
 * `docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md` §1 names that exact change as the one that voids its
 * central argument. It would look like a simplification and would pass every happy-path test.
 *
 * A NULL DIGEST IS [DwAsrManifestVerdict.NOT_PUBLISHED] AND NEVER [DwAsrManifestVerdict.AGREES],
 * because the server nulls it when the file is not ready — an absent claim is not a matching one.
 */
fun dwAsrManifestVerdict(
    manifest: DwAsrManifestArtifact?,
    pinned: DwAsrModel,
): DwAsrManifestVerdict {
    if (manifest == null) return DwAsrManifestVerdict.ARTIFACT_ABSENT
    if (manifest.artifactId != pinned.modelId) return DwAsrManifestVerdict.ARTIFACT_ABSENT
    if (!manifest.available) return DwAsrManifestVerdict.NOT_PUBLISHED

    val published = manifest.files.associateBy { it.fileName }
    pinned.files.forEach { want ->
        val got = published[want.fileName] ?: return DwAsrManifestVerdict.FILE_MISSING
        if (!got.available) return DwAsrManifestVerdict.NOT_PUBLISHED
        val bytes = got.bytes ?: return DwAsrManifestVerdict.NOT_PUBLISHED
        val digest = got.sha256?.trim().orEmpty()
        if (digest.isEmpty()) return DwAsrManifestVerdict.NOT_PUBLISHED
        if (bytes != want.bytes) return DwAsrManifestVerdict.DISAGREES_ON_SIZE
        // Through the real verifier and not `==`, so the fail-closed rules in [dwAsrVerify] apply
        // here too: a malformed digest can never be read as agreement by comparing strings directly.
        if (dwAsrVerify(want.sha256, digest) != DwAsrVerification.VERIFIED) {
            return DwAsrManifestVerdict.DISAGREES_ON_DIGEST
        }
    }
    return DwAsrManifestVerdict.AGREES
}

/**
 * The verdict as a state the card can render, for the HTTP 200 case only.
 *
 * Split out so the status-code mapping (401/403/404/anything else) lives with the transport and this
 * half stays pure. Everything that is not [DwAsrManifestVerdict.AGREES] renders as
 * [DwAsrEndpointState.NOT_PUBLISHED]: a deployment serving a DIFFERENT model is, to this handset, a
 * deployment with no model it can use. The verdict itself is kept beside it by the caller, because
 * "disagrees on digest" is the one an administrator has to be told about and the card's sentence
 * names it.
 */
fun dwAsrEndpointStateOf(verdict: DwAsrManifestVerdict): DwAsrEndpointState =
    if (verdict == DwAsrManifestVerdict.AGREES) {
        DwAsrEndpointState.PUBLISHES
    } else {
        DwAsrEndpointState.NOT_PUBLISHED
    }
