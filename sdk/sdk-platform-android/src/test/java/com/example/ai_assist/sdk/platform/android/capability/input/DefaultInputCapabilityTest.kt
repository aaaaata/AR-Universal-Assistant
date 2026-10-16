package com.example.ai_assist.sdk.platform.android.capability.input

import com.example.ai_assist.sdk.capability.InputAction
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultInputCapabilityTest {
    /** 验证宿主发布的通用动作可被场景订阅者接收。 */
    @Test
    fun publishesInputActionsToSubscriber() = runBlocking {
        val capability = DefaultInputCapability()
        val receivedAction = async(start = CoroutineStart.UNDISPATCHED) {
            capability.actions.first()
        }

        capability.publish(InputAction.TRIPLE_CLICK)

        assertEquals(InputAction.TRIPLE_CLICK, receivedAction.await())
    }
}
