package duks.routing

/**
 * How a navigation request updates the route stacks.
 *
 * @property Push Append to the target layer stack (default). Scene navigations still clear
 *   content and modal stacks so overlays do not orphan above a new scene.
 * @property SingleTop If the top entry of the target layer already has the same path, replace
 *   that entry (e.g. refresh params) instead of stacking another copy; otherwise [Push].
 * @property ReplaceLayer Replace the entire target layer stack with the destination route only.
 *   For [NavigationLayer.Scene], also clears content and modals (tab-root style).
 *   For [NavigationLayer.Content], clears modals but keeps the scene stack.
 *   For [NavigationLayer.Modal], replaces the modal stack only.
 * @property ClearHistory Clear all layers and place the destination as the sole route
 *   (logout / post-login reset). Same effect as `clearHistory = true` on navigate.
 */
enum class NavigationMode {
    Push,
    SingleTop,
    ReplaceLayer,
    ClearHistory
}
