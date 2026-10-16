package com.example.ai_assist

import com.example.ai_assist.sdk.device.rayneo.x3.MercuryInputActionMapper
import com.example.ai_assist.sdk.capability.InputAction
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import org.junit.Assert.assertEquals
import org.junit.Test

class MercuryInputActionMapperTest {
    @Test
    fun mapsSupportedMercuryActionsToCommonActions() {
        val mappings = listOf(
            TempleAction.Click(false, 1L) to InputAction.CLICK,
            TempleAction.DoubleClick(false, 2L) to InputAction.DOUBLE_CLICK,
            TempleAction.TripleClick(false, 3L) to InputAction.TRIPLE_CLICK
        )

        mappings.forEach { (source, expected) ->
            assertEquals(expected, MercuryInputActionMapper.map(source))
        }
    }

    @Test
    fun mapsUnsupportedMercuryActionToUnknown() {
        assertEquals(
            InputAction.UNKNOWN,
            MercuryInputActionMapper.map(TempleAction.LongClick(false, 4L))
        )
    }
}
