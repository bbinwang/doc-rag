package com.docrag.ask;

import java.util.List;

/**
 * 单模式问答结果：answer 与 error 互斥（正常作答 / LLM 调用失败），
 * degraded=该模式向量服务不可用已降级纯 BM25；无命中时 answer 为占位提示、chunks/docs 为空。
 */
public record AskModeResult(String answer, String error, boolean degraded,
                            List<AskChunk> chunks, List<AskDocRef> docs) {
}
