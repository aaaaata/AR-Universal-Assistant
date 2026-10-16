package com.example.ai_assist.sdk.capability

import com.example.ai_assist.sdk.capability.AudioCaptureConfig
import com.example.ai_assist.sdk.capability.DisplayInfo
import com.example.ai_assist.sdk.capability.LocationSample
import com.example.ai_assist.sdk.capability.MotionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CapabilityModelsTest {
    @Test
    fun commonCapabilityModelsAcceptValidValues() {
        assertEquals(16_000, AudioCaptureConfig().sampleRateHz)
        assertEquals(90f, DisplayInfo(640, 480, 90f, stereoscopic = true).refreshRateHz)
        assertEquals(31.2, LocationSample(31.2, 121.5, 3f, 1L).latitude, 0.0)
        assertEquals(
            3,
            MotionSample(1L, FloatArray(3), FloatArray(3))
                .accelerationMetersPerSecondSquared.size
        )
    }

    @Test
    fun commonCapabilityModelsRejectInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { AudioCaptureConfig(channelCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { DisplayInfo(0, 480, 90f, false) }
        assertThrows(IllegalArgumentException::class.java) {
            LocationSample(91.0, 121.5, null, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionSample(1L, FloatArray(2), FloatArray(3))
        }
    }
}
