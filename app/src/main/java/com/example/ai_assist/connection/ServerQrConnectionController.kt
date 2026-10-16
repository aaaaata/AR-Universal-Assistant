package com.example.ai_assist.connection

import com.example.ai_assist.scene.SceneController
import com.example.ai_assist.sdk.capability.CameraCapability
import com.example.ai_assist.sdk.capability.CameraConfig
import com.example.ai_assist.sdk.capability.CameraFrameListener
import com.example.ai_assist.sdk.capability.CameraFrameSelector
import com.example.ai_assist.sdk.capability.CapturedFrame
import com.example.ai_assist.sdk.network.ServerConnectionQrParser
import com.example.ai_assist.sdk.network.ServerReadinessValidator
import com.example.ai_assist.sdk.engine.SessionState
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 扫码连接过程向页面报告的状态。 */
internal sealed interface ServerQrScanEvent {
    data class Searching(val attemptedFrames: Int) : ServerQrScanEvent
    data object InvalidQrCode : ServerQrScanEvent
    data object IncompatibleScene : ServerQrScanEvent
    data class Validating(val authority: String) : ServerQrScanEvent
    data class ServerUnavailable(val authority: String) : ServerQrScanEvent
    data class Connected(val sceneId: String, val authority: String) : ServerQrScanEvent
    data class Failed(val message: String) : ServerQrScanEvent
}

/** 使用通用相机能力扫描、校验并应用服务配置。 */
internal class ServerQrConnectionController(
    private val camera: CameraCapability?,
    private val sceneController: SceneController,
    private val readinessValidator: ServerReadinessValidator = ServerReadinessValidator()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val lastSelectedAtMillis = AtomicLong(NO_SELECTION)
    private val scanGeneration = AtomicLong(0L)
    private var attemptedFrames = 0
    @Volatile
    private var activeSceneId: String? = null
    @Volatile
    private var eventListener: ((ServerQrScanEvent) -> Unit)? = null

    val isScanning: Boolean
        get() = active.get()

    fun start(sceneId: String, listener: (ServerQrScanEvent) -> Unit): Boolean {
        val cameraCapability = camera ?: return false
        if (sceneController.state.value != SessionState.READY) return false
        if (!sceneController.supportsServerConfiguration(sceneId)) return false
        if (!active.compareAndSet(false, true)) return false
        attemptedFrames = 0
        processing.set(false)
        lastSelectedAtMillis.set(NO_SELECTION)
        activeSceneId = sceneId
        eventListener = listener
        val generation = scanGeneration.incrementAndGet()
        cameraCapability.start(
            CameraConfig(),
            CameraFrameSelector { timestampMillis -> shouldCapture(generation, timestampMillis) },
            frameListener(generation)
        )
        return true
    }

    fun cancel() {
        scanGeneration.incrementAndGet()
        active.set(false)
        processing.set(false)
        activeSceneId = null
        eventListener = null
        camera?.stop()
    }

    fun close() {
        cancel()
        scope.cancel()
    }

    private fun shouldCapture(generation: Long, timestampMillis: Long): Boolean {
        if (generation != scanGeneration.get() || !active.get() || processing.get()) return false
        while (true) {
            val last = lastSelectedAtMillis.get()
            if (last != NO_SELECTION && timestampMillis - last < QR_SCAN_INTERVAL_MILLIS) {
                return false
            }
            if (lastSelectedAtMillis.compareAndSet(last, timestampMillis)) return true
        }
    }

    private fun frameListener(generation: Long) = object : CameraFrameListener {
        override fun onFrameCaptured(frame: CapturedFrame) {
            if (generation != scanGeneration.get() ||
                !active.get() ||
                !processing.compareAndSet(false, true)
            ) {
                frame.file.delete()
                return
            }
            scope.launch { processFrame(frame, generation) }
        }

        override fun onError(error: Throwable) {
            finish(generation, ServerQrScanEvent.Failed(error.message ?: "相机启动失败"))
        }
    }

    private suspend fun processFrame(frame: CapturedFrame, generation: Long) {
        try {
            if (!isActiveGeneration(generation)) return
            attemptedFrames++
            val rawValue = QrCodeDecoder.decode(frame.file)
            if (!isActiveGeneration(generation)) return
            if (rawValue == null) {
                eventListener?.invoke(ServerQrScanEvent.Searching(attemptedFrames))
                return
            }
            val scanned = runCatching { ServerConnectionQrParser.parse(rawValue) }
                .getOrElse {
                    eventListener?.invoke(ServerQrScanEvent.InvalidQrCode)
                    return
                }
            val requestedSceneId = activeSceneId ?: return
            if (!sceneController.supportsServerConfiguration(requestedSceneId, scanned.sceneId)) {
                eventListener?.invoke(ServerQrScanEvent.IncompatibleScene)
                return
            }

            val authority = URI(scanned.baseUrl).authority ?: scanned.baseUrl
            eventListener?.invoke(ServerQrScanEvent.Validating(authority))
            if (!readinessValidator.isReady(scanned.baseUrl)) {
                if (isActiveGeneration(generation)) {
                    eventListener?.invoke(ServerQrScanEvent.ServerUnavailable(authority))
                }
                return
            }
            if (!isActiveGeneration(generation)) return
            val configuredSceneId = sceneController.updateServerEndpoint(
                requestedSceneId,
                scanned
            ) ?: return
            finish(generation, ServerQrScanEvent.Connected(configuredSceneId, authority))
        } catch (error: Throwable) {
            finish(
                generation,
                ServerQrScanEvent.Failed(error.message ?: error::class.java.simpleName)
            )
        } finally {
            frame.file.delete()
            if (generation == scanGeneration.get()) processing.set(false)
        }
    }

    private fun finish(generation: Long, event: ServerQrScanEvent) {
        if (generation != scanGeneration.get()) return
        if (!active.compareAndSet(true, false)) return
        val listener = eventListener
        activeSceneId = null
        eventListener = null
        camera?.stop()
        listener?.invoke(event)
    }

    private fun isActiveGeneration(generation: Long): Boolean =
        active.get() && generation == scanGeneration.get()

    private companion object {
        const val QR_SCAN_INTERVAL_MILLIS = 700L
        const val NO_SELECTION = Long.MIN_VALUE
    }
}
