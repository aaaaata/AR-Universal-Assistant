package com.example.ai_assist.sdk.runtime

import android.view.ViewGroup
import com.example.ai_assist.sdk.scene.SceneDescriptor
import com.example.ai_assist.sdk.scene.ScenePayload
import com.example.ai_assist.sdk.scene.SceneProvider
import com.example.ai_assist.sdk.scene.SceneRenderer
import com.example.ai_assist.sdk.scene.SceneSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SceneRegistryTest {
    @Test
    fun registersAndFindsScene() {
        val registry = SceneRegistry()
        val provider = StubSceneProvider("fake")

        registry.register(provider)

        assertSame(provider, registry.find("fake"))
        assertEquals(listOf(provider), registry.registeredScenes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateSceneId() {
        val registry = SceneRegistry()
        registry.register(StubSceneProvider("fake"))

        registry.register(StubSceneProvider("fake"))
    }
}

private class StubSceneProvider(sceneId: String) : SceneProvider {
    override val descriptor = SceneDescriptor(sceneId, "Fake", "1.0")
    override val renderer = object : SceneRenderer {
        override val rendererKey = "fake"

        override fun render(container: ViewGroup, payload: ScenePayload) = Unit
    }

    override suspend fun start(session: SceneSession) = Unit

    override suspend fun stop() = Unit
}
