package com.docrag.ask;

/**
 * 送入该次 LLM 的一条上下文 chunk：ref 与答案中的 [n] 标注及 prompt 资料编号一一对应；
 * source/score 为混合召回来源（both/bm25/vector）与 chunk 级 RRF 融合分；
 * title 仅 deep 模式表格块填「表格 N」；text 为完整块文本（前端调试展示用）。
 */
public record AskChunk(int ref, String docId, String filename, String type,
                       String title, String source, double score, String text) {
}
