package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TWO DECISIONS THAT COST A DESIGNER 365 MB IF THEY ARE WRONG, ASKED ON A DESKTOP JVM.
 *
 * `DwAsrModelEndpoint.kt` is pure for the reason `DwAsrModelInstall.kt` states about itself — the
 * untestable half is the half that is wrong — and this file is the half that would otherwise only be
 * exercised by a designer standing in a district office with a prepaid bundle.
 *
 *   1. WHAT PATH IS BUILT. A separator, a `..` or a bare `.` reaching a `File()` inside this app's
 *      own internal storage, or a URL joined with a doubled or missing slash, is a fault nothing
 *      catches until the fetch 404s an hour in.
 *   2. WHETHER THE FETCH IS WORTH STARTING. A published digest that differs from the constant in
 *      this APK has to be found at ONE JSON READ, which is the manifest's entire stated purpose.
 *      `backend/app/services/asr_artifacts.py`'s module docstring: "It lets a client fail before
 *      spending the bytes."
 *
 * And one thing this file exists to make hard to break rather than to verify: the manifest may only
 * ever REFUSE. See the last test, which is named so it cannot be deleted casually.
 */
class DwAsrModelEndpointTest {

    /**
     * THE PINNED MODEL ITSELF, not a fixture, and that is deliberate.
     *
     * The path assertions below are about the strings THIS APK will actually put on the wire, and a
     * hand-written `DwAsrModel("test-model", …)` would assert that the helper works on a string
     * nobody ships. `backend/tests/test_asr_model_download.py` reads the same constants off disk from
     * the other side.
     */
    private val pinned = DW_ASR_MODELS.single()

    private fun manifestOf(
        artifactId: String = pinned.modelId,
        available: Boolean = true,
        files: List<DwAsrManifestFile> = pinned.files.map { file ->
            DwAsrManifestFile(
                fileName = file.fileName,
                url = "/api/${dwAsrModelFilePath(pinned.modelId, file.fileName)}",
                mediaType = "application/octet-stream",
                bytes = file.bytes,
                sha256 = file.sha256,
                available = true,
            )
        },
    ) = DwAsrManifestArtifact(
        artifactId = artifactId,
        version = "2025-11-12",
        quantisation = pinned.quantisation,
        available = available,
        totalBytes = files.sumOf { it.bytes ?: 0L },
        files = files,
    )

    // ── 1. The path ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the file path is the one this deployment mounts`() {
        assertEquals(
            "asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/" +
                "files/model.int8.onnx",
            dwAsrModelFilePath(pinned.modelId, "model.int8.onnx"),
        )
        assertEquals(
            "asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            dwAsrModelManifestPath(pinned.modelId),
        )
        // AND IT IS BUILT FROM WHAT THE APK PINS, not from a literal typed here twice. Both files in
        // the catalogue must produce a path; a helper that only works for the one somebody happened
        // to test with is the shape of defect this asks about.
        pinned.files.forEach { file ->
            assertTrue(
                "the pinned name did not reach the path: ${file.fileName}",
                dwAsrModelFilePath(pinned.modelId, file.fileName).endsWith("/files/${file.fileName}"),
            )
        }
    }

    /**
     * NOTHING THAT IS NOT A BARE NAME BECOMES A PATH — the server's `_is_bare_name`, ported.
     *
     * This helper is deliberately STRICTER than [DwAsrModelFile]'s and [DwAsrModelArtifact]'s own
     * `require`s, which test blank, `/`, `\` and `..` and NOT a bare `.`. So the `"."` case below is
     * asserting behaviour this helper implements itself rather than inherits, which is why it is
     * written out rather than assumed — and why the two looser constructors are named in
     * `DwAsrModelEndpoint.kt` as a separate, small thing somebody may want to close.
     */
    @Test
    fun `a name that is not a bare name never becomes a path`() {
        val refused = listOf("", "   ", ".", "..", "a/b", "a\\b", "../etc", "x/../y")
        refused.forEach { bad ->
            assertTrue(
                "a model id of “$bad” produced a path instead of throwing",
                runCatching { dwAsrModelFilePath(bad, "model.int8.onnx") }.isFailure,
            )
            assertTrue(
                "a file name of “$bad” produced a path instead of throwing",
                runCatching { dwAsrModelFilePath(pinned.modelId, bad) }.isFailure,
            )
        }
        // The manifest path takes the same argument and must refuse it identically — a check on one
        // of two entry points is a check somebody routes around.
        assertTrue(runCatching { dwAsrModelManifestPath("..") }.isFailure)
    }

    @Test
    fun `the base url gains exactly one slash, with or without a trailing one`() {
        val expected =
            "https://d3ekigkotd1xa2.cloudfront.net/api/asr-models/${pinned.modelId}/files/tokens.txt"
        assertEquals(
            expected,
            dwAsrModelFileUrl(
                "https://d3ekigkotd1xa2.cloudfront.net/api/", pinned.modelId, "tokens.txt",
            ),
        )
        assertEquals(
            expected,
            dwAsrModelFileUrl(
                "https://d3ekigkotd1xa2.cloudfront.net/api", pinned.modelId, "tokens.txt",
            ),
        )
        // The cleartext base a handset probe uses through `adb reverse`, which
        // network_security_config.xml permits for 127.0.0.1 and nothing else. It must join the same
        // way; a probe that could not be pointed anywhere is a route nobody ever executes.
        assertEquals(
            "http://127.0.0.1:8000/api/asr-models/${pinned.modelId}/files/tokens.txt",
            dwAsrModelFileUrl("http://127.0.0.1:8000/api/", pinned.modelId, "tokens.txt"),
        )
    }

    // ── 2. The verdict ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a manifest matching what this apk pins is worth spending the bytes on`() {
        assertEquals(DwAsrManifestVerdict.AGREES, dwAsrManifestVerdict(manifestOf(), pinned))
        assertEquals(
            DwAsrEndpointState.PUBLISHES,
            dwAsrEndpointStateOf(dwAsrManifestVerdict(manifestOf(), pinned)),
        )
    }

    /**
     * THE CASE THAT WOULD OTHERWISE BE FOUND AT 365 MB RATHER THAN AT ONE JSON READ.
     *
     * A digest that differs from the constant in this APK means this deployment is serving a
     * different file under the name this app asks for. Discovering that after the transfer costs a
     * prepaid bundle and produces `DW_ASR_MODEL_MISMATCH_SENTENCE`, which is the right sentence at
     * the wrong moment. The manifest exists so it is discovered before the socket opens, and
     * `asr_artifacts.py` says so: "It lets a client fail before spending the bytes."
     */
    @Test
    fun `a published digest that differs from the pinned one refuses the fetch`() {
        val wrongDigest = manifestOf(
            files = pinned.files.map { file ->
                DwAsrManifestFile(
                    fileName = file.fileName,
                    bytes = file.bytes,
                    // A well-formed SHA-256 that is not this one: the refusal must not depend on the
                    // substitute being malformed.
                    sha256 = "0".repeat(64),
                    available = true,
                )
            },
        )
        assertEquals(
            DwAsrManifestVerdict.DISAGREES_ON_DIGEST,
            dwAsrManifestVerdict(wrongDigest, pinned),
        )

        val wrongSize = manifestOf(
            files = pinned.files.map { file ->
                DwAsrManifestFile(
                    fileName = file.fileName,
                    bytes = file.bytes + 1L,
                    sha256 = file.sha256,
                    available = true,
                )
            },
        )
        assertEquals(DwAsrManifestVerdict.DISAGREES_ON_SIZE, dwAsrManifestVerdict(wrongSize, pinned))
    }

    /**
     * A NULL SIZE OR DIGEST IS "not published" AND NEVER "agrees".
     *
     * `_file_payload` nulls both unless its own on-disk verdict is ready, and its docstring says why:
     * "They are never filled in from the catalogue: a manifest that answered with the size and digest
     * it WISHED the file had would be describing something that is not there." An absent claim is not
     * a matching one, and a client that read null as "no disagreement" would fetch from an origin
     * that has nothing to serve and take a 503 at the first byte.
     */
    @Test
    fun `an unpublished or silent manifest never agrees`() {
        assertEquals(
            DwAsrManifestVerdict.NOT_PUBLISHED,
            dwAsrManifestVerdict(manifestOf(available = false), pinned),
        )

        val nullDigest = manifestOf(
            files = pinned.files.map {
                DwAsrManifestFile(fileName = it.fileName, bytes = it.bytes, sha256 = null, available = true)
            },
        )
        assertEquals(DwAsrManifestVerdict.NOT_PUBLISHED, dwAsrManifestVerdict(nullDigest, pinned))
        assertNotEquals(DwAsrManifestVerdict.AGREES, dwAsrManifestVerdict(nullDigest, pinned))

        val nullBytes = manifestOf(
            files = pinned.files.map {
                DwAsrManifestFile(fileName = it.fileName, bytes = null, sha256 = it.sha256, available = true)
            },
        )
        assertEquals(DwAsrManifestVerdict.NOT_PUBLISHED, dwAsrManifestVerdict(nullBytes, pinned))

        val oneFileUnavailable = manifestOf(
            files = pinned.files.mapIndexed { index, file ->
                DwAsrManifestFile(
                    fileName = file.fileName,
                    bytes = file.bytes,
                    sha256 = file.sha256,
                    available = index != 0,
                )
            },
        )
        assertEquals(
            "one unavailable file is not most of a yes",
            DwAsrManifestVerdict.NOT_PUBLISHED,
            dwAsrManifestVerdict(oneFileUnavailable, pinned),
        )
    }

    @Test
    fun `a manifest about a different model, or none at all, is not this model`() {
        assertEquals(DwAsrManifestVerdict.ARTIFACT_ABSENT, dwAsrManifestVerdict(null, pinned))
        assertEquals(
            DwAsrManifestVerdict.ARTIFACT_ABSENT,
            dwAsrManifestVerdict(manifestOf(artifactId = "some-other-export"), pinned),
        )
        // Present, available, and missing a file this APK needs. A model is not one file: a graph
        // with no vocabulary decodes to nothing, and a vocabulary with no graph decodes nothing.
        assertEquals(
            DwAsrManifestVerdict.FILE_MISSING,
            dwAsrManifestVerdict(
                manifestOf(
                    files = listOf(
                        DwAsrManifestFile(
                            fileName = pinned.files.first().fileName,
                            bytes = pinned.files.first().bytes,
                            sha256 = pinned.files.first().sha256,
                            available = true,
                        )
                    ),
                ),
                pinned,
            ),
        )
    }

    /**
     * **A MANIFEST THAT AGREES DOES NOT MARK ANYTHING INSTALLED OR VERIFIED. DO NOT DELETE THIS.**
     *
     * `docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md` §1 argues that the digest a handset trusts is the
     * constant compiled into the APK, and its own amendment names the exact condition that voids the
     * argument: "a client that verifies against the manifest instead of against its own constant".
     * That change would look like a simplification — the digest is right there in the payload, and
     * reading it saves nothing but is one fewer lookup — and it would pass every happy-path test in
     * this file and every other, because on a healthy deployment the two strings are equal.
     *
     * What it would actually do is verify the file against its own sender.
     *
     * So this asserts the boundary in the only place a pure test can reach it: agreement produces a
     * verdict and an endpoint state, and NOTHING that any part of this app accepts as evidence a
     * model may be loaded. [dwAsrModelMayLoad] is the only permission to decode, it reads
     * [DwAsrModelStatus.verifiedSha256], and that list is only ever filled by `dwAsrReadInstalledModel`
     * hashing bytes off this phone's own disk.
     */
    @Test
    fun `a manifest that agrees does not mark anything installed`() {
        val verdict = dwAsrManifestVerdict(manifestOf(), pinned)
        assertEquals(DwAsrManifestVerdict.AGREES, verdict)

        assertEquals(DwAsrEndpointState.PUBLISHES, dwAsrEndpointStateOf(verdict))

        // Nothing in the endpoint module produces a status at all, and the strongest thing a caller
        // may honestly hold after reading an agreeing manifest is still "not installed" — the only
        // thing an agreement buys is permission to open a socket.
        val afterReadingTheManifest = DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED, pinned)
        assertNotEquals(DwAsrModelState.INSTALLED, afterReadingTheManifest.state)
        assertTrue(
            "a manifest agreement was accepted as permission to decode",
            !dwAsrModelMayLoad(afterReadingTheManifest),
        )

        // AND THE CONSTRUCTOR ITSELF REFUSES THE SHORTCUT. Handing it the digests the MANIFEST
        // published — which on a healthy deployment are byte-for-byte the pinned ones — is exactly
        // the "optimisation" above, and the invariant that stops it is that `verifiedSha256` means
        // "hashed off disk in THIS run". This assertion cannot tell the two lists apart, which is
        // the honest limit of a pure test and the reason `dwAsrReadInstalledModel` is where the rule
        // actually lives: what it CAN hold is that an empty list is never enough.
        assertTrue(
            "a status with nothing hashed was accepted as installed",
            runCatching {
                DwAsrModelStatus(DwAsrModelState.INSTALLED, pinned, verifiedSha256 = emptyList())
            }.isFailure,
        )
    }
}
