package duks.routing.features

import duks.StateModel
import kotlin.test.*

class FeatureToggleEvaluatorTest {
    
    data class TestAppState(
        val userRole: String = "user",
        val isPremium: Boolean = false,
        val experimentFlags: Set<String> = emptySet()
    ) : StateModel
    
    class RoleBasedFeatureEvaluator : FeatureToggleEvaluator {
        override fun <TState : StateModel> isFeatureEnabled(state: TState, featureName: String): Boolean {
            return when (state) {
                is TestAppState -> {
                    when (featureName) {
                        "admin-panel" -> state.userRole == "admin"
                        "moderator-tools" -> state.userRole in listOf("admin", "moderator")
                        "premium-features" -> state.isPremium
                        else -> false
                    }
                }
                else -> false
            }
        }
    }
    
    class ExperimentBasedFeatureEvaluator : FeatureToggleEvaluator {
        override fun <TState : StateModel> isFeatureEnabled(state: TState, featureName: String): Boolean {
            return when (state) {
                is TestAppState -> state.experimentFlags.contains(featureName)
                else -> false
            }
        }
    }
    
    class CompositeFeatureEvaluator(
        private val evaluators: List<FeatureToggleEvaluator>
    ) : FeatureToggleEvaluator {
        override fun <TState : StateModel> isFeatureEnabled(state: TState, featureName: String): Boolean {
            return evaluators.any { it.isFeatureEnabled(state, featureName) }
        }
    }
    
    @Test
    fun `RoleBasedFeatureEvaluator evaluates admin features correctly`() {
        val evaluator = RoleBasedFeatureEvaluator()
        
        val adminState = TestAppState(userRole = "admin")
        assertTrue(evaluator.isFeatureEnabled(adminState, "admin-panel"))
        assertTrue(evaluator.isFeatureEnabled(adminState, "moderator-tools"))
        
        val userState = TestAppState(userRole = "user")
        assertFalse(evaluator.isFeatureEnabled(userState, "admin-panel"))
        assertFalse(evaluator.isFeatureEnabled(userState, "moderator-tools"))
    }
    
    @Test
    fun `RoleBasedFeatureEvaluator evaluates moderator features correctly`() {
        val evaluator = RoleBasedFeatureEvaluator()
        
        val moderatorState = TestAppState(userRole = "moderator")
        assertFalse(evaluator.isFeatureEnabled(moderatorState, "admin-panel"))
        assertTrue(evaluator.isFeatureEnabled(moderatorState, "moderator-tools"))
    }
    
    @Test
    fun `RoleBasedFeatureEvaluator evaluates premium features correctly`() {
        val evaluator = RoleBasedFeatureEvaluator()
        
        val premiumState = TestAppState(isPremium = true)
        assertTrue(evaluator.isFeatureEnabled(premiumState, "premium-features"))
        
        val freeState = TestAppState(isPremium = false)
        assertFalse(evaluator.isFeatureEnabled(freeState, "premium-features"))
    }
    
    @Test
    fun `ExperimentBasedFeatureEvaluator evaluates experiment flags correctly`() {
        val evaluator = ExperimentBasedFeatureEvaluator()
        
        val stateWithExperiments = TestAppState(
            experimentFlags = setOf("new-ui", "beta-feature", "test-experiment")
        )
        
        assertTrue(evaluator.isFeatureEnabled(stateWithExperiments, "new-ui"))
        assertTrue(evaluator.isFeatureEnabled(stateWithExperiments, "beta-feature"))
        assertFalse(evaluator.isFeatureEnabled(stateWithExperiments, "disabled-experiment"))
    }
    
    @Test
    fun `CompositeFeatureEvaluator combines multiple evaluators correctly`() {
        val roleEvaluator = RoleBasedFeatureEvaluator()
        val experimentEvaluator = ExperimentBasedFeatureEvaluator()
        val compositeEvaluator = CompositeFeatureEvaluator(listOf(roleEvaluator, experimentEvaluator))
        
        val state = TestAppState(
            userRole = "admin",
            isPremium = false,
            experimentFlags = setOf("new-ui")
        )
        
        assertTrue(compositeEvaluator.isFeatureEnabled(state, "admin-panel"))
        assertTrue(compositeEvaluator.isFeatureEnabled(state, "new-ui"))
        assertFalse(compositeEvaluator.isFeatureEnabled(state, "premium-features"))
        assertFalse(compositeEvaluator.isFeatureEnabled(state, "unknown-feature"))
    }
    
    @Test
    fun `FeatureToggleEvaluator handles unknown state types gracefully`() {
        val evaluator = RoleBasedFeatureEvaluator()
        
        data class UnknownState(val data: String) : StateModel
        val unknownState = UnknownState("test")
        
        assertFalse(evaluator.isFeatureEnabled(unknownState, "admin-panel"))
        assertFalse(evaluator.isFeatureEnabled(unknownState, "any-feature"))
    }
    
    @Test
    fun `FeatureToggleEvaluator can use generic type parameter for type safety`() {
        class TypeSafeEvaluator<T : StateModel> : FeatureToggleEvaluator {
            override fun <TState : StateModel> isFeatureEnabled(state: TState, featureName: String): Boolean {
                return when (state) {
                    is TestAppState -> {
                        featureName == "type-safe-feature" && state.userRole == "admin"
                    }
                    else -> false
                }
            }
        }
        
        val evaluator = TypeSafeEvaluator<TestAppState>()
        val state = TestAppState(userRole = "admin")
        
        assertTrue(evaluator.isFeatureEnabled(state, "type-safe-feature"))
    }
}