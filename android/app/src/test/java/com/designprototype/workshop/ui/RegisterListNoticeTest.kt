package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AN EMPTY REGISTER READS AS EMPTY; ONLY A FAILED READ SAYS IT COULD NOT LOAD.
 *
 * The 0.0.19 emulator smoke opened the artisan form on a deployment with no crafts and read
 * "Couldn't load crafts" while online: a live fetch sets `online = true`, and `registerListNotice`
 * had no arm for a read that answered with nothing, so it fell through to the failure sentence.
 */
class RegisterListNoticeTest {

    private val live = RegisterLoad(source = RegisterSource.LIVE, online = true)

    @Test
    fun `a live read with no rows is empty, not a failure`() {
        assertEquals(unscopedEmptyLine("crafts"), registerListNotice("crafts", 0, live))
        assertNotEquals(couldNotListLine("crafts"), registerListNotice("crafts", 0, live))
    }

    @Test
    fun `the craft picker names the next move the role allows`() {
        assertEquals(
            "No crafts yet. Type a new craft name below.",
            registerListNotice("crafts", 0, live, craftsEmptyLine(canAdd = true)),
        )
        assertEquals(
            "No crafts yet. Ask a professor or admin to add one.",
            registerListNotice("crafts", 0, live, craftsEmptyLine(canAdd = false)),
        )
    }

    @Test
    fun `a cached file with no rows is the server having answered none`() {
        val cachedNone = RegisterLoad(source = RegisterSource.CACHED, fetchedAt = "2026-10-01T06:00:00Z")
        assertEquals(craftsEmptyLine(true), registerListNotice("crafts", 0, cachedNone, craftsEmptyLine(true)))
    }

    @Test
    fun `only a real failure says it could not load`() {
        val refused = RegisterLoad(source = RegisterSource.NONE, online = true)
        val unreachable = RegisterLoad(source = RegisterSource.NONE, online = false)
        assertEquals(couldNotListLine("crafts"), registerListNotice("crafts", 0, refused, craftsEmptyLine(true)))
        assertEquals(offlineListLine("crafts"), registerListNotice("crafts", 0, unreachable, craftsEmptyLine(true)))
    }

    @Test
    fun `a live list with rows says nothing and a pending one is still loading`() {
        assertNull(registerListNotice("crafts", 3, live, craftsEmptyLine(true)))
        assertEquals(loadingListLine("crafts"), registerListNotice("crafts", 0, RegisterLoad()))
    }
}
