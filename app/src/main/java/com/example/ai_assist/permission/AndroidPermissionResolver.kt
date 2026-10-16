package com.example.ai_assist.permission

import android.Manifest
import com.example.ai_assist.sdk.capability.CapabilityRequirement
import com.example.ai_assist.sdk.capability.StandardCapabilities

/** 将场景声明的通用能力转换为当前 Android 宿主需要的运行时权限。 */
internal object AndroidPermissionResolver {
    /** 返回指定能力集合对应的 Android 运行时权限。 */
    fun resolve(requirements: Set<CapabilityRequirement>): Set<String> =
        requirements.mapNotNullTo(linkedSetOf()) { requirement ->
            when (requirement.capabilityId) {
                StandardCapabilities.CAMERA -> Manifest.permission.CAMERA
                StandardCapabilities.MICROPHONE -> Manifest.permission.RECORD_AUDIO
                StandardCapabilities.LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
                else -> null
            }
        }
}
