package com.example.ai_assist.sdk.engine

import com.example.ai_assist.sdk.capability.SdkCapability
import com.example.ai_assist.sdk.scene.SceneDescriptor
import com.example.ai_assist.sdk.scene.SceneProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 宿主管理场景生命周期、会话状态和 Capability 资源的入口。 */
interface SceneEngine {
    /** 当前会话状态。 */
    val state: StateFlow<SessionState>

    /** SDK 产生的会话级事件流。 */
    val events: Flow<SdkEvent>

    /** 使用全局配置初始化 SDK。 */
    fun initialize(config: SdkConfig)

    /** 注册一个编译期可用的场景。 */
    fun registerScene(provider: SceneProvider)

    /** 注册一个由 Core 管理租约和生命周期的能力。 */
    fun registerCapability(capabilityId: String, capability: SdkCapability)

    /** 返回当前已注册场景的描述信息。 */
    fun registeredScenes(): List<SceneDescriptor>

    /** 使用指定配置启动场景。 */
    suspend fun startScene(
        sceneId: String,
        config: SceneConfig = EmptySceneConfig
    )

    /** 停止当前活动场景并释放其资源。 */
    suspend fun stopScene()

    /** 关闭 SDK 并释放全部内部资源。 */
    suspend fun close()
}
