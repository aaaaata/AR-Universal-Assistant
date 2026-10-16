package com.example.ai_assist.sdk.platform.android.capability.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale

internal data class YuvPlaneBuffer(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int
)

internal object Yuv420JpegEncoder {
    private const val JPEG_QUALITY = 80

    /** 将 YUV_420_888 图像编码并写入 JPEG 文件。 */
    fun writeJpeg(image: Image, destination: File, frameId: Long) {
        require(image.format == ImageFormat.YUV_420_888) { "Expected YUV_420_888 image" }

        val conversionStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val planes = image.planes.map {
            YuvPlaneBuffer(it.buffer, it.rowStride, it.pixelStride)
        }
        val nv21 = packNv21(image.width, image.height, planes[0], planes[1], planes[2])
        val conversionCompletedAtNs = SystemClock.elapsedRealtimeNanos()

        val jpegBytes = ByteArrayOutputStream(image.width * image.height)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        check(
            yuvImage.compressToJpeg(
                Rect(0, 0, image.width, image.height),
                JPEG_QUALITY,
                jpegBytes
            )
        ) { "JPEG encoding failed" }
        val compressionCompletedAtNs = SystemClock.elapsedRealtimeNanos()

        FileOutputStream(destination).use { stream -> jpegBytes.writeTo(stream) }
        val fileWrittenAtNs = SystemClock.elapsedRealtimeNanos()

        Log.d(
            "RealtimeTiming",
            "frame_encode_breakdown frame_id=$frameId " + String.format(
                Locale.US,
                "yuv_to_nv21_ms=%.3f jpeg_compress_ms=%.3f file_write_ms=%.3f " +
                    "total_ms=%.3f quality=%d bytes=%d",
                elapsedMillis(conversionStartedAtNs, conversionCompletedAtNs),
                elapsedMillis(conversionCompletedAtNs, compressionCompletedAtNs),
                elapsedMillis(compressionCompletedAtNs, fileWrittenAtNs),
                elapsedMillis(conversionStartedAtNs, fileWrittenAtNs),
                JPEG_QUALITY,
                destination.length()
            )
        )
    }

    /** Median luma sampled from the Y plane without allocating a full image copy. */
    fun medianLuma(image: Image, sampleStep: Int = 16): Int? {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.isEmpty()) return null
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val baseOffset = buffer.position()
        val histogram = IntArray(256)
        var sampleCount = 0
        var row = 0
        while (row < image.height) {
            var column = 0
            while (column < image.width) {
                val offset = baseOffset + row * plane.rowStride + column * plane.pixelStride
                if (offset >= buffer.limit()) break
                histogram[buffer.get(offset).toInt() and 0xFF]++
                sampleCount++
                column += sampleStep
            }
            row += sampleStep
        }
        if (sampleCount == 0) return null
        val middle = (sampleCount + 1) / 2
        var cumulative = 0
        return histogram.indices.first { value ->
            cumulative += histogram[value]
            cumulative >= middle
        }
    }

    /** 将带行步长和像素步长的 YUV 三平面打包为 NV21。 */
    internal fun packNv21(
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

    /** 将亮度平面按有效宽高复制到连续数组。 */
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

    /** 按平面步长读取指定像素字节。 */
    private fun readPlaneByte(plane: YuvPlaneBuffer, row: Int, column: Int): Byte =
        plane.buffer.get(plane.buffer.position() + row * plane.rowStride + column * plane.pixelStride)

    /** 将纳秒时间差转换为毫秒。 */
    private fun elapsedMillis(startNs: Long, endNs: Long): Double =
        (endNs - startNs) / 1_000_000.0
}
