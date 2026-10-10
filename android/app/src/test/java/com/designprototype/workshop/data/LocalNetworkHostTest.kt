package com.designprototype.workshop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHICH API BASES NEED ANDROID 17'S LOCAL-NETWORK PERMISSION, AND — THE HALF THAT MATTERS MORE —
 * WHICH DO NOT.
 *
 * `apiHostNeedsLocalNetwork` decides whether a debug build asks for `ACCESS_LOCAL_NETWORK` at launch.
 * The two ways to get it wrong cost different things: missing a developer's LAN or emulator host
 * leaves an Android 17 sign-in timing out with no reason given, and answering yes for a public host
 * would put a Nearby-devices prompt in front of somebody for nothing. Production must always be a no.
 */
class LocalNetworkHostTest {

    @Test
    fun `production and every public host answer no`() {
        assertFalse(apiHostNeedsLocalNetwork("https://d3ekigkotd1xa2.cloudfront.net/api/"))
        assertFalse(apiHostNeedsLocalNetwork("https://example.org/api/"))
        assertFalse(apiHostNeedsLocalNetwork("http://8.8.8.8:8000/api/"))
        assertFalse(apiHostNeedsLocalNetwork("http://[2001:db8::1]:8000/api/"))
    }

    @Test
    fun `the emulator's host alias and the private ranges answer yes`() {
        // The README's own override for an emulator.
        assertTrue(apiHostNeedsLocalNetwork("http://10.0.2.2:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://192.168.1.20:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://172.16.0.5:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://172.31.255.254:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://169.254.10.1:8000/api/"))
    }

    @Test
    fun `the edges of the 172 range are where they are`() {
        // 172.16.0.0/12 is 172.16–172.31; its neighbours are public.
        assertFalse(apiHostNeedsLocalNetwork("http://172.15.0.1:8000/api/"))
        assertFalse(apiHostNeedsLocalNetwork("http://172.32.0.1:8000/api/"))
    }

    @Test
    fun `the shared 100_64 range counts, and only its 64 to 127 part`() {
        // Android lists 100.64.0.0/10 as local network; a VPN overlay such as Tailscale lives there.
        assertTrue(apiHostNeedsLocalNetwork("http://100.64.0.1:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://100.101.102.103:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://100.127.255.254:8000/api/"))
        assertFalse(apiHostNeedsLocalNetwork("http://100.63.0.1:8000/api/"))
        assertFalse(apiHostNeedsLocalNetwork("http://100.128.0.1:8000/api/"))
    }

    @Test
    fun `loopback is not the local network`() {
        // What `adb reverse` and a desktop backend use; it never leaves the device.
        assertFalse(apiHostNeedsLocalNetwork("http://localhost:8000/api/"))
        assertFalse(apiHostNeedsLocalNetwork("http://127.0.0.1:8000/api/"))
        assertFalse(apiHostNeedsLocalNetwork("http://[::1]:8000/api/"))
    }

    @Test
    fun `IPv6 unique-local and link-local answer yes`() {
        assertTrue(apiHostNeedsLocalNetwork("http://[fd12:3456::1]:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://[fc00::1]:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://[fe80::1]:8000/api/"))
        assertFalse(apiHostNeedsLocalNetwork("http://[fec0::1]:8000/api/"))
    }

    @Test
    fun `names only a local resolver answers count, and nothing else does`() {
        assertTrue(apiHostNeedsLocalNetwork("http://devbox.local:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://devbox.home.arpa:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://api.lan:8000/api/"))
        assertTrue(apiHostNeedsLocalNetwork("http://api.internal:8000/api/"))
        // A suffix must be a whole label, not the end of one.
        assertFalse(apiHostNeedsLocalNetwork("https://notlocal/api/"))
        assertFalse(apiHostNeedsLocalNetwork("https://example.global/api/"))
    }

    @Test
    fun `something that is not a URL answers no rather than throwing`() {
        assertFalse(apiHostNeedsLocalNetwork(""))
        assertFalse(apiHostNeedsLocalNetwork("not a url"))
    }
}
