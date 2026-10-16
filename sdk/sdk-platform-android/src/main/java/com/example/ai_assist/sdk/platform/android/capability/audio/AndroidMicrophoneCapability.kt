package com.example.ai_assist.sdk.platform.android.capability.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.ai_assist.sdk.capability.AudioCaptureConfig
import com.example.ai_assist.sdk.capability.AudioChunk
import com.example.ai_assist.sdk.capability.MicrophoneCapability
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 使用 Android AudioRecord 发布 PCM 16 位音频块。 */
internal class AndroidMicrophoneCapability(context: Context) : MicrophoneCapability {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val mutableChunks = MutableSharedFlow<AudioChunk>(
        extraBufferCapacity = AUDIO_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var closed = false

    override val chunks: Flow<AudioChunk> = mutableChunks.asSharedFlow()

    override fun isAvailable(): Boolean =
        synchronized(lock) { !closed } &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) &&
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun supportedFeatures(): Set<String> = setOf("pcm-16bit", "mono", "stereo")

    @SuppressLint("MissingPermission")
    override fun start(config: AudioCaptureConfig) {
        val channelMask = channelMask(config.channelCount)
        synchronized(lock) {
            check(!closed) { "Microphone capability has been closed" }
            check(
                appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            ) { "Record audio permission is not granted" }
            if (audioRecord != null) return

            val minimumBufferSize = AudioRecord.getMinBufferSize(
                config.sampleRateHz,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            require(minimumBufferSize > 0) {
                "Unsupported audio capture configuration: ${config.sampleRateHz} Hz, " +
                    "${config.channelCount} channels"
            }
            val bufferSize = maxOf(
                minimumBufferSize,
                config.sampleRateHz * config.channelCount * BYTES_PER_SAMPLE / 5
            )
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRateHz)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                error("Unable to initialize AudioRecord")
            }
            try {
                record.startRecording()
            } catch (error: Throwable) {
                record.release()
                throw error
            }
            audioRecord = record
            recordingThread = Thread(
                { readAudio(record, bufferSize) },
                "assist-sdk-microphone"
            ).apply { start() }
        }
    }

    override fun stop() {
        val record: AudioRecord
        val thread: Thread?
        synchronized(lock) {
            record = audioRecord ?: return
            thread = recordingThread
            audioRecord = null
            recordingThread = null
        }
        runCatching { record.stop() }
        if (thread !== Thread.currentThread()) runCatching { thread?.join(STOP_JOIN_MILLIS) }
        record.release()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stop()
    }

    private fun readAudio(record: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        while (synchronized(lock) { audioRecord === record }) {
            val byteCount = runCatching {
                record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            }.getOrNull() ?: break
            if (byteCount > 0) {
                mutableChunks.tryEmit(
                    AudioChunk(
                        pcmBytes = buffer.copyOf(byteCount),
                        capturedAtMillis = System.currentTimeMillis()
                    )
                )
            } else break
        }
    }

    private fun channelMask(channelCount: Int): Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_IN_MONO
        2 -> AudioFormat.CHANNEL_IN_STEREO
        else -> throw IllegalArgumentException("Android microphone supports 1 or 2 channels")
    }

    private companion object {
        const val AUDIO_BUFFER_CAPACITY = 8
        const val BYTES_PER_SAMPLE = 2
        const val STOP_JOIN_MILLIS = 1_000L
    }
}
