# Aerial × tv-manager 屏幕时间集成调研（只读）

- 调研时间：2026-10-29；仓库 HEAD：1.67（versionCode 81）
- 方式：只读，未改任何代码、未跑 gradle
- 结论行号以调研时文件内容为准
- 参照：`docs/HANDOFF.md`、`docs/ARCHITECTURE.md`、`design/screen-time/DESIGN.md` §5

## 1. 构建与产物

### 1.1 standard / guard 两个 flavor 的区别

两者代码完全相同，只差 manifest 里有没有 HOME intent-filter。

- `app/build.gradle.kts:28-38`：
  ```kotlin
  flavorDimensions += "home"
  productFlavors {
      create("standard") { dimension = "home" }
      create("guard") {
          dimension = "home"
          versionNameSuffix = "-guard"
      }
  }
  ```
- `app/src/standard/AndroidManifest.xml:4-17`：standard 额外给 `MainActivity` 合并进 `CATEGORY_HOME` + `DEFAULT`（以及酷开私有类别 `HOME.CC`）。base manifest（`app/src/main/AndroidManifest.xml:83` 注释）明确「The HOME intent filter is added by the "standard" flavor」。
- `docs/ARCHITECTURE.md:68-75`：guard 用于「拒绝安装第三方桌面的固件（TCL 的 LauncherStrategy），只靠桌面守护接管」。
- 产物名：`app/build/outputs/apk/{standard|guard}/release/app-{standard|guard}-release.apk`（`docs/HANDOFF.md:183,195-196`），发布时改名 `aerial-standard.apk` / `aerial-guard.apk`。

**对屏幕时间功能的意义**：采集/执行逻辑放在 `app/src/main` 即两个 flavor 共享；guard 版没有 HOME 身份，「拉回桌面」依赖守护服务，行为与 standard 一致（守护本来就是两条腿共用）。

### 1.2 SDK 版本

`app/build.gradle.kts:12,17-18`：

```kotlin
compileSdk = 36
minSdk = 21        // Android 5.0；Compose 1.10+ 需要 6.0，BOM 固定在 2025.11.01
targetSdk = 34
```

`docs/ARCHITECTURE.md:3` 同口径，并强调「比 Android 5.0 新的接口都要按 `Build.VERSION.SDK_INT` 判断，`./gradlew lintStandardRelease` 的 NewApi 检查能找出漏掉的地方」。

### 1.3 signingConfigs 读不到 local/signing.properties 时退化成什么

`app/build.gradle.kts:43-54`：只有 `local/signing.properties` 存在时才 `create("aerial")`；`:56-62` release 构建：

```kotlin
signingConfig = signingConfigs.findByName("aerial") ?: signingConfigs.getByName("debug")
```

即**退化为 AGP 默认的本机 debug key**。`docs/HANDOFF.md:165`：「没有它也能编译，但会回落到本机的 debug key，装不上电视（签名不一致）」——电视上现有版本用 `e08962dc…` 那把钥匙签的，debug 包只能全新安装、不能覆盖升级。`lite/build.gradle.kts:27-45` 是同一套写法（同一把钥匙）。

### 1.4 versionCode / versionName 在哪一行

- `app/build.gradle.kts:19-20`：`versionCode = 81`、`versionName = "1.67"`
- `lite/build.gradle.kts:22-23`：`versionCode = 7`、`versionName = "1.6"`（独立包 `app.orchard.tv.lite`，独立版本号）

### 1.5 「发 TCL 前 versionCode 要 +1」落在何处

三处口径一致：

1. `AGENTS.md`「命令」节末行：「发 TCL 版本前先给 `versionCode` 加 1。」
2. `docs/HANDOFF.md:178`：「多屏互动推送不要求 versionCode 变大，但回退到当贝时，当贝会隐藏不比已装版本新的 APK。所以发版时照样给 versionCode 加 1，两台电视的版本号保持一致。」
3. `docs/HANDOFF.md:191`（发版第 1 步）：「`app/build.gradle.kts` 里 `versionCode` 加 1，并更新 `versionName`。」

改动落点就是 `app/build.gradle.kts:19` 这一行。注意 TCL 侧还有一句：`docs/HANDOFF.md:244`「TCL 多屏互动只接受版本号更高的包，同一个 versionCode 推过去会超时」——本地反复推测试包时也要每次 +1（1.66 前后的记录多次出现「推了 versionCode N 的测试包，正式版用 N+1」）。

### 1.6 lite 模块是否需要同步改

- Lite 是独立应用（Android 4.0–4.4，原生 View，无 AndroidX/Compose/协程，`docs/HANDOFF.md:229` 必须遵守 #10），与 app 只共享少数文件：`lite/build.gradle.kts:72-104` 在构建时从 `app/src/main` 复制 `aerials.json`、字体、`devices.json`、**整个 `assets/web/`（手机页）**以及 GlyphPaths/TileRecipes/LayoutStore/AppNames 等源码。
- 也就是说：**改 `app/src/main/assets/web/index.html`（加 stime tab）会被 Lite 原样复制过去**（`lite/build.gradle.kts:78-79`：`from(rootProject.file("app/src/main/assets/web")) { into("web") }`，注释「The phone page, the full app's own.」），而 Lite **确实跑手机页**（`:120-123` 依赖 nanohttpd + zxing，注释「The phone page's server and its QR code」）。于是 Lite 手机上会看到一个依赖 `/api/stime/*` 的 tab 而 Lite 侧没有对应实现；Lite 也没有无障碍守护和 UsageStats（4.x 无此 API）。versionCode 规则：`docs/HANDOFF.md:199`「Lite 的 versionCode 独立，改了 Lite 才加 1」。
- 建议（实施层面，不属于本调研结论）：手机页对 Lite 隐藏 stime tab（按 `api/state` 增一个能力位，或 Lite 构建时过滤），或者第一期根本不在 index.html 挂载、独立成 `screen-time.html`（与记忆中的约束「新增独立模块/文件，避开上游热区」一致）。

## 2. 手机页挂载点（PhoneServer.kt / assets/web/index.html）

文件：`app/src/main/java/app/orchard/tv/wallpaper/PhoneServer.kt`（497 行）

### 2.1 serve() 路由分派结构

`PhoneServer.kt:50` 类声明 `class PhoneServer private constructor(context, token) : NanoHTTPD(PORT)`；`PORT = 8790`（`:409`）。

`:52-58` 是先剥 token 前缀、再进一个巨大的 `when`：

```kotlin
override fun serve(session: IHTTPSession): Response {
    val prefix = "/$token"
    val uri = session.uri
    if (!uri.startsWith(prefix)) return text(Response.Status.NOT_FOUND, "Not found")
    val path = uri.removePrefix(prefix).ifEmpty { "/" }
    return runCatching {
        when {
            path == "/" -> ... page()
            path == "/api/state" -> json(state())
            ...
            else -> text(Response.Status.NOT_FOUND, "Not found")   // :183
        }
    }.getOrElse { Log.w(TAG, "request failed", it); text(INTERNAL_ERROR, "error") }  // :185-188
}
```

要点：GET 路由用 `path ==`/`startsWith`，POST 路由一律先判 `session.method == Method.POST && path == "..."`；整链包在 `runCatching` 里，任何异常 → 503 文本 "error" 并记 `AerialPhone` 日志。响应工厂：`json()`（`:404`）、`text()`（`:406`）。

### 2.2 state() 形状

`:194-262`，单个大 `JSONObject`，扁平键：`selected / latestDaily / rotatePhotos / screensaver / screensaverFollows / videos[] / focusStyle / wallpaperAlwaysMoves / showWeather / screensaverClock / wallpapers[] / apps[] / layout{} / devices[] / inputs[] / folders[] / weather{}`。

`apps[]` 每项 `{key,label,default,name,custom,library,icon,stamp}`（`:227-238`）——屏幕时间的 App 清单可直接复用这一份，或按 DESIGN.md §5 单独出 `/api/stime`。写路由的响应惯例 = 「改完后重新 `json(state())`」再 `.put("saved", ...)` / `.put("added", ...)` / `.put("removed", ...)`（如 `:83,100,122,308,375`），与 DESIGN.md §5「响应 = 完整状态 JSON + 结果字段」一致。

`layoutState()`（`:265-284`）里有家长密码门控先例：`val locked = AerialSettings.locked`，锁着时 `hidden` 数组置空并只给 `hiddenCount`（`:274-280`）——stime 的「未解锁不给写」可照此办理。

### 2.3 token 鉴权

`:53-55` 唯一的鉴权：URI 必须以 `/<token>` 开头，否则 404。token 是 8 位、字母表去掉易混字符（`:452-456`），存 `phone_page` prefs 的 `token` 键、跨重启保持不变（`:442-450`）。无会话、无 cookie、无 grant——DESIGN.md §5 要求的 `grant` 会话在这里是**空白**，需新增。

### 2.4 写路由如何切主线程

统一用 `runBlocking(Dispatchers.Main)`：辅助函数 `onMain`（`:402`）`private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }`；需要回值或内联处直接写 `runBlocking(Dispatchers.Main) { ... }`（`:109,118,119,129,137,147,165,176,307,371`）。注释在 `:108`：「On the main thread before answering, so the state sent back has the new name.」——即**先切主线程执行、再在同一请求里返回新 state**。NanoHTTPD 每请求一线程，block 安全。

### 2.5 新增 `/api/stime/*` 的最小侵入接法（不写代码）

- 边界 1：新文件（如 `app/src/main/java/app/orchard/tv/screentime/ScreenTimeHandler.kt`，DESIGN.md §6 #8 就叫这名），暴露一个入口：`(method, path 剩余段, form/session) -> Response 或 null`。
- 边界 2：`PhoneServer.serve()` 的 `when` 链内、`:183` 的 `else ->` 之前加**一条分支**：`path.startsWith("/api/stime") || path == "/api/usage" -> handler(...)`；不匹配时落回 `else` 的 404。除此之外不动 PhoneServer 任何现有行。
- handler 内部自己组织子路由（group/add、daily、unlock…），沿用同款约定：表单走 `form(session)`（`:397-400` 是 private，或在新文件里复制其 3 行实现）、写操作 `runBlocking(Dispatchers.Main)`、响应 `json(...)`。
- 生命周期：`PhoneServer.listen()`（`:424-433`）由 `MainActivity.startWork` 调用、Aerial 运行期间常驻（`docs/HANDOFF.md:46`，1.55 起），stime 不需要额外的服务启动点。
- 注意 `state()` 里加字段（如 `stime: {...}`）也是改上游热区，能不塞就不塞（DESIGN.md §2 已定「用量矩阵不塞进 state」）。

### 2.6 assets/web/index.html 加第四个 tab 的最小挂载点（行号）

文件 1933 行。要动的位置一共 6 处：

| # | 行号 | 内容 |
|---|---|---|
| 1 | `:284-292` 旁 | 新增 `<section data-tab="stime" hidden>…</section>`（仿 `:284` home / `:295` looks / `:324` more） |
| 2 | `:360-362` 旁 | `<nav>` 里加一个 `<button role="tab" data-go="stime">` + 图标 + `<span data-i18n="tab_stime">` |
| 3 | `:1162` | `const TABS = ['home', 'looks', 'more']` → 追加 `'stime'` |
| 4 | `:1163` | `const TITLES = {...}` 加 `stime: 'tab_stime'`（或专属标题键） |
| 5 | `:383` + `:384` 起的 `TEXTS` | `KEYS` 数组追加新文案键，`TEXTS` 的 zh/en/ru/uk/kk 五个数组各补同长度条目（索引对齐，占位符一致——AGENTS.md 硬性要求；原型 DESIGN.md 已有 162 键的中英表可移植） |
| 6 | `:1912-1921` | `render()` 里挂 `renderStime()`；若要 30s 独立刷用量（DESIGN.md §5 轮询约定），在 `:1929-1930` 的 `refresh()`/`setInterval` 旁加独立 fetch，不复用 6 秒全量轮询 |

`show()`（`:1174-1191`）按 `section[data-tab]` 与 `nav button` 泛化选择，新 tab 自动获得滚动记忆、hash（`#stime`）、localStorage 记住上次页——**这两处不用改**。风险：该文件是上游活跃区（1.67 刚合入 PR #807 改过拖拽，`docs/HANDOFF.md:8`），上述 6 处每一处都是未来 rebase 上游的冲突点；独立 `screen-time.html`（新文件 + `page()` 式的一个新 GET 路由）能把冲突面压到 serve() 一行。

## 3. 前台探测与守护（HomeGuardService）

文件：`app/src/main/java/app/orchard/tv/guard/HomeGuardService.kt`（1329 行），无障碍服务（`app/src/main/AndroidManifest.xml:188-200`，`BIND_ACCESSIBILITY_SERVICE`，`directBootAware="true"`）。

### 3.1 foreground()：用 queryEvents 取当前前台包名

`:147-160`：

```kotlin
private fun foreground(lookbackMs: Long = FOREGROUND_LOOKBACK_MS): Pair<Pair<String, String>, Long>? = runCatching {
    val now = System.currentTimeMillis()
    val events = getSystemService<UsageStatsManager>()?.queryEvents(now - lookbackMs, now) ?: return null
    ...
    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.className != null) {
        last = (event.packageName to event.className) to event.timeStamp
    }
    last
}.getOrNull()
```

回看窗 `FOREGROUND_LOOKBACK_MS = 10_000L`（`:1132`）；`frontByUsage()`（`:165-168`）用 `UNKNOWN_FRONT_LOOKBACK_MS = 6 小时`（`:1134`）兜底。返回 `(pkg, cls) to timeStamp`，只认 `MOVE_TO_FOREGROUND`。

### 3.2 轮询周期与启动条件（重要）

`foregroundCheck`（`:120-141`）自递归 `handler.postDelayed(this, FOREGROUND_POLL_MS)`，`FOREGROUND_POLL_MS = 1_000L`（`:1131`），**1 秒一轮**。但它**不是无条件跑**：

- `:455-459`：只有 `Build.VERSION.SDK_INT <= M`（Android 6 及以下）**或** `VIDAA_LAUNCHER ("com.jamdeo.tv.vod") in stockHomes` 时才 post；且必须 `usageStatsAllowed(this)`。
- 注释 `:110-118`：这条 1 秒轮询是为「小米 PatchWall 在 Android 6 对无障碍隐身（无窗口事件）」加的（issue #81）。
- 每轮里再跳过：`screenOff`、用户未解锁、`allowStockUntil` 宽限期（`:126-127`）；只对「原桌面的 home Activity 到前台」反应（`:135`），并有 `broughtBackAt` 1 秒去抖（`:137`）。

**结论**：在 MFTR0（Android 14）这类新机上，守护平时**不**跑 1 秒 queryEvents；前台主要靠无障碍 `TYPE_WINDOW_STATE_CHANGED`（`:886-899` 维护 `lastWindow`）。屏幕时间若要持续、按会话记用量，不能默认这条轮询已经存在——要么自己的采集循环独立跑，要么把 foregroundCheck 的条件扩展（改上游热区）。

### 3.3 goHome()：拉回桌面的具体实现

`:1098-1117`：

```kotlin
private fun goHome(woke: Boolean = false) {
    if (!GuardSetup.isUserUnlocked(this)) return
    if (getSystemService<PowerManager>()?.isInteractive == false) { ...; return }   // 熄屏不拉，交给亮屏检查
    if (Settings.Global.getString(contentResolver, "tv_power_mode") == "QUICK") { ...; return }  // OPPO 快速待机
    broughtBackAt = SystemClock.uptimeMillis()
    runCatching {
        startActivity(MainActivity.bringBackIntent(this).putExtra(MainActivity.EXTRA_WOKE, woke))
    }.onFailure { Log.w(TAG, "could not bring Aerial Home to the front", it) }
}
```

`MainActivity.bringBackIntent`（`MainActivity.kt:859-862`）：`Intent(context, MainActivity::class.java)` + `ACTION_MAIN` + `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`。没有特殊权限要求——**无障碍服务进程内直接 startActivity 拉起自家 Activity**；manifest 里的 `SYSTEM_ALERT_WINDOW`（`AndroidManifest.xml:31`）只是「开机后立刻打开 Aerial」的豁免。`EXTRA_WOKE`（`MainActivity.kt:876`）供开机信号源逻辑（`MainActivity.kt:295,688`）。

调用方一共 ~10 处：HOME 键过滤（`:708,723`）、窗口事件看到原桌面（`:938`）、homekey 广播（`:301`）、极米选择框（`:326`）、usage 轮询（`:139`）、开机复查/接管（`:555,561`）、补拉（`:1044`）、Aerial 为 Home 让开时（`:1325`）。

### 3.4 这套守护在什么条件下才运行

- 前提：**无障碍服务被系统绑定**（`instance != null`，`isRunning`，`:1234-1237`）。写入 `enabled_accessibility_services` 靠 `GuardSetup.ensureEnabled()`（用 `WRITE_SECURE_SETTINGS`，adb 由 `tools/install.sh` 授予；见 `docs/ARCHITECTURE.md:240-244`）。
- 被强杀后复活：`RevivalListener`（通知监听）/ `RevivalSync`（同步适配器）拉起进程 → 重新 ensureEnabled（`docs/ARCHITECTURE.md:255-257`）。
- `guard` flavor 没有 HOME 身份也照样跑（它就是为此存在的）；`standard` 版在 Aerial 是默认桌面时按键过滤关掉，但窗口事件与拉回逻辑照常（`:248` 与 ARCHITECTURE:248）。
- 熄屏（`screenOff`，`:247-261`）、锁定用户（Direct Boot 期）、`allowStockLauncher` 1 小时宽限（`:1288-1291`）时不拉回。

### 3.5 「到点把 App 拉回桌面」能否直接复用

**能，且是 DESIGN.md §3.3/§6#3 钦定的路径**（「Aerial 已有守护服务在拉回桌面这条路上验证过，风险最低」）。复用方式：执行器不自己写 Activity 启动，而是触发守护的 `goHome()` 等价物（新增一个 `companion` 静态入口，如 `fun bringHomeNow(): Boolean = instance?.run { handler.post { goHome() }; true } ?: false`——`refreshCecHome()`（`:1247`）与 `onBootCompleted()`（`:1250`）就是同款先例），或退化到直接 `startActivity(MainActivity.bringBackIntent(ctx))`（Aerial 常驻进程自己也能调）。

已知坑（全部有代码/文档证据）：

1. **守护可能没在跑**：无障碍被固件关掉（小米待机 killAllThirdApp，`docs/ARCHITECTURE.md:255`）、用户手动关、TCL 部分机型的 `guard_off_in_standby`（`docs/HANDOFF.md:75`，`ConfigReceiver --ez guard_off_in_standby true` 时息屏期守护退出）。执行器要有「instance == null 时自行 startActivity」的退路，并注意那时 HOME 键仍归原桌面。
2. **厂商 ROM 差异**：PatchWall（Android 6）不发窗口事件、极米会弹自己的 Home 选择框（`:321-328`）、ColorOS TV `tv_power_mode=QUICK` 拉回会误亮屏（`:1106-1112`）、OPPO K9 开机交接（HANDOFF 多条）。屏幕时间触发拉回的时刻比 HOME 键更「无预警」，这些路径都要真机回归。
3. **反复重启抖动**：现有代码已内置两层去抖——`broughtBackAt` 1 秒窗（`:137,298,932-941`）与 `stockHomeRecheck`（`:1028-1045`）；Android 11 及以下 Home 后 5.5 秒内后台启动被冻结（`APP_SWITCH_HOLD_MS`，`:1039,1137`）。若「超时拦 App → goHome」后孩子端 App 有自启逻辑再拉起，形成拉锯：DESIGN.md §6#3 已要求「被拦 App 若 3 秒内被再次拉起则退避」——现仓库**没有**这种退避的现成实现（`allowStockUntil` 是反向宽限），需要新写：记录 (pkg → 最近拦截时间)，同 pkg 短时间重复到前台时只提示不再硬拉。
4. **不要把「拦 App」做成停用/卸载**：AGENTS.md「不要做」+ `docs/HANDOFF.md:220` 不得停用系统应用；执行动作限定 `goHome`/屏保/弹窗。
5. **拉回后 Aerial 自己打开的 App 不算违规**：判定要基于「前台包名 ≠ Aerial 且属于超限组」，注意 `isApp()`（`:952-954`）、信号源播放器 `INPUT_PLAYERS`（`:1171`）、Aerial 自己的设置/安装器等窗口（`:893-895` 只认 Activity）。HDMI/投屏在守护视角是播放器 App 到前台，可映射成虚拟包名管控（DESIGN.md §7 的 `hdmi_*`/`cast`）。

## 4. 用量采集可行性

### 4.1 PACKAGE_USAGE_STATS 声明与实际授权路径

- 声明：`app/src/main/AndroidManifest.xml:36-39`
  ```xml
  <!-- Which app is in front, where a launcher hides from accessibility (Xiaomi PatchWall on
       Android 6, issue #81); granted over ADB. -->
  <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />
  ```
- 判断：`HomeGuardService:1240-1245`
  ```kotlin
  fun usageStatsAllowed(context: Context): Boolean = runCatching {
      context.getSystemService<AppOpsManager>()?.checkOpNoThrow(
          AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
      ) == AppOpsManager.MODE_ALLOWED
  }.getOrDefault(false)
  ```
  即**只查 appops**（`checkOpNoThrow`，Android 5.0+ 可用；lint 兼容 minSdk 21）。
- 授权路径**只有 adb appops**，两处先例：`tools/install.sh:288-289`
  ```bash
  adb shell appops set "$PKG" GET_USAGE_STATS allow 2>/dev/null || true
  adb shell appops set "$PKG" android:get_usage_stats allow 2>/dev/null || true
  ```
  （第二行是 Android 5 的旧 op 名，`docs/HANDOFF.md:76` 有说明）；`site/agent.md:116-117` 给 Agent 的同款两条。
- **没有引导用户开权限的现成代码**：全仓库（app/、lite/、tools/、site/）搜 `USAGE_ACCESS` / `ACTION_USAGE_ACCESS_SETTINGS` 均无结果——电视上从未做过「去设置里允许查看使用记录」的入口或深链。DESIGN.md §6 #7 的「未授权时手机页横幅 + 深链」是**净新增**。

### 4.2 queryEvents 与 queryUsageStats 的精度差别

- `queryUsageStats` 全仓库**零调用**（grep 证实，`UsageStatsManager` 只出现在 `HomeGuardService.kt:7,149` 一处 import 与一次 `queryEvents`）。DESIGN.md §6 #1 的说法一致。
- 现有 `queryEvents(now-10s, now)` 只取「最后一条 `MOVE_TO_FOREGROUND`」（`:150-159`），用完即弃，不做会话配对，也无历史落盘。
- 仓库内没有两者精度的对比记录（**未找到**）。从代码可见的事实差别：`queryEvents` 给出逐事件（前/后台切换）+ 毫秒时间戳 + className，能自定义会话边界；`queryUsageStats` 只能拿系统聚合的 `totalTimeInForeground`（按桶），改不了口径，也拿不到「今天 19:02–19:34」这类明细——DESIGN.md §3.2 要 `sessions[]`，方向上只有 events 可行。
- 现有代码对 events 的保留窗没有任何处理逻辑：回看窗最短 10 秒、最长 6 小时（`UNKNOWN_FRONT_LOOKBACK_MS = 6*60*60*1000`，`:1134`）——**若要「重装后回补历史」，events 的保留时长与厂商实现差异是未验证项**（见 §8 最大不确定项）。

### 4.3 「自己观察前台切换」替代 UsageStatsManager：更可行，理由与证据

Aerial 有两个天然、免额外授权的观察面：

1. **无障碍窗口事件**（守护进程内，`HomeGuardService.onAccessibilityEvent:886-899`）：`TYPE_WINDOW_STATE_CHANGED` 带 `packageName` + `className`，用 `isActivity(pkg, cls)`（`:950-953`，带缓存）过滤掉浮层/对话框后写进 `lastWindow`。这是**每次前台切换都来一次**的事件流，比 1 秒轮询更准（无 1s 量化误差、无 10s 回看窗假设），且不需要 GET_USAGE_STATS。
2. **Aerial 自己的生命周期**：`onResume`→`HomeGuardService.onAerialResumed()` / `aerialResumed = true`（`:1293-1314`），`onPause`→`onAerialPaused()`（`:1317-1327`）。桌面视角「我离开的时刻 = 某个 App 进来的时刻」。

因此**推荐主采集源 = 无障碍窗口事件序列做会话配对，UsageStatsManager 只作降级/校准**。可行性支撑：MainActivity 与 HomeGuardService **同进程**（服务声明在 Aerial 的 manifest 内），Aerial 常驻（被杀也有 RevivalListener/RevivalSync 复活，`docs/ARCHITECTURE.md:255-257`），故事件采集在桌面不可见时仍在跑；PhoneServer 也常驻（`MainActivity.startWork` 起，`docs/HANDOFF.md:46`，1.55 起一直监听），采集器可直接被 `/api/usage` 读。

已知失效面（必须保留 queryEvents 兜底的场景，都有代码注释证据）：

- PatchWall（Android 6 及以下）**不发窗口事件**（`:110-118`），海信聚好看 `com.jamdeo.tv.vod` 任何版本都不发（`:450-454`）——这类机器上只能靠 usage stats 轮询；这两条也说明「无障碍一定收得到」不成立。
- 无障碍被关（固件杀、用户关）→ 事件流断，只能靠 `onPause/onResume` + `queryEvents`。
- 反方向：窗口事件里的 className 是任意窗口类，需 `isActivity` 过滤，且部分 App 的多进程/插件页会给出奇怪类名；用「包名 + 是否 Activity」聚合即可，会话以包名为单位。

### 4.4 跨天、待机、屏保、HDMI/投屏：现有代码怎么识别

| 情形 | 现有识别 | 对采集的含义 |
|---|---|---|
| 跨天 | `dayReceiver`（`MainActivity.kt:106-111`）收 `ACTION_DATE_CHANGED/TIME_CHANGED/TIMEZONE_CHANGED`（注册 `:733-740`），只用来刷新每日壁纸/轮播 | 这是**系统改日期/时区**的广播，**不是每天 0 点的日切**；每日日切需自己按本地日期比较（`Wallpapers` 的 `daily:<date>` 走的是另一条：`docs/HANDOFF.md:106`「过了 0 点就查新图」）。另外 `startWork()` 里注册的接收器在 `onStop` 全部注销（`:797-799`）——**Aerial 不在前台时收不到这些广播**，采集器不能依赖它 |
| 待机（熄屏） | 守护 `screenOn` 接收器 `:247-261`（`ACTION_SCREEN_ON/OFF` → `screenOff` 标志 + `BootOpen.noteScreenOff`）；MainActivity 也注册同款（`:279`）；`goHome` 用 `PowerManager.isInteractive` 判断（`:1102`） | 采集应复用 `screenOff` 语义：息屏那一刻结束当前会话并停止计时（否则一晚上算成前台时长）。守护进程收到 SCREEN_OFF 与 Activity 收到同样广播，常驻侧可覆盖 |
| 屏保 | 桌面内屏保 = `vm.screensaverVisible`（`MainActivity:530,631`；`onPause`/进屏保时 `hideScreensaver`）；系统级 = `dream/AerialDream.kt`；`docs/ARCHITECTURE.md:154-156` | 屏保是 Aerial 自己的窗口/服务，前台包名仍是 `app.orchard.tv`；按「非 Aerial 包才计时长」自然排除，但要确认屏保期间不被算成「桌面空闲」以外的时长 |
| HDMI / 投屏 | 信号源走 `INPUT_PLAYERS = com.tcl.tv / com.xiaomi.mitv.tvplayer / com.skyworth.tv`（`:1171`），守护不当它是原桌面；Aerial 内有 `source:<id>` 壁纸与 `SystemProbe` | HDMI 是「播放器 App 在前台」→ 有包名可统计；要按 DESIGN.md §7 把它们映射成 `hdmi_*`/`cast` 虚拟成员，需要在 `arrange`/`SourceLooks` 那侧对齐键；多屏互动/投屏的接收进程各厂商不同（**未找到**统一识别代码） |

## 5. 应用清单与包监听

### 5.1 AppCatalog 如何枚举可启动应用

`app/src/main/java/app/orchard/tv/apps/Apps.kt`（449 行），`object AppCatalog`（`:106`）。`suspend fun load(context, first)`（`:201`）跑在 `Dispatchers.Default`，三路查询 + 兜底，**按包名去重**（`LinkedHashMap.putIfAbsent`，先到先得即为主入口）：

```kotlin
pm.queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LEANBACK_LAUNCHER), 0) ... to true   // :208-209
pm.queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER), 0) ... to false           // :210-211
pm.queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_APPSTORE_LAUNCHER), 0)               // :212-213
for (component in UNLISTED_ENTRIES) { ... }                                                            // :214-217
```

`CATEGORY_APPSTORE_LAUNCHER = "android.intent.category.APPSTORE_LAUNCHER"`（`:108-109`，小米应用商店只声明它）。之后 `stockLaunchers(pm)`（`:218-236`，`:313`）把原桌面补成「原桌面」磁贴，并 `found.remove(context.packageName)`（`:236`）去掉 Aerial 自己。类别查询还用于 `byCategory`（`:238-244`，`CATEGORY_APP_GALLERY/CALENDAR/BROWSER/MARKET` → 自绘系统磁贴）。

### 5.2 返回结构与可直接复用的全局视图

`data class AppEntry`（`:35-51`）：

```kotlin
val packageName: String; val label: String; val component: ComponentName
val leanback: Boolean; val removable: Boolean; val art: TileArt
val source: TvSource? = null; val folder: Folder? = null; val shortcut: Intent? = null
val key: String get() = packageName
fun launchIntent(): Intent = ...   // :53-56，NEW_TASK | RESET_TASK_IF_NEEDED
```

`AppCatalog` 暴露的 `@Volatile` 快照（`:161-184`）：`loaded`（全部）、`home`（第一排顺序）、`folderApps`、`hiddenApps`、`folders`、`pinnedSources`、`sources`、`layout: LayoutOps?`、`renameFolder`、`pinSource`、`lastTiming`。**PhoneServer.state() 就是在 HTTP 线程直接读这些字段**（`PhoneServer.kt:214-256`），stime 的 App 清单可同样直接读，无需主线程。

- 图标：没有「取标准 icon」的 API，只有 `art: TileArt`（16:9 磁贴图，缓存 `cache/tiles/<pkg>.tile`）与 `AppCatalog.ownArt(context, entry)`（`:383`，App 自带横幅/图标）。手机页现成端点：`GET tile/<key>`（`PhoneServer.kt:102,317-328`）、`GET library/<pkg>`（`:103,331-337`）。屏幕时间列表用 `tile/<key>` 即可（含文件夹/信号源）。
- 分类：**未找到**通用 App 分类（无 `CATEGORY_APP_*` 回传、无 `getRecommendedOrigins`/play-category 调用）；`byCategory` 只在 `entry()` 内决定画哪种自绘磁贴（`:420`）。DESIGN.md §3.1 的 `categoryId` 若要用，只能自建包名→分类表，或退化为「只按分组管」。
- 改名：`apps/AppNames.kt`（`settings` 的 `app_names`），`PhoneServer.kt:217` 的取法 `AppNames.of(key) ?: label` 就是「电视上显示名」的唯一口径，stime 上报/展示应沿用。

### 5.3 PACKAGE_ADDED/REMOVED 广播现状

`MainActivity.kt:97-98`：

```kotlin
private val packageReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = vm.reload()
}
```

注册在 `startWork()`（`:717-725`）：`ACTION_PACKAGE_ADDED / REMOVED / CHANGED / REPLACED` + `addDataScheme("package")`，`RECEIVER_EXPORTED`；`onStop` 里注销（`:797`）。**监听只在 Aerial 处于前台期间生效**，因此有两条补偿：

- 回前台补漏：`vm.reloadIfPackagesChanged()`（`:715`；`HomeViewModel.kt:204-209`，Android 8+ 用 `PackageManager.getChangedPackages` 序号比对，注释指出「桌面不在前面时装/卸的 App 收不到广播，issue #55」）。
- 开机补扫：`BOOT_CATCH_UP_MS = listOf(60_000L, 180_000L)`（`HomeViewModel.kt:74`）→ `catchUpAfterBoot()`（`:296-309`，#394 #170）。

**给屏幕时间的启示**：新装 App 的「未分组提醒」（DESIGN.md §6 #4）不能只挂这个 Activity 接收器（Aerial 后台时收不到）；应复用 `getChangedPackages` 轮询或在常驻侧（守护/采集器）另注册一个接收器——注意注册接收器要避开 `onStop` 注销的生命周期绑定。

## 6. 出网能力

### 6.1 可复用的 HTTP client：只有 HttpURLConnection，零新依赖

全仓库无 OkHttp / Retrofit / Ktor（`grep okhttp|retrofit|ktor` 零结果）。依赖只有 NanoHTTPD 2.3.1 + ZXing（`app/build.gradle.kts:106-108`、`docs/ARCHITECTURE.md:277`）。现成写法五处，可照抄：

| 位置 | 用途 | 超时 | 错误处理 |
|---|---|---|---|
| `weather/Weather.kt:221-235` | Open-Meteo / ipinfo.io | connect 8s / read 10s | `get()` 先 https，捕获 `SSLException` 后 `url.replaceFirst("https://","http://")` 再试（注释指明老系统不信任 LE 根证书） |
| `apps/IconLibrary.kt:160-176` | 官网 `icons/index.json` + 图片 | connect 8s / read 15s | `runCatching{...}.onFailure{Log.w(TAG,...)}`，10 分钟内不重试（`RETRY_MS`），最多 12 小时查一次（`CHECK_EVERY_MS`） |
| `apps/IconLibrary.kt:179-193` | 下载图片 | 同上 | 写 `.part` → `inJustDecodeBounds` 能解才 `renameTo`，否则删（防坏文件占位） |
| `update/Updates.kt:117-126` | 读 `download/version.txt` | 8s / 10s | `runCatching` + 正则校验返回体（`\\d+(\\.\\d+)*`），失败当作「无新版」 |
| `update/Updates.kt:326-360` | 下载 APK | 10s / 20s | `.part` 临时文件 + **按窗口计数判停滞**（`STALL_MS`/`STALL_BYTES`，#390：慢链路 read() 不超时但进度卡 0%） |
| `wallpaper/Wallpapers.kt:710-715` | Bing 每日图 | 10s / 20s | `runCatching` + `dailyFailed` 标志，失败后 `retryDailyLater()` |
| `ui/home/LiveWallpaper.kt:697` | `RangedHttpSource`（#567，Android 9 x32a0 打不开 https 时分块下载） | — | Range 请求，512KB/块 |

### 6.2 线程模型与调度惯例（照抄这个）

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)     // Weather.kt:59 / Updates.kt:70
...
scope.launch {
    val latest = withContext(Dispatchers.IO) { runCatching { read(URL).trim() }.onFailure { Log.w(TAG, "...", it) }.getOrNull() } ?: return@launch
    prefs.edit { putString("latest", latest); putLong("checked_at", System.currentTimeMillis()) }   // 回 Main 写 prefs
}
```

要点：单例 `object` + `Dispatchers.Main` 的 `SupervisorJob` 作用域；网络体一律 `withContext(Dispatchers.IO)`；每个 `runCatching` 打 `Log.w`（tag 如 `AerialWeather`/`AerialUpdate`）；`lastAttempt` + prefs `checked_at` 双节流；失败不抛出、只记状态位（`available`/`dailyFailed`）。触发点：`Updates.onHomeResumed()`（`MainActivity.kt:770`）与控制中心打开（10 分钟）、每 3 小时（`docs/ARCHITECTURE.md:42`）；`IconLibrary` 在 App 列表加载后后台跑（`HomeViewModel.kt:251,256-259`）；联网回调到来时重试（`MainActivity.kt:113-118` `networkCallback` → `refreshIfStale(networkCameUp=true)`）。JSON 一律 `org.json`（`JSONObject`/`JSONArray`，见 `IconLibrary.parse:155-158`、`PhoneServer.state`），**没有 kotlinx.serialization / Gson**。

### 6.3 有没有访问局域网对端的先例

作为**服务端**有（PhoneServer 绑 8790，`lanAddress()` 取本机 LAN IPv4，`PhoneServer.kt:458-474`；Lite 还有 `127.0.0.1` 的 FilmProxy，见 `lite/src/main/res/xml/network_security_config.xml`）。作为**客户端**主动连局域网另一台机器：`grep` 全仓库 `http://192.` / 私有 IP 常量 —— **未找到**。所以「电视 → NAS」是新增的出网方向，除 §6.4 的明文策略外还要自己处理：NAS 地址未填/不可达（超时与退避，照 IconLibrary 的 `RETRY_MS`）、以及 `lanAddress()` 那套「排除 VPN 段」的经验（#667）反过来用不上。

### 6.4 【关键结论】network_security_config 会**拦死** `http://192.168.3.3:9080`

- `app/src/main/AndroidManifest.xml:81`：application 上挂了 `android:networkSecurityConfig="@xml/network_security_config"`。
- `app/src/main/res/xml/network_security_config.xml`（全文）：只有两个 `<domain-config cleartextTrafficPermitted="true">`，域名分别是 `sylvan.apple.com` 与 `api.open-meteo.com / geocoding-api.open-meteo.com / ipinfo.io`；**没有 `<base-config>`**。
- `grep usesCleartextTraffic` 在 app/lite 两处 manifest：无结果（未设置）。

结论：Aerial `targetSdk = 34`（≥28），在存在 networkSecurityConfig 且无 base-config 的情况下，**未列入白名单的主机默认不允许明文 HTTP** —— `URL("http://192.168.3.3:9080/...")` 在 Android 7+ 的新系统上会抛异常（等价 `CLEARTEXT communication to ... not permitted by network security policy`）。反而**Android 5/6 会忽略这个配置文件**（`network_security_config.xml` 注释与 `docs/ARCHITECTURE.md:157`、`docs/HANDOFF.md:159` 都写明「Android 5.0 and 6.0 ignore this file」），即老电视上 http 能连、新电视上不能连——这是最容易被误判成「代码 bug」的坑。

必须做的改动（三选一，都只动这一份 xml，不动代码）：

1. 精确加一条 `<domain includeSubdomains="false">192.168.3.3</domain>` 到某个 `cleartextTrafficPermitted="true"` 的 domain-config。**先例现成**：Lite 那份就为 `127.0.0.1` 这么写过。**但**这会把户主的 NAS IP 写进版本库文件，违反 AGENTS.md「`local/TVS.md` 这些是本机信息，不要复制进版本库里的文件」。
2. `<base-config cleartextTrafficPermitted="true">`：全放开，最省事，但把「HTTPS everywhere」（该文件第一行注释的项目原则）整个放弃，也会放宽 Apple/天气域名，需要户主拍板。
3. 运行时豁免：MSC 无法用代码追加域名 → 只能靠 1/2。若坚持不放宽，另一条路是让 tv-manager 上 HTTPS（自签 + 打进 raw 信任锚，先例：`raw/apple_root_ca.pem` 只给 `sylvan.apple.com` 用），或让手机页走「电视起服务、NAS 不来连」的拉取模型 —— DESIGN.md §8 已把 HTTPS 列为独立工程，别顺手加在屏幕时间里。

> 注：域名条目对 IP 型 host 按字符串精确匹配（Lite 的 `127.0.0.1` 即在真实工作）；`*.` 通配不支持 IP 段，故「任意 192.168.x.x」这条写不出来，只能要么固定 IP、要么 base-config 放开。若采用方案 1 而 NAS 地址可变，需要「安装时由 install.sh 用 sed 生成 xml」这类本机化做法（不进版本库），属实施决策，**本调研未验证可行性**。

## 7. 设置存储与配置入口

### 7.1 AerialSettings 的写法约定

`app/src/main/java/app/orchard/tv/settings/AerialSettings.kt`（343 行）：

- 载体：`object AerialSettings`（`:59`），`private const val PREFS = "settings"`（`:61`），`fun init(context)`（`:181`）一次性把所有字段读进内存；调用方在广播/服务里也各自 `AerialSettings.init(context)`（`ConfigReceiver.kt:90,132,145`）。
- 状态：全部是 Compose state + 只写内部：`var focusStyle by mutableStateOf(FocusStyle.HighContrast); private set`（`:64`）。类注释 `:58`：**「Aerial's own preferences. Compose state, written on the main thread only.」**
- 写：一律 `fun chooseXxx(...)`——先改内存字段，再 `app.getSharedPreferences(PREFS, MODE_PRIVATE).edit { putString("boot_source", id).remove("boot_app") }`（`:216-289`，`androidx.core.content.edit`，即 `apply()`）。没有同步 `commit()` 的偏好写（例外：`ConfigReceiver.kt:148` 的 cec 用 `edit().commit()`，因为广播生命周期短）。
- 命名：字段 camelCase（`homeColumns`），prefs key snake_case（`home_columns`、`pin_hash`、`aerial_quality`、`ui_sounds_kind`）；枚举带 `key: String` + `companion fun of(key)`（`FocusStyle` `:22-29`、`Effects` `:48-56`）。越界值在 init 里收敛（`:196`：`takeIf { it in HOME_COLUMNS } ?: DEFAULT_HOME_COLUMNS`）。
- 敏感项：家长 PIN 存 SHA-256（`"aerial:$pin"`，`:252-253`），`locked`/`unlock(pin)`/`relock()` 用 `SystemClock.elapsedRealtime()` + `UNLOCK_MS = 10 分钟`（`:231-238`，`:154`）。**这套 unlock/locked 正是 DESIGN.md §6 #6 要复用的 grant 底座**（但注意 `elapsedRealtime()` 重启即归零 → 语义是「本次开机内 10 分钟」，与手机 grant 对齐时要换成墙钟或另存）。
- 其它 prefs 文件（`grep getSharedPreferences`）：`system`（DeviceProfile 覆盖、cec_*、BootOpen）、`wallpaper`（`liveSpot`/`video_film`）、`layout`（LayoutStore）、`icon_library`、`phone_page`（token）、`uhd_canvas`。新增「stime 策略」按 DESIGN.md §6 #5 走 `settings` 一个 JSON 键，或独立文件 `stime_policy`——独立文件对上游更友好。

### 7.2 电视端有没有「填文本 / 配置服务器地址」的现成入口

**没有自由文本输入**：全仓库（app + lite）搜 `EditText|BasicTextField|TextField` **零结果**（`PinPad.kt:29` 用的是只读 `BasicText`）。`docs/ARCHITECTURE.md:172` 明确：「电视上没有好用的输入法，所以不做自由输入」——文件夹改名只能从预设名 `folder_names` 里选。

**所以文本输入的既有分工是**：需要打字的东西一律放**手机页**（天气城市 `POST api/weather/city`、App 改名 `api/app/name`、信号源名 `api/source/name`，`PhoneServer.kt:105-149`；`docs/ARCHITECTURE.md:209` 同样写明「自己起的名字在手机配置页输入」）。电视端只做「从固定选项里选」（`SettingRow` / 分组卡片，`docs/ARCHITECTURE.md:139`）。

### 7.3 adb broadcast 配置先例（ConfigReceiver）

`app/src/main/AndroidManifest.xml:146-153`：`.guard.ConfigReceiver`，`exported="true"`，`android:permission="android.permission.WRITE_SECURE_SETTINGS"`，action `app.orchard.tv.CONFIG` —— 类注释 `ConfigReceiver.kt:29-31` 说明：「The manifest requires the sender to hold WRITE_SECURE_SETTINGS, which the ADB shell has and ordinary apps cannot get, so no other app on the TV can flip these.」

键的写法惯例（`:36-150`）：`--ez` 布尔（`keep_adb`、`clear_pin`、`check_update`）、`--ei` 整数（`home_columns`、`preview_apps`、`adb_probe_port`）、`--es` 字符串（`screensaver`、`device_profile` 收 JSON、`open_source`、`export_tiles`）；JSON 非法就不保存并回显旧值（`:53-58`）；结果写 `resultData`（`:72,151`）便于 `adb` 里直接看；无 UI。最贴近「填一个地址/标识」的先例是 **`cec_home_input`**（`:143-150`：`--es` 存进 `system` prefs，`"*"` 表示任意，空串关闭，然后 `HomeGuardService.refreshCecHome()` 让常驻服务立刻生效）。

### 7.4 若要户主在电视上填 NAS 地址，现有最接近的做法

按侵入度排序（都有先例）：

1. **手机页填（最符合现有分工）**：`/api/stime/*` 的 `base` 参数写进 prefs，电视端只读展示；先例是天气城市。缺点：鸡生蛋——地址没填之前电视连不上 NAS，第一次得靠别的通道。
2. **adb 一次性配（推荐给自用/适配阶段）**：`ConfigReceiver --es stime_base http://192.168.3.3:9080`，完全照 `cec_home_input` 的写法与校验风格；这也符合 AGENTS.md「厂商特殊处理按功能检测、装机走脚本」的现状（`tools/install.sh` 已经是授权+配置的集中地）。缺点：对普通户主太难，若要做成公开功能不能只靠它。
3. **电视端数字键盘填 IP**：复用 `PinPad.kt` 的键盘范式（`:205` `val keys = listOf("123","456","789"," 0<")`，`:235` 用 `Pressable`，`:144-157` 接遥控器 `KEYCODE_0..9` / `DEL` / 确认）做一个「四段数字 + 端口」输入器；先例是家长 PIN 与「4 个方向键」式输入（`docs/HANDOFF.md:87` #368）。只能填 IPv4:port，填不了域名（无字母键）——若接受这个限制，这是电视端唯一可行的原生输入 UI。
4. **零配置发现**（如 mDNS/广播扫 tv-manager）：仓库内**未找到**任何 mDNS/NSD 用法，属净新增且要引依赖，不建议。

> 附带：`site/agent.md` / `tools/install.sh` 是给 Agent 的安装契约（AGENTS.md 要求它们只装 Aerial 自己）。若屏幕时间要写进安装流程，只能加「Aerial 自身的授权」（如 `appops GET_USAGE_STATS` 已存在，`install.sh:288-289`），**不得**加下载/安装其他东西。

## 8. 雷区与实施顺序

### 8.1 `docs/HANDOFF.md`「必须遵守（踩过的坑）」全部条目原文（`:218-229`）

1. 「**不要停用小米的 `com.mitv.tvhome`**：小米只认系统签名的桌面，停用后电视会卡在黑屏的 FallbackHome。」
2. 「**改 `enabled_accessibility_services` 时要保留别人的服务**（TCL 有两个 `com.tcl.walleve` 服务），用追加的方式写。」
3. 「**在 zsh 里不要写 `"$VAR:xxx"`**：`:a`、`:h`、`:t` 这些会被当成路径修饰符。一律写 `"${VAR}:xxx"`。这个坑把 TCL 的无障碍设置写坏过两次。zsh 也不会按空格拆分 `$VAR`，要循环就用数组。」
4. 「**清理测试数据时，只删自己创建的东西**（按记下来的 id 删），不要删「所有照片」这种。户主在测试期间用手机上传过照片，差点被一起删掉。用户选的壁纸也不要改回去。」
5. 「**在电视上按键操作前先截图确认焦点**，尤其是当贝的安装包管理（左侧有「一键清空」」。」
6. 「**不要把 `local/` 提交到版本库**（里面有 IP 和签名密钥）。」
7. 「改动系统设置前，先记下原值（写进 `local/TVS.md`），并确保 `restore.sh` 能还原。」
8. 「每次改完都在真机上验证，不要只看编译通过。电视上的行为和模拟器差别很大。」
9. 「**不要升级 Compose BOM 到 2025.12 及以后**（Compose 1.10 要求 Android 6.0），也不要升级到要求 minSdk 23 的 AndroidX 版本（比如 `lifecycle` 2.10 起）。升级前看 AAR 里 `AndroidManifest.xml` 的 `minSdkVersion`。」
10. 「**Lite（`lite/`）不要引入 AndroidX、Compose 或协程**，任何新接口都要能在 Android 4.0 上跑……`HashMap.putIfAbsent`、`Map.forEach { k, v -> }` 这类 Java 8 默认方法在 4.x 上不存在。」

`AGENTS.md` 的「不要做」（`:33-40`）与之叠加，其中与本任务直接相关的是 `:35`（只安装 Aerial 自己，不加第三方 SDK / 不归我们管的第三方服务器）、`:36`（不停用 `com.mitv.tvhome`、不卸载停用任何系统应用）、`:40`（没在真机验证过的功能不要说「已完成」）；「代码约定」（`:27-31`）要求注释与标识符英文、界面文字五份 `res/values*` 且占位符一致、手机页文字进 `KEYS`/`TEXTS`、960×540dp 画布、可按控件用 `Pressable`、厂商差异按功能检测并注明验证机型。

### 8.2 与本次改动直接相关的条目

| 条目 | 对本次改动的影响 |
|---|---|
| HANDOFF #1 / AGENTS `:36` | **拦截动作只能是「拉回桌面 / 起屏保 / 弹提示」**，不得 `setApplicationBlocked`、不得停用/卸载 App（DESIGN.md §3.3 已定此调，理由：设备管理员与 `forceStopPackage` 在厂商 ROM 上不可控）。`com.mitv.tvhome` 是原桌面磁贴，永不触碰 |
| HANDOFF #2 | 若要「让守护更卖力」（例如为屏幕时间重绑无障碍），只能走 `GuardSetup` 的**追加式**写法（`GuardSetup.kt` 现有实现已处理）；绝不在 stime 代码里直接写 `enabled_accessibility_services` |
| HANDOFF #4 | 测试期新建的分组/例外/用量文件要按自己记下的 id 删；**「清空数据」按钮**在真机验证时要格外小心，别清掉户主照片/壁纸选择（`Wallpapers` 的 `files/photos/`、`files/icons/` 与 `layout` prefs 不许碰） |
| HANDOFF #6 / AGENTS `:39` | NAS 地址 `192.168.3.3` 属本机信息：不得写进版本库源码/资源（与 §6.4 的方案 1 冲突，须与户主确认落地形式，例如只从 prefs/adb 读） |
| HANDOFF #8 / AGENTS `:40` | 拦截、跨天、待机、HDMI 四条路径**必须真机验证**（小米 MFTR0 = standard、TCL MT5879 = guard），否则只能说「已实现未验证」 |
| HANDOFF #9 | 不得为「定时上报」引 WorkManager/新 AndroidX；用现有 `CoroutineScope(SupervisorJob() + Dispatchers.Main)` + `Handler` 范式（§6.2）即可，零新依赖 |
| HANDOFF #10 + AGENTS `:27` | Lite 不能跑采集（无 UsageStats/无障碍），且 `assets/web/` 会被复制进 Lite（§1.6）→ 必须让手机页在 Lite 上不显示 stime；Lite 里也不许出现协程/AndroidX |
| AGENTS `:27` | 电视端新增文案（到点提示、锁定态、设置行）要一次写满 5 份 `res/values*`（目录已确认：`values`、`values-zh`、`values-ru`、`values-uk`、`values-kk`），占位符一致；手机页文字进 `KEYS`/`TEXTS` 五语表 |
| AGENTS `:28` | 电视端「到点提示」等浮层按 960×540dp 画布写 dp（缩放见 `MainActivity.kt:254-259` 的 `LocalDensity provides canvas`） |
| AGENTS `:30` | 任何电视端新按钮/列表项用 `ui/components/Pressable.kt`（147 行，范式见 `PinPad.kt:235`） |
| AGENTS `:31` | 「HDMI/投屏识别」「某些 ROM 不发窗口事件」这类差异要写成**按功能检测**（查包存在、查设置存在）并注明在哪台机型验证；无法检测才进 `DeviceProfile`/`assets/devices.json` |
| `docs/ARCHITECTURE.md:95-110` 性能约束 | 采集循环**不得引入空闲时的持续动画/绘制**（「空闲时不能有任何持续动画」）；1 秒轮询只做 IPC（`queryEvents`）不触发重绘，UI 只在内容变化时更新 |

### 8.3 分步实施计划（每步可独立验证）

前提决策（要主人先定）：① 明文 http 走哪个方案（§6.4）；② NAS 地址是否进版本库（§8.2 #6）；③ 手机页是加 tab 还是独立 `screen-time.html`（§2.6）。

| 步 | 内容 | 独立验证方式 | 必须真机 |
|---|---|---|---|
| 0 | 契约冻结：tv-manager 侧确认 `/api/stime/*` 的鉴权/幂等，Aerial 侧定下设备标识（建议 `Settings.Secure.ANDROID_ID`）与上报频率 | 用 `curl` 从 Mac 打 NAS 跑通 push/pull（与 Aerial 无关） | 否 |
| 1 | 网络底座：`Net`（HttpURLConnection + `withContext(Dispatchers.IO)` + 超时 + `RETRY_MS` 退避，照 §6.2 抄），加 `network_security_config` 明文放行 | 电视上「诊断」里加一行 `GET /api/health` 结果；或 ConfigReceiver `--ez stime_ping true` 打印 `resultData`。**这一步会直接暴露明文是否被拦** | **是**（Android 14 小米 + 一台 Android 5/6 老机对照） |
| 2 | 配置项：`stime_base`（prefs）+ ConfigReceiver `--es`（照 `cec_home_input`，`ConfigReceiver.kt:143-150`）+ 手机页读回显示 | adb 设值 → `GET /api/stime` 返回的 base 变了；重启后仍在 | 是（ prefs 与广播在厂商 ROM 上无特殊，但顺手确认） |
| 3 | 采集器 `UsageCollector`：会话配对，双源（无障碍 `lastWindow` + `MainActivity` 的 resume/pause），落 `files/usage/<date>.json`，30 天滚动删除 | adb `--ez stime_dump true` 导出当日 JSON 与人工操作对账（开 A 3 分钟、切 HDMI、进屏保） | **是**（PatchWall/VIDAA 无窗口事件那条只有真机能验） |
| 4 | 上报：定时 + 会话结束触发；失败重试、离线缓存当日；跨天日切自己判（本地日期比较，不依赖 `ACTION_DATE_CHANGED`，见 §4.4） | NAS 上 `data/usage/<date>.json` 出现且与第 3 步对账一致；拔网线后恢复能补报 | 是 |
| 5 | 规则下发：`GET /api/stime/policy` 拉取 + 本地缓存；手机页只读展示当前策略与「上次同步时间」 | 改 `policy.json` → 电视在下一次拉取后 `GET /api/state` 或 stime 路由里反映 | 部分（同步逻辑可模拟器，界面需真机） |
| 6 | 判定 + 执行器：`evaluate(frontPkg, now)`（§3.3 优先级），到点走守护 `goHome()` 等价入口 + 屏幕提示（新 `res/values*` 五语文案，`NoticeBanner` 先例 `docs/ARCHITECTURE.md:217`），带 3 秒退避防拉锯 | 设一个 1 分钟限额，实测被拦回桌面；再验证 HOME 键、屏保、开机打开 App、原桌面磁贴都不被误伤 | **是，且必须小米 + TCL 两台**（TCL 走 guard + 多屏互动推送，需 versionCode+1） |
| 7 | 宵禁 / 用眼休息 / 例外倒计时（都复用 6 的执行器） | 改系统时间跨宵禁区间；例外到期自动恢复 | 是 |
| 8 | 授权与开关：未授 `GET_USAGE_STATS` 时的降级（只用无障碍源）+ 手机页横幅（§4.1 无现成引导代码，需新建）+ 功能总开关（关掉时完全不跑循环） | 用 `restore.sh` 撤销授权后验证不崩、不误拦 | 是 |
| 9 | 手机页挂载（tab 或独立页）+ 五语 + 与 Lite 的隔离（§1.6） | 浏览器 390/1440 宽看过；Lite 包打开同一页不报错 | 是（Lite 至少一台 4.x 或 5.x 实机；本仓库无 4.x 机器 → 记录为未验证） |
| 10 | 回归与文档：`tools/selftest.sh`、`tools/report.sh` 诊断加 stime 行、`ARCHITECTURE.md` 新节 + 手机页那节（文档已滞后）修正 | `./gradlew lintStandardRelease`（NewApi）+ 现有单测（`docs/HANDOFF.md:8` 记 81 个）+ 两台真机全流程 | 是 |

建议 1→2→3 先做成「只上报不拦截」的可发版增量（风险最低、立刻能在 NAS 上看到数据），执行器（步 6）单独作为一个开关后再上。

### 8.4 最大不确定项

1. **明文 HTTP 能否连通（阻塞性）**：`app/src/main/res/xml/network_security_config.xml` 无 base-config，targetSdk 34 → Android 7+ 上 `http://192.168.3.3:9080` 预期被系统拒绝，而 Android 5/6 忽略该文件反而能连。必须第 1 步在真机上打出这条请求才算定案（同时决定是否违反「不把 IP 写进版本库」）。
2. **前台观察的完整性**：无障碍 `TYPE_WINDOW_STATE_CHANGED` 在部分 ROM 上不发（`HomeGuardService.kt:110-118,450-454` 已证两类实例）；纯靠窗口事件做会话在「App 内部切 Activity / 对话框 / 播放器全屏」等边界上的误差，仓库里无任何记录，必须实测对账。
3. **拦截的真实效果与抖动**：被拦 App 自启、Android 11- 的 5 秒后台启动冻结（`APP_SWITCH_HOLD_MS`，`:1137`）、Android 12+ 小米「Aerial 在前台不过滤按键」（`:1061-1064`）与 `backOnPause` 的 30–80 ms 竞态；退避策略是全新设计，只有真机能给结论。
4. **守护不可用时的兜底**：无障碍被关闭/待机退出（`guard_off_in_standby`，`docs/HANDOFF.md:75`）时拦截失效，属于「孩子能发现的口子」，需与主人确认是否接受。
5. **HDMI / 投屏是否可控**：多屏互动/投屏接收进程无统一识别代码（§4.4「未找到」），DESIGN.md §7 把它列为已知漏口。
6. **Lite 的影响面**：手机页被 Lite 整体复制（`lite/build.gradle.kts:79`），stime 页在 Lite 上没有后端；是否隐藏、如何隐藏，需要先看 Lite 实际渲染（本仓库无 4.x 真机 → 只能标未验证）。
7. **上游热区合并成本**：`PhoneServer.kt` 与 `assets/web/index.html` 是上游活跃改动区（1.67 刚合入 PR #807，`docs/HANDOFF.md:8`），改动面越大越难跟进上游；这是工程节奏风险，不是技术障碍。

### 8.5 关键事实速查

- 仓库内**尚无任何屏幕时间实现**：`app/src/main/java/app/orchard/tv/` 下无 `screentime/` 目录，`git status` 只有 `design/` 与 `docs/research/` 未跟踪（调研时核实）。
- 三处「未找到」：`queryUsageStats` 调用、权限引导入口（`ACTION_USAGE_ACCESS_SETTINGS`）、通用 App 分类；另加自由文本输入（无 `EditText`/`BasicTextField`）。
