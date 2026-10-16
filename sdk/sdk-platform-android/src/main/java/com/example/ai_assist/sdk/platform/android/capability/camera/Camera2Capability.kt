package com.example.ai_assist.sdk.platform.android.capability.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import com.example.ai_assist.sdk.capability.CameraCapability
import com.example.ai_assist.sdk.capability.CameraConfig
import com.example.ai_assist.sdk.capability.CameraFrameListener
import com.example.ai_assist.sdk.capability.CameraFrameSelector
import com.example.ai_assist.sdk.capability.CapturedFrame
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 使用 Android Camera2 提供无预览画面的相机采集能力。
 *
 * @param context 用于访问相机服务和应用专属文件目录的上下文。
 */
internal class Camera2Capability(context: Context) : CameraCapability {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraThread = HandlerThread("assist-sdk-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val lock = Any()
    private val isEncoding = AtomicBoolean(false)

    private var generation = 0L
    private var opening = false
    private var released = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    /** 启动 Camera2 重复采集，并在编码前调用帧选择器。 */
    @SuppressLint("MissingPermission")
    override fun start(
        config: CameraConfig,
        frameSelector: CameraFrameSelector,
        listener: CameraFrameListener
    ) {
        require(config.targetWidth > 0 && config.targetHeight > 0) {
            "Camera target dimensions must be positive"
        }
        require(config.framesPerSecond > 0) { "Camera FPS must be positive" }

        val requestGeneration = synchronized(lock) {
            if (released) {
                notifyError(listener, IllegalStateException("Camera capability has been released"))
                return
            }
            if (opening || cameraDevice != null) return
            opening = true
            ++generation
        }

        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            synchronized(lock) { opening = false }
            notifyError(listener, SecurityException("Camera permission is not granted"))
            return
        }

        try {
            val cameraId = cameraManager.cameraIdList.first()
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamMap = requireNotNull(
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ) { "Camera stream configuration is unavailable" }
            val captureOutput = selectCaptureOutput(
                streamMap.getOutputSizes(ImageFormat.YUV_420_888),
                streamMap.getOutputSizes(ImageFormat.JPEG),
                streamMap.getOutputSizes(SurfaceTexture::class.java),
                config.targetWidth,
                config.targetHeight
            )

            cameraManager.openCamera(
                cameraId,
                createStateCallback(
                    requestGeneration,
                    captureOutput,
                    config,
                    frameSelector,
                    listener
                ),
                cameraHandler
            )
        } catch (error: Throwable) {
            synchronized(lock) { opening = false }
            notifyError(listener, error)
        }
    }

    /** 关闭 Camera2 会话、设备和 ImageReader。 */
    override fun stop() {
        synchronized(lock) {
            generation++
            opening = false
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        }
    }

    /** 释放相机资源和后台线程；释放后不可再次启动。 */
    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
        }
        stop()
        cameraExecutor.shutdown()
        cameraThread.quitSafely()
    }

    /** 创建一次相机打开请求对应的状态回调。 */
    private fun createStateCallback(
        requestGeneration: Long,
        captureOutput: CaptureOutput,
        config: CameraConfig,
        frameSelector: CameraFrameSelector,
        listener: CameraFrameListener
    ) = object : CameraDevice.StateCallback() {
        /** 接受当前启动请求打开的相机，并延迟创建采集会话。 */
        override fun onOpened(camera: CameraDevice) {
            val accepted = synchronized(lock) {
                if (released || requestGeneration != generation) {
                    false
                } else {
                    opening = false
                    cameraDevice = camera
                    true
                }
            }
            if (!accepted) {
                camera.close()
                return
            }
            cameraHandler.postDelayed({
                if (synchronized(lock) { cameraDevice === camera && requestGeneration == generation }) {
                    setUpImageReader(
                        camera,
                        requestGeneration,
                        captureOutput,
                        config,
                        frameSelector,
                        listener
                    )
                }
            }, CAMERA_OPEN_SETTLE_MILLIS)
        }

        /** 关闭已断开的相机并通知当前会话。 */
        override fun onDisconnected(camera: CameraDevice) {
            closeOpenedCamera(camera, requestGeneration)
            if (isCurrentGeneration(requestGeneration)) {
                notifyError(listener, IllegalStateException("Camera disconnected"))
            }
        }

        /** 关闭打开失败的相机并通知当前会话。 */
        override fun onError(camera: CameraDevice, error: Int) {
            closeOpenedCamera(camera, requestGeneration)
            if (isCurrentGeneration(requestGeneration)) {
                notifyError(listener, IllegalStateException("Camera open failed with code $error"))
            }
        }
    }

    /** 创建 ImageReader，并启动仅输出到 ImageReader 的重复请求。 */
    private fun setUpImageReader(
        camera: CameraDevice,
        requestGeneration: Long,
        captureOutput: CaptureOutput,
        config: CameraConfig,
        frameSelector: CameraFrameSelector,
        listener: CameraFrameListener
    ) {
        val reader = ImageReader.newInstance(
            captureOutput.size.width,
            captureOutput.size.height,
            captureOutput.format,
            MAX_IMAGES
        )
        synchronized(lock) {
            imageReader?.close()
            imageReader = reader
        }
        reader.setOnImageAvailableListener({ availableReader ->
            handleAvailableImage(
                availableReader,
                requestGeneration,
                frameSelector,
                listener
            )
        }, cameraHandler)

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(config.framesPerSecond, config.framesPerSecond)
                )
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (captureOutput.format == ImageFormat.JPEG) {
                    set(CaptureRequest.JPEG_ORIENTATION, YUV_UPLOAD_ROTATION_DEGREES)
                }
            }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(reader.surface)),
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    /** 保存当前采集会话并启动重复请求。 */
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (synchronized(lock) { cameraDevice !== camera }) {
                            session.close()
                            return
                        }
                        synchronized(lock) { captureSession = session }
                        try {
                            session.setRepeatingRequest(request.build(), null, cameraHandler)
                            Log.d(TAG, "ImageReader repeating capture started at ${config.framesPerSecond} FPS")
                        } catch (error: Throwable) {
                            notifyError(listener, error)
                        }
                    }

                    /** 报告采集会话配置失败。 */
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        notifyError(listener, IllegalStateException("Camera session configuration failed"))
                    }
                }
            )
            camera.createCaptureSession(sessionConfig)
        } catch (error: Throwable) {
            notifyError(listener, error)
        }
    }

    /** 选择最新帧，并将编码任务交给独立线程。 */
    private fun handleAvailableImage(
        reader: ImageReader,
        requestGeneration: Long,
        frameSelector: CameraFrameSelector,
        listener: CameraFrameListener
    ) {
        val image = try {
            reader.acquireLatestImage()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Drop frame because ImageReader buffer is full", error)
            null
        } ?: return
        val frameId = image.timestamp / NANOS_PER_MILLISECOND
        val shouldCapture = runCatching { frameSelector.shouldCapture(frameId) }
            .onFailure { notifyError(listener, it) }
            .getOrDefault(false)
        if (!shouldCapture || !isEncoding.compareAndSet(false, true)) {
            image.close()
            return
        }
        val selectedAtMillis = SystemClock.elapsedRealtime()
        cameraExecutor.execute {
            encodeImage(image, requestGeneration, frameId, selectedAtMillis, listener)
        }
    }

    /** 将 YUV 或硬件 JPEG 帧写入临时文件。 */
    private fun encodeImage(
        image: Image,
        requestGeneration: Long,
        frameId: Long,
        selectedAtMillis: Long,
        listener: CameraFrameListener
    ) {
        val encodeStartedAtMillis = SystemClock.elapsedRealtime()
        var outputFile: File? = null
        try {
            outputFile = createFrameFile()
            val medianLuma = Yuv420JpegEncoder.medianLuma(image)
            val rotationDegrees = if (image.format == ImageFormat.YUV_420_888) {
                Yuv420JpegEncoder.writeJpeg(image, outputFile, frameId)
                YUV_UPLOAD_ROTATION_DEGREES
            } else {
                writeHardwareJpeg(image, outputFile)
                JPEG_UPLOAD_ROTATION_DEGREES
            }
            val encodedAtMillis = SystemClock.elapsedRealtime()
            Log.d(
                "RealtimeTiming",
                "frame_encoded frame_id=$frameId queue_ms=${encodeStartedAtMillis - selectedAtMillis} " +
                    "encode_ms=${encodedAtMillis - encodeStartedAtMillis} bytes=${outputFile.length()}"
            )
            if (isCurrentGeneration(requestGeneration)) {
                listener.onFrameCaptured(
                    CapturedFrame(
                        outputFile,
                        rotationDegrees,
                        frameId,
                        selectedAtMillis,
                        encodedAtMillis,
                        medianLuma
                    )
                )
            } else {
                outputFile.delete()
            }
        } catch (error: Throwable) {
            outputFile?.delete()
            notifyError(listener, error)
        } finally {
            image.close()
            isEncoding.set(false)
        }
    }

    /** 把硬件 JPEG 平面的原始字节写入文件。 */
    private fun writeHardwareJpeg(image: Image, outputFile: File) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        FileOutputStream(outputFile).use { stream -> stream.write(bytes) }
    }

    /** 在应用专属图片目录创建帧临时文件。 */
    private fun createFrameFile(): File {
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: appContext.filesDir
        directory.mkdirs()
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis()) + ".jpg"
        return File(directory, name)
    }

    /** 关闭指定相机，并清理当前引用。 */
    private fun closeOpenedCamera(camera: CameraDevice, requestGeneration: Long) {
        synchronized(lock) {
            camera.close()
            if (requestGeneration == generation) {
                if (cameraDevice === camera) cameraDevice = null
                opening = false
            }
        }
    }

    /** 判断回调是否仍属于当前活动相机请求。 */
    private fun isCurrentGeneration(requestGeneration: Long): Boolean = synchronized(lock) {
        !released && requestGeneration == generation
    }

    /** 选择优先 YUV、后备 JPEG 的最合适输出格式和尺寸。 */
    private fun selectCaptureOutput(
        yuvSizes: Array<Size>?,
        jpegSizes: Array<Size>?,
        previewSizes: Array<Size>?,
        targetWidth: Int,
        targetHeight: Int
    ): CaptureOutput {
        if (!yuvSizes.isNullOrEmpty()) {
            return CaptureOutput(
                ImageFormat.YUV_420_888,
                chooseOptimalSize(yuvSizes, targetWidth, targetHeight)
            )
        }
        val fallbackSizes = when {
            !jpegSizes.isNullOrEmpty() -> jpegSizes
            !previewSizes.isNullOrEmpty() -> previewSizes
            else -> arrayOf(Size(targetWidth, targetHeight))
        }
        Log.w(TAG, "YUV_420_888 unavailable; using hardware JPEG byte-stream fallback")
        return CaptureOutput(
            ImageFormat.JPEG,
            chooseOptimalSize(fallbackSizes, targetWidth, targetHeight)
        )
    }

    /** 选择不小于目标的最小尺寸，否则选择小于目标的最大尺寸。 */
    private fun chooseOptimalSize(choices: Array<Size>, targetWidth: Int, targetHeight: Int): Size {
        val bigEnough = choices.filter { it.width >= targetWidth && it.height >= targetHeight }
        return (bigEnough.minByOrNull(::area) ?: choices.maxByOrNull(::area))
            ?: Size(targetWidth, targetHeight)
    }

    /** 计算尺寸面积用于大小比较。 */
    private fun area(size: Size): Long = size.width.toLong() * size.height

    /** 在后台线程发送错误，避免阻塞调用方线程。 */
    private fun notifyError(listener: CameraFrameListener, error: Throwable) {
        if (!cameraExecutor.isShutdown) {
            runCatching { cameraExecutor.execute { listener.onError(error) } }
                .onFailure { Log.w(TAG, "Drop camera error after executor shutdown", it) }
        }
    }

    /** 相机输出格式与尺寸。 */
    private data class CaptureOutput(val format: Int, val size: Size)

    private companion object {
        private const val TAG = "Camera2Capability"
        private const val MAX_IMAGES = 4
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val CAMERA_OPEN_SETTLE_MILLIS = 100L
        private const val YUV_UPLOAD_ROTATION_DEGREES = 90
        private const val JPEG_UPLOAD_ROTATION_DEGREES = 0
    }
}
