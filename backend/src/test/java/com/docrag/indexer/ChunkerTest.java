package com.docrag.indexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 语义边界切块测试：片段 ≤maxChars，chunk()/chunkKeepingTables() 在片段之上
 * 贪心累积到 MIN_CHUNK_CHARS 才成块（尾块/表格前冲刷的余量块可不足下限）。
 */
class ChunkerTest {

    @Test
    void shortParagraphsMergeIntoSingleMinChunk() {
        // 总长 < 下限：全部段落合并为单个尾块（片段间以换行拼接）
        List<String> chunks = Chunker.chunk("第一段。\n第二段：说明文字。\n第三段");
        assertEquals(List.of("第一段。\n第二段：说明文字。\n第三段"), chunks);
    }

    @Test
    void piecesRespectMaxCharsWithoutMerging() {
        // 双参 chunk 只产片段：24 字/句 × 7 句 = 168 字 > 128，按句累积成多片段且每片 ≤128
        String sentence = "这是一句用来测试按句累积切块逻辑长度的中文句子。";
        assertEquals(24, sentence.length());
        List<String> pieces = Chunker.chunk(sentence.repeat(7), Chunker.MAX_CHARS);
        assertTrue(pieces.size() >= 2);
        for (String p : pieces) {
            assertTrue(p.length() <= Chunker.MAX_CHARS, "片段超长: " + p.length());
            assertTrue(p.endsWith("。"), "片段未在句边界断开: " + p);
        }
    }

    @Test
    void sentencesAccumulateToMinChunkSize() {
        // 24 字/句 × 30 句 = 720 字：首块 ≥512，尾块（192 字）不足下限允许保留
        String sentence = "这是一句用来测试按句累积切块逻辑长度的中文句子。";
        String para = sentence.repeat(30);
        List<String> chunks = Chunker.chunk(para);
        assertTrue(chunks.size() >= 2, chunks.toString());
        for (String c : chunks.subList(0, chunks.size() - 1)) {
            assertTrue(c.length() >= Chunker.MIN_CHUNK_CHARS, "非尾块低于下限: " + c.length());
            assertTrue(c.length() <= Chunker.MIN_CHUNK_CHARS + Chunker.MAX_CHARS,
                    "块超出下限+片段上限余量: " + c.length());
            assertTrue(c.endsWith("。"), "块未在句边界断开: " + c);
        }
        // 无内容丢失（片段拼接引入的换行不计）
        assertEquals(para, String.join("", chunks).replace("\n", ""));
    }

    @Test
    void underMinContentYieldsSingleTailChunk() {
        String para = "这是一句用来测试按句累积切块逻辑长度的中文句子。".repeat(7);
        List<String> chunks = Chunker.chunk(para);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).length() < Chunker.MIN_CHUNK_CHARS);
    }

    @Test
    void unbreakableLongTextHardCutThenMerged() {
        // 300 字符无标点：硬切成 ≤128 片段后合并为单个尾块
        String shortText = "无标点".repeat(100);
        assertEquals(1, Chunker.chunk(shortText).size());
        // 600 字符无标点：硬切后合并出 ≥512 的块 + 尾块
        String longText = "无标点".repeat(200);
        List<String> chunks = Chunker.chunk(longText);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).length() >= Chunker.MIN_CHUNK_CHARS);
        assertEquals(longText, String.join("", chunks).replace("\n", ""));
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
        // 文本块（不足下限）作为表格边界冲刷出的余量块保留
        assertTrue(chunks.contains("表格前的正文说明。"), chunks.toString());
        assertTrue(chunks.contains("表格后的正文说明。"), chunks.toString());
        // 表格块 = 标题行 + markdown 表格整体，内部无空行、不被拆行、不与文本合并
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
        // 纯文本块超过 maxChars 仍按句细切，不足下限时在表格边界冲刷为单个余量块
        String para = "这是一句用来测试按句累积切块逻辑长度的中文句子。".repeat(7);
        String content = para + "\n\n表格 1\n| a | b |\n| --- | --- |\n| 1 | 2 |";
        List<String> chunks = Chunker.chunkKeepingTables(content, 128);
        assertEquals(2, chunks.size(), chunks.toString());
        assertTrue(chunks.get(0).endsWith("。"));
        assertTrue(chunks.get(1).startsWith("表格 1"));
    }

    @Test
    void chunkKeepingTablesMergesAcrossBlocksUpToMin() {
        // 两个 312 字文本块：跨块累积出 ≥512 的块，余量与表格的边界冲刷语义不变
        String block = "这是一句用来测试按句累积切块逻辑长度的中文句子。".repeat(13);
        String content = block + "\n\n" + block + "\n\n表格 1\n| a | b |\n| --- | --- |\n| 1 | 2 |";
        List<String> chunks = Chunker.chunkKeepingTables(content, Chunker.DEEP_EMBED_MAX_CHARS);
        assertTrue(chunks.size() == 3, chunks.toString());
        assertTrue(chunks.get(0).length() >= Chunker.MIN_CHUNK_CHARS, "跨块合并未达下限: " + chunks.get(0).length());
        assertTrue(chunks.get(0).endsWith("。"));
        assertTrue(chunks.get(1).length() < Chunker.MIN_CHUNK_CHARS, "余量块应不足下限");
        assertTrue(chunks.get(2).startsWith("表格 1"));
        assertEquals(block + block, (chunks.get(0) + "\n" + chunks.get(1)).replace("\n", ""));
    }
}
