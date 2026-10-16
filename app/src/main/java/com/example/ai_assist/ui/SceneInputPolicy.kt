package com.example.ai_assist.ui

import com.example.ai_assist.sdk.capability.InputAction
import com.example.ai_assist.sdk.engine.SessionState

/** ViewModel 根据场景页输入执行的命令。 */
internal enum class HostCommand {
    START_SCENE,
    CONFIGURE_CONNECTION,
    STOP_SCENE,
    RECOVER_AND_START_SCENE,
    NAVIGATE_BACK,
    SHOW_SCENE_SWITCHING,
    SHOW_ALREADY_RUNNING,
    SHOW_AUTOMATIC_PROCESSING,
    SHOW_STOP_HINT,
    NONE
}

/** 集中定义输入动作在不同会话状态下的含义。 */
internal object SceneInputPolicy {
    fun resolve(state: SessionState, action: InputAction): HostCommand = when (action) {
        InputAction.TRIPLE_CLICK -> when (state) {
            SessionState.READY -> HostCommand.NONE
            SessionState.RUNNING -> HostCommand.STOP_SCENE
            SessionState.ERROR -> HostCommand.RECOVER_AND_START_SCENE
            else -> HostCommand.SHOW_SCENE_SWITCHING
        }
        InputAction.DOUBLE_CLICK -> HostCommand.NAVIGATE_BACK
        InputAction.CLICK -> when (state) {
            SessionState.READY -> HostCommand.CONFIGURE_CONNECTION
            SessionState.RUNNING -> HostCommand.SHOW_ALREADY_RUNNING
            else -> HostCommand.NONE
        }
        InputAction.SLIDE_FORWARD -> if (state == SessionState.RUNNING) {
            HostCommand.SHOW_AUTOMATIC_PROCESSING
        } else {
            HostCommand.NONE
        }
        InputAction.SLIDE_BACKWARD -> if (state == SessionState.RUNNING) {
            HostCommand.SHOW_STOP_HINT
        } else {
            HostCommand.NONE
        }
        InputAction.SLIDE_UP,
        InputAction.SLIDE_DOWN,
        InputAction.UNKNOWN -> HostCommand.NONE
    }

    fun moveMenuSelection(currentIndex: Int, itemCount: Int, offset: Int): Int {
        require(itemCount > 0) { "Item count must be positive" }
        return Math.floorMod(currentIndex + offset, itemCount)
    }
}
