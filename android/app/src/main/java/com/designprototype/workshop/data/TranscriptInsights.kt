package com.designprototype.workshop.data

import java.io.IOException
import retrofit2.HttpException

const val TRANSCRIPT_INSIGHTS_BATCH_MAX = 50

const val MEDIA_BATCH_MAX = 100

suspend fun <T> insightOrHidden(call: suspend () -> T): T? =
    try {
        call()
    } catch (_: HttpException) {
        null
    } catch (_: IOException) {
        null
    }

object TranscriptInsightStates {
    const val READY = "READY"
    const val QUEUED = "QUEUED"
    const val PROCESSING = "PROCESSING"
    const val FAILED = "FAILED"

    fun isWorking(view: TranscriptInsightViewDto?): Boolean =
        view?.state == QUEUED || view?.state == PROCESSING

    fun isShown(view: TranscriptInsightViewDto?): Boolean = when (view?.state) {
        READY -> view.insight != null
        QUEUED, PROCESSING -> true
        FAILED -> !view.error.isNullOrBlank()
        else -> false
    }

    fun hasTranslation(insight: TranscriptInsightDto): Boolean =
        (insight.translation.status == "COMPLETED" || insight.translation.status == "PARTIAL") &&
            !insight.translation.text.isNullOrBlank()

    fun provenanceLine(insight: TranscriptInsightDto, formatDate: (String?) -> String?): String {
        val edited = insight.edited
        if (edited != null) {
            return listOfNotNull("Edited by a researcher", formatDate(edited.at)).joinToString(" · ")
        }
        val model = insight.provenance.modelId.takeIf { it.isNotBlank() && it != "UNRECORDED" } ?: "model not recorded"
        return listOfNotNull("Machine-generated", model, formatDate(insight.provenance.generatedAt)).joinToString(" · ")
    }
}
