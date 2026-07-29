package duks.routing

import duks.*
import duks.logging.Logger
import duks.logging.debug
import duks.logging.error
import duks.logging.info
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Policy middleware for routing: auth callbacks, restore rehydration, initial route,
 * session-loss redirects, feature sync, and navigation listeners.
 *
 * Stack mutations live in the auto-registered reducer ([RouterLogic.reduce]).
 * [state] mirrors [HasRouterState.routerState] from the store for convenience/tests.
 */
class RouterMiddleware<TState : HasRouterState>(
    internal val logic: RouterLogic<TState>,
    private val authConfig: AuthConfig<TState>,
    private val navigationListeners: List<NavigationListener> = emptyList(),
    private val reevaluateFeaturesOnAppStateChange: Boolean = true
) : Middleware<TState>, StoreLifecycleAware<TState> {
    private val logger = Logger.default()

    private val mirror = MutableStateFlow(RouterState())
    val state: StateFlow<RouterState> = mirror.asStateFlow()

    private var hasInitialized = false
    private var isRestorationInProgress = false
    private var storeReference: KStore<TState>? = null
    private var restorationCompleted = false
    private var pendingInitialRoute = false
    private var lastKnownAuthenticated: Boolean? = null

    override suspend fun onStoreCreated(store: KStore<TState>) {
        storeReference = store
        lastKnownAuthenticated = authConfig.authChecker(store.state.value)
        mirror.value = store.state.value.routerState

        if (pendingInitialRoute && !hasInitialized) {
            applyInitialRoute(store)
            hasInitialized = true
            pendingInitialRoute = false
        } else if (!isRestorationInProgress && !restorationCompleted) {
            applyInitialRoute(store)
            hasInitialized = true
        }
    }

    override suspend fun onStoreDestroyed() {
        storeReference = null
        hasInitialized = false
        isRestorationInProgress = false
        restorationCompleted = false
        pendingInitialRoute = false
        lastKnownAuthenticated = null
        mirror.value = RouterState()
    }

    override suspend fun onStorageRestorationStarted() {
        isRestorationInProgress = true
    }

    override suspend fun onStorageRestorationCompleted(restored: Boolean) {
        isRestorationInProgress = false
        restorationCompleted = true

        if (!restored && !hasInitialized) {
            if (storeReference != null) {
                applyInitialRoute(storeReference!!)
                hasInitialized = true
            } else {
                pendingInitialRoute = true
            }
        }
    }

    override suspend fun invoke(
        store: KStore<TState>,
        next: suspend (Action) -> Action,
        action: Action
    ): Action {
        val previousRouter = store.state.value.routerState

        // Auth failure side effect (stack rewrite happens in the reducer)
        when (action) {
            is Routing.NavigateTo, is Routing.ShowModal, is Routing.ReplaceContent -> {
                logic.routeBlockedByAuth(action, store.state.value, previousRouter)?.let { route ->
                    authConfig.onAuthFailure?.invoke(store, route)
                }
            }
        }

        val actionToProcess = when (action) {
            is RestoreStateAction<*> -> {
                @Suppress("UNCHECKED_CAST")
                val restoreAction = action as RestoreStateAction<TState>
                val restored = restoreAction.state
                val live = logic.rehydrateFromAppState(restored)
                    ?: logic.buildInitialRouterState(restored)
                if (live != null) {
                    @Suppress("UNCHECKED_CAST")
                    val fixed = restored.withRouterState(live) as TState
                    hasInitialized = true
                    lastKnownAuthenticated = authConfig.authChecker(fixed)
                    logger.info(
                        live.contentRoutes.size,
                        live.modalRoutes.size,
                        live.sceneRoutes.size
                    ) {
                        "Router state restored with {contentCount} content routes, {modalCount} modals, {sceneCount} scenes"
                    }
                    RestoreStateAction(fixed)
                } else {
                    action
                }
            }
            else -> action
        }

        val result = next(actionToProcess)
        mirror.value = store.state.value.routerState

        val currentRouter = store.state.value.routerState
        if (previousRouter != currentRouter) {
            notifyListeners(previousRouter, currentRouter, action)
        }

        // Session loss after domain reducers ran
        if (action !is Routing && action !is DeviceAction && action !is RestoreStateAction<*>) {
            val wasAuthenticated = lastKnownAuthenticated
                ?: authConfig.authChecker(store.state.value)
            val isAuthenticated = authConfig.authChecker(store.state.value)
            lastKnownAuthenticated = isAuthenticated

            if (authConfig.revalidateOnSessionLoss &&
                wasAuthenticated &&
                !isAuthenticated &&
                logic.hasProtectedRoutesActive(store.state.value.routerState)
            ) {
                logger.info(authConfig.unauthenticatedRoute) {
                    "Session loss detected with protected routes active; redirecting to {unauthPath}"
                }
                val before = store.state.value.routerState
                store.dispatchAsync(logic.unauthenticatedNavigateAction())
                mirror.value = store.state.value.routerState
                if (before != store.state.value.routerState) {
                    notifyListeners(before, store.state.value.routerState, action)
                }
            } else if (logic.featuresNeedSync(store.state.value)) {
                val before = store.state.value.routerState
                store.dispatchAsync(Routing.SyncFeatures)
                mirror.value = store.state.value.routerState
                if (before != store.state.value.routerState) {
                    notifyListeners(before, store.state.value.routerState, action)
                }
            }
        }

        return result
    }

    private fun notifyListeners(previous: RouterState, current: RouterState, action: Action) {
        navigationListeners.forEach { listener ->
            try {
                listener.onRouterStateChanged(previous, current, action)
            } catch (e: Exception) {
                logger.error("NavigationListener failed: ${e.message}")
            }
        }
    }

    private suspend fun applyInitialRoute(store: KStore<TState>) {
        if (hasRoutes(store.state.value.routerState)) return
        val initial = logic.findInitialRoute() ?: return
        logger.debug(initial.path) { "Applying initial route: {path}" }
        val before = store.state.value.routerState
        store.dispatchAsync(Routing.NavigateTo(path = initial.path, layer = initial.layer))
        mirror.value = store.state.value.routerState
        hasInitialized = true
        if (before != store.state.value.routerState) {
            notifyListeners(before, store.state.value.routerState, Routing.NavigateTo(initial.path))
        }
    }

    private fun hasRoutes(routerState: RouterState): Boolean =
        routerState.sceneRoutes.isNotEmpty() ||
            routerState.contentRoutes.isNotEmpty() ||
            routerState.modalRoutes.isNotEmpty()
}
