package duks.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// Composition local for accessing route param
val LocalRouteParam = compositionLocalOf<Any?> { null }

interface RouteInstance {
    val path: String
    val param: Any?
    
    @Composable
    fun Content()
}

/**
 * Wire format for a route instance. [param] is reconstructed from [paramType]/[paramPayload]
 * via [RouteParamRegistry] when present; otherwise null.
 */
@Serializable
data class SerializableRouteInstance(
    override val path: String,
    val paramType: String? = null,
    val paramPayload: String? = null,
    @Transient
    override val param: Any? = null
) : RouteInstance {
    
    @Composable
    override fun Content() {
        error("SerializableRouteInstance.Content() should not be called directly")
    }

    fun withDecodedParam(registry: RouteParamRegistry = RouteParamRegistry.Default): SerializableRouteInstance {
        if (param != null || paramType == null || paramPayload == null) return this
        return copy(param = registry.decode(paramType, paramPayload))
    }
}

data class SimpleRouteInstance(
    val route: Route<*>
) : RouteInstance {
    override val path: String = route.path
    override val param: Any? = null
    
    @Composable
    override fun Content() {
        route.content()
    }
}

data class ParameterizedRouteInstance<T>(
    val route: Route<*>,
    override val param: T
) : RouteInstance {
    override val path: String = route.path
    
    @Composable
    override fun Content() {
        CompositionLocalProvider(LocalRouteParam provides param) {
            route.content()
        }
    }
}

// Factory function to create appropriate RouteInstance
fun createRouteInstance(route: Route<*>, param: Any? = null): RouteInstance {
    return if (param != null) {
        ParameterizedRouteInstance(route, param)
    } else {
        SimpleRouteInstance(route)
    }
}

/**
 * Create a serializable snapshot of a path, optionally encoding [param] with [registry].
 * Unencodable params are dropped (path is still returned).
 */
fun createSerializableRouteInstance(
    path: String,
    param: Any? = null,
    registry: RouteParamRegistry = RouteParamRegistry.Default
): SerializableRouteInstance {
    val encoded = param?.let { registry.encode(it) }
    return SerializableRouteInstance(
        path = path,
        paramType = encoded?.type,
        paramPayload = encoded?.payload,
        param = if (encoded != null) param else null
    )
}

/**
 * Convert a [RouteInstance] to its serializable form.
 * Params that cannot be encoded with [registry] are omitted.
 */
fun RouteInstance.toSerializable(
    registry: RouteParamRegistry = RouteParamRegistry.Default
): SerializableRouteInstance {
    val encoded = param?.let { registry.encode(it) }
    return SerializableRouteInstance(
        path = path,
        paramType = encoded?.type,
        paramPayload = encoded?.payload,
        param = if (encoded != null) param else null
    )
}

// Extension property to get route from RouteInstance (if available)
val RouteInstance.route: Route<*>?
    get() = when (this) {
        is SimpleRouteInstance -> route
        is ParameterizedRouteInstance<*> -> route
        else -> null
    }
