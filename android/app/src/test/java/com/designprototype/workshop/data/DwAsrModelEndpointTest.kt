package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DwAsrModelEndpointTest {

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
        pinned.files.forEach { file ->
            assertTrue(
                "the pinned name did not reach the path: ${file.fileName}",
                dwAsrModelFilePath(pinned.modelId, file.fileName).endsWith("/files/${file.fileName}"),
            )
        }
    }

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
        assertEquals(
            "http://127.0.0.1:8000/api/asr-models/${pinned.modelId}/files/tokens.txt",
            dwAsrModelFileUrl("http://127.0.0.1:8000/api/", pinned.modelId, "tokens.txt"),
        )
    }

    @Test
    fun `a manifest matching what this apk pins is worth spending the bytes on`() {
        assertEquals(DwAsrManifestVerdict.AGREES, dwAsrManifestVerdict(manifestOf(), pinned))
        assertEquals(
            DwAsrEndpointState.PUBLISHES,
            dwAsrEndpointStateOf(dwAsrManifestVerdict(manifestOf(), pinned)),
        )
    }

    @Test
    fun `a published digest that differs from the pinned one refuses the fetch`() {
        val wrongDigest = manifestOf(
            files = pinned.files.map { file ->
                DwAsrManifestFile(
                    fileName = file.fileName,
                    bytes = file.bytes,
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

    @Test
    fun `a manifest that agrees does not mark anything installed`() {
        val verdict = dwAsrManifestVerdict(manifestOf(), pinned)
        assertEquals(DwAsrManifestVerdict.AGREES, verdict)

        assertEquals(DwAsrEndpointState.PUBLISHES, dwAsrEndpointStateOf(verdict))

        val afterReadingTheManifest = DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED, pinned)
        assertNotEquals(DwAsrModelState.INSTALLED, afterReadingTheManifest.state)
        assertTrue(
            "a manifest agreement was accepted as permission to decode",
            !dwAsrModelMayLoad(afterReadingTheManifest),
        )

        assertTrue(
            "a status with nothing hashed was accepted as installed",
            runCatching {
                DwAsrModelStatus(DwAsrModelState.INSTALLED, pinned, verifiedSha256 = emptyList())
            }.isFailure,
        )
    }
}
