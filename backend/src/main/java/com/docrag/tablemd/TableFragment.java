package com.docrag.tablemd;

/** 表格 markdown 索引的单个片段：kind=table 为 markdown 表格，kind=text 为正文片段 */
public record TableFragment(String kind, String title, String content) {

    public static final String KIND_TABLE = "table";
    public static final String KIND_TEXT = "text";

    public static TableFragment table(String title, String content) {
        return new TableFragment(KIND_TABLE, title, content);
    }

    public static TableFragment text(String title, String content) {
        return new TableFragment(KIND_TEXT, title, content);
    }
}
