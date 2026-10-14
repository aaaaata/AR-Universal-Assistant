package com.example.ai_assist.scene.mahjong.framework

import com.example.ai_assist.sdk.framework.CapabilityLease
import com.example.ai_assist.sdk.framework.CapabilityType
import com.example.ai_assist.sdk.framework.Frame
import com.example.ai_assist.sdk.framework.InputAction
import com.example.ai_assist.sdk.framework.SceneConfig
import com.example.ai_assist.sdk.framework.SceneConnection
import com.example.ai_assist.sdk.framework.SceneContext
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MahjongSceneProviderTest {
    @Test
    fun mapsGenericConnectionIntoMahjongSceneConfig() {
        val provider = MahjongSceneProvider()
        val config = provider.createSceneConfig(
            connection = SceneConnection(
                serverBaseUrl = "http://192.168.1.2:8000/",
                serviceModeId = "local",
                captureIntervalMillis = 500L
            ),
            startModeId = "regression"
        ) as MahjongSceneConfig

        assertEquals(setOf("mahjong", "mahjong-guangdong"), provider.supportedConnectionSceneIds)
        assertEquals(listOf("regression", "normal"), provider.startModes.map { it.id })
        assertEquals("http://192.168.1.2:8000/", config.serverBaseUrl)
        assertEquals(MahjongServiceMode.LOCAL, config.serviceMode)
        assertEquals(MahjongRecordingMode.RECORD, config.recordingMode)
        assertEquals(500L, config.captureIntervalMillis)
    }

    @Test
    fun processesSingleFrameAndEndsCloudSession() = runBlocking<Unit> {
        val frameFile = createFrameFile(byteArrayOf(1, 2, 3))
        val service = FakeCloudService()
        val context = FakeSceneContext(credentialValue = "secret")
        val session = provider(service).createSession(context)

        session.start()
        val result = session.processFrame(Frame(frameFile, 90, 123L), sequenceId = 7L)
        session.close()

        assertEquals("framework-session", result?.sessionId)
        assertEquals(7L, result?.sequenceId)
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), service.images.map { it.bytes.toList() })
        assertEquals(123L, service.images.single().capturedAtMillis)
        assertEquals(90, service.images.single().imageRotationDegrees)
        assertEquals(listOf("cloud-session"), service.endedSessions)
        assertTrue(frameFile.exists())
        frameFile.delete()
    }

    @Test
    fun missingCredentialIsReportedAsNonRecoverable() = runBlocking {
        val context = FakeSceneContext(credentialValue = null)
        val session = provider(FakeCloudService()).createSession(context)
        val error = runCatching { session.start() }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("MAHJONG_CREDENTIAL_MISSING", context.errors.single().code)
        assertFalse(context.errors.single().recoverable)
    }

    @Test
    fun recoverableFrameFailureDoesNotPreventFollowingFrame() = runBlocking<Unit> {
        val first = createFrameFile(byteArrayOf(1))
        val second = createFrameFile(byteArrayOf(2))
        val service = FakeCloudService(failFirstSubmit = true)
        val context = FakeSceneContext(credentialValue = "secret")
        val session = provider(service).createSession(context)

        session.start()
        val firstResult = session.processFrame(Frame(first, 0, 1L), sequenceId = 1L)
        val secondResult = session.processFrame(Frame(second, 0, 2L), sequenceId = 2L)
        session.close()

        assertNull(firstResult)
        assertEquals(2L, secondResult?.sequenceId)
        assertEquals("MAHJONG_FRAME_SUBMIT_FAILED", context.errors.single().code)
        assertTrue(context.errors.single().recoverable)
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertEquals(listOf("cloud-session"), service.endedSessions)
        first.delete()
        second.delete()
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

        override suspend fun createSession(): Result<String> = Result.success("cloud-session")

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
        private val credentialValue: String?
    ) : SceneContext {
        override val sessionId: String = "framework-session"
        override val config: SceneConfig = MahjongSceneConfig()
        override val inputActions: Flow<InputAction> = emptyFlow()
        val errors = mutableListOf<ReportedError>()

        override fun requireCapability(type: CapabilityType): CapabilityLease =
            object : CapabilityLease {}

        override fun credential(): String? = credentialValue

        override suspend fun reportError(code: String, message: String, recoverable: Boolean) {
            errors += ReportedError(code, recoverable)
        }

        override suspend fun reportDiagnostic(code: String, message: String) = Unit
    }
}
