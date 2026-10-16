package com.example.ai_assist.sdk.capability

import java.io.File

/**
 * 相机采集参数。
 *
 * @property targetWidth 期望输出宽度。
 * @property targetHeight 期望输出高度。
 * @property framesPerSecond 相机重复请求帧率。
 */
data class CameraConfig(
    val targetWidth: Int = 810,
    val targetHeight: Int = 1080,
    val framesPerSecond: Int = 15
)

/**
 * 已完成编码的相机帧。
 *
 * @property file JPEG 临时文件，由接收方使用后删除。
 * @property imageRotationDegrees 上传时需要附带的顺时针旋转角度。
 * @property frameId 相机时间戳生成的帧标识。
 * @property selectedAtElapsedMillis 帧被选中的单调时钟时间。
 * @property encodedAtElapsedMillis 帧编码完成的单调时钟时间。
 */
data class CapturedFrame(
    val file: File,
    val imageRotationDegrees: Int,
    val frameId: Long,
    val selectedAtElapsedMillis: Long,
    val encodedAtElapsedMillis: Long,
    val medianLuma: Int? = null
)

/** 在编码前判断当前帧是否需要处理。 */
fun interface CameraFrameSelector {
    /** 根据相机时间戳决定是否选择当前帧。 */
    fun shouldCapture(timestampMillis: Long): Boolean
}

/** 接收相机编码结果。 */
interface CameraFrameListener {
    /** 接收已写入临时文件的 JPEG 帧。 */
    fun onFrameCaptured(frame: CapturedFrame)

    /** 接收相机打开、采集或编码错误。 */
    fun onError(error: Throwable)
}

/** 提供无预览画面的相机帧采集能力。 */
interface CameraCapability : SdkCapability {
    /** 启动相机，并按选择器结果异步返回编码帧。 */
    fun start(
        config: CameraConfig,
        frameSelector: CameraFrameSelector,
        listener: CameraFrameListener
    )

    /** 停止采集并释放当前相机会话。 */
    fun stop()

    /** 永久释放相机能力持有的线程和资源。 */
    fun release()

    /** 租约释放时停止当前相机会话。 */
    override fun onLeaseReleased() = stop()

    /** SDK 关闭时永久释放相机线程和资源。 */
    override fun close() = release()
}
