package duks.routing

import androidx.compose.runtime.Composable
import duks.Action
import duks.StateModel
import duks.createStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class NavigationListenerTest {

    data class TestAppState(
        override val routerState: RouterState = RouterState()
    ) : StateModel, HasRouterState {
        override fun withRouterState(routerState: RouterState) = copy(routerState = routerState)
    }

    private fun reduce(state: TestAppState, action: Action): TestAppState = state

    @Test
    fun `navigation listener fires after router state is committed`() = runTest {
        val events = mutableListOf<Triple<String?, String?, String?>>()
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                onNavigation { previous, current, action ->
                    events.add(
                        Triple(
                            previous.primaryRoute()?.path,
                            current.primaryRoute()?.path,
                            action::class.simpleName
                        )
                    )
                }
                content("/a") { Empty() }
                content("/b") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/a")
        router.state.first { it.contentRoutes.any { r -> r.path == "/a" } }
        store.routeTo("/b")
        router.state.first { it.contentRoutes.any { r -> r.path == "/b" } }
        advanceUntilIdle()

        assertTrue(events.size >= 2)
        val last = events.last()
        assertEquals("/a", last.first)
        assertEquals("/b", last.second)
        assertEquals("NavigateTo", last.third)
        assertEquals("/b", store.state.value.routerState.contentRoutes.last().path)
    }

    @Test
    fun `addNavigationListener receives transitions after store create`() = runTest {
        val events = mutableListOf<Triple<String?, String?, String?>>()
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/a") { Empty() }
                content("/b") { Empty() }
            }
            reduceWith(::reduce)
        }

        val listener = NavigationListener { previous, current, action ->
            events.add(
                Triple(
                    previous.primaryRoute()?.path,
                    current.primaryRoute()?.path,
                    action::class.simpleName
                )
            )
        }
        router.addNavigationListener(listener)

        store.routeTo("/a")
        router.state.first { it.contentRoutes.any { r -> r.path == "/a" } }
        store.routeTo("/b")
        router.state.first { it.contentRoutes.any { r -> r.path == "/b" } }
        advanceUntilIdle()

        assertTrue(events.size >= 2, "Expected late-registered listener to receive navigations")
        val last = events.last()
        assertEquals("/a", last.first)
        assertEquals("/b", last.second)
        assertEquals("NavigateTo", last.third)
    }

    @Test
    fun `removeNavigationListener stops further notifications`() = runTest {
        val events = mutableListOf<String?>()
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/a") { Empty() }
                content("/b") { Empty() }
                content("/c") { Empty() }
            }
            reduceWith(::reduce)
        }

        val listener = NavigationListener { _, current, _ ->
            events.add(current.primaryRoute()?.path)
        }
        router.addNavigationListener(listener)

        store.routeTo("/a")
        router.state.first { it.contentRoutes.any { r -> r.path == "/a" } }
        assertTrue(events.contains("/a"))

        router.removeNavigationListener(listener)
        val countAfterRemove = events.size

        store.routeTo("/b")
        router.state.first { it.contentRoutes.any { r -> r.path == "/b" } }
        store.routeTo("/c")
        router.state.first { it.contentRoutes.any { r -> r.path == "/c" } }
        advanceUntilIdle()

        assertEquals(countAfterRemove, events.size, "Listener should not fire after removal")
    }

    @Test
    fun `addNavigationListener ignores duplicate registration`() = runTest {
        var calls = 0
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/home") { Empty() }
            }
            reduceWith(::reduce)
        }

        val listener = NavigationListener { _, _, _ -> calls++ }
        router.addNavigationListener(listener)
        router.addNavigationListener(listener)

        store.routeTo("/home")
        router.state.first { it.contentRoutes.any { r -> r.path == "/home" } }
        advanceUntilIdle()

        assertEquals(1, calls, "Duplicate registration should not double-fire")
    }

    @Composable
    private fun Empty() {}
}
