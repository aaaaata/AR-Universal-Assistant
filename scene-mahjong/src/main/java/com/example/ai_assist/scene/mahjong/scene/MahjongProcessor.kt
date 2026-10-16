package com.example.ai_assist.scene.mahjong.scene

import com.example.ai_assist.scene.mahjong.network.AnalyzeResponse
import com.example.ai_assist.scene.mahjong.network.MahjongGateway
import com.example.ai_assist.scene.mahjong.network.MahjongGatewayFactory
import com.example.ai_assist.sdk.capability.CapturedFrame
import com.example.ai_assist.sdk.cv.CVProcessor

/** 麻将场景的云端会话和单帧业务协议。 */
internal class MahjongProcessor(
    private val config: MahjongSceneConfig,
    private val apiKey: String,
    private val gatewayFactory: MahjongGatewayFactory
) : CVProcessor<AnalyzeResponse> {
    private var gateway: MahjongGateway? = null
    private var cloudSessionId: String? = null

    override suspend fun start() {
        val createdGateway = gatewayFactory.create(config.serverBaseUrl)
        gateway = createdGateway
        cloudSessionId = createdGateway.startSession(
            apiKey,
            config.ruleProfileId,
            config.recordingMode.wireValue
        )
    }

    override suspend fun process(frame: CapturedFrame, sequenceId: Long): AnalyzeResponse {
        val activeGateway = checkNotNull(gateway) { "Mahjong processor is not started" }
        val activeSessionId = checkNotNull(cloudSessionId) { "Mahjong session is not started" }
        return activeGateway.analyzeFrame(
            apiKey = apiKey,
            sessionId = activeSessionId,
            sequenceId = sequenceId,
            imageRotationDegrees = frame.imageRotationDegrees,
            imageFile = frame.file
        )
    }

    override suspend fun stop() {
        val activeGateway = gateway
        val activeSessionId = cloudSessionId
        gateway = null
        cloudSessionId = null
        if (activeGateway != null && activeSessionId != null) {
            runCatching { activeGateway.endSession(apiKey, activeSessionId) }
                .onFailure { android.util.Log.w(TAG, "Cloud session end failed", it) }
        }
    }

    private companion object {
        const val TAG = "MahjongProcessor"
    }
}
