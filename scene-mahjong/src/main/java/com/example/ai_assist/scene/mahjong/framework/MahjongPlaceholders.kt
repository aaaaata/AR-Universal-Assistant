package com.example.ai_assist.scene.mahjong.framework

import android.view.ViewGroup
import com.example.ai_assist.sdk.framework.CapabilityType
import com.example.ai_assist.sdk.framework.SceneConfig
import com.example.ai_assist.sdk.framework.SceneContent
import com.example.ai_assist.sdk.framework.SceneContext
import com.example.ai_assist.sdk.framework.ScenePayload
import com.example.ai_assist.sdk.framework.SceneProvider
import com.example.ai_assist.sdk.framework.SceneRenderer
import com.example.ai_assist.sdk.framework.SceneResult
import kotlinx.coroutines.flow.Flow

/** 麻将 HUD 使用的三字段结果。类型信息以后再填。 */
data class MahjongScenePayload(
    val userHand: List<String>,
    val meldedTiles: List<String>,
    val suggestedPlay: String
) : ScenePayload {
    companion object {
        const val RENDERER_KEY = "mahjong.result.v1"
    }
}

/** 云端麻将分析结果占位。字段以后按云端接口填。 */
interface MahjongCloudResult

/** 云端会话、上传图片和结束会话。 */
interface MahjongCloudService {
    suspend fun createSession(): Result<String>
    suspend fun submitImage(sessionId: String, image: ByteArray): Result<MahjongCloudResult>
    suspend fun endSession(sessionId: String): Result<Unit>
}

/** 云端结果到场景模型。13 张进张、14 张出牌以后在 map 里区分。 */
interface MahjongFieldMapper {
    fun map(cloudResult: MahjongCloudResult): MahjongScenePayload
    fun parseTile(raw: String): String
}

/** 麻将业务结果渲染器。 */
class MahjongRenderer : SceneRenderer {
    override val rendererKey: String = MahjongScenePayload.RENDERER_KEY

    override fun render(container: ViewGroup, content: SceneContent) {
        TODO("渲染手牌、副露、进张、建议与置信度")
    }

    override fun clear() {
        TODO("清空渲染区")
    }
}

/** 麻将场景占位。采集、上传和映射以后再填。 */
class MahjongSceneProvider : SceneProvider {
    override val sceneId: String = "mahjong"
    override val sceneName: String = "麻将助手"
    override val version: String = "1.0.0"
    override val requiredCapabilities: List<CapabilityType> =
        listOf(CapabilityType.CAMERA, CapabilityType.INPUT)

    override fun createSceneConfig(): SceneConfig {
        TODO("麻将场景配置：服务地址、1 FPS、超时")
    }

    override fun createRenderer(): SceneRenderer = MahjongRenderer()

    override fun start(sceneContext: SceneContext): Flow<SceneResult> {
        TODO("创建云端会话，订阅相机帧，映射为 SceneResult")
    }

    override fun stop() {
        TODO("结束云端会话")
    }
}
