package com.example.ai_assist.sdk.network

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClientConfigTest {
    @Test
    fun acceptsValidBaseUrlAndTimeout() {
        val config = NetworkClientConfig("https://example.invalid/", timeoutMillis = 3_000L)

        assertEquals("https://example.invalid/", config.baseUrl)
        assertEquals(3_000L, config.timeoutMillis)
    }

    @Test
    fun cloudInferenceUsesTwoMinuteTimeoutByDefault() {
        val config = NetworkClientConfig("https://example.invalid/")

        assertEquals(120_000L, config.timeoutMillis)
    }

    @Test
    fun rejectsBaseUrlWithoutTrailingSlash() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkClientConfig("https://example.invalid")
        }
    }

    @Test
    fun defaultMetadataIsTraceable() {
        val metadata = DefaultRequestMetadataProvider.create()

        assertEquals(metadata.requestId, UUID.fromString(metadata.requestId).toString())
        assertTrue(metadata.createdAtMillis > 0)
    }
}
