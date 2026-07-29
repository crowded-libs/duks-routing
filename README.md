# duks-routing

Kotlin Multiplatform routing for Compose apps built on [duks](https://github.com/crowded-libs/duks).

[![Build](https://github.com/crowded-libs/duks-routing/actions/workflows/build.yml/badge.svg)](https://github.com/crowded-libs/duks-routing/actions/workflows/build.yml)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-v1.11.1-blue)](https://github.com/JetBrains/compose-multiplatform)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.crowded-libs/duks-routing.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.crowded-libs%22%20AND%20a:%22duks-routing%22)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Requires **duks 0.4.0**. Targets: JVM, Android, iOS, wasmJs.

## Install

```kotlin
dependencies {
    implementation("io.github.crowded-libs:duks-routing:0.3.0")
}
```

## How it works

1. App state implements `HasRouterState` and `withRouterState`.
2. `StoreBuilder.routing { }` registers **middleware** and a **router reducer**.
3. Navigation actions (`routeTo`, `goBack`, …) update `state.routerState` through that reducer.
4. UI reads `store.state` / `routerState` (or `RouterMiddleware.state`, which mirrors the same stack).

Domain reducers should not handle routing actions.

## Quick start

```kotlin
@Serializable
data class AppState(
    val user: User? = null,
    override val routerState: RouterState = RouterState()
) : HasRouterState {
    override fun withRouterState(routerState: RouterState) = copy(routerState = routerState)
}

val store = createStore(AppState()) {
    routing(
        authConfig = AuthConfig(
            authChecker = { it.user != null },
            unauthenticatedRoute = "/login"
        )
    ) {
        initialRoute("/home")
        content("/home") { HomeScreen() }
        content("/login") { LoginScreen() }
        content("/profile", requiresAuth = true) { ProfileScreen() }
        modal("/settings") { SettingsModal() }
    }
    reduceWith(::appReduce)
}
```

Render the current route, for example:

```kotlin
val route by store.state.mapToPropsAsState { routerState.primaryRoute() }
route?.Content()
```

Use `DeviceContextProvider(store) { … }` so device-based `RenderCondition`s receive size/orientation updates.

## Layers

| Layer | DSL | Behavior |
|---|---|---|
| Scene | `scene(path) { }` | Full-screen stack. Navigating to a scene clears content and modal stacks. |
| Content | `content(path) { }` | Content stack (e.g. main chrome). |
| Modal | `modal(path) { }` | Overlay stack. |

Optional `config` on a route is available as `RouteInstance.config` / `RouterState.primaryConfig<T>()`.

Groups share auth/config/prefix:

```kotlin
group(requiresAuth = true, config = ScaffoldConfig(showBack = true)) {
    content("/account") { AccountScreen() }
    content("/billing") { BillingScreen() }
}
```

## Navigation

```kotlin
store.routeTo("/products")
store.routeTo("/product", param = productId)
store.routeTo("/login", clearHistory = true)          // same as mode = ClearHistory
store.routeTo("/item", param = id, mode = NavigationMode.SingleTop)
store.switchScene("/home")                             // scene ReplaceLayer; clears content + modals
store.goBack()                                         // no-op at root; does not set lastRouteType
store.showModal("/filter", param = options)
store.dismissModal()
store.popToRoute("/home")                              // modal → content → scene
store.dispatch(Routing.DeepLink("myapp://host/item/42"))
```

### Navigation modes

| Mode | Behavior |
|---|---|
| `Push` (default) | Append on the target layer. Scene still clears content + modals. |
| `SingleTop` | If the top of that layer has the same path, replace it; otherwise push. |
| `ReplaceLayer` | Replace that layer only. Scene also clears content + modals. Content clears modals and keeps scenes. |
| `ClearHistory` | Clear all layers; destination alone. |

### Router state helpers

```kotlin
routerState.primaryRoute()     // last content, else last scene
routerState.canGoBack()
routerState.primaryConfig<MyConfig>()
route.pathEquals("/home")      // normalized path equality
```

### Auth

- `requiresAuth = true` on a route: unauthenticated navigation is redirected to `AuthConfig.unauthenticatedRoute` (that route’s layer).
- `onAuthFailure` is invoked when a protected route is blocked.
- `revalidateOnSessionLoss` (default `true`): if auth goes true → false while protected routes are active, navigates to the unauthenticated route with history cleared.

## Path templates and params

```kotlin
content("/item/{id}") {
    val id = routeParam<String>()
    ItemScreen(id)
}

store.routeTo("/item/42")  // param = "42"
```

Multiple path segments become a `Map<String, String>` when no explicit `param` is passed.

Typed DSL:

```kotlin
content<String>("/item/{id}") { id -> ItemScreen(id) }
modal<FilterOptions>("/filter") { options -> FilterModal(options) }
```

## Persistence of params

When `RouterState` is serialized (e.g. with app state), params are encoded if a codec exists:

- Built-in: `String`, `Int`, `Long`, `Boolean`, `Float`, `Double`
- Custom: register a `@Serializable` type

```kotlin
routing {
    registerParamSerializer<ProductId>()
    // …
}
// or: RouteParamRegistry.Default.register<ProductId>()
```

Unregistered / non-serializable params: path is kept, param is dropped on serialize.

## Restoration

With duks persistence, restored `routerState` is rehydrated against the route table (paths → live `Route` content). Configure in the DSL:

```kotlin
routing {
    restoration {
        restoreAll()  // default base
        // restoreOnly(RouteType.Scene, RouteType.Content)
        // restoreSpecific { scenes("/home"); content("/list") }

        conditionalDefaults(mode = ConditionalDefaultsMode.OnlyIfEmpty) {
            `when` { it.user == null } then "/login"
            `when` { it.user?.isOnboarded == true } then "/home"
            otherwise("/onboarding")
        }
    }
}
```

| Mode | When defaults apply |
|---|---|
| `OverrideAlways` (default) | Matching condition (or `otherwise`) replaces restored stacks |
| `OnlyIfEmpty` | Only if the restored stack is empty |
| `OnlyIfInvalid` | If empty, or any restored path is not a registered route |

## Device conditions

```kotlin
content(
    "/dashboard",
    whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Desktop, DeviceClass.Tablet))
) {
    DesktopDashboard()
}

content(
    "/dashboard",
    whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Phone))
) {
    MobileDashboard()
}
```

Also: `RenderCondition.ScreenSize`, `Orientation`, `Custom`, `FeatureEnabled`, and `and` / `or`.

Device class breakpoints (smallest edge, prefer dp): watch ≤320, phone &lt;600, tablet &lt;900, else desktop — see `DeviceClassHeuristics`.

## Feature toggles

Implement `FeatureToggleEvaluator` and pass it to `featureToggles(...)`. Routes can require a feature:

```kotlin
routing {
    featureToggles(AppFeatureEvaluator())  // reevaluateOnAppStateChange = true by default

    content("/beta", requiredFeature = "beta_access") {
        BetaScreen()
    }
}
```

`RouterState.enabledFeatures` is the set of feature names declared as `requiredFeature` on routes that currently evaluate to true. It is updated on navigation/device changes and, by default, after other actions when that set would change.

UI helpers (they read `enabledFeatures`, so the feature name must appear as some route’s `requiredFeature` if you rely on the cache):

```kotlin
if (state.isFeatureEnabled("beta_access")) { /* … */ }

FeatureGate(store, "beta_access", fallback = { LockedScreen() }) {
    BetaScreen()
}
```

## Navigation listeners

```kotlin
routing {
    onNavigation { previous, current, action ->
        // analytics, logging
    }
    // routes…
}
```

Called after the store has committed a new `routerState`.

## Middleware order (duks)

Recommended order matches duks docs: exception handling → logging/cache → **persistence** → **routing** → sagas → async. Call `routing { }` so it sits with domain middleware after persistence.

## License

Apache 2.0
