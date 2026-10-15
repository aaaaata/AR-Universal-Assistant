package com.example.ai_assist.sdk.framework

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

data class AndroidCameraConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val jpegQuality: Byte = 90
) : CapabilityConfig

/** Camera2 持续帧流；只把 Core 门禁批准的原始帧编码成业务 Frame。 */
class AndroidCameraScheduler(context: Context) : CameraScheduler, AutoCloseable {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val imageThread = HandlerThread("cv-camera-images").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    private val lock = Any()
    private val isEncoding = AtomicBoolean(false)
    private var generation = 0L
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    override fun frames(
        streamConfig: FrameStreamConfig,
        frameSelector: (frameAtElapsedMillis: Long) -> Boolean
    ): Flow<Frame> = callbackFlow {
        val config = streamConfig.cameraConfig as? AndroidCameraConfig ?: AndroidCameraConfig()
        val cameraId = cameraManager.cameraIdList.firstOrNull()
            ?: error("No camera is available")
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val requestGeneration = synchronized(lock) {
            closeCameraLocked()
            generation += 1
            generation
        }
        openContinuousStream(
            cameraId = cameraId,
            characteristics = characteristics,
            config = config,
            sourceFramesPerSecond = streamConfig.sourceFramesPerSecond,
            requestGeneration = requestGeneration,
            frameSelector = frameSelector,
            onFrame = { frame ->
                if (trySend(frame).isFailure) frame.file.delete()
            },
            onError = { error -> close(error) }
        )
        awaitClose { closeCamera() }
    }

    private suspend fun openContinuousStream(
        cameraId: String,
        characteristics: CameraCharacteristics,
        config: AndroidCameraConfig,
        sourceFramesPerSecond: Int,
        requestGeneration: Long,
        frameSelector: (Long) -> Boolean,
        onFrame: (Frame) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        check(appContext.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) { "Camera permission is not granted" }
        val camera = openCamera(cameraId, onError)
        val output = selectOutput(characteristics, config)
        val jpegOrientation = jpegRotationDegrees(characteristics)
        val reader = ImageReader.newInstance(
            output.size.width,
            output.size.height,
            output.format,
            MAX_IMAGES
        )
        reader.setOnImageAvailableListener({ source ->
            handleAvailableImage(
                source = source,
                outputFormat = output.format,
                jpegQuality = config.jpegQuality,
                jpegOrientation = jpegOrientation,
                requestGeneration = requestGeneration,
                frameSelector = frameSelector,
                onFrame = onFrame,
                onError = onError
            )
        }, imageHandler)
        val session = createSession(camera, reader)
        val fpsRange = selectFpsRange(characteristics, sourceFramesPerSecond)
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (output.format == ImageFormat.JPEG) {
                set(CaptureRequest.JPEG_QUALITY, config.jpegQuality)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }
        }.build()
        synchronized(lock) {
            if (generation != requestGeneration) {
                session.close()
                reader.close()
                camera.close()
                return
            }
            cameraDevice = camera
            imageReader = reader
            captureSession = session
        }
        session.setRepeatingRequest(request, null, imageHandler)
    }

    private suspend fun openCamera(
        cameraId: String,
        onRuntimeError: (Throwable) -> Unit
    ): CameraDevice =
        suspendCancellableCoroutine { continuation ->
            cameraManager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(value: CameraDevice) {
                    if (continuation.isActive) continuation.resume(value) else value.close()
                }

                override fun onDisconnected(value: CameraDevice) {
                    value.close()
                    val error = IllegalStateException("Camera disconnected")
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    } else {
                        onRuntimeError(error)
                    }
                }

                override fun onError(value: CameraDevice, error: Int) {
                    value.close()
                    val cause = IllegalStateException("Camera error: $error")
                    if (continuation.isActive) {
                        continuation.resumeWithException(cause)
                    } else {
                        onRuntimeError(cause)
                    }
                }
            })
        }

    private suspend fun createSession(
        camera: CameraDevice,
        reader: ImageReader
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(reader.surface)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(value) else value.close()
                }

                override fun onConfigureFailed(value: CameraCaptureSession) {
                    value.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Camera session configuration failed")
                        )
                    }
                }
            }
        )
        camera.createCaptureSession(sessionConfig)
    }

    private fun handleAvailableImage(
        source: ImageReader,
        outputFormat: Int,
        jpegQuality: Byte,
        jpegOrientation: Int,
        requestGeneration: Long,
        frameSelector: (Long) -> Boolean,
        onFrame: (Frame) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        val selectedAtElapsedMillis = elapsedRealtimeMillis()
        if (isEncoding.get() || !frameSelector(selectedAtElapsedMillis)) {
            image.close()
            return
        }
        if (!isEncoding.compareAndSet(false, true)) {
            image.close()
            return
        }
        executor.execute {
            var outputFile: File? = null
            try {
                outputFile = File.createTempFile("cv-frame-", ".jpg", appContext.cacheDir)
                val medianLuma: Int?
                val imageRotationDegrees = if (outputFormat == ImageFormat.YUV_420_888) {
                    medianLuma = Yuv420JpegEncoder.medianLuma(image)
                    Yuv420JpegEncoder.writeJpeg(image, outputFile, jpegQuality)
                    jpegOrientation
                } else {
                    val jpeg = writeHardwareJpeg(image, outputFile)
                    medianLuma = estimateMedianLuma(jpeg)
                    JPEG_FRAME_ROTATION_DEGREES
                }
                if (synchronized(lock) { generation == requestGeneration }) {
                    onFrame(
                        Frame(
                            file = outputFile,
                            imageRotationDegrees = imageRotationDegrees,
                            capturedAtMillis = System.currentTimeMillis(),
                            selectedAtElapsedMillis = selectedAtElapsedMillis,
                            medianLuma = medianLuma
                        )
                    )
                } else {
                    outputFile.delete()
                }
            } catch (error: Throwable) {
                outputFile?.delete()
                onError(error)
            } finally {
                image.close()
                isEncoding.set(false)
            }
        }
    }

    private fun selectOutput(
        characteristics: CameraCharacteristics,
        config: AndroidCameraConfig
    ): CameraOutput {
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera stream configuration is unavailable")
        val yuvSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888)
        if (!yuvSizes.isNullOrEmpty()) {
            return CameraOutput(
                ImageFormat.YUV_420_888,
                chooseOptimalSize(yuvSizes, config.width, config.height)
            )
        }
        val jpegSizes = streamMap.getOutputSizes(ImageFormat.JPEG)
        require(!jpegSizes.isNullOrEmpty()) { "Camera does not support YUV or JPEG output" }
        return CameraOutput(
            ImageFormat.JPEG,
            chooseOptimalSize(jpegSizes, config.width, config.height)
        )
    }

    private fun selectFpsRange(
        characteristics: CameraCharacteristics,
        requestedFps: Int
    ): Range<Int>? {
        val ranges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ).orEmpty()
        return ranges.filter { requestedFps in it.lower..it.upper }
            .minByOrNull { it.upper - it.lower }
            ?: ranges.minByOrNull {
                minOf(abs(it.lower - requestedFps), abs(it.upper - requestedFps))
            }
    }

    private fun chooseOptimalSize(
        choices: Array<out Size>,
        targetWidth: Int,
        targetHeight: Int
    ): Size = choices.minByOrNull {
        abs(it.width - targetWidth) + abs(it.height - targetHeight)
    } ?: Size(targetWidth, targetHeight)

    private fun jpegRotationDegrees(characteristics: CameraCharacteristics): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val displayRotation = appContext.getSystemService(WindowManager::class.java)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    private fun writeHardwareJpeg(image: Image, outputFile: File): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        FileOutputStream(outputFile).use { it.write(bytes) }
        return bytes
    }

    private fun estimateMedianLuma(jpeg: ByteArray): Int? {
        val options = BitmapFactory.Options().apply { inSampleSize = LUMA_DECODE_SAMPLE_SIZE }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
        return try {
            val samples = ArrayList<Int>()
            for (y in 0 until bitmap.height step LUMA_PIXEL_STEP) {
                for (x in 0 until bitmap.width step LUMA_PIXEL_STEP) {
                    val pixel = bitmap.getPixel(x, y)
                    samples += (
                        77 * Color.red(pixel) +
                            150 * Color.green(pixel) +
                            29 * Color.blue(pixel)
                        ) shr 8
                }
            }
            samples.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        closeCamera()
        executor.shutdown()
        imageThread.quitSafely()
    }

    private fun closeCamera() {
        synchronized(lock) {
            generation += 1
            closeCameraLocked()
        }
    }

    private fun closeCameraLocked() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private data class CameraOutput(val format: Int, val size: Size)

    private companion object {
        const val MAX_IMAGES = 4
        const val JPEG_FRAME_ROTATION_DEGREES = 0
        const val LUMA_DECODE_SAMPLE_SIZE = 16
        const val LUMA_PIXEL_STEP = 2

        fun elapsedRealtimeMillis(): Long =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
    }
}
