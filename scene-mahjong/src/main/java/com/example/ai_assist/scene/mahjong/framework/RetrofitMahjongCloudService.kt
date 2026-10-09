package com.example.ai_assist.scene.mahjong.framework

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

internal interface MahjongApiService {
    @POST("api/v1/mahjong/sessions")
    suspend fun createSession(
        @Header("X-API-Key") apiKey: String,
        @Body request: CreateMahjongSessionRequest
    ): CreateMahjongSessionResponse

    @POST("api/v1/mahjong/sessions/{sessionId}/analyses")
    suspend fun submitImage(
        @Path("sessionId") sessionId: String,
        @Header("X-API-Key") apiKey: String,
        @Header("X-Request-Id") requestId: String,
        @Header("X-Sequence-Id") sequenceId: Long,
        @Header("X-Frame-Ts") frameTimestampMillis: Long,
        @Header("X-Frame-Count") frameCount: Int,
        @Header("X-Image-Rotation-Degrees") imageRotationDegrees: Int,
        @Body image: RequestBody
    ): MahjongAnalysisResponse

    @POST("api/v1/mahjong/sessions/{sessionId}/end")
    suspend fun endSession(
        @Path("sessionId") sessionId: String,
        @Header("X-API-Key") apiKey: String
    ): EndMahjongSessionResponse
}

/** MahjongCloudService 的 Retrofit 实现，不依赖旧工程 sdk-network。 */
class RetrofitMahjongCloudService internal constructor(
    private val api: MahjongApiService,
    private val apiKey: String,
    private val ruleProfileId: String,
    private val recordingMode: String,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val requestId: () -> String = { UUID.randomUUID().toString() }
) : MahjongCloudService {
    private val sequence = AtomicLong(0L)

    override suspend fun createSession(): Result<String> = suspendResult {
        api.createSession(
            apiKey = apiKey,
            request = CreateMahjongSessionRequest(ruleProfileId, recordingMode)
        ).sessionId.also { require(it.isNotBlank()) { "Cloud returned an empty session ID" } }
    }

    override suspend fun submitImage(
        sessionId: String,
        image: MahjongImageSubmission
    ): Result<MahjongCloudResult> = suspendResult {
        require(image.bytes.isNotEmpty()) { "Image must not be empty" }
        val mediaType = if (image.bytes.isPng()) "image/png" else "image/jpeg"
        api.submitImage(
            sessionId = sessionId,
            apiKey = apiKey,
            requestId = requestId(),
            sequenceId = sequence.incrementAndGet(),
            frameTimestampMillis = image.capturedAtMillis.takeIf { it > 0 } ?: clockMillis(),
            frameCount = 1,
            imageRotationDegrees = image.imageRotationDegrees,
            image = image.bytes.toRequestBody(mediaType.toMediaType())
        )
    }

    override suspend fun endSession(sessionId: String): Result<Unit> = suspendResult {
        api.endSession(sessionId, apiKey)
        Unit
    }

    companion object {
        fun create(config: MahjongSceneConfig, apiKey: String): RetrofitMahjongCloudService {
            require(apiKey.isNotBlank()) { "Mahjong API key is required" }
            val api = Retrofit.Builder()
                .baseUrl(config.serverBaseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MahjongApiService::class.java)
            return RetrofitMahjongCloudService(
                api = api,
                apiKey = apiKey,
                ruleProfileId = config.ruleProfileId,
                recordingMode = config.recordingMode.wireValue
            )
        }
    }
}

private suspend fun <T> suspendResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}

private fun ByteArray.isPng(): Boolean =
    size >= 8 &&
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
