package duks.routing

import duks.StateModel

/**
 * App state that includes [RouterState].
 *
 * When you call `routing { }` on the store builder, the library registers a reducer that
 * updates [routerState] via [withRouterState]. You do **not** handle routing actions in
 * your own reducer — read stacks from [routerState] on the store.
 *
 * ```kotlin
 * data class AppState(
 *     val user: User? = null,
 *     override val routerState: RouterState = RouterState()
 * ) : HasRouterState {
 *     override fun withRouterState(routerState: RouterState) = copy(routerState = routerState)
 * }
 * ```
 */
interface HasRouterState : StateModel {
    val routerState: RouterState

    /**
     * Return a copy of this state with [routerState] replaced.
     * For data classes: `copy(routerState = routerState)`.
     */
    fun withRouterState(routerState: RouterState): HasRouterState
}
