package com.example.ai_assist.connection

import com.example.ai_assist.AppConfig
import com.example.ai_assist.model.ConnectionConfig
import com.example.ai_assist.model.ServiceMode

class CloudServiceConnector(
    private val readinessProbe: ServiceReadinessProbe,
    private val cloudBaseUrl: String = AppConfig.CLOUD_SERVER_BASE_URL
) {
    suspend fun connect(sceneId: String): Result<ConnectionConfig> = runCatching {
        require(sceneId.isNotBlank()) { "sceneId must not be blank" }
        val normalized = ServerConnectionQrParser.normalizeBaseUrl(cloudBaseUrl)
        check(readinessProbe.isReady(normalized)) { "Cloud service is not ready" }
        ConnectionConfig(
            serverUrl = normalized,
            sceneId = sceneId,
            serviceMode = ServiceMode.CLOUD,
            label = "云端服务"
        )
    }
}
