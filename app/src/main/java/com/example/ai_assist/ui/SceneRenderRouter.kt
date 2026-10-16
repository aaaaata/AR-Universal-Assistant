package com.example.ai_assist.ui

import android.view.ViewGroup
import com.example.ai_assist.sdk.scene.SceneRenderer
import com.example.ai_assist.sdk.scene.SceneNoticeRenderer
import com.example.ai_assist.sdk.scene.SceneNoticeUpdate
import com.example.ai_assist.sdk.scene.SceneRegistration
import com.example.ai_assist.sdk.scene.SceneResult

/** 将场景私有结果路由到主页面的场景内容区域。 */
internal class SceneRenderRouter(
    registrations: List<SceneRegistration>
) {
    private val registrationsById = registrations.associateBy {
        it.provider.descriptor.sceneId
    }

    init {
        require(registrationsById.size == registrations.size) { "App scene IDs must be unique" }
    }

    fun resetScene(container: ViewGroup, sceneId: String) {
        val registration = registrationsById[sceneId]
        registration?.presentation?.initialPayload?.let {
            registration.provider.renderer.render(container, it)
        } ?: container.removeAllViews()
    }

    fun clear(container: ViewGroup) {
        container.removeAllViews()
    }

    fun rendererFor(sceneId: String, rendererKey: String): SceneRenderer? {
        val registration = registrationsById[sceneId] ?: return null
        return registration.provider.renderer.takeIf { it.rendererKey == rendererKey }
    }

    fun render(container: ViewGroup, result: SceneResult): Boolean {
        val content = result.content ?: return false
        val renderer = rendererFor(result.sceneId, content.rendererKey) ?: return false
        renderer.render(container, content.scenePayload)
        return true
    }

    fun renderNotice(sceneId: String, update: SceneNoticeUpdate): Boolean {
        val renderer = registrationsById[sceneId]?.provider?.renderer as? SceneNoticeRenderer
            ?: return false
        renderer.renderNotice(update)
        return true
    }
}
