package com.example.ai_assist.ui

import com.example.ai_assist.sdk.capability.CapabilityRequirement
import com.example.ai_assist.sdk.engine.SessionState
import com.example.ai_assist.sdk.engine.SceneStartMode
import com.example.ai_assist.sdk.scene.SceneResult
import com.example.ai_assist.sdk.scene.SceneNoticeUpdate

/** 通用客户端当前显示的一级页面。 */
internal enum class AppScreen {
    MENU,
    SCENE
}

/** 场景页的本地服务二维码配置状态。 */
internal enum class ConnectionScanState {
    IDLE,
    SCANNING,
    VALIDATING
}

/** 主菜单中的场景入口。 */
internal sealed interface SceneMenuItem {
    val title: String

    data class Registered(
        val sceneId: String,
        override val title: String,
        val iconRes: Int
    ) : SceneMenuItem

    data object AddScene : SceneMenuItem {
        override val title: String = "添加场景"
    }
}

/** 通用客户端页面状态。 */
internal data class MainUiState(
    val sessionState: SessionState,
    val menuItems: List<SceneMenuItem>,
    val screen: AppScreen = AppScreen.MENU,
    val selectedMenuIndex: Int = 0,
    val activeSceneId: String? = null,
    val activeSceneTitle: String? = null,
    val activeSceneSupportsServerConfiguration: Boolean = false,
    val connectionScanState: ConnectionScanState = ConnectionScanState.IDLE,
    val connectionStatusText: String? = null,
    val startModeOptions: List<SceneStartMode> = emptyList(),
    val activeStartMode: SceneStartMode? = null
) {
    init {
        require(menuItems.isNotEmpty()) { "At least one menu item is required" }
        require(selectedMenuIndex in menuItems.indices) { "Selected menu index is out of range" }
    }

    val isSelectingStartMode: Boolean = startModeOptions.isNotEmpty()

    val showIdleInstruction: Boolean =
        screen == AppScreen.SCENE &&
            sessionState == SessionState.READY &&
            (isSelectingStartMode || connectionScanState != ConnectionScanState.IDLE)

    val showRunningInstruction: Boolean =
        screen == AppScreen.SCENE && sessionState == SessionState.RUNNING
}

/** 通用客户端只消费一次的页面效果。 */
internal sealed interface MainUiEffect {
    data class ShowMessage(val message: String) : MainUiEffect
    data class RenderResult(val result: SceneResult) : MainUiEffect
    data class PresentNotice(val sceneId: String, val update: SceneNoticeUpdate) : MainUiEffect
    data class ResetSceneContent(val sceneId: String) : MainUiEffect
    data object ClearSceneContent : MainUiEffect
    data object OpenSceneCatalog : MainUiEffect
    data class OpenModePicker(val startModeOnly: Boolean = false) : MainUiEffect
    data object NavigateBack : MainUiEffect
    data class RequestScenePermissions(
        val sceneId: String,
        val requirements: Set<CapabilityRequirement>
    ) : MainUiEffect
}
