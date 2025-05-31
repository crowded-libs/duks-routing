package duks.routing

// Navigation layers define where routes render
enum class NavigationLayer {
    Scene,       // Full screen replacement
    Content,     // Content area only (preserves nav chrome)
    Modal        // Overlay on current state
}