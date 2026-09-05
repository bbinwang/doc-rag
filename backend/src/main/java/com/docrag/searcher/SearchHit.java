package com.docrag.searcher;

/** 单条检索结果；snippet 为含 <em> 高亮标记、已做 HTML 转义的片段；source 标明召回来源 */
public record SearchHit(String docId, String filename, String path, String type,
                        String snippet, float score, String source) {

    public static final String SOURCE_BOTH = "both";
    public static final String SOURCE_BM25 = "bm25";
    public static final String SOURCE_VECTOR = "vector";
}
