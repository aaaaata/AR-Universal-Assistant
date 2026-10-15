package com.example.ai_assist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ai_assist.databinding.ActivityMainBinding
import com.example.ai_assist.databinding.ViewSceneMenuItemBinding
import com.example.ai_assist.framework.registerScenes
import com.example.ai_assist.sdk.framework.AndroidCameraScheduler
import com.example.ai_assist.sdk.framework.CapabilityType
import com.example.ai_assist.sdk.framework.CredentialProvider
import com.example.ai_assist.sdk.framework.DefaultSdkConfig
import com.example.ai_assist.sdk.framework.FrameworkRuntime
import com.example.ai_assist.sdk.framework.InputAction
import com.example.ai_assist.sdk.framework.SceneConfig
import com.example.ai_assist.sdk.framework.SceneLaunchController
import com.example.ai_assist.sdk.framework.SceneLaunchProvider
import com.example.ai_assist.sdk.framework.SceneRegistration
import com.example.ai_assist.sdk.framework.SceneRenderer
import com.example.ai_assist.sdk.framework.SdkEvent
import com.example.ai_assist.sdk.framework.SdkState
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/** 通用场景宿主：只管理场景列表、容器、平台输入和 Core。 */
class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {
    private enum class Screen { MENU, LAUNCH, SCENE }

    private lateinit var cameraScheduler: AndroidCameraScheduler
    private lateinit var core: FrameworkRuntime
    private var registrations: List<SceneRegistration> = emptyList()
    private var screen = Screen.MENU
    private var selectedMenuIndex = 0
    private var selectedRegistration: SceneRegistration? = null
    private var selectedRenderer: SceneRenderer? = null
    private var launchController: SceneLaunchController? = null
    private var sceneJob: Job? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var hasRenderedResult = false
    private var fatalSceneError: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingPermissionAction
            pendingPermissionAction = null
            if (granted) action?.invoke() else showMessage("需要相机权限")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraScheduler = AndroidCameraScheduler(applicationContext)
        core = FrameworkRuntime(
            cameraScheduler = cameraScheduler,
            credentialProvider = CredentialProvider { _, credentialId ->
                when (credentialId) {
                    "mahjong.local" -> BuildConfig.LOCAL_API_KEY
                    "mahjong.cloud" -> BuildConfig.CLOUD_API_KEY
                    else -> null
                }?.takeIf(String::isNotBlank)
            }
        )
        registrations = registerScenes(core)
        core.initialize(DefaultSdkConfig)
        observeInput()
        showMenu()
    }

    private fun observeInput() {
        lifecycleScope.launch {
            templeActionViewModel.state.collect { source ->
                val action = source.toFrameworkAction() ?: return@collect
                when (screen) {
                    Screen.MENU -> handleMenuInput(action)
                    Screen.LAUNCH -> launchController?.onInput(action)
                    Screen.SCENE -> core.publishInput(action)
                }
            }
        }
    }

    private fun handleMenuInput(action: InputAction) {
        when (action) {
            InputAction.SLIDE_FORWARD -> moveMenu(1)
            InputAction.SLIDE_BACKWARD -> moveMenu(-1)
            InputAction.CLICK -> activateMenuItem()
            InputAction.DOUBLE_CLICK -> finish()
            else -> Unit
        }
    }

    private fun showMenu() {
        closeLaunchController()
        screen = Screen.MENU
        hasRenderedResult = false
        selectedRegistration = null
        selectedRenderer = null
        val itemCount = registrations.size + 1
        selectedMenuIndex = selectedMenuIndex.coerceIn(0, itemCount - 1)
        mBindingPair.updateView {
            menuRoot.visibility = View.VISIBLE
            sceneRoot.visibility = View.GONE
            sceneContent.removeAllViews()
        }
        renderMenu()
    }

    private fun renderMenu() {
        mBindingPair.updateView {
            menuSceneItems.removeAllViews()
            registrations.forEachIndexed { index, registration ->
                addMenuItem(
                    index,
                    registration.presentation.menuTitle,
                    registration.presentation.menuIconRes
                )
            }
            addMenuItem(registrations.size, getString(R.string.scene_add_name), R.drawable.ic_add_scene)
            menuSceneScroll.post {
                val selected = menuSceneItems.getChildAt(selectedMenuIndex) ?: return@post
                val targetX = selected.left - (menuSceneScroll.width - selected.width) / 2
                menuSceneScroll.smoothScrollTo(targetX.coerceAtLeast(0), 0)
            }
        }
    }

    private fun ActivityMainBinding.addMenuItem(index: Int, title: String, iconRes: Int) {
        val item = ViewSceneMenuItemBinding.inflate(
            LayoutInflater.from(this@MainActivity),
            menuSceneItems,
            false
        )
        val selected = index == selectedMenuIndex
        item.root.isSelected = selected
        item.sceneMenuIconFrame.isSelected = selected
        item.sceneMenuIcon.setImageResource(iconRes)
        item.sceneMenuIcon.contentDescription = title
        item.sceneMenuIcon.scaleX = if (selected) 1.18f else 1f
        item.sceneMenuIcon.scaleY = if (selected) 1.18f else 1f
        item.sceneMenuIcon.alpha = if (selected) 1f else 0.82f
        item.sceneMenuTitle.text = title
        item.sceneMenuTitle.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        item.root.setOnClickListener {
            selectedMenuIndex = index
            renderMenu()
            activateMenuItem()
        }
        menuSceneItems.addView(item.root)
    }

    private fun moveMenu(offset: Int) {
        val count = registrations.size + 1
        selectedMenuIndex = (selectedMenuIndex + offset + count) % count
        renderMenu()
    }

    private fun activateMenuItem() {
        if (selectedMenuIndex !in registrations.indices) {
            showMessage(getString(R.string.scene_catalog_unavailable))
            return
        }
        val registration = registrations[selectedMenuIndex]
        ensureCapabilities(registration) { openRegistration(registration) }
    }

    private fun ensureCapabilities(registration: SceneRegistration, action: () -> Unit) {
        val needsCamera = CapabilityType.CAMERA in registration.provider.requiredCapabilities
        if (!needsCamera || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingPermissionAction = action
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun openRegistration(registration: SceneRegistration) {
        selectedRegistration = registration
        selectedRenderer = registration.provider.createRenderer()
        val launchProvider = registration.provider as? SceneLaunchProvider
        if (launchProvider == null) {
            startSelectedScene(registration.provider.createSceneConfig())
            return
        }
        screen = Screen.LAUNCH
        mBindingPair.updateView {
            menuRoot.visibility = View.GONE
            sceneRoot.visibility = View.VISIBLE
            sceneContent.visibility = View.VISIBLE
            tvStatus.visibility = View.GONE
            tvInstructionIdle.visibility = View.GONE
            tvInstructionRunning.visibility = View.GONE
        }
        launchController = launchProvider.createLaunchController().also { controller ->
            mBindingPair.updateView {
                controller.attach(
                    activity = this@MainActivity,
                    container = sceneContent,
                    onStart = { config ->
                        closeLaunchController()
                        startSelectedScene(config)
                    },
                    onExit = ::showMenu
                )
            }
        }
    }

    private fun closeLaunchController() {
        launchController?.close()
        launchController = null
    }

    private fun startSelectedScene(config: SceneConfig) {
        val registration = selectedRegistration ?: return
        val provider = registration.provider
        screen = Screen.SCENE
        hasRenderedResult = false
        fatalSceneError = null
        selectedRenderer?.clear()
        mBindingPair.updateView {
            menuRoot.visibility = View.GONE
            sceneRoot.visibility = View.VISIBLE
            sceneContent.visibility = View.INVISIBLE
            sceneContent.removeAllViews()
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.status_scene_starting, provider.sceneName)
            tvInstructionIdle.visibility = View.GONE
            tvInstructionRunning.visibility = View.GONE
        }
        sceneJob?.cancel()
        sceneJob = lifecycleScope.launch {
            core.startScene(provider.sceneId, config)
                .onEach(::handleSdkEvent)
                .takeWhile { event ->
                    event !is SdkEvent.StateChanged || event.state != SdkState.READY
                }
                .catch { error ->
                    if (error !is CancellationException) {
                        showMessage(error.message ?: "场景启动失败")
                        if (screen == Screen.SCENE) showMenu()
                    }
                }
                .collect { }
            if (screen == Screen.SCENE) showMenu()
        }
    }

    private fun handleSdkEvent(event: SdkEvent) {
        when (event) {
            is SdkEvent.StateChanged -> {
                Log.i(TAG, "Scene state=${event.state}, session=${event.sessionId}")
                if (event.state != SdkState.READY || fatalSceneError == null) {
                    renderState(event.state)
                }
            }
            is SdkEvent.ResultReceived -> mBindingPair.updateView {
                event.result.content?.let { selectedRenderer?.render(sceneContent, it) }
                hasRenderedResult = true
                sceneContent.visibility = View.VISIBLE
                tvStatus.visibility = View.GONE
            }
            is SdkEvent.ErrorOccurred -> {
                Log.e(TAG, "Scene error ${event.code}: ${event.message}")
                if (event.recoverable) {
                    showMessage("本次处理失败：${event.message}")
                } else {
                    fatalSceneError = event.message
                    mBindingPair.updateView {
                        tvStatus.text = getString(
                            R.string.status_scene_failed_detail,
                            selectedRegistration?.provider?.sceneName.orEmpty(),
                            event.message
                        )
                        tvStatus.visibility = View.VISIBLE
                        sceneContent.visibility = View.INVISIBLE
                    }
                }
            }
            is SdkEvent.DiagnosticReported -> showMessage(event.message)
        }
    }

    private fun renderState(state: SdkState) {
        val sceneName = selectedRegistration?.provider?.sceneName.orEmpty()
        mBindingPair.updateView {
            tvStatus.text = when (state) {
                SdkState.UNINITIALIZED -> "正在初始化"
                SdkState.READY -> getString(R.string.status_ready)
                SdkState.STARTING -> getString(R.string.status_scene_starting, sceneName)
                SdkState.RUNNING -> getString(R.string.status_scene_running, sceneName)
                SdkState.STOPPING -> getString(R.string.status_scene_stopping, sceneName)
                SdkState.ERROR -> getString(R.string.status_scene_error, sceneName)
            }
            tvStatus.visibility = if (state == SdkState.RUNNING && hasRenderedResult) {
                View.GONE
            } else {
                View.VISIBLE
            }
            tvInstructionIdle.visibility = View.GONE
            tvInstructionRunning.visibility = View.GONE
        }
    }

    override fun onPause() {
        when (screen) {
            Screen.LAUNCH -> showMenu()
            Screen.SCENE -> core.onAppBackground()
            Screen.MENU -> Unit
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::core.isInitialized) core.onAppForeground()
    }

    override fun onDestroy() {
        pendingPermissionAction = null
        closeLaunchController()
        sceneJob?.cancel()
        if (::core.isInitialized) {
            core.close()
        } else if (::cameraScheduler.isInitialized) {
            cameraScheduler.close()
        }
        super.onDestroy()
    }

    private fun showMessage(message: String) {
        Log.w(TAG, message)
    }

    private fun TempleAction.toFrameworkAction(): InputAction? = when (this) {
        is TempleAction.Click -> InputAction.CLICK
        is TempleAction.DoubleClick -> InputAction.DOUBLE_CLICK
        is TempleAction.TripleClick -> InputAction.TRIPLE_CLICK
        is TempleAction.SlideForward -> InputAction.SLIDE_FORWARD
        is TempleAction.SlideBackward -> InputAction.SLIDE_BACKWARD
        is TempleAction.SlideUpwards -> InputAction.SLIDE_UP
        is TempleAction.SlideDownwards -> InputAction.SLIDE_DOWN
        else -> null
    }

    private companion object {
        const val TAG = "MahjongAssist"
    }
}
