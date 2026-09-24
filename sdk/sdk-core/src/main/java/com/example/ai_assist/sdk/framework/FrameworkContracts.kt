package com.example.ai_assist.sdk.framework

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

/** 场景配置占位。服务地址、采集频率和业务参数由场景定义。 */
interface SceneConfig

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
 * 场景上传后负责删除 [file]。
 */
data class Frame(
    val file: File,
    val imageRotationDegrees: Int,
    val capturedAtMillis: Long
)

/** 一个场景会话占位。 */
interface Session

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
    val cameraScheduler: CameraScheduler

    /** 已租到的能力。场景不能自己创建或释放相机。 */
    fun requireCapability(type: CapabilityType): CapabilityLease

    /** 本场景的接口密钥。未配置时返回 null，不得写入普通配置或日志。 */
    fun credential(): String?

    /** 当前会话的输入动作。没有输入能力时为空流。 */
    val inputActions: Flow<InputAction>

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
    fun start(sceneContext: SceneContext): Flow<SceneResult>
    fun stop()
}

/** sdk-core 入口。场景通过它注册和启动。 */
interface CvGameCore {
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
 * 会话管理、请求调度和跳帧门禁。
 * 上一帧未完成时 [submitFrame] 返回 null。
 */
interface SessionManager {
    fun createSession(sceneId: String, provider: SceneProvider): Session
    suspend fun submitFrame(image: Frame): SceneResult?
    fun endSession()
}

/**
 * 状态机。
 * 合法转换：UNINITIALIZED->READY，READY->STARTING，
 * STARTING->RUNNING|ERROR，RUNNING->STOPPING|ERROR，
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
    fun acquire(type: CapabilityType, sceneId: String): CapabilityLease
    fun releaseAll()
}
