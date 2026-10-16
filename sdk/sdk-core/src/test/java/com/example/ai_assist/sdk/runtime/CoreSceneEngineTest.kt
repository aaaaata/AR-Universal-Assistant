package com.example.ai_assist.sdk.runtime

import android.view.ViewGroup
import com.example.ai_assist.sdk.scene.SceneSession
import com.example.ai_assist.sdk.scene.SceneDescriptor
import com.example.ai_assist.sdk.scene.ScenePayload
import com.example.ai_assist.sdk.scene.SceneProvider
import com.example.ai_assist.sdk.scene.SceneRenderer
import com.example.ai_assist.sdk.scene.SceneResult
import com.example.ai_assist.sdk.scene.SceneSummary
import com.example.ai_assist.sdk.scene.SceneNotice
import com.example.ai_assist.sdk.scene.SceneNoticeUpdate
import com.example.ai_assist.sdk.capability.CapabilityRequirement
import com.example.ai_assist.sdk.capability.SdkCapability
import com.example.ai_assist.sdk.capability.StandardCapabilities
import com.example.ai_assist.sdk.engine.EmptySceneConfig
import com.example.ai_assist.sdk.engine.SdkConfig
import com.example.ai_assist.sdk.engine.SdkEvent
import com.example.ai_assist.sdk.engine.SessionState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreSceneEngineTest {
    @Test
    fun sceneLifecyclePublishesOrderedStateResultAndDiagnosticEvents() = runBlocking {
        val sdk = CoreSceneEngine()
        val camera = CoreRecordingCapability()
        val scene = CoreFakeScene()
        val events = mutableListOf<SdkEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            sdk.events.collect { events += it }
        }
        sdk.registerCapability(StandardCapabilities.CAMERA, camera)
        sdk.registerScene(scene)
        sdk.initialize(SdkConfig())

        sdk.startScene(CoreFakeScene.SCENE_ID, EmptySceneConfig)
        assertEquals(SessionState.RUNNING, sdk.state.value)
        assertTrue(scene.started)
        assertSame(camera, scene.camera)

        sdk.stopScene()
        val resultCount = events.filterIsInstance<SdkEvent.ResultReceived>().size
        scene.publishResult()
        assertEquals(resultCount, events.filterIsInstance<SdkEvent.ResultReceived>().size)
        sdk.close()
        collector.cancelAndJoin()

        assertEquals(SessionState.UNINITIALIZED, sdk.state.value)
        assertFalse(scene.started)
        assertEquals(1, camera.leaseReleaseCount)
        assertEquals(1, camera.closeCount)
        assertEquals(
            listOf(
                SessionState.STARTING,
                SessionState.RUNNING,
                SessionState.STOPPING,
                SessionState.READY
            ),
            events.filterIsInstance<SdkEvent.StateChanged>().map { it.state }
        )
        assertEquals(1, events.filterIsInstance<SdkEvent.ResultReceived>().size)
        assertEquals(1, events.filterIsInstance<SdkEvent.DiagnosticReported>().size)
        assertEquals(
            "FAKE_NOTICE",
            events.filterIsInstance<SdkEvent.NoticeUpdated>().single().update.code
        )
        val sessionIds = events.map { it.sessionId }.distinct()
        assertEquals(1, sessionIds.size)
    }

    @Test
    fun missingCapabilityEntersErrorAndCanReturnToReady() = runBlocking {
        val sdk = CoreSceneEngine()
        val events = mutableListOf<SdkEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            sdk.events.collect { events += it }
        }
        sdk.registerScene(CoreFakeScene())
        sdk.initialize(SdkConfig())

        expectThrowsSuspend<IllegalStateException> { sdk.startScene(CoreFakeScene.SCENE_ID) }

        assertEquals(SessionState.ERROR, sdk.state.value)
        val error = events.filterIsInstance<SdkEvent.ErrorOccurred>().single()
        assertEquals("CAPABILITY_UNAVAILABLE", error.code)
        assertFalse(error.recoverable)

        sdk.stopScene()
        collector.cancelAndJoin()
        assertEquals(SessionState.READY, sdk.state.value)
        assertEquals(
            listOf(SessionState.STARTING, SessionState.ERROR, SessionState.READY),
            events.filterIsInstance<SdkEvent.StateChanged>().map { it.state }
        )
    }

    @Test
    fun startAndStopFailuresAlwaysReleaseCapabilityLease() = runBlocking {
        val startSdk = CoreSceneEngine()
        val startCapability = CoreRecordingCapability()
        startSdk.registerCapability(StandardCapabilities.CAMERA, startCapability)
        startSdk.registerScene(CoreFakeScene(failOnStart = true))
        startSdk.initialize(SdkConfig())

        expectThrowsSuspend<IllegalStateException> {
            startSdk.startScene(CoreFakeScene.SCENE_ID)
        }
        assertEquals(SessionState.ERROR, startSdk.state.value)
        assertEquals(1, startCapability.leaseReleaseCount)

        val stopSdk = CoreSceneEngine()
        val stopCapability = CoreRecordingCapability()
        stopSdk.registerCapability(StandardCapabilities.CAMERA, stopCapability)
        stopSdk.registerScene(CoreFakeScene(failOnStop = true))
        stopSdk.initialize(SdkConfig())
        stopSdk.startScene(CoreFakeScene.SCENE_ID)

        expectThrowsSuspend<IllegalStateException> { stopSdk.stopScene() }
        assertEquals(SessionState.ERROR, stopSdk.state.value)
        assertEquals(1, stopCapability.leaseReleaseCount)
    }

    @Test
    fun recoverableErrorKeepsRunningFatalErrorReleasesLeaseAndBlocksOldResults() = runBlocking {
        val sdk = CoreSceneEngine()
        val capability = CoreRecordingCapability()
        val scene = CoreFakeScene()
        val events = mutableListOf<SdkEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            sdk.events.collect { events += it }
        }
        sdk.registerCapability(StandardCapabilities.CAMERA, capability)
        sdk.registerScene(scene)
        sdk.initialize(SdkConfig())
        sdk.startScene(CoreFakeScene.SCENE_ID)

        scene.session!!.reportError("FRAME_TIMEOUT", "frame timed out", recoverable = true)
        assertEquals(SessionState.RUNNING, sdk.state.value)
        assertEquals(0, capability.leaseReleaseCount)

        scene.session!!.reportError("CAMERA_LOST", "camera unavailable", recoverable = false)
        assertEquals(SessionState.ERROR, sdk.state.value)
        assertEquals(1, capability.leaseReleaseCount)
        val resultCount = events.filterIsInstance<SdkEvent.ResultReceived>().size

        scene.publishResult()
        sdk.stopScene()
        collector.cancelAndJoin()

        assertEquals(resultCount, events.filterIsInstance<SdkEvent.ResultReceived>().size)
        assertEquals(
            listOf(true, false),
            events.filterIsInstance<SdkEvent.ErrorOccurred>().map { it.recoverable }
        )
    }

    private suspend inline fun <reified T : Throwable> expectThrowsSuspend(
        crossinline block: suspend () -> Unit
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    }
}

private class CoreFakeScene(
    private val failOnStart: Boolean = false,
    private val failOnStop: Boolean = false
) : SceneProvider {
    companion object {
        const val SCENE_ID = "fake-core"
    }

    override val descriptor = SceneDescriptor(
        SCENE_ID,
        "Fake Core Scene",
        "1.0",
        setOf(CapabilityRequirement(StandardCapabilities.CAMERA, exclusive = true))
    )
    override val renderer = object : SceneRenderer {
        override val rendererKey = "fake-core"
        override fun render(container: ViewGroup, payload: ScenePayload) = Unit
    }
    var started = false
    var camera: CoreRecordingCapability? = null
    var session: SceneSession? = null

    override suspend fun start(session: SceneSession) {
        if (failOnStart) error("start failed")
        this.session = session
        camera = session.requireCapability(
            StandardCapabilities.CAMERA,
            CoreRecordingCapability::class.java
        )
        started = true
        session.reportDiagnostic("FAKE_STARTED", "fake scene started")
        session.publishNotice(
            SceneNoticeUpdate.Show(SceneNotice("FAKE_NOTICE", "fake notice"))
        )
        publishResult()
    }

    override suspend fun stop() {
        if (failOnStop) error("stop failed")
        started = false
    }

    suspend fun publishResult() {
        val currentSession = requireNotNull(session)
        currentSession.publishResult(
            SceneResult(
                SCENE_ID,
                currentSession.sessionId,
                System.currentTimeMillis(),
                SceneSummary("Fake result", "running")
            )
        )
    }
}

private class CoreRecordingCapability : SdkCapability {
    var leaseReleaseCount = 0
    var closeCount = 0

    override fun onLeaseReleased() {
        leaseReleaseCount++
    }

    override fun close() {
        closeCount++
    }
}
