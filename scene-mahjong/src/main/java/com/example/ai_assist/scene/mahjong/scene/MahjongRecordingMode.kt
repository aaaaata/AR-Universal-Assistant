package com.example.ai_assist.scene.mahjong.scene

/** 服务端支持的麻将开局记录模式。 */
enum class MahjongRecordingMode(val wireValue: String) {
    NORMAL("normal"),
    REGRESSION("regression")
}
