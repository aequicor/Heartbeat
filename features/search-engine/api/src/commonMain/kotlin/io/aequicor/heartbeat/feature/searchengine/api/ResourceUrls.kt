package io.aequicor.heartbeat.feature.searchengine.api

/**
 * Accepts only public `http(s)` URLs: no credentials in the authority, no `localhost`/`.local` names,
 * no loopback, private, link-local, CGNAT or unspecified IP literals (IPv4, IPv6 and numeric shorthands).
 *
 * `web_search` / `web_fetch` run without user approval in every engine (owner decision): they are read-only,
 * never touch the local file system and the page is read by the provider or a native reader, not by this
 * device model turn. This pure classifier keeps model-chosen URLs from addressing the local network through
 * either route, including every redirect hop a native reader follows. Host names are not resolved here, so
 * DNS pointing at private addresses remains the reader's responsibility.
 */
public fun isPublicWebUrl(url: String): Boolean {
    val scheme = listOf("https://", "http://").firstOrNull { url.startsWith(it, ignoreCase = true) } ?: return false
    if (url.length <= MIN_RESOURCE_URL_LENGTH || url.any { it.isWhitespace() }) return false
    val authority = url.substring(scheme.length).substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isEmpty() || '@' in authority || '\\' in authority) return false
    val host = if (authority.startsWith('[')) {
        authority.substringAfter('[').substringBefore(']', missingDelimiterValue = "")
    } else {
        authority.substringBefore(':')
    }.lowercase().trimEnd('.')
    return host.isNotEmpty() && isPublicHost(host)
}

private fun isPublicHost(host: String): Boolean = when {
    ':' in host -> isPublicIpv6(host)

    host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") -> false

    // As in the WHATWG URL parser, a numeric last label makes the host an IPv4 address; shorthand, octal and
    // hex forms are rejected, only a canonical public dotted quad passes.
    host.substringAfterLast('.').let { it.all(Char::isDigit) || it.startsWith("0x") } -> isPublicIpv4(host)

    else -> true
}

private fun isPublicIpv4(host: String): Boolean {
    val parts = host.split('.').map { part -> part.toIntOrNull()?.takeIf { it in 0..BYTE_MAX && part == "$it" } }
    if (parts.size != IPV4_PARTS || parts.any { it == null }) return false
    val address = parts.requireNoNulls().fold(0L) { value, part -> (value shl BYTE_BITS) or part.toLong() }
    return NON_PUBLIC_IPV4.none { (network, prefix) ->
        val shift = IPV4_BITS - prefix
        address shr shift == network shr shift
    }
}

private fun ipv4Block(cidr: String): Pair<Long, Int> {
    val (network, prefix) = cidr.split('/')
    val value = network.split('.').fold(0L) { acc, part -> (acc shl BYTE_BITS) or part.toLong() }
    return value to prefix.toInt()
}

/** Unspecified, private, CGNAT, loopback, link-local, IETF/benchmark/TEST-NET and multicast/reserved IPv4. */
private val NON_PUBLIC_IPV4 = listOf(
    "0.0.0.0/8",
    "192.0.0.0/24",
    "192.0.2.0/24",
    "198.18.0.0/15",
    "198.51.100.0/24",
    "203.0.113.0/24",
    "10.0.0.0/8",
    "100.64.0.0/10",
    "127.0.0.0/8",
    "169.254.0.0/16",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "224.0.0.0/3",
).map(::ipv4Block)

private fun isPublicIpv6(host: String): Boolean {
    val groups = ipv6Groups(host.substringBefore('%')) ?: return false
    val first = groups[0]
    val v4Marker = groups[IPV6_V4_PREFIX_GROUPS]
    // Unspecified, loopback, IPv4-compatible and IPv4-mapped addresses.
    val isEmbeddedIpv4 = groups.take(IPV6_V4_PREFIX_GROUPS).all { it == 0 } && (v4Marker == 0 || v4Marker == HEXTET_MAX)
    val isNat64 = first == NAT64_FIRST && groups[1] == NAT64_SECOND
    val isLocalScope = first and ULA_MASK == ULA_PREFIX || first and LINK_LOCAL_MASK == LINK_LOCAL_PREFIX
    val isMulticast = first and MULTICAST_MASK == MULTICAST_MASK
    return !(isEmbeddedIpv4 || isNat64 || isLocalScope || isMulticast)
}

/** Expands any textual IPv6 form (`::`, leading zeros, dotted IPv4 tail) to eight hextets; null if invalid. */
private fun ipv6Groups(address: String): List<Int>? {
    val text = withHexIpv4Tail(address) ?: return null
    val halves = text.split("::")
    if (halves.size > 2) return null
    val head = hextets(halves[0])
    val rest = if (halves.size == 2) hextets(halves[1]) else emptyList()
    val missing = IPV6_GROUPS - head.size - rest.size
    val isValidLength = if (halves.size == 2) missing >= 1 else missing == 0
    if (!isValidLength) return null
    val groups = head + List(missing) { 0 } + rest
    return groups.takeIf { all -> all.all { it != null && it in 0..HEXTET_MAX } }?.requireNoNulls()
}

private fun hextets(part: String): List<Int?> =
    if (part.isEmpty()) emptyList() else part.split(':').map { it.toIntOrNull(HEX) }

/** Rewrites a dotted IPv4 tail (`::ffff:1.2.3.4`) as two hextets; null if the tail is not a valid quad. */
private fun withHexIpv4Tail(address: String): String? {
    val tail = address.substringAfterLast(':')
    if ('.' !in tail) return address
    val quad = tail.split('.').map { part -> part.toIntOrNull()?.takeIf { it in 0..BYTE_MAX && part == "$it" } }
    if (quad.size != IPV4_PARTS || quad.any { it == null }) return null
    val bytes = quad.requireNoNulls()
    val high = (bytes[0] shl BYTE_BITS) or bytes[1]
    val low = (bytes[2] shl BYTE_BITS) or bytes[IPV4_PARTS - 1]
    return address.dropLast(tail.length) + high.toString(HEX) + ":" + low.toString(HEX)
}

private const val MIN_RESOURCE_URL_LENGTH = 9
private const val IPV4_PARTS = 4
private const val BYTE_MAX = 255
private const val BYTE_BITS = 8
private const val HEX = 16
private const val IPV6_GROUPS = 8
private const val IPV6_V4_PREFIX_GROUPS = 5
private const val HEXTET_MAX = 0xffff
private const val NAT64_FIRST = 0x64
private const val NAT64_SECOND = 0xff9b
private const val ULA_MASK = 0xfe00
private const val ULA_PREFIX = 0xfc00
private const val LINK_LOCAL_MASK = 0xffc0
private const val LINK_LOCAL_PREFIX = 0xfe80
private const val MULTICAST_MASK = 0xff00
private const val IPV4_BITS = 32
