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

## Documentation

Detailed documentation is in Chinese:

- **[README.zh.md](README.zh.md)** — full Chinese README
- [PROJECT.md](PROJECT.md) — architecture, protocols, data model
- [docs/ADR.md](docs/ADR.md) — architecture decision records
- [NOTICE.md](NOTICE.md) — upstream attribution

## License

MIT — same as upstream. See [NOTICE.md](NOTICE.md).
