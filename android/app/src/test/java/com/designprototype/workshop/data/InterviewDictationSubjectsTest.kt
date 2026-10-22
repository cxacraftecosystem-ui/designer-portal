package com.designprototype.workshop.data

import com.designprototype.workshop.qLiveSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3 (audit 21 Oct 2026): instant transcription in the interview form must name the interview's own
 * artisans, so the server can refuse a send for an artisan who said no but is filed under another
 * workshop.
 */
class InterviewDictationSubjectsTest {

    @Test
    fun namesTheSavedInterviewAndEverySelectedArtisan() {
        val subjects = dictationSubjects("int_1", listOf("art_b", " art_a ", "art_b", ""))
        assertEquals("int_1", subjects.interviewId)
        assertEquals("art_a,art_b", subjects.artisanIds)
    }

    @Test
    fun anUnsavedOrTemporaryInterviewSendsNoId() {
        assertNull(dictationSubjects(null, emptyList()).interviewId)
        assertNull(dictationSubjects("tmp-123", emptyList()).interviewId)
        assertNull(dictationSubjects(" ", emptyList()).artisanIds)
    }

}
