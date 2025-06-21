package duks.routing.features

import duks.routing.HasRouterState

/**
 * Extension method to check if a feature is enabled.
 * This uses the cached enabled features in the RouterState.
 * 
 * @param featureName The name of the feature to check
 * @return true if the feature is enabled, false otherwise
 */
fun HasRouterState.isFeatureEnabled(featureName: String): Boolean {
    return routerState.enabledFeatures.contains(featureName)
}

/**
 * Extension method to check if all specified features are enabled.
 * 
 * @param featureNames The names of the features to check
 * @return true if all features are enabled, false otherwise
 */
fun HasRouterState.areAllFeaturesEnabled(vararg featureNames: String): Boolean {
    return featureNames.all { routerState.enabledFeatures.contains(it) }
}

/**
 * Extension method to check if any of the specified features are enabled.
 * 
 * @param featureNames The names of the features to check
 * @return true if any feature is enabled, false otherwise
 */
fun HasRouterState.isAnyFeatureEnabled(vararg featureNames: String): Boolean {
    return featureNames.any { routerState.enabledFeatures.contains(it) }
}