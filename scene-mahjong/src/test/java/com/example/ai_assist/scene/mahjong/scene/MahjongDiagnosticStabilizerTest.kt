package com.example.ai_assist.scene.mahjong.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MahjongDiagnosticStabilizerTest {
    @Test
    fun confirmsOnlyAfterThreeMatchingDiagnosticFrames() {
        val stabilizer = MahjongDiagnosticStabilizer()

        assertNull(stabilizer.evaluate("INVALID_TILE_COUNT"))
        assertNull(stabilizer.evaluate("INVALID_TILE_COUNT"))
        assertEquals(
            "INVALID_TILE_COUNT",
            stabilizer.evaluate("INVALID_TILE_COUNT")?.code
        )
        assertNull(stabilizer.evaluate("INVALID_TILE_COUNT"))
    }

    @Test
    fun clearsConfirmedWarningWhenTheDiagnosticChangesOrDisappears() {
        val stabilizer = MahjongDiagnosticStabilizer()
        repeat(3) { stabilizer.evaluate("INVALID_TILE_COUNT") }

        assertEquals(null, stabilizer.evaluate("NO_VALID_HAND_TILES")?.code)
        assertNull(stabilizer.evaluate("NO_VALID_HAND_TILES"))
        assertEquals(
            "NO_VALID_HAND_TILES",
            stabilizer.evaluate("NO_VALID_HAND_TILES")?.code
        )
        assertEquals(null, stabilizer.evaluate(null)?.code)
    }
}
