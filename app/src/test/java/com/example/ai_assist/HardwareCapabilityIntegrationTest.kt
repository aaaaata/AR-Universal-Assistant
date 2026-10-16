package com.example.ai_assist

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCapabilityIntegrationTest {
    private val activitySource = File(
        "src/main/java/com/example/ai_assist/entry/MainActivity.kt"
    ).readText()
    private val sceneControllerSource = File(
        "src/main/java/com/example/ai_assist/scene/SceneController.kt"
    ).readText()
    private val appAssemblerSource = File(
        "src/main/java/com/example/ai_assist/assembly/AppAssembler.kt"
    ).readText()
    private val sdkAssemblerSource = File(
        "../sdk/sdk-platform-android/src/main/java/com/example/ai_assist/sdk/platform/android/assembly/AndroidSdkAssembler.kt"
    ).readText()
    private val sceneDiscoverySource = File(
        "../sdk/sdk-platform-android/src/main/java/com/example/ai_assist/sdk/platform/android/assembly/AndroidSceneModuleDiscovery.kt"
    ).readText()
    private val inputMapperSource = File(
        "../sdk/sdk-device-rayneo-x3/src/main/java/com/example/ai_assist/sdk/device/rayneo/x3/MercuryInputActionMapper.kt"
    ).readText()
    private val sdkInitializerSource = File(
        "../sdk/sdk-device-rayneo-x3/src/main/java/com/example/ai_assist/sdk/device/rayneo/x3/MercurySdkInitializer.kt"
    ).readText()
    private val applicationSource = File(
        "src/main/java/com/example/ai_assist/entry/AssistApplication.kt"
    ).readText()
    private val viewModelSource = File(
        "src/main/java/com/example/ai_assist/ui/MainViewModel.kt"
    ).readText()
    private val renderRouterSource = File(
        "src/main/java/com/example/ai_assist/ui/SceneRenderRouter.kt"
    ).readText()
    private val permissionRequesterSource = File(
        "src/main/java/com/example/ai_assist/permission/PermissionRequester.kt"
    ).readText()
    private val screenRendererSource = File(
        "src/main/java/com/example/ai_assist/ui/MainScreenRenderer.kt"
    ).readText()
    private val appBuildSource = File("build.gradle.kts").readText()

    /** 验证 MainActivity 不依赖具体场景、Camera 实现或 Core 实现。 */
    @Test
    fun mainActivityIsSceneAgnostic() {
        assertTrue(activitySource.contains("MainViewModelFactory(applicationContext)"))
        assertTrue(
            activitySource.contains(
                "viewModel.onInputAction(MercuryInputActionMapper.map(source))"
            )
        )
        assertTrue(activitySource.contains("sceneRenderRouter = viewModel.sceneRenderRouter"))
        assertTrue(activitySource.contains("screenRenderer.renderResult(this, effect.result)"))
        assertFalse(activitySource.contains("startPrimaryScene()"))
        assertFalse(activitySource.contains("stopScene()"))
        assertFalse(activitySource.contains("Mahjong"))
        assertFalse(activitySource.contains("Camera2Capability"))
        assertFalse(activitySource.contains("CoreSceneEngine"))
    }

    /** 验证 App 只发现通用场景注册，SDK 根据场景声明装配默认能力。 */
    @Test
    fun sdkFactoryInstallsCapabilitiesRequiredByScenes() {
        assertTrue(appAssemblerSource.contains("AndroidSceneModuleDiscovery.discover("))
        assertTrue(appAssemblerSource.contains("AndroidSdkAssembler.assemble("))
        assertTrue(appAssemblerSource.contains("scenes = registrations.map { it.provider }"))
        assertFalse(appAssemblerSource.contains("Mahjong"))
        assertTrue(sceneDiscoverySource.contains("SceneModule"))
        assertTrue(sceneDiscoverySource.contains("META_DATA_PREFIX"))
        assertFalse(appAssemblerSource.contains("Camera2Capability"))
        assertFalse(appAssemblerSource.contains("AndroidDeviceCapability"))
        assertFalse(appAssemblerSource.contains("DefaultInputCapability"))
        assertTrue(sdkAssemblerSource.contains("descriptor.requiredCapabilities"))
        assertTrue(sdkAssemblerSource.contains("createDefaultCapability"))
        assertFalse(sceneControllerSource.contains("Mahjong"))
        assertFalse(sceneControllerSource.contains("Camera2Capability"))
        assertFalse(sceneControllerSource.contains("ViewGroup"))
        assertTrue(renderRouterSource.contains("renderer.render(container, content.scenePayload)"))
    }

    /** 验证 MainActivity 按场景声明映射权限并处理 Android 返回动作。 */
    @Test
    fun mainActivityDelegatesSceneDrivenPermissionAndNavigation() {
        assertTrue(activitySource.contains("AndroidPermissionResolver.resolve(effect.requirements)"))
        assertTrue(activitySource.contains("permissionRequester.request(PermissionRequest(permissions))"))
        assertTrue(activitySource.contains("viewModel.onScenePermissionResult(effect.sceneId, result)"))
        assertFalse(activitySource.contains("cameraPermissionRequest"))
        assertTrue(activitySource.contains("onBackPressedDispatcher.onBackPressed()"))
        assertFalse(activitySource.contains("registerForActivityResult"))
        assertTrue(
            permissionRequesterSource.contains(
                "suspend fun request(permissionRequest: PermissionRequest)"
            )
        )
        assertTrue(permissionRequesterSource.contains("RequestMultiplePermissions"))
        assertTrue(screenRendererSource.contains("sceneRenderRouter.render(binding.sceneContent, result)"))
    }

    /** 验证 Mercury 适配覆盖全部受支持手势并发布到输入能力。 */
    @Test
    fun mercuryAdapterCoversAllSupportedGestures() {
        val expectedMappings = listOf(
            "TempleAction.Click -> InputAction.CLICK",
            "TempleAction.DoubleClick -> InputAction.DOUBLE_CLICK",
            "TempleAction.TripleClick -> InputAction.TRIPLE_CLICK",
            "TempleAction.SlideForward -> InputAction.SLIDE_FORWARD",
            "TempleAction.SlideBackward -> InputAction.SLIDE_BACKWARD",
            "TempleAction.SlideUpwards -> InputAction.SLIDE_UP",
            "TempleAction.SlideDownwards -> InputAction.SLIDE_DOWN"
        )

        expectedMappings.forEach { mapping -> assertTrue(inputMapperSource.contains(mapping)) }
        assertTrue(activitySource.contains("MercuryInputActionMapper.map(source)"))
        assertTrue(
            activitySource.contains(
                "viewModel.onInputAction(MercuryInputActionMapper.map(source))"
            )
        )
        assertTrue(viewModelSource.contains("sceneController.publishInput(action)"))
        assertTrue(viewModelSource.contains("SceneInputPolicy.resolve"))
    }

    /** 验证 App 通过通用契约选择 Mercury 平台实现。 */
    @Test
    fun mercuryIntegrationImplementsCommonContracts() {
        assertTrue(inputMapperSource.contains("InputActionMapper<TempleAction>"))
        assertTrue(sdkInitializerSource.contains(": DeviceSdkInitializer"))
        assertTrue(applicationSource.contains("sdkInitializer: DeviceSdkInitializer"))
        assertTrue(applicationSource.contains("MercurySdkInitializer"))
        assertFalse(applicationSource.contains("MercurySDK.init"))
    }

    /** 验证 App 不再打包已迁移或未使用的业务依赖。 */
    @Test
    fun appDropsUnusedFeatureDependencies() {
        assertTrue(appBuildSource.contains("packagedSceneModules"))
        assertTrue(appBuildSource.contains("it.path.startsWith(\":scene-\")"))
        assertFalse(appBuildSource.contains("scene-mahjong"))
        assertFalse(appBuildSource.contains("androidx.camera"))
        assertFalse(appBuildSource.contains("coil"))
        assertFalse(appBuildSource.contains("retrofit"))
        assertFalse(appBuildSource.contains("recyclerview"))
    }
}
