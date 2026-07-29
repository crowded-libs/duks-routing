package duks.routing

import duks.KStore
import duks.StateModel

/**
 * Authentication configuration for protected routes.
 *
 * @param authChecker Returns true when the current state is considered authenticated
 * @param unauthenticatedRoute Path to navigate to when auth is required but missing
 * @param onAuthFailure Optional callback when navigation to a protected route fails auth
 * @param revalidateOnSessionLoss When true, if [authChecker] transitions from true to false
 * while protected routes are active, those routes are cleared and the unauthenticated route
 * is applied (same effect as navigating there with a cleared history)
 */
data class AuthConfig<TState: StateModel>(
    val authChecker: (TState) -> Boolean,
    val unauthenticatedRoute: String = "/login",
    val onAuthFailure: ((KStore<TState>, Route<*>) -> Unit)? = null,
    val revalidateOnSessionLoss: Boolean = true
)
