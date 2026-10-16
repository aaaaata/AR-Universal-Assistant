package com.example.ai_assist.sdk.device.rayneo.x3

import android.app.Application
import com.example.ai_assist.sdk.platform.android.device.DeviceSdkInitializer
import com.ffalcon.mercury.android.sdk.MercurySDK

/** 使用 Mercury 实现通用平台 SDK 初始化契约。 */
object MercurySdkInitializer : DeviceSdkInitializer {
    /** 初始化 Mercury SDK。 */
    override fun initialize(application: Application) {
        MercurySDK.init(application)
    }
}
