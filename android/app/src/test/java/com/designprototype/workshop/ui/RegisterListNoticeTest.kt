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

    /** A caller's own empty sentence; the craft list's own empty state is the catalogue picker's now. */
    private val empty = "No crafts yet. Ask an admin to add one."

    @Test
    fun `a live read with no rows is empty, not a failure`() {
        assertEquals(unscopedEmptyLine("crafts"), registerListNotice("crafts", 0, live))
        assertNotEquals(couldNotListLine("crafts"), registerListNotice("crafts", 0, live))
    }

    @Test
    fun `a caller's empty sentence is used for a live read with no rows`() {
        assertEquals(empty, registerListNotice("crafts", 0, live, empty))
    }

    @Test
    fun `a cached file with no rows is the server having answered none`() {
        val cachedNone = RegisterLoad(source = RegisterSource.CACHED, fetchedAt = "2026-10-01T06:00:00Z")
        assertEquals(empty, registerListNotice("crafts", 0, cachedNone, empty))
    }

    @Test
    fun `only a real failure says it could not load`() {
        val refused = RegisterLoad(source = RegisterSource.NONE, online = true)
        val unreachable = RegisterLoad(source = RegisterSource.NONE, online = false)
        assertEquals(couldNotListLine("crafts"), registerListNotice("crafts", 0, refused, empty))
        assertEquals(offlineListLine("crafts"), registerListNotice("crafts", 0, unreachable, empty))
    }

    @Test
    fun `a live list with rows says nothing and a pending one is still loading`() {
        assertNull(registerListNotice("crafts", 3, live, empty))
        assertEquals(loadingListLine("crafts"), registerListNotice("crafts", 0, RegisterLoad()))
    }
}
