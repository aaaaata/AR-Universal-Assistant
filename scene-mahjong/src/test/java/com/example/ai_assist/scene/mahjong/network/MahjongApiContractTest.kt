package com.example.ai_assist.scene.mahjong.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

class MahjongApiContractTest {
    /** 固定麻将会话接口路径。 */
    @Test
    fun sessionEndpointsKeepCurrentPaths() {
        assertEquals("api/v1/mahjong/sessions", postPath("startSession"))
        assertEquals(
            "api/v1/mahjong/sessions/{sessionId}/analyses",
            postPath("analyzeFrame")
        )
        assertEquals(
            "api/v1/mahjong/sessions/{sessionId}/end",
            postPath("endSession")
        )
    }

    /** 固定单帧分析接口的路径参数和诊断请求头。 */
    @Test
    fun analyzeFrameKeepsOrderingAndDiagnosticHeaders() {
        val annotations = method("analyzeFrame").parameterAnnotations.flatten()
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

    /** 教程要求全部业务接口都通过 X-API-Key 鉴权。 */
    @Test
    fun allBusinessEndpointsRequireApiKeyHeader() {
        listOf("startSession", "analyzeFrame", "endSession").forEach { methodName ->
            val headers = method(methodName).parameterAnnotations
                .flatten()
                .filterIsInstance<Header>()
                .map { it.value }

            assertTrue("$methodName must send X-API-Key", "X-API-Key" in headers)
        }
    }

    @Test
    fun startSessionIncludesRecordingMode() {
        val json = Gson().toJson(StartSessionRequest("guangdong_mvp_v1", "regression"))

        assertTrue(json.contains("\"rule_profile_id\":\"guangdong_mvp_v1\""))
        assertTrue(json.contains("\"recording_mode\":\"regression\""))
    }

    /** 返回指定 Retrofit 方法。 */
    private fun method(name: String) =
        MahjongApiService::class.java.declaredMethods.single { it.name == name }

    /** 返回指定方法的 POST 路径。 */
    private fun postPath(name: String) =
        requireNotNull(method(name).getAnnotation(POST::class.java)).value
}
