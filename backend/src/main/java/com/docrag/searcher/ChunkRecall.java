package com.docrag.searcher;

import java.util.List;

/** 单模式 chunk 级混合召回结果（RRF 融合序）；degraded=true 表示该模式向量服务不可用、已降级纯 BM25 */
public record ChunkRecall(List<RecalledChunk> chunks, boolean degraded) {
}
