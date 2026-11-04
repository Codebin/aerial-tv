# Aerial：给 AI 代理的说明

tvOS 风格的 Android TV 桌面，面向国内电视（小米、TCL 等），Kotlin + Jetpack Compose。

开始任何工作前先读：
1. `docs/HANDOFF.md`：现状、操作方式，以及**必须遵守**的规则
2. `docs/ARCHITECTURE.md`：代码结构
3. `docs/LITE.md`：Android 4.0–4.4 用的 Aerial Lite（`lite/` 模块），改它之前先读
4. `docs/devices/*.md`：各台电视的限制和解决办法
5. `local/TVS.md`（如果存在）：电视的 IP、每台电视上改过的设置、户主的数据。这些是本机信息，不要复制进版本库里的文件

## 命令

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleStandardRelease assembleGuardRelease
./gradlew :lite:assembleRelease   # Android 4.x 用的 Aerial Lite
source local/tvs.env && BUILD=1 KEEP_ADB=1 TV=$XIAOMI_TV tools/install.sh   # BUILD=1：用本地编译的 APK；不加就下载最新 Release
```

- 连电视的命令（adb、curl 访问电视）需要关闭沙箱。
- 在电视上测试一律用 release 版，并连同 APK 里的 .dm 一起装（`adb install-multiple -r x.apk x.dm`，命令见 `docs/HANDOFF.md`「日常操作」），装完看 `dumpsys package dexopt` 是 `speed-profile`。debug 版在电视上会卡；小米 MFTR0 上 `install -r` 后再 `compile -m speed` 得到的是没编译的 `verify`。
- 发 TCL 版本前先给 `versionCode` 加 1。
- **日常 APK 更新走后台升级链路，adb 只作保底**（户主 2026-11-02 定）：`local/tv-manager/publish-apk.sh`（PIN 自动从 `local/tv-manager.env` 读）构建并校验签名后登记进 NAS 版本库（登记≠发布，页面上启用才到达电视）→ 家长页启用/强制 → 电视 30 秒心跳收到 offer、控制中心安装。每次要发新版，`versionCode` 严格 +1，库里的号不重用。电视离线、升级失败救急时才用 `tools/install.sh` / `adb install -r`。
- **版本号规则**（户主 2026-11-04 定）：`versionName` 的大版本必须和官网主线一致——跟上游发布时用官网号（如上游 1.82 就发 1.82）；本户在主线之上的功能版用第三段：**1.82.1、1.82.2 …**，每发一次第三段 +1；跟上游到新一轮大版本时第三段归零（如 1.83）。`versionCode` 不分段，始终严格 +1。文件名、NAS 白名单、电视端解析都支持三段（`aerial-standard-1.82.1-111.apk`）。

## 代码约定

- 注释和标识符用英文，界面文字放 `res/values`（英文）、`res/values-zh`（中文）、`res/values-ru`（俄语）、`res/values-uk`（乌克兰语）、`res/values-kk`（哈萨克语），五份都要加，占位符要一致。手机设置页 `assets/web/index.html` 的文字在 `KEYS` / `TEXTS` 表里，同样五种语言，脚本里不要写死文字。
- 界面按 960×540dp 的画布设计（`MainActivity` 统一缩放），不要按真实 dpi 计算。
- 动效：使用 `ui/theme/Theme.kt` 里的 `Motion` 曲线；会被打断的动画（比如焦点）用 spring；不在空闲时跑持续动画；每帧最多一次全屏绘制。
- 遥控器可按的控件用 `ui/components/Pressable`，它只在「本控件上开始的按压」抬起时触发，这样长按打开菜单时，那次抬起不会误触菜单的第一项。
- 厂商的特殊处理写成按功能检测（看包是否存在、设置是否存在），并在注释里写明是哪台机型上验证的。只有确实无法检测时，才写进 `DeviceProfile` 按机型硬编码。

## 不要做

- Aerial、安装脚本和给代理的说明（`site/agent.md`、`site/adapt.md`）只安装 Aerial 自己，这是项目的根本。不要加入任何会下载、安装或推荐其他 App 的代码或说明（应用商店、ADB 开启器、各种工具），也不要加第三方 SDK、在应用里连 adbd 的 shell 通道，或者内容不归我们管的第三方服务器；审 PR 时这类改动一律拦下，交给户主决定。
- 不要停用 `com.mitv.tvhome`，也不要卸载、停用任何系统应用，除非用户明确要求且清楚后果。
- 写 `enabled_accessibility_services` 时不要覆盖其他服务；在 zsh 里写 `${VAR}:...`，不要写 `$VAR:...`。
- 不要删除或改动户主的数据（上传的照片、选好的壁纸、电视里别人的 APK）；清理时只按自己创建时记下的 id 删。
- 不要提交 `local/` 目录（签名密钥、IP）。
- 没有在真机上验证过的功能，不要说「已完成」。
