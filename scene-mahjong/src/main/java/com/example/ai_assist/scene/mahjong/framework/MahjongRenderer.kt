package com.example.ai_assist.scene.mahjong.framework

import android.view.LayoutInflater
import android.view.ViewGroup
import com.example.ai_assist.scene.mahjong.R
import com.example.ai_assist.sdk.framework.SceneContent
import com.example.ai_assist.sdk.framework.SceneRenderer
import java.lang.ref.WeakReference

/** 在新框架 SceneRenderer 契约上复刻旧工程的单目麻将 HUD。 */
class MahjongRenderer : SceneRenderer {
    override val rendererKey: String = MahjongScenePayload.RENDERER_KEY
    private val rootsLock = Any()
    private val rootReferences = mutableListOf<WeakReference<MahjongHudView>>()
    @Volatile
    private var diagnosticWarning: String? = null
    @Volatile
    private var noticeMessage: String? = null

    override fun render(container: ViewGroup, content: SceneContent) {
        require(content.rendererKey == rendererKey) {
            "Unsupported renderer key: ${content.rendererKey}"
        }
        val payload = content.scenePayload as? MahjongScenePayload
            ?: throw IllegalArgumentException("Unsupported Mahjong scene payload")
        ensureRoot(container).also {
            it.render(payload)
            it.renderDiagnosticWarning(diagnosticWarning)
            it.renderNotice(noticeMessage)
        }
    }

    fun renderNotice(message: String?) {
        noticeMessage = message
        activeRoots().forEach { root -> root.post { root.renderNotice(message) } }
    }

    fun renderEmpty(container: ViewGroup, serviceMode: MahjongServiceMode) {
        prepare(serviceMode)
        render(
            container,
            SceneContent(rendererKey, MahjongScenePayload.EMPTY.copy(serviceMode = serviceMode))
        )
    }

    fun prepare(serviceMode: MahjongServiceMode) {
        diagnosticWarning = null
        noticeMessage = null
        activeRoots().forEach { root ->
            root.post {
                root.render(MahjongScenePayload.EMPTY.copy(serviceMode = serviceMode))
                root.renderDiagnosticWarning(null)
                root.renderNotice(null)
            }
        }
    }

    override fun clear() = prepare(MahjongServiceMode.CLOUD)

    fun renderDiagnosticWarning(message: String?) {
        diagnosticWarning = message
        activeRoots().forEach { root ->
            root.post {
                root.renderDiagnosticWarning(message)
            }
        }
    }

    private fun ensureRoot(container: ViewGroup): MahjongHudView {
        activeRoots().firstOrNull { it.parent === container }?.let { return it }
        container.removeAllViews()
        val root = LayoutInflater.from(container.context)
            .inflate(R.layout.view_mahjong_scene, container, false) as MahjongHudView
        container.addView(root)
        synchronized(rootsLock) {
            rootReferences.add(WeakReference(root))
        }
        return root
    }

    private fun activeRoots(): List<MahjongHudView> = synchronized(rootsLock) {
        val iterator = rootReferences.iterator()
        val active = mutableListOf<MahjongHudView>()
        while (iterator.hasNext()) {
            iterator.next().get()?.let(active::add) ?: iterator.remove()
        }
        active
    }
}
