package com.example.ai_assist.sdk.runtime

import com.example.ai_assist.sdk.engine.CredentialProvider
import com.example.ai_assist.sdk.engine.EmptySceneConfig
import com.example.ai_assist.sdk.engine.SceneConfig
import com.example.ai_assist.sdk.engine.SceneEngine
import com.example.ai_assist.sdk.engine.SdkConfig
import com.example.ai_assist.sdk.engine.SdkEvent
import com.example.ai_assist.sdk.engine.SessionState
import com.example.ai_assist.sdk.capability.CapabilityLeaseConflictException
import com.example.ai_assist.sdk.capability.CapabilityUnavailableException
import com.example.ai_assist.sdk.capability.SdkCapability
import com.example.ai_assist.sdk.scene.SceneDescriptor
import com.example.ai_assist.sdk.scene.SceneProvider
import com.example.ai_assist.sdk.scene.SceneResult
import com.example.ai_assist.sdk.scene.SceneNoticeUpdate
import com.example.ai_assist.sdk.scene.SceneSession
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 场景生命周期、状态事件和 Capability 租约的 Core 实现。
 *
 * @param credentialProvider 按场景提供敏感凭据的安全入口。
 */
class CoreSceneEngine(
    private val credentialProvider: CredentialProvider = CredentialProvider { null }
) : SceneEngine {
    private val sceneRegistry = SceneRegistry()
    private val capabilityRegistry = CapabilityRegistry()
    private val lifecycleMutex = Mutex()
    private val eventMutex = Mutex()
    private val mutableState = MutableStateFlow(SessionState.UNINITIALIZED)
    private val mutableEvents = MutableSharedFlow<SdkEvent>()

    private var sdkConfig: SdkConfig? = null
    private var lastSession: SessionIdentity? = null
    @Volatile
    private var activeSession: ActiveSession? = null

    /** 当前串行会话状态。 */
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** 当前和后续会话产生的统一事件流。 */
    override val events: Flow<SdkEvent> = mutableEvents.asSharedFlow()

    /** 初始化全局配置并进入 READY。 */
    override fun initialize(config: SdkConfig) {
        check(mutableState.value == SessionState.UNINITIALIZED) {
            "SDK is already initialized"
        }
        sdkConfig = config
        mutableState.value = SessionState.READY
    }

    /** 注册编译期场景；活动会话期间不允许修改注册表。 */
    override fun registerScene(provider: SceneProvider) {
        check(activeSession == null) { "Cannot register a scene while a session is active" }
        sceneRegistry.register(provider)
    }

    /** 注册硬件能力；活动会话期间不允许修改注册表。 */
    override fun registerCapability(capabilityId: String, capability: SdkCapability) {
        check(activeSession == null) { "Cannot register a capability while a session is active" }
        capabilityRegistry.register(capabilityId, capability)
    }

    /** 返回按注册顺序排列的场景描述快照。 */
    override fun registeredScenes(): List<SceneDescriptor> =
        sceneRegistry.registeredScenes().map { it.descriptor }

    /** 串行启动一个场景，并在启动前一次性取得全部能力租约。 */
    override suspend fun startScene(sceneId: String, config: SceneConfig) {
        lifecycleMutex.withLock {
            check(sdkConfig != null) { "SDK is not initialized" }
            check(activeSession == null) { "Another scene is already active" }
            check(mutableState.value == SessionState.READY || mutableState.value == SessionState.ERROR) {
                "SDK cannot start a scene from ${mutableState.value}"
            }
            val provider = requireNotNull(sceneRegistry.find(sceneId)) {
                "Scene is not registered: $sceneId"
            }
            val sessionId = UUID.randomUUID().toString()
            lastSession = SessionIdentity(sceneId, sessionId)
            emitState(sceneId, sessionId, SessionState.STARTING)

            var lease: CapabilityLease? = null
            try {
                lease = capabilityRegistry.acquire(
                    sessionId,
                    provider.descriptor.requiredCapabilities,
                    provider.descriptor.optionalCapabilities
                )
                val session = ActiveSession(provider, sessionId, lease)
                activeSession = session
                provider.start(CoreSceneSession(session, config))
                if (session.fatalErrorReported) throw FatalSceneError()
                emitState(sceneId, sessionId, SessionState.RUNNING)
            } catch (error: Throwable) {
                lease?.release()
                activeSession = null
                if (error !is FatalSceneError) {
                    emitError(sceneId, sessionId, errorCode(error), safeMessage(error), false)
                }
                if (mutableState.value != SessionState.ERROR) {
                    emitState(sceneId, sessionId, SessionState.ERROR)
                }
                if (error is FatalSceneError) {
                    throw IllegalStateException("Scene reported an unrecoverable error")
                }
                throw error
            }
        }
    }

    /** 串行停止活动场景，并在所有退出路径释放能力租约。 */
    override suspend fun stopScene() {
        lifecycleMutex.withLock {
            val session = activeSession
            if (session == null) {
                if (mutableState.value == SessionState.ERROR) {
                    val failedSession = lastSession
                    if (failedSession == null) {
                        mutableState.value = SessionState.READY
                    } else {
                        emitState(
                            failedSession.sceneId,
                            failedSession.sessionId,
                            SessionState.READY
                        )
                        lastSession = null
                    }
                }
                return
            }

            emitState(session.sceneId, session.sessionId, SessionState.STOPPING)
            var stopError: Throwable? = null
            try {
                session.provider.stop()
            } catch (error: Throwable) {
                stopError = error
            } finally {
                session.lease.release()
                activeSession = null
            }

            if (stopError == null) {
                emitState(session.sceneId, session.sessionId, SessionState.READY)
                lastSession = null
            } else {
                emitError(
                    session.sceneId,
                    session.sessionId,
                    ERROR_SCENE_STOP_FAILED,
                    safeMessage(stopError),
                    false
                )
                emitState(session.sceneId, session.sessionId, SessionState.ERROR)
                throw stopError
            }
        }
    }

    /** 停止活动场景，关闭能力并清空全部注册信息。 */
    override suspend fun close() {
        try {
            stopScene()
        } finally {
            lifecycleMutex.withLock {
                capabilityRegistry.clear()
                sceneRegistry.clear()
                sdkConfig = null
                lastSession = null
                mutableState.value = SessionState.UNINITIALIZED
            }
        }
    }

    /** 验证并转发当前会话的场景结果。 */
    private suspend fun publishResult(session: ActiveSession, result: SceneResult) {
        if (activeSession !== session || mutableState.value !in RESULT_ACCEPTING_STATES) return
        require(result.sceneId == session.sceneId) { "Result sceneId does not match active scene" }
        require(result.sessionId == session.sessionId) { "Result sessionId does not match active session" }
        emitEvent(
            SdkEvent.ResultReceived(
                session.sceneId,
                session.sessionId,
                now(),
                result
            )
        )
    }

    private suspend fun publishNotice(session: ActiveSession, update: SceneNoticeUpdate) {
        if (activeSession !== session || mutableState.value !in RESULT_ACCEPTING_STATES) return
        emitEvent(
            SdkEvent.NoticeUpdated(
                session.sceneId,
                session.sessionId,
                now(),
                update
            )
        )
    }

    /** 转发场景错误，不可恢复错误同时释放租约并进入 ERROR。 */
    private suspend fun reportError(
        session: ActiveSession,
        code: String,
        message: String,
        recoverable: Boolean
    ) {
        if (activeSession !== session || mutableState.value !in RESULT_ACCEPTING_STATES) return
        require(code.isNotBlank()) { "Error code must not be blank" }
        require(message.isNotBlank()) { "Error message must not be blank" }
        emitError(session.sceneId, session.sessionId, code, message, recoverable)
        if (!recoverable) {
            session.fatalErrorReported = true
            session.lease.release()
            emitState(session.sceneId, session.sessionId, SessionState.ERROR)
        }
    }

    /** 转发当前会话的诊断信息。 */
    private suspend fun reportDiagnostic(
        session: ActiveSession,
        code: String,
        message: String
    ) {
        if (activeSession !== session || mutableState.value !in RESULT_ACCEPTING_STATES) return
        require(code.isNotBlank()) { "Diagnostic code must not be blank" }
        require(message.isNotBlank()) { "Diagnostic message must not be blank" }
        emitEvent(
            SdkEvent.DiagnosticReported(
                session.sceneId,
                session.sessionId,
                now(),
                code,
                message
            )
        )
    }

    /** 更新状态并串行发送对应状态事件。 */
    private suspend fun emitState(sceneId: String, sessionId: String, state: SessionState) {
        eventMutex.withLock {
            mutableState.value = state
            mutableEvents.emit(SdkEvent.StateChanged(sceneId, sessionId, now(), state))
        }
    }

    /** 串行发送错误事件。 */
    private suspend fun emitError(
        sceneId: String,
        sessionId: String,
        code: String,
        message: String,
        recoverable: Boolean
    ) {
        emitEvent(
            SdkEvent.ErrorOccurred(
                sceneId,
                sessionId,
                now(),
                code,
                message,
                recoverable
            )
        )
    }

    /** 串行发送单个会话事件。 */
    private suspend fun emitEvent(event: SdkEvent) {
        eventMutex.withLock { mutableEvents.emit(event) }
    }

    /** 将内部异常映射为稳定错误码。 */
    private fun errorCode(error: Throwable): String = when (error) {
        is CapabilityUnavailableException -> ERROR_CAPABILITY_UNAVAILABLE
        is CapabilityLeaseConflictException -> ERROR_CAPABILITY_LEASE_CONFLICT
        else -> ERROR_SCENE_START_FAILED
    }

    /** 返回不包含堆栈或凭据的错误文本。 */
    private fun safeMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

    /** 返回当前墙上时钟时间。 */
    private fun now(): Long = System.currentTimeMillis()

    /** Core 提供给单个活动场景的隔离会话接口。 */
    private inner class CoreSceneSession(
        private val session: ActiveSession,
        override val config: SceneConfig
    ) : SceneSession {
        override val sessionId: String = session.sessionId
        override val credentialProvider: CredentialProvider = this@CoreSceneEngine.credentialProvider

        /** 发布当前会话结果。 */
        override suspend fun publishResult(result: SceneResult) {
            this@CoreSceneEngine.publishResult(session, result)
        }

        override suspend fun publishNotice(update: SceneNoticeUpdate) {
            this@CoreSceneEngine.publishNotice(session, update)
        }

        /** 只允许访问当前场景已租用的能力。 */
        override fun <T : SdkCapability> requireCapability(
            capabilityId: String,
            type: Class<T>
        ): T = session.lease.requireCapability(capabilityId, type)

        /** 返回设备实际提供的可选能力。 */
        override fun <T : SdkCapability> optionalCapability(
            capabilityId: String,
            type: Class<T>
        ): T? = session.lease.optionalCapability(capabilityId, type)

        /** 发布当前会话错误。 */
        override suspend fun reportError(code: String, message: String, recoverable: Boolean) {
            this@CoreSceneEngine.reportError(session, code, message, recoverable)
        }

        /** 发布当前会话诊断信息。 */
        override suspend fun reportDiagnostic(code: String, message: String) {
            this@CoreSceneEngine.reportDiagnostic(session, code, message)
        }
    }

    /** 一个活动场景的 Provider、会话 ID 和能力租约。 */
    private data class ActiveSession(
        val provider: SceneProvider,
        val sessionId: String,
        val lease: CapabilityLease,
        @Volatile var fatalErrorReported: Boolean = false
    ) {
        val sceneId: String = provider.descriptor.sceneId
    }

    /** 最近一次会话的场景 ID 和会话 ID。 */
    private data class SessionIdentity(val sceneId: String, val sessionId: String)

    /** 标记场景已通过上下文报告致命错误。 */
    private class FatalSceneError : IllegalStateException()

    private companion object {
        private val RESULT_ACCEPTING_STATES = setOf(SessionState.STARTING, SessionState.RUNNING)
        private const val ERROR_CAPABILITY_UNAVAILABLE = "CAPABILITY_UNAVAILABLE"
        private const val ERROR_CAPABILITY_LEASE_CONFLICT = "CAPABILITY_LEASE_CONFLICT"
        private const val ERROR_SCENE_START_FAILED = "SCENE_START_FAILED"
        private const val ERROR_SCENE_STOP_FAILED = "SCENE_STOP_FAILED"
    }
}
