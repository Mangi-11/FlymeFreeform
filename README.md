<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_mark.png" width="112" alt="Flyme 小窗图标" />
</p>

<h1 align="center">Flyme 小窗（FlymeFreeform）</h1>

<p align="center">
  <img src="https://img.shields.io/badge/ROM-ColorOS-00A862" alt="面向 ColorOS" />
  <img src="https://img.shields.io/badge/minSdk-35-3DDC84?logo=android" alt="最低 API 35" />
  <img src="https://img.shields.io/badge/libxposed-API_102-4285F4" alt="libxposed API 102" />
</p>

魅族 Flyme 的小窗，将“呼之即来，挥之即去”做得轻巧又顺手。几次简单的滑动与轻点，便能处理眼前的小事，这份细腻的巧思让人愉悦。

本项目通过 Xposed 模块，将这份即用即走的快捷交互带到 ColorOS。

## 预览

<p align="center">
  <img src="docs/images/preview.gif" width="360" alt="Flyme 小窗交互预览" />
</p>

## 功能

- **快速唤出**：从左右下角斜向内上滑，呼出扇形菜单，滑选应用后松手以小窗打开。
- **窗外关闭**：支持单击或双击小窗外关闭，也可关闭此功能。
- **双击横条关闭**：开启「双击小横条快速关闭」后，双击普通小窗底部横条关闭当前小窗；默认关闭，保留原有拖动与上滑手势。
- **上滑迷你窗**：快速上滑普通小窗底部横条，切换为迷你小窗。
- **应用管理**：固定容量由扇形布局动态计算，支持拖动排序，通过“更多”访问其他应用与系统工具。缩小容量时保留固定列表与顺序，超出部分暂不显示在扇形中。
- **扇形布局**：可调整图标尺寸、扇形半径、圈间距与圈数；半径最大为手机短边，圈数上限自动计算，拖动滑块时显示实时示例。
- **手势设置**：左右入口独立开关，可调整角落触发范围。
- **自动暂停**：默认在横屏或游戏模式下暂停增强，退出后自动恢复，可分别关闭。
- **防误触**：可分别开启横屏或游戏模式下的角落手势防误触，首次有效滑动提示“请再划一次”，2 秒内从同一侧再次滑动才唤出菜单。默认关闭，仅在对应的暂停功能关闭时可设置。

*更多功能持续开发中。*

## 设计与实现

本项目借鉴 Flyme 的快捷交互，复刻扇形菜单的展开动画与滑选体验；“更多”窗口复用 ColorOS 智能侧边栏的“全部”面板，应用窗口继续由系统已有的自由窗能力管理。

完整照搬另一套系统的界面，容易造成视觉与操作上的割裂；另建一套小窗能力，也会增加系统适配与后续维护的成本。因此，我们保留 Flyme 的交互巧思，同时沿用 ColorOS 的界面与窗口能力，让这份体验自然融入当前系统。

## 致谢

- [Flyme](https://www.flyme.com/)：感谢其小窗细腻的设计与交互巧思。
- [libxposed API](https://github.com/libxposed/api)：现代 Xposed API。
- [Miuix](https://github.com/compose-miuix-ui/miuix)：UI 组件库。

## 许可证

本项目基于 GPL-3.0 开源，详情请参阅 [LICENSE](LICENSE)。
