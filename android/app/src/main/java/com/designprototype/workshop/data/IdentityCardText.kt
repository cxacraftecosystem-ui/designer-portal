package com.designprototype.workshop.data

object IdentityCardText {

    private const val LARGE_LINE_RATIO = 1.25

    data class RecognisedLine(
        val text: String,
        val top: Int = 0,
        val bottom: Int = 0,
    ) {
        internal val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    data class IdentityCardReading(
        val aadhaar: List<String> = emptyList(),
        val rejectedAadhaarCount: Int = 0,
    )

    fun read(lines: List<IdentityCardText.RecognisedLine>): IdentityCardReading {
        val heights = lines.map { it.height }.filter { it > 0 }.sorted()
        val medianHeight = if (heights.isEmpty()) 0 else heights[heights.size / 2]

        val accepted = mutableListOf<Ranked>()
        var rejected = 0
        val seen = mutableSetOf<String>()

        lines.forEach { line ->
            scanDigitRuns(line.text).forEach { run ->
                val digits = run.digits
                if (digits.length != ArtisanIdentity.AADHAAR_LENGTH) return@forEach
                if (!seen.add(digits)) return@forEach
                if (digits[0] == '0' || digits[0] == '1' || !ArtisanIdentity.verhoeffOk(digits)) {
                    rejected += 1
                    return@forEach
                }
                accepted += Ranked(
                    value = digits,
                    alone = line.text.removeRange(run.start, run.end).none { !it.isWhitespace() },
                    large = medianHeight > 0 && line.height >= medianHeight * LARGE_LINE_RATIO,
                )
            }
        }

        return IdentityCardReading(
            aadhaar = accepted.sortedByDescending { it.score }.map { it.value },
            rejectedAadhaarCount = rejected,
        )
    }

    private data class Ranked(val value: String, val alone: Boolean, val large: Boolean) {
        val score: Int get() = (if (alone) 2 else 0) + (if (large) 1 else 0)
    }

    private data class DigitRun(val digits: String, val start: Int, val end: Int)

    private fun scanDigitRuns(text: String): List<DigitRun> {
        val runs = mutableListOf<DigitRun>()
        var i = 0
        while (i < text.length) {
            if (text[i] !in '0'..'9') {
                i += 1
                continue
            }
            val start = i
            val digits = StringBuilder()
            var j = i
            while (j < text.length) {
                val c = text[j]
                when {
                    c in '0'..'9' -> {
                        digits.append(c)
                        j += 1
                    }
                    (c == ' ' || c == '-') && j + 1 < text.length && text[j + 1] in '0'..'9' -> j += 1
                    else -> break
                }
            }
            runs += DigitRun(digits.toString(), start, j)
            i = j
        }
        return runs
    }
}
