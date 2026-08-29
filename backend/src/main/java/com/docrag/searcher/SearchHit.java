package com.docrag.searcher;

/** 单条检索结果；snippet 为含 <em> 高亮标记、已做 HTML 转义的片段；source 标明召回来源 */
public record SearchHit(String docId, String filename, String path, String type,
                        String snippet, float score, String source, Integer matchCount) {

    public static final String SOURCE_BOTH = "both";
    public static final String SOURCE_BM25 = "bm25";
    public static final String SOURCE_VECTOR = "vector";
    public static final String SOURCE_TABLE = "table";

    /** 全文格式的便捷构造（无片段计数） */
    public SearchHit(String docId, String filename, String path, String type,
                     String snippet, float score, String source) {
        this(docId, filename, path, type, snippet, score, source, null);
    }
}
