package com.example.ai_assist.sdk.scene

/**
 * 场景发布给 SDK 的一次完整结果。
 *
 * @property sceneId 结果所属场景 ID。
 * @property sessionId 结果所属会话 ID。
 * @property updatedAtMillis 结果更新时间戳，单位毫秒。
 * @property summary 通用壳可直接展示的摘要。
 * @property content 交给场景 Renderer 的可选业务内容。
 */
data class SceneResult(
    val sceneId: String,
    val sessionId: String,
    val updatedAtMillis: Long,
    val summary: SceneSummary,
    val content: SceneContent? = null
) {
    init {
        require(sceneId.isNotBlank()) { "sceneId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(updatedAtMillis >= 0) { "updatedAtMillis must not be negative" }
    }
}

/**
 * 通用壳可理解的场景结果摘要。
 *
 * @property title 面向用户的一句话摘要。
 * @property status 场景定义的结果状态。
 * @property confidence 可选置信度，范围为 0 到 1。
 * @property warnings 可恢复问题或提示列表。
 */
data class SceneSummary(
    val title: String,
    val status: String,
    val confidence: Double? = null,
    val warnings: List<String> = emptyList()
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(status.isNotBlank()) { "status must not be blank" }
        require(confidence == null || confidence in 0.0..1.0) {
            "confidence must be between 0 and 1"
        }
    }
}
