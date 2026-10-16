package com.example.ai_assist.sdk.platform.android.assembly

import android.content.Context
import com.example.ai_assist.sdk.capability.CapabilityUnavailableException
import com.example.ai_assist.sdk.capability.SdkCapability
import com.example.ai_assist.sdk.capability.StandardCapabilities
import com.example.ai_assist.sdk.capability.AudioOutputCapability
import com.example.ai_assist.sdk.capability.CameraCapability
import com.example.ai_assist.sdk.capability.DeviceCapability
import com.example.ai_assist.sdk.capability.DisplayCapability
import com.example.ai_assist.sdk.capability.InputActionPublisher
import com.example.ai_assist.sdk.capability.InputCapability
import com.example.ai_assist.sdk.capability.LocationCapability
import com.example.ai_assist.sdk.capability.MicrophoneCapability
import com.example.ai_assist.sdk.capability.MotionCapability
import com.example.ai_assist.sdk.capability.NetworkCapability
import com.example.ai_assist.sdk.engine.CredentialProvider
import com.example.ai_assist.sdk.engine.SceneEngine
import com.example.ai_assist.sdk.engine.SdkConfig
import com.example.ai_assist.sdk.platform.android.capability.audio.AndroidAudioOutputCapability
import com.example.ai_assist.sdk.platform.android.capability.audio.AndroidMicrophoneCapability
import com.example.ai_assist.sdk.platform.android.capability.camera.Camera2Capability
import com.example.ai_assist.sdk.platform.android.capability.device.AndroidDeviceCapability
import com.example.ai_assist.sdk.platform.android.capability.display.AndroidDisplayCapability
import com.example.ai_assist.sdk.platform.android.capability.input.DefaultInputCapability
import com.example.ai_assist.sdk.platform.android.capability.location.AndroidLocationCapability
import com.example.ai_assist.sdk.platform.android.capability.motion.AndroidMotionCapability
import com.example.ai_assist.sdk.platform.android.capability.network.AndroidNetworkCapability
import com.example.ai_assist.sdk.runtime.CoreSceneEngine
import com.example.ai_assist.sdk.scene.SceneDescriptor
import com.example.ai_assist.sdk.scene.SceneProvider

/** 已根据场景需求完成装配的 SceneEngine 及宿主输入发布端口。 */
data class AndroidSceneEnvironment(
    val sceneEngine: SceneEngine,
    val inputPublisher: InputActionPublisher?,
    val cameraCapability: CameraCapability?
)

/** 根据启用场景的能力声明创建默认 Android SDK。 */
object AndroidSdkAssembler {
    /** 注册场景需要的默认能力，并允许宿主覆盖指定能力实现。 */
    fun assemble(
        context: Context,
        scenes: List<SceneProvider>,
        credentialProvider: CredentialProvider = CredentialProvider { null },
        config: SdkConfig = SdkConfig(),
        capabilityOverrides: Map<String, SdkCapability> = emptyMap()
    ): AndroidSceneEnvironment {
        require(scenes.isNotEmpty()) { "At least one scene is required" }
        val descriptors = scenes.map { it.descriptor }
        val requiredCapabilityIds = requiredCapabilityIds(descriptors)
        val declaredCapabilityIds = declaredCapabilityIds(descriptors)
        val unusedOverrides = capabilityOverrides.keys - declaredCapabilityIds
        require(unusedOverrides.isEmpty()) {
            "Capability overrides are not required by any scene: ${unusedOverrides.joinToString()}"
        }

        val sceneEngine = CoreSceneEngine(credentialProvider)
        scenes.forEach(sceneEngine::registerScene)
        val capabilities = declaredCapabilityIds.mapNotNull { capabilityId ->
            val capability = capabilityOverrides[capabilityId]
                ?: createDefaultCapabilityOrNull(context.applicationContext, capabilityId)
            if (capability == null && capabilityId in requiredCapabilityIds) {
                throw CapabilityUnavailableException(capabilityId)
            }
            capability?.let { capabilityId to it }
        }.toMap()
        capabilities.forEach { (capabilityId, capability) ->
            validateStandardCapability(capabilityId, capability)
        }
        capabilities.forEach { (capabilityId, capability) ->
            sceneEngine.registerCapability(capabilityId, capability)
        }
        sceneEngine.initialize(config)
        return AndroidSceneEnvironment(
            sceneEngine = sceneEngine,
            inputPublisher = capabilities[StandardCapabilities.INPUT] as? InputActionPublisher,
            cameraCapability = capabilities[StandardCapabilities.CAMERA] as? CameraCapability
        )
    }

    /** 返回全部场景声明的能力 ID 并集。 */
    internal fun requiredCapabilityIds(descriptors: List<SceneDescriptor>): Set<String> =
        descriptors.flatMapTo(linkedSetOf()) { descriptor ->
            descriptor.requiredCapabilities.map { it.capabilityId }
        }

    /** 返回全部场景声明的必要与可选能力 ID 并集。 */
    internal fun declaredCapabilityIds(descriptors: List<SceneDescriptor>): Set<String> =
        descriptors.flatMapTo(linkedSetOf()) { descriptor ->
            (descriptor.requiredCapabilities + descriptor.optionalCapabilities)
                .map { it.capabilityId }
        }

    /** 创建标准能力的默认 Android 实现。 */
    private fun createDefaultCapabilityOrNull(
        context: Context,
        capabilityId: String
    ): SdkCapability? =
        when (capabilityId) {
            StandardCapabilities.CAMERA -> Camera2Capability(context)
            StandardCapabilities.INPUT -> DefaultInputCapability()
            StandardCapabilities.DEVICE -> AndroidDeviceCapability(context)
            StandardCapabilities.NETWORK -> AndroidNetworkCapability(context)
            StandardCapabilities.MICROPHONE -> AndroidMicrophoneCapability(context)
            StandardCapabilities.AUDIO_OUTPUT -> AndroidAudioOutputCapability(context)
            StandardCapabilities.MOTION -> AndroidMotionCapability(context)
            StandardCapabilities.DISPLAY -> AndroidDisplayCapability(context)
            StandardCapabilities.LOCATION -> AndroidLocationCapability(context)
            else -> null
        }

    /** 防止标准能力 ID 与覆盖实现类型不匹配。 */
    private fun validateStandardCapability(capabilityId: String, capability: SdkCapability) {
        val valid = when (capabilityId) {
            StandardCapabilities.CAMERA -> capability is CameraCapability
            StandardCapabilities.INPUT -> capability is InputCapability
            StandardCapabilities.DEVICE -> capability is DeviceCapability
            StandardCapabilities.NETWORK -> capability is NetworkCapability
            StandardCapabilities.MICROPHONE -> capability is MicrophoneCapability
            StandardCapabilities.AUDIO_OUTPUT -> capability is AudioOutputCapability
            StandardCapabilities.MOTION -> capability is MotionCapability
            StandardCapabilities.DISPLAY -> capability is DisplayCapability
            StandardCapabilities.LOCATION -> capability is LocationCapability
            else -> true
        }
        require(valid) { "Capability implementation does not match ID: $capabilityId" }
    }
}
