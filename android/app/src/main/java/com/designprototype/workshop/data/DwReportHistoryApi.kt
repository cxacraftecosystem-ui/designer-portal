package com.designprototype.workshop.data

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * The one call behind the report-history screen.
 *
 * ── WHAT IT SERVES, AND WHAT IT DELIBERATELY DOES NOT ────────────────────────────────────────────
 *
 * FACTS, NOT A DIFF. `report_history` returns every recorded export plus every stage row's
 * `createdAt` / `updatedAt` / `deletedAt`, and stops there; the comparison itself is
 * [dwDiffExports], on this device, so a designer flipping between generation 1 and generation 4 on a
 * metered rural connection pays for one request and not one per pair. That split is the endpoint's
 * own design decision and not this client's — see its docstring, and `frontend/lib/reportDiff.ts`,
 * which is the same arithmetic on the other surface.
 *
 * A SUPERSET OF `GET /{id}/exports`, which this app already binds for the WRITE side
 * ([WorkshopRepositoryApi.recordDesignWorkshopExport]). The two differences are the whole reason
 * this one exists: it names WHO generated each file, and it carries the stage timestamps —
 * INCLUDING rows that were DELETED, which `GET /{id}` filters out and which are exactly the change a
 * diff must not miss. A struck-out cost line is invisible in every other payload in this API.
 *
 * ── WHY A SERVICE OF ITS OWN ─────────────────────────────────────────────────────────────────────
 *
 * It is created from [ApiClient.retrofit], which exists for precisely this — "so a feature can
 * declare its OWN typed service without standing up a second HTTP stack beside this one", in that
 * function's words. It therefore keeps the CloudFront 504 retry (a GET is safely retriable, so this
 * call gets it), the bearer header, the field timeouts and the lenient decoder; a hand-rolled OkHttp
 * client here would silently opt out of all four, which is the failure `DwAsrModelEndpointApi` and
 * `DwJoinCardApi` are both written to avoid.
 *
 * It belongs on [WorkshopRepositoryApi] as a twenty-third design-workshop method the next time
 * somebody is editing that file, and moving it is two lines with nothing about the call changing:
 * [WorkshopRepository] holds exactly one instance of this service, built once and lazily, so the
 * only cost of the separate declaration is one connection pool on a screen most designers open a
 * handful of times a year.
 *
 * ── NOTHING HERE IS EVER WRITTEN ─────────────────────────────────────────────────────────────────
 *
 * There is no method on this interface that edits or removes an export row, and there must not be.
 * The checksum is what makes the record evidence, and evidence that can be tidied up is not evidence.
 */
interface DwReportHistoryApi {
    /**
     * Every report ever generated for this workshop, and the timestamps a diff can be built from.
     *
     * [id] IS THE SERVER'S ID AND NOT THE DRAFT STORE'S. A workshop started in a courtyard carries a
     * local id no server has ever seen, and this endpoint has nothing to say about one — see
     * [DW_REPORT_HISTORY_LOCAL_ONLY] for what the screen says instead of asking.
     *
     * NO PAGING PARAMETERS, because the route takes none. It caps itself at the newest hundred
     * exports and two thousand entry rows and REPORTS both truncations
     * ([DwReportHistoryDto.exportsTruncated], [DwReportHistoryDto.entriesTruncated]) rather than
     * letting a client claim a stage was unchanged when the rows that changed it merely did not fit.
     * Both flags are honoured on screen; neither may be ignored.
     */
    @GET("design-workshops/{id}/report-history")
    suspend fun designWorkshopReportHistory(@Path("id") id: String): DwReportHistoryDto
}
