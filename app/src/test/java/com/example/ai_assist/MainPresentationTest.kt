package com.example.ai_assist

import com.example.ai_assist.ui.AppScreen
import com.example.ai_assist.ui.ConnectionScanState
import com.example.ai_assist.ui.HostCommand
import com.example.ai_assist.ui.MainUiState
import com.example.ai_assist.ui.SceneInputPolicy
import com.example.ai_assist.ui.SceneMenuItem
import com.example.ai_assist.sdk.capability.InputAction
import com.example.ai_assist.sdk.engine.SessionState
import com.example.ai_assist.sdk.engine.SceneStartMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainPresentationTest {
    /** 验证会话状态转换为稳定页面可见性。 */
    @Test
    fun mapsSessionStateToUiState() {
        val menuItems = listOf(SceneMenuItem.AddScene)
        val menu = MainUiState(SessionState.READY, menuItems)
        val ready = MainUiState(
            SessionState.READY,
            menuItems,
            screen = AppScreen.SCENE,
            activeSceneId = "mahjong",
            activeSceneTitle = "AR麻将助手"
        )
        val running = ready.copy(sessionState = SessionState.RUNNING)

        assertFalse(menu.showIdleInstruction)
        assertFalse(menu.showRunningInstruction)
        assertFalse(ready.showIdleInstruction)
        assertFalse(ready.showRunningInstruction)
        assertFalse(running.showIdleInstruction)
        assertTrue(running.showRunningInstruction)

        val selecting = ready.copy(
            startModeOptions = listOf(
                SceneStartMode("normal", "普通开局"),
                SceneStartMode("regression", "完整录制", isRecording = true)
            )
        )
        assertTrue(selecting.isSelectingStartMode)
        assertTrue(selecting.showIdleInstruction)
        assertTrue(
            ready.copy(connectionScanState = ConnectionScanState.SCANNING).showIdleInstruction
        )
    }

    /** 验证菜单前后滑动会循环选择场景入口。 */
    @Test
    fun wrapsMenuSelectionInBothDirections() {
        assertEquals(1, SceneInputPolicy.moveMenuSelection(0, 2, 1))
        assertEquals(0, SceneInputPolicy.moveMenuSelection(1, 2, 1))
        assertEquals(1, SceneInputPolicy.moveMenuSelection(0, 2, -1))
    }

    /** 验证三击只负责结束或恢复，场景启动由选择流程自动完成。 */
    @Test
    fun resolvesTripleClickSceneCommands() {
        assertEquals(
            HostCommand.NONE,
            SceneInputPolicy.resolve(SessionState.READY, InputAction.TRIPLE_CLICK)
        )
        assertEquals(
            HostCommand.STOP_SCENE,
            SceneInputPolicy.resolve(SessionState.RUNNING, InputAction.TRIPLE_CLICK)
        )
        assertEquals(
            HostCommand.RECOVER_AND_START_SCENE,
            SceneInputPolicy.resolve(SessionState.ERROR, InputAction.TRIPLE_CLICK)
        )
        assertEquals(
            HostCommand.SHOW_SCENE_SWITCHING,
            SceneInputPolicy.resolve(SessionState.STARTING, InputAction.TRIPLE_CLICK)
        )
    }

    /** 验证宿主返回、运行提示和场景私有滑动命令。 */
    @Test
    fun resolvesOtherHostInputCommands() {
        assertEquals(
            HostCommand.CONFIGURE_CONNECTION,
            SceneInputPolicy.resolve(SessionState.READY, InputAction.CLICK)
        )
        assertEquals(
            HostCommand.NAVIGATE_BACK,
            SceneInputPolicy.resolve(SessionState.READY, InputAction.DOUBLE_CLICK)
        )
        assertEquals(
            HostCommand.SHOW_ALREADY_RUNNING,
            SceneInputPolicy.resolve(SessionState.RUNNING, InputAction.CLICK)
        )
        assertEquals(
            HostCommand.NONE,
            SceneInputPolicy.resolve(SessionState.RUNNING, InputAction.SLIDE_UP)
        )
    }
}
