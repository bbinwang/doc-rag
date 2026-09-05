package com.docrag.indexer;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义边界切块：块上限 128 字符。
 * 优先级：换行切段 → 段内句末标点（。！？；!?;）累积成块 → 超长无标点硬切兜底。
 * 目的：embedding 上下文短，避免词句被硬切两半影响向量召回。
 */
public final class Chunker {

    public static final int MAX_CHARS = 128;

    /** deep 模式向量 embedding 切块上限（bge-small 上限 512 token，非表格块 256 字符安全） */
    public static final int DEEP_EMBED_MAX_CHARS = 256;

    private static final String SENTENCE_END = "。！？；!?;";

    private Chunker() {
    }

    public static List<String> chunk(String content) {
        return chunk(content, MAX_CHARS);
    }

    /** 指定块上限的切块：embedding 向量用默认 128，表格 markdown 索引的正文片段用更大块 */
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
     * 块感知切块（deep 统一文本的向量切块与问答上下文切块共用）：
     * 空行分块；含 markdown 表格行（以 | 开头）的块整体保留（标题行+表格不被拆行，
     * 保证送 LLM 的表格块自含表头）；纯文本块再按 maxChars 细切。
     * 依赖 deepmd 的块边界约定（空行分隔、表格标题行紧贴表格）。
     */
    public static List<String> chunkKeepingTables(String content, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        for (String block : content.split("\n\\s*\n")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.lines().anyMatch(l -> l.strip().startsWith("|"))) {
                chunks.add(trimmed); // 表格块整体保留（可能超 maxChars，调用方按预算取舍）
            } else {
                chunks.addAll(chunk(trimmed, maxChars));
            }
        }
        return chunks;
    }
}
