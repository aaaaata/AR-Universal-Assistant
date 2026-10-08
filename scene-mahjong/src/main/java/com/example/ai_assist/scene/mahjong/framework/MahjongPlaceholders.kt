package com.example.ai_assist.scene.mahjong.framework

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
    fun diagnosticMessage(code: String): String = code
}
