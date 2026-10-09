package com.example.ai_assist.scene.mahjong.framework

import com.example.ai_assist.sdk.framework.CapabilityType
import com.example.ai_assist.sdk.framework.SceneConfig
import com.example.ai_assist.sdk.framework.SceneContent
import com.example.ai_assist.sdk.framework.SceneContext
import com.example.ai_assist.sdk.framework.SceneLaunchProvider
import com.example.ai_assist.sdk.framework.SceneRenderer
import com.example.ai_assist.sdk.framework.SceneResult
import com.example.ai_assist.sdk.framework.SceneStartMode
import com.example.ai_assist.sdk.framework.SceneSummary
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal fun interface MahjongCloudServiceFactory {
    fun create(config: MahjongSceneConfig, credential: String): MahjongCloudService
}

/** 新框架 SceneProvider 契约上的麻将云端交换实现。 */
class MahjongSceneProvider internal constructor(
    private val serviceFactory: MahjongCloudServiceFactory,
    private val fieldMapper: MahjongFieldMapper
) : SceneLaunchProvider {
    constructor() : this(
        MahjongCloudServiceFactory(RetrofitMahjongCloudService::create),
        DefaultMahjongFieldMapper()
    )

    override val sceneId: String = "mahjong"
    override val sceneName: String = "麻将助手"
    override val version: String = "1.0.0"
    override val requiredCapabilities: List<CapabilityType> =
        listOf(CapabilityType.CAMERA)
    override val supportedConnectionSceneIds: Set<String> = setOf(sceneId, "mahjong-guangdong")
    override val startModes: List<SceneStartMode> = listOf(
        SceneStartMode(MahjongRecordingMode.RECORD.wireValue, "录制对局"),
        SceneStartMode(MahjongRecordingMode.NORMAL.wireValue, "普通对局")
    )

    private val activeJob = AtomicReference<Job?>(null)
    private val renderer = MahjongRenderer()
    private var resultStabilizer = MahjongResultStabilizer()
    private var diagnosticStabilizer = MahjongDiagnosticStabilizer()
    private var captureGuidance = MahjongCaptureGuidance()

    override fun createSceneConfig(): SceneConfig = MahjongSceneConfig()

    override fun createSceneConfig(
        connection: com.example.ai_assist.sdk.framework.SceneConnection?,
        startModeId: String?
    ): SceneConfig {
        val requiredConnection = requireNotNull(connection) {
            "Mahjong scene requires a service connection"
        }
        val recordingModeId = requireNotNull(startModeId) {
            "Mahjong scene requires a game mode"
        }
        return MahjongSceneConfig(
            serverBaseUrl = requiredConnection.serverBaseUrl,
            serviceMode = when (requiredConnection.serviceModeId.lowercase()) {
                "local" -> MahjongServiceMode.LOCAL
                "cloud" -> MahjongServiceMode.CLOUD
                else -> throw IllegalArgumentException(
                    "Unsupported Mahjong service mode: ${requiredConnection.serviceModeId}"
                )
            },
            recordingMode = MahjongRecordingMode.entries.firstOrNull {
                it.wireValue == recordingModeId
            } ?: throw IllegalArgumentException(
                "Unsupported Mahjong recording mode: $recordingModeId"
            ),
            captureIntervalMillis = requiredConnection.captureIntervalMillis
        )
    }

    override fun createRenderer(): SceneRenderer = renderer

    override fun start(sceneContext: SceneContext): Flow<SceneResult> = flow {
        val job = requireNotNull(currentCoroutineContext()[Job])
        check(activeJob.compareAndSet(null, job)) { "Mahjong scene is already running" }
        var cloudSessionId: String? = null
        var cloudService: MahjongCloudService? = null
        try {
            val config = sceneContext.config as? MahjongSceneConfig
                ?: failStartup(sceneContext, "MAHJONG_CONFIG_INVALID", "MahjongSceneConfig is required")
            resultStabilizer = MahjongResultStabilizer()
            diagnosticStabilizer = MahjongDiagnosticStabilizer()
            captureGuidance = MahjongCaptureGuidance()
            renderer.prepare(config.serviceMode)
            val credential = sceneContext.credential()?.takeIf(String::isNotBlank)
                ?: failStartup(sceneContext, "MAHJONG_CREDENTIAL_MISSING", "Mahjong API key is required")

            try {
                sceneContext.requireCapability(CapabilityType.CAMERA)
            } catch (error: Throwable) {
                failStartup(
                    sceneContext,
                    "MAHJONG_CAMERA_UNAVAILABLE",
                    error.message ?: "Camera capability is unavailable",
                    error
                )
            }
            val service = try {
                serviceFactory.create(config, credential)
            } catch (error: Throwable) {
                failStartup(
                    sceneContext,
                    "MAHJONG_CLOUD_CLIENT_CREATE_FAILED",
                    error.message ?: "Unable to create Mahjong cloud client",
                    error
                )
            }
            cloudService = service
            val sessionId = service.createSession().getOrElse { error ->
                failStartup(
                    sceneContext,
                    "MAHJONG_SESSION_CREATE_FAILED",
                    error.message ?: "Unable to create Mahjong cloud session",
                    error
                )
            }
            cloudSessionId = sessionId
            sceneContext.reportDiagnostic(
                "MAHJONG_SESSION_CREATED",
                "Mahjong cloud session created"
            )

            var sequenceId = 0L
            sceneContext.cameraScheduler.frames(
                intervalMillis = config.captureIntervalMillis,
                cameraConfig = config.cameraConfig
            ).collect { frame ->
                sequenceId += 1
                try {
                    renderGuidance(captureGuidance.onFrameReady(frame))
                    val result = service.submitImage(
                        sessionId,
                        MahjongImageSubmission(
                            bytes = frame.file.readBytes(),
                            capturedAtMillis = frame.capturedAtMillis,
                            imageRotationDegrees = frame.imageRotationDegrees
                        )
                    )
                        .getOrElse { throw it }
                    val response = result as? MahjongAnalysisResponse
                        ?: throw IllegalArgumentException("Unsupported Mahjong cloud result")
                    val payload = fieldMapper.map(response).copy(serviceMode = config.serviceMode)
                    val sceneResult = SceneResult(
                        sceneId = sceneId,
                        sessionId = sceneContext.sessionId,
                        sequenceId = sequenceId,
                        updatedAtMillis = System.currentTimeMillis(),
                        summary = SceneSummary(
                            title = payload.suggestedDiscard
                                ?.let { "建议打出：$it" } ?: "麻将分析完成",
                            status = response.status.ifBlank { "unknown" },
                            confidence = response.confidence?.suggestion?.coerceIn(0.0, 1.0),
                            warnings = response.warnings + response.confidence?.reasons.orEmpty()
                        ),
                        content = SceneContent(MahjongScenePayload.RENDERER_KEY, payload)
                    )
                    diagnosticStabilizer.evaluate(response.diagnosticCode)?.let { update ->
                        renderer.renderDiagnosticWarning(
                            update.code?.let(fieldMapper::diagnosticMessage)
                        )
                    }
                    renderGuidance(captureGuidance.onResult(response))
                    if (resultStabilizer.evaluate(response, sceneResult) ==
                        MahjongResultStabilizer.Decision.PUBLISH
                    ) {
                        emit(sceneResult)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    sceneContext.reportError(
                        code = "MAHJONG_FRAME_SUBMIT_FAILED",
                        message = error.message ?: "Mahjong frame upload failed",
                        recoverable = true
                    )
                } finally {
                    if (!frame.file.delete() && frame.file.exists()) {
                        sceneContext.reportDiagnostic(
                            "MAHJONG_FRAME_DELETE_FAILED",
                            "Unable to delete uploaded frame"
                        )
                    }
                }
            }
        } finally {
            activeJob.compareAndSet(job, null)
            renderer.renderDiagnosticWarning(null)
            renderGuidance(captureGuidance.reset())
            cloudSessionId?.let { sessionId ->
                cloudService?.let { service ->
                    withContext(NonCancellable) {
                        service.endSession(sessionId)
                            .onFailure { error ->
                                sceneContext.reportError(
                                    "MAHJONG_SESSION_END_FAILED",
                                    error.message ?: "Unable to end Mahjong cloud session",
                                    recoverable = true
                                )
                            }
                    }
                }
            }
        }
    }

    override fun stop() {
        activeJob.get()?.cancel(CancellationException("Mahjong scene stopped"))
    }

    private fun renderGuidance(update: MahjongGuidanceUpdate?) {
        when (update) {
            is MahjongGuidanceUpdate.Show -> renderer.renderNotice(update.message)
            MahjongGuidanceUpdate.Dismiss -> renderer.renderNotice(null)
            null -> Unit
        }
    }

    private suspend fun failStartup(
        context: SceneContext,
        code: String,
        message: String,
        cause: Throwable? = null
    ): Nothing {
        context.reportError(code, message, recoverable = false)
        throw IllegalStateException(message, cause)
    }

}
