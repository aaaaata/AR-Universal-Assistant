package com.example.ai_assist.sdk.network

import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** 通用 HTTP 客户端配置，不包含任何场景业务字段或凭据。 */
data class NetworkClientConfig(
    val baseUrl: String,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(baseUrl.endsWith('/')) { "baseUrl must end with /" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}

/** 每个请求共用的非敏感追踪信息。 */
data class RequestMetadata(
    val requestId: String,
    val createdAtMillis: Long
)

/** 生成请求标识和时间戳，便于场景协议添加诊断请求头。 */
fun interface RequestMetadataProvider {
    fun create(): RequestMetadata
}

/** SDK 默认请求元数据生成器。 */
object DefaultRequestMetadataProvider : RequestMetadataProvider {
    override fun create() = RequestMetadata(
        requestId = UUID.randomUUID().toString(),
        createdAtMillis = System.currentTimeMillis()
    )
}

/** 为场景定义的协议接口创建网络实现。 */
interface NetworkServiceFactory {
    fun <T : Any> create(config: NetworkClientConfig, serviceType: Class<T>): T
}

/** 基于 Retrofit/Gson 的默认通信实现。 */
object RetrofitNetworkServiceFactory : NetworkServiceFactory {
    override fun <T : Any> create(config: NetworkClientConfig, serviceType: Class<T>): T {
        val client = OkHttpClient.Builder()
            .connectTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(serviceType)
    }
}
