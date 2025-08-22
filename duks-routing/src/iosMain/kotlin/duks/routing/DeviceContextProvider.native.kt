package duks.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPhone
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIUserInterfaceIdiomTV
import platform.UIKit.UIUserInterfaceIdiomCarPlay

/**
 * iOS/macOS native platform context.
 * Provides device-specific information for iOS and macOS platforms.
 */
@Composable
actual fun getPlatformContext(): Map<String, String> {
    return remember {
        buildMap {
            val device = UIDevice.currentDevice
            
            // Device idiom
            val idiom = when (device.userInterfaceIdiom) {
                UIUserInterfaceIdiomPhone -> "phone"
                UIUserInterfaceIdiomPad -> "pad"
                UIUserInterfaceIdiomTV -> "tv"
                UIUserInterfaceIdiomCarPlay -> "carplay"
                5L -> "mac" // UIUserInterfaceIdiomMac value
                else -> "unknown"
            }
            put("uiIdiom", idiom)
            
            // System information
            put("systemName", device.systemName)
            put("systemVersion", device.systemVersion)
            put("model", device.model)
            put("name", device.name)
            
            // Device characteristics
            put("isMultitaskingSupported", device.multitaskingSupported.toString())
        }
    }
}