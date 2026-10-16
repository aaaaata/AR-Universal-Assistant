package com.example.ai_assist.sdk.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceCapabilityContractTest {
    /** 验证场景可以通过通用契约读取设备信息和状态。 */
    @Test
    fun fakeCapabilityExposesDeviceSnapshots() {
        val info = DeviceInfo("OpenAI", "Glasses", "12", 31)
        val status = DeviceStatus(80, charging = true, networkAvailable = true)
        val capability = FakeDeviceCapability(info, status)

        assertEquals(info, capability.getDeviceInfo())
        assertEquals(status, capability.getDeviceStatus())
    }

    /** 验证设备状态拒绝越界电量。 */
    @Test
    fun deviceStatusRejectsInvalidBatteryPercent() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceStatus(101, charging = false, networkAvailable = false)
        }
    }
}

private class FakeDeviceCapability(
    private val info: DeviceInfo,
    private val status: DeviceStatus
) : DeviceCapability {
    /** 返回测试设备信息。 */
    override fun getDeviceInfo(): DeviceInfo = info

    /** 返回测试设备状态。 */
    override fun getDeviceStatus(): DeviceStatus = status
}
