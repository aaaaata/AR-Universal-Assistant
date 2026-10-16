package com.example.ai_assist.sdk.scene

import android.view.ViewGroup
import com.example.ai_assist.sdk.capability.CapabilityRequirement
import com.example.ai_assist.sdk.capability.StandardCapabilities
import com.example.ai_assist.sdk.engine.CredentialProvider
import com.example.ai_assist.sdk.engine.EmptySceneConfig
import com.example.ai_assist.sdk.engine.SceneConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSceneContractTest {
    @Test
    fun fakeSceneStartsPublishesAndStopsThroughPublicContracts() = runBlocking {
        val scene = FakeSceneProvider()
        val session = RecordingSceneSession()

        scene.start(session)

        assertTrue(scene.started)
        assertEquals("fake-session", session.results.single().sessionId)
        assertEquals("fake.renderer", session.results.single().content?.rendererKey)
        assertEquals("Fake scene is running", session.results.single().summary.title)

        scene.stop()

        assertFalse(scene.started)
    }
}

private class FakeSceneProvider : SceneProvider {
    companion object {
        const val SCENE_ID = "fake"
    }

    var started = false
        private set

    override val descriptor = SceneDescriptor(
        sceneId = SCENE_ID,
        name = "Fake Scene",
        version = "1.0",
        requiredCapabilities = setOf(
            CapabilityRequirement(StandardCapabilities.CAMERA, exclusive = true)
        )
    )
    override val renderer = object : SceneRenderer {
        override val rendererKey = "fake.renderer"

        override fun render(container: ViewGroup, payload: ScenePayload) = Unit
    }

    override suspend fun start(session: SceneSession) {
        started = true
        session.publishResult(
            SceneResult(
                sceneId = SCENE_ID,
                sessionId = session.sessionId,
                updatedAtMillis = 1L,
                summary = SceneSummary(
                    title = "Fake scene is running",
                    status = "running",
                    confidence = 1.0
                ),
                content = SceneContent(renderer.rendererKey, FakePayload)
            )
        )
    }

    override suspend fun stop() {
        started = false
    }
}

private object FakePayload : ScenePayload

private class RecordingSceneSession : SceneSession {
    val results = mutableListOf<SceneResult>()

    override val sessionId = "fake-session"
    override val config: SceneConfig = EmptySceneConfig
    override val credentialProvider = CredentialProvider { "fake-credential" }

    override suspend fun publishResult(result: SceneResult) {
        results += result
    }
}
