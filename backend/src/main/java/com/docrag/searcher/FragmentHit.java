package com.docrag.searcher;

/** 表格 markdown 索引命中的单个片段（kind=table 为 markdown 表格，text 为正文片段） */
public record FragmentHit(String docId, String filename, String type, String kind,
                          String title, String content, float score) {
}
