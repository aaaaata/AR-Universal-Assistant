package com.example.ai_assist.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ai_assist.databinding.ActivityMahjongModeSelectionBinding
import com.example.ai_assist.sdk.capability.InputAction
import com.example.ai_assist.sdk.device.rayneo.x3.MercuryInputActionMapper
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.launch

/** Two-step glasses-friendly service and game-mode picker. */
class MahjongModeSelectionActivity : BaseMirrorActivity<ActivityMahjongModeSelectionBinding>() {
    private var step = Step.SERVICE
    private var selectedOption = 0
    private var serviceMode = SERVICE_LOCAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_START_MODE_ONLY, false)) {
            step = Step.GAME_MODE
            serviceMode = ""
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mBindingPair.updateView {
            leftButton.setOnClickListener { selectAndConfirm(0) }
            rightButton.setOnClickListener { selectAndConfirm(1) }
        }
        renderStep()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                templeActionViewModel.state.collect { source ->
                    when (MercuryInputActionMapper.map(source)) {
                        InputAction.SLIDE_FORWARD -> moveSelection(1)
                        InputAction.SLIDE_BACKWARD -> moveSelection(-1)
                        InputAction.CLICK -> confirmSelection()
                        InputAction.DOUBLE_CLICK -> navigateBack()
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun moveSelection(offset: Int) {
        selectedOption = (selectedOption + offset + OPTION_COUNT) % OPTION_COUNT
        renderSelection()
    }

    private fun selectAndConfirm(option: Int) {
        selectedOption = option
        renderSelection()
        confirmSelection()
    }

    private fun confirmSelection() {
        when (step) {
            Step.SERVICE -> {
                serviceMode = if (selectedOption == 0) SERVICE_LOCAL else SERVICE_CLOUD
                if (serviceMode == SERVICE_LOCAL) {
                    finishWithSelection(startMode = "")
                } else {
                    step = Step.GAME_MODE
                    selectedOption = 0
                    renderStep()
                }
            }
            Step.GAME_MODE -> {
                val startMode = if (selectedOption == 0) MODE_REGRESSION else MODE_NORMAL
                finishWithSelection(startMode)
            }
        }
    }

    /** The picker owns the two selection steps, so a double-click only leaves at its first step. */
    private fun navigateBack() {
        if (step == Step.GAME_MODE) {
            step = Step.SERVICE
            selectedOption = 0
            serviceMode = SERVICE_LOCAL
            renderStep()
        } else {
            finish()
        }
    }

    private fun finishWithSelection(startMode: String) {
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_SERVICE_MODE, serviceMode)
                .putExtra(EXTRA_START_MODE, startMode)
        )
        finish()
    }

    private fun renderStep() {
        mBindingPair.updateView {
            when (step) {
                Step.SERVICE -> {
                    selectionTitle.text = "连接方式选择"
                    leftButton.text = "本地服务"
                    rightButton.text = "云端服务"
                }
                Step.GAME_MODE -> {
                    selectionTitle.text = "开局模式选择"
                    leftButton.text = "录制对局"
                    rightButton.text = "普通对局"
                }
            }
        }
        renderSelection()
    }

    private fun renderSelection() {
        mBindingPair.updateView {
            leftButton.isActivated = selectedOption == 0
            rightButton.isActivated = selectedOption == 1
            if (selectedOption == 0) leftButton.requestFocus() else rightButton.requestFocus()
        }
    }

    private enum class Step { SERVICE, GAME_MODE }

    companion object {
        const val EXTRA_SERVICE_MODE = "mahjong_service_mode"
        const val EXTRA_START_MODE = "mahjong_start_mode"
        const val EXTRA_START_MODE_ONLY = "mahjong_start_mode_only"
        const val SERVICE_LOCAL = "local"
        const val SERVICE_CLOUD = "cloud"
        const val MODE_REGRESSION = "regression"
        const val MODE_NORMAL = "normal"
        private const val OPTION_COUNT = 2
    }
}
