package duks.routing

import duks.Action
import duks.StateModel

/**
 * Interface for states that include [RouterState] for serialization/restoration.
 *
 * ## Dual-state contract
 *
 * [RouterMiddleware] keeps an authoritative [RouterMiddleware.state] and, on every mutation,
 * dispatches [Routing.StateChanged] (via `dispatchAsync`) so apps can mirror it into
 * [routerState]. Your root reducer **must** apply that action, for example:
 *
 * ```kotlin
 * is Routing.StateChanged -> state.copy(routerState = action.routerState)
 * // or:
 * else -> state.applyRouterStateChanged(action) { copy(routerState = it) }
 * ```
 *
 * After each publish, app [routerState] and middleware [RouterMiddleware.state] should match
 * for routes, device context, and [RouterState.enabledFeatures].
 */
interface HasRouterState : StateModel {
    val routerState: RouterState
}

/**
 * Applies [Routing.StateChanged] into a [HasRouterState] model using [update].
 * Other actions leave [this] unchanged.
 */
inline fun <T : HasRouterState> T.applyRouterStateChanged(
    action: Action,
    update: T.(RouterState) -> T
): T = if (action is Routing.StateChanged) update(action.routerState) else this
