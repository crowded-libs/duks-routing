package duks.routing

import duks.Action

// Device actions
sealed class DeviceAction : Action {
    data class UpdateDeviceContext(val context: DeviceContext) : DeviceAction()
    data class UpdateScreenSize(val width: Int, val height: Int) : DeviceAction()
    data class UpdateOrientation(val orientation: ScreenOrientation) : DeviceAction()
}