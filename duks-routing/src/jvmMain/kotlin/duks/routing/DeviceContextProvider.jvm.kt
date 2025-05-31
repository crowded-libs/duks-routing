package duks.routing

import androidx.compose.runtime.Composable

/**
 * JVM-specific device type detection.
 * Returns DeviceClass.Desktop for JVM/desktop applications.
 */
@Composable
actual fun rememberDeviceType(): DeviceClass {
    // JVM applications are desktop applications
    return DeviceClass.Desktop
}