
## 到点拉回：后台 startActivity 被静默丢弃（KD-65X9500H，Android 10，2026-11-07）

屏幕时间到点后 yst 继续播了 5.5 分钟才停。现场取证（`adb shell am broadcast … stime_dump` + `dumpsys usagestats`）：

- 判定全对：`frontPkg=yst`、`status=GroupLimit blocks=true`、快轮询每 ~1s 在跑（`frontElapsedMs≈840ms`），blocked 事件 4 秒内到后台。
- 拉回全灭：`HomeGuardService.goHome` 的 `startActivity` 从 Aerial 自己进程（含守护，同进程）发起，被 Android 10 的后台活动启动限制**全量静默丢弃**——`runCatching` 不抛异常，每秒重发也全丢。
- 对照实验：`adb am start` 发同一 intent，3 秒回前台；shell 注入 Home 键，2 秒回 Aerial。说明 intent/任务链没问题，只有发起方受限。

这台能用的两条通道：系统 Home 动作（无障碍 `performGlobalAction(GLOBAL_ACTION_HOME)`，Aerial 是默认 HOME，主页键 2 秒回桌面一直有效）；以及「刚有用户交互」后的 BAL 宽限期——之前真机验证「到点拉回」成功过，都是恰好落在宽限窗里，掩盖了问题。

1.86.3（121）起，到点拉回走 `HomeGuardService.pressHomeForLimit()`（系统 Home 动作）；Home 带不了 extras，拦截卡暂存在 `ScreenTime.pendingBlock`（15 秒新鲜度），Aerial 回前台在 `onNewIntent` 的 `onHomePressed` 之后取走弹卡（放前面会被 Home 复位的 `overlay = None` 当帧抹掉）。真机验证：撤销加时后重起被限 App，1 秒内回桌面 + 弹卡。
