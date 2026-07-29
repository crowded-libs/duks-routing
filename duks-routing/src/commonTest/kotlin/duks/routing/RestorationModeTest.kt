package duks.routing

import androidx.compose.runtime.Composable
import duks.Action
import duks.RestoreStateAction
import duks.StateModel
import duks.createStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class RestorationModeTest {

    data class TestAppState(
        val isLoggedIn: Boolean = false,
        override val routerState: RouterState = RouterState()
    ) : StateModel, HasRouterState

    private fun reduce(state: TestAppState, action: Action): TestAppState = when (action) {
        is Routing.StateChanged -> state.copy(routerState = action.routerState)
        is RestoreStateAction<*> -> {
            @Suppress("UNCHECKED_CAST")
            val restored = action as RestoreStateAction<TestAppState>
            restored.state
        }
        else -> state
    }

    @Test
    fun `OverrideAlways replaces restored stack when condition matches`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState(isLoggedIn = true)) {
            scope(backgroundScope)
            router = routing {
                content("/home") { Empty() }
                content("/detail") { Empty() }
                content("/login") { Empty() }
                restoration {
                    conditionalDefaults(mode = ConditionalDefaultsMode.OverrideAlways) {
                        `when` { it.isLoggedIn } then "/home"
                        otherwise("/login")
                    }
                }
            }
            reduceWith(::reduce)
        }

        val saved = TestAppState(
            isLoggedIn = true,
            routerState = RouterState(
                contentRoutes = listOf(SerializableRouteInstance("/detail"))
            )
        )
        store.dispatch(RestoreStateAction(saved))
        router.state.first { it.contentRoutes.any { r -> r.path == "/home" } }
        assertEquals("/home", router.state.value.contentRoutes.single().path)
    }

    @Test
    fun `OnlyIfEmpty keeps restored stack when non-empty`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState(isLoggedIn = true)) {
            scope(backgroundScope)
            router = routing {
                content("/home") { Empty() }
                content("/detail") { Empty() }
                content("/login") { Empty() }
                restoration {
                    conditionalDefaults(mode = ConditionalDefaultsMode.OnlyIfEmpty) {
                        `when` { it.isLoggedIn } then "/home"
                        otherwise("/login")
                    }
                }
            }
            reduceWith(::reduce)
        }

        val saved = TestAppState(
            isLoggedIn = true,
            routerState = RouterState(
                contentRoutes = listOf(SerializableRouteInstance("/detail"))
            )
        )
        store.dispatch(RestoreStateAction(saved))
        router.state.first { it.contentRoutes.any { r -> r.path == "/detail" } }
        assertEquals("/detail", router.state.value.contentRoutes.single().path)
    }

    @Test
    fun `OnlyIfEmpty applies default when restored stack empty`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState(isLoggedIn = true)) {
            scope(backgroundScope)
            router = routing {
                content("/home") { Empty() }
                content("/login") { Empty() }
                restoration {
                    conditionalDefaults(mode = ConditionalDefaultsMode.OnlyIfEmpty) {
                        `when` { it.isLoggedIn } then "/home"
                        otherwise("/login")
                    }
                }
            }
            reduceWith(::reduce)
        }

        val saved = TestAppState(
            isLoggedIn = true,
            routerState = RouterState()
        )
        store.dispatch(RestoreStateAction(saved))
        router.state.first { it.contentRoutes.any { r -> r.path == "/home" } }
        assertEquals("/home", router.state.value.contentRoutes.single().path)
    }

    @Test
    fun `OnlyIfInvalid overrides when path is unknown`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState(isLoggedIn = true)) {
            scope(backgroundScope)
            router = routing {
                content("/home") { Empty() }
                content("/login") { Empty() }
                restoration {
                    conditionalDefaults(mode = ConditionalDefaultsMode.OnlyIfInvalid) {
                        `when` { it.isLoggedIn } then "/home"
                        otherwise("/login")
                    }
                }
            }
            reduceWith(::reduce)
        }

        val saved = TestAppState(
            isLoggedIn = true,
            routerState = RouterState(
                contentRoutes = listOf(SerializableRouteInstance("/removed-screen"))
            )
        )
        store.dispatch(RestoreStateAction(saved))
        router.state.first { it.contentRoutes.any { r -> r.path == "/home" } }
        assertEquals("/home", router.state.value.contentRoutes.single().path)
    }

    @Test
    fun `param round trip through restore keeps String id`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState(isLoggedIn = true)) {
            scope(backgroundScope)
            router = routing {
                content("/item/{id}") { Empty() }
                content("/home") { Empty() }
                restoration {
                    // Only apply defaults when empty so restored detail survives
                    conditionalDefaults(mode = ConditionalDefaultsMode.OnlyIfEmpty) {
                        `when` { it.isLoggedIn } then "/home"
                    }
                }
            }
            reduceWith(::reduce)
        }

        val encoded = createSerializableRouteInstance("/item/{id}", param = "xyz")
        val saved = TestAppState(
            isLoggedIn = true,
            routerState = RouterState(contentRoutes = listOf(encoded))
        )
        store.dispatch(RestoreStateAction(saved))
        router.state.first {
            it.contentRoutes.singleOrNull()?.path == "/item/{id}" &&
                it.contentRoutes.singleOrNull()?.param == "xyz"
        }
        assertEquals("xyz", store.state.value.routerState.contentRoutes.single().param)
    }

    @Test
    fun `conditionalDefaults DSL defaults to OverrideAlways`() {
        val builder = RestorationBuilder<TestAppState>()
        builder.conditionalDefaults {
            `when` { it.isLoggedIn } then "/home"
        }
        @Suppress("UNCHECKED_CAST")
        val strategy = builder.build() as RestorationStrategy.RestoreWithDefaults<TestAppState>
        assertEquals(ConditionalDefaultsMode.OverrideAlways, strategy.conditionalDefaults.mode)
        assertTrue(strategy.conditionalDefaults.defaults.isNotEmpty())
    }

    @Composable
    private fun Empty() {}
}
