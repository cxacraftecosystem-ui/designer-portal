package com.designprototype.workshop.data

/**
 * A count and its noun, agreed: "1 record", "3 records". [many] defaults to [one] + "s"; pass it for
 * irregulars ("person" / "people"). Never "record(s)" (UX audit DA-38).
 */
fun plural(count: Int, one: String, many: String = one + "s"): String = "$count ${if (count == 1) one else many}"
