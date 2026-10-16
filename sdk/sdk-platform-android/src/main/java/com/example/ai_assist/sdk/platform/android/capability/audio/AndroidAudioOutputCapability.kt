package com.example.ai_assist.sdk.platform.android.capability.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.ai_assist.sdk.capability.AudioOutputCapability
import java.io.File

/** 使用 Android MediaPlayer 播放场景提供的本地音频文件。 */
internal class AndroidAudioOutputCapability(context: Context) : AudioOutputCapability {
    private val packageManager = context.applicationContext.packageManager
    private val lock = Any()
    private var player: MediaPlayer? = null
    private var closed = false

    override fun isAvailable(): Boolean =
        synchronized(lock) { !closed } &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

    override fun supportedFeatures(): Set<String> = setOf("local-file-playback")

    override fun play(file: File) {
        require(file.isFile && file.canRead()) { "Audio file is not readable: ${file.path}" }
        synchronized(lock) {
            check(!closed) { "Audio output capability has been closed" }
            releasePlayerLocked()
            val newPlayer = MediaPlayer()
            player = newPlayer
            try {
                newPlayer.apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { completed -> releasePlayer(completed) }
                    setOnErrorListener { failed, _, _ ->
                        releasePlayer(failed)
                        true
                    }
                    prepare()
                    start()
                }
            } catch (error: Throwable) {
                if (player === newPlayer) releasePlayerLocked()
                throw error
            }
        }
    }

    override fun stop() {
        synchronized(lock) { releasePlayerLocked() }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            releasePlayerLocked()
        }
    }

    private fun releasePlayer(expected: MediaPlayer) {
        synchronized(lock) {
            if (player === expected) releasePlayerLocked()
        }
    }

    private fun releasePlayerLocked() {
        val current = player ?: return
        player = null
        runCatching { current.stop() }
        runCatching { current.reset() }
        runCatching { current.release() }
    }
}
