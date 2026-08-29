package com.docrag.ask;

/** 引用条目：ref 与答案中的 [n] 标注及送 LLM 的上下文编号一一对应 */
public record AskCitation(int ref, String docId, String filename, String type,
                          String title, String excerpt) {
}
