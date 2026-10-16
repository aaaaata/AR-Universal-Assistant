package com.example.ai_assist.scene

import com.example.ai_assist.sdk.capability.InputAction
import com.example.ai_assist.sdk.capability.InputActionPublisher
import com.example.ai_assist.sdk.engine.SceneEngine
import com.example.ai_assist.sdk.engine.SdkEvent
import com.example.ai_assist.sdk.engine.SessionState
import com.example.ai_assist.sdk.engine.SceneStartMode
import com.example.ai_assist.sdk.engine.SceneStartModeConfig
import com.example.ai_assist.sdk.scene.SceneRegistration
import com.example.ai_assist.sdk.network.ScannedServerConfig
import com.example.ai_assist.sdk.network.ServerEndpointSceneConfig
import com.example.ai_assist.sdk.network.SelectableServerEndpointSceneConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 连接 App 页面与 SDK，统一控制场景的启动、停止和输入。 */
internal class SceneController(
    private val sceneEngine: SceneEngine,
    private val inputPublisher: InputActionPublisher?,
    registrations: List<SceneRegistration>
) {
    private val registrationsById: MutableMap<String, SceneRegistration>
    private val defaultServerConfigs: Map<String, ServerEndpointSceneConfig>
    private val registrationLock = Any()

    val scenes: List<AppSceneSummary>
    val state: StateFlow<SessionState> = sceneEngine.state
    val events: Flow<SdkEvent> = sceneEngine.events

    init {
        registrationsById = registrations.associateBy { it.provider.descriptor.sceneId }.toMutableMap()
        require(registrationsById.size == registrations.size) { "App scene IDs must be unique" }
        defaultServerConfigs = registrations.mapNotNull { registration ->
            val config = registration.config as? ServerEndpointSceneConfig
                ?: return@mapNotNull null
            registration.provider.descriptor.sceneId to config
        }.toMap()
        scenes = registrations.map { registration ->
            AppSceneSummary(
                sceneId = registration.provider.descriptor.sceneId,
                title = registration.presentation.menuTitle,
                iconRes = registration.presentation.menuIconRes,
                requiredCapabilities = registration.provider.descriptor.requiredCapabilities
            )
        }
    }

    suspend fun startScene(sceneId: String) {
        val registration = requireNotNull(synchronized(registrationLock) {
            registrationsById[sceneId]
        }) {
            "Scene is not registered by the app: $sceneId"
        }
        try {
            sceneEngine.startScene(sceneId, registration.config)
        } finally {
            resetServerEndpoint(sceneId)
        }
    }

    fun supportsServerConfiguration(sceneId: String): Boolean = synchronized(registrationLock) {
        registrationsById[sceneId]?.config is ServerEndpointSceneConfig
    }

    fun supportsServerConfiguration(sceneId: String, qrSceneId: String): Boolean =
        synchronized(registrationLock) {
            val config = registrationsById[sceneId]?.config as? ServerEndpointSceneConfig
                ?: return@synchronized false
            qrSceneId in config.supportedQrSceneIds
        }

    fun currentServerBaseUrl(sceneId: String): String? = synchronized(registrationLock) {
        (registrationsById[sceneId]?.config as? ServerEndpointSceneConfig)?.serverBaseUrl
    }

    fun startModes(sceneId: String): List<SceneStartMode> = synchronized(registrationLock) {
        (registrationsById[sceneId]?.config as? SceneStartModeConfig)?.startModes.orEmpty()
    }

    fun selectedStartMode(sceneId: String): SceneStartMode? = synchronized(registrationLock) {
        val config = registrationsById[sceneId]?.config as? SceneStartModeConfig
            ?: return@synchronized null
        config.startModes.firstOrNull { it.id == config.selectedStartModeId }
    }

    fun updateStartMode(sceneId: String, modeId: String): Boolean = synchronized(registrationLock) {
        if (state.value != SessionState.READY) return@synchronized false
        val registration = registrationsById[sceneId] ?: return@synchronized false
        val config = registration.config as? SceneStartModeConfig ?: return@synchronized false
        if (config.startModes.none { it.id == modeId }) return@synchronized false
        registrationsById[sceneId] = registration.copy(config = config.withStartMode(modeId))
        true
    }

    fun updateServiceMode(sceneId: String, modeId: String): Boolean =
        synchronized(registrationLock) {
            if (state.value != SessionState.READY) return@synchronized false
            val registration = registrationsById[sceneId] ?: return@synchronized false
            val config = registration.config as? SelectableServerEndpointSceneConfig
                ?: return@synchronized false
            registrationsById[sceneId] = registration.copy(config = config.withServiceMode(modeId))
            true
        }

    fun updateServerEndpoint(sceneId: String, scanned: ScannedServerConfig): String? =
        synchronized(registrationLock) {
            if (state.value != SessionState.READY) return@synchronized null
            val registration = registrationsById[sceneId] ?: return@synchronized null
            val config = registration.config as? ServerEndpointSceneConfig
                ?: return@synchronized null
            if (scanned.sceneId !in config.supportedQrSceneIds) return@synchronized null
            registrationsById[sceneId] = registration.copy(
                config = config.withServerBaseUrl(scanned.baseUrl)
            )
            sceneId
        }

    /** 丢弃尚未消费的扫码地址，并恢复场景随 APK 提供的默认服务地址。 */
    fun resetServerEndpoint(sceneId: String): Boolean = synchronized(registrationLock) {
        val defaultConfig = defaultServerConfigs[sceneId] ?: return@synchronized false
        val registration = registrationsById[sceneId] ?: return@synchronized false
        registrationsById[sceneId] = registration.copy(config = defaultConfig)
        true
    }

    suspend fun stopScene() {
        sceneEngine.stopScene()
    }

    suspend fun close() {
        sceneEngine.close()
    }

    suspend fun publishInput(action: InputAction) {
        inputPublisher?.publish(action)
    }
}
