package duks.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Native implementation of device type detection.
 * 
 * Platform-specific implementations can override this:
 * - iOS: Can use UIDevice.currentDevice.userInterfaceIdiom for device type detection
 * - macOS: Can use NSScreen for screen dimensions  
 * - Other native: Default to reasonable values
 */
@Composable
actual fun rememberDeviceType(): DeviceClass {
    return remember {
        // For iOS: typically phone (iPhone) or tablet (iPad)
        // For other native: typically desktop
        // This can be enhanced with platform-specific detection
        DeviceClass.Phone  // Default - can be overridden in platform-specific source sets
    }
}