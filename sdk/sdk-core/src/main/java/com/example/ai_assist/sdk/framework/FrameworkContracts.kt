package com.example.ai_assist.sdk.framework

import android.app.Activity
import android.view.ViewGroup
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** SDK 会话状态。合法转换见 [StateMachine]。 */
enum class SdkState {
    UNINITIALIZED,
    READY,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/** 场景声明需要的硬件能力。 */
enum class CapabilityType {
    CAMERA,
    INPUT
}

/** SDK 全局配置占位。日志、缓存和默认超时以后在这里填。 */
interface SdkConfig

/** 场景配置。业务字段由场景定义，Core 只读取可选凭据标识。 */
interface SceneConfig {
    val credentialId: String?
        get() = null
}

/** 按不透明标识提供敏感凭据；真实凭据不得进入普通配置或日志。 */
fun interface CredentialProvider {
    fun credentialFor(sceneId: String, credentialId: String): String?
}

/** 硬件能力配置占位。图像尺寸、格式和相机参数以后在这里填。 */
interface CapabilityConfig

/** 镜腿或触控产生的输入动作。 */
enum class InputAction {
    CLICK,
    DOUBLE_CLICK,
    TRIPLE_CLICK,
    LONG_PRESS,
    SLIDE_FORWARD,
    SLIDE_BACKWARD,
    SLIDE_UP,
    SLIDE_DOWN
}

/**
 * 一帧已编码的图像。
 * SessionManager 在单帧处理结束后负责删除 [file]。
 */
data class Frame(
    val file: File,
    val imageRotationDegrees: Int,
    val capturedAtMillis: Long
)

/** Core 运行帧流所需的场景参数。 */
data class FrameStreamConfig(
    val intervalMillis: Long,
    val cameraConfig: CapabilityConfig
) {
    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
    }
}

/** 单次场景会话。场景管理业务会话，Core 的 SessionManager 管理帧流。 */
interface Session {
    val frameStreamConfig: FrameStreamConfig
    suspend fun start()
    suspend fun processFrame(frame: Frame, sequenceId: Long): SceneResult?
    suspend fun close()
}

/** 能力租约占位。场景不能自己创建或释放 Camera。 */
interface CapabilityLease

/** 场景私有结果占位。SDK 不解析其字段。 */
interface ScenePayload

/** 通用壳可展示的结果摘要。 */
data class SceneSummary(
    val title: String,
    val status: String,
    val confidence: Double? = null,
    val warnings: List<String> = emptyList()
)

/** 交给场景 Renderer 的业务内容。 */
data class SceneContent(
    val rendererKey: String,
    val scenePayload: ScenePayload
)

/** 一次完整场景结果。通用壳看 summary，Renderer 看 content。 */
data class SceneResult(
    val sceneId: String,
    val sessionId: String,
    val sequenceId: Long,
    val updatedAtMillis: Long,
    val summary: SceneSummary,
    val content: SceneContent? = null
)

/** 会话级事件。 */
sealed interface SdkEvent {
    val sceneId: String
    val sessionId: String
    val sequenceId: Long?
    val occurredAtMillis: Long

    data class StateChanged(
        override val sceneId: String,
        override val sessionId: String,
        override val sequenceId: Long?,
        override val occurredAtMillis: Long,
        val state: SdkState
    ) : SdkEvent

    data class ResultReceived(
        override val sceneId: String,
        override val sessionId: String,
        override val sequenceId: Long?,
        override val occurredAtMillis: Long,
        val result: SceneResult
    ) : SdkEvent

    data class ErrorOccurred(
        override val sceneId: String,
        override val sessionId: String,
        override val sequenceId: Long?,
        override val occurredAtMillis: Long,
        val code: String,
        val message: String,
        val recoverable: Boolean
    ) : SdkEvent

    data class DiagnosticReported(
        override val sceneId: String,
        override val sessionId: String,
        override val sequenceId: Long?,
        override val occurredAtMillis: Long,
        val code: String,
        val message: String
    ) : SdkEvent
}

/**
 * 相机调度占位。
 * 默认 1 秒一帧；上一帧未完成时不发出新帧。场景不得自己打开或释放相机。
 */
interface CameraScheduler {
    fun frames(intervalMillis: Long, cameraConfig: CapabilityConfig): Flow<Frame>
}

/** 活动场景可见的会话入口。填业务逻辑时只使用这些成员，不再改签名。 */
interface SceneContext {
    val sessionId: String
    val config: SceneConfig

    /** 已租到的能力。场景不能自己创建或释放相机。 */
    fun requireCapability(type: CapabilityType): CapabilityLease

    /** 本场景的接口密钥。未配置时返回 null，不得写入普通配置或日志。 */
    fun credential(): String?

    /** 当前会话的输入动作。没有输入能力时为空流。 */
    val inputActions: Flow<InputAction>

    /** 场景请求 Core 停止当前会话；资源释放仍由 Core 完成。 */
    fun requestStop()

    /** 单帧失败用 recoverable=true，会话保持 RUNNING。 */
    suspend fun reportError(code: String, message: String, recoverable: Boolean)

    /** 诊断信息，不包含密钥。 */
    suspend fun reportDiagnostic(code: String, message: String)
}

/** 场景结果渲染器。 */
interface SceneRenderer {
    val rendererKey: String
    fun render(container: ViewGroup, content: SceneContent)
    fun clear()
}

/** 场景向 SDK 注册的入口。 */
interface SceneProvider {
    val sceneId: String
    val sceneName: String
    val version: String
    val requiredCapabilities: List<CapabilityType>
    fun createSceneConfig(): SceneConfig
    fun createRenderer(): SceneRenderer
    fun createSession(sceneContext: SceneContext): Session
}

/** 有启动前页面的场景可选实现；没有启动页的场景直接使用默认配置启动。 */
interface SceneLaunchProvider : SceneProvider {
    fun createLaunchController(): SceneLaunchController
}

/**
 * 场景私有启动流程的通用宿主入口，不包含连接、二维码或模式等业务字段。
 * 双目宿主可为每只眼的容器分别调用 [attach]；实现必须保持各容器显示状态一致。
 */
interface SceneLaunchController {
    fun attach(
        activity: Activity,
        container: ViewGroup,
        onStart: (SceneConfig) -> Unit,
        onExit: () -> Unit
    )

    fun onInput(action: InputAction)
    fun close()
}

/** 场景在通用菜单中的展示信息；资源由场景模块提供。 */
data class ScenePresentation(
    val menuTitle: String,
    val menuIconRes: Int
)

/** 一个可安装场景对宿主暴露的注册信息。 */
data class SceneRegistration(
    val provider: SceneProvider,
    val presentation: ScenePresentation
)

/** 编译期安装场景的通用入口。 */
fun interface SceneModule {
    fun createRegistration(): SceneRegistration
}

/** sdk-core 入口。场景通过它注册和启动。 */
interface CvGameCore {
    /** UNINITIALIZED -> READY；READY 状态重复调用保持幂等。 */
    fun initialize()
    fun registerScene(provider: SceneProvider)
    fun unregisterScene(sceneId: String)
    fun getScenes(): List<SceneProvider>
    /**
     * 启动场景。
     * [sessionConfig] 不为空时覆盖场景默认配置，只对这一局生效。
     */
    fun startScene(sceneId: String, sessionConfig: SceneConfig? = null): Flow<SdkEvent>
    fun stopScene()
    fun getCurrentState(): SdkState
    fun onAppBackground()
    fun onAppForeground()
}

/**
 * Core 层会话调度。顺序消费 Frame Flow，上一帧处理完成后才请求下一帧。
 */
interface SessionManager {
    suspend fun runSession(
        session: Session,
        sceneContext: SceneContext,
        onStarted: () -> Unit,
        onResult: suspend (SceneResult) -> Unit
    )
}

/**
 * 状态机。
 * 合法转换：UNINITIALIZED->READY，READY->STARTING，
 * STARTING->RUNNING|STOPPING|ERROR，RUNNING->STOPPING|ERROR，
 * STOPPING->READY，ERROR->READY。
 */
interface StateMachine {
    val state: StateFlow<SdkState>
    fun transition(to: SdkState)
}

/** 状态、结果、错误和诊断的统一出口。 */
interface EventDispatcher {
    val events: SharedFlow<SdkEvent>
    fun eventsForSession(sessionId: String): Flow<SdkEvent>
}

/** 能力租约。Core 管生命周期，Capability 管硬件操作。 */
interface ResourceManager {
    /** Returns whether this host has registered an implementation for [type]. */
    fun supports(type: CapabilityType): Boolean
    fun acquire(type: CapabilityType, sceneId: String): CapabilityLease
    fun releaseAll()
}
