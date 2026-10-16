package com.example.ai_assist.sdk.scene

/** A short-lived scene notice that can be rendered with scene content. */
data class SceneNotice(
    val code: String,
    val message: String,
    val durationMillis: Long = DEFAULT_DURATION_MILLIS
) {
    init {
        require(code.isNotBlank()) { "Notice code must not be blank" }
        require(message.isNotBlank()) { "Notice message must not be blank" }
        require(durationMillis > 0) { "Notice duration must be positive" }
    }

    companion object {
        const val DEFAULT_DURATION_MILLIS = 2_000L
    }
}

sealed interface SceneNoticeUpdate {
    val code: String

    data class Show(val notice: SceneNotice) : SceneNoticeUpdate {
        override val code: String = notice.code
    }

    data class Dismiss(override val code: String) : SceneNoticeUpdate {
        init {
            require(code.isNotBlank()) { "Notice code must not be blank" }
        }
    }
}
