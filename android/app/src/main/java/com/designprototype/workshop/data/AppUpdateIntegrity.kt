package com.designprototype.workshop.data

import java.io.File

const val DW_UPDATE_DOWNLOAD_RETRY_MESSAGE =
    "Couldn't download the update. Check your connection and try again."

internal fun dwApkSizeMismatch(expectedBytes: Long?, actualBytes: Long): Boolean =
    expectedBytes != null && expectedBytes > 0L && expectedBytes != actualBytes

internal fun dwRequireWholeApk(file: File, expectedBytes: Long?): File {
    if (!dwApkSizeMismatch(expectedBytes, file.length())) return file
    runCatching { file.delete() }
    throw IllegalStateException(DW_UPDATE_DOWNLOAD_RETRY_MESSAGE)
}
