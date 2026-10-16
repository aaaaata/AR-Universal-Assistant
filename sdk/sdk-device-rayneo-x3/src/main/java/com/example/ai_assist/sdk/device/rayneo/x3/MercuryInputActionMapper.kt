package com.example.ai_assist.sdk.device.rayneo.x3

import com.example.ai_assist.sdk.capability.InputAction
import com.example.ai_assist.sdk.capability.InputActionMapper
import com.ffalcon.mercury.android.sdk.touch.TempleAction

/** 将 Mercury 触控事件适配为 SDK 通用输入动作。 */
object MercuryInputActionMapper : InputActionMapper<TempleAction> {
    /** 转换 Mercury 单击、连击和滑动事件。 */
    override fun map(source: TempleAction): InputAction = when (source) {
        is TempleAction.Click -> InputAction.CLICK
        is TempleAction.DoubleClick -> InputAction.DOUBLE_CLICK
        is TempleAction.TripleClick -> InputAction.TRIPLE_CLICK
        is TempleAction.SlideForward -> InputAction.SLIDE_FORWARD
        is TempleAction.SlideBackward -> InputAction.SLIDE_BACKWARD
        is TempleAction.SlideUpwards -> InputAction.SLIDE_UP
        is TempleAction.SlideDownwards -> InputAction.SLIDE_DOWN
        else -> InputAction.UNKNOWN
    }
}
