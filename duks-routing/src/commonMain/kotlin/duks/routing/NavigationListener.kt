package duks.routing

import duks.Action

/**
 * Observes committed router state transitions for analytics, logging, or side effects.
 *
 * Invoked after middleware [RouterState] is updated and [Routing.StateChanged] has been
 * published to the store, so app state that reduces on [Routing.StateChanged] is already
 * in sync when the listener runs.
 */
fun interface NavigationListener {
    /**
     * @param previous Router state before the mutation
     * @param current Router state after the mutation
     * @param action The action that caused the change (never [Routing.StateChanged] itself)
     */
    fun onRouterStateChanged(previous: RouterState, current: RouterState, action: Action)
}
