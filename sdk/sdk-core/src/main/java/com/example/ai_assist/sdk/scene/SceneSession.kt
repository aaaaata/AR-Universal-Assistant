package com.example.ai_assist.sdk.scene

import com.example.ai_assist.sdk.capability.SdkCapability
import com.example.ai_assist.sdk.engine.CredentialProvider
import com.example.ai_assist.sdk.engine.SceneConfig

/** SDK 提供给活动场景的会话接口。 */
interface SceneSession {
    /** 当前会话唯一 ID。 */
    val sessionId: String

    /** 当前场景的会话配置。 */
    val config: SceneConfig

    /** 按场景获取凭据的安全入口。 */
    val credentialProvider: CredentialProvider

    /** 向 SDK 发布最新场景结果。 */
    suspend fun publishResult(result: SceneResult)

    /** Publish a transient notice without placing it in a separate system overlay. */
    suspend fun publishNotice(update: SceneNoticeUpdate) = Unit

    /** 返回当前会话已租用的指定类型能力。 */
    fun <T : SdkCapability> requireCapability(capabilityId: String, type: Class<T>): T {
        throw UnsupportedOperationException("Scene session does not provide capabilities")
    }

    /** 返回当前会话已租用的可选能力；设备不支持时返回 null。 */
    fun <T : SdkCapability> optionalCapability(capabilityId: String, type: Class<T>): T? = null

    /** 发布场景错误；不可恢复错误会使当前会话进入 ERROR。 */
    suspend fun reportError(code: String, message: String, recoverable: Boolean) = Unit

    /** 发布不包含敏感信息的场景诊断事件。 */
    suspend fun reportDiagnostic(code: String, message: String) = Unit
}
