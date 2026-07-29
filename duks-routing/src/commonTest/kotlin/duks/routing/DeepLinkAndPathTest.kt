package duks.routing

import androidx.compose.runtime.Composable
import duks.Action
import duks.StateModel
import duks.createStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class DeepLinkAndPathTest {

    data class TestAppState(
        override val routerState: RouterState = RouterState()
    ) : StateModel, HasRouterState

    private fun reduce(state: TestAppState, action: Action): TestAppState = when (action) {
        is Routing.StateChanged -> state.copy(routerState = action.routerState)
        else -> state
    }

    @Test
    fun `parseDeepLink handles custom scheme host path and query`() {
        val parsed = parseDeepLink("myapp://shop/item/42?ref=home&x=1")
        assertEquals("myapp", parsed.scheme)
        assertEquals("shop", parsed.host)
        assertEquals("/item/42", parsed.path)
        assertEquals(mapOf("ref" to "home", "x" to "1"), parsed.query)
    }

    @Test
    fun `parseDeepLink handles https urls`() {
        val parsed = parseDeepLink("https://example.com/products/1")
        assertEquals("https", parsed.scheme)
        assertEquals("example.com", parsed.host)
        assertEquals("/products/1", parsed.path)
    }

    @Test
    fun `parseDeepLink handles bare paths`() {
        val parsed = parseDeepLink("/settings/account")
        assertNull(parsed.scheme)
        assertNull(parsed.host)
        assertEquals("/settings/account", parsed.path)
    }

    @Test
    fun `matchPath extracts single and multi segment params`() {
        val single = matchPath("/item/{id}", "/item/99")
        assertNotNull(single)
        assertEquals(mapOf("id" to "99"), single.pathParams)
        assertEquals("99", single.inferredParam())

        val multi = matchPath("/a/{x}/b/{y}", "/a/1/b/2")
        assertNotNull(multi)
        assertEquals(mapOf("x" to "1", "y" to "2"), multi.pathParams)
        assertTrue(multi.inferredParam() is Map<*, *>)
    }

    @Test
    fun `matchPath returns null on mismatch`() {
        assertNull(matchPath("/item/{id}", "/other/1"))
        assertNull(matchPath("/item/{id}", "/item/1/extra"))
    }

    @Test
    fun `deep link navigates to path template and supplies String param`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/") { Empty() }
                content("/video/{id}") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.dispatch(Routing.DeepLink("myapp://host/video/123"))
        router.state.first {
            it.contentRoutes.lastOrNull()?.path == "/video/{id}" &&
                it.contentRoutes.lastOrNull()?.param == "123"
        }

        assertEquals("123", router.state.value.contentRoutes.last().param)
        assertEquals("123", store.state.value.routerState.contentRoutes.last().param)
    }

    @Test
    fun `routeTo concrete path matches template route`() = runTest {
        lateinit var router: RouterMiddleware<TestAppState>
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/item/{id}") { Empty() }
            }
            reduceWith(::reduce)
        }

        store.routeTo("/item/abc")
        router.state.first { it.contentRoutes.any { r -> r.param == "abc" } }
        assertEquals("/item/{id}", router.state.value.contentRoutes.single().path)
    }

    @Composable
    private fun Empty() {}
}
