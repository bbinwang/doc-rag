package com.docrag.ask;

/**
 * 引用条目：ref 与答案中的 [n] 标注及送 LLM 的上下文编号一一对应；
 * mode 为该上下文的来源解析模式（plain/deep），title 仅 deep 模式填表格标题。
 */
public record AskCitation(int ref, String docId, String filename, String type,
                          String mode, String title, String excerpt) {
}
