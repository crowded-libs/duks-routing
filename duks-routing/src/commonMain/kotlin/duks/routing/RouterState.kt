package duks.routing

import duks.StateModel
import kotlinx.serialization.Serializable

// Router state with layer management
@Serializable
data class RouterState(
    @Serializable(with = RouteInstanceListSerializer::class)
    val sceneRoutes: List<RouteInstance> = emptyList(),
    @Serializable(with = RouteInstanceListSerializer::class)
    val contentRoutes: List<RouteInstance> = emptyList(),
    @Serializable(with = RouteInstanceListSerializer::class)
    val modalRoutes: List<RouteInstance> = emptyList(),
    val deviceContext: DeviceContext? = null,
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
            instance.config as? T
        }
    }

    // Get the current visible content route
    fun getCurrentContentRoute(): RouteInstance? = contentRoutes.lastOrNull()

    /**
     * Primary visible non-modal route: content overlay if present, otherwise the current scene.
     * Useful for chrome that should follow detail screens over tab roots.
     */
    fun primaryRoute(): RouteInstance? = lastContent ?: lastScene

    /**
     * Whether a back navigation would change the stack (modal dismiss, content pop, or scene pop).
     */
    fun canGoBack(): Boolean =
        modalRoutes.isNotEmpty() ||
            contentRoutes.size > 1 ||
            (contentRoutes.isNotEmpty() && sceneRoutes.isNotEmpty()) ||
            sceneRoutes.size > 1

    /**
     * Config from [primaryRoute], if any.
     */
    inline fun <reified T> primaryConfig(): T? = primaryRoute()?.config as? T
}

// Convenience extension properties for accessing the last route in each layer
val RouterState.lastScene: RouteInstance?
    get() = sceneRoutes.lastOrNull()

val RouterState.lastContent: RouteInstance?
    get() = contentRoutes.lastOrNull()

val RouterState.lastModal: RouteInstance?
    get() = modalRoutes.lastOrNull()

/**
 * Config attached to the route definition, when this instance was created from a live [Route].
 */
val RouteInstance.config: Any?
    get() = when (this) {
        is SimpleRouteInstance -> route.config
        is ParameterizedRouteInstance<*> -> route.config
        else -> null
    }

/**
 * Exact path equality helper (prefer over substring checks for tab selection).
 */
fun RouteInstance.pathEquals(path: String): Boolean =
    this.path == normalizePath(path)
