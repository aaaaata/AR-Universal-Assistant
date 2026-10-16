package com.example.ai_assist.scene.mahjong.scene

import com.example.ai_assist.scene.mahjong.network.AnalyzeResponse
import com.example.ai_assist.sdk.capability.CapturedFrame
import com.example.ai_assist.sdk.cv.CaptureGuidanceController
import com.example.ai_assist.sdk.cv.CaptureGuidanceNotices
import com.example.ai_assist.sdk.cv.FramingSample
import com.example.ai_assist.sdk.scene.SceneNotice
import com.example.ai_assist.sdk.scene.SceneNoticeUpdate

/** Adapts Mahjong frame and response data to the shared capture-guidance controller. */
internal class MahjongCaptureGuidance(
    private val controller: CaptureGuidanceController = CaptureGuidanceController(
        CaptureGuidanceNotices(DARK_NOTICE, TOO_FAR_NOTICE, TOO_NEAR_NOTICE)
    )
) {

    fun onFrameReady(frame: CapturedFrame): SceneNoticeUpdate? {
        return frame.medianLuma?.let(controller::onMedianLuma)
    }

    fun onResult(response: AnalyzeResponse): SceneNoticeUpdate? {
        if (response.status != "complete" || response.diagnosticCode != null) return null
        if ((response.confidence?.recognition ?: 0.0) < MIN_RECOGNITION_CONFIDENCE) return null
        val imageWidth = response.image?.width?.takeIf { it > 0 } ?: return null
        val widths = response.tileDetections.mapNotNull { detection ->
            if (detection.regionType != "hand" ||
                detection.tileConfidence < MIN_RECOGNITION_CONFIDENCE
            ) return@mapNotNull null
            detection.bboxInOriginalImage?.let { it.x2 - it.x1 }?.takeIf { it.isFinite() && it > 0 }
        }
        if (widths.size < MIN_OBJECT_COUNT) return null

        return controller.onFramingSample(FramingSample(imageWidth, widths))
    }

    fun reset(): SceneNoticeUpdate? = controller.reset()

    private companion object {
        const val MIN_OBJECT_COUNT = 3
        const val MIN_RECOGNITION_CONFIDENCE = 0.7

        val DARK_NOTICE = SceneNotice("SCENE_TOO_DARK", "画面太暗")
        val TOO_FAR_NOTICE = SceneNotice("SCENE_TARGET_TOO_FAR", "请靠近牌面")
        val TOO_NEAR_NOTICE = SceneNotice("SCENE_TARGET_TOO_NEAR", "请远离牌面")
    }
}
