package duks.routing

// Device context for responsive routing
data class DeviceContext(
    val screenWidth: Int,
    val screenHeight: Int,
    val orientation: ScreenOrientation,
    val deviceType: DeviceClass,
    val customProperties: Map<String, Any> = emptyMap()
)