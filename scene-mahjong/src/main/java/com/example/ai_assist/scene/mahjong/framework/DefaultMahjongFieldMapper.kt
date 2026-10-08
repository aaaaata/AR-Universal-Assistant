package com.example.ai_assist.scene.mahjong.framework

/** 当前服务端响应到新框架 MahjongScenePayload 契约的映射。 */
class DefaultMahjongFieldMapper : MahjongFieldMapper {
    override fun map(cloudResult: MahjongCloudResult): MahjongScenePayload {
        val response = cloudResult as? MahjongAnalysisResponse
            ?: throw IllegalArgumentException("Unsupported MahjongCloudResult implementation")
        val suggestedPlay = when {
            response.suggestion?.canHu == true -> "胡"
            response.suggestion?.adviceType == "discard" ->
                response.suggestion?.suggestion?.tile.orEmpty()
            else -> response.suggestion?.suggestion?.tile.orEmpty()
        }
        return MahjongScenePayload(
            userHand = response.handTiles.map(::parseTile),
            meldedTiles = response.meldGroups.flatMap { it.tiles }.map(::parseTile),
            suggestedPlay = parseTile(suggestedPlay)
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

        fun tile(codePoint: Int): String = String(Character.toChars(codePoint))
    }
}
