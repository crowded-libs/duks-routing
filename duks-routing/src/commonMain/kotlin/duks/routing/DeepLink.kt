package duks.routing

/**
 * Parsed deep link / URI components used for navigation.
 */
data class ParsedDeepLink(
    val scheme: String?,
    val host: String?,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    /** Original URL string. */
    val raw: String
)

/**
 * Parse a deep link URL into scheme, host, path, and query parameters.
 *
 * Supports:
 * - `myapp://host/path/to?x=1`
 * - `https://example.com/path?x=1`
 * - bare paths `/path` (no scheme)
 *
 * Path is always normalized with a leading `/`. Query values are not nested.
 */
fun parseDeepLink(url: String): ParsedDeepLink {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) {
        return ParsedDeepLink(scheme = null, host = null, path = "/", raw = url)
    }

    // Bare path
    if (!trimmed.contains("://")) {
        val (pathPart, queryPart) = splitPathAndQuery(trimmed)
        return ParsedDeepLink(
            scheme = null,
            host = null,
            path = normalizePath(pathPart.ifEmpty { "/" }),
            query = parseQuery(queryPart),
            raw = url
        )
    }

    val scheme = trimmed.substringBefore("://")
    val rest = trimmed.substringAfter("://")

    // rest: host/path?query  or  /path?query  or host?query
    val (authorityAndPath, queryPart) = splitPathAndQuery(rest)

    val host: String?
    val pathRaw: String
    if (authorityAndPath.startsWith("/")) {
        host = null
        pathRaw = authorityAndPath
    } else {
        val slash = authorityAndPath.indexOf('/')
        if (slash < 0) {
            host = authorityAndPath.takeIf { it.isNotEmpty() }
            pathRaw = "/"
        } else {
            host = authorityAndPath.substring(0, slash).takeIf { it.isNotEmpty() }
            pathRaw = authorityAndPath.substring(slash)
        }
    }

    return ParsedDeepLink(
        scheme = scheme.ifEmpty { null },
        host = host,
        path = normalizePath(pathRaw.ifEmpty { "/" }),
        query = parseQuery(queryPart),
        raw = url
    )
}

private fun splitPathAndQuery(value: String): Pair<String, String?> {
    val q = value.indexOf('?')
    return if (q < 0) {
        value to null
    } else {
        value.substring(0, q) to value.substring(q + 1)
    }
}

private fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrEmpty()) return emptyMap()
    return query.split('&')
        .mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val eq = part.indexOf('=')
            if (eq < 0) {
                decodePathSegment(part) to ""
            } else {
                decodePathSegment(part.substring(0, eq)) to decodePathSegment(part.substring(eq + 1))
            }
        }
        .toMap()
}
