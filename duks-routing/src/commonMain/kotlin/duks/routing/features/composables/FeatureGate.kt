package duks.routing.features.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import duks.KStore
import duks.routing.HasRouterState
import duks.routing.features.isFeatureEnabled

/**
 * A composable that conditionally renders content based on whether a feature is enabled.
 * 
 * @param store The store containing the application state
 * @param featureName The name of the feature to check
 * @param fallback Optional composable to render when the feature is disabled
 * @param content The content to render when the feature is enabled
 */
@Composable
fun <TState : HasRouterState> FeatureGate(
    store: KStore<TState>,
    featureName: String,
    fallback: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val state by store.state.collectAsState()
    val isEnabled = state.isFeatureEnabled(featureName)
    
    if (isEnabled) {
        content()
    } else {
        fallback?.invoke()
    }
}