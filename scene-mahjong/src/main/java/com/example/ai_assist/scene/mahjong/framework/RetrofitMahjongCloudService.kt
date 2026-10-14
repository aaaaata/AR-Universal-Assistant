package com.example.ai_assist.scene.mahjong.framework

import com.google.gson.annotations.SerializedName
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

internal data class StartSessionRequest(
    @SerializedName("rule_profile_id") val ruleProfileId: String = "guangdong_mvp_v1",
    @SerializedName("recording_mode") val recordingMode: String = "normal"
)

internal data class StartSessionResponse(
    @SerializedName("session_id") val sessionId: String
)

internal data class EndSessionResponse(
    val status: String,
    @SerializedName("session_id") val sessionId: String
)

data class MahjongMeldGroup(
    val type: String = "unknown",
    val tiles: List<String> = emptyList()
)

data class MahjongAdviceCandidate(
    val tile: String = "",
    @SerializedName("tingpai_steps") val tingpaiSteps: Int = 0,
    @SerializedName("jinzhang_count") val jinzhangCount: Int = 0,
    @SerializedName("jinzhang_tiles") val jinzhangTiles: List<String> = emptyList()
)

data class MahjongSuggestion(
    @SerializedName("advice_type") val adviceType: String = "",
    val suggestion: MahjongAdviceCandidate? = null,
    @SerializedName("tingpai_steps") val tingpaiSteps: Int? = null,
    @SerializedName("jinzhang_count") val jinzhangCount: Int? = null,
    @SerializedName("jinzhang_tiles") val jinzhangTiles: List<String> = emptyList(),
    @SerializedName("can_hu") val canHu: Boolean = false
)

data class MahjongConfidence(
    val region: Double = 0.0,
    val recognition: Double = 0.0,
    val structure: Double = 0.0,
    val suggestion: Double = 0.0,
    val reasons: List<String> = emptyList()
)

data class MahjongAnalyzeResult(
    @SerializedName("analysis_id") val analysisId: String = "",
    val status: String = "",
    @SerializedName("diagnostic_code") val diagnosticCode: String? = null,
    @SerializedName("hand_tiles") val handTiles: List<String> = emptyList(),
    @SerializedName("meld_groups") val meldGroups: List<MahjongMeldGroup> = emptyList(),
    @SerializedName("bonus_tiles") val bonusTiles: List<String> = emptyList(),
    val suggestion: MahjongSuggestion? = null,
    val confidence: MahjongConfidence? = null,
    val warnings: List<String> = emptyList()
) : MahjongCloudResult {
    fun diagnosticMessage(): String = when (diagnosticCode) {
        "TOO_MANY_REGIONS" -> "牌区过多，请重新取景"
        "HAND_REGION_NOT_FOUND" -> "未找到手牌区，请重新取景"
        "REGION_ASSIGNMENT_AMBIGUOUS" -> "牌归属区域不明确"
        "NO_VALID_HAND_TILES" -> "未识别到有效手牌"
        "INVALID_MELD_STRUCTURE" -> "副露结构不合法"
        "DUPLICATE_TILE_OVER_LIMIT" -> "同一张牌超过四张"
        "INVALID_TILE_COUNT" -> "牌数不合法"
        else -> diagnosticCode ?: ""
    }

    fun summaryTitle(payload: MahjongScenePayload): String = when {
        status == "diagnostic" -> diagnosticMessage().ifBlank { "识别异常，请重拍" }
        handTiles.size == 14 -> "手牌 14 张，${payload.suggestedPlay}"
        handTiles.size == 13 -> "手牌 13 张，${payload.suggestedPlay}"
        else -> "麻将分析完成"
    }
}

internal interface MahjongApiService {
    @POST("api/v1/mahjong/sessions")
    suspend fun createSession(
        @Header("X-API-Key") apiKey: String,
        @Body request: StartSessionRequest
    ): StartSessionResponse

    @POST("api/v1/mahjong/sessions/{sessionId}/analyses")
    suspend fun submitImage(
        @Path("sessionId") sessionId: String,
        @Header("X-API-Key") apiKey: String,
        @Header("X-Request-Id") requestId: String,
        @Header("X-Sequence-Id") sequenceId: Long,
        @Header("X-Frame-Ts") frameTimestampMillis: Long,
        @Header("X-Frame-Count") frameCount: Int,
        @Header("X-Image-Rotation-Degrees") imageRotationDegrees: Int,
        @Body image: okhttp3.RequestBody
    ): MahjongAnalyzeResult

    @POST("api/v1/mahjong/sessions/{sessionId}/end")
    suspend fun endSession(
        @Path("sessionId") sessionId: String,
        @Header("X-API-Key") apiKey: String
    ): EndSessionResponse
}

internal class RetrofitMahjongCloudService(
    private val api: MahjongApiService,
    private val apiKey: String,
    private val recordingMode: String
) : MahjongCloudService {
    private val sequence = AtomicLong(0L)
    @Volatile
    private var frameTimestampMillis: Long = 0L
    @Volatile
    private var imageRotationDegrees: Int = 0

    fun prepareFrame(capturedAtMillis: Long, rotationDegrees: Int) {
        frameTimestampMillis = capturedAtMillis
        imageRotationDegrees = rotationDegrees
    }

    override suspend fun createSession(): Result<String> = runCatching {
        api.createSession(apiKey, StartSessionRequest(recordingMode = recordingMode)).sessionId
    }

    override suspend fun submitImage(
        sessionId: String,
        image: ByteArray
    ): Result<MahjongCloudResult> = runCatching {
        api.submitImage(
            sessionId = sessionId,
            apiKey = apiKey,
            requestId = UUID.randomUUID().toString(),
            sequenceId = sequence.incrementAndGet(),
            frameTimestampMillis = frameTimestampMillis.takeIf { it > 0 }
                ?: System.currentTimeMillis(),
            frameCount = 1,
            imageRotationDegrees = imageRotationDegrees,
            image = image.toRequestBody("image/jpeg".toMediaType())
        )
    }

    override suspend fun endSession(sessionId: String): Result<Unit> = runCatching {
        api.endSession(sessionId, apiKey)
        Unit
    }

    companion object {
        fun create(config: MahjongSceneConfig, apiKey: String): RetrofitMahjongCloudService {
            val client = OkHttpClient.Builder()
                .connectTimeout(config.requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(config.requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(config.requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .build()
            val api = Retrofit.Builder()
                .baseUrl(config.normalizedServerBaseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MahjongApiService::class.java)
            return RetrofitMahjongCloudService(api, apiKey, config.recordingMode.wireValue)
        }
    }
}

internal object DefaultMahjongFieldMapper : MahjongFieldMapper {
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

    override fun map(cloudResult: MahjongCloudResult): MahjongScenePayload {
        val response = cloudResult as? MahjongAnalyzeResult
            ?: error("Unsupported Mahjong cloud result")
        val incomingTiles = when (response.suggestion?.adviceType) {
            "discard" -> response.suggestion.suggestion?.jinzhangTiles.orEmpty()
            else -> response.suggestion?.jinzhangTiles.orEmpty()
        }
        val suggestion = when {
            response.suggestion?.canHu == true -> "可以和牌"
            response.handTiles.size == 14 -> {
                val discard = response.suggestion?.suggestion?.tile.orEmpty()
                buildString {
                    append("建议打出：")
                    append(parseTile(discard).ifBlank { "等待识别" })
                    if (incomingTiles.isNotEmpty()) {
                        append("；进张：")
                        append(incomingTiles.joinToString(" ", transform = ::parseTile))
                    }
                }
            }
            response.handTiles.size == 13 -> {
                if (incomingTiles.isEmpty()) "等待进张建议"
                else "建议进张：${incomingTiles.joinToString(" ", transform = ::parseTile)}"
            }
            else -> response.diagnosticMessage().ifBlank { "等待有效手牌" }
        }
        return MahjongScenePayload(
            userHand = response.handTiles.map(::parseTile),
            meldedTiles = response.meldGroups.flatMap { it.tiles }.map(::parseTile),
            suggestedPlay = suggestion,
            suggestedDiscard = response.suggestion?.takeIf { it.adviceType == "discard" }
                ?.suggestion?.tile?.takeIf(String::isNotBlank)?.let(::parseTile),
            jinzhangTiles = incomingTiles.take(5).map(::parseTile),
            pengTiles = response.handTiles.groupingBy { it }.eachCount()
                .filterValues { it >= 2 }.keys.map(::parseTile),
            gangTiles = response.handTiles.groupingBy { it }.eachCount()
                .filterValues { it >= 3 }.keys.map(::parseTile),
            shanten = response.suggestion?.suggestion?.tingpaiSteps
                ?: response.suggestion?.tingpaiSteps,
            recommendationStrength = RecommendationStrength.fromConfidence(
                response.confidence?.suggestion
            )
        )
    }

    override fun parseTile(raw: String): String {
        val normalized = raw.lowercase().replace("r", "")
        return tileMap[normalized] ?: raw
    }

    private fun tile(codePoint: Int): String = String(Character.toChars(codePoint))
}
