package duks.routing

import duks.Action

// Routing actions
sealed class Routing : Action {
    data class NavigateTo(
        val path: String,
        val layer: NavigationLayer? = null,
        val preserveNavigation: Boolean = true,
        val param: Any? = null,
        val clearHistory: Boolean = false
    ) : Routing()

    data class ReplaceContent(
        val path: String,
        val param: Any? = null
    ) : Routing()

    object GoBack : Routing()
    data class PopToPath(val path: String) : Routing()
    data class ClearLayer(val layer: NavigationLayer) : Routing()
    data class ShowModal(val path: String, val param: Any? = null) : Routing()
    data class DismissModal(val path: String? = null) : Routing()
    data class DeepLink(val url: String) : Routing()

    // State change notification for app
    data class StateChanged(val routerState: RouterState) : Routing()
}