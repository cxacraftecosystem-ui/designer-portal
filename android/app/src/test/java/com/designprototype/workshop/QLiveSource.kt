package com.designprototype.workshop

import java.io.File

fun qLiveSource(relative: String): File =
    generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, relative), File(it, "android/$relative")) }
        .first { it.isFile }
