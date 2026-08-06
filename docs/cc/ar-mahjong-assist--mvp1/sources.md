---
slugid: ar-mahjong-assist--mvp1
stage: sources
title: AR 麻将助手 MVP 源文档索引
created: 2026-08-05
status: active
---

# AR 麻将助手 MVP 源文档索引

本文件说明 `docs/cc/ar-mahjong-assist--mvp1/` 如何使用历史文档和代码现状。历史文档是 origin，本目录中的 `spec.md`、`tech.md`、`plan.md` 是后续执行时的工作流 source-of-truth。

## Origin Documents

| Origin | 用途 | 当前权威程度 | 已迁移到 |
|---|---|---|---|
| `docs/Q3_MVP_PLAN.md` | 项目背景、Q3 里程碑、M0-M4 目标、成熟方案边界、关键风险 | 产品方向与里程碑 origin；具体执行以后续 workflow 为准 | `spec.md`, `plan.md` |
| `docs/AR_MAHJONG_MVP_PROJECT_MANAGEMENT.md` | MVP 范围、验收标准、周推进计划、测试验证体系、风险和决策 | 管理口径 origin；验收指标和边界需保留 | `spec.md`, `tech.md` |
| `docs/AR_MAHJONG_MVP_TECHNICAL_DESIGN.md` | 云端主路径、视觉模型、多帧、置信度、耗时、接口草案和评测设计 | 技术方向 origin；具体字段需和当前代码对齐 | `tech.md` |
| `docs/AR_GLASSES_ARCHITECTURE_AND_PROTOCOL.md` | 眼镜端连接配置、会话、HTTP 上传、WebSocket 下行、mock 方案 | 当前协议和客户端行为 origin；正式契约需继续收敛 | `tech.md` |
| `docs/SERVER_HANDOFF_TO_BACKEND_CN.md` | 后端交接、mock QR/WS、上传清理、STT lazy load、正式后端对齐项 | 后端交接 origin；mock/debug 能力不可直接视为生产契约 | `tech.md`, later handoff |

## External User-Provided Documents

| 文档 | 使用方式 |
|---|---|
| `AR麻将助手 项目规划文档 (2).docx` | 与 `docs/Q3_MVP_PLAN.md` 口径一致，用于确认项目背景和 Q3 MVP 范围 |
| `AR 麻将助手 MVP 项目管理文档.docx` | 与 `docs/AR_MAHJONG_MVP_PROJECT_MANAGEMENT.md` 口径一致，用于确认验收指标和周计划 |
| `AR 麻将助手 MVP 技术方案设计文档.zip` | 与技术设计文档口径一致，用于确认技术链路、接口草案和评测方案 |

## Code References

| 路径 | 当前含义 | 对 workflow 的影响 |
|---|---|---|
| `app/src/main/java/com/example/ai_assist/MainActivity.kt` | 眼镜端主入口，包含运行态、Camera2 采集、上传、WebSocket、音频入口、RayNeo touch action | `tech.md` 的状态机和 AR 展示模型必须能映射到此处 |
| `app/src/main/java/com/example/ai_assist/QrScanActivity.kt` | 二维码扫码入口 | 二维码配置 flow 的产品入口 |
| `app/src/main/java/com/example/ai_assist/storage/ConnectionConfigStore.kt` | 解析和保存 `serverUrl`、`streamUrl`、`fps`、`sessionKey`、`label` | `tech.md` 的二维码字段以此为当前实现基线 |
| `app/src/main/java/com/example/ai_assist/model/AnalyzeResponse.kt` | Android 当前只消费 `user_hand`、`melded_tiles`、`suggested_play` | contract 字段迁移矩阵中的 Android current column |
| `server/main.py` | FastAPI 入口，包含 session、analyze、audio、end-session、mock QR/WS、history/debug | `tech.md` 的 API 和生产/debug 边界以此为现状 |
| `server/schemas.py` | Pydantic response/request 模型 | contract 字段迁移矩阵中的 server current column |
| `server/vision_service.py` | 视觉识别封装 | 模型与评测计划的代码入口 |
| `server/yolo_inference.py` | YOLO ONNX 推理入口 | 模型 baseline 和阈值调优入口 |
| `server/mahjong_state_tracker.py` | 麻将状态追踪 | 多帧稳定、鸣牌和会话状态风险入口 |
| `server/efficiency_engine.py` | 牌效建议 | 建议质量和建议置信度后续任务入口 |
| `server/tests/` | 服务端测试 | 后续行为改动默认从这里寻找测试模式 |
| `server/static/dashboard.html`、`server/static/mock_qr.html`、`server/static/yolo_debug.html` | 调试页面 | 生产暴露必须进入 risk/decision |

## Authority Rules

- MVP 范围冲突时，以 `spec.md` 为准。
- 技术契约冲突时，以 `tech.md` 为准；若后续拆出 `contracts.md`，则以 `contracts.md` 为协议字段权威。
- 历史文档中“成熟方案设想”只作为 Phase 2+ 候选，不自动进入 MVP1。
- mock QR、mock WebSocket、debug static pages 只作为调试能力，不自动成为生产协议。
- `sessionKey` 当前只是解析/保存字段，是否作为鉴权凭据仍是未决安全决策。

## Open Migration Items

- 本轮 document-review 的 findings 已落入 `review.md`。
- 已确认和待关闭决策已落入 `decisions.md`。
- 将模型、隐私、端到端稳定性风险落入 `risks.md`。
- 将 QA/评测口径落入 `qa.md`，必要时再拆 `evaluation.md`。
