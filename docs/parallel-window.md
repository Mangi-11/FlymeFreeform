# 微信平行小窗

Flyme 小窗是面向 ColorOS 的非官方独立实现，与魅族、Flyme、OPPO 或 ColorOS 无关联。

## 目标

微信停在朋友圈、视频号、转发等**二级界面**时，从扇形再点一次微信，应该像 ColorOS 原生智能侧边栏那样，
**另开一个小窗显示主界面**，原来的页面留在后面继续用；已经停在主界面（聊天列表或聊天窗口）时不再新开。

## 真机对照结论（Android 16 / ColorOS）

先按"和原生对齐"量了一遍，几条反直觉的事实：

- 微信 `LauncherUI` 是 `singleTop` 单实例，**同一个微信号不可能有两个独立实例**。
- 原生侧边栏在同一场景下**也会把微信拆成两个 task**（一个留朋友圈、一个装主界面），划掉小窗后
  朋友圈仍在但只剩它自己，按返回同样直接回桌面，多任务里同样是两条微信。
- 也就是说"多任务里只有一个微信、返回还能回到主界面"并不是原生行为，属于微信自身 task 结构决定的结果，
  模块能对齐的边界就是：**能开、原页面保留、可以反复开、不越堆越多**。

## 判定

`ParallelWindowPolicy` 是纯逻辑（有 JVM 单测），输入是前台 activity 与该 task 的构成：

- 只对微信生效；主应用与分身同包名，所以**必须按 `userId` 对齐**，读不到用户时一律走普通启动。
- 主界面用**白名单**判定：只有启动组件本身（聊天列表）与聊天窗口 `chatting.ChattingUI` 算主界面，
  其余页面一律算二级界面。早先按 `.plugin.` 黑名单判断，会把转发、选择联系人、公众号文章等
  不挂在插件包下的页面误判成主界面，表现为"只有朋友圈能开平行小窗"。
- 二级界面**仍叠在主界面那个 task 里** → 新开实例（普通启动会被系统"提到前台"，开不出小窗）。
- 二级界面**已经独立成 task** → 复用已有的主界面 task。

`PARALLEL_GATE` 日志会打出前台组件、用户来源、task 内 activity 数与该 task 是否仍含主界面，便于核对判定。

## ColorOS 小窗启动协议

启动参数来自真机抓包（`FlexibleTaskController` 收到的 `ActivityOptions`）。模块原本只传
`android:activity.mZoomLaunchFlags=4` 与 `android.activity.windowingMode=100`，只能完成"新开一个小窗"；
复用已有 task 还需要：

- `androidx.activity.extra` 里的 `ZoomLaunchFlag=31`、`ZoomCallPkg`、`androidx.activity.HasCaption`、
  `MaintainTaskState`、`ChangeToSplit`、`FocusChangeWithNonFlexible`、`LaunchCornerRadius`、
  `androidx.flexible.ResizeMode`、`ResizeForOrientationChange`、`source_flexible_task_id` 等键；
- 复用路径上**不能带 `CATEGORY_LAUNCHER`**（原生入口用的是纯 `ACTION_MAIN`），否则系统会按"启动器启动"
  处理并把小窗作用在**当前聚焦**的那个 task 上——表现出来就是"朋友圈被缩成了小窗"；
- 复用路径用 `ActivityOptions.setLaunchTaskId(taskId)` 把启动**锁进已有的主界面 task**，
  系统才不会又新建一个实例（否则每点一次多一个 task，多任务里越堆越多）。

这些键必须**最后合并**进 options：平台侧 `toBundle()` 里也带同名 key，先放会被 `putAll` 覆盖掉，
症状就是"第一次能开、第二次参数丢失"。

主界面 task 是把 `mRootWindowContainer` 当树走一遍找到的，按 `mTaskId` / `mUserId` 判定，
**主应用与分身各找各的**（分身仍走 `startActivityAsUser`，日志 `PARALLEL_REUSE_TASK user=999 taskId=…`）。

## 验证边界

判定逻辑有 JVM 单测；真机确认了主应用与分身两条链路：二级界面首次能开、划掉后能再次打开、
小窗里是对应用户的主界面、多任务不累积；主界面/聊天界面在前台时不新开。

未验证或与机型相关的部分：`ZoomCallPkg` 等个别键是否必需（目前照抄原生入口的取值）、
其他微信版本或 ROM 的二级界面命名、以及除微信以外的应用（本模块不打算对所有应用放开多实例）。
