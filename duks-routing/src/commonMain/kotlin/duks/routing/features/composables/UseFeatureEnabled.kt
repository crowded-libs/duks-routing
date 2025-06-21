package duks.routing.features.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import duks.KStore
import duks.routing.HasRouterState
import duks.routing.features.isFeatureEnabled

/**
 * A composable hook that returns whether a feature is enabled.
 * 
 * @param store The store containing the application state
 * @param featureName The name of the feature to check
 * @return true if the feature is enabled, false otherwise
 */
@Composable
fun <TState : HasRouterState> useFeatureEnabled(
    store: KStore<TState>,
    featureName: String
): Boolean {
    val state by store.state.collectAsState()
    return state.isFeatureEnabled(featureName)
}