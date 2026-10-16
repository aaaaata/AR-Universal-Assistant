package com.example.ai_assist.sdk.capability

import kotlinx.coroutines.flow.Flow

/** 与具体传感器 API 无关的设备运动样本。 */
data class MotionSample(
    val timestampNanos: Long,
    val accelerationMetersPerSecondSquared: FloatArray,
    val angularVelocityRadiansPerSecond: FloatArray
) {
    init {
        require(timestampNanos >= 0) { "timestampNanos must not be negative" }
        require(accelerationMetersPerSecondSquared.size == AXIS_COUNT) {
            "acceleration must contain x, y and z"
        }
        require(angularVelocityRadiansPerSecond.size == AXIS_COUNT) {
            "angular velocity must contain x, y and z"
        }
    }

    private companion object {
        const val AXIS_COUNT = 3
    }
}

/** 发布设备运动与姿态相关样本。 */
interface MotionCapability : SdkCapability {
    val samples: Flow<MotionSample>
    fun start(samplingPeriodMicros: Int)
    fun stop()

    override fun onLeaseReleased() = stop()
}
