package com.example.ai_assist.permission

/** 一次运行时权限请求。 */
internal data class PermissionRequest(
    val permissions: Set<String>
) {
    init {
        require(permissions.isNotEmpty()) { "permissions must not be empty" }
        require(permissions.none(String::isBlank)) { "permissions must not contain blank values" }
    }
}

/** 运行时权限检查或请求后的结果。 */
internal sealed interface PermissionResult {
    data object Granted : PermissionResult

    data class Denied(
        val permissions: Set<String>,
        val permanentlyDenied: Set<String>
    ) : PermissionResult {
        init {
            require(permanentlyDenied.all(permissions::contains)) {
                "permanentlyDenied must be a subset of permissions"
            }
        }
    }
}
