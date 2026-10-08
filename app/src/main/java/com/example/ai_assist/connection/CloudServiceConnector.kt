package com.example.ai_assist.connection

import com.example.ai_assist.AppConfig
import com.example.ai_assist.model.ConnectionConfig
import com.example.ai_assist.model.ServiceMode

class CloudServiceConnector(
    private val readinessProbe: ServiceReadinessProbe,
    private val cloudBaseUrl: String = AppConfig.CLOUD_SERVER_BASE_URL
) {
    suspend fun connect(): Result<ConnectionConfig> = runCatching {
        val normalized = ServerConnectionQrParser.normalizeBaseUrl(cloudBaseUrl)
        check(readinessProbe.isReady(normalized)) { "Cloud service is not ready" }
        ConnectionConfig(
            serverUrl = normalized,
            sceneId = "mahjong",
            serviceMode = ServiceMode.CLOUD,
            label = "云端麻将服务"
        )
    }
}
