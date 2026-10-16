# Android 通用智能助手客户端 SDK 架构

本工程将产品壳、通用 SDK、设备适配和业务场景分开维护。麻将是首个场景，
不再承担相机调度、并发门禁、任务取消和临时文件清理等通用职责。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | 权限、生命周期、产品配置、场景装配、公共状态和页面容器 |
| `sdk/sdk-bundle` | 对外聚合其余 SDK 模块，便于完整交付 |
| `sdk/sdk-core` | 场景契约、生命周期、能力租约、配置、凭据、事件和诊断 |
| `sdk/sdk-cv` | 标准 CV 场景、拍摄策略、串行帧处理、取消和临时文件清理 |
| `sdk/sdk-network` | Retrofit/Gson 客户端、超时和请求追踪元数据 |
| `sdk/sdk-platform-android` | Camera2、Android 设备状态、网络状态和通用输入实现 |
| `sdk/sdk-device-rayneo-x3` | Mercury SDK 初始化和 X3 手势映射 |
| `scene-mahjong` | 广东麻将配置、云端协议、Processor、结果映射和 Renderer |

依赖方向：

```text
app -> sdk-bundle -> sdk-device-rayneo-x3 -> sdk-platform-android -> sdk-core
                  -> sdk-cv -------------------------------------> sdk-core
                  -> sdk-network
app -> scene-mahjong -> sdk-core + sdk-cv + sdk-network
```

`sdk-core` 已定义相机、麦克风、音频输出、输入、运动、显示、网络、定位和设备状态
能力契约。当前只有实际场景依赖且可验证的能力提供默认实现；未实现的可选能力会被跳过，
未实现或运行时不可用的必需能力会阻止场景启动。

## CV 场景流程

`CVSceneProvider` 负责以下固定流程：

```text
创建 Processor -> 启动业务会话 -> 按策略选帧 -> 串行处理
-> 映射并发布结果 -> 发布诊断或可恢复错误 -> 删除临时帧
-> 停止时取消任务并结束业务会话
```

`MahjongSceneProvider` 只声明每秒一次的拍摄策略、必要/可选能力、
`MahjongProcessor`、结果映射和场景内输入处理。

## Android Studio 运行

1. 使用 Android Studio 打开本文件所在的工程根目录。
2. 等待 Gradle Sync 完成，选择 `app` 配置。
3. 当前开发服务使用教程约定的 `local-token`。如服务端更换鉴权值，在用户级 Gradle
   配置中覆盖 API Key：

   ```properties
   ASSIST_API_KEY=<api-key>
   ```

4. 连接 RayNeo X3，授予相机权限后运行 `app`。

应用默认连接已部署的云端服务：

```text
http://39.96.67.192:8552/
```

无需修改源码即可切换服务端，配置优先级为：

```text
ASSIST_SERVER_BASE_URL（显式覆盖）
-> ASSIST_SERVER_ENV=local 时的 LOCAL_SERVER_BASE_URL
-> ASSIST_SERVER_ENV=cloud 时的 CLOUD_SERVER_BASE_URL
-> 内置云端地址
```

Android 模拟器连接电脑本地服务：

```properties
ASSIST_SERVER_ENV=local
LOCAL_SERVER_BASE_URL=http://10.0.2.2:8000/
# LOCAL_API_KEY=<api-key>  # 本地服务使用非默认 token 时设置
```

RayNeo X3 或 Android 真机连接同一局域网内的电脑：

```properties
ASSIST_SERVER_ENV=local
LOCAL_SERVER_BASE_URL=http://192.168.1.100:8000/
# LOCAL_API_KEY=<api-key>  # 本地服务使用非默认 token 时设置
```

临时指定任意测试服务时可以直接覆盖最终地址：

```properties
ASSIST_SERVER_BASE_URL=http://host-or-ip:port/
```

API Key 配置优先级为：

```text
ASSIST_API_KEY（显式覆盖所有环境）
-> ASSIST_SERVER_ENV=local 时的 LOCAL_API_KEY
-> ASSIST_SERVER_ENV=cloud 时的 CLOUD_API_KEY
-> 当前开发服务默认值 local-token
```

所有服务根地址必须是以 `/` 结尾的完整 `http` 或 `https` URL。旧配置项
`CLOUD_BASE_URL` 和 `CLOUD_API_KEY` 仍可作为兼容回退使用。当前网络层连接、读取和写入
超时统一为 120 秒，以覆盖当前公网服务的图像推理耗时。正式环境应通过用户级或 CI Gradle 属性覆盖
默认开发 token，不应把正式凭据写入仓库。

## 验证

标准验证命令：

```powershell
.\gradlew.bat test
.\gradlew.bat :app:assembleDebug
```

如果命令行 Gradle 在当前 Windows JDK 上报告 `Unable to establish loopback connection`，
可先在 Android Studio 内执行 Sync/Run。该错误发生在 Gradle daemon 建立本地通信时，
早于工程配置和源码编译。
