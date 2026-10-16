package com.example.ai_assist.sdk.capability

import kotlinx.coroutines.flow.Flow

/** 与 Android Location 解耦的位置样本。 */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val capturedAtMillis: Long
) {
    init {
        require(latitude in -90.0..90.0) { "latitude is out of range" }
        require(longitude in -180.0..180.0) { "longitude is out of range" }
        require(accuracyMeters == null || accuracyMeters >= 0f) {
            "accuracyMeters must not be negative"
        }
    }
}

/** 发布宿主或配对设备提供的位置更新。 */
interface LocationCapability : SdkCapability {
    val locations: Flow<LocationSample>
    fun start(minUpdateIntervalMillis: Long)
    fun stop()

    override fun onLeaseReleased() = stop()
}
