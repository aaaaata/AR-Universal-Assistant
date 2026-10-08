package com.example.ai_assist.scene.mahjong.framework

/** Confirms a diagnostic independently from the hand-result stabilizer. */
internal class MahjongDiagnosticStabilizer(
    private val requiredMatchingFrames: Int = REQUIRED_MATCHING_FRAMES
) {
    private var candidateCode: String? = null
    private var candidateCount = 0
    private var confirmedCode: String? = null

    data class Update(val code: String?)

    fun evaluate(diagnosticCode: String?): Update? {
        if (diagnosticCode == null) {
            candidateCode = null
            candidateCount = 0
            return clearConfirmed()
        }

        if (diagnosticCode != candidateCode) {
            candidateCode = diagnosticCode
            candidateCount = 1
            return clearConfirmed()
        }

        candidateCount++
        if (candidateCount < requiredMatchingFrames || confirmedCode == diagnosticCode) return null

        confirmedCode = diagnosticCode
        return Update(diagnosticCode)
    }

    private fun clearConfirmed(): Update? {
        if (confirmedCode == null) return null
        confirmedCode = null
        return Update(null)
    }

    private companion object {
        const val REQUIRED_MATCHING_FRAMES = 3
    }
}
