package com.example.ai_assist.sdk.engine

import com.example.ai_assist.sdk.scene.SceneResult
import com.example.ai_assist.sdk.scene.SceneNoticeUpdate

/** SDK 会话生命周期状态。 */
enum class SessionState {
    /** SDK 尚未初始化。 */
    UNINITIALIZED,

    /** SDK 已初始化，可启动场景。 */
    READY,

    /** 场景正在启动。 */
    STARTING,

    /** 场景正在运行。 */
    RUNNING,

    /** 场景正在停止。 */
    STOPPING,

    /** 当前会话遇到不可恢复错误。 */
    ERROR
}

/** 所有 SDK 会话级事件的公共父类型。 */
sealed interface SdkEvent {
    /** 事件所属场景 ID。 */
    val sceneId: String

    /** 事件所属会话 ID。 */
    val sessionId: String

    /** 事件发生时间戳，单位毫秒。 */
    val occurredAtMillis: Long

    /** 会话状态发生变化。 */
    data class StateChanged(
        override val sceneId: String,
        override val sessionId: String,
        override val occurredAtMillis: Long,
        val state: SessionState
    ) : SdkEvent

    /** 场景发布了新的业务结果。 */
    data class ResultReceived(
        override val sceneId: String,
        override val sessionId: String,
        override val occurredAtMillis: Long,
        val result: SceneResult
    ) : SdkEvent

    /** The active scene updated a notice shown on its own visual plane. */
    data class NoticeUpdated(
        override val sceneId: String,
        override val sessionId: String,
        override val occurredAtMillis: Long,
        val update: SceneNoticeUpdate
    ) : SdkEvent

    /** 会话发生可恢复或不可恢复错误。 */
    data class ErrorOccurred(
        override val sceneId: String,
        override val sessionId: String,
        override val occurredAtMillis: Long,
        val code: String,
        val message: String,
        val recoverable: Boolean
    ) : SdkEvent

    /** SDK 或场景发布诊断信息。 */
    data class DiagnosticReported(
        override val sceneId: String,
        override val sessionId: String,
        override val occurredAtMillis: Long,
        val code: String,
        val message: String
    ) : SdkEvent
}
