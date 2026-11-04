# 调研报告：Aerial 现有「手机设置页 H5」实现方式

- 调研日期：2026-10-28
- 调研范围：`app/src/main/assets/web/index.html`（112,151 字节 / 1,933 行）及其原生侧支撑代码
- 目的：为新增「H5 后台（屏幕时间 / 应用使用时长控制）」做准备
- 约束：只读调研，未修改任何现有文件（本报告为唯一新增文件）

> 行号均以当前工作区文件为准。所有结论附证据；读不到的写「未找到」。

---

## 0. TL;DR（十条核心结论）

1. **架构**：TV 端内嵌 NanoHTTPD 服务器（端口 **8790**），手机浏览器经局域网直接访问；**不是**打包进手机 App 的 WebView，也没有手机 App。
2. **入口**：TV 个性化壁纸面板显示二维码 + 明文地址，URL 形如 `http://<TV局域网IP>:8790/<8位token>/`；Aerial 运行期间服务**常驻监听**（1.55 起）。
3. **鉴权**：唯一凭据是 URL 路径前缀里的 8 位随机 token（持久保存于 SharedPreferences `phone_page`）；**无 HTTPS、无登录、无 PIN 交换、无 mDNS/SSDP 发现**；错 token 一律 404。
4. **协议**：`GET /api/state` 返回全量 JSON 状态；所有写操作是 `POST /api/*` + **表单**（`application/x-www-form-urlencoded`，multipart 仅用于上传照片/视频/图标）；**无 JSON-RPC、无 schema 定义**。
5. **推送**：无 SSE / WebSocket。页面 **6 秒轮询** `api/state`，`JSON.stringify` 对比无变化则跳过重建；页面隐藏、弹层打开、拖拽中不轮询。
6. **i18n**：`KEYS`（一维字符串数组，~137 键）+ `TEXTS`（`{zh,en,ru,uk,kk}` 各一条**与 KEYS 等长、按索引对齐**的文案数组）；插值用 `{name}` 花括号占位符，函数 `t(key, vars)`；静态 DOM 用 `data-i18n` / `data-i18n-placeholder` / `data-i18n-aria-label` 属性填充。
7. **UI**：单文件内联 CSS/JS；iOS 风格设置列表（`--bg/--card/--tint` 等 CSS custom properties 设计令牌，`prefers-color-scheme` 深浅色）；**手机优先**（`width=device-width`、safe-area、px 布局），`min-width:900px` 时切换为桌面侧边导航。
8. **导航**：三个 tab（`home` 主屏幕整理 / `looks` 壁纸与屏保 / `more` 更多），hash 路由（`#home`）+ localStorage 记忆；旧 `#wallpaper`、`#screensaver` 有兼容映射；详情操作用底部滑出 sheet + toast。
9. **原生存储**：设置在 `SharedPreferences`（文件名 `settings`，另有 `layout`、`phone_page` 等），对象 `AerialSettings` 用 Compose `mutableStateOf` 暴露，**仅主线程写**，HTTP 线程经 `runBlocking(Dispatchers.Main)` 切回。
10. **使用时长相关能力**：✅ 可直接复用——已声明 `PACKAGE_USAGE_STATS` 权限、`AppOpsManager` 授权检查、`UsageStatsManager.queryEvents()` 前台探测（HomeGuardService）、应用枚举（AppCatalog）、包增删改广播监听、家长 PIN（SHA-256、方向键式）。❌ 缺口——**无任何按天/按应用的时长汇总**（`queryUsageStats` 全仓库零调用）、无「限制/封锁某 App」的执行机制（无 DevicePolicyManager / setMode / 到点踢回）、token 化设置写入均无审批流。详见 §6。

---

## 1. H5 页面如何被手机打开

### 1.1 结论：TV 端起 HTTP server，手机浏览器直接访问

服务器实现是 **NanoHTTPD**（`fi.iki.elonen.NanoHTTPD`），随 APK 打包，运行在 TV 进程内：

- `app/build.gradle.kts:107-108`
  ```kotlin
  implementation("org.nanohttpd:nanohttpd:2.3.1")
  implementation("com.google.zxing:core:3.5.3")
  ```
- `app/src/main/java/app/orchard/tv/wallpaper/PhoneServer.kt:30,50`
  ```kotlin
  import fi.iki.elonen.NanoHTTPD
  class PhoneServer private constructor(private val context: Context, private val token: String) : NanoHTTPD(PORT) {
  ```
- `PhoneServer.kt:409`：`const val PORT = 8790`

页面本体是 assets 里的单文件 HTML，服务器每个请求现读并做占位符替换（**不是** WebViewAssetLoader，也没有手机端 App；全仓库未发现任何 `WebView` 加载该页的代码）：

- `PhoneServer.kt:191-192`
  ```kotlin
  private fun page(): String = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
      .replace("{{DEVICE}}", Build.MODEL.replace("_", " "))
  ```

生命周期类注释（`PhoneServer.kt:41-49`）：

> It listens on the local network while Aerial runs (see [listen]), and every address carries a random token shown only on the TV (the QR code and the address under it), so another device on the network cannot use it by guessing. The token stays the same from one start to the next.

### 1.2 服务何时启动：常驻（1.55 起）

- `app/src/main/java/app/orchard/tv/MainActivity.kt:707-712`（`startWork()`）
  ```kotlin
  // The phone and computer page, at any time the TV is on (only once unlocked: its token is
  // in credential-protected storage).
  lifecycleScope.launch(Dispatchers.IO) { PhoneServer.listen(this@MainActivity) }
  ```
- `PhoneServer.kt:419-433`：`listen()` 幂等（`running?.let { if (it.isAlive) return it }`），`it.start(SOCKET_READ_TIMEOUT, true)`，绑定所有接口。

⚠️ **文档滞后**：`docs/ARCHITECTURE.md:169` 仍写「只在壁纸面板打开时运行，关闭后再保留 5 分钟」「令牌每次打开面板都重新生成」，与代码不符。`docs/HANDOFF.md:44`（1.55 发布记录）为准：「手机 / 电脑设置页改为 Aerial 运行期间一直监听……令牌固定，仍是唯一的访问凭据」。另外 MainActivity 注释称 token 在 "credential-protected storage"，但 `PhoneServer.kt:446-450` 实际是普通 `getSharedPreferences("phone_page", MODE_PRIVATE)`——以代码为准。

### 1.3 手机如何拿到地址：TV 屏二维码（ZXing），无自动发现

- `PhoneServer.kt:436-440`
  ```kotlin
  fun start(context: Context): String? {
      val ip = lanAddress(context) ?: return null
      val server = listen(context) ?: return null
      return "http://$ip:$PORT/${server.token}/"
  }
  ```
- `PhoneServer.kt:483-495`：`qr(text, scale=8)` 用 ZXing `QRCodeWriter` 直接画 Bitmap。
- TV 端展示入口（个性化 → 壁纸面板）：`app/src/main/java/app/orchard/tv/ui/wallpaper/WallpaperSheet.kt:112-118`
  ```kotlin
  // The phone page listens all along (PhoneServer.listen); the address is read again each time,
  val url = withContext(Dispatchers.IO) { PhoneServer.start(context) }
  qr = url?.let { withContext(Dispatchers.Default) { PhoneServer.qr(it).asImageBitmap() } }
  ```
- 局域网地址选择做了代理/VPN 场景修正（Clash 隧道 198.18.0.1 不可达，issue #667）：`PhoneServer.kt:458-480`（优先 Wi-Fi/以太网非 VPN 网络，排除 198.18.0.0/15 benchmark 段）。

**未发现** mDNS/NsdManager/SSDP/UDP 广播等任何局域网自动发现机制（全仓库搜索 `NsdManager|mdns|SSDP|MulticastSocket` 零命中，仅 `ui/home/SourceWallpaper.kt:74` 一个无关的 `FOCUS_BLOCK_DESCENDANTS`）。配对方式 = 扫 TV 屏上的二维码，仅此一种。

### 1.4 HTTP 路由总表

全部路由定义在 `PhoneServer.serve()`（`PhoneServer.kt:52-189`）。所有路径都相对于 `/<token>` 前缀；**不以该前缀开头的 URI 一律 404**（`PhoneServer.kt:53-55`）。

| 方法 | 路径（去前缀） | 作用 | 证据行 |
|---|---|---|---|
| GET | `/` | 返回 index.html（替换 `{{DEVICE}}`） | 59 |
| GET | `/api/state` | 全量状态 JSON（见 §2.2） | 60 |
| GET | `/api/diagnostics` | 纯文本诊断报告（token/IP 已打码） | 61 |
| GET | `/thumb/<id>` | 壁纸缩略图 JPEG（Cache-Control: max-age=600） | 62, 352-360 |
| GET | `/tile/<key>[?art=own]` | 磁贴当前样子的 JPEG | 102, 317-328 |
| GET | `/library/<pkg>` | 图标库下载的图 | 103, 331-337 |
| POST | `/api/wallpaper` | 选壁纸；form: `id` | 63-67 |
| POST | `/api/screensaver` | 选屏保模式；form: `mode` | 68-72 |
| POST | `/api/setting` | 开关型设置；form: `key`(wallpaperAlwaysMoves/showWeather/screensaverClock) + `on`(1/0) | 73-84 |
| POST | `/api/focus` | 焦点样式；form: `style` | 85-89 |
| POST | `/api/delete` | 删照片；form: `id` | 90-94 |
| POST | `/api/upload` | multipart 上传照片（可多张，≤200MB） | 95, 362-376 |
| POST | `/api/videos/upload` | multipart 上传屏保视频（单段 ≤200MB，字段名 `video`） | 96, 379-390 |
| POST | `/api/videos/delete` | 删视频；form: `id` | 97-101 |
| POST | `/api/icon` | multipart 上传自定义磁贴图；form: `app` | 104, 339-350 |
| POST | `/api/source/name` | 信号源磁贴改名；form: `app`,`name` | 105-111 |
| POST | `/api/app/name` | App 改名（24 字内）；form: `app`,`name` | 112-123 |
| POST | `/api/layout` | 主屏幕整理；form: `op`(move/movein/hide/unhide/folder/unfolder/ungroup), `key`, `target`, `folder` | 124, 291-309 |
| POST | `/api/source/device` | 信号源接入设备；form: `app`,`device` | 125-131 |
| POST | `/api/source/pin` | 信号源钉到主屏幕；form: `id`,`pinned`(1/0) | 132-139 |
| POST | `/api/folder/name` | 文件夹改名；form: `id`,`name` | 140-149 |
| POST | `/api/icon/pick` | 选图标来源；form: `app`,`pick`(library/own) | 150-168 |
| POST | `/api/icon/reset` | 恢复默认图标；form: `app` | 169-173 |
| POST | `/api/weather/city` | 设置天气城市；form: `name` | 174-178 |
| POST | `/api/weather/auto` | 用网络定位城市 | 179-182 |

注：路由是 `when` 链硬编码，无路由表/注解框架；`serve()` 整体包在 `runCatching` 里，异常回 500 文本 `"error"`（`PhoneServer.kt:185-188`）。

## 2. H5 ↔ TV 通信协议

### 2.1 数据格式

- **请求**：POST 一律 HTML 表单。H5 侧封装 `api()`（`index.html:1153-1158`）：
  ```js
  async function api(path, body) {
    const opts = body ? { method: 'POST', body: new URLSearchParams(body) } : {};
    const r = await fetch(base + path, opts);
    if (!r.ok) throw new Error(r.status);
    return r.json();
  }
  ```
  `base = location.pathname.replace(/\/?$/, '/')`（`index.html:376`）——token 前缀由当前 URL 继承，页面代码里不出现 token。
  原生侧 `form()`（`PhoneServer.kt:397-400`）`session.parseBody` 后取 `session.parameters` 每键首值。文件上传走 multipart：`session.parseBody(files)` 落临时文件（`PhoneServer.kt:341-345, 364-365, 383-385`），并用 `tooLarge()` 按 Content-Length 拦 200MB（`PhoneServer.kt:362, 392-395`，防写满 TV 存储，issue #57）。
- **响应**：写操作与 `/api/state` 均返回 `application/json; charset=utf-8` 的 **完整 state JSON**（`json(state())`，`PhoneServer.kt:404`），常附带结果字段：`saved` / `added` / `removed` / `found`（如 `PhoneServer.kt:83, 100, 177`）。即「改完立刻回全量新状态，前端直接重绘」，H5 侧对应 `take(next)`（`index.html:1159`）。
- **无 JSON Schema 文件**；事实 schema 即 `PhoneServer.state()` 的输出结构（`PhoneServer.kt:194-262`），字段：

  ```
  { selected, latestDaily, rotatePhotos, screensaver, screensaverFollows,
    videos[{id,name}], focusStyle, wallpaperAlwaysMoves, showWeather, screensaverClock,
    wallpapers[{id,kind,title,caption}],
    apps[{key,label,default,custom,stamp,icon,library,name?, source?,port?,device?,look?}],
    layout{home[],folders{[id]:[]},hidden[],hiddenCount,columns,locked,ready},
    devices[{key,label}], inputs[{id,port,device,name,pinned}],
    folders[{id,key,name,count,stamp}],
    weather{place,chosen,celsius?,kind?,day?} }
  ```

  `stamp` 是磁贴图缓存失效戳（`PhoneServer.kt:223-237`；前端 `${base}tile/${key}?v=${e.stamp}`，`index.html:1552`）。

### 2.2 鉴权与安全

- **机制**：仅 URL 路径前缀 token。`serve()` 第一行即 `if (!uri.startsWith("/$token")) return 404`（`PhoneServer.kt:52-55`）。
- **token 生成/持久化**（`PhoneServer.kt:442-456`）：
  ```kotlin
  private fun token(context: Context): String {
      val prefs = context.getSharedPreferences("phone_page", Context.MODE_PRIVATE)
      prefs.getString("token", null)?.takeIf { it.length == 8 }?.let { return it }
      return newToken().also { prefs.edit().putString("token", it).apply() }
  }
  // alphabet = "abcdefghjkmnpqrstuvwxyz23456789"（去 i/o/l/0/1），SecureRandom，8 位
  ```
  设计意图（注释 `PhoneServer.kt:443-445`）：可在电脑书签里固定，隔天标签页仍有效。
- **明文 HTTP**，无 TLS。`docs/HANDOFF.md:235` 已知限制一节明说：「手机配置页是局域网 HTTP，只靠随机令牌保护」。
- **泄漏面控制**：诊断报告把日志里的 `:8790/<token>` 打码为 `:8790/<token>`（`app/src/main/java/app/orchard/tv/system/Diagnostics.kt:244-248`）。
- **家长 PIN 与此页的关系**：PIN 只能在电视端设置（`ui/home/PinPad.kt:112`、`HomeScreen.kt:1033`、`guard/ConfigReceiver.kt:89` 调用 `AerialSettings.choosePin`）；**手机 API 没有读写 PIN 的路由**。PIN 只影响可见性：`layoutState()` 在 `AerialSettings.locked` 时不列隐藏 App 且拒绝 `unhide`（`PhoneServer.kt:274-281, 301`）。

### 2.3 推送 / 轮询

- **无 SSE、无 WebSocket、无长轮询**（原生与 H5 两侧搜索 `EventSource|WebSocket` 均零命中）。
- H5 轮询（`index.html:1928-1930`）：
  ```js
  async function refresh() { try { state = await api('api/state'); render(); } catch (e) {} }
  refresh();
  setInterval(() => { if (!document.hidden && sheet.hidden && !arrDragging) refresh(); }, 6000);
  ```
  6 秒一次；页面隐藏、sheet 打开、拖拽排序中暂停。`render()` 用 `JSON.stringify(state)` 与上次比较，相同则整体跳过重建，避免图片闪烁和丢点击（`index.html:1912-1917` 注释）。
- 服务端写操作**同步**切主线程执行后回新 state（`onMain`/`runBlocking(Dispatchers.Main)`，`PhoneServer.kt:307, 402`），所以 TV 界面即时跟随；手机之间只靠轮询收敛（延迟 ≤6s）。

## 3. 页面内部结构约定（index.html）

### 3.1 多语言：KEYS / TEXTS / t()

**写法**（全部内联，无外部 i18n 库）：

- `index.html:382-384` 注释与 `KEYS`（节选，实际约 137 键一行写完）：
  ```js
  // first of the phone's preferred languages that has a table, else English. Keys are in KEYS, one array of texts per language.
  const KEYS = ["screensaver", "screensaver_short", "ss_aerial", "ss_photos", "ss_clock", "focus", "focus_high", "focus_standard", "weather", "city_ph", "set", ..., "toast_diag_paste"];
  ```
- `index.html:384` 起 `TEXTS`：对象，5 个语言键，**顺序为 zh(385), en(527), ru(669), uk(811), kk(953)**，每种语言是与 KEYS **一一对齐的纯文案数组**（不是 key→value 映射），共约 137 条 × 5：
  ```js
  const TEXTS = {
   "zh": [
    "屏幕保护程序",      // ← KEYS[0] = "screensaver"
    "屏保",
    "航拍",
    ...
    "正在发送 {n} 张…",  // 占位符示例（zh 表 444 行）
    ...
   ],
   "en": [
    "Screensaver",      // 527 行起，与 KEYS[0] 对齐
    ...
   ],
   "ru": [ "Заставка", ... ],   // 669 行
   "uk": [ "Заставка", ... ],   // 811 行
   "kk": [ "Экран қорғаушы", ... ] // 953 行
  };
  ```
- 装载与取用（`index.html:1096-1112`）：
  ```js
  const LANGS = Object.keys(TEXTS);                       // zh en ru uk kk
  const NAMES = { zh: '中文', en: 'English', ru: 'Русский', uk: 'Українська', kk: 'Қазақша' };
  const I18N = {};
  for (const l of LANGS) { I18N[l] = {}; KEYS.forEach((k, i) => I18N[l][k] = TEXTS[l][i]); }
  function t(key, vars) {
    let s = I18N[LANG][key] || I18N.en[key] || I18N.zh[key] || key;
    if (vars) for (const k in vars) s = s.split('{' + k + '}').join(vars[k]);
    return s;
  }
  ```
- 语言选择 `LANG`（`index.html:1097-1104`）：URL `?lang=` > localStorage `aerial-lang` > `navigator.languages` 逐个取语种子码匹配，兜底 en。切换语言 = 写 localStorage 后 `location.replace` 带 `?lang=` 重载（`index.html:1113-1122`）。

**占位符规则**：`{word}` 花括号单词，`t()` 用 `split/join` 全量替换。真实样例：`"正在发送 {n} 张…"`、`"没有找到「{name}」"`、`"视频：添加 {n} 段，失败 {failed} 段"`、`"正在发送 {n} / {total}…"`（zh 表 444-497 行）；en 对应 `"Sending {n}…"`、`"Could not find “{name}”"`（586-591 行）。

**调用样例**：
- 静态 DOM：属性驱动，`applyI18n()`（`index.html:1123-1128`）扫三种属性：
  ```html
  <h2 data-i18n="focus"></h2>
  <input data-i18n-placeholder="city_ph">
  <div class="list" role="radiogroup" data-i18n-aria-label="screensaver_short">
  ```
- JS 动态：`t('toast_renamed')`、`t('folder_count', {n: apps.length})`、`t('was_named', {name: def})` 等；组名/提示（`index.html:1131-1139`）：
  ```js
  const hints = { aerial: t('hint_aerial'), photos: t('hint_photos'), ... };
  const groups = [ ['live', t('g_live'), t('g_live_sub')], ['daily', t('g_daily'), t('g_daily_sub')], ... ];
  ```

**给新增功能的硬性约定**（AGENTS.md 复述）：「手机设置页 `assets/web/index.html` 的文字在 `KEYS` / `TEXTS` 表里，同样五种语言……占位符要一致，脚本里不要写死文字」。即新加一条文案 = KEYS 加键 + 5 个数组各按同一索引补译文。

### 3.2 CSS / JS 组织与视觉体系

- **全部内联**：`<style>` 9-273 行，`<script>` 375-1931 行；无外链、无构建工具、无框架（原生 DOM）。文件骨架：
  ```
  1-8   <!doctype html> + <head>（charset/viewport/title）
  9-273 <style>
  275-373 <body>：main>header + 3 个 <section data-tab> + <nav role="tablist"> + sheet + toast + drop
  375-1931 <script>：KEYS/TEXTS/i18n → tabs/sheet → 各 render* → 轮询启动
  ```
- **设计令牌**：`:root` CSS custom properties（`index.html:10-16`），iOS 色板：
  ```css
  :root {
    --bg: #f2f2f7; --card: #ffffff; --label: #000; --secondary: rgba(60,60,67,.6); --tertiary: rgba(60,60,67,.3);
    --sep: rgba(60,60,67,.18); --tint: #007aff; --fill: rgba(118,118,128,.12); --danger: #ff3b30;
    --bar: ...; --scrim: ...; --sheet: ...;
    --ease-out: cubic-bezier(.23,1,.32,1); --ease-drawer: cubic-bezier(.32,.72,0,1);
    --tabbar: calc(50px + env(safe-area-inset-bottom));
  }
  @media (prefers-color-scheme: dark) { :root { --bg:#000; --card:#1c1c1e; --tint:#0a84ff; ... } }   // 18-22
  ```
- **控件词汇表**（新页面应沿用）：`.list`（白卡片分组，圆角 12px）内放 `.item` 行（`47-66` 行），行内 `.txt` + `.sub`（13px 次要说明）+ `.val`（右侧值）+ `.chev`；`.switch`（自绘 iOS 开关，checked 绿 `#34c759`，`67-77`）；`.item.tint`/`.item.danger`/`.item.center`；`.btn`（`115-122`）；`.field`（输入行，`132-136`）；`role="radiogroup"` + `.item[role=radio]` + `.tick` 勾选 SVG 的单选组（屏保/焦点样式，`302-305, 330-334` 行 HTML；勾选图标集中在 `ICON` 常量，`index.html:1140-1147`）；`.hero`（16:9 大图卡，`80-87`）；`.wp`（壁纸网格卡，`90-113`）；`.tag`（图上小标签，`113`）；`.sheet`（底部弹层，桌面变居中弹窗）；`.toast`；`.drop`（整页拖放上传遮罩）。
- **字体**：`-apple-system, "PingFang SC", "HarmonyOS Sans SC", "MiSans", system-ui`，17px 基准（`index.html:26-28`）。
- **动效**：贝塞尔曲线走变量；尊重 `prefers-reduced-motion`（CSS `267-272` 行禁动画；JS `reducedMotion()`/`scrollBehavior()`，`index.html:1195-1196`）。

### 3.3 尺寸适配：手机优先 + 桌面渐进增强（非 TV）

- viewport（`index.html:5`）：
  ```html
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  ```
- 手机约定：`main { max-width: 720px; ... padding: env(safe-area-inset-top) 16px calc(var(--tabbar) + 32px) }`（`34` 行）；底部 tab 栏高度含 `env(safe-area-inset-bottom)`（`14,149` 行）；布局用 **px**，无 rem/vw 适配脚本。
- 桌面浏览器增强（`index.html:229-266`）：`@media (min-width: 900px)` 时 tab 栏移到左侧 220px 侧边导航、sheet 变居中 520px 弹窗、加 hover 态（`@media (hover:hover) and (pointer:fine)`）。注释：「A computer's browser: the tabs down the side, a wider page, dialogs in the middle.」
- **没有 TV 尺寸（960×540dp 画布）适配**——那是 Compose 界面的约定，与此 H5 无关。此页服务对象 = 手机浏览器 / 电脑浏览器（HANDOFF 多处提到在 iOS Safari 模拟器与 1440/375 宽浏览器验证）。

### 3.4 导航与路由

- `const TABS = ['home', 'looks', 'more']`；`TITLES = { home:'tab_home', looks:'looks_title', more:'tab_more' }`；旧 tab 兼容 `const OLD_TABS = { wallpaper: 'looks', screensaver: 'looks' }`（`index.html:1162-1165`）。
- `show(next)`（`1174-1191`）：section `hidden` 切换 + `aria-selected` + 每 tab 记忆滚动位置 + `history.replaceState(null,'','#'+tab)` + `localStorage 'aerial-tab'`。启动时读 `location.hash`，回退到 localStorage（`1926` 行）。
- 二级结构不走路由：「整理主屏幕」进文件夹用 `arrView` 状态 + header 左滑返回（`#homeBack`，`278` 行、CSS `.view.push/.pop`）；单对象操作用底部 sheet（`openSheet(build)`，`1200-1250`，支持下拉关闭手势）。

## 4. 现有功能分区（导航层级）

```
底部/侧边导航（3 tab）
├─ home 「主屏幕」（默认 tab；1.47 由整页编辑器升级而来）
│   ├─ 磁贴网格整理（#homeBody；拖拽排序、每排列数跟 TV 设置 layout.columns）
│   │   └─ 点磁贴 → sheet：改名(api/app/name)、换图标(api/icon、api/icon/pick、api/icon/reset)、
│   │            放/建/移出文件夹、隐藏/显示、（信号源）设备与改名(api/source/device、api/source/name)
│   ├─ 文件夹内视图（进文件夹改名/排序/解散；家长 PIN 锁定时不列隐藏 App）
│   ├─ 隐藏的 App 列表（hidden_h）
│   └─ 信号源开关列表（inputs_h → api/source/pin）
├─ looks 「壁纸与屏保」（1.47 由壁纸+屏保两 tab 合并；#wallpaper #screensaver 旧地址映射到此）
│   ├─ 当前壁纸 hero（点击进壁纸网格）
│   ├─ 壁纸分组（groups：live 航拍 / daily 每日一图 / photo 我的照片 / builtin / auto / source 信号源画面）
│   │   └─ 删照片走 sheet 确认（api/delete）；上传（api/upload，多选、可拖放整页 drop 区）
│   ├─ 屏保模式单选（aerial/photos/clock/videos；api/screensaver）
│   ├─ 「屏保显示时钟」开关（sw_saver_time → api/setting:screensaverClock）
│   └─ 视频管理（列表 + api/videos/upload/delete）
└─ more 「更多」
    ├─ 焦点样式单选（high_contrast/standard；api/focus）
    ├─ 天气（showWeather 开关、当前天气、城市搜索 api/weather/city、定位 api/weather/auto）
    ├─ 本机（型号 {{DEVICE}}、语言下拉 #lang）
    └─ 反馈问题（GET api/diagnostics 展示/复制 + GitHub issue 链接）
```

证据：`index.html:284-358`（三个 section 的 HTML）、`1132-1139`（groups）、`1292/1347/1415/1429/1567`（renderWallpapers/renderScreensaver/renderMore/renderInputs/renderArrange）；tab 历史沿革见 `docs/HANDOFF.md:67`（1.47「手机页改成三个标签」）、`:70`（1.46 四 tab）、`PhoneServer.kt:1164-1165`（OLD_TABS 注释「The four tabs of 1.46 and 1.47's first builds」）。

**未找到**「输入源投屏 / AirPlay / DLNA」分区——投屏类内容不在手机页（README 里的 tcast 是另一套 `docs/tcast-protocol.md` 协议，与本 H5 无关；页面内搜索 `airplay|dlna|cast|mirror|投屏` 零命中）。

## 5. 原生侧：设置存储

**统一用 SharedPreferences + Compose 可观察字段，无 DataStore、无 Room**（`grep DataStore|preferencesDataStore` 命中的只有注释与无关词）。

| 存储 | 文件（prefs name） | 用途 | 证据 |
|---|---|---|---|
| `AerialSettings`（object） | **`settings`** | 全部开关/样式/PIN 哈希/屏保分钟数/开机目标/每排列数等 | `settings/AerialSettings.kt:61` `private const val PREFS = "settings"`；写方法如 `chooseScreensaverMinutes`→key `screensaver_minutes`（323 行） |
| `LayoutStore`（类） | `layout` | 主屏幕排序、文件夹、隐藏 App（JSON） | `apps/LayoutStore.kt:22-23` |
| `AppNames` | `settings` 内 key `app_names` | 按包名的 App 自定义名（≤24 字） | `apps/AppNames.kt:18,27,40` |
| `PhoneServer` token | `phone_page` 内 key `token` | 手机页访问令牌 | `wallpaper/PhoneServer.kt:447` |
| 其他 | `icon_library`、照片/视频文件目录 `files/…` 等 | 图标偏好、户主上传内容 | ARCHITECTURE.md:116；`Wallpapers.kt:64` |

关键模式（新接口必须照抄）：

- `AerialSettings.kt:59`：`/** Aerial's own preferences. Compose state, written on the main thread only. */` —— 所以 PhoneServer 每个写路由都 `onMain {}` / `runBlocking(Dispatchers.Main) {}` 后再回 state（`PhoneServer.kt:65,86,109,307,402`）。
- 家长 PIN：`AerialSettings.kt:138-153,231-249`——SHA-256 哈希存 `pin_hash`，支持「方向键 PIN」（U/D/L/R 四次，`pin_arrows`），`locked = pinSet && elapsedRealtime() > unlockedUntil`，一次解锁 10 分钟（`UNLOCK_MS = 10*60*1000L`，153 行），进屏保/离开桌面会重新锁（142 行注释）。

## 6. 使用时长 / 前台应用 / 应用枚举能力盘点（关键结论）

### 6.1 可直接复用的现有能力

| 能力 | 现状 | 证据 |
|---|---|---|
| `PACKAGE_USAGE_STATS` 权限 | **已声明**（signature\|preinstalled，tools:ignore） | `app/src/main/AndroidManifest.xml:38-39`；INTERNET:35；QUERY_ALL_PACKAGES:49 |
| 使用统计授权检查 | `HomeGuardService.usageStatsAllowed(context)`：AppOps `OPSTR_GET_USAGE_STATS` == MODE_ALLOWED；注释注明「granted over ADB」 | `guard/HomeGuardService.kt:1239-1245`；被 Diagnostics 打进报告（`system/Diagnostics.kt:117`） |
| **前台应用实时探测** | `UsageStatsManager.queryEvents(now-10s, now)` 取最新 `MOVE_TO_FOREGROUND` 的 `(pkg, cls)` + 时间戳；守护每秒轮询一次（`FOREGROUND_POLL_MS = 1_000L`，回看 10s：`FOREGROUND_LOOKBACK_MS = 10_000L`）；仅在无障碍「看不见窗口」的机型启用 | `HomeGuardService.kt:146-160`（foreground()）、`120-144`（foregroundCheck）、`1131-1132`（常量）、类注释 55-64 |
| 事件类型常量 | 代码用 `UsageEvents.Event.MOVE_TO_FOREGROUND`（DEPRECATION 抑制，兼容新系统 MOVE_TO_FOREGROUND=1） | `HomeGuardService.kt:155-157` |
| **应用列表枚举** | `AppCatalog`（object）：`queryIntentActivities` 按 LEANBACK_LAUNCHER → LAUNCHER → 应用商店类目枚举，产出 `AppEntry(key,label,art,…)`；`AppCatalog.loaded` 全量、`home/folderApps/hiddenApps` 布局视图 | `apps/Apps.kt:106,208-212`；已在 `state().apps` 暴露给 H5（`PhoneServer.kt:214-239`） |
| **安装/卸载/变更监听** | MainActivity 动态注册 `packageReceiver`：`ACTION_PACKAGE_ADDED/REMOVED/CHANGED/REPLACED`（scheme=package，EXPORTED） | `MainActivity.kt:717-726`；Lite 同样（`lite/.../HomeActivity.kt:77-78`） |
| 隐藏 App（弱管控雏形） | `LayoutStore` 的 hidden 集合 + 家长 PIN 锁（电视端解锁看，手机 API 锁定态拒绝 unhide 且不列出） | `PhoneServer.kt:274-281,301`；HANDOFF:66 |
| 固件级儿童模式入口 | 小米「儿童」磁贴（firmware shortcut）可作为限制目标之一，已在 `installed()` 允许集合内 | `PhoneServer.kt:312-314`（注释 issue #530）；HANDOFF:63 |
| 设置页 UI 全套资产 | tab/sheet/开关/单选组/i18n/轮询渲染管线，新增一个「屏幕时间」tab 或 more 分区是纯增量 | §3、§4 |

### 6.2 缺口（需新建）

1. **使用时长统计**：全仓库 **未找到** `queryUsageStats` / `queryAndAggregateUsageStats` 的任何调用——现有 `queryEvents` 只用于「最近 10 秒谁在前台」，用完即弃，**没有按天/按 App 的会话聚合与持久化**。做屏幕时间需新建：事件累积（建议查询窗口 ≥1s 的 foreground→background 配对）、日历日聚合、存储（可仿 `LayoutStore` 加 prefs 文件，或 JSON 存 `settings` 同级新 prefs）。`usageStatsAllowed` 检查可复用，但要考虑未授权时引导（现有授予途径是 ADB/appops，Manifest 注释同）。
2. **应用限制执行**：**未找到** `DevicePolicyManager`、`AppOpsManager.setMode`、`forceStopPackage` 等任何限制/封锁调用（搜索仅命中无关的 `FOCUS_BLOCK_DESCENDANTS`）。到点踢回 Aerial 的「执行器」要么走 HomeGuardService 的无障碍拉回通道（已有 `goHome()`，但语义是防原桌面抢占），要么走 `ActivityLifecycleCallbacks`——**全仓库也未找到 `registerActivityLifecycleCallbacks` 与 `getRunningTasks` 的使用**，均无现成件。
3. **手机页读写设置之外的新数据通道**：`/api/state` 是「全量小状态」设计，逐日逐 App 时长矩阵会显著变大；需要新的独立端点（如 `GET /api/usage`）与增量渲染，而不是塞进 state。同理若限制规则要 PIN 保护，现有 API 层**没有任何会话/凭据概念**（只有 URL token），「H5 上输入家长 PIN 解锁后台」需要新协议（最自然：`POST /api/pin/unlock` 换短期凭据；现状 PIN 验证只在电视端 `unlock(pin)`）。
4. **文档同步**：ARCHITECTURE.md「手机配置」一节落后于代码（§1.2），新增功能时记得一并修正。

### 6.3 结论一句话

**地基齐全**（权限、授权检查、事件源、应用目录、包监听、PIN、整套 H5 框架都现成），**但「统计聚合 + 限制执行 + 会话鉴权」三件事在本仓库今天还不存在，全部要新建**；新建时应对齐的既有约定：NanoHTTPD 路由加在 `serve()` 的 `when` 链、主线程写 `AerialSettings`/新 prefs、state 之外的数据开新端点、文案进 KEYS/TEXTS 五语齐全、UI 复用 `.list/.item/.switch/sheet`。

## 7. 文档摘录

### 7.1 docs/ARCHITECTURE.md（相关部分）

- 结构图（`:38`）：`PhoneServer.kt  NanoHTTPD 手机配置页 + 二维码生成`；（`:60`）`app/src/main/assets/web/index.html 手机配置页（单文件，iOS 风格，深浅色）`。
- 「手机配置（PhoneServer）」专节（`:167-172`）：路由清单（同上表，另注意其「常驻/令牌固定」描述已滞后，见 §1.2）；家长 PIN 语义：「家长密码开着时不给 unhide，api/state 的 layout.hidden 也是空的」。
- 诊断（`:192`）：「手机页 `GET api/diagnostics`……局域网 HTTP 没有 `navigator.clipboard`，用 `execCommand`」——解释了 H5 里复制兜底。
- 依赖（`:276`）：`Compose BOM 2026.02.01、…… NanoHTTPD 2.3.1、ZXing core 3.5.3`。
- 图标库（`:116`）：手机页 `library/<包名>` 与 `tile/<包名>?art=own` 的取图约定、`POST api/icon/pick` 语义。
- 升级二维码（`:185`）：`https://aerial.qust.me/update/#v=…&f=…&tv=<局域网IP>` 是**官网**升级引导页（ControlCenter 两处 QR，`ControlCenter.kt:324-326, 399-401`），与 TV 本地 8790 服务是两回事，勿混淆。

### 7.2 docs/HANDOFF.md——「必须遵守（踩过的坑）」全文（`:216-227`）

1. **不要停用小米的 `com.mitv.tvhome`**：小米只认系统签名的桌面，停用后电视会卡在黑屏的 FallbackHome。
2. **改 `enabled_accessibility_services` 时要保留别人的服务**（TCL 有两个 `com.tcl.walleve` 服务），用追加的方式写。
3. **在 zsh 里不要写 `"$VAR:xxx"`**：`:a`、`:h`、`:t` 会被当成路径修饰符，一律写 `"${VAR}:xxx"`；zsh 也不按空格拆分 `$VAR`，循环用数组。
4. **清理测试数据时只删自己创建的东西**（按记下的 id 删）；户主测试期间上传过照片差点被删；用户选的壁纸不要改回去。
5. **在电视上按键操作前先截图确认焦点**，尤其是当贝的安装包管理（左侧有「一键清空」）。
6. **不要把 `local/` 提交到版本库**（IP、签名密钥）。
7. 改动系统设置前先记原值进 `local/TVS.md`，确保 `restore.sh` 能还原。
8. **每次改完都在真机上验证**，不要只看编译通过；电视行为和模拟器差别很大。
9. **不要升级 Compose BOM 到 2025.12 及以后**（要求 Android 6.0），不要升级到要求 minSdk 23 的 AndroidX（如 lifecycle 2.10 起）；升级前看 AAR 的 minSdkVersion。
10. **Lite（`lite/`）不要引入 AndroidX、Compose 或协程**，新接口要能在 Android 4.0 跑（lintRelease NewApi）；`use` 不用于 Socket/ServerSocket（4.4 前非 Closeable），避免 `putIfAbsent`、`Map.forEach { k, v -> }` 等 Java 8 方法。

（另 AGENTS.md 层面的硬规则与本调研直接相关：五语 KEYS/TEXTS 不写死文字；只安装 Aerial 自己、不加第三方 App/SDK/shell 通道；不停用系统应用；不删户主数据；未真机验证不得称「已完成」。）

### 7.3 HANDOFF 中与手机页/配对/局域网 API 直接相关的条目

- `:44`（1.55 发布）：改为**运行期间一直监听**；「地址里的令牌固定，仍是唯一的访问凭据」；真机验证「带令牌 200、错令牌 404、二维码地址不变」。
- `:49`：令牌持久保存在 `phone_page` prefs；宽屏（≥900px）侧导航/居中弹窗/悬停态；整页拖放上传；375/1440 两种宽度验证。
- `:47`（#667）：`lanAddress` 排除代理 VPN 地址段 198.18.0.0/15。
- `:65-67`（1.47）：手机页任意 App 改名（`app_names`）、信号源 pin、三个标签重做、整理主屏幕整页编辑器；「开了家长密码时手机上不列出、也不能恢复隐藏的 App……服务端同样拒绝」。
- `:70`（1.46）：6 秒轮询 + state 未变不重建、弹层开着不轮询、tab 记忆滚动。
- `:235`（已知限制）：「手机配置页是局域网 HTTP，只靠随机令牌保护。」
- `:19`（待办）：「5. 手机页增强：调整 App 排序、隐藏 App、屏保等待时间」——官方 Roadmap 已把手机页当控制面扩展的先例。

---

## 附：调研中核对过的否定项（均为「未找到」）

- WebView / WebViewAssetLoader / 手机端 App：未找到。
- Ktor / 其他 HTTP 框架：未找到（仅 NanoHTTPD）。
- mDNS / NsdManager / SSDP / UDP 广播发现：未找到。
- SSE / WebSocket / 长轮询：未找到。
- JSON Schema / OpenAPI 定义文件：未找到（协议即代码）。
- `queryUsageStats` / `queryAndAggregateUsageStats`（按天汇总）：未找到。
- `DevicePolicyManager` / `AppOpsManager.setMode` / `forceStopPackage`（限制执行）：未找到。
- `registerActivityLifecycleCallbacks` / `getRunningTasks`：未找到（前台感知走 Accessibility 事件 + usageEvents）。
- 手机页投屏（AirPlay/DLNA/Miracast）分区：未找到。
- HTTPS / TLS：未找到（HANDOFF:235 明示为局域网 HTTP）。
