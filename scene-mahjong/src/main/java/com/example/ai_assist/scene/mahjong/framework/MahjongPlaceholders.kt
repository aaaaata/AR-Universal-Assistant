package com.example.ai_assist.scene.mahjong.framework

import android.view.LayoutInflater
import android.view.ViewGroup
import android.util.Log
import com.example.ai_assist.scene.mahjong.R
import com.example.ai_assist.sdk.framework.AndroidCameraConfig
import com.example.ai_assist.sdk.framework.CapabilityType
import com.example.ai_assist.sdk.framework.CapabilityConfig
import com.example.ai_assist.sdk.framework.Frame
import com.example.ai_assist.sdk.framework.FrameController
import com.example.ai_assist.sdk.framework.FrameStreamConfig
import com.example.ai_assist.sdk.framework.InputAction
import com.example.ai_assist.sdk.framework.SceneConfig
import com.example.ai_assist.sdk.framework.SceneContent
import com.example.ai_assist.sdk.framework.SceneContext
import com.example.ai_assist.sdk.framework.ScenePayload
import com.example.ai_assist.sdk.framework.SceneLaunchController
import com.example.ai_assist.sdk.framework.SceneLaunchProvider
import com.example.ai_assist.sdk.framework.SceneRenderer
import com.example.ai_assist.sdk.framework.SceneResult
import com.example.ai_assist.sdk.framework.SceneSummary
import com.example.ai_assist.sdk.framework.Session
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.random.Random

/** 麻将 HUD 使用的场景结果。 */
data class MahjongScenePayload(
    val userHand: List<String>,
    val meldedTiles: List<String>,
    val suggestedPlay: String,
    val suggestedDiscard: String? = null,
    val canHu: Boolean = false,
    val jinzhangTiles: List<String> = emptyList(),
    val pengTiles: List<String> = emptyList(),
    val gangTiles: List<String> = emptyList(),
    val shanten: Int? = null,
    val recommendationStrength: RecommendationStrength = RecommendationStrength.WEAK,
    val serviceMode: MahjongServiceMode = MahjongServiceMode.CLOUD
) : ScenePayload {
    companion object {
        const val RENDERER_KEY = "mahjong.result.v1"
        val EMPTY = MahjongScenePayload(emptyList(), emptyList(), "")
    }
}

enum class RecommendationStrength(val label: String) {
    STRONG("强建议"),
    NORMAL("一般建议"),
    WEAK("弱建议");

    companion object {
        fun fromConfidence(confidence: Double?): RecommendationStrength = when {
            confidence != null && confidence >= 0.7 -> STRONG
            confidence != null && confidence >= 0.5 -> NORMAL
            else -> WEAK
        }
    }
}

/** 云端麻将分析结果契约，具体 DTO 对调用方保持不透明。 */
interface MahjongCloudResult

/** 云端会话、上传图片和结束会话。 */
interface MahjongCloudService {
    suspend fun createSession(): Result<String>
    suspend fun submitImage(sessionId: String, image: ByteArray): Result<MahjongCloudResult>
    suspend fun endSession(sessionId: String): Result<Unit>
}

/** 云端结果到场景模型。13 张进张、14 张出牌以后在 map 里区分。 */
interface MahjongFieldMapper {
    fun map(cloudResult: MahjongCloudResult): MahjongScenePayload
    fun parseTile(raw: String): String
}

enum class MahjongServiceMode { LOCAL, CLOUD }

enum class MahjongRecordingMode(val wireValue: String) {
    NORMAL("normal"),
    RECORD("regression")
}

/** 麻将场景单次运行配置。凭据只通过 SceneContext 注入。 */
data class MahjongSceneConfig(
    val serverBaseUrl: String = DEFAULT_SERVER_BASE_URL,
    val serviceMode: MahjongServiceMode = MahjongServiceMode.CLOUD,
    val recordingMode: MahjongRecordingMode = MahjongRecordingMode.NORMAL,
    val ruleProfileId: String = DEFAULT_RULE_PROFILE_ID,
    val captureIntervalMillis: Long = DEFAULT_CAPTURE_INTERVAL_MILLIS,
    val captureJitterMillis: Long = DEFAULT_CAPTURE_JITTER_MILLIS,
    val startupJitterMillis: Long = DEFAULT_STARTUP_JITTER_MILLIS,
    val sourceFramesPerSecond: Int = DEFAULT_SOURCE_FRAMES_PER_SECOND,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val cameraConfig: CapabilityConfig = AndroidCameraConfig(),
    override val credentialId: String = when (serviceMode) {
        MahjongServiceMode.LOCAL -> LOCAL_CREDENTIAL_ID
        MahjongServiceMode.CLOUD -> CLOUD_CREDENTIAL_ID
    }
) : SceneConfig {
    init {
        require(serverBaseUrl.isNotBlank()) { "serverBaseUrl must not be blank" }
        require(ruleProfileId.isNotBlank()) { "ruleProfileId must not be blank" }
        require(captureIntervalMillis > 0) { "captureIntervalMillis must be positive" }
        require(captureJitterMillis >= 0) { "captureJitterMillis must not be negative" }
        require(startupJitterMillis >= 0) { "startupJitterMillis must not be negative" }
        require(sourceFramesPerSecond > 0) { "sourceFramesPerSecond must be positive" }
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
    }

    val normalizedServerBaseUrl: String
        get() = if (serverBaseUrl.endsWith('/')) serverBaseUrl else "$serverBaseUrl/"

    companion object {
        const val DEFAULT_SERVER_BASE_URL = "http://127.0.0.1:8000/"
        const val DEFAULT_CAPTURE_INTERVAL_MILLIS = 2_000L
        const val DEFAULT_CAPTURE_JITTER_MILLIS = 1_500L
        const val DEFAULT_STARTUP_JITTER_MILLIS = 2_000L
        const val DEFAULT_SOURCE_FRAMES_PER_SECOND = 15
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_RULE_PROFILE_ID = "guangdong_mvp_v1"
        const val LOCAL_CREDENTIAL_ID = "mahjong.local"
        const val CLOUD_CREDENTIAL_ID = "mahjong.cloud"
    }
}

/** 麻将业务结果渲染器。 */
class MahjongRenderer : SceneRenderer {
    override val rendererKey: String = MahjongScenePayload.RENDERER_KEY
    private val rootsLock = Any()
    private val roots = mutableListOf<WeakReference<MahjongHudView>>()
    @Volatile
    private var diagnosticWarning: String? = null
    @Volatile
    private var noticeMessage: String? = null

    override fun render(container: ViewGroup, content: SceneContent) {
        require(content.rendererKey == rendererKey) { "Unsupported renderer key" }
        val payload = content.scenePayload as? MahjongScenePayload
            ?: error("Unsupported Mahjong payload")
        ensureRoot(container).also {
            it.render(payload)
            it.renderDiagnosticWarning(diagnosticWarning)
            it.renderNotice(noticeMessage)
        }
    }

    override fun clear() {
        prepare(MahjongServiceMode.CLOUD)
    }

    fun prepare(serviceMode: MahjongServiceMode) {
        diagnosticWarning = null
        noticeMessage = null
        activeRoots().forEach { root ->
            root.post {
                root.render(MahjongScenePayload.EMPTY.copy(serviceMode = serviceMode))
                root.renderDiagnosticWarning(null)
                root.renderNotice(null)
            }
        }
    }

    fun renderDiagnosticWarning(message: String?) {
        diagnosticWarning = message
        activeRoots().forEach { root -> root.post { root.renderDiagnosticWarning(message) } }
    }

    fun renderNotice(message: String?) {
        noticeMessage = message
        activeRoots().forEach { root -> root.post { root.renderNotice(message) } }
    }

    private fun ensureRoot(container: ViewGroup): MahjongHudView {
        activeRoots().firstOrNull { it.parent === container }?.let { return it }
        container.removeAllViews()
        return (LayoutInflater.from(container.context)
            .inflate(R.layout.view_mahjong_scene, container, false) as MahjongHudView).also {
            container.addView(it)
            synchronized(rootsLock) { roots.add(WeakReference(it)) }
        }
    }

    private fun activeRoots(): List<MahjongHudView> = synchronized(rootsLock) {
        val active = mutableListOf<MahjongHudView>()
        val iterator = roots.iterator()
        while (iterator.hasNext()) {
            iterator.next().get()?.let(active::add) ?: iterator.remove()
        }
        active
    }
}

/** 麻将场景入口：创建私有启动流程、Renderer 和单局业务 Session。 */
class MahjongSceneProvider : SceneLaunchProvider {
    private val renderer = MahjongRenderer()
    override val sceneId: String = "mahjong"
    override val sceneName: String = "麻将助手"
    override val version: String = "1.0.0"
    override val requiredCapabilities: List<CapabilityType> =
        listOf(CapabilityType.CAMERA, CapabilityType.INPUT)
    override fun createSceneConfig(): SceneConfig = MahjongSceneConfig()

    override fun createRenderer(): SceneRenderer = renderer

    override fun createLaunchController(): SceneLaunchController = MahjongLaunchController()

    override fun createSession(sceneContext: SceneContext): Session {
        val config = sceneContext.config as? MahjongSceneConfig
            ?: error("MahjongSceneConfig is required")
        return MahjongSession(sceneId, sceneContext, config, renderer)
    }
}

/** 单局麻将业务；本类安排选帧时机，Core 管持续流、门禁和相机资源。 */
private class MahjongSession(
    private val sceneId: String,
    private val sceneContext: SceneContext,
    private val config: MahjongSceneConfig,
    private val renderer: MahjongRenderer
) : Session {
    override val frameStreamConfig = FrameStreamConfig(
        sourceFramesPerSecond = config.sourceFramesPerSecond,
        cameraConfig = config.cameraConfig
    )

    private val inputScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inputJob: Job? = null
    private var frameController: FrameController? = null
    private var service: RetrofitMahjongCloudService? = null
    private var cloudSessionId: String? = null
    private val resultStabilizer = MahjongResultStabilizer()
    private val diagnosticStabilizer = MahjongDiagnosticStabilizer()
    private val captureGuidance = MahjongCaptureGuidance()

    override suspend fun start(frameController: FrameController) {
        this.frameController = frameController
        renderer.prepare(config.serviceMode)
        inputJob = inputScope.launch {
            sceneContext.inputActions.collect { action ->
                when (action) {
                    InputAction.TRIPLE_CLICK -> sceneContext.requestStop()
                    InputAction.CLICK,
                    InputAction.DOUBLE_CLICK,
                    InputAction.LONG_PRESS,
                    InputAction.SLIDE_FORWARD,
                    InputAction.SLIDE_BACKWARD,
                    InputAction.SLIDE_UP,
                    InputAction.SLIDE_DOWN -> Unit
                }
            }
        }
        val credential = sceneContext.credential()
            ?: error("Mahjong service credential is not configured")
        val createdService = RetrofitMahjongCloudService.create(config, credential)
        service = createdService
        Log.i(TAG, "Creating cloud Mahjong session at ${config.normalizedServerBaseUrl}")
        cloudSessionId = createdService.createSession().getOrElse { error ->
            Log.e(TAG, "Cloud Mahjong session creation failed", error)
            throw error
        }
        Log.i(TAG, "Cloud Mahjong session created: ${cloudSessionId.orEmpty()}")
        frameController.scheduleCaptureAfter(randomDelay(config.startupJitterMillis))
    }

    override suspend fun processFrame(frame: Frame, sequenceId: Long): SceneResult? {
        frameController?.scheduleCaptureAt(
            frame.selectedAtElapsedMillis +
                config.captureIntervalMillis +
                randomDelay(config.captureJitterMillis)
        )
        val activeService = checkNotNull(service) { "Mahjong service is not started" }
        val activeSessionId = checkNotNull(cloudSessionId) { "Mahjong session is not started" }
        val mapper: MahjongFieldMapper = DefaultMahjongFieldMapper
        return try {
            renderGuidance(captureGuidance.onFrameReady(frame))
            activeService.prepareFrame(frame.capturedAtMillis, frame.imageRotationDegrees)
            val cloudResult = activeService.submitImage(activeSessionId, frame.file.readBytes())
                .getOrElse { throw it }
            val payload = mapper.map(cloudResult).copy(serviceMode = config.serviceMode)
            val response = cloudResult as MahjongAnalyzeResult
            diagnosticStabilizer.evaluate(response.diagnosticCode)?.let { update ->
                val message = update.code?.let {
                    response.copy(diagnosticCode = it).diagnosticMessage()
                }
                renderer.renderDiagnosticWarning(message)
                if (update.code != null) {
                    sceneContext.reportDiagnostic(update.code, message.orEmpty())
                }
            }
            renderGuidance(captureGuidance.onResult(response))
            val result = SceneResult(
                sceneId = sceneId,
                sessionId = sceneContext.sessionId,
                sequenceId = sequenceId,
                updatedAtMillis = System.currentTimeMillis(),
                summary = SceneSummary(
                    title = response.summaryTitle(payload),
                    status = response.status.ifBlank { "unknown" },
                    confidence = response.confidence?.suggestion?.coerceIn(0.0, 1.0),
                    warnings = response.warnings + response.confidence?.reasons.orEmpty()
                ),
                content = SceneContent(MahjongScenePayload.RENDERER_KEY, payload)
            )
            when (resultStabilizer.evaluate(response, result)) {
                MahjongResultStabilizer.Decision.PUBLISH -> result
                MahjongResultStabilizer.Decision.HOLD -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Frame analysis failed at sequence=$sequenceId", error)
            sceneContext.reportError(
                code = "FRAME_ANALYSIS_FAILED",
                message = error.message ?: error::class.java.simpleName,
                recoverable = true
            )
            null
        }
    }

    override suspend fun close() {
        inputJob?.cancel()
        inputJob = null
        inputScope.cancel()
        frameController?.cancelScheduledCapture()
        frameController = null
        renderGuidance(captureGuidance.reset())
        renderer.renderDiagnosticWarning(null)
        val activeService = service
        val activeSessionId = cloudSessionId
        service = null
        cloudSessionId = null
        if (activeService != null && activeSessionId != null) {
            val error = activeService.endSession(activeSessionId).exceptionOrNull()
            if (error != null) {
                sceneContext.reportError(
                    code = "MAHJONG_SESSION_END_FAILED",
                    message = error.message ?: "Unable to end Mahjong session",
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

    private companion object {
        const val TAG = "MahjongScene"

        fun randomDelay(maxMillis: Long): Long =
            if (maxMillis == 0L) 0L else Random.nextLong(maxMillis + 1L)
    }
}
