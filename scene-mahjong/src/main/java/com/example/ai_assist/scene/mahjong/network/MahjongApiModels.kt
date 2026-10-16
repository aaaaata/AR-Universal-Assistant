package com.example.ai_assist.scene.mahjong.network

import com.google.gson.annotations.SerializedName

/** 创建麻将服务端会话的请求。 */
internal data class StartSessionRequest(
    @SerializedName("rule_profile_id") val ruleProfileId: String,
    @SerializedName("recording_mode") val recordingMode: String
)

/** 创建麻将服务端会话的响应。 */
internal data class StartSessionResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("rule_profile_id") val ruleProfileId: String
)

/** 结束麻将服务端会话的响应。 */
internal data class EndSessionResponse(
    @SerializedName("status") val status: String,
    @SerializedName("session_id") val sessionId: String
)

data class MeldGroup(
    @SerializedName("type") val type: String = "unknown",
    @SerializedName("tiles") val tiles: List<String> = emptyList()
)

data class AdviceCandidate(
    @SerializedName("tile") val tile: String = "",
    @SerializedName("tingpai_steps") val tingpaiSteps: Int = 0,
    @SerializedName("jinzhang_count") val jinzhangCount: Int = 0,
    @SerializedName("jinzhang_tiles") val jinzhangTiles: List<String> = emptyList()
)

data class SuggestionPayload(
    @SerializedName("advice_type") val adviceType: String = "",
    @SerializedName("suggestion") val suggestion: AdviceCandidate? = null,
    @SerializedName("tingpai_steps") val tingpaiSteps: Int? = null,
    @SerializedName("jinzhang_count") val jinzhangCount: Int? = null,
    @SerializedName("jinzhang_tiles") val jinzhangTiles: List<String> = emptyList(),
    @SerializedName("can_hu") val canHu: Boolean = false
)

data class ConfidencePayload(
    @SerializedName("region") val region: Double = 0.0,
    @SerializedName("recognition") val recognition: Double = 0.0,
    @SerializedName("structure") val structure: Double = 0.0,
    @SerializedName("suggestion") val suggestion: Double = 0.0,
    @SerializedName("reasons") val reasons: List<String> = emptyList()
)

data class ImagePayload(
    @SerializedName("width") val width: Int = 0,
    @SerializedName("height") val height: Int = 0
)

data class BoundingBoxPayload(
    @SerializedName("x1") val x1: Double = 0.0,
    @SerializedName("y1") val y1: Double = 0.0,
    @SerializedName("x2") val x2: Double = 0.0,
    @SerializedName("y2") val y2: Double = 0.0
)

data class TileDetectionPayload(
    @SerializedName("region_type") val regionType: String = "",
    @SerializedName("tile_confidence") val tileConfidence: Double = 0.0,
    @SerializedName("bbox_in_original_image")
    val bboxInOriginalImage: BoundingBoxPayload? = null
)

/** 麻将单帧分析接口的完整响应。 */
data class AnalyzeResponse(
    @SerializedName("analysis_id") val analysisId: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("diagnostic_code") val diagnosticCode: String? = null,
    @SerializedName("hand_tiles") val handTiles: List<String> = emptyList(),
    @SerializedName("meld_groups") val meldGroups: List<MeldGroup> = emptyList(),
    @SerializedName("bonus_tiles") val bonusTiles: List<String> = emptyList(),
    @SerializedName("suggestion") val suggestion: SuggestionPayload? = null,
    @SerializedName("confidence") val confidence: ConfidencePayload? = null,
    @SerializedName("warnings") val warnings: List<String> = emptyList(),
    @SerializedName("image") val image: ImagePayload? = null,
    @SerializedName("tile_detections")
    val tileDetections: List<TileDetectionPayload> = emptyList(),
    @SerializedName("has_low_confidence_hand_tile_detection")
    val hasLowConfidenceHandTileDetection: Boolean? = null
)
