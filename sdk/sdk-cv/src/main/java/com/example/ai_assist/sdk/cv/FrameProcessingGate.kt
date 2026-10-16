package com.example.ai_assist.sdk.cv

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** 按间隔和随机抖动串行控制帧采集和后续处理。 */
class FrameProcessingGate(
    private val intervalMillis: Long,
    private val jitterMillis: Long = 0L,
    private val startupJitterMillis: Long = 0L,
    private val random: Random = Random.Default
) {
    private val stage = AtomicReference(Stage.IDLE)
    private val nextAllowedAtMillis = AtomicLong(NO_SELECTION)
    private val startupPending = AtomicBoolean(true)

    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        require(jitterMillis >= 0) { "jitterMillis must not be negative" }
        require(startupJitterMillis >= 0) { "startupJitterMillis must not be negative" }
    }

    /** 尝试按间隔取得一帧采集执行权。 */
    fun tryBeginCapture(frameAtMillis: Long): Boolean {
        if (stage.get() != Stage.IDLE) return false
        val nextAllowed = nextAllowedAtMillis.updateAndGet { current ->
            if (current != NO_SELECTION) current
            else if (startupPending.compareAndSet(true, false)) {
                frameAtMillis + random.nextLongInclusive(startupJitterMillis)
            } else {
                frameAtMillis
            }
        }
        if (frameAtMillis < nextAllowed) return false
        if (!stage.compareAndSet(Stage.IDLE, Stage.CAPTURING)) return false
        nextAllowedAtMillis.set(frameAtMillis + intervalMillis + random.nextLongInclusive(jitterMillis))
        return true
    }

    /** 标记当前帧采集或编码已经完成。 */
    fun completeCapture() {
        stage.compareAndSet(Stage.CAPTURING, Stage.IDLE)
    }

    /** 将当前帧从采集阶段原子切换到后续处理阶段。 */
    fun tryBeginProcessing(): Boolean = stage.compareAndSet(Stage.CAPTURING, Stage.PROCESSING)

    /** 标记当前帧后续处理已经完成，并可允许下一帧绕过常规采样间隔。 */
    fun completeProcessing(allowImmediateNextCapture: Boolean = false) {
        if (allowImmediateNextCapture && stage.get() == Stage.PROCESSING) {
            nextAllowedAtMillis.set(NO_SELECTION)
        }
        stage.compareAndSet(Stage.PROCESSING, Stage.IDLE)
    }

    private enum class Stage {
        IDLE,
        CAPTURING,
        PROCESSING
    }

    private companion object {
        const val NO_SELECTION = Long.MIN_VALUE
    }

    private fun Random.nextLongInclusive(maxMillis: Long): Long =
        if (maxMillis == 0L) 0L else nextLong(maxMillis + 1L)
}
