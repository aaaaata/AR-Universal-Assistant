package com.example.ai_assist.sdk.capability

import kotlinx.coroutines.flow.Flow

/** SDK 可识别的通用输入动作。 */
enum class InputAction {
    CLICK,
    DOUBLE_CLICK,
    TRIPLE_CLICK,
    SLIDE_FORWARD,
    SLIDE_BACKWARD,
    SLIDE_UP,
    SLIDE_DOWN,
    UNKNOWN
}

/** 将厂商输入事件转换为 SDK 通用动作。 */
fun interface InputActionMapper<in T> {
    /** 转换单个厂商输入事件。 */
    fun map(source: T): InputAction
}

/** 宿主向输入能力发布通用动作的端口。 */
fun interface InputActionPublisher {
    /** 发布一个已完成厂商适配的输入动作。 */
    suspend fun publish(action: InputAction)
}

/** 提供通用输入动作流。 */
interface InputCapability : SdkCapability {
    /** 持续发布已完成厂商适配的输入动作。 */
    val actions: Flow<InputAction>
}
