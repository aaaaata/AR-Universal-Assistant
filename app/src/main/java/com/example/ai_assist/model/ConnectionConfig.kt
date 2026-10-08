package com.example.ai_assist.model

data class ConnectionConfig(
    val version: Int = 1,
    val serverUrl: String,
    val sceneId: String = "mahjong",
    val streamUrl: String? = null,
    val fps: Int = 1,
    val sessionKey: String? = null,
    val label: String? = null
)
