package com.example.ai_assist.sdk.capability

import com.example.ai_assist.sdk.runtime.CapabilityRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class CapabilityRegistryTest {
    @Test
    fun exclusiveLeaseRejectsDuplicateAndCanBeReacquiredAfterRelease() {
        val registry = CapabilityRegistry()
        val camera = RecordingCapability()
        registry.register(StandardCapabilities.CAMERA, camera)
        val requirement = CapabilityRequirement(StandardCapabilities.CAMERA, exclusive = true)

        val firstLease = registry.acquire("session-1", setOf(requirement))
        assertSame(
            camera,
            firstLease.requireCapability(StandardCapabilities.CAMERA, RecordingCapability::class.java)
        )
        expectThrows<CapabilityLeaseConflictException> {
            registry.acquire("session-2", setOf(requirement))
        }

        firstLease.release()
        firstLease.release()
        assertEquals(1, camera.leaseReleaseCount)

        registry.acquire("session-2", setOf(requirement)).release()
        assertEquals(2, camera.leaseReleaseCount)
    }

    @Test
    fun missingRequirementDoesNotKeepPartialLease() {
        val registry = CapabilityRegistry()
        val input = RecordingCapability()
        registry.register(StandardCapabilities.INPUT, input)
        val requirements = setOf(
            CapabilityRequirement(StandardCapabilities.INPUT),
            CapabilityRequirement(StandardCapabilities.CAMERA, exclusive = true)
        )

        val error = expectThrows<CapabilityUnavailableException> {
            registry.acquire("session-1", requirements)
        }

        assertEquals(StandardCapabilities.CAMERA, error.capabilityId)
        registry.acquire(
            "session-2",
            setOf(CapabilityRequirement(StandardCapabilities.INPUT, exclusive = true))
        ).release()
        assertEquals(1, input.leaseReleaseCount)
    }

    @Test
    fun clearPermanentlyClosesRegisteredCapabilities() {
        val registry = CapabilityRegistry()
        val camera = RecordingCapability()
        registry.register(StandardCapabilities.CAMERA, camera)

        registry.clear()

        assertEquals(1, camera.closeCount)
    }

    @Test
    fun missingOptionalCapabilityDoesNotBlockLease() {
        val registry = CapabilityRegistry()
        val input = RecordingCapability()
        registry.register(StandardCapabilities.INPUT, input)

        val lease = registry.acquire(
            sessionId = "session-1",
            requirements = emptySet(),
            optionalRequirements = setOf(
                CapabilityRequirement(StandardCapabilities.INPUT),
                CapabilityRequirement(StandardCapabilities.AUDIO_OUTPUT)
            )
        )

        assertSame(
            input,
            lease.optionalCapability(StandardCapabilities.INPUT, RecordingCapability::class.java)
        )
        assertNull(
            lease.optionalCapability(
                StandardCapabilities.AUDIO_OUTPUT,
                RecordingCapability::class.java
            )
        )
        lease.release()
    }

    @Test
    fun unavailableRequiredCapabilityIsRejected() {
        val registry = CapabilityRegistry()
        registry.register(StandardCapabilities.NETWORK, RecordingCapability(available = false))

        val error = expectThrows<CapabilityUnavailableException> {
            registry.acquire(
                "session-1",
                setOf(CapabilityRequirement(StandardCapabilities.NETWORK))
            )
        }

        assertEquals(StandardCapabilities.NETWORK, error.capabilityId)
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    }
}

private class RecordingCapability(
    private val available: Boolean = true
) : SdkCapability {
    var leaseReleaseCount = 0
    var closeCount = 0

    override fun isAvailable(): Boolean = available

    override fun onLeaseReleased() {
        leaseReleaseCount++
    }

    override fun close() {
        closeCount++
    }
}
