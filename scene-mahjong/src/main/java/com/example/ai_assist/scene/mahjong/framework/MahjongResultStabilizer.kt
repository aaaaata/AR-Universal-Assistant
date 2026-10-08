package com.example.ai_assist.scene.mahjong.framework

import com.example.ai_assist.sdk.framework.SceneResult
import java.util.Locale

/** 按牌面变化规律过滤连续识别结果，避免低置信度结果造成显示跳变。 */
internal class MahjongResultStabilizer(
    private val highConfidenceThreshold: Double = HIGH_CONFIDENCE_THRESHOLD
) {
    private var bootstrapCandidateKey: ResultKey? = null
    private var bootstrapCandidateCount = 0
    private var stableKey: ResultKey? = null
    private var provisionalKey: ResultKey? = null
    private var provisionalCount = 0
    private var provisionalPublished = false

    enum class Decision {
        PUBLISH,
        HOLD
    }

    fun evaluate(response: MahjongAnalysisResponse, result: SceneResult): Decision {
        val current = Observation(
            key = resultKey(response, result),
            trusted = isTrusted(response)
        )
        val stable = stableKey
        if (stable == null) {
            recordBootstrapCandidate(current)
            return Decision.PUBLISH
        }
        if (current.key == stable) {
            clearProvisional()
            return Decision.PUBLISH
        }

        val provisional = provisionalKey
        if (provisional != null && current.key == provisional) {
            return recordProvisional(current)
        }
        if (!current.trusted) {
            resetProvisionalStreak()
            return Decision.HOLD
        }

        val hasLowConfidenceHandTileDetection =
            response.hasLowConfidenceHandTileDetection == true
        val followsStable = expectedResultTransition(
            stable,
            current.key,
            hasLowConfidenceHandTileDetection
        ) != null
        val followsProvisional = provisional?.let {
            expectedResultTransition(it, current.key, hasLowConfidenceHandTileDetection)
        } != null
        if (followsStable || followsProvisional) {
            beginProvisional(current.key, published = true)
            return Decision.PUBLISH
        }
        beginProvisional(current.key, published = false)
        return Decision.HOLD
    }

    private fun expectedResultTransition(
        before: ResultKey,
        after: ResultKey,
        hasLowConfidenceHandTileDetection: Boolean
    ): ResultTransition? {
        if (before.status != COMPLETE_STATUS || after.status != COMPLETE_STATUS) return null
        if (isExpectedMeldAddition(before, after)) return ResultTransition.MELD_ADDED
        if (!hasSameMelds(before, after)) return null
        if (effectiveTileCount(before) == 13 && effectiveTileCount(after) == 14 &&
            containsAllTiles(after.userHand, before.userHand)
        ) {
            return ResultTransition.DRAW
        }
        if (effectiveTileCount(before) == 14 && effectiveTileCount(after) == 13 &&
            before.adviceType == DISCARD_ADVICE &&
            !hasLowConfidenceHandTileDetection &&
            containsAllTiles(before.userHand, after.userHand)
        ) {
            return ResultTransition.DISCARD
        }
        return null
    }

    private fun isExpectedMeldAddition(before: ResultKey, after: ResultKey): Boolean =
        after.meldGroupCount == before.meldGroupCount + 1 &&
            containsAllTiles(after.meldedTiles, before.meldedTiles)

    private fun hasSameMelds(before: ResultKey, after: ResultKey): Boolean =
        before.meldGroupCount == after.meldGroupCount &&
            before.kanCount == after.kanCount &&
            tileCounts(before.meldedTiles) == tileCounts(after.meldedTiles)

    private fun effectiveTileCount(result: ResultKey): Int =
        result.userHand.size + result.meldedTiles.size - result.kanCount

    private fun containsAllTiles(container: List<String>, contents: List<String>): Boolean {
        val containerCounts = tileCounts(container)
        return tileCounts(contents).all { (tile, count) ->
            containerCounts.getOrDefault(tile, 0) >= count
        }
    }

    private fun tileCounts(tiles: List<String>): Map<String, Int> = tiles.groupingBy { it }.eachCount()

    private fun recordBootstrapCandidate(observation: Observation) {
        if (!observation.trusted) {
            bootstrapCandidateKey = null
            bootstrapCandidateCount = 0
            return
        }
        if (bootstrapCandidateKey == observation.key) {
            bootstrapCandidateCount++
        } else {
            bootstrapCandidateKey = observation.key
            bootstrapCandidateCount = 1
        }
        if (bootstrapCandidateCount >= REQUIRED_MATCHING_RESULTS) {
            stableKey = observation.key
            bootstrapCandidateKey = null
            bootstrapCandidateCount = 0
        }
    }

    private fun beginProvisional(key: ResultKey, published: Boolean) {
        provisionalKey = key
        provisionalCount = 1
        provisionalPublished = published
    }

    private fun recordProvisional(observation: Observation): Decision {
        if (!observation.trusted) {
            resetProvisionalStreak()
            return Decision.HOLD
        }
        provisionalCount++
        if (provisionalCount >= REQUIRED_MATCHING_RESULTS) {
            stableKey = observation.key
            clearProvisional()
            return Decision.HOLD
        }
        if (provisionalPublished || provisionalCount < TEMPORARY_CANDIDATE_MATCHES) {
            return Decision.HOLD
        }
        provisionalPublished = true
        return Decision.PUBLISH
    }

    private fun resetProvisionalStreak() {
        provisionalCount = 0
    }

    private fun clearProvisional() {
        provisionalKey = null
        provisionalCount = 0
        provisionalPublished = false
    }

    private fun isTrusted(response: MahjongAnalysisResponse): Boolean {
        if (!response.status.equals(COMPLETE_STATUS, ignoreCase = true)) return false
        if ((response.confidence?.recognition ?: 0.0) < highConfidenceThreshold) return false
        return true
    }

    private fun resultKey(response: MahjongAnalysisResponse, result: SceneResult): ResultKey {
        val payload = result.content?.scenePayload as? MahjongScenePayload ?: MahjongScenePayload.EMPTY
        val suggestion = response.suggestion
        return ResultKey(
            status = response.status.lowercase(Locale.ROOT),
            diagnosticCode = response.diagnosticCode,
            userHand = payload.userHand,
            meldedTiles = payload.meldedTiles,
            meldGroupCount = response.meldGroups.size,
            kanCount = response.meldGroups.count { it.type == KAN_MELD_TYPE },
            adviceType = suggestion?.adviceType,
            suggestedTile = suggestion?.suggestion?.tile,
            tingpaiSteps = suggestion?.suggestion?.tingpaiSteps ?: suggestion?.tingpaiSteps,
            jinzhangCount = suggestion?.suggestion?.jinzhangCount ?: suggestion?.jinzhangCount,
            jinzhangTiles = suggestion?.jinzhangTiles.orEmpty(),
            canHu = suggestion?.canHu == true
        )
    }

    private enum class ResultTransition { DRAW, DISCARD, MELD_ADDED }

    private data class Observation(
        val key: ResultKey,
        val trusted: Boolean
    )

    private data class ResultKey(
        val status: String,
        val diagnosticCode: String?,
        val userHand: List<String>,
        val meldedTiles: List<String>,
        val meldGroupCount: Int,
        val kanCount: Int,
        val adviceType: String?,
        val suggestedTile: String?,
        val tingpaiSteps: Int?,
        val jinzhangCount: Int?,
        val jinzhangTiles: List<String>,
        val canHu: Boolean
    )

    private companion object {
        const val COMPLETE_STATUS = "complete"
        const val DISCARD_ADVICE = "discard"
        const val KAN_MELD_TYPE = "kan"
        const val HIGH_CONFIDENCE_THRESHOLD = 0.7
        const val REQUIRED_MATCHING_RESULTS = 3
        const val TEMPORARY_CANDIDATE_MATCHES = 2
    }
}
