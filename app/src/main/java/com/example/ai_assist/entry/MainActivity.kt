package com.example.ai_assist.entry

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ai_assist.R
import com.example.ai_assist.databinding.ActivityMainBinding
import com.example.ai_assist.permission.AndroidPermissionResolver
import com.example.ai_assist.permission.PermissionRequest
import com.example.ai_assist.permission.PermissionRequester
import com.example.ai_assist.permission.PermissionResult
import com.example.ai_assist.sdk.device.rayneo.x3.MercuryInputActionMapper
import com.example.ai_assist.ui.MainScreenRenderer
import com.example.ai_assist.ui.MainUiEffect
import com.example.ai_assist.ui.MainViewModel
import com.example.ai_assist.ui.MainViewModelFactory
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.launch

/** Android 页面入口，负责生命周期、系统交互和 UI 事件连接。 */
class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {
    private lateinit var viewModel: MainViewModel
    private lateinit var screenRenderer: MainScreenRenderer
    private lateinit var permissionRequester: PermissionRequester
    private lateinit var modePicker: ModeSelectionLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TIMING_TAG, "event=app_on_create elapsed_ms=${SystemClock.elapsedRealtime()}")
        super.onCreate(savedInstanceState)
        permissionRequester = PermissionRequester(this)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(applicationContext)
        )[MainViewModel::class.java]
        modePicker = ModeSelectionLauncher(this, viewModel::startSelectedScene)
        screenRenderer = MainScreenRenderer(
            context = this,
            sceneRenderRouter = viewModel.sceneRenderRouter,
            onMenuItemClicked = viewModel::onMenuItemClicked
        )
        mBindingPair.updateView { screenRenderer.setup(this) }
        observePresentation()
        observeInput()
        Log.i(TIMING_TAG, "event=app_observers_ready elapsed_ms=${SystemClock.elapsedRealtime()}")
    }

    override fun onPause() {
        Log.i(TIMING_TAG, "event=app_paused elapsed_ms=${SystemClock.elapsedRealtime()}")
        viewModel.onAppPaused()
        super.onPause()
    }

    private fun observePresentation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        mBindingPair.updateView { screenRenderer.render(this, state) }
                    }
                }
                launch { viewModel.effects.collect(::handleUiEffect) }
            }
        }
    }

    private fun observeInput() {
        lifecycleScope.launch {
            templeActionViewModel.state.collect { source ->
                viewModel.onInputAction(MercuryInputActionMapper.map(source))
            }
        }
    }

    private suspend fun handleUiEffect(effect: MainUiEffect) {
        when (effect) {
            is MainUiEffect.RenderResult -> mBindingPair.updateView {
                screenRenderer.renderResult(this, effect.result)
            }
            is MainUiEffect.PresentNotice -> mBindingPair.updateView {
                screenRenderer.presentNotice(effect.sceneId, effect.update)
            }
            is MainUiEffect.ResetSceneContent -> mBindingPair.updateView {
                screenRenderer.resetSceneContent(this, effect.sceneId)
            }
            MainUiEffect.ClearSceneContent -> mBindingPair.updateView {
                screenRenderer.clearSceneContent(this)
            }
            MainUiEffect.OpenSceneCatalog -> showMessage(
                getString(R.string.scene_catalog_unavailable)
            )
            is MainUiEffect.OpenModePicker -> modePicker.launch(this, effect.startModeOnly)
            is MainUiEffect.ShowMessage -> showMessage(effect.message)
            MainUiEffect.NavigateBack -> onBackPressedDispatcher.onBackPressed()
            is MainUiEffect.RequestScenePermissions -> requestScenePermissions(effect)
        }
    }

    private suspend fun requestScenePermissions(
        effect: MainUiEffect.RequestScenePermissions
    ) {
        Log.i(
            TIMING_TAG,
            "event=permission_request scene_id=${effect.sceneId} " +
                "elapsed_ms=${SystemClock.elapsedRealtime()}"
        )
        val permissions = AndroidPermissionResolver.resolve(effect.requirements)
        val result = if (permissions.isEmpty()) {
            PermissionResult.Granted
        } else {
            permissionRequester.request(PermissionRequest(permissions))
        }
        viewModel.onScenePermissionResult(effect.sceneId, result)
    }

    private fun showMessage(message: String) {
        mBindingPair.updateView { screenRenderer.showTemporaryMessage(this, message) }
    }

    private companion object {
        const val TIMING_TAG = "AssistTiming"
    }
}
