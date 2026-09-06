package com.docrag.ask;

/** 本次问答实际生效的三个检索参数（请求覆盖 + 钳制后），回显给前端核对 */
public record AskParams(int bm25Chunks, int vectorChunks, int contextChunks) {
}
