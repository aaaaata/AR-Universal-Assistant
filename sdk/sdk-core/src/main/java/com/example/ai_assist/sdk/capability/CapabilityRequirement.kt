package com.example.ai_assist.sdk.capability

/**
 * 场景启动前声明的能力需求。
 *
 * @property capabilityId 能力唯一 ID。
 * @property exclusive 是否要求独占该能力。
 */
data class CapabilityRequirement(
    val capabilityId: String,
    val exclusive: Boolean = false
) {
    init {
        require(capabilityId.isNotBlank()) { "capabilityId must not be blank" }
    }
}

/** MVP 已约定的标准能力 ID。 */
object StandardCapabilities {
    /** 完整图像采集能力。 */
    const val CAMERA = "camera"

    /** 眼镜或宿主输入能力。 */
    const val INPUT = "input"

    /** 设备信息与状态能力。 */
    const val DEVICE = "device"

    /** 网络连接状态能力。 */
    const val NETWORK = "network"

    /** 麦克风采集能力。 */
    const val MICROPHONE = "microphone"

    /** 音频输出能力。 */
    const val AUDIO_OUTPUT = "audio-output"

    /** 运动传感器与设备姿态能力。 */
    const val MOTION = "motion"

    /** 显示设备信息与模式能力。 */
    const val DISPLAY = "display"

    /** 位置更新能力。 */
    const val LOCATION = "location"
}
