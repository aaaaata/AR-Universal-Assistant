package com.example.ai_assist.sdk.cv

import com.example.ai_assist.sdk.scene.SceneNotice
import com.example.ai_assist.sdk.scene.SceneNoticeUpdate

/** Shared, UI-agnostic hysteresis for camera brightness and framing guidance. */
data class CaptureGuidanceConfig(
    val darkEnterLuma: Int = 100,
    val darkExitLuma: Int = 115,
    val darkEnterFrames: Int = 2,
    val darkExitFrames: Int = 2,
    val farEnterRatio: Double = 0.034,
    val farExitRatio: Double = 0.036,
    val nearEnterRatio: Double = 0.051,
    val nearExitRatio: Double = 0.048,
    val distanceEnterFrames: Int = 2,
    val distanceExitFrames: Int = 3,
    val minimumObjectCount: Int = 3
) {
    init {
        require(darkEnterLuma in 0..255 && darkExitLuma in 0..255)
        require(darkEnterLuma < darkExitLuma)
        require(darkEnterFrames > 0 && darkExitFrames > 0)
        require(farEnterRatio > 0.0 && farEnterRatio < farExitRatio)
        require(farExitRatio < nearExitRatio && nearExitRatio < nearEnterRatio)
        require(distanceEnterFrames > 0 && distanceExitFrames > 0)
        require(minimumObjectCount > 0)
    }
}

data class FramingSample(val imageWidth: Int, val objectWidths: List<Double>) {
    init {
        require(imageWidth > 0)
        require(objectWidths.all { it > 0.0 && it.isFinite() })
    }
}

data class CaptureGuidanceNotices(
    val tooDark: SceneNotice,
    val tooFar: SceneNotice,
    val tooNear: SceneNotice
) {
    init {
        require(setOf(tooDark.code, tooFar.code, tooNear.code).size == 3)
    }
}

/** Maintains capture-quality state without depending on a scene UI implementation. */
class CaptureGuidanceController(
    private val notices: CaptureGuidanceNotices,
    private val config: CaptureGuidanceConfig = CaptureGuidanceConfig()
) {
    private var dark = false
    private var darkEnterCount = 0
    private var darkExitCount = 0
    private var distanceState = DistanceState.NORMAL
    private var distanceCandidate = DistanceState.NORMAL
    private var distanceCandidateCount = 0
    private var distanceExitCount = 0

    @Synchronized
    fun onMedianLuma(medianLuma: Int): SceneNoticeUpdate? {
        require(medianLuma in 0..255)
        val wasDark = dark
        if (!dark) {
            darkEnterCount = if (medianLuma < config.darkEnterLuma) darkEnterCount + 1 else 0
            if (darkEnterCount >= config.darkEnterFrames) {
                dark = true
                darkEnterCount = 0
                darkExitCount = 0
            }
        } else {
            darkExitCount = if (medianLuma > config.darkExitLuma) darkExitCount + 1 else 0
            if (darkExitCount >= config.darkExitFrames) {
                dark = false
                darkExitCount = 0
            }
        }
        return when {
            dark -> SceneNoticeUpdate.Show(notices.tooDark)
            wasDark -> SceneNoticeUpdate.Dismiss(notices.tooDark.code)
            else -> null
        }
    }

    @Synchronized
    fun onFramingSample(sample: FramingSample): SceneNoticeUpdate? {
        if (sample.objectWidths.size < config.minimumObjectCount) return null
        val ratio = median(sample.objectWidths) / sample.imageWidth
        val previous = distanceState
        updateDistanceState(ratio)
        if (dark) return null
        return when (distanceState) {
            DistanceState.TOO_FAR -> SceneNoticeUpdate.Show(notices.tooFar)
            DistanceState.TOO_NEAR -> SceneNoticeUpdate.Show(notices.tooNear)
            DistanceState.NORMAL -> when (previous) {
                DistanceState.TOO_FAR -> SceneNoticeUpdate.Dismiss(notices.tooFar.code)
                DistanceState.TOO_NEAR -> SceneNoticeUpdate.Dismiss(notices.tooNear.code)
                DistanceState.NORMAL -> null
            }
        }
    }

    @Synchronized
    fun reset(): SceneNoticeUpdate? {
        val activeCode = when {
            dark -> notices.tooDark.code
            distanceState == DistanceState.TOO_FAR -> notices.tooFar.code
            distanceState == DistanceState.TOO_NEAR -> notices.tooNear.code
            else -> null
        }
        dark = false
        darkEnterCount = 0
        darkExitCount = 0
        distanceState = DistanceState.NORMAL
        distanceCandidate = DistanceState.NORMAL
        distanceCandidateCount = 0
        distanceExitCount = 0
        return activeCode?.let(SceneNoticeUpdate::Dismiss)
    }

    private fun updateDistanceState(ratio: Double) {
        when (distanceState) {
            DistanceState.NORMAL -> {
                val candidate = when {
                    ratio < config.farEnterRatio -> DistanceState.TOO_FAR
                    ratio > config.nearEnterRatio -> DistanceState.TOO_NEAR
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
                    if (distanceCandidateCount >= config.distanceEnterFrames) {
                        distanceState = candidate
                        distanceCandidate = DistanceState.NORMAL
                        distanceCandidateCount = 0
                        distanceExitCount = 0
                    }
                }
            }
            DistanceState.TOO_FAR -> {
                distanceExitCount = if (ratio > config.farExitRatio) distanceExitCount + 1 else 0
                if (distanceExitCount >= config.distanceExitFrames) resetDistance()
            }
            DistanceState.TOO_NEAR -> {
                distanceExitCount = if (ratio < config.nearExitRatio) distanceExitCount + 1 else 0
                if (distanceExitCount >= config.distanceExitFrames) resetDistance()
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
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private enum class DistanceState { NORMAL, TOO_FAR, TOO_NEAR }
}
