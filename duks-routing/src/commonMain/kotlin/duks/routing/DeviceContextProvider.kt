package duks.routing

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
    customProperties: Map<String, Any> = emptyMap(),
    content: @Composable () -> Unit
) {
    BoxWithConstraints {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val deviceType = rememberDeviceType()
        val orientation = if (width > height) ScreenOrientation.Landscape else ScreenOrientation.Portrait
        
        val deviceContext = remember(width, height, deviceType, orientation, customProperties) {
            DeviceContext(
                screenWidth = width,
                screenHeight = height,
                deviceType = deviceType,
                orientation = orientation,
                customProperties = customProperties
            )
        }
        
        LaunchedEffect(deviceContext) {
            store.dispatch(DeviceAction.UpdateDeviceContext(deviceContext))
        }
        
        content()
    }
}

/**
 * Remember the device type based on platform-specific characteristics.
 * This is the only platform-specific function for device classification.
 */
@Composable
expect fun rememberDeviceType(): DeviceClass

