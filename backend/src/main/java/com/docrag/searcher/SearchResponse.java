package com.docrag.searcher;

import java.util.List;

/** 检索响应；degraded=true 表示向量服务不可用、已降级为纯 BM25 */
public record SearchResponse(long total, List<SearchHit> hits, boolean degraded) {
}
