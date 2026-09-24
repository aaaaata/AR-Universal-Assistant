package com.example.ai_assist.framework

import com.example.ai_assist.scene.mahjong.framework.MahjongSceneProvider
import com.example.ai_assist.sdk.framework.CvGameCore

/** 编译期场景注册入口。现有页面暂不调用，后续只在这里增加场景。 */
fun registerScenes(core: CvGameCore) {
    core.registerScene(MahjongSceneProvider())
}
