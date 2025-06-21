package duks.routing.features

import androidx.compose.runtime.Composable
import duks.Action
import duks.StateModel
import duks.createStore
import duks.routing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class FeatureToggleRoutingTest {
    
    data class TestAppState(
        val isAuthenticated: Boolean = false,
        val userRole: String = "user",
        val enabledFeatures: Set<String> = emptySet()
    ) : StateModel
    
    data class UpdateFeaturesAction(val features: Set<String>) : Action
    data class SetRoleAction(val role: String) : Action
    
    class TestFeatureEvaluator : FeatureToggleEvaluator {
        override fun <TState : StateModel> isFeatureEnabled(state: TState, featureName: String): Boolean {
            return when (state) {
                is TestAppState -> state.enabledFeatures.contains(featureName)
                else -> false
            }
        }
    }
    
    @Composable
    fun TestScreen(name: String = "Test") = Unit
    
    @Test
    fun `routes with requiredFeature property are filtered correctly`() = runTest(timeout = 5.seconds) {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            
            routerMiddleware = routing {
                featureToggles(TestFeatureEvaluator())
                
                content("/home") { TestScreen("Home") }
                content("/beta", requiredFeature = "beta-access") { TestScreen("Beta") }
                content("/experimental", requiredFeature = "experimental-features") { TestScreen("Experimental") }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is UpdateFeaturesAction -> state.copy(enabledFeatures = action.features)
                    else -> state
                }
            }
        }
        
        store.routeTo("/home")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/home" }
        
        store.routeTo("/beta")
        val noBetaState = routerMiddleware.state.first()
        assertEquals("/home", noBetaState.getCurrentContentRoute()?.path)
        
        store.dispatch(UpdateFeaturesAction(setOf("beta-access")))
        store.state.first { it.enabledFeatures.contains("beta-access") }
        
        store.routeTo("/beta")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/beta" }
        
        store.routeTo("/experimental")
        val noExperimentalState = routerMiddleware.state.first()
        assertEquals("/beta", noExperimentalState.getCurrentContentRoute()?.path)
        
        store.dispatch(UpdateFeaturesAction(setOf("beta-access", "experimental-features")))
        store.state.first { it.enabledFeatures.contains("experimental-features") }
        
        store.routeTo("/experimental")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/experimental" }
    }
    
    @Test
    fun `routes with RenderCondition FeatureEnabled are evaluated correctly`() = runTest(timeout = 5.seconds) {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            
            routerMiddleware = routing {
                featureToggles(TestFeatureEvaluator())
                
                content("/") { TestScreen("Home") }
                
                content(
                    "/feature-only",
                    whenCondition = RenderCondition.FeatureEnabled("special-features")
                ) { TestScreen("Feature Only") }
                
                content(
                    "/beta",
                    whenCondition = RenderCondition.FeatureEnabled("beta-features")
                ) { TestScreen("Beta") }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is UpdateFeaturesAction -> state.copy(enabledFeatures = action.features)
                    else -> state
                }
            }
        }
        
        store.routeTo("/")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/" }
        
        // Try to navigate to feature-gated route without the feature
        store.routeTo("/feature-only")
        // Should stay on home since feature is not enabled
        assertEquals("/", routerMiddleware.state.value.getCurrentContentRoute()?.path)
        
        // Enable the feature
        store.dispatch(UpdateFeaturesAction(setOf("special-features")))
        store.state.first { it.enabledFeatures.contains("special-features") }
        
        // Now navigation should work
        store.routeTo("/feature-only")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/feature-only" }
        
        // Try beta route without feature
        store.routeTo("/beta")
        assertEquals("/feature-only", routerMiddleware.state.value.getCurrentContentRoute()?.path)
        
        // Enable beta feature  
        store.dispatch(UpdateFeaturesAction(setOf("special-features", "beta-features")))
        store.state.first { it.enabledFeatures.contains("beta-features") }
        
        // Now beta route should work
        store.routeTo("/beta")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/beta" }
    }
    
    @Test
    fun `composite render conditions with features work with AND operator`() = runTest(timeout = 5.seconds) {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState(isAuthenticated = true)) {
            scope(backgroundScope)
            
            routerMiddleware = routing(
                authConfig = AuthConfig<TestAppState>({ it.isAuthenticated })
            ) {
                featureToggles(TestFeatureEvaluator())
                
                content("/") { TestScreen("Home") }
                
                content(
                    "/premium-tablet",
                    whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Tablet)) and 
                        RenderCondition.FeatureEnabled("premium-features")
                ) { TestScreen("Premium Tablet") }
                
                content(
                    "/auth-and-beta",
                    requiresAuth = true,
                    whenCondition = RenderCondition.FeatureEnabled("beta-access")
                ) { TestScreen("Auth and Beta") }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is UpdateFeaturesAction -> state.copy(enabledFeatures = action.features)
                    else -> state
                }
            }
        }
        
        val tabletContext = DeviceContext(
            screenWidth = 768,
            screenHeight = 1024,
            orientation = ScreenOrientation.Portrait,
            deviceType = DeviceClass.Tablet
        )
        store.dispatch(DeviceAction.UpdateDeviceContext(tabletContext))
        
        store.routeTo("/")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/" }
        
        store.routeTo("/premium-tablet")
        assertEquals("/", routerMiddleware.state.value.getCurrentContentRoute()?.path)
        
        store.dispatch(UpdateFeaturesAction(setOf("premium-features")))
        store.state.first { it.enabledFeatures.contains("premium-features") }
        
        store.routeTo("/premium-tablet")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/premium-tablet" }
        
        store.routeTo("/auth-and-beta")
        assertEquals("/premium-tablet", routerMiddleware.state.value.getCurrentContentRoute()?.path)
        
        store.dispatch(UpdateFeaturesAction(setOf("premium-features", "beta-access")))
        store.state.first { it.enabledFeatures.contains("beta-access") }
        
        store.routeTo("/auth-and-beta")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/auth-and-beta" }
    }
    
    @Test
    fun `composite render conditions with features work with OR operator`() = runTest(timeout = 5.seconds) {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            
            routerMiddleware = routing {
                featureToggles(TestFeatureEvaluator())
                
                content("/") { TestScreen("Home") }
                
                content(
                    "/mobile-or-beta",
                    whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Phone)) or 
                        RenderCondition.FeatureEnabled("beta-access")
                ) { TestScreen("Mobile or Beta") }
                
                content(
                    "/any-feature",
                    whenCondition = RenderCondition.FeatureEnabled("feature-a") or
                        RenderCondition.FeatureEnabled("feature-b") or
                        RenderCondition.FeatureEnabled("feature-c")
                ) { TestScreen("Any Feature") }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is UpdateFeaturesAction -> state.copy(enabledFeatures = action.features)
                    else -> state
                }
            }
        }
        
        val desktopContext = DeviceContext(
            screenWidth = 1920,
            screenHeight = 1080,
            orientation = ScreenOrientation.Landscape,
            deviceType = DeviceClass.Desktop
        )
        store.dispatch(DeviceAction.UpdateDeviceContext(desktopContext))
        
        // Initialize router with home route
        store.routeTo("/")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/" }
        
        store.routeTo("/mobile-or-beta")
        assertEquals("/", routerMiddleware.state.value.getCurrentContentRoute()?.path)
        
        store.dispatch(UpdateFeaturesAction(setOf("beta-access")))
        store.state.first { it.enabledFeatures.contains("beta-access") }
        
        store.routeTo("/mobile-or-beta")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/mobile-or-beta" }
        
        store.routeTo("/any-feature")
        assertEquals("/mobile-or-beta", routerMiddleware.state.value.getCurrentContentRoute()?.path)
        
        store.dispatch(UpdateFeaturesAction(setOf("beta-access", "feature-b")))
        store.state.first { it.enabledFeatures.contains("feature-b") }
        
        store.routeTo("/any-feature")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/any-feature" }
    }
    
    @Test
    fun `feature toggle evaluator is optional and features are disabled without it`() = runTest(timeout = 5.seconds) {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState(enabledFeatures = setOf("all-features"))) {
            scope(backgroundScope)
            
            routerMiddleware = routing {
                content("/") { TestScreen("Home") }
                content("/feature", requiredFeature = "all-features") { TestScreen("Feature") }
                content(
                    "/condition",
                    whenCondition = RenderCondition.FeatureEnabled("all-features")
                ) { TestScreen("Condition") }
            }
        }
        
        store.routeTo("/")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/" }
        
        store.routeTo("/feature")
        assertEquals("/", routerMiddleware.state.value.getCurrentContentRoute()?.path)
        
        store.routeTo("/condition")
        assertEquals("/", routerMiddleware.state.value.getCurrentContentRoute()?.path)
    }
    
    @Test
    fun `router middleware updates enabled features based on app state changes`() = runTest(timeout = 5.seconds) {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            
            routerMiddleware = routing {
                featureToggles(TestFeatureEvaluator())
                content("/") { TestScreen("Home") }
                content("/feature1-route", requiredFeature = "feature1") { TestScreen("Feature1") }
                content("/feature2-route", requiredFeature = "feature2") { TestScreen("Feature2") }
                content("/feature3-route", requiredFeature = "feature3") { TestScreen("Feature3") }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is UpdateFeaturesAction -> state.copy(enabledFeatures = action.features)
                    else -> state
                }
            }
        }
        
        // Initial state should have no enabled features
        store.routeTo("/")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/" }
        assertEquals(emptySet(), routerMiddleware.state.value.enabledFeatures)
        
        // Update app state with features
        store.dispatch(UpdateFeaturesAction(setOf("feature1", "feature2")))
        store.state.first { it.enabledFeatures == setOf("feature1", "feature2") }
        
        // Trigger router to re-evaluate features by navigating
        store.routeTo("/")
        val updatedState = routerMiddleware.state.first { 
            it.enabledFeatures == setOf("feature1", "feature2") 
        }
        assertEquals(setOf("feature1", "feature2"), updatedState.enabledFeatures)
        
        // Replace features
        store.dispatch(UpdateFeaturesAction(setOf("feature3")))
        store.state.first { it.enabledFeatures == setOf("feature3") }
        
        // Trigger router to re-evaluate features by navigating
        store.routeTo("/")
        val replacedState = routerMiddleware.state.first {
            it.enabledFeatures == setOf("feature3")
        }
        assertEquals(setOf("feature3"), replacedState.enabledFeatures)
    }
    
    @Test
    fun `complex nested conditions with features evaluate correctly`() = runTest(timeout = 5.seconds) {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            
            routerMiddleware = routing {
                featureToggles(TestFeatureEvaluator())
                
                content("/") { TestScreen("Home") }
                
                content(
                    "/complex",
                    whenCondition = (RenderCondition.FeatureEnabled("feature-a") and RenderCondition.FeatureEnabled("feature-b")) or
                        (RenderCondition.DeviceType(setOf(DeviceClass.Desktop)) and RenderCondition.FeatureEnabled("desktop-override"))
                ) { TestScreen("Complex") }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is UpdateFeaturesAction -> state.copy(enabledFeatures = action.features)
                    else -> state
                }
            }
        }
        
        val desktopContext = DeviceContext(
            screenWidth = 1920,
            screenHeight = 1080,
            orientation = ScreenOrientation.Landscape,
            deviceType = DeviceClass.Desktop
        )
        store.dispatch(DeviceAction.UpdateDeviceContext(desktopContext))
        
        // Initialize router with home route
        store.routeTo("/")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/" }
        
        store.routeTo("/complex")
        assertEquals("/", routerMiddleware.state.value.getCurrentContentRoute()?.path)
        
        store.dispatch(UpdateFeaturesAction(setOf("feature-a")))
        store.state.first { it.enabledFeatures.contains("feature-a") }
        store.routeTo("/complex")
        assertEquals("/", routerMiddleware.state.value.getCurrentContentRoute()?.path)
        
        store.dispatch(UpdateFeaturesAction(setOf("feature-a", "feature-b")))
        store.state.first { it.enabledFeatures.contains("feature-b") }
        store.routeTo("/complex")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.path == "/complex" }
        
        store.dispatch(UpdateFeaturesAction(setOf("desktop-override")))
        store.state.first { !it.enabledFeatures.contains("feature-a") && it.enabledFeatures.contains("desktop-override") }
        store.routeTo("/complex")
        val complexState = routerMiddleware.state.first()
        assertEquals("/complex", complexState.getCurrentContentRoute()?.path)
    }
}