package com.designprototype.workshop.data

import retrofit2.http.GET
import retrofit2.http.Path

interface DwReportHistoryApi {
    @GET("design-workshops/{id}/report-history")
    suspend fun designWorkshopReportHistory(@Path("id") id: String): DwReportHistoryDto
}
