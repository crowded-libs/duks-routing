package duks.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

// Composition local for accessing route param
val LocalRouteParam = compositionLocalOf<Any?> { null }

// Base interface for all route instances
interface RouteInstance {
    val route: Route<*>
    
    @Composable
    fun Content()
}

// Route instance for routes without parameters
data class SimpleRouteInstance(
    override val route: Route<*>
) : RouteInstance {
    
    @Composable
    override fun Content() {
        route.content()
    }
}

// Route instance for routes with parameters
data class ParameterizedRouteInstance<T>(
    override val route: Route<*>,
    val param: T
) : RouteInstance {
    
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

// Extension property to safely get param from RouteInstance
val RouteInstance.param: Any?
    get() = when (this) {
        is ParameterizedRouteInstance<*> -> param
        is SimpleRouteInstance -> null
        else -> null // For future implementations
    }