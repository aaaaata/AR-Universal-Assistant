package com.example.ai_assist.storage

import android.content.Context
import com.example.ai_assist.AppConfig
import com.example.ai_assist.connection.ServerConnectionQrParser
import com.example.ai_assist.model.ConnectionConfig
import com.example.ai_assist.model.ServiceMode
import org.json.JSONObject

class ConnectionConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ConnectionConfig? {
        val raw = prefs.getString(AppConfig.KEY_CONNECTION_CONFIG, null) ?: return null
        return parse(raw)
    }

    fun save(config: ConnectionConfig) {
        prefs.edit().putString(AppConfig.KEY_CONNECTION_CONFIG, toJson(config)).apply()
    }

    fun clear() {
        prefs.edit().remove(AppConfig.KEY_CONNECTION_CONFIG).apply()
    }

    fun parse(raw: String): ConnectionConfig? {
        if (raw.trim().startsWith("rayneo-assist://", ignoreCase = true)) {
            return runCatching { ServerConnectionQrParser.parse(raw) }.getOrNull()
        }
        return runCatching {
            val json = JSONObject(raw)
            val serverUrl = normalizeUrl(json.getString("serverUrl"))
            ConnectionConfig(
                version = json.optInt("version", 1),
                serverUrl = serverUrl,
                sceneId = json.getString("sceneId").also {
                    require(it.isNotBlank()) { "sceneId must not be blank" }
                },
                serviceMode = ServiceMode.fromWireValue(json.optString("serviceMode", "local")),
                streamUrl = json.optString("streamUrl").takeIf { it.isNotBlank() },
                fps = json.optInt("fps", AppConfig.DEFAULT_CAPTURE_FPS).coerceIn(1, AppConfig.MAX_CAPTURE_FPS),
                sessionKey = json.optString("sessionKey").takeIf { it.isNotBlank() },
                label = json.optString("label").takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private fun toJson(config: ConnectionConfig): String {
        return JSONObject().apply {
            put("version", config.version)
            put("serverUrl", normalizeUrl(config.serverUrl))
            put("sceneId", config.sceneId)
            put("serviceMode", config.serviceMode.wireValue)
            config.streamUrl?.let { put("streamUrl", it) }
            put("fps", config.fps)
            config.sessionKey?.let { put("sessionKey", it) }
            config.label?.let { put("label", it) }
        }.toString()
    }

    private fun normalizeUrl(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }
}
