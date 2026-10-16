package com.example.ai_assist.entry

import android.app.Application
import com.example.ai_assist.sdk.device.rayneo.x3.MercurySdkInitializer
import com.example.ai_assist.sdk.platform.android.device.DeviceSdkInitializer

/** 通用客户端应用入口。 */
class AssistApplication : Application() {
    private val sdkInitializer: DeviceSdkInitializer = MercurySdkInitializer

    override fun onCreate() {
        super.onCreate()
        deleteSharedPreferences(LEGACY_SERVER_ENDPOINT_PREFERENCES)
        sdkInitializer.initialize(this)
    }

    private companion object {
        const val LEGACY_SERVER_ENDPOINT_PREFERENCES = "server_endpoints"
    }
}
