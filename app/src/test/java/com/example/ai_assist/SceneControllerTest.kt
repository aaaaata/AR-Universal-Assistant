package com.example.ai_assist

import android.view.ViewGroup
import com.example.ai_assist.scene.SceneController
import com.example.ai_assist.ui.SceneRenderRouter
import com.example.ai_assist.sdk.scene.SceneSession
import com.example.ai_assist.sdk.scene.SceneDescriptor
import com.example.ai_assist.sdk.scene.ScenePayload
import com.example.ai_assist.sdk.scene.ScenePresentation
import com.example.ai_assist.sdk.scene.SceneProvider
import com.example.ai_assist.sdk.scene.SceneRegistration
import com.example.ai_assist.sdk.scene.SceneRenderer
import com.example.ai_assist.sdk.capability.CapabilityRequirement
import com.example.ai_assist.sdk.capability.StandardCapabilities
import com.example.ai_assist.sdk.capability.InputActionPublisher
import com.example.ai_assist.sdk.engine.EmptySceneConfig
import com.example.ai_assist.sdk.engine.SceneConfig
import com.example.ai_assist.sdk.engine.SdkConfig
import com.example.ai_assist.sdk.network.ScannedServerConfig
import com.example.ai_assist.sdk.network.ServerEndpointSceneConfig
import com.example.ai_assist.sdk.runtime.CoreSceneEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SceneControllerTest {
    /** 没有预装场景时，通用壳仍可显示“添加场景”入口。 */
    @Test
    fun supportsEmptyInstalledSceneList() {
        val registrations = emptyList<SceneRegistration>()

        assertEquals(0, controller(registrations).scenes.size)
        assertNull(SceneRenderRouter(registrations).rendererFor("missing", "missing"))
    }

    /** 验证场景目录按 sceneId 和 Renderer Key 路由。 */
    @Test
    fun resolvesRegisteredSceneRenderer() {
        val provider = FakeSceneProvider("scene-a")
        val registrations = listOf(registration(provider))
        val controller = controller(registrations)
        val router = SceneRenderRouter(registrations)

        assertEquals("Fake", controller.scenes.single().title)
        assertEquals("scene-a", controller.scenes.single().sceneId)
        assertEquals(1, controller.scenes.single().iconRes)
        assertEquals(
            setOf(CapabilityRequirement(StandardCapabilities.CAMERA, exclusive = true)),
            controller.scenes.single().requiredCapabilities
        )
        assertSame(provider.renderer, router.rendererFor("scene-a", "fake-renderer"))
        assertNull(router.rendererFor("scene-a", "other-renderer"))
        assertNull(router.rendererFor("missing-scene", "fake-renderer"))
    }

    /** 验证通用壳拒绝重复 sceneId。 */
    @Test
    fun rejectsDuplicateAppSceneIds() {
        assertThrows(IllegalArgumentException::class.java) {
            controller(
                listOf(
                    registration(FakeSceneProvider("duplicate")),
                    registration(FakeSceneProvider("duplicate"))
                )
            )
        }
    }

    /** 扫码地址只交给下一次会话，场景注册配置随后恢复默认值。 */
    @Test
    fun consumesScannedServerEndpointForOneSession() = runBlocking {
        val provider = FakeSceneProvider("scene-a", emptySet())
        val controller = controller(
            listOf(
                registration(
                    provider,
                    EndpointConfig("https://cloud.example/")
                )
            )
        )

        assertEquals(
            "scene-a",
            controller.updateServerEndpoint(
                "scene-a",
                ScannedServerConfig("scene-a", "http://192.168.1.25:8000/")
            )
        )
        assertEquals("http://192.168.1.25:8000/", controller.currentServerBaseUrl("scene-a"))

        controller.startScene("scene-a")

        assertEquals(
            "http://192.168.1.25:8000/",
            (provider.startedConfig as EndpointConfig).serverBaseUrl
        )
        assertEquals("https://cloud.example/", controller.currentServerBaseUrl("scene-a"))
        controller.stopScene()
    }

    /** 未开始会话就退出场景时，也能丢弃尚未消费的扫码地址。 */
    @Test
    fun resetsUnconsumedServerEndpoint() {
        val provider = FakeSceneProvider("scene-a", emptySet())
        val controller = controller(
            listOf(registration(provider, EndpointConfig("https://cloud.example/")))
        )
        controller.updateServerEndpoint(
            "scene-a",
            ScannedServerConfig("scene-a", "http://192.168.1.25:8000/")
        )

        controller.resetServerEndpoint("scene-a")

        assertEquals("https://cloud.example/", controller.currentServerBaseUrl("scene-a"))
    }

    /** 创建不需要初始化硬件的测试宿主。 */
    private fun controller(registrations: List<SceneRegistration>): SceneController {
        val engine = CoreSceneEngine().apply {
            initialize(SdkConfig())
            registrations.forEach { registerScene(it.provider) }
        }
        return SceneController(
            sceneEngine = engine,
            inputPublisher = InputActionPublisher { },
            registrations = registrations
        )
    }

    /** 创建测试场景注册项。 */
    private fun registration(
        provider: SceneProvider,
        config: SceneConfig = EmptySceneConfig
    ) = SceneRegistration(
        provider = provider,
        config = config,
        credential = null,
        presentation = ScenePresentation(
            menuTitle = provider.descriptor.name,
            menuIconRes = 1
        )
    )

    private class FakeSceneProvider(
        sceneId: String,
        requiredCapabilities: Set<CapabilityRequirement> = setOf(
            CapabilityRequirement(StandardCapabilities.CAMERA, exclusive = true)
        )
    ) : SceneProvider {
        var startedConfig: SceneConfig? = null

        override val descriptor = SceneDescriptor(
            sceneId,
            "Fake",
            "1",
            requiredCapabilities
        )
        override val renderer = object : SceneRenderer {
            override val rendererKey = "fake-renderer"

            /** 测试只验证路由，不执行 Android 渲染。 */
            override fun render(container: ViewGroup, payload: ScenePayload) = Unit
        }

        /** 测试场景无需启动行为。 */
        override suspend fun start(session: SceneSession) {
            startedConfig = session.config
        }

        /** 测试场景无需停止行为。 */
        override suspend fun stop() = Unit
    }

    private data class EndpointConfig(
        override val serverBaseUrl: String
    ) : ServerEndpointSceneConfig {
        override val supportedQrSceneIds = setOf("scene-a")

        override fun withServerBaseUrl(serverBaseUrl: String): EndpointConfig =
            copy(serverBaseUrl = serverBaseUrl)
    }
}
