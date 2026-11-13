---
type: grilling
status: closed
---

## Question

视频翻译的输出应是外挂字幕文件、烧录进画面，还是仅导出文本？

## Answer（2026-11-12 用户拍板）

**App 内播放带双语字幕 + 导出 .srt，不做烧录。**

- 视频不进重编码管线：原文件只读，字幕作为 side-loaded SRT 挂到 Media3 ExoPlayer
- 导出走 SAF `CreateDocument`，MIME `application/x-subrip`
- **不做硬字幕烧录**（整片重编码，10 分钟视频要数分钟且明显发热，收益不匹配成本）

### 连带影响

1. 不需要处理视频编码/字幕排版/字体嵌入，**省掉整个重编码分支**
2. FFmpeg 只用于「视频 → 16k 单声道 wav」，不碰视频流
3. 双语 SRT 采用「原文行 + 译文行」合并格式，标准渲染天然两行显示
4. 用户若要分享带字幕的成片，需自行用第三方工具烧录——UI 里说明即可
