# AR 麻将助手：眼镜端架构与后端对齐文档

## 1. 文档目的

这份文档用于对齐当前眼镜端的实现现状，以及后续建议统一的协议方向。

重点覆盖：

- 二维码连接配置
- 会话生命周期
- 图片持续上传链路
- 可选的流式建议下行通道
- 当前已经实现的中台/客户端改造
- 后端存储与监控注意事项

## 2. 系统整体结构

当前系统可以理解为三层：

1. 眼镜 Android 客户端
   - 负责 UI、相机预览、触控手势、二维码扫码配置、运行时会话管理
   - 按固定 FPS 持续采样
   - 通过 HTTP 上传图片
   - 可选连接一个 WebSocket 建议下行通道

2. 后端服务
   - 接收会话开始/结束请求
   - 接收图片帧
   - 做视觉识别、麻将状态分析、出牌建议计算
   - 可选通过 WebSocket 主动推送建议

3. Mock / 中台适配层
   - 目前本地服务里已经内置了一个 mock 能力
   - 可以生成测试二维码
   - 可以提供 mock WebSocket 流式建议
   - 便于正式后端协议未定前先联调眼镜端

## 3. 当前眼镜端行为

### 3.1 启动流程

- 如果本地没有保存连接配置，进入设置页
- 如果本地已有配置，直接进入运行态

### 3.2 运行态

- 默认持续采样，当前默认 `1 fps`
- 支持二维码下发 `fps` 覆盖本地默认值
- 客户端当前有“单飞”保护：
  - 上一帧还没处理完时，不会叠加新的上传
  - 也就是不会无限并发把后端一下打爆

### 3.3 手势模型

- 单击
  - 设置页：确认当前焦点项
- 双击
  - 主界面：退出应用
  - 设置页：如果存在配置，则回到主界面继续运行；否则退出应用
  - 扫码页：返回上一页
- 内滑
  - 运行态：录音开关
- 外滑
  - 运行态：打开设置页
- 上滑 / 下滑
  - 设置页：切换焦点
  - 运行态：滚动“操作建议”区域

## 4. 二维码配置协议

眼镜端当前扫码内容要求为 JSON。

### 4.1 当前支持字段

```json
{
  "version": 1,
  "serverUrl": "http://192.168.2.195:8000/",
  "streamUrl": "ws://192.168.2.195:8000/ws/mock-suggestions/default",
  "fps": 1,
  "sessionKey": "optional-token",
  "label": "本地模拟服务"
}
```

### 4.2 字段说明

- `version`
  - 配置协议版本
  - 当前固定使用 `1`

- `serverUrl`
  - 必填
  - 后端 HTTP 基地址
  - 客户端会自动补齐结尾 `/`

- `streamUrl`
  - 可选
  - 建议下行流地址
  - 当前客户端按 WebSocket 处理

- `fps`
  - 可选
  - 持续采样频率
  - 当前客户端限制为 `1..5`

- `sessionKey`
  - 可选
  - 预留给未来鉴权或路由
  - 当前客户端会解析和保存，但还没有真正作为 header / query 发送出去

- `label`
  - 可选
  - 环境名称，主要用于设置页显示

## 5. 当前 HTTP 协议

### 5.1 开始会话

接口：

```text
POST /api/start-session
```

请求体：

```json
{
  "session_id": "uuid-string"
}
```

响应：

```json
{
  "status": "success",
  "session_id": "uuid-string"
}
```

说明：

- `session_id` 由眼镜端本地生成
- 每次进入运行态会尝试启动一个新会话

### 5.2 图片分析

接口：

```text
POST /api/analyze-hand
```

请求类型：

```text
multipart/form-data
```

当前请求字段：

- `image`: JPEG 图片
- `session_id`: 字符串

当前后端还支持：

- `incoming_tile`: 可选字符串

当前响应格式：

```json
{
  "user_hand": ["1m", "2m", "3m"],
  "melded_tiles": ["4p", "5p", "6p"],
  "suggested_play": "切 8m",
  "annotated_image_path": "/static/uploads/xxx.jpg",
  "action_detected": null,
  "warning": null,
  "is_stable": true
}
```

当前眼镜端实际用到的字段：

- `user_hand`
- `melded_tiles`
- `suggested_play`

当前眼镜端还没有显式消费的字段：

- `annotated_image_path`
- `action_detected`
- `warning`
- `is_stable`

### 5.3 音频处理

接口：

```text
POST /api/process-audio
```

请求类型：

```text
multipart/form-data
```

当前请求字段：

- `audio`: WAV 文件
- `session_id`: 字符串

当前返回是结构化音频事件结果，但眼镜端还没有把这部分做成复杂 UI 状态联动。

### 5.4 结束会话

接口：

```text
POST /api/end-session
```

请求体：

```json
{
  "session_id": "uuid-string"
}
```

## 6. 当前流式下行通道

当前眼镜端支持一个可选的建议流。

如果二维码里存在 `streamUrl`：

- 客户端会建立 WebSocket 连接
- 把它当作建议下行通道
- 即使没有这个字段，系统仍可通过“持续上传 + HTTP 响应”工作

### 6.1 当前 mock WebSocket

当前本地 mock 地址：

```text
WS /ws/mock-suggestions/{session_id}
```

当前 mock 推送示例：

```json
{
  "session_id": "default",
  "status": "ready",
  "suggested_play": "切 8m",
  "user_hand": ["1m", "2m", "3m"],
  "melded_tiles": ["4p", "5p", "6p"]
}
```

### 6.2 当前客户端状态逻辑

- `status == "processing"` 时，UI 显示 `等待建议`
- WebSocket 已连通时，显示 `流已连接`
- WebSocket 未连，但 HTTP 主链路正常时，显示 `已连接`

## 7. 已完成的中台 / 客户端改造

### 7.1 二维码动态连接

- 不再只依赖代码里写死地址
- 通过二维码动态切换环境
- 配置会保存在本地

### 7.2 持续采样模式

- 不再需要“三击开始”
- 进入运行态即自动持续采样
- FPS 可配置

### 7.3 可选流式建议通道

- 客户端已支持附加 WebSocket 下行
- 当前可以先接 mock，后续可平滑切到正式后端

### 7.4 运行态 / 设置态分离

- 运行态尽量 HUD 化，减少非关键信息占位
- 详细连接信息、环境信息和操作说明放到设置页

## 8. 后端会不会因为持续拍照“爆掉”

### 8.1 当前真实情况

当前不是“瞬间并发爆掉”，而是更接近“文件持续累积”。

原因：

- 客户端有单飞保护，不会无限并发上传
- 但后端当前会把每次上传的图片、标注图、音频文件持续落盘到 `server/static/uploads`
- 如果长时间持续运行，不清理的话，磁盘占用会不断增长

### 8.2 已观察到的现状

在当前开发环境里，`uploads` 已经累计到较大的体积和文件数，说明这个风险是真实存在的。

### 8.3 目前已补的防护

后端源码中已新增自动清理逻辑：

- 默认保留最近 `24` 小时文件
- 同时最多保留 `1500` 个文件
- 每 `10` 分钟后台自动清理一次

当前配置来自环境变量：

- `UPLOAD_RETENTION_HOURS`
- `UPLOAD_MAX_FILES`

如果不传，则分别默认：

- `24`
- `1500`

### 8.4 建议后续和后端继续确认

建议后端进一步确认是否需要：

1. 只保留原图，不保留标注图
2. 只保留最近 N 帧，不保留完整历史
3. 只把监控数据写数据库，不保留全部图片文件
4. 对上传节奏再做一层服务端限流或丢帧策略

## 9. 会话监控与调试入口

### 9.1 当前监控页

当前后端内置了会话监控页，页面文件是 `dashboard.html`。

当前可访问入口：

- `/`
- `/monitor`

建议以后统一使用：

```text
http://127.0.0.1:8000/monitor
```

这样即使后续根路由有别的用途，也不会影响快速查看会话。

### 9.2 当前监控接口

会话列表：

```text
GET /api/history/sessions
```

会话详情：

```text
GET /api/history/details/{session_id}
```

### 9.3 当前监控能力

监控页能看到：

- 会话列表
- 会话状态
- 最近活动时间
- 每次交互的识别步骤
- 每次交互的响应结果
- 图片或音频对应的历史记录

## 10. 建议与后端统一的最终协议方向

为了减少后续返工，建议与后端统一成“两条链路”模型：

1. HTTP 上行
   - 用于会话控制、图片上传、音频上传
2. WebSocket 下行
   - 用于建议、状态、警告的主动推送

这和当前眼镜端结构是兼容的，不需要重做 UI。

### 10.1 建议统一的二维码 JSON

```json
{
  "version": 1,
  "serverUrl": "https://backend.example.com/",
  "streamUrl": "wss://backend.example.com/ws/suggestions/{session_id}",
  "fps": 1,
  "sessionKey": "optional-auth-token",
  "label": "测试环境"
}
```

建议规则：

- `serverUrl` 必填
- `streamUrl` 推荐提供
- `sessionKey` 后续可以转成真正的鉴权 token

### 10.2 建议统一的流式建议 payload

建议后端最终推送格式：

```json
{
  "session_id": "uuid-string",
  "status": "ready",
  "seq": 12,
  "frame_ts": 1712345678901,
  "user_hand": ["1m", "2m", "3m"],
  "melded_tiles": ["4p", "5p", "6p"],
  "suggested_play": "切 8m",
  "confidence": 0.92,
  "warning": null,
  "action_detected": null
}
```

建议字段说明：

- `status`
  - 建议取值：`processing` / `ready` / `warning` / `error`
- `seq`
  - 推送序号，单调递增
- `frame_ts`
  - 对应帧时间戳
- `confidence`
  - 可选，后续可用于 UI 过滤或降噪
- `warning`
  - 可选，给用户看的告警文案
- `action_detected`
  - 可选，例如摸牌、切牌、吃碰杠等语义动作

### 10.3 建议统一的错误 payload

```json
{
  "session_id": "uuid-string",
  "status": "error",
  "code": "VISION_TIMEOUT",
  "message": "frame processing timeout"
}
```

## 11. 当前还需要和后端明确的开放项

建议和后端重点对齐以下问题：

1. 最终下行是否确定使用 WebSocket
2. `sessionKey` 最终怎么传
   - HTTP header
   - query 参数
   - WebSocket header / query
3. 图片上行是否继续保持 HTTP multipart
4. 流式 payload 是否要带：
   - `confidence`
   - `warning`
   - `action_detected`
   - `seq`
   - `frame_ts`
5. 是否需要客户端每帧附带额外元信息：
   - capture timestamp
   - frame index
   - device id
   - 当前局面阶段
6. 后端是否保留全量图片文件，还是只保留最近窗口

## 12. 推荐的下一步

建议按下面顺序对齐：

1. 先定二维码 JSON 字段
2. 再定 WebSocket URL 形态
3. 再定流式建议 payload
4. 再定 `sessionKey` 鉴权方式
5. 最后才接正式后端流解析

这样可以最大限度复用当前眼镜端实现，避免反复返工。
