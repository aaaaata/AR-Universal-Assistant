package com.example.ai_assist.sdk.capability

/** 当前显示设备可供场景查询的稳定信息。 */
data class DisplayInfo(
    val widthPixels: Int,
    val heightPixels: Int,
    val refreshRateHz: Float,
    val stereoscopic: Boolean
) {
    init {
        require(widthPixels > 0) { "widthPixels must be positive" }
        require(heightPixels > 0) { "heightPixels must be positive" }
        require(refreshRateHz > 0f) { "refreshRateHz must be positive" }
    }
}

/** 提供显示尺寸、刷新率和双目特性查询。 */
interface DisplayCapability : SdkCapability {
    fun getDisplayInfo(): DisplayInfo
}
