package duks.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * WasmJS-specific device type detection.
 * Uses JavaScript interop to analyze user agent and screen dimensions
 * to determine if the device is a desktop, tablet, or phone.
 */
@Composable
actual fun rememberDeviceType(): DeviceClass {
    val density = LocalDensity.current
    
    return remember {
        val userAgent = getUserAgent()
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        
        // Convert pixels to dp for consistent measurement across devices
        val widthDp = with(density) { screenWidth.dp.value }
        val heightDp = with(density) { screenHeight.dp.value }
        val smallestWidthDp = minOf(widthDp, heightDp)
        
        when {
            // Check for TV devices (rare in web, but possible with smart TVs)
            isTvUserAgent(userAgent) -> DeviceClass.TV
            
            // Check for mobile devices first (phones and tablets)
            isMobileUserAgent(userAgent) -> {
                // Use screen size to distinguish between phone and tablet
                // Using 600dp as the threshold, consistent with Android
                if (smallestWidthDp >= 600) {
                    DeviceClass.Tablet
                } else {
                    DeviceClass.Phone
                }
            }
            
            // Check for tablet-specific indicators
            isTabletUserAgent(userAgent) -> DeviceClass.Tablet
            
            // Default to desktop for all other cases
            else -> DeviceClass.Desktop
        }
    }
}

/**
 * Gets the user agent string from the browser.
 */
private fun getUserAgent(): String = js("navigator.userAgent.toLowerCase()")

/**
 * Gets the screen width in pixels.
 */
private fun getScreenWidth(): Int = js("window.screen.width")

/**
 * Gets the screen height in pixels.
 */
private fun getScreenHeight(): Int = js("window.screen.height")

/**
 * Checks if the user agent indicates a mobile device.
 * This includes both phones and tablets.
 */
private fun isMobileUserAgent(userAgent: String): Boolean {
    val mobileKeywords = listOf(
        "mobile", "android", "iphone", "ipod", "blackberry", 
        "windows phone", "webos", "opera mini", "opera mobi"
    )
    return mobileKeywords.any { keyword -> userAgent.contains(keyword) }
}

/**
 * Checks if the user agent specifically indicates a tablet.
 * Note: This is used as a fallback when mobile detection fails but we suspect a tablet.
 */
private fun isTabletUserAgent(userAgent: String): Boolean {
    val tabletKeywords = listOf("ipad", "tablet", "kindle", "silk", "playbook")
    return tabletKeywords.any { keyword -> userAgent.contains(keyword) }
}

/**
 * Checks if the user agent indicates a TV device.
 * Smart TVs and TV-based browsers often identify themselves in the user agent.
 */
private fun isTvUserAgent(userAgent: String): Boolean {
    val tvKeywords = listOf(
        "tv", "smarttv", "googletv", "appletv", "hbbtv", 
        "pov_tv", "netcast", "webos.tv", "tizen.tv"
    )
    return tvKeywords.any { keyword -> userAgent.contains(keyword) }
}