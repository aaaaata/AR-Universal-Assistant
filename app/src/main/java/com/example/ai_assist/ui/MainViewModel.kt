package com.example.ai_assist.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ai_assist.assembly.AppAssembler
import com.example.ai_assist.assembly.AppEnvironment
import com.example.ai_assist.connection.ServerQrConnectionController
import com.example.ai_assist.connection.ServerQrScanEvent
import com.example.ai_assist.permission.PermissionResult
import com.example.ai_assist.sdk.capability.CapabilityRequirement
import com.example.ai_assist.sdk.capability.InputAction
import com.example.ai_assist.sdk.capability.StandardCapabilities
import com.example.ai_assist.sdk.engine.SdkEvent
import com.example.ai_assist.sdk.engine.SceneStartMode
import com.example.ai_assist.sdk.engine.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.net.URI

/** 管理主菜单、活动场景页面和 SDK 会话。 */
internal class MainViewModel(
    appEnvironment: AppEnvironment
) : ViewModel() {
    private val sceneController = appEnvironment.sceneController
    private val serverQrConnectionController = appEnvironment.serverQrConnectionController
    private val menuItems = sceneController.scenes.map { scene ->
        SceneMenuItem.Registered(scene.sceneId, scene.title, scene.iconRes)
    } + SceneMenuItem.AddScene
    private val mutableUiState = MutableStateFlow(
        MainUiState(sessionState = sceneController.state.value, menuItems = menuItems)
    )
    private val effectChannel = Channel<MainUiEffect>(Channel.BUFFERED)
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingAction: PendingAction? = null

    val uiState: StateFlow<MainUiState> = mutableUiState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()
    val sceneRenderRouter = appEnvironment.sceneRenderRouter

    init {
        observeSceneController()
    }

    fun onInputAction(action: InputAction) {
        viewModelScope.launch {
            val state = mutableUiState.value
            Log.i(
                TIMING_TAG,
                "event=input action=$action screen=${state.screen} " +
                    "session_state=${sceneController.state.value} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime()}"
            )
            if (state.screen == AppScreen.MENU) {
                handleMenuInput(action)
            } else if (state.connectionScanState != ConnectionScanState.IDLE) {
                handleConnectionScanInput(action)
            } else if (state.isSelectingStartMode) {
                handleStartModeSelectionInput(action)
            } else {
                sceneController.publishInput(action)
                handleHostCommand(SceneInputPolicy.resolve(sceneController.state.value, action))
            }
        }
    }

    fun onMenuItemClicked(index: Int) {
        viewModelScope.launch {
            if (index !in menuItems.indices) return@launch
            mutableUiState.value = mutableUiState.value.copy(selectedMenuIndex = index)
            activateSelectedMenuItem()
        }
    }

    fun openScene(sceneId: String) {
        viewModelScope.launch {
            val item = menuItems.filterIsInstance<SceneMenuItem.Registered>()
                .firstOrNull { it.sceneId == sceneId }
                ?: return@launch
            enterScene(item)
        }
    }

    fun startSelectedScene(serviceMode: String, startModeId: String) {
        viewModelScope.launch {
            val item = menuItems.filterIsInstance<SceneMenuItem.Registered>()
                .firstOrNull { it.sceneId == MAHJONG_SCENE_ID } ?: return@launch
            if (serviceMode.isNotBlank() &&
                !sceneController.updateServiceMode(item.sceneId, serviceMode)
            ) {
                showMessage("无法切换服务")
                return@launch
            }
            if (startModeId.isBlank()) {
                if (serviceMode != "local") {
                    showMessage("请选择开局模式")
                    return@launch
                }
                enterScene(item)
                requestServerConfiguration()
                return@launch
            }
            if (!sceneController.updateStartMode(item.sceneId, startModeId)) {
                showMessage("无法切换开局模式")
                return@launch
            }
            enterScene(item)
            requestActiveSceneStartPermission(item.sceneId)
        }
    }

    fun onAppPaused() {
        pendingAction = null
        cancelServerConfiguration(showConfirmation = false)
        mutableUiState.value = mutableUiState.value.copy(startModeOptions = emptyList())
        viewModelScope.launch {
            runCatching { sceneController.stopScene() }
                .onFailure { Log.e(TAG, "Stop scene on pause failed", it) }
        }
    }

    fun onScenePermissionResult(sceneId: String, result: PermissionResult) {
        viewModelScope.launch {
            Log.i(
                TIMING_TAG,
                "event=permission_result scene_id=$sceneId result=${result::class.java.simpleName} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime()}"
            )
            val action = pendingAction
            if (action?.sceneId != sceneId || mutableUiState.value.activeSceneId != sceneId) {
                return@launch
            }
            pendingAction = null
            when (result) {
                PermissionResult.Granted -> when (action.type) {
                    PendingActionType.START_SCENE -> startSceneAfterPermission(sceneId)
                    PendingActionType.CONFIGURE_CONNECTION -> startServerConfiguration(sceneId)
                }
                is PermissionResult.Denied -> showMessage("需要相机权限")
            }
        }
    }

    private fun observeSceneController() {
        viewModelScope.launch {
            sceneController.state.collect { sessionState ->
                Log.i(
                    TIMING_TAG,
                    "event=sdk_state state=$sessionState elapsed_ms=${SystemClock.elapsedRealtime()}"
                )
                val current = mutableUiState.value
                mutableUiState.value = current.copy(sessionState = sessionState)
                if (sessionState == SessionState.READY && current.screen == AppScreen.SCENE) {
                    current.activeSceneId?.let { sceneId ->
                        effectChannel.send(MainUiEffect.ResetSceneContent(sceneId))
                    }
                }
            }
        }
        viewModelScope.launch {
            sceneController.events.collect(::handleSdkEvent)
        }
    }

    private suspend fun handleMenuInput(action: InputAction) {
        when (action) {
            InputAction.SLIDE_FORWARD -> moveMenuSelection(1)
            InputAction.SLIDE_BACKWARD -> moveMenuSelection(-1)
            InputAction.CLICK -> activateSelectedMenuItem()
            InputAction.DOUBLE_CLICK -> effectChannel.send(MainUiEffect.NavigateBack)
            else -> Unit
        }
    }

    private fun moveMenuSelection(offset: Int) {
        val current = mutableUiState.value
        mutableUiState.value = current.copy(
            selectedMenuIndex = SceneInputPolicy.moveMenuSelection(
                current.selectedMenuIndex,
                current.menuItems.size,
                offset
            )
        )
    }

    private suspend fun activateSelectedMenuItem() {
        when (val item = mutableUiState.value.menuItems[mutableUiState.value.selectedMenuIndex]) {
            is SceneMenuItem.Registered -> if (item.sceneId == MAHJONG_SCENE_ID) {
                effectChannel.send(MainUiEffect.OpenModePicker())
            } else {
                enterScene(item)
            }
            SceneMenuItem.AddScene -> effectChannel.send(MainUiEffect.OpenSceneCatalog)
        }
    }

    private suspend fun enterScene(item: SceneMenuItem.Registered) {
        Log.i(
            TIMING_TAG,
            "event=scene_entered scene_id=${item.sceneId} elapsed_ms=${SystemClock.elapsedRealtime()}"
        )
        mutableUiState.value = mutableUiState.value.copy(
            screen = AppScreen.SCENE,
            activeSceneId = item.sceneId,
            activeSceneTitle = item.title,
            activeSceneSupportsServerConfiguration =
                sceneController.supportsServerConfiguration(item.sceneId),
            connectionStatusText = serverStatusText(item.sceneId),
            activeStartMode = sceneController.selectedStartMode(item.sceneId)
        )
        effectChannel.send(MainUiEffect.ResetSceneContent(item.sceneId))
    }

    private suspend fun returnToMenu() {
        val sceneId = mutableUiState.value.activeSceneId
        pendingAction = null
        serverQrConnectionController.cancel()
        mutableUiState.value = mutableUiState.value.copy(
            screen = AppScreen.MENU,
            activeSceneId = null,
            activeSceneTitle = null,
            activeSceneSupportsServerConfiguration = false,
            connectionScanState = ConnectionScanState.IDLE,
            connectionStatusText = null,
            startModeOptions = emptyList(),
            activeStartMode = null
        )
        effectChannel.send(MainUiEffect.ClearSceneContent)
        runCatching { sceneController.stopScene() }
            .onFailure { Log.e(TAG, "Stop scene while returning to menu failed", it) }
        sceneId?.let(sceneController::resetServerEndpoint)
    }

    private suspend fun handleSdkEvent(event: SdkEvent) {
        when (event) {
            is SdkEvent.ResultReceived -> {
                if (event.sceneId == mutableUiState.value.activeSceneId) {
                    Log.i(
                        TIMING_TAG,
                        "event=result_received scene_id=${event.sceneId} " +
                            "session_id=${event.sessionId} status=${event.result.summary.status} " +
                            "elapsed_ms=${SystemClock.elapsedRealtime()}"
                    )
                    effectChannel.send(MainUiEffect.RenderResult(event.result))
                }
            }
            is SdkEvent.NoticeUpdated -> {
                if (event.sceneId == mutableUiState.value.activeSceneId) {
                    effectChannel.send(MainUiEffect.PresentNotice(event.sceneId, event.update))
                }
            }
            is SdkEvent.ErrorOccurred -> effectChannel.send(
                MainUiEffect.ShowMessage(
                    if (event.recoverable) "本次处理失败，场景继续运行" else "场景运行失败，请重试"
                )
            )
            is SdkEvent.DiagnosticReported -> Log.i(
                TIMING_TAG,
                "event=diagnostic scene_id=${event.sceneId} code=${event.code} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime()}"
            )
            is SdkEvent.StateChanged -> Unit
        }
    }

    private suspend fun handleHostCommand(command: HostCommand) {
        when (command) {
            HostCommand.START_SCENE -> requestActiveSceneStart()
            HostCommand.CONFIGURE_CONNECTION -> requestServerConfiguration()
            HostCommand.STOP_SCENE -> stopActiveScene()
            HostCommand.RECOVER_AND_START_SCENE -> recoverAndStartActiveScene()
            HostCommand.NAVIGATE_BACK -> returnToMenu()
            HostCommand.SHOW_SCENE_SWITCHING -> showMessage("场景状态切换中，请稍候")
            HostCommand.SHOW_ALREADY_RUNNING -> showMessage("场景运行中，无需手动触发")
            HostCommand.SHOW_AUTOMATIC_PROCESSING -> showMessage("场景自动处理中，无需手动发送")
            HostCommand.SHOW_STOP_HINT -> showMessage("三击结束当前场景")
            HostCommand.NONE -> Unit
        }
    }

    private suspend fun requestActiveSceneStart() {
        if (pendingAction != null) return
        val sceneId = mutableUiState.value.activeSceneId ?: return
        val startModes = sceneController.startModes(sceneId)
        if (startModes.size > 1) {
            mutableUiState.value = mutableUiState.value.copy(startModeOptions = startModes)
            return
        }
        requestActiveSceneStartPermission(sceneId)
    }

    private suspend fun requestActiveSceneStartPermission(sceneId: String) {
        val scene = sceneController.scenes.firstOrNull { it.sceneId == sceneId } ?: return
        pendingAction = PendingAction(sceneId, PendingActionType.START_SCENE)
        Log.i(
            TIMING_TAG,
            "event=scene_start_requested scene_id=$sceneId elapsed_ms=${SystemClock.elapsedRealtime()}"
        )
        effectChannel.send(
            MainUiEffect.RequestScenePermissions(sceneId, scene.requiredCapabilities)
        )
    }

    private suspend fun handleStartModeSelectionInput(action: InputAction) {
        val options = mutableUiState.value.startModeOptions
        when (action) {
            InputAction.SLIDE_FORWARD -> options.getOrNull(0)?.let { selectStartMode(it) }
            InputAction.SLIDE_BACKWARD -> options.getOrNull(1)?.let { selectStartMode(it) }
            InputAction.DOUBLE_CLICK -> {
                mutableUiState.value = mutableUiState.value.copy(startModeOptions = emptyList())
                showMessage("已取消开局")
            }
            InputAction.CLICK,
            InputAction.TRIPLE_CLICK -> showMessage("请选择前滑完整录制或后滑普通开局")
            else -> Unit
        }
    }

    private suspend fun selectStartMode(mode: SceneStartMode) {
        val sceneId = mutableUiState.value.activeSceneId ?: return
        if (!sceneController.updateStartMode(sceneId, mode.id)) {
            showMessage("无法切换开局模式")
            return
        }
        mutableUiState.value = mutableUiState.value.copy(
            startModeOptions = emptyList(),
            activeStartMode = mode
        )
        requestActiveSceneStartPermission(sceneId)
    }

    private suspend fun requestServerConfiguration() {
        if (pendingAction != null) return
        val sceneId = mutableUiState.value.activeSceneId ?: return
        if (!sceneController.supportsServerConfiguration(sceneId)) {
            showMessage("当前场景不支持扫码配置")
            return
        }
        pendingAction = PendingAction(sceneId, PendingActionType.CONFIGURE_CONNECTION)
        effectChannel.send(
            MainUiEffect.RequestScenePermissions(
                sceneId,
                setOf(CapabilityRequirement(StandardCapabilities.CAMERA, exclusive = true))
            )
        )
    }

    private fun startServerConfiguration(sceneId: String) {
        val started = serverQrConnectionController.start(sceneId) { event ->
            viewModelScope.launch { handleServerQrEvent(event) }
        }
        if (!started) {
            showMessage("无法启动二维码扫描")
            return
        }
        mutableUiState.value = mutableUiState.value.copy(
            connectionScanState = ConnectionScanState.SCANNING,
            connectionStatusText = "扫描服务二维码..."
        )
        showMessage("请对准服务二维码")
    }

    private suspend fun handleConnectionScanInput(action: InputAction) {
        when (action) {
            InputAction.CLICK -> cancelServerConfiguration(showConfirmation = true)
            InputAction.DOUBLE_CLICK -> {
                cancelServerConfiguration(showConfirmation = false)
                returnToMenu()
            }
            InputAction.TRIPLE_CLICK -> showMessage("请先完成或取消服务配置")
            else -> Unit
        }
    }

    private fun cancelServerConfiguration(showConfirmation: Boolean) {
        if (!serverQrConnectionController.isScanning &&
            mutableUiState.value.connectionScanState == ConnectionScanState.IDLE
        ) return
        serverQrConnectionController.cancel()
        val sceneId = mutableUiState.value.activeSceneId
        mutableUiState.value = mutableUiState.value.copy(
            connectionScanState = ConnectionScanState.IDLE,
            connectionStatusText = sceneId?.let(::serverStatusText)
        )
        if (showConfirmation) showMessage("已取消服务配置")
    }

    private fun handleServerQrEvent(event: ServerQrScanEvent) {
        when (event) {
            is ServerQrScanEvent.Searching -> {
                if (event.attemptedFrames % 5 == 0) {
                    mutableUiState.value = mutableUiState.value.copy(
                        connectionStatusText = "扫描服务二维码... 已尝试${event.attemptedFrames}帧"
                    )
                }
            }
            ServerQrScanEvent.InvalidQrCode -> {
                mutableUiState.value = mutableUiState.value.copy(
                    connectionStatusText = "二维码格式不正确"
                )
                showMessage("二维码不是有效的服务配置")
            }
            ServerQrScanEvent.IncompatibleScene -> showMessage("二维码与当前场景不匹配")
            is ServerQrScanEvent.Validating -> mutableUiState.value = mutableUiState.value.copy(
                connectionScanState = ConnectionScanState.VALIDATING,
                connectionStatusText = "已识别二维码，正在校验服务..."
            )
            is ServerQrScanEvent.ServerUnavailable -> {
                mutableUiState.value = mutableUiState.value.copy(
                    connectionScanState = ConnectionScanState.SCANNING,
                    connectionStatusText = "服务不可用：${event.authority}"
                )
                showMessage("服务不可用，请检查眼镜 Wi-Fi")
            }
            is ServerQrScanEvent.Connected -> {
                mutableUiState.value = mutableUiState.value.copy(
                    connectionScanState = ConnectionScanState.IDLE,
                    connectionStatusText = "服务：${event.authority}"
                )
                showMessage("服务配置成功")
                effectChannel.trySend(MainUiEffect.OpenModePicker(startModeOnly = true))
            }
            is ServerQrScanEvent.Failed -> {
                mutableUiState.value = mutableUiState.value.copy(
                    connectionScanState = ConnectionScanState.IDLE,
                    connectionStatusText = mutableUiState.value.activeSceneId?.let(::serverStatusText)
                )
                showMessage(event.message)
            }
        }
    }

    private fun serverStatusText(sceneId: String): String? =
        sceneController.currentServerBaseUrl(sceneId)?.let { baseUrl ->
            val authority = runCatching { URI(baseUrl).authority }.getOrNull() ?: baseUrl
            "服务：$authority"
        }

    private suspend fun startSceneAfterPermission(sceneId: String) {
        effectChannel.send(MainUiEffect.ResetSceneContent(sceneId))
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TIMING_TAG, "event=scene_start_begin scene_id=$sceneId elapsed_ms=$startedAt")
        runCatching { sceneController.startScene(sceneId) }
            .onSuccess {
                val completedAt = SystemClock.elapsedRealtime()
                Log.i(
                    TIMING_TAG,
                    "event=scene_start_complete scene_id=$sceneId " +
                        "duration_ms=${completedAt - startedAt} elapsed_ms=$completedAt"
                )
            }
            .onFailure {
                Log.e(TAG, "Start scene failed: $sceneId", it)
                showMessage("场景启动失败，请重试")
            }
    }

    private suspend fun stopActiveScene() {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TIMING_TAG, "event=scene_stop_begin elapsed_ms=$startedAt")
        // A triple-click ends the current session and returns to scene selection, per the
        // glasses navigation flow. returnToMenu() also clears the active server state.
        returnToMenu()
        Log.i(
            TIMING_TAG,
            "event=scene_stop_complete duration_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "elapsed_ms=${SystemClock.elapsedRealtime()}"
        )
    }

    private suspend fun recoverAndStartActiveScene() {
        runCatching { sceneController.stopScene() }
            .onFailure { Log.e(TAG, "Recover failed scene failed", it) }
            .onSuccess { requestActiveSceneStart() }
    }

    private fun showMessage(message: String) {
        effectChannel.trySend(MainUiEffect.ShowMessage(message))
    }

    override fun onCleared() {
        serverQrConnectionController.close()
        closeScope.launch {
            runCatching { sceneController.close() }
                .onFailure { Log.e(TAG, "Close scene controller failed", it) }
            closeScope.cancel()
        }
        super.onCleared()
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val TIMING_TAG = "AssistTiming"
        const val MAHJONG_SCENE_ID = "mahjong-guangdong"
    }

    private data class PendingAction(
        val sceneId: String,
        val type: PendingActionType
    )

    private enum class PendingActionType {
        START_SCENE,
        CONFIGURE_CONNECTION
    }
}

/** 在 ViewModel 首次创建时完成 App 装配。 */
internal class MainViewModelFactory(
    context: Context
) : ViewModelProvider.Factory {
    private val applicationContext = context.applicationContext

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(AppAssembler.assemble(applicationContext)) as T
    }
}
