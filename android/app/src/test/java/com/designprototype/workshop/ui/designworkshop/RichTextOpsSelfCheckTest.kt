package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Test

class RichTextOpsSelfCheckTest {

    @Test
    fun `every ladder in the port behaves as the web's does`() {
        val failures = richTextOpsSelfCheck()

        assertEquals(
            "RichTextOps self-check failed:\n  - " + failures.joinToString("\n  - "),
            emptyList<String>(),
            failures,
        )
    }
}
