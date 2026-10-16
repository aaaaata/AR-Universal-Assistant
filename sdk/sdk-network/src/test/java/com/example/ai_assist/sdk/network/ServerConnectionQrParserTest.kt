package com.example.ai_assist.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerConnectionQrParserTest {
    @Test
    fun parsesMahjongConfigurationQrCode() {
        val result = ServerConnectionQrParser.parse(
            "rayneo-assist://configure?version=1&scene=mahjong&" +
                "base_url=http%3A%2F%2F192.168.1.25%3A8000"
        )

        assertEquals("mahjong", result.sceneId)
        assertEquals("http://192.168.1.25:8000/", result.baseUrl)
    }

    @Test
    fun normalizesSafeHttpPaths() {
        assertEquals(
            "https://example.com/api/",
            ServerConnectionQrParser.normalizeBaseUrl("HTTPS://example.com/api")
        )
    }

    @Test
    fun rejectsCredentialsQueryAndUnsupportedSchemes() {
        listOf(
            "ftp://192.168.1.2/",
            "http://user:password@192.168.1.2/",
            "http://192.168.1.2/?token=secret",
            "http://192.168.1.2/#fragment"
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerConnectionQrParser.normalizeBaseUrl(value)
            }
        }
    }

    @Test
    fun rejectsWrongProtocolAndDuplicateParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConnectionQrParser.parse(
                "https://configure?version=1&scene=mahjong&base_url=http%3A%2F%2Flocalhost"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerConnectionQrParser.parse(
                "rayneo-assist://configure?version=1&scene=mahjong&scene=other&" +
                    "base_url=http%3A%2F%2Flocalhost"
            )
        }
    }
}
