package duks.routing

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Android-specific device type detection.
 * Determines if the device is a Phone, Tablet, TV, or Watch based on:
 * - UI mode for TV and Watch detection
 * - Screen layout size for distinguishing phones from tablets
 * - Device features for watch detection
 */
@Composable
actual fun rememberDeviceType(): DeviceClass {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    
    return remember(configuration) {
        when {
            // Check if it's a watch device
            isWatchDevice(context) -> DeviceClass.Watch
            
            // Check if it's a TV device
            isTvDevice(context) -> DeviceClass.TV
            
            // Check if it's a tablet based on screen size
            isTablet(configuration) -> DeviceClass.Tablet
            
            // Default to phone
            else -> DeviceClass.Phone
        }
    }
}

/**
 * Checks if the device is running in TV mode.
 * This includes Android TV devices and devices in leanback mode.
 */
private fun isTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature("android.software.leanback")
}

/**
 * Checks if the device is a watch device.
 * This includes Wear OS devices and devices in watch mode.
 * Uses multiple detection methods:
 * - UI mode type for watch detection
 * - Hardware features specific to watches
 * - Screen size characteristics typical of watches
 */
private fun isWatchDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    
    // Check UI mode type first (most reliable for Wear OS)
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_WATCH) {
        return true
    }
    
    // Check for watch-specific hardware features
    val packageManager = context.packageManager
    if (packageManager.hasSystemFeature("android.hardware.type.watch")) {
        return true
    }
    
    // Fallback: Check for typical watch screen characteristics
    // Watches typically have small, square or round screens
    val configuration = context.resources.configuration
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    
    // Most watches have screens smaller than 320dp in both dimensions
    // and have relatively square aspect ratios
    if (screenWidthDp <= 320 && screenHeightDp <= 320) {
        val aspectRatio = maxOf(screenWidthDp, screenHeightDp).toFloat() / 
                         minOf(screenWidthDp, screenHeightDp).toFloat()
        // Square or nearly square screens (aspect ratio close to 1:1)
        return aspectRatio <= 1.5f
    }
    
    return false
}

/**
 * Determines if the device is a tablet based on screen size.
 * Uses the smallestScreenWidthDp which is orientation-independent.
 * Common thresholds:
 * - Phone: < 600dp
 * - 7" Tablet: >= 600dp
 * - 10" Tablet: >= 720dp
 */
private fun isTablet(configuration: Configuration): Boolean {
    // Using 600dp as the threshold between phone and tablet
    // This is the standard breakpoint used in Android's resource qualifiers
    return configuration.smallestScreenWidthDp >= 600
}