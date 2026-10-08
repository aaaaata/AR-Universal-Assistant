package com.example.ai_assist.scene.mahjong.framework

import com.example.ai_assist.sdk.framework.SceneContent
import com.example.ai_assist.sdk.framework.SceneResult
import com.example.ai_assist.sdk.framework.SceneSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MahjongResultStrategyTest {
    @Test
    fun mapperBuildsCompleteHudPayload() {
        val payload = DefaultMahjongFieldMapper().map(
            response(
                handTiles = listOf("1m", "1m", "1m", "2p"),
                suggestion = MahjongSuggestion(
                    adviceType = "discard",
                    suggestion = MahjongAdviceCandidate(
                        tile = "2p",
                        tingpaiSteps = 1,
                        jinzhangTiles = listOf("3s", "4s")
                    )
                )
            )
        )

        assertEquals(4, payload.userHand.size)
        assertEquals(payload.userHand.first(), payload.gangTiles.single())
        assertEquals(2, payload.jinzhangTiles.size)
        assertEquals(1, payload.shanten)
        assertEquals(RecommendationStrength.STRONG, payload.recommendationStrength)
    }

    @Test
    fun stabilizerHoldsUntrustedUnexpectedHandChangeAfterBootstrap() {
        val stabilizer = MahjongResultStabilizer()
        val stable = response(handTiles = listOf("1m"), recognition = 0.9)
        repeat(3) { assertEquals(MahjongResultStabilizer.Decision.PUBLISH, stabilizer.evaluate(stable, result(stable))) }

        val noisy = response(handTiles = listOf("9s"), recognition = 0.2)

        assertEquals(MahjongResultStabilizer.Decision.HOLD, stabilizer.evaluate(noisy, result(noisy)))
    }

    @Test
    fun diagnosticRequiresThreeMatchingFrames() {
        val stabilizer = MahjongDiagnosticStabilizer()

        assertNull(stabilizer.evaluate("INVALID_TILE_COUNT"))
        assertNull(stabilizer.evaluate("INVALID_TILE_COUNT"))
        assertEquals("INVALID_TILE_COUNT", stabilizer.evaluate("INVALID_TILE_COUNT")?.code)
    }

    private fun result(response: MahjongAnalysisResponse): SceneResult {
        val payload = DefaultMahjongFieldMapper().map(response)
        return SceneResult(
            sceneId = "mahjong",
            sessionId = "test",
            sequenceId = 1,
            updatedAtMillis = 1,
            summary = SceneSummary("test", response.status),
            content = SceneContent(MahjongScenePayload.RENDERER_KEY, payload)
        )
    }

    private fun response(
        handTiles: List<String>,
        recognition: Double = 0.9,
        suggestion: MahjongSuggestion? = null
    ) = MahjongAnalysisResponse(
        status = "complete",
        handTiles = handTiles,
        suggestion = suggestion,
        confidence = MahjongConfidence(recognition = recognition, suggestion = 0.8)
    )
}
