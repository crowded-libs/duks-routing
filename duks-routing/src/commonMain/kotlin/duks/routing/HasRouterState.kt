package duks.routing

import duks.StateModel

/**
 * Interface for states that include RouterState for serialization/restoration.
 * States implementing this interface will have their router state automatically
 * restored when RestoreStateAction is dispatched.
 */
interface HasRouterState : StateModel {
    val routerState: RouterState
}