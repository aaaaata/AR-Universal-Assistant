package com.example.ai_assist.connection

import com.example.ai_assist.model.ServiceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerConnectionQrParserTest {
    @Test
    fun parsesLocalMahjongConfiguration() {
        val config = ServerConnectionQrParser.parse(
            "rayneo-assist://configure?version=1&scene=mahjong&" +
                "base_url=http%3A%2F%2F192.168.1.25%3A8000"
        )

        assertEquals("mahjong", config.sceneId)
        assertEquals("http://192.168.1.25:8000/", config.serverUrl)
        assertEquals(ServiceMode.LOCAL, config.serviceMode)
    }

    @Test
    fun rejectsUnsafeOrIncompatibleConfiguration() {
        listOf(
            "rayneo-assist://configure?version=2&scene=mahjong&base_url=http%3A%2F%2Flocalhost",
            "rayneo-assist://configure?version=1&scene=other&base_url=http%3A%2F%2Flocalhost",
            "rayneo-assist://configure?version=1&scene=mahjong&base_url=ftp%3A%2F%2Flocalhost"
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerConnectionQrParser.parse(value)
            }
        }
    }
}
