package com.example.ai_assist.framework

import com.example.ai_assist.sdk.framework.CapabilityLease
import com.example.ai_assist.sdk.framework.CapabilityType
import com.example.ai_assist.sdk.framework.ResourceManager

/** 当前 Android 宿主的能力租约管理；本阶段只开放麻将所需的相机能力。 */
class AndroidResourceManager(
    private val cameraScheduler: Camera2FrameScheduler
) : ResourceManager {
    private var cameraLease: CapabilityLease? = null

    @Synchronized
    override fun acquire(type: CapabilityType, sceneId: String): CapabilityLease {
        require(type == CapabilityType.CAMERA) { "Unsupported capability: $type" }
        return cameraLease ?: (object : CapabilityLease {}).also { cameraLease = it }
    }

    @Synchronized
    override fun releaseAll() {
        cameraLease = null
        cameraScheduler.stop()
    }
}
