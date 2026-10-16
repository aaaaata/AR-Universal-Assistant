package com.example.ai_assist.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import com.example.ai_assist.R
import com.example.ai_assist.databinding.ActivityMainBinding
import com.example.ai_assist.databinding.ViewSceneMenuItemBinding
import com.example.ai_assist.sdk.engine.SessionState
import com.example.ai_assist.sdk.scene.SceneResult
import com.example.ai_assist.sdk.scene.SceneNoticeUpdate

/** 集中负责主页面控件和场景内容区域的渲染。 */
internal class MainScreenRenderer(
    private val context: Context,
    private val sceneRenderRouter: SceneRenderRouter,
    private val onMenuItemClicked: (Int) -> Unit
) {
    private var lastRenderedScreen: AppScreen? = null
    private var lastRenderedSessionState: SessionState? = null
    private var hasRenderedSceneResult = false

    fun setup(binding: ActivityMainBinding) {
        binding.tvStatus.setTextColor(context.getColor(R.color.neon_green))
    }

    fun render(binding: ActivityMainBinding, state: MainUiState) {
        logStateChanges(state)
        binding.menuRoot.visibility = if (state.screen == AppScreen.MENU) View.VISIBLE else View.GONE
        binding.sceneRoot.visibility = if (state.screen == AppScreen.SCENE) View.VISIBLE else View.GONE
        binding.sceneContent.visibility = if (
            state.screen == AppScreen.SCENE &&
            state.sessionState == SessionState.RUNNING &&
            hasRenderedSceneResult
        ) View.VISIBLE else View.INVISIBLE
        if (state.screen == AppScreen.MENU) renderSceneMenu(binding, state)
        binding.tvInstructionIdle.visibility =
            if (state.showIdleInstruction) View.VISIBLE else View.GONE
        binding.tvInstructionIdle.text = when {
            state.isSelectingStartMode -> startModeInstruction(state.startModeOptions)
            state.connectionScanState != ConnectionScanState.IDLE ->
                "对准服务二维码 · 单击取消"
            state.activeSceneSupportsServerConfiguration -> "单击扫码配置 · 三击开始"
            else -> context.getString(R.string.instruction_start_scene)
        }
        binding.tvInstructionRunning.visibility = View.GONE
        binding.tvInstructionRunning.text = if (state.activeStartMode?.isRecording == true) {
            "回归录制中 · 三击结束并保存"
        } else {
            context.getString(R.string.instruction_stop_scene)
        }
        binding.tvStatus.text = when (state.sessionState) {
            SessionState.UNINITIALIZED -> "正在初始化"
            SessionState.READY -> if (state.isSelectingStartMode) {
                "请选择开局模式"
            } else {
                state.connectionStatusText ?: "已连接 - 等待开始"
            }
            SessionState.STARTING -> context.getString(
                R.string.status_scene_starting,
                state.activeSceneTitle.orEmpty()
            )
            SessionState.RUNNING -> if (state.activeStartMode?.isRecording == true) {
                "${state.activeSceneTitle.orEmpty()} 回归录制中"
            } else {
                context.getString(R.string.status_scene_running, state.activeSceneTitle.orEmpty())
            }
            SessionState.STOPPING -> context.getString(
                R.string.status_scene_stopping,
                state.activeSceneTitle.orEmpty()
            )
            SessionState.ERROR -> context.getString(
                R.string.status_scene_error,
                state.activeSceneTitle.orEmpty()
            )
        }
        binding.tvStatus.visibility = if (
            state.screen == AppScreen.SCENE && state.sessionState == SessionState.RUNNING
        ) View.GONE else View.VISIBLE
    }

    private fun startModeInstruction(modes: List<com.example.ai_assist.sdk.engine.SceneStartMode>): String =
        when {
            modes.size >= 2 -> "前滑${modes[1].label} · 后滑${modes[0].label}"
            modes.size == 1 -> modes[0].label
            else -> context.getString(R.string.instruction_start_scene)
        }

    fun renderResult(binding: ActivityMainBinding, result: SceneResult) {
        val renderStartedAt = SystemClock.elapsedRealtime()
        sceneRenderRouter.render(binding.sceneContent, result)
        hasRenderedSceneResult = true
        binding.sceneContent.visibility = View.VISIBLE
        val renderCompletedAt = SystemClock.elapsedRealtime()
        Log.i(
            TIMING_TAG,
            "event=result_rendered scene_id=${result.sceneId} " +
                "session_id=${result.sessionId} status=${result.summary.status} " +
                "render_ms=${renderCompletedAt - renderStartedAt} elapsed_ms=$renderCompletedAt"
        )
    }

    fun presentNotice(sceneId: String, update: SceneNoticeUpdate) {
        sceneRenderRouter.renderNotice(sceneId, update)
    }

    fun resetSceneContent(binding: ActivityMainBinding, sceneId: String) {
        hasRenderedSceneResult = false
        binding.sceneContent.visibility = View.INVISIBLE
        sceneRenderRouter.resetScene(binding.sceneContent, sceneId)
    }

    fun clearSceneContent(binding: ActivityMainBinding) {
        hasRenderedSceneResult = false
        sceneRenderRouter.clear(binding.sceneContent)
    }

    fun showTemporaryMessage(binding: ActivityMainBinding, message: String) {
        binding.tvCustomToast.text = message
        binding.tvCustomToast.visibility = View.VISIBLE
        binding.tvCustomToast.postDelayed(
            { binding.tvCustomToast.visibility = View.GONE },
            MESSAGE_DURATION_MS
        )
    }

    private fun renderSceneMenu(binding: ActivityMainBinding, state: MainUiState) {
        binding.menuSceneItems.removeAllViews()
        state.menuItems.forEachIndexed { index, item ->
            val itemBinding = ViewSceneMenuItemBinding.inflate(
                LayoutInflater.from(context),
                binding.menuSceneItems,
                false
            )
            val isSelected = index == state.selectedMenuIndex
            itemBinding.root.isSelected = isSelected
            itemBinding.sceneMenuIconFrame.isSelected = isSelected
            itemBinding.sceneMenuIcon.setImageResource(
                when (item) {
                    is SceneMenuItem.Registered -> item.iconRes
                    SceneMenuItem.AddScene -> R.drawable.ic_add_scene
                }
            )
            itemBinding.sceneMenuIcon.contentDescription = item.title
            val iconScale = if (isSelected) SELECTED_ICON_SCALE else DEFAULT_ICON_SCALE
            itemBinding.sceneMenuIcon.scaleX = iconScale
            itemBinding.sceneMenuIcon.scaleY = iconScale
            itemBinding.sceneMenuIcon.alpha = if (isSelected) SELECTED_ICON_ALPHA else DEFAULT_ICON_ALPHA
            itemBinding.sceneMenuTitle.text = item.title
            itemBinding.sceneMenuTitle.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            itemBinding.root.setOnClickListener { onMenuItemClicked(index) }
            binding.menuSceneItems.addView(itemBinding.root)
        }
        binding.menuSceneScroll.post {
            val selected = binding.menuSceneItems.getChildAt(state.selectedMenuIndex)
                ?: return@post
            val targetX = selected.left - (binding.menuSceneScroll.width - selected.width) / 2
            binding.menuSceneScroll.smoothScrollTo(targetX.coerceAtLeast(0), 0)
        }
    }

    private fun logStateChanges(state: MainUiState) {
        if (lastRenderedScreen != state.screen) {
            lastRenderedScreen = state.screen
            Log.i(
                TIMING_TAG,
                "event=screen_rendered screen=${state.screen} elapsed_ms=${SystemClock.elapsedRealtime()}"
            )
        }
        if (lastRenderedSessionState != state.sessionState) {
            lastRenderedSessionState = state.sessionState
            Log.i(
                TIMING_TAG,
                "event=session_state_rendered state=${state.sessionState} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime()}"
            )
        }
    }

    private companion object {
        const val TIMING_TAG = "AssistTiming"
        const val MESSAGE_DURATION_MS = 2_000L
        const val DEFAULT_ICON_SCALE = 1f
        const val SELECTED_ICON_SCALE = 1.18f
        const val DEFAULT_ICON_ALPHA = 0.82f
        const val SELECTED_ICON_ALPHA = 1f
    }
}
