package com.example.ai_assist.scene.mahjong.scene

import com.example.ai_assist.scene.mahjong.network.AnalyzeResponse
import com.example.ai_assist.scene.mahjong.network.ConfidencePayload
import com.example.ai_assist.scene.mahjong.network.SuggestionPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class MahjongResultStabilizerTest {
    @Test
    fun publishesTemporaryResultOnSecondMatchingUnexpectedFrame() {
        val stabilizer = MahjongResultStabilizer()
        val baseline = response((1..13).map { "${(it % 9) + 1}m" })
        repeat(3) {
            assertEquals(
                MahjongResultStabilizer.Decision.PUBLISH,
                stabilizer.evaluate(baseline, mapped(baseline))
            )
        }
        val unexpected = response(listOf("1p", "2p", "3p"))

        assertEquals(
            MahjongResultStabilizer.Decision.HOLD,
            stabilizer.evaluate(unexpected, mapped(unexpected))
        )
        assertEquals(
            MahjongResultStabilizer.Decision.PUBLISH,
            stabilizer.evaluate(unexpected, mapped(unexpected))
        )
        assertEquals(
            MahjongResultStabilizer.Decision.HOLD,
            stabilizer.evaluate(unexpected, mapped(unexpected))
        )
    }

    @Test
    fun publishesExplicitNormalDiscardImmediately() {
        val stabilizer = MahjongResultStabilizer()
        val fourteenTiles = (1..14).map { "${(it % 9) + 1}m" }
        val baseline = response(
            fourteenTiles,
            SuggestionPayload(adviceType = "discard")
        )
        repeat(3) { stabilizer.evaluate(baseline, mapped(baseline)) }
        val discard = response(
            fourteenTiles.dropLast(1),
            hasLowConfidenceHandTileDetection = false
        )

        assertEquals(
            MahjongResultStabilizer.Decision.PUBLISH,
            stabilizer.evaluate(discard, mapped(discard))
        )
    }

    @Test
    fun completionWarningDoesNotRejectOtherwiseTrustedResult() {
        val stabilizer = MahjongResultStabilizer()
        val baseline = response((1..13).map { "${(it % 9) + 1}m" })
        repeat(3) { stabilizer.evaluate(baseline, mapped(baseline)) }
        val lowConfidence = response(
            listOf("1p", "2p", "3p"),
            warnings = listOf("LOW_CONFIDENCE_HAND_SLOT_CANDIDATE_USED")
        )

        assertEquals(
            MahjongResultStabilizer.Decision.HOLD,
            stabilizer.evaluate(lowConfidence, mapped(lowConfidence))
        )
        assertEquals(
            MahjongResultStabilizer.Decision.PUBLISH,
            stabilizer.evaluate(lowConfidence, mapped(lowConfidence))
        )
    }

    private fun response(
        tiles: List<String>,
        suggestion: SuggestionPayload? = SuggestionPayload(adviceType = "draw_wait"),
        warnings: List<String> = emptyList(),
        hasLowConfidenceHandTileDetection: Boolean? = null
    ) = AnalyzeResponse(
        status = "complete",
        handTiles = tiles,
        suggestion = suggestion,
        confidence = ConfidencePayload(recognition = 0.9),
        warnings = warnings,
        hasLowConfidenceHandTileDetection = hasLowConfidenceHandTileDetection
    )

    private fun mapped(response: AnalyzeResponse) = MahjongResultMapper.toSceneResult(
        response = response,
        sceneId = MahjongSceneProvider.SCENE_ID,
        sessionId = "session",
        updatedAtMillis = 1L
    )
}
