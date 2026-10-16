package com.example.ai_assist.sdk.scene

/** 场景模块向 SDK 提供的生命周期入口。 */
interface SceneProvider {
    /** 场景静态描述。 */
    val descriptor: SceneDescriptor

    /** 场景业务内容渲染器。 */
    val renderer: SceneRenderer

    /** 使用当前场景会话启动场景。 */
    suspend fun start(session: SceneSession)

    /** 停止场景并释放场景私有资源。 */
    suspend fun stop()
}
