package com.example.ai_assist.permission

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 检查并请求宿主页面所需的 Android 运行时权限。 */
internal class PermissionRequester(
    private val activity: ComponentActivity
) {
    private val context = activity.applicationContext
    private var pendingRequest: PermissionRequest? = null
    private var pendingContinuation: CancellableContinuation<PermissionResult>? = null
    private val requestLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        dispatchCurrentResult()
    }

    /** 已授权时直接返回，否则请求当前缺失权限。 */
    suspend fun request(permissionRequest: PermissionRequest): PermissionResult {
        val missingPermissions = missingPermissions(permissionRequest.permissions)
        if (missingPermissions.isEmpty()) return PermissionResult.Granted

        return suspendCancellableCoroutine { continuation ->
            check(pendingContinuation == null) { "A permission request is already running" }
            pendingRequest = PermissionRequest(missingPermissions)
            pendingContinuation = continuation
            continuation.invokeOnCancellation {
                if (pendingContinuation === continuation) clearPendingRequest()
            }
            requestLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /** 返回指定集合中尚未授权的权限。 */
    private fun missingPermissions(permissions: Set<String>): Set<String> =
        permissions.filterTo(linkedSetOf()) {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /** 将系统授权后的最新状态返回给等待中的调用方。 */
    private fun dispatchCurrentResult() {
        val request = pendingRequest ?: return
        val continuation = pendingContinuation ?: return
        val deniedPermissions = missingPermissions(request.permissions)
        val result = if (deniedPermissions.isEmpty()) {
            PermissionResult.Granted
        } else {
            PermissionResult.Denied(
                permissions = deniedPermissions,
                permanentlyDenied = deniedPermissions.filterTo(linkedSetOf()) {
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
                }
            )
        }
        clearPendingRequest()
        if (continuation.isActive) continuation.resume(result)
    }

    /** 清除当前权限请求，允许发起下一次请求。 */
    private fun clearPendingRequest() {
        pendingRequest = null
        pendingContinuation = null
    }
}
