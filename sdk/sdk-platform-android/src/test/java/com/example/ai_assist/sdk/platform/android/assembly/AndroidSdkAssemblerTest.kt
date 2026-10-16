package com.example.ai_assist.sdk.platform.android.assembly

import com.example.ai_assist.sdk.capability.CapabilityRequirement
import com.example.ai_assist.sdk.capability.StandardCapabilities
import com.example.ai_assist.sdk.scene.SceneDescriptor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSdkAssemblerTest {
    /** 只汇总启用场景实际声明的能力，并去除重复 ID。 */
    @Test
    fun collectsRequiredCapabilityIdsFromScenes() {
        val descriptors = listOf(
            SceneDescriptor(
                sceneId = "camera-scene",
                name = "Camera",
                version = "1",
                requiredCapabilities = setOf(
                    CapabilityRequirement(StandardCapabilities.CAMERA, exclusive = true),
                    CapabilityRequirement(StandardCapabilities.INPUT)
                )
            ),
            SceneDescriptor(
                sceneId = "input-scene",
                name = "Input",
                version = "1",
                requiredCapabilities = setOf(
                    CapabilityRequirement(StandardCapabilities.INPUT)
                )
            )
        )

        assertEquals(
            linkedSetOf(StandardCapabilities.CAMERA, StandardCapabilities.INPUT),
            AndroidSdkAssembler.requiredCapabilityIds(descriptors)
        )
    }

    @Test
    fun collectsRequiredAndOptionalCapabilityIds() {
        val descriptor = SceneDescriptor(
            sceneId = "cv-scene",
            name = "CV",
            version = "1",
            requiredCapabilities = setOf(
                CapabilityRequirement(StandardCapabilities.CAMERA)
            ),
            optionalCapabilities = setOf(
                CapabilityRequirement(StandardCapabilities.INPUT),
                CapabilityRequirement(StandardCapabilities.AUDIO_OUTPUT)
            )
        )

        assertEquals(
            linkedSetOf(
                StandardCapabilities.CAMERA,
                StandardCapabilities.INPUT,
                StandardCapabilities.AUDIO_OUTPUT
            ),
            AndroidSdkAssembler.declaredCapabilityIds(listOf(descriptor))
        )
    }

    /** 防止标准能力新增契约后忘记接入默认 Android 实现。 */
    @Test
    fun installsEveryStandardAndroidCapability() {
        val factorySource = File(
            "src/main/java/com/example/ai_assist/sdk/platform/android/assembly/AndroidSdkAssembler.kt"
        ).readText()
        val expectedMappings = mapOf(
            StandardCapabilities.CAMERA to "Camera2Capability(context)",
            StandardCapabilities.INPUT to "DefaultInputCapability()",
            StandardCapabilities.DEVICE to "AndroidDeviceCapability(context)",
            StandardCapabilities.NETWORK to "AndroidNetworkCapability(context)",
            StandardCapabilities.MICROPHONE to "AndroidMicrophoneCapability(context)",
            StandardCapabilities.AUDIO_OUTPUT to "AndroidAudioOutputCapability(context)",
            StandardCapabilities.MOTION to "AndroidMotionCapability(context)",
            StandardCapabilities.DISPLAY to "AndroidDisplayCapability(context)",
            StandardCapabilities.LOCATION to "AndroidLocationCapability(context)"
        )

        expectedMappings.forEach { (capabilityId, implementation) ->
            assertTrue(
                "Missing default implementation for $capabilityId",
                factorySource.contains("StandardCapabilities.${constantName(capabilityId)} -> $implementation")
            )
        }
    }

    private fun constantName(capabilityId: String): String = when (capabilityId) {
        StandardCapabilities.CAMERA -> "CAMERA"
        StandardCapabilities.INPUT -> "INPUT"
        StandardCapabilities.DEVICE -> "DEVICE"
        StandardCapabilities.NETWORK -> "NETWORK"
        StandardCapabilities.MICROPHONE -> "MICROPHONE"
        StandardCapabilities.AUDIO_OUTPUT -> "AUDIO_OUTPUT"
        StandardCapabilities.MOTION -> "MOTION"
        StandardCapabilities.DISPLAY -> "DISPLAY"
        StandardCapabilities.LOCATION -> "LOCATION"
        else -> error("Unknown standard capability: $capabilityId")
    }
}
