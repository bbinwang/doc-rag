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
        assertTrue(Chunker.chunkKeepingTables("", 128).isEmpty());
    }

    @Test
    void chunkKeepingTablesKeepsTableBlockWholeWithTitle() {
        String content = "表格前的正文说明。\n\n表格 1\n| 部门 | 预算金额 |\n| --- | --- |\n| 销售部 | 100万 |\n\n表格后的正文说明。";
        List<String> chunks = Chunker.chunkKeepingTables(content, 32);
        // 纯文本块整段保留（未超 maxChars）
        assertTrue(chunks.contains("表格前的正文说明。"), chunks.toString());
        assertTrue(chunks.contains("表格后的正文说明。"), chunks.toString());
        // 表格块 = 标题行 + markdown 表格整体，内部无空行、不被拆行
        String tableChunk = chunks.stream().filter(c -> c.startsWith("表格 1")).findFirst().orElse("");
        assertTrue(!tableChunk.isEmpty(), "表格块应存在: " + chunks);
        assertTrue(tableChunk.contains("| --- | --- |"), tableChunk);
        assertTrue(tableChunk.contains("| 销售部 | 100万 |"), tableChunk);
        assertTrue(!tableChunk.contains("\n\n"), "表格块内部不应有空行: " + tableChunk);
    }

    @Test
    void chunkKeepingTablesKeepsOversizeTableWhole() {
        // 表格块超过 maxChars 也整体保留（调用方按字符预算取舍）
        StringBuilder table = new StringBuilder("表格 1\n| a | b |\n| --- | --- |");
        for (int i = 0; i < 30; i++) {
            table.append("\n| 项目").append(i).append(" | 值 |");
        }
        List<String> chunks = Chunker.chunkKeepingTables(table.toString(), 64);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).startsWith("表格 1"));
        assertTrue(chunks.get(0).endsWith("| 值 |"));
    }

    @Test
    void chunkKeepingTablesRechunksLongTextBlocks() {
        // 纯文本块超过 maxChars 仍按句细切
        String para = "这是一句用来测试按句累积切块逻辑长度的中文句子。".repeat(7);
        String content = para + "\n\n表格 1\n| a | b |\n| --- | --- |\n| 1 | 2 |";
        List<String> chunks = Chunker.chunkKeepingTables(content, 128);
        assertTrue(chunks.size() >= 2, chunks.toString());
        for (String c : chunks) {
            if (!c.startsWith("表格 1")) {
                assertTrue(c.length() <= 128, "文本块超长: " + c.length());
            }
        }
    }
}
