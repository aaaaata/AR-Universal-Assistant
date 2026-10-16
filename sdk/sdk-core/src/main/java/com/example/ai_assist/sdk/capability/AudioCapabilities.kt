package com.example.ai_assist.sdk.capability

import java.io.File
import kotlinx.coroutines.flow.Flow

/** 通用麦克风采集参数。 */
data class AudioCaptureConfig(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(channelCount > 0) { "channelCount must be positive" }
    }
}

/** 一段带采集时间的 PCM 音频。 */
data class AudioChunk(
    val pcmBytes: ByteArray,
    val capturedAtMillis: Long
)

/** 采集连续音频数据，可由 Android 或厂商设备模块实现。 */
interface MicrophoneCapability : SdkCapability {
    val chunks: Flow<AudioChunk>
    fun start(config: AudioCaptureConfig)
    fun stop()

    override fun onLeaseReleased() = stop()
}

/** 播放场景提供的音频文件。 */
interface AudioOutputCapability : SdkCapability {
    fun play(file: File)
    fun stop()

    override fun onLeaseReleased() = stop()
}
