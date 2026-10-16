package com.example.ai_assist.entry

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/** Keeps the scene-specific selection Activity Result wiring outside the generic app entry. */
internal class ModeSelectionLauncher(
    activity: ComponentActivity,
    private val onSelected: (serviceMode: String, startModeId: String) -> Unit
) {
    private val launcher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        onSelected(
            data.getStringExtra(MahjongModeSelectionActivity.EXTRA_SERVICE_MODE).orEmpty(),
            data.getStringExtra(MahjongModeSelectionActivity.EXTRA_START_MODE).orEmpty()
        )
    }

    fun launch(activity: ComponentActivity, startModeOnly: Boolean) {
        launcher.launch(
            Intent(activity, MahjongModeSelectionActivity::class.java)
                .putExtra(MahjongModeSelectionActivity.EXTRA_START_MODE_ONLY, startModeOnly)
        )
    }
}
