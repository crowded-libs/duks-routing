package duks.routing

import androidx.compose.runtime.Composable
import duks.Action
import duks.StateModel
import duks.createStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Correctness coverage for dual-state sync, auth session loss, device classification, and helpers.
 */
class RouterCorrectnessTest {

    data class TestAppState(
        val isAuthenticated: Boolean = false,
        override val routerState: RouterState = RouterState()
    ) : StateModel, HasRouterState

    data class SetAuth(val authenticated: Boolean) : Action

    private fun reduce(state: TestAppState, action: Action): TestAppState = when (action) {
        is Routing.StateChanged -> state.copy(routerState = action.routerState)
        is SetAuth -> state.copy(isAuthenticated = action.authenticated)
        else -> state
    }

    @Test
    fun `device context updates are published to app HasRouterState`() = runTest {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            routerMiddleware = routing {
                content("/") { EmptyScreen() }
            }
            reduceWith(::reduce)
        }

        store.dispatch(DeviceAction.UpdateScreenSize(400, 800))
        routerMiddleware.state.first { it.deviceContext != null }
        store.state.first { it.routerState.deviceContext != null }

        val middlewareCtx = routerMiddleware.state.value.deviceContext
        val appCtx = store.state.value.routerState.deviceContext
        assertNotNull(middlewareCtx)
        assertNotNull(appCtx)
        assertEquals(middlewareCtx, appCtx)
        assertEquals(400, appCtx.screenWidth)
        assertEquals(800, appCtx.screenHeight)
        assertEquals(DeviceClass.Phone, appCtx.deviceType)
    }

    @Test
    fun `device class uses smallest dimension breakpoints`() {
        assertEquals(DeviceClass.Watch, DeviceClassHeuristics.fromDimensions(300, 320))
        assertEquals(DeviceClass.Phone, DeviceClassHeuristics.fromDimensions(400, 800))
        assertEquals(DeviceClass.Tablet, DeviceClassHeuristics.fromDimensions(800, 1200))
        assertEquals(DeviceClass.Desktop, DeviceClassHeuristics.fromDimensions(1200, 900))
        // Landscape phone still phone (smallest edge wins)
        assertEquals(DeviceClass.Phone, DeviceClassHeuristics.fromDimensions(800, 400))
    }

    @Test
    fun `goBack at root does not set lastRouteType to Back`() = runTest {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            routerMiddleware = routing {
                content("/home") { EmptyScreen() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/home")
        routerMiddleware.state.first {
            it.contentRoutes.size == 1 && it.lastRouteType == RouteType.Content
        }

        store.goBack()
        advanceUntilIdle()

        assertEquals(1, routerMiddleware.state.value.contentRoutes.size)
        assertEquals(RouteType.Content, routerMiddleware.state.value.lastRouteType)
        assertEquals(RouteType.Content, store.state.value.routerState.lastRouteType)
    }

    @Test
    fun `modal requires auth redirects when unauthenticated`() = runTest {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState(isAuthenticated = false)) {
            scope(backgroundScope)
            routerMiddleware = routing(
                authConfig = AuthConfig(
                    authChecker = { it.isAuthenticated },
                    unauthenticatedRoute = "/login"
                )
            ) {
                content("/login") { EmptyScreen() }
                content("/home") { EmptyScreen() }
                modal("/secure-modal", requiresAuth = true) { EmptyScreen() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/home")
        routerMiddleware.state.first { it.contentRoutes.any { r -> r.path == "/home" } }

        store.showModal("/secure-modal")
        routerMiddleware.state.first {
            it.modalRoutes.isEmpty() && it.contentRoutes.any { r -> r.path == "/login" }
        }

        assertTrue(routerMiddleware.state.value.modalRoutes.isEmpty())
        assertEquals("/login", routerMiddleware.state.value.contentRoutes.last().path)
    }

    @Test
    fun `session loss redirects away from protected routes`() = runTest {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState(isAuthenticated = true)) {
            scope(backgroundScope)
            routerMiddleware = routing(
                authConfig = AuthConfig(
                    authChecker = { it.isAuthenticated },
                    unauthenticatedRoute = "/login",
                    revalidateOnSessionLoss = true
                )
            ) {
                content("/login") { EmptyScreen() }
                content("/home") { EmptyScreen() }
                content("/settings", requiresAuth = true) { EmptyScreen() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/settings")
        routerMiddleware.state.first { it.contentRoutes.any { r -> r.path == "/settings" } }

        store.dispatch(SetAuth(false))
        advanceUntilIdle()

        routerMiddleware.state.first {
            it.contentRoutes.size == 1 && it.contentRoutes.single().path == "/login"
        }
        assertEquals("/login", store.state.value.routerState.contentRoutes.single().path)
        assertFalse(store.state.value.isAuthenticated)
    }

    @Test
    fun `primaryRoute prefers content over scene`() {
        val scene = createRouteInstance(
            Route<Nothing>(path = "/tabs", layer = NavigationLayer.Scene, content = { EmptyScreen() })
        )
        val content = createRouteInstance(
            Route<Nothing>(path = "/detail", layer = NavigationLayer.Content, content = { EmptyScreen() })
        )
        val state = RouterState(
            sceneRoutes = listOf(scene),
            contentRoutes = listOf(content)
        )
        assertEquals("/detail", state.primaryRoute()?.path)
        assertTrue(state.canGoBack())
    }

    @Test
    fun `canGoBack is false for single scene root`() {
        val scene = createRouteInstance(
            Route<Nothing>(path = "/home", layer = NavigationLayer.Scene, content = { EmptyScreen() })
        )
        val state = RouterState(sceneRoutes = listOf(scene))
        assertFalse(state.canGoBack())
        assertEquals("/home", state.primaryRoute()?.path)
    }

    @Test
    fun `pathEquals normalizes paths`() {
        val route = createRouteInstance(
            Route<Nothing>(path = "/home", content = { EmptyScreen() })
        )
        assertTrue(route.pathEquals("home"))
        assertTrue(route.pathEquals("/home/"))
        assertFalse(route.pathEquals("/home-settings"))
    }

    @Composable
    private fun EmptyScreen() {}
}
