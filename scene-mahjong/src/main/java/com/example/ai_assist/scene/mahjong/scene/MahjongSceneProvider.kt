package com.example.ai_assist.scene.mahjong.scene

import com.example.ai_assist.scene.mahjong.network.AnalyzeResponse
import com.example.ai_assist.scene.mahjong.network.MahjongGatewayFactory
import com.example.ai_assist.scene.mahjong.network.RetrofitMahjongGateway
import com.example.ai_assist.scene.mahjong.ui.MahjongSceneRenderer
import com.example.ai_assist.sdk.scene.SceneDescriptor
import com.example.ai_assist.sdk.scene.SceneResult
import com.example.ai_assist.sdk.scene.SceneSession
import com.example.ai_assist.sdk.capability.CapabilityRequirement
import com.example.ai_assist.sdk.capability.StandardCapabilities
import com.example.ai_assist.sdk.capability.CameraConfig
import com.example.ai_assist.sdk.capability.InputCapability
import com.example.ai_assist.sdk.engine.SceneConfig
import com.example.ai_assist.sdk.cv.CVEventCodes
import com.example.ai_assist.sdk.cv.CVProcessor
import com.example.ai_assist.sdk.cv.CVResultDecision
import com.example.ai_assist.sdk.cv.CVSceneProvider
import com.example.ai_assist.sdk.cv.CapturePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 广东麻将场景，只定义业务策略、处理器、结果映射和展示。 */
class MahjongSceneProvider internal constructor(
    private val gatewayFactory: MahjongGatewayFactory
) : CVSceneProvider<AnalyzeResponse>() {
    @Volatile
    private var resultStabilizer = MahjongResultStabilizer()
    @Volatile
    private var diagnosticStabilizer = MahjongDiagnosticStabilizer()
    @Volatile
    private var captureGuidance = MahjongCaptureGuidance()
    override val descriptor = SceneDescriptor(
        sceneId = SCENE_ID,
        name = "广东麻将",
        version = "1.0.0",
        requiredCapabilities = setOf(
            CapabilityRequirement(StandardCapabilities.CAMERA, exclusive = true),
            CapabilityRequirement(StandardCapabilities.NETWORK)
        ),
        optionalCapabilities = setOf(
            CapabilityRequirement(StandardCapabilities.INPUT),
            CapabilityRequirement(StandardCapabilities.AUDIO_OUTPUT)
        )
    )

    override val renderer = MahjongSceneRenderer()

    override val eventCodes = CVEventCodes(
        processingStarted = MahjongDiagnostics.FRAME_ANALYZING,
        processingCompleted = MahjongDiagnostics.FRAME_ANALYSIS_COMPLETED,
        processingFailed = MahjongDiagnostics.FRAME_ANALYSIS_FAILED,
        cameraFailed = MahjongDiagnostics.CAMERA_FAILED
    )

    constructor() : this(MahjongGatewayFactory(RetrofitMahjongGateway::create))

    override fun capturePolicy(config: SceneConfig): CapturePolicy =
        config.asMahjongConfig().let {
            CapturePolicy(
                intervalMillis = it.captureIntervalMillis,
                jitterMillis = it.captureJitterMillis,
                startupJitterMillis = it.startupJitterMillis
            )
        }

    override fun cameraConfig(config: SceneConfig): CameraConfig =
        config.asMahjongConfig().cameraConfig

    override fun createProcessor(session: SceneSession): CVProcessor<AnalyzeResponse> {
        resultStabilizer = MahjongResultStabilizer()
        diagnosticStabilizer = MahjongDiagnosticStabilizer()
        renderer.renderDiagnosticWarning(null)
        captureGuidance = MahjongCaptureGuidance()
        val config = session.config.asMahjongConfig()
        val apiKey = config.apiKey.takeIf(String::isNotBlank)
            ?: requireNotNull(
                session.credentialProvider.credentialFor(SCENE_ID)?.takeIf(String::isNotBlank)
            ) { "Mahjong API key is required" }
        return MahjongProcessor(config, apiKey, gatewayFactory)
    }

    override fun mapResult(result: AnalyzeResponse, session: SceneSession): SceneResult =
        MahjongResultMapper.toSceneResult(
            response = result,
            sceneId = SCENE_ID,
            sessionId = session.sessionId,
            updatedAtMillis = System.currentTimeMillis(),
            serviceMode = session.config.asMahjongConfig().serviceMode
        )

    override suspend fun onFrameReady(
        frame: com.example.ai_assist.sdk.capability.CapturedFrame,
        session: SceneSession
    ) {
        captureGuidance.onFrameReady(frame)?.let { session.publishNotice(it) }
    }

    override suspend fun onResultReady(result: AnalyzeResponse, session: SceneSession) {
        diagnosticStabilizer.evaluate(result.diagnosticCode)?.let { update ->
            renderer.renderDiagnosticWarning(update.code?.let(MahjongResultMapper::diagnosticMessage))
        }
        captureGuidance.onResult(result)?.let { session.publishNotice(it) }
    }

    override fun evaluateResult(
        result: AnalyzeResponse,
        mappedResult: SceneResult,
        session: SceneSession
    ): CVResultDecision = when (resultStabilizer.evaluate(result, mappedResult)) {
        MahjongResultStabilizer.Decision.PUBLISH -> CVResultDecision.PUBLISH
        MahjongResultStabilizer.Decision.HOLD -> CVResultDecision.HOLD
    }

    override suspend fun onSessionStarted(session: SceneSession, scope: CoroutineScope) {
        val input = session.optionalCapability(
            StandardCapabilities.INPUT,
            InputCapability::class.java
        ) ?: return
        scope.launch { input.actions.collect { renderer.handleInputAction(it) } }
    }

    private fun SceneConfig.asMahjongConfig(): MahjongSceneConfig =
        this as? MahjongSceneConfig
            ?: throw IllegalArgumentException("MahjongSceneConfig is required")

    companion object {
        const val SCENE_ID = "mahjong-guangdong"
    }
}

/** App 可识别的麻将场景诊断代码。 */
object MahjongDiagnostics {
    const val FRAME_ANALYZING = "MAHJONG_FRAME_ANALYZING"
    const val FRAME_ANALYSIS_COMPLETED = "MAHJONG_FRAME_ANALYSIS_COMPLETED"
    const val FRAME_ANALYSIS_FAILED = "MAHJONG_FRAME_ANALYSIS_FAILED"
    const val CAMERA_FAILED = "MAHJONG_CAMERA_FAILED"
}
