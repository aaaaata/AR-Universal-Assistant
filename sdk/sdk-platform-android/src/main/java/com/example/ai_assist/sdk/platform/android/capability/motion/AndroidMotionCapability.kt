package com.example.ai_assist.sdk.platform.android.capability.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.example.ai_assist.sdk.capability.MotionCapability
import com.example.ai_assist.sdk.capability.MotionSample
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 使用 Android 加速度计和陀螺仪发布运动样本。 */
internal class AndroidMotionCapability(context: Context) : MotionCapability, SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val sensorThread = HandlerThread("assist-sdk-motion").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)
    private val lock = Any()
    private val mutableSamples = MutableSharedFlow<MotionSample>(
        extraBufferCapacity = SAMPLE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var latestAcceleration: FloatArray? = null
    private var started = false
    private var closed = false

    override val samples: Flow<MotionSample> = mutableSamples.asSharedFlow()

    override fun isAvailable(): Boolean = synchronized(lock) {
        accelerometer != null && gyroscope != null && !closed
    }

    override fun supportedFeatures(): Set<String> = setOf("accelerometer", "gyroscope")

    override fun start(samplingPeriodMicros: Int) {
        require(samplingPeriodMicros > 0) { "samplingPeriodMicros must be positive" }
        synchronized(lock) {
            check(!closed) { "Motion capability has been closed" }
            if (started) return
            val manager = checkNotNull(sensorManager) { "Sensor manager is unavailable" }
            val accelerationSensor = checkNotNull(accelerometer) { "Accelerometer is unavailable" }
            val angularVelocitySensor = checkNotNull(gyroscope) { "Gyroscope is unavailable" }
            val accelerationRegistered = manager.registerListener(
                this,
                accelerationSensor,
                samplingPeriodMicros,
                sensorHandler
            )
            val gyroscopeRegistered = manager.registerListener(
                this,
                angularVelocitySensor,
                samplingPeriodMicros,
                sensorHandler
            )
            if (!accelerationRegistered || !gyroscopeRegistered) {
                manager.unregisterListener(this)
                error("Unable to register required motion sensors")
            }
            started = true
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            latestAcceleration = null
            sensorManager?.unregisterListener(this)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stop()
        sensorThread.quitSafely()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> synchronized(lock) {
                if (started) latestAcceleration = event.values.copyAxisValues()
            }

            Sensor.TYPE_GYROSCOPE -> {
                val acceleration = synchronized(lock) {
                    if (started) latestAcceleration?.copyOf() else null
                } ?: return
                mutableSamples.tryEmit(
                    MotionSample(
                        timestampNanos = event.timestamp,
                        accelerationMetersPerSecondSquared = acceleration,
                        angularVelocityRadiansPerSecond = event.values.copyAxisValues()
                    )
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun FloatArray.copyAxisValues(): FloatArray = floatArrayOf(this[0], this[1], this[2])

    private companion object {
        const val SAMPLE_BUFFER_CAPACITY = 64
    }
}
