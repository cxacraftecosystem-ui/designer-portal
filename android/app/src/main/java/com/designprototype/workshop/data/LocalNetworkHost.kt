package com.designprototype.workshop.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun apiHostNeedsLocalNetwork(baseUrl: String): Boolean {
    val host = baseUrl.trim().toHttpUrlOrNull()?.host?.lowercase() ?: return false
    if (host == "localhost") return false
    ipv4Octets(host)?.let { return ipv4IsLocalNetwork(it) }
    if (':' in host) return ipv6IsLocalNetwork(host)
    return LOCAL_ONLY_SUFFIXES.any { host.endsWith(it) }
}

private val LOCAL_ONLY_SUFFIXES = listOf(".local", ".home.arpa", ".lan", ".internal")

private fun ipv4Octets(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 && part.isNotEmpty() } }
    return if (octets.any { it == null }) null else octets.map { it!! }
}

private fun ipv4IsLocalNetwork(octets: List<Int>): Boolean {
    val (a, b) = octets
    return when {
        a == 127 -> false
        a == 10 -> true
        a == 172 && b in 16..31 -> true
        a == 192 && b == 168 -> true
        a == 169 && b == 254 -> true
        a == 100 && b in 64..127 -> true
        else -> false
    }
}

private fun ipv6IsLocalNetwork(host: String): Boolean {
    if (host == "::1") return false
    val first = host.substringBefore(':')
    if (first.isEmpty()) return false
    val group = first.toIntOrNull(16) ?: return false
    return (group and 0xfe00) == 0xfc00 || (group and 0xffc0) == 0xfe80
}
