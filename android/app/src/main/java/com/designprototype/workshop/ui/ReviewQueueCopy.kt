package com.designprototype.workshop.ui

fun reviewQueueCutNotice(truncated: Boolean, shown: Int, total: Int, cap: Int): String? {
    if (!truncated) return null
    if (shown == 0) return "Couldn't list the $total waiting records. Try again."
    if (total <= shown) return "More waiting — Load older to see them."
    return "$shown of $total waiting, newest first."
}

const val REVIEW_QUEUE_PAGE_SIZE = 50
