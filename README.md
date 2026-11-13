# BeyondLingo AI · 无界译 AI

> **Break Language Barriers. Speak Freely.**
> 跨越语言边界，让沟通自由发生。

An Android translation app built with Kotlin and Jetpack Compose.
Its distinguishing feature is **automatic bidirectional direction detection**:
declare your native language and the other language once, and the app figures
out which way to translate on every input — no manual swapping.

Based on [STranslate](https://github.com/STranslate/STranslate) (MIT) — protocol and
design ideas reused; the Android implementation is independent.

## Features

- **Automatic direction** — type Chinese, get the foreign language; type the foreign
  language, get Chinese. No source/target switching.
- **Two engines** — Yandex (zero-config, default) or any OpenAI-compatible API
  (Zhipu GLM, DeepSeek, SiliconFlow, Moonshot, Groq, OpenAI).
- **33 languages**
- **Text selection translation** — accessibility service + floating window
- **Floating bubble** — copy text, tap the bubble to translate
- **OCR** — ML Kit Latin model, fully offline, images never leave the device
- **Translation history** — local Room database, searchable and bookmarkable

### Since 1.4.0

- **Simultaneous interpretation** — microphone → on-device streaming ASR →
  cloud translation → bilingual subtitles. **Audio never leaves the device**;
  only the recognized text is sent for translation.
- **Floating subtitle overlay** — keep reading translations while using other apps.
- **Optional local TTS** — read translations aloud (Matcha zh-en).
- **Video translation** — pick a local video, get bilingual subtitles and an
  exportable `.srt`. The video is never re-encoded.
- **Document translation** — `txt` / `md` / `docx`, with a self-written docx
  parser (no Apache POI, which breaks on Android) and GB18030 encoding detection.
- **Bring-your-own model hosting** — ASR / punctuation / TTS models can be
  downloaded from your own cloud storage with SHA-256 verification and resume
  support, instead of being bundled into the APK.

## Install

Download the latest APK from [Releases](https://github.com/2026wpcr-sys/beyondlingo/releases).

> **Version 1.3.0 changed the signing key.** If you have 1.1.0 or 1.2.0 installed,
> you must uninstall it first — Android will not allow an in-place upgrade across
> different signatures. Uninstalling clears history and settings.

Requires Android 8.0 (API 26) or newer.

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
```

Release builds need `keystore.properties` and `keys/*.jks` (both gitignored);
without them the build produces an unsigned APK.

### Model and native dependencies are not in this repo

`app/libs/` and `app/src/main/assets/asr/` are gitignored because they total
~170 MB. Before building, fetch them:

| What | Where |
|---|---|
| `app/libs/sherpa-onnx-2.0.1.aar` (47 MB) | [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) → tag `v2.0.1` |
| `app/src/main/assets/asr/` (24 MB) | `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`, int8 variant; rename to `encoder.int8.onnx` / `decoder.int8.onnx` / `joiner.int8.onnx` / `tokens.txt`, plus a `test.wav` from `test_wavs/` |

Optional larger models (bilingual zh-en ASR, punctuation, TTS) are downloaded at
runtime from your own cloud storage — see [docs/models-manifest.example.json](docs/models-manifest.example.json).

## Documentation

Detailed documentation is in Chinese:

- **[README.zh.md](README.zh.md)** — full Chinese README
- [PROJECT.md](PROJECT.md) — architecture, protocols, data model
- [docs/ADR.md](docs/ADR.md) — architecture decision records
- [NOTICE.md](NOTICE.md) — upstream attribution

## License

MIT — same as upstream. See [NOTICE.md](NOTICE.md).
