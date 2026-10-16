package com.example.ai_assist.sdk.capability

/** 提供与具体传输实现无关的网络可用性查询。 */
interface NetworkCapability : SdkCapability {
    /** 当前是否存在可用于业务通信的活动网络。 */
    fun isNetworkAvailable(): Boolean

    override fun isAvailable(): Boolean = isNetworkAvailable()
}
