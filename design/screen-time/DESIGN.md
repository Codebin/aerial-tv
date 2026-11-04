# Aerial 屏幕时间 · 手机 H5 后台设计说明

> 状态：设计稿 + 可交互原型（[design/screen-time/index.html](index.html)，内置 mock 数据，手机浏览器直接打开）。
> 本轮只做 H5；TV 原生侧（采集、执行、路由）后期实现，本文 §6 给出接口边界。
>
> **实现状态（2026-10-31，policy v3）**：原则 2「一台电视一份策略」已经落地 —— `data/policy.json`
> 的 `devices[].rules` 各自保存一套 global/groups，页面点顶部的客户端名切换（记在 localStorage），
> 新接入的电视从 `defaultRules` 复制一份起步，改一台不会动到另一台。同时补齐了两件家长必需的操作：
> **后台临时加时不再受 `extendPolicy.max` 限制**（那个上限只约束电视端的申请），
> **「放心全天」可以在后台收回**，今天还可一键重置（清用量 + 收回全部放行）。
> 接口契约见 [CLIENTS.md](CLIENTS.md)。

## 1. 定位与第一性原则

给家里电视做「屏幕时间」：**家长在手机浏览器上，按分组管控电视上各个 App 的使用时长**。

三条设计原则：

1. **策略挂在分组，不挂在 App。** App 只是成员。一个 App 至多属于一个分组；未分组的 App 不受限制（仅统计）。这样电视上装了什么、卸载了什么，规则都不会漂；家长只需要维护 3–5 个组，而不是对着 80 个 App 逐条设限。
2. **管控对象是「电视这块屏幕」，不是某个人。** 一台电视 = 一份策略。Aerial 里没有账号体系，硬造「成员/Profile」只会带来「孩子换个 App 就绕过」的假安全感。真要多孩子，用 §7 的「方案 B：多策略档 + 电视端切换」。
3. **默认宽松，明确越界。** 新装 App 默认不限制（只统计），避免家长一开功能就把家里电视锁死、然后不敢用。真正要管的东西由家长亲手拉进组。

## 2. 与现有手机页的关系

现有页面（`app/src/main/assets/web/index.html`）已经是「TV 起 NanoHTTPD:8790 + 手机扫码 + URL 8 位 token 鉴权 + `GET /api/state` 全量 + `POST /api/*` 表单 + 6 秒轮询」的成熟形态。屏幕时间后台**沿用同一套地基**，不另起服务、不另造鉴权：

| 维度 | 沿用 | 新增 |
|---|---|---|
| 传输 | 同一 PhoneServer、同一 token、同端口 | 新路由前缀 `/api/stime/*`、`/api/usage` |
| 渲染 | `.list/.item/.switch/.sheet/.toast` + CSS 令牌 + 深浅色 | 进度环、限额进度条、7 天柱状、多选态 |
| i18n | `KEYS` / `TEXTS` 索引对齐 + `t()` + `{占位符}` | 新键（生产须补齐 zh/en/ru/uk/kk 五语，原型先 zh/en） |
| 刷新 | 6 秒轮询、`document.hidden`/sheet 打开时暂停 | 用量矩阵**不塞进 `state`**，单独 `GET /api/usage` |
| 鉴权 | URL token 保护只读概览 | **写策略需 PIN 会话**（token 是设备级、可被家人转发，扛不住孩子自己改规则） |

入口：现有多为 `home / looks / more` 三 tab。屏幕时间作为第四个 tab「屏幕时间」加入（`TABS` 增一项 + hash `#stime`），组详情/应用详情用底部 sheet，与既有交互语言一致。原型为便于评审，做成独立单页四 tab。

## 3. 概念与数据模型

### 3.1 三层策略

```
全局 Global ─ 一台电视一份
  ├─ 每日总时长 dailyMinutes { weekday, weekend }        ← 「全局使用时长」
  ├─ 宵禁 bedtime { enabled, from, to, weekdays[] }       ← 「使用时间窗」
  ├─ 到点行为 overflow: home | screensaver | prompt
  ├─ 用眼休息 eyeBreak { enabled, everyMinutes, restMinutes }
  └─ 加时策略 extendPolicy { allowRequest, stepMinutes, maxPerDay }

分组 Group ─ 家长自定义，策略落在组上
  ├─ id, name, color, system?
  ├─ dailyMinutes: number | null（0–1439 分钟任意取值；null = 不限，只受全局约束）
  ├─ bedtimeExempt: bool       ← 宵禁期仍可用（教育类白名单）
  ├─ blocked: bool             ← 整组直接禁用
  ├─ members: [package]        ← 「选择 App 加入管控分组」
  └─ extend: { allowed: bool } ← 是否允许临时加时

例外 Exception ─ 一次性的「临时使用时长」，带到期时间
  └─ id, scope { type: group|global, id }, minutes, createdAt, expiresAt, label

App ─ 只有关联属性，没有独立策略
  └─ package, label（电视上显示名，复用 AppNames）, categoryId
```

**为什么例外要独立成一等对象**：临时加时不能写成「把 `dailyMinutes` 改成 90」——那会静默改掉长期规则，第二天家长忘了改回来。例外带 `expiresAt`，到点自动失效，界面上永远显示「剩余 12 分钟」的倒计时，家长一眼看到自己开过的口子。

### 3.2 用量：只按 App 记，组用量派生

```
UsageDay {
  date: "2026-10-28", tz: "Asia/Shanghai",
  totalMs: 6120000,
  byApp: { "com.cibn.tv": 2700000, ... },      // 唯一需要落盘的聚合事实
  sessions: [ { pkg, start, end } ]            // 原始会话，用于「今天 19:02–19:34 在看酷喵」
}
```

组用量 = `sum(byApp[pkg] for pkg in group.members)`，前端/服务端现算，**不单独存**。
好处：App 换组、改名、删组，历史统计都不会错乱，也不需要回填迁移。

### 3.3 判定优先级（越靠前越硬）

对「前台 App X、当前时刻 T」求值：

1. X 不属于任何组 → **放行**（只统计）
2. 存在生效中的例外（`scope=global` 或 X 所在组）且未过期 → **放行**
3. X 的组 `blocked` → **拦**
4. 宵禁生效 且 组未 `bedtimeExempt` → **拦**
5. 组 `dailyMinutes` 非 null 且组今日已用 ≥ 限额 → **拦**
6. 全局 `dailyMinutes` 今日总用量 ≥ 限额 → **拦**
7. 否则 → **放行**；剩余 ≤ 5 分钟时先发一次「即将到点」提示

拦截动作 = §3.1 的 `overflow`（默认 `home`：`goHome()` 拉回桌面 + 弹「今天的时间用完啦」）。
不用 `DevicePolicyManager.setApplicationBlock` / `forceStopPackage`：前者要把 Aerial 注册成设备管理员（卸载不掉、电视厂商 ROM 行为不可控），后者需要系统签名。Aerial 已有守护服务在拉回桌面这条路上验证过，风险最低，见 §6。

## 4. 信息架构与关键流程

四个 tab（原型即此结构）：

```
今天 Today          全局进度环 · 今日剩余 · 宵禁状态 · 生效中的例外(倒计时)
                    当前正在用(实时) · 各组今日条形 · 近 7 天柱状 · 一键加时
分组 Groups         组卡片列表(限额/已用/成员数/状态徽标)
                    └ 组详情 sheet: 每日限额 · 宵禁豁免 · 整组禁用 · 临时加时 · 成员管理
应用 Apps           全部 App(搜索 + 过滤:未分组/已分组/今日有用) · 多选 → 「加入分组」
                    └ App sheet: 归属组 · 今日用量 · 移出分组 · 重命名
设置 Settings       每日总时长(工作日/周末) · 宵禁时段+星期 · 到点行为 · 用眼休息
                    · 加时策略 · 家长 PIN · 权限状态(PACKAGE_USAGE_STATS) · 清空数据
```

### 4.1 「选择 App 加入管控分组」（主人指定的核心交互）

1. 「应用」tab 右上角「选择」进入多选态；每行左侧变勾选框，底部浮出操作条 `已选 3 项 · 加入分组`。
2. 点「加入分组」→ sheet 列出所有组（带色点 + 成员数）+「＋ 新建分组」。选组即批量 `POST /api/stime/group/members`，乐观更新、toast 回显。
3. 已在别的组的 App 被选中时，操作条上标注 `其中 1 个在「视频」`，确认后**移动**而非复制（一个 App 只在一个组）。
4. 反向入口同样存在：组详情 →「添加应用」→ 同一个多选组件，只列出未分组 + 其他组的 App。
5. 空状态：「应用」tab 若一个都没分组，顶部给一张引导卡「还没有管控分组 · 先建一个『看视频』组，把酷喵、奇异果拉进去」+ 一键按钮（用 3 个常见视频 App 预填）。

### 4.2 临时加时

* 今天 tab 顶部「今天的时间」卡下方一排快捷：`+15` `+30` `+60`（作用于**全局**，即"再看一会儿"）。
* 组详情内 `+15/+30` 只放宽该组（"酷喵今天多看 30 分钟"）。
* 例外即刻出现在今天 tab 的「生效中的例外」列表，倒计时 + 撤销按钮。撤销 = 立即回到原规则。
* `extendPolicy.maxPerDay` 用尽后，快捷按钮置灰并提示「今天的加时次数用完了」——这道闸门本身就是给孩子看的，比口头拒绝省事。

### 4.3 PIN 会话

* 只读（今天、用量）不需要 PIN；任何写策略动作，先过一次 PIN 面板（4 位数字键盘，语义与电视端方向键 PIN 并行，H5 上数字更快）。
* 成功后服务端发**短期 grant**（`grant` + `expiresIn=600`，与电视端解锁 10 分钟语义一致），此后写请求带 `grant` 参数；过期自动重新弹面板。
* 明确写进文档的限制：现有传输是局域网明文 HTTP、token 在 URL 里，grant 只是防「孩子随手点点」，不是防抓包。真要防威胁模型更高的对手，需要 HTTPS + 配对，属独立工程（§8）。

## 5. API 契约

约定与现有一致：表单 `application/x-www-form-urlencoded`；响应 = **完整 stime 状态 JSON**（含 `policy + usage`）+ 结果字段（`saved/added/removed/moved`）；失败 400 `{"error":"..."}`。所有写路由需带 `grant`。

```
GET  /api/stime                       → policy + 派生组用量 + 例外 + 会话态 {locked, expiresIn}
GET  /api/usage?days=7                → { days: [UsageDay], current: {pkg, sinceMs} }   // 与 stime 分开：7 天矩阵比小对象重
POST /api/stime/unlock        pin                                             → {grant, expiresIn} | 403
POST /api/stime/lock

# 分组
POST /api/stime/group/add     name, color                                     → {id}
POST /api/stime/group/update  id, name?, color?, minutes?, exempt?, blocked?, extendAllowed?
                                                   # minutes = 分钟数（1 小时 20 分 → 80），字符串 null 表示不限
POST /api/stime/group/del     id                                              // 成员回落为未分组，历史统计不动
POST /api/stime/group/members id, pkgs[], mode=move|copy                      // 移入；copy 仅用于将来多组

# 全局
POST /api/stime/daily         scope=weekday|weekend, minutes   # 同上：任意分钟数或 null
POST /api/stime/bedtime       on, from=HH:MM, to=HH:MM, weekdays=1,2,3,4,5     // 1=周一
POST /api/stime/overflow      action=home|screensaver|prompt
POST /api/stime/break         on, every, rest
POST /api/stime/extendpolicy  on, step, max

# 例外（临时使用时长）
POST /api/stime/extend        scope=global|group, id?, minutes, note?
POST /api/stime/extend/cancel id
```

`GET /api/stime` 响应骨架：

```json
{
  "locked": false, "grantExpiresIn": 540,
  "global": { "dailyMinutes": {"weekday": 120, "weekend": 240},
              "bedtime": {"enabled": true, "from": "21:30", "to": "06:30", "weekdays": [1,2,3,4,5,6,7]},
              "inBedtime": false, "overflow": "home",
              "eyeBreak": {"enabled": true, "every": 30, "rest": 10},
              "extendPolicy": {"allowRequest": true, "step": 15, "max": 2} },
  "groups": [ { "id": "g_video", "name": "看视频", "color": "#0a84ff", "dailyMinutes": 60,
                "bedtimeExempt": false, "blocked": false, "members": ["com.cibn.tv"],
                "usedMs": 2700000 } ],
  "un grouped": { "usedMs": 340000, "count": 41 },
  "exceptions": [ { "id": "e1", "scope": "global", "minutes": 30, "expiresAt": 1761631200000 } ],
  "usage": { "date": "2026-10-28", "totalMs": 6120000,
             "byApp": {"com.cibn.tv": 2700000}, "current": {"pkg": "com.cibn.tv", "since": 1761620000000} },
  "apps": [ { "pkg": "com.cibn.tv", "label": "CIBN酷喵", "groupId": "g_video", "usedMs": 2700000 } ],
  "ready": { "usageStats": true, "serviceRunning": true }
}
```

轮询：今天 tab 30 秒刷一次用量（前台在变），分组/设置不轮询、写完即返回新状态，避免电视端每秒 `queryEvents` 变成手机高频拉取。

## 6. TV 原生侧缺口（后期实现清单）

| # | 缺口 | 现状 | 做法 |
|---|---|---|---|
| 1 | 按天/按 App 聚合 | `queryUsageStats` 全仓库零调用；`queryEvents` 只回看 10s 用完即弃 | 新建 `UsageStore`：复用 `HomeGuardService` 已有 1s 前台轮询做**会话配对**（`MOVE_TO_FOREGROUND`/`BACKGROUND`），`sessions` 落 `files/usage/<date>.json`；30 天滚动删除；只在会话结束 + 每 60s flush，避免频繁写盘 |
| 2 | 跨天/时区 | 无 | 以设备本地日切；`sessions` 存 epoch ms，读取时按 `date` 归并；夏令时不管（国内无） |
| 3 | 限制执行 | 无 `DevicePolicyManager`/`setMode`/`forceStopPackage` | 用守护已有的 `goHome()` 拉回 + 磁贴置灰 + 到点提示；**不**注册设备管理员（见 §3.3）；被拦 App 若 3 秒内被再次拉起则退避，防抖动 |
| 4 | 新装 App 归属 | 已有 `PACKAGE_ADDED/REMOVED/CHANGED` 广播 | 落一个 `unassigned` 提醒（手机页红点），默认不管控；卸载/更新**不动** `groupId` 与历史用量 |
| 5 | 存储 | 全 `SharedPreferences`，无 DataStore/Room | `stime_policy`（JSON 一个键）沿用现风格；用量按天分文件（见 #1）；写走 `runBlocking(Dispatchers.Main)`，与 `AerialSettings` 主线程写约定一致 |
| 6 | 会话鉴权 | 只有 URL token，PIN 只在电视端校验 | 服务端 `AerialSettings.unlock(pin)` 复用 + 发 grant（内存 Map + 过期），写路由统一 `requireGrant()` |
| 7 | 授权引导 | `usageStatsAllowed()` 已有，经 ADB 授予 | 未授权时今天 tab 顶部横幅「需要在电视上开启使用情况访问权限」+ 深链到电视设置页；不假装能统计 |
| 8 | 路由挂载 | `serve()` 硬编码 when 链 | 新前缀集中在 `ScreenTimeHandler.kt`，`serve()` 只加一行分派，避免 that 文件继续膨胀 |

## 7. 已知的取舍与备选方案

* **为什么不做多成员/Profile**：见 §1 原则 2。将来要，方案 B 成本最低：`policy.profiles: [{id,name,policyRef}]` + 电视端「切换使用者」（PIN 保护），手机页顶部加一个 profile 切换器。当前 schema 已留 `version`，可平滑迁移。
* **为什么一个 App 只属于一个组**：多组会引入「取最严还是取最宽」的长期争论，且家长实际不需要同一个 App 有两条规则。`group/members` 留 `mode=copy` 与 `version` 字段，将来支持多组不必改路由名。
* **HDMI / 投屏是漏口**：孩子插个 U 盘或手机投屏就绕开了整个体系。原型在设置里放一条「同时管控信号源与投屏」的开关（默认开），实现上把 `hdmi_*`/`cast` 视作两个虚拟 App 归入「其他」组——这是这套模型白送的能力，值得做，但要真机验证各家 ROM 的 HDMI 切换行为。
* **统计精度**：电视上部分 App 会后台播（音乐 App 退到桌面仍响），按前台事件算会低估。v1 接受，界面上写「按前台使用时间统计」。

## 8. 安全边界（写给下一个读代码的人）

现状与原型都不改变这一条：**明文 HTTP + URL 内 8 位 token**，家庭局域网可用，但有陌生人共存网络（民宿、公司、邻居蹭网）时不足以保护。PIN/grant 只提高误操作门槛。若要做成公开发布的功能，前置条件是 HTTPS（自签 + 指纹确认，或配对码方案），这是独立一项工程，别顺手加在屏幕时间里。

## 9. 原型说明

[index.html](index.html) 是自包含单文件（约 880 行，无依赖、无构建）：内置 mock（5 个组、22 个 App、7 天用量、前台会话每秒推进），交互全部可点通 —— 切 tab、多选入组、组详情改限额、整组禁用、宵禁时段与星期、临时加时与收回、PIN 面板与 10 分钟会话、深浅色、中英切换。文件内 `api()` 是 §5 各路由的替身，接真实后端时把它换成 `fetch`，其余渲染层不用动。

演示参数（写在 URL 上，方便截图与评审）：

| 参数 | 作用 |
|---|---|
| `?lock=0` | 以已解锁状态打开，跳过 PIN |
| `?ex=30` | 预置一条 30 分钟的全机临时时长，看倒计时与收回 |
| `?lang=en` | 强制英文（`zh`/`en`） |
| `#groups` | 直达某个 tab：`today` / `groups` / `apps` / `settings` |

已验证：46 项交互回归全绿（Chrome headless，390×844），覆盖锁定态写入被拦截、PIN 解锁后重放写入、整组禁用开关往返、按组与全机临时时长创建与收回、多选 4 个 App 批量入组、单 App 入组与移出、宵禁星期增删、到点行为切换、加时步长步进、手动锁定、中英切换；时长选择器另测「60 分回显为 1 小时」「分钟箭头步进 5」「键入 1 小时 20 分 → 存 80」「0 小时 0 分提示一整天打不开」「不限开关置灰字段并存 null」「关掉不限后回到键入值」「选择器重开时按存储值回填」。

### 4.4 时长怎么填：小时 + 分钟，不给档位

限额不用 15/30/45/60 这种固定档位，否则「1 小时 20 分」「2 小时 45 分」这类真正想要的数根本设不出来。底部弹层里是一个**时长输入器**：

```
        1 小时 20 分            ← 实时预览，就是最终写进规则的数
     [−] [ 1 ] [+]   [−] [ 20 ] [+]
        小时              分钟
   ┌────────────────────────────────┐
   │ 不限时长                    (○) │  ← 开关一开，两列字段置灰
   └────────────────────────────────┘        [        保存        ]
```

* 小时 0–23，分钟 0–59；**分钟按钮每次 ±5，长按连跑**（设 1 小时要按 60 下是不能接受的），也可以直接在框里键入任意数，22 分这种非 5 倍数照样填得进。
* 触到边界自动禁用对应的 −/＋，不弹错误。
* 0 小时 0 分不等于「不限」：预览明确写「一整天都打不开」，两种意图不能混。
* 必须点**保存**才落库：调节过程中不发请求，既省电视端写入，也让家长能改到一半反悔。
* 组的每日限额、全局工作日/周末总时长共用这一个控件；到点后的临时加时仍保留 15/30/60 快捷按钮，因为那是「通融一下」，不是「定规则」。深浅色与五个界面截图见 [shots/](shots/)。

原型阶段踩到并修掉的五个实现坑，记录以免合并时回退：`setHours()` 收到的是「距零点分钟数」而非小时，宵禁倒计时算成了 53 天后；底部弹层的关闭动画用 `setTimeout` 清 `innerHTML`，与「关闭后立刻再开一个」竞态，改用递增令牌判定；关闭弹层时清空上下文，使限额选择器的回调拿到空组 id 而**静默丢弃写入**（连 PIN 都不弹），现改为打开新弹层时清陈旧回调；`openSheet()` 自己会清上下文，所以选择器必须在打开之后再登记状态，否则保存时读到 null；长按连跑的 `setInterval` 在弹层被程序关闭后仍在刷（真实手指抬起才会停），改由 `closeSheet()` 统一叫停，并在同步函数里加空值守卫。

> 文案已按 `KEYS`/`TEXTS` 索引对齐结构组织（162 键，中英文占位符逐条校验一致），生产化时须补 ru/uk/kk 三语（AGENTS.md 硬性要求）。

## 10. 下一步

1. 主人过原型 → 定 tab 划分与组/例外交互是否符合预期（成本最低的改动点在这里）。
2. 定 §6 的 #1（采集落盘）与 #6（grant）两个技术选型。
3. 原生实现顺序：`UsageStore` 聚合 → `/api/usage`（先只读、能看图）→ `/api/stime` 读写 + grant → 执行器 `goHome` → 真机（小米 + TCL 各一台）验证后才可声称完成。
4. 并入生产页：`TABS` 加 `#stime`、文案五语补齐、`ARCHITECTURE.md` 手机页节同步修正（该节现已滞后于代码）。
