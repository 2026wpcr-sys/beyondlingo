# 交接文档 · 无界译 AI (BeyondLingo AI)

> **用途**：开启新对话时，把本文档交给 AI 作为上下文，即可无缝接手。
>
> **最后更新**：2026-10-31 · 版本 1.3.0

---

## 一、项目一句话说明

**无界译 AI / BeyondLingo AI** 是一款 Android 原生翻译应用，由 **无界者（BeyondDev）** 开发，
基于开源项目 [STranslate](https://github.com/STranslate/STranslate)（MIT，作者 zggsong）二次开发。

口号：**跨越语言边界，让沟通自由发生。** / *Break Language Barriers. Speak Freely.*

---

## 二、必读的环境信息

| 项 | 值 |
|---|---|
| 项目根目录 | `<工作区>`（本机 checkout 位置，向所有者索取） |
| Android SDK | `<SDK 路径>` |
| JDK | `<JDK 21 路径>` |
| Gradle | 8.14.3（wrapper 已内置） |
| AGP / Kotlin | 8.13.0 / 2.2.20 |
| 编译 SDK | 36（minSdk 26，targetSdk 36） |
| 包名 | `dev.beyonddev.beyondlingo`（debug 为 `.debug`） |
| 模拟器 AVD | `<AVD 路径>`（可选，非必需） |
| 真机 | realme RMX3823 · Android 16 · arm64-v8a |
| GitHub 仓库 | https://github.com/2026wpcr-sys/beyondlingo （Public） |
| Release | https://github.com/2026wpcr-sys/beyondlingo/releases/tag/v1.3.0 |

### 必设环境变量（构建前）

```powershell
$env:JAVA_HOME = "<JDK 21 路径>"
$env:ANDROID_HOME = "<SDK 路径>"
$env:ANDROID_SDK_ROOT = "<SDK 路径>"
```

### 构建命令

```powershell
cd "<工作区>"
.\gradlew.bat assembleDebug        # 61.76 MB
.\gradlew.bat assembleRelease      # 43.43 MB
```

---

## 三、⛔ 关键凭证（丢失即不可逆）

> **⚠️ 本仓库已公开。凭证的实际值一律不写入版本控制。**
> 密码、私钥内容、令牌只保存在本机离线位置；本文档只描述「在哪里找」，
> 不记录「是什么」。新接手的人向仓库所有者索取。
>
> **历史事件（2026-10-31 已处理）**：本文档曾以明文记录签名库密码，
> 仓库转公开后该提交（`c0afa1b`）暴露。已采取的措施：
> ① 重写全部 git 历史并强推，分支上不再包含明文；
> ② 生成全新签名密钥，旧密钥归档为 `beyondlingo-keystore-REVOKED-*` 并标记废弃；
> ③ 旧提交在 GitHub 上仍可能通过直接 SHA 访问（等待平台 GC 或 Support 清理），
>    因此**旧密码永久视为已泄露**。

### 1. 签名密钥库（最高优先级）

| 项 | 值 |
|---|---|
| 备份位置 | 工作区外的 `beyondlingo-keystore-backup\` 目录（路径向所有者索取） |
| 文件名 | `beyondlingo-release.jks` |
| 别名 | `beyondlingo` |
| 密码 | `<离线保存，不写入仓库>`（2026-10-31 已更换，32 位随机） |
| 算法 | RSA 4096，SHA384withRSA |
| 有效期 | 30 年（10950 天） |
| 证书 SHA-256 | `<离线保存，不写入仓库>` |
| 生效版本 | 1.3.0 起 |

**已废弃的旧密钥**：`beyondlingo-keystore-REVOKED-20261031-220359\`
证书指纹 `956abf0f...65a51bb`，仅用于 1.0.0–1.2.0，**严禁再用**。

> **丢失后果**：无法更新已发布的应用，用户必须卸载重装（数据全失），只能换包名重发。
> **必须离线备份到至少两个位置。**
>
> **构建时如何取用**：`keystore.properties`（项目根目录，已 gitignore）中填写，
> `build.gradle.kts` 读取后传给 signingConfig。不要硬编码进源码。

### 2. SSH 私钥

| 项 | 值 |
|---|---|
| 路径 | 用户主目录下的 `.ssh\` 目录（文件名与用户名向所有者索取） |
| 指纹 | `<离线保存，不写入仓库>` |
| 用途 | 向 GitHub 推送代码 |
| 远端 | `git@github.com:2026wpcr-sys/beyondlingo.git` |

> **丢失后果**：无法推送代码，需重新生成并重新添加到 GitHub 账户。

### 3. gh CLI（已装）

```
<本机工具目录>\gh.exe
```

已通过 `gh auth login` 登录 `2026wpcr-sys`，用于创建 Release。
凭据缓存在本机 `%APPDATA%\GitHub CLI\`。

---

## 四、已完成的功能

### 4.1 翻译引擎（2 个）

| 引擎 ID | 名称 | 说明 |
|---|---|---|
| `yandex` | Yandex 翻译 | 默认，免配置直连。协议逆向自 STranslate |
| `custom` | 自定义引擎 | 6 个预设，支持任意 OpenAI 兼容服务 |

**自定义引擎预设**：智谱 GLM / DeepSeek / 硅基流动 / Moonshot / Groq / OpenAI

**可配置项**（`EngineConfig`）：`apiKey` `baseUrl` `model` `systemPrompt` `apiPath` `authHeader` `authPrefix` `extraHeaders` `presetName`

### 4.2 取词方式（3 条通道，全部实测通过）

| 方式 | 适用 | 机制 | 状态 |
|---|---|---|---|
| **悬浮球** ⭐ | 所有 App | 复制 → 点悬浮球 | ✅ 实测通过 |
| **自动划词** | 仅 WhatsApp | `TYPE_VIEW_LONG_CLICKED` | ✅ 实测通过 |
| **OCR 图片取词** | 图片 | 相册选图 / 拍照，ML Kit 离线识别 | ✅ 实测通过 |

另有两条备用通道：
- **PROCESS_TEXT 菜单** — App 端链路正确，但 Android 11+ 包可见性限制导致第三方 App 菜单不显示
- **ACTION_SEND 分享** — 已声明 `SEND` intent-filter

### 4.3 双向自动判定方向（v1.2.0 新增）

用户只声明一次配对关系，之后方向由输入内容自动决定：

```
我的母语    简体中文
对方语言    西班牙语
```

| 输入 | 自动译成 |
|---|---|
| 我明天到你那边 | 西班牙语 |
| Mañana llego a tu casa | 简体中文 |

- 实现：`util/DirectionResolver.kt`
- 开关：设置 → 翻译行为 → 「双向自动判定方向」
- 覆盖三条路径：主界面输入 / 悬浮球与划词 / 静默模式
- 关闭开关时行为与 1.1.0 完全一致
- 主界面会实时显示当前判定方向（`简体中文 → 西班牙语 · 自动判定方向`）

### 4.4 其他功能

- 输入框防抖自动翻译（800ms）
- 静默模式（结果写通知栏，不弹窗）
- 翻译历史（Room，支持搜索/收藏/删除）
- 悬浮窗：字号跟随系统、长文本折叠（160 字符折 6 行）、固定高度防闪烁
- 主题切换（跟随/浅色/深色）
- 品牌化：启动页、关于页品牌卡片、开源声明卡片

---

## 五、🔑 关键技术决策（改代码前必读）

### 5.1 为什么不用剪贴板监听

Android 10+ 禁止非前台应用读剪贴板，**无障碍服务也不例外**：

```
ClipboardService: Denying clipboard access to <pkg>,
application is not in focus nor is it a system service for user 0
```

→ 已移除 `ClipboardWatcher`，改为「悬浮球点击瞬间抢焦点读剪贴板」。

### 5.2 为什么划词有白名单

真机实测（realme / Android 16）：

| App | 选中事件 | 长按事件 | 结论 |
|---|---|---|---|
| Chrome | ❌ 0 次 | ❌ 0 次 | 只派发 `TYPE_WINDOW_CONTENT_CHANGED` |
| 微信 | ❌ 0 次 | ✅ 3 次但 `event.text` 为空 | 取不到文字 |
| WhatsApp | ❌ 0 次 | ✅ 带完整文本 | **可用** |

Chrome/微信自绘渲染，文字选区不通过无障碍暴露。

→ 白名单在 `SelectionAccessibilityService.SELECTION_WHITELIST`，当前只有 `com.whatsapp`。
→ 新增 App 时先实测，确认能拿到文本再加。

### 5.3 悬浮球为什么要「抢焦点」

悬浮球窗口是 `FLAG_NOT_FOCUSABLE`，无焦点时读剪贴板返回空。
→ 点击时临时创建**可聚焦透明窗口**（不加 `NOT_FOCUSABLE`），拿到焦点后读，再立刻移除。
→ 见 `FloatingWindowService.acquireFocusThenRead()`。

### 5.4 悬浮球不能被自动消失逻辑带走

结果窗自动消失时原本调 `stopSelf()`，服务一停球也没了。
→ 统一用 `stopSelfIfNoBubble()`。

### 5.5 源语言交给引擎判断（普通模式）

早期用本地 `LanguageDetector.detect()` 预判源语言，导致西班牙语被误判为中文
（聊天文本里混着「星期二」「10:40」等字符），翻译返回原文。
→ 普通模式下源语言直接传 `Auto` 给引擎，`LanguageDetector` 不参与。

> 注意：v1.2.0 的双语模式是**例外**，见 5.8。那里必须做本地判定，否则无法决定方向。

### 5.6 同语言保护

`TranslateRepository.translate()` 中：源语言与目标语言相同时自动降级为 `Auto`。

### 5.7 ProGuard 必须保留这些

`app/proguard-rules.pro` 中保留了 `vm.**`、`ui.**`、`data.ocr.**`、`com.google.mlkit.**`。
**曾因漏掉导致 release 版 OCR 类被 R8 移除，一进图片取词页就崩。新增包时务必检查。**

### 5.8 双语模式为什么用二分类而非通用检测

双向场景只有两种可能（母语 / 对方语言），做二分类比 33 种语言的通用识别更准。

判定规则（`DirectionResolver`）：

```
母语是中文      → 看汉字占比，≥0.3 判为母语
对方语言是中文  → 取反
双方都不是中文  → 退回 LanguageDetector，检测不出则按「对方语言」处理
```

**关键细节：标点与数字不计入分母。** 这是 5.5 那个坑的正面解法——
「Mañana llego a las 10:40」里数字和标点会稀释汉字占比，
把它们排除后，判定只看真正的字母字符。

阈值 0.3 而非 0.5：允许中英混排（「OK 我明天到」）仍判为中文。
调整阈值只需改 `DirectionResolver.HAN_RATIO_THRESHOLD` 一个常量。

---

## 六、代码结构

```
beyondlingo/
├── app/src/main/java/dev/beyonddev/beyondlingo/
│   ├── BeyondLingoApp.kt          Application + Brand 常量
│   ├── MainActivity.kt            导航 + PROCESS_TEXT/SEND 接收
│   ├── ExternalTextChannel.kt     外部取词文本通道
│   ├── data/
│   │   ├── SettingsStore.kt       DataStore 设置（AppSettings）
│   │   ├── TranslateRepository.kt 仓库层（缓存/持久化/同语言保护）
│   │   ├── db/                    Room: HistoryEntity/Dao/AppDatabase
│   │   ├── engine/                引擎: EngineRegistry/CustomEngine/YandexEngine/Http/TranslateEngine/LangEnum
│   │   └── ocr/OcrEngine.kt       ML Kit 封装
│   ├── service/
│   │   ├── SelectionAccessibilityService.kt  无障碍划词（白名单）
│   │   ├── FloatingWindowService.kt          悬浮窗 + 悬浮球
│   │   └── ClipboardWriter.kt                剪贴板写入
│   ├── ui/
│   │   ├── component/LanguageSelector.kt
│   │   ├── overlay/OverlayContent.kt         译文卡片
│   │   ├── overlay/FloatingBubble.kt         悬浮球
│   │   ├── screen/{Translate,History,Settings,Ocr,Splash}Screen.kt
│   │   └── theme/{Theme,Type}.kt
│   ├── util/{Debouncer,TriggerGate,LanguageDetector,DirectionResolver}.kt
│   │                              DirectionResolver: 双语模式方向判定（二分类）
│   └── vm/{Translate,History,Ocr}ViewModel.kt
├── app/proguard-rules.pro
├── NOTICE.md                      二次开发声明（必读）
├── README.md
├── PROJECT.md                     完整技术设计
├── docs/ADR.md                   架构决策记录
└── dist/                         发布产物（已 gitignore）
```

---

## 七、Git 状态

```
分支：main（与 origin/main 同步）
Tag ：v1.3.0（已推送，Release 已发布）
提交：见下方历史
```

> ⚠️ **2026-10-31 重写过历史**：为清除明文凭证，全部 commit hash 已变更。
> 网上任何旧的 hash 引用都已失效，不要照抄旧记录。

### 提交历史（重写后）

```
6089bd1 docs: 新增交接文档 HANDOVER.md，dist/ 加入 gitignore   ← 根提交
1e58c80 docs: HANDOVER 移除明文凭证，改为离线索取说明
62cfb91 docs: 泛化 SSH 私钥路径描述
20a5620 docs: 本机绝对路径替换为占位符，目录名对齐仓库名
1d092fa docs: PROJECT 中 sdk.dir 示例路径改为占位符
954f00c feat: 双向自动判定方向（v1.2.0）          8 files, +336 -39
2e2f14e docs: 同步 v1.2.0 状态到 HANDOVER 与 README
```

> 说明：仓库的 git 历史从 `c0afa1b`（首次公开前的交接文档提交）开始，
> 更早的 `stranslate-android` 本地开发历史没有推送过。
> v1.0.0 与 v1.1.0 的 tag 指向重写前已推送的旧对象，
> 若需要追溯请以 Release 页面的 APK 与 SHA256 为准。

---

## 八、发版流程

```powershell
# 1. 改版本号
#    app/build.gradle.kts → versionCode +1，versionName 改
#    BeyondLingoApp.kt → Brand.VERSION 同步改

# 2. 构建
cd "<工作区>"
.\gradlew.bat assembleRelease

# 3. 准备发布产物
$hash = (Get-FileHash "app\build\outputs\apk\release\app-release.apk" -Algorithm SHA256).Hash.ToLower()
Copy-Item "app\build\outputs\apk\release\app-release.apk" "dist\BeyondLingo-AI-1.3.0.apk"
"$hash  BeyondLingo-AI-1.3.0.apk" | Out-File "dist\BeyondLingo-AI-1.3.0.apk.sha256" -Encoding ascii

# 4. 提交推送
git add -A
git commit -m "release: 版本号升至 1.3.0"
git push

# 5. 打 tag
git tag -a v1.3.0 -m "无界译 AI 1.3.0"
git push origin v1.3.0

# 6. 发 Release（gh 已在 PATH 时直接用 gh，否则填绝对路径）
$gh = "gh"
& $gh release create v1.3.0 `
  "dist\BeyondLingo-AI-1.3.0.apk" `
  "dist\BeyondLingo-AI-1.3.0.apk.sha256" `
  --title "无界译 AI 1.3.0" `
  --notes-file "dist\RELEASE_NOTES.md" `
  --latest
```

> ⚠️ GitHub 网页上传 43MB APK 会失败，**必须用 gh CLI**。

### 实测过的两个坑

**1. 提交信息带 BOM**

`Out-File -Encoding utf8` 在 PowerShell 5.1 下会写入 BOM，导致 commit subject
开头多一个不可见字符（`﻿feat: ...`）。改用无 BOM 写法：

```powershell
[System.IO.File]::WriteAllText("$PWD\.git\MSG_TMP", $msg, (New-Object System.Text.UTF8Encoding($false)))
git commit -F '.git\MSG_TMP'
```

验证：`$subj = git log -1 --pretty=%s; [int][char]$subj[0]` 应为 102（`f`）。

**2. `git tag -a` 需要 committer 身份**

只在命令行用 `-c user.name=...` 临时传参不够，`git tag -a` 仍会失败：
`Committer identity unknown`。需要落进仓库配置（仅本仓库，不影响全局）：

```powershell
git config user.name "2026wpcr-sys"
git config user.email "2026wpcr-sys@users.noreply.github.com"
```

### 构建前的签名准备

`keystore.properties` 与 `keys/` 均在 `.gitignore` 中，克隆仓库后需要手动放回：

```
项目根目录\keystore.properties      ← 从 beyondlingo-keystore-backup 复制
项目根目录\keys\beyondlingo-release.jks
```

缺失时 release 构建不会报错，而是产出 **unsigned APK**（见 `build.gradle.kts`
的 `hasSigningConfig` 判断）。装到手机上会提示未签名。

验证签名正确：

```powershell
$env:JAVA_HOME = "<JDK 21>"
& "<SDK>\build-tools\36.0.0\apksigner.bat" verify --print-certs app-release.apk
```

证书 SHA-256 应匹配离线保存的值。

---

## 九、⚠️ 已知问题与坑

### 9.1 重装 APK 会解绑无障碍服务

每次 `adb install -r` 后，系统会解绑无障碍服务，`dumpsys accessibility` 的 `Bound services` 变空。
**必须重新启用**：

```powershell
$adbExe = "<SDK 路径>\platform-tools\adb.exe"
& $adbExe shell settings put secure enabled_accessibility_services "null"
& $adbExe shell settings put secure accessibility_enabled 0
Start-Sleep -Seconds 2
& $adbExe shell settings put secure enabled_accessibility_services "dev.beyonddev.beyondlingo.debug/dev.beyonddev.beyondlingo.service.SelectionAccessibilityService"
& $adbExe shell settings put secure accessibility_enabled 1
Start-Sleep -Seconds 5
```

验证：`& $adbExe shell "dumpsys accessibility" | Select-String "Bound services"`

> 注意：改包名后，settings 里的包名要同步改（release 版是 `dev.beyonddev.beyondlingo`）

### 9.2 adb 命令转义陷阱

PowerShell 里 `--es key "value with space"` 会被 shell 重新解析。
→ 用**单个字符串**传给 `adb shell`，或写进 `.sh` 脚本。

### 9.3 截图必须用 cmd 重定向

```powershell
cmd /c "`"$adbExe`" exec-out screencap -p > `"$out.png`""
```

PowerShell 的 `>` 会破坏 PNG 二进制。

### 9.4 logcat 会被系统噪音刷掉

realme/OPPO 的 `systemui` 疯狂刷 `TYPE_WINDOW_CONTENT_CHANGED`。
→ 用 `-s TAG:V` 精确过滤，或持续写入文件（`Start-Process` + `RedirectStandardOutput`）。

### 9.5 真机内存紧张

设备只剩 491MB 空闲时 `adb install` 会失败（`Failure [-99]`）。

### 9.6 ColorOS 后台管控

```
OplusHansManager: cannot transition from R to M, importance=accessibility
```

realme 会限制应用后台状态。悬浮球服务需保持前台通知，用户可能需要在电池设置里加白名单。

### 9.7 Chrome 网页上传 APK 失败

GitHub 网页不支持 43MB 附件，必须用 gh CLI。

### 9.8 换签名后无法覆盖安装（1.3.0 起）

1.3.0 换了签名证书，旧版（1.1.0 / 1.2.0）**不能直接覆盖升级**，会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

必须：
```
卸载旧版 → 安装新版 → 重新授权无障碍/悬浮窗 → 重新配置引擎 API Key
```

卸载会清空翻译历史与设置，这是 Android 的机制，无法绕过。

**验证签名是否为新证书**：
```powershell
$env:JAVA_HOME = "<JDK 21>"
& "<SDK>\build-tools\36.0.0\apksigner.bat" verify --print-certs app-release.apk
# SHA-256 应与离线记录的当前证书一致（1.3.0 起为新证书）
```

**如果忘了换 keystore 就构建**：`validateSigningRelease` 可能显示 `UP-TO-DATE`
（Gradle 缓存），产出的 APK 仍是旧签名。**必须用 apksigner 实际验证证书指纹**，
不能只看构建成功。

---

## 十、待办事项

### 10.1 分发方式 —— ✅ 已定：C（开源）

仓库已于 2026-10-31 转为 Public：https://github.com/2026wpcr-sys/beyondlingo

同时完成了凭证清理：HANDOVER 中的明文密码 / 证书指纹 / 私钥路径已移除，
改为「离线保存，向所有者索取」。

### 10.1.1 凭证泄露事件处理记录（2026-10-31）

**事件**：仓库转 Public 时，`HANDOVER.md` 的初始版本（`c0afa1b`）含明文签名密码、
证书 SHA-256、SSH 指纹。该提交在公开状态下可被访问。

**已执行**：

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | `git filter-repo --replace-text` 重写全部历史 | 7 个 commit 全部重写，hash 变更 |
| 2 | 强推覆盖远端 | `--force --prune origin --all` + `--force --tags` |
| 3 | 本地 `reflog expire` + `gc --prune=now` | 旧对象本地已清除 |
| 4 | 生成新签名密钥（RSA 4096 / 32 位随机密码） | 新证书指纹已离线记录 |
| 5 | 旧密钥归档为 `beyondlingo-keystore-REVOKED-*` | 标记废弃，仅留作旧包验证 |
| 6 | 版本升到 1.3.0（versionCode 4）重新签名发布 | 用户需卸载重装 |

**仍未完成 / 需要注意**：

> ⚠️ **旧提交在 GitHub 上仍可通过直接 SHA 访问。**
> 实测 `GET raw.githubusercontent.com/.../c0afa1b.../HANDOVER.md` 返回 200，
> 仍能读到明文密码。GitHub 只清理不可达对象在内部 GC 时，时间不确定。
>
> **建议**：向 GitHub Support 提交 purging 请求
> （https://support.github.com/contact，类别 Repository → Remove data），
> 附上要清除的 commit SHA。通常 1–3 个工作日处理。
>
> 无论如何，**旧密码永久视为已泄露**，因为密码与 keystore 文件分离
> （`keys/` 从未提交），单独密码不构成伪造能力，但已不具备任何保密价值。

### 10.2 可选增强

- 中文 OCR（`text-recognition-chinese:16.0.1`，+20MB）
- **应用内检查更新（查 GitHub Release）** —— 现在有了公开 Release，可以做了
- TTS 朗读译文
- 悬浮球位置记忆
- 划词白名单扩充（需先实测确认能拿到文本）
- 语言对预设（保存多套「母语 ⇄ 对方语言」，一键切换）

---

## 十一、真实用户反馈（问题已修）

| 反馈 | 根因 | 处理 |
|---|---|---|
| 点翻译闪退 | OkHttp 阻塞调用在主线程 | `withContext(Dispatchers.IO)` 包裹 |
| 智谱显示为「自定义引擎」 | 显示名写死 | 加 `presetName` 字段 |
| 点搜索框就弹翻译 | 选中事件在聚焦/输入时也触发 | 白名单 + 同语言保护 + 事件判据 |
| 长文字时窗口闪烁 | `animateContentSize` + 内容反复清空 | 移除动画 + 固定高度 + 保留旧译文 |
| 悬浮球读完剪贴板是空 | 窗口 `FLAG_NOT_FOCUSABLE` 无焦点 | 临时可聚焦窗口抢焦点 |
| 悬浮球自动消失 | 自动消失逻辑调 `stopSelf()` | `stopSelfIfNoBubble()` |
| 翻译返回原文 | 本地语言检测误判 | 源语言交给引擎判断 |

---

## 十二、注意事项

1. **NOTICE.md 必须保留** — MIT 协议要求保留上游版权声明
2. **品牌信息集中在 `Brand` 对象** — 改名字只改这一处
3. **新增 ProGuard 规则后必须验证 release 版** — 用 `dexdump` 检查关键类
4. **改包名要同步改三处** — `build.gradle.kts`、`AndroidManifest.xml`、无障碍服务 settings 命令
5. **中文不影响稳定性** — 只影响显示层，包名/资源名/标识符仍须英文

---

**交接人**：无界者（BeyondDev）
**文档位置**：仓库根目录 `HANDOVER.md`
