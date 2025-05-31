package duks.routing

import androidx.compose.runtime.Composable
import duks.KStore
import duks.StateModel
import duks.StoreBuilder


// Store extension methods
fun <TState: StateModel> KStore<TState>.routeTo(
    path: String,
    param: Any? = null,
    layer: NavigationLayer? = null,
    preserveNavigation: Boolean = true,
    clearHistory: Boolean = false
) {
    dispatch(Routing.NavigateTo(path, layer, preserveNavigation, param, clearHistory))
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
class RouterBuilder {
    private val routes = mutableListOf<Route<*>>()
    private var initialRoutePath: String? = null
    
    // Set the initial route for the application
    fun initialRoute(path: String) {
        initialRoutePath = path
    }
    
    // Scene route - replaces entire screen
    fun scene(
        path: String,
        requiresAuth: Boolean = false,
        config: Any? = null,
        whenCondition: RenderCondition? = null,
        content: @Composable () -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Scene,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
        content = content
    )
    
    // Scene route with typed param - replaces entire screen
    inline fun <reified P> scene(
        path: String,
        requiresAuth: Boolean = false,
        config: Any? = null,
        whenCondition: RenderCondition? = null,
        noinline content: @Composable (param: P) -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Scene,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
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
        content: @Composable () -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Content,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
        content = content
    )
    
    // Content route with typed param - updates content area only
    inline fun <reified P> content(
        path: String,
        requiresAuth: Boolean = false,
        config: Any? = null,
        whenCondition: RenderCondition? = null,
        noinline content: @Composable (param: P) -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Content,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
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
        content: @Composable () -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Modal,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
        content = content
    )
    
    // Modal route with typed param
    inline fun <reified P> modal(
        path: String,
        requiresAuth: Boolean = false,
        config: Any? = null,
        whenCondition: RenderCondition? = null,
        noinline content: @Composable (param: P) -> Unit
    ) = route(
        path = path,
        layer = NavigationLayer.Modal,
        requiresAuth = requiresAuth,
        config = config,
        whenCondition = whenCondition,
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
        content: @Composable () -> Unit
    ): Route<T> {
        val conditions = listOfNotNull(whenCondition)
        val route = Route(
            path = path,
            layer = layer,
            requiresAuth = requiresAuth,
            content = content,
            config = config,
            renderConditions = conditions
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
        block: RouteGroupBuilder<T>.() -> Unit
    ) {
        val builder = RouteGroupBuilder<T>(
            this,
            pathPrefix,
            requiresAuth,
            config,
            listOfNotNull(whenCondition)
        )
        builder.apply(block)
    }
    
    fun build(): List<Route<*>> = routes.toList()
    
    fun getInitialRoute(): String? = initialRoutePath
}

// Route group builder
class RouteGroupBuilder<T>(
    val routerBuilder: RouterBuilder,
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
            content = content
        )
    }
    
    fun modal(
        path: String,
        requiresAuth: Boolean = groupRequiresAuth,
        config: T? = null,
        whenCondition: RenderCondition? = null,
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
            content = content
        )
    }
    
    // Typed param versions for group builder
    inline fun <reified P> content(
        path: String,
        requiresAuth: Boolean = groupRequiresAuth,
        config: T? = null,
        whenCondition: RenderCondition? = null,
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
            content = content
        )
    }
}

// Extension function for StoreBuilder
fun <TState: StateModel> StoreBuilder<TState>.routing(
    authConfig: AuthConfig<TState> = AuthConfig({ true }),
    fallbackRoute: String = "/404",
    routes: RouterBuilder.() -> Unit
): RouterMiddleware<TState> {
    val builder = RouterBuilder()
    builder.apply(routes)
    val routeList = builder.build()
    val initialRoute = builder.getInitialRoute()
    
    val routerMiddleware = RouterMiddleware<TState>(
        authConfig = authConfig,
        routes = routeList,
        fallbackRoute = fallbackRoute,
        initialRoute = initialRoute
    )
    
    middleware {
        middleware(routerMiddleware)
        lifecycleAware(routerMiddleware)
    }
    return routerMiddleware
}