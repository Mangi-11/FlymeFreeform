# 第三方资源声明

## DexKit

本项目使用 LuckyPray 的 [DexKit 2.3.0](https://github.com/LuckyPray/DexKit/tree/2.3.0) 定位桌面 APK 中的混淆输入成员，使用 Maven Central 发布的原始 AAR，未修改库源码。

根据该版本发布配置，`Core/` 使用 LGPL-3.0，其余部分使用 Apache-2.0。部分源文件还保留 LGPL 声明，原始版权与许可声明以对应版本源码为准。完整源码可从上述版本链接取得；APK 的 `assets/licenses/` 附带 Apache-2.0、LGPL-3.0 与 GPL-3.0 文本。

其传递依赖包括 [FlatBuffers](https://github.com/google/flatbuffers)（Apache-2.0）和 [libcxx Prefab](https://github.com/RikkaW/libcxx-prefab)（发布元数据声明 Apache-2.0）。

## 应用图标

应用图标中的蓝色气泡图案取自魅族 22 的 Flyme 12.6.0.0A 官方固件，原资源属于“系统界面工具”应用，该应用包含“应用小窗”组件。固件来源：[Flyme 系统更新页面](https://www.flyme.com/firmwarelist-207.html#3)。

本项目保留原始透明 PNG 的图案与配色，增加白色自适应背景、比例留白与单色着色配置。图案的相关权利归原权利人所有，不属于本项目原创资源。固件内未发现该图标的独立再分发许可；公开分发前仍需确认相应授权，本声明不构成授权。

Flyme 小窗是面向 ColorOS 的非官方独立实现，与魅族、Flyme、OPPO、ColorOS 及其关联方无关。使用该图案不代表上述主体对本项目的授权、背书或支持。
