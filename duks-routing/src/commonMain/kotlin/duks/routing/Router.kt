package duks.routing

import androidx.compose.runtime.Composable
import duks.*
import duks.logging.*
import duks.routing.features.FeatureToggleEvaluator

// Store extension methods
fun <TState: StateModel> KStore<TState>.routeTo(
    path: String,
    param: Any? = null,
    layer: NavigationLayer? = null,
    clearHistory: Boolean = false,
    mode: NavigationMode = NavigationMode.Push
) {
    dispatch(
        Routing.NavigateTo(
            path = path,
            layer = layer,
            param = param,
            clearHistory = clearHistory,
            mode = mode
        )
    )
}

/**
 * Navigate to a scene root suitable for tab-style switches: the scene stack becomes only
 * [path], and content/modal stacks are cleared. Equivalent to
 * `routeTo(path, layer = Scene, mode = ReplaceLayer)`.
 */
fun <TState: StateModel> KStore<TState>.switchScene(path: String, param: Any? = null) {
    routeTo(path, param = param, layer = NavigationLayer.Scene, mode = NavigationMode.ReplaceLayer)
}

fun <TState: StateModel> KStore<TState>.goBack() {
    dispatch(Routing.GoBack)
}

fun <TState: StateModel> KStore<TState>.showModal(path: String, param: Any? = null) {
    dispatch(Routing.ShowModal(path, param))
}

fun <TState: StateModel> KStore<TState>.dismissModal(path: String? = null) {
    dispatch(Routing.DismissModal(path))
}

fun <TState: StateModel> KStore<TState>.popToRoute(path: String) {
    dispatch(Routing.PopToPath(path))
}

// DSL for building routes
class RouterBuilder<TState : HasRouterState> {
    private val logger = Logger.default()
    private val routes = mutableListOf<Route<*>>()
    private var initialRoutePath: String? = null
    private var restorationStrategy: RestorationStrategy = RestorationStrategy.RestoreAll
    private var featureToggleEvaluator: FeatureToggleEvaluator? = null
    private var _paramRegistry: RouteParamRegistry = RouteParamRegistry.Default
    private val navigationListeners = mutableListOf<NavigationListener>()
    private var reevaluateFeaturesOnAppStateChange: Boolean = true

    /** Active param registry used for restoration (and registration helpers). */
    val paramRegistry: RouteParamRegistry
        get() = _paramRegistry
    
    // Set the initial route for the application
    fun initialRoute(path: String) {
        logger.debug(path) { "Setting initial route: {path}" }
        initialRoutePath = path
    }

    /**
     * Use a custom [RouteParamRegistry] for restoring encoded route parameters.
     * Defaults to [RouteParamRegistry.Default] (shared process-wide registry).
     */
    fun paramSerializers(registry: RouteParamRegistry) {
        this._paramRegistry = registry
        logger.debug { "Configured route param registry" }
    }

    /**
     * Register a serializable param type on the active registry (usually [RouteParamRegistry.Default]).
     */
    inline fun <reified T : Any> registerParamSerializer(
        typeName: String = kotlinx.serialization.serializer<T>().descriptor.serialName
    ) {
        paramRegistry.register<T>(typeName)
    }
    
    // Scene route - replaces entire screen
    fun scene(
        path: String,
        requiresAuth: Boolean = false,
        config: Any? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        content: @Composable () -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Scene,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
        requiredFeature = requiredFeature,
        content = content
    )
    
    // Scene route with typed param - replaces entire screen
    inline fun <reified P> scene(
        path: String,
        requiresAuth: Boolean = false,
        config: Any? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        noinline content: @Composable (param: P) -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Scene,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
        requiredFeature = requiredFeature,
        content = {
            val param: P = routeParam()
            content(param)
        }
    )
    
    // Content route - updates content area only
    fun content(
        path: String,
        requiresAuth: Boolean = false,
        config: Any? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        content: @Composable () -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Content,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
        requiredFeature = requiredFeature,
        content = content
    )
    
    // Content route with typed param - updates content area only
    inline fun <reified P> content(
        path: String,
        requiresAuth: Boolean = false,
        config: Any? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        noinline content: @Composable (param: P) -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Content,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
        requiredFeature = requiredFeature,
        content = {
            val param: P = routeParam()
            content(param)
        }
    )
    
    // Modal route
    fun modal(
        path: String,
        requiresAuth: Boolean = false,
        config: Any? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        content: @Composable () -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Modal,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
        requiredFeature = requiredFeature,
        content = content
    )
    
    // Modal route with typed param
    inline fun <reified P> modal(
        path: String,
        requiresAuth: Boolean = false,
        config: Any? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        noinline content: @Composable (param: P) -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Modal,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
        requiredFeature = requiredFeature,
        content = {
            val param: P = routeParam()
            content(param)
        }
    )
    
    
    // Generic route builder
    fun <T> route(
        path: String,
        layer: NavigationLayer = NavigationLayer.Content,
        requiresAuth: Boolean = false,
        config: T? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        content: @Composable () -> Unit
    ): Route<T> {
        logger.debug(path, layer, if (requiresAuth) " (requires auth)" else "") { "Registering route: {path} on layer {layer}{authStatus}" }
        
        val conditions = listOfNotNull(whenCondition)
        val route = Route(
            path = path,
            layer = layer,
            requiresAuth = requiresAuth,
            content = content,
            config = config,
            renderConditions = conditions,
            requiredFeature = requiredFeature
        )
        routes.add(route)
        return route
    }
    
    // Group routes with shared configuration
    fun <T> group(
        pathPrefix: String? = null,
        requiresAuth: Boolean = false,
        config: T? = null,
        whenCondition: RenderCondition? = null,
        block: RouteGroupBuilder<T, TState>.() -> Unit
    ) {
        logger.debug(pathPrefix) { if (pathPrefix != null) "Creating route group with prefix: {prefix}" else "Creating route group" }
        
        val builder = RouteGroupBuilder<T, TState>(
            this,
            pathPrefix,
            requiresAuth,
            config,
            listOfNotNull(whenCondition)
        )
        builder.apply(block)
    }
    
    /**
     * Configure restoration strategy for the router.
     */
    fun restoration(block: RestorationBuilder<TState>.() -> Unit) {
        val builder = RestorationBuilder<TState>()
        builder.block()
        restorationStrategy = builder.build()
        logger.debug { "Configured restoration strategy: $restorationStrategy" }
    }
    
    /**
     * Configure feature toggle evaluator for the router.
     *
     * @param reevaluateOnAppStateChange When true (default), any non-routing action that changes
     *   app state will re-run the evaluator and update [RouterState.enabledFeatures] if needed.
     */
    fun featureToggles(
        evaluator: FeatureToggleEvaluator,
        reevaluateOnAppStateChange: Boolean = true
    ) {
        this.featureToggleEvaluator = evaluator
        this.reevaluateFeaturesOnAppStateChange = reevaluateOnAppStateChange
        logger.debug { "Configured feature toggle evaluator" }
    }

    /**
     * Observe committed router transitions after the store has updated [HasRouterState.routerState].
     * Useful for analytics / screen tracking.
     */
    fun onNavigation(listener: NavigationListener) {
        navigationListeners.add(listener)
        logger.debug { "Registered navigation listener" }
    }
    
    fun build(): List<Route<*>> = routes.toList()
    
    fun getInitialRoute(): String? = initialRoutePath
    
    fun getRestorationStrategy(): RestorationStrategy = restorationStrategy
    
    fun getFeatureToggleEvaluator(): FeatureToggleEvaluator? = featureToggleEvaluator

    internal fun resolveParamRegistry(): RouteParamRegistry = _paramRegistry

    internal fun resolveNavigationListeners(): List<NavigationListener> = navigationListeners.toList()

    internal fun resolveReevaluateFeaturesOnAppStateChange(): Boolean = reevaluateFeaturesOnAppStateChange
}

// Route group builder
class RouteGroupBuilder<T, TState : HasRouterState>(
    val routerBuilder: RouterBuilder<TState>,
    val pathPrefix: String?,
    val groupRequiresAuth: Boolean,
    val groupConfig: T?,
    val groupConditions: List<RenderCondition>
) {
    fun content(
        path: String,
        requiresAuth: Boolean = groupRequiresAuth,
        config: T? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        content: @Composable () -> Unit
    ) {
        val fullPath = if (pathPrefix != null) "$pathPrefix/$path" else path
        val conditions = groupConditions + listOfNotNull(whenCondition)
        val effectiveConfig = config ?: groupConfig
        
        routerBuilder.route(
            path = fullPath,
            layer = NavigationLayer.Content,
            requiresAuth = requiresAuth,
            config = effectiveConfig,
            whenCondition = conditions.reduceOrNull { acc, condition -> acc and condition },
            requiredFeature = requiredFeature,
            content = content
        )
    }
    
    fun modal(
        path: String,
        requiresAuth: Boolean = groupRequiresAuth,
        config: T? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        content: @Composable () -> Unit
    ) {
        val fullPath = if (pathPrefix != null) "$pathPrefix/$path" else path
        val conditions = groupConditions + listOfNotNull(whenCondition)
        val effectiveConfig = config ?: groupConfig
        
        routerBuilder.route(
            path = fullPath,
            layer = NavigationLayer.Modal,
            requiresAuth = requiresAuth,
            config = effectiveConfig,
            whenCondition = conditions.reduceOrNull { acc, condition -> acc and condition },
            requiredFeature = requiredFeature,
            content = content
        )
    }
    
    // Typed param versions for group builder
    inline fun <reified P> content(
        path: String,
        requiresAuth: Boolean = groupRequiresAuth,
        config: T? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        noinline content: @Composable (param: P) -> Unit
    ) {
        val fullPath = if (pathPrefix != null) "$pathPrefix/$path" else path
        val conditions = groupConditions + listOfNotNull(whenCondition)
        val effectiveConfig = config ?: groupConfig
        
        routerBuilder.route(
            path = fullPath,
            layer = NavigationLayer.Content,
            requiresAuth = requiresAuth,
            config = effectiveConfig,
            whenCondition = conditions.reduceOrNull { acc, condition -> acc and condition },
            requiredFeature = requiredFeature,
            content = {
                val param: P = routeParam()
                content(param)
            }
        )
    }
    
    inline fun <reified P> modal(
        path: String,
        requiresAuth: Boolean = groupRequiresAuth,
        config: T? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        noinline content: @Composable (param: P) -> Unit
    ) {
        val fullPath = if (pathPrefix != null) "$pathPrefix/$path" else path
        val conditions = groupConditions + listOfNotNull(whenCondition)
        val effectiveConfig = config ?: groupConfig
        
        routerBuilder.route(
            path = fullPath,
            layer = NavigationLayer.Modal,
            requiresAuth = requiresAuth,
            config = effectiveConfig,
            whenCondition = conditions.reduceOrNull { acc, condition -> acc and condition },
            requiredFeature = requiredFeature,
            content = {
                val param: P = routeParam()
                content(param)
            }
        )
    }
    
    inline fun <reified P> scene(
        path: String,
        requiresAuth: Boolean = groupRequiresAuth,
        config: T? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        noinline content: @Composable (param: P) -> Unit
    ) {
        val fullPath = if (pathPrefix != null) "$pathPrefix/$path" else path
        val conditions = groupConditions + listOfNotNull(whenCondition)
        val effectiveConfig = config ?: groupConfig
        
        routerBuilder.route(
            path = fullPath,
            layer = NavigationLayer.Scene,
            requiresAuth = requiresAuth,
            config = effectiveConfig,
            whenCondition = conditions.reduceOrNull { acc, condition -> acc and condition },
            requiredFeature = requiredFeature,
            content = {
                val param: P = routeParam()
                content(param)
            }
        )
    }
    
    fun scene(
        path: String,
        requiresAuth: Boolean = groupRequiresAuth,
        config: T? = null,
        whenCondition: RenderCondition? = null,
        requiredFeature: String? = null,
        content: @Composable () -> Unit
    ) {
        val fullPath = if (pathPrefix != null) "$pathPrefix/$path" else path
        val conditions = groupConditions + listOfNotNull(whenCondition)
        val effectiveConfig = config ?: groupConfig
        
        routerBuilder.route(
            path = fullPath,
            layer = NavigationLayer.Scene,
            requiresAuth = requiresAuth,
            config = effectiveConfig,
            whenCondition = conditions.reduceOrNull { acc, condition -> acc and condition },
            requiredFeature = requiredFeature,
            content = content
        )
    }
}

/**
 * Register routing middleware **and** an automatic reducer that owns [HasRouterState.routerState].
 *
 * Apps implement [HasRouterState.withRouterState]; they do not need to handle routing actions
 * in their own reducer.
 */
fun <TState : HasRouterState> StoreBuilder<TState>.routing(
    authConfig: AuthConfig<TState> = AuthConfig({ true }),
    fallbackRoute: String = "/404",
    routes: RouterBuilder<TState>.() -> Unit
): RouterMiddleware<TState> {
    val builder = RouterBuilder<TState>()
    builder.apply(routes)
    val routeList = builder.build()
    val logic = RouterLogic(
        authConfig = authConfig,
        routes = routeList,
        fallbackRoute = fallbackRoute,
        initialRoutePath = builder.getInitialRoute(),
        restorationStrategy = builder.getRestorationStrategy(),
        featureToggleEvaluator = builder.getFeatureToggleEvaluator(),
        paramRegistry = builder.resolveParamRegistry(),
        reevaluateFeaturesOnAppStateChange = builder.resolveReevaluateFeaturesOnAppStateChange()
    )

    val routerMiddleware = RouterMiddleware(
        logic = logic,
        authConfig = authConfig,
        navigationListeners = builder.resolveNavigationListeners(),
        reevaluateFeaturesOnAppStateChange = builder.resolveReevaluateFeaturesOnAppStateChange()
    )

    // Auto-wire pure router reducer (runs with other reduceWith registrations)
    reduceWith { state, action -> logic.reduce(state, action) }

    middleware {
        middleware(routerMiddleware)
        lifecycleAware(routerMiddleware)
    }
    return routerMiddleware
}