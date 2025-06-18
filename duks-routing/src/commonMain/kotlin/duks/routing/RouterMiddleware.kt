package duks.routing

import duks.*
import duks.logging.Logger
import duks.logging.debug
import duks.logging.info
import duks.logging.warn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Router middleware with isolated state
class RouterMiddleware<TState: StateModel>(
    private val authConfig: AuthConfig<TState>,
    private val routes: List<Route<*>>,
    private val fallbackRoute: String = "/404",
    private val initialRoute: String? = null,
    private val restorationStrategy: RestorationStrategy = RestorationStrategy.RestoreAll
) : Middleware<TState>, StoreLifecycleAware<TState> {
    private val logger = Logger.default()
    
    // Private mutable state
    private val internalState = MutableStateFlow(RouterState())
    private var hasInitialized = false

    // Public read-only access to router state
    val state: StateFlow<RouterState> = internalState.asStateFlow()
    
    // Track if storage restoration is in progress
    private var isRestorationInProgress = false
    private var storeReference: KStore<TState>? = null
    // Track if restoration has completed (regardless of whether state was restored)
    private var restorationCompleted = false
    // Track if we need to apply initial route after getting store reference
    private var pendingInitialRoute = false
    
    override suspend fun onStoreCreated(store: KStore<TState>) {
        // Store reference for later use
        storeReference = store
        
        // Check if we have a pending initial route (restoration completed before onStoreCreated)
        if (pendingInitialRoute && !hasInitialized) {
            applyInitialRoute(store)
            hasInitialized = true
            pendingInitialRoute = false
        } else if (!isRestorationInProgress && !restorationCompleted) {
            applyInitialRoute(store)
            hasInitialized = true
        }
    }
    
    override suspend fun onStorageRestorationStarted() {
        // Mark that restoration is starting
        isRestorationInProgress = true
    }
    
    override suspend fun onStorageRestorationCompleted(restored: Boolean) {
        // Mark restoration as complete
        isRestorationInProgress = false
        restorationCompleted = true
        
        // Only apply initial route if no state was restored (empty storage)
        // If state was restored, RestoreStateAction will handle initialization
        if (!restored && !hasInitialized) {
            if (storeReference != null) {
                applyInitialRoute(storeReference!!)
                hasInitialized = true
            } else {
                pendingInitialRoute = true
            }
        }
    }

    override suspend fun invoke(store: KStore<TState>, next: suspend (Action) -> Action, action: Action): Action {
        return when (action) {
            is Routing -> {
                if (action is Routing.StateChanged) {
                    // StateChanged actions are just passed through - don't process them again
                    next(action)
                } else {
                    val currentState = internalState.value
                    val newState = processRoutingAction(store, action, currentState)

                    if (newState != currentState) {
                        logger.debug(action::class.simpleName) { "Router state changing due to action: {actionType}" }
                        
                        // Update state
                        internalState.value = newState

                        // Pass the original action through
                        val result = next(action)

                        // Then dispatch the state change through the store to ensure it reaches all reducers
                        store.dispatch(Routing.StateChanged(newState))

                        result
                    } else {
                        // Pass through the action even if state didn't change
                        next(action)
                    }
                }
            }
            is DeviceAction -> {
                // Update device context in router state
                val currentState = internalState.value
                val newState = when (action) {
                    is DeviceAction.UpdateDeviceContext -> {
                        logger.debug(action.context.deviceType, action.context.screenWidth, action.context.screenHeight) { "Updating device context: {deviceType}, {screenWidth}x{screenHeight}" }
                        currentState.copy(deviceContext = action.context)
                    }
                    is DeviceAction.UpdateScreenSize -> {
                        val deviceType = determineDeviceType(action.width)
                        logger.debug(action.width, action.height, deviceType) { "Updating screen size: {width}x{height}, detected device type: {deviceType}" }
                        
                        val context = currentState.deviceContext ?: DeviceContext(
                            screenWidth = action.width,
                            screenHeight = action.height,
                            orientation = if (action.width > action.height) ScreenOrientation.Landscape else ScreenOrientation.Portrait,
                            deviceType = deviceType
                        )
                        currentState.copy(
                            deviceContext = context.copy(
                                screenWidth = action.width,
                                screenHeight = action.height,
                                orientation = if (action.width > action.height) ScreenOrientation.Landscape else ScreenOrientation.Portrait
                            )
                        )
                    }
                    is DeviceAction.UpdateOrientation -> {
                        logger.debug(action.orientation) { "Updating device orientation: {orientation}" }
                        val context = currentState.deviceContext
                        if (context != null) {
                            currentState.copy(
                                deviceContext = context.copy(orientation = action.orientation)
                            )
                        } else {
                            currentState
                        }
                    }
                }
                internalState.value = newState
                next(action)
            }
            is RestoreStateAction<*> -> {
                @Suppress("UNCHECKED_CAST") 
                val restoreAction = action as RestoreStateAction<TState>
                val restoredState = restoreAction.state
                
                // Extract router state if the state implements HasRouterState
                val routerState = when (restoredState) {
                    is HasRouterState -> {
                        restoredState.routerState
                    }
                    else -> {
                        null
                    }
                }
                
                // Restore router state using the restoration logic
                val restoredInternalState = if (routerState != null) {
                    restoreFromRouterState(
                        routerState = routerState,
                        availableRoutes = routes,
                        strategy = restorationStrategy,
                        currentState = restoredState
                    )
                } else {
                    // No router state - check if we should apply conditional defaults
                    if (restorationStrategy is RestorationStrategy.RestoreWithDefaults<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val defaults = restorationStrategy.conditionalDefaults as ConditionalDefaultsConfig<TState>
                        applyConditionalDefaults(defaults, restoredState, routes)
                    } else {
                        null
                    }
                }
                
                // Pass the action through first so app reducer can process it
                val result = next(action)
                
                if (restoredInternalState != null) {
                    // Log restoration details
                    val restoredPaths = buildString {
                        if (restoredInternalState.sceneRoutes.isNotEmpty()) {
                            append("scenes: ${restoredInternalState.sceneRoutes.map { it.path }}")
                        }
                        if (restoredInternalState.contentRoutes.isNotEmpty()) {
                            if (this.isNotEmpty()) append(", ")
                            append("content: ${restoredInternalState.contentRoutes.map { it.path }}")
                        }
                        if (restoredInternalState.modalRoutes.isNotEmpty()) {
                            if (this.isNotEmpty()) append(", ")
                            append("modals: ${restoredInternalState.modalRoutes.map { it.path }}")
                        }
                    }
                    
                    logger.info(
                        restoredInternalState.contentRoutes.size,
                        restoredInternalState.modalRoutes.size,
                        restoredInternalState.sceneRoutes.size
                    ) { "Router state restored with {contentCount} content routes, {modalCount} modals, {sceneCount} scenes" }
                    
                    if (restoredPaths.isNotEmpty()) {
                        logger.debug(restoredPaths) { "Restored routes: {routes}" }
                    }
                    
                    internalState.value = restoredInternalState
                    hasInitialized = true
                    
                    // Dispatch state change notification AFTER the RestoreStateAction has been processed
                    // This ensures the app gets the real routes, not the serializable ones
                    store.dispatch(Routing.StateChanged(restoredInternalState))
                } else {
                    // No router state restored - check if we should apply initial route
                    // Only apply if no routes exist (in case onStoreCreated didn't run yet)
                    if (!hasRoutes(internalState.value)) {
                        applyInitialRoute(store)
                        hasInitialized = true
                    }
                }
                
                result
            }
            else -> next(action)
        }
    }

    private fun findInitialRoute(): Route<*>? {
        
        // Use the explicitly set initial route if provided
        initialRoute?.let { path ->
            val normalizedPath = normalizePath(path)
            
            val contentRoute = routes.find { it.path == normalizedPath && it.layer == NavigationLayer.Content }
            if (contentRoute != null) {
                return contentRoute
            }
            
            val anyRoute = routes.find { it.path == normalizedPath }
            if (anyRoute != null) {
                return anyRoute
            }
            
        }
        
        // Only use "/" as default initial route, don't auto-select other routes
        return routes.find { it.path == "/" && it.layer == NavigationLayer.Content }
            ?: routes.find { it.path == "/" }
    }
    
    private suspend fun applyInitialRoute(store: KStore<TState>) {
        val initialRoute = findInitialRoute()
        if (initialRoute != null) {
            logger.debug(initialRoute.path) { "Applying initial route: {path}" }
            
            val instance = createRouteInstance(initialRoute)
            val initialInternalState = when (initialRoute.layer) {
                NavigationLayer.Scene -> RouterState(sceneRoutes = listOf(instance), lastRouteType = RouteType.Scene)
                NavigationLayer.Content -> RouterState(contentRoutes = listOf(instance), lastRouteType = RouteType.Content)
                NavigationLayer.Modal -> RouterState(modalRoutes = listOf(instance), lastRouteType = RouteType.Modal)
            }
            
            internalState.value = initialInternalState
            
            try {
                store.dispatch(Routing.StateChanged(initialInternalState))
            } catch (e: Exception) {
                logger.error("Failed to dispatch StateChanged: ${e.message}")
                throw e
            }
        }
    }

    private fun processRoutingAction(store: KStore<TState>, action: Routing, state: RouterState): RouterState {
        return when (action) {
            is Routing.NavigateTo -> handleNavigateTo(store, action, state)
            is Routing.ReplaceContent -> handleReplaceContent(action, state)
            is Routing.GoBack -> handleGoBack(state)
            is Routing.PopToPath -> handlePopToPath(action, state)
            is Routing.ClearLayer -> handleClearLayer(action, state)
            is Routing.ShowModal -> handleShowModal(action, state)
            is Routing.DismissModal -> handleDismissModal(action, state)
            is Routing.DeepLink -> handleDeepLink(store, action, state)
            is Routing.StateChanged -> state // No-op, just for notification
        }
    }

    private fun handleNavigateTo(store: KStore<TState>, action: Routing.NavigateTo, state: RouterState): RouterState {
        val path = normalizePath(action.path)
        logger.debug(path, action.layer ?: "auto", action.param) { "Navigating to: {path}, layer: {layer}, param: {param}" }
        
        val matchingRoutes = findMatchingRoutes(path, state.deviceContext)
        
        // If layer is specified, filter by it
        val route = if (action.layer != null) {
            matchingRoutes.firstOrNull { it.layer == action.layer }
        } else {
            matchingRoutes.firstOrNull()
        }

        if (route == null) {
            logger.warn(path, fallbackRoute) { "Route not found: {path}, attempting fallback to: {fallbackRoute}" }
            // Try fallback
            val fallbackRoutes = findMatchingRoutes(fallbackRoute, state.deviceContext)
            return fallbackRoutes.firstOrNull()?.let { fallback ->
                navigateToRoute(fallback, action.param, action.layer ?: fallback.layer, state, action.clearHistory)
            } ?: state
        }

        // Check authentication
        if (route.requiresAuth && !authConfig.authChecker(store.state.value)) {
            logger.info(path, authConfig.unauthenticatedRoute) { "Authentication required for route: {path}, redirecting to: {unauthPath}" }
            authConfig.onAuthFailure?.invoke(store, route)
            val authRoutes = findMatchingRoutes(authConfig.unauthenticatedRoute, state.deviceContext)
            return authRoutes.firstOrNull()?.let { authRoute ->
                navigateToRoute(authRoute, action.param, action.layer ?: authRoute.layer, state, action.clearHistory)
            } ?: state
        }

        logger.debug(route.path, route.layer) { "Successfully navigating to route: {routePath} on layer: {routeLayer}" }
        return navigateToRoute(route, action.param, action.layer ?: route.layer, state, action.clearHistory)
    }

    private fun handleReplaceContent(action: Routing.ReplaceContent, state: RouterState): RouterState {
        val path = normalizePath(action.path)
        val matchingRoutes = findMatchingRoutes(path, state.deviceContext)
        val route = matchingRoutes.firstOrNull() ?: return state

        return when (route.layer) {
            NavigationLayer.Content -> state.copy(
                contentRoutes = listOf(createRouteInstance(route, action.param)),
                lastRouteType = RouteType.Content
            )
            else -> state
        }
    }

    private fun handleGoBack(state: RouterState): RouterState {
        return when {
            state.modalRoutes.isNotEmpty() -> {
                logger.debug { "Going back: dismissing modal" }
                state.copy(
                    modalRoutes = state.modalRoutes.dropLast(1),
                    lastRouteType = RouteType.Back
                )
            }
            state.contentRoutes.size > 1 -> {
                logger.debug(state.contentRoutes.last().path, state.contentRoutes[state.contentRoutes.size - 2].path) { "Going back: from {fromPath} to {toPath}" }
                state.copy(
                    contentRoutes = state.contentRoutes.dropLast(1),
                    lastRouteType = RouteType.Back
                )
            }
            state.sceneRoutes.size > 1 -> {
                logger.debug(state.sceneRoutes.last().path, state.sceneRoutes[state.sceneRoutes.size - 2].path) { "Going back: from scene {fromPath} to {toPath}" }
                state.copy(
                    sceneRoutes = state.sceneRoutes.dropLast(1),
                    lastRouteType = RouteType.Back
                )
            }
            else -> {
                logger.debug { "Going back: no navigation possible, already at root" }
                state.copy(lastRouteType = RouteType.Back)
            }
        }
    }

    private fun handlePopToPath(action: Routing.PopToPath, state: RouterState): RouterState {
        val path = normalizePath(action.path)
        val contentIndex = state.contentRoutes.indexOfLast { it.path == path }

        return if (contentIndex >= 0) {
            state.copy(
                contentRoutes = state.contentRoutes.take(contentIndex + 1),
                lastRouteType = RouteType.Back
            )
        } else {
            state
        }
    }

    private fun handleClearLayer(action: Routing.ClearLayer, state: RouterState): RouterState {
        return when (action.layer) {
            NavigationLayer.Scene -> state.copy(sceneRoutes = emptyList())
            NavigationLayer.Content -> state.copy(contentRoutes = emptyList())
            NavigationLayer.Modal -> state.copy(modalRoutes = emptyList())
        }
    }

    private fun handleShowModal(action: Routing.ShowModal, state: RouterState): RouterState {
        val path = normalizePath(action.path)
        logger.debug(path, action.param) { "Showing modal: {path}, param: {param}" }
        
        val matchingRoutes = findMatchingRoutes(path, state.deviceContext)
        val route = matchingRoutes.firstOrNull { it.layer == NavigationLayer.Modal }
        
        if (route == null) {
            logger.warn(path) { "Modal route not found: {path}" }
            return state
        }

        return state.copy(
            modalRoutes = state.modalRoutes + createRouteInstance(route, action.param),
            lastRouteType = RouteType.Modal
        )
    }

    private fun handleDismissModal(action: Routing.DismissModal, state: RouterState): RouterState {
        return if (action.path != null) {
            val path = normalizePath(action.path)
            state.copy(
                modalRoutes = state.modalRoutes.filter { it.path != path },
                lastRouteType = RouteType.Back
            )
        } else {
            state.copy(
                modalRoutes = state.modalRoutes.dropLast(1),
                lastRouteType = RouteType.Back
            )
        }
    }

    private fun handleDeepLink(store: KStore<TState>, action: Routing.DeepLink, state: RouterState): RouterState {
        val path = parseDeepLink(action.url)
        return handleNavigateTo(store, Routing.NavigateTo(path), state)
    }

    private fun navigateToRoute(
        route: Route<*>,
        param: Any?,
        layer: NavigationLayer,
        state: RouterState,
        clearHistory: Boolean = false
    ): RouterState {
        val instance = createRouteInstance(route, param)

        return when (layer) {
            NavigationLayer.Scene -> state.copy(
                sceneRoutes = state.sceneRoutes + instance,
                contentRoutes = emptyList(),
                modalRoutes = emptyList(),
                lastRouteType = RouteType.Scene
            )
            NavigationLayer.Content -> if (clearHistory) {
                state.copy(
                    contentRoutes = listOf(instance),
                    modalRoutes = emptyList(),
                    lastRouteType = RouteType.Content
                )
            } else {
                state.copy(
                    contentRoutes = state.contentRoutes + instance,
                    lastRouteType = RouteType.Content
                )
            }
            NavigationLayer.Modal -> state.copy(
                modalRoutes = state.modalRoutes + instance,
                lastRouteType = RouteType.Modal
            )
        }
    }

    private fun findMatchingRoutes(path: String, deviceContext: DeviceContext?): List<Route<*>> {
        return routes
            .filter { it.path == path }
            .filter { route ->
                if (deviceContext == null || route.renderConditions.isEmpty()) {
                    true
                } else {
                    route.renderConditions.all { condition ->
                        evaluateCondition(condition, deviceContext)
                    }
                }
            }
    }

    private fun evaluateCondition(condition: RenderCondition, context: DeviceContext): Boolean {
        return when (condition) {
            is RenderCondition.ScreenSize -> {
                (condition.minWidth == null || context.screenWidth >= condition.minWidth) &&
                (condition.maxWidth == null || context.screenWidth <= condition.maxWidth)
            }
            is RenderCondition.Orientation -> context.orientation == condition.orientation
            is RenderCondition.DeviceType -> context.deviceType in condition.types
            is RenderCondition.Custom -> condition.check(context)
            is RenderCondition.Composite -> when (condition.operator) {
                CompositeOperator.AND -> condition.conditions.all { evaluateCondition(it, context) }
                CompositeOperator.OR -> condition.conditions.any { evaluateCondition(it, context) }
            }
        }
    }

    private fun determineDeviceType(width: Int): DeviceClass {
        return when {
            width <= 320 -> DeviceClass.Watch  // Watch devices typically have very small screens
            width < 600 -> DeviceClass.Phone
            width < 1024 -> DeviceClass.Tablet
            else -> DeviceClass.Desktop
        }
    }
    
    private fun <TState: StateModel> restoreFromRouterState(
        routerState: RouterState,
        availableRoutes: List<Route<*>>,
        strategy: RestorationStrategy,
        currentState: TState? = null
    ): RouterState? {
        // Use the RouterRestoration logic to handle restoration strategy
        val filteredRouterState = RouterRestoration.applyRestorationStrategy(
            routerState = routerState,
            strategy = strategy,
            currentState = currentState
        )
        
        // Always evaluate conditional defaults when using RestoreWithDefaults strategy
        if (strategy is RestorationStrategy.RestoreWithDefaults<*>) {
            @Suppress("UNCHECKED_CAST")
            val defaults = strategy.conditionalDefaults as ConditionalDefaultsConfig<TState>
            if (currentState != null) {
                // Apply conditional defaults - if one matches, use it instead of restored routes
                val conditionalRouterState = applyConditionalDefaults(defaults, currentState, availableRoutes)
                if (conditionalRouterState != null) {
                    return conditionalRouterState
                }
            }
        }
        
        // Convert serializable routes back to actual RouteInstances
        val routeMap = availableRoutes.associateBy { it.path }
        
        val sceneRoutes = filteredRouterState.sceneRoutes.mapNotNull { serializableRoute ->
            routeMap[serializableRoute.path]?.let { route ->
                createRouteInstance(route, serializableRoute.param)
            }
        }
        
        val contentRoutes = filteredRouterState.contentRoutes.mapNotNull { serializableRoute ->
            routeMap[serializableRoute.path]?.let { route ->
                createRouteInstance(route, serializableRoute.param)
            }
        }
        
        val modalRoutes = filteredRouterState.modalRoutes.mapNotNull { serializableRoute ->
            routeMap[serializableRoute.path]?.let { route ->
                createRouteInstance(route, serializableRoute.param)
            }
        }
        
        return RouterState(
            sceneRoutes = sceneRoutes,
            contentRoutes = contentRoutes,
            modalRoutes = modalRoutes,
            deviceContext = filteredRouterState.deviceContext,
            lastRouteType = filteredRouterState.lastRouteType
        )
    }
    
    private fun hasRoutes(routerState: RouterState): Boolean {
        return routerState.sceneRoutes.isNotEmpty() || 
               routerState.contentRoutes.isNotEmpty() || 
               routerState.modalRoutes.isNotEmpty()
    }
    
    private fun <TState: StateModel> applyConditionalDefaults(
        config: ConditionalDefaultsConfig<TState>,
        currentState: TState,
        routes: List<Route<*>>
    ): RouterState? {
        // Find the first matching condition
        val matchingDefault = config.defaults.firstOrNull { default ->
            try {
                default.condition(currentState)
            } catch (e: Exception) {
                logger.error("Error evaluating conditional default: ${e.message ?: "Unknown error"}")
                false
            }
        }
        
        val routePath = matchingDefault?.route ?: config.fallbackRoute
        if (routePath == null) {
            logger.debug("No matching conditional defaults or fallback, continuing with restored routes")
            return null
        }
        
        logger.info("Applying conditional default route: $routePath (overriding restored routes)")
        
        // Find the route in available routes
        val route = routes.find { it.path == routePath }
        if (route == null) {
            logger.warn("Conditional default route not found: $routePath")
            return null
        }
        
        // Create route instance based on layer
        val routeInstance = createRouteInstance(route)
        return when (route.layer) {
            NavigationLayer.Scene -> RouterState(
                sceneRoutes = listOf(routeInstance),
                lastRouteType = RouteType.Scene
            )
            NavigationLayer.Content -> RouterState(
                contentRoutes = listOf(routeInstance),
                lastRouteType = RouteType.Content
            )
            NavigationLayer.Modal -> RouterState(
                modalRoutes = listOf(routeInstance),
                lastRouteType = RouteType.Modal
            )
        }
    }
}

internal fun normalizePath(path: String): String {
    return "/" + path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/")
}

private fun parseDeepLink(url: String): String {
    return url.substringAfter("://").let { "/$it" }
}