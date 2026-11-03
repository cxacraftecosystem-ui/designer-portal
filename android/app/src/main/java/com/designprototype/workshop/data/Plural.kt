package com.designprototype.workshop.data

fun plural(count: Int, one: String, many: String = one + "s"): String = "$count ${if (count == 1) one else many}"
