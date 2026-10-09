package com.example.ai_assist.connection

import com.example.ai_assist.model.ServiceMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudServiceConnectorTest {
    @Test
    fun createsCloudConfigurationAfterReadinessSucceeds() = runBlocking {
        val connector = CloudServiceConnector(
            readinessProbe = ServiceReadinessProbe { true },
            cloudBaseUrl = "https://cloud.example.test/api"
        )

        val config = connector.connect("fake-scene").getOrThrow()

        assertEquals("https://cloud.example.test/api/", config.serverUrl)
        assertEquals(ServiceMode.CLOUD, config.serviceMode)
        assertEquals("fake-scene", config.sceneId)
        assertEquals("云端服务", config.label)
    }

    @Test
    fun rejectsCloudConfigurationWhenServiceIsNotReady() = runBlocking {
        val result = CloudServiceConnector(
            readinessProbe = ServiceReadinessProbe { false },
            cloudBaseUrl = "https://cloud.example.test/"
        ).connect("fake-scene")

        assertTrue(result.isFailure)
    }
}
