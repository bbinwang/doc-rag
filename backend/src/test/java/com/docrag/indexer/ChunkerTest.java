package com.docrag.indexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 语义边界切块（≤128 字符）测试 */
class ChunkerTest {

    @Test
    void shortParagraphsStayWhole() {
        List<String> chunks = Chunker.chunk("第一段。\n第二段：说明文字。\n第三段");
        assertEquals(List.of("第一段。", "第二段：说明文字。", "第三段"), chunks);
    }

    @Test
    void sentencesAccumulateUpToLimit() {
        // 24 字/句 × 7 句 = 168 字 > 128，应按句累积切成多块且每块 ≤128
        String sentence = "这是一句用来测试按句累积切块逻辑长度的中文句子。";
        assertEquals(24, sentence.length());
        String para = sentence.repeat(7);
        List<String> chunks = Chunker.chunk(para);
        assertTrue(chunks.size() >= 2);
        for (String c : chunks) {
            assertTrue(c.length() <= Chunker.MAX_CHARS, "块超长: " + c.length());
        }
        // 切分必须发生在句边界：每块以句号结尾（最后一块也应是完整句子）
        for (String c : chunks) {
            assertTrue(c.endsWith("。"), "块未在句边界断开: " + c);
        }
        // 无内容丢失
        assertEquals(para, String.join("", chunks));
    }

    @Test
    void unbreakableLongTextHardCut() {
        String text = "无标点".repeat(100); // 300 字符无换行无句号
        List<String> chunks = Chunker.chunk(text);
        assertEquals(3, chunks.size());
        assertEquals(Chunker.MAX_CHARS, chunks.get(0).length());
        assertEquals(text, String.join("", chunks));
    }

    @Test
    void blankContentYieldsNoChunks() {
        assertTrue(Chunker.chunk("").isEmpty());
        assertTrue(Chunker.chunk("\n\n \n").isEmpty());
    }
}
