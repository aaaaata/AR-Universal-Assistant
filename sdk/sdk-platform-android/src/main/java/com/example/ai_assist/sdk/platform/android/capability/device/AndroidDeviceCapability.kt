package com.example.ai_assist.sdk.platform.android.capability.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.example.ai_assist.sdk.capability.DeviceCapability
import com.example.ai_assist.sdk.capability.DeviceInfo
import com.example.ai_assist.sdk.capability.DeviceStatus

/** 使用 Android 系统服务提供默认设备信息和状态。 */
internal class AndroidDeviceCapability(context: Context) : DeviceCapability {
    private val appContext = context.applicationContext

    /** 读取不包含序列号、IMEI 或 Android ID 的设备信息。 */
    override fun getDeviceInfo(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER.toNonBlankValue(),
        model = Build.MODEL.toNonBlankValue(),
        androidVersion = Build.VERSION.RELEASE.toNonBlankValue(),
        sdkInt = Build.VERSION.SDK_INT
    )

    /** 读取电量、充电状态和活动网络状态。 */
    override fun getDeviceStatus(): DeviceStatus {
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager?.activeNetwork
        val networkCapabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
        return DeviceStatus(
            batteryPercent = batteryManager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 },
            charging = batteryManager?.isCharging == true,
            networkAvailable = networkCapabilities
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        )
    }

    /** 将缺失的平台文本统一转换为可展示占位值。 */
    private fun String?.toNonBlankValue(): String = this?.takeIf(String::isNotBlank) ?: UNKNOWN_VALUE

    private companion object {
        const val UNKNOWN_VALUE = "unknown"
    }
}
