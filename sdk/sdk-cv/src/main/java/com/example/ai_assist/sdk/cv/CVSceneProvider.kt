package com.example.ai_assist.sdk.cv

import com.example.ai_assist.sdk.scene.SceneProvider
import com.example.ai_assist.sdk.scene.SceneResult
import com.example.ai_assist.sdk.scene.SceneSession
import com.example.ai_assist.sdk.capability.StandardCapabilities
import com.example.ai_assist.sdk.capability.CameraCapability
import com.example.ai_assist.sdk.capability.CameraConfig
import com.example.ai_assist.sdk.capability.CameraFrameListener
import com.example.ai_assist.sdk.capability.CameraFrameSelector
import com.example.ai_assist.sdk.capability.CapturedFrame
import com.example.ai_assist.sdk.engine.SceneConfig
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.random.Random

/** CV 场景的拍摄调度策略。 */
data class CapturePolicy(
    val intervalMillis: Long,
    val jitterMillis: Long = 0L,
    val startupJitterMillis: Long = 0L
) {
    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        require(jitterMillis >= 0) { "jitterMillis must not be negative" }
        require(startupJitterMillis >= 0) { "startupJitterMillis must not be negative" }
    }
}

/** 单个 CV 会话的业务处理器，由具体场景实现业务协议。 */
interface CVProcessor<R : Any> {
    suspend fun start()
    suspend fun process(frame: CapturedFrame, sequenceId: Long): R
    suspend fun stop()
}

/** CV 通用流程发布的稳定诊断和错误代码。 */
data class CVEventCodes(
    val processingStarted: String = "CV_FRAME_PROCESSING_STARTED",
    val processingCompleted: String = "CV_FRAME_PROCESSING_COMPLETED",
    val processingFailed: String = "CV_FRAME_PROCESSING_FAILED",
    val cameraFailed: String = "CV_CAMERA_FAILED"
)

/** 场景对单帧业务结果的发布决策。 */
enum class CVResultDecision {
    PUBLISH,
    HOLD,
    HOLD_AND_RETRY
}

/**
 * 拍摄、串行处理、结果发布和资源清理的标准 CV 场景骨架。
 *
 * 具体场景只负责策略、业务 Processor、结果映射和可选交互。
 */
abstract class CVSceneProvider<R : Any> : SceneProvider {
    @Volatile
    private var activeSession: ActiveSession<R>? = null

    protected abstract fun capturePolicy(config: SceneConfig): CapturePolicy
    protected abstract fun cameraConfig(config: SceneConfig): CameraConfig
    protected abstract fun createProcessor(session: SceneSession): CVProcessor<R>
    protected abstract fun mapResult(result: R, session: SceneSession): SceneResult

    /** Called before upload so capture-quality notices can be emitted. */
    protected open suspend fun onFrameReady(frame: CapturedFrame, session: SceneSession) = Unit

    /** Called after a successful response so result-based notices can be emitted. */
    protected open suspend fun onResultReady(result: R, session: SceneSession) = Unit

    /** 允许场景在映射后执行稳定性判断；默认立即发布。 */
    protected open fun evaluateResult(
        result: R,
        mappedResult: SceneResult,
        session: SceneSession
    ): CVResultDecision = CVResultDecision.PUBLISH

    protected open val eventCodes = CVEventCodes()

    /** 可选场景逻辑，例如订阅输入动作。 */
    protected open suspend fun onSessionStarted(
        session: SceneSession,
        scope: CoroutineScope
    ) = Unit

    final override suspend fun start(session: SceneSession) {
        check(activeSession == null) { "${descriptor.sceneId} is already running" }
        require(descriptor.requiredCapabilities.any {
            it.capabilityId == StandardCapabilities.CAMERA
        }) { "A CV scene must require the camera capability" }

        val processor = createProcessor(session)
        var running: ActiveSession<R>? = null
        try {
            processor.start()
            val policy = capturePolicy(session.config)
            running = ActiveSession(
                sceneSession = session,
                processor = processor,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                frameGate = FrameProcessingGate(
                    intervalMillis = policy.intervalMillis,
                    jitterMillis = policy.jitterMillis,
                    startupJitterMillis = policy.startupJitterMillis
                )
            )
            activeSession = running
            onSessionStarted(session, running.scope)
            val camera = session.requireCapability(
                StandardCapabilities.CAMERA,
                CameraCapability::class.java
            )
            camera.start(
                cameraConfig(session.config),
                CameraFrameSelector(running.frameGate::tryBeginCapture),
                frameListener(running)
            )
        } catch (error: Throwable) {
            activeSession = null
            running?.scope?.cancel()
            runCatching { processor.stop() }
            throw error
        }
    }

    final override suspend fun stop() {
        val running = activeSession ?: return
        activeSession = null
        running.scope.coroutineContext[Job]?.cancelAndJoin()
        running.processor.stop()
    }

    private fun frameListener(running: ActiveSession<R>) = object : CameraFrameListener {
        override fun onFrameCaptured(frame: CapturedFrame) {
            if (activeSession !== running || !running.frameGate.tryBeginProcessing()) {
                running.frameGate.completeCapture()
                frame.file.delete()
                return
            }
            running.scope.launch { processFrame(running, frame) }
        }

        override fun onError(error: Throwable) {
            running.frameGate.completeCapture()
            running.scope.launch { handleFatalCameraError(running, error) }
        }
    }

    private suspend fun processFrame(running: ActiveSession<R>, frame: CapturedFrame) {
        var allowImmediateNextCapture = false
        try {
            onFrameReady(frame, running.sceneSession)
            running.sceneSession.reportDiagnostic(
                eventCodes.processingStarted,
                "CV frame processing started"
            )
            val result = running.processor.process(
                frame,
                running.sequenceId.incrementAndGet()
            )
            onResultReady(result, running.sceneSession)
            val mappedResult = mapResult(result, running.sceneSession)
            when (evaluateResult(result, mappedResult, running.sceneSession)) {
                CVResultDecision.PUBLISH -> running.sceneSession.publishResult(mappedResult)
                CVResultDecision.HOLD -> Unit
                CVResultDecision.HOLD_AND_RETRY -> allowImmediateNextCapture = true
            }
            running.sceneSession.reportDiagnostic(
                eventCodes.processingCompleted,
                "CV frame processing completed"
            )
        } catch (error: Throwable) {
            if (activeSession === running) {
                running.sceneSession.reportError(
                    eventCodes.processingFailed,
                    safeMessage(error),
                    recoverable = true
                )
            }
        } finally {
            frame.file.delete()
            running.frameGate.completeProcessing(allowImmediateNextCapture)
        }
    }

    private suspend fun handleFatalCameraError(
        running: ActiveSession<R>,
        error: Throwable
    ) {
        if (activeSession !== running) return
        running.sceneSession.reportError(
            eventCodes.cameraFailed,
            safeMessage(error),
            recoverable = false
        )
        activeSession = null
        runCatching { running.processor.stop() }
        running.scope.cancel()
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName

    private data class ActiveSession<R : Any>(
        val sceneSession: SceneSession,
        val processor: CVProcessor<R>,
        val scope: CoroutineScope,
        val frameGate: FrameProcessingGate,
        val sequenceId: AtomicLong = AtomicLong(0)
    )
}
