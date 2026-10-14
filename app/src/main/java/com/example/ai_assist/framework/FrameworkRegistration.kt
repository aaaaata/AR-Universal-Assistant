package com.example.ai_assist.framework

import com.example.ai_assist.scene.mahjong.framework.MahjongSceneModule
import com.example.ai_assist.sdk.framework.CvGameCore
import com.example.ai_assist.sdk.framework.SceneRegistration

/** 编译期场景注册入口。App 启动时调用，后续只在这里增加场景。 */
fun registerScenes(core: CvGameCore): List<SceneRegistration> =
    listOf(MahjongSceneModule().createRegistration()).onEach {
        core.registerScene(it.provider)
    }
