package com.docrag.mode;

/**
 * 解析模式：系统核心概念（见 CLAUDE.md §1）。
 * 两模式结构完全对称——各一套 Lucene 倒排 + 各一个向量 collection：
 * - plain（纯文本解析）：Parser 纯文本，chunk = Chunker.chunk
 * - deep（深度解析）：deepmd 统一文本（正文 + markdown 表格），chunk = Chunker.chunkKeepingTables
 */
public enum Mode {

    PLAIN("plain", "纯文本解析"),
    DEEP("deep", "深度解析");

    /** API / 配置 / 目录 / URL 中的标识 */
    private final String id;
    /** 前端与提示文案的中文显示名 */
    private final String label;

    Mode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** 按 id 解析，未知值抛 IllegalArgumentException（API 层转 400） */
    public static Mode of(String id) {
        for (Mode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        throw new IllegalArgumentException("未知的解析模式: " + id + "（可选 plain / deep）");
    }

    @Override
    public String toString() {
        return id;
    }
}
