package com.example.ai_assist.scene.mahjong.network

import android.os.SystemClock
import android.util.Log
import com.example.ai_assist.sdk.network.DefaultRequestMetadataProvider
import com.example.ai_assist.sdk.network.NetworkClientConfig
import com.example.ai_assist.sdk.network.NetworkServiceFactory
import com.example.ai_assist.sdk.network.RequestMetadataProvider
import com.example.ai_assist.sdk.network.RetrofitNetworkServiceFactory
import okhttp3.MediaType
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.File

internal interface MahjongApiService {
    /** 创建麻将云端会话。 */
    @POST("api/v1/mahjong/sessions")
    suspend fun startSession(
        @Header("X-API-Key") apiKey: String,
        @Body request: StartSessionRequest
    ): StartSessionResponse

    /** 上传一帧麻将图片进行分析。 */
    @POST("api/v1/mahjong/sessions/{sessionId}/analyses")
    suspend fun analyzeFrame(
        @Path("sessionId") sessionId: String,
        @Header("X-API-Key") apiKey: String,
        @Header("X-Request-Id") requestId: String,
        @Header("X-Sequence-Id") sequenceId: Long,
        @Header("X-Frame-Ts") frameTimestampMs: Long,
        @Header("X-Frame-Count") frameCount: Int,
        @Header("X-Image-Rotation-Degrees") imageRotationDegrees: Int,
        @Body image: okhttp3.RequestBody
    ): AnalyzeResponse

    /** 结束麻将云端会话。 */
    @POST("api/v1/mahjong/sessions/{sessionId}/end")
    suspend fun endSession(
        @Path("sessionId") sessionId: String,
        @Header("X-API-Key") apiKey: String
    ): EndSessionResponse
}

internal interface MahjongGateway {
    /** 创建麻将云端会话并返回服务端会话 ID。 */
    suspend fun startSession(
        apiKey: String,
        ruleProfileId: String,
        recordingMode: String
    ): String

    /** 上传单帧并返回麻将分析结果。 */
    suspend fun analyzeFrame(
        apiKey: String,
        sessionId: String,
        sequenceId: Long,
        imageRotationDegrees: Int,
        imageFile: File
    ): AnalyzeResponse

    /** 结束指定麻将云端会话。 */
    suspend fun endSession(apiKey: String, sessionId: String)
}

internal fun interface MahjongGatewayFactory {
    /** 按服务根地址创建一次会话使用的网关。 */
    fun create(serverBaseUrl: String): MahjongGateway
}

internal class RetrofitMahjongGateway(
    private val apiService: MahjongApiService,
    private val requestMetadataProvider: RequestMetadataProvider = DefaultRequestMetadataProvider
) : MahjongGateway {
    /** 调用既有创建会话接口。 */
    override suspend fun startSession(
        apiKey: String,
        ruleProfileId: String,
        recordingMode: String
    ): String = apiService.startSession(
        apiKey,
        StartSessionRequest(ruleProfileId, recordingMode)
    ).sessionId

    /** 保持既有请求头和原始图片请求体上传单帧。 */
    override suspend fun analyzeFrame(
        apiKey: String,
        sessionId: String,
        sequenceId: Long,
        imageRotationDegrees: Int,
        imageFile: File
    ): AnalyzeResponse {
        val mediaType = when (imageFile.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> throw IllegalArgumentException("Only PNG or JPEG capture files can be uploaded")
        }.let(MediaType::parse)
        val metadata = requestMetadataProvider.create()
        val imageBody = RequestBody.create(mediaType, imageFile)
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(
            FRAME_TIMING_TAG,
            "event=frame_cloud_round_trip_begin request_id=${metadata.requestId} " +
                "sequence_id=$sequenceId image_bytes=${imageFile.length()} elapsed_ms=$startedAt"
        )
        return try {
            val response = apiService.analyzeFrame(
                sessionId = sessionId,
                apiKey = apiKey,
                requestId = metadata.requestId,
                sequenceId = sequenceId,
                frameTimestampMs = metadata.createdAtMillis,
                frameCount = 1,
                imageRotationDegrees = imageRotationDegrees,
                image = imageBody
            )
            val completedAt = SystemClock.elapsedRealtime()
            Log.i(
                FRAME_TIMING_TAG,
                "event=frame_cloud_round_trip_complete request_id=${metadata.requestId} " +
                    "sequence_id=$sequenceId analysis_id=${response.analysisId} " +
                    "status=${response.status} duration_ms=${completedAt - startedAt} " +
                    "elapsed_ms=$completedAt"
            )
            response
        } catch (error: Throwable) {
            val failedAt = SystemClock.elapsedRealtime()
            Log.w(
                FRAME_TIMING_TAG,
                "event=frame_cloud_round_trip_failed request_id=${metadata.requestId} " +
                    "sequence_id=$sequenceId duration_ms=${failedAt - startedAt} " +
                    "elapsed_ms=$failedAt error=${error::class.java.simpleName}"
            )
            throw error
        }
    }

    /** 调用既有结束会话接口。 */
    override suspend fun endSession(apiKey: String, sessionId: String) {
        apiService.endSession(sessionId, apiKey)
    }

    companion object {
        private const val FRAME_TIMING_TAG = "FrameRoundTrip"

        /** 使用 Retrofit 和 Gson 创建协议实现。 */
        fun create(
            serverBaseUrl: String,
            networkServiceFactory: NetworkServiceFactory = RetrofitNetworkServiceFactory
        ): RetrofitMahjongGateway {
            val service = networkServiceFactory.create(
                NetworkClientConfig(serverBaseUrl),
                MahjongApiService::class.java
            )
            return RetrofitMahjongGateway(service)
        }
    }
}
