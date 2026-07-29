package duks.routing

/**
 * Result of matching a concrete path against a route template.
 *
 * Templates may include `{name}` segments, e.g. `/item/{id}`.
 */
data class PathMatch(
    /** Registered route template path (normalized). */
    val template: String,
    /** Concrete path that was matched (normalized). */
    val path: String,
    /** Named path segments extracted from the concrete path. */
    val pathParams: Map<String, String>
) {
    /**
     * Param value for navigation when none was supplied explicitly:
     * a single path segment is returned as [String]; multiple segments as the map.
     */
    fun inferredParam(): Any? = when (pathParams.size) {
        0 -> null
        1 -> pathParams.values.first()
        else -> pathParams
    }
}

/**
 * Match [path] against [template]. Both are normalized first.
 * Returns null when segment counts differ or a static segment does not match.
 */
fun matchPath(template: String, path: String): PathMatch? {
    val normalizedTemplate = normalizePath(template)
    val normalizedPath = normalizePath(path)

    if (normalizedTemplate == normalizedPath) {
        return PathMatch(normalizedTemplate, normalizedPath, emptyMap())
    }

    val templateParts = splitSegments(normalizedTemplate)
    val pathParts = splitSegments(normalizedPath)
    if (templateParts.size != pathParts.size) return null

    val params = mutableMapOf<String, String>()
    for (i in templateParts.indices) {
        val t = templateParts[i]
        val p = pathParts[i]
        if (t.startsWith("{") && t.endsWith("}") && t.length > 2) {
            val name = t.substring(1, t.length - 1)
            params[name] = decodePathSegment(p)
        } else if (t != p) {
            return null
        }
    }
    return PathMatch(normalizedTemplate, normalizedPath, params)
}

private fun splitSegments(path: String): List<String> =
    path.trim('/').split('/').filter { it.isNotEmpty() }

/**
 * Minimal percent-decoding for path segments (`%20`, `%2F`, etc.).
 */
internal fun decodePathSegment(segment: String): String {
    if (!segment.contains('%')) return segment
    val out = StringBuilder(segment.length)
    var i = 0
    while (i < segment.length) {
        val c = segment[i]
        if (c == '%' && i + 2 < segment.length) {
            val hex = segment.substring(i + 1, i + 3)
            val value = hex.toIntOrNull(16)
            if (value != null) {
                out.append(value.toChar())
                i += 3
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}
