package com.example.ai_assist.sdk.capability

/** SDK 可注册硬件能力的公共生命周期接口。 */
interface SdkCapability {
    /** 当前实现是否可被新会话使用。 */
    fun isAvailable(): Boolean = true

    /** 当前实现支持的可选特性标识。 */
    fun supportedFeatures(): Set<String> = emptySet()

    /** 最后一个会话租约释放后停止当前硬件操作。 */
    fun onLeaseReleased() = Unit

    /** SDK 永久关闭时释放能力持有的全部资源。 */
    fun close() = onLeaseReleased()
}

/** 指定能力尚未注册。 */
class CapabilityUnavailableException(
    /** 未注册的能力 ID。 */
    val capabilityId: String
) : IllegalStateException("Capability is unavailable: $capabilityId")

/** 指定能力与现有租约发生独占冲突。 */
class CapabilityLeaseConflictException(
    /** 发生冲突的能力 ID。 */
    val capabilityId: String
) : IllegalStateException("Capability lease conflict: $capabilityId")
