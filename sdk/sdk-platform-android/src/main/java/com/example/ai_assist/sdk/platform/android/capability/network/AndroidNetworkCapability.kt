package com.example.ai_assist.sdk.platform.android.capability.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.ai_assist.sdk.capability.NetworkCapability

/** 使用 Android ConnectivityManager 提供网络可用性。 */
internal class AndroidNetworkCapability(context: Context) : NetworkCapability {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    override fun isNetworkAvailable(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun supportedFeatures(): Set<String> = setOf("connectivity-status")
}
