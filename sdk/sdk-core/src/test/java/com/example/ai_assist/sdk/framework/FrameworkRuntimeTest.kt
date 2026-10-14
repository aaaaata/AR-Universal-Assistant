package com.example.ai_assist.sdk.framework

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameworkRuntimeTest {
    @Test
    fun coreOwnsFrameFlowPublishesResultAndCleansFrame() = runBlocking {
        val frameFile = File.createTempFile("core-frame-", ".jpg").apply { writeBytes(byteArrayOf(1)) }
        val provider = FakeProvider()
        val resources = FakeResourceManager()
        val core = DefaultCvGameCore(
            cameraScheduler = SingleFrameScheduler(frameFile),
            resourceManager = resources,
            credentialProvider = CredentialProvider { "local-token" }
        )
        core.registerScene(provider)

        val events = core.startScene(provider.sceneId).takeWhile { event ->
            event !is SdkEvent.StateChanged || event.state != SdkState.READY
        }.toList()

        assertTrue(events.any { it is SdkEvent.StateChanged && it.state == SdkState.RUNNING })
        assertTrue(events.any { it is SdkEvent.ResultReceived })
        assertEquals(listOf(1L), provider.processedSequences)
        assertEquals("local-token", provider.credential)
        assertTrue(provider.closed)
        assertFalse(frameFile.exists())
        assertEquals(1, resources.acquireCount)
        assertEquals(1, resources.releaseCount)
        assertEquals(SdkState.READY, core.getCurrentState())
    }

    @Test
    fun stopCancelsSessionAndReturnsToReady() = runBlocking {
        val provider = WaitingProvider()
        val core = DefaultCvGameCore(WaitingCameraScheduler, FakeResourceManager())
        core.registerScene(provider)
        val events = core.startScene(provider.sceneId)
        events.first { it is SdkEvent.StateChanged && it.state == SdkState.RUNNING }
        provider.started.await()

        core.stopScene()
        events.first { it is SdkEvent.StateChanged && it.state == SdkState.READY }

        assertTrue(provider.closed)
        assertEquals(SdkState.READY, core.getCurrentState())
    }

    private class FakeProvider : SceneProvider {
        override val sceneId = "fake"
        override val sceneName = "Fake"
        override val version = "1"
        override val requiredCapabilities = listOf(CapabilityType.CAMERA)
        var credential: String? = null
        var closed = false
        val processedSequences = mutableListOf<Long>()
        override fun createSceneConfig(): SceneConfig = object : SceneConfig {}
        override fun createRenderer(): SceneRenderer = FakeRenderer
        override fun createSession(sceneContext: SceneContext): Session = object : Session {
            override val frameStreamConfig = FrameStreamConfig(1L, object : CapabilityConfig {})
            override suspend fun start() {
                credential = sceneContext.credential()
            }
            override suspend fun processFrame(frame: Frame, sequenceId: Long): SceneResult {
                processedSequences += sequenceId
                return result(sceneContext.sessionId, sequenceId)
            }
            override suspend fun close() {
                closed = true
            }
        }
    }

    private class WaitingProvider : SceneProvider {
        override val sceneId = "waiting"
        override val sceneName = "Waiting"
        override val version = "1"
        override val requiredCapabilities = emptyList<CapabilityType>()
        val started = CompletableDeferred<Unit>()
        var closed = false
        override fun createSceneConfig(): SceneConfig = object : SceneConfig {}
        override fun createRenderer(): SceneRenderer = FakeRenderer
        override fun createSession(sceneContext: SceneContext): Session = object : Session {
            override val frameStreamConfig = FrameStreamConfig(1L, object : CapabilityConfig {})
            override suspend fun start() {
                started.complete(Unit)
            }
            override suspend fun processFrame(frame: Frame, sequenceId: Long): SceneResult? = null
            override suspend fun close() {
                closed = true
            }
        }
    }

    private class FakeResourceManager : ResourceManager {
        var acquireCount = 0
        var releaseCount = 0
        override fun acquire(type: CapabilityType, sceneId: String): CapabilityLease {
            acquireCount++
            return object : CapabilityLease {}
        }
        override fun releaseAll() {
            releaseCount++
        }
    }

    private class SingleFrameScheduler(private val file: File) : CameraScheduler {
        override fun frames(intervalMillis: Long, cameraConfig: CapabilityConfig): Flow<Frame> = flow {
            emit(Frame(file, 0, 1L))
        }
    }

    private object WaitingCameraScheduler : CameraScheduler {
        override fun frames(intervalMillis: Long, cameraConfig: CapabilityConfig): Flow<Frame> = flow {
            awaitCancellation()
        }
    }

    private object FakeRenderer : SceneRenderer {
        override val rendererKey = "fake"
        override fun render(container: android.view.ViewGroup, content: SceneContent) = Unit
        override fun clear() = Unit
    }

    private companion object {
        fun result(sessionId: String, sequenceId: Long) = SceneResult(
            sceneId = "fake",
            sessionId = sessionId,
            sequenceId = sequenceId,
            updatedAtMillis = 1,
            summary = SceneSummary("done", "complete")
        )
    }
}
