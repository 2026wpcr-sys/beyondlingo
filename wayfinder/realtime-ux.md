---
type: grilling
status: closed
---

## Question

同传运行时的界面形态应该是全屏字幕、悬浮窗、还是分屏对照？

## Answer（2026-11-12 用户拍板）

**全屏双语字幕（默认）+ 悬浮窗（可切换）双模式。**

- 全屏页：大字滚动双语字幕，原文与译文同屏对照
- 悬浮窗：一键切到系统悬浮窗，可边看视频/开会/打游戏边看译
- **复用 `beyondlingo` 已有的 `FloatingWindowService`**（22.5KB），不是从零写

### 连带影响

1. 需要 `SYSTEM_ALERT_WINDOW` 权限 + 前台服务（已有 `FOREGROUND_SERVICE` 基础）
2. Android 14+ 必须声明 `foregroundServiceType`（麦克风场景用 `microphone`）
3. 悬浮窗与全屏页共用同一份字幕流，UI 层做双渲染，**同传引擎只跑一份**（不能因为切模式就重启 ASR）
4. 切换模式时 ASR 会话必须无感延续——这是实现上最容易出错的地方
5. 双语字幕的滚动策略（逐句替换 vs 累积滚动）仍未定，见 Not yet specified
