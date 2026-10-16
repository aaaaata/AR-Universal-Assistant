package com.example.ai_assist.scene.mahjong.scene

import com.example.ai_assist.scene.mahjong.network.AnalyzeResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.ai_assist.scene.mahjong.ui.MahjongScenePayload
import com.example.ai_assist.scene.mahjong.ui.RecommendationStrength

class MahjongResultMapperTest {
    /** 固定当前云端麻将分析 JSON 字段映射。 */
    @Test
    fun parsesCurrentMahjongAnalysisPayload() {
        val response = Gson().fromJson(RESPONSE_JSON, AnalyzeResponse::class.java)

        assertEquals("analysis-1", response.analysisId)
        assertEquals(listOf("1m", "2m"), response.handTiles)
        assertEquals("pon", response.meldGroups.single().type)
        assertEquals("2m", response.suggestion?.suggestion?.tile)
        assertEquals(listOf("1m", "4m"), response.suggestion?.jinzhangTiles)
        assertEquals(0.92, response.confidence?.recognition ?: 0.0, 0.0)
        assertTrue(response.confidence?.reasons?.contains("stable") == true)
        assertEquals(listOf("review_hand"), response.warnings)
        assertEquals(false, response.hasLowConfidenceHandTileDetection)
    }

    /** 固定紧凑牌面、字牌和未知文本的迁移前映射行为。 */
    @Test
    fun mapsCurrentTileNotation() {
        assertEquals(
            tile(0x1F008) + tile(0x1F009),
            MahjongResultMapper.mapToUnicode("23m")
        )
        assertEquals(
            "wait " + tile(0x1F000) + tile(0x1F006) + tile(0x1F004),
            MahjongResultMapper.mapToUnicode("wait 1z5z7z")
        )
        assertEquals(
            listOf(tile(0x1F007), tile(0x1F019), "unknown"),
            MahjongResultMapper.mapListToUnicode(listOf("1m", "1p", "unknown"))
        )
    }

    @Test
    fun mapsLiveHudDataAndLimitsJinzhangToFive() {
        val response = Gson().fromJson(HUD_RESPONSE_JSON, AnalyzeResponse::class.java)

        val result = MahjongResultMapper.toSceneResult(
            response,
            "mahjong-guangdong",
            "session",
            1L,
            MahjongServiceMode.LOCAL
        )
        val payload = result.content?.scenePayload as MahjongScenePayload

        assertEquals(14, payload.userHand.size)
        assertEquals(tile(0x1F007), payload.suggestedDiscard)
        assertTrue(payload.canHu)
        assertEquals(5, payload.jinzhangTiles.size)
        assertEquals(listOf(tile(0x1F007)), payload.gangTiles)
        assertTrue(payload.pengTiles.contains(tile(0x1F007)))
        assertEquals(1, payload.shanten)
        assertEquals(RecommendationStrength.STRONG, payload.recommendationStrength)
        assertEquals(MahjongServiceMode.LOCAL, payload.serviceMode)
    }

    @Test
    fun mapsRecommendationStrengthThresholds() {
        assertEquals(RecommendationStrength.STRONG, RecommendationStrength.fromConfidence(0.7))
        assertEquals(RecommendationStrength.NORMAL, RecommendationStrength.fromConfidence(0.5))
        assertEquals(RecommendationStrength.WEAK, RecommendationStrength.fromConfidence(0.499))
    }

    @Test
    fun mapsDiagnosticCodesToChineseWarnings() {
        val expected = mapOf(
            "TOO_MANY_REGIONS" to "牌区过多",
            "HAND_REGION_NOT_FOUND" to "未找到手牌区",
            "REGION_ASSIGNMENT_AMBIGUOUS" to "牌归属区域不明确",
            "NO_VALID_HAND_TILES" to "未识别到有效手牌",
            "INVALID_MELD_STRUCTURE" to "副露结构不合法",
            "DUPLICATE_TILE_OVER_LIMIT" to "同一张牌超过四张",
            "INVALID_TILE_COUNT" to "牌数不合法"
        )

        expected.forEach { (code, message) ->
            assertEquals(message, MahjongResultMapper.diagnosticMessage(code))
        }
        assertEquals("UNKNOWN_CODE", MahjongResultMapper.diagnosticMessage("UNKNOWN_CODE"))
    }

    /** 将 Unicode 码点转换为测试字符串。 */
    private fun tile(codePoint: Int) = String(Character.toChars(codePoint))

    private companion object {
        val RESPONSE_JSON =
            """
            {
              "analysis_id": "analysis-1",
              "status": "ok",
              "hand_tiles": ["1m", "2m"],
              "meld_groups": [{"type": "pon", "tiles": ["3p", "3p", "3p"]}],
              "bonus_tiles": ["5s"],
              "suggestion": {
                "advice_type": "discard",
                "suggestion": {"tile": "2m", "tingpai_steps": 1, "jinzhang_count": 8},
                "jinzhang_tiles": ["1m", "4m"],
                "can_hu": false
              },
              "confidence": {
                "region": 0.91,
                "recognition": 0.92,
                "structure": 0.93,
                "suggestion": 0.94,
                "reasons": ["stable"]
              },
              "warnings": ["review_hand"],
              "has_low_confidence_hand_tile_detection": false
            }
            """.trimIndent()

        val HUD_RESPONSE_JSON =
            """
            {
              "status": "complete",
              "hand_tiles": ["1m", "1m", "1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "2p", "3p", "4p"],
              "meld_groups": [],
                "suggestion": {
                  "advice_type": "discard",
                  "can_hu": true,
                  "suggestion": {
                  "tile": "1m",
                  "tingpai_steps": 1,
                  "jinzhang_count": 20,
                  "jinzhang_tiles": ["1p", "2p", "3p", "4p", "5p", "6p"]
                }
              },
              "confidence": {"suggestion": 0.7}
            }
            """.trimIndent()
    }
}
