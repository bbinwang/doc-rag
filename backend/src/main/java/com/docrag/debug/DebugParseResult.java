package com.docrag.debug;

import java.util.List;

/**
 * debug 解析结果：用于对比「结构化解析（表格行列）」与「实际入索引的拍平文本」。
 * 图片只计数不入索引（无 OCR，见 CLAUDE.md 边界）。
 */
public record DebugParseResult(
        String filename,
        String type,
        int imageCount,
        int tableCount,
        boolean tablesTruncated,
        boolean textTruncated,
        String indexedText,
        List<TableInfo> tables) {
}
