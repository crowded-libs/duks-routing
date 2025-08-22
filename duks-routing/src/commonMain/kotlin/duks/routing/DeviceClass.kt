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