package com.example.ai_assist.preview

import android.os.Bundle
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ai_assist.databinding.ActivityMahjongUiPreviewLayoutTwoBinding
import com.example.ai_assist.sdk.capability.InputAction
import com.example.ai_assist.sdk.device.rayneo.x3.MercuryInputActionMapper
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.launch

/** Debug-only entry point for the second Mahjong HUD layout. */
class MahjongUiPreviewLayoutTwoActivity : BaseMirrorActivity<ActivityMahjongUiPreviewLayoutTwoBinding>() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                templeActionViewModel.state.collect { source ->
                    if (MercuryInputActionMapper.map(source) == InputAction.DOUBLE_CLICK) {
                        finish()
                    }
                }
            }
        }
    }
}
