package duks.routing

import duks.Action

/**
 * Observes router state transitions after they are committed to the store.
 *
 * Invoked when [HasRouterState.routerState] changes (reducer-owned stacks). Safe to read
 * `store.state` for the updated app state when using async dispatch completion paths.
 */
fun interface NavigationListener {
    /**
     * @param previous Router state before the mutation
     * @param current Router state after the mutation
     * @param action The action that caused the change
     */
    fun onRouterStateChanged(previous: RouterState, current: RouterState, action: Action)
}
