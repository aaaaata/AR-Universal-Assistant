package com.example.ai_assist.sdk.platform.android.capability.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityContractTest {
    private val cameraSource = File(
        "src/main/java/com/example/ai_assist/sdk/platform/android/capability/camera/Camera2Capability.kt"
    ).readText()
    private val encoderSource = File(
        "src/main/java/com/example/ai_assist/sdk/platform/android/capability/camera/Yuv420JpegEncoder.kt"
    ).readText()

    @Test
    fun imageReaderIsTheOnlyRepeatingCameraOutput() {
        val setupBlock = cameraSource
            .substringAfter("private fun setUpImageReader")
            .substringBefore("private fun handleAvailableImage")

        assertTrue(setupBlock.contains("addTarget(reader.surface)"))
        assertFalse(setupBlock.contains("surfaceTextureListener"))
        assertFalse(setupBlock.contains("for (surface in surfaceList)"))
        assertFalse(setupBlock.contains("TEMPLATE_STILL_CAPTURE"))
    }

    @Test
    fun frameSelectorRunsBeforeEncoding() {
        val imageBlock = cameraSource
            .substringAfter("private fun handleAvailableImage")
            .substringBefore("private fun encodeImage")

        assertTrue(imageBlock.contains("frameSelector.shouldCapture(frameId)"))
        assertTrue(imageBlock.indexOf("frameSelector.shouldCapture(frameId)") < imageBlock.indexOf("encodeImage("))
        assertFalse(cameraSource.contains("AppConfig.REALTIME_CAPTURE_INTERVAL_MS"))
    }

    @Test
    fun jpegEncoderLogsStageTimingsWithFrameId() {
        assertTrue(cameraSource.contains("Yuv420JpegEncoder.writeJpeg(image, outputFile, frameId)"))
        assertTrue(encoderSource.contains("YuvImage(nv21, ImageFormat.NV21"))
        assertTrue(encoderSource.contains("private const val JPEG_QUALITY = 80"))
        assertTrue(encoderSource.contains("frame_encode_breakdown frame_id=\$frameId"))
        assertTrue(encoderSource.contains("yuv_to_nv21_ms="))
        assertTrue(encoderSource.contains("jpeg_compress_ms="))
        assertTrue(encoderSource.contains("file_write_ms="))
        assertFalse(encoderSource.contains("Bitmap"))
    }
}
