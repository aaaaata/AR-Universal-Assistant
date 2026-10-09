package com.example.ai_assist.framework

import android.content.Context
import android.content.pm.PackageManager
import com.example.ai_assist.sdk.framework.CvGameCore
import com.example.ai_assist.sdk.framework.SceneModule
import com.example.ai_assist.sdk.framework.SceneRegistration

private const val SCENE_MODULE_PREFIX = "com.example.ai_assist.sdk.scene_module."

/** 从合并后的 Manifest 发现并注册场景，App 不持有任何具体场景类型。 */
fun discoverAndRegisterScenes(context: Context, core: CvGameCore): List<SceneRegistration> {
    val applicationInfo = context.packageManager.getApplicationInfo(
        context.packageName,
        PackageManager.GET_META_DATA
    )
    val metadata = applicationInfo.metaData ?: return emptyList()
    return metadata.keySet()
        .filter { it.startsWith(SCENE_MODULE_PREFIX) }
        .sorted()
        .map { key ->
            val className = requireNotNull(metadata.getString(key)) {
                "Scene module class is missing for $key"
            }
            val module = Class.forName(className, true, context.classLoader)
                .getDeclaredConstructor()
                .newInstance() as? SceneModule
                ?: error("Scene module does not implement SceneModule: $className")
            module.createRegistration().also { core.registerScene(it.provider) }
        }
}
