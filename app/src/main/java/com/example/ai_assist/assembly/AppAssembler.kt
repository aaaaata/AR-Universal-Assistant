package com.example.ai_assist.assembly

import android.content.Context
import com.example.ai_assist.scene.SceneController
import com.example.ai_assist.connection.ServerQrConnectionController
import com.example.ai_assist.sdk.engine.CredentialProvider
import com.example.ai_assist.sdk.platform.android.assembly.AndroidSdkAssembler
import com.example.ai_assist.sdk.platform.android.assembly.AndroidSceneModuleDiscovery
import com.example.ai_assist.ui.SceneRenderRouter

/** App 装配完成后交给主页面使用的对象。 */
internal data class AppEnvironment(
    val sceneController: SceneController,
    val sceneRenderRouter: SceneRenderRouter,
    val serverQrConnectionController: ServerQrConnectionController
)

/** 发现 APK 中声明的场景模块，并把 App 与 Android SDK 装配起来。 */
internal object AppAssembler {
    fun assemble(context: Context): AppEnvironment {
        val registrations = AndroidSceneModuleDiscovery.discover(context.applicationContext)
        val credentials = registrations.associate {
            it.provider.descriptor.sceneId to it.credential
        }
        val sdkEnvironment = AndroidSdkAssembler.assemble(
            context = context.applicationContext,
            scenes = registrations.map { it.provider },
            credentialProvider = CredentialProvider { sceneId -> credentials[sceneId] }
        )
        val sceneController = SceneController(
                sdkEnvironment.sceneEngine,
                sdkEnvironment.inputPublisher,
                registrations
            )
        return AppEnvironment(
            sceneController = sceneController,
            sceneRenderRouter = SceneRenderRouter(registrations),
            serverQrConnectionController = ServerQrConnectionController(
                sdkEnvironment.cameraCapability,
                sceneController
            )
        )
    }
}
