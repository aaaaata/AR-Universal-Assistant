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
        assertEquals(
            "api/v1/mahjong/sessions/{sessionId}/analyses",
            postPath("submitImage")
        )
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
    fun sessionRequestUsesServerFieldNames() {
        val json = Gson().toJson(CreateMahjongSessionRequest("guangdong_mvp_v1", "normal"))
        assertTrue(json.contains("\"rule_profile_id\":\"guangdong_mvp_v1\""))
        assertTrue(json.contains("\"recording_mode\":\"normal\""))
    }

    private fun method(name: String) =
        MahjongApiService::class.java.declaredMethods.single { it.name == name }

    private fun postPath(name: String) =
        requireNotNull(method(name).getAnnotation(POST::class.java)).value
}
