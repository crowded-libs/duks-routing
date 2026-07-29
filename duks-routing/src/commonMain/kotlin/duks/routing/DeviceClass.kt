package duks.routing

import kotlinx.serialization.Serializable

// Device classes
@Serializable
enum class DeviceClass {
    Phone,
    Tablet,
    Desktop,
    TV,
    Watch
}

/**
 * Shared device-class heuristics for routing and [DeviceContextProvider].
 *
 * Breakpoints use the **smallest** window dimension (similar to Android
 * `smallestScreenWidthDp`) so classification is stable across orientation changes.
 * Callers should pass values in the same unit they use consistently (prefer dp).
 */
object DeviceClassHeuristics {
    const val WATCH_MAX = 320
    const val PHONE_MAX = 600
    const val TABLET_MAX = 900

    /**
     * Classify a device from its smallest dimension (width or height).
     */
    fun fromSmallestDimension(smallest: Int): DeviceClass = when {
        smallest <= WATCH_MAX -> DeviceClass.Watch
        smallest < PHONE_MAX -> DeviceClass.Phone
        smallest < TABLET_MAX -> DeviceClass.Tablet
        else -> DeviceClass.Desktop
    }

    /**
     * Classify from width and height using the smaller edge.
     */
    fun fromDimensions(width: Int, height: Int): DeviceClass =
        fromSmallestDimension(minOf(width, height))
}