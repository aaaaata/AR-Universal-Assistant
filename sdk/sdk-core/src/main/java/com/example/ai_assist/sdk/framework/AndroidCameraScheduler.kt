package com.example.ai_assist.sdk.framework

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.view.Surface
import android.view.WindowManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine

data class AndroidCameraConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val jpegQuality: Byte = 90
) : CapabilityConfig

/** Camera2 完整图片采集；Flow 的背压同时承担单帧 in-flight 门禁。 */
class AndroidCameraScheduler(context: Context) : CameraScheduler, AutoCloseable {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val imageThread = HandlerThread("cv-camera-images").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    private val lock = Any()
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    override fun frames(intervalMillis: Long, cameraConfig: CapabilityConfig): Flow<Frame> = flow {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        val config = cameraConfig as? AndroidCameraConfig ?: AndroidCameraConfig()
        val cameraId = cameraManager.cameraIdList.firstOrNull()
            ?: error("No camera is available")
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val jpegOrientation = jpegRotationDegrees(characteristics)
        open(cameraId, config)
        try {
            while (currentCoroutineContext().isActive) {
                val startedAt = System.currentTimeMillis()
                emit(capture(config, jpegOrientation))
                val elapsed = System.currentTimeMillis() - startedAt
                delay((intervalMillis - elapsed).coerceAtLeast(0L))
            }
        } finally {
            closeCamera()
        }
    }

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

    private suspend fun open(cameraId: String, config: AndroidCameraConfig) {
        check(appContext.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) { "Camera permission is not granted" }
        val camera = suspendCancellableCoroutine<CameraDevice> { continuation ->
            cameraManager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(value: CameraDevice) {
                    if (continuation.isActive) continuation.resume(value) else value.close()
                }

                override fun onDisconnected(value: CameraDevice) {
                    value.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Camera disconnected"))
                    }
                }

                override fun onError(value: CameraDevice, error: Int) {
                    value.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Camera error: $error"))
                    }
                }
            })
        }
        val outputSize = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.minByOrNull { abs(it.width - config.width) + abs(it.height - config.height) }
            ?: error("Camera does not support JPEG output")
        val reader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 2)
        val session = suspendCancellableCoroutine<CameraCaptureSession> { continuation ->
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
        synchronized(lock) {
            cameraDevice = camera
            imageReader = reader
            captureSession = session
        }
    }

    private suspend fun capture(config: AndroidCameraConfig, jpegOrientation: Int): Frame =
        suspendCancellableCoroutine { continuation ->
            val camera = synchronized(lock) { cameraDevice } ?: run {
                continuation.resumeWithException(IllegalStateException("Camera is closed"))
                return@suspendCancellableCoroutine
            }
            val session = synchronized(lock) { captureSession } ?: run {
                continuation.resumeWithException(IllegalStateException("Camera session is closed"))
                return@suspendCancellableCoroutine
            }
            val reader = synchronized(lock) { imageReader } ?: run {
                continuation.resumeWithException(IllegalStateException("Image reader is closed"))
                return@suspendCancellableCoroutine
            }
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                    val file = File.createTempFile("cv-frame-", ".jpg", appContext.cacheDir)
                    FileOutputStream(file).use { it.write(bytes) }
                    if (continuation.isActive) {
                        continuation.resume(
                            Frame(file, JPEG_FRAME_ROTATION_DEGREES, System.currentTimeMillis())
                        )
                    } else {
                        file.delete()
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    image.close()
                    reader.setOnImageAvailableListener(null, null)
                }
            }, imageHandler)
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_QUALITY, config.jpegQuality)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            session.capture(request, null, null)
            continuation.invokeOnCancellation { reader.setOnImageAvailableListener(null, null) }
        }

    override fun close() {
        closeCamera()
    }

    private fun closeCamera() {
        synchronized(lock) {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        }
    }

    private companion object {
        // JPEG_ORIENTATION is embedded by Camera2, so the uploaded encoded frame needs no rotation.
        const val JPEG_FRAME_ROTATION_DEGREES = 0
    }
}
