# 应用分身

Flyme 小窗是面向 ColorOS 的非官方独立实现，与魅族、Flyme、OPPO 或 ColorOS 无关联。

## 目标标识

ColorOS 的应用分身运行在独立的多开用户中，同一包名会同时存在于主用户与分身用户，因此固定项不能只记录组件名。模块把 `ComponentName` 升级为 `AppTarget`（组件 + `userId`），存储键为 `组件#userId`。

旧配置按主用户 `0` 兼容解析：没有 `#用户` 后缀的条目仍然有效，升级后原有固定项不变。

设置页通过 `UserManager.userProfiles` 与 `LauncherApps.profiles` 枚举所有可访问用户，主应用与分身分别作为独立条目出现；分身名称追加“（分身）”，图标右下角带“分”角标。

## 目录与显示

扇形目录运行在 `system_server`。该进程只有系统资源，引用模块自身的 `R.string` 会抛出 `Resources.NotFoundException`，进而在目录枚举的 `catch (RuntimeException)` 中被静默丢弃——这就是分身条目“设置页可见、扇形不可见”的原因。因此分身后缀改用常量 `CLONE_LABEL_SUFFIX`，不再依赖模块资源。

多开用户能否被 `LauncherApps` 枚举因机型而异，所以固定项不再只依赖枚举结果：只有真正产出目录条目的目标才算命中，否则按 `userId` 回退直查（先 `LauncherApps.getActivityList`，再 `PackageManager.getActivityInfoAsUser`），并在同一次刷新内缓存结果，避免重复查询。

刷新过程输出 `CATALOG_PROFILES`、`CATALOG_RADIAL`、`CATALOG_PIN_UNRESOLVED` 等日志，可直接核对每个用户的条目数与最终扇形列表。

## 启动

分身不能靠调用方所在用户启动。提交后先按目标用户创建 Context 校验组件可用，再显式调用 `startActivityAsUser(Intent, Bundle, UserHandle)`；该隐藏方法在当前系统不可用时，退回按用户 Context 自身的 `startActivity`。实际走通的路径写入 `FREEFORM_LAUNCH_STARTED ... route=` 日志，便于区分是显式用户启动还是上下文回退。

## 验证边界

`PinnedComponentCodec` 的 `#userId` 解析有 JVM 单元测试；构建与单元测试不能替代真机验证。已在 Android 16 的 ColorOS 上确认：设置页可分别添加主应用与分身、扇形中分身条目正常显示并可点击、分身以小窗在对应多开用户中打开。工作资料、其他厂商的分身实现以及用户数更多的机型仍待验证。

`MAX_PINNED_APPS` 为 6，扇形槽位为“固定应用 + 更多”共 7 个，与 `RadialIconGeometry` 允许的上限一致；今后若放开固定数量，需要同步调整两处。
