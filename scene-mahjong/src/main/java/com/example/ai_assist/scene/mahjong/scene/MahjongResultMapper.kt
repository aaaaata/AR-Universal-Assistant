package com.example.ai_assist.scene.mahjong.scene

import com.example.ai_assist.scene.mahjong.network.AnalyzeResponse
import com.example.ai_assist.scene.mahjong.ui.MahjongScenePayload
import com.example.ai_assist.sdk.scene.SceneContent
import com.example.ai_assist.sdk.scene.SceneResult
import com.example.ai_assist.sdk.scene.SceneSummary
import com.example.ai_assist.scene.mahjong.ui.RecommendationStrength

internal object MahjongResultMapper {
    private val tileMap = buildMap {
        (1..9).forEach { put("${it}m", tile(0x1F006 + it)) }
        (1..9).forEach { put("${it}p", tile(0x1F018 + it)) }
        (1..9).forEach { put("${it}s", tile(0x1F00F + it)) }
        put("1z", tile(0x1F000))
        put("2z", tile(0x1F001))
        put("3z", tile(0x1F002))
        put("4z", tile(0x1F003))
        put("5z", tile(0x1F006))
        put("6z", tile(0x1F005))
        put("7z", tile(0x1F004))
    }

    /** 将云端分析响应转换为 SDK 场景结果。 */
    fun toSceneResult(
        response: AnalyzeResponse,
        sceneId: String,
        sessionId: String,
        updatedAtMillis: Long,
        serviceMode: MahjongServiceMode = MahjongServiceMode.CLOUD
    ): SceneResult {
        val meldTiles = response.meldGroups.flatMap { it.tiles }
        val advice = response.suggestion
        val counts = response.handTiles.groupingBy { it }.eachCount()
        val jinzhangTiles = when (advice?.adviceType) {
            "discard" -> advice.suggestion?.jinzhangTiles.orEmpty()
            else -> advice?.jinzhangTiles.orEmpty()
        }
        val payload = MahjongScenePayload(
            userHand = mapListToUnicode(response.handTiles),
            meldedTiles = mapListToUnicode(meldTiles),
            suggestedDiscard = advice?.takeIf { it.adviceType == "discard" }
                ?.suggestion?.tile?.takeIf(String::isNotBlank)?.let(::mapToUnicode),
            canHu = advice?.canHu == true,
            jinzhangTiles = mapListToUnicode(jinzhangTiles.take(5)),
            pengTiles = mapListToUnicode(
                counts.filterValues { it >= 2 }.keys.sortedWith(tileCodeComparator)
            ),
            gangTiles = mapListToUnicode(
                counts.filterValues { it >= 3 }.keys.sortedWith(tileCodeComparator)
            ),
            shanten = advice?.suggestion?.tingpaiSteps ?: advice?.tingpaiSteps,
            recommendationStrength = RecommendationStrength.fromConfidence(
                response.confidence?.suggestion
            ),
            serviceMode = serviceMode
        )
        return SceneResult(
            sceneId = sceneId,
            sessionId = sessionId,
            updatedAtMillis = updatedAtMillis,
            summary = SceneSummary(
                title = payload.suggestedDiscard?.let { "建议打出：$it" } ?: "麻将分析完成",
                status = response.status.ifBlank { "unknown" },
                confidence = response.confidence?.suggestion?.coerceIn(0.0, 1.0),
                warnings = response.warnings + response.confidence?.reasons.orEmpty()
            ),
            content = SceneContent(MahjongScenePayload.RENDERER_KEY, payload)
        )
    }

    /** 将紧凑牌面编码和普通文本转换为 Unicode 牌面文本。 */
    fun mapToUnicode(text: String): String {
        val expanded = Regex("([0-9]+)([mpsz])").replace(text) { match ->
            match.groupValues[1].map { "${it}${match.groupValues[2]}" }.joinToString("")
        }
        return tileMap.entries.fold(expanded) { result, (code, unicode) ->
            result.replace(code, unicode)
        }
    }

    /** 分别转换列表中的每个牌面编码。 */
    fun mapListToUnicode(codes: List<String>): List<String> = codes.map(::mapToUnicode)

    private val tileCodeComparator = compareBy<String>(
        { "mpsz".indexOf(it.lastOrNull() ?: 'z').let { index -> if (index < 0) 4 else index } },
        { it.dropLast(1).toIntOrNull() ?: Int.MAX_VALUE }
    )

    internal fun diagnosticMessage(code: String): String = when (code) {
        "TOO_MANY_REGIONS" -> "牌区过多"
        "HAND_REGION_NOT_FOUND" -> "未找到手牌区"
        "REGION_ASSIGNMENT_AMBIGUOUS" -> "牌归属区域不明确"
        "NO_VALID_HAND_TILES" -> "未识别到有效手牌"
        "INVALID_MELD_STRUCTURE" -> "副露结构不合法"
        "DUPLICATE_TILE_OVER_LIMIT" -> "同一张牌超过四张"
        "INVALID_TILE_COUNT" -> "牌数不合法"
        else -> code
    }

    /** 将麻将 Unicode 码点转换为字符串。 */
    private fun tile(codePoint: Int): String = String(Character.toChars(codePoint))
}
