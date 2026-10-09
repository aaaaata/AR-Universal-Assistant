package com.example.ai_assist.scene.mahjong.framework

import com.example.ai_assist.sdk.framework.CameraScheduler
import com.example.ai_assist.sdk.framework.CapabilityConfig
import com.example.ai_assist.sdk.framework.CapabilityLease
import com.example.ai_assist.sdk.framework.CapabilityType
import com.example.ai_assist.sdk.framework.Frame
import com.example.ai_assist.sdk.framework.InputAction
import com.example.ai_assist.sdk.framework.SceneConfig
import com.example.ai_assist.sdk.framework.SceneConnection
import com.example.ai_assist.sdk.framework.SceneContext
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MahjongSceneProviderTest {
    @Test
    fun mapsGenericConnectionIntoMahjongSceneConfig() {
        val config = MahjongSceneProvider().createSceneConfig(
            SceneConnection(
                serverBaseUrl = "http://192.168.1.2:8000/",
                serviceModeId = "local",
                captureIntervalMillis = 500L
            )
        ) as MahjongSceneConfig

        assertEquals("http://192.168.1.2:8000/", config.serverBaseUrl)
        assertEquals(MahjongServiceMode.LOCAL, config.serviceMode)
        assertEquals(500L, config.captureIntervalMillis)
    }

    @Test
    fun exchangesCameraFrameThroughFrameworkContractsAndEndsSession() = runBlocking {
        val frameFile = createFrameFile(byteArrayOf(1, 2, 3))
        val service = FakeCloudService()
        val context = FakeSceneContext(
            credentialValue = "secret",
            frames = listOf(Frame(frameFile, 90, 123L))
        )

        val result = provider(service).start(context).single()

        assertEquals("framework-session", result.sessionId)
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), service.images.map { it.bytes.toList() })
        assertEquals(123L, service.images.single().capturedAtMillis)
        assertEquals(90, service.images.single().imageRotationDegrees)
        assertEquals(listOf("cloud-session"), service.endedSessions)
        assertEquals(listOf(CapabilityType.CAMERA), context.requiredCapabilities)
        assertFalse(frameFile.exists())
    }

    @Test
    fun missingCredentialIsReportedAsNonRecoverable() = runBlocking {
        val context = FakeSceneContext(credentialValue = null, frames = emptyList())
        val error = runCatching { provider(FakeCloudService()).start(context).single() }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("MAHJONG_CREDENTIAL_MISSING", context.errors.single().code)
        assertFalse(context.errors.single().recoverable)
    }

    @Test
    fun recoverableFrameFailureDoesNotStopFollowingFrames() = runBlocking {
        val first = createFrameFile(byteArrayOf(1))
        val second = createFrameFile(byteArrayOf(2))
        val service = FakeCloudService(failFirstSubmit = true)
        val context = FakeSceneContext(
            credentialValue = "secret",
            frames = listOf(Frame(first, 0, 1L), Frame(second, 0, 2L))
        )

        val result = provider(service).start(context).single()

        assertEquals(2L, result.sequenceId)
        assertEquals("MAHJONG_FRAME_SUBMIT_FAILED", context.errors.single().code)
        assertTrue(context.errors.single().recoverable)
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertEquals(listOf("cloud-session"), service.endedSessions)
    }

    @Test
    fun stopCancelsCollectionAndEndsCloudSession() = runBlocking {
        val service = FakeCloudService()
        val context = FakeSceneContext(
            credentialValue = "secret",
            frameFlow = flow { awaitCancellation() }
        )
        val provider = provider(service)
        val collection = launch { provider.start(context).collect {} }
        service.sessionCreated.await()

        provider.stop()
        collection.join()

        assertTrue(collection.isCancelled)
        assertEquals(listOf("cloud-session"), service.endedSessions)
    }

    @Test
    fun fieldMapperMapsHandMeldAndDiscardSuggestion() {
        val payload = DefaultMahjongFieldMapper().map(
            MahjongAnalysisResponse(
                handTiles = listOf("1m", "2m"),
                meldGroups = listOf(MahjongMeldGroup("peng", listOf("3p", "3p", "3p"))),
                suggestion = MahjongSuggestion(
                    adviceType = "discard",
                    suggestion = MahjongAdviceCandidate(tile = "2m")
                )
            )
        )

        assertEquals(2, payload.userHand.size)
        assertEquals(3, payload.meldedTiles.size)
        assertEquals(payload.userHand[1], payload.suggestedDiscard)
    }

    private fun provider(service: FakeCloudService) = MahjongSceneProvider(
        serviceFactory = MahjongCloudServiceFactory { _, _ -> service },
        fieldMapper = DefaultMahjongFieldMapper()
    )

    private fun createFrameFile(bytes: ByteArray): File =
        File.createTempFile("mahjong-frame-", ".jpg").apply { writeBytes(bytes) }

    private class FakeCloudService(
        private val failFirstSubmit: Boolean = false
    ) : MahjongCloudService {
        val images = mutableListOf<MahjongImageSubmission>()
        val endedSessions = mutableListOf<String>()
        val sessionCreated = CompletableDeferred<Unit>()

        override suspend fun createSession(): Result<String> {
            sessionCreated.complete(Unit)
            return Result.success("cloud-session")
        }

        override suspend fun submitImage(
            sessionId: String,
            image: MahjongImageSubmission
        ): Result<MahjongCloudResult> {
            images += image
            if (failFirstSubmit && images.size == 1) {
                return Result.failure(IllegalStateException("temporary failure"))
            }
            return Result.success(
                MahjongAnalysisResponse(
                    status = "complete",
                    handTiles = listOf("1m"),
                    suggestion = MahjongSuggestion(
                        adviceType = "discard",
                        suggestion = MahjongAdviceCandidate(tile = "1m")
                    )
                )
            )
        }

        override suspend fun endSession(sessionId: String): Result<Unit> {
            endedSessions += sessionId
            return Result.success(Unit)
        }
    }

    private data class ReportedError(val code: String, val recoverable: Boolean)

    private class FakeSceneContext(
        private val credentialValue: String?,
        private val frameFlow: Flow<Frame>
    ) : SceneContext {
        constructor(credentialValue: String?, frames: List<Frame>) :
            this(credentialValue, frames.asFlow())

        override val sessionId: String = "framework-session"
        override val config: SceneConfig = MahjongSceneConfig()
        override val cameraScheduler: CameraScheduler = object : CameraScheduler {
            override fun frames(
                intervalMillis: Long,
                cameraConfig: CapabilityConfig
            ): Flow<Frame> = frameFlow
        }
        override val inputActions: Flow<InputAction> = emptyFlow()
        val requiredCapabilities = mutableListOf<CapabilityType>()
        val errors = mutableListOf<ReportedError>()

        override fun requireCapability(type: CapabilityType): CapabilityLease {
            requiredCapabilities += type
            return object : CapabilityLease {}
        }

        override fun credential(): String? = credentialValue

        override suspend fun reportError(code: String, message: String, recoverable: Boolean) {
            errors += ReportedError(code, recoverable)
        }

        override suspend fun reportDiagnostic(code: String, message: String) = Unit
    }
}
