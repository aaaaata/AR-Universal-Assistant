package com.example.ai_assist

import android.Manifest
import com.example.ai_assist.permission.AndroidPermissionResolver
import com.example.ai_assist.permission.PermissionRequest
import com.example.ai_assist.permission.PermissionResult
import com.example.ai_assist.sdk.capability.CapabilityRequirement
import com.example.ai_assist.sdk.capability.StandardCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PermissionContractsTest {
    /** 空权限集合不是有效请求。 */
    @Test
    fun rejectsEmptyPermissionRequest() {
        assertThrows(IllegalArgumentException::class.java) {
            PermissionRequest(emptySet())
        }
    }

    /** 永久拒绝集合必须属于未授权集合。 */
    @Test
    fun rejectsUnrelatedPermanentlyDeniedPermission() {
        assertThrows(IllegalArgumentException::class.java) {
            PermissionResult.Denied(
                permissions = setOf("camera"),
                permanentlyDenied = setOf("location")
            )
        }
    }

    /** 需要敏感硬件的场景能力映射为对应 Android 运行时权限。 */
    @Test
    fun resolvesRuntimePermissionsFromSceneCapabilities() {
        assertEquals(
            setOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            AndroidPermissionResolver.resolve(
                setOf(
                    CapabilityRequirement(StandardCapabilities.CAMERA),
                    CapabilityRequirement(StandardCapabilities.MICROPHONE),
                    CapabilityRequirement(StandardCapabilities.LOCATION),
                    CapabilityRequirement(StandardCapabilities.INPUT)
                )
            )
        )
    }
}
