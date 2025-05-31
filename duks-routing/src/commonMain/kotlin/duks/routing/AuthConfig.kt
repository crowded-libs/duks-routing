package duks.routing

import duks.KStore
import duks.StateModel

data class AuthConfig<TState: StateModel>(
    val authChecker: (TState) -> Boolean,
    val unauthenticatedRoute: String = "/login",
    val onAuthFailure: ((KStore<TState>, Route<*>) -> Unit)? = null
)