---
slugid: ar-mahjong-assist--mvp1
stage: workflow-readme
title: AR 麻将助手 MVP 工作流入口
created: 2026-08-05
status: active
---

# AR 麻将助手 MVP 工作流入口

本目录是 AR 麻将助手 MVP1 的 `docs/cc` 工作流目录，用来承载当前阶段的 source-of-truth。它不替代历史规划、管理、技术方案、眼镜协议或后端交接文档，而是把这些材料收敛成后续 `cc-work`、`cc-qa`、`cc-review` 可以直接使用的执行入口。

## 当前就绪文档

| 文档 | 定位 | 状态 |
|---|---|---|
| `plan.md` | 执行就绪规划，说明为什么做、按什么顺序做、后续工作如何拆 | ready |
| `README.md` | 本目录入口，说明文档职责和使用方式 | ready |
| `sources.md` | 源文档索引，说明每份 origin 的用途和权威程度 | ready |
| `spec.md` | 产品规格，回答 MVP 做什么、不做什么、怎样算可用 | ready |
| `tech.md` | 技术方案，回答端云链路、契约、状态机、风险和后续实现边界 | ready |
| `decisions.md` | 已确认决策和待关闭决策，记录用户/团队取舍 | ready |
| `review.md` | 本轮 `cc-document-review` 结果和处理状态 | ready |
| `tasks.md` | `cc-work` 执行状态记录 | ready |

## 文档职责

- `spec.md` 只回答 WHAT：用户场景、MVP 范围、非目标、成功标准、用户流程和产品验证门槛。
- `tech.md` 只回答 HOW-tech：架构、数据流、接口契约、状态生命周期、安全/隐私边界、评测和待定技术决策。
- `plan.md` 只回答 HOW-exec：如何拆单元、按什么依赖顺序推进、哪些文档和实现任务先后落地。
- `sources.md` 负责追溯：历史文档、代码入口和当前 workflow 文档之间的关系。
- `decisions.md` 负责记录已拍板的产品/技术取舍，避免后续实现重复争论。
- `review.md` 负责记录文档评审 findings、处理结果和保留问题。
- `tasks.md` 是 `cc-work` 的执行状态，不作为产品或技术结论来源。

## 工作原则

- 新结论优先写入本目录，不再散落到多份历史文档里。
- 历史文档保留为 origin，用于追溯背景和阶段口径。
- MVP 面向内部演示为主的普通用户，优先证明 AR + CV 链路在受控演示条件下可用。
- MVP 聚焦眼镜采集、云端视觉识别、手牌/鸣牌结构化、多帧稳定、广东麻将优先的手牌效率建议、低侵入 AR 展示和端到端验证。
- 规则体系以广东麻将为 MVP1 默认规则，同时保留规则扩展口，允许后续通过受控规则配置接入四川麻将或其他规则。
- 复杂 UI、App 端模型、语音交互、低置信度专门交互、四家完整追踪、AI 对手建模、赛后复盘不进入 MVP1。
- QR 扫描连接是当前本地调试形式，不作为对外用户连接方案或生产鉴权方案。
- 涉及运行行为的后续代码任务应优先 test-first 或 characterization-first；纯文档任务可直接实现。

## 推荐使用方式

1. 评估新需求是否进入 MVP：先读 `spec.md`。
2. 判断接口、状态、数据结构如何对齐：先读 `tech.md`。
3. 进入实现：从 `plan.md` 选择 implementation unit，再用 `cc-work` 维护 `tasks.md`。
4. 做测试或实机验证：后续补齐 `qa.md` 后从 `cc-qa` 进入。
5. 关闭复杂问题并沉淀经验：问题解决后再用 `cc-compound` 写入团队知识库。

## 当前不变量

- 本轮文档落地不修改 Android、server、模型、测试或部署逻辑。
- 本轮文档不改变 `/api/start-session`、`/api/analyze-hand`、`/api/process-audio`、`/api/end-session` 或 mock WebSocket 行为。
- 所有新增 Markdown 文档应保存为 UTF-8，并在 Windows/WSL 读取时确认中文可读。
