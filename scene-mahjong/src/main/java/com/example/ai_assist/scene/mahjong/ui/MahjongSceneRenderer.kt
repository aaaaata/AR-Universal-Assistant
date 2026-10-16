package com.example.ai_assist.scene.mahjong.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import com.example.ai_assist.scene.mahjong.R
import com.example.ai_assist.sdk.capability.InputAction
import com.example.ai_assist.sdk.scene.SceneNoticeRenderer
import com.example.ai_assist.sdk.scene.SceneNoticeUpdate
import com.example.ai_assist.sdk.scene.ScenePayload
import com.example.ai_assist.sdk.scene.SceneRenderer
import java.lang.ref.WeakReference

/** Renders live results with the approved preview-layout-two HUD. */
class MahjongSceneRenderer : SceneRenderer, SceneNoticeRenderer {
    override val rendererKey: String = MahjongScenePayload.RENDERER_KEY
    private val rootsLock = Any()
    private val rootReferences = mutableListOf<WeakReference<MahjongHudView>>()
    @Volatile
    private var diagnosticWarning: String? = null

    override fun render(container: ViewGroup, payload: ScenePayload) {
        require(payload is MahjongScenePayload) { "Unsupported Mahjong scene payload" }
        ensureRoot(container).also {
            it.render(payload)
            it.renderDiagnosticWarning(diagnosticWarning)
        }
    }

    override fun renderNotice(update: SceneNoticeUpdate) {
        activeRoots().forEach { it.renderNotice(update) }
    }

    fun clear(container: ViewGroup) = render(container, MahjongScenePayload.EMPTY)

    fun renderDiagnosticWarning(message: String?) {
        diagnosticWarning = message
        activeRoots().forEach { root ->
            root.post {
                root.renderDiagnosticWarning(message)
            }
        }
    }

    fun handleInputAction(action: InputAction) = Unit

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
