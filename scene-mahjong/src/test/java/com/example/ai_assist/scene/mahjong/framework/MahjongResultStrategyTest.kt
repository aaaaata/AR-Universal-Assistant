package com.example.ai_assist.scene.mahjong.framework

import com.example.ai_assist.sdk.framework.Frame
import com.example.ai_assist.sdk.framework.SceneContent
import com.example.ai_assist.sdk.framework.SceneResult
import com.example.ai_assist.sdk.framework.SceneSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MahjongResultStrategyTest {
    @Test
    fun mapperBuildsSortedHudPayload() {
        val payload = DefaultMahjongFieldMapper.map(
            response(
                handTiles = listOf("9p", "9p", "2m", "2m", "2m", "5s"),
                suggestion = MahjongSuggestion(
                    adviceType = "discard",
                    suggestion = MahjongAdviceCandidate(
                        tile = "5s",
                        tingpaiSteps = 1,
                        jinzhangTiles = listOf("3s", "4s")
                    )
                )
            )
        )

        assertEquals(
            listOf(DefaultMahjongFieldMapper.parseTile("2m"), DefaultMahjongFieldMapper.parseTile("9p")),
            payload.pengTiles
        )
        assertEquals(listOf(DefaultMahjongFieldMapper.parseTile("2m")), payload.gangTiles)
        assertEquals(2, payload.jinzhangTiles.size)
        assertEquals(1, payload.shanten)
    }

    @Test
    fun stabilizerHoldsUntrustedUnexpectedHandChangeAfterBootstrap() {
        val stabilizer = MahjongResultStabilizer()
        val stable = response(handTiles = listOf("1m"), recognition = 0.9)
        repeat(3) {
            assertEquals(
                MahjongResultStabilizer.Decision.PUBLISH,
                stabilizer.evaluate(stable, result(stable))
            )
        }

        val noisy = response(handTiles = listOf("9s"), recognition = 0.2)
        assertEquals(
            MahjongResultStabilizer.Decision.HOLD,
            stabilizer.evaluate(noisy, result(noisy))
        )
    }

    @Test
    fun diagnosticRequiresThreeMatchingFrames() {
        val stabilizer = MahjongDiagnosticStabilizer()

        assertNull(stabilizer.evaluate("INVALID_TILE_COUNT"))
        assertNull(stabilizer.evaluate("INVALID_TILE_COUNT"))
        assertEquals("INVALID_TILE_COUNT", stabilizer.evaluate("INVALID_TILE_COUNT")?.code)
    }

    @Test
    fun captureGuidanceRestoresDarkAndDistanceWarnings() {
        val guidance = MahjongCaptureGuidance()
        val file = File.createTempFile("guidance", ".jpg")
        try {
            val darkFrame = Frame(file, 0, 1L, 1L, medianLuma = 50)
            assertNull(guidance.onFrameReady(darkFrame))
            assertEquals(
                "画面太暗",
                (guidance.onFrameReady(darkFrame) as MahjongGuidanceUpdate.Show).message
            )
            guidance.reset()

            val far = response(
                handTiles = listOf("1m"),
                image = MahjongImageInfo(width = 1_000, height = 600),
                tileDetections = listOf(10.0, 40.0, 70.0).map { left ->
                    MahjongTileDetection(
                        regionType = "hand",
                        tileConfidence = 0.9,
                        bboxInOriginalImage = MahjongBoundingBox(x1 = left, x2 = left + 20.0)
                    )
                }
            )
            assertNull(guidance.onResult(far))
            assertEquals(
                "请靠近牌面",
                (guidance.onResult(far) as MahjongGuidanceUpdate.Show).message
            )
        } finally {
            assertTrue(file.delete() || !file.exists())
        }
    }

    private fun result(response: MahjongAnalyzeResult): SceneResult {
        val payload = DefaultMahjongFieldMapper.map(response)
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
        suggestion: MahjongSuggestion? = null,
        image: MahjongImageInfo? = null,
        tileDetections: List<MahjongTileDetection> = emptyList()
    ) = MahjongAnalyzeResult(
        status = "complete",
        handTiles = handTiles,
        suggestion = suggestion,
        confidence = MahjongConfidence(recognition = recognition, suggestion = 0.8),
        image = image,
        tileDetections = tileDetections
    )
}
