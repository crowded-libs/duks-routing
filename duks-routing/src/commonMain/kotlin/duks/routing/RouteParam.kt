package duks.routing

import androidx.compose.runtime.Composable

// Extension functions for RouteInstance to get parameters with type casting and warning suppression
inline fun <reified T> RouteInstance.routeParam(): T? {
    @Suppress("UNCHECKED_CAST")
    return param as? T
}

// Helper to get typed route param from composition local (for use inside route content)
@Composable
inline fun <reified T> routeParam(): T {
    val param = LocalRouteParam.current
    return param as? T 
        ?: throw IllegalStateException("Expected route parameter of type ${T::class.simpleName} but got ${param?.let { it::class.simpleName } ?: "null"}")
}

// Get the route parameter with default value
inline fun <reified T> RouteInstance.routeParam(default: T): T {
    return routeParam<T>() ?: default
}

// Extension to get param from RouterState for current route
inline fun <reified T> RouterState.currentParam(): T? {
    return getCurrentContentRoute()?.param as? T
}

inline fun <reified T> RouterState.currentModalParam(): T? {
    return lastModal?.param as? T
}

inline fun <reified T> RouterState.currentSceneParam(): T? {
    return lastScene?.param as? T
}