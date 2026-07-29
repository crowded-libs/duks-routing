package duks.routing

import androidx.compose.runtime.Composable
import duks.Action
import duks.StateModel
import duks.createStore
import duks.routing.features.FeatureToggleEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class RouterPhase4Test {

    data class TestAppState(
        val features: Set<String> = emptySet(),
        override val routerState: RouterState = RouterState()
    ) : StateModel, HasRouterState {
        override fun withRouterState(routerState: RouterState) = copy(routerState = routerState)
    }

    data class SetFeatures(val features: Set<String>) : Action

    private fun reduce(state: TestAppState, action: Action): TestAppState {
        return when (action) {
            is SetFeatures -> state.copy(features = action.features)
            else -> state
        }
    }

    private val featureEvaluator = object : FeatureToggleEvaluator {
        override fun <TState : StateModel> isFeatureEnabled(state: TState, featureName: String): Boolean {
            return (state as TestAppState).features.contains(featureName)
        }
    }

    @Test
    fun `withRouterState replaces router slice`() {
        val state = TestAppState()
        val next = RouterState(contentRoutes = listOf(SerializableRouteInstance("/home")))
        val updated = state.withRouterState(next)
        assertEquals("/home", updated.routerState.contentRoutes.single().path)
        assertTrue(state.routerState.contentRoutes.isEmpty())
    }

    @Test
    fun `enabledFeatures refresh when app feature flags change`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                featureToggles(featureEvaluator)
                content("/", requiredFeature = "beta") { Empty() }
                content("/home") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/home")
        router.state.first { it.contentRoutes.any { r -> r.path == "/home" } }
        assertEquals(emptySet(), router.state.value.enabledFeatures)

        store.dispatch(SetFeatures(setOf("beta")))
        router.state.first { it.enabledFeatures.contains("beta") }
        assertEquals(setOf("beta"), store.state.value.routerState.enabledFeatures)
    }

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
                    // App routerState should already reflect commit
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
    fun `feature re-eval can be disabled`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                featureToggles(featureEvaluator, reevaluateOnAppStateChange = false)
                content("/home") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/home")
        router.state.first { it.contentRoutes.isNotEmpty() }
        store.dispatch(SetFeatures(setOf("beta")))
        advanceUntilIdle()

        assertEquals(emptySet(), router.state.value.enabledFeatures)
    }

    @Composable
    private fun Empty() {}
}
