package com.example.ai_assist.sdk.cv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameProcessingGateTest {
    /** 验证无抖动策略下的固定间隔和采集阶段互斥。 */
    @Test
    fun `按间隔且单路选择采集帧`() {
        val gate = FrameProcessingGate(intervalMillis = 1_000L)

        assertTrue(gate.tryBeginCapture(0L))
        assertFalse(gate.tryBeginCapture(1_000L))
        gate.completeCapture()
        assertFalse(gate.tryBeginCapture(999L))
        assertTrue(gate.tryBeginCapture(1_000L))
    }

    /** 验证采集到处理的原子交接阻止其他帧进入。 */
    @Test
    fun `处理期间拒绝新采集和重复处理`() {
        val gate = FrameProcessingGate(intervalMillis = 1_000L)

        assertTrue(gate.tryBeginCapture(1_000L))
        assertTrue(gate.tryBeginProcessing())
        assertFalse(gate.tryBeginProcessing())
        assertFalse(gate.tryBeginCapture(2_000L))
        gate.completeProcessing()
        assertTrue(gate.tryBeginCapture(2_000L))
    }

    /** 验证业务要求复核时，下一帧不必等待固定采样间隔。 */
    @Test
    fun `允许立即选择复核帧`() {
        val gate = FrameProcessingGate(intervalMillis = 1_000L)

        assertTrue(gate.tryBeginCapture(1_000L))
        assertTrue(gate.tryBeginProcessing())
        gate.completeProcessing(allowImmediateNextCapture = true)

        assertTrue(gate.tryBeginCapture(1_001L))
    }

    /** 验证门禁拒绝无效的时间间隔。 */
    @Test
    fun `拒绝非正数间隔`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameProcessingGate(intervalMillis = 0L)
        }
    }
}
