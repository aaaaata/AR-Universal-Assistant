package com.example.ai_assist.scene.mahjong.framework

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

class MahjongApiContractTest {
    @Test
    fun endpointsMatchCurrentServerContract() {
        assertEquals("api/v1/mahjong/sessions", postPath("createSession"))
        assertEquals("api/v1/mahjong/sessions/{sessionId}/analyses", postPath("submitImage"))
        assertEquals("api/v1/mahjong/sessions/{sessionId}/end", postPath("endSession"))
    }

    @Test
    fun imageSubmissionKeepsRequiredHeaders() {
        val annotations = method("submitImage").parameterAnnotations.flatten()
        assertTrue(annotations.filterIsInstance<Path>().any { it.value == "sessionId" })
        assertEquals(
            setOf(
                "X-API-Key",
                "X-Request-Id",
                "X-Sequence-Id",
                "X-Frame-Ts",
                "X-Frame-Count",
                "X-Image-Rotation-Degrees"
            ),
            annotations.filterIsInstance<Header>().map { it.value }.toSet()
        )
    }

    @Test
    fun sessionAndAnalysisModelsKeepServerFields() {
        val gson = Gson()
        val requestJson = gson.toJson(StartSessionRequest("guangdong_mvp_v1", "normal"))
        assertTrue(requestJson.contains("\"rule_profile_id\":\"guangdong_mvp_v1\""))
        assertTrue(requestJson.contains("\"recording_mode\":\"normal\""))

        val response = gson.fromJson(
            """{
                "status":"complete",
                "image":{"width":1920,"height":1080},
                "tile_detections":[{
                    "region_type":"hand",
                    "tile_confidence":0.9,
                    "bbox_in_original_image":{"x1":10,"y1":20,"x2":30,"y2":60}
                }],
                "has_low_confidence_hand_tile_detection":true
            }""".trimIndent(),
            MahjongAnalyzeResult::class.java
        )
        assertEquals(1920, response.image?.width)
        assertEquals(1, response.tileDetections.size)
        assertEquals(true, response.hasLowConfidenceHandTileDetection)
    }

    private fun method(name: String) =
        MahjongApiService::class.java.declaredMethods.single { it.name == name }

    private fun postPath(name: String) =
        requireNotNull(method(name).getAnnotation(POST::class.java)).value
}
