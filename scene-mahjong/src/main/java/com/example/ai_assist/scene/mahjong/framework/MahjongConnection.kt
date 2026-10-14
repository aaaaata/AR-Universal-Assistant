package com.example.ai_assist.scene.mahjong.framework

import android.content.Context
import android.net.Uri
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class MahjongConnectionConfig(
    val version: Int = 1,
    val serverUrl: String,
    val fps: Int = 1,
    val label: String? = null
)

internal class MahjongConnectionConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): MahjongConnectionConfig? {
        prefs.getString(KEY_CONNECTION_CONFIG, null)?.let { return parsePersisted(it) }
        val legacyRaw = legacyPrefs.getString(KEY_CONNECTION_CONFIG, null) ?: return null
        return parsePersisted(legacyRaw)?.also(::save)
    }

    fun save(config: MahjongConnectionConfig) {
        prefs.edit().putString(KEY_CONNECTION_CONFIG, toJson(config)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_CONNECTION_CONFIG).apply()
        legacyPrefs.edit().remove(KEY_CONNECTION_CONFIG).apply()
    }

    fun parseQrConnection(raw: String): MahjongConnectionConfig? = runCatching {
        val uri = Uri.parse(raw.trim())
        require(uri.scheme.equals(QR_SCHEME, ignoreCase = true))
        require(uri.host.equals(QR_HOST, ignoreCase = true))
        require(uri.getQueryParameter("version") == SUPPORTED_VERSION.toString())
        require(uri.getQueryParameter("scene") == MAHJONG_SCENE)
        MahjongConnectionConfig(
            version = SUPPORTED_VERSION,
            serverUrl = normalizeAndValidateServerUrl(
                requireNotNull(uri.getQueryParameter("base_url"))
            ),
            fps = DEFAULT_CAPTURE_FPS,
            label = "本地服务"
        )
    }.getOrNull()

    private fun parsePersisted(raw: String): MahjongConnectionConfig? = runCatching {
        val json = JSONObject(raw)
        MahjongConnectionConfig(
            version = json.optInt("version", SUPPORTED_VERSION),
            serverUrl = normalizeAndValidateServerUrl(json.getString("serverUrl")),
            fps = json.optInt("fps", DEFAULT_CAPTURE_FPS).coerceIn(1, MAX_CAPTURE_FPS),
            label = json.optString("label").takeIf(String::isNotBlank)
        )
    }.getOrNull()

    private fun toJson(config: MahjongConnectionConfig): String = JSONObject().apply {
        put("version", config.version)
        put("serverUrl", normalizeAndValidateServerUrl(config.serverUrl))
        put("fps", config.fps)
        config.label?.let { put("label", it) }
    }.toString()

    private fun normalizeAndValidateServerUrl(raw: String): String {
        val value = raw.trim()
        val uri = URI(value)
        require(uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true))
        require(!uri.host.isNullOrBlank())
        require(uri.userInfo == null)
        require(uri.rawQuery == null)
        require(uri.rawFragment == null)
        return if (value.endsWith('/')) value else "$value/"
    }

    private companion object {
        const val PREFS_NAME = "mahjong_scene_prefs"
        const val LEGACY_PREFS_NAME = "ai_assist_prefs"
        const val KEY_CONNECTION_CONFIG = "connection_config_json"
        const val QR_SCHEME = "rayneo-assist"
        const val QR_HOST = "configure"
        const val SUPPORTED_VERSION = 1
        const val MAHJONG_SCENE = "mahjong"
        const val DEFAULT_CAPTURE_FPS = 1
        const val MAX_CAPTURE_FPS = 5
    }
}

internal class MahjongServiceReadinessProbe {
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun isReady(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val root = baseUrl.toHttpUrlOrNull() ?: return@runCatching false
            val url = root.resolve("health/ready") ?: return@runCatching false
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body?.string() ?: return@use false
                JSONObject(body).optString("status") == "ready"
            }
        }.getOrDefault(false)
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
