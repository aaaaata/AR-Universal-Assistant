---
slugid: ar-mahjong-assist--mvp1
stage: document-review
title: AR 麻将助手 MVP 文档评审记录
created: 2026-08-05
status: active
---

# AR 麻将助手 MVP 文档评审记录

## Review Scope

本轮评审覆盖：

- `README.md`
- `sources.md`
- `spec.md`
- `tech.md`

评审 persona：

- coherence
- feasibility
- product-lens
- design-lens
- security-lens
- scope-guardian
- adversarial

## Applied Decisions

用户已在本轮评审后确认：

- MVP1 面向内部演示为主的普通用户。
- 低置信度专门交互暂时不做。
- QR 扫描连接形式是当前本地调试形式，不会对外。
- MVP1 以广东麻将为主，同时保留规则接口，允许受控上传四川或其他自定义规则配置。
- 其他非冲突建议可补入文档。

## Findings And Handling

| Finding | Priority | Handling |
|---|---|---|
| 目标用户未收敛 | P1 | 已处理：`spec.md` 明确为内部演示为主的普通用户 |
| 可信建议缺少通过门槛 | P1 | 部分处理：补 provisional gates；正式阈值留给 QA |
| Hand-only advice may not be trustworthy | P1 | 已处理：建议语义收窄为手牌效率建议 |
| Confidence contract remains undecided | P1 | 部分处理：统一字段为 `recognition_confidence` / `suggestion_confidence`；正式阈值留给 QA |
| Low-confidence behavior is unresolved | P1 | 已处理：MVP1 不做专门低置信度交互，仅记录字段 |
| HTTP endpoints lack access-control contract | P1 | 部分处理：标记本地调试可暂缓；对外演示/生产化前必须补 access-control contract |
| QR configuration trust boundary is missing | P1 | 已处理：QR 标记为本地调试/内部演示，不对外 |
| 鸣牌结构化口径冲突 | P1 | 部分处理：`tech.md` 补 `melds` 可选目标；是否 MVP 必需仍为 open decision |
| 胡牌提醒价值未对齐 / 缺少技术映射 | P2 | 部分处理：从 MVP 包含中移出，作为 open decision |
| Default WebSocket conflicts with lifecycle | P2 | 已处理：`default` 标记为 debug-only，MVP1 HTTP 权威 |
| Timeout recovery lacks API contract | P2 | 未处理：需要后续 error contract 表 |
| AR layout rules are too abstract | P2 | 未处理：需要后续 AR display contract |
| Touch actions are not mapped | P1 | 未处理：需要后续 RayNeo touch action map |
| Retention requirements lack concrete values | P2 | 未处理：需要后续 retention matrix |
| Debug surface gating remains unresolved | P2 | 部分处理：标记本地调试，生产 gating 留后续 |
| Cloud-only path assumes real-table connectivity | P2 | 部分处理：补内部演示受控网络 gate；正式网络验证留 QA |
| Ordering metadata is deferred despite stale-state requirements | P2 | 部分处理：`tech.md` 提升最小 freshness 字段为 MVP stale UI 前置条件 |
| 规则口径不明确 | P1 | 已处理：新增广东麻将主路径和规则扩展决策 |

## Remaining Follow-ups

- 创建 `risks.md`：图片/日志保留、debug 暴露、云端网络、模型数据集风险。
- 创建 `qa.md`：provisional gates、评测数据集、错误建议率、人工纠正次数、端到端耗时。
- 补 `decisions.md` 中 open decision：鸣牌结构化、胡牌提醒、sessionKey、WebSocket、retention、touch action。
- 补规则配置 schema：广东麻将默认 profile、自定义规则格式、上传入口和安全校验。
- 后续实现前，先选一个小的 `cc-work` 切片：协议字段对齐和置信度记录。
