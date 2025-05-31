package duks.routing

import duks.Action
import duks.KStore
import duks.Middleware
import duks.StateModel
import duks.StoreLifecycleAware
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Router middleware with isolated state
class RouterMiddleware<TState: StateModel>(
    private val authConfig: AuthConfig<TState>,
    private val routes: List<Route<*>>,
    private val fallbackRoute: String = "/404",
    private val initialRoute: String? = null
) : Middleware<TState>, StoreLifecycleAware<TState> {
    // Private internal state
    private val routerState = MutableStateFlow(RouterState())

    // Public read-only access to router state
    val state: StateFlow<RouterState> = routerState.asStateFlow()
    
    override suspend fun onStoreCreated(store: KStore<TState>) {
        // Initialize with the configured initial route
        val initialRoute = findInitialRoute()
        val initialState = if (initialRoute != null) {
            val instance = createRouteInstance(initialRoute)
            when (initialRoute.layer) {
                NavigationLayer.Scene -> RouterState(sceneRoutes = listOf(instance), lastRouteType = RouteType.Scene)
                NavigationLayer.Content -> RouterState(contentRoutes = listOf(instance), lastRouteType = RouteType.Content)
                NavigationLayer.Modal -> RouterState(modalRoutes = listOf(instance), lastRouteType = RouteType.Modal)
            }
        } else {
            RouterState()
        }
        routerState.value = initialState
        store.dispatch(Routing.StateChanged(initialState))
    }

    override suspend fun invoke(store: KStore<TState>, next: suspend (Action) -> Action, action: Action): Action {
        return when (action) {
            is Routing -> {
                if (action is Routing.StateChanged) {
                    // StateChanged actions are just passed through - don't process them again
                    next(action)
                } else {
                    val currentState = routerState.value
                    val newState = processRoutingAction(store, action, currentState)

                    if (newState != currentState) {
                        // Update internal state first
                        routerState.value = newState

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
                val currentState = routerState.value
                val newState = when (action) {
                    is DeviceAction.UpdateDeviceContext ->
                        currentState.copy(deviceContext = action.context)
                    is DeviceAction.UpdateScreenSize -> {
                        val context = currentState.deviceContext ?: DeviceContext(
                            screenWidth = action.width,
                            screenHeight = action.height,
                            orientation = if (action.width > action.height) ScreenOrientation.Landscape else ScreenOrientation.Portrait,
                            deviceType = determineDeviceType(action.width)
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
                routerState.value = newState
                next(action)
            }
            else -> next(action)
        }
    }

    private fun findInitialRoute(): Route<*>? {
        // Use the explicitly set initial route if provided
        initialRoute?.let { path ->
            val normalizedPath = normalizePath(path)
            return routes.find { it.path == normalizedPath && it.layer == NavigationLayer.Content }
                ?: routes.find { it.path == normalizedPath }
        }
        
        // Only use "/" as default initial route, don't auto-select other routes
        return routes.find { it.path == "/" && it.layer == NavigationLayer.Content }
            ?: routes.find { it.path == "/" }
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
        val matchingRoutes = findMatchingRoutes(path, state.deviceContext)
        
        // If layer is specified, filter by it
        val route = if (action.layer != null) {
            matchingRoutes.firstOrNull { it.layer == action.layer }
        } else {
            matchingRoutes.firstOrNull()
        }

        if (route == null) {
            // Try fallback
            val fallbackRoutes = findMatchingRoutes(fallbackRoute, state.deviceContext)
            return fallbackRoutes.firstOrNull()?.let { fallback ->
                navigateToRoute(fallback, action.param, action.layer ?: fallback.layer, state, action.clearHistory)
            } ?: state
        }

        // Check authentication
        if (route.requiresAuth && !authConfig.authChecker(store.state.value)) {
            authConfig.onAuthFailure?.invoke(store, route)
            val authRoutes = findMatchingRoutes(authConfig.unauthenticatedRoute, state.deviceContext)
            return authRoutes.firstOrNull()?.let { authRoute ->
                navigateToRoute(authRoute, action.param, action.layer ?: authRoute.layer, state, action.clearHistory)
            } ?: state
        }

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
                state.copy(
                    modalRoutes = state.modalRoutes.dropLast(1),
                    lastRouteType = RouteType.Back
                )
            }
            state.contentRoutes.size > 1 -> {
                state.copy(
                    contentRoutes = state.contentRoutes.dropLast(1),
                    lastRouteType = RouteType.Back
                )
            }
            state.sceneRoutes.size > 1 -> {
                state.copy(
                    sceneRoutes = state.sceneRoutes.dropLast(1),
                    lastRouteType = RouteType.Back
                )
            }
            else -> {
                state.copy(lastRouteType = RouteType.Back)
            }
        }
    }

    private fun handlePopToPath(action: Routing.PopToPath, state: RouterState): RouterState {
        val path = normalizePath(action.path)
        val contentIndex = state.contentRoutes.indexOfLast { it.route.path == path }

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
        val matchingRoutes = findMatchingRoutes(path, state.deviceContext)
        val route = matchingRoutes.firstOrNull { it.layer == NavigationLayer.Modal } ?: return state

        return state.copy(
            modalRoutes = state.modalRoutes + createRouteInstance(route, action.param),
            lastRouteType = RouteType.Modal
        )
    }

    private fun handleDismissModal(action: Routing.DismissModal, state: RouterState): RouterState {
        return if (action.path != null) {
            val path = normalizePath(action.path)
            state.copy(
                modalRoutes = state.modalRoutes.filter { it.route.path != path },
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
}

internal fun normalizePath(path: String): String {
    return "/" + path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/")
}

private fun parseDeepLink(url: String): String {
    return url.substringAfter("://").let { "/$it" }
}