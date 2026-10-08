package com.example.ai_assist.scene.mahjong.framework

import android.view.ViewGroup
import com.example.ai_assist.sdk.framework.SceneContent
import com.example.ai_assist.sdk.framework.ScenePayload
import com.example.ai_assist.sdk.framework.SceneRenderer

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

/** 云端麻将分析结果的场景边界，具体协议 DTO 通过实现该接口接入。 */
interface MahjongCloudResult

/** 云端会话、上传图片和结束会话。 */
data class MahjongImageSubmission(
    val bytes: ByteArray,
    val capturedAtMillis: Long,
    val imageRotationDegrees: Int
)

interface MahjongCloudService {
    suspend fun createSession(): Result<String>
    suspend fun submitImage(
        sessionId: String,
        image: MahjongImageSubmission
    ): Result<MahjongCloudResult>
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
        require(content.rendererKey == rendererKey) {
            "Unsupported renderer key: ${content.rendererKey}"
        }
    }

    override fun clear() = Unit
}
