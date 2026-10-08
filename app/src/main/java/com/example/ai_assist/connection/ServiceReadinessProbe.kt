package com.example.ai_assist.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

fun interface ServiceReadinessProbe {
    suspend fun isReady(baseUrl: String): Boolean
}

class HttpServiceReadinessProbe(
    private val client: OkHttpClient
) : ServiceReadinessProbe {
    override suspend fun isReady(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = ServerConnectionQrParser.normalizeBaseUrl(baseUrl)
            val request = Request.Builder()
                .url(normalized + "health/ready")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                JSONObject(response.body?.string().orEmpty())
                    .optString("status")
                    .equals("ready", ignoreCase = true)
            }
        }.getOrDefault(false)
    }
}
