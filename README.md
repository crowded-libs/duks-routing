# Duks Routing

A Kotlin Multiplatform routing library with advanced features for managing navigation in compose-based applications.

[![Build](https://github.com/crowded-libs/duks-routing/actions/workflows/build.yml/badge.svg)](https://github.com/crowded-libs/duks-routing/actions/workflows/build.yml)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.21-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-v1.8.1-blue)](https://github.com/JetBrains/compose-multiplatform)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.crowded-libs/duks-routing.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.crowded-libs%22%20AND%20a:%22duks-routing%22)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)


## Features

- 🎯 **Multi-layer Navigation**: Support for scenes, content, and modal layers
- 🔒 **Authentication Support**: Built-in route protection with auth requirements
- 📱 **Responsive Design**: Device-aware routing with render conditions
- 💾 **State Restoration**: Automatic state persistence and restoration
- 🎛️ **Feature Toggles**: Advanced feature flagging for both conditional view rendering and components within
- 🧩 **Type-safe Parameters**: Pass typed parameters between routes

## Installation

```kotlin
dependencies {
    implementation("io.crowded-innovations:duks-routing:0.1.1")
}
```

## Quick Start

```kotlin
// Define your app state
@Serializable
data class AppState(
    val user: User? = null,
    override val routerState: RouterState = RouterState()
) : HasRouterState

// Create your store with routing
val store = createStore<AppState>(AppState()) {
    routing {
        // Set initial route
        initialRoute("/home")
        
        // Define routes
        content("/home") {
            HomeScreen()
        }
        
        content("/profile", requiresAuth = true) {
            ProfileScreen()
        }
        
        modal("/settings") {
            SettingsModal()
        }
    }
}
```

## Navigation Layers

The library supports three distinct navigation layers:

### Scene Routes
Full screen replacements, typically for major navigation changes:
```kotlin
scene("/onboarding") {
    OnboardingFlow()
}
```

### Content Routes  
Standard navigation within the main content area:
```kotlin
content("/products") {
    ProductListScreen()
}
```

### Modal Routes
Overlay content that appears above the current screen:
```kotlin
modal("/filter") {
    FilterModal()
}
```

## Authentication

Protect routes with authentication requirements:

```kotlin
routing(
    authConfig = AuthConfig(
        authChecker = { state -> state.user != null },
        unauthenticatedRoute = "/login"
    )
) {
    content("/profile", requiresAuth = true) {
        ProfileScreen()
    }
}
```

## Responsive Routing

Define different routes based on device characteristics:

```kotlin
content("/dashboard") {
    whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Desktop, DeviceClass.Tablet))
    DesktopDashboard()
}

content("/dashboard") {
    whenCondition = RenderCondition.DeviceType(setOf(DeviceClass.Phone))
    MobileDashboard()
}
```

## Feature Toggles

The routing library includes a powerful feature toggle system that enables:
- Different routes for different user types (e.g., premium vs free users)
- A/B testing with feature flags
- Remote configuration support
- Preview-friendly API design

### Setting Up Feature Toggles

1. **Implement a Feature Evaluator**:
```kotlin
class AppFeatureEvaluator : FeatureToggleEvaluator {
    override fun <TState : StateModel> isFeatureEnabled(
        state: TState, 
        featureName: String
    ): Boolean {
        val appState = state as AppState
        
        // Check remote config first
        appState.remoteFeatures[featureName]?.let { return it }
        
        // Local feature logic
        return when (featureName) {
            "premium_user" -> appState.user?.isPremium == true
            "beta_features" -> appState.user?.isBetaTester == true
            else -> false
        }
    }
}
```

2. **Configure Feature-Based Routes**:
```kotlin
routing {
    // Configure evaluator
    featureToggles(AppFeatureEvaluator())
    
    // Premium users see ad-free experience
    scene("/movies", requiredFeature = "premium_user") {
        PremiumMoviesScreen()
    }
    
    // Free users see ads
    scene("/movies", requiredFeature = "free_tier") {
        MoviesWithAdsScreen()
    }
}
```

3. **Use Feature Checks in UI**:
```kotlin
// Direct state access
if (state.isFeatureEnabled("premium_features")) {
    ShowPremiumContent()
}

// In composables with FeatureGate
@Composable
fun MovieScreen(store: KStore<AppState>) {
    FeatureGate(store, "download_feature") {
        DownloadButton()
    }
    
    // With fallback
    FeatureGate(
        store = store,
        featureName = "hd_streaming",
        fallback = { StandardPlayer() }
    ) {
        HDPlayer()
    }
}
```

### Feature Toggle Benefits

- **Preview-Friendly**: No composition locals needed, works great with Compose previews
- **Type-Safe**: Full Kotlin type safety with compile-time checks
- **Performance**: Features are cached in RouterState and only re-evaluated on state changes
- **Flexible**: Supports any evaluation logic including remote config, user properties, or experiments

## State Restoration

Configure how router state is restored:

```kotlin
routing {
    restoration {
        // Restore all routes (default)
        restoreAll()
        
        // Or restore only modals
        restoreOnly(NavigationLayer.Modal)
        
        // Or use conditional defaults
        restoreWithDefaults {
            when {
                state.user == null -> "/login"
                state.user.isOnboarded -> "/home"
                else -> "/onboarding"
            }
        }
    }
}
```

## Navigation Actions

```kotlin
// Navigate to a route
store.routeTo("/products")

// Navigate with parameters
store.routeTo("/product/details", param = Product(id = "123"))

// Go back
store.goBack()

// Show modal
store.showModal("/filter", param = FilterOptions())

// Dismiss modal
store.dismissModal()

// Pop to specific route
store.popToRoute("/home")
```