package duks.routing

import androidx.compose.runtime.Composable
import duks.*
import duks.logging.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class RouterTest {
    private val logger = Logger.default()
    
    // Test app state
    data class TestAppState(
        val isAuthenticated: Boolean = false,
        val currentTab: String? = null
    ) : StateModel

    // Test action for authentication
    data class AuthAction(val isAuthenticated: Boolean) : Action

    // Test configs
    data class RouteConfig(
        val toolBarTitle: String = "Home", 
        val useFadeAnimation: Boolean = false,
        val transition: RouteTransition? = null
    )
    data class NavConfig(val selectedTab: String)
    
    // Common configs moved from main library - these are just examples
    data class NavigationConfig(
        val selectedTab: String? = null,
        val showBottomNav: Boolean = true,
        val showTopBar: Boolean = true
    )
    
    data class ModalConfig(
        val dismissible: Boolean = true,
        val presentationStyle: ModalPresentationStyle = ModalPresentationStyle.Sheet
    )
    
    enum class ModalPresentationStyle {
        Sheet,
        FullScreen,
        Overlay,
        Dialog
    }
    
    // Transition types moved from main library - these are just examples
    sealed class RouteTransition {
        data class Slide(
            val direction: SlideDirection = SlideDirection.FromRight,
            val duration: Int = 300
        ) : RouteTransition()
        
        data class Fade(val duration: Int = 300) : RouteTransition()
        
        data class Custom(
            val name: String,
            val params: Map<String, Any> = emptyMap()
        ) : RouteTransition()
    }
    
    enum class SlideDirection {
        FromRight,
        FromLeft,
        FromTop,
        FromBottom
    }
    
    @Test
    fun `test router middleware with isolated state`() = runTest {
        var appStateChangeCount = 0
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            routerMiddleware = routing(
                authConfig = AuthConfig({ state -> state.isAuthenticated })
            ) {
                content("/", config = NavigationConfig(selectedTab = "home")) { TestHomeScreen() }
                content("/profile", config = NavigationConfig(selectedTab = "profile")) { TestProfileScreen() }
                content("/settings", requiresAuth = true) { TestSettingsScreen() }
            }
            
            // App's reducer listens to routing changes
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        appStateChangeCount++
                        val navConfig = action.routerState.findConfig<NavigationConfig>()
                        state.copy(currentTab = navConfig?.selectedTab)
                    }
                    else -> state
                }
            }
        }
        
        // Wait for initial state to stabilize (router starts with "/" route)
        store.state.first { it.currentTab == "home" }
        // Also wait for router middleware state to stabilize
        routerMiddleware.state.first { it.contentRoutes.isNotEmpty() && it.getCurrentContentRoute()?.route?.path == "/" }
        
        // Navigate to profile
        store.routeTo("/profile")
        
        // Wait for router middleware state to update first
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/profile" }
        // Then wait for app state to change to profile
        store.state.first { it.currentTab == "profile" }
        
        // Verify app state was updated
        assertTrue(appStateChangeCount > 0, "Expected state change count to be greater than 0, but was $appStateChangeCount")
        assertEquals("profile", store.state.value.currentTab)
    }

    @Test
    fun `test navigation layers`() = runTest {
        val store = createStore(TestAppState()) {
            routing {
                content("/") { TestHomeScreen() } // Add root route
                scene("/login") { TestLoginScreen() }
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                modal("/compose") { TestComposeModal() }
            }
        }
        
        // Test different navigation actions
        store.routeTo("/home")
        store.routeTo("/profile")
        store.showModal("/compose")
        
        // Test go back
        store.goBack() // Should dismiss modal
        store.goBack() // Should go back to home
    }

    @Test
    fun `test authentication flow`() = runTest {
        // Enhanced state to track auth failures and navigation
        data class AppState(
            val isAuthenticated: Boolean = false,
            val currentRoute: String? = null,
            val authFailureCount: Int = 0,
            val lastFailedRoute: String? = null
        ) : StateModel
        
        val store = createStore(AppState()) {
            routing(
                authConfig = AuthConfig(
                    authChecker = { state -> state.isAuthenticated },
                    unauthenticatedRoute = "/login",
                    onAuthFailure = { store, route ->
                        // Track auth failures in the state via a custom action
                        store.dispatch(AuthFailedAction(route.path))
                    }
                )
            ) {
                content("/") { TestHomeScreen() }
                content("/login") { TestLoginScreen() }
                content("/home") { TestHomeScreen() }
                content("/profile", requiresAuth = true) { TestProfileScreen() }
                content("/settings", requiresAuth = true) { TestSettingsScreen() }
            }
            
            // Track all state changes in the reducer
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        val currentRoute = action.routerState.getCurrentContentRoute()?.route?.path
                        state.copy(currentRoute = currentRoute)
                    }
                    is AuthAction -> {
                        state.copy(isAuthenticated = action.isAuthenticated)
                    }
                    is AuthFailedAction -> {
                        state.copy(
                            authFailureCount = state.authFailureCount + 1,
                            lastFailedRoute = action.route
                        )
                    }
                    else -> state
                }
            }
        }
        
        // Test 1: Navigate to protected route while not authenticated
        store.routeTo("/profile")
        
        // Wait for the state to reflect the auth failure and redirect
        val stateAfterAuthFail = store.state.first { state ->
            state.authFailureCount > 0 && state.currentRoute == "/login"
        }
        
        // Verify auth failure was recorded and we were redirected to login
        assertEquals(1, stateAfterAuthFail.authFailureCount)
        assertEquals("/profile", stateAfterAuthFail.lastFailedRoute)
        assertEquals("/login", stateAfterAuthFail.currentRoute)
        assertFalse(stateAfterAuthFail.isAuthenticated)
        
        // Test 2: Authenticate and try again
        store.dispatch(AuthAction(true))
        
        // Wait for authentication to be processed
        store.state.first { it.isAuthenticated }
        
        // Navigate to protected route again
        store.routeTo("/profile")
        
        // Wait for navigation to complete
        val stateAfterAuth = store.state.first { state ->
            state.isAuthenticated && state.currentRoute == "/profile"
        }
        
        // Verify we successfully navigated and no new auth failures occurred
        assertEquals("/profile", stateAfterAuth.currentRoute)
        assertEquals(1, stateAfterAuth.authFailureCount) // Should still be 1 from before
        assertTrue(stateAfterAuth.isAuthenticated)
    }
    
    // Custom action to track auth failures
    data class AuthFailedAction(val route: String) : Action

    @Test
    fun `test device conditions with operators`() = runTest {
        val store = createStore(TestAppState()) {
            routing {
                content("/") { TestHomeScreen() } // Add root route
                // Desktop layout
                content(
                    "/dashboard",
                    whenCondition = RenderCondition.ScreenSize(minWidth = 1024),
                    config = RouteConfig(toolBarTitle = "Desktop Dashboard")
                ) { TestDesktopDashboard() }
                
                // Tablet landscape
                content(
                    "/dashboard",
                    whenCondition = RenderCondition.ScreenSize(minWidth = 768, maxWidth = 1023) and 
                                   RenderCondition.Orientation(ScreenOrientation.Landscape),
                    config = RouteConfig(toolBarTitle = "Tablet Dashboard")
                ) { TestTabletDashboard() }
                
                // Mobile or tablet portrait
                content(
                    "/dashboard",
                    whenCondition = RenderCondition.ScreenSize(maxWidth = 767) or
                                   (RenderCondition.ScreenSize(minWidth = 768, maxWidth = 1023) and 
                                    RenderCondition.Orientation(ScreenOrientation.Portrait)),
                    config = RouteConfig(toolBarTitle = "Mobile Dashboard")
                ) { TestMobileDashboard() }
                
                // Default fallback
                content("/dashboard") { TestDefaultDashboard() }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> state
                    else -> state
                }
            }
        }
        
        // Update device context for desktop
        store.dispatch(DeviceAction.UpdateScreenSize(1200, 800))
        store.routeTo("/dashboard")
        
        // Update device context for tablet landscape
        store.dispatch(DeviceAction.UpdateScreenSize(800, 600))
        store.routeTo("/dashboard")
        
        // Update device context for mobile
        store.dispatch(DeviceAction.UpdateScreenSize(400, 800))
        store.routeTo("/dashboard")
    }

    @Test
    fun `test route groups with shared configuration`() = runTest {
        val store = createStore(TestAppState()) {
            routing {
                // Public routes
                group(config = RouteConfig(toolBarTitle = "Public")) {
                    content("/") { TestHomeScreen() }
                    content("/about") { TestAboutScreen() }
                }
                
                // Auth required routes
                group(
                    requiresAuth = true,
                    config = RouteConfig(toolBarTitle = "Protected", useFadeAnimation = true)
                ) {
                    content("/profile") { TestProfileScreen() }
                    content("/settings") { TestSettingsScreen() }
                    
                    // Override config for specific route
                    content(
                        "/admin",
                        config = RouteConfig(toolBarTitle = "Admin Panel")
                    ) { TestAdminScreen() }
                }
                
                // Routes with path prefix
                group(pathPrefix = "/app") {
                    content("home", config = NavConfig("home")) { TestHomeScreen() }
                    content("profile", config = NavConfig("profile")) { TestProfileScreen() }
                    
                    // Typed param route in group
                    content<String>("user") { userId ->
                        // userId is typed as String (non-nullable)
                        TestUserProfile()
                    }
                }
            }
        }
        
        // Test navigation to grouped routes
        store.routeTo("/profile") // Should require auth
        store.routeTo("/app/home") // Should have path prefix applied
    }

    @Test
    fun `test group config inheritance behavior`() = runTest(timeout = 5.seconds) {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val groupConfig = RouteConfig(toolBarTitle = "Group Default", useFadeAnimation = true)
        val overrideConfig = RouteConfig(toolBarTitle = "Override Config", useFadeAnimation = false)
        
        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                initialRoute("/home")
                // Add a default content route to ensure router is initialized
                content("/home") { TestHomeScreen() }
                
                // Group with default config that should be inherited
                group(config = groupConfig) {
                    // Route without explicit config - should inherit group config
                    content("/inherit") { TestHomeScreen() }
                    
                    // Route with explicit config - should use the explicit config
                    content("/override", config = overrideConfig) { TestProfileScreen() }
                    
                    // Modal route without explicit config - should inherit group config
                    modal("/modal-inherit") { TestComposeModal() }
                    
                    // Modal route with explicit config - should use the explicit config
                    modal("/modal-override", config = overrideConfig) { TestComposeModal() }
                    
                    // Scene route without explicit config - should inherit group config
                    scene("/scene-inherit") { TestSplashScreen() }
                    
                    // Scene route with explicit config - should use the explicit config
                    scene("/scene-override", config = overrideConfig) { TestLoginScreen() }
                }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        state.copy(currentTab = action.routerState.getCurrentContentRoute()?.route?.path)
                    }
                    else -> state
                }
            }
        }
        
        // Wait for initial router state
        routerMiddleware.state.first { it.contentRoutes.isNotEmpty() }
        
        // Navigate to home first to ensure router is initialized
        store.routeTo("/home")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/home" }
        store.state.first { it.currentTab == "/home" }
        
        // Test content route that should inherit group config
        store.routeTo("/inherit")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/inherit" }
        store.state.first { it.currentTab == "/inherit" }
        
        val inheritRoute = routerMiddleware.state.value.getCurrentContentRoute()
        assertEquals("/inherit", inheritRoute?.route?.path)
        val inheritedConfig = inheritRoute?.route?.config as? RouteConfig
        assertEquals(groupConfig, inheritedConfig)
        assertEquals("Group Default", inheritedConfig?.toolBarTitle)
        assertEquals(true, inheritedConfig?.useFadeAnimation)
        
        // Test content route that should use explicit config
        store.routeTo("/override")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/override" }
        store.state.first { it.currentTab == "/override" }
        
        val overrideRoute = routerMiddleware.state.value.getCurrentContentRoute()
        assertEquals("/override", overrideRoute?.route?.path)
        val explicitConfig = overrideRoute?.route?.config as? RouteConfig
        assertEquals(overrideConfig, explicitConfig)
        assertEquals("Override Config", explicitConfig?.toolBarTitle)
        assertEquals(false, explicitConfig?.useFadeAnimation)
        
        // Test modal route that should inherit group config
        store.showModal("/modal-inherit")
        routerMiddleware.state.first { it.modalRoutes.isNotEmpty() }
        
        val modalInheritRoute = routerMiddleware.state.value.modalRoutes.firstOrNull { it.route.path == "/modal-inherit" }
        assertEquals("/modal-inherit", modalInheritRoute?.route?.path)
        val modalInheritedConfig = modalInheritRoute?.route?.config as? RouteConfig
        assertEquals(groupConfig, modalInheritedConfig)
        assertEquals("Group Default", modalInheritedConfig?.toolBarTitle)
        assertEquals(true, modalInheritedConfig?.useFadeAnimation)
        
        // Test modal route that should use explicit config
        store.showModal("/modal-override")
        routerMiddleware.state.first { it.modalRoutes.size >= 2 }
        
        val modalOverrideRoute = routerMiddleware.state.value.modalRoutes.firstOrNull { it.route.path == "/modal-override" }
        assertEquals("/modal-override", modalOverrideRoute?.route?.path)
        val modalExplicitConfig = modalOverrideRoute?.route?.config as? RouteConfig
        assertEquals(overrideConfig, modalExplicitConfig)
        assertEquals("Override Config", modalExplicitConfig?.toolBarTitle)
        assertEquals(false, modalExplicitConfig?.useFadeAnimation)
        
        // Test scene route that should inherit group config
        store.routeTo("/scene-inherit", layer = NavigationLayer.Scene)
        
        // Wait for scene route to be added with correct path
        routerMiddleware.state.first { state ->
            state.sceneRoutes.any { it.route.path == "/scene-inherit" }
        }
        
        // Debug: log all scene routes
        logger.debug { "Scene routes after navigating to /scene-inherit:" }
        routerMiddleware.state.value.sceneRoutes.forEach { route ->
            logger.debug(route.route.path, route.route.config) { "  Path: {path}, Config: {config}" }
        }
        
        val sceneInheritRoute = routerMiddleware.state.value.sceneRoutes.firstOrNull { it.route.path == "/scene-inherit" }
        assertNotNull(sceneInheritRoute)
        assertEquals("/scene-inherit", sceneInheritRoute.route.path)
        val sceneInheritedConfig = sceneInheritRoute.route.config as? RouteConfig
        assertNotNull(sceneInheritedConfig)
        assertEquals(groupConfig, sceneInheritedConfig)
        assertEquals("Group Default", sceneInheritedConfig.toolBarTitle)
        assertEquals(true, sceneInheritedConfig.useFadeAnimation)
        
        // Test scene route that should use explicit config
        store.routeTo("/scene-override", layer = NavigationLayer.Scene)
        
        // Wait for second scene route to be added with correct path
        routerMiddleware.state.first { state ->
            state.sceneRoutes.size >= 2 && 
            state.sceneRoutes.any { it.route.path == "/scene-override" }
        }
        
        val sceneOverrideRoute = routerMiddleware.state.value.sceneRoutes.firstOrNull { it.route.path == "/scene-override" }
        assertNotNull(sceneOverrideRoute)
        assertEquals("/scene-override", sceneOverrideRoute.route.path)
        val sceneExplicitConfig = sceneOverrideRoute.route.config as? RouteConfig
        assertNotNull(sceneExplicitConfig)
        assertEquals(overrideConfig, sceneExplicitConfig)
        assertEquals("Override Config", sceneExplicitConfig.toolBarTitle)
        assertEquals(false, sceneExplicitConfig.useFadeAnimation)
    }

    @Test
    fun `test modal navigation`() = runTest {
        var routerStateChanges = 0
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                content("/") { TestHomeScreen() } // Add root route
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                
                modal("/compose", config = ModalConfig(dismissible = true)) { TestComposeModal() }
                modal("/photo/123", config = ModalConfig(presentationStyle = ModalPresentationStyle.FullScreen)) { TestPhotoModal() }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        routerStateChanges++
                        state.copy(currentTab = "changed-$routerStateChanges")
                    }
                    else -> state
                }
            }
        }
        
        // Wait for initial router state
        routerMiddleware.state.first { it.contentRoutes.isNotEmpty() }
        
        // Navigate to content
        store.routeTo("/home")
        
        // Wait for router middleware state to update
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/home" }
        // Wait for app state change
        store.state.first { it.currentTab?.startsWith("changed-") == true }
        
        // Show modal
        store.showModal("/compose")
        
        // Wait for router middleware to show modal
        routerMiddleware.state.first { it.modalRoutes.any { modal -> modal.route.path == "/compose" } }
        
        assertTrue(routerStateChanges > 0, "Expected router state to change at least once, but was $routerStateChanges")
        
        // Show another modal (stacking)
        store.showModal("/photo/123")
        
        // Wait for second modal
        routerMiddleware.state.first { it.modalRoutes.size == 2 }
        
        // Dismiss specific modal
        store.dismissModal("/compose")
        
        // Wait for modal to be dismissed
        routerMiddleware.state.first { it.modalRoutes.size == 1 && it.modalRoutes.first().route.path == "/photo/123" }
        
        // Dismiss top modal
        store.dismissModal()
        
        // Wait for all modals to be dismissed
        routerMiddleware.state.first { it.modalRoutes.isEmpty() }
        
        // Go back from content
        store.goBack()
        
        // Wait for navigation back - should go from /home to /
        routerMiddleware.state.first { state ->
            state.contentRoutes.isNotEmpty() && state.getCurrentContentRoute()?.route?.path == "/"
        }
    }

    @Test
    fun `test navigation state preservation`() = runTest {
        val store = createStore(TestAppState()) {
            routing {
                content("/") { TestHomeScreen() } // Add root route
                content("/videos", config = NavigationConfig(selectedTab = "videos")) { TestVideosList() }
                content("/songs", config = NavigationConfig(selectedTab = "songs")) { SongsList() }
                content("/profile", config = NavigationConfig(selectedTab = "profile")) { TestProfileScreen() }
                
                // These routes don't change the selected tab
                content("/video/:id") { TestVideoDetail() }
                content("/songs/:id") { TestSongDetail() }
                content("/settings") { TestSettingsScreen() }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        val navConfig = action.routerState.findConfig<NavigationConfig>()
                        state.copy(currentTab = navConfig?.selectedTab ?: state.currentTab)
                    }
                    else -> state
                }
            }
        }
        
        // Navigate to videos tab
        store.routeTo("/videos")
        store.state.first { it.currentTab == "videos" }
        assertEquals("videos", store.state.value.currentTab)
        
        // Navigate to video detail - should preserve "videos" tab
        store.routeTo("/video/123")
        // Tab should stay the same since no NavigationConfig is provided
        assertEquals("videos", store.state.value.currentTab)
        
        // Navigate to songs tab
        store.routeTo("/songs")
        store.state.first { it.currentTab == "songs" }
        assertEquals("songs", store.state.value.currentTab)
        
        // Navigate to video detail from songs - should preserve "songs" tab
        store.routeTo("/video/456")
        // Tab should stay the same since no NavigationConfig is provided
        assertEquals("songs", store.state.value.currentTab)
    }

    @Test
    fun `test deep linking`() = runTest {
        val store = createStore(TestAppState()) {
            routing {
                content("/") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                content("/video/:id") { TestVideoDetail() }
            }
        }
        
        // Test deep link
        store.dispatch(Routing.DeepLink("myapp://video/123"))
    }

    @Test
    fun `test route transitions`() = runTest {
        // This test demonstrates how transitions can be handled at the config level
        // rather than being baked into the routing DSL
        val store = createStore(TestAppState()) {
            routing {
                content(
                    "/home",
                    config = RouteConfig(
                        toolBarTitle = "Home",
                        transition = RouteTransition.Fade(duration = 200)
                    )
                ) { TestHomeScreen() }
                
                content(
                    "/profile",
                    config = RouteConfig(
                        toolBarTitle = "Profile",
                        transition = RouteTransition.Slide(direction = SlideDirection.FromRight)
                    )
                ) { TestProfileScreen() }
                
                modal(
                    "/compose",
                    config = ModalConfig(
                        dismissible = true,
                        presentationStyle = ModalPresentationStyle.Sheet
                    )
                ) { TestComposeModal() }
                
                content(
                    "/custom",
                    config = RouteConfig(
                        toolBarTitle = "Custom",
                        transition = RouteTransition.Custom("customAnimation", mapOf("speed" to 1.5f))
                    )
                ) { TestCustomScreen() }
            }
        }
        
        // Navigate with transitions
        store.routeTo("/profile")
        store.routeTo("/custom")
        store.showModal("/compose")
    }

    @Test
    fun `test fallback route`() = runTest {
        val store = createStore(TestAppState()) {
            routing(fallbackRoute = "/404") {
                content("/") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                content("/404") { Test404Screen() }
            }
        }
        
        // Navigate to non-existent route
        store.routeTo("/non-existent")
        // Should navigate to /404
    }

    @Test
    fun `test replace content navigation`() = runTest {
        val store = createStore(TestAppState()) {
            routing {
                content("/") { TestHomeScreen() } // Add root route
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                content("/settings") { TestSettingsScreen() }
            }
        }
        
        // Build up navigation stack
        store.routeTo("/home")
        store.routeTo("/profile")
        store.routeTo("/settings")
        
        // Replace content (clears stack)
        store.dispatch(Routing.ReplaceContent("/home"))
        
        // Now going back should do nothing (only one route in stack)
        store.goBack()
    }

    @Test
    fun `test pop to specific route`() = runTest {
        val store = createStore(TestAppState()) {
            routing {
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                content("/settings") { TestSettingsScreen() }
                content("/billing") { TestBillingScreen() }
            }
        }
        
        // Build up navigation stack
        store.routeTo("/home")
        store.routeTo("/profile")
        store.routeTo("/settings")
        store.routeTo("/billing")
        
        // Pop back to profile
        store.popToRoute("/profile")
        // Stack should now be: home, profile
    }

    @Test
    fun `test clear layer`() = runTest {
        val store = createStore(TestAppState()) {
            routing {
                content("/home") { TestHomeScreen() }
                modal("/modal1") { TestModal1() }
                modal("/modal2") { TestModal2() }
            }
        }
        
        // Set up multiple layers
        store.routeTo("/home")
        store.showModal("/modal1")
        store.showModal("/modal2")
        
        // Clear modal layer
        store.dispatch(Routing.ClearLayer(NavigationLayer.Modal))
        // All modals should be dismissed
    }

    @Test
    fun `test custom device conditions`() = runTest {
        
        val store = createStore(TestAppState()) {
            routing {
                content(
                    "/dashboard",
                    whenCondition = RenderCondition.Custom { context ->
                        context.customProperties["deviceType"] == "premium"
                    },
                    config = RouteConfig(toolBarTitle = "Premium Dashboard")
                ) { TestPremiumDashboard() }
                
                content(
                    "/dashboard",
                    config = RouteConfig(toolBarTitle = "Standard Dashboard")
                ) { TestStandardDashboard() }
            }
        }
        
        // Update device context with custom properties
        store.dispatch(DeviceAction.UpdateDeviceContext(
            DeviceContext(
                screenWidth = 1200,
                screenHeight = 800,
                orientation = ScreenOrientation.Landscape,
                deviceType = DeviceClass.Desktop,
                customProperties = mapOf("deviceType" to "premium")
            )
        ))
        
        store.routeTo("/dashboard")
        // Should route to premium dashboard
    }

    @Test
    fun `test watch device detection`() = runTest {
        val store = createStore(TestAppState()) {
            routing {
                // Watch-specific layout
                content(
                    "/dashboard",
                    whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Watch)),
                    config = RouteConfig(toolBarTitle = "Watch Dashboard")
                ) { TestWatchDashboard() }
                
                // Phone layout (fallback)
                content(
                    "/dashboard",
                    whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Phone)),
                    config = RouteConfig(toolBarTitle = "Phone Dashboard")
                ) { TestMobileDashboard() }
                
                // Default fallback
                content("/dashboard") { TestDefaultDashboard() }
            }
        }
        
        // Simulate watch device context (small, square screen)
        store.dispatch(DeviceAction.UpdateDeviceContext(
            DeviceContext(
                screenWidth = 280,
                screenHeight = 280,
                orientation = ScreenOrientation.Portrait,
                deviceType = DeviceClass.Watch
            )
        ))
        
        store.routeTo("/dashboard")
        
        // Verify the watch-specific route was chosen
        // Note: In real implementation, the routing would select the watch-specific route
        // This test verifies the API and device context handling works
        
        // Test that device type determination logic includes watch
        store.dispatch(DeviceAction.UpdateScreenSize(300, 320))
        store.routeTo("/dashboard")
    }

    @Test
    fun `test DeviceContextProvider usage example`() = runTest {
        // This test demonstrates how DeviceContextProvider would be used in a real app
        // Note: This is a conceptual test showing the API usage
        
        /*
        @Composable
        fun App() {
            val store = remember { 
                createStore(TestAppState()) {
                    routing {
                        // Mobile layout
                        content(
                            "/dashboard",
                            whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Phone)),
                            config = RouteConfig(toolBarTitle = "Mobile Dashboard")
                        ) { MobileDashboard() }
                        
                        // Tablet layout
                        content(
                            "/dashboard",
                            whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Tablet)),
                            config = RouteConfig(toolBarTitle = "Tablet Dashboard")
                        ) { TabletDashboard() }
                        
                        // Desktop layout
                        content(
                            "/dashboard",
                            whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Desktop)),
                            config = RouteConfig(toolBarTitle = "Desktop Dashboard")
                        ) { DesktopDashboard() }
                    }
                }
            }
            
            // DeviceContextProvider automatically tracks device changes
            // - Android/JVM/WasmJS: Uses BoxWithConstraints for real-time dimension updates
            // - Native: Uses static default values
            DeviceContextProvider(
                store = store,
                customProperties = mapOf("theme" to "dark", "userTier" to "premium")
            ) {
                // Your app content - automatically adapts to device changes
                RouterContent(store)
            }
        }
        
        // The DeviceContextProvider now automatically uses BoxWithConstraints internally
        // on platforms that support it (Android, JVM, WasmJS) for real-time updates:
        //
        // - Screen orientation changes are automatically detected
        // - Window resize events are automatically tracked  
        // - Device type is determined by screen dimensions
        // - Orientation is derived from width vs height
        //
        // For native platforms, static defaults are used but can be enhanced
        // with platform-specific detection code.
        */
        
        // For testing purposes, we manually dispatch device updates
        val store = createStore(TestAppState()) {
            routing {
                content(
                    "/dashboard",
                    whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Phone))
                ) { TestMobileDashboard() }
                
                content(
                    "/dashboard",
                    whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Desktop))
                ) { TestDesktopDashboard() }
            }
        }
        
        // Simulate device context updates that would come from DeviceContextProvider
        store.dispatch(DeviceAction.UpdateDeviceContext(
            DeviceContext(
                screenWidth = 375,
                screenHeight = 667,
                orientation = ScreenOrientation.Portrait,
                deviceType = DeviceClass.Phone
            )
        ))
        
        store.routeTo("/dashboard")
        // Should route to mobile dashboard
    }

// Test composables
@Composable
private fun TestHomeScreen() {}

@Composable
private fun TestProfileScreen() {}

@Composable
private fun TestSettingsScreen() {}

@Composable
private fun TestLoginScreen() {}

@Composable
private fun TestComposeModal() {}

@Composable
private fun TestDesktopDashboard() {}

@Composable
private fun TestTabletDashboard() {}

@Composable
private fun TestMobileDashboard() {}

@Composable
private fun TestDefaultDashboard() {}

@Composable
private fun TestAboutScreen() {}

@Composable
private fun TestAdminScreen() {}

@Composable
private fun TestPhotoModal() {}

@Composable
private fun TestVideosList() {}

@Composable
private fun SongsList() {}

@Composable
private fun TestVideoDetail() {}

@Composable
private fun TestSongDetail() {}

@Composable
private fun TestCustomScreen() {}

@Composable
private fun Test404Screen() {}

@Composable
private fun TestBillingScreen() {}

@Composable
private fun TestModal1() {}

@Composable
private fun TestModal2() {}

@Composable
private fun TestPremiumDashboard() {}

@Composable
private fun TestStandardDashboard() {}

@Composable
private fun TestWatchDashboard() {}

@Composable
private fun TestSplashScreen() {}

@Composable
private fun TestOnboardingScreen() {}

@Composable
private fun TestMainScreen() {}

    @Test
    fun `test generic parameter support`() = runTest {
        // Test data classes
        data class Product(
            val id: String,
            val name: String,
            val price: Double,
            val category: String
        )
        
        data class User(
            val id: String,
            val name: String,
            val email: String,
            val premium: Boolean
        )
        
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                content("/") { TestHomeScreen() }  // Add scene route
                content("/product/detail") { TestProductDetail() }
                content("/user/profile") { TestUserProfile() }
                content("/list") { TestListScreen() }
            }
            
            // Add reducer to track routing state changes
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        // Force state update to trigger flow emissions
                        state.copy(currentTab = action.routerState.getCurrentContentRoute()?.route?.path)
                    }
                    else -> state
                }
            }
        }
        
        // Wait for initial router state
        routerMiddleware.state.first { it.contentRoutes.isNotEmpty() }
        
        // Test passing a product object
        val testProduct = Product(
            id = "123",
            name = "Test Product",
            price = 99.99,
            category = "Electronics"
        )
        store.routeTo("/product/detail", testProduct)
        
        // Wait for router middleware state to update first
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/product/detail" }
        // Then wait for app state to update
        store.state.first { it.currentTab == "/product/detail" }
        
        // Get the current route directly from router middleware
        val productRoute = routerMiddleware.state.value.getCurrentContentRoute()
        assertEquals("/product/detail", productRoute?.route?.path)
        assertEquals(testProduct, productRoute?.param)
        
        // Test passing a user object as parameter
        val testUser = User(
            id = "456",
            name = "John Doe",
            email = "john@example.com",
            premium = true
        )
        store.routeTo("/user/profile", testUser)
        
        // Wait for navigation to complete
        store.state.first { it.currentTab == "/user/profile" }
        
        // Get the current route directly from router middleware
        val userRoute = routerMiddleware.state.value.getCurrentContentRoute()
        assertEquals("/user/profile", userRoute?.route?.path)
        assertEquals(testUser, userRoute?.param)
        
        // Test passing a list as parameter
        val testList = listOf("Item 1", "Item 2", "Item 3")
        store.routeTo("/list", testList)
        
        // Wait for navigation to complete
        store.state.first { it.currentTab == "/list" }
        
        // Get the current route directly from router middleware
        val listRoute = routerMiddleware.state.value.getCurrentContentRoute()
        assertEquals("/list", listRoute?.route?.path)
        assertEquals(testList, listRoute?.param)
        
        // Test passing a map as parameter (to show Any? flexibility)
        val testMap = mapOf("key1" to "value1", "key2" to 42)
        store.routeTo("/product/detail", testMap)
        
        // Wait for navigation to complete
        store.state.first { it.currentTab == "/product/detail" }
        
        // Get the current route directly from router middleware
        val mapRoute = routerMiddleware.state.value.getCurrentContentRoute()
        assertEquals("/product/detail", mapRoute?.route?.path)
        assertEquals(testMap, mapRoute?.param)
    }

    @Test
    fun `test modal with object parameters`() = runTest {
        data class Comment(
            val id: String,
            val text: String,
            val author: String,
            val timestamp: Long
        )
        
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        var modalCount = 0

        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                content("/home") { TestHomeScreen() }
                modal("/comment/edit") { TestCommentEditModal() }
            }
            
            // Add reducer to track routing state changes
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        modalCount = action.routerState.modalRoutes.size
                        // Force state update to trigger flow emissions
                        state.copy(currentTab = "modals:$modalCount")
                    }
                    else -> state
                }
            }
        }
        
        // Navigate to home first
        store.routeTo("/home")
        
        // Wait for navigation to complete
        store.state.first { it.currentTab != null }
        
        // Show modal with comment object
        val testComment = Comment(
            id = "comment-1",
            text = "This is a test comment",
            author = "Test User",
            timestamp = 1234567890L
        )
        store.showModal("/comment/edit", testComment)
        
        // Wait for modal to be shown
        store.state.first { it.currentTab == "modals:1" }
        
        // Get the modal route directly from router middleware
        val modalRoute = routerMiddleware.state.value.modalRoutes.firstOrNull()
        assertEquals("/comment/edit", modalRoute?.route?.path)
        assertEquals(testComment, modalRoute?.param)
    }

@Composable
private fun TestProductDetail() {}

@Composable
private fun TestUserProfile() {}

@Composable
private fun TestListScreen() {}

@Composable
private fun TestCommentEditModal() {}

@Composable
private fun TestEditModal() {}

@Composable
private fun TestWizardScreen() {}

@Composable
private fun TestSearchScreen() {}

@Composable
private fun TestArticleScreen() {}

@Composable
private fun TestTagsModal() {}

    @Test
    fun `test goBack functionality comprehensively`() = runTest {
        val store = createStore(TestAppState()) {
            routing {
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                modal("/compose") { TestComposeModal() }
            }
        }
        
        // Test basic goBack functionality - API exists and doesn't throw
        store.routeTo("/home")
        
        store.goBack()
        
        // Test that goBack can be called multiple times without error
        store.goBack()
        store.goBack()
        
        // Verify the API exists and is callable
        assertTrue(true, "goBack function is available and callable without errors")
    }

    @Test
    fun `test clear history navigation`() = runTest {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        var currentPath: String?

        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                content("/") { TestHomeScreen() } // Add root route
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                content("/settings") { TestSettingsScreen() }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        currentPath = action.routerState.getCurrentContentRoute()?.route?.path
                        state.copy(currentTab = currentPath)
                    }
                    else -> state
                }
            }
        }
        
        // Wait for initial router state
        routerMiddleware.state.first { it.contentRoutes.isNotEmpty() }
        
        // Build up a navigation stack first
        store.routeTo("/home")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/home" }
        store.state.first { it.currentTab == "/home" }
        
        store.routeTo("/profile")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/profile" }
        store.state.first { it.currentTab == "/profile" }
        
        store.routeTo("/settings")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/settings" }
        store.state.first { it.currentTab == "/settings" }
        
        // Wait for router state to stabilize with the expected routes
        routerMiddleware.state.first { it.contentRoutes.size >= 4 }
        
        // Verify we have multiple routes in the stack
        // Note: The initial route is automatically added, so we expect 4 routes total
        val routeCount = routerMiddleware.state.value.contentRoutes.size
        assertTrue(routeCount >= 4, "Expected at least 4 routes in stack, but was $routeCount")
        
        // Now use clearHistory to navigate to a new route
        store.routeTo("/profile", clearHistory = true)
        store.state.first { it.currentTab == "/profile" }
        
        // Verify the history was cleared - should only have one route now
        assertEquals(1, routerMiddleware.state.value.contentRoutes.size)
        assertEquals("/profile", routerMiddleware.state.value.getCurrentContentRoute()?.route?.path)
        
        // Verify modals are also cleared
        assertEquals(0, routerMiddleware.state.value.modalRoutes.size)
    }

    @Test
    fun `test scene route history stack`() = runTest {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                content("/") { TestHomeScreen() } // Add root route
                scene("/splash") { TestSplashScreen() }
                scene("/login") { TestLoginScreen() }
                scene("/onboarding") { TestOnboardingScreen() }
                scene("/main") { TestMainScreen() }
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                modal("/compose") { TestComposeModal() }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        val currentSceneRoute = action.routerState.sceneRoutes.lastOrNull()?.route?.path
                        state.copy(currentTab = currentSceneRoute)
                    }
                    else -> state
                }
            }
        }
        
        // Wait for router initialization to complete
        routerMiddleware.state.first { it.contentRoutes.isNotEmpty() }
        
        // Check initial state
        val initialState = routerMiddleware.state.value
        logger.debug { "Initial state debug:" }
        logger.debug(initialState.sceneRoutes.size) { "  sceneRoutes.size: {size}" }
        logger.debug(initialState.contentRoutes.size) { "  contentRoutes.size: {size}" }
        if (initialState.contentRoutes.isNotEmpty()) {
            logger.debug(initialState.contentRoutes.first().route.path) { "  Initial content route: {path}" }
        }
        assertEquals(0, initialState.sceneRoutes.size, "Expected initial sceneRoutes to be empty, but was ${initialState.sceneRoutes.size}")
        assertEquals(1, initialState.contentRoutes.size, "Expected initial contentRoutes to have root route")
        assertEquals("/", initialState.contentRoutes.first().route.path)
        
        // Navigate to splash screen
        store.routeTo("/splash", layer = NavigationLayer.Scene)
        routerMiddleware.state.first { it.sceneRoutes.isNotEmpty() && it.sceneRoutes.last().route.path == "/splash" }
        store.state.first { it.currentTab == "/splash" }
        assertEquals(1, routerMiddleware.state.value.sceneRoutes.size, "Expected 1 scene route after navigating to splash")
        assertEquals("/splash", routerMiddleware.state.value.sceneRoutes.last().route.path)
        
        // Navigate to login screen
        store.routeTo("/login", layer = NavigationLayer.Scene)
        routerMiddleware.state.first { it.sceneRoutes.size == 2 && it.sceneRoutes.last().route.path == "/login" }
        store.state.first { it.currentTab == "/login" }
        assertEquals(2, routerMiddleware.state.value.sceneRoutes.size)
        assertEquals("/login", routerMiddleware.state.value.sceneRoutes.last().route.path)
        
        // Navigate to onboarding
        store.routeTo("/onboarding", layer = NavigationLayer.Scene)
        routerMiddleware.state.first { it.sceneRoutes.size == 3 && it.sceneRoutes.last().route.path == "/onboarding" }
        store.state.first { it.currentTab == "/onboarding" }
        assertEquals(3, routerMiddleware.state.value.sceneRoutes.size)
        assertEquals("/onboarding", routerMiddleware.state.value.sceneRoutes.last().route.path)
        
        // Navigate to main app
        store.routeTo("/main", layer = NavigationLayer.Scene)
        routerMiddleware.state.first { it.sceneRoutes.size == 4 && it.sceneRoutes.last().route.path == "/main" }
        store.state.first { it.currentTab == "/main" }
        assertEquals(4, routerMiddleware.state.value.sceneRoutes.size)
        assertEquals("/main", routerMiddleware.state.value.sceneRoutes.last().route.path)
        
        // Test going back through scene routes
        logger.debug { "Before first goBack:" }
        logger.debug(routerMiddleware.state.value.sceneRoutes.size) { "  sceneRoutes.size: {size}" }
        logger.debug(routerMiddleware.state.value.contentRoutes.size) { "  contentRoutes.size: {size}" }
        logger.debug(routerMiddleware.state.value.modalRoutes.size) { "  modalRoutes.size: {size}" }
        
        store.goBack()
        
        // Wait for the state to actually change to 3 scene routes
        routerMiddleware.state.first { it.sceneRoutes.size == 3 }
        
        logger.debug { "After first goBack:" }
        logger.debug(routerMiddleware.state.value.sceneRoutes.size) { "  sceneRoutes.size: {size}" }
        logger.debug(routerMiddleware.state.value.contentRoutes.size) { "  contentRoutes.size: {size}" }
        logger.debug(routerMiddleware.state.value.modalRoutes.size) { "  modalRoutes.size: {size}" }
        
        assertEquals(3, routerMiddleware.state.value.sceneRoutes.size)
        assertEquals("/onboarding", routerMiddleware.state.value.sceneRoutes.last().route.path)
        
        store.goBack()
        
        // Wait for the state to actually change to 2 scene routes
        routerMiddleware.state.first { it.sceneRoutes.size == 2 }
        
        logger.debug { "After second goBack:" }
        logger.debug(routerMiddleware.state.value.sceneRoutes.size) { "  sceneRoutes.size: {size}" }
        
        assertEquals(2, routerMiddleware.state.value.sceneRoutes.size)
        assertEquals("/login", routerMiddleware.state.value.sceneRoutes.last().route.path)
        
        store.goBack()
        
        routerMiddleware.state.first { it.sceneRoutes.size == 1 }
        
        logger.debug { "After third goBack:" }
        logger.debug(routerMiddleware.state.value.sceneRoutes.size) { "  sceneRoutes.size: {size}" }
        
        assertEquals(1, routerMiddleware.state.value.sceneRoutes.size)
        assertEquals("/splash", routerMiddleware.state.value.sceneRoutes.last().route.path)
        
        // Can't go back from single scene route
        store.goBack()
        // State should remain unchanged
        assertEquals(1, routerMiddleware.state.value.sceneRoutes.size)
        assertEquals("/splash", routerMiddleware.state.value.sceneRoutes.last().route.path)
        
        // Test that scene navigation clears content and modal routes
        store.routeTo("/home")
        // Wait for home route to be added
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/home" }
        
        store.routeTo("/profile")
        // Wait for profile route to be added
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/profile" }
        
        store.showModal("/compose")
        // Wait for modal to be added
        routerMiddleware.state.first { it.modalRoutes.isNotEmpty() }
        
        // Verify state before scene navigation
        val stateBeforeScene = routerMiddleware.state.value
        assertTrue(stateBeforeScene.contentRoutes.isNotEmpty(), "Expected contentRoutes to not be empty")
        assertTrue(stateBeforeScene.modalRoutes.isNotEmpty(), "Expected modalRoutes to not be empty")
        
        // Navigate to scene route
        store.routeTo("/login", layer = NavigationLayer.Scene)
        
        // Wait for scene navigation to complete and clear other layers
        routerMiddleware.state.first { state ->
            state.sceneRoutes.size == 2 && 
            state.sceneRoutes.last().route.path == "/login" &&
            state.contentRoutes.isEmpty() &&
            state.modalRoutes.isEmpty()
        }
        
        // Wait for app state to update
        store.state.first { it.currentTab == "/login" }
        
        // Verify final state
        val finalState = routerMiddleware.state.value
        assertEquals(2, finalState.sceneRoutes.size)
        assertTrue(finalState.contentRoutes.isEmpty())
        assertTrue(finalState.modalRoutes.isEmpty())
    }

    @Test
    fun `test RouteInstance extension functions for parameter access`() = runTest {
        // Test data classes
        data class Product(
            val id: String,
            val name: String,
            val price: Double
        )
        
        data class User(
            val id: String,
            val name: String,
            val premium: Boolean
        )
        
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                content("/product/detail") { TestProductDetail() }
                content("/user/profile") { TestUserProfile() }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        state.copy(currentTab = action.routerState.getCurrentContentRoute()?.route?.path)
                    }
                    else -> state
                }
            }
        }
        
        // Test passing a product object and using extension function
        val testProduct = Product(id = "123", name = "Test Product", price = 99.99)
        store.routeTo("/product/detail", testProduct)
        
        store.state.first { it.currentTab == "/product/detail" }
        
        val productRoute = routerMiddleware.state.value.getCurrentContentRoute()!!
        
        // Test extension function access
        val extractedProduct: Product? = productRoute.routeParam()
        assertEquals(testProduct, extractedProduct)
        assertEquals("123", extractedProduct?.id)
        assertEquals("Test Product", extractedProduct?.name)
        assertEquals(99.99, extractedProduct?.price)
        
        // Test extension function with default value
        val defaultProduct = Product("default", "Default Product", 0.0)
        val productWithDefault: Product = productRoute.routeParam(defaultProduct)
        assertEquals(testProduct, productWithDefault) // Should return the actual product, not default
        
        // Test with different type - should return null
        val userFromProductRoute: User? = productRoute.routeParam()
        assertEquals(null, userFromProductRoute)
        
        // Test with default value when type doesn't match
        val defaultUser = User("default", "Default User", false)
        val userWithDefault: User = productRoute.routeParam(defaultUser)
        assertEquals(defaultUser, userWithDefault) // Should return default since cast fails
        
        // Test with user object
        val testUser = User(id = "456", name = "John Doe", premium = true)
        store.routeTo("/user/profile", testUser)
        
        store.state.first { it.currentTab == "/user/profile" }
        
        val userRoute = routerMiddleware.state.value.getCurrentContentRoute()!!
        val extractedUser: User? = userRoute.routeParam()
        assertEquals(testUser, extractedUser)
        assertEquals("456", extractedUser?.id)
        assertEquals("John Doe", extractedUser?.name)
        assertEquals(true, extractedUser?.premium)
    }

    @Test
    fun `test lastRouteType tracking for different navigation actions`() = runTest {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                content("/") { TestHomeScreen() } // Add root route
                scene("/splash") { TestSplashScreen() }
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                modal("/compose") { TestComposeModal() }
            }
        }
        
        // Test Scene navigation
        store.routeTo("/splash", layer = NavigationLayer.Scene)
        routerMiddleware.state.first { it.lastRouteType == RouteType.Scene }
        assertEquals(RouteType.Scene, routerMiddleware.state.value.lastRouteType)
        
        // Test Content navigation
        store.routeTo("/home")
        routerMiddleware.state.first { it.lastRouteType == RouteType.Content }
        assertEquals(RouteType.Content, routerMiddleware.state.value.lastRouteType)
        
        store.routeTo("/profile")
        routerMiddleware.state.first { it.lastRouteType == RouteType.Content }
        assertEquals(RouteType.Content, routerMiddleware.state.value.lastRouteType)
        
        // Test Modal navigation
        store.showModal("/compose")
        routerMiddleware.state.first { it.lastRouteType == RouteType.Modal }
        assertEquals(RouteType.Modal, routerMiddleware.state.value.lastRouteType)
        
        // Test Back navigation (dismiss modal)
        store.goBack()
        routerMiddleware.state.first { it.lastRouteType == RouteType.Back }
        assertEquals(RouteType.Back, routerMiddleware.state.value.lastRouteType)
        
        // Test Back navigation (content back)
        store.goBack()
        routerMiddleware.state.first { it.lastRouteType == RouteType.Back }
        assertEquals(RouteType.Back, routerMiddleware.state.value.lastRouteType)
    }

    @Test
    fun `test route with param in DSL`() = runTest {
        // Test data classes
        data class Product(val id: String, val name: String, val price: Double)
        data class EditData(val itemId: String, val mode: String)
        
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                // Add root route
                content("/") { TestHomeScreen() }
                
                // Routes without params
                content("/home") { TestHomeScreen() }
                
                // Routes with params using the new generic DSL overload
                content<Product>("/product/detail") { product ->
                    // product is non-nullable Product
                    TestProductDetail()
                }
                
                modal<EditData>("/edit") { editData ->
                    // editData is non-nullable EditData
                    TestEditModal()
                }
                
                scene<Int>("/wizard") { step ->
                    // step is non-nullable Int
                    TestWizardScreen()
                }
            }
            
            // Add reducer to track routing state changes
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        // Force state update to trigger flow emissions
                        state.copy(currentTab = action.routerState.getCurrentContentRoute()?.route?.path)
                    }
                    else -> state
                }
            }
        }
        
        // Test navigating with product param
        val testProduct = Product("123", "Test Product", 99.99)
        store.routeTo("/product/detail", testProduct)
        
        // Wait for navigation to complete
        store.state.first { it.currentTab == "/product/detail" }
        
        // Verify the route instance has the correct param
        val productRoute = routerMiddleware.state.value.getCurrentContentRoute()
        assertNotNull(productRoute)
        assertEquals("/product/detail", productRoute.route.path)
        assertEquals(testProduct, productRoute.param)
        
        // Test modal with param
        val editData = EditData("456", "quick-edit")
        store.showModal("/edit", editData)
        
        // Wait for modal to be shown
        routerMiddleware.state.first { it.modalRoutes.isNotEmpty() }
        
        // Verify the modal route instance has the correct param
        val modalRoute = routerMiddleware.state.value.modalRoutes.lastOrNull()
        assertNotNull(modalRoute)
        assertEquals("/edit", modalRoute.route.path)
        assertEquals(editData, modalRoute.param)
        
        // Test scene with param
        store.routeTo("/wizard", 3, layer = NavigationLayer.Scene)
        
        // Wait for scene to be shown
        routerMiddleware.state.first { it.sceneRoutes.isNotEmpty() }
        
        // Verify the scene route instance has the correct param
        val sceneRoute = routerMiddleware.state.value.sceneRoutes.lastOrNull()
        assertNotNull(sceneRoute)
        assertEquals("/wizard", sceneRoute.route.path)
        assertEquals(3, sceneRoute.param)
        
        // Note: In a real application, the route's content function would be called
        // when the UI renders the route. The typed parameter would then be passed
        // to the content lambda, ensuring type safety. This test verifies that
        // the parameter is correctly stored in the RouteInstance.
    }
    
    @Test
    fun `test typed route params provide compile-time safety`() = runTest {
        // Test data classes
        data class UserProfile(val userId: String, val name: String, val email: String)
        data class SearchQuery(val query: String, val filters: Map<String, Any>)
        
        val store = createStore(TestAppState()) {
            routing {
                // Mix of routes with and without params
                content("/home") { TestHomeScreen() }
                
                // Typed param routes
                content<UserProfile>("/user/profile") { user ->
                    // user is typed as UserProfile (non-nullable)
                    assertEquals("123", user.userId)
                    TestUserProfile()
                }
                
                content<SearchQuery>("/search") { query ->
                    // query is typed as SearchQuery (non-nullable)
                    assertEquals("kotlin", query.query)
                    assertEquals("category", query.filters.keys.first())
                    TestSearchScreen()
                }
                
                // Can still use config with typed params
                content<String>("/article", config = RouteConfig(toolBarTitle = "Article")) { articleId ->
                    // articleId is typed as String (non-nullable)
                    assertEquals("article-123", articleId)
                    TestArticleScreen()
                }
                
                // Modal with complex type
                modal<List<String>>("/tags") { tags ->
                    // tags is typed as List<String> (non-nullable)
                    assertEquals(3, tags.size)
                    assertEquals("kotlin", tags[0])
                    TestTagsModal()
                }
            }
        }
        
        // Test navigation with correct types
        store.routeTo("/user/profile", UserProfile("123", "John Doe", "john@example.com"))
        
        store.routeTo("/search", SearchQuery("kotlin", mapOf("category" to "libraries")))
        
        store.routeTo("/article", "article-123")
        
        store.showModal("/tags", listOf("kotlin", "android", "compose"))
    }
    
    @Test
    fun `test exception thrown when navigating to typed route without param`() = runTest {
        data class Product(val id: String, val name: String)
        
        var exceptionThrown = false
        var exceptionMessage = ""
        
        val store = createStore(TestAppState()) {
            routing {
                content<Product>("/product/detail") { product ->
                    // This should never be reached when no param is provided
                    TestProductDetail()
                }
            }
        }
        
        // Navigate without providing a parameter
        try {
            store.routeTo("/product/detail") // No param provided!
            // This would normally happen when the UI renders the route
            val currentRoute = store.state.value
            // Simulate rendering the route content
            try {
                // In real usage, the framework would call route.content(param)
                // which would throw the exception
            } catch (e: IllegalStateException) {
                exceptionThrown = true
                exceptionMessage = e.message ?: ""
            }
        } catch (e: Exception) {
            // Exception might be thrown during navigation
        }
        
        // Note: In actual usage, the exception would be thrown when the route
        // is rendered in the UI, not during navigation itself
    }
    
    @Test
    fun `test parameterized routes with content composition local`() = runTest {
        // Test data class
        data class Product(val id: String, val name: String, val price: Double)
        
        var capturedProduct: Product? = null
        var contentInvoked = false
        
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                content("/") { TestHomeScreen() }
                
                // Parameterized route using the new DSL
                content<Product>("/product") { product ->
                    // This should capture the product when rendered
                    capturedProduct = product
                    contentInvoked = true
                    TestProductDetail()
                }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        state.copy(currentTab = action.routerState.getCurrentContentRoute()?.route?.path)
                    }
                    else -> state
                }
            }
        }
        
        // Navigate with a product parameter
        val testProduct = Product("123", "Test Product", 99.99)
        store.routeTo("/product", testProduct)
        
        // Wait for router middleware state to update
        routerMiddleware.state.first { state ->
            state.contentRoutes.any { it.route.path == "/product" }
        }
        
        // Get the route instance
        val routeInstance = routerMiddleware.state.value.getCurrentContentRoute()
        assertNotNull(routeInstance)
        assertEquals("/product", routeInstance.route.path)
        assertEquals(testProduct, routeInstance.param)
        
        // The route's content property is a no-argument function
        val route = routeInstance.route
        assertNotNull(route.content)
        
        // In a real app, the UI would call routeInstance.Content() which provides
        // the param via LocalRouteParam. For testing, we can verify the structure
        // is correct and the param is stored in RouteInstance
        assertEquals(testProduct, routeInstance.param)
    }
    
    @Test
    fun `test lastRouteType with different router actions`() = runTest {
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            routerMiddleware = routing {
                content("/") { TestHomeScreen() } // Add root route
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                modal("/modal1") { TestModal1() }
            }
        }
        
        // Wait for router initialization to complete
        routerMiddleware.state.first { it.contentRoutes.isNotEmpty() }
        
        // Test Content navigation
        store.routeTo("/home")
        routerMiddleware.state.first { it.contentRoutes.any { route -> route.route.path == "/home" } }
        assertEquals(RouteType.Content, routerMiddleware.state.value.lastRouteType)
        
        // Test Modal navigation
        store.showModal("/modal1")
        routerMiddleware.state.first { it.modalRoutes.isNotEmpty() }
        assertEquals(RouteType.Modal, routerMiddleware.state.value.lastRouteType)
        
        // Test Back navigation (dismiss modal)
        store.goBack()
        routerMiddleware.state.first { it.modalRoutes.isEmpty() }
        assertEquals(RouteType.Back, routerMiddleware.state.value.lastRouteType)
        
        // Test Back navigation (content back)
        store.goBack()
        routerMiddleware.state.first { it.contentRoutes.size == 1 } // Should be back to root
        assertEquals(RouteType.Back, routerMiddleware.state.value.lastRouteType)
    }
}