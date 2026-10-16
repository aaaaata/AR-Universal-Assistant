package com.example.ai_assist.scene.mahjong.scene

import com.example.ai_assist.sdk.capability.CameraConfig
import com.example.ai_assist.sdk.network.ServerEndpointSceneConfig
import com.example.ai_assist.sdk.network.SelectableServerEndpointSceneConfig
import com.example.ai_assist.sdk.engine.SceneStartMode
import com.example.ai_assist.sdk.engine.SceneStartModeConfig

enum class MahjongServiceMode { LOCAL, CLOUD }

/**
 * 麻将场景单次会话配置。
 *
 * @property serverBaseUrl 云端服务根地址，必须以斜杠结尾。
 * @property ruleProfileId 服务端规则配置 ID。
 * @property captureIntervalMillis 实时分析选帧间隔。
 * @property cameraConfig 相机采集配置。
 */
data class MahjongSceneConfig(
    override val serverBaseUrl: String,
    val serviceMode: MahjongServiceMode = MahjongServiceMode.CLOUD,
    val apiKey: String = "",
    val localServerBaseUrl: String = serverBaseUrl,
    val localApiKey: String = apiKey,
    val cloudServerBaseUrl: String = serverBaseUrl,
    val cloudApiKey: String = apiKey,
    val ruleProfileId: String = DEFAULT_RULE_PROFILE_ID,
    val recordingMode: MahjongRecordingMode = MahjongRecordingMode.NORMAL,
    val captureIntervalMillis: Long = DEFAULT_CAPTURE_INTERVAL_MILLIS,
    val captureJitterMillis: Long = DEFAULT_CAPTURE_JITTER_MILLIS,
    val startupJitterMillis: Long = DEFAULT_STARTUP_JITTER_MILLIS,
    val cameraConfig: CameraConfig = CameraConfig()
) : SelectableServerEndpointSceneConfig, SceneStartModeConfig {
    override val supportedQrSceneIds: Set<String>
        get() = SUPPORTED_QR_SCENE_IDS

    override fun withServerBaseUrl(serverBaseUrl: String): MahjongSceneConfig =
        copy(serverBaseUrl = serverBaseUrl, serviceMode = MahjongServiceMode.LOCAL)

    fun withServiceMode(mode: MahjongServiceMode): MahjongSceneConfig = when (mode) {
        MahjongServiceMode.LOCAL -> copy(
            serverBaseUrl = localServerBaseUrl,
            apiKey = localApiKey,
            serviceMode = mode
        )
        MahjongServiceMode.CLOUD -> copy(
            serverBaseUrl = cloudServerBaseUrl,
            apiKey = cloudApiKey,
            serviceMode = mode
        )
    }

    override fun withServiceMode(modeId: String): MahjongSceneConfig =
        withServiceMode(
            when (modeId.lowercase()) {
                "local" -> MahjongServiceMode.LOCAL
                "cloud" -> MahjongServiceMode.CLOUD
                else -> throw IllegalArgumentException("Unsupported service mode: $modeId")
            }
        )

    override val selectedStartModeId: String
        get() = recordingMode.wireValue

    override val startModes: List<SceneStartMode>
        get() = START_MODES

    override fun withStartMode(modeId: String): MahjongSceneConfig = copy(
        recordingMode = MahjongRecordingMode.entries.firstOrNull { it.wireValue == modeId }
            ?: throw IllegalArgumentException("Unsupported Mahjong recording mode: $modeId")
    )

    init {
        require(serverBaseUrl.isNotBlank()) { "serverBaseUrl must not be blank" }
        require(serverBaseUrl.endsWith('/')) { "serverBaseUrl must end with /" }
        require(ruleProfileId.isNotBlank()) { "ruleProfileId must not be blank" }
        require(captureIntervalMillis > 0) { "captureIntervalMillis must be positive" }
        require(captureJitterMillis >= 0) { "captureJitterMillis must not be negative" }
        require(startupJitterMillis >= 0) { "startupJitterMillis must not be negative" }
    }

    private companion object {
        val SUPPORTED_QR_SCENE_IDS = setOf("mahjong", "mahjong-guangdong")
        val START_MODES = listOf(
            SceneStartMode(MahjongRecordingMode.NORMAL.wireValue, "普通开局"),
            SceneStartMode(
                MahjongRecordingMode.REGRESSION.wireValue,
                "完整录制",
                isRecording = true
            )
        )
        const val DEFAULT_RULE_PROFILE_ID = "guangdong_mvp_v1"
        const val DEFAULT_CAPTURE_INTERVAL_MILLIS = 2_000L
        const val DEFAULT_CAPTURE_JITTER_MILLIS = 1_500L
        const val DEFAULT_STARTUP_JITTER_MILLIS = 2_000L
    }
}
