# 核心定位

本工程是一套运行在 Android AR 眼镜上的通用场景 SDK 和产品宿主。它把设备能力、场景生命周期、视觉处理、网络通信和具体业务场景分开，使同一个 App 可以承载多个互不耦合的 `scene-*` 场景模块。

当前已经落地的业务场景只有 `scene-mahjong`，但通用 App 和 SDK 不直接引用麻将类型。构建系统按 `scene-*` 命名约定打包场景，运行时通过 Android Manifest 发现 `SceneModule`，主菜单再根据用户选择的 `sceneId` 启动对应场景。

当前产品边界如下：

- App 是页面和 Android 生命周期宿主，不实现具体业务识别。
- `sdk-core` 定义并执行场景、会话、事件和能力租约规则。
- `sdk-cv` 提供视觉场景共有的单帧处理骨架。
- `sdk-network` 只提供通用 HTTP 客户端创建能力。
- `sdk-platform-android` 把通用能力接口实现为 Android 系统能力。
- `sdk-device-rayneo-x3` 只处理 RayNeo X3/Mercury 的厂商接入。
- 每个 `scene-*` 模块独立拥有自己的配置、协议、业务处理和 UI。
- 云端识别服务是场景的外部依赖，不在 Android SDK 内运行。

当前实现支持多个编译期打包的场景，但同一时刻只运行一个活动场景。主界面的“添加场景”入口已经存在，云端场景目录、下载、签名校验、安装和动态注册尚未实现。

## 为什么要这样设计

最初只有麻将场景时，把 Provider、服务器地址、图标和结果 UI 直接写进 App 也能工作，但这样会产生三个问题：

1. 每增加一个场景都必须修改 App，App 会逐渐成为所有业务的集合。
2. 场景会接触 Camera2、Mercury 等实现细节，无法复用稳定的通用能力。
3. 生命周期、并发、权限和资源释放容易由每个场景重复实现，行为不一致。

现在的设计使用以下边界解决这些问题：

```text
App 只负责选择和展示场景
        ↓
SceneEngine 统一管理场景生命周期
        ↓
SceneSession 只交付该场景已经租用的能力
        ↓
Capability 隔离 Android 和厂商实现
        ↓
scene-* 只实现自己的业务规则、协议和 UI
```

这种拆分的目标不是让文件越多越好，而是让变化原因清楚：改主页面不碰场景协议，改相机实现不碰业务，新增场景不修改通用 App，切换设备时不复制 Core。

# 工程结构

## 整体架构

当前 Android 工程由一个产品 App、一个 SDK 聚合入口、五个 SDK 功能模块和若干场景模块组成：

```text
armahjongassist-develop-huangwl/
├── app
├── sdk
│   ├── sdk-bundle
│   ├── sdk-core
│   ├── sdk-cv
│   ├── sdk-network
│   ├── sdk-platform-android
│   └── sdk-device-rayneo-x3
└── scene-*
    └── scene-mahjong
```

依赖方向如下：

```text
app
├── sdk-bundle
│   ├── sdk-core
│   ├── sdk-cv ───────────────→ sdk-core
│   ├── sdk-network
│   ├── sdk-platform-android ─→ sdk-core
│   └── sdk-device-rayneo-x3 ─→ sdk-core + sdk-platform-android
└── 所有构建期发现的 scene-*
    └── scene-mahjong ────────→ sdk-core + sdk-cv + sdk-network
```

`sdk-bundle` 没有业务源码，只使用 Gradle `api(project(...))` 聚合 SDK 模块。当前聚合包包含 RayNeo X3 适配，因此它是当前产品的完整 Android/X3 交付入口，不是完全与设备无关的最小 Core 包。

场景存在两级发现：

1. `settings.gradle.kts` 扫描根目录下带构建脚本的 `scene-*` 目录并纳入 Gradle 工程。
2. App 构建脚本打包这些模块；模块自己的 Manifest 声明 `SceneModule` 类，运行时由 `AndroidSceneModuleDiscovery` 实例化。

这意味着通用 App 不枚举 `scene-mahjong`，但当前场景代码仍随 APK 编译和安装，并不是从云端动态下载。

## 模块介绍

### app

#### 核心定位

**一句话介绍：** 通用客户端的产品壳和总装入口。

**主要职责：** `app` 是应用入口和主界面层，主要负责主页面 UI、场景菜单、用户交互、权限申请、场景启动与结果展示。它只持有“当前页面”“用户选择的 `sceneId`”和“SDK 会话状态”等宿主状态，不实现具体场景的网络协议、识别逻辑或业务 UI。

#### 工程结构

```text
app/
├── entry
│   ├── AssistApplication.kt          启动 Android 进程并初始化眼镜厂商 SDK
│   └── MainActivity.kt               连接生命周期、眼镜输入、权限和页面渲染
├── assembly
│   └── AppAssembler.kt               发现全部场景并装配 SDK 与页面依赖
├── scene
│   ├── SceneController.kt            按 sceneId 控制场景启动、停止和输入
│   └── AppSceneModels.kt             定义主菜单使用的通用场景摘要
├── ui
│   ├── MainViewModel.kt              管理菜单选择、活动场景和页面状态
│   ├── MainUiModels.kt               定义页面状态、菜单项和一次性 UI 效果
│   ├── SceneInputPolicy.kt           把输入动作和会话状态转换成宿主命令
│   ├── MainScreenRenderer.kt         渲染公共菜单、状态、提示和场景容器
│   └── SceneRenderRouter.kt          将结果路由给 sceneId 对应的 Renderer
└── permission
    ├── AndroidPermissionResolver.kt  将能力需求映射成 Android 权限
    ├── PermissionRequester.kt        发起系统权限请求并等待结果
    └── PermissionModels.kt           定义权限请求和授权结果
```

#### 核心内部接口

这些接口只负责 App 与其他模块协作，不作为 SDK 对外 API 发布：

| 关键契约                        | 连接的模块                                               | 在 App 中的作用                                      |
| --------------------------- | --------------------------------------------------- | ----------------------------------------------- |
| `AppAssembler`              | `app` ↔ `sdk-platform-android`、`sdk-core`、`scene-*` | 发现场景，创建 Android SDK 环境，并把注册信息交给页面层              |
| `SceneController`           | `app` ↔ `sdk-core`                                  | 把页面的启动、停止和输入操作转换为 `SceneEngine` 调用              |
| `SceneRenderRouter`         | `app` ↔ `scene-*`                                   | 根据 `sceneId` 和 `rendererKey` 把场景结果交给对应 Renderer |
| `AndroidPermissionResolver` | `app` ↔ `sdk-core`                                  | 把 Core 中声明的能力需求转换成宿主需要申请的 Android 权限            |

| 关键契约                        | 连接的模块                                               | 在 App 中的作用                                      |
| --------------------------- | --------------------------------------------------- | ----------------------------------------------- |
| `AppAssembler`              | `app` ↔ `sdk-platform-android`、`sdk-core`、`scene-*` | 发现场景，创建 Android SDK 环境，并把注册信息交给页面层              |
| `SceneController`           | `app` ↔ `sdk-core`                                  | 把页面的启动、停止和输入操作转换为 `SceneEngine` 调用              |
| `SceneRenderRouter`         | `app` ↔ `scene-*`                                   | 根据 `sceneId` 和 `rendererKey` 把场景结果交给对应 Renderer |
| `AndroidPermissionResolver` | `app` ↔ `sdk-core`                                  | 把 Core 中声明的能力需求转换成宿主需要申请的 Android 权限            |

### sdk-core

#### 核心定位

**一句话介绍：** 整个 SDK 的通用规则和运行中枢。

**主要职责：** `sdk-core` 定义场景、会话、状态、事件和能力的通用契约，并实现场景生命周期、会话隔离和能力租约。它不知道具体场景是什么，也不直接创建 Camera2、传感器或 Mercury 对象；其中 `runtime` 表示这些通用规则在 App 进程内的实现，不表示 Android 底层。

#### 工程结构

```text
sdk-core/
├── engine
│   ├── SceneEngine.kt              定义宿主管理场景生命周期的总入口
│   ├── SdkConfig.kt                定义 SDK、场景配置和凭据提供契约
│   └── SdkEvent.kt                 定义会话状态以及结果、错误和诊断事件
├── scene
│   ├── SceneModule.kt              定义独立场景模块的完整注册入口
│   ├── SceneProvider.kt            定义场景描述、渲染器及启停生命周期
│   ├── SceneDescriptor.kt          描述场景身份、版本和能力需求
│   ├── SceneSession.kt             向活动场景提供能力、凭据和事件发布端口
│   ├── SceneResult.kt              定义通用结果外壳和宿主可读摘要
│   └── SceneRendering.kt           定义场景私有 Payload 与 Renderer 契约
├── capability
│   ├── SdkCapability.kt            定义所有能力的可用性和释放生命周期
│   ├── CapabilityRequirement.kt    定义能力需求、独占标记和标准能力 ID
│   ├── CameraCapability.kt         定义相机参数、选帧和编码帧回调
│   ├── AudioCapabilities.kt        定义麦克风采集和音频播放能力
│   ├── InputCapability.kt          定义通用输入动作、映射器和输入流
│   ├── NetworkCapability.kt        定义网络可用性查询能力
│   ├── DeviceCapability.kt         定义设备信息和动态状态查询能力
│   ├── DisplayCapability.kt        定义显示尺寸、刷新率和双目信息能力
│   ├── LocationCapability.kt       定义位置更新流和位置样本
│   └── MotionCapability.kt         定义加速度与角速度样本流
└── runtime
    ├── CoreSceneEngine.kt          实现场景状态机、会话隔离和生命周期编排
    ├── SceneRegistry.kt            按 sceneId 保存、查找和清理场景
    └── CapabilityRegistry.kt       注册能力并分配、校验和释放会话租约
```

| 目录           | 职责                     |
| ------------ | ---------------------- |
| `engine`     | 宿主可见的引擎入口、配置、状态和事件     |
| `scene`      | 场景安装、生命周期、会话、结果和渲染契约   |
| `capability` | 与平台实现无关的能力接口和数据模型      |
| `runtime`    | 状态机、注册表、会话隔离和能力租约的实际实现 |

能力接口按领域放在一个文件中，没有把每个小数据类继续拆开。当前标准能力 ID 包括 Camera、Input、Device、Network、Microphone、Audio Output、Motion、Display 和 Location。

#### 核心内部接口

| 关键契约                              | 连接的模块                                                  | 契约作用                               |
| --------------------------------- | ------------------------------------------------------ | ---------------------------------- |
| `SceneEngine`                     | `sdk-core` ↔ `app`、`sdk-platform-android`              | 对宿主提供统一的场景注册、启停、状态和事件入口            |
| `SceneModule`、`SceneRegistration` | `sdk-core` ↔ `scene-*`、`sdk-platform-android`、`app`    | 让场景模块提供完整注册信息，并让发现层和宿主以通用方式消费      |
| `SceneProvider`、`SceneSession`    | `sdk-core` ↔ `scene-*`、`sdk-cv`                        | 约束场景生命周期，并向活动场景交付能力、凭据和事件发布端口      |
| `SdkCapability` 及各领域能力接口          | `sdk-core` ↔ `sdk-platform-android`、`scene-*`、`sdk-cv` | 平台模块负责实现，场景模块只按通用接口使用硬件能力          |
| `SceneResult`、`SceneRenderer`     | `sdk-core` ↔ `scene-*`、`app`                           | 让场景发布私有结果，同时由 App 把结果交回场景 Renderer |

Core 保证一次只运行一个活动场景。启动时先一次性取得全部必要能力租约；任一必要能力不可用或发生独占冲突，启动整体失败，不保留部分租约。可选能力不可用时会跳过，场景通过 `optionalCapability()` 得到 `null`。

### sdk-cv

#### 核心定位

**一句话介绍：** 可直接套用的标准视觉场景骨架。

**主要职责：** `sdk-cv` 固化相机启动、选帧、单飞处理、错误上报、结果发布、临时文件删除和停止清理，让视觉场景只实现自身业务处理。它不认识麻将协议，也不直接依赖 Camera2，只使用 `sdk-core` 定义的 `CameraCapability`。

#### 工程结构

```text
sdk-cv/
├── CVSceneProvider.kt       固化相机启动、逐帧处理、结果发布和清理流程
└── FrameProcessingGate.kt   控制最短选帧间隔并保证同一时间只有一帧在途
```

`captureIntervalMillis` 是最短选帧间隔，不是固定上传频率。如果上一帧仍在编码、上传或识别，后续帧直接跳过，不排队。

#### 核心内部接口

| 关键契约                 | 连接的模块                                        | 契约作用                                          |
| -------------------- | -------------------------------------------- | --------------------------------------------- |
| `CVSceneProvider<R>` | `sdk-cv` ↔ `scene-*`、`sdk-core`              | 场景继承它补充视觉业务差异，它再按 Core 的 `SceneProvider` 契约运行 |
| `CVProcessor<R>`     | `sdk-cv` ↔ `scene-*`                         | 把通用采集流程与场景自己的会话建立、单帧处理和停止逻辑隔开                 |
| `CameraCapability`   | `sdk-cv` ↔ `sdk-core`、`sdk-platform-android` | CV 骨架依赖 Core 接口取得图像，实际相机由 Android 平台模块提供      |

### sdk-network

#### 核心定位

**一句话介绍：** 各场景共用的 HTTP 客户端工具箱。

**主要职责：** `sdk-network` 统一创建和配置 HTTP 服务，但不定义任何场景路径、请求字段或鉴权规则。它也不等同于 `NetworkCapability`：前者实际发送 HTTP，后者只查询设备当前是否联网。

#### 工程结构

```text
sdk-network/
└── NetworkClient.kt   定义 HTTP 配置、追踪元数据和 Retrofit 服务创建工厂
```

这个文件集中保存相关的小型网络契约，避免只有一个实现仍拆成多个空目录。

#### 核心内部接口

| 关键契约                      | 连接的模块                     | 契约作用                              |
| ------------------------- | ------------------------- | --------------------------------- |
| `NetworkServiceFactory`   | `sdk-network` ↔ `scene-*` | 场景提供自己的 Retrofit 协议，网络模块按通用配置创建实现 |
| `NetworkClientConfig`     | `sdk-network` ↔ `scene-*` | 由场景传入服务地址和超时，不让网络模块绑定具体业务服务       |
| `RequestMetadataProvider` | `sdk-network` ↔ `scene-*` | 为场景请求提供通用追踪信息，不接触场景凭据和业务字段        |

### sdk-platform-android

#### 核心定位

**一句话介绍：** 通用 SDK 接入 Android 系统能力的适配层。

**主要职责：** `sdk-platform-android` 负责发现 Manifest 中的场景模块、根据场景声明按需创建 Android 能力，并把 Camera2、音频、位置和传感器等系统 API 实现为 `sdk-core` 的通用 Capability。它不包含具体业务，也不处理 RayNeo 独有的 Mercury 事件。

#### 工程结构

```text
sdk-platform-android/
├── assembly
│   ├── AndroidSceneModuleDiscovery.kt  从合并 Manifest 中发现 SceneModule
│   └── AndroidSdkAssembler.kt          按场景声明创建能力并装配 Core 引擎
├── capability
│   ├── audio
│   │   ├── AndroidMicrophoneCapability.kt   使用 AudioRecord 发布 PCM 音频块
│   │   └── AndroidAudioOutputCapability.kt  使用 MediaPlayer 播放本地音频
│   ├── camera
│   │   ├── Camera2Capability.kt             管理无预览 Camera2 采集和临时帧文件
│   │   └── Yuv420JpegEncoder.kt             将 YUV_420_888 图像编码为 JPEG
│   ├── device/AndroidDeviceCapability.kt    查询设备、系统、电量和连接状态
│   ├── display/AndroidDisplayCapability.kt  查询显示尺寸、刷新率和双目属性
│   ├── input/DefaultInputCapability.kt      接收宿主发布的通用输入动作
│   ├── location/AndroidLocationCapability.kt  使用 LocationManager 发布位置
│   ├── motion/AndroidMotionCapability.kt    合并加速度计和陀螺仪数据
│   └── network/AndroidNetworkCapability.kt  查询 Android 活动网络是否可用
└── device
    └── DeviceSdkInitializer.kt              定义厂商 SDK 初始化入口
```

#### 核心内部接口

| 关键契约                                            | 连接的模块                                         | 契约作用                                              |
| ----------------------------------------------- | --------------------------------------------- | ------------------------------------------------- |
| `AndroidSceneModuleDiscovery`                   | `sdk-platform-android` ↔ `scene-*`、`app`      | 读取场景 Manifest 声明，并把发现的 `SceneRegistration` 交给 App |
| `AndroidSdkAssembler`、`AndroidSceneEnvironment` | `sdk-platform-android` ↔ `app`、`sdk-core`     | 创建 Android 能力和 Core 引擎，并向 App 返回可运行环境             |
| Android Capability 实现                           | `sdk-platform-android` ↔ `sdk-core`           | 把 Android 系统 API 实现为 Core 定义的能力契约                 |
| `DeviceSdkInitializer`                          | `sdk-platform-android` ↔ `sdk-device-*`、`app` | 定义厂商 SDK 初始化标准，由设备模块实现、App 调用                     |

当前装配器只创建所有场景声明过的能力，不会无条件实例化全部能力。它当前要求 `scenes` 非空，因此虽然 App 的菜单和路由已经能表示“只有添加按钮”，完全不预装场景的 APK 仍需先调整装配器，这也是实现云端场景中心前需要处理的限制。

### sdk-device-rayneo-x3

#### 核心定位

**一句话介绍：** RayNeo X3 眼镜的厂商差异转换器。

**主要职责：** `sdk-device-rayneo-x3` 把 Mercury SDK 初始化和厂商触控动作翻译接入通用 SDK。它只处理 RayNeo X3 特有差异，不创建 Core 引擎、不管理场景，也不实现麻将业务。

#### 工程结构

```text
sdk-device-rayneo-x3/
├── MercurySdkInitializer.kt     初始化 Mercury 厂商 SDK
└── MercuryInputActionMapper.kt  将 Mercury 触控事件转换为 InputAction
```

#### 核心内部接口

| 关键契约                       | 连接的模块                                                 | 契约作用                                            |
| -------------------------- | ----------------------------------------------------- | ----------------------------------------------- |
| `MercurySdkInitializer`    | `sdk-device-rayneo-x3` ↔ `sdk-platform-android`、`app` | 实现 `DeviceSdkInitializer`，由 App 在进程启动时调用        |
| `MercuryInputActionMapper` | `sdk-device-rayneo-x3` ↔ `sdk-core`、`app`             | 实现 Core 的输入映射契约，把 Mercury 事件转换为通用 `InputAction` |

App 当前仍明确选择 RayNeo X3 适配，这是产品设备选择，不是业务场景绑定。支持另一款眼镜时，应新增独立设备模块并替换这两个入口，而不是修改 Core 或复制场景。

### scene-*

#### 核心定位

**一句话介绍：** 可独立接入通用客户端的业务场景插件。

**主要职责：** 每个 `scene-*` 模块完整拥有自己的场景入口、会话配置、凭据来源、服务协议、业务处理、结果映射和 Renderer，通用 App 只消费 `SceneRegistration`。当前 `scene-mahjong` 负责广东麻将云端会话、图像分析、结果转换和麻将 UI，但不负责 Camera2 生命周期和 Core 能力租约。

#### 工程结构

```text
scene-mahjong/
├── scene
│   ├── MahjongSceneModule.kt    组装 Provider、配置、凭据和菜单信息
│   ├── MahjongSceneProvider.kt  声明能力并填充标准 CV 场景策略
│   ├── MahjongSceneConfig.kt    定义服务、规则、选帧和相机参数
│   ├── MahjongProcessor.kt      管理云端会话并处理每一帧
│   └── MahjongResultMapper.kt   将云端响应转换为 SDK 结果
├── network
│   ├── MahjongGateway.kt        定义 Retrofit 协议和云端访问实现
│   └── MahjongApiModels.kt      定义麻将服务请求与响应 DTO
└── ui
    ├── MahjongScenePayload.kt   定义 Renderer 使用的麻将私有数据
    └── MahjongSceneRenderer.kt  创建并更新麻将业务界面
```

#### 核心内部接口

| 关键契约                                         | 连接的模块                                                     | 契约作用                               |
| -------------------------------------------- | --------------------------------------------------------- | ---------------------------------- |
| `MahjongSceneModule`                         | `scene-mahjong` ↔ `sdk-core`、`sdk-platform-android`、`app` | 实现 `SceneModule`，向发现层和宿主提供完整场景注册信息 |
| `MahjongSceneProvider`                       | `scene-mahjong` ↔ `sdk-cv`、`sdk-core`                     | 填充标准 CV 场景策略，并按 Core 生命周期运行        |
| `NetworkServiceFactory`                      | `scene-mahjong` ↔ `sdk-network`                           | 由麻将场景提供协议和配置，由网络模块创建 Retrofit 服务   |
| `MahjongScenePayload`、`MahjongSceneRenderer` | `scene-mahjong` ↔ `sdk-core`、`app`                        | 通过 Core 的渲染契约，让 App 路由并展示麻将私有结果    |

除 `SceneModule` 入口以及确有跨模块使用价值的模型外，场景内部网络、处理器和 DTO 应保持 `internal`，避免把业务实现误当成 SDK 公共 API。

# 业务流程

## 1. 场景构建与运行时发现

```text
settings.gradle.kts 扫描 scene-* 目录
  → app/build.gradle.kts 将所有 scene-* 加入 APK
  → 场景模块 Manifest 合并到应用 Manifest
  → AndroidSceneModuleDiscovery 查找 scene_module.* 元数据
  → 反射创建 SceneModule
  → SceneModule.createRegistration()
  → AppAssembler 得到全部 SceneRegistration
```

具体业务配置留在场景模块内。以麻将为例，Provider、服务器地址、API Key、菜单标题、图标和初始 Payload 都由 `MahjongSceneModule` 创建，App 不导入任何麻将类。

## 2. App 与 SDK 初始化

```text
AssistApplication
  → MercurySdkInitializer.initialize()

MainActivity
  → MainViewModelFactory
  → AppAssembler
  → AndroidSceneModuleDiscovery
  → AndroidSdkAssembler
  → CoreSceneEngine.registerScene/registerCapability
  → CoreSceneEngine.initialize
  → SessionState.READY
```

`AndroidSdkAssembler` 汇总全部场景的必要和可选能力 ID，只创建声明过的能力。宿主可以通过 `capabilityOverrides` 替换指定实现，但不能传入没有任何场景声明的多余覆盖项。

## 3. 用户选择场景

```text
场景注册信息
  → SceneController.scenes
  → MainViewModel 生成 Registered 菜单项
  → 用户滑动或点击选择
  → activeSceneId = 被选中菜单项的 sceneId
  → 进入对应场景页
```

后续权限、启动、结果过滤和 Renderer 路由都使用这个 `activeSceneId`，不会默认启动某个固定业务场景。

“添加场景”始终作为菜单最后一项。当前点击后只产生 `OpenSceneCatalog`，Activity 显示“场景中心即将开放”，没有发起网络请求或安装模块。

## 4. 权限申请与场景启动

```text
用户在场景页三击
  → SceneInputPolicy: READY + TRIPLE_CLICK = START_SCENE
  → ViewModel 读取 activeSceneId 对应的 requiredCapabilities
  → AndroidPermissionResolver 转换为 Android 权限
  → PermissionRequester 请求缺失权限
  → SceneController.startScene(activeSceneId)
  → SceneEngine.startScene(sceneId, registration.config)
```

权限拒绝时不启动场景。当前提示文本固定为“需要相机权限”，对未来麦克风或位置场景还需要改成按拒绝权限生成通用提示。

## 5. Core 会话与能力租约

```text
READY
  → STARTING
  → 查找 SceneProvider
  → 为必要/可选能力申请会话租约
  → 创建隔离 SceneSession
  → SceneProvider.start(session)
  → RUNNING
```

必要能力未注册、不可用或发生独占冲突时，Core 释放已经取得的租约并进入 `ERROR`。场景只能通过当前 `SceneSession` 访问已经租用的能力，不能任意取得注册表里的其他硬件对象。

## 6. 标准 CV 场景处理

麻将场景继承 `CVSceneProvider`，当前单帧流程为：

```text
MahjongSceneProvider.start
  → MahjongProcessor.start
  → 创建云端麻将 session
  → 取得 CameraCapability
  → Camera2Capability.start
  → FrameProcessingGate 选择一帧
  → YUV/JPEG 编码到临时文件
  → MahjongProcessor.process
  → MahjongGateway 上传图片
  → 云端返回 AnalyzeResponse
  → MahjongResultMapper 生成 SceneResult
  → SceneSession.publishResult
  → 删除临时图片并释放单飞门禁
```

Camera2 重复请求当前默认是 15 FPS；麻将场景最短选帧间隔是 1000 ms。只有前一帧完成后下一帧才可能进入处理，因此识别慢时不会累计大量待上传图片。

## 7. 结果与 UI 路由

```text
SceneSession.publishResult
  → Core 校验 sceneId 和 sessionId
  → SdkEvent.ResultReceived
  → MainViewModel 只接受 activeSceneId 的结果
  → MainUiEffect.RenderResult
  → SceneRenderRouter 按 sceneId + rendererKey 查找 Renderer
  → 场景 Renderer 更新宿主内容容器
```

通用 App 只理解 `SceneSummary`。`SceneContent` 中的私有 Payload 由场景自己的 Renderer 解释，因此新增业务结果字段不需要修改主页面。

## 8. 停止、错误与释放

```text
RUNNING 状态三击，或 App 进入后台
  → SceneEngine.stopScene
  → STOPPING
  → SceneProvider.stop
  → 业务 Processor.stop
  → 结束远端会话
  → 释放全部 Capability 租约
  → READY
```

可恢复错误通过 `SdkEvent.ErrorOccurred(recoverable = true)` 通知宿主，场景继续运行。不可恢复错误会释放租约并进入 `ERROR`。`close()` 会停止活动场景、关闭所有能力、清空注册表，最终回到 `UNINITIALIZED`。

# 核心对外接口

这一部分只介绍 SDK 使用者需要直接调用或实现的稳定契约。模块内部的注册表、并发门禁、具体 Android 实现和场景业务类不属于对外接口。

## 面向 SDK 开发者

这里的 SDK 开发者指负责宿主接入、平台能力实现或设备适配的开发者，而不是具体业务场景作者。

### 宿主装配与场景控制

```kotlin
val registrations = AndroidSceneModuleDiscovery.discover(applicationContext)
val credentials = registrations.associate {
    it.provider.descriptor.sceneId to it.credential
}
val environment = AndroidSdkAssembler.assemble(
    context = applicationContext,
    scenes = registrations.map { it.provider },
    credentialProvider = CredentialProvider { sceneId -> credentials[sceneId] }
)
```

| 对外接口                                     | 所属模块与文件                                                        | 使用方式                                      |
| ---------------------------------------- | -------------------------------------------------------------- | ----------------------------------------- |
| `AndroidSceneModuleDiscovery.discover()` | `sdk-platform-android/assembly/AndroidSceneModuleDiscovery.kt` | 发现 APK Manifest 中声明的全部场景模块                |
| `AndroidSdkAssembler.assemble()`         | `sdk-platform-android/assembly/AndroidSdkAssembler.kt`         | 创建 Android 默认能力、Core 引擎和可运行环境             |
| `AndroidSceneEnvironment`                | `sdk-platform-android/assembly/AndroidSdkAssembler.kt`         | 向宿主交付 `sceneEngine` 与可选的 `inputPublisher` |
| `SceneEngine.state`                      | `sdk-core/engine/SceneEngine.kt`                               | 观察当前会话状态                                  |
| `SceneEngine.events`                     | `sdk-core/engine/SceneEngine.kt`                               | 接收结果、错误、诊断和状态事件                           |
| `SceneEngine.registerScene()`            | `sdk-core/engine/SceneEngine.kt`                               | 手动注册场景；自动发现模式通常由装配器代劳                     |
| `SceneEngine.registerCapability()`       | `sdk-core/engine/SceneEngine.kt`                               | 手动注册能力；Android 默认能力通常由装配器代劳               |
| `SceneEngine.registeredScenes()`         | `sdk-core/engine/SceneEngine.kt`                               | 查询已注册的场景描述                                |
| `SceneEngine.startScene()`               | `sdk-core/engine/SceneEngine.kt`                               | 按 `sceneId` 和强类型 `SceneConfig` 启动场景       |
| `SceneEngine.stopScene()`                | `sdk-core/engine/SceneEngine.kt`                               | 停止活动场景并释放其能力租约                            |
| `SceneEngine.close()`                    | `sdk-core/engine/SceneEngine.kt`                               | 永久关闭引擎和能力资源                               |
| `SdkConfig`                              | `sdk-core/engine/SdkConfig.kt`                                 | 配置引擎级默认超时                                 |
| `CredentialProvider`                     | `sdk-core/engine/SdkConfig.kt`                                 | 按 `sceneId` 向活动会话提供凭据                     |
| `SessionState`、`SdkEvent`                | `sdk-core/engine/SdkEvent.kt`                                  | 表达引擎状态以及结果、错误和诊断事件                        |

### 平台能力实现

| 对外接口                                                            | 所属模块与文件                                        | 实现责任                       |
| --------------------------------------------------------------- | ---------------------------------------------- | -------------------------- |
| `SdkCapability`                                                 | `sdk-core/capability/SdkCapability.kt`         | 所有能力实现共同遵守的可用性、租约释放和关闭生命周期 |
| `CapabilityRequirement`、`StandardCapabilities`                  | `sdk-core/capability/CapabilityRequirement.kt` | 定义能力 ID、必要/可选要求和是否独占       |
| `CameraCapability`                                              | `sdk-core/capability/CameraCapability.kt`      | 提供按配置采集和筛选编码帧的相机能力         |
| `MicrophoneCapability`、`AudioOutputCapability`                  | `sdk-core/capability/AudioCapabilities.kt`     | 提供音频采集和播放能力                |
| `InputCapability`、`InputActionMapper<T>`、`InputActionPublisher` | `sdk-core/capability/InputCapability.kt`       | 接收、转换和发布通用输入动作             |
| `NetworkCapability`                                             | `sdk-core/capability/NetworkCapability.kt`     | 查询设备当前网络是否可用               |
| `DeviceCapability`                                              | `sdk-core/capability/DeviceCapability.kt`      | 查询设备静态信息和动态状态              |
| `DisplayCapability`                                             | `sdk-core/capability/DisplayCapability.kt`     | 查询显示尺寸、刷新率和双目信息            |
| `LocationCapability`                                            | `sdk-core/capability/LocationCapability.kt`    | 提供位置样本流                    |
| `MotionCapability`                                              | `sdk-core/capability/MotionCapability.kt`      | 提供加速度和角速度样本流               |

实现或替换 Capability 时，`isAvailable()` 必须真实反映权限、硬件和关闭状态；`onLeaseReleased()` 只停止当前会话的硬件操作，允许未来重新租用；`close()` 永久释放线程、监听器和系统资源。实现类型必须与标准能力 ID 匹配，并由 Core 统一管理共享或独占租约。

### 设备适配

| 对外接口                   | 所属模块与文件                                               | 使用方式                               |
| ---------------------- | ----------------------------------------------------- | ---------------------------------- |
| `DeviceSdkInitializer` | `sdk-platform-android/device/DeviceSdkInitializer.kt` | 新设备模块实现它，App 在 `Application` 启动时调用 |
| `InputActionMapper<T>` | `sdk-core/capability/InputCapability.kt`              | 新设备模块用它把厂商输入事件翻译为通用 `InputAction`  |

能通过标准 Android API 实现的相机、音频、显示、位置和传感器能力应继续复用 `sdk-platform-android`；设备模块只承接厂商专属初始化和输入差异。

## 面向场景模块开发者

### 通用场景注册

| 对外接口                                          | 所属模块与文件                             | 场景开发者的责任                                  |
| --------------------------------------------- | ----------------------------------- | ----------------------------------------- |
| `SceneModule`                                 | `sdk-core/scene/SceneModule.kt`     | 实现场景模块入口并返回完整注册信息                         |
| `SceneRegistration`、`ScenePresentation`       | `sdk-core/scene/SceneModule.kt`     | 提供 Provider、配置、凭据、菜单标题、图标和初始内容            |
| `SceneDescriptor`                             | `sdk-core/scene/SceneDescriptor.kt` | 声明稳定的场景 ID、名称、版本及能力要求                     |
| `SceneConfig`                                 | `sdk-core/engine/SdkConfig.kt`      | 定义该场景自己的强类型启动配置                           |
| `SceneProvider`                               | `sdk-core/scene/SceneProvider.kt`   | 实现场景描述、Renderer、`start()` 和 `stop()` 生命周期 |
| `SceneSession`                                | `sdk-core/scene/SceneSession.kt`    | 获取已租用能力和凭据，并发布结果、错误或诊断                    |
| `SceneResult`、`SceneSummary`                  | `sdk-core/scene/SceneResult.kt`     | 返回通用结果外壳和宿主可读摘要                           |
| `SceneRenderer`、`ScenePayload`、`SceneContent` | `sdk-core/scene/SceneRendering.kt`  | 定义场景私有数据及其 UI 渲染方式                        |
| `NetworkServiceFactory`、`NetworkClientConfig` | `sdk-network/NetworkClient.kt`      | 按场景自己的 Retrofit 协议和服务地址创建 HTTP 服务         |

所有场景都需要完成类似下面的注册：

```kotlin
class ExampleSceneModule : SceneModule {
    override fun createRegistration() = SceneRegistration(
        provider = ExampleSceneProvider(),
        config = ExampleSceneConfig(...),
        credential = obtainCredentialSafely(),
        presentation = ScenePresentation(
            menuTitle = "示例场景",
            menuIconRes = R.drawable.ic_scene_example,
            initialPayload = ExamplePayload.EMPTY
        )
    )
}
```

场景还必须在本模块的 `scene-<name>/src/main/AndroidManifest.xml` 中声明 `SceneModule` 入口：

```xml
<application>
    <meta-data
        android:name="com.example.ai_assist.sdk.scene_module.example"
        android:value="com.example.scene.ExampleSceneModule" />
</application>
```

入口类需要公开的无参构造方法；如果 Release 开启代码压缩，还要添加对应 consumer ProGuard 保留规则。`sceneId`、Manifest 元数据名称和 `rendererKey` 都必须全局唯一且保持稳定。

### 标准 CV 场景

需要连续拍照并逐帧处理的场景优先继承 `CVSceneProvider<R>`，不要重新实现相机并发和临时文件清理。

| 对外接口或成员                            | 所属模块与文件                     | 场景开发者的责任                       |
| ---------------------------------- | --------------------------- | ------------------------------ |
| `CVSceneProvider<R>`               | `sdk-cv/CVSceneProvider.kt` | 继承标准视觉场景骨架                     |
| `descriptor`                       | `sdk-cv/CVSceneProvider.kt` | 提供场景 ID、版本和能力声明，Camera 必须是必要能力 |
| `renderer`                         | `sdk-cv/CVSceneProvider.kt` | 提供场景私有结果 Renderer              |
| `capturePolicy(config)`            | `sdk-cv/CVSceneProvider.kt` | 返回最短选帧间隔                       |
| `cameraConfig(config)`             | `sdk-cv/CVSceneProvider.kt` | 返回期望图像尺寸和相机帧率                  |
| `createProcessor(session)`         | `sdk-cv/CVSceneProvider.kt` | 创建场景业务处理器                      |
| `mapResult(result, session)`       | `sdk-cv/CVSceneProvider.kt` | 把业务结果转换为 `SceneResult`         |
| `eventCodes`                       | `sdk-cv/CVSceneProvider.kt` | 可选：替换标准诊断与错误码                  |
| `onSessionStarted(session, scope)` | `sdk-cv/CVSceneProvider.kt` | 可选：订阅输入、音频或其他能力                |
| `CVProcessor.start()`              | `sdk-cv/CVSceneProvider.kt` | 建立业务会话或准备处理资源                  |
| `CVProcessor.process()`            | `sdk-cv/CVSceneProvider.kt` | 处理一张已编码图片并返回业务结果               |
| `CVProcessor.stop()`               | `sdk-cv/CVSceneProvider.kt` | 结束业务会话并释放场景私有资源                |
| `CapturePolicy`、`CVEventCodes`     | `sdk-cv/CVSceneProvider.kt` | 配置选帧节奏和稳定事件码                   |

`CVSceneProvider` 已负责 Camera2 上层的相机启停、单飞处理和 `CapturedFrame.file` 删除。标准 CV 场景只处理业务差异，不应再次创建相机、排帧或清理通用临时文件。

### 高级场景

不符合“相机帧进入、处理、返回结果”模型的场景，可以直接实现 `SceneProvider`，例如纯语音、位置导航、传感器交互或同时协调多种异步输入的场景。

| 对外接口或成员                             | 所属模块与文件                           | 场景开发者的责任                         |
| ----------------------------------- | --------------------------------- | -------------------------------- |
| `SceneProvider.descriptor`          | `sdk-core/scene/SceneProvider.kt` | 准确声明全部必要和可选 Capability           |
| `SceneProvider.start(session)`      | `sdk-core/scene/SceneProvider.kt` | 获取能力并启动场景自己的协程、订阅和业务会话           |
| `SceneProvider.stop()`              | `sdk-core/scene/SceneProvider.kt` | 取消任务并释放全部场景私有资源                  |
| `SceneProvider.renderer`            | `sdk-core/scene/SceneProvider.kt` | 提供与结果 `rendererKey` 匹配的 Renderer |
| `SceneSession.requireCapability()`  | `sdk-core/scene/SceneSession.kt`  | 获取启动前已经租用的必要能力                   |
| `SceneSession.optionalCapability()` | `sdk-core/scene/SceneSession.kt`  | 获取可选能力；不可用时接受 `null`             |
| `SceneSession.publishResult()`      | `sdk-core/scene/SceneSession.kt`  | 发布属于当前 `sceneId/sessionId` 的结果   |
| `SceneSession.reportError()`        | `sdk-core/scene/SceneSession.kt`  | 报告可恢复或不可恢复错误                     |
| `SceneSession.reportDiagnostic()`   | `sdk-core/scene/SceneSession.kt`  | 发布不包含凭据的诊断信息                     |

高级场景不能直接访问 Core 注册表，也不应绕过 Capability 调用 Android 硬件。平台模块先把硬件实现注册到 Core，Core 再通过会话租约把允许使用的能力交给场景。

当前 `SceneModule` 是 APK 内模块发现契约，不是云端代码下载契约。未来实现场景中心时，需要在它之前增加目录、下载、签名、兼容性、安装和持久化层，并决定下载声明式场景包还是可执行代码；现有生命周期和 Capability 接口仍可作为安装后的运行边界。
