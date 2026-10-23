# 桌面输入目标解析

桌面进程使用 DexKit 2.3.0 解析输入处理方法及指针抢占包装方法，libxposed API 102 负责安装 Hook。侧边栏的独立适配见 [侧边栏适配与能力校验](sidebar-dex-resolution.md)。

## 匹配条件

- 在已核对的 `OplusBaseTouchInteractionService` 内，输入方法必须为实例方法、接收一个 `InputEvent`、返回 `void`，同时包含两条输入处理日志字符串并调用 `MotionEvent.getActionMasked()`。
- 从服务实例字段的类型中寻找仅持有一个 `InputMonitor` 实例字段的包装类；抢占方法必须无参数、返回 `void`，实际读取该字段并调用 `InputMonitor.pilferPointers()`。
- 输入入口与完整的字段／包装方法组合都必须唯一命中。未匹配或歧义时不安装该 Hook，也不退回单参数签名猜测。
- 定位不使用混淆成员名，但仍依赖已验证的行为特征。厂商删除日志、内联方法或改变调用结构时，需重新研究规则，不能宣称自动兼容。

## 生命周期与缓存

包 ClassLoader 就绪后启动一个一次性后台解析任务。APK 读取、SHA-256、缓存、native 库加载和查询均在该任务执行；DexKit 查询使用一个工作线程，bridge 通过 `use` 释放。

缓存放在桌面自己的 `code_cache/flymefreeform/` 中，只保存符号名，绑定 APK 内容摘要及规则版本；不保存宿主对象或用户配置。缓存损坏或反射校验失败后只重新解析一次，写缓存失败不阻断已验证目标。

结果回到主线程安装 Hook，解析期间的输入继续由桌面处理，安装后只从下一次 DOWN 开始接管。十秒后未完成则放弃安装并请求中断；native 查询本身不保证立即响应 Java 中断，迟到结果不会安装 Hook，返回后释放 bridge。没有热路径扫描、常驻线程池或自动重试。

## 验证方式与边界

`LauncherSymbolCacheTest` 验证内容变更、规则变更、损坏缓存和原子替换。可选的 `LauncherDexQueryTest` 使用本地宿主 APK、同版本桌面 native 库和人工核对的符号结果，验证实际查询唯一性；未提供环境变量时跳过该项。

该测试的环境变量为 `FLYMEFREEFORM_TEST_LAUNCHER_APK`、`FLYMEFREEFORM_TEST_DEXKIT_LIBRARY` 与 `FLYMEFREEFORM_TEST_LAUNCHER_SYMBOLS`（输入方法、监视器字段、包装类、抢占方法，逗号分隔）。更换输入文件运行时应强制重新执行测试任务，避免 Gradle 复用旧结果。

APK 静态解析与构建通过不代表 Hook 已在桌面进程生效。安装后的冷启动、缓存命中、首次 DOWN、取消、多指、导航竞争及停用恢复仍需真机验证。
