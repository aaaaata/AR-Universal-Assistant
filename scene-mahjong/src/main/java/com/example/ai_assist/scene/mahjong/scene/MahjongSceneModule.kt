package com.example.ai_assist.scene.mahjong.scene

import com.example.ai_assist.scene.mahjong.BuildConfig
import com.example.ai_assist.scene.mahjong.R
import com.example.ai_assist.scene.mahjong.ui.MahjongScenePayload
import com.example.ai_assist.sdk.scene.SceneModule
import com.example.ai_assist.sdk.scene.ScenePresentation
import com.example.ai_assist.sdk.scene.SceneRegistration

/** 麻将场景模块自己的安装入口；通用 App 无需认识麻将实现。 */
class MahjongSceneModule : SceneModule {
    override fun createRegistration(): SceneRegistration =
        SceneRegistration(
            provider = MahjongSceneProvider(),
            config = MahjongSceneConfig(
                serverBaseUrl = BuildConfig.ASSIST_SERVER_BASE_URL,
                serviceMode = if (BuildConfig.ASSIST_SERVER_ENV == "local") {
                    MahjongServiceMode.LOCAL
                } else {
                    MahjongServiceMode.CLOUD
                },
                apiKey = BuildConfig.ASSIST_API_KEY,
                localServerBaseUrl = BuildConfig.LOCAL_SERVER_BASE_URL,
                localApiKey = BuildConfig.LOCAL_API_KEY,
                cloudServerBaseUrl = BuildConfig.CLOUD_SERVER_BASE_URL,
                cloudApiKey = BuildConfig.CLOUD_API_KEY
            ),
            credential = BuildConfig.ASSIST_API_KEY.takeIf(String::isNotBlank),
            presentation = ScenePresentation(
                menuTitle = "AR麻将助手",
                menuIconRes = R.drawable.ic_scene_mahjong_app,
                initialPayload = MahjongScenePayload.EMPTY
            )
        )
}
