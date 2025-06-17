package duks.routing

import duks.StateModel

/**
 * Handles the restoration of router state.
 * Applies restoration strategies and conditional defaults.
 */
object RouterRestoration {

    /**
     * Apply restoration strategy to filter router state.
     */
    fun <TState: StateModel> applyRestorationStrategy(
        routerState: RouterState,
        strategy: RestorationStrategy,
        currentState: TState? = null
    ): RouterState {
        // Handle RestoreWithDefaults by extracting base strategy
        val baseStrategy = when (strategy) {
            is RestorationStrategy.RestoreWithDefaults<*> -> strategy.baseStrategy
            else -> strategy
        }
        
        // Filter routes based on restoration strategy
        return filterRoutes(routerState, baseStrategy)
    }
    
    /**
     * Filter routes based on restoration strategy.
     */
    private fun filterRoutes(
        routerState: RouterState,
        strategy: RestorationStrategy
    ): RouterState {
        return when (strategy) {
            is RestorationStrategy.RestoreAll -> routerState
            
            is RestorationStrategy.RestoreOnly -> RouterState(
                sceneRoutes = if (RouteType.Scene in strategy.types) routerState.sceneRoutes else emptyList(),
                contentRoutes = if (RouteType.Content in strategy.types) routerState.contentRoutes else emptyList(),
                modalRoutes = if (RouteType.Modal in strategy.types) routerState.modalRoutes else emptyList(),
                deviceContext = routerState.deviceContext,
                lastRouteType = routerState.lastRouteType
            )
            
            is RestorationStrategy.RestoreSpecific -> RouterState(
                sceneRoutes = routerState.sceneRoutes.filter { it.path in strategy.scenes },
                contentRoutes = routerState.contentRoutes.filter { it.path in strategy.content },
                modalRoutes = routerState.modalRoutes.filter { it.path in strategy.modals },
                deviceContext = routerState.deviceContext,
                lastRouteType = routerState.lastRouteType
            )
            
            is RestorationStrategy.RestoreWithDefaults<*> -> {
                // This case is handled above by extracting the base strategy
                throw IllegalStateException("RestoreWithDefaults should not reach here")
            }
        }
    }
}