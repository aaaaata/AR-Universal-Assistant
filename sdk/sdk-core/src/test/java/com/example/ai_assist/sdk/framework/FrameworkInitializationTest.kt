package com.example.ai_assist.sdk.framework

import android.view.ViewGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameworkInitializationTest {
    @Test
    fun initializeMovesToReadyAndIsIdempotent() {
        val runtime = FrameworkRuntime(EmptyCameraScheduler())

        assertEquals(SdkState.UNINITIALIZED, runtime.getCurrentState())
        runtime.initialize()
        runtime.initialize()

        assertEquals(SdkState.READY, runtime.getCurrentState())
        runtime.close()
    }

    @Test
    fun startSceneBeforeInitializeIsRejected() {
        val runtime = FrameworkRuntime(EmptyCameraScheduler())
        runtime.registerScene(FakeProvider(emptyList()))

        val error = runCatching { runtime.startScene(FakeProvider.ID) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("SDK is not ready"))
        runtime.close()
    }

    @Test
    fun initializeOutsideUninitializedOrReadyIsRejected() {
        val runtime = FrameworkRuntime(
            cameraScheduler = EmptyCameraScheduler(),
            stateMachine = DefaultStateMachine(SdkState.STARTING)
        )

        val error = runCatching(runtime::initialize).exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("Cannot initialize SDK"))
        runtime.close()
    }

    @Test
    fun unsupportedCapabilityIsRejectedBeforeStarting() {
        val resources = DefaultResourceManager(setOf(CapabilityType.CAMERA))
        val runtime = FrameworkRuntime(
            cameraScheduler = EmptyCameraScheduler(),
            resourceManager = resources
        )
        runtime.registerScene(FakeProvider(listOf(CapabilityType.INPUT)))
        runtime.initialize()

        val error = runCatching { runtime.startScene(FakeProvider.ID) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("unsupported capabilities"))
        assertEquals(SdkState.READY, runtime.getCurrentState())
        runtime.close()
    }
}

private class EmptyCameraScheduler : CameraScheduler {
    override fun frames(
        intervalMillis: Long,
        cameraConfig: CapabilityConfig
    ): Flow<Frame> = emptyFlow()
}

private class FakeProvider(
    override val requiredCapabilities: List<CapabilityType>
) : SceneProvider {
    override val sceneId: String = ID
    override val sceneName: String = "Fake"
    override val version: String = "1"

    override fun createSceneConfig(): SceneConfig = object : SceneConfig {}

    override fun createRenderer(): SceneRenderer = object : SceneRenderer {
        override val rendererKey: String = "fake"
        override fun render(container: ViewGroup, content: SceneContent) = Unit
        override fun clear() = Unit
    }

    override fun createSession(sceneContext: SceneContext): Session =
        error("Session must not be created in initialization tests")

    companion object {
        const val ID = "fake"
    }
}
