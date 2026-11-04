# 客户端（每台电视一套规则）+ 家长加时/重置 — 接口契约

改版目标（户主要求，2026-10-31）：

1. **客户端概念**：后台要能管多台电视。每台电视有自己的分组、限额、宵禁、放行。
   现在的实现是一套规则全家共享，第二台电视接进来之后只能跟着第一台走，等于管不了。
2. **后台能临时加时间、能重置**：家长在后台加的时长不受「每天最多 N 次」限制；
   「放心全天」（电视上输密码放行到半夜）必须能在后台一键收回；今天用量要能一键清零。

样式不动（继续 iOS 风格）。只改功能。

---

## 1. 数据模型

`policy.json` 顶层：

```jsonc
{
  "version": 3,
  "active": "demo",              // 兼容字段：最近一次访问的客户端，前端显式传 device 时不依赖它
  "pin": "1234",
  "defaultRules": { "global": {...}, "groups": [...] },   // 新建客户端时复制的模板
  "devices": [
    {
      "id": "demo", "name": "小米电视 EA55", "model": "...", "kind": "demo",
      "addedAt": 0, "lastSeen": 0, "secret": "...",
      "current": { "pkg": "...", "since": 0 },
      "appsAt": 0, "appsWantedAt": 0,
      "rules": { "global": {...}, "groups": [...], "extendUsed": 0, "extendUsedDay": "2026-10-31" }
    }
  ],
  "exceptions": [ { "id": "e...", "scope": "global|group", "gid": "...", "device": "tv-x",
                   "minutes": 30, "by": "parent|tv-pin", "kind": "timed|rest-of-day",
                   "createdAt": 0, "expiresAt": 0 } ],
  "events": [...]
}
```

- `rules.global` 与 `rules.groups` 的字段形状和现在**完全一样**（`dailyMinutes` / `bedtime` /
  `overflow` / `eyeBreak` / `extendPolicy`；group 的 `id,name,color,dailyMinutes,bedtimeExempt,
  blocked,extendAllowed,members`）。前端不用改字段名。
- `exceptions` 仍然顶层一份，但每条都带 `device`（现在就有）。**例外只对所属客户端生效**，
  只包含 `device == 该客户端` 的条目；没有 `device` 的历史条目视为对所有客户端生效（迁移期兼容）。
- `extendUsed` / `extendUsedDay` 移到**每客户端**，含义改为「电视上输密码申请的加时次数」，
  家长在后台手动加时不计入。

### 迁移（进程启动时一次，写回 policy.json）

- 顶层已有 `global` / `groups` 时：复制成 `defaultRules`，并把 `global`/`groups` 从顶层删掉。
- 每台 `devices[i]` 缺 `rules` 时：深拷贝 `defaultRules` 补上（已有客户端立刻就有了自己的一份）。
- 顶层 `extendUsed` / `extendUsedDay` 若有，塞给 `demo`（或第一台）后删除顶层字段。
- `version` 升到 3。

之后所有规则读写都走 `rules_of(did)`，**不再读写顶层 global/groups**。

## 2. 读接口

### `GET /api/stime?grant=<g>&device=<id>`（现有，语义扩展）

- `device` 缺省 = `policy.active`。
- 返回体字段名一律不变，但 `global` / `groups` / `exceptions` / `extendUsed` 都是**该客户端**的。
- 新增两个字段（前端需要）：
  - `device.id` 已有；`devices[]` 每项新增 `todayMs`（该机今天用量，含进行中的会话）。
  - 顶层新增 `defaultDeviceId`：`policy.active`，前端首次打开时用它。

> 现有 `state()` 已经返回 `devices[]` / `device` / `hasRealDevice`，保持。

### 家长密码：后台一份，电视跟随

`/api/tv/policy` 的回复里多一个 `pin` 对象（**只给电视，`/api/stime` 不返回**）：

```jsonc
"pin": { "set": true, "hash": "<sha256('aerial:' + pin) 的十六进制>" }
```

- 只下发哈希，不下发明文：电视端 `AerialSettings.adoptPinHash` 比对后写本地，
  和它自己存的 `pin_hash` 同一格式（`sha256("aerial:" + pin)`）。
- `hash` 为空或形状不对时电视**保持原样**：后台没密码不等于让电视把设置页敞开。
- 电视端在 `pullRulesIfStale()` 里处理，并把 `pin` 从要缓存的 rules 里摘掉
  （缓存不该带锁的钥匙）；`/api/tv/daypass` 的回复同理。
- 后果：**后台改密码，电视 30 秒内跟着改**；电视上单独改会被下一轮心跳覆盖。

### 其它读接口

- `GET /api/usage?device=`：该客户端。
- `GET /api/tv/policy`（TV 侧）：只下发该 TV 自己的规则。

## 3. 写接口（全部走家长 PIN，和现在一样）

所有写路由接受一个可选 `device` 字段，**缺省 = `policy.active`**。前端会显式传当前客户端。

| 路由 | 变化 |
|---|---|
| `/api/stime/daily` | 写该客户端 `rules.global.dailyMinutes` |
| `/api/stime/bedtime`、`/bedtime/days` | 写该客户端 `rules.global.bedtime` |
| `/api/stime/overflow`、`/break`、`/extendpolicy` | 写该客户端 `rules.global.*` |
| `/api/stime/group/add`、`/update`、`/del`、`/members`、`/app/leave` | 写该客户端 `rules.groups` |
| `/api/stime/device` | 兼容保留：设置 `policy.active`，返回 `{active}` |
| `/api/stime/device/del` | 现有，不变 |
| `/api/stime/apps/refresh` | 现有，不变（按 `id`/`device`） |

### 新增：`/api/stime/device/rename`

```
POST device/rename  { id, name }   ->  { renamed: "<id>", name: "<new>" }
```

- `name` 去掉首尾空白、最长 40 字符，空则报错 `{"error": "name required"}`。
- `demo` 也允许改名（页面占位设备）。不存在的 `id` → `{"error": "no such device"}`。

### 改：`/api/stime/extend` — 家长加时不受次数限制

```
POST extend  { minutes, scope: "global"|"group", id?: <gid>, device? }
```

- 仍然追加一条 `exceptions`，`by: "parent"`、`kind: "timed"`、`device: <该客户端>`、`expiresAt = now + minutes*60000`。
- **删掉** `extendUsed >= extendPolicy.max` 的拒绝逻辑：那个上限是给电视上孩子申请用的。
- `minutes` 必须 1..1440（`pos_int` 之外再夹一下），否则 `{"error": "minutes must be 1..1440"}`。
- 不改 `extendUsed`。

### 新增：`/api/stime/exceptions/revoke` — 收回生效中的放行

```
POST exceptions/revoke  { device?, id? }  ->  { revoked: n }
```

- 给 `id`：只删那一条（不存在也返回 `revoked: 0`，不报错）。
- 不给 `id`：删掉该客户端**所有**还在生效的例外（含 `by: "tv-pin"` 的「放心全天」）。
- 每次收回写一条 `note`：`家长收回了「放心全天」/ 临时时长`（沿用现有 `revoke` 文案风格）。

### 新增：`/api/stime/recover` — 重置今天（一键回收）

```
POST recover  { device? }  ->  { cleared: true, revoked: n }
```

- 干两件事、一次落盘：清空该客户端**今天**的用量文件（等价 `/api/stime/reset`）
  + 收回该客户端所有生效例外（等价 `exceptions/revoke` 不带 id）。
- 写一条 `note`：`家长重置了今天的用量与放行`。
- 这就是户主说的「不小心放心全天后要能回收」。

### `/api/stime/reset`（保留）

- 仍然只清今天用量（设置页老入口，保留不删）。

## 4. 前端要点（给改页面的那个人）

- **当前客户端**存 `localStorage["aerial-stime-device"]`，缺省用 `state.defaultDeviceId`；
  如果存的 id 已不存在则回落到 `defaultDeviceId`。
- 所有 `api()` / `fetchState()` 请求都带上 `device=<当前客户端 id>`。
- header 的客户端名可点：弹出底部 sheet，列出 `M.devices`，每行显示名字、在线点、今天用量，
  当前项打勾；点一行就切换（写 localStorage + `fetchState()` + `render()`）。
- 设置页底部加「客户端」区块：重命名、移除（demo 不可移除）、显示接入令牌是否开启
  （`state.ready.enrolment`）。
- 今天页：
  - 加时按钮保留 `+step / +2step / +4step`，**不再因为 `extendUsed >= max` 置灰**
    （家长加时不受次数限制）；底部那行文案改成「电视上申请过 N 次」（不是配额）。
  - 「当前生效」卡片：每条例外都要有「收回」；`by === "tv-pin"` 的显示成「放心全天」。
  - 新增「重置今天」按钮：`ask()` 确认文案要说清「清空今天的用量，并收回所有放行」，
    确认后调 `stime/recover`。
- 新文案同时补中文和英文两列（页面的 KEYS 表是 `["key","中文","English"]`）。

## 5. 验收（必须实测）

- `python3 local/tv-manager/test_api.py` 全绿（会补上多客户端用例）。
- 两台客户端：A 改限额不影响 B；A 的加时不显示在 B；A 的「放心全天」只在 A 能收回，
  收回后 A 立即恢复限制。
- 部署到 fn33 后：手机页切换客户端、加时、重置今天都要真点一遍。
