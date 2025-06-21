package duks.routing.features

import duks.routing.RouterState
import duks.routing.HasRouterState
import kotlin.test.*

class FeatureToggleExtensionsTest {
    
    class TestHasRouterState(override val routerState: RouterState) : HasRouterState
    
    @Test
    fun `isFeatureEnabled returns true when feature is in enabled features set`() {
        val routerState = RouterState(
            enabledFeatures = setOf("feature1", "feature2", "feature3")
        )
        val hasRouterState = TestHasRouterState(routerState)
        
        assertTrue(hasRouterState.isFeatureEnabled("feature1"))
        assertTrue(hasRouterState.isFeatureEnabled("feature2"))
        assertTrue(hasRouterState.isFeatureEnabled("feature3"))
    }
    
    @Test
    fun `isFeatureEnabled returns false when feature is not in enabled features set`() {
        val routerState = RouterState(
            enabledFeatures = setOf("feature1", "feature2")
        )
        val hasRouterState = TestHasRouterState(routerState)
        
        assertFalse(hasRouterState.isFeatureEnabled("feature3"))
        assertFalse(hasRouterState.isFeatureEnabled("nonexistent"))
    }
    
    @Test
    fun `isFeatureEnabled returns false when enabled features set is empty`() {
        val routerState = RouterState(
            enabledFeatures = emptySet()
        )
        val hasRouterState = TestHasRouterState(routerState)
        
        assertFalse(hasRouterState.isFeatureEnabled("anyFeature"))
    }
    
    @Test
    fun `areAllFeaturesEnabled returns true when all features are enabled`() {
        val routerState = RouterState(
            enabledFeatures = setOf("feature1", "feature2", "feature3", "feature4")
        )
        val hasRouterState = TestHasRouterState(routerState)
        
        assertTrue(hasRouterState.areAllFeaturesEnabled("feature1", "feature2"))
        assertTrue(hasRouterState.areAllFeaturesEnabled("feature1", "feature2", "feature3"))
        assertTrue(hasRouterState.areAllFeaturesEnabled("feature4"))
    }
    
    @Test
    fun `areAllFeaturesEnabled returns false when any feature is not enabled`() {
        val routerState = RouterState(
            enabledFeatures = setOf("feature1", "feature2")
        )
        val hasRouterState = TestHasRouterState(routerState)
        
        assertFalse(hasRouterState.areAllFeaturesEnabled("feature1", "feature3"))
        assertFalse(hasRouterState.areAllFeaturesEnabled("feature1", "feature2", "feature3"))
        assertFalse(hasRouterState.areAllFeaturesEnabled("nonexistent"))
    }
    
    @Test
    fun `areAllFeaturesEnabled returns true when called with no features`() {
        val routerState = RouterState(
            enabledFeatures = setOf("feature1")
        )
        val hasRouterState = TestHasRouterState(routerState)
        
        assertTrue(hasRouterState.areAllFeaturesEnabled())
    }
    
    @Test
    fun `isAnyFeatureEnabled returns true when at least one feature is enabled`() {
        val routerState = RouterState(
            enabledFeatures = setOf("feature1", "feature2")
        )
        val hasRouterState = TestHasRouterState(routerState)
        
        assertTrue(hasRouterState.isAnyFeatureEnabled("feature1", "feature3"))
        assertTrue(hasRouterState.isAnyFeatureEnabled("feature3", "feature2"))
        assertTrue(hasRouterState.isAnyFeatureEnabled("feature1", "feature2", "feature3"))
    }
    
    @Test
    fun `isAnyFeatureEnabled returns false when no features are enabled`() {
        val routerState = RouterState(
            enabledFeatures = setOf("feature1", "feature2")
        )
        val hasRouterState = TestHasRouterState(routerState)
        
        assertFalse(hasRouterState.isAnyFeatureEnabled("feature3", "feature4"))
        assertFalse(hasRouterState.isAnyFeatureEnabled("nonexistent"))
    }
    
    @Test
    fun `isAnyFeatureEnabled returns false when called with no features`() {
        val routerState = RouterState(
            enabledFeatures = setOf("feature1")
        )
        val hasRouterState = TestHasRouterState(routerState)
        
        assertFalse(hasRouterState.isAnyFeatureEnabled())
    }
    
    @Test
    fun `feature extensions handle case sensitivity correctly`() {
        val routerState = RouterState(
            enabledFeatures = setOf("Feature1", "FEATURE2", "feature3")
        )
        val hasRouterState = TestHasRouterState(routerState)
        
        assertTrue(hasRouterState.isFeatureEnabled("Feature1"))
        assertFalse(hasRouterState.isFeatureEnabled("feature1"))
        
        assertTrue(hasRouterState.isFeatureEnabled("FEATURE2"))
        assertFalse(hasRouterState.isFeatureEnabled("feature2"))
        
        assertTrue(hasRouterState.isFeatureEnabled("feature3"))
        assertFalse(hasRouterState.isFeatureEnabled("Feature3"))
    }
}