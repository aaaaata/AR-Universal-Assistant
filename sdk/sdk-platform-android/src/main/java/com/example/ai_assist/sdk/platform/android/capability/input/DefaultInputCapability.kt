package com.example.ai_assist.sdk.platform.android.capability.input

import com.example.ai_assist.sdk.capability.InputAction
import com.example.ai_assist.sdk.capability.InputActionPublisher
import com.example.ai_assist.sdk.capability.InputCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 将宿主适配完成的通用输入动作发布给场景。 */
internal class DefaultInputCapability : InputCapability, InputActionPublisher {
    private val mutableActions = MutableSharedFlow<InputAction>()

    /** 场景租约内可订阅的通用输入动作。 */
    override val actions: Flow<InputAction> = mutableActions.asSharedFlow()

    /** 发布一个通用输入动作。 */
    override suspend fun publish(action: InputAction) {
        mutableActions.emit(action)
    }
}
