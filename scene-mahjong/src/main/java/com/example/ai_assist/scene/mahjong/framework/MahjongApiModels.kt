package com.example.ai_assist.scene.mahjong.framework

import com.google.gson.annotations.SerializedName

internal data class CreateMahjongSessionRequest(
    @SerializedName("rule_profile_id") val ruleProfileId: String,
    @SerializedName("recording_mode") val recordingMode: String
)

internal data class CreateMahjongSessionResponse(
    @SerializedName("session_id") val sessionId: String
)

internal data class EndMahjongSessionResponse(
    @SerializedName("status") val status: String,
    @SerializedName("session_id") val sessionId: String
)

data class MahjongMeldGroup(
    @SerializedName("type") val type: String = "",
    @SerializedName("tiles") val tiles: List<String> = emptyList()
)

data class MahjongAdviceCandidate(
    @SerializedName("tile") val tile: String = "",
    @SerializedName("tingpai_steps") val tingpaiSteps: Int = 0,
    @SerializedName("jinzhang_count") val jinzhangCount: Int = 0,
    @SerializedName("jinzhang_tiles") val jinzhangTiles: List<String> = emptyList()
)

data class MahjongSuggestion(
    @SerializedName("advice_type") val adviceType: String = "",
    @SerializedName("suggestion") val suggestion: MahjongAdviceCandidate? = null,
    @SerializedName("tingpai_steps") val tingpaiSteps: Int? = null,
    @SerializedName("jinzhang_count") val jinzhangCount: Int? = null,
    @SerializedName("jinzhang_tiles") val jinzhangTiles: List<String> = emptyList(),
    @SerializedName("can_hu") val canHu: Boolean = false
)

data class MahjongConfidence(
    @SerializedName("suggestion") val suggestion: Double = 0.0,
    @SerializedName("reasons") val reasons: List<String> = emptyList()
)

/** 服务端单帧分析响应，也是 MahjongCloudResult 契约的协议实现。 */
data class MahjongAnalysisResponse(
    @SerializedName("analysis_id") val analysisId: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("diagnostic_code") val diagnosticCode: String? = null,
    @SerializedName("hand_tiles") val handTiles: List<String> = emptyList(),
    @SerializedName("meld_groups") val meldGroups: List<MahjongMeldGroup> = emptyList(),
    @SerializedName("suggestion") val suggestion: MahjongSuggestion? = null,
    @SerializedName("confidence") val confidence: MahjongConfidence? = null,
    @SerializedName("warnings") val warnings: List<String> = emptyList()
) : MahjongCloudResult
