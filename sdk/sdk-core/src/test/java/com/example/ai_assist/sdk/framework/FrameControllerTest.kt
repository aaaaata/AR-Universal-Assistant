package com.example.ai_assist.sdk.framework

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameControllerTest {
    @Test
    fun overduePlanRunsOnFirstFrameAfterInFlightCompletes() {
        var now = 1_000L
        val controller = DefaultFrameController { now }
        controller.scheduleCaptureAt(1_100L)

        assertFalse(controller.tryBeginFrame(1_099L))
        assertTrue(controller.tryBeginFrame(1_100L))

        controller.scheduleCaptureAt(1_300L)
        assertFalse(controller.tryBeginFrame(1_500L))
        controller.completeFrame()
        assertTrue(controller.tryBeginFrame(1_501L))
    }

    @Test
    fun latestScheduleReplacesPendingPlanAndCancelClearsIt() {
        var now = 2_000L
        val controller = DefaultFrameController { now }
        controller.scheduleCaptureAfter(500L)
        controller.scheduleCaptureAt(3_000L)

        assertFalse(controller.tryBeginFrame(2_999L))
        controller.cancelScheduledCapture()
        assertFalse(controller.tryBeginFrame(3_000L))
    }

    @Test
    fun negativeDelayIsRejected() {
        val controller = DefaultFrameController { 0L }

        assertTrue(
            runCatching { controller.scheduleCaptureAfter(-1L) }.exceptionOrNull()
                is IllegalArgumentException
        )
    }

    @Test
    fun closedControllerRejectsNewPlans() {
        val controller = DefaultFrameController { 0L }
        controller.close()

        assertTrue(
            runCatching { controller.scheduleCaptureAfter() }.exceptionOrNull()
                is IllegalStateException
        )
        assertFalse(controller.tryBeginFrame(Long.MAX_VALUE))
    }
}
