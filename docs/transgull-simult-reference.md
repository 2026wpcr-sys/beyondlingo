# 翻译鸥同传链路拆解（供 BeyondLingo 参考）

> 来源：`transgull-re/extracted/com.transgull.translator.apk` 静态分析（DEX 字符串与类名）。
> 标注：**observed** = 直接从 APK 读到；**inferred** = 由命名与结构推断，未经动态验证。

## 1. 它的技术栈（observed）

| 环节 | 供应商 | 依据 |
|---|---|---|
| ASR | **Soniox** 实时 WebSocket | `wss://stt-rt.soniox.com/transcribe-websocket`、`com.soniox.SonioxClient` |
| TTS | **Azure** Cognitive Services | `libMicrosoft.CognitiveServices.Speech.*.so`、`mstts:ttsembedding` |
| 实时广播 | **Supabase Realtime** + **LiveKit** | `/realtime/v1/websocket`、`liblivekit_uniffi.so` |
| 配置下发 | **RemoteConfigManager** | `com.soniox.RemoteConfigManager`、`RemoteConfigRuntime` |

注意：**ASR 和翻译都在云端**，客户端只负责采集、传输、渲染。这是它敢做「边听边出字幕」的前提——服务端替它扛了流式解码。

## 2. 核心机制：字幕状态机（observed）

它没有「识别出文本就刷 UI」，而是维护一套带版本号的状态：

```
Soniox token 流
   ↓  is_final=false  →  partial（临时字幕，可被覆盖）
   ↓  is_final=true   →  final 段
   ↓
emitPendingFinalSegment  ← 关键：final 不立即上屏，先入 pending 队列
   ↓  （等翻译结果回来）
一起上屏：原文 + 译文同时出现
```

### 关键字段（全部 observed）

| 字段 | 作用（inferred） |
|---|---|
| `is_final` / `isFinal` | token 级最终标记，区分 partial 与 final |
| `segment_id` / `lk.segment_id` | 段标识，用于归并 |
| `utteranceId` / `UtteranceId:` | 话语标识，一次连续说话一个 ID |
| `subtitleRevision` | **字幕版本号**，用于判断是否需要重绘 |
| `subtitleOrder` | 字幕显示顺序 |
| `cursorSegmentId` | **游标**，处理乱序/重传，保证不重复不丢段 |
| `final_published_revision` | 已发布到 UI 的最终版本号 |
| `final_audio_proc_ms` | 音频处理耗时（性能观测） |
| `TranslationStatus` / `translationStatus` | 翻译状态（进行中/完成/失败） |

### 两个容易被忽略的设计（observed）

1. **`emitPendingFinalSegment`** —— final 段不直接上屏。因为 ASR 出 final 时翻译还没回来，如果先显示原文，用户会看到「原文先冒出来、译文几百毫秒后补上」的割裂感。它选择**等翻译一起出**。
2. **`$debounceJob` + `mUpdateThrottle`** —— 双层节流。debounce 吸收抖动（partial 高频更新），throttle 限制 UI 刷新频率（避免每帧重绘）。

## 3. 端点检测是可调档的（observed）

```
enableEndpointDetection=
endpointDetection=
endpointSensitivity=          ← 灵敏度
endpointLatencyAdjustmentLevel= ← 延迟调整档位
Conversation_Initial_Silence_Timeout
EndSilenceTimeout / InitialSilenceTimeout
```

它**不硬编码**静音阈值，而是按场景调档。配合 `RemoteConfigManager`，可以在不发版的情况下改参数——这是运营层的能力，很实用。

## 4. 我们（sherpa-onnx 离线）的映射

| 翻译鸥 | 我们 | 差异与应对 |
|---|---|---|
| Soniox token 级 `is_final` | sherpa-onnx **无 token 级 final** | 用 `recognizer.isEndpoint(stream)` 判定端点；partial 用 `getResult(stream).text` 实时读 |
| 服务端流式解码 | 本地流式解码（RTF 0.018，余量 55×） | 我们算力富余，可以做更激进的 partial 刷新 |
| `subtitleRevision` | 需自建 | 每次 partial/final 递增 revision，UI 按 revision 丢弃过期更新 |
| `cursorSegmentId` | 需自建 | 用自增 segmentId + 只接受 `== cursor` 的段，防乱序 |
| `emitPendingFinalSegment` | **需自建，且是关键** | final 到达后入 pending 队列，等翻译返回再与译文一起上屏 |
| `debounceJob` + `mUpdateThrottle` | 需自建 | partial 用 ~120ms debounce；UI 刷新用 ~60fps throttle |
| 端点灵敏度可调 | `OnlineRecognizerConfig` 的 endpoint 规则 | sherpa-onnx 支持 `rule1/rule2/rule3` 阈值，可暴露为「字幕灵敏度」设置 |

## 5. 落地建议（inferred，伪代码级）

```
状态：
  cursorSegmentId: Int
  pendingFinal: MutableList<Segment>   // 等翻译的 final 段
  partialText: String                   // 当前临时字幕
  revision: Long

onAudioChunk(pcm):
  stream.acceptWaveform(pcm)
  while recognizer.isReady(stream): recognizer.decode(stream)

  // 1) partial：高频但防抖
  if (++tick % PARTIAL_DEBOUNCE_TICKS == 0):
      val t = recognizer.getResult(stream).text
      if (t != partialText):
          partialText = t
          emitUi(Partial(t, revision++))

  // 2) 端点：对应翻译鸥的 final
  if (recognizer.isEndpoint(stream)):
      val finalText = recognizer.getResult(stream).text
      if (finalText.isNotBlank()):
          val seg = Segment(id = cursorSegmentId++, original = finalText)
          pendingFinal += seg
          launchTranslate(seg)          // 异步翻译
      recognizer.reset(stream)
      partialText = ""
      emitUi(ClearPartial(revision++))

onTranslateDone(seg, translated):
  // 3) 原文+译文一起上屏（对应 emitPendingFinalSegment 的意图）
  val idx = pendingFinal.indexOfFirst { it.id == seg.id }
  if (idx >= 0 && seg.id == publishedCursor):
      pendingFinal.removeAt(idx)
      emitUi(Final(seg.original, translated, revision++))
      publishedCursor++
  else:
      // 乱序到达：留在 pending，等前序段先上屏
      pendingFinal[idx] = seg.withTranslation(translated)
```

## 6. 可直接复用的三条经验

1. **原文和译文必须同时上屏**。先出原文再补译文，观感会差很多。这是翻译鸥用 `emitPendingFinalSegment` 解决的。
2. **partial 一定要防抖**，否则字幕会疯狂抖动重排。翻译鸥用 `debounceJob` 单独处理。
3. **端点阈值要做成可调**，而不是写死 1.2 秒。不同场景（会议/看剧/嘈杂环境）最优值差很远。

## 7. 我们比它强的一点

翻译鸥把音频送云端（火山/Soniox）。我们 ASR 在本地，**音频不出设备**，只有文字外发。这是架构上的隐私优势，值得写进产品说明。
