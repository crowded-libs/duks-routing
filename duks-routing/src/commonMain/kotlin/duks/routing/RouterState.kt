package duks.routing

import duks.StateModel

// Interface for states that provide router state for persistence/restoration
interface RoutingStateProvider {
    val routingState: RouterState
}

// Router state with layer management
data class RouterState(
    val sceneRoutes: List<RouteInstance> = emptyList(),
    val contentRoutes: List<RouteInstance> = emptyList(),
    val modalRoutes: List<RouteInstance> = emptyList(),
    val deviceContext: DeviceContext? = null,
    val lastRouteType: RouteType? = null
) : StateModel {

    // Get all active routes across layers
    fun getActiveRoutes(): List<RouteInstance> {
        return sceneRoutes + contentRoutes + modalRoutes
    }

    // Find config of type T from all active routes
    inline fun <reified T> findConfig(): T? {
        return getActiveRoutes()
            .mapNotNull { it.route.config as? T }
            .lastOrNull()
    }

    // Get the current visible content route
    fun getCurrentContentRoute(): RouteInstance? = contentRoutes.lastOrNull()
}

// Convenience extension properties for accessing the last route in each layer
val RouterState.lastScene: RouteInstance?
    get() = sceneRoutes.lastOrNull()

val RouterState.lastContent: RouteInstance?
    get() = contentRoutes.lastOrNull()

val RouterState.lastModal: RouteInstance?
    get() = modalRoutes.lastOrNull()