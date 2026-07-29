package duks.routing

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Encoded form of a route parameter for persistence.
 */
data class EncodedRouteParam(
    val type: String,
    val payload: String
)

/**
 * Registry of kotlinx.serialization codecs used when persisting route parameters.
 *
 * Built-in support: [String], [Int], [Long], [Boolean], [Float], [Double].
 * Register custom `@Serializable` types with [register]. Non-registered params are
 * omitted from persistence (path is kept; param is dropped).
 *
 * [Default] is a process-wide registry apps can configure at startup. Prefer passing a
 * dedicated instance into [RouterBuilder.paramSerializers] when multiple stores coexist.
 *
 * Registration is intended at startup (single-threaded). Lookups use snapshot maps.
 */
class RouteParamRegistry(
    private val json: Json = defaultJson
) {
    private var byTypeName: Map<String, KSerializer<*>> = emptyMap()
    private var byClassName: Map<String, String> = emptyMap()

    init {
        register(String.serializer(), "kotlin.String")
        register(Int.serializer(), "kotlin.Int")
        register(Long.serializer(), "kotlin.Long")
        register(Boolean.serializer(), "kotlin.Boolean")
        register(Float.serializer(), "kotlin.Float")
        register(Double.serializer(), "kotlin.Double")
    }

    /**
     * Register a serializer under an explicit type name (stable across platforms).
     */
    fun <T : Any> register(serializer: KSerializer<T>, typeName: String = serializer.descriptor.serialName) {
        byTypeName = byTypeName + (typeName to serializer)
    }

    /**
     * Register a serializer for a concrete reified type, also indexing by runtime class name.
     */
    inline fun <reified T : Any> register(typeName: String = serializer<T>().descriptor.serialName) {
        register(serializer<T>(), typeName)
        indexClassName(T::class.qualifiedName ?: T::class.simpleName, typeName)
    }

    @PublishedApi
    internal fun indexClassName(className: String?, typeName: String) {
        if (className != null) {
            byClassName = byClassName + (className to typeName)
        }
    }

    fun encode(param: Any): EncodedRouteParam? {
        val className = param::class.qualifiedName ?: param::class.simpleName ?: return null
        val typeName = byClassName[className] ?: when (param) {
            is String -> "kotlin.String"
            is Int -> "kotlin.Int"
            is Long -> "kotlin.Long"
            is Boolean -> "kotlin.Boolean"
            is Float -> "kotlin.Float"
            is Double -> "kotlin.Double"
            else -> null
        } ?: return null

        val serializer = byTypeName[typeName] ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val payload = json.encodeToString(serializer as KSerializer<Any>, param)
            EncodedRouteParam(typeName, payload)
        } catch (_: Exception) {
            null
        }
    }

    fun decode(type: String, payload: String): Any? {
        val serializer = byTypeName[type] ?: return null
        return try {
            json.decodeFromString(serializer, payload)
        } catch (_: Exception) {
            null
        }
    }

    fun decodeOrNull(type: String?, payload: String?): Any? {
        if (type == null || payload == null) return null
        return decode(type, payload)
    }

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Process-wide default used by [RouterState] serialization unless overridden. */
        val Default = RouteParamRegistry()
    }
}
