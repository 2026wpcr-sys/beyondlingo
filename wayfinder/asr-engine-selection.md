---
type: research
status: closed
---

## Question

Android 上做中英日韩多语种实时语音识别，sherpa-onnx、whisper.cpp、Vosk 与云端 API 之间应选哪个？（判据：离线可用、包体积、延迟、中文准确率、Kotlin/JNI 接入成本）

## Answer

### 决策：sherpa-onnx v2.0.1（Apache-2.0）+ 两遍式识别

流式 Zipformer 出草稿字幕 → 端点后 SenseVoice 重打分替换。

### 三个与常见博客说法冲突的事实（已核实）

1. **sherpa-onnx 有官方预编译 AAR，但不在 Maven Central**（`repo1.maven.org/maven2/com/k2fsa/` → 404）。AAR 是 GitHub Release 附件 `sherpa-onnx-2.0.1.aar`（49,441,341 B）。中文博客流传的 `com.k2fsa.sherpa.onnx:sherpa-onnx:1.10.0` 坐标**不存在**。接入写法：
   ```kotlin
   implementation(files("libs/sherpa-onnx-2.0.1.aar"))
   ```
2. **没有单个流式模型覆盖中英日韩**。中英双语 198MB；中英日(+阿印俄泰越) 339MB；韩语必须单独 126MB。想一模型打四语只有**非流式** SenseVoice（int8 239.2MB，zh/en/ja/ko/yue），须 VAD 切段做伪流式。
3. **真正瓶颈是离线翻译，不是 ASR**（见票据 `translation-layer`）。

### 模型体积（HF API `?blobs=true` 实测字节数）

| 模型 | 体积 | 用途 |
|---|---|---|
| streaming zh-int8-2025-06-30 | 160MB | 纯中文首选 |
| streaming bilingual-zh-en-2023-02-20 | 198MB | 中英混说首选 |
| streaming ar_en_id_ja_ru_th_vi_zh-2025-02-10 | 339MB | 含日语 |
| streaming korean-2024-06-16 | 126MB | 韩语单独加载 |
| SenseVoice int8 | 239.2MB | 端点重打分 / 伪流式 |

**全功能 ≈ 560MB，绝不能塞 APK** → 必须「首装内置中英，其余按需下载」。

### 淘汰理由（有硬证据）

- **whisper.cpp**：Android 流式实测 6.7s 处理 1.1s 音频、延迟累积到 ANR（discussion #3567）。根因是 `whisper_full` 每次重跑整段 PCM。
- **Vosk**：`vosk-model-small-cn-0.22` WER 23.54（SpeechIO-02）/ 38.29（SpeechIO-06），中文直接出局。
- **Moonshine**：中文只有 Tiny Streaming（CER 16.1%）；韩语模型是 Community 非商用许可。

### 延迟预算（能否压到 1.5s 的关键）

chunk 320ms + 流式编码 50–150ms + 翻译 100–400ms ≈ **0.5–0.9s**，走 partial 路径达标。

**但** sherpa-onnx 默认端点规则 rule2 是「尾部静音 ≥1.2s」才出 final —— 等 final 再翻译，1.2s 静音单独就超预算。

→ 必须：字幕走 **partial**（允许抖动）+ 实现「提交稳定前缀」防抖器；final 到达后静默替换为高准确率版本。或把 `min_trailing_silence` 降到 0.4–0.6s。

### 集成要点（照搬官方 demo）

采样率 16000 / `CHANNEL_IN_MONO` / `ENCODING_PCM_16BIT` / `AudioSource.MIC`；buffer = `getMinBufferSize()*2`；录音线程只搬运 `AudioRecord.read(ShortArray(512))` 不做推理；推理放 `Dispatchers.IO`；VAD 用内置 silero（`Vad.acceptWaveform/empty/front/pop/reset`）；UI 只在 Main 线程更新。

### 风险

1. **SenseVoice/FunASR 权重走自定义 MODEL_LICENSE，不是 Apache/MIT** —— 闭源商用前必须过法务。这是当前方案最大法务风险。
2. **官方 RTF 全是 RPi4 或 macOS 笔记本数据，没有任何 Android 真机 RTF/内存实测** —— 手机 RTF 是推算。
3. AAR 49.4MB 会直接进 APK，需评估是否改为按需下载模型 + 保留 native 库。

### 待实测

- 1–2 台中端真机跑流式 benchmark 验 RTF
- `streaming-zipformer-small-bilingual-zh-en-2023-02-16` 体积（HF 401 拿不到）
- 第三方 AAR `com.bihe832.android:lib-sherpa-onnx:8.6.10` 质量未审查

## 真机实测（2026-11-12，已关闭「待实测」第 1 项）

设备：realme RMX3823，Android 16（SDK 36），arm64-v8a
模型：`sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23` **int8**（encoder 20.62 + decoder 1.80 + joiner 1.71 = **24.35 MB**）
测法：assets 内模型，`OnlineRecognizer(assets, config)`，16kHz / 100ms chunk / numThreads=2 / enableEndpoint=true

```
model load : 1008.7 ms
audio len  : 5611.5 ms
decode time:  102.5 ms
RTF        :  0.018     <-- 关键指标
first char :   15.5 ms
decode call:  17
heap used  :     6 MB
识别文本   : 对我做了介绍那么我想说的是大家如果对我的研究感兴趣呢   (正确)
```

### 结论

1. **算力不是瓶颈**：RTF 0.018 = 只用了 1.8% 实时预算，余量约 55 倍。
2. **首字延迟 15.5ms**，字幕跟得上，不需要为延迟做妥协设计。
3. **内存 6MB**，可忽略。
4. **冷启动 1 秒**，需在 UI 上做「模型加载中」的过渡。
5. 换中英双语模型（参数量约 5–10 倍）线性外推 RTF ≈ 0.1–0.2，仍有 5–10 倍余量 → **可以放心上更大模型换准确率**。

→ 架构风险从「能不能跑」降级为「模型怎么分发」（见 Not yet specified 的「模型分发」）。

### 验证工具

`asrprobe` 模块（独立包名 `dev.beyonddev.asrprobe`，应用名 ASR Probe，51.3MB），
与主应用零共享业务代码，仅通过 `srcDirs` 复用 app 的 assets 模型。
源码：`asrprobe/src/main/java/dev/beyonddev/asrprobe/MainActivity.kt`

### 仍未测

- 真实 `AudioRecord` 实时链路的 RTF（本次是文件回放，无录音开销）
- 中英双语大模型的真实 RTF
- 长时间（30 分钟）连续运行的发热与降频

## 模型外部加载验证（2026-11-12，模拟器 x86_64 / SDK 34）

目的：确认「模型放云空间、首启下载到 filesDir」是否可行。

| 实验 | 做法 | 结果 |
|---|---|---|
| A 对照 | `OnlineRecognizer(assets, cfg)` + assets 相对路径 | ✅ SUCCESS，load 3552ms |
| B | `OnlineRecognizer(assets, cfg)` + **绝对路径** | ❌ **native 崩溃**，进程直接死亡，无 Java 异常、crash buffer 为空 |
| C | **反射调 private `newFromFile(cfg)`** + 绝对路径 | ✅ **SUCCESS**，load 1599ms，文本一致，RTF 0.115 |

### 根因（observed）

`libsherpa-onnx-jni.so` 导出了两个入口：

```
Java_com_k2fsa_sherpa_onnx_OnlineRecognizer_newFromAsset   ← Kotlin 包装唯一暴露的
Java_com_k2fsa_sherpa_onnx_OnlineRecognizer_newFromFile    ← 支持文件路径，但 Kotlin 声明为 private
```

`javap -p` 确认：

```java
private final native long newFromFile(com.k2fsa.sherpa.onnx.OnlineRecognizerConfig);
private long ptr;
private final OnlineRecognizerConfig config;
```

即 **native 层完全支持绝对路径，只是 Kotlin 包装层没开放**。传绝对路径给 `newFromAsset` 会让 native 按 asset 打开而崩溃。

### 解法（已验证）

```kotlin
// 1. Unsafe.allocateInstance 跳过构造器（构造器必然走 newFromAsset）
val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    .apply { isAccessible = true }.get(null)
val inst = Class.forName("sun.misc.Unsafe")
    .getMethod("allocateInstance", Class::class.java)
    .invoke(unsafe, OnlineRecognizer::class.java) as OnlineRecognizer

// 2. 反射调 private native newFromFile
val m = OnlineRecognizer::class.java
    .getDeclaredMethod("newFromFile", OnlineRecognizerConfig::class.java)
    .apply { isAccessible = true }
val ptr = m.invoke(inst, config) as Long

// 3. 把 ptr / config 填回字段，之后公开方法即可正常使用
OnlineRecognizer::class.java.getDeclaredField("ptr")
    .apply { isAccessible = true }.setLong(inst, ptr)
OnlineRecognizer::class.java.getDeclaredField("config")
    .apply { isAccessible = true }.set(inst, config)
```

→ **「模型放云空间」方案成立**。

### 风险

1. 反射 + Unsafe 依赖内部字段名（`ptr` / `config`）与私有方法名，**升级 AAR 版本可能失效**，必须锁版本 + 加降级路径
2. Android 对隐藏 API 的限制逐年收紧，`sun.misc.Unsafe` 长期可用性存疑
3. **长期正解**：fork sherpa-onnx 的 Kotlin 包装、把 `newFromFile` 改为 public 后自编译 AAR；或向上游提 PR 暴露该 API
4. 上述验证在 x86_64 模拟器完成，真机 arm64 需复测



### 关键参考

- sherpa-onnx releases（AAR 在此）：https://github.com/k2-fsa/sherpa-onnx/releases
- whisper.cpp Android 流式问题：discussion #3567
- SpeechIO 中文 WER 榜（Vosk 数据来源）
