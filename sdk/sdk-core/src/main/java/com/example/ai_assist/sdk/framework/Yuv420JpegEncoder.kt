package com.example.ai_assist.sdk.framework

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

internal data class YuvPlaneBuffer(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int
)

/** 仅在 Core 门禁选中原始帧后，将 YUV_420_888 编码为 JPEG。 */
internal object Yuv420JpegEncoder {
    fun writeJpeg(image: Image, destination: File, jpegQuality: Byte) {
        require(image.format == ImageFormat.YUV_420_888) { "Expected YUV_420_888 image" }
        val planes = image.planes.map {
            YuvPlaneBuffer(it.buffer, it.rowStride, it.pixelStride)
        }
        val nv21 = packNv21(image.width, image.height, planes[0], planes[1], planes[2])
        val jpegBytes = ByteArrayOutputStream(image.width * image.height)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        check(
            yuvImage.compressToJpeg(
                Rect(0, 0, image.width, image.height),
                jpegQuality.toInt() and 0xFF,
                jpegBytes
            )
        ) { "JPEG encoding failed" }
        FileOutputStream(destination).use { output ->
            jpegBytes.writeTo(output)
        }
    }

    private fun packNv21(
        width: Int,
        height: Int,
        yPlane: YuvPlaneBuffer,
        uPlane: YuvPlaneBuffer,
        vPlane: YuvPlaneBuffer
    ): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "YUV_420_888 dimensions must be positive and even"
        }
        val output = ByteArray(width * height * 3 / 2)
        copyLuma(width, height, yPlane, output)
        var outputOffset = width * height
        for (row in 0 until height / 2) {
            for (column in 0 until width / 2) {
                output[outputOffset++] = readPlaneByte(vPlane, row, column)
                output[outputOffset++] = readPlaneByte(uPlane, row, column)
            }
        }
        return output
    }

    private fun copyLuma(
        width: Int,
        height: Int,
        plane: YuvPlaneBuffer,
        output: ByteArray
    ) {
        if (plane.pixelStride == 1) {
            val source = plane.buffer.duplicate()
            val baseOffset = source.position()
            for (row in 0 until height) {
                source.position(baseOffset + row * plane.rowStride)
                source.get(output, row * width, width)
            }
            return
        }
        for (row in 0 until height) {
            for (column in 0 until width) {
                output[row * width + column] = readPlaneByte(plane, row, column)
            }
        }
    }

    private fun readPlaneByte(plane: YuvPlaneBuffer, row: Int, column: Int): Byte =
        plane.buffer.get(plane.buffer.position() + row * plane.rowStride + column * plane.pixelStride)
}
