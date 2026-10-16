package com.example.ai_assist.sdk.runtime

import com.example.ai_assist.sdk.scene.SceneProvider

/** 保存宿主编译期注册的场景。 */
internal class SceneRegistry {
    private val providers = linkedMapOf<String, SceneProvider>()

    /** 注册场景；相同 sceneId 重复注册时抛出异常。 */
    @Synchronized
    fun register(provider: SceneProvider) {
        val sceneId = provider.descriptor.sceneId
        require(sceneId !in providers) { "Scene is already registered: $sceneId" }
        providers[sceneId] = provider
    }

    /** 按 sceneId 查找场景，未注册时返回 null。 */
    @Synchronized
    fun find(sceneId: String): SceneProvider? = providers[sceneId]

    /** 按注册顺序返回场景快照。 */
    @Synchronized
    fun registeredScenes(): List<SceneProvider> = providers.values.toList()

    /** 清空全部场景注册信息。 */
    @Synchronized
    internal fun clear() {
        providers.clear()
    }
}
