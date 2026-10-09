package com.example.ai_assist.scene.mahjong.framework

import com.example.ai_assist.sdk.framework.CapabilityConfig
import com.example.ai_assist.sdk.framework.ImageCaptureConfig
import com.example.ai_assist.sdk.framework.SceneConfig

enum class MahjongServiceMode { LOCAL, CLOUD }

enum class MahjongRecordingMode(val wireValue: String) {
    NORMAL("normal"),
    RECORD("regression")
}

/** 麻将场景使用的相机参数。具体分辨率由相机能力实现选择。 */
data class MahjongCameraConfig(
    override val preferredWidth: Int = 1280,
    override val preferredHeight: Int = 720,
    override val imageFormat: String = "JPEG"
) : ImageCaptureConfig {
    init {
        require(preferredWidth > 0) { "preferredWidth must be positive" }
        require(preferredHeight > 0) { "preferredHeight must be positive" }
        require(imageFormat.isNotBlank()) { "imageFormat must not be blank" }
    }
}

/** 单次麻将云端交换会话配置。API Key 必须由 SceneContext.credential() 提供。 */
data class MahjongSceneConfig(
    val serverBaseUrl: String = DEFAULT_SERVER_BASE_URL,
    val serviceMode: MahjongServiceMode = MahjongServiceMode.CLOUD,
    val ruleProfileId: String = DEFAULT_RULE_PROFILE_ID,
    val recordingMode: MahjongRecordingMode = MahjongRecordingMode.NORMAL,
    val captureIntervalMillis: Long = DEFAULT_CAPTURE_INTERVAL_MILLIS,
    val cameraConfig: CapabilityConfig = MahjongCameraConfig()
) : SceneConfig {
    init {
        require(serverBaseUrl.startsWith("http://") || serverBaseUrl.startsWith("https://")) {
            "serverBaseUrl must use HTTP or HTTPS"
        }
        require(serverBaseUrl.endsWith('/')) { "serverBaseUrl must end with /" }
        require(ruleProfileId.isNotBlank()) { "ruleProfileId must not be blank" }
        require(captureIntervalMillis > 0) { "captureIntervalMillis must be positive" }
    }

    companion object {
        const val DEFAULT_SERVER_BASE_URL = "http://39.96.67.192:8552/"
        const val DEFAULT_RULE_PROFILE_ID = "guangdong_mvp_v1"
        const val DEFAULT_CAPTURE_INTERVAL_MILLIS = 2_000L
    }
}
