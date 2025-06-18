package duks.routing

import duks.StateModel

/**
 * Represents a conditional default route that is applied when a condition is met.
 */
data class ConditionalDefault<TState: StateModel>(
    val condition: (TState) -> Boolean,
    val route: String,
    val layer: NavigationLayer = NavigationLayer.Content,
    val description: String? = null
)

/**
 * Configuration for conditional defaults during restoration.
 */
data class ConditionalDefaultsConfig<TState: StateModel>(
    val defaults: List<ConditionalDefault<TState>> = emptyList(),
    val fallbackRoute: String? = null
)

/**
 * Defines the strategy for restoring router state.
 */
sealed class RestorationStrategy {
    /**
     * Restore all routes from the saved state.
     */
    object RestoreAll : RestorationStrategy()
    
    /**
     * Restore only specific types of routes.
     */
    data class RestoreOnly(val types: Set<RouteType>) : RestorationStrategy()
    
    /**
     * Restore specific routes by path.
     */
    data class RestoreSpecific(
        val scenes: Set<String> = emptySet(),
        val content: Set<String> = emptySet(),
        val modals: Set<String> = emptySet()
    ) : RestorationStrategy()
    
    /**
     * Restore routes with conditional defaults. When conditions match, the default routes
     * will override any restored routes, allowing for dynamic routing based on app state.
     */
    data class RestoreWithDefaults<TState: StateModel>(
        val baseStrategy: RestorationStrategy = RestoreAll,
        val conditionalDefaults: ConditionalDefaultsConfig<TState>
    ) : RestorationStrategy()
}

/**
 * DSL builder for configuring router restoration.
 */
class RestorationBuilder<TState: StateModel> {
    private var strategy: RestorationStrategy = RestorationStrategy.RestoreAll
    
    /**
     * Restore all routes from saved state.
     */
    fun restoreAll() {
        strategy = RestorationStrategy.RestoreAll
    }
    
    /**
     * Restore only specific types of routes.
     */
    fun restoreOnly(vararg types: RouteType) {
        strategy = RestorationStrategy.RestoreOnly(types.toSet())
    }
    
    /**
     * Restore specific routes by path.
     */
    fun restoreSpecific(block: RestoreSpecificBuilder.() -> Unit) {
        val builder = RestoreSpecificBuilder()
        builder.block()
        strategy = RestorationStrategy.RestoreSpecific(
            scenes = builder.sceneRoutes,
            content = builder.contentRoutes,
            modals = builder.modalRoutes
        )
    }
    
    /**
     * Configure conditional defaults for restoration.
     */
    fun conditionalDefaults(block: ConditionalDefaultsBuilder<TState>.() -> Unit) {
        val builder = ConditionalDefaultsBuilder<TState>()
        builder.block()
        
        val baseStrategy = strategy // Use the current strategy as base
        strategy = RestorationStrategy.RestoreWithDefaults(
            baseStrategy = baseStrategy,
            conditionalDefaults = builder.build()
        )
    }
    
    internal fun build(): RestorationStrategy = strategy
}

/**
 * Builder for specifying individual routes to restore.
 */
class RestoreSpecificBuilder {
    internal var sceneRoutes = mutableSetOf<String>()
    internal var contentRoutes = mutableSetOf<String>()
    internal var modalRoutes = mutableSetOf<String>()
    
    /**
     * Specify scene routes to restore.
     */
    fun scenes(vararg paths: String) {
        sceneRoutes.addAll(paths)
    }
    
    /**
     * Specify content routes to restore.
     */
    fun content(vararg paths: String) {
        contentRoutes.addAll(paths)
    }
    
    /**
     * Specify modal routes to restore.
     */
    fun modals(vararg paths: String) {
        modalRoutes.addAll(paths)
    }
}

/**
 * Builder for configuring conditional defaults.
 */
class ConditionalDefaultsBuilder<TState: StateModel> {
    private val defaults = mutableListOf<ConditionalDefault<TState>>()
    private var fallbackRoute: String? = null
    
    /**
     * Add a conditional default route using a when-style syntax.
     * Example: `when { state.isAuthenticated } then "/home"`
     */
    fun `when`(condition: (TState) -> Boolean): ConditionalDefaultClause<TState> {
        return ConditionalDefaultClause(this, condition)
    }
    
    /**
     * Set a fallback route to use when no conditions match.
     */
    fun otherwise(route: String) {
        fallbackRoute = route
    }
    
    internal fun addDefault(default: ConditionalDefault<TState>) {
        defaults.add(default)
    }
    
    internal fun build(): ConditionalDefaultsConfig<TState> {
        return ConditionalDefaultsConfig(
            defaults = defaults.toList(),
            fallbackRoute = fallbackRoute
        )
    }
}

/**
 * Helper class for the when-then DSL syntax.
 */
class ConditionalDefaultClause<TState: StateModel>(
    private val builder: ConditionalDefaultsBuilder<TState>,
    private val condition: (TState) -> Boolean
) {
    /**
     * Specify the route to navigate to when the condition is true.
     */
    infix fun then(route: String) {
        builder.addDefault(ConditionalDefault(
            condition = condition,
            route = route
        ))
    }
    
    /**
     * Specify the route and layer to navigate to when the condition is true.
     */
    fun then(route: String, layer: NavigationLayer) {
        builder.addDefault(ConditionalDefault(
            condition = condition,
            route = route,
            layer = layer
        ))
    }
}