package duks.routing.features

import duks.StateModel

/**
 * Interface for evaluating feature toggles against application state.
 * Applications implement this to define their own feature evaluation logic.
 */
interface FeatureToggleEvaluator {
    /**
     * Evaluates whether a feature is enabled for the current state.
     * The generic type parameter allows for type-safe state access while
     * keeping the interface itself non-generic for easier storage and passing.
     * 
     * @param state The current application state
     * @param featureName The name of the feature to check
     * @return true if the feature is enabled, false otherwise
     */
    fun <TState : StateModel> isFeatureEnabled(state: TState, featureName: String): Boolean
}