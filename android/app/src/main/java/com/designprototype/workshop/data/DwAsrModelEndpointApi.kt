package com.designprototype.workshop.data

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * The MANIFEST half of `backend/app/api/routes/asr_models.py`, as a typed service.
 *
 * ── WHY THE MANIFEST GOES THROUGH `ApiClient` AND THE 365 MB DOES NOT ─────────────────────────
 *
 * `ApiClient.retrofit`'s own docstring states the house rule and the reason for it: "so a feature can
 * declare its OWN typed service without standing up a second HTTP stack beside this one. A second
 * stack is not a style question here: it would silently opt that feature out of the 504 retry that
 * exists because CloudFront times out this origin." **This interface is that declaration**, and a
 * small authenticated JSON GET through a distribution that times its origin out is precisely the
 * call that retry was written for. It also inherits the auth header and `ApiClient.json`, whose
 * `ignoreUnknownKeys` is what stops a field added to this payload from breaking installed handsets.
 *
 * **THE BYTES ROUTE DELIBERATELY DOES NOT COME THROUGH HERE, AND THAT IS THE ONE CASE THE RULE ABOVE
 * DOES NOT COVER.** `ApiClient.isSafelyRetriable` returns true for every GET and replays it up to
 * four times with backoff. Replaying a 365 MB GET on a district-town connection is the opposite of
 * what that transfer wants, and it would fight the resume logic in `DwAsrModelController` that
 * already handles the failure correctly — the resume asks for the remainder, the retry asks for the
 * whole file again. So the stream keeps the controller's own bespoke client: auth interceptor, no
 * retry interceptor, `callTimeout(60, MINUTES)`. If you are here to "tidy up" by sharing one client
 * between the two, this paragraph is the reason not to.
 *
 * There is no typed declaration of the bytes route at all, on purpose: a Retrofit method returning a
 * `ResponseBody` for a 365 MB file would be a second, easier way to fetch it that skips every guard
 * — the byte cap, `dwResumePlan`, `dwRangeHonoured` and the on-disk digest.
 */
interface DwAsrModelEndpointApi {
    /**
     * `GET /api/asr-models/{artifactId}` — the single-artifact manifest.
     *
     * The single artifact and not the catalogue: one row is all this build pins, and it is a smaller
     * body on the connection that is about to be asked for 365 MB.
     *
     * ANSWERS 200 EVEN WHEN NOTHING IS PUBLISHED, which is why the caller reads `available` rather
     * than treating any 200 as a yes. The route module's docstring gives the reason: "answering it
     * with an error would leave a phone unable to distinguish 'not published' from 'your token
     * expired'." The four status codes that are NOT 200 each mean something different and the client
     * keeps them apart — see [DwAsrEndpointState].
     */
    @GET("asr-models/{artifactId}")
    suspend fun asrModel(@Path("artifactId") artifactId: String): DwAsrManifestArtifact
}
