package com.example.ai_assist.scene.mahjong.framework

import com.example.ai_assist.scene.mahjong.R
import com.example.ai_assist.sdk.framework.SceneModule
import com.example.ai_assist.sdk.framework.ScenePresentation
import com.example.ai_assist.sdk.framework.SceneRegistration

/** 麻将模块自己的安装入口；通用宿主无需认识麻将实现类。 */
class MahjongSceneModule : SceneModule {
    override fun createRegistration(): SceneRegistration = SceneRegistration(
        provider = MahjongSceneProvider(),
        presentation = ScenePresentation(
            menuTitle = "AR麻将助手",
            menuIconRes = R.drawable.ic_scene_mahjong_app
        )
    )
}
