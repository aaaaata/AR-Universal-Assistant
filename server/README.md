# AR 麻将助手 Cloud 服务

`cloud/` 是当前 AR 麻将助手的服务端主链路：接收一张 PNG/JPEG 原始图片，检测手牌/副露区域，识别牌面，按广东 MVP 规则生成 13 张摸牌建议或 14 张出牌建议，并返回可诊断的结果与耗时。

本仓库不包含客户端实现。Android/RayNeo 客户端在 [armahjongassist](https://gitlab.rayneo.com/CAI/armahjongassist) 仓库维护；双方以 [HTTP 契约](../docs/cc/ar-mahjong-cloud--mvp1/contracts.md) 联调。

## 处理链路

```text
原始 PNG/JPEG 二进制
  → 图片校验 / 旋转处理
  → hand / meld 区域检测
  → ROI 裁剪
  → 牌面模型批量识别
  → 原图坐标回映射、去重与结构化
  → guangdong_mvp_v1 规则引擎
  → 结果、诊断、置信度与服务端耗时
```

当前默认模型都通过 manifest 接入：

| 作用 | Manifest | 默认 runtime | 模型路径 |
|---|---|---|---|
| 手牌/副露区域 | `cloud/models/region.manifest.json` | Ultralytics（CPU 推理配置） | `models/region/yolo26_hand_detect.pt` |
| 麻将牌面 | `cloud/models/tile.manifest.json` | Ultralytics（CPU 推理配置） | `models/tile/yolo26m_mahjong_detect_finetune.pt` |

服务端不额外按区域置信度过滤检测框；模型原始置信度会进入后续置信度计算。未设置 `conf`/`iou` 时，由 Ultralytics 使用其默认推理阈值。

## 本地启动

### 1. 安装依赖

```bash
python -m pip install -r cloud/requirements.txt
```

### 2. 配置

可直接使用脚本默认值。仅在需要覆盖端口、API Key、模型 manifest 或图片限制时，才创建本地配置：

```bash
cp cloud/.env.example cloud/.env.local
```

`.env.local` 仅在当前机器生效，且不会提交到 Git；`cloud/.env.example` 使用宿主机相对路径，不能用于 Docker/流水线容器。`CLOUD_MAX_IMAGE_PIXELS=0` 只用于原图性能实验，不是常规运行默认值。GPU 实验复用相同模型权重，但需要在 manifest 中显式指定 GPU 设备，并使用具备 CUDA 运行时的环境。

运行环境仅支持 `CLOUD_RUNTIME_ENV=local|test`，其他值会拒绝启动。`local` 默认全量保存成功帧，适合短期联调；公网测试服必须显式使用 `test`，默认每个会话每 30 秒最多保存 1 张成功帧。两种 Profile 的留存、容量与异常取证规则见[运行数据留存与异常取证](../docs/deployment/cloud-data-retention-and-forensics.md)。

### 3. 启动与停止

```bash
./scripts/start-cloud.sh
./scripts/check-cloud.sh
./scripts/stop-cloud.sh
```

未覆盖环境变量时，脚本监听 `0.0.0.0:8000`、启动 3 个 Uvicorn worker。每个 worker 的 `CLOUD_ANALYSIS_CONCURRENCY` 默认是 1；总承载应按部署 CPU、内存、模型和实际压测确定，不能由 worker 数直接推导。


健康检查不需要 API key：

```bash
curl http://127.0.0.1:8000/health/live
curl http://127.0.0.1:8000/health/ready
```

## Docker / 流水线

根目录默认 Compose 已指向 cloud 服务：

```bash
docker compose build
docker compose up
```

如需给本地 Docker 覆盖 API Key 或其他容器配置：

```bash
cp cloud/.env.container.example cloud/.env.container.local
```

`.env.container.local` 使用容器内 `/app/...` 路径，只供 Docker Compose 读取；流水线应通过平台环境变量与 secret 注入配置。

当前流水线属于公网测试服，必须注入 `CLOUD_RUNTIME_ENV=test`；不要使用本地 Profile 或将 `cloud/.env.local` 复制到容器。

容器监听 `8080`，当前模型权重临时随镜像 COPY，并默认使用 CPU 推理 manifest。GPU 推理复用相同权重，但需要 CUDA 运行时、GPU 资源和显式 GPU manifest。根目录 `docker-compose.yml` 只挂载 `cloud/data`，不会以空的宿主机 `models/` 覆盖镜像模型。公网测试服硬必填：

```env
CLOUD_API_KEY=<由 secret 管理的随机值>
CLOUD_RUNTIME_ENV=test
```

建议额外注入 `CLOUD_SERVICE_VERSION=<镜像-tag 或 Git commit>`，以便在证据 ZIP 中追溯部署版本。数据库目录、调试图片目录、模型 manifest、监听地址和测试服的保留策略均有镜像/Profile 默认值；仅在使用自定义模型或数据卷路径、调整 worker 或需要覆盖留存策略时再显式配置。完整参数分级见[运行手册](../docs/deployment/cloud-service-runbook.md)。
镜像构建、模型替换和正式制品化策略见 [models/README.md](../models/README.md)；完整部署操作见 [运行手册](../docs/deployment/cloud-service-runbook.md)。

## API 与并发语义

接口路径、字段、错误码和响应以 [contracts.md](../docs/cc/ar-mahjong-cloud--mvp1/contracts.md) 为准。分析请求使用原始 PNG/JPEG request body 和 `X-API-Key`、`X-Request-Id`、`X-Sequence-Id`、`X-Frame-Ts`、`X-Frame-Count` 等请求头。

并发保护：

- 同一 session 固定只允许一个 in-flight analysis；冲突返回 `409 SESSION_ANALYSIS_BUSY`。
- 单个 worker 的全局推理名额由 `CLOUD_ANALYSIS_CONCURRENCY` 控制；短等待超时返回 `429 ANALYSIS_BUSY`。
- 忙碌帧不创建 analysis，也不推进 `sequence_id`；客户端应丢弃本帧，不重传旧帧。
- session 租约保存在 SQLite。同一数据库文件的多 worker 可协调；多 Pod 部署需要共享/替代存储方案后再宣称全局一致性。

建议语义：

- 外层 `suggestion` 包含 `advice_type`、`can_hu`、`tingpai_steps` 等汇总字段，内层
  `suggestion.suggestion` 才是具体弃牌或进张细节。
- 13 张有效手牌：`draw_wait`，进张列表位于 `suggestion.suggestion.jinzhang_tiles`。
- 14 张有效手牌：`discard`，推荐弃牌位于 `suggestion.suggestion.tile`。
- 广东 MVP 只支持碰/杠副露，不支持吃；不支持的副露类型返回 `UNSUPPORTED_MELD_TYPE`。

## 耗时与调试

`timings_ms.total`/`algorithm_total` 是算法耗时；公网问题排查还应看：

- `request_body_read`、`image_decode`
- `session_in_flight_acquire`、`queue_wait`
- `db_start_analysis`、`debug_image_write`、`db_complete_analysis`
- `analysis_worker`、`route_handler_total`、`route_overhead`

完整释义见 [分析日志说明](../docs/cc/ar-mahjong-cloud--mvp1/analysis-log-guide.md)。

Monitor 静态页面：

```text
http://<host>:<port>/monitor
```

`/monitor` 页面本身未做应用层鉴权；其数据和图片接口，以及 `/debug/connect` 服务地址二维码页面，都要求 `X-API-Key` 或 `?api_key=`。生产环境应使用内网隔离或网关鉴权，避免监控页面暴露在公网。

S4 运行数据策略不会把每帧图片永久写盘：本地保存全量成功帧；测试服每会话每 30 秒最多保存 1 张成功帧，诊断或失败可自动取证。达到容量阈值时服务仍返回分析结果，但会停止保存普通成功帧。

Monitor 支持把指定分析冻结为证据，并下载不含 API Key 的证据 ZIP：

```text
POST /debug/analyses/{analysis_id}/evidence
GET  /debug/evidence/{evidence_id}/download
```

证据和 SQLite 仅是当前运行实例的短期诊断缓存；测试服重新部署前，如需跨部署保留异常，必须先下载证据包到受控位置。

## Session regression 录制与回放

创建会话可显式选择录制模式；省略时为普通会话：

```json
{"rule_profile_id": "guangdong_mvp_v1", "recording_mode": "normal"}
```

成功响应包含 `session_id`、`rule_profile_id` 和实际 `recording_mode`。`regression` 只在
`CLOUD_RUNTIME_ENV=test` 且 `CLOUD_SESSION_RECORDING_ENABLED=1` 时开放。默认同时只允许
一个活跃录制，每个会话最多 8 GiB，结束后保留 1 天，原始帧写入
`CLOUD_SESSION_RECORDING_DIR/<session_id>/frames/`。本地模板使用
`cloud/data/session_recordings`；容器应使用容器内路径或数据卷。

创建回归会话会先检查完整 8 GiB 预算和最小剩余空间；需要腾挪时先清理普通调试图，
有效 hold 内的冻结 evidence 受保护。录制只有在非空、全部 analysis 完成且帧索引完整
时才成为 `completed`；写盘/容量问题、pending/failed analysis、空会话或缺帧都会成为
`incomplete`。两者到期清理后保留 `expired` 元数据墓碑；`incomplete` 和 `expired`
均不可下载。

Monitor 的会话详情展示录制状态、帧数、字节数、失败原因、过期时间和导出时间；只有
`completed` 会话显示下载按钮。也可直接调用：

```text
GET /debug/sessions/{session_id}/regression-pack/download
```

该路由要求 `X-API-Key` 或 `?api_key=`，且只允许已结束、非空、`completed`、未过期的
录制。服务端动态生成 `ar-mahjong-session-regression-<session_id>.zip`，发送后删除
临时 ZIP，不持久保存。归档 v1 的精确成员、manifest 字段、checksum 规则、状态约束和
错误码见 [HTTP 契约](../docs/cc/ar-mahjong-cloud--mvp1/contracts.md)。

下载后可在目标服务回放并生成 JSON 报告：

```bash
python tools/replay_session_archive.py \
  --archive ar-mahjong-session-regression-<session_id>.zip \
  --base-url http://127.0.0.1:8000 \
  --api-key local-token \
  --report replay-report.json
```

CLI 的语义差异字段、仅报告字段和退出码同样以契约为准。回归录制开关由服务端控制；
眼镜 App 是否创建 `regression` 会话由独立客户端仓库按契约接入，本仓库不包含该 App
开关。测试服数据卷或实例可能在重新部署时清空，需要保留的回归包和 evidence 必须先
下载到受控位置。

## 测试

```bash
PYTHONPATH=. python -m pytest -q cloud/tests

python tools/test_cloud_analysis_request.py \
  --base-url http://127.0.0.1:8000 \
  --api-key local-token \
  --image /path/to/sample.png \
  --allow-diagnostic

CLOUD_BENCH_IMAGE=/path/to/sample.png ./scripts/bench-cloud.sh
CLOUD_BENCH_BASE_URL=http://<public-host>:<port> \
CLOUD_BENCH_IMAGE=/path/to/sample.png \
./scripts/bench-cloud-four-tables.sh
```

`tools/test_cloud_analysis_request.py` 适合验证单图完整 HTTP 链路；`bench-cloud.sh` 只生成当前环境的容量基线，不构成跨环境性能承诺。`bench-cloud-four-tables.sh` 模拟 4 家客户端：压缩图、独立 session、采集间隔扫描、in-flight 跳帧和 429 退避，用来在不改公网服务配置的前提下找稳定节奏。
