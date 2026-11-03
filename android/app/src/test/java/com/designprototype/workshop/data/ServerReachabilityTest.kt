package com.designprototype.workshop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

class ServerReachabilityTest {

    @Before
    fun reset() = ServerReachability.recordAnswer()

    @Test
    fun `one failure is a dropped packet, two in a row is a server that is not there`() {
        ServerReachability.recordFailure(ConnectException("Failed to connect"))
        assertFalse(ServerReachability.unreachable.value)
        ServerReachability.recordFailure(ConnectException("Failed to connect"))
        assertTrue(ServerReachability.unreachable.value)
    }

    @Test
    fun `the next answer of any kind clears it`() {
        repeat(3) { ServerReachability.recordFailure(ConnectException("Failed to connect")) }
        assertTrue(ServerReachability.unreachable.value)
        ServerReachability.recordAnswer()
        assertFalse(ServerReachability.unreachable.value)
    }

    @Test
    fun `a cancelled call says nothing about the server`() {
        repeat(5) { ServerReachability.recordFailure(IOException("Canceled")) }
        assertFalse(ServerReachability.unreachable.value)
    }
}
