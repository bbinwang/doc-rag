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

    private static final String SENTENCE_END = "。！？；!?;";

    private Chunker() {
    }

    public static List<String> chunk(String content) {
        List<String> chunks = new ArrayList<>();
        for (String para : content.split("\n")) {
            if (para.isBlank()) {
                continue;
            }
            if (para.length() <= MAX_CHARS) {
                chunks.add(para);
                continue;
            }
            // 长段：按句累积
            StringBuilder buf = new StringBuilder();
            for (String sentence : splitSentences(para)) {
                if (sentence.length() > MAX_CHARS) {
                    // 无标点的超长串：先收掉已累积内容，再硬切
                    flush(buf, chunks);
                    for (int i = 0; i < sentence.length(); i += MAX_CHARS) {
                        chunks.add(sentence.substring(i, Math.min(sentence.length(), i + MAX_CHARS)));
                    }
                    continue;
                }
                if (buf.length() + sentence.length() > MAX_CHARS) {
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
}
