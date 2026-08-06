package com.example.ai_assist.model

import com.google.gson.annotations.SerializedName

data class AnalyzeResponse(
    @SerializedName("user_hand") val userHand: List<String>,
    @SerializedName("melded_tiles") val meldedTiles: List<String> = emptyList(),
    @SerializedName("suggested_play") val suggestedPlay: String,
    @SerializedName("rule_profile") val ruleProfile: String? = null,
    @SerializedName("recognition_confidence") val recognitionConfidence: Double? = null,
    @SerializedName("suggestion_confidence") val suggestionConfidence: Double? = null,
    @SerializedName("annotated_image_path") val annotatedImagePath: String? = null,
    @SerializedName("action_detected") val actionDetected: String? = null,
    @SerializedName("warning") val warning: String? = null,
    @SerializedName("is_stable") val isStable: Boolean = true
)
