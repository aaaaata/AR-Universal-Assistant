package com.example.ai_assist.sdk.framework

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 线程安全状态机；状态转换只接受契约声明的路径。 */
class DefaultStateMachine(initialState: SdkState = SdkState.UNINITIALIZED) : StateMachine {
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<SdkState> = mutableState

    @Synchronized
    override fun transition(to: SdkState) {
        val from = mutableState.value
        if (from == to) return
        require(to in ALLOWED_TRANSITIONS.getValue(from)) {
            "Illegal SDK state transition: $from -> $to"
        }
        mutableState.value = to
    }

    private companion object {
        val ALLOWED_TRANSITIONS = mapOf(
            SdkState.UNINITIALIZED to setOf(SdkState.READY),
            SdkState.READY to setOf(SdkState.STARTING),
            SdkState.STARTING to setOf(
                SdkState.RUNNING,
                SdkState.STOPPING,
                SdkState.ERROR
            ),
            SdkState.RUNNING to setOf(SdkState.STOPPING, SdkState.ERROR),
            SdkState.STOPPING to setOf(SdkState.READY),
            SdkState.ERROR to setOf(SdkState.READY)
        )
    }
}

/** 状态、结果、错误和诊断的统一事件出口。 */
class DefaultEventDispatcher : EventDispatcher {
    private val mutableEvents = MutableSharedFlow<SdkEvent>(
        replay = EVENT_REPLAY,
        extraBufferCapacity = EVENT_BUFFER
    )
    override val events: SharedFlow<SdkEvent> = mutableEvents

    override fun eventsForSession(sessionId: String): Flow<SdkEvent> =
        events.filter { it.sessionId == sessionId }

    suspend fun dispatch(event: SdkEvent) {
        mutableEvents.emit(event)
    }

    fun tryDispatch(event: SdkEvent) {
        check(mutableEvents.tryEmit(event)) { "SDK event buffer is full" }
    }

    private companion object {
        const val EVENT_REPLAY = 32
        const val EVENT_BUFFER = 64
    }
}

/** 当前场景会话可见的通用上下文。 */
class DefaultSceneContext(
    override val sessionId: String,
    private val sceneId: String,
    override val config: SceneConfig,
    private val resourceManager: ResourceManager,
    private val credentialProvider: CredentialProvider,
    private val eventDispatcher: DefaultEventDispatcher,
    override val inputActions: Flow<InputAction>,
    private val stopRequester: () -> Unit
) : SceneContext {
    private val fatalErrorReported = AtomicBoolean(false)
    private val leases = linkedMapOf<CapabilityType, CapabilityLease>()

    @Synchronized
    override fun requireCapability(type: CapabilityType): CapabilityLease =
        leases.getOrPut(type) { resourceManager.acquire(type, sceneId) }

    override fun credential(): String? = config.credentialId?.let { credentialId ->
        credentialProvider.credentialFor(sceneId, credentialId)
    }

    override fun requestStop() = stopRequester()

    override suspend fun reportError(code: String, message: String, recoverable: Boolean) {
        if (!recoverable) fatalErrorReported.set(true)
        eventDispatcher.dispatch(
            SdkEvent.ErrorOccurred(
                sceneId = sceneId,
                sessionId = sessionId,
                sequenceId = null,
                occurredAtMillis = System.currentTimeMillis(),
                code = code,
                message = message,
                recoverable = recoverable
            )
        )
    }

    override suspend fun reportDiagnostic(code: String, message: String) {
        eventDispatcher.dispatch(
            SdkEvent.DiagnosticReported(
                sceneId = sceneId,
                sessionId = sessionId,
                sequenceId = null,
                occurredAtMillis = System.currentTimeMillis(),
                code = code,
                message = message
            )
        )
    }

    internal fun hasFatalError(): Boolean = fatalErrorReported.get()

    internal fun acquireRequiredCapabilities(types: List<CapabilityType>) {
        types.forEach(::requireCapability)
    }
}

/** Core 独占 Frame Flow；顺序 collect 自然保证上一帧处理完成后才采下一帧。 */
class DefaultSessionManager(
    private val cameraScheduler: CameraScheduler
) : SessionManager {
    override suspend fun runSession(
        session: Session,
        sceneContext: SceneContext,
        onStarted: () -> Unit,
        onResult: suspend (SceneResult) -> Unit
    ) {
        var sequenceId = 0L
        try {
            session.start()
            onStarted()
            val frameConfig = session.frameStreamConfig
            cameraScheduler.frames(
                intervalMillis = frameConfig.intervalMillis,
                cameraConfig = frameConfig.cameraConfig
            ).collect { frame ->
                sequenceId += 1
                try {
                    session.processFrame(frame, sequenceId)?.let { onResult(it) }
                } finally {
                    if (!frame.file.delete() && frame.file.exists()) {
                        sceneContext.reportDiagnostic(
                            "FRAME_DELETE_FAILED",
                            "Unable to delete processed frame"
                        )
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }
}

/** 默认 Core：SessionManager 管 Frame Flow，场景 Session 只处理单帧业务。 */
class FrameworkRuntime(
    private val cameraScheduler: CameraScheduler,
    private val credentialProvider: CredentialProvider = CredentialProvider { _, _ -> null },
    private val resourceManager: ResourceManager = DefaultResourceManager {
        (cameraScheduler as? AutoCloseable)?.close()
    },
    private val stateMachine: DefaultStateMachine = DefaultStateMachine(),
    private val eventDispatcher: DefaultEventDispatcher = DefaultEventDispatcher(),
    private val sessionManager: SessionManager = DefaultSessionManager(cameraScheduler)
) : CvGameCore {
    private val providers = linkedMapOf<String, SceneProvider>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inputActions = MutableSharedFlow<InputAction>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var activeScene: ActiveScene? = null

    val state: StateFlow<SdkState> = stateMachine.state
    val events: SharedFlow<SdkEvent> = eventDispatcher.events

    @Synchronized
    override fun initialize() {
        when (stateMachine.state.value) {
            SdkState.UNINITIALIZED -> stateMachine.transition(SdkState.READY)
            SdkState.READY -> Unit
            else -> error("Cannot initialize SDK while state is ${stateMachine.state.value}")
        }
    }

    @Synchronized
    override fun registerScene(provider: SceneProvider) {
        check(activeScene == null) { "Cannot register a scene while another scene is running" }
        check(provider.sceneId !in providers) { "Scene is already registered: ${provider.sceneId}" }
        providers[provider.sceneId] = provider
    }

    @Synchronized
    override fun unregisterScene(sceneId: String) {
        check(activeScene?.provider?.sceneId != sceneId) { "Cannot unregister the active scene" }
        providers.remove(sceneId)
    }

    @Synchronized
    override fun getScenes(): List<SceneProvider> = providers.values.toList()

    override fun startScene(sceneId: String, sessionConfig: SceneConfig?): Flow<SdkEvent> {
        val provider: SceneProvider
        val sessionId: String
        val context: DefaultSceneContext
        synchronized(this) {
            check(stateMachine.state.value == SdkState.READY) {
                "SDK is not ready: ${stateMachine.state.value}"
            }
            provider = requireNotNull(providers[sceneId]) { "Scene is not registered: $sceneId" }
            val unsupported = provider.requiredCapabilities.filterNot(resourceManager::supports)
            check(unsupported.isEmpty()) {
                "Scene $sceneId requires unsupported capabilities: $unsupported"
            }
            sessionId = UUID.randomUUID().toString()
            context = DefaultSceneContext(
                sessionId = sessionId,
                sceneId = sceneId,
                config = sessionConfig ?: provider.createSceneConfig(),
                resourceManager = resourceManager,
                credentialProvider = credentialProvider,
                eventDispatcher = eventDispatcher,
                inputActions = inputActions,
                stopRequester = ::stopScene
            )
            transition(provider, sessionId, SdkState.STARTING)
            val active = ActiveScene(provider, sessionId)
            activeScene = active
            active.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runScene(active, context)
            }
        }
        return eventDispatcher.eventsForSession(sessionId)
    }

    override fun stopScene() {
        val active = synchronized(this) {
            activeScene?.also { scene ->
                if (scene.stopping.compareAndSet(false, true)) {
                    when (stateMachine.state.value) {
                        SdkState.STARTING, SdkState.RUNNING ->
                            transition(scene.provider, scene.sessionId, SdkState.STOPPING)
                        else -> Unit
                    }
                }
            }
        } ?: return
        active.job?.cancel(CancellationException("Scene stopped"))
    }

    override fun getCurrentState(): SdkState = stateMachine.state.value

    override fun onAppBackground() = stopScene()

    override fun onAppForeground() {
        synchronized(this) {
            if (activeScene == null && stateMachine.state.value == SdkState.ERROR) {
                stateMachine.transition(SdkState.READY)
            }
        }
    }

    /** App 将眼镜输入适配为公共动作后送入当前运行会话。 */
    fun publishInput(action: InputAction) {
        inputActions.tryEmit(action)
    }

    fun close() {
        stopScene()
        scope.cancel()
        resourceManager.releaseAll()
    }

    private suspend fun runScene(active: ActiveScene, context: DefaultSceneContext) {
        var failed = false
        try {
            context.acquireRequiredCapabilities(active.provider.requiredCapabilities)
            val session = active.provider.createSession(context)
            sessionManager.runSession(
                session = session,
                sceneContext = context,
                onStarted = {
                    synchronized(this) {
                        if (stateMachine.state.value != SdkState.STARTING || active.stopping.get()) {
                            throw CancellationException("Scene stopped during startup")
                        }
                        transition(active.provider, active.sessionId, SdkState.RUNNING)
                    }
                },
                onResult = { result ->
                    eventDispatcher.dispatch(
                        SdkEvent.ResultReceived(
                            sceneId = active.provider.sceneId,
                            sessionId = active.sessionId,
                            sequenceId = result.sequenceId,
                            occurredAtMillis = System.currentTimeMillis(),
                            result = result
                        )
                    )
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failed = true
            if (!context.hasFatalError()) {
                context.reportError(
                    code = "SCENE_RUNTIME_FAILED",
                    message = error.message ?: error::class.java.simpleName,
                    recoverable = false
                )
            }
            synchronized(this) {
                if (stateMachine.state.value in setOf(SdkState.STARTING, SdkState.RUNNING)) {
                    transition(active.provider, active.sessionId, SdkState.ERROR)
                }
            }
        } finally {
            withContext(NonCancellable) {
                resourceManager.releaseAll()
                synchronized(this@FrameworkRuntime) {
                    if (activeScene === active) activeScene = null
                    when (stateMachine.state.value) {
                        SdkState.STARTING, SdkState.RUNNING -> {
                            transition(active.provider, active.sessionId, SdkState.STOPPING)
                            transition(active.provider, active.sessionId, SdkState.READY)
                        }
                        SdkState.STOPPING ->
                            transition(active.provider, active.sessionId, SdkState.READY)
                        SdkState.ERROR -> if (failed) {
                            transition(active.provider, active.sessionId, SdkState.READY)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun transition(provider: SceneProvider, sessionId: String, state: SdkState) {
        stateMachine.transition(state)
        eventDispatcher.tryDispatch(
            SdkEvent.StateChanged(
                sceneId = provider.sceneId,
                sessionId = sessionId,
                sequenceId = null,
                occurredAtMillis = System.currentTimeMillis(),
                state = state
            )
        )
    }

    private class ActiveScene(
        val provider: SceneProvider,
        val sessionId: String,
        val stopping: AtomicBoolean = AtomicBoolean(false),
        var job: Job? = null
    )
}

class DefaultResourceManager(
    private val supportedCapabilities: Set<CapabilityType> =
        setOf(CapabilityType.CAMERA, CapabilityType.INPUT),
    private val onReleaseAll: () -> Unit = {}
) : ResourceManager {
    private val owners = mutableMapOf<CapabilityType, String>()

    override fun supports(type: CapabilityType): Boolean = type in supportedCapabilities

    override fun acquire(type: CapabilityType, sceneId: String): CapabilityLease = synchronized(owners) {
        check(supports(type)) { "Capability is not supported by this host: $type" }
        val owner = owners[type]
        require(owner == null || owner == sceneId) { "$type is already owned by $owner" }
        owners[type] = sceneId
        RuntimeCapabilityLease(type, sceneId)
    }

    override fun releaseAll() {
        synchronized(owners) { owners.clear() }
        onReleaseAll()
    }
}

private data class RuntimeCapabilityLease(
    val type: CapabilityType,
    val sceneId: String
) : CapabilityLease
