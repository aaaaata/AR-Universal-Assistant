package com.example.ai_assist.sdk.platform.android.capability.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.example.ai_assist.sdk.capability.DisplayCapability
import com.example.ai_assist.sdk.capability.DisplayInfo

/** 使用 Android 默认显示器提供分辨率和刷新率信息。 */
internal class AndroidDisplayCapability(context: Context) : DisplayCapability {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    override fun isAvailable(): Boolean = defaultDisplay() != null

    override fun supportedFeatures(): Set<String> = setOf(
        "display-size",
        "refresh-rate"
    )

    override fun getDisplayInfo(): DisplayInfo {
        val mode = checkNotNull(defaultDisplay()) { "Default display is unavailable" }.mode
        return DisplayInfo(
            widthPixels = mode.physicalWidth,
            heightPixels = mode.physicalHeight,
            refreshRateHz = mode.refreshRate,
            stereoscopic = false
        )
    }

    private fun defaultDisplay(): Display? = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
}
