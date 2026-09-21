package com.docrag.indexer;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义边界切块：块下限 512 字符（MIN_CHUNK_CHARS），相邻句/段贪心累积到达下限才产出块，尾块可不足。
 * 片段优先级：换行切段 → 段内句末标点（。！？；!?;）→ 超长无标点按 maxChars 硬切兜底。
 * 单参 chunk() 与 chunkKeepingTables() 在片段之上做下限合并；
 * 双参 chunk(content, maxChars) 只产片段（≤maxChars，不合并），供 chunkKeepingTables 与测试复用。
 * deep 统一文本走 chunkKeepingTables：markdown 表格块（标题行+表格）原子保留、不与文本合并。
 */
public final class Chunker {

    /** 无标点长串的硬切片段上限（合并前粒度，防超长串撑爆 embedding） */
    public static final int MAX_CHARS = 128;

    /** 成块最低字符数：相邻片段贪心累积到达该值才产出块（尾块可不足；表格块原子不受限） */
    public static final int MIN_CHUNK_CHARS = 512;

    /** deep 模式向量 embedding 切块上限（bge-small 上限 512 token，非表格块 256 字符安全） */
    public static final int DEEP_EMBED_MAX_CHARS = 256;

    private static final String SENTENCE_END = "。！？；!?;";

    private Chunker() {
    }

    public static List<String> chunk(String content) {
        return mergeToMin(chunk(content, MAX_CHARS), MIN_CHUNK_CHARS);
    }

    /** 指定片段粒度上限的切块：只产 ≤maxChars 的片段、不做下限合并（embedding 片段粒度与测试用） */
    public static List<String> chunk(String content, int maxChars) {
        List<String> chunks = new ArrayList<>();
        for (String para : content.split("\n")) {
            if (para.isBlank()) {
                continue;
            }
            if (para.length() <= maxChars) {
                chunks.add(para);
                continue;
            }
            // 长段：按句累积
            StringBuilder buf = new StringBuilder();
            for (String sentence : splitSentences(para)) {
                if (sentence.length() > maxChars) {
                    // 无标点的超长串：先收掉已累积内容，再硬切
                    flush(buf, chunks);
                    for (int i = 0; i < sentence.length(); i += maxChars) {
                        chunks.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
                    }
                    continue;
                }
                if (buf.length() + sentence.length() > maxChars) {
                    flush(buf, chunks);
                }
                buf.append(sentence);
            }
            flush(buf, chunks);
        }
        return chunks;
    }

    /** 句末标点随句保留 */
    private static List<String> splitSentences(String para) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < para.length(); i++) {
            char c = para.charAt(i);
            sb.append(c);
            if (SENTENCE_END.indexOf(c) >= 0) {
                out.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (!sb.isEmpty()) {
            out.add(sb.toString());
        }
        return out;
    }

    private static void flush(StringBuilder buf, List<String> chunks) {
        if (!buf.isEmpty()) {
            chunks.add(buf.toString());
            buf.setLength(0);
        }
    }

    /**
     * 下限合并：片段以换行拼接，累积到达 min 才产出块，尾块可不足 min。
     * 已产出的块长度 ∈ [min, min + 最长片段]。
     */
    private static List<String> mergeToMin(List<String> pieces, int min) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String p : pieces) {
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(p);
            if (cur.length() >= min) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * 块感知切块（deep 统一文本的向量切块与问答上下文切块共用）：
     * 空行分块；含 markdown 表格行（以 | 开头）的块整体保留（标题行+表格不被拆行、
     * 不与文本片段合并，保证送 LLM 的表格块自含表头）；纯文本块按 maxChars 细切后
     * 跨块贪心累积到 MIN_CHUNK_CHARS 才成块，遇表格块先冲刷未满下限的文本片段。
     * 依赖 deepmd 的块边界约定（空行分隔、表格标题行紧贴表格）。
     */
    public static List<String> chunkKeepingTables(String content, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        List<String> pending = new ArrayList<>(); // 未达下限的文本片段（与后续文本块继续合并）
        for (String block : content.split("\n\\s*\n")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.lines().anyMatch(l -> l.strip().startsWith("|"))) {
                chunks.addAll(mergeToMin(pending, MIN_CHUNK_CHARS));
                pending.clear();
                chunks.add(trimmed); // 表格块整体保留（可能超 maxChars，调用方按预算取舍）
            } else {
                pending.addAll(chunk(trimmed, maxChars));
                // 到达下限的部分先出块，余量（<min）留在 pending 继续累积
                List<String> merged = mergeToMin(pending, MIN_CHUNK_CHARS);
                if (!merged.isEmpty() && merged.get(merged.size() - 1).length() < MIN_CHUNK_CHARS) {
                    String remainder = merged.remove(merged.size() - 1);
                    pending.clear();
                    pending.add(remainder);
                } else {
                    pending.clear();
                }
                chunks.addAll(merged);
            }
        }
        chunks.addAll(mergeToMin(pending, MIN_CHUNK_CHARS));
        return chunks;
    }
}
