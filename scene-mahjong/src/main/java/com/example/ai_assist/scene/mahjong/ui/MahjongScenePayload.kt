package com.example.ai_assist.scene.mahjong.ui

import com.example.ai_assist.sdk.scene.ScenePayload
import com.example.ai_assist.scene.mahjong.scene.MahjongServiceMode

/** Renderer 使用的麻将场景结果。 */
data class MahjongScenePayload(
    val userHand: List<String>,
    val meldedTiles: List<String>,
    val suggestedDiscard: String?,
    val canHu: Boolean,
    val jinzhangTiles: List<String>,
    val pengTiles: List<String>,
    val gangTiles: List<String>,
    val shanten: Int?,
    val recommendationStrength: RecommendationStrength,
    val serviceMode: MahjongServiceMode
) : ScenePayload {
    companion object {
        /** 麻将场景结果使用的稳定 Renderer 标识。 */
        const val RENDERER_KEY = "mahjong.result.v1"

        /** 尚未收到分析结果时使用的空内容。 */
        val EMPTY = MahjongScenePayload(
            userHand = emptyList(),
            meldedTiles = emptyList(),
            suggestedDiscard = null,
            canHu = false,
            jinzhangTiles = emptyList(),
            pengTiles = emptyList(),
            gangTiles = emptyList(),
            shanten = null,
            recommendationStrength = RecommendationStrength.WEAK,
            serviceMode = MahjongServiceMode.CLOUD
        )
    }
}

enum class RecommendationStrength(val label: String) {
    STRONG("强建议"),
    NORMAL("一般建议"),
    WEAK("弱建议");

    companion object {
        fun fromConfidence(confidence: Double?): RecommendationStrength = when {
            confidence != null && confidence >= 0.7 -> STRONG
            confidence != null && confidence >= 0.5 -> NORMAL
            else -> WEAK
        }
    }
}
