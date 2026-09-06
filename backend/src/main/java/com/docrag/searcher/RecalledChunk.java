package com.docrag.searcher;

/** 问答混合召回的单个 chunk：source=两路命中来源，score=chunk 级 RRF 融合分 */
public record RecalledChunk(String docId, String filename, String type, String path,
                            String title, String text, String source, float score) {

    public static final String SOURCE_BOTH = "both";
    public static final String SOURCE_BM25 = "bm25";
    public static final String SOURCE_VECTOR = "vector";
}
