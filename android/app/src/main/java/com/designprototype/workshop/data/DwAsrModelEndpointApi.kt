package com.designprototype.workshop.data

import retrofit2.http.GET
import retrofit2.http.Path

interface DwAsrModelEndpointApi {
    @GET("asr-models/{artifactId}")
    suspend fun asrModel(@Path("artifactId") artifactId: String): DwAsrManifestArtifact
}
