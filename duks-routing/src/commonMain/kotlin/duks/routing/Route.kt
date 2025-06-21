package duks.routing

import androidx.compose.runtime.Composable

// Route definition with layer and conditions
data class Route<out T>(
    val path: String,
    val layer: NavigationLayer = NavigationLayer.Content,
    val requiresAuth: Boolean = false,
    val content: @Composable () -> Unit,
    val config: T? = null,
    val renderConditions: List<RenderCondition> = emptyList(),
    val requiredFeature: String? = null
)