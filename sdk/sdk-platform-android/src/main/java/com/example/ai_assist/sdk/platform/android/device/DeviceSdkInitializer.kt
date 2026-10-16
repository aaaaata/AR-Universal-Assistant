package com.example.ai_assist.sdk.platform.android.device

import android.app.Application

/** 初始化宿主选用的 Android 平台 SDK。 */
fun interface DeviceSdkInitializer {
    /** 在应用进程启动时完成平台 SDK 初始化。 */
    fun initialize(application: Application)
}
