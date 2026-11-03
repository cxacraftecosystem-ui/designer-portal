package com.designprototype.workshop.ui

import com.designprototype.workshop.data.apiErrorMessage
import retrofit2.HttpException

enum class WorkshopAccessQueueView {
    PENDING,

    HISTORY,
}

data class WorkshopAccessQueueFailure(
    val refused: Boolean,
    val message: String,
)

fun Throwable.workshopAccessQueueFailure(): WorkshopAccessQueueFailure {
    val http = this as? HttpException
    return WorkshopAccessQueueFailure(
        refused = http?.code() == 403,
        message = apiErrorMessage("No reason was given."),
    )
}

data class WorkshopAccessQueueNotice(
    val heading: String,
    val body: String,
    val answered: Boolean,
)

fun workshopAccessQueueNotice(
    view: WorkshopAccessQueueView,
    failure: WorkshopAccessQueueFailure?,
    rowsOnScreen: Int,
): WorkshopAccessQueueNotice? {
    if (failure != null) {
        val stale = if (rowsOnScreen > 0) {
            " This list may be out of date — some may already have been answered."
        } else {
            ""
        }
        return if (failure.refused) {
            WorkshopAccessQueueNotice(
                heading = "You are not being shown this queue",
                body = "Only an admin or the master admin can see this queue. Researchers may be waiting. If you could before, sign out and back in. ${failure.message}$stale",
                answered = false,
            )
        } else {
            WorkshopAccessQueueNotice(
                heading = "The queue could not be read",
                body = "Offline — can't tell if anyone is waiting. Requests are kept online and safe; try again where there is signal. ${failure.message}$stale",
                answered = false,
            )
        }
    }
    if (rowsOnScreen > 0) return null
    return when (view) {
        WorkshopAccessQueueView.PENDING -> WorkshopAccessQueueNotice(
            heading = "Nothing waiting",
            body = "All requests answered.",
            answered = true,
        )

        WorkshopAccessQueueView.HISTORY -> WorkshopAccessQueueNotice(
            heading = "No requests yet",
            body = "Nobody has asked for access to a workshop.",
            answered = true,
        )
    }
}
