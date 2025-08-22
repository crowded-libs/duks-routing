package duks.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import duks.KStore
import duks.StateModel

/**
 * Provides device context updates to the router.
 * This component monitors dimension changes and automatically dispatches
 * DeviceAction updates to keep the router's device context synchronized.
 * 
 * For all platforms:
 * - Automatically recomposes when screen orientation changes
 * - Automatically recomposes when window is resized (desktop)
 * - Automatically recomposes when device is rotated
 * 
 * Usage:
 * ```
 * @Composable
 * fun App() {
 *     val store = remember { createStore(...) }
 *     
 *     DeviceContextProvider(store) {
 *         // Your app content
 *     }
 * }
 * ```
 */
@Composable
fun <TState: StateModel> DeviceContextProvider(
    store: KStore<TState>,
    customProperties: Map<String, String> = emptyMap(),
    content: @Composable () -> Unit
) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    
    // Convert to dp for consistent measurement across devices
    val widthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val heightDp = with(density) { windowInfo.containerSize.height.toDp() }
    
    // Use smallest dimension to determine device type (consistent regardless of orientation)
    // This is similar to Android's smallestScreenWidthDp
    val smallestDimensionDp = minOf(widthDp, heightDp)
    
    // Calculate device type based on smallest dimension
    val deviceType = when {
        smallestDimensionDp < 600.dp -> DeviceClass.Phone
        smallestDimensionDp < 900.dp -> DeviceClass.Tablet
        else -> DeviceClass.Desktop
    }
    
    // Get platform-specific context
    val platformContext = getPlatformContext()
    
    // Calculate orientation
    val orientation = if (windowInfo.containerSize.width > windowInfo.containerSize.height) {
        ScreenOrientation.Landscape
    } else {
        ScreenOrientation.Portrait
    }
    
    // Create device context - no remember, let it recreate on changes
    val deviceContext = DeviceContext(
        screenWidth = windowInfo.containerSize.width,
        screenHeight = windowInfo.containerSize.height,
        orientation = orientation,
        deviceType = deviceType,
        customProperties = platformContext + customProperties
    )
    
    // Dispatch whenever dimensions or other properties change
    LaunchedEffect(
        windowInfo.containerSize.width,
        windowInfo.containerSize.height,
        deviceType,
        orientation,
        platformContext,
        customProperties
    ) {
        store.dispatch(DeviceAction.UpdateDeviceContext(deviceContext))
    }
    
    content()
}

/**
 * Get platform-specific context information.
 * Returns a map of string key-value pairs with platform-specific details.
 */
@Composable
expect fun getPlatformContext(): Map<String, String>

