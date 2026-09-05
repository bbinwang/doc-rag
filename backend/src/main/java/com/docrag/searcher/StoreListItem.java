package com.docrag.searcher;

/** 索引明细列表项（GET /api/store/full|table）：不含 content 的轻量文档信息 */
public record StoreListItem(String docId, String filename, String path, String type, long modified) {
}
