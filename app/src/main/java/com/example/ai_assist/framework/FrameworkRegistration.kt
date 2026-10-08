package com.example.ai_assist.framework

import com.example.ai_assist.scene.mahjong.framework.MahjongSceneProvider
import com.example.ai_assist.sdk.framework.CvGameCore

/** 编译期场景注册入口；允许宿主持有同一 Provider/Renderer 实例。 */
fun registerScenes(core: CvGameCore, mahjongProvider: MahjongSceneProvider) {
    core.registerScene(mahjongProvider)
}
