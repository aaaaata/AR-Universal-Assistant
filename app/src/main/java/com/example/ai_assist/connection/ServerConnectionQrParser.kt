package com.example.ai_assist.connection

import com.example.ai_assist.model.ConnectionConfig
import com.example.ai_assist.model.ServiceMode
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ServerConnectionQrParser {
    private const val SCHEME = "rayneo-assist"
    private const val ACTION = "configure"
    private const val VERSION = "1"
    private val supportedScenes = setOf("mahjong", "mahjong-guangdong")

    fun parse(rawValue: String): ConnectionConfig {
        val uri = URI(rawValue.trim())
        require(uri.scheme.equals(SCHEME, ignoreCase = true)) { "Unsupported QR scheme" }
        require(uri.host.equals(ACTION, ignoreCase = true)) { "Unsupported QR action" }

        val parameters = parseQuery(uri.rawQuery)
        require(parameters["version"] == VERSION) { "Unsupported QR version" }
        val sceneId = requireNotNull(parameters["scene"]?.takeIf(String::isNotBlank)) {
            "Missing scene"
        }
        require(sceneId in supportedScenes) { "Unsupported scene" }
        val baseUrl = requireNotNull(parameters["base_url"]) { "Missing base_url" }

        return ConnectionConfig(
            version = VERSION.toInt(),
            serverUrl = normalizeBaseUrl(baseUrl),
            sceneId = sceneId,
            serviceMode = ServiceMode.LOCAL,
            label = "本地麻将服务"
        )
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
