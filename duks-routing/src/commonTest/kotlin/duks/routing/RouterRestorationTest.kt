package duks.routing

import duks.Reducer
import duks.RestoreStateAction
import duks.StateModel
import duks.createStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouterRestorationTest {
    
    // Use default Json - contextual serialization handles both primitives and @Serializable types
    private val json = Json
    
    @Test
    fun `RouterState should serialize and deserialize with all route types`() {
        // Create a router state with various routes
        val originalState = RouterState(
            sceneRoutes = listOf(
                createSerializableRouteInstance("/home"),
                createSerializableRouteInstance("/settings")
            ),
            contentRoutes = listOf(
                createSerializableRouteInstance("/dashboard"),
                createSerializableRouteInstance("/profile")
            ),
            modalRoutes = listOf(
                createSerializableRouteInstance("/alert")
            )
        )
        
        // Serialize to JSON
        val jsonString = json.encodeToString(originalState)
        
        // Verify it's serializable and contains paths
        assertTrue(jsonString.contains("home"))
        assertTrue(jsonString.contains("settings"))
        assertTrue(jsonString.contains("dashboard"))
        assertTrue(jsonString.contains("profile"))
        assertTrue(jsonString.contains("alert"))
        
        // Deserialize
        val deserializedState = json.decodeFromString<RouterState>(jsonString)
        
        // Verify deserialized data
        assertEquals(2, deserializedState.sceneRoutes.size)
        assertEquals(2, deserializedState.contentRoutes.size)
        assertEquals(1, deserializedState.modalRoutes.size)
        
        assertEquals("/home", deserializedState.sceneRoutes[0].path)
        assertEquals("/settings", deserializedState.sceneRoutes[1].path)
        assertEquals("/dashboard", deserializedState.contentRoutes[0].path)
        assertEquals("/profile", deserializedState.contentRoutes[1].path)
        assertEquals("/alert", deserializedState.modalRoutes[0].path)
    }
    
    @Test
    fun `RestoreOnly strategy should filter routes by type`() {
        // Create router state
        val routerState = RouterState(
            sceneRoutes = listOf(createSerializableRouteInstance("/home")),
            contentRoutes = listOf(createSerializableRouteInstance("/dashboard")),
            modalRoutes = listOf(createSerializableRouteInstance("/alert"))
        )
        
        // Apply RestoreOnly strategy (only Scene and Content)
        val filteredState = RouterRestoration.applyRestorationStrategy<TestAppState>(
            routerState = routerState,
            strategy = RestorationStrategy.RestoreOnly(setOf(RouteType.Scene, RouteType.Content))
        )
        
        // Verify only specified types are restored
        assertEquals(1, filteredState.sceneRoutes.size)
        assertEquals(1, filteredState.contentRoutes.size)
        assertEquals(0, filteredState.modalRoutes.size) // Modals not restored
    }
    
    @Test
    fun `RestoreSpecific strategy should restore only specified routes`() {
        // Create router state with multiple routes
        val routerState = RouterState(
            sceneRoutes = listOf(createSerializableRouteInstance("/home"), createSerializableRouteInstance("/settings")),
            contentRoutes = listOf(createSerializableRouteInstance("/dashboard"), createSerializableRouteInstance("/profile")),
            modalRoutes = listOf(createSerializableRouteInstance("/alert"), createSerializableRouteInstance("/confirm"))
        )
        
        // Apply RestoreSpecific strategy
        val filteredState = RouterRestoration.applyRestorationStrategy<TestAppState>(
            routerState = routerState,
            strategy = RestorationStrategy.RestoreSpecific(
                scenes = setOf("/home"),
                content = setOf("/dashboard"),
                modals = setOf("/alert", "/confirm")
            )
        )
        
        // Verify only specified routes are restored
        assertEquals(1, filteredState.sceneRoutes.size)
        assertEquals("/home", filteredState.sceneRoutes[0].path)
        
        assertEquals(1, filteredState.contentRoutes.size)
        assertEquals("/dashboard", filteredState.contentRoutes[0].path)
        
        assertEquals(2, filteredState.modalRoutes.size)
        assertEquals("/alert", filteredState.modalRoutes[0].path)
        assertEquals("/confirm", filteredState.modalRoutes[1].path)
    }
    
    @Test
    fun `restoration DSL should build correct restoration strategies`() {
        // Test RestoreAll
        val builderAll = RestorationBuilder<TestAppState>()
        builderAll.restoreAll()
        assertTrue(builderAll.build() is RestorationStrategy.RestoreAll)
        
        // Test RestoreOnly
        val builderOnly = RestorationBuilder<TestAppState>()
        builderOnly.restoreOnly(RouteType.Scene, RouteType.Modal)
        val strategyOnly = builderOnly.build() as RestorationStrategy.RestoreOnly
        assertEquals(setOf(RouteType.Scene, RouteType.Modal), strategyOnly.types)
        
        // Test RestoreSpecific
        val builderSpecific = RestorationBuilder<TestAppState>()
        builderSpecific.restoreSpecific {
            scenes("/home", "/settings")
            content("/dashboard")
            modals("/alert")
        }
        val strategySpecific = builderSpecific.build() as RestorationStrategy.RestoreSpecific
        assertEquals(setOf("/home", "/settings"), strategySpecific.scenes)
        assertEquals(setOf("/dashboard"), strategySpecific.content)
        assertEquals(setOf("/alert"), strategySpecific.modals)
    }
    
    @Test
    fun `conditional defaults DSL should create proper conditions`() {
        val builder = RestorationBuilder<TestAppState>()
        builder.restoreSpecific {
            content("/home", "/dashboard")
        }
        builder.conditionalDefaults {
            `when` { it.isLoggedIn } then "/home"
            `when` { !it.isLoggedIn } then "/landing"
            otherwise("/welcome")
        }

        @Suppress("UNCHECKED_CAST")
        val strategy = builder.build() as RestorationStrategy.RestoreWithDefaults<TestAppState>
        
        // Verify base strategy
        assertTrue(strategy.baseStrategy is RestorationStrategy.RestoreSpecific)
        
        // Verify conditional defaults
        val defaults = strategy.conditionalDefaults
        assertEquals(2, defaults.defaults.size)
        assertEquals("/home", defaults.defaults[0].route)
        assertEquals("/landing", defaults.defaults[1].route)
        assertEquals("/welcome", defaults.fallbackRoute)
    }
    
    @Test
    fun `conditional defaults should navigate based on app state`() = runTest {
        // Create a store with conditional defaults
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            reduceWith { state, action -> testAppReducer(state, action) }
            
            // Use the test coroutine scope
            scope(backgroundScope)
            
            routerMiddleware = routing(
                authConfig = AuthConfig<TestAppState>(
                    authChecker = { it.isLoggedIn },
                    unauthenticatedRoute = "/login"
                )
            ) {
                initialRoute("/") // This should be overridden by conditional defaults
                
                restoration {
                    conditionalDefaults {
                        `when` { it.isLoggedIn } then "/home"
                        `when` { !it.isLoggedIn } then "/login"
                        otherwise("/welcome")
                    }
                }
                
                content("/") { }
                content("/login") { }
                content("/home", requiresAuth = true) { }
                content("/welcome") { }
            }
        }
        
        // Scenario 1: Restore with authenticated state
        val authenticatedState = TestAppState(
            userName = "test@example.com",
            isLoggedIn = true,
            routerState = RouterState() // Empty routes
        )
        
        store.dispatch(RestoreStateAction(authenticatedState))
        
        // Wait for router state to update
        routerMiddleware.state.first { it.contentRoutes.isNotEmpty() }
        
        // Should navigate to /home due to conditional default
        assertEquals("/home", routerMiddleware.state.value.contentRoutes.firstOrNull()?.path)
        
        // Scenario 2: Create new store and restore with unauthenticated state
        lateinit var routerMiddleware2: RouterMiddleware<TestAppState>
        
        val store2 = createStore(TestAppState()) {
            reduceWith { state, action -> testAppReducer(state, action) }
            
            // Use the test coroutine scope
            scope(backgroundScope)
            
            routerMiddleware2 = routing(
                authConfig = AuthConfig<TestAppState>(
                    authChecker = { it.isLoggedIn },
                    unauthenticatedRoute = "/login"
                )
            ) {
                initialRoute("/") // This should be overridden by conditional defaults
                
                restoration {
                    conditionalDefaults {
                        `when` { it.isLoggedIn } then "/home"
                        `when` { !it.isLoggedIn } then "/login"
                        otherwise("/welcome")
                    }
                }
                
                content("/") { }
                content("/login") { }
                content("/home", requiresAuth = true) { }
                content("/welcome") { }
            }
        }
        
        val unauthenticatedState = TestAppState(
            userName = "",
            isLoggedIn = false,
            routerState = RouterState() // Empty routes
        )
        
        store2.dispatch(RestoreStateAction(unauthenticatedState))
        
        // Wait for router state to update
        routerMiddleware2.state.first { it.contentRoutes.isNotEmpty() }
        
        // Should navigate to /login due to conditional default
        assertEquals("/login", routerMiddleware2.state.value.contentRoutes.firstOrNull()?.path)
    }
    
    @Test
    fun `conditional defaults should override restored routes when conditions match`() = runTest {
        // Create a store with conditional defaults
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            reduceWith { state, action -> testAppReducer(state, action) }
            
            // Use the test coroutine scope
            scope(backgroundScope)
            
            routerMiddleware = routing(
                authConfig = AuthConfig<TestAppState>(
                    authChecker = { it.isLoggedIn },
                    unauthenticatedRoute = "/login"
                )
            ) {
                restoration {
                    conditionalDefaults {
                        `when` { !it.isLoggedIn } then "/login"
                        otherwise("/home")
                    }
                }
                
                content("/dashboard") { }
                content("/profile") { }
                content("/login") { }
                content("/home") { }
            }
        }
        
        // Create a state with existing routes but user is not authenticated
        val stateWithRoutes = TestAppState(
            userName = "",
            isLoggedIn = false,
            routerState = RouterState(
                contentRoutes = listOf(
                    createSerializableRouteInstance("/dashboard"),
                    createSerializableRouteInstance("/profile")
                )
            )
        )
        
        store.dispatch(RestoreStateAction(stateWithRoutes))
        
        // Wait for router state to update
        routerMiddleware.state.first { it.contentRoutes.isNotEmpty() }
        
        // Should navigate to /login due to conditional default overriding the restored routes
        assertEquals(1, routerMiddleware.state.value.contentRoutes.size)
        assertEquals("/login", routerMiddleware.state.value.contentRoutes.firstOrNull()?.path)
    }
    
    @Test
    fun `initial route should be applied when no state is restored`() = runTest {
        // Create a store with an initial route
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            reduceWith { state, action -> testAppReducer(state, action) }
            
            // Use the test coroutine scope
            scope(backgroundScope)
            
            routerMiddleware = routing {
                initialRoute("/landing")
                
                scene("/landing") { }
                scene("/home") { }
                scene("/login") { }
            }
        }
        
        // Don't dispatch any RestoreStateAction - router should initialize with initial route
        
        // Wait a bit for initialization
        routerMiddleware.state.first { it.sceneRoutes.isNotEmpty() }
        
        // Should have the initial route
        assertEquals(1, routerMiddleware.state.value.sceneRoutes.size)
        assertEquals("/landing", routerMiddleware.state.value.sceneRoutes.firstOrNull()?.path)
    }
    
    @Test
    fun `router should defer initialization when storage restoration is in progress`() = runTest {
        // Create a store that simulates storage restoration lifecycle
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            reduceWith { state, action -> testAppReducer(state, action) }
            
            // Use the test coroutine scope
            scope(backgroundScope)
            
            routerMiddleware = routing(
                authConfig = AuthConfig<TestAppState>(
                    authChecker = { it.isLoggedIn },
                    unauthenticatedRoute = "/login"
                )
            ) {
                initialRoute("/landing")
                
                restoration {
                    conditionalDefaults {
                        `when` { it.isLoggedIn } then "/home"
                        otherwise("/login")
                    }
                }
                
                content("/landing") { }
                content("/home") { }
                content("/login") { }
            }
        }
        
        // Simulate storage restoration lifecycle
        routerMiddleware.onStorageRestorationStarted()
        
        // Verify no routes have been initialized yet
        assertEquals(0, routerMiddleware.state.value.contentRoutes.size)
        
        // Now dispatch a restored state
        val restoredState = TestAppState(
            userName = "TestUser",
            isLoggedIn = true,
            routerState = RouterState() // Empty router state - conditional defaults should apply
        )
        
        store.dispatch(RestoreStateAction(restoredState))
        
        // Complete storage restoration
        routerMiddleware.onStorageRestorationCompleted(true)
        
        // Wait for router state to update
        routerMiddleware.state.first { it.contentRoutes.isNotEmpty() }
        
        // Should have applied conditional default route (/home) instead of initial route (/landing)
        assertEquals(1, routerMiddleware.state.value.contentRoutes.size)
        assertEquals("/home", routerMiddleware.state.value.contentRoutes.firstOrNull()?.path)
    }
    
    @Test
    fun `full serialization round trip should preserve router state`() = runTest {
        // Create a store and navigate to some routes
        val store = createStore(TestAppState()) {
            reduceWith { state, action -> testAppReducer(state, action) }
            
            // Use the test coroutine scope
            scope(backgroundScope)
            
            routing {
                content("/home") { }
                content("/profile") { }
                modal("/alert") { }
            }
        }
        
        // Navigate to create state
        store.routeTo("/home")
        store.routeTo("/profile")
        store.showModal("/alert")
        
        // Wait for state to be updated by waiting for the expected state
        // The middleware processes actions and then dispatches StateChanged
        // We need to wait for the final state
        val finalState = store.state.first { state ->
            state.routerState.contentRoutes.size == 2 && 
            state.routerState.modalRoutes.size == 1
        }
        
        
        // Serialize to JSON
        val jsonString = json.encodeToString(finalState)
        
        // Deserialize
        val deserializedState = json.decodeFromString<TestAppState>(jsonString)
        
        // Verify serialized router state
        assertEquals(listOf("/home", "/profile"), deserializedState.routerState.contentRoutes.map { it.path })
        assertEquals(listOf("/alert"), deserializedState.routerState.modalRoutes.map { it.path })
        
        // Create new store and restore
        lateinit var routerMiddleware2: RouterMiddleware<TestAppState>
        
        val store2 = createStore(TestAppState()) {
            reduceWith { state, action -> testAppReducer(state, action) }
            
            // Use the test coroutine scope
            scope(backgroundScope)
            
            routerMiddleware2 = routing {
                content("/home") { }
                content("/profile") { }
                modal("/alert") { }
            }
        }
        
        store2.dispatch(RestoreStateAction(deserializedState))
        
        // Verify routes were restored
        routerMiddleware2.state.first { it.contentRoutes.size == 2 }
        
        assertEquals(2, routerMiddleware2.state.value.contentRoutes.size)
        assertEquals("/home", routerMiddleware2.state.value.contentRoutes[0].path)
        assertEquals("/profile", routerMiddleware2.state.value.contentRoutes[1].path)
        assertEquals(1, routerMiddleware2.state.value.modalRoutes.size)
        assertEquals("/alert", routerMiddleware2.state.value.modalRoutes[0].path)
    }
}

// Example app state for integration testing
@Serializable
data class TestAppState(
    val userName: String = "",
    val isLoggedIn: Boolean = false,
    override val routerState: RouterState = RouterState()
) : StateModel, HasRouterState

// Test reducer that updates routing state
val testAppReducer: Reducer<TestAppState> = { state, action ->
    when (action) {
        is Routing.StateChanged -> state.copy(routerState = action.routerState)
        else -> state
    }
}