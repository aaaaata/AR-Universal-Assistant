package com.example.ai_assist.sdk.network

import com.example.ai_assist.sdk.engine.SceneConfig
import com.google.gson.annotations.SerializedName
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import retrofit2.http.GET

/** 可由通用宿主在运行时替换服务地址的场景配置。 */
interface ServerEndpointSceneConfig : SceneConfig {
    val serverBaseUrl: String
    val supportedQrSceneIds: Set<String>
    fun withServerBaseUrl(serverBaseUrl: String): ServerEndpointSceneConfig
}

/** 从配置二维码中解析出的场景别名和服务根地址。 */
data class ScannedServerConfig(
    val sceneId: String,
    val baseUrl: String
)

/** 通用客户端服务配置二维码协议。 */
object ServerConnectionQrParser {
    private const val SCHEME = "rayneo-assist"
    private const val ACTION = "configure"
    private const val VERSION = "1"

    fun parse(rawValue: String): ScannedServerConfig {
        val uri = URI(rawValue.trim())
        require(uri.scheme.equals(SCHEME, ignoreCase = true)) { "Unsupported QR scheme" }
        require(uri.host.equals(ACTION, ignoreCase = true)) { "Unsupported QR action" }
        val parameters = parseQuery(uri.rawQuery)
        require(parameters["version"] == VERSION) { "Unsupported QR version" }
        val sceneId = requireNotNull(parameters["scene"]?.takeIf(String::isNotBlank)) {
            "Missing scene"
        }
        val baseUrl = requireNotNull(parameters["base_url"]) { "Missing base_url" }
        return ScannedServerConfig(sceneId, normalizeBaseUrl(baseUrl))
    }

    fun normalizeBaseUrl(value: String): String {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Only HTTP or HTTPS is supported" }
        require(!uri.host.isNullOrBlank()) { "Server host is required" }
        require(uri.userInfo == null) { "Credentials are not allowed in server URL" }
        require(uri.query == null && uri.fragment == null) {
            "Query and fragment are not allowed in server URL"
        }
        val path = when {
            uri.path.isNullOrBlank() -> "/"
            uri.path.endsWith('/') -> uri.path
            else -> "${uri.path}/"
        }
        return URI(scheme, null, uri.host, uri.port, path, null, null).toASCIIString()
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        val result = linkedMapOf<String, String>()
        rawQuery.orEmpty().split('&').filter(String::isNotBlank).forEach { part ->
            val pieces = part.split('=', limit = 2)
            val key = decode(pieces[0])
            require(key !in result) { "Duplicate QR parameter: $key" }
            result[key] = decode(pieces.getOrElse(1) { "" })
        }
        return result
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

/** 使用 sdk-network 的统一 Retrofit 工厂验证目标服务是否就绪。 */
class ServerReadinessValidator(
    private val networkServiceFactory: NetworkServiceFactory = RetrofitNetworkServiceFactory,
    private val timeoutMillis: Long = DEFAULT_HEALTH_TIMEOUT_MILLIS
) {
    suspend fun isReady(baseUrl: String): Boolean = runCatching {
        val normalized = ServerConnectionQrParser.normalizeBaseUrl(baseUrl)
        networkServiceFactory.create(
            NetworkClientConfig(normalized, timeoutMillis),
            HealthReadyApi::class.java
        ).healthReady().status.equals("ready", ignoreCase = true)
    }.getOrDefault(false)

    private interface HealthReadyApi {
        @GET("health/ready")
        suspend fun healthReady(): HealthReadyResponse
    }

    private data class HealthReadyResponse(
        @SerializedName("status") val status: String = ""
    )

    private companion object {
        const val DEFAULT_HEALTH_TIMEOUT_MILLIS = 5_000L
    }
}
