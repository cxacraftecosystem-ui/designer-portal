package com.designprototype.workshop.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Does reaching this API base need Android 17's `ACCESS_LOCAL_NETWORK`?
 *
 * ── WHY THE QUESTION EXISTS ──────────────────────────────────────────────────────────────────────
 *
 * An app targeting API 37 has its traffic to devices on the local network blocked until the person
 * grants `ACCESS_LOCAL_NETWORK` (a runtime permission in the Nearby devices group); TCP to a blocked
 * address does not fail fast, it times out. This app targets 37 since 2026-10-09. Production is
 * CloudFront, a public host, and is untouched by any of it — but a developer's `apiBaseUrl` override
 * in `local.properties` is exactly the kind of address the rule is about: the emulator's
 * `http://10.0.2.2:8000/api/` and a laptop on the office Wi-Fi at `http://192.168.x.y:8000/api/`.
 * Without the permission, the first sign-in on an Android 17 device or emulator spins and fails with
 * a timeout that says nothing about why.
 *
 * ── WHAT COUNTS, AND WHERE THE LINE IS DRAWN ────────────────────────────────────────────────────
 *
 * Android's documentation names the traffic (raw sockets to local addresses, mDNS, SSDP) but, as of
 * 2026-10-09, not a list of ranges, so this answers for the addresses that are local by definition:
 * the RFC 1918 private IPv4 ranges, IPv4 and IPv6 link-local, IPv6 unique-local, and the names that
 * only a local resolver answers (`.local` is multicast DNS; `.home.arpa` is RFC 8375's; `.lan` and
 * `.internal` are the common conventions). LOOPBACK IS NOT LOCAL NETWORK — `localhost`, `127.x` and
 * `::1` never leave the device, which is what `adb reverse` relies on — and an ordinary public name
 * answers no, because a public name is what production is.
 *
 * Asking when it was not strictly needed costs one prompt on a developer's own debug build; not
 * asking when it was costs a sign-in that times out. So where the rule is unclear the answer leans
 * to asking — but only for the address shapes above, never for a public host.
 *
 * Pure, so `LocalNetworkHostTest` pins it on the desktop; `MainActivity` is the only caller, and it
 * asks only on a DEBUG build, which is the only build whose manifest declares the permission.
 */
fun apiHostNeedsLocalNetwork(baseUrl: String): Boolean {
    val host = baseUrl.trim().toHttpUrlOrNull()?.host?.lowercase() ?: return false
    if (host == "localhost") return false
    ipv4Octets(host)?.let { return ipv4IsLocalNetwork(it) }
    if (':' in host) return ipv6IsLocalNetwork(host)
    return LOCAL_ONLY_SUFFIXES.any { host.endsWith(it) }
}

/** Names only a resolver on the local network answers. See [apiHostNeedsLocalNetwork]. */
private val LOCAL_ONLY_SUFFIXES = listOf(".local", ".home.arpa", ".lan", ".internal")

/** The four octets of a dotted-quad IPv4 literal, or null when [host] is not one. */
private fun ipv4Octets(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 && part.isNotEmpty() } }
    return if (octets.any { it == null }) null else octets.map { it!! }
}

private fun ipv4IsLocalNetwork(octets: List<Int>): Boolean {
    val (a, b) = octets
    return when {
        a == 127 -> false // loopback never leaves the device
        a == 10 -> true // RFC 1918, and the emulator's 10.0.2.2
        a == 172 && b in 16..31 -> true // RFC 1918
        a == 192 && b == 168 -> true // RFC 1918
        a == 169 && b == 254 -> true // link-local
        else -> false
    }
}

/**
 * IPv6 as OkHttp prints a host: no brackets, lower-case, compressed. Only the first group decides
 * the ranges that matter here, so no full parse is needed: `::1` is loopback, `fc00::/7` is
 * unique-local and `fe80::/10` is link-local.
 */
private fun ipv6IsLocalNetwork(host: String): Boolean {
    if (host == "::1") return false
    val first = host.substringBefore(':')
    if (first.isEmpty()) return false // `::…` compresses a leading run of zero groups: not fc/fe80
    val group = first.toIntOrNull(16) ?: return false
    return (group and 0xfe00) == 0xfc00 || (group and 0xffc0) == 0xfe80
}
