---
type: research
status: closed
---

## Question

同传译文朗读应使用 Android 系统 TTS、sherpa-onnx 离线 TTS 还是云端合成？（判据：中文自然度、离线、延迟、包体积）

## Answer

### 决策：sherpa-onnx 内置 TTS（Matcha zh-en 为主），系统 TTS 兜底日/韩

### Matcha 优于 MeloTTS

| | RTF（RPi4 4 线程） | 判断 |
|---|---|---|
| Matcha zh-en | 0.391 | ✅ 手机约 0.2–0.3，有余量 |
| MeloTTS zh_en int8 | 2.518 | ❌ 手机约 0.5–0.8，没余量 |

MeloTTS 自然度更好但实时性不够。体积：Matcha ~93MB（`model-steps-3.onnx` 75.7MB）；MeloTTS int8 ~74MB。

### 能边合成边播（关键能力）

sherpa-onnx Kotlin API 有：
```kotlin
OfflineTts.generateWithCallback(text, sid, speed) { samples -> /* 直接喂 AudioTrack */ }
```
已核对 `Tts.kt` 源码，满足「边出字幕边朗读」。

### 系统 TTS 的硬伤

- `speak()` 是黑盒队列，客户端**拿不到 PCM 流**，无法做时间轴对齐
- 国内 ROM 常被厂商引擎替换，离线语音包需手动下载
- 但它是**日/韩唯一可行方案**（sherpa-onnx 预训练索引里没找到日/韩 TTS 模型）

### 风险

1. 日/韩 TTS 只能靠系统引擎，国行 ROM 可用性需运行时 `isLanguageAvailable()` 探测
2. 「未发现」≠「不存在」，只是官方索引里没有
3. Kokoro v1.1（~420MB）音质好但体积不可接受，只能做成可选下载

### 待实测

- Google TTS 中文离线包在国行 ROM 的实际可用性
- 手机上 Matcha 的真实 RTF
