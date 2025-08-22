package duks.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.GraphicsEnvironment

/**
 * JVM-specific platform context.
 * Provides desktop-specific information for JVM applications.
 */
@Composable
actual fun getPlatformContext(): Map<String, String> {
    return remember {
        buildMap {
            // OS information
            put("os.name", System.getProperty("os.name", "unknown"))
            put("os.version", System.getProperty("os.version", "unknown"))
            put("os.arch", System.getProperty("os.arch", "unknown"))
            
            // Java information
            put("java.version", System.getProperty("java.version", "unknown"))
            put("java.vendor", System.getProperty("java.vendor", "unknown"))
            
            // Display information if available
            try {
                val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
                val sd = ge.defaultScreenDevice
                val dm = sd.displayMode
                
                put("screen.width", dm.width.toString())
                put("screen.height", dm.height.toString())
                put("screen.refreshRate", dm.refreshRate.toString())
                put("screen.bitDepth", dm.bitDepth.toString())
                
                // Check if running in headless mode
                put("isHeadless", GraphicsEnvironment.isHeadless().toString())
            } catch (e: Exception) {
                // In case of headless environment or other issues
                put("isHeadless", "true")
            }
            
            // User locale
            put("user.language", System.getProperty("user.language", "unknown"))
            put("user.country", System.getProperty("user.country", "unknown"))
        }
    }
}