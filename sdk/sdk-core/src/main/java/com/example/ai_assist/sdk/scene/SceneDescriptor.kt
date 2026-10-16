package com.example.ai_assist.sdk.scene

import com.example.ai_assist.sdk.capability.CapabilityRequirement

/**
 * 场景的静态描述信息。
 *
 * @property sceneId 场景唯一 ID。
 * @property name 面向用户的场景名称。
 * @property version 场景实现版本。
 * @property requiredCapabilities 启动场景必须具备的能力集合。
 * @property optionalCapabilities 场景可按需使用、缺失时仍可启动的能力集合。
 */
data class SceneDescriptor(
    val sceneId: String,
    val name: String,
    val version: String,
    val requiredCapabilities: Set<CapabilityRequirement> = emptySet(),
    val optionalCapabilities: Set<CapabilityRequirement> = emptySet()
) {
    init {
        require(sceneId.isNotBlank()) { "sceneId must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(
            requiredCapabilities.map { it.capabilityId }.toSet()
                .intersect(optionalCapabilities.map { it.capabilityId }.toSet())
                .isEmpty()
        ) { "A capability cannot be both required and optional" }
    }
}
