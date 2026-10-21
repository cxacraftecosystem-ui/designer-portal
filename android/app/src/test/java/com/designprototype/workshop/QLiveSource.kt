package com.designprototype.workshop

import java.io.File

/** A file of this repository by its path under `android/`, for the 21 Oct 2026 audit's source checks. */
fun qLiveSource(relative: String): File =
    generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, relative), File(it, "android/$relative")) }
        .first { it.isFile }
