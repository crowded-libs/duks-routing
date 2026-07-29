package duks.routing

import duks.Action
import duks.RestoreStateAction
import duks.StateModel
import duks.logging.Logger
import duks.logging.debug
import duks.logging.info
import duks.logging.warn
import duks.routing.features.FeatureToggleEvaluator

/**
 * Pure navigation logic shared by the auto-registered reducer and middleware policy checks.
 */
class RouterLogic<TState : HasRouterState>(
    private val authConfig: AuthConfig<TState>,
    private val routes: List<Route<*>>,
    private val fallbackRoute: String = "/404",
    private val initialRoutePath: String? = null,
    private val restorationStrategy: RestorationStrategy = RestorationStrategy.RestoreAll,
    private val featureToggleEvaluator: FeatureToggleEvaluator? = null,
    private val paramRegistry: RouteParamRegistry = RouteParamRegistry.Default,
    private val reevaluateFeaturesOnAppStateChange: Boolean = true
) {
    private val logger = Logger.default()

    private val routeFeatures: Set<String> = routes.mapNotNull { it.requiredFeature }.toSet()

    /**
     * Reduce [state] for any action. Returns the same instance when nothing router-related changed.
     */
    @Suppress("UNCHECKED_CAST")
    fun reduce(state: TState, action: Action): TState {
        val nextRouter = reduceRouter(state.routerState, action, state) ?: return state
        if (nextRouter == state.routerState) return state
        return state.withRouterState(nextRouter) as TState
    }

    /**
     * @return new [RouterState], or null when the action does not affect routing
     */
    fun reduceRouter(router: RouterState, action: Action, appState: TState): RouterState? {
        val navigated = when (action) {
            is Routing.NavigateTo -> handleNavigateTo(action, router, appState)
            is Routing.ReplaceContent -> handleReplaceContent(action, router, appState)
            is Routing.GoBack -> handleGoBack(router)
            is Routing.PopToPath -> handlePopToPath(action, router)
            is Routing.ClearLayer -> handleClearLayer(action, router)
            is Routing.ShowModal -> handleShowModal(action, router, appState)
            is Routing.DismissModal -> handleDismissModal(action, router)
            is Routing.DeepLink -> {
                val parsed = parseDeepLink(action.url)
                handleNavigateTo(Routing.NavigateTo(path = parsed.path), router, appState)
            }
            is Routing.SyncFeatures -> router
            is DeviceAction -> handleDeviceAction(action, router)
            is RestoreStateAction<*> -> {
                // Full restore is applied by KStore before reducers; middleware rewrites the action.
                // If a Restore still reaches the reducer, rehydrate from its payload.
                @Suppress("UNCHECKED_CAST")
                val restored = action.state as? TState ?: return null
                rehydrateFromAppState(restored) ?: return null
            }
            else -> {
                if (!reevaluateFeaturesOnAppStateChange || featureToggleEvaluator == null) {
                    return null
                }
                router
            }
        }

        val withFeatures = if (featureToggleEvaluator != null) {
            navigated.copy(enabledFeatures = evaluateFeatures(appState))
        } else {
            navigated
        }
        return withFeatures
    }

    fun findInitialRoute(): Route<*>? {
        initialRoutePath?.let { path ->
            val normalizedPath = normalizePath(path)
            routes.find { it.path == normalizedPath && it.layer == NavigationLayer.Content }
                ?: routes.find { it.path == normalizedPath }
        }?.let { return it }

        return routes.find { it.path == "/" && it.layer == NavigationLayer.Content }
            ?: routes.find { it.path == "/" }
    }

    fun buildInitialRouterState(appState: TState): RouterState? {
        val route = findInitialRoute() ?: return null
        val instance = createRouteInstance(route)
        val enabledFeatures = evaluateFeatures(appState)
        return when (route.layer) {
            NavigationLayer.Scene -> RouterState(
                sceneRoutes = listOf(instance),
                lastRouteType = RouteType.Scene,
                enabledFeatures = enabledFeatures
            )
            NavigationLayer.Content -> RouterState(
                contentRoutes = listOf(instance),
                lastRouteType = RouteType.Content,
                enabledFeatures = enabledFeatures
            )
            NavigationLayer.Modal -> RouterState(
                modalRoutes = listOf(instance),
                lastRouteType = RouteType.Modal,
                enabledFeatures = enabledFeatures
            )
        }
    }

    /**
     * Rehydrate wire-format routes and apply restoration strategy / conditional defaults.
     */
    fun rehydrateFromAppState(appState: TState): RouterState? {
        val routerState = appState.routerState
        if (!hasRoutes(routerState) && restorationStrategy is RestorationStrategy.RestoreWithDefaults<*>) {
            @Suppress("UNCHECKED_CAST")
            val defaults = restorationStrategy.conditionalDefaults as ConditionalDefaultsConfig<TState>
            return applyConditionalDefaults(defaults, appState)?.let {
                it.copy(enabledFeatures = evaluateFeatures(appState))
            }
        }
        if (!hasRoutes(routerState) && restorationStrategy !is RestorationStrategy.RestoreWithDefaults<*>) {
            return null
        }
        return restoreFromRouterState(routerState, appState)?.copy(
            enabledFeatures = evaluateFeatures(appState)
        )
    }

    fun hasProtectedRoutesActive(router: RouterState): Boolean {
        val activePaths = router.getActiveRoutes().map { it.path }.toSet()
        return routes.any { it.requiresAuth && it.path in activePaths }
    }

    fun evaluateFeatures(appState: TState): Set<String> {
        if (featureToggleEvaluator == null) return emptySet()
        return routeFeatures.filter { feature ->
            featureToggleEvaluator.isFeatureEnabled(appState, feature)
        }.toSet()
    }

    fun featuresNeedSync(appState: TState): Boolean {
        if (featureToggleEvaluator == null || !reevaluateFeaturesOnAppStateChange) return false
        return evaluateFeatures(appState) != appState.routerState.enabledFeatures
    }

    fun unauthenticatedNavigateAction(): Routing.NavigateTo =
        Routing.NavigateTo(
            path = authConfig.unauthenticatedRoute,
            clearHistory = true,
            mode = NavigationMode.ClearHistory
        )

    /**
     * Route that would be blocked by auth for this action, if any (for [AuthConfig.onAuthFailure]).
     */
    fun routeBlockedByAuth(action: Action, appState: TState, router: RouterState): Route<*>? {
        val path = when (action) {
            is Routing.NavigateTo -> normalizePath(action.path)
            is Routing.ShowModal -> normalizePath(action.path)
            is Routing.ReplaceContent -> normalizePath(action.path)
            else -> return null
        }
        val layer = when (action) {
            is Routing.NavigateTo -> action.layer
            is Routing.ShowModal -> NavigationLayer.Modal
            else -> null
        }
        val matched = findMatchingRoutesWithParams(path, router.deviceContext, appState)
        val route = if (layer != null) {
            matched.firstOrNull { it.route.layer == layer }?.route
        } else {
            matched.firstOrNull()?.route
        } ?: return null
        return if (route.requiresAuth && !authConfig.authChecker(appState)) route else null
    }

    private fun handleDeviceAction(action: DeviceAction, state: RouterState): RouterState {
        return when (action) {
            is DeviceAction.UpdateDeviceContext -> {
                state.copy(deviceContext = action.context)
            }
            is DeviceAction.UpdateScreenSize -> {
                val deviceType = DeviceClassHeuristics.fromDimensions(action.width, action.height)
                val context = state.deviceContext ?: DeviceContext(
                    screenWidth = action.width,
                    screenHeight = action.height,
                    orientation = if (action.width > action.height) {
                        ScreenOrientation.Landscape
                    } else {
                        ScreenOrientation.Portrait
                    },
                    deviceType = deviceType
                )
                state.copy(
                    deviceContext = context.copy(
                        screenWidth = action.width,
                        screenHeight = action.height,
                        orientation = if (action.width > action.height) {
                            ScreenOrientation.Landscape
                        } else {
                            ScreenOrientation.Portrait
                        },
                        deviceType = deviceType
                    )
                )
            }
            is DeviceAction.UpdateOrientation -> {
                val context = state.deviceContext ?: return state
                state.copy(deviceContext = context.copy(orientation = action.orientation))
            }
        }
    }

    private fun handleNavigateTo(
        action: Routing.NavigateTo,
        state: RouterState,
        appState: TState
    ): RouterState {
        val path = normalizePath(action.path)
        logger.debug(path, action.layer ?: "auto", action.param) {
            "Navigating to: {path}, layer: {layer}, param: {param}"
        }

        val matching = findMatchingRoutesWithParams(path, state.deviceContext, appState)
        val matched = if (action.layer != null) {
            matching.firstOrNull { it.route.layer == action.layer }
        } else {
            matching.firstOrNull()
        }
        val mode = if (action.clearHistory) NavigationMode.ClearHistory else action.mode

        if (matched == null) {
            logger.warn(path, fallbackRoute) {
                "Route not found: {path}, attempting fallback to: {fallbackRoute}"
            }
            val fallbackRoutes = findMatchingRoutesWithParams(fallbackRoute, state.deviceContext, appState)
            return fallbackRoutes.firstOrNull()?.let { fallback ->
                navigateToRoute(fallback.route, action.param, action.layer ?: fallback.route.layer, state, mode)
            } ?: state
        }

        val route = matched.route
        val effectiveParam = action.param ?: matched.pathMatch.inferredParam()

        if (route.requiresAuth && !authConfig.authChecker(appState)) {
            return redirectForAuthFailure(route, state, appState, mode)
        }

        logger.debug(route.path, route.layer, mode) {
            "Successfully navigating to route: {routePath} on layer: {routeLayer} mode={mode}"
        }
        return navigateToRoute(route, effectiveParam, action.layer ?: route.layer, state, mode)
    }

    private fun handleReplaceContent(
        action: Routing.ReplaceContent,
        state: RouterState,
        appState: TState
    ): RouterState {
        val path = normalizePath(action.path)
        val matched = findMatchingRoutesWithParams(path, state.deviceContext, appState).firstOrNull()
            ?: return state
        val route = matched.route
        val effectiveParam = action.param ?: matched.pathMatch.inferredParam()

        if (route.requiresAuth && !authConfig.authChecker(appState)) {
            return redirectForAuthFailure(route, state, appState)
        }

        return when (route.layer) {
            NavigationLayer.Content -> state.copy(
                contentRoutes = listOf(createRouteInstance(route, effectiveParam)),
                modalRoutes = emptyList(),
                lastRouteType = RouteType.Content
            )
            else -> state
        }
    }

    private fun handleGoBack(state: RouterState): RouterState {
        return when {
            state.modalRoutes.isNotEmpty() -> state.copy(
                modalRoutes = state.modalRoutes.dropLast(1),
                lastRouteType = RouteType.Back
            )
            state.contentRoutes.size > 1 ||
                (state.contentRoutes.isNotEmpty() && state.sceneRoutes.isNotEmpty()) -> {
                state.copy(
                    contentRoutes = state.contentRoutes.dropLast(1),
                    lastRouteType = RouteType.Back
                )
            }
            state.sceneRoutes.size > 1 -> state.copy(
                sceneRoutes = state.sceneRoutes.dropLast(1),
                lastRouteType = RouteType.Back
            )
            else -> state
        }
    }

    private fun handlePopToPath(action: Routing.PopToPath, state: RouterState): RouterState {
        val path = normalizePath(action.path)

        val modalIndex = state.modalRoutes.indexOfLast { it.path == path }
        if (modalIndex >= 0) {
            return state.copy(
                modalRoutes = state.modalRoutes.take(modalIndex + 1),
                lastRouteType = RouteType.Back
            )
        }

        val contentIndex = state.contentRoutes.indexOfLast { it.path == path }
        if (contentIndex >= 0) {
            return state.copy(
                contentRoutes = state.contentRoutes.take(contentIndex + 1),
                modalRoutes = emptyList(),
                lastRouteType = RouteType.Back
            )
        }

        val sceneIndex = state.sceneRoutes.indexOfLast { it.path == path }
        if (sceneIndex >= 0) {
            return state.copy(
                sceneRoutes = state.sceneRoutes.take(sceneIndex + 1),
                contentRoutes = emptyList(),
                modalRoutes = emptyList(),
                lastRouteType = RouteType.Back
            )
        }

        return state
    }

    private fun handleClearLayer(action: Routing.ClearLayer, state: RouterState): RouterState {
        return when (action.layer) {
            NavigationLayer.Scene -> state.copy(sceneRoutes = emptyList())
            NavigationLayer.Content -> state.copy(contentRoutes = emptyList())
            NavigationLayer.Modal -> state.copy(modalRoutes = emptyList())
        }
    }

    private fun handleShowModal(
        action: Routing.ShowModal,
        state: RouterState,
        appState: TState
    ): RouterState {
        val path = normalizePath(action.path)
        val matched = findMatchingRoutesWithParams(path, state.deviceContext, appState)
            .firstOrNull { it.route.layer == NavigationLayer.Modal }
            ?: return state

        val route = matched.route
        val effectiveParam = action.param ?: matched.pathMatch.inferredParam()

        if (route.requiresAuth && !authConfig.authChecker(appState)) {
            return redirectForAuthFailure(route, state, appState)
        }

        return state.copy(
            modalRoutes = state.modalRoutes + createRouteInstance(route, effectiveParam),
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

    private fun redirectForAuthFailure(
        route: Route<*>,
        state: RouterState,
        appState: TState,
        mode: NavigationMode = NavigationMode.Push
    ): RouterState {
        logger.info(route.path, authConfig.unauthenticatedRoute) {
            "Authentication required for route: {path}, redirecting to: {unauthPath}"
        }
        val authRoutes = findMatchingRoutesWithParams(
            normalizePath(authConfig.unauthenticatedRoute),
            state.deviceContext,
            appState
        )
        return authRoutes.firstOrNull()?.let { matched ->
            navigateToRoute(matched.route, param = null, matched.route.layer, state, mode)
        } ?: state
    }

    private fun navigateToRoute(
        route: Route<*>,
        param: Any?,
        layer: NavigationLayer,
        state: RouterState,
        mode: NavigationMode = NavigationMode.Push
    ): RouterState {
        val instance = createRouteInstance(route, param)

        return when (mode) {
            NavigationMode.ClearHistory -> when (layer) {
                NavigationLayer.Scene -> state.copy(
                    sceneRoutes = listOf(instance),
                    contentRoutes = emptyList(),
                    modalRoutes = emptyList(),
                    lastRouteType = RouteType.Scene
                )
                NavigationLayer.Content -> state.copy(
                    sceneRoutes = emptyList(),
                    contentRoutes = listOf(instance),
                    modalRoutes = emptyList(),
                    lastRouteType = RouteType.Content
                )
                NavigationLayer.Modal -> state.copy(
                    sceneRoutes = emptyList(),
                    contentRoutes = emptyList(),
                    modalRoutes = listOf(instance),
                    lastRouteType = RouteType.Modal
                )
            }

            NavigationMode.ReplaceLayer -> when (layer) {
                NavigationLayer.Scene -> state.copy(
                    sceneRoutes = listOf(instance),
                    contentRoutes = emptyList(),
                    modalRoutes = emptyList(),
                    lastRouteType = RouteType.Scene
                )
                NavigationLayer.Content -> state.copy(
                    contentRoutes = listOf(instance),
                    modalRoutes = emptyList(),
                    lastRouteType = RouteType.Content
                )
                NavigationLayer.Modal -> state.copy(
                    modalRoutes = listOf(instance),
                    lastRouteType = RouteType.Modal
                )
            }

            NavigationMode.SingleTop -> when (layer) {
                NavigationLayer.Scene -> {
                    val scenes = if (state.sceneRoutes.lastOrNull()?.path == instance.path) {
                        state.sceneRoutes.dropLast(1) + instance
                    } else {
                        state.sceneRoutes + instance
                    }
                    state.copy(
                        sceneRoutes = scenes,
                        contentRoutes = emptyList(),
                        modalRoutes = emptyList(),
                        lastRouteType = RouteType.Scene
                    )
                }
                NavigationLayer.Content -> {
                    val contents = if (state.contentRoutes.lastOrNull()?.path == instance.path) {
                        state.contentRoutes.dropLast(1) + instance
                    } else {
                        state.contentRoutes + instance
                    }
                    state.copy(
                        contentRoutes = contents,
                        lastRouteType = RouteType.Content
                    )
                }
                NavigationLayer.Modal -> {
                    val modals = if (state.modalRoutes.lastOrNull()?.path == instance.path) {
                        state.modalRoutes.dropLast(1) + instance
                    } else {
                        state.modalRoutes + instance
                    }
                    state.copy(
                        modalRoutes = modals,
                        lastRouteType = RouteType.Modal
                    )
                }
            }

            NavigationMode.Push -> when (layer) {
                NavigationLayer.Scene -> state.copy(
                    sceneRoutes = state.sceneRoutes + instance,
                    contentRoutes = emptyList(),
                    modalRoutes = emptyList(),
                    lastRouteType = RouteType.Scene
                )
                NavigationLayer.Content -> state.copy(
                    contentRoutes = state.contentRoutes + instance,
                    lastRouteType = RouteType.Content
                )
                NavigationLayer.Modal -> state.copy(
                    modalRoutes = state.modalRoutes + instance,
                    lastRouteType = RouteType.Modal
                )
            }
        }
    }

    private data class MatchedRoute(val route: Route<*>, val pathMatch: PathMatch)

    private fun findMatchingRoutesWithParams(
        path: String,
        deviceContext: DeviceContext?,
        appState: TState?
    ): List<MatchedRoute> {
        return routes.mapNotNull { route ->
            val pathMatch = matchPath(route.path, path) ?: return@mapNotNull null

            if (route.requiredFeature != null && featureToggleEvaluator != null && appState != null) {
                if (!featureToggleEvaluator.isFeatureEnabled(appState, route.requiredFeature)) {
                    return@mapNotNull null
                }
            }

            if (deviceContext != null && route.renderConditions.isNotEmpty()) {
                val ok = route.renderConditions.all { condition ->
                    evaluateCondition(condition, deviceContext, appState)
                }
                if (!ok) return@mapNotNull null
            }

            MatchedRoute(route, pathMatch)
        }
    }

    private fun evaluateCondition(
        condition: RenderCondition,
        context: DeviceContext,
        appState: TState?
    ): Boolean {
        return when (condition) {
            is RenderCondition.ScreenSize -> {
                (condition.minWidth == null || context.screenWidth >= condition.minWidth) &&
                    (condition.maxWidth == null || context.screenWidth <= condition.maxWidth)
            }
            is RenderCondition.Orientation -> context.orientation == condition.orientation
            is RenderCondition.DeviceType -> context.deviceType in condition.types
            is RenderCondition.Custom -> condition.check(context)
            is RenderCondition.Composite -> when (condition.operator) {
                CompositeOperator.AND -> condition.conditions.all { evaluateCondition(it, context, appState) }
                CompositeOperator.OR -> condition.conditions.any { evaluateCondition(it, context, appState) }
            }
            is RenderCondition.FeatureEnabled -> {
                if (featureToggleEvaluator != null && appState != null) {
                    featureToggleEvaluator.isFeatureEnabled(appState, condition.featureName)
                } else {
                    false
                }
            }
        }
    }

    private fun restoreFromRouterState(
        routerState: RouterState,
        currentState: TState
    ): RouterState? {
        val filteredRouterState = RouterRestoration.applyRestorationStrategy(
            routerState = routerState,
            strategy = restorationStrategy,
            currentState = currentState
        )

        val routeMap = routes.associateBy { it.path }

        fun rehydrate(list: List<RouteInstance>): List<RouteInstance> =
            list.mapNotNull { instance ->
                val decoded = when (instance) {
                    is SerializableRouteInstance -> instance.withDecodedParam(paramRegistry)
                    else -> instance
                }
                routeMap[decoded.path]?.let { route ->
                    createRouteInstance(route, decoded.param)
                }
            }

        val rehydrated = RouterState(
            sceneRoutes = rehydrate(filteredRouterState.sceneRoutes),
            contentRoutes = rehydrate(filteredRouterState.contentRoutes),
            modalRoutes = rehydrate(filteredRouterState.modalRoutes),
            deviceContext = filteredRouterState.deviceContext,
            lastRouteType = filteredRouterState.lastRouteType
        )

        if (restorationStrategy is RestorationStrategy.RestoreWithDefaults<*>) {
            @Suppress("UNCHECKED_CAST")
            val defaults = restorationStrategy.conditionalDefaults as ConditionalDefaultsConfig<TState>
            if (shouldApplyConditionalDefaults(defaults, rehydrated)) {
                val conditional = applyConditionalDefaults(defaults, currentState)
                if (conditional != null) return conditional
            }
        }

        return rehydrated
    }

    private fun shouldApplyConditionalDefaults(
        config: ConditionalDefaultsConfig<TState>,
        restored: RouterState
    ): Boolean {
        return when (config.mode) {
            ConditionalDefaultsMode.OverrideAlways -> true
            ConditionalDefaultsMode.OnlyIfEmpty -> !hasRoutes(restored)
            ConditionalDefaultsMode.OnlyIfInvalid -> {
                if (!hasRoutes(restored)) return true
                val known = routes.map { it.path }.toSet()
                restored.getActiveRoutes().any { it.path !in known }
            }
        }
    }

    private fun hasRoutes(routerState: RouterState): Boolean =
        routerState.sceneRoutes.isNotEmpty() ||
            routerState.contentRoutes.isNotEmpty() ||
            routerState.modalRoutes.isNotEmpty()

    private fun applyConditionalDefaults(
        config: ConditionalDefaultsConfig<TState>,
        currentState: TState
    ): RouterState? {
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
            logger.debug { "No matching conditional defaults or fallback, continuing with restored routes" }
            return null
        }

        logger.info("Applying conditional default route: $routePath (overriding restored routes)")

        val route = routes.find { it.path == routePath }
        if (route == null) {
            logger.warn("Conditional default route not found: $routePath")
            return null
        }

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
