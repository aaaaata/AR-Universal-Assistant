package com.example.ai_assist.scene.mahjong.framework

import com.example.ai_assist.sdk.framework.Frame

internal sealed interface MahjongGuidanceUpdate {
    data class Show(val message: String) : MahjongGuidanceUpdate
    data object Dismiss : MahjongGuidanceUpdate
}

/** 将通用帧亮度和麻将检测框转换为场景专有的拍摄提示。 */
internal class MahjongCaptureGuidance {
    private var dark = false
    private var darkEnterCount = 0
    private var darkExitCount = 0
    private var distanceState = DistanceState.NORMAL
    private var distanceCandidate = DistanceState.NORMAL
    private var distanceCandidateCount = 0
    private var distanceExitCount = 0

    fun onFrameReady(frame: Frame): MahjongGuidanceUpdate? {
        val medianLuma = frame.medianLuma ?: return null
        val wasDark = dark
        if (!dark) {
            darkEnterCount = if (medianLuma < DARK_ENTER_LUMA) darkEnterCount + 1 else 0
            if (darkEnterCount >= DARK_ENTER_FRAMES) {
                dark = true
                darkEnterCount = 0
                darkExitCount = 0
            }
        } else {
            darkExitCount = if (medianLuma > DARK_EXIT_LUMA) darkExitCount + 1 else 0
            if (darkExitCount >= DARK_EXIT_FRAMES) {
                dark = false
                darkExitCount = 0
            }
        }
        return when {
            dark -> MahjongGuidanceUpdate.Show("画面太暗")
            wasDark -> MahjongGuidanceUpdate.Dismiss
            else -> null
        }
    }

    fun onResult(response: MahjongAnalyzeResult): MahjongGuidanceUpdate? {
        if (response.status != "complete" || response.diagnosticCode != null) return null
        if ((response.confidence?.recognition ?: 0.0) < MIN_RECOGNITION_CONFIDENCE) return null
        val imageWidth = response.image?.width?.takeIf { it > 0 } ?: return null
        val widths = response.tileDetections.mapNotNull { detection ->
            if (detection.regionType != "hand" ||
                detection.tileConfidence < MIN_RECOGNITION_CONFIDENCE
            ) return@mapNotNull null
            detection.bboxInOriginalImage?.let { it.x2 - it.x1 }
                ?.takeIf { it.isFinite() && it > 0 }
        }
        if (widths.size < MIN_OBJECT_COUNT) return null

        val previous = distanceState
        updateDistanceState(median(widths) / imageWidth)
        if (dark) return null
        return when (distanceState) {
            DistanceState.TOO_FAR -> MahjongGuidanceUpdate.Show("请靠近牌面")
            DistanceState.TOO_NEAR -> MahjongGuidanceUpdate.Show("请远离牌面")
            DistanceState.NORMAL -> if (previous == DistanceState.NORMAL) null
                else MahjongGuidanceUpdate.Dismiss
        }
    }

    fun reset(): MahjongGuidanceUpdate? {
        val hadNotice = dark || distanceState != DistanceState.NORMAL
        dark = false
        darkEnterCount = 0
        darkExitCount = 0
        distanceState = DistanceState.NORMAL
        distanceCandidate = DistanceState.NORMAL
        distanceCandidateCount = 0
        distanceExitCount = 0
        return if (hadNotice) MahjongGuidanceUpdate.Dismiss else null
    }

    private fun updateDistanceState(ratio: Double) {
        when (distanceState) {
            DistanceState.NORMAL -> {
                val candidate = when {
                    ratio < FAR_ENTER_RATIO -> DistanceState.TOO_FAR
                    ratio > NEAR_ENTER_RATIO -> DistanceState.TOO_NEAR
                    else -> DistanceState.NORMAL
                }
                if (candidate == DistanceState.NORMAL) {
                    distanceCandidate = DistanceState.NORMAL
                    distanceCandidateCount = 0
                } else {
                    distanceCandidateCount = if (candidate == distanceCandidate) {
                        distanceCandidateCount + 1
                    } else {
                        distanceCandidate = candidate
                        1
                    }
                    if (distanceCandidateCount >= DISTANCE_ENTER_FRAMES) {
                        distanceState = candidate
                        distanceCandidate = DistanceState.NORMAL
                        distanceCandidateCount = 0
                        distanceExitCount = 0
                    }
                }
            }
            DistanceState.TOO_FAR -> {
                distanceExitCount = if (ratio > FAR_EXIT_RATIO) distanceExitCount + 1 else 0
                if (distanceExitCount >= DISTANCE_EXIT_FRAMES) resetDistance()
            }
            DistanceState.TOO_NEAR -> {
                distanceExitCount = if (ratio < NEAR_EXIT_RATIO) distanceExitCount + 1 else 0
                if (distanceExitCount >= DISTANCE_EXIT_FRAMES) resetDistance()
            }
        }
    }

    private fun resetDistance() {
        distanceState = DistanceState.NORMAL
        distanceExitCount = 0
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else sorted[middle]
    }

    private enum class DistanceState { NORMAL, TOO_FAR, TOO_NEAR }

    private companion object {
        const val DARK_ENTER_LUMA = 100
        const val DARK_EXIT_LUMA = 115
        const val DARK_ENTER_FRAMES = 2
        const val DARK_EXIT_FRAMES = 2
        const val FAR_ENTER_RATIO = 0.034
        const val FAR_EXIT_RATIO = 0.036
        const val NEAR_ENTER_RATIO = 0.051
        const val NEAR_EXIT_RATIO = 0.048
        const val DISTANCE_ENTER_FRAMES = 2
        const val DISTANCE_EXIT_FRAMES = 3
        const val MIN_OBJECT_COUNT = 3
        const val MIN_RECOGNITION_CONFIDENCE = 0.7
    }
}
