package com.docrag.vector;

/** 向量检索命中；chunk 为命中块的原文，similarity 为余弦相似度 */
public record VectorHit(String docId, String filename, String type, String chunk, double similarity) {
}
