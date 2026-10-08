package com.example.ai_assist.scene.mahjong.framework

/** 将完整云端响应映射为旧版 HUD 使用的数据，同时保持新框架 ScenePayload 契约。 */
class DefaultMahjongFieldMapper : MahjongFieldMapper {
    override fun map(cloudResult: MahjongCloudResult): MahjongScenePayload {
        val response = cloudResult as? MahjongAnalysisResponse
            ?: throw IllegalArgumentException("Unsupported MahjongCloudResult implementation")
        val advice = response.suggestion
        val counts = response.handTiles.groupingBy { it }.eachCount()
        val jinzhangTiles = when (advice?.adviceType) {
            "discard" -> advice.suggestion?.jinzhangTiles.orEmpty()
            else -> advice?.jinzhangTiles.orEmpty()
        }
        return MahjongScenePayload(
            userHand = response.handTiles.map(::parseTile),
            meldedTiles = response.meldGroups.flatMap { it.tiles }.map(::parseTile),
            suggestedDiscard = advice?.takeIf { it.adviceType == "discard" }
                ?.suggestion?.tile?.takeIf(String::isNotBlank)?.let(::parseTile),
            jinzhangTiles = jinzhangTiles.take(MAX_JINZHANG_TILES).map(::parseTile),
            pengTiles = counts.filterValues { it >= 2 }.keys
                .sortedWith(TILE_CODE_COMPARATOR).map(::parseTile),
            gangTiles = counts.filterValues { it >= 3 }.keys
                .sortedWith(TILE_CODE_COMPARATOR).map(::parseTile),
            shanten = advice?.suggestion?.tingpaiSteps ?: advice?.tingpaiSteps,
            recommendationStrength = RecommendationStrength.fromConfidence(
                response.confidence?.suggestion
            ),
            serviceMode = MahjongServiceMode.CLOUD
        )
    }

    override fun parseTile(raw: String): String {
        val expanded = Regex("([0-9]+)([mpsz])").replace(raw) { match ->
            match.groupValues[1].map { "${it}${match.groupValues[2]}" }.joinToString("")
        }
        return TILE_CODES.entries.fold(expanded) { value, entry ->
            value.replace(entry.key, entry.value)
        }
    }

    override fun diagnosticMessage(code: String): String = when (code) {
        "TOO_MANY_REGIONS" -> "牌区过多"
        "HAND_REGION_NOT_FOUND" -> "未找到手牌区"
        "REGION_ASSIGNMENT_AMBIGUOUS" -> "牌归属区域不明确"
        "NO_VALID_HAND_TILES" -> "未识别到有效手牌"
        "INVALID_MELD_STRUCTURE" -> "副露结构不合法"
        "DUPLICATE_TILE_OVER_LIMIT" -> "同一张牌超过四张"
        "INVALID_TILE_COUNT" -> "牌数不合法"
        else -> code
    }

    private companion object {
        val TILE_CODES: Map<String, String> = buildMap {
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

        val TILE_CODE_COMPARATOR = compareBy<String>(
            { "mpsz".indexOf(it.lastOrNull() ?: 'z').let { index -> if (index < 0) 4 else index } },
            { it.dropLast(1).toIntOrNull() ?: Int.MAX_VALUE }
        )

        fun tile(codePoint: Int): String = String(Character.toChars(codePoint))

        const val MAX_JINZHANG_TILES = 5
    }
}
