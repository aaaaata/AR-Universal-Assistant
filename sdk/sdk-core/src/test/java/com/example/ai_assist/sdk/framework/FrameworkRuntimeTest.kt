package com.example.ai_assist.sdk.framework

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameworkRuntimeTest {
    @Test
    fun coreRunsProviderAndPublishesResultAndStates() = runBlocking {
        val provider = FakeProvider()
        val resources = FakeResourceManager()
        val core = DefaultCvGameCore(
            cameraScheduler = EmptyCameraScheduler,
            resourceManager = resources,
            credentialProvider = CredentialProvider { "local-token" }
        )
        core.registerScene(provider)

        val events = core.startScene(provider.sceneId).takeWhile { event ->
            event !is SdkEvent.StateChanged || event.state != SdkState.READY
        }.toList()

        assertTrue(events.any { it is SdkEvent.StateChanged && it.state == SdkState.RUNNING })
        assertTrue(events.any { it is SdkEvent.ResultReceived })
        assertEquals("local-token", provider.credential)
        assertEquals(1, resources.acquireCount)
        assertEquals(1, resources.releaseCount)
        assertEquals(SdkState.READY, core.getCurrentState())
    }

    @Test
    fun stopCancelsProviderAndReturnsToReady() = runBlocking {
        val provider = WaitingProvider()
        val core = DefaultCvGameCore(EmptyCameraScheduler, FakeResourceManager())
        core.registerScene(provider)
        val events = core.startScene(provider.sceneId)
        events.first { it is SdkEvent.StateChanged && it.state == SdkState.RUNNING }
        provider.started.await()

        core.stopScene()
        events.first { it is SdkEvent.StateChanged && it.state == SdkState.READY }

        assertEquals(SdkState.READY, core.getCurrentState())
    }

    private class FakeProvider : SceneProvider {
        override val sceneId = "fake"
        override val sceneName = "Fake"
        override val version = "1"
        override val requiredCapabilities = listOf(CapabilityType.CAMERA)
        var credential: String? = null
        override fun createSceneConfig(): SceneConfig = object : SceneConfig {}
        override fun createRenderer(): SceneRenderer = FakeRenderer
        override fun start(sceneContext: SceneContext): Flow<SceneResult> = flow {
            credential = sceneContext.credential()
            emit(result(sceneContext.sessionId))
        }
        override fun stop() = Unit
    }

    private class WaitingProvider : SceneProvider {
        override val sceneId = "waiting"
        override val sceneName = "Waiting"
        override val version = "1"
        override val requiredCapabilities = emptyList<CapabilityType>()
        val started = CompletableDeferred<Unit>()
        override fun createSceneConfig(): SceneConfig = object : SceneConfig {}
        override fun createRenderer(): SceneRenderer = FakeRenderer
        override fun start(sceneContext: SceneContext): Flow<SceneResult> = flow {
            started.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        override fun stop() = Unit
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

    private object EmptyCameraScheduler : CameraScheduler {
        override fun frames(intervalMillis: Long, cameraConfig: CapabilityConfig): Flow<Frame> =
            emptyFlow()
    }

    private object FakeRenderer : SceneRenderer {
        override val rendererKey = "fake"
        override fun render(container: android.view.ViewGroup, content: SceneContent) = Unit
        override fun clear() = Unit
    }

    private companion object {
        fun result(sessionId: String) = SceneResult(
            sceneId = "fake",
            sessionId = sessionId,
            sequenceId = 1,
            updatedAtMillis = 1,
            summary = SceneSummary("done", "complete")
        )
    }
}
