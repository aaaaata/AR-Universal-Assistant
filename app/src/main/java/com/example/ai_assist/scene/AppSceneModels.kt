package com.example.ai_assist.scene

import com.example.ai_assist.sdk.capability.CapabilityRequirement

/** 主菜单展示场景所需的稳定信息。 */
internal data class AppSceneSummary(
    val sceneId: String,
    val title: String,
    val iconRes: Int,
    val requiredCapabilities: Set<CapabilityRequirement>
)
