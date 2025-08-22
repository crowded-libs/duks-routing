package duks.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * WasmJS-specific platform context.
 * Uses JavaScript interop to gather browser and device information.
 */
@Composable
actual fun getPlatformContext(): Map<String, String> {
    return remember {
        buildMap {
            val userAgent = getUserAgent()
            
            // User agent for device detection
            put("userAgent", userAgent)
            
            // Screen information
            put("screen.width", getScreenWidth().toString())
            put("screen.height", getScreenHeight().toString())
            put("screen.availWidth", getAvailableScreenWidth().toString())
            put("screen.availHeight", getAvailableScreenHeight().toString())
            put("screen.colorDepth", getColorDepth().toString())
            put("screen.pixelDepth", getPixelDepth().toString())
            
            // Window information
            put("window.innerWidth", getWindowInnerWidth().toString())
            put("window.innerHeight", getWindowInnerHeight().toString())
            
            // Device detection hints
            put("isMobile", isMobileUserAgent(userAgent).toString())
            put("isTablet", isTabletUserAgent(userAgent).toString())
            put("isTV", isTvUserAgent(userAgent).toString())
            
            // Touch capability
            put("touchEnabled", isTouchEnabled().toString())
            
            // Device pixel ratio for high DPI screens
            put("devicePixelRatio", getDevicePixelRatio().toString())
            
            // Browser language
            put("language", getBrowserLanguage())
            
            // Platform
            put("platform", getPlatform())
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

/**
 * Additional JavaScript interop functions for browser information
 */
private fun getAvailableScreenWidth(): Int = js("window.screen.availWidth")
private fun getAvailableScreenHeight(): Int = js("window.screen.availHeight")
private fun getColorDepth(): Int = js("window.screen.colorDepth")
private fun getPixelDepth(): Int = js("window.screen.pixelDepth")
private fun getWindowInnerWidth(): Int = js("window.innerWidth")
private fun getWindowInnerHeight(): Int = js("window.innerHeight")
private fun isTouchEnabled(): Boolean = js("'ontouchstart' in window || navigator.maxTouchPoints > 0")
private fun getDevicePixelRatio(): Float = js("window.devicePixelRatio || 1")
private fun getBrowserLanguage(): String = js("navigator.language || navigator.userLanguage || 'en'")
private fun getPlatform(): String = js("navigator.platform || 'unknown'")