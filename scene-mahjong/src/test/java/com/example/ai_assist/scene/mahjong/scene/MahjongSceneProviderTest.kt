package com.example.ai_assist.scene.mahjong.scene

import com.example.ai_assist.scene.mahjong.network.AnalyzeResponse
import com.example.ai_assist.scene.mahjong.network.MahjongGateway
import com.example.ai_assist.scene.mahjong.network.MahjongGatewayFactory
import com.example.ai_assist.scene.mahjong.network.SuggestionPayload
import com.example.ai_assist.scene.mahjong.ui.MahjongScenePayload
import com.example.ai_assist.sdk.scene.SceneSession
import com.example.ai_assist.sdk.scene.SceneResult
import com.example.ai_assist.sdk.capability.SdkCapability
import com.example.ai_assist.sdk.capability.StandardCapabilities
import com.example.ai_assist.sdk.capability.CameraCapability
import com.example.ai_assist.sdk.capability.CameraConfig
import com.example.ai_assist.sdk.capability.CameraFrameListener
import com.example.ai_assist.sdk.capability.CameraFrameSelector
import com.example.ai_assist.sdk.capability.CapturedFrame
import com.example.ai_assist.sdk.capability.InputAction
import com.example.ai_assist.sdk.capability.InputCapability
import com.example.ai_assist.sdk.engine.CredentialProvider
import com.example.ai_assist.sdk.engine.SceneConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MahjongSceneProviderTest {
    /** 验证场景取得能力、维持序列号、发布结果并结束云端会话。 */
    @Test
    fun sceneOwnsProductionMahjongFlow() = runBlocking {
        val gateway = FakeGateway()
        val camera = FakeCameraCapability()
        val session = FakeSceneSession(camera)
        val provider = MahjongSceneProvider(MahjongGatewayFactory { gateway })

        provider.start(session)
        assertTrue(camera.started)
        assertEquals("guangdong_mvp_v1", gateway.startedRuleProfile)
        assertEquals("normal", gateway.startedRecordingMode)
        assertEquals(
            setOf(StandardCapabilities.CAMERA, StandardCapabilities.NETWORK),
            provider.descriptor.requiredCapabilities.map { it.capabilityId }.toSet()
        )
        assertEquals(
            setOf(StandardCapabilities.INPUT, StandardCapabilities.AUDIO_OUTPUT),
            provider.descriptor.optionalCapabilities.map { it.capabilityId }.toSet()
        )

        val firstFile = frameFile()
        assertTrue(camera.selector.shouldCapture(1_000L))
        camera.listener.onFrameCaptured(frame(firstFile, 1_000L))
        val firstResult = withTimeout(2_000L) { session.nextResult.await() }

        withTimeout(2_000L) {
            while (firstFile.exists()) yield()
        }
        assertFalse(firstFile.exists())
        assertEquals(listOf(1L), gateway.sequenceIds)
        assertEquals(listOf(tile(0x1F007)), payload(firstResult).userHand)
        assertFalse(camera.selector.shouldCapture(1_500L))

        provider.stop()
        assertEquals("cloud-session", gateway.endedSessionId)
    }

    /** 验证单帧失败可恢复，后续帧仍可继续分析。 */
    @Test
    fun frameFailureIsRecoverableAndDoesNotStopScene() = runBlocking {
        val gateway = FakeGateway().apply { failNextAnalysis = true }
        val camera = FakeCameraCapability()
        val session = FakeSceneSession(camera)
        val provider = MahjongSceneProvider(MahjongGatewayFactory { gateway })
        provider.start(session)

        val failedFile = frameFile()
        assertTrue(camera.selector.shouldCapture(1_000L))
        camera.listener.onFrameCaptured(frame(failedFile, 1_000L))
        assertTrue(withTimeout(2_000L) { session.nextError.await() })
        assertFalse(failedFile.exists())

        withTimeout(2_000L) {
            while (!camera.selector.shouldCapture(2_000L)) yield()
        }
        camera.listener.onFrameCaptured(frame(frameFile(), 2_000L))
        val result = withTimeout(2_000L) { session.nextResult.await() }

        assertEquals(2L, gateway.sequenceIds.last())
        assertEquals("ok", result.summary.status)
        provider.stop()
    }

    /** 教程规定业务接口必须鉴权，缺少 API Key 时拒绝启动场景。 */
    @Test
    fun sceneRejectsMissingApiKey() {
        val gateway = FakeGateway()
        val provider = MahjongSceneProvider(MahjongGatewayFactory { gateway })

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                provider.start(FakeSceneSession(FakeCameraCapability(), credential = null))
            }
        }
    }

    /** 创建带 JPEG 后缀的临时帧文件。 */
    private fun frameFile(): File = File.createTempFile("mahjong-frame", ".jpg").apply {
        writeBytes(byteArrayOf(1, 2, 3))
    }

    /** 创建测试相机帧。 */
    private fun frame(file: File, frameId: Long) = CapturedFrame(
        file = file,
        imageRotationDegrees = 90,
        frameId = frameId,
        selectedAtElapsedMillis = frameId,
        encodedAtElapsedMillis = frameId
    )

    /** 返回场景结果中的麻将私有 payload。 */
    private fun payload(result: SceneResult) =
        result.content?.scenePayload as MahjongScenePayload

    /** 将 Unicode 码点转换为测试字符串。 */
    private fun tile(codePoint: Int) = String(Character.toChars(codePoint))

    private class FakeGateway : MahjongGateway {
        var startedApiKey: String? = "not-started"
        var startedRuleProfile: String? = null
        var startedRecordingMode: String? = null
        var endedSessionId: String? = null
        var failNextAnalysis = false
        val sequenceIds = mutableListOf<Long>()

        /** 记录规则配置并返回固定云端会话 ID。 */
        override suspend fun startSession(
            apiKey: String,
            ruleProfileId: String,
            recordingMode: String
        ): String {
            startedApiKey = apiKey
            startedRuleProfile = ruleProfileId
            startedRecordingMode = recordingMode
            return "cloud-session"
        }

        /** 记录序列号并返回固定麻将分析结果。 */
        override suspend fun analyzeFrame(
            apiKey: String,
            sessionId: String,
            sequenceId: Long,
            imageRotationDegrees: Int,
            imageFile: File
        ): AnalyzeResponse {
            sequenceIds += sequenceId
            if (failNextAnalysis) {
                failNextAnalysis = false
                error("frame failed")
            }
            return AnalyzeResponse(
                status = "ok",
                handTiles = listOf("1m"),
                suggestion = SuggestionPayload(adviceType = "draw_wait")
            )
        }

        /** 记录结束的云端会话 ID。 */
        override suspend fun endSession(apiKey: String, sessionId: String) {
            endedSessionId = sessionId
        }
    }

    private class FakeCameraCapability : CameraCapability {
        var started = false
        lateinit var selector: CameraFrameSelector
        lateinit var listener: CameraFrameListener

        /** 保存场景传入的选帧器和回调。 */
        override fun start(
            config: CameraConfig,
            frameSelector: CameraFrameSelector,
            listener: CameraFrameListener
        ) {
            started = true
            selector = frameSelector
            this.listener = listener
        }

        /** 标记测试相机停止。 */
        override fun stop() {
            started = false
        }

        /** 测试能力无需永久资源清理。 */
        override fun release() = Unit
    }

    private class FakeInputCapability : InputCapability {
        /** 测试不产生输入动作。 */
        override val actions: Flow<InputAction> = emptyFlow()
    }

    private class FakeSceneSession(
        camera: CameraCapability,
        credential: String? = "api-key"
    ) : SceneSession {
        private val capabilities = mapOf(
            StandardCapabilities.CAMERA to camera,
            StandardCapabilities.INPUT to FakeInputCapability()
        )

        override val sessionId = "sdk-session"
        override val config: SceneConfig = MahjongSceneConfig(
            serverBaseUrl = "http://localhost/",
            captureJitterMillis = 0L,
            startupJitterMillis = 0L
        )
        override val credentialProvider = CredentialProvider { credential }
        val nextResult = CompletableDeferred<SceneResult>()
        val nextError = CompletableDeferred<Boolean>()

        /** 保存场景发布的首个结果。 */
        override suspend fun publishResult(result: SceneResult) {
            nextResult.complete(result)
        }

        /** 保存场景发布的首个错误是否可恢复。 */
        override suspend fun reportError(code: String, message: String, recoverable: Boolean) {
            nextError.complete(recoverable)
        }

        /** 返回测试已注册的指定能力。 */
        override fun <T : SdkCapability> requireCapability(
            capabilityId: String,
            type: Class<T>
        ): T = requireNotNull(type.cast(requireNotNull(capabilities[capabilityId])))
    }
}
