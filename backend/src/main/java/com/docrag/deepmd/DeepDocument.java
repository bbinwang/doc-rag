package com.docrag.deepmd;

/**
 * 表格索引的一个文档：统一文本 + 其中 markdown 表格数。
 * tableCount 定义：docx = body 中表格数；xlsx = 非空 sheet 数；pdf = 0。
 * 仅用于上传响应展示，不入索引 schema。
 */
public record DeepDocument(String content, int tableCount) {
}
