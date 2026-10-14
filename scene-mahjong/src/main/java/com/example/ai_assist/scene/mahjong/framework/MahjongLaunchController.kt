package com.example.ai_assist.scene.mahjong.framework

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.example.ai_assist.scene.mahjong.BuildConfig
import com.example.ai_assist.scene.mahjong.R
import com.example.ai_assist.sdk.framework.InputAction
import com.example.ai_assist.sdk.framework.SceneConfig
import com.example.ai_assist.sdk.framework.SceneLaunchController
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 麻将私有启动流程：服务选择、扫码/保存配置、服务校验和开局模式选择。 */
internal class MahjongLaunchController : SceneLaunchController {
    private enum class Page { SERVICE, LOCAL_CONFIG, QR_SCAN, CLOUD, GAME_MODE }

    private data class MountedView(
        val container: ViewGroup,
        val root: View
    ) {
        val barcodeScanner: DecoratedBarcodeView
            get() = root.findViewById(R.id.mahjong_barcode_scanner)
    }

    private lateinit var configStore: MahjongConnectionConfigStore
    private val mountedViews = mutableListOf<MountedView>()
    private val readinessProbe = MahjongServiceReadinessProbe()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var readinessJob: Job? = null
    private var onStart: ((SceneConfig) -> Unit)? = null
    private var onExit: (() -> Unit)? = null
    private var page = Page.SERVICE
    private var focusIndex = 0
    private var selectedConnection: MahjongConnectionConfig? = null
    private var pendingConnection: MahjongConnectionConfig? = null
    private var selectedServiceMode = MahjongServiceMode.LOCAL
    private var pendingSave = false
    private var warnOnConnectionFailure = false
    private var scanHandled = false
    private var warningMessage: String? = null

    override fun attach(
        activity: Activity,
        container: ViewGroup,
        onStart: (SceneConfig) -> Unit,
        onExit: () -> Unit
    ) {
        this.onStart = onStart
        this.onExit = onExit
        if (!::configStore.isInitialized) {
            configStore = MahjongConnectionConfigStore(activity)
        }
        val root = LayoutInflater.from(activity)
            .inflate(R.layout.view_mahjong_launch, container, false)
        container.removeAllViews()
        container.addView(root)
        val mountedView = MountedView(container, root)
        mountedViews += mountedView
        mountedView.barcodeScanner.barcodeView.decoderFactory =
            DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        mountedView.barcodeScanner.setStatusText("")
        bindClicks(root)
        renderPage(root, page)
        renderFocus()
    }

    override fun onInput(action: InputAction) {
        if (warningMessage != null) {
            if (action == InputAction.CLICK || action == InputAction.DOUBLE_CLICK) {
                dismissWarning()
            }
            return
        }
        when (action) {
            InputAction.SLIDE_FORWARD -> moveFocus(
                if (page == Page.LOCAL_CONFIG) -1 else 1
            )
            InputAction.SLIDE_BACKWARD -> moveFocus(
                if (page == Page.LOCAL_CONFIG) 1 else -1
            )
            InputAction.SLIDE_DOWN -> if (page != Page.LOCAL_CONFIG) moveFocus(1)
            InputAction.SLIDE_UP -> if (page != Page.LOCAL_CONFIG) moveFocus(-1)
            InputAction.CLICK -> activateFocused()
            InputAction.DOUBLE_CLICK -> navigateBack()
            InputAction.TRIPLE_CLICK, InputAction.LONG_PRESS -> Unit
        }
    }

    override fun close() {
        readinessJob?.cancel()
        readinessJob = null
        mountedViews.forEach { it.barcodeScanner.pauseAndWait() }
        readinessProbe.close()
        scope.cancel()
        onStart = null
        onExit = null
        mountedViews.forEach { it.container.removeAllViews() }
        mountedViews.clear()
    }

    private fun bindClicks(root: View) {
        root.findViewById<Button>(R.id.mahjong_service_local).setOnClickListener {
            selectedServiceMode = MahjongServiceMode.LOCAL
            showPage(Page.LOCAL_CONFIG)
        }
        root.findViewById<Button>(R.id.mahjong_service_cloud).setOnClickListener {
            connectCloud()
        }
        root.findViewById<Button>(R.id.mahjong_local_scan).setOnClickListener { startScanning() }
        root.findViewById<Button>(R.id.mahjong_local_saved).setOnClickListener { useSavedConfig() }
        root.findViewById<Button>(R.id.mahjong_local_clear).setOnClickListener { clearConfig() }
        root.findViewById<Button>(R.id.mahjong_cloud_retry).setOnClickListener { retryConnection() }
        root.findViewById<Button>(R.id.mahjong_mode_normal).setOnClickListener {
            startScene(MahjongRecordingMode.NORMAL)
        }
        root.findViewById<Button>(R.id.mahjong_mode_record).setOnClickListener {
            startScene(MahjongRecordingMode.RECORD)
        }
        root.findViewById<Button>(R.id.mahjong_warning_ok).setOnClickListener {
            dismissWarning()
        }
    }

    private fun showPage(target: Page) {
        page = target
        focusIndex = 0
        mountedViews.forEach { renderPage(it.root, target) }
        if (target != Page.QR_SCAN) {
            mountedViews.forEach { it.barcodeScanner.pause() }
        }
        renderFocus()
    }

    private fun renderPage(root: View, target: Page) {
        root.findViewById<View>(R.id.mahjong_service_page).visibility =
            if (target == Page.SERVICE) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.mahjong_local_page).visibility =
            if (target == Page.LOCAL_CONFIG) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.mahjong_qr_page).visibility =
            if (target == Page.QR_SCAN) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.mahjong_cloud_page).visibility =
            if (target == Page.CLOUD) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.mahjong_mode_page).visibility =
            if (target == Page.GAME_MODE) View.VISIBLE else View.GONE
        renderWarning(root)
    }

    private fun focusableButtons(root: View): List<Button> = if (warningMessage != null) {
        listOf(root.findViewById(R.id.mahjong_warning_ok))
    } else when (page) {
        Page.SERVICE -> listOf(
            root.findViewById(R.id.mahjong_service_local),
            root.findViewById(R.id.mahjong_service_cloud)
        )
        Page.LOCAL_CONFIG -> listOf(
            root.findViewById(R.id.mahjong_local_scan),
            root.findViewById(R.id.mahjong_local_saved),
            root.findViewById(R.id.mahjong_local_clear)
        )
        Page.CLOUD -> listOf(root.findViewById(R.id.mahjong_cloud_retry))
        Page.GAME_MODE -> listOf(
            root.findViewById(R.id.mahjong_mode_normal),
            root.findViewById(R.id.mahjong_mode_record)
        )
        Page.QR_SCAN -> emptyList()
    }

    private fun moveFocus(offset: Int) {
        val buttons = mountedViews.firstOrNull()?.let { focusableButtons(it.root) }.orEmpty()
        if (buttons.isEmpty()) return
        focusIndex = (focusIndex + offset + buttons.size) % buttons.size
        renderFocus()
    }

    private fun renderFocus() {
        mountedViews.forEach { mountedView ->
            focusableButtons(mountedView.root).forEachIndexed { index, button ->
                button.isActivated = index == focusIndex
                if (index == focusIndex) button.requestFocus()
            }
        }
    }

    private fun activateFocused() {
        if (page == Page.QR_SCAN) return
        mountedViews.firstOrNull()?.let { mountedView ->
            focusableButtons(mountedView.root).getOrNull(focusIndex)?.performClick()
        }
    }

    private fun navigateBack() {
        readinessJob?.cancel()
        when (page) {
            Page.SERVICE -> onExit?.invoke()
            Page.LOCAL_CONFIG -> showPage(Page.SERVICE)
            Page.CLOUD -> showPage(
                if (selectedServiceMode == MahjongServiceMode.LOCAL) {
                    Page.LOCAL_CONFIG
                } else {
                    Page.SERVICE
                }
            )
            Page.QR_SCAN -> showPage(Page.LOCAL_CONFIG)
            Page.GAME_MODE -> showPage(Page.SERVICE)
        }
    }

    private fun startScanning() {
        scanHandled = false
        showPage(Page.QR_SCAN)
        mountedViews.forEach {
            it.barcodeScanner.decodeContinuous(scanCallback)
            it.barcodeScanner.resume()
        }
    }

    private fun useSavedConfig() {
        val saved = configStore.load()
        if (saved == null) {
            showWarning("本地无保存配置")
            return
        }
        validateLocal(saved, saveAfterSuccess = false)
    }

    private fun clearConfig() {
        configStore.clear()
        showWarning("已清除保存配置")
    }

    private val scanCallback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            val value = result?.text ?: return
            if (scanHandled) return
            scanHandled = true
            mountedViews.forEach { it.barcodeScanner.pause() }
            val connection = configStore.parseQrConnection(value)
            if (connection == null) {
                showWarning("二维码配置格式无效")
                scanHandled = false
                mountedViews.forEach { it.barcodeScanner.resume() }
                return
            }
            validateLocal(connection, saveAfterSuccess = true)
        }

        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) = Unit
    }

    private fun validateLocal(connection: MahjongConnectionConfig, saveAfterSuccess: Boolean) {
        selectedServiceMode = MahjongServiceMode.LOCAL
        pendingSave = saveAfterSuccess
        warnOnConnectionFailure = !saveAfterSuccess
        showConnectionStatus("正在校验本地服务...", connection.serverUrl)
        validateConnection(connection)
    }

    private fun connectCloud() {
        selectedServiceMode = MahjongServiceMode.CLOUD
        pendingSave = false
        warnOnConnectionFailure = false
        val connection = MahjongConnectionConfig(
            serverUrl = BuildConfig.MAHJONG_CLOUD_SERVER_BASE_URL,
            fps = 1,
            label = "云端服务"
        )
        showConnectionStatus("正在连接云端服务...", connection.serverUrl)
        validateConnection(connection)
    }

    private fun showConnectionStatus(status: String, endpoint: String) {
        showPage(Page.CLOUD)
        mountedViews.forEach {
            it.root.findViewById<TextView>(R.id.mahjong_cloud_status).text = status
            it.root.findViewById<TextView>(R.id.mahjong_cloud_endpoint).text = endpoint
        }
    }

    private fun validateConnection(connection: MahjongConnectionConfig) {
        readinessJob?.cancel()
        pendingConnection = connection
        readinessJob = scope.launch {
            if (readinessProbe.isReady(connection.serverUrl)) {
                if (pendingSave) configStore.save(connection)
                selectedConnection = connection
                showPage(Page.GAME_MODE)
            } else {
                mountedViews.forEach {
                    it.root.findViewById<TextView>(R.id.mahjong_cloud_status).text =
                        "服务不可用，请检查服务和网络后重试"
                }
                if (warnOnConnectionFailure) {
                    showWarning("保存配置连接失败，请检查服务和网络后重试")
                }
            }
        }
    }

    private fun retryConnection() {
        val connection = pendingConnection ?: return
        showConnectionStatus(
            status = if (selectedServiceMode == MahjongServiceMode.LOCAL) {
                "正在校验本地服务..."
            } else {
                "正在连接云端服务..."
            },
            endpoint = connection.serverUrl
        )
        validateConnection(connection)
    }

    private fun startScene(recordingMode: MahjongRecordingMode) {
        val connection = selectedConnection ?: return
        mountedViews.forEach { it.barcodeScanner.pause() }
        onStart?.invoke(
            MahjongSceneConfig(
                serverBaseUrl = connection.serverUrl,
                serviceMode = selectedServiceMode,
                recordingMode = recordingMode,
                captureIntervalMillis = 1_000L / connection.fps.coerceIn(1, 5)
            )
        )
    }

    private fun showWarning(message: String) {
        warningMessage = message
        focusIndex = 0
        mountedViews.forEach(::renderWarning)
        renderFocus()
    }

    private fun dismissWarning() {
        warningMessage = null
        focusIndex = 0
        mountedViews.forEach(::renderWarning)
        renderFocus()
    }

    private fun renderWarning(mountedView: MountedView) = renderWarning(mountedView.root)

    private fun renderWarning(root: View) {
        val message = warningMessage
        root.findViewById<View>(R.id.mahjong_warning_overlay).visibility =
            if (message == null) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.mahjong_warning_message).text = message.orEmpty()
    }
}
