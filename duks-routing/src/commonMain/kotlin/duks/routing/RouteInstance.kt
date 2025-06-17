package duks.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import kotlinx.serialization.Serializable

// Composition local for accessing route param
val LocalRouteParam = compositionLocalOf<Any?> { null }

interface RouteInstance {
    val path: String
    val param: Any?
    
    @Composable
    fun Content()
}

// Serializable route instance - only serializes the path, parameters are not supported
@Serializable
data class SerializableRouteInstance(
    override val path: String
) : RouteInstance {
    override val param: Any?
        get() = null
    
    @Composable
    override fun Content() {
        error("SerializableRouteInstance.Content() should not be called directly")
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

// Factory function to create serializable route instance (parameters not supported)
fun createSerializableRouteInstance(path: String, param: Any? = null): SerializableRouteInstance {
    // Note: param is ignored - parameters are not serialized
    return SerializableRouteInstance(path)
}

// Convert a RouteInstance to its serializable form (parameters not preserved)
fun RouteInstance.toSerializable(): SerializableRouteInstance {
    // Note: parameters are not serialized - this is a current limitation
    return SerializableRouteInstance(path)
}

// Extension property to get route from RouteInstance (if available)
val RouteInstance.route: Route<*>?
    get() = when (this) {
        is SimpleRouteInstance -> route
        is ParameterizedRouteInstance<*> -> route
        else -> null
    }