package com.example.ai_assist

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.ai_assist.connection.CloudServiceConnector
import com.example.ai_assist.databinding.ActivityMainBinding
import com.example.ai_assist.connection.HttpServiceReadinessProbe
import com.example.ai_assist.connection.ServiceReadinessProbe
import com.example.ai_assist.model.ConnectionConfig
import com.example.ai_assist.repository.ChatRepository
import com.example.ai_assist.service.GameApiService
import com.example.ai_assist.service.RayNeoDeviceManager
import com.example.ai_assist.storage.ConnectionConfigStore
import com.example.ai_assist.utils.RayNeoAudioRecorder
import com.example.ai_assist.viewmodel.ChatViewModel
import com.example.ai_assist.viewmodel.ChatViewModelFactory
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {

    private enum class UiMode { SERVICE_SELECTION, CONFIG, CLOUD_CONFIG, STREAMING }

    private lateinit var viewModel: ChatViewModel
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var configStore: ConnectionConfigStore

    private val surfaceList = mutableListOf<Surface>()
    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraManager: CameraManager
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var backHandler: Handler
    private lateinit var backHandlerThread: HandlerThread
    private var cameraJob: Job? = null
    private var previewSize: Size? = null
    private var currentPhotoFile: File? = null
    private var captureLoopJob: Job? = null
    private val uploadInFlight = AtomicBoolean(false)
    private var pendingAutoUpload = false
    private var hasStartedCamera = false

    private var audioRecorder: RayNeoAudioRecorder? = null
    private var isRecordingAudio = false
    private var currentMode = UiMode.SERVICE_SELECTION
    private var activeConfig: ConnectionConfig? = null
    private val streamClient = OkHttpClient()
    private val readinessProbe: ServiceReadinessProbe by lazy {
        HttpServiceReadinessProbe(streamClient)
    }
    private val cloudServiceConnector: CloudServiceConnector by lazy {
        CloudServiceConnector(readinessProbe)
    }
    private var suggestionSocket: WebSocket? = null
    private var serviceFocusIndex = 0
    private var configFocusIndex = 0
    private var isPrimaryConnected = false
    private var isSuggestionStreamConnected = false
    private var isCloudConnectionInFlight = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (!allGranted) {
                showCustomToast("需要相机和存储权限")
            }
        }

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val contents = result.data?.getStringExtra(QrScanActivity.EXTRA_QR_CONTENT)
            if (contents.isNullOrBlank()) {
                showCustomToast("未识别到二维码")
                return@registerForActivityResult
            }

            val parsed = configStore.parse(contents)
            if (parsed == null) {
                showCustomToast("二维码配置格式无效")
                return@registerForActivityResult
            }

            validateAndApplyConnection(parsed, persistOnSuccess = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        configStore = ConnectionConfigStore(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        backHandlerThread = HandlerThread("background")
        backHandlerThread.start()
        backHandler = Handler(backHandlerThread.looper)

        setupDependencies()
        setupUI()
        observeViewModel()
        checkPermissions()

        activeConfig = configStore.load()
        configFocusIndex = if (activeConfig == null) 0 else 1
        showServiceSelectionMode()
    }

    override fun onResume() {
        super.onResume()
        if (currentMode == UiMode.STREAMING && !hasStartedCamera) {
            startStreamingUi()
        }
    }

    override fun onPause() {
        stopCaptureLoop()
        disconnectSuggestionStream()
        stopAudioRecording(silent = true)
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        stopCaptureLoop()
        disconnectSuggestionStream()
        closeCamera()
        stopAudioRecording(silent = true)
        cameraExecutor.shutdown()
        backHandlerThread.quitSafely()
        streamClient.dispatcher.executorService.shutdown()
        streamClient.connectionPool.evictAll()
        super.onDestroy()
    }

    private fun setupDependencies() {
        val retrofit = Retrofit.Builder()
            .baseUrl(AppConfig.SERVER_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val apiService = retrofit.create(GameApiService::class.java)
        val repository = ChatRepository(apiService)
        val deviceManager = RayNeoDeviceManager(this)
        val factory = ChatViewModelFactory(repository, deviceManager)
        viewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]

        audioRecorder = RayNeoAudioRecorder(this) { file ->
            viewModel.uploadAudio(file)
        }
    }

    private fun setupUI() {
        mBindingPair.updateView {
            tvStatus.text = "未配置连接"
            tvStatus.setTextColor(getColor(R.color.neon_green))
            tvContentHand.text = ""
            tvContentSuggested.text = ""
            tvContentWaiting.text = ""

            btnScrollUp.setOnClickListener {
                val scrollAmount = (tvContentWaiting.textSize * 3).toInt()
                svContentWaiting.smoothScrollBy(0, -scrollAmount)
            }
            btnScrollDown.setOnClickListener {
                val scrollAmount = (tvContentWaiting.textSize * 3).toInt()
                svContentWaiting.smoothScrollBy(0, scrollAmount)
            }

            tvConnectionStatus.setOnClickListener { toggleConfigMode() }
            btnLocalConnection.setOnClickListener {
                serviceFocusIndex = 0
                updateServiceFocusUi()
                showConfigMode()
            }
            btnCloudConnection.setOnClickListener {
                serviceFocusIndex = 1
                updateServiceFocusUi()
                showCloudConfigMode()
            }
            btnRetryCloudConnection.setOnClickListener { connectCloudService() }
            btnScanConfig.setOnClickListener {
                configFocusIndex = 0
                updateConfigFocusUi()
                launchQrScanner()
            }
            btnUseSavedConfig.setOnClickListener {
                configFocusIndex = 1
                updateConfigFocusUi()
                useSavedConnection()
            }
            btnClearConfig.setOnClickListener {
                configFocusIndex = 2
                updateConfigFocusUi()
                clearSavedConnection()
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.mappedResult.collect { result ->
                result?.let {
                    mBindingPair.updateView {
                        tvContentHand.text = formatMahjongText(it.userHand.joinToString(" "))
                        tvContentSuggested.text = formatMahjongText(it.meldedTiles.joinToString(" "))
                        tvContentWaiting.text = formatMahjongText(it.suggestedPlay)
                    }
                }
            }
        }

        lifecycleScope.launch {
            templeActionViewModel.state.collect { action ->
                when (action) {
                    is TempleAction.Click -> handleTempleClick()
                    is TempleAction.DoubleClick -> handleDoubleClickBack()
                    is TempleAction.SlideForward -> toggleAudioRecording()
                    is TempleAction.SlideBackward -> handleSlideBackward()
                    is TempleAction.SlideUpwards -> {
                        when (currentMode) {
                            UiMode.SERVICE_SELECTION -> moveServiceFocus(-1)
                            UiMode.CONFIG -> moveConfigFocus(-1)
                            UiMode.STREAMING -> mBindingPair.updateView {
                                val scrollAmount = (tvContentWaiting.textSize * 3).toInt()
                                svContentWaiting.smoothScrollBy(0, scrollAmount)
                            }
                            UiMode.CLOUD_CONFIG -> Unit
                        }
                    }
                    is TempleAction.SlideDownwards -> {
                        when (currentMode) {
                            UiMode.SERVICE_SELECTION -> moveServiceFocus(1)
                            UiMode.CONFIG -> moveConfigFocus(1)
                            UiMode.STREAMING -> mBindingPair.updateView {
                                val scrollAmount = (tvContentWaiting.textSize * 3).toInt()
                                svContentWaiting.smoothScrollBy(0, -scrollAmount)
                            }
                            UiMode.CLOUD_CONFIG -> Unit
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun handleDoubleClickBack() {
        var isPhotoReviewVisible = false
        mBindingPair.updateView {
            isPhotoReviewVisible = layoutPhotoReviewContainer.visibility == View.VISIBLE
        }
        if (isPhotoReviewVisible) {
            mBindingPair.updateView {
                layoutPhotoReviewContainer.visibility = View.GONE
                imagePhotoReview.setImageDrawable(null)
            }
            return
        }
        when (currentMode) {
            UiMode.SERVICE_SELECTION -> finish()
            UiMode.CONFIG, UiMode.CLOUD_CONFIG -> showServiceSelectionMode()
            UiMode.STREAMING -> {
                stopStreaming()
                finish()
            }
        }
    }

    private fun handleTempleClick() {
        when (currentMode) {
            UiMode.SERVICE_SELECTION -> activateFocusedServiceAction()
            UiMode.CONFIG -> activateFocusedConfigAction()
            UiMode.CLOUD_CONFIG -> connectCloudService()
            UiMode.STREAMING -> Unit
        }
    }

    private fun moveServiceFocus(direction: Int) {
        serviceFocusIndex = (serviceFocusIndex + direction + 2) % 2
        updateServiceFocusUi()
    }

    private fun activateFocusedServiceAction() {
        if (serviceFocusIndex == 0) showConfigMode() else showCloudConfigMode()
    }

    private fun updateServiceFocusUi() {
        mBindingPair.updateView {
            val focusedBackground = R.drawable.bg_config_option_focused
            val idleBackground = R.drawable.bg_config_option_idle
            btnLocalConnection.setBackgroundResource(
                if (serviceFocusIndex == 0) focusedBackground else idleBackground
            )
            btnCloudConnection.setBackgroundResource(
                if (serviceFocusIndex == 1) focusedBackground else idleBackground
            )
            btnLocalConnection.alpha = if (serviceFocusIndex == 0) 1f else 0.72f
            btnCloudConnection.alpha = if (serviceFocusIndex == 1) 1f else 0.72f
            btnLocalConnection.text = if (serviceFocusIndex == 0) "▶ 本地连接" else "本地连接"
            btnCloudConnection.text = if (serviceFocusIndex == 1) "▶ 云端连接" else "云端连接"
        }
    }

    private fun moveConfigFocus(direction: Int) {
        val optionCount = 3
        configFocusIndex = (configFocusIndex + direction + optionCount) % optionCount
        updateConfigFocusUi()
    }

    private fun handleSlideBackward() {
        when (currentMode) {
            UiMode.SERVICE_SELECTION -> showCustomToast("请选择连接方式")
            UiMode.CONFIG, UiMode.CLOUD_CONFIG -> showServiceSelectionMode()
            UiMode.STREAMING -> showConfigMode()
        }
    }

    private fun activateFocusedConfigAction() {
        when (configFocusIndex) {
            0 -> launchQrScanner()
            1 -> useSavedConnection()
            2 -> clearSavedConnection()
        }
    }

    private fun updateConfigFocusUi() {
        mBindingPair.updateView {
            val focusedBackground = R.drawable.bg_config_option_focused
            val idleBackground = R.drawable.bg_config_option_idle

            btnScanConfig.setBackgroundResource(if (configFocusIndex == 0) focusedBackground else idleBackground)
            btnUseSavedConfig.setBackgroundResource(if (configFocusIndex == 1) focusedBackground else idleBackground)
            btnClearConfig.setBackgroundResource(if (configFocusIndex == 2) focusedBackground else idleBackground)

            btnScanConfig.alpha = if (configFocusIndex == 0) 1f else 0.72f
            btnUseSavedConfig.alpha = if (configFocusIndex == 1) 1f else 0.72f
            btnClearConfig.alpha = if (configFocusIndex == 2) 1f else 0.72f

            btnScanConfig.text = if (configFocusIndex == 0) "▶ 1. 扫码连接" else "1. 扫码连接"
            btnUseSavedConfig.text = if (configFocusIndex == 1) "▶ 2. 继续当前配置" else "2. 继续当前配置"
            btnClearConfig.text = if (configFocusIndex == 2) "▶ 3. 清除配置" else "3. 清除配置"

            tvConfigGestureHint.text = when (configFocusIndex) {
                0 -> "当前焦点：扫码连接。上滑下滑切换，单击确认。"
                1 -> "当前焦点：继续当前配置。上滑下滑切换，单击确认。"
                else -> "当前焦点：清除配置。上滑下滑切换，单击确认。"
            }
        }
    }

    private fun useSavedConnection() {
        val savedConfig = configStore.load()
        if (savedConfig == null) {
            configFocusIndex = 0
            updateConfigFocusUi()
            AlertDialog.Builder(this)
                .setTitle("本地无保存配置")
                .setMessage("请先选择“扫码连接”配置本地服务。")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        validateAndApplyConnection(savedConfig, persistOnSuccess = false)
    }

    private fun clearSavedConnection() {
        configStore.clear()
        activeConfig = null
        stopStreaming()
        showConfigMode()
        showCustomToast("已清除配置")
    }

    private fun validateAndApplyConnection(config: ConnectionConfig, persistOnSuccess: Boolean) {
        mBindingPair.updateView {
            tvStatus.text = "正在连接服务"
            tvConfigSummary.text = config.serverUrl
        }
        lifecycleScope.launch {
            if (!readinessProbe.isReady(config.serverUrl)) {
                showCustomToast("服务不可用，请确认服务端和局域网")
                mBindingPair.updateView { tvStatus.text = "连接失败" }
                return@launch
            }
            if (persistOnSuccess) {
                configStore.save(config)
            }
            applyConnectionConfig(config, startStreaming = false)
            mBindingPair.updateView { tvStatus.text = "服务已连接" }
            showCustomToast(if (persistOnSuccess) "连接成功，配置已保存" else "已连接保存的服务")
        }
    }

    private fun applyConnectionConfig(config: ConnectionConfig, startStreaming: Boolean) {
        val hasConfigChanged = activeConfig != null && activeConfig != config
        if (hasConfigChanged) {
            viewModel.endCurrentSession()
        }
        activeConfig = config
        viewModel.updateConnection(config)
        updateConnectionSummary(config)
        if (startStreaming) {
            startStreaming()
        }
    }

    private fun updateConnectionSummary(config: ConnectionConfig?) {
        mBindingPair.updateView {
            val summary = buildConfigSummary(config)
            tvConfigSummary.text = summary
            tvConnectionSummary.text = summary
            tvConfigDetails.text = buildConfigDetails(config)
            tvConnectionStatus.text = if (config == null) "连接配置" else "已连接"
        }
    }

    private fun showServiceSelectionMode() {
        currentMode = UiMode.SERVICE_SELECTION
        stopCaptureLoop()
        closeCamera()
        mBindingPair.updateView {
            layoutServiceSelectionContainer.visibility = View.VISIBLE
            layoutConfigContainer.visibility = View.GONE
            layoutCloudConfigContainer.visibility = View.GONE
            cardCameraPreview.visibility = View.GONE
            tvInstructionCameraPreview.visibility = View.GONE
            tvStreamGestureHint.visibility = View.GONE
            viewConnectionIndicator.visibility = View.GONE
            tvStatus.visibility = View.GONE
            tvConnectionSummary.visibility = View.GONE
            layoutPhotoReviewContainer.visibility = View.GONE
            layoutInstructionGaming.visibility = View.GONE
            tvInstructionIdle.visibility = View.GONE
        }
        updateServiceFocusUi()
    }

    private fun showConfigMode() {
        currentMode = UiMode.CONFIG
        activeConfig = configStore.load()
        stopCaptureLoop()
        closeCamera()
        mBindingPair.updateView {
            layoutServiceSelectionContainer.visibility = View.GONE
            layoutConfigContainer.visibility = View.VISIBLE
            layoutCloudConfigContainer.visibility = View.GONE
            cardCameraPreview.visibility = View.GONE
            tvInstructionCameraPreview.visibility = View.GONE
            tvStreamGestureHint.visibility = View.GONE
            viewConnectionIndicator.visibility = View.GONE
            tvStatus.visibility = View.VISIBLE
            tvConnectionSummary.visibility = View.VISIBLE
            layoutPhotoReviewContainer.visibility = View.GONE
            layoutInstructionGaming.visibility = View.GONE
            tvInstructionIdle.visibility = View.VISIBLE
            tvInstructionIdle.text = "上下选择 / 单击确认"
            tvStatus.text = "设置"
            tvConnectionSummary.text = buildConfigSummary(activeConfig)
            tvConfigDetails.text = buildConfigDetails(activeConfig)
        }
        updateConnectionSummary(activeConfig)
        updateConfigFocusUi()
    }

    private fun showCloudConfigMode() {
        currentMode = UiMode.CLOUD_CONFIG
        stopCaptureLoop()
        closeCamera()
        mBindingPair.updateView {
            layoutServiceSelectionContainer.visibility = View.GONE
            layoutConfigContainer.visibility = View.GONE
            layoutCloudConfigContainer.visibility = View.VISIBLE
            cardCameraPreview.visibility = View.GONE
            tvInstructionCameraPreview.visibility = View.GONE
            tvStreamGestureHint.visibility = View.GONE
            viewConnectionIndicator.visibility = View.GONE
            tvStatus.visibility = View.GONE
            tvConnectionSummary.visibility = View.GONE
            layoutPhotoReviewContainer.visibility = View.GONE
            layoutInstructionGaming.visibility = View.GONE
            tvInstructionIdle.visibility = View.GONE
            tvCloudEndpoint.text = AppConfig.CLOUD_SERVER_BASE_URL
        }
        connectCloudService()
    }

    private fun connectCloudService() {
        if (currentMode != UiMode.CLOUD_CONFIG || isCloudConnectionInFlight) return
        isCloudConnectionInFlight = true
        mBindingPair.updateView {
            tvCloudConnectionStatus.text = "正在连接云端服务..."
            tvCloudConnectionStatus.setTextColor(getColor(R.color.bright_blue))
            btnRetryCloudConnection.isEnabled = false
        }
        lifecycleScope.launch {
            val result = cloudServiceConnector.connect()
            isCloudConnectionInFlight = false
            if (currentMode != UiMode.CLOUD_CONFIG) return@launch
            result
                .onSuccess { config ->
                    activeConfig = config
                    mBindingPair.updateView {
                        tvCloudConnectionStatus.text = "云端服务连接成功"
                        tvCloudConnectionStatus.setTextColor(getColor(R.color.neon_green))
                        tvCloudEndpoint.text = config.serverUrl
                        btnRetryCloudConnection.isEnabled = true
                    }
                }
                .onFailure {
                    mBindingPair.updateView {
                        tvCloudConnectionStatus.text = "云端服务连接失败"
                        tvCloudConnectionStatus.setTextColor(getColor(android.R.color.holo_red_light))
                        btnRetryCloudConnection.isEnabled = true
                    }
                }
        }
    }

    private fun startStreaming() {
        currentMode = UiMode.STREAMING
        isPrimaryConnected = false
        isSuggestionStreamConnected = false
        startStreamingUi()
        connectSuggestionStream()
        lifecycleScope.launch {
            val started = viewModel.ensureSessionStarted()
            if (!started) {
                showCustomToast("无法建立会话")
                showConfigMode()
                return@launch
            }
            isPrimaryConnected = true
            refreshConnectionStatus()
            startCaptureLoop(activeConfig?.fps ?: AppConfig.DEFAULT_CAPTURE_FPS)
        }
    }

    private fun startStreamingUi() {
        hasStartedCamera = true
        mBindingPair.updateView {
            layoutServiceSelectionContainer.visibility = View.GONE
            layoutConfigContainer.visibility = View.GONE
            layoutCloudConfigContainer.visibility = View.GONE
            layoutInstructionGaming.visibility = View.GONE
            tvStreamGestureHint.visibility = View.GONE
            tvInstructionIdle.visibility = View.GONE
            tvStatus.visibility = View.GONE
            tvConnectionSummary.visibility = View.GONE
            viewConnectionIndicator.visibility = View.VISIBLE
            cardCameraPreview.visibility = View.VISIBLE
            tvInstructionCameraPreview.visibility = View.GONE
            layoutPhotoReviewContainer.visibility = View.GONE
            tvStatus.text = activeConfig?.label ?: "运行中"
            tvConnectionSummary.text = activeConfig?.let {
                "${it.label ?: "已连"}  ${it.fps} fps"
            } ?: "未配置连接"
            tvConnectionStatus.text = "已连接"
            tvStreamGestureHint.text = "录 | 设 | 退"
        }
        startCamera()
    }

    private fun stopStreaming() {
        currentMode = UiMode.CONFIG
        stopCaptureLoop()
        disconnectSuggestionStream()
        stopAudioRecording(silent = true)
        closeCamera()
        viewModel.endCurrentSession()
    }

    private fun connectSuggestionStream() {
        disconnectSuggestionStream()
        val streamUrl = activeConfig?.streamUrl ?: return
        val request = Request.Builder().url(streamUrl).build()
        suggestionSocket = streamClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isSuggestionStreamConnected = true
                runOnUiThread {
                    refreshConnectionStatus()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                applySuggestionPayload(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("SuggestionStream", "WebSocket failed", t)
                isSuggestionStreamConnected = false
                runOnUiThread {
                    refreshConnectionStatus()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isSuggestionStreamConnected = false
                runOnUiThread {
                    if (currentMode == UiMode.STREAMING) {
                        refreshConnectionStatus()
                    }
                }
            }
        })
    }

    private fun disconnectSuggestionStream() {
        suggestionSocket?.close(1000, "client closing")
        suggestionSocket = null
        isSuggestionStreamConnected = false
    }

    private fun applySuggestionPayload(raw: String) {
        runOnUiThread {
            runCatching {
                val json = JSONObject(raw)
                val hand = json.optJSONArray("user_hand")
                val melded = json.optJSONArray("melded_tiles")
                val suggestion = json.optString("suggested_play", "")
                val status = json.optString("status", "ready")

                val handText = buildJsonArrayText(hand)
                val meldedText = buildJsonArrayText(melded)

                mBindingPair.updateView {
                    if (handText.isNotBlank()) {
                        tvContentHand.text = formatMahjongText(handText)
                    }
                    if (meldedText.isNotBlank()) {
                        tvContentSuggested.text = formatMahjongText(meldedText)
                    }
                    if (suggestion.isNotBlank()) {
                        tvContentWaiting.text = formatMahjongText(suggestion)
                    }
                    tvConnectionSummary.text = buildString {
                        append(activeConfig?.label ?: "已连")
                        append("  ")
                        append(activeConfig?.fps ?: AppConfig.DEFAULT_CAPTURE_FPS)
                        append(" fps")
                    }
                }
                if (status == "ready") {
                    isSuggestionStreamConnected = true
                }
                refreshConnectionStatus(status)
            }.onFailure {
                Log.e("SuggestionStream", "Failed to parse payload: $raw", it)
            }
        }
    }

    private fun refreshConnectionStatus(streamStatus: String? = null) {
        if (currentMode != UiMode.STREAMING) return
        val text = when {
            isSuggestionStreamConnected && streamStatus == "processing" -> "等待建议"
            isSuggestionStreamConnected -> "流已连接"
            isPrimaryConnected -> "已连接"
            else -> "连接中"
        }
        val indicatorColor = when {
            isSuggestionStreamConnected -> R.color.neon_green
            isPrimaryConnected -> R.color.bright_blue
            else -> R.color.red
        }
        mBindingPair.updateView {
            tvConnectionStatus.text = text
            viewConnectionIndicator.backgroundTintList =
                ContextCompat.getColorStateList(this@MainActivity, indicatorColor)
        }
    }

    private fun buildConfigSummary(config: ConnectionConfig?): String {
        return if (config == null) {
            "未配置连接"
        } else {
            buildString {
                append(config.label ?: "当前环境")
                append("  ")
                append("${config.fps} fps")
            }
        }
    }

    private fun buildConfigDetails(config: ConnectionConfig?): String {
        if (config == null) {
            return "环境: 未配置 | 主地址: 请先扫码连接 | 流式: 未配置 | 会话Key: 无 | 操作: 内滑录音 | 外滑设置 | 双击返回"
        }

        val streamState = when {
            config.streamUrl.isNullOrBlank() -> "未配置"
            isSuggestionStreamConnected -> "已连接"
            currentMode == UiMode.STREAMING -> "已配置"
            else -> "已配置"
        }
        val sessionState = if (config.sessionKey.isNullOrBlank()) "无" else "已配置"

        return buildString {
            append("环境: ${config.label ?: "当前环境"}")
            append(" | 主地址: ${config.serverUrl}")
            append(" | 流式: $streamState")
            append(" | 会话Key: $sessionState")
            append(" | 操作: 内滑录音 | 外滑设置 | 双击返回")
        }
    }

    private fun buildJsonArrayText(array: org.json.JSONArray?): String {
        if (array == null) return ""
        val items = mutableListOf<String>()
        for (index in 0 until array.length()) {
            items.add(array.optString(index))
        }
        return items.joinToString(" ")
    }

    private fun toggleConfigMode() {
        if (currentMode != UiMode.CONFIG) {
            showConfigMode()
        }
    }

    private fun toggleAudioRecording() {
        if (currentMode != UiMode.STREAMING) {
            return
        }

        if (isRecordingAudio) {
            stopAudioRecording()
        } else {
            audioRecorder?.start()
            isRecordingAudio = true
            showCustomToast("录音开始")
        }
    }

    private fun stopAudioRecording(silent: Boolean = false) {
        if (!isRecordingAudio) return
        audioRecorder?.stop()
        isRecordingAudio = false
        if (!silent) {
            showCustomToast("录音停止")
        }
    }

    private fun startCaptureLoop(fps: Int) {
        stopCaptureLoop()
        val safeFps = fps.coerceIn(1, AppConfig.MAX_CAPTURE_FPS)
        val intervalMs = 1000L / safeFps
        captureLoopJob = lifecycleScope.launch {
            while (currentMode == UiMode.STREAMING) {
                if (!uploadInFlight.get() && cameraCaptureSession != null) {
                    pendingAutoUpload = true
                    uploadInFlight.set(true)
                    takePhoto()
                }
                delay(intervalMs)
            }
        }
    }

    private fun stopCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = null
        uploadInFlight.set(false)
        pendingAutoUpload = false
        hasStartedCamera = false
    }

    private fun launchQrScanner() {
        scanLauncher.launch(Intent(this, QrScanActivity::class.java))
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }

    private fun formatMahjongText(originalText: String): SpannableString {
        val spannable = SpannableString(originalText)
        val mahjongTypeface = if (AppConfig.USE_COLOR_FONT) {
            try {
                resources.getFont(R.font.mahjong_color)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load mahjong font", e)
                null
            }
        } else {
            null
        }

        val scaleFactor = if (AppConfig.USE_COLOR_FONT) {
            AppConfig.FONT_SCALE_COLOR
        } else {
            AppConfig.FONT_SCALE_DEFAULT
        }

        var index = 0
        while (index < originalText.length) {
            val codePoint = originalText.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            if (codePoint in 0x1F000..0x1F02B) {
                spannable.setSpan(
                    RelativeSizeSpan(scaleFactor),
                    index,
                    index + charCount,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                if (mahjongTypeface != null) {
                    spannable.setSpan(
                        TypefaceSpan(mahjongTypeface),
                        index,
                        index + charCount,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            index += charCount
        }
        return spannable
    }

    private fun startCamera() {
        if (surfaceList.isNotEmpty()) {
            return
        }

        surfaceList.clear()
        mBindingPair.updateView {
            fun onSurfaceReady(surface: SurfaceTexture, width: Int, height: Int) {
                configureTransform(width, height)
                val previewSurface = Surface(surface)
                if (!surfaceList.contains(previewSurface)) {
                    surfaceList.add(previewSurface)
                }

                if (surfaceList.size == 2) {
                    lifecycleScope.launch {
                        delay(100L)
                        setupCamera2()
                    }
                }
            }

            viewCameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    onSurfaceReady(surface, width, height)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    configureTransform(width, height)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }

            if (viewCameraPreview.isAvailable) {
                viewCameraPreview.surfaceTexture?.let {
                    onSurfaceReady(it, viewCameraPreview.width, viewCameraPreview.height)
                }
            }
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        mBindingPair.updateView {
            val textureView = viewCameraPreview
            val matrix = Matrix()
            val pWidth = previewSize?.width?.toFloat() ?: 900f
            val pHeight = previewSize?.height?.toFloat() ?: 1200f
            val centerX = viewWidth / 2f
            val centerY = viewHeight / 2f
            val videoAspect = pWidth / pHeight
            val scaleX = (viewHeight * videoAspect) / viewWidth
            matrix.setScale(scaleX, 1f, centerX, centerY)
            textureView.setTransform(matrix)
        }
    }

    private fun setupCamera2() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList.first()
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val supportedSizes = map.getOutputSizes(SurfaceTexture::class.java).toList()
                previewSize = chooseOptimalSize(supportedSizes.toTypedArray(), 900, 1200)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, stateCallback, backHandler)
            }
        } catch (e: Exception) {
            Log.e("Camera2", "Failed to open camera", e)
        }
    }

    private fun chooseOptimalSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int): Size {
        val bigEnough = ArrayList<Size>()
        val notBigEnough = ArrayList<Size>()
        for (option in choices) {
            if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                bigEnough.add(option)
            } else {
                notBigEnough.add(option)
            }
        }
        return when {
            bigEnough.isNotEmpty() -> Collections.min(bigEnough) { lhs, rhs ->
                java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
            }

            notBigEnough.isNotEmpty() -> Collections.max(notBigEnough) { lhs, rhs ->
                java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
            }

            else -> choices[0]
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraJob = lifecycleScope.launch {
                cameraDevice = camera
                delay(100L)
                if (cameraDevice == camera) {
                    setUpImageReader(camera)
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
            cameraDevice = null
            cameraJob?.cancel()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
            cameraDevice = null
        }
    }

    private fun setUpImageReader(camera: CameraDevice) {
        imageReader?.close()
        val pWidth = previewSize?.width ?: 900
        val pHeight = previewSize?.height ?: 1200

        imageReader = ImageReader.newInstance(pWidth, pHeight, android.graphics.ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            lifecycleScope.launch(Dispatchers.IO) {
                var imageClosed = false
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    imageClosed = true

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val matrix = Matrix().apply { postRotate(90f) }
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                    val cropY = (rotatedBitmap.height * 0.50).toInt()
                    val cropHeight = (rotatedBitmap.height * 0.50).toInt()
                    val finalY = cropY.coerceIn(0, rotatedBitmap.height)
                    val finalHeight = cropHeight.coerceAtMost(rotatedBitmap.height - finalY)
                    val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, 0, finalY, rotatedBitmap.width, finalHeight)

                    val photoFile = File(
                        getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                        "${System.currentTimeMillis()}.jpg"
                    )

                    FileOutputStream(photoFile).use { fos ->
                        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    }

                    bitmap.recycle()
                    rotatedBitmap.recycle()
                    currentPhotoFile = photoFile

                    withContext(Dispatchers.Main) {
                        if (pendingAutoUpload && currentMode == UiMode.STREAMING) {
                            viewModel.uploadPhoto(photoFile) {
                                uploadInFlight.set(false)
                            }
                        } else {
                            uploadInFlight.set(false)
                            showPhotoReview(Uri.fromFile(photoFile))
                        }
                        pendingAutoUpload = false
                    }
                } catch (e: Exception) {
                    Log.e("Camera2", "Save photo failed", e)
                    uploadInFlight.set(false)
                    pendingAutoUpload = false
                    withContext(Dispatchers.Main) {
                        showCustomToast("保存照片失败: ${e.message}")
                    }
                } finally {
                    if (!imageClosed) {
                        try {
                            image.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }, backHandler)

        try {
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                for (surface in surfaceList) {
                    addTarget(surface)
                }
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
            }

            val outputConfigs = mutableListOf<OutputConfiguration>()
            imageReader?.let { outputConfigs.add(OutputConfiguration(it.surface)) }
            for (surface in surfaceList) {
                outputConfigs.add(OutputConfiguration(surface))
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            session.close()
                            return
                        }
                        cameraCaptureSession = session
                        try {
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, backHandler)
                        } catch (e: Exception) {
                            Log.e("Camera2", "Capture request failed", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("Camera2", "Session configuration failed")
                    }
                }
            )
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e("Camera2", "Create capture session failed", e)
        }
    }

    private fun takePhoto() {
        try {
            val session = cameraCaptureSession ?: return
            val device = cameraDevice ?: return
            val reader = imageReader ?: return
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            for (surface in surfaceList) {
                captureBuilder.addTarget(surface)
            }
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90)
            session.capture(captureBuilder.build(), null, backHandler)
        } catch (e: Exception) {
            uploadInFlight.set(false)
            pendingAutoUpload = false
            Log.e("Camera2", "Take photo failed", e)
            showCustomToast("拍照失败")
        }
    }

    private fun closeCamera() {
        try {
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            previewSize = null
            for (surface in surfaceList) {
                surface.release()
            }
            surfaceList.clear()
        } catch (e: Exception) {
            Log.e("Camera2", "Close camera failed", e)
        }
    }

    private fun showPhotoReview(uri: Uri) {
        mBindingPair.updateView {
            layoutPhotoReviewContainer.visibility = View.VISIBLE
            imagePhotoReview.setImageURI(uri)
        }
    }

    private fun showCustomToast(message: String) {
        mBindingPair.updateView {
            tvCustomToast.text = message
            tvCustomToast.visibility = View.VISIBLE
            tvCustomToast.postDelayed({
                tvCustomToast.visibility = View.GONE
            }, 2000)
        }
    }
}
