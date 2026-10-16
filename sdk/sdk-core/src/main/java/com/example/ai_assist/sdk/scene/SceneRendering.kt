package com.example.ai_assist.sdk.scene

import android.view.ViewGroup

/** 将场景私有结果渲染到宿主业务容器。 */
interface SceneRenderer {
    /** 与 [SceneContent.rendererKey] 匹配的渲染器标识。 */
    val rendererKey: String

    /** 将私有 payload 渲染到指定容器。 */
    fun render(container: ViewGroup, payload: ScenePayload)
}

/** Optional renderer contract for notices that must share the scene's visual plane. */
interface SceneNoticeRenderer {
    fun renderNotice(update: SceneNoticeUpdate)
}

/** 场景私有结果的标记接口，SDK 不解析其字段。 */
interface ScenePayload

/**
 * 交给场景 Renderer 的业务内容。
 *
 * @property rendererKey 目标渲染器标识。
 * @property scenePayload 场景私有 payload。
 */
data class SceneContent(
    val rendererKey: String,
    val scenePayload: ScenePayload
) {
    init {
        require(rendererKey.isNotBlank()) { "rendererKey must not be blank" }
    }
}
