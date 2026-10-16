package com.example.ai_assist.sdk.engine

/**
 * SDK 全局配置。
 *
 * @property defaultTimeoutMillis 未被场景覆盖时使用的默认超时。
 */
data class SdkConfig(
    val defaultTimeoutMillis: Long = 15_000L
) {
    init {
        require(defaultTimeoutMillis > 0) { "defaultTimeoutMillis must be positive" }
    }
}

/** 场景会话配置的标记接口，由具体场景定义字段。 */
interface SceneConfig

/** 场景启动前可由宿主选择的模式。 */
data class SceneStartMode(
    val id: String,
    val label: String,
    val isRecording: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "Scene start mode ID must not be blank" }
        require(label.isNotBlank()) { "Scene start mode label must not be blank" }
    }
}

/** 支持在启动前切换运行模式的场景配置。 */
interface SceneStartModeConfig : SceneConfig {
    val selectedStartModeId: String
    val startModes: List<SceneStartMode>
    fun withStartMode(modeId: String): SceneStartModeConfig
}

/** 不需要额外配置时使用的空场景配置。 */
object EmptySceneConfig : SceneConfig

/** 为指定场景提供敏感凭据，凭据不得写入普通配置或日志。 */
fun interface CredentialProvider {
    /** 返回场景凭据；未配置时返回 null。 */
    fun credentialFor(sceneId: String): String?
}
