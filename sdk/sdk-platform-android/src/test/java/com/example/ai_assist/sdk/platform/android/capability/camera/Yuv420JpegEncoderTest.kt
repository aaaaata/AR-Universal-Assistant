package com.example.ai_assist.sdk.platform.android.capability.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class Yuv420JpegEncoderTest {
    @Test
    fun packNv21HandlesRowPaddingAndInterleavedChromaPlanes() {
        val y = plane(
            bytes = byteArrayOf(1, 2, 3, 4, 99, 99, 5, 6, 7, 8, 99, 99),
            rowStride = 6,
            pixelStride = 1
        )
        val u = plane(
            bytes = byteArrayOf(10, 99, 11, 99),
            rowStride = 4,
            pixelStride = 2
        )
        val v = plane(
            bytes = byteArrayOf(20, 99, 21, 99),
            rowStride = 4,
            pixelStride = 2
        )

        val nv21 = Yuv420JpegEncoder.packNv21(4, 2, y, u, v)

        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11),
            nv21
        )
    }

    private fun plane(bytes: ByteArray, rowStride: Int, pixelStride: Int) =
        YuvPlaneBuffer(ByteBuffer.wrap(bytes), rowStride, pixelStride)
}
