package com.docrag.searcher;

/** 索引库中一个文档的完整视图：content 即写入索引的原始纯文本 */
public record DocumentDetail(String docId, String filename, String path, String type,
                             long modified, String content) {
}
