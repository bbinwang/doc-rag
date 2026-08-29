package com.docrag.searcher;

/** 按查询召出的整篇文档（含全文），供问答在 docId 范围内切块选上下文 */
public record RankedDoc(String docId, String filename, String type, String content, float score) {
}
