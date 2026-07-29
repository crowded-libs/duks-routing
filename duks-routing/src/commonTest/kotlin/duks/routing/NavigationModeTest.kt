package duks.routing

import androidx.compose.runtime.Composable
import duks.Action
import duks.StateModel
import duks.createStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class NavigationModeTest {

    data class TestAppState(
        override val routerState: RouterState = RouterState()
    ) : StateModel, HasRouterState

    private fun reduce(state: TestAppState, action: Action): TestAppState = when (action) {
        is Routing.StateChanged -> state.copy(routerState = action.routerState)
        else -> state
    }

    @Test
    fun `switchScene replaces scene stack and clears content and modals`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                scene("/home") { Empty() }
                scene("/profile") { Empty() }
                content("/detail") { Empty() }
                modal("/sheet") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/home", layer = NavigationLayer.Scene)
        router.state.first { it.sceneRoutes.any { r -> r.path == "/home" } }
        store.routeTo("/detail", layer = NavigationLayer.Content)
        router.state.first { it.contentRoutes.isNotEmpty() }
        store.showModal("/sheet")
        router.state.first { it.modalRoutes.isNotEmpty() }

        store.switchScene("/profile")
        router.state.first {
            it.sceneRoutes.size == 1 &&
                it.sceneRoutes.single().path == "/profile" &&
                it.contentRoutes.isEmpty() &&
                it.modalRoutes.isEmpty()
        }

        assertEquals(listOf("/profile"), router.state.value.sceneRoutes.map { it.path })
        assertEquals(0, router.state.value.contentRoutes.size)
        assertEquals(0, router.state.value.modalRoutes.size)
    }

    @Test
    fun `switchScene does not grow scene stack across tab switches`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                scene("/home") { Empty() }
                scene("/activity") { Empty() }
                scene("/profile") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.switchScene("/home")
        router.state.first { it.sceneRoutes.singleOrNull()?.path == "/home" }
        store.switchScene("/activity")
        router.state.first { it.sceneRoutes.singleOrNull()?.path == "/activity" }
        store.switchScene("/profile")
        router.state.first { it.sceneRoutes.singleOrNull()?.path == "/profile" }
        store.switchScene("/home")
        router.state.first { it.sceneRoutes.singleOrNull()?.path == "/home" }

        assertEquals(1, router.state.value.sceneRoutes.size)
        assertEquals("/home", router.state.value.sceneRoutes.single().path)
    }

    @Test
    fun `SingleTop on content replaces top when path matches`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/list") { Empty() }
                content("/item") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/list")
        router.state.first { it.contentRoutes.any { r -> r.path == "/list" } }
        store.routeTo("/item", param = "a")
        router.state.first { it.contentRoutes.last().path == "/item" }

        store.routeTo("/item", param = "b", mode = NavigationMode.SingleTop)
        router.state.first {
            it.contentRoutes.size == 2 &&
                it.contentRoutes.last().path == "/item" &&
                it.contentRoutes.last().param == "b"
        }

        assertEquals(listOf("/list", "/item"), router.state.value.contentRoutes.map { it.path })
        assertEquals("b", router.state.value.contentRoutes.last().param)
    }

    @Test
    fun `SingleTop on content pushes when path differs`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/list") { Empty() }
                content("/item") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/list")
        router.state.first { it.contentRoutes.size == 1 }
        store.routeTo("/item", mode = NavigationMode.SingleTop)
        router.state.first { it.contentRoutes.size == 2 }

        assertEquals(listOf("/list", "/item"), router.state.value.contentRoutes.map { it.path })
    }

    @Test
    fun `ReplaceLayer on content keeps scenes and clears modals`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                scene("/tabs") { Empty() }
                content("/a") { Empty() }
                content("/b") { Empty() }
                modal("/sheet") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.switchScene("/tabs")
        router.state.first { it.sceneRoutes.singleOrNull()?.path == "/tabs" }
        store.routeTo("/a", layer = NavigationLayer.Content)
        store.routeTo("/b", layer = NavigationLayer.Content)
        store.showModal("/sheet")
        router.state.first { it.modalRoutes.isNotEmpty() && it.contentRoutes.size == 2 }

        store.routeTo("/a", layer = NavigationLayer.Content, mode = NavigationMode.ReplaceLayer)
        router.state.first {
            it.contentRoutes.size == 1 &&
                it.contentRoutes.single().path == "/a" &&
                it.modalRoutes.isEmpty() &&
                it.sceneRoutes.single().path == "/tabs"
        }
    }

    @Test
    fun `clearHistory still maps to ClearHistory mode`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                scene("/a") { Empty() }
                content("/b") { Empty() }
                content("/login") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.switchScene("/a")
        store.routeTo("/b", layer = NavigationLayer.Content)
        router.state.first { it.contentRoutes.isNotEmpty() }

        store.routeTo("/login", clearHistory = true)
        router.state.first {
            it.contentRoutes.size == 1 &&
                it.contentRoutes.single().path == "/login" &&
                it.sceneRoutes.isEmpty()
        }
    }

    @Test
    fun `popToRoute finds path in scene stack and clears overlays`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                scene("/home") { Empty() }
                scene("/settings") { Empty() }
                content("/detail") { Empty() }
                modal("/sheet") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/home", layer = NavigationLayer.Scene)
        store.routeTo("/settings", layer = NavigationLayer.Scene)
        router.state.first { it.sceneRoutes.size == 2 }
        store.routeTo("/detail", layer = NavigationLayer.Content)
        store.showModal("/sheet")
        router.state.first { it.modalRoutes.isNotEmpty() }

        store.popToRoute("/home")
        router.state.first {
            it.sceneRoutes.size == 1 &&
                it.sceneRoutes.single().path == "/home" &&
                it.contentRoutes.isEmpty() &&
                it.modalRoutes.isEmpty()
        }
    }

    @Test
    fun `popToRoute finds path in content and clears modals only`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                scene("/tabs") { Empty() }
                content("/list") { Empty() }
                content("/detail") { Empty() }
                modal("/sheet") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.switchScene("/tabs")
        store.routeTo("/list", layer = NavigationLayer.Content)
        store.routeTo("/detail", layer = NavigationLayer.Content)
        store.showModal("/sheet")
        router.state.first { it.contentRoutes.size == 2 && it.modalRoutes.isNotEmpty() }

        store.popToRoute("/list")
        router.state.first {
            it.contentRoutes.map { r -> r.path } == listOf("/list") &&
                it.modalRoutes.isEmpty() &&
                it.sceneRoutes.single().path == "/tabs"
        }
    }

    @Composable
    private fun Empty() {}
}
