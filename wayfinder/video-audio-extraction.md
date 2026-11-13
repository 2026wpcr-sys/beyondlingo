---
type: research
status: closed
---

## Question

本地视频翻译如何抽音频、如何生成并挂载双语字幕？（判据：FFmpegKit 依赖体积、字幕轨 vs 外挂、播放器选择、Android 16 兼容）

## Answer

### 关键前提：FFmpegKit 已退役，但有现成替代（observed）

- Arthenica 于 **2025-01-06 正式退役** FFmpegKit（作者 Taner Sener 公开信），2025-04 后二进制从仓库下架
- **替代 1（推荐）**：`dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` —— Maven Central 有，**drop-in replacement**，API 包名仍是 `com.arthenica.ffmpegkit`，只改 groupId
- **替代 2**：FFmpegKitNext —— 2026-07 由原作者推出的官方延续版本
- 参考：https://central.sonatype.com/artifact/dev.ffmpegkit-maintained/ffmpeg-kit-full/8.1.7

⚠️ 注意 `ffmpeg-kit-full` 体积很大（全 codec 变体），应改用 `min` / `lts` / `audio` 等精简变体，或自编译。**具体变体体积待实测**。

### 抽音频方案

用 FFmpeg 把视频转成 ASR 需要的格式：
```
-i input.mp4 -vn -ac 1 -ar 16000 -f wav output.wav
```
16kHz / 单声道 / 16bit PCM，与 sherpa-onnx 输入要求一致。

**替代路径（不引 FFmpeg）**：`MediaExtractor` + `MediaCodec` 直接解出 PCM。零依赖、零体积，但只支持系统解码器覆盖的容器/编码，且要自己处理音轨选择与重采样。

### 播放与字幕

- **播放器**：Media3 ExoPlayer（`androidx.media3:media3-exoplayer`），原生支持 side-loaded 字幕
- **挂载方式**：`MediaItem.SubtitleConfiguration`（id / uri / mimeType / language / label / selectionFlags / roleFlags）挂本地 SRT
- **双语实现（关键洞察）**：ExoPlayer 一次只渲染**一条**选中字幕轨，没有"双轨同显"。最省事且可靠的做法是**把双语合并进同一个 SRT**：

```
1
00:00:01,000 --> 00:00:03,000
Hello world
你好世界
```

标准字幕渲染天然显示两行，**零自定义渲染代码**。

备选：用 Compose overlay 自己按播放位置绘制（灵活但要多写一套时间轴同步逻辑）。

### 三种输出形态的成本（inferred）

| 形态 | 开发量 | 运行时 | 说明 |
|---|---|---|---|
| App 内看带字幕 | 低 | 零额外 | 只挂 SRT，不碰视频数据 |
| 导出 .srt | 低 | 零额外 | 走 SAF CreateDocument |
| **烧录进画面** | 高 | **整片重编码**，10 分钟视频可能数分钟 + 明显发热耗电 | 必须重新编码视频流，且要处理字幕排版/字体 |

→ 默认做前两种，烧录作为可选且要明确警告耗时。

### 风险

1. `ffmpeg-kit-full` 体积可能让 APK 膨胀数十 MB → 必须选精简变体并实测
2. FFmpegKit 社区 fork 的长期维护性未知（原作者已退出一次）
3. Android 16 的 16KB page size 兼容性需验证 native 库
4. MediaExtractor 路线在部分厂商 ROM 上解码器支持不全

### 待实测

- 各 ffmpeg-kit 变体的实际 APK 增量
- `dev.ffmpegkit-maintained` 8.1.7 在 Android 16 / 16KB page 下的可用性
- 双语合并 SRT 在中文字体、长句换行下的实际显示效果
