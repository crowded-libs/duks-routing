package duks.routing

import duks.Action

/** Actions that drive navigation. Applied by the auto-registered router reducer. */
sealed class Routing : Action {
    data class NavigateTo(
        val path: String,
        val layer: NavigationLayer? = null,
        val param: Any? = null,
        val clearHistory: Boolean = false,
        val mode: NavigationMode = NavigationMode.Push
    ) : Routing()

    data class ReplaceContent(
        val path: String,
        val param: Any? = null
    ) : Routing()

    data object GoBack : Routing()
    data class PopToPath(val path: String) : Routing()
    data class ClearLayer(val layer: NavigationLayer) : Routing()
    data class ShowModal(val path: String, val param: Any? = null) : Routing()
    data class DismissModal(val path: String? = null) : Routing()
    data class DeepLink(val url: String) : Routing()

    /**
     * Recompute [RouterState.enabledFeatures] from the current app state.
     * Emitted by middleware after non-routing actions when feature re-evaluation is enabled.
     */
    data object SyncFeatures : Routing()
}
