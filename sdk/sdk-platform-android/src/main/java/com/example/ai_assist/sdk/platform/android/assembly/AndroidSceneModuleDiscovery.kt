package com.example.ai_assist.sdk.platform.android.assembly

import android.content.Context
import android.content.pm.PackageManager
import com.example.ai_assist.sdk.scene.SceneModule
import com.example.ai_assist.sdk.scene.SceneRegistration

/** 从合并后的 Android Manifest 中发现 APK 已安装的场景模块。 */
object AndroidSceneModuleDiscovery {
    const val META_DATA_PREFIX = "com.example.ai_assist.sdk.scene_module."

    @Suppress("DEPRECATION")
    fun discover(context: Context): List<SceneRegistration> {
        val applicationInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        val metadata = applicationInfo.metaData ?: return emptyList()
        return metadata.keySet()
            .asSequence()
            .filter { it.startsWith(META_DATA_PREFIX) }
            .sorted()
            .map { key ->
                val className = requireNotNull(metadata.getString(key)) {
                    "Scene module metadata must contain a class name: $key"
                }
                instantiate(context, className).createRegistration()
            }
            .toList()
    }

    private fun instantiate(context: Context, className: String): SceneModule {
        val instance = context.classLoader
            .loadClass(className)
            .getDeclaredConstructor()
            .newInstance()
        return instance as? SceneModule
            ?: error("Scene module does not implement SceneModule: $className")
    }
}
