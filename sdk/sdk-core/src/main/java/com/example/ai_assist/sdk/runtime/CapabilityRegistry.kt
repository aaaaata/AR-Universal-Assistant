package com.example.ai_assist.sdk.runtime

import com.example.ai_assist.sdk.capability.CapabilityLeaseConflictException
import com.example.ai_assist.sdk.capability.CapabilityRequirement
import com.example.ai_assist.sdk.capability.CapabilityUnavailableException
import com.example.ai_assist.sdk.capability.SdkCapability

/** 保存宿主注册的能力，并统一分配会话级租约。 */
class CapabilityRegistry {
    private val registrations = linkedMapOf<String, Registration>()

    /** 注册能力；相同 ID 重复注册时抛出异常。 */
    @Synchronized
    fun register(capabilityId: String, capability: SdkCapability) {
        require(capabilityId.isNotBlank()) { "capabilityId must not be blank" }
        require(capabilityId !in registrations) {
            "Capability is already registered: $capabilityId"
        }
        registrations[capabilityId] = Registration(capability)
    }

    /** 为会话一次性申请全部能力，任一能力失败时不保留部分租约。 */
    @Synchronized
    internal fun acquire(
        sessionId: String,
        requirements: Set<CapabilityRequirement>,
        optionalRequirements: Set<CapabilityRequirement> = emptySet()
    ): CapabilityLease {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val requirementsById = requirements.associateBy { it.capabilityId }
        require(requirementsById.size == requirements.size) {
            "A scene cannot request the same capability more than once"
        }
        val optionalById = optionalRequirements.associateBy { it.capabilityId }
        require(optionalById.size == optionalRequirements.size) {
            "A scene cannot request the same optional capability more than once"
        }
        require(requirementsById.keys.intersect(optionalById.keys).isEmpty()) {
            "A capability cannot be both required and optional"
        }

        requirementsById.forEach { (capabilityId, requirement) ->
            val registration = registrations[capabilityId]
                ?: throw CapabilityUnavailableException(capabilityId)
            if (!registration.capability.isAvailable()) {
                throw CapabilityUnavailableException(capabilityId)
            }
            val hasConflict = registration.leases.values.any { existingExclusive ->
                requirement.exclusive || existingExclusive
            }
            if (hasConflict) throw CapabilityLeaseConflictException(capabilityId)
        }

        val availableOptional = optionalById.filter { (capabilityId, requirement) ->
            val registration = registrations[capabilityId] ?: return@filter false
            registration.capability.isAvailable() && registration.leases.values.none {
                existingExclusive -> requirement.exclusive || existingExclusive
            }
        }
        val leasedRequirements = requirementsById + availableOptional
        leasedRequirements.forEach { (capabilityId, requirement) ->
            registrations.getValue(capabilityId).leases[sessionId] = requirement.exclusive
        }
        return CapabilityLease(this, sessionId, leasedRequirements.keys)
    }

    /** 返回租约持有的指定类型能力。 */
    @Synchronized
    internal fun <T : SdkCapability> requireCapability(
        lease: CapabilityLease,
        capabilityId: String,
        type: Class<T>
    ): T {
        check(!lease.released) { "Capability lease has been released" }
        check(capabilityId in lease.capabilityIds) {
            "Capability was not leased by this session: $capabilityId"
        }
        val capability = registrations.getValue(capabilityId).capability
        check(type.isInstance(capability)) {
            "Capability $capabilityId is not ${type.name}"
        }
        return requireNotNull(type.cast(capability))
    }

    /** 返回租约内的能力；未取得可选能力时返回 null。 */
    @Synchronized
    internal fun <T : SdkCapability> optionalCapability(
        lease: CapabilityLease,
        capabilityId: String,
        type: Class<T>
    ): T? {
        check(!lease.released) { "Capability lease has been released" }
        if (capabilityId !in lease.capabilityIds) return null
        val capability = registrations.getValue(capabilityId).capability
        check(type.isInstance(capability)) {
            "Capability $capabilityId is not ${type.name}"
        }
        return requireNotNull(type.cast(capability))
    }

    /** 幂等释放一个会话持有的全部能力。 */
    @Synchronized
    internal fun release(lease: CapabilityLease) {
        if (lease.released) return
        lease.capabilityIds.forEach { capabilityId ->
            registrations[capabilityId]?.let { registration ->
                registration.leases.remove(lease.sessionId)
                if (registration.leases.isEmpty()) {
                    registration.capability.onLeaseReleased()
                }
            }
        }
        lease.released = true
    }

    /** 清空未被租用的能力注册信息。 */
    @Synchronized
    internal fun clear() {
        check(registrations.values.none { it.leases.isNotEmpty() }) {
            "Cannot clear capabilities while leases are active"
        }
        registrations.values.forEach { it.capability.close() }
        registrations.clear()
    }

    private data class Registration(
        val capability: SdkCapability,
        val leases: MutableMap<String, Boolean> = linkedMapOf()
    )
}

/** 当前场景会话持有的能力集合。 */
internal class CapabilityLease(
    private val registry: CapabilityRegistry,
    internal val sessionId: String,
    internal val capabilityIds: Set<String>
) {
    internal var released = false

    /** 返回当前租约中的指定类型能力。 */
    fun <T : SdkCapability> requireCapability(capabilityId: String, type: Class<T>): T =
        registry.requireCapability(this, capabilityId, type)

    /** 返回当前租约中的可选能力。 */
    fun <T : SdkCapability> optionalCapability(capabilityId: String, type: Class<T>): T? =
        registry.optionalCapability(this, capabilityId, type)

    /** 幂等释放当前租约中的全部能力。 */
    fun release() {
        registry.release(this)
    }
}
