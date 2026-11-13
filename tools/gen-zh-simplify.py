import urllib.request, io

def fetch(u):
    with urllib.request.urlopen(u, timeout=60) as r:
        return r.read().decode("utf-8")

def parse(t):
    d = {}
    for line in t.splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        k = parts[0].strip()
        vals = [p.strip() for p in parts[1].split() if p.strip()]
        if k and vals:
            d[k] = vals
    return d

C = parse(fetch("https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/TSCharacters.txt"))
P = parse(fetch("https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/TSPhrases.txt"))

def bmp(s):
    return len(s) == 1 and ord(s) < 0x10000

chars = {}
for trad, vals in C.items():
    if not bmp(trad):
        continue
    simp = vals[0]
    if not bmp(simp) or trad == simp:
        continue
    chars[trad] = simp

ph = {}
for trad, vals in P.items():
    simp = vals[0]
    if len(trad) != 1 and len(simp) != len(trad):
        continue
    if any(ord(c) >= 0x10000 for c in trad + simp):
        continue
    ph[trad] = simp

def chunks(lst, n):
    for i in range(0, len(lst), n):
        yield lst[i:i+n]

out = io.StringIO()
out.write("package dev.beyonddev.beyondlingo.data.asr\n\n")
out.write("/**\n")
out.write(" * 繁体->简体 对照数据。\n")
out.write(" *\n")
out.write(" * 由 OpenCC 官方 TSCharacters.txt + TSPhrases.txt 生成，只取繁->简方向。\n")
out.write(" * 说明见 ZhSimplify.kt；生成脚本 tools/gen-zh-simplify.py。\n")
out.write(" *\n")
out.write(" * 只收 BMP 内的一对一映射（扩展区汉字在 UTF-16 占 2 个 char，Kotlin 无法用 Char 表示）。\n")
out.write(" *\n")
out.write(" * 分块构造：单个 mapOf 放 2965 项会让 <clinit> 超过 JVM 64KB 方法上限\n")
out.write(" * （编译期报 \"Method too large\"），因此拆成多个小表再合并。\n")
out.write(" */\n")
out.write("internal object ZhSimplifyTable {\n\n")

# ---- 字级：分 6 块 ----
items = sorted(chars.items())
blocks = list(chunks(items, 500))
out.write("    private val CHAR_BLOCKS: List<Map<Char, Char>> = listOf(\n")
for bi, blk in enumerate(blocks):
    out.write("        mapOf(\n")
    for i in range(0, len(blk), 8):
        out.write("            " + " ".join("'%s' to '%s'," % (k, v) for k, v in blk[i:i+8]) + "\n")
    out.write("        )" + ("," if bi < len(blocks)-1 else "") + "\n")
out.write("    )\n\n")
out.write("    val CHARS: Map<Char, Char> = CHAR_BLOCKS.fold(emptyMap()) { acc, m -> acc + m }\n\n")

# ---- 词级：分 2 块 ----
items = sorted(ph.items())
blocks = list(chunks(items, 300))
out.write("    private val PHRASE_BLOCKS: List<Map<String, String>> = listOf(\n")
for bi, blk in enumerate(blocks):
    out.write("        mapOf(\n")
    for i in range(0, len(blk), 4):
        out.write("            " + " ".join('"%s" to "%s",' % (k, v) for k, v in blk[i:i+4]) + "\n")
    out.write("        )" + ("," if bi < len(blocks)-1 else "") + "\n")
out.write("    )\n\n")
out.write("    val PHRASES: Map<String, String> = PHRASE_BLOCKS.fold(emptyMap()) { acc, m -> acc + m }\n")
out.write("}\n")

path = r"E:\Users\sunshine\Documents\deepseek-harness\default-workspace\beyondlingo\app\src\main\java\dev\beyonddev\beyondlingo\data\asr\ZhSimplifyTable.kt"
open(path, "w", encoding="utf-8", newline="\n").write(out.getvalue())
print("WROTE %d bytes  char_blocks=%d phrase_blocks=%d" % (len(out.getvalue()), len(list(chunks(sorted(chars.items()), 500))), len(list(chunks(sorted(ph.items()), 300)))))
