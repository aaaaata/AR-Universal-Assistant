package com.example.ai_assist.model

enum class ServiceMode(val wireValue: String) {
    LOCAL("local"),
    CLOUD("cloud");

    companion object {
        fun fromWireValue(value: String?): ServiceMode =
            entries.firstOrNull { it.wireValue == value?.lowercase() } ?: LOCAL
    }
}

data class ConnectionConfig(
    val version: Int = 1,
    val serverUrl: String,
    val sceneId: String = "mahjong",
    val serviceMode: ServiceMode = ServiceMode.LOCAL,
    val streamUrl: String? = null,
    val fps: Int = 1,
    val sessionKey: String? = null,
    val label: String? = null
)
