package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Run `RichTextOps`' own self-check, which until now nothing ran.
 *
 * `richTextOpsSelfCheck` is a hundred-odd assertions about every ladder in the port — Enter, the
 * four rungs of Backspace, mark toggling, the input rules, paste, find-and-replace — written in the
 * file itself because it has no Android dependency and can therefore be exercised on a plain JVM.
 * Its only caller was a `main()` intended to be invoked by hand with `java -cp`, which means the
 * checks ran when somebody remembered to run them and at no other time. R8 strips both from the
 * release build, so they were not even shipped; they were simply never asserted.
 *
 * ONE LINE TURNS ALL OF THEM INTO CI COVERAGE. That is the whole content of this file. It is here
 * rather than folded into `RichTextInlineImageTest` because it is not about photographs — the
 * inline-photograph checks are merely the newest of the group, and the day somebody changes what
 * Backspace does to a nested bullet, this is what tells them.
 */
class RichTextOpsSelfCheckTest {

    @Test
    fun `every ladder in the port behaves as the web's does`() {
        // The failure list is returned rather than thrown, so the message names EVERY broken check
        // at once instead of the first one. A port being fixed one behaviour at a time is a port
        // being rebuilt one build at a time.
        val failures = richTextOpsSelfCheck()

        assertEquals(
            "RichTextOps self-check failed:\n  - " + failures.joinToString("\n  - "),
            emptyList<String>(),
            failures,
        )
    }
}
