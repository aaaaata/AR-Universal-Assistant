# 模型文件目录

当前为快速验证云端流水线，模型权重临时进入 git 并打进 Docker 镜像。部署或本地调试前，模型文件结构为：

```text
models/
├── region/
│   └── yolo26_hand_detect.pt
└── tile/
    └── combined_original_and_new_data_best.pt
```

模型权重本身不区分 CPU 或 GPU；当前默认 manifest 按 CPU 推理配置加载。若部署具备 CUDA 运行时和 GPU 资源，应复用相同权重，并通过 GPU manifest 和部署资源显式启用 GPU 推理。

## 区域模型

- 作用：检测 `hand` 手牌区域和 `meld` 副露区域。
- 默认 manifest：`cloud/models/region.manifest.json`（CPU 推理配置）；`cloud/models/region.ultralytics.local.json` 仅用于本地 GPU 覆盖。
- 类别映射必须能输出 `hand` 与 `meld`，否则 `/health/ready` 不应通过。

## 牌面模型

- 作用：在 ROI 内识别具体麻将牌。
- 默认 manifest：`cloud/models/tile.manifest.json`。
- 当前默认权重：`models/tile/combined_original_and_new_data_best.pt`（实机暗光数据与原数据合并后的 best）。

## 替换规则

替换模型时优先修改 manifest 的 `model_path`、`model_version`、`predict_args` 和 `class_mapping`，不要修改云端 API 或主编排代码。

## 长期建议

模型内置镜像只用于当前流水线快速验证。根目录默认 Compose 不挂载 `./models`，避免空目录覆盖镜像内的权重。

正式部署建议改为 PV、制品或对象存储注入模型，避免模型文件长期污染 Git 历史并降低镜像重建成本。切换该策略时必须同时修改部署配置和 manifest 路径，并重新验证 `/health/ready`。
