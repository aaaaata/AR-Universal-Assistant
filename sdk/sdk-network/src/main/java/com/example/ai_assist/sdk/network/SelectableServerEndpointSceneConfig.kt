package com.example.ai_assist.sdk.network

/** 服务地址之外，还支持由宿主选择服务模式的场景配置。 */
interface SelectableServerEndpointSceneConfig : ServerEndpointSceneConfig {
    fun withServiceMode(modeId: String): SelectableServerEndpointSceneConfig
}
