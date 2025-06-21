package duks.routing

import duks.StateModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual

// Router state with layer management
@Serializable
data class RouterState(
    @Serializable(with = RouteInstanceListSerializer::class)
    val sceneRoutes: List<RouteInstance> = emptyList(),
    @Serializable(with = RouteInstanceListSerializer::class)
    val contentRoutes: List<RouteInstance> = emptyList(),
    @Serializable(with = RouteInstanceListSerializer::class)
    val modalRoutes: List<RouteInstance> = emptyList(),
    @Contextual val deviceContext: DeviceContext? = null,
    val lastRouteType: RouteType? = null,
    val enabledFeatures: Set<String> = emptySet()
) : StateModel {
    // Get all active routes across layers
    fun getActiveRoutes(): List<RouteInstance> {
        return sceneRoutes + contentRoutes + modalRoutes
    }

    // Find config of type T from all active routes
    inline fun <reified T> findConfig(): T? {
        return getActiveRoutes().firstNotNullOfOrNull { instance ->
            when (instance) {
                is SimpleRouteInstance -> instance.route.config as? T
                is ParameterizedRouteInstance<*> -> instance.route.config as? T
                else -> null
            }
        }
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