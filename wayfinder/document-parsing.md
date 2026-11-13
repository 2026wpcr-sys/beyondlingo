---
type: research
status: closed
---

## Question

文档翻译需要支持哪些格式，各格式在 Android 上的解析方案是什么？（判据：docx/pdf/txt/md 解析库、分页与限流、导出方式）

## Answer

调研时点 2026-11-12。版本号/字节数取自 Maven Central 目录清单与 Android 官方文档（观测）；未实测的体积与可用率标注为推断。

### 决策 1：MVP 只做 txt / md / docx

| 格式 | 方案 | 体积增量 |
|---|---|---|
| txt | `java.nio` + 自写编码探测 | ≈ 0 |
| md | 按行正则识别块类型 | ≈ 0 |
| docx | **`java.util.zip` + `android.util.Xml` 自研解析 `word/document.xml`** | **≈ 0** |

**不用 Apache POI**：`poi` + `poi-ooxml` + `xmlbeans` 原始体积 12 MiB+，且依赖 `java.awt`（`NoClassDefFoundError: Ljava/awt/Color;` 经典崩法）与 `javax.xml.stream`；6.0.0 起还要求 Java 17。`poi-android`（SUPERCILEX 转译版）仓库 2022-11-20 已归档。`docx4j` 强依赖 JAXB/StAX/awt，面向服务端 JVM。

### 决策 2：pdf 推迟到 MVP+1

- **首选系统原生**：`PdfRenderer.Page.getTextContents()`（API 35 原生 / API 31+ 需 Sdk Extension ≥ 13），**零体积**。运行时门禁 `SDK_INT >= 35 || SdkExtensions.getExtensionVersion(VERSION_CODES.S) >= 13`。
- **兜底 `com.tom-roush:pdfbox-android:2.0.27.0`**（aar 3,254,019 B），但**2023-01-02 后停更**，仓库末次提交 2023-12。
- PDF 无结构语义（只有坐标），还原排版本质是重新排版；扫描件需 OCR，是另一个量级工程。
- **iText 是 AGPLv3，闭源商用不可用**。

### 决策 3：砍掉 xlsx / pptx，html/epub 二期

表格类翻译价值低且破坏列宽；pptx 文本散落形状/母版。html 与 md 能力重叠，epub 受众窄（技术上都 = ZIP + 标记，等 html 做完顺手有）。

### 决策 4：导出只做 .txt / .md

走 SAF `ActivityResultContracts.CreateDocument`，targetSdk 36 零权限。**不做 docx→docx 保格式导出**（要回写 `document.xml` 并保住 `styles.xml`/`numbering.xml`/`media/`/`*.rels`，任一处损坏用户就打不开——二期再碰）。

### 决策 5：分段以「块（block）」为翻译单元

- 段落级翻译质量优于句子级；块 ↔ 译文一一映射，回填零歧义
- 句子级（`android.icu.text.BreakIterator`）只用于**块超长时的二级切分**
- **长度必须用 `codePointCount` 而非 `String.length`**（emoji 在 UTF-16 占 2）
- 限额抽成 `TranslateApiProfile` 配置类，不硬编码
- **反模式**：绝不让模型输出带 markdown 的整篇译文再解析回来（块数对不上就整篇崩）。必须块 id 对应 + 数量校验 + 不一致即退化为逐块重试

### 关键实现细节（docx）

必须先落盘（`ZipFile` 需随机访问，SAF 的 `content://` 流不可 seek）→ 复制到 `cacheDir` 再 `ZipFile(file)`。流式 `XmlPullParser`，不建 DOM。

| 目标 | OOXML 位置 |
|---|---|
| 段落 | `w:p`，标题靠 `w:pPr/w:outlineLvl`（**优先于 `w:pStyle` 名称**，中文模板 styleId 常是 `a3`/`1` 这类） |
| 文本 | `w:t`（保留 `xml:space="preserve"` 前后空格） |
| 表格 | `w:tbl` → `w:tr` → `w:tc` |
| **必须丢弃** | `w:delText`（修订删除内容）、`w:instrText`（域代码） |
| 需递归进入 | `w:sdt/w:sdtContent` |

硬限制：不支持 `.doc`（OLE 二进制），检测到就提示另存为 .docx。

### 风险

1. `pdfbox-android` 停更（既成事实）
2. POI 在 Android 崩溃 → 已通过不用 POI 规避
3. docx 自研解析对嵌套表格/文本框/SmartArt/脚注/修订覆盖不全 → 明确降级为「仅正文+表格+标题+列表」，并统计未识别元素数，超阈值提示结果可能不完整
4. SAF 可能返回不可 seek 的 FD（云盘 provider）→ 一律先复制到 cacheDir
5. 超大文件 OOM → 硬限制 文件 ≤ 20 MiB、PDF ≤ 300 页、docx 解压后 ≤ 50 MiB

### 待实测（不确定项）

- `PdfRenderer.getTextContents()` 在 API 31–34 的真实设备覆盖率（需埋点统计 Sdk Extension 分布）
- R8 后的精确 APK 增量（3.10 MiB / 12 MiB 都是原始字节数）
- `PdfDocument` 导出中文 PDF 的字形嵌入
- 中文 docx 里 `w:outlineLvl` 识别标题的实际命中率
- 各翻译 API 的上下文能力差异（决定是否传上文）

### 关键参考

- `PdfRenderer.Page#getTextContents()`：https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page
- `android.icu.text`（**不含** CharsetDetector，编码探测必须自带）：https://developer.android.com/reference/android/icu/text/package-summary
- pdfbox-android 版本目录（末版 2.0.27.0）：https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/
- poi-android 已归档：https://github.com/SUPERCILEX/poi-android
- iText AGPLv3：https://itextpdf.com/how-buy/AGPLv3-license
