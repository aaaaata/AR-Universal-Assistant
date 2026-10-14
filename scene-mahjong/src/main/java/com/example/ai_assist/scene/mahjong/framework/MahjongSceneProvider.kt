package com.example.ai_assist.scene.mahjong.framework

import com.example.ai_assist.sdk.framework.CapabilityType
import com.example.ai_assist.sdk.framework.Frame
import com.example.ai_assist.sdk.framework.FrameStreamConfig
import com.example.ai_assist.sdk.framework.SceneConfig
import com.example.ai_assist.sdk.framework.SceneConnection
import com.example.ai_assist.sdk.framework.SceneContent
import com.example.ai_assist.sdk.framework.SceneContext
import com.example.ai_assist.sdk.framework.SceneLaunchProvider
import com.example.ai_assist.sdk.framework.SceneRenderer
import com.example.ai_assist.sdk.framework.SceneResult
import com.example.ai_assist.sdk.framework.SceneStartMode
import com.example.ai_assist.sdk.framework.SceneSummary
import com.example.ai_assist.sdk.framework.Session
import kotlinx.coroutines.CancellationException

internal fun interface MahjongCloudServiceFactory {
    fun create(config: MahjongSceneConfig, credential: String): MahjongCloudService
}

class MahjongSceneProvider internal constructor(
    private val serviceFactory: MahjongCloudServiceFactory,
    private val fieldMapper: MahjongFieldMapper
) : SceneLaunchProvider {
    constructor() : this(
        MahjongCloudServiceFactory(RetrofitMahjongCloudService::create),
        DefaultMahjongFieldMapper()
    )

    override val sceneId = "mahjong"
    override val sceneName = "麻将助手"
    override val version = "1.0.0"
    override val requiredCapabilities = listOf(CapabilityType.CAMERA)
    override val supportedConnectionSceneIds = setOf(sceneId, "mahjong-guangdong")
    override val startModes = listOf(
        SceneStartMode(MahjongRecordingMode.RECORD.wireValue, "录制对局"),
        SceneStartMode(MahjongRecordingMode.NORMAL.wireValue, "普通对局")
    )

    private val renderer = MahjongRenderer()

    override fun createSceneConfig(): SceneConfig = MahjongSceneConfig()

    override fun createSceneConfig(connection: SceneConnection?, startModeId: String?): SceneConfig {
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

    override fun createSession(sceneContext: SceneContext): Session {
        val config = sceneContext.config as? MahjongSceneConfig
            ?: throw IllegalArgumentException("MahjongSceneConfig is required")
        return MahjongSession(sceneId, sceneContext, config, renderer, serviceFactory, fieldMapper)
    }
}

private class MahjongSession(
    private val sceneId: String,
    private val context: SceneContext,
    private val config: MahjongSceneConfig,
    private val renderer: MahjongRenderer,
    private val serviceFactory: MahjongCloudServiceFactory,
    private val fieldMapper: MahjongFieldMapper
) : Session {
    override val frameStreamConfig = FrameStreamConfig(
        intervalMillis = config.captureIntervalMillis,
        cameraConfig = config.cameraConfig
    )

    private val resultStabilizer = MahjongResultStabilizer()
    private val diagnosticStabilizer = MahjongDiagnosticStabilizer()
    private val captureGuidance = MahjongCaptureGuidance()
    private var cloudService: MahjongCloudService? = null
    private var cloudSessionId: String? = null

    override suspend fun start() {
        renderer.prepare(config.serviceMode)
        val credential = context.credential()?.takeIf(String::isNotBlank)
            ?: failStartup("MAHJONG_CREDENTIAL_MISSING", "Mahjong API key is required")
        val service = try {
            serviceFactory.create(config, credential)
        } catch (error: Throwable) {
            failStartup(
                "MAHJONG_CLOUD_CLIENT_CREATE_FAILED",
                error.message ?: "Unable to create Mahjong cloud client",
                error
            )
        }
        cloudService = service
        cloudSessionId = service.createSession().getOrElse { error ->
            failStartup(
                "MAHJONG_SESSION_CREATE_FAILED",
                error.message ?: "Unable to create Mahjong cloud session",
                error
            )
        }
        context.reportDiagnostic("MAHJONG_SESSION_CREATED", "Mahjong cloud session created")
    }

    override suspend fun processFrame(frame: Frame, sequenceId: Long): SceneResult? {
        val service = checkNotNull(cloudService) { "Mahjong cloud service is not started" }
        val sessionId = checkNotNull(cloudSessionId) { "Mahjong cloud session is not started" }
        return try {
            renderGuidance(captureGuidance.onFrameReady(frame))
            val response = service.submitImage(
                sessionId,
                MahjongImageSubmission(
                    bytes = frame.file.readBytes(),
                    capturedAtMillis = frame.capturedAtMillis,
                    imageRotationDegrees = frame.imageRotationDegrees
                )
            ).getOrElse { throw it } as? MahjongAnalysisResponse
                ?: throw IllegalArgumentException("Unsupported Mahjong cloud result")
            val payload = fieldMapper.map(response).copy(serviceMode = config.serviceMode)
            val result = SceneResult(
                sceneId = sceneId,
                sessionId = context.sessionId,
                sequenceId = sequenceId,
                updatedAtMillis = System.currentTimeMillis(),
                summary = SceneSummary(
                    title = payload.suggestedDiscard?.let { "建议打出：$it" } ?: "麻将分析完成",
                    status = response.status.ifBlank { "unknown" },
                    confidence = response.confidence?.suggestion?.coerceIn(0.0, 1.0),
                    warnings = response.warnings + response.confidence?.reasons.orEmpty()
                ),
                content = SceneContent(MahjongScenePayload.RENDERER_KEY, payload)
            )
            diagnosticStabilizer.evaluate(response.diagnosticCode)?.let { update ->
                renderer.renderDiagnosticWarning(update.code?.let(fieldMapper::diagnosticMessage))
            }
            renderGuidance(captureGuidance.onResult(response))
            result.takeIf {
                resultStabilizer.evaluate(response, result) ==
                    MahjongResultStabilizer.Decision.PUBLISH
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            context.reportError(
                "MAHJONG_FRAME_SUBMIT_FAILED",
                error.message ?: "Mahjong frame upload failed",
                recoverable = true
            )
            null
        }
    }

    override suspend fun close() {
        renderer.renderDiagnosticWarning(null)
        renderGuidance(captureGuidance.reset())
        val service = cloudService
        val sessionId = cloudSessionId
        cloudService = null
        cloudSessionId = null
        if (service != null && sessionId != null) {
            service.endSession(sessionId).onFailure { error ->
                context.reportError(
                    "MAHJONG_SESSION_END_FAILED",
                    error.message ?: "Unable to end Mahjong cloud session",
                    recoverable = true
                )
            }
        }
    }

    private fun renderGuidance(update: MahjongGuidanceUpdate?) {
        when (update) {
            is MahjongGuidanceUpdate.Show -> renderer.renderNotice(update.message)
            MahjongGuidanceUpdate.Dismiss -> renderer.renderNotice(null)
            null -> Unit
        }
    }

    private suspend fun failStartup(
        code: String,
        message: String,
        cause: Throwable? = null
    ): Nothing {
        context.reportError(code, message, recoverable = false)
        throw IllegalStateException(message, cause)
    }
}
