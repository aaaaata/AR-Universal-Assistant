package com.example.ai_assist.sdk.capability

/**
 * 不包含敏感标识的设备基本信息。
 *
 * @property manufacturer 设备厂商。
 * @property model 设备型号。
 * @property androidVersion Android 系统版本。
 * @property sdkInt Android SDK 版本号。
 */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int
) {
    init {
        require(manufacturer.isNotBlank()) { "manufacturer must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(androidVersion.isNotBlank()) { "androidVersion must not be blank" }
        require(sdkInt > 0) { "sdkInt must be positive" }
    }
}

/**
 * 当前设备的动态状态快照。
 *
 * @property batteryPercent 可用时返回 0 到 100 的电量百分比。
 * @property charging 当前是否正在充电。
 * @property networkAvailable 当前是否存在具备互联网能力的活动网络。
 */
data class DeviceStatus(
    val batteryPercent: Int?,
    val charging: Boolean,
    val networkAvailable: Boolean
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100) {
            "batteryPercent must be between 0 and 100"
        }
    }
}

/** 提供与厂商实现无关的设备信息和状态。 */
interface DeviceCapability : SdkCapability {
    /** 返回当前设备的基本信息。 */
    fun getDeviceInfo(): DeviceInfo

    /** 返回调用时刻的设备状态快照。 */
    fun getDeviceStatus(): DeviceStatus
}
