---
slugid: ar-mahjong-assist--mvp1
stage: decisions
title: AR 麻将助手 MVP 决策记录
created: 2026-08-05
status: active
---

# AR 麻将助手 MVP 决策记录

## Accepted Decisions

| ID | Decision | Status | Rationale | Impact |
|---|---|---|---|---|
| D1 | MVP1 第一目标用户是内部演示为主的普通用户 | accepted | 用户明确收敛；本阶段优先证明内部演示链路，不做外部产品化承诺 | `spec.md` 的用户价值和验收口径改为内部演示 |
| D2 | 出牌建议表述收窄为手牌效率建议 | accepted | MVP1 不做四家完整追踪、牌河、宝牌和完整局面建模，不能承诺完整对局策略 | `suggested_play` 仍保留，但文档避免称为完整可信策略建议 |
| D3 | 低置信度专门交互暂不做 | accepted | 用户明确拍板；当前先记录 confidence/warning/is_stable 字段，后续再做 UI/交互 | `spec.md` 和 `tech.md` 不再把低置信度 UI 作为 MVP1 验收项 |
| D4 | QR 扫描形式仅用于本地调试/内部演示，不对外 | accepted | 用户明确说明 QR 是当前本地调试形式，不作为生产连接方案 | `tech.md` 标记 `http/ws` 只允许受控本地网络，外部演示前需重设安全边界 |
| D5 | MVP1 runtime acceptance 以 HTTP 分析响应为权威 | accepted | 当前 WebSocket 仍是 mock/可选通道，`default` session 不适合作为真实会话绑定 | WebSocket 仅为调试或后续候选；若启用必须绑定真实 `session_id` |
| D6 | MVP1 默认规则为广东麻将 | accepted | 用户明确收敛；内部演示优先围绕广东麻将验收 | `spec.md` 和 `tech.md` 增加广东麻将主路径 |
| D7 | 保留规则扩展口，允许受控自定义规则配置 | accepted | 用户希望保留四川或其他规则扩展能力 | 技术方案按 declarative config 设计，不允许任意代码上传 |

## Provisional Gates

| Gate | Provisional value | Owner | Follow-up |
|---|---|---|---|
| End-to-end latency | 受控演示网络中 3-10 秒内返回 | TBD | 在 `qa.md` 中记录 average/max/P95 |
| Recognition accuracy | 整手牌识别目标 >80% | TBD | 需要数据集和统计口径 |
| Obvious bad suggestion | 演示样例中不出现明显错误建议 | TBD | QA 阶段定义错误建议率 |
| Manual correction | 15 分钟演示流程中目标不超过 2 次 | TBD | QA 阶段按实测调整 |
| Low-confidence interaction | 不作为 MVP1 验收项 | accepted | 后续版本再定义 UI 模式 |
| Rule profile | 默认 `guangdong`；自定义规则不阻断主路径 | accepted | 服务端先支持规则配置识别和校验 |

## Open Decisions

| ID | Question | Status | Notes |
|---|---|---|---|
| O1 | 鸣牌结构化是否必须在 MVP1 中表达 `melds: [{type, tiles}]` | open | 若要验收吃/碰/杠类型，不能只保留扁平 `melded_tiles` |
| O2 | 胡牌提醒是否进入 MVP1 | open | 当前建议降级为 Phase 2 或后续候选 |
| O3 | `sessionKey` 是否作为对外鉴权凭据 | deferred | 本地调试不需要；对外演示/生产化前必须关闭 |
| O4 | WebSocket 是否成为正式下行权威源 | deferred | MVP1 内部演示默认 HTTP 权威 |
| O5 | 上传图片、标注图、失败样本、日志的 TTL 和访问 owner | open | 应在 `risks.md` / `qa.md` 中补矩阵 |
| O6 | RayNeo touch action 在 MVP1 中承担哪些用户动作 | open | 低置信度交互已延后，但隐藏/结束/设置仍需确认 |
| O7 | 自定义规则配置格式和上传入口 | open | 建议 JSON/YAML + schema 校验；不允许任意代码执行 |
| O8 | 四川麻将等自定义规则是否进入 MVP1 演示范围 | open | 当前不阻断广东麻将主路径 |
