package com.example.ai_assist.framework

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import com.example.ai_assist.sdk.framework.ImageCaptureConfig
import com.example.ai_assist.sdk.framework.CameraScheduler
import com.example.ai_assist.sdk.framework.CapabilityConfig
import com.example.ai_assist.sdk.framework.Frame
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine

/** Android Camera2 到新框架 Flow<Frame> 的适配器。 */
class Camera2FrameScheduler(context: Context) : CameraScheduler {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("framework-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var activeResources: CameraResources? = null
    private var closed = false

    override fun frames(intervalMillis: Long, cameraConfig: CapabilityConfig): Flow<Frame> = flow {
        require(intervalMillis > 0) { "Frame interval must be positive" }
        val config = cameraConfig as? ImageCaptureConfig
            ?: throw IllegalArgumentException("ImageCaptureConfig is required")
        val resources = open(config)
        synchronized(lock) {
            check(!closed) { "Camera scheduler is closed" }
            check(activeResources == null) { "Camera scheduler is already active" }
            activeResources = resources
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val cycleStartedAt = System.currentTimeMillis()
                emit(resources.captureFrame())
                val elapsed = System.currentTimeMillis() - cycleStartedAt
                delay((intervalMillis - elapsed).coerceAtLeast(0L))
            }
        } finally {
            synchronized(lock) {
                if (activeResources === resources) activeResources = null
            }
            resources.close()
        }
    }

    fun stop() {
        synchronized(lock) { activeResources }?.close()
    }

    fun close() {
        val resources = synchronized(lock) {
            if (closed) return
            closed = true
            activeResources.also { activeResources = null }
        }
        resources?.close()
        cameraExecutor.shutdown()
        cameraThread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(config: ImageCaptureConfig): CameraResources {
        check(appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Camera permission is not granted"
        }
        synchronized(lock) { check(!closed) { "Camera scheduler is closed" } }
        val cameraId = cameraManager.cameraIdList.firstOrNull()
            ?: error("No camera is available")
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            .orEmpty()
        val size = chooseSize(sizes, config.preferredWidth, config.preferredHeight)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, MAX_IMAGES)
        val camera = try {
            openCamera(cameraId)
        } catch (error: Throwable) {
            reader.close()
            throw error
        }
        val session = try {
            createSession(camera, reader)
        } catch (error: Throwable) {
            camera.close()
            reader.close()
            throw error
        }
        return CameraResources(camera, session, reader)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { continuation ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (continuation.isActive) continuation.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    continuation.resumeExceptionIfActive(IllegalStateException("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    continuation.resumeExceptionIfActive(
                        IllegalStateException("Camera open failed with code $error")
                    )
                }
            }, cameraHandler)
        }

    private suspend fun createSession(
        camera: CameraDevice,
        reader: ImageReader
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(reader.surface)),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    continuation.resumeExceptionIfActive(
                        IllegalStateException("Camera capture session configuration failed")
                    )
                }
            }
        )
        camera.createCaptureSession(config)
    }

    private fun chooseSize(sizes: Array<out Size>, width: Int, height: Int): Size {
        if (sizes.isEmpty()) return Size(width, height)
        val largeEnough = sizes.filter { it.width >= width && it.height >= height }
        return (largeEnough.minByOrNull(::area) ?: sizes.maxByOrNull(::area))!!
    }

    private fun area(size: Size): Long = size.width.toLong() * size.height

    private inner class CameraResources(
        private val camera: CameraDevice,
        private val session: CameraCaptureSession,
        private val reader: ImageReader
    ) {
        private var released = false

        suspend fun captureFrame(): Frame = suspendCancellableCoroutine { continuation ->
            synchronized(this) { check(!released) { "Camera resources are released" } }
            reader.setOnImageAvailableListener({ availableReader ->
                val image = runCatching { availableReader.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val file = createFrameFile()
                    FileOutputStream(file).use { it.write(bytes) }
                    if (continuation.isActive) {
                        Log.i(
                            TAG,
                            "event=frame_captured bytes=${file.length()} captured_at=${file.lastModified()}"
                        )
                        continuation.resume(
                            Frame(
                                file = file,
                                imageRotationDegrees = 0,
                                capturedAtMillis = System.currentTimeMillis(),
                                medianLuma = runCatching { estimateMedianLuma(bytes) }.getOrNull()
                            )
                        )
                    } else {
                        file.delete()
                    }
                } catch (error: Throwable) {
                    continuation.resumeExceptionIfActive(error)
                } finally {
                    image.close()
                    reader.setOnImageAvailableListener(null, null)
                }
            }, cameraHandler)
            continuation.invokeOnCancellation {
                reader.setOnImageAvailableListener(null, null)
            }
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, JPEG_ORIENTATION_DEGREES)
            }.build()
            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) = Unit

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    continuation.resumeExceptionIfActive(
                        IllegalStateException("Camera capture failed: ${failure.reason}")
                    )
                }
            }, cameraHandler)
        }

        @Synchronized
        fun close() {
            if (released) return
            released = true
            reader.setOnImageAvailableListener(null, null)
            session.close()
            camera.close()
            reader.close()
        }
    }

    private fun createFrameFile(): File {
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: appContext.filesDir
        directory.mkdirs()
        return File.createTempFile("mahjong-frame-", ".jpg", directory)
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

    private fun <T> CancellableContinuation<T>.resumeExceptionIfActive(error: Throwable) {
        if (isActive) resumeWithException(error)
    }

    private companion object {
        const val TAG = "SceneFrameFlow"
        const val MAX_IMAGES = 2
        const val JPEG_ORIENTATION_DEGREES = 90
        const val LUMA_DECODE_SAMPLE_SIZE = 16
        const val LUMA_PIXEL_STEP = 2
    }
}
