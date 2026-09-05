package com.docrag.vector;

import java.util.Map;

/**
 * vector-service /health 快照：可用性与统计。
 * vectors 为 per-mode 计数（plain/deep 各自 collection 的向量数）。
 * 不可达时 available=false，vectors/model 为 null（前端据此显示离线态）。
 */
public record VectorStats(boolean available, Map<String, Integer> vectors, String model) {

    static VectorStats unavailable() {
        return new VectorStats(false, null, null);
    }
}
