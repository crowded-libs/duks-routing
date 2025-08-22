package duks.routing

import kotlinx.serialization.Serializable

// Device context for responsive routing
@Serializable
data class DeviceContext(
    val screenWidth: Int,
    val screenHeight: Int,
    val orientation: ScreenOrientation,
    val deviceType: DeviceClass,
    val customProperties: Map<String, String> = emptyMap()
)