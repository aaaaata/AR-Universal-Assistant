# server 目录交接说明（给后端同事）

## 1. 结论先说

这次 `server` 侧改动里，真正建议后端同事重点看、且大概率可以直接复用的，主要是下面两类：

1. `server/main.py`
   - 新增监控页入口 `/monitor`
   - 新增 mock 二维码配置接口与 mock WebSocket 建议流
   - 新增上传文件自动清理逻辑

2. `server/stt_service.py`
   - 把 STT 初始化从“启动时立即加载”改成“首次使用时懒加载”
   - 解决 Docker / 服务启动时被 `faster-whisper` 首次下载卡住的问题

如果后端同事只想吸收“和正式环境最相关”的部分，优先看：

- `server/main.py`
- `server/stt_service.py`

## 2. 哪些内容建议直接发给后端

建议直接给后端同事的内容：

- [main.py](/Users/cj/code/Agent/majiang/AR-Mahjong-Assistant-preview/server/main.py)
- [stt_service.py](/Users/cj/code/Agent/majiang/AR-Mahjong-Assistant-preview/server/stt_service.py)
- [AR_GLASSES_ARCHITECTURE_AND_PROTOCOL.md](/Users/cj/code/Agent/majiang/AR-Mahjong-Assistant-preview/docs/AR_GLASSES_ARCHITECTURE_AND_PROTOCOL.md)
- 本文档

如果他们也想看本地联调辅助页面，可以再附上：

- [mock_qr.html](/Users/cj/code/Agent/majiang/AR-Mahjong-Assistant-preview/server/static/mock_qr.html)

## 3. 哪些内容不建议直接发

这些更偏本地开发产物或测试素材，不建议当成正式后端改动直接丢给后端同事：

- `server/.venv/`
- `server/synthetic_tests/`
- `server/test_variants/`
- 各种本地生成的二维码图片、测试图片

这些不是核心协议或后端逻辑。

## 4. 这次 server 到底改了什么

## 4.1 `server/main.py`

主要新增了三类能力：

### A. 监控页入口增强

新增：

- `GET /monitor`

作用：

- 直接指向现有 `dashboard.html`
- 避免以后根路由 `/` 被别的用途占掉后，监控入口不好找

是否建议复用：

- 建议复用
- 成本低，收益明确

### B. 二维码 / mock 流式联调能力

新增：

- `GET /mock/qr`
- `GET /api/mock/connection-config`
- `WS /ws/mock-suggestions/{session_id}`

作用：

- 让眼镜端在正式后端协议没完全定之前，也能做扫码联调
- `connection-config` 会生成当前可扫码的 JSON 配置
- `mock-suggestions` 会按最近一次分析结果持续推送建议

是否建议复用：

- 正式生产环境不一定需要原样保留
- 但在联调环境、测试环境里非常有价值
- 建议后端同事至少参考这个思路

### C. 上传目录自动清理

新增：

- `UPLOAD_RETENTION_HOURS`
- `UPLOAD_MAX_FILES`
- `cleanup_upload_dir(...)`
- `monitor_upload_cleanup()`

作用：

- 解决持续采样后，图片/标注图不断落盘导致磁盘膨胀的问题
- 当前默认：
  - 最多保留最近 `24` 小时
  - 最多保留 `1500` 个文件
  - 每 `10` 分钟清理一次

是否建议复用：

- 强烈建议复用
- 哪怕后端实现方式不同，也建议保留“自动清理”这个机制

## 4.2 `server/stt_service.py`

改动核心：

- STT 不再在服务启动时立刻初始化
- 改成第一次真正调用转写时再加载模型

解决的问题：

- Docker 容器刚启动时，如果 `faster-whisper` 首次下载模型，会把服务 startup 卡住
- 表现为端口在监听，但 `/`、`/monitor` 打不开

改完后的收益：

- 后端首页、监控页、视觉接口可以先 ready
- 只有真正第一次走语音链路时，才触发 STT 初始化

是否建议复用：

- 强烈建议复用
- 尤其是服务里同时有“视觉主流程”和“语音辅流程”时

## 5. 是否“基本可以直接复用”

可以，但要分层看。

### 可以直接复用的

- `STT lazy load` 这套思路
- 上传目录自动清理这套逻辑
- `/monitor` 单独入口
- `二维码配置接口 + mock ws` 作为测试环境能力

### 可以参考但不一定原样照搬的

- `mock-suggestions` 的 payload 结构
- `connection-config` 的字段命名

原因是：

- 这部分最终还是要和正式后端协议统一
- 当前更偏“眼镜端联调过渡方案”

### 不建议原样照搬的

- 本地测试素材目录
- 本地生成文件
- 虚拟环境目录

## 6. 给后端同事的建议说法

你可以直接这样转述：

> 这次 server 侧我这边主要补了三件事：
> 1）给眼镜端联调用的二维码配置和 mock 流式建议接口；
> 2）给持续采样场景加了上传目录自动清理，避免图片越堆越多；
> 3）把 STT 改成懒加载，避免 Docker 启动时因为首次下载 whisper 模型把整个服务卡住。
> 正式可复用的建议重点看 `main.py` 和 `stt_service.py`，测试素材和本地虚拟环境不用看。

## 7. 推荐后端最少对齐项

如果后端同事时间有限，建议最少对齐这几项：

1. 二维码 JSON 字段
   - `serverUrl`
   - `streamUrl`
   - `fps`
   - `sessionKey`
   - `label`

2. 下行流式消息结构
   - `session_id`
   - `status`
   - `user_hand`
   - `melded_tiles`
   - `suggested_play`

3. 上传文件保留策略
   - 是否全量保存
   - 是否保留最近窗口
   - 是否只保留监控样本

4. STT 是否延迟初始化

## 8. 一句话建议

如果后端同事只收最有价值的部分，就收：

- `server/main.py`
- `server/stt_service.py`
- [AR_GLASSES_ARCHITECTURE_AND_PROTOCOL.md](/Users/cj/code/Agent/majiang/AR-Mahjong-Assistant-preview/docs/AR_GLASSES_ARCHITECTURE_AND_PROTOCOL.md)

这三样基本足够开始对齐和复用。
