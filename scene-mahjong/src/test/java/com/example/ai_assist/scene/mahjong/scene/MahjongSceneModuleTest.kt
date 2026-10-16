package com.example.ai_assist.scene.mahjong.scene

import com.example.ai_assist.scene.mahjong.BuildConfig
import com.example.ai_assist.scene.mahjong.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MahjongSceneModuleTest {
    @Test
    fun createsCompleteRegistrationInsideSceneModule() {
        val registration = MahjongSceneModule().createRegistration()
        val config = registration.config as MahjongSceneConfig

        assertEquals(MahjongSceneProvider.SCENE_ID, registration.provider.descriptor.sceneId)
        assertEquals(BuildConfig.ASSIST_SERVER_BASE_URL, config.serverBaseUrl)
        assertEquals(MahjongRecordingMode.NORMAL, config.recordingMode)
        assertEquals(listOf("normal", "regression"), config.startModes.map { it.id })
        assertTrue(config.startModes.last().isRecording)
        assertEquals(BuildConfig.ASSIST_API_KEY, registration.credential)
        assertEquals("AR麻将助手", registration.presentation.menuTitle)
        assertEquals(R.drawable.ic_scene_mahjong_app, registration.presentation.menuIconRes)
        assertNotNull(registration.presentation.initialPayload)
    }
}
