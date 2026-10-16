package com.example.ai_assist.sdk.scene

import com.example.ai_assist.sdk.engine.SceneConfig

/** 场景在宿主菜单中的展示信息。 */
data class ScenePresentation(
    val menuTitle: String,
    val menuIconRes: Int,
    val initialPayload: ScenePayload? = null
) {
    init {
        require(menuTitle.isNotBlank()) { "menuTitle must not be blank" }
    }
}

/** 一个场景模块交给宿主装配的完整注册信息。 */
data class SceneRegistration(
    val provider: SceneProvider,
    val config: SceneConfig,
    val credential: String?,
    val presentation: ScenePresentation
)

/** 独立场景模块向 Android 宿主暴露的通用安装入口。 */
fun interface SceneModule {
    fun createRegistration(): SceneRegistration
}
