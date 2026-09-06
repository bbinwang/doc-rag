package com.docrag.searcher;

/** 按查询召出的整篇文档（含全文），供问答全库召回后切块选上下文 */
public record RankedDoc(String docId, String filename, String type, String path,
                        String content, float score) {
}
